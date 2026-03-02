package com.localserver;

import java.io.IOException;
import java.util.List;

import com.localserver.utils.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class);

    public static void main(String[] args) {
        Logger.enableFileLogging("server.log");
        log.info("Server is starting...");


        // try {
        //     List<ConfigLoader.ServerConfig> configs =
        //         ConfigLoader.load("src/main/resources/config/server.conf");

        //     // Afficher ce qui a ete charge
        //     for (ConfigLoader.ServerConfig server : configs) {
        //         log.info("Loaded server: " + server);
        //     }

        // } catch (IOException e) {
        //     log.error("Failed to load config", e);
        // } catch (IllegalStateException e) {
        //     log.error("Invalid config: " + e.getMessage());
        // }

        try {
            List<ConfigLoader.ServerConfig> configs =
                ConfigLoader.load("src/main/resources/config/server.conf");

            // Pour l'instant on lance seulement le premier serveur
            Server server = new Server(configs.get(0));

            // Arret propre quand on fait Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

            server.start(); // bloque ici — c'est l'event loop

        } catch (IOException e) {
            log.error("Fatal error", e);
            System.exit(1);
        }
    }
}