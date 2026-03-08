# 🚀 Java LocalServer: The Educational Deep Dive

Welcome to the **Java LocalServer** project! This isn't just a server; it's a fully transparent, from-scratch educational journey into how the web works. We've built an HTTP/1.1 web server using *pure Java 11*—zero external dependencies, no Spring, no Tomcat, not even a JSON parser library.

Whether you're a CS student, a curious beginner, or an experienced dev going back to basics, this guide will pull back the curtain on the magic of web servers.

---

## 1. Project Overview & Architecture

### High-Level System Architecture
Imagine a bustling restaurant. 
- **The Restaurant Front Door** is the `ServerSocketChannel` listening on a port (e.g., 8080).
- **The Maitre D'** is the `Selector` in `Server.java`, efficiently managing multiple waiting customers.
- **The Waiter** is `ConnectionHandler.java`. This is our specialized connection module. It takes the raw orders (bytes), parses them into a proper menu item (`HttpRequest`), and eventually brings the plate back to the table (`HttpResponse`).
- **The Kitchen/Chef** is `Router.java`. This is our server logic module. It decides exactly how to fulfill the request—whether it's grabbing a pre-made item (static file), cooking something fresh (CGI), or handling a new delivery (POST upload).

### Seamless Module Integration
Our architecture strictly separates **Connection Management** from **Business Logic**:
1. `Server.java` (The Acceptor) accepts new connections and registers them.
2. `ConnectionHandler.java` (The Peer Module) manages the state of the socket, buffers bytes, and handles the HTTP keep-alive lifecycle.
3. `Router.java` (The Logic Module) is invoked by the handler to generate the actual response content based on the configuration.

### How it Fits into the Client-Server Model
When you type `http://localhost:8080` into your browser, your browser (the **Client**) establishes a TCP connection to our server. It sends a text-based HTTP request. Our **Java LocalServer** reads that text, parses it, finds the requested resource, and sends back an HTTP response containing headers (like `Content-Type: text/html`) and the raw bytes of the file.

### Key Technologies & Why We Chose Them
- **Java NIO (Non-blocking I/O):** Instead of the traditional "one thread per connection" model (which consumes massive memory for idle connections), we use a single thread with an event loop (`Selector`). 
  - *Why This Matters:* It allows our server to handle thousands of concurrent connections using almost zero RAM, just like Nginx or Node.js!
- **Zero Dependencies:** We parse JSON, HTTP headers, and Multipart-Form data entirely by hand.
  - *Why This Matters:* It forces us to understand the underlying RFC specs. No "black box" magic.

---

## 2. Deep Code Explanation - File by File

### `Main.java`
**Purpose:** The entry point. It reads the command-line argument for the config file and starts the server.
**Under the Hood:** 
```java
Server server = new Server(configPath);
server.start();
```
*Why This Matters:* It separates configuration loading from the actual event loop execution, adhering to the Single Responsibility Principle.

### `Server.java`
**Purpose:** The beating heart of the application. It manages the NIO `Selector`, accepts connections, reads incoming bytes, and writes outgoing responses.
**Deep Dive (`start` method):**
```java
selector = Selector.open();
// ... setup server channels ...
while (true) {
    int readyChannels = selector.select(1000); 
    // ...
}
```
- *Line-by-line:* `selector.select(1000)` pauses the thread for up to 1 second waiting for network activity. If someone connects, sends data, or is ready to receive data, it wakes up.
- *Why This Matters:* This is the famous **Event Loop**. It prevents the CPU from spinning at 100% while idle, but instantly reacts to traffic.

### `Router.java`
**Purpose:** The decision-maker. Takes a parsed `HttpRequest` and returns an `HttpResponse`.
**Deep Dive (`handle` method):**
It checks exact routes, methods (GET/POST/DELETE), handles multipart uploads, and serves static files.
- *Under the Hood:* If you request a directory, it checks for `index.html`. If missing and `listing: true` is set, it dynamically generates an HTML directory listing!

### `HttpRequest.java` & `HttpResponse.java`
**Purpose:** Object representations of raw HTTP text.
- `HttpRequest`: Contains a complex `appendData` method. 
  - *Why This Matters:* Because TCP doesn't guarantee you get the whole request in one packet, `appendData` buffers incoming bytes until it finds the `\r\n\r\n` (end of headers) and ensures the full `Content-Length` or `Chunked` body is received before processing.
- `HttpResponse`: Constructs the exact byte array required by the HTTP/1.1 spec to send back to the browser.

### `ConfigLoader.java`
**Purpose:** Reads `config.json`.
- *Design Pattern:* We built a custom JSON tokenizer using RegEx and brace-matching `findBlocks()`.
- *Why This Matters:* While a library like Jackson is safer for production, parsing JSON from scratch teaches you about abstract syntax trees and string manipulation.

### `CGIHandler.java`
**Purpose:** Executes external scripts (like Python) and pipes the HTTP request into them via Standard Input (stdin) and Environment Variables.

---

## 3. Execution Flow & Lifecycle

1. **Startup:** `Main` initializes `Server`. `Server` opens a `ServerSocketChannel` for every port defined in `config.json` and registers them with the `Selector` for `OP_ACCEPT` (waiting for connections).
2. **Acceptance:** A browser connects. The `Selector` wakes up, accepts the `SocketChannel`, sets it to non-blocking, and registers it for `OP_READ`.
3. **Reading:** Browser sends `GET / HTTP/1.1...`. The `Selector` detects `OP_READ`. We read bytes into `HttpRequest`. 
4. **Routing:** Once the request is fully received, `Router.java` maps the URI to a file or CGI script.
5. **Writing:** `HttpResponse` is generated. The channel is registered for `OP_WRITE`. In the next loop iteration, the server pumps the response bytes back to the browser.
6. **Teardown:** If the connection drops or the transfer is complete, the channel is closed and removed from the `Selector`.

