package com.localserver;

import com.localserver.utils.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class ResponseBuilder {

    private static final Logger log = Logger.getLogger(ResponseBuilder.class);

    private static final DateTimeFormatter HTTP_DATE =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
                         .withZone(ZoneOffset.UTC);

    // Reponse 200 OK avec un body
    public static ByteBuffer ok(byte[] body, String contentType, boolean keepAlive) {
        Map<String, String> headers = baseHeaders(keepAlive);
        headers.put("Content-Type",   contentType);
        headers.put("Content-Length", String.valueOf(body.length));
        return build(200, "OK", headers, body);
    }

    // Reponse de redirection (301 ou 302).
    public static ByteBuffer redirect(int code, String location) {
        String reason = code == 301 ? "Moved Permanently" : "Found";
        String bodyHtml =
            "<!DOCTYPE html><html><body>" +
            "<h1>" + code + " " + reason + "</h1>" +
            "<p>Redirecting to <a href=\"" + location + "\">" + location + "</a></p>" +
            "</body></html>";

        byte[] body = bodyHtml.getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = baseHeaders(false);
        headers.put("Location",       location);
        headers.put("Content-Type",   "text/html; charset=UTF-8");
        headers.put("Content-Length", String.valueOf(body.length));

        return build(code, reason, headers, body);
    }

    /**
     * Reponse d'erreur avec page HTML.
     * Cherche d'abord une page personnalisee dans la config,
     * sinon genere une page minimale par defaut.
     */
    public static ByteBuffer error(int code,
                                   ConfigLoader.ServerConfig config) {
        String reason = reasonPhrase(code);
        byte[] body   = loadErrorPage(code, config);

        Map<String, String> headers = baseHeaders(false);
        headers.put("Content-Type",   "text/html; charset=UTF-8");
        headers.put("Content-Length", String.valueOf(body.length));

        // Header special pour 405 : indiquer les methodes autorisees
        if (code == 405) {
            headers.put("Allow", "GET, POST, DELETE");
        }

        return build(code, reason, headers, body);
    }

    /**
     * Reponse d'erreur pour une route specifique
     * (avec les methodes autorisees de cette route pour le 405).
     */
    public static ByteBuffer error(int code,
                                   ConfigLoader.ServerConfig config,
                                   ConfigLoader.RouteConfig route) {
        ByteBuffer buf = error(code, config);

        // Si 405, on peut preciser les methodes autorisees par cette route
        if (code == 405 && route != null && !route.methods.isEmpty()) {
            String allow = String.join(", ", route.methods);
            // On reconstruit avec le bon header Allow
            String reason = reasonPhrase(code);
            byte[] body   = loadErrorPage(code, config);

            Map<String, String> headers = baseHeaders(false);
            headers.put("Content-Type",   "text/html; charset=UTF-8");
            headers.put("Content-Length", String.valueOf(body.length));
            headers.put("Allow",          allow);

            return build(code, reason, headers, body);
        }

        return buf;
    }

    /**
     * Convertit un HttpResponse en ByteBuffer pour NIO.
     */
    public static ByteBuffer fromResponse(HttpResponse response, boolean keepAlive) {
        if (keepAlive) {
            response.setHeader("Connection", "keep-alive");
        } else {
            response.setHeader("Connection", "close");
        }
        
        byte[] bytes = response.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }


    /**
     * Assemble la reponse HTTP complete en bytes.
     *
     * Format :
     *   HTTP/1.1 200 OK\r\n
     *   Header1: valeur\r\n
     *   Header2: valeur\r\n
     *   \r\n
     *   <body bytes>
     */
    private static ByteBuffer build(int code,
                                    String reason,
                                    Map<String, String> headers,
                                    byte[] body) {
        StringBuilder sb = new StringBuilder();

        // Status line
        sb.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n");

        // Headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }

        // Ligne vide separant headers et body
        sb.append("\r\n");

        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + body.length);
        buffer.put(headerBytes);
        buffer.put(body);
        buffer.flip(); // pret a etre lu par ch.write()

        log.debug("Built response: " + code + " " + reason +
                  " (" + body.length + " bytes)");

        return buffer;
    }

    /**
     * Headers communs a toutes les reponses.
     */
    private static Map<String, String> baseHeaders(boolean keepAlive) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Server",     "LocalServer/1.0");
        headers.put("Date",       ZonedDateTime.now(ZoneOffset.UTC).format(HTTP_DATE));
        headers.put("Connection", keepAlive ? "keep-alive" : "close");
        return headers;
    }

    // Utilitaires

    private static byte[] loadErrorPage(int code, ConfigLoader.ServerConfig config) {
        String pagePath = config.errorPages.get(code);

        if (pagePath != null) {
            java.io.File file = new java.io.File("src/main/resources" + pagePath);
            if (file.exists()) {
                try {
                    return java.nio.file.Files.readAllBytes(file.toPath());
                } catch (java.io.IOException e) {
                    log.warn("Could not read error page: " + pagePath);
                }
            }
        }

        // Page par defaut minimale
        String reason = reasonPhrase(code);
        String html =
            "<!DOCTYPE html><html><head><title>" + code + " " + reason + "</title></head>" +
            "<body><h1>" + code + " " + reason + "</h1></body></html>";

        return html.getBytes(StandardCharsets.UTF_8);
    }

    public static String contentType(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css"))   return "text/css";
        if (path.endsWith(".js"))    return "application/javascript";
        if (path.endsWith(".json"))  return "application/json";
        if (path.endsWith(".png"))   return "image/png";
        if (path.endsWith(".jpg") ||
            path.endsWith(".jpeg"))  return "image/jpeg";
        if (path.endsWith(".gif"))   return "image/gif";
        if (path.endsWith(".ico"))   return "image/x-icon";
        if (path.endsWith(".txt"))   return "text/plain";
        if (path.endsWith(".pdf"))   return "application/pdf";
        return "application/octet-stream";
    }

    public static String reasonPhrase(int code) {
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
}