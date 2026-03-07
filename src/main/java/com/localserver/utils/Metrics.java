package com.localserver.utils;

import java.util.concurrent.atomic.AtomicLong;

public class Metrics {
    public static final AtomicLong totalRequests = new AtomicLong(0);
    public static final AtomicLong activeConnections = new AtomicLong(0);
    public static final AtomicLong totalErrors = new AtomicLong(0);
    public static final long startTime = System.currentTimeMillis();

    public static String getJson() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        return String.format(
            "Uptime: %ds | Active: %d | Requests: %d | Errors: %d",
            uptime,
            activeConnections.get(),
            totalRequests.get(),
            totalErrors.get()
        );
    }
}
