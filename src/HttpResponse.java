package src;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import src.utils.Cookie;

public class HttpResponse {
    private int statusCode = 200;
    private String statusMessage = "OK";
    private Map<String, String> headers = new HashMap<>();
    private List<Cookie> cookies = new ArrayList<>();
    private byte[] body;

    public HttpResponse() {
        headers.put("Server", "JavaLocalServer/1.0");
        headers.put("Content-Type", "text/html");
    }

    public void setStatusCode(int code, String message) {
        this.statusCode = code;
        this.statusMessage = message;
    }

    public void setHeader(String key, String value) {
        headers.put(key, value);
    }

    public void addCookie(Cookie cookie) {
        cookies.add(cookie);
    }

    public void setBody(String body) {
        this.body = body.getBytes();
        setHeader("Content-Length", String.valueOf(this.body.length));
    }

    public void setBody(byte[] body) {
        this.body = body;
        setHeader("Content-Length", String.valueOf(this.body.length));
    }

    public byte[] getBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        for (Cookie cookie : cookies) {
            sb.append("Set-Cookie: ").append(cookie.toString()).append("\r\n");
        }
        sb.append("\r\n");

        byte[] headerBytes = sb.toString().getBytes();
        if (body == null) return headerBytes;

        byte[] fullResponse = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
        System.arraycopy(body, 0, fullResponse, headerBytes.length, body.length);
        return fullResponse;
    }
}
