# Java LocalServer

A high-performance, single-threaded, event-driven HTTP/1.1 server built from scratch using Java NIO.

## 🚀 Quick Start
1.  **Compile:** `make`
2.  **Run:** `make run`
3.  **Test:** `make test`

---

## 🗺️ Project Roadmap & Architecture

The server follows a **Reactor Pattern** using a single thread to handle thousands of concurrent connections.

### 1. The Core Lifecycle (The Reactor)
- **`Main.java`**: Entry point. Loads the configuration and initializes the `Server`.
- **`Server.java`**: The heart of the engine.
    - Uses `java.nio.channels.Selector` to monitor multiple sockets.
    - Loop: `select()` -> Identify ready channels -> Delegate to handlers.
    - **Non-blocking:** No thread ever waits for data; it only acts when data is ready.

### 2. Request Handling Pipeline
1.  **Accept:** `ServerSocketChannel` accepts a new connection and registers it with the Selector.
2.  **Read:** `HttpRequest.java` collects raw bytes. It handles:
    - **Header Parsing:** Splitting by `

`.
    - **Body Parsing:** Managing `Content-Length`.
    - **De-chunking:** Automatically decoding `Transfer-Encoding: chunked`.
3.  **Route:** `Router.java` decides what to do based on `config.json`.
    - Matches the URL path to a `RouteConfig`.
    - Selects the correct virtual host using the `Host` header.
4.  **Execute:**
    - **Static:** Reads files from the `root` directory.
    - **CGI:** `CGIHandler.java` spawns a process (Python/Bash) and pipes data.
    - **Metrics:** Serves internal state as JSON.
5.  **Write:** `HttpResponse.java` formats the headers and body into a raw byte stream and sends it back to the client.

---

## 🧠 Key Concepts You Must Know

### 1. I/O Multiplexing (The "Selector")
In traditional servers, each connection gets a thread. If you have 10,000 idle users, you have 10,000 threads wasting memory. 
**This project uses one thread.** The `Selector` tells the OS: "Wake me up only when one of these 10,000 sockets actually has data."

### 2. The State Machine (`ConnectionState`)
Because I/O is non-blocking, a large request might arrive in 100 small pieces. We use `ConnectionState` (attached to each `SelectionKey`) to remember "where we are" for each specific user between `select()` calls.

### 3. CGI (Common Gateway Interface)
CGI is the oldest way to run dynamic code.
- We set environment variables (`PATH_INFO`, `QUERY_STRING`).
- We use `ProcessBuilder` to run a script.
- We pipe the HTTP Body into the script's `stdin`.
- We read the script's `stdout` and send it as the HTTP Response.

### 4. Multipart Parsing (File Uploads)
Standard POST bodies are simple. Multipart is complex because it contains multiple files separated by a `boundary` string. This server scans for these boundaries at a **binary level** to prevent corrupting images or zip files.

---

## 🛠️ How to Update the Project

### Adding a New Route
Modify `config.json`. Add a new object to the `routes` array:
```json
{
  "path": "/api",
  "methods": ["GET"],
  "root": "data",
  "listing": false
}
```

### Adding a New CGI Language
1.  Update `config.json` under the `cgi` object:
    `".php": "/usr/bin/php-cgi"`
2.  The `CGIHandler` will automatically use that executable for files ending in `.php`.

### Customizing Error Pages
Add the status code to the `error_pages` map in `config.json` and ensure the file exists in the `error_pages/` directory.

---

## 📈 Monitoring
Visit `http://localhost:8080/metrics` to see:
- **Total Requests:** Lifetime request count.
- **Active Connections:** Current users connected.
- **Uptime:** How long the engine has been running.

---

## 🛡️ Safety & Reliability
- **Timeout Logic:** Connections idle for >30s are automatically pruned in `Server.java`.
- **Crash Proofing:** All I/O operations are wrapped in try-catch blocks; an error in one request will not kill the server.
- **Memory Safety:** `client_body_size_limit` is strictly enforced to prevent RAM exhaustion.
