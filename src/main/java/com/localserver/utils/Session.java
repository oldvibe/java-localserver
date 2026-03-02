package com.localserver.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Session {
    private static final Map<String, Session> activeSessions = new ConcurrentHashMap<>();
    private String id;
    private Map<String, Object> attributes = new HashMap<>();
    private long lastAccessed;

    private Session(String id) {
        this.id = id;
        this.lastAccessed = System.currentTimeMillis();
    }

    public static Session getOrCreate(String id) {
        if (id == null || !activeSessions.containsKey(id)) {
            String newId = UUID.randomUUID().toString();
            Session session = new Session(newId);
            activeSessions.put(newId, session);
            return session;
        }
        Session session = activeSessions.get(id);
        session.lastAccessed = System.currentTimeMillis();
        return session;
    }

    public String getId() { return id; }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Object getAttribute(String key) { return attributes.get(key); }
    
    public static void cleanup() {
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(entry -> now - entry.getValue().lastAccessed > 1800000); // 30 min
    }
}
