package com.localserver;

import com.localserver.utils.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class ConnectionHandler {

    private static final Logger log = Logger.getLogger(ConnectionHandler.class);

    private static final int MAX_REQUEST_SIZE = 8192 * 10;

    private enum State { READING, WRITING, DONE }

    private State state = State.READING;

    private final SocketChannel           channel;
    private final ConfigLoader.ServerConfig config;

    private final StringBuilder rawRequest = new StringBuilder();
    private HttpRequest         request;
    private ByteBuffer          responseBuffer;
    private boolean             keepAlive = false;

    private long lastActivity = System.currentTimeMillis();
    private static final long TIMEOUT_MS = 30_000;

    public ConnectionHandler(SocketChannel channel, ConfigLoader.ServerConfig config) {
        this.channel = channel;
        this.config  = config;
    }

    // -------------------------------------------------------------------------
    // Phase READING
    // -------------------------------------------------------------------------

    public boolean process(ByteBuffer buffer) throws IOException {
        lastActivity = System.currentTimeMillis();

        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        rawRequest.append(new String(bytes, StandardCharsets.ISO_8859_1));

        if (rawRequest.length() > MAX_REQUEST_SIZE) {
            log.warn("Request too large");
            responseBuffer = ResponseBuilder.error(413, config);
            state = State.WRITING;
            return true;
        }

        int headerEnd = rawRequest.indexOf("\r\n\r\n");
        if (headerEnd == -1) return false; // attendre plus de donnees

        // --- Parser les headers via RequestParser ---
        String headerSection = rawRequest.substring(0, headerEnd);
        request = RequestParser.parse(headerSection);

        if (request == null) {
            responseBuffer = ResponseBuilder.error(400, config);
            state = State.WRITING;
            return true;
        }

        if (request.methodNotAllowed) {
            responseBuffer = ResponseBuilder.error(405, config);
            state = State.WRITING;
            return true;
        }

        // --- Gerer le body ---
        String bodyRaw = rawRequest.substring(headerEnd + 4);

        if (!handleBody(bodyRaw)) return false; // attendre le reste du body

        // --- Determiner keep-alive ---
        String conn = request.headers.getOrDefault("connection", "");
        keepAlive = conn.equalsIgnoreCase("keep-alive") ||
                    (request.version.equals("HTTP/1.1") &&
                     !conn.equalsIgnoreCase("close"));

        log.debug(request.method + " " + request.path +
                  " (keep-alive=" + keepAlive + ")");

        // --- Construire la reponse via ResponseBuilder ---
        responseBuffer = buildResponse();
        state = State.WRITING;
        return true;
    }

    /**
     * Retourne false si on doit attendre plus de donnees pour le body.
     */
    private boolean handleBody(String bodyRaw) {
        String te = request.headers.get("transfer-encoding");
        String cl = request.headers.get("content-length");

        if (te != null && te.equalsIgnoreCase("chunked")) {
            if (!bodyRaw.contains("0\r\n\r\n")) return false;
            request.body = RequestParser.decodeChunked(bodyRaw);

        } else if (cl != null) {
            int contentLength;
            try {
                contentLength = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid Content-Length: " + cl);
                responseBuffer = ResponseBuilder.error(400, config);
                return true;
            }

            if (contentLength > config.clientMaxBodySize) {
                log.warn("Body too large: " + contentLength);
                responseBuffer = ResponseBuilder.error(413, config);
                return true;
            }

            int received = bodyRaw.getBytes(StandardCharsets.ISO_8859_1).length;
            if (received < contentLength) return false;

            request.body = bodyRaw.substring(0, contentLength)
                                  .getBytes(StandardCharsets.ISO_8859_1);
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Phase PROCESSING — router et construire la reponse
    // -------------------------------------------------------------------------

    private ByteBuffer buildResponse() {
        ConfigLoader.RouteConfig route = findRoute(request.path);

        if (route == null) {
            return ResponseBuilder.error(404, config);
        }

        if (!route.methods.isEmpty() && !route.methods.contains(request.method)) {
            return ResponseBuilder.error(405, config, route);
        }

        if (route.redirectCode != 0) {
            return ResponseBuilder.redirect(route.redirectCode, route.redirectTarget);
        }

        return serveStaticFile(route);
    }

    private ConfigLoader.RouteConfig findRoute(String path) {
        ConfigLoader.RouteConfig best = null;
        int bestLen = -1;

        for (ConfigLoader.RouteConfig route : config.routes) {
            if (path.startsWith(route.path) && route.path.length() > bestLen) {
                best   = route;
                bestLen = route.path.length();
            }
        }

        return best;
    }

    private ByteBuffer serveStaticFile(ConfigLoader.RouteConfig route) {
        String filePath = route.root + request.path;

        if (filePath.endsWith("/") && route.index != null) {
            filePath += route.index;
        }

        java.io.File file = new java.io.File(filePath);

        // Protection traversee de dossiers
        try {
            String canonical  = file.getCanonicalPath();
            String rootCanon  = new java.io.File(route.root).getCanonicalPath();
            if (!canonical.startsWith(rootCanon)) {
                log.warn("Directory traversal blocked: " + filePath);
                return ResponseBuilder.error(403, config);
            }
        } catch (IOException e) {
            return ResponseBuilder.error(500, config);
        }

        if (!file.exists())    return ResponseBuilder.error(404, config);
        if (!file.canRead())   return ResponseBuilder.error(403, config);

        try {
            byte[] content    = java.nio.file.Files.readAllBytes(file.toPath());
            String ct         = ResponseBuilder.contentType(filePath);
            return ResponseBuilder.ok(content, ct, keepAlive);
        } catch (IOException e) {
            log.error("Error reading file: " + filePath, e);
            return ResponseBuilder.error(500, config);
        }
    }

    // -------------------------------------------------------------------------
    // Phase WRITING
    // -------------------------------------------------------------------------

    public boolean writeResponse(SocketChannel ch) throws IOException {
        if (responseBuffer == null) return true;
        ch.write(responseBuffer);
        return !responseBuffer.hasRemaining();
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

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
        lastActivity   = System.currentTimeMillis();
    }
}