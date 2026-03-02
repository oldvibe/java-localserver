package com.localserver;

import java.io.*;
import java.util.Map;

public class CGIHandler {
    public static HttpResponse execute(HttpRequest request, String scriptPath, String executable) {
        try {
            ProcessBuilder pb = new ProcessBuilder(executable, scriptPath);
            Map<String, String> env = pb.environment();
            
            // Set standard CGI environment variables
            env.put("REQUEST_METHOD", request.getMethod());
            env.put("PATH_INFO", request.getPath());
            env.put("QUERY_STRING", request.getQueryString());
            env.put("CONTENT_LENGTH", request.getHeaders().getOrDefault("Content-Length", "0"));
            env.put("CONTENT_TYPE", request.getHeaders().getOrDefault("Content-Type", ""));
            env.put("SCRIPT_FILENAME", scriptPath);
            env.put("SERVER_PROTOCOL", "HTTP/1.1");
            
            Process process = pb.start();
            
            // If POST, write body to stdin
            if (request.getMethod().equals("POST") && request.getBody() != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(request.getBody());
                }
            }
            
            // Read stdout
            InputStream is = process.getInputStream();
            byte[] output = is.readAllBytes();
            
            // Read stderr for debugging
            InputStream es = process.getErrorStream();
            byte[] error = es.readAllBytes();
            if (error.length > 0) {
                System.err.println("CGI Error: " + new String(error));
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                HttpResponse response = new HttpResponse();
                response.setStatusCode(500, "Internal Server Error");
                response.setBody("CGI script failed with exit code " + exitCode);
                return response;
            }
            
            HttpResponse response = new HttpResponse();
            
            // Simple header parsing
            String outputStr = new String(output);
            int headerEnd = -1;
            int separatorLen = 0;
            
            if (outputStr.contains("\r\n\r\n")) {
                headerEnd = outputStr.indexOf("\r\n\r\n");
                separatorLen = 4;
            } else if (outputStr.contains("\n\n")) {
                headerEnd = outputStr.indexOf("\n\n");
                separatorLen = 2;
            }

            if (headerEnd != -1) {
                String headerPart = outputStr.substring(0, headerEnd);
                for (String line : headerPart.split("\r?\n")) {
                    if (line.toLowerCase().contains("content-type:")) {
                        String contentType = line.split(":")[1].trim();
                        response.setHeader("Content-Type", contentType);
                    }
                }
                byte[] bodyOnly = java.util.Arrays.copyOfRange(output, headerEnd + separatorLen, output.length);
                response.setBody(bodyOnly);
            } else {
                response.setBody(output);
            }
            return response;
            
        } catch (Exception e) {
            e.printStackTrace();
            HttpResponse response = new HttpResponse();
            response.setStatusCode(500, "Internal Server Error");
            response.setBody("CGI execution failed: " + e.getMessage());
            return response;
        }
    }
}

