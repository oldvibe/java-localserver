package com.localserver;

import com.localserver.utils.Logger;

import java.io.*;
import java.util.*;

public class ConfigLoader {

    private static final Logger log = Logger.getLogger(ConfigLoader.class);

    // -------------------------------------------------------------------------
    // ce que le parser produit
    // -------------------------------------------------------------------------

    public static class RouteConfig {
        public String path;
        public List<String> methods    = new ArrayList<>();
        public String root             = null;
        public String index            = null;
        public boolean listing         = false;
        public boolean upload          = false;
        public String cgiExtension     = null;
        public int redirectCode        = 0;
        public String redirectTarget   = null;

        @Override
        public String toString() {
            return "Route{path='" + path + "', methods=" + methods +
                   ", root='" + root + "', listing=" + listing +
                   ", upload=" + upload + ", redirect=" + redirectCode + "}";
        }
    }

    public static class ServerConfig {
        public String host                    = "0.0.0.0";
        public List<Integer> ports            = new ArrayList<>();
        public String serverName              = "localhost";
        public boolean isDefault              = false;
        public long clientMaxBodySize         = 1024 * 1024; // 1MB par defaut
        public Map<Integer, String> errorPages = new HashMap<>();
        public List<RouteConfig> routes       = new ArrayList<>();

        @Override
        public String toString() {
            return "Server{host='" + host + "', ports=" + ports +
                   ", name='" + serverName + "', default=" + isDefault +
                   ", maxBody=" + clientMaxBodySize +
                   ", errorPages=" + errorPages +
                   ", routes=" + routes + "}";
        }
    }

    // -------------------------------------------------------------------------
    // Point d'entree public
    // -------------------------------------------------------------------------

    /**
     * Charge et valide le fichier de config.
     * Lance une RuntimeException si la config est invalide.
     */
    public static List<ServerConfig> load(String filePath) throws IOException {
        log.info("Loading configuration from: " + filePath);

        List<String> lines = readLines(filePath);
        List<ServerConfig> servers = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();

            if (line.equals("server {")) {
                // parseServer() retourne la config ET met a jour
                // la position courante via le tableau end[]
                int[] end = {0};
                ServerConfig config = parseServer(lines, i + 1, end);
                servers.add(config);
                i = end[0]; // on reprend apres le } fermant du bloc server
            } else {
                i++;
            }
        }

        validate(servers);
        log.info("Configuration loaded: " + servers.size() + " server(s) found");
        return servers;
    }

    // -------------------------------------------------------------------------
    // Parsing d'un bloc server { ... }
    // -------------------------------------------------------------------------

    private static ServerConfig parseServer(List<String> lines, int start, int[] endIdx) {
        ServerConfig config = new ServerConfig();
        int i = start;

        while (i < lines.size()) {
            String line = lines.get(i).trim();

            // Fin du bloc server
            if (line.equals("}")) {
                endIdx[0] = i + 1;
                return config;
            }

            // Debut d'un bloc route
            // line ressemble a : "route /upload {"
            if (line.startsWith("route ") && line.endsWith("{")) {
                // split(" ") sur "route /upload {" donne ["route", "/upload", "{"]
                String path = line.split("\\s+")[1];
                int[] end = {0};
                RouteConfig route = parseRoute(lines, i + 1, end);
                route.path = path;
                config.routes.add(route);
                i = end[0]; // reprendre apres le } de la route
                continue;
            }

            // Directive simple : "cle valeur"
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) { i++; continue; }

            String key = parts[0];
            String val = parts[1];

            switch (key) {
                case "host":
                    config.host = val;
                    break;

                case "port":
                    config.ports.add(Integer.parseInt(val));
                    break;

                case "server_name":
                    config.serverName = val;
                    break;

                case "default":
                    config.isDefault = val.equalsIgnoreCase("true");
                    break;

                case "client_max_body_size":
                    config.clientMaxBodySize = parseSize(val);
                    break;

                case "error_page":
                    // val = "404 /error_pages/404.html"
                    String[] ep = val.split("\\s+", 2);
                    if (ep.length == 2) {
                        config.errorPages.put(Integer.parseInt(ep[0]), ep[1]);
                    }
                    break;

                default:
                    log.warn("Unknown directive in server block: '" + key + "'");
            }

            i++;
        }

        // Si on arrive ici, il manque un } fermant
        log.warn("Reached end of file without closing } for server block");
        endIdx[0] = i;
        return config;
    }

    // -------------------------------------------------------------------------
    // Parsing d'un bloc route { ... }
    // -------------------------------------------------------------------------

    private static RouteConfig parseRoute(List<String> lines, int start, int[] endIdx) {
        RouteConfig route = new RouteConfig();
        int i = start;

        while (i < lines.size()) {
            String line = lines.get(i).trim();

            if (line.equals("}")) {
                endIdx[0] = i + 1;
                return route;
            }

            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) { i++; continue; }

            String key = parts[0];
            String val = parts[1];

            switch (key) {
                case "methods":
                    // val = "GET POST DELETE" → on split sur les espaces
                    route.methods.addAll(Arrays.asList(val.split("\\s+")));
                    break;

                case "root":
                    route.root = val;
                    break;

                case "index":
                    route.index = val;
                    break;

                case "listing":
                    route.listing = val.equalsIgnoreCase("on");
                    break;

                case "upload":
                    route.upload = val.equalsIgnoreCase("on");
                    break;

                case "cgi":
                    route.cgiExtension = val; // ex: ".py"
                    break;

                case "redirect":
                    // val = "301 /nouvelle-page"
                    String[] r = val.split("\\s+", 2);
                    if (r.length == 2) {
                        route.redirectCode   = Integer.parseInt(r[0]);
                        route.redirectTarget = r[1];
                    }
                    break;

                default:
                    log.warn("Unknown directive in route block: '" + key + "'");
            }

            i++;
        }

        log.warn("Reached end of file without closing } for route block");
        endIdx[0] = i;
        return route;
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    /**
     * Lit le fichier ligne par ligne en ignorant les lignes vides
     * et les commentaires (commencant par #).
     */
    private static List<String> readLines(String filePath) throws IOException {
        List<String> lines = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        }

        return lines;
    }

    /**
     * Convertit "10M", "512K", "1G" en bytes.
     * Permet d'ecrire des tailles lisibles dans la config.
     */
    private static long parseSize(String val) {
        val = val.trim().toUpperCase();

        if (val.endsWith("G"))
            return Long.parseLong(val.replace("G", "")) * 1024 * 1024 * 1024;
        if (val.endsWith("M"))
            return Long.parseLong(val.replace("M", "")) * 1024 * 1024;
        if (val.endsWith("K"))
            return Long.parseLong(val.replace("K", "")) * 1024;

        return Long.parseLong(val); // deja en bytes
    }

    /**
     * Valide la config au demarrage — fail fast.
     * Vaut mieux crasher immediatement avec un message clair
     * que de crasher mysterieusement plus tard.
     */
    private static void validate(List<ServerConfig> servers) {
        if (servers.isEmpty())
            throw new IllegalStateException("Config error: no server blocks found");

        for (ServerConfig s : servers) {
            if (s.ports.isEmpty())
                throw new IllegalStateException(
                    "Config error: server '" + s.serverName + "' has no port defined");

            for (int port : s.ports) {
                if (port < 1 || port > 65535)
                    throw new IllegalStateException(
                        "Config error: invalid port " + port);
            }
        }

        long defaultCount = servers.stream().filter(s -> s.isDefault).count();
        if (defaultCount > 1)
            throw new IllegalStateException(
                "Config error: more than one server marked as default");

        log.info("Config validation passed");
    }
}