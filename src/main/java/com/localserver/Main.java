package com.localserver;

import java.util.List;

import com.localserver.utils.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class);

    public static void main(String[] args) {
        Logger.enableFileLogging("server.log");
        Logger.setLevel(Logger.Level.DEBUG);

        String configPath = args.length > 0 ? args[0] : "config.json";
        
        log.info("Starting LocalServer with config: " + configPath);
        
        try {
            List<ConfigLoader.ServerConfig> configs = ConfigLoader.load(configPath);
            
            Server server = new Server(configs);
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            server.start();

        } catch (Exception e) {
            log.error("Fatal error", e);
            e.printStackTrace();
            System.exit(1);
        }
    }
}
