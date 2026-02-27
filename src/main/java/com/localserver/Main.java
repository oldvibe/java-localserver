package com.localserver;

import java.io.IOException;
import java.util.List;

import com.localserver.utils.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class);

    public static void main(String[] args) {
        System.out.println("LocalServer starting...");
        Logger.enableFileLogging("server.log");

        // log.debug("Debug message — details internes");
        // log.info("Server is starting...");
        // log.warn("This is a warning");
        // log.error("Something went wrong");

        // // Test avec une exception
        // try {
        //     int result = 10 / 0;
        // } catch (Exception e) {
        //     log.error("Division by zero caught", e);
        // }

        try {
            List<ConfigLoader.ServerConfig> configs =
                ConfigLoader.load("src/main/resources/config/server.conf");

            // Afficher ce qui a ete charge
            for (ConfigLoader.ServerConfig server : configs) {
                log.info("Loaded server: " + server);
            }

        } catch (IOException e) {
            log.error("Failed to load config", e);
        } catch (IllegalStateException e) {
            log.error("Invalid config: " + e.getMessage());
        }
    }
}