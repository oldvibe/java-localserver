package com.localserver;

import com.localserver.utils.Logger;
import com.localserver.utils.Metrics;
import com.localserver.utils.Session;
import com.localserver.utils.Cookie;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Module de logique serveur : decide comment repondre a une requete donnee.
 */
public class Router {
    private static final Logger log = Logger.getLogger(Router.class);
    private final ConfigLoader.ServerConfig config;

    public Router(ConfigLoader.ServerConfig config) {
        this.config = config;
    }

    public HttpResponse handle(HttpRequest request) {
        Metrics.totalRequests.incrementAndGet();

        // 1. Session Handling
        String sessionId = request.getCookies().get("LOCALSERVER_SESSION");
        Session session = Session.getOrCreate(sessionId);
        HttpResponse response = null;

        // 2. Login handling
        if (request.path.equals("/login") && request.method.equals("POST")) {
            Map<String, String> params = request.getFormParams();
            if ("admin".equals(params.get("username")) && "admin".equals(params.get("password"))) {
                session.setAttribute("user", "admin");
                response = new HttpResponse();
                response.setStatusCode(302, "Found");
                response.setHeader("Location", "/protected.html");
                return finalize(response, sessionId, session);
            } else {
                return errorResponse(401, "Unauthorized", sessionId, session);
            }
        }

        // 3. Logout handling
        if (request.path.equals("/logout")) {
            session.setAttribute("user", null);
            response = new HttpResponse();
            response.setStatusCode(302, "Found");
            response.setHeader("Location", "/login.html");
            return finalize(response, sessionId, session);
        }

        // 4. Protected page check
        if (request.path.equals("/protected.html")) {
            if (session.getAttribute("user") == null) {
                response = new HttpResponse();
                response.setStatusCode(302, "Found");
                response.setHeader("Location", "/login.html");
                return finalize(response, sessionId, session);
            }
        }

        // 5. Metrics API
        if (request.path.equals("/metrics")) {
            response = new HttpResponse();
            response.setHeader("Content-Type", "application/json");
            String json = Metrics.getJson();
            log.info("Returning metrics: " + json);
            response.setBody(json);
            return finalize(response, sessionId, session);
        }

        // 6. Regular Routing
        ConfigLoader.RouteConfig route = findRoute(request.path);
        if (route == null) {
            return errorResponse(404, "Not Found", sessionId, session);
        }

        // Check allowed methods
        if (!route.methods.isEmpty() && !route.methods.contains(request.method)) {
            return errorResponse(405, "Method Not Allowed", sessionId, session);
        }

        // Handle Redirection
        if (route.redirectCode != 0) {
            response = new HttpResponse();
            response.setStatusCode(route.redirectCode, route.redirectCode == 301 ? "Moved Permanently" : "Found");
            response.setHeader("Location", route.redirectTarget);
            return finalize(response, sessionId, session);
        }

        // Calculate local path relative to route root
        String relativePath = request.path.substring(route.path.length());
        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
        String localPath = route.root + (route.root.endsWith("/") || relativePath.isEmpty() ? "" : "/") + relativePath;

        // Check for CGI execution
        for (String ext : route.cgiHandlers.keySet()) {
            if (request.path.endsWith(ext)) {
                response = CGIHandler.execute(request, localPath, route.cgiHandlers.get(ext));
                return finalize(response, sessionId, session);
            }
        }

        // Handle POST/DELETE or Static Files
        if (request.method.equals("DELETE")) {
            response = handleDelete(localPath);
        } else if (request.method.equals("POST")) {
            response = handlePost(request, localPath, route);
        } else if (request.method.equals("GET") || request.method.equals("HEAD")) {
            response = serveStaticFile(request, localPath, route, sessionId, session);
        } else {
            response = errorResponse(405, "Method Not Allowed", sessionId, session);
        }

        return finalize(response, sessionId, session);
    }

