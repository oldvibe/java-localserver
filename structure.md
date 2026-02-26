# Local server 

```
localserver/
├── key_concepts.md
├── README.md
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com
│   │   │       └── localserver
│   │   │           ├── CGIHandler.java
│   │   │           ├── ConfigLoader.java
│   │   │           ├── ConnectionHandler.java
│   │   │           ├── ErrorHandler.java
│   │   │           ├── HttpRequest.java
│   │   │           ├── Main.java
│   │   │           ├── RequestParser.java
│   │   │           ├── ResponseBuilder.java
│   │   │           ├── Router.java
│   │   │           ├── Server.java
│   │   │           └── utils
│   │   │               ├── CookieUtils.java
│   │   │               ├── FileUtils.java
│   │   │               ├── Logger.java
│   │   │               └── SessionManager.java
│   │   └── resources
│   │       ├── config
│   │       │   └── server.conf
│   │       ├── error_pages
│   │       │   ├── 400.html
│   │       │   ├── 403.html
│   │       │   ├── 404.html
│   │       │   ├── 405.html
│   │       │   ├── 413.html
│   │       │   └── 500.html
│   │       └── www
│   │           └── index.html
│   └── test
│       └── java
│           └── com
│               └── localserver
│                   ├── ConfigTests.java
│                   ├── ErrorTests.java
│                   └── RoutingTests.java
└── structure.md
```
