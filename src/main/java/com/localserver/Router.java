package com.localserver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class Router {
    private Config.ServerConfig serverConfig;

    public Router(Config.ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
    }

    public Config.ServerConfig getServerConfig() {
        return serverConfig;
    }

    public HttpResponse handle(HttpRequest request) {
        com.localserver.utils.Metrics.totalRequests.incrementAndGet();
        
        // 1. Strict Host header check for HTTP/1.1
        if ("HTTP/1.1".equals(request.getVersion()) && !request.getHeaders().containsKey("Host")) {
            return errorResponse(400, "Bad Request: Missing Host Header");
        }

        // 2. Path Traversal Protection
        if (request.getPath().contains("..")) {
            return errorResponse(403, "Forbidden: Path traversal not allowed");
        }

        // 3. Session Handling
        String sessionId = request.getCookies().get("LOCALSERVER_SESSION");
        com.localserver.utils.Session session = com.localserver.utils.Session.getOrCreate(sessionId);
        HttpResponse response = null;

        // Login handling
        if (request.getPath().equals("/login") && request.getMethod().equals("POST")) {
            java.util.Map<String, String> params = request.getFormParams();
            if ("admin".equals(params.get("username")) && "admin".equals(params.get("password"))) {
                session.setAttribute("user", "admin");
                response = new HttpResponse();
                response.setStatusCode(302, "Found");
                response.setHeader("Location", "/protected.html");
                return finalizeResponse(response, sessionId, session);
            } else {
                response = errorResponse(401, "Unauthorized: Invalid credentials");
                return finalizeResponse(response, sessionId, session);
            }
        }

        // Logout handling
        if (request.getPath().equals("/logout")) {
            session.setAttribute("user", null);
            response = new HttpResponse();
            response.setStatusCode(302, "Found");
            response.setHeader("Location", "/login.html");
            return finalizeResponse(response, sessionId, session);
        }

        // Protected page check
        if (request.getPath().equals("/protected.html")) {
            if (session.getAttribute("user") == null) {
                response = new HttpResponse();
                response.setStatusCode(302, "Found");
                response.setHeader("Location", "/login.html");
                return finalizeResponse(response, sessionId, session);
            }
        }

        if (request.getPath().equals("/metrics")) {
            response = new HttpResponse();
            response.setHeader("Content-Type", "application/json");
            response.setBody(com.localserver.utils.Metrics.getJson());
        }

        if (response == null) {
            Config.RouteConfig route = findRoute(request.getPath());
            
            if (route == null) {
                response = errorResponse(404, "Not Found");
            } else if (route.redirection != null) {
                response = new HttpResponse();
                response.setStatusCode(301, "Moved Permanently");
                response.setHeader("Location", route.redirection);
            } else if (!route.methods.contains(request.getMethod())) {
                response = errorResponse(405, "Method Not Allowed");
            } else {
                // Calculate local path relative to route root
                String relativePath = request.getPath().substring(route.path.length());
                if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
                String localPath = route.root + (route.root.endsWith("/") || relativePath.isEmpty() ? "" : "/") + relativePath;

                // Check for CGI
                boolean handledByCGI = false;
                for (String ext : route.cgi.keySet()) {
                    if (request.getPath().endsWith(ext)) {
                        response = CGIHandler.execute(request, localPath, route.cgi.get(ext));
                        handledByCGI = true;
                        break;
                    }
                }

                if (!handledByCGI) {
                    if (request.getMethod().equals("DELETE")) {
                        response = handleDelete(localPath);
                    } else if (request.getMethod().equals("POST")) {
                        response = handlePost(request, route);
                    } else {
                        // Handle static files
                        File file = new File(localPath);

                        if (file.isDirectory()) {
                            if (route.index != null) {
                                File indexFile = new File(file, route.index);
                                if (indexFile.exists() && indexFile.isFile()) {
                                    file = indexFile;
                                } else if (route.listing) {
                                    response = directoryListing(file, request.getPath());
                                } else {
                                    response = errorResponse(403, "Forbidden");
                                }
                            } else if (route.listing) {
                                response = directoryListing(file, request.getPath());
                            } else {
                                response = errorResponse(403, "Forbidden");
                            }
                        }

                        if (response == null) {
                            if (file.exists() && file.isFile()) {
                                try {
                                    byte[] content = Files.readAllBytes(file.toPath());
                                    response = new HttpResponse();
                                    response.setBody(content);
                                    // Simple mime-type detection
                                    if (file.getName().endsWith(".html")) response.setHeader("Content-Type", "text/html");
                                    else if (file.getName().endsWith(".jpg")) response.setHeader("Content-Type", "image/jpeg");
                                } catch (IOException e) {
                                    response = errorResponse(500, "Internal Server Error");
                                }
                            } else {
                                response = errorResponse(404, "Not Found");
                            }
                        }
                    }
                }
            }
        }

        return finalizeResponse(response, sessionId, session);
    }

    private HttpResponse finalizeResponse(HttpResponse response, String sessionId, com.localserver.utils.Session session) {
        if (response == null) {
            response = errorResponse(500, "Internal Server Error: No Response Generated");
        }
        // Add session cookie if newly created or if we want to ensure it's there
        if (sessionId == null || !sessionId.equals(session.getId())) {
            response.addCookie(new com.localserver.utils.Cookie("LOCALSERVER_SESSION", session.getId()));
        }
        return response;
    }

    private HttpResponse handlePost(HttpRequest request, Config.RouteConfig route) {
        String contentType = request.getHeaders().getOrDefault("Content-Type", "");
        if (contentType.contains("multipart/form-data")) {
            String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
            
            // Calculate upload directory from route
            String relativePath = request.getPath().substring(route.path.length());
            if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            String uploadDir = route.root + (route.root.endsWith("/") || relativePath.isEmpty() ? "" : "/") + relativePath;

            return handleMultipartUpload(request, uploadDir, boundary);
        }
        return errorResponse(201, "Created");
    }

    private HttpResponse handleMultipartUpload(HttpRequest request, String uploadDir, String boundary) {
        byte[] body = request.getBody();
        if (body == null) return errorResponse(400, "Bad Request: No Body");

        byte[] boundaryBytes = ("--" + boundary).getBytes();
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
        
        HttpResponse response = new HttpResponse();
        response.setStatusCode(201, "Created");
        response.setBody("File(s) uploaded successfully.");
        return response;
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

        String headers = new String(part, 0, headerEnd);
        if (headers.contains("filename=\"")) {
            int nameStart = headers.indexOf("filename=\"") + 10;
            int nameEnd = headers.indexOf("\"", nameStart);
            String filename = headers.substring(nameStart, nameEnd);
            
            byte[] content = java.util.Arrays.copyOfRange(part, headerEnd + 4, part.length - 2); // -2 for trailing CRLF
            try {
                File dir = new File(uploadDir);
                if (!dir.exists()) dir.mkdirs();
                Files.write(new File(dir, filename).toPath(), content);
            } catch (IOException e) {
                e.printStackTrace();
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

    private HttpResponse handleDelete(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return errorResponse(404, "Not Found");
        }
        if (file.delete()) {
            HttpResponse response = new HttpResponse();
            response.setStatusCode(204, "No Content");
            return response;
        } else {
            return errorResponse(500, "Internal Server Error");
        }
    }

    private Config.RouteConfig findRoute(String path) {
        Config.RouteConfig bestMatch = null;
        for (Config.RouteConfig route : serverConfig.routes) {
            if (path.startsWith(route.path)) {
                if (bestMatch == null || route.path.length() > bestMatch.path.length()) {
                    bestMatch = route;
                }
            }
        }
        return bestMatch;
    }

    private HttpResponse directoryListing(File dir, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Directory listing for ").append(path).append("</h1><hr><ul>");
        for (File f : dir.listFiles()) {
            sb.append("<li><a href=\"").append(path).append(path.endsWith("/") ? "" : "/").append(f.getName()).append("\">")
              .append(f.getName()).append("</a></li>");
        }
        sb.append("</ul>");
        HttpResponse response = new HttpResponse();
        response.setBody(sb.toString());
        return response;
    }

    private HttpResponse errorResponse(int code, String message) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(code, message);
        
        String customPage = serverConfig.errorPages.get(code);
        if (customPage != null) {
            File f = new File(customPage);
            if (f.exists() && f.isFile()) {
                try {
                    response.setBody(Files.readAllBytes(f.toPath()));
                    return response;
                } catch (IOException ignored) {}
            }
        }
        
        response.setBody("<h1>" + code + " " + message + "</h1>");
        return response;
    }
}
