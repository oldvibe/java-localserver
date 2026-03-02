package com.localserver;


public class Main {
    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config.json";
        System.out.println("Starting LocalServer with config: " + configPath);

        try {
            // Initializing the server
            Server server = new Server(configPath);
            server.start();
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
