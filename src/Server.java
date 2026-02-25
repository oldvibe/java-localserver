package src;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
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
            
            // Check for timeouts
            long timeoutLimit = 30000; // 30 seconds
            for (SelectionKey key : selector.keys()) {
                if (key.isValid() && key.attachment() instanceof ConnectionState) {
                    ConnectionState state = (ConnectionState) key.attachment();
                    if (state.isTimedOut(timeoutLimit)) {
                        System.out.println("Closing timed out connection");
                        src.utils.Metrics.activeConnections.decrementAndGet();
                        try { key.channel().close(); } catch (IOException ignored) {}
                        key.cancel();
                    }
                }
            }

            if (readyChannels == 0) continue;

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
        
        src.utils.Metrics.activeConnections.incrementAndGet();
        
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
                src.utils.Metrics.activeConnections.decrementAndGet();
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
                    String host = state.request.getHeaders().getOrDefault("Host", "").split(":")[0];
                    for (Router r : state.routers) {
                        if (r.getServerConfig().host.equals(host)) {
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
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(state.response.getBytes());
                clientChannel.write(buffer);
                src.utils.Metrics.activeConnections.decrementAndGet();
                clientChannel.close();
            } catch (IOException e) {
                src.utils.Metrics.activeConnections.decrementAndGet();
                try { clientChannel.close(); } catch (IOException ignored) {}
            }
        }
    }
}
