Key Concepts to Understand
1. The HTTP/1.1 Protocol (RFC 2616)
An HTTP request has a strict structure:
```METHOD /path HTTP/1.1\r\n
Header-Name: Header-Value\r\n
\r\n
[optional body]
```
The \r\n (CRLF) is not optional — it's the delimiter the spec defines. The blank line (\r\n\r\n) separates headers from body. You need to internalize this before writing a single line of parsing code.
2. Java NIO — Non-Blocking I/O
The project forbids threads per connection. Instead you use a Selector — a single thread that monitors multiple SocketChannels for readiness events (ACCEPT, READ, WRITE). This is the event loop pattern used by Node.js, Nginx, etc.
Key classes: Selector, ServerSocketChannel, SocketChannel, SelectionKey, ByteBuffer
3. The Event Loop Pattern
```while (true) {
    selector.select();           // blocks until something is ready
    for (SelectionKey key : selector.selectedKeys()) {
        if (key.isAcceptable()) // new connection
        if (key.isReadable())   // data to read
        if (key.isWritable())   // ready to send response
    }
}
```
4. CGI (Common Gateway Interface)
Your server spawns a subprocess (Python script, etc.) via ProcessBuilder, passes the request body via stdin, reads the response from stdout. Environment variables like PATH_INFO, REQUEST_METHOD, CONTENT_LENGTH carry request metadata.
5. Configuration Parsing
Your server.conf defines virtual servers, routes, limits. You parse it once at startup, validate it, and use it as the runtime source of truth for every decision.
