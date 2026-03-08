package com.localserver;

import com.localserver.utils.Logger;
import com.localserver.utils.Metrics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Module de gestion de connexion (Peer's module).
 * Responsable de la lecture des octets, du parsing de la requete,
 * et de l'ecriture de la reponse.
 * Delegue la logique de decision au Router.
 */
public class ConnectionHandler {

    private static final Logger log = Logger.getLogger(ConnectionHandler.class);

    private static final int MAX_REQUEST_SIZE = 8192 * 10;

    private enum State { READING, WRITING, DONE }

    private State state = State.READING;

    private final SocketChannel           channel;
    private final List<ConfigLoader.ServerConfig> configs;
    private final Server                  server;
    private ConfigLoader.ServerConfig     config;

    private final StringBuilder rawRequest = new StringBuilder();
    private HttpRequest         request;
    private ByteBuffer          responseBuffer;
    private boolean             keepAlive = false;

    private long lastActivity = System.currentTimeMillis();
    private static final long TIMEOUT_MS = 30_000;

    public ConnectionHandler(SocketChannel channel, List<ConfigLoader.ServerConfig> configs, Server server) {
        this.channel = channel;
        this.configs = configs;
        this.server  = server;
        // Default initial config
        this.config  = selectInitialConfig(configs);
        log.debug("New ConnectionHandler created for " + channel);
    }

    private ConfigLoader.ServerConfig selectInitialConfig(List<ConfigLoader.ServerConfig> configs) {
        for (ConfigLoader.ServerConfig sc : configs) {
            if (sc.isDefault) return sc;
        }
        return configs.get(0);
    }

    // -------------------------------------------------------------------------
    // Phase READING
    // -------------------------------------------------------------------------

    public boolean process(ByteBuffer buffer) throws IOException {
        if (state == State.WRITING) {
            buffer.position(buffer.limit());
            return true;
        }
        lastActivity = System.currentTimeMillis();

        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        rawRequest.append(new String(bytes, StandardCharsets.ISO_8859_1));

        if (rawRequest.length() > MAX_REQUEST_SIZE) {
            log.warn("Request too large: " + rawRequest.length());
            responseBuffer = ResponseBuilder.error(413, config);
            state = State.WRITING;
            return true;
        }

        int headerEnd = rawRequest.indexOf("\r\n\r\n");
        if (headerEnd == -1) return false; // wait for more data

        // --- Parser les headers via RequestParser ---
        String headerSection = rawRequest.substring(0, headerEnd);
        request = RequestParser.parse(headerSection);

        if (request == null) {
            log.warn("RequestParser returned null");
            responseBuffer = ResponseBuilder.error(400, config);
            state = State.WRITING;
            return true;
        }

        // --- Re-selection de la config basee sur le Host header ---
        String hostHeader = request.headers.get("host");
        this.config = server.selectConfig(configs, hostHeader);

        if (request.methodNotAllowed) {
            responseBuffer = ResponseBuilder.error(405, config);
            state = State.WRITING;
            return true;
        }

        // --- Gerer le body ---
        String bodyRaw = rawRequest.substring(headerEnd + 4);

        if (!handleBody(bodyRaw)) return false; // wait for body
        
        if (responseBuffer != null) {
            state = State.WRITING;
            return true;
        }

        // --- Determiner keep-alive ---
        String conn = request.headers.getOrDefault("connection", "");
        keepAlive = conn.equalsIgnoreCase("keep-alive") ||
                    (request.version.equals("HTTP/1.1") && !conn.equalsIgnoreCase("close"));

        log.debug(request.method + " " + request.path + " (keep-alive=" + keepAlive + ")");

        // --- Deleguer la logique au Router ---
        responseBuffer = buildResponse();
        
        // Handle HEAD method (headers only)
        if (request.method.equals("HEAD") && responseBuffer != null) {
            byte[] arr = responseBuffer.array();
            int limit = responseBuffer.limit();
            for (int i = 0; i < limit - 3; i++) {
                if (arr[i] == '\r' && arr[i+1] == '\n' && arr[i+2] == '\r' && arr[i+3] == '\n') {
                    responseBuffer.limit(i + 4);
                    break;
                }
            }
        }

        state = State.WRITING;
        return true;
    }

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
                responseBuffer = ResponseBuilder.error(400, config);
                return true;
            }

            if (contentLength > config.clientMaxBodySize) {
                log.warn("Body too large (Content-Length): " + contentLength);
                responseBuffer = ResponseBuilder.error(413, config);
                return true;
            }

            int received = bodyRaw.getBytes(StandardCharsets.ISO_8859_1).length;
            if (received < contentLength) return false;

            request.body = bodyRaw.substring(0, contentLength)
                                  .getBytes(StandardCharsets.ISO_8859_1);
        } else {
            byte[] bodyBytes = bodyRaw.getBytes(StandardCharsets.ISO_8859_1);
            if (bodyBytes.length > config.clientMaxBodySize) {
                log.warn("Body too large: " + bodyBytes.length);
                responseBuffer = ResponseBuilder.error(413, config);
                return true;
            }
            request.body = bodyBytes;
        }

        return true;
    }

    private ByteBuffer buildResponse() {
        Router router = new Router(config);
        HttpResponse response = router.handle(request);
        if (response == null) {
            // Fallback for unexpected null (should be handled by Router)
            return ResponseBuilder.error(500, config);
        }
        return ResponseBuilder.fromResponse(response, keepAlive);
    }

    // -------------------------------------------------------------------------
    // Phase WRITING
    // -------------------------------------------------------------------------

    public boolean writeResponse(SocketChannel ch) throws IOException {
        if (responseBuffer == null) return true;
        ch.write(responseBuffer);
        boolean done = !responseBuffer.hasRemaining();
        if (done) state = State.DONE;
        return done;
    }

    // -------------------------------------------------------------------------
    // Utilities
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
