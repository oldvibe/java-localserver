import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class RequestParser {

    public static class HttpRequest {
        public String method;
        public String path;
        public String query;       // after '?' in URI
        public String version;
        public Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body;
        public boolean isChunked;
        public boolean isComplete;

        @Override
        public String toString() {
            return method + " " + path + " " + version +
                   "\nHeaders: " + headers +
                   "\nBody length: " + (body != null ? body.length : 0);
        }
    }

    // Internal state for incremental parsing (connection may send data in chunks)
    private final ByteBuffer buffer;
    private HttpRequest current;
    private ParserState state;
    private StringBuilder headerSection;

    private enum ParserState {
        READING_HEADERS,
        READING_BODY,
        DONE,
        ERROR
    }

    public RequestParser(int bufferSize) {
        this.buffer = ByteBuffer.allocate(bufferSize);
        this.state = ParserState.READING_HEADERS;
        this.headerSection = new StringBuilder();
        this.current = new HttpRequest();
    }

    /**
     * Feed raw bytes from the SocketChannel into the parser.
     * Returns the completed request if parsing is done, null if more data is needed.
     */
    public HttpRequest feed(byte[] data, int length) {
        headerSection.append(new String(data, 0, length, StandardCharsets.ISO_8859_1));

        if (state == ParserState.READING_HEADERS) {
            int headerEnd = headerSection.indexOf("\r\n\r\n");
            if (headerEnd == -1) return null; // need more data

            String rawHeaders = headerSection.substring(0, headerEnd);
            parseHeaders(rawHeaders);

            // Check if there's body data already buffered after the headers
            String afterHeaders = headerSection.substring(headerEnd + 4);

            if (state == ParserState.ERROR) return current;

            state = ParserState.READING_BODY;
            handleBody(afterHeaders.getBytes(StandardCharsets.ISO_8859_1));
        } else if (state == ParserState.READING_BODY) {
            handleBody(data);
        }

        if (state == ParserState.DONE) {
            current.isComplete = true;
            return current;
        }
        return null;
    }

    private void parseHeaders(String rawHeaders) {
        String[] lines = rawHeaders.split("\r\n");
        if (lines.length == 0) {
            state = ParserState.ERROR;
            return;
        }

        // --- Request Line ---
        // Example: "GET /index.html?foo=bar HTTP/1.1"
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length != 3) {
            state = ParserState.ERROR;
            return;
        }

        current.method  = requestLine[0].toUpperCase();
        current.version = requestLine[2];

        // Split URI into path and query string
        String uri = requestLine[1];
        int qMark = uri.indexOf('?');
        if (qMark != -1) {
            current.path  = uri.substring(0, qMark);
            current.query = uri.substring(qMark + 1);
        } else {
            current.path  = uri;
            current.query = "";
        }

        // --- Header Fields ---
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon == -1) continue; // malformed header, skip

            String name  = lines[i].substring(0, colon).trim().toLowerCase();
            String value = lines[i].substring(colon + 1).trim();
            current.headers.put(name, value);
        }

        // Detect chunked transfer encoding
        String te = current.headers.getOrDefault("transfer-encoding", "");
        current.isChunked = te.equalsIgnoreCase("chunked");
    }

    private void handleBody(byte[] incoming) {
        String method = current.method;

        // Methods that cannot have a body
        if (method.equals("GET") || method.equals("DELETE")) {
            state = ParserState.DONE;
            return;
        }

        if (current.isChunked) {
            current.body = decodeChunked(incoming);
            state = ParserState.DONE;
        } else {
            String clHeader = current.headers.get("content-length");
            if (clHeader == null) {
                // No content-length and not chunked: treat as empty body
                current.body = new byte[0];
                state = ParserState.DONE;
                return;
            }

            int contentLength = Integer.parseInt(clHeader.trim());
            if (incoming.length >= contentLength) {
                current.body = Arrays.copyOf(incoming, contentLength);
                state = ParserState.DONE;
            }
            // else: need more data, stay in READING_BODY
        }
    }

    /**
     * Decode a chunked transfer-encoded body.
     * Format: <hex-size>\r\n<data>\r\n ... 0\r\n\r\n
     */
    private byte[] decodeChunked(byte[] raw) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        List<Byte> result = new ArrayList<>();
        int pos = 0;

        while (pos < text.length()) {
            int lineEnd = text.indexOf("\r\n", pos);
            if (lineEnd == -1) break;

            int chunkSize = Integer.parseInt(text.substring(pos, lineEnd).trim(), 16);
            if (chunkSize == 0) break; // last chunk

            pos = lineEnd + 2;
            for (int i = pos; i < pos + chunkSize && i < text.length(); i++) {
                result.add((byte) text.charAt(i));
            }
            pos += chunkSize + 2; // skip chunk data + trailing \r\n
        }

        byte[] out = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) out[i] = result.get(i);
        return out;
    }

    public void reset() {
        state = ParserState.READING_HEADERS;
        headerSection = new StringBuilder();
        current = new HttpRequest();
    }
}



// ### `ConfigLoader.java`

// Your config drives everything. Here's a clean format and parser:

// **`config/server.conf`**
// ```
// server {
//     host        0.0.0.0
//     port        8080
//     port        8081
//     server_name localhost
//     default     true

//     error_page 400 /error_pages/400.html
//     error_page 403 /error_pages/403.html
//     error_page 404 /error_pages/404.html
//     error_page 405 /error_pages/405.html
//     error_page 413 /error_pages/413.html
//     error_page 500 /error_pages/500.html

//     client_max_body_size 10M

//     route / {
//         methods     GET POST
//         root        ./www
//         index       index.html
//         listing     off
//     }

//     route /upload {
//         methods     POST
//         root        ./www/uploads
//         upload      on
//     }

//     route /api {
//         methods     GET POST DELETE
//         root        ./www/api
//         cgi         .py
//     }

//     route /old {
//         redirect    301 /new
//     }
// }