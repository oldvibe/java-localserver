package com.localserver;

import java.util.HashMap;
import java.util.Map;

public class HttpRequest {
    public String method  = "";
    public String path    = "";
    public String query   = "";
    public String version = "";
    public Map<String, String> headers = new HashMap<>();
    public byte[] body = new byte[0];
    public boolean methodNotAllowed = false;
}