package com.localserver;

import java.util.List;

import com.localserver.utils.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class);

    public static void main(String[] args) {
        Logger.enableFileLogging("server.log");

        String configPath = args.length > 0 
            ? args[0] 
            // : "config.json";
            : "src/main/resources/config/server.conf";
        // System.out.println("Starting LocalServer with config: " + configPath);
        log.info("Starting LocalServer with config: " + configPath);
        
        try {
            List<ConfigLoader.ServerConfig> configs = ConfigLoader.load(configPath);
            
            // Initializing the server
            Server server = new Server(configs);
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            server.start();

        } catch (Exception e) {
            // System.err.println("Failed to start server: " + e.getMessage());
            log.error("Fatal error", e);
            e.printStackTrace();
            System.exit(1);
        }
    }
}
