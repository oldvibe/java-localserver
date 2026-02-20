import java.util.Map;

public class HttpRequest {

    private final String method;
    private final String path;
    private final String version;
    private final Map<String, String> headers;
    private final byte[] body;

    public HttpRequest(String method,
                       String path,
                       String version,
                       Map<String, String> headers,
                       byte[] body) {
        this.method = method;
        this.path = path;
        this.version = version;
        this.headers = headers;
        this.body = body;
    }

    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getVersion() { return version; }
    public Map<String, String> getHeaders() { return headers; }
    public byte[] getBody() { return body; }

    public String getHeader(String key) {
        return headers.get(key);
    }
}
