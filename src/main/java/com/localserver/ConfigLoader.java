package com.localserver;


import com.localserver.utils.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {
    private static final Logger log = Logger.getLogger(ConfigLoader.class);
    
    // Data Models — produits par les deux parsers
    public static class RouteConfig {
        public String path;
        public List<String> methods    = new ArrayList<>();
        public String root             = null;
        public String index            = null;
        public boolean listing         = false;
        public boolean upload          = false;
        public int redirectCode        = 0;
        public String redirectTarget   = null;
        public Map<String, String> cgiHandlers = new LinkedHashMap<>();

         // retourne vrai si cette route a au moins un CGI configure
        public boolean hasCgi() {
            return !cgiHandlers.isEmpty();
        }

        // retourne l'executable pour une extension donnee
        public String getCgiExecutable(String extension) {
            return cgiHandlers.get(extension);
        }

        @Override
        public String toString() {
            return "Route{path='" + path + "', methods=" + methods +
                   ", root='" + root + "', listing=" + listing +
                   ", upload=" + upload +
                   ", cgi=" + cgiHandlers +
                   ", redirect=" + redirectCode + "}";
        }
    }

    public static class ServerConfig {
        public String host                     = "0.0.0.0";
        public List<Integer> ports             = new ArrayList<>();
        public String serverName               = "localhost";
        public boolean isDefault               = false;
        public long clientMaxBodySize          = 1024 * 1024;
        public Map<Integer, String> errorPages = new HashMap<>();
        public List<RouteConfig> routes        = new ArrayList<>();

        public long clientBodySizeLimit() {
            return clientMaxBodySize;
        }

        @Override
        public String toString() {
            return "Server{host='" + host + "', ports=" + ports +
                   ", name='" + serverName + "', default=" + isDefault +
                   ", maxBody=" + clientMaxBodySize +
                   ", errorPages=" + errorPages +
                   ", routes=" + routes + "}";
        }
    }


    /// hady dyalyyyyyyyyy
    public static List<ServerConfig> load(String filePath) throws IOException {
        log.info("Loading configuration from: " + filePath);

        List<ServerConfig> servers;

        if (filePath.endsWith(".json")) {
            log.info("Detected JSON format");
            servers = loadJson(filePath);
        } else {
            log.info("Detected .conf format");
            servers = loadConf(filePath);
        }

        validate(servers);
        log.info("Configuration loaded: " + servers.size() + " server(s) found");
        return servers;
    }

    private static List<ServerConfig> loadConf(String filePath) throws IOException {
        List<String> lines = readLines(filePath);
        List<ServerConfig> servers = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (line.equals("server {")) {
                int[] end = {0};
                servers.add(parseConfServer(lines, i + 1, end));
                i = end[0];
            } else {
                i++;
            }
        }

        return servers;
    }

    private static ServerConfig parseConfServer(List<String> lines,
                                                 int start, int[] endIdx) {
        ServerConfig config = new ServerConfig();
        int i = start;

        while (i < lines.size()) {
            String line = lines.get(i).trim();

            if (line.equals("}")) {
                endIdx[0] = i + 1;
                return config;
            }

            if (line.startsWith("route ") && line.endsWith("{")) {
                String path = line.split("\\s+")[1];
                int[] end   = {0};
                RouteConfig route = parseConfRoute(lines, i + 1, end);
                route.path = path;
                config.routes.add(route);
                i = end[0];
                continue;
            }

            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) { i++; continue; }

            String key = parts[0];
            String val = parts[1];

            switch (key) {
                case "host":                    config.host = val; break;
                case "port":                    config.ports.add(Integer.parseInt(val)); break;
                case "server_name":             config.serverName = val; break;
                case "default":                 config.isDefault = val.equalsIgnoreCase("true"); break;
                case "client_max_body_size":
                case "client_body_size_limit":  config.clientMaxBodySize = parseSize(val); break;
                case "error_page": {
                    String[] ep = val.split("\\s+", 2);
                    if (ep.length == 2)
                        config.errorPages.put(Integer.parseInt(ep[0]), ep[1]);
                    break;
                }
                default:
                    log.warn("Unknown directive in server block: '" + key + "'");
            }

            i++;
        }

        log.warn("Reached end of file without closing } for server block");
        endIdx[0] = i;
        return config;
    }


    private static RouteConfig parseConfRoute(List<String> lines,
                                               int start, int[] endIdx) {
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
                case "methods":  route.methods.addAll(Arrays.asList(val.split("\\s+"))); break;
                case "root":     route.root = val; break;
                case "index":    route.index = val; break;
                case "listing":  route.listing = val.equalsIgnoreCase("on"); break;
                case "upload":   route.upload = val.equalsIgnoreCase("on"); break;
                case "cgi": {
                    // syntaxe : "cgi .py /usr/bin/python3"
                    String[] cp = val.split("\\s+", 2);
                    route.cgiHandlers.put(
                        cp[0],
                        cp.length == 2 ? cp[1] : detectExecutable(cp[0])
                    );
                    break;
                }
                case "redirect": {
                    String[] r = val.split("\\s+", 2);
                    if (r.length == 2) {
                        route.redirectCode   = Integer.parseInt(r[0]);
                        route.redirectTarget = r[1];
                    }
                    break;
                }
                default:
                    log.warn("Unknown directive in route block: '" + key + "'");
            }

            i++;
        }

        log.warn("Reached end of file without closing } for route block");
        endIdx[0] = i;
        return route;
    }

    // hady kany loadConfig o rdetha loadJson flmerge smohaaat 🫣
    /// hady dyaaalk nta Json wana config bl7a9 rah ghantl9aha chwiya mbdlaaa 
    

    private static List<ServerConfig> loadJson(String filePath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(filePath)));

        // Utiliser le parser JSON du teammate pour obtenir un objet Config
        Config rawConfig = parseJson(content);

        // Convertir Config.ServerConfig → ConfigLoader.ServerConfig
        List<ServerConfig> servers = new ArrayList<>();
        for (Config.ServerConfig raw : rawConfig.servers) {
            servers.add(convertFromRaw(raw));
        }

        return servers;
    }

    private static ServerConfig convertFromRaw(Config.ServerConfig raw) {
        ServerConfig sc = new ServerConfig();
        sc.host               = raw.host != null ? raw.host : "0.0.0.0";
        sc.serverName         = raw.host != null ? raw.host : "0.0.0.0";
        sc.ports              = raw.ports;
        sc.isDefault          = raw.isDefault;
        sc.clientMaxBodySize  = raw.clientBodySizeLimit > 0
                                ? raw.clientBodySizeLimit
                                : 1024 * 1024;
        sc.errorPages         = raw.errorPages;

        for (Config.RouteConfig rr : raw.routes) {
            RouteConfig route = new RouteConfig();
            route.path        = rr.path;
            route.methods     = rr.methods;
            route.root        = rr.root;
            route.index       = rr.index;
            route.listing     = rr.listing;
            route.cgiHandlers = rr.cgi;

            // Convertir le champ "redirection" du teammate
            // format : "301 /nouvelle-page" ou juste "/nouvelle-page" (302 par defaut)
            if (rr.redirection != null && !rr.redirection.isBlank()) {
                String[] parts = rr.redirection.split("\\s+", 2);
                try {
                    route.redirectCode   = Integer.parseInt(parts[0]);
                    route.redirectTarget = parts.length > 1 ? parts[1] : "/";
                } catch (NumberFormatException e) {
                    // Pas de code → 302 par defaut
                    route.redirectCode   = 302;
                    route.redirectTarget = parts[0];
                }
            }

            sc.routes.add(route);
        }

        return sc;
    }

    public static Config parseJson(String content) {
        Config config = new Config();
        
        int serversIndex = content.indexOf("\"servers\"");
        if (serversIndex == -1) return config;
        
        int arrayStart = content.indexOf("[", serversIndex);
        if (arrayStart == -1) return config;
        
        List<String> serverBlocks = findBlocks(content, arrayStart);
        
        for (String block : serverBlocks) {
            Config.ServerConfig sc = new Config.ServerConfig();
            sc.host = getString(block, "host");
            sc.ports = getIntList(block, "ports");
            sc.clientBodySizeLimit = getLong(block, "client_body_size_limit");
            sc.isDefault = getBoolean(block, "default");
            
            int routesIndex = block.indexOf("\"routes\"");
            if (routesIndex != -1) {
                int rArrayStart = block.indexOf("[", routesIndex);
                if (rArrayStart != -1) {
                    List<String> routeBlocks = findBlocks(block, rArrayStart);
                    for (String rBlock : routeBlocks) {
                        Config.RouteConfig rc = new Config.RouteConfig();
                        rc.path = getString(rBlock, "path");
                        rc.methods = getStringList(rBlock, "methods");
                        rc.root = getString(rBlock, "root");
                        rc.index = getString(rBlock, "index");
                        rc.listing = getBoolean(rBlock, "listing");
                        rc.redirection = getString(rBlock, "redirection");
                        rc.cgi         = getJsonStringMap(rBlock, "cgi");
                        sc.routes.add(rc);
                    }
                }
            }
            config.servers.add(sc);
        }
        
        log.info("JSON parser: loaded " + config.servers.size() + " server(s)");
        return config;
    }

    private static Map<String, String> getJsonStringMap(String block, String key) {
        Map<String, String> map = new LinkedHashMap<>();
        int keyIdx = block.indexOf("\"" + key + "\"");
        if (keyIdx == -1) return map;

        int objStart = block.indexOf("{", keyIdx);
        int objEnd   = block.indexOf("}", objStart);
        if (objStart == -1 || objEnd == -1) return map;

        String obj = block.substring(objStart + 1, objEnd);
        Pattern p  = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m  = p.matcher(obj);
        while (m.find()) map.put(m.group(1), m.group(2));
        return map;
    }

    private static List<String> findBlocks(String text, int startFrom) {
        List<String> blocks = new ArrayList<>();
        int braceCount = 0;
        int blockStart = -1;
        boolean inArray = false;
        
        for (int i = startFrom; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') inArray = true;
            else if (c == ']') {
                if (braceCount == 0) break; // End of main array
            }
            else if (c == '{') {
                if (braceCount == 0) blockStart = i;
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0 && blockStart != -1) {
                    blocks.add(text.substring(blockStart, i + 1));
                }
            }
        }
        return blocks;
    }

    private static String getString(String block, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1) : null;
    }

    private static List<Integer> getIntList(String block, String key) {
        List<Integer> list = new ArrayList<>();
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]");
        Matcher m = p.matcher(block);
        if (m.find()) {
            for (String s : m.group(1).split(",")) {
                list.add(Integer.parseInt(s.trim()));
            }
        }
        return list;
    }

    private static List<String> getStringList(String block, String key) {
        List<String> list = new ArrayList<>();
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]");
        Matcher m = p.matcher(block);
        if (m.find()) {
            for (String s : m.group(1).split(",")) {
                list.add(s.replace("\"", "").trim());
            }
        }
        return list;
    }

    private static boolean getBoolean(String block, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)");
        Matcher m = p.matcher(block);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private static long getLong(String block, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(block);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static List<String> readLines(String filePath) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#"))
                    lines.add(trimmed);
            }
        }
        return lines;
    }

    private static String detectExecutable(String extension) {
        switch (extension.toLowerCase()) {
            case ".py":  return "/usr/bin/python3";
            case ".php": return "/usr/bin/php";
            case ".rb":  return "/usr/bin/ruby";
            case ".pl":  return "/usr/bin/perl";
            default:     return extension;
        }
    }

    private static long parseSize(String val) {
        val = val.trim().toUpperCase();
        if (val.endsWith("G")) return Long.parseLong(val.replace("G", "")) * 1024 * 1024 * 1024;
        if (val.endsWith("M")) return Long.parseLong(val.replace("M", "")) * 1024 * 1024;
        if (val.endsWith("K")) return Long.parseLong(val.replace("K", "")) * 1024;
        return Long.parseLong(val);
    }


    private static void validate(List<ServerConfig> servers) {
        if (servers.isEmpty())
            throw new IllegalStateException("Config error: no server blocks found");

        for (ServerConfig s : servers) {
            if (s.ports.isEmpty())
                throw new IllegalStateException(
                    "Config error: server '" + s.serverName + "' has no port defined");

            for (int port : s.ports)
                if (port < 1 || port > 65535)
                    throw new IllegalStateException("Config error: invalid port " + port);
        }

        long defaultCount = servers.stream().filter(s -> s.isDefault).count();
        if (defaultCount > 1)
            throw new IllegalStateException(
                "Config error: more than one server marked as default");

        log.info("Config validation passed");
    }
}