---

## 4. Data Flow Diagrams

**Request Parsing Flow:**
```text
[Raw Bytes from TCP] --> Server.java (ByteBuffer)
                          |
                          v
[HttpRequest.java] --> 1. Find \r\n\r\n (Headers end)
                   --> 2. Parse Method, Path, Headers
                   --> 3. Read Body (Content-Length or Chunked)
                          |
                          v
[Router.java] <--- (Fully parsed HttpRequest object)
```

**Response Generation Flow:**
```text
[Router.java] --> Finds File/Executes CGI
                  |
                  v
[HttpResponse.java] <-- Adds StatusCode (200 OK)
                    <-- Adds Content-Type Header
                    <-- Attaches File Bytes to Body
                  |
                  v
[Server.java] --> Writes Headers Buffer -> Writes Body Buffer -> [Network]
```

---

## 5. Configuration Deep Dive

The `config.json` acts as the master blueprint.

```json
{
  "servers": [
    {
      "host": "127.0.0.1",
      "ports": [8080],
      "client_body_size_limit": 1048576, 
      "error_pages": { "404": "error_pages/404.html" },
      "routes": [ ... ]
    }
  ]
}
```
- `host`: The domain or IP the server responds to (Virtual Hosting).
- `ports`: Array of ports to listen on.
- `client_body_size_limit`: Maximum bytes allowed in a POST request. *Why This Matters:* Prevents malicious users from sending a 500GB file and crashing your server's RAM (OOM Exception).
- `routes[].root`: The physical folder on your hard drive mapping to this URL path.
- `routes[].listing`: Boolean. If true, generates a visual directory list when no `index.html` is found.
- `routes[].redirection`: URL to redirect the user to (returns a 301/302).

---

## 6. Build & Deployment Roadmap

### Prerequisites
- JDK 11 or higher.
- (Optional but included) Maven Wrapper (`./mvnw`).

### Compilation & Build
We use Maven with the `maven-shade-plugin`.
```bash
./mvnw clean package
```
*Why This Matters:* The `shade` plugin takes our compiled classes and packages them into a single, executable "Fat JAR". You don't need to specify the classpath or dependencies; you just run the JAR.

### Running the Server
```bash
java -jar target/java-localserver-1.0-SNAPSHOT.jar config.json
```

### Testing Framework
We have a comprehensive shell script suite for validation. You can run all tests via:
```bash
make test
# OR
./test.sh
```
This script automates:
- **Project Build:** Ensures the latest code is compiled.
- **Compliance Tests:** Validates core HTTP/1.1 compliance.
- **Integration Tests:** Checks CGI execution, large file transfers (10MB), and custom error pages.
- **Load Testing:** Uses `siege` to simulate 50 concurrent users over 10 seconds.
- **Server Lifecycle:** Automatically starts the server before tests and kills it afterwards.

---

## 7. Troubleshooting & Debugging Guide

| Error/Symptom | Root Cause | Fix |
| :--- | :--- | :--- |
| **"Address already in use"** | Another program (or a crashed instance of this server) is already using port 8080. | Run `lsof -i:8080` to find the PID, then `kill -9 <PID>`. Or change the port in `config.json`. |
| **"Payload Too Large (413)"** | You uploaded a file larger than `client_body_size_limit`. | Increase the limit in `config.json` (value is in bytes). |
| **CGI script downloading instead of running** | Missing `.py` in the route's `cgi` configuration block. | Ensure `"cgi": { ".py": "/usr/bin/python3" }` is correctly mapped in `config.json`. |
| **Server hangs on large files** | OS write buffers are full. | *Already fixed in our NIO loop!* Our `handleWrite` loops `clientChannel.write()` until the OS buffer is cleared without blocking the thread. |

---

## 8. Extension Guide

Want to add new features? Here is where you hook in:

- **Adding Custom Middleware (e.g., Authentication):**
  Open `Router.java`. Inside the `handle()` method, right after `findRoute()`, you can inspect `request.getHeaders().get("Authorization")`. If it fails, immediately return `errorResponse(401, "Unauthorized")`.
- **Adding Session Management:**
  Use the included `com.localserver.utils.Session` class. In your router, check for a `LOCALSERVER_SESSION` cookie. If missing, generate a new `Session`, add it to `HttpResponse.addCookie()`, and store user state in memory.
- **Adding WebSockets:**
  This requires upgrading the connection. In `Server.java` `handleRead`, intercept the `Upgrade: websocket` header, perform the SHA1 handshake, and switch the channel processing mode to read WebSocket frames instead of HTTP text.

---

## 📖 Glossary
- **NIO (Non-blocking I/O):** A way of reading network data where the thread doesn't pause waiting for data. If no data is there, it immediately moves on to check the next connection.
- **Selector:** A Java NIO class that watches dozens of sockets at once and alerts you when one is ready to read or write.
- **CGI (Common Gateway Interface):** An old-school but effective way to let a web server run external scripts (like Python or Bash) to generate dynamic HTML content.
- **Chunked Transfer Encoding:** A way of sending an HTTP body in pieces when the total size isn't known upfront.

---
*Built with ❤️ for educational purposes.*