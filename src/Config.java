package src;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Config {
    public List<ServerConfig> servers = new ArrayList<>();

    public static class ServerConfig {
        public String host;
        public List<Integer> ports = new ArrayList<>();
        public boolean isDefault;
        public Map<Integer, String> errorPages = new HashMap<>();
        public long clientBodySizeLimit;
        public List<RouteConfig> routes = new ArrayList<>();
    }

    public static class RouteConfig {
        public String path;
        public List<String> methods = new ArrayList<>();
        public String redirection;
        public String root;
        public String index;
        public Map<String, String> cgi = new HashMap<>();
        public boolean listing;
    }
}
