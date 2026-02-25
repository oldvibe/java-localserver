package src.utils;

import java.util.concurrent.atomic.AtomicLong;

public class Metrics {
    public static final AtomicLong totalRequests = new AtomicLong(0);
    public static final AtomicLong activeConnections = new AtomicLong(0);
    public static final long startTime = System.currentTimeMillis();

    public static String getJson() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        return "{" +
                "\"total_requests\":" + totalRequests.get() + "," +
                "\"active_connections\":" + activeConnections.get() + "," +
                "\"uptime_seconds\":" + uptime +
                "}";
    }
}
