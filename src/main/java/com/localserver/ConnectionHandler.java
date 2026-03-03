package com.localserver;

import java.util.*;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import com.localserver.utils.Logger;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class ConnectionHandler {

    private static final Logger log = Logger.getLogger(ConnectionHandler.class);
    
    // Taille max de l'accumulateur : protection contre les requetes infinies
    private static final int MAX_REQUEST_SIZE = 8192 * 10; // 80KB

    private enum State {
        READING,     // on accumule les bytes de la requete
        PROCESSING,  // requete complete, on prepare la reponse
        WRITING,     // on envoie la reponse
        DONE         // connexion terminee
    }

    private State state = State.READING;

    // Le channel de ce client specifique
    private final SocketChannel channel;

    // La config du serveur (pour les routes, limites, pages d'erreur)
    private final ConfigLoader.ServerConfig config;

    // Reponse preparee, prete a etre envoyee
    private ByteBuffer responseBuffer;

    // Accumulateur de bytes bruts — on y ajoute chaque lecture NIO
    // On utilise un StringBuilder pour les headers (texte)
    // et une liste de bytes pour le body (binaire)
    private final StringBuilder rawRequest = new StringBuilder();

    // La requete HTTP parsee
    private HttpRequest request;
    
    // Keep-alive : reutiliser la connexion pour plusieurs requetes
    private boolean keepAlive = false;

    // Timestamp de la derniere activite — pour le timeout
    private long lastActivity = System.currentTimeMillis();

    // Timeout : 30 secondes sans activite = on ferme
    private static final long TIMEOUT_MS = 30_000;

    public ConnectionHandler(SocketChannel channel, ConfigLoader.ServerConfig config) {
        this.channel = channel;
        this.config  = config;
    }

    // -------------------------------------------------------------------------
    // Phase 1 : READING — accumulation et detection de requete complete
    // -------------------------------------------------------------------------

    /**
    * Appele par Server.handleRead() a chaque fois que des bytes arrivent.
    * Retourne true quand la requete est complete et prete a etre traitee.
    */

    // Recoit des bytes du Selector, retourne true quand la requete est complete.
    public boolean process(ByteBuffer buffer) throws IOException {
        lastActivity = System.currentTimeMillis();

        // Convertir les bytes en String et les ajouter a l'accumulateur
        // ISO_8859_1 car les headers HTTP sont Latin-1 par spec
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        rawRequest.append(new String(bytes, StandardCharsets.ISO_8859_1));

        // Protection contre les requetes trop grandes
        if (rawRequest.length() > MAX_REQUEST_SIZE) {
            log.warn("Request too large, sending 413");
            prepareErrorResponse(413);
            state = State.WRITING;
            return true;
        }

        // Detecter la fin des headers : \r\n\r\n
        // C'est la limite obligatoire definie par le RFC HTTP/1.1
        int headerEnd = rawRequest.indexOf("\r\n\r\n");
        if (headerEnd == -1) {
            // Pas encore recu tous les headers — attendre plus de donnees
            return false;
        }

        // Extraire et parser les headers
        String headerSection = rawRequest.substring(0, headerEnd);
        request = parseRequest(headerSection);

        if (request == null) {
            // Requete malformee
            prepareErrorResponse(400);
            state = State.WRITING;
            return true;
        }

        // Verifier si on doit lire un body
        String contentLengthHeader = request.headers.get("content-length");
        String transferEncoding    = request.headers.get("transfer-encoding");

        if (transferEncoding != null && transferEncoding.equalsIgnoreCase("chunked")) {
            // Body en chunks — on verifie si on a recu le chunk final "0\r\n\r\n"
            String bodySection = rawRequest.substring(headerEnd + 4);
            if (!bodySection.contains("0\r\n\r\n")) {
                return false; // attendre la fin des chunks
            }
            request.body = decodeChunked(bodySection);

        } else if (contentLengthHeader != null) {
            // Body de taille connue
            int contentLength = Integer.parseInt(contentLengthHeader.trim());

            // Verifier la limite client_max_body_size de la config
            if (contentLength > config.clientMaxBodySize) {
                log.warn("Body too large: " + contentLength + " bytes");
                prepareErrorResponse(413);
                state = State.WRITING;
                return true;
            }

            // Calculer combien de bytes du body on a deja recu
            String bodySection = rawRequest.substring(headerEnd + 4);
            int bodyReceived   = bodySection.getBytes(StandardCharsets.ISO_8859_1).length;

            if (bodyReceived < contentLength) {
                return false; // attendre le reste du body
            }

            // On a tout le body
            request.body = bodySection
                .substring(0, contentLength)
                .getBytes(StandardCharsets.ISO_8859_1);
        }

        // Pas de body (GET, DELETE, etc.)

        // Determiner keep-alive
        String connection = request.headers.getOrDefault("connection", "");
        keepAlive = connection.equalsIgnoreCase("keep-alive") ||
                    (request.version.equals("HTTP/1.1") &&
                     !connection.equalsIgnoreCase("close"));

        log.debug(request.method + " " + request.path +
                  " (keep-alive: " + keepAlive + ")");

        // Requete complete — passer a la phase de traitement
        state = State.PROCESSING;
        prepareResponse();
        state = State.WRITING;
        return true;
    }


    // -------------------------------------------------------------------------
    // Phase 2 : PARSING — transformer les bytes en objet HttpRequest
    // -------------------------------------------------------------------------

   
     private HttpRequest parseRequest(String headerSection) {
        String[] lines = headerSection.split("\r\n");
        if (lines.length == 0) return null;

        // --- Request Line ---
        // Ex: "GET /index.html?foo=bar HTTP/1.1"
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length != 3) {
            log.warn("Malformed request line: " + lines[0]);
            return null;
        }

        HttpRequest req = new HttpRequest();
        req.method  = requestLine[0].toUpperCase();
        req.version = requestLine[2];

        // Separer le chemin de la query string
        // "/search?q=hello" → path="/search", query="q=hello"
        String uri   = requestLine[1];
        int qMark    = uri.indexOf('?');
        if (qMark != -1) {
            req.path  = uri.substring(0, qMark);
            req.query = uri.substring(qMark + 1);
        } else {
            req.path  = uri;
            req.query = "";
        }

        // --- Headers ---
        // Chaque ligne apres la request line est un header "Nom: valeur"
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon == -1) continue; // header malformed, on ignore

            // toLowerCase() car les headers sont case-insensitive
            String name  = lines[i].substring(0, colon).trim().toLowerCase();
            String value = lines[i].substring(colon + 1).trim();
            req.headers.put(name, value);
        }

        // Valider la methode
        List<String> allowed = Arrays.asList("GET", "POST", "DELETE");
        if (!allowed.contains(req.method)) {
            log.warn("Method not allowed: " + req.method);
            return null;
        }

        return req;
    }

    // -------------------------------------------------------------------------
    // Phase 3 : PROCESSING — construire la reponse
    // -------------------------------------------------------------------------

    /**
     * Decide quoi repondre en fonction de la requete et de la config.
     * Pour l'instant : cherche une route dans la config et sert le fichier.
     */
    private void prepareResponse() {
        // Chercher la route qui correspond au chemin demande
        ConfigLoader.RouteConfig route = findRoute(request.path);

        if (route == null) {
            prepareErrorResponse(404);
            return;
        }

        // Verifier que la methode est autorisee pour cette route
        if (!route.methods.isEmpty() && !route.methods.contains(request.method)) {
            prepareErrorResponse(405);
            return;
        }

        // Redirection
        if (route.redirectCode != 0) {
            prepareRedirectResponse(route.redirectCode, route.redirectTarget);
            return;
        }

        // Servir un fichier statique
        serveStaticFile(route);
    }

    /**
     * Cherche la route la plus specifique qui correspond au path.
     * Ex: pour "/upload/file.txt", "/upload" est plus specifique que "/"
     */
    private ConfigLoader.RouteConfig findRoute(String path) {
        ConfigLoader.RouteConfig best = null;
        int bestLength = -1;

        for (ConfigLoader.RouteConfig route : config.routes) {
            if (path.startsWith(route.path)) {
                if (route.path.length() > bestLength) {
                    best = route;
                    bestLength = route.path.length();
                }
            }
        }

        return best;
    }

    public boolean shouldKeepAlive() {
        return false;
    }

    public void reset() {
        responseBuffer = null;
    }
}