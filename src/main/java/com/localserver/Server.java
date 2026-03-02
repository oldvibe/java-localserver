package com.localserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class Server {
    private Selector selector;
    private Config config;
    private String configPath;
    private java.util.Map<Integer, java.util.List<Router>> portRouters = new java.util.HashMap<>();
    private java.util.Set<Integer> boundPorts = new java.util.HashSet<>();

    public Server(String configPath) {
        this.configPath = configPath;
    }

    public void start() throws IOException {
        selector = Selector.open();
        config = ConfigLoader.loadConfig(configPath);

        for (Config.ServerConfig sc : config.servers) {
            Router router = new Router(sc);
            for (int port : sc.ports) {
                if (!boundPorts.contains(port)) {
                    try {
                        setupServerSocket(port);
                        boundPorts.add(port);
                        portRouters.put(port, new java.util.ArrayList<>());
                    } catch (IOException e) {
                        System.err.println("Error binding port " + port + ": " + e.getMessage());
                        continue; // Requirement: One bad config shouldn't bring down others
                    }
                }
                portRouters.get(port).add(router);
            }
        }

        System.out.println("Server is running. Waiting for connections...");

        while (true) {
            int readyChannels = selector.select(1000); // Wait up to 1 second
            
            if (readyChannels == 0) {
                // Check for timeouts only when idle
                long timeoutLimit = 60000;
                Iterator<SelectionKey> allKeys = selector.keys().iterator();
                while (allKeys.hasNext()) {
                    SelectionKey key = allKeys.next();
                    if (key.isValid() && key.attachment() instanceof ConnectionState) {
                        ConnectionState state = (ConnectionState) key.attachment();
                        if (state.isTimedOut(timeoutLimit)) {
                            System.out.println("Closing timed out connection");
                            com.localserver.utils.Metrics.activeConnections.decrementAndGet();
                            try { key.channel().close(); } catch (IOException ignored) {}
                            key.cancel();
                        }
                    }
                }
                continue;
            }

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();

            while (iter.hasNext()) {
                SelectionKey key = iter.next();

                if (key.isAcceptable()) {
                    acceptConnection(key);
                } else if (key.isReadable()) {
                    handleRead(key);
                } else if (key.isWritable()) {
                    handleWrite(key);
                }

                iter.remove();
            }
        }
    }

    private void setupServerSocket(int port) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT, port); // Attach port number
        System.out.println("Listening on port: " + port);
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        int port = (int) key.attachment();
        SocketChannel clientChannel = serverChannel.accept();
        clientChannel.configureBlocking(false);
        
        com.localserver.utils.Metrics.activeConnections.incrementAndGet();
        
        HttpRequest request = new HttpRequest();
        java.util.List<Router> routers = portRouters.get(port);
        
        ConnectionState state = new ConnectionState(request, routers);
        clientChannel.register(selector, SelectionKey.OP_READ, state);
        
        System.out.println("Accepted connection on port " + port + " from: " + clientChannel.getRemoteAddress());
    }

    private static class ConnectionState {
        HttpRequest request;
        java.util.List<Router> routers;
        Router selectedRouter;
        HttpResponse response;
        java.nio.ByteBuffer headerBuffer;
        java.nio.ByteBuffer bodyBuffer;
        long lastActivity;

        ConnectionState(HttpRequest request, java.util.List<Router> routers) {
            this.request = request;
            this.routers = routers;
            this.lastActivity = System.currentTimeMillis();
        }

        void updateActivity() {
            this.lastActivity = System.currentTimeMillis();
        }

        boolean isTimedOut(long timeoutMs) {
            return System.currentTimeMillis() - lastActivity > timeoutMs;
        }
    }

    private void handleRead(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        state.updateActivity();

        try {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4096);
            int bytesRead = clientChannel.read(buffer);

            if (bytesRead == -1) {
                com.localserver.utils.Metrics.activeConnections.decrementAndGet();
                clientChannel.close();
                return;
            }

            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            
            // Temporary limit check for headers
            if (state.request.appendData(data, 10000000)) {
                // If router not selected yet, find it based on Host header
                if (state.selectedRouter == null) {
                    String host = state.request.getHeaders().getOrDefault("Host", "").split(":")[0].trim();
                    for (Router r : state.routers) {
                        if (r.getServerConfig().host.equalsIgnoreCase(host)) {
                            state.selectedRouter = r;
                            break;
                        }
                    }
                    if (state.selectedRouter == null) state.selectedRouter = state.routers.get(0);
                }

                // Final check with actual limit
                if (state.request.getBody() != null && state.request.getBody().length > state.selectedRouter.getServerConfig().clientBodySizeLimit) {
                    state.response = new HttpResponse();
                    state.response.setStatusCode(413, "Payload Too Large");
                    state.response.setBody("<h1>413 Payload Too Large</h1>");
                    key.interestOps(SelectionKey.OP_WRITE);
                    return;
                }

                System.out.println("Received " + state.request.getMethod() + " request for " + state.request.getPath());
                state.response = state.selectedRouter.handle(state.request);
                key.interestOps(SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            try { clientChannel.close(); } catch (IOException ignored) {}
        }
    }

    private void handleWrite(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        state.updateActivity();

        if (state.response != null) {
            try {
                if (state.headerBuffer == null) {
                    state.headerBuffer = java.nio.ByteBuffer.wrap(state.response.getHeaderBytes());
                    byte[] body = state.response.getBody();
                    if (body != null) {
                        state.bodyBuffer = java.nio.ByteBuffer.wrap(body);
                    }
                }
                
                if (state.headerBuffer.hasRemaining()) {
                    while (state.headerBuffer.hasRemaining()) {
                        if (clientChannel.write(state.headerBuffer) == 0) return;
                    }
                }

                if (state.bodyBuffer != null && state.bodyBuffer.hasRemaining()) {
                    while (state.bodyBuffer.hasRemaining()) {
                        int written = clientChannel.write(state.bodyBuffer);
                        if (written == 0) {
                            return; // Buffer full, wait for next OP_WRITE
                        }
                    }
                }

                if (!state.headerBuffer.hasRemaining() && (state.bodyBuffer == null || !state.bodyBuffer.hasRemaining())) {
                    System.out.println("Response fully sent. Closing connection.");
                    com.localserver.utils.Metrics.activeConnections.decrementAndGet();
                    key.cancel();
                    clientChannel.close();
                }
            } catch (IOException e) {
                com.localserver.utils.Metrics.activeConnections.decrementAndGet();
                try { key.channel().close(); } catch (IOException ignored) {}
                key.cancel();
            }
        }
    }
}
