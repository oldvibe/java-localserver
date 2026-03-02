package com.localserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {
    public static Config loadConfig(String configPath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(configPath)));
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
                        
                        int cgiIndex = rBlock.indexOf("\"cgi\"");
                        if (cgiIndex != -1) {
                            int cgiObjStart = rBlock.indexOf("{", cgiIndex);
                            if (cgiObjStart != -1) {
                                String cgiContent = findBlocks(rBlock, cgiIndex).get(0); // This should be the object
                                // Simple parse of "key": "value"
                                Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
                                Matcher m = p.matcher(cgiContent);
                                while (m.find()) {
                                    rc.cgi.put(m.group(1), m.group(2));
                                }
                            }
                        }
                        sc.routes.add(rc);
                    }
                }
            }
            config.servers.add(sc);
        }
        
        System.out.println("Loaded " + config.servers.size() + " server configurations.");
        return config;
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
}
