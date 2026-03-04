package com.localserver;

import com.localserver.utils.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.http.HttpRequest;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConnectionHandler {

    private static final Logger log = Logger.getLogger(ConnectionHandler.class);

    // Taille max de l'accumulateur — protection contre les requetes infinies
    private static final int MAX_REQUEST_SIZE = 8192 * 10; // 80KB

    // -------------------------------------------------------------------------
    // Etat interne de la connexion
    // -------------------------------------------------------------------------

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

    // Accumulateur de bytes bruts — on y ajoute chaque lecture NIO
    // On utilise un StringBuilder pour les headers (texte)
    // et une liste de bytes pour le body (binaire)
    private final StringBuilder rawRequest = new StringBuilder();

    // La requete HTTP parsee
    private HttpRequest request;

    // La reponse HTTP prete a etre envoyee
    private ByteBuffer responseBuffer;

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

    /**
     * Lit le fichier demande et prepare la reponse 200.
     */
    private void serveStaticFile(ConfigLoader.RouteConfig route) {
        // Construire le chemin complet vers le fichier
        String filePath = route.root + request.path;

        // Si le chemin se termine par /, ajouter le fichier index
        if (filePath.endsWith("/") && route.index != null) {
            filePath += route.index;
        }

        java.io.File file = new java.io.File(filePath);

        // Securite : empecher la traversee de dossiers ("../../../etc/passwd")
        try {
            String canonical = file.getCanonicalPath();
            String rootCanon = new java.io.File(route.root).getCanonicalPath();
            if (!canonical.startsWith(rootCanon)) {
                log.warn("Directory traversal attempt: " + filePath);
                prepareErrorResponse(403);
                return;
            }
        } catch (IOException e) {
            prepareErrorResponse(500);
            return;
        }

        if (!file.exists()) {
            prepareErrorResponse(404);
            return;
        }

        if (!file.canRead()) {
            prepareErrorResponse(403);
            return;
        }

        // Lire le fichier
        try {
            byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
            String contentType = getContentType(filePath);
            prepareOkResponse(content, contentType);
        } catch (IOException e) {
            log.error("Error reading file: " + filePath, e);
            prepareErrorResponse(500);
        }
    }

    // -------------------------------------------------------------------------
    // Construction des reponses HTTP
    // -------------------------------------------------------------------------

    private void prepareOkResponse(byte[] body, String contentType) {
        String headers =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: "   + contentType     + "\r\n" +
            "Content-Length: " + body.length      + "\r\n" +
            "Connection: "     + (keepAlive ? "keep-alive" : "close") + "\r\n" +
            "\r\n";

        byte[] headerBytes = headers.getBytes(StandardCharsets.UTF_8);

        // Concatener headers + body dans un seul ByteBuffer
        responseBuffer = ByteBuffer.allocate(headerBytes.length + body.length);
        responseBuffer.put(headerBytes);
        responseBuffer.put(body);
        responseBuffer.flip(); // pret a etre lu
    }

    private void prepareRedirectResponse(int code, String location) {
        String reason = code == 301 ? "Moved Permanently" : "Found";
        String body   = "<html><body>Redirecting to <a href=\"" +
                         location + "\">" + location + "</a></body></html>";

        String response =
            "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Location: "       + location          + "\r\n" +
            "Content-Type: text/html\r\n"           +
            "Content-Length: " + body.length()      + "\r\n" +
            "Connection: close\r\n"                 +
            "\r\n" +
            body;

        responseBuffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
        keepAlive = false;
    }

    private void prepareErrorResponse(int code) {
        String reason = getReasonPhrase(code);

        // Chercher une page d'erreur personnalisee dans la config
        String errorPagePath = config.errorPages.get(code);
        byte[] body;

        if (errorPagePath != null) {
            java.io.File f = new java.io.File(
                "src/main/resources" + errorPagePath
            );
            if (f.exists()) {
                try {
                    body = java.nio.file.Files.readAllBytes(f.toPath());
                } catch (IOException e) {
                    body = defaultErrorBody(code, reason);
                }
            } else {
                body = defaultErrorBody(code, reason);
            }
        } else {
            body = defaultErrorBody(code, reason);
        }

        String headers =
            "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: text/html\r\n"                +
            "Content-Length: " + body.length             + "\r\n" +
            "Connection: close\r\n"                      +
            "\r\n";

        byte[] headerBytes = headers.getBytes(StandardCharsets.UTF_8);
        responseBuffer = ByteBuffer.allocate(headerBytes.length + body.length);
        responseBuffer.put(headerBytes);
        responseBuffer.put(body);
        responseBuffer.flip();

        keepAlive = false;
    }

    private byte[] defaultErrorBody(int code, String reason) {
        String html = "<!DOCTYPE html><html><body>" +
                      "<h1>" + code + " " + reason + "</h1>" +
                      "</body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Phase 4 : WRITING — envoyer la reponse
    // -------------------------------------------------------------------------

    /**
     * Appele par Server.handleWrite().
     * Envoie autant de bytes que possible.
     * Retourne true quand tout est envoye.
     */
    public boolean writeResponse(SocketChannel ch) throws IOException {
        if (responseBuffer == null) return true;

        ch.write(responseBuffer);

        // hasRemaining() = false → tout a ete envoye
        return !responseBuffer.hasRemaining();
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    /**
     * Decode un body en chunked transfer encoding.
     * Format : <taille-hex>\r\n<donnees>\r\n ... 0\r\n\r\n
     */
    private byte[] decodeChunked(String raw) {
        List<Byte> result = new ArrayList<>();
        int pos = 0;

        while (pos < raw.length()) {
            int lineEnd = raw.indexOf("\r\n", pos);
            if (lineEnd == -1) break;

            int chunkSize;
            try {
                chunkSize = Integer.parseInt(raw.substring(pos, lineEnd).trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }

            if (chunkSize == 0) break; // dernier chunk

            pos = lineEnd + 2;
            byte[] chunkBytes = raw.substring(pos, pos + chunkSize)
                                   .getBytes(StandardCharsets.ISO_8859_1);
            for (byte b : chunkBytes) result.add(b);

            pos += chunkSize + 2; // sauter les donnees + \r\n final
        }

        byte[] out = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) out[i] = result.get(i);
        return out;
    }

    private String getContentType(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") ||
            path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif"))  return "image/gif";
        if (path.endsWith(".ico"))  return "image/x-icon";
        if (path.endsWith(".txt"))  return "text/plain";
        if (path.endsWith(".pdf"))  return "application/pdf";
        return "application/octet-stream"; // type par defaut
    }

    private String getReasonPhrase(int code) {
        switch (code) {
            case 200: return "OK";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Content Too Large";
            case 500: return "Internal Server Error";
            default:  return "Unknown";
        }
    }

    public boolean shouldKeepAlive() { return keepAlive; }

    public boolean isTimedOut() {
        return System.currentTimeMillis() - lastActivity > TIMEOUT_MS;
    }

    public void reset() {
        rawRequest.setLength(0);
        request        = null;
        responseBuffer = null;
        state          = State.READING;
        keepAlive      = false;
    }
}