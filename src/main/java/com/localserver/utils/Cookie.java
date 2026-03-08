package com.localserver.utils;

import java.util.HashMap;
import java.util.Map;

public class Cookie {
    private String name;
    private String value;
    private Map<String, String> attributes = new HashMap<>();

    public Cookie(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public void setAttribute(String key, String value) {
        attributes.put(key, value);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("=").append(value);
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            sb.append("; ").append(entry.getKey());
            if (entry.getValue() != null) {
                sb.append("=").append(entry.getValue());
            }
        }
        return sb.toString();
    }

    public static Map<String, String> parse(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieHeader == null) return cookies;
        
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                cookies.put(kv[0].trim(), kv[1].trim());
            }
        }
        return cookies;
    }
}