    private HttpResponse finalize(HttpResponse response, String sessionId, Session session) {
        if (response == null) return null;
        if (sessionId == null || !sessionId.equals(session.getId())) {
            response.addCookie(new Cookie("LOCALSERVER_SESSION", session.getId()));
        }
        return response;
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

    private HttpResponse serveStaticFile(HttpRequest request, String localPath, ConfigLoader.RouteConfig route, String sessionId, Session session) {
        File file = new File(localPath);

        if (file.isDirectory()) {
            if (route.index != null) {
                File indexFile = new File(file, route.index);
                if (indexFile.exists() && indexFile.isFile()) {
                    file = indexFile;
                    localPath = file.getPath();
                } else if (route.listing) {
                    return directoryListing(file, request.path);
                } else {
                    return errorResponse(403, "Forbidden", sessionId, session);
                }
            } else if (route.listing) {
                return directoryListing(file, request.path);
            } else {
                return errorResponse(403, "Forbidden", sessionId, session);
            }
        }

        // Path Traversal Security
        try {
            String canonical = file.getCanonicalPath();
            String rootCanon = new File(route.root).getCanonicalPath();
            if (!canonical.startsWith(rootCanon)) {
                log.warn("Path traversal blocked: " + localPath);
                return errorResponse(403, "Forbidden", sessionId, session);
            }
        } catch (IOException e) {
            return errorResponse(500, "Internal Server Error", sessionId, session);
        }

        if (!file.exists())  return errorResponse(404, "Not Found", sessionId, session);
        if (!file.canRead()) return errorResponse(403, "Forbidden", sessionId, session);

        try {
            byte[] content = Files.readAllBytes(file.toPath());
            HttpResponse resp = new HttpResponse();
            resp.setBody(content);
            resp.setHeader("Content-Type", ResponseBuilder.contentType(localPath));
            return resp;
        } catch (IOException e) {
            log.error("Error reading file: " + localPath, e);
            return errorResponse(500, "Internal Server Error", sessionId, session);
        }
    }

    private HttpResponse directoryListing(File dir, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Directory listing for ").append(path).append("</h1><hr><ul>");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                sb.append("<li><a href=\"").append(path).append(path.endsWith("/") ? "" : "/").append(name).append("\">")
                  .append(name).append("</a></li>");
            }
        }
        sb.append("</ul>");
        HttpResponse resp = new HttpResponse();
        resp.setBody(sb.toString());
        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        return resp;
    }

    private HttpResponse handleDelete(String path) {
        File file = new File(path);
        if (!file.exists()) return null; // let handle() call errorResponse
        if (file.delete()) {
            HttpResponse resp = new HttpResponse();
            resp.setStatusCode(204, "No Content");
            return resp;
        } else {
            return null; // internal error
        }
    }

    private HttpResponse handlePost(HttpRequest request, String localPath, ConfigLoader.RouteConfig route) {
        String contentType = request.getHeaders().getOrDefault("content-type", "");
        if (contentType.contains("multipart/form-data")) {
            int bIdx = contentType.indexOf("boundary=");
            if (bIdx != -1) {
                String boundary = contentType.substring(bIdx + 9);
                return handleMultipartUpload(request, localPath, boundary);
            }
        }
        HttpResponse resp = new HttpResponse();
        resp.setStatusCode(201, "Created");
        return resp;
    }

    private HttpResponse handleMultipartUpload(HttpRequest request, String uploadDir, String boundary) {
        byte[] body = request.getBody();
        if (body == null || body.length == 0) return null;

        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int lastPos = 0;
        
        while (true) {
            int start = findBytes(body, boundaryBytes, lastPos);
            if (start == -1) break;
            int nextBoundary = findBytes(body, boundaryBytes, start + boundaryBytes.length);
            if (nextBoundary == -1) break;
            
            byte[] part = java.util.Arrays.copyOfRange(body, start + boundaryBytes.length, nextBoundary);
            processPart(part, uploadDir);
            lastPos = nextBoundary;
        }
        
        HttpResponse resp = new HttpResponse();
        resp.setStatusCode(201, "Created");
        resp.setBody("File(s) uploaded successfully.");
        return resp;
    }

    private void processPart(byte[] part, String uploadDir) {
        int headerEnd = -1;
        for (int i = 0; i < part.length - 3; i++) {
            if (part[i] == '\r' && part[i+1] == '\n' && part[i+2] == '\r' && part[i+3] == '\n') {
                headerEnd = i;
                break;
            }
        }
        if (headerEnd == -1) return;

        String headers = new String(part, 0, headerEnd, StandardCharsets.ISO_8859_1);
        if (headers.contains("filename=\"")) {
            int nameStart = headers.indexOf("filename=\"") + 10;
            int nameEnd = headers.indexOf("\"", nameStart);
            String filename = headers.substring(nameStart, nameEnd);
            
            byte[] content = java.util.Arrays.copyOfRange(part, headerEnd + 4, part.length - 2); 
            try {
                File dir = new File(uploadDir);
                if (!dir.exists()) dir.mkdirs();
                Files.write(new File(dir, filename).toPath(), content);
            } catch (IOException e) {
                log.error("Failed to write uploaded file", e);
            }
        }
    }

    private int findBytes(byte[] data, byte[] target, int start) {
        for (int i = start; i <= data.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private HttpResponse errorResponse(int code, String message, String sessionId, Session session) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(code, message);
        
        String customPage = config.errorPages.get(code);
        if (customPage != null) {
            File f = new File(customPage);
            if (f.exists() && f.isFile()) {
                try {
                    response.setBody(Files.readAllBytes(f.toPath()));
                    return finalize(response, sessionId, session);
                } catch (IOException ignored) {}
            }
        }
        
        response.setBody("<h1>" + code + " " + message + "</h1>");
        return finalize(response, sessionId, session);
    }
}
