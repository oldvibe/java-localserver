package src;

import java.util.HashMap;
import java.util.Map;
import src.utils.Cookie;

public class HttpRequest {
    private String method;
    private String path;
    private String queryString = "";
    private String version;
    private Map<String, String> headers = new HashMap<>();
    private Map<String, String> cookies = new HashMap<>();
    private byte[] body;
    private boolean isComplete = false;

    private java.io.ByteArrayOutputStream rawData = new java.io.ByteArrayOutputStream();
    private boolean headersParsed = false;
    private int contentLength = -1;
    private boolean isChunked = false;
    private int currentChunkSize = -1;
    private java.io.ByteArrayOutputStream bodyCollector = new java.io.ByteArrayOutputStream();

    public boolean appendData(byte[] data, long limit) {
        if (rawData.size() + data.length > limit && limit > 0) {
            return false; // Should ideally throw a specific exception for 413
        }
        try {
            rawData.write(data);
        } catch (java.io.IOException e) {
            return false;
        }

        if (!headersParsed) {
            byte[] currentRaw = rawData.toByteArray();
            int headerEnd = findHeaderEnd(currentRaw);
            if (headerEnd != -1) {
                parseHeaders(new String(currentRaw, 0, headerEnd));
                headersParsed = true;
                
                String cl = headers.get("Content-Length");
                if (cl != null) {
                    contentLength = Integer.parseInt(cl.trim());
                } else if ("chunked".equalsIgnoreCase(headers.get("Transfer-Encoding"))) {
                    isChunked = true;
                    contentLength = -1; 
                } else {
                    contentLength = 0;
                }
            }
        }

        if (headersParsed) {
            if (isChunked) {
                return parseChunked();
            }
            if (contentLength == 0) return true;
            if (contentLength > 0) {
                byte[] currentRaw = rawData.toByteArray();
                int headerEnd = findHeaderEnd(currentRaw);
                int bodyReceived = currentRaw.length - (headerEnd + 4);
                if (bodyReceived >= contentLength) {
                    body = new byte[contentLength];
                    System.arraycopy(currentRaw, headerEnd + 4, body, 0, contentLength);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean parseChunked() {
        byte[] currentRaw = rawData.toByteArray();
        int headerEnd = findHeaderEnd(currentRaw);
        int offset = headerEnd + 4;
        
        // We need a way to track how much of rawData we've already processed for chunks
        // For simplicity in this implementation, we'll re-parse from the start of the body
        bodyCollector.reset();
        int pos = offset;
        
        while (pos < currentRaw.length) {
            int lineEnd = -1;
            for (int i = pos; i < currentRaw.length - 1; i++) {
                if (currentRaw[i] == '\r' && currentRaw[i+1] == '\n') {
                    lineEnd = i;
                    break;
                }
            }
            
            if (lineEnd == -1) return false; // Incomplete chunk size line
            
            String sizeHex = new String(currentRaw, pos, lineEnd - pos).split(";")[0].trim();
            int chunkSize = Integer.parseInt(sizeHex, 16);
            
            if (chunkSize == 0) {
                this.body = bodyCollector.toByteArray();
                return true;
            }
            
            int chunkDataStart = lineEnd + 2;
            if (currentRaw.length < chunkDataStart + chunkSize + 2) return false; // Incomplete chunk data
            
            bodyCollector.write(currentRaw, chunkDataStart, chunkSize);
            pos = chunkDataStart + chunkSize + 2; // Move past data + \r\n
        }
        
        return false;
    }

    private int findHeaderEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i+1] == '\n' && data[i+2] == '\r' && data[i+3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void parseHeaders(String rawHeaders) {
        String[] lines = rawHeaders.split("\r\n");
        if (lines.length == 0) return;

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length >= 3) {
            this.method = requestLine[0];
            String fullPath = requestLine[1];
            int queryIndex = fullPath.indexOf("?");
            if (queryIndex != -1) {
                this.path = fullPath.substring(0, queryIndex);
                this.queryString = fullPath.substring(queryIndex + 1);
            } else {
                this.path = fullPath;
                this.queryString = "";
            }
            this.version = requestLine[2];
        }

        for (int i = 1; i < lines.length; i++) {
            String[] header = lines[i].split(": ", 2);
            if (header.length == 2) {
                headers.put(header[0], header[1]);
                if (header[0].equalsIgnoreCase("Cookie")) {
                    cookies.putAll(Cookie.parse(header[1]));
                }
            }
        }
    }
    public Map<String, String> getCookies() { return cookies; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getQueryString() { return queryString; }
    public String getVersion() { return version; }
    public Map<String, String> getHeaders() { return headers; }
    public byte[] getBody() { return body; }
}
