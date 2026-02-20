# Local server 

```
localserver/
├── src/
│   ├── Main.java                # Application entry point
│   ├── Server.java              # Server lifecycle and event loop
│   ├── ConnectionHandler.java   # Client connection management
│   ├── RequestParser.java       # HTTP request parsing
│   ├── ResponseBuilder.java     # HTTP response construction
│   ├── Router.java              # Route resolution and dispatching
│   ├── CGIHandler.java          # CGI execution logic
│   ├── ConfigLoader.java        # Configuration file parsing
│   ├── ErrorHandler.java        # Error response generation
|   ├── HttpRequest.java
│   └── utils/
│       ├── SessionManager.java  # Session handling
│       ├── CookieUtils.java     # Cookie parsing and creation
│       ├── FileUtils.java       # File and directory utilities
│       └── Logger.java          # Logging utilities
├── config/
│   └── server.conf              # Server configuration file
├── error_pages/
│   ├── 400.html
│   ├── 403.html
│   ├── 404.html
│   ├── 405.html
│   ├── 413.html
│   └── 500.html
├── www/
│   └── index.html               # Static content root
├── tests/
│   ├── ConfigTests.java
│   ├── RoutingTests.java
│   ├── ErrorTests.java
│   └── StressTests.md
└── README.md
```
