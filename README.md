# LocalServer

## Overview
LocalServer is a lightweight HTTP/1.1 server implemented in Java. The project focuses on understanding how the web works from the server side by building a custom, event-driven web server without relying on existing server frameworks.

The server is designed to handle static content, internal APIs, and CGI-based dynamic content while maintaining high availability, stability, and extensibility.

## Learning Objectives
This project enables the ability to:
- Design and implement a custom HTTP/1.1-compliant server in Java
- Work with non-blocking I/O using Java NIO
- Manually parse HTTP requests and construct HTTP responses
- Manage routing, error handling, uploads, and CGI execution
- Evaluate server performance, memory usage, and process safety under stress

## Technical Stack
- Java (Core Libraries only)
- java.net for networking
- java.nio for non-blocking, event-driven I/O

No external server frameworks or asynchronous runtimes are used.

## Features

### Core Server
- Single process and single thread
- Event-driven, non-blocking I/O
- Handles multiple ports and server instances
- Supports GET, POST, and DELETE methods
- HTTP/1.1-compliant request and response handling
- Chunked and unchunked request support
- Request timeout handling
- File uploads
- Cookie and session management

### Error Handling
Default error pages are provided for:
- 400 Bad Request
- 403 Forbidden
- 404 Not Found
- 405 Method Not Allowed
- 413 Payload Too Large
- 500 Internal Server Error

### CGI Support
- Execution of one CGI type (e.g. `.py`)
- Implemented using `ProcessBuilder`
- PATH_INFO environment variable support
- Correct handling of relative and absolute paths

### Configuration
The server behavior is defined through a configuration file supporting:
- Host and multiple ports
- Default server selection
- Custom error page paths
- Client body size limits
- Route definitions:
  - Allowed HTTP methods
  - Redirections
  - Root directories
  - Default index files
  - CGI configuration by file extension
  - Directory listing enable/disable

Regex support is not required.

## Testing
- Stress testing using `siege`
- Availability target: 99.5%
- Functional testing for routes, redirections, errors, and configuration
- Memory leak detection and resource cleanup validation

## Bonus Features
- Additional CGI handler
- Administrative dashboard or metrics endpoint

## Disclaimer
This project is intended for educational purposes only. Stress testing tools must not be used against third-party servers without explicit authorization.
