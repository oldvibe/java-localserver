package com.localserver;

import com.localserver.utils.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class RequestParser {

    private static final Logger log = Logger.getLogger(RequestParser.class);

    // -------------------------------------------------------------------------
    // Point d'entree unique
    // -------------------------------------------------------------------------

    /**
     * Parse une section headers brute (tout ce qui est avant \r\n\r\n)
     * et retourne un HttpRequest structure.
     *
     * Retourne null si la requete est malformee.
     */
    public static HttpRequest parse(String headerSection) {
        String[] lines = headerSection.split("\r\n");

        if (lines.length == 0 || lines[0].isBlank()) {
            log.warn("Empty request received");
            return null;
        }

        HttpRequest req = new HttpRequest();

        // Etape 1 : parser la request line
        if (!parseRequestLine(lines[0], req)) return null;

        // Etape 2 : parser les headers
        parseHeaders(lines, req);

        // Etape 3 : valider
        if (!validate(req)) return null;

        return req;
    }

    // -------------------------------------------------------------------------
    // Etape 1 : Request Line
    // -------------------------------------------------------------------------

    /**
     * Parse "GET /path?query HTTP/1.1"
     *
     * Le split(" ", 3) est important :
     * - sans le 3, un espace dans le path casserait tout
     * - avec le 3, on obtient exactement [method, uri, version]
     */
    private static boolean parseRequestLine(String line, HttpRequest req) {
        String[] parts = line.split(" ", 3);

        if (parts.length != 3) {
            log.warn("Malformed request line: '" + line + "'");
            return false;
        }

        req.method  = parts[0].toUpperCase();
        req.version = parts[2].trim();

        // Separer le path de la query string
        // "/search?q=hello&page=2" → path="/search", query="q=hello&page=2"
        String uri  = parts[1];
        int qMark   = uri.indexOf('?');

        if (qMark != -1) {
            req.path  = uri.substring(0, qMark);
            req.queryString = uri.substring(qMark + 1);
        } else {
            req.path  = uri;
            req.queryString = "";
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Etape 2 : Headers
    // -------------------------------------------------------------------------

    /**
     * Parse toutes les lignes de headers apres la request line.
     *
     * Format d'un header : "Nom: valeur"
     * - Le nom est case-insensitive → on le met en lowercase
     * - La valeur peut contenir des ":" (ex: Authorization: Bearer token:xyz)
     *   donc on split seulement sur le premier ":"
     */
    private static void parseHeaders(String[] lines, HttpRequest req) {
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];

            // Ligne vide = fin des headers
            if (line.isBlank()) break;

            int colon = line.indexOf(':');
            if (colon == -1) {
                log.warn("Skipping malformed header: '" + line + "'");
                continue;
            }

            String name  = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            // Si le header existe deja, on concatene avec une virgule
            // (comportement standard HTTP/1.1 pour les headers dupliques)
            req.headers.merge(name, value, (existing, newVal) -> existing + ", " + newVal);

            // Gestion des cookies
            if (name.equals("cookie")) {
                req.cookies.putAll(com.localserver.utils.Cookie.parse(value));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Etape 3 : Validation
    // -------------------------------------------------------------------------

    private static boolean validate(HttpRequest req) {
        // Methodes supportees par notre serveur
        List<String> supported = Arrays.asList("GET", "POST", "DELETE", "HEAD");

        log.info("Validating method: " + req.method);
        if (!supported.contains(req.method)) {
            log.warn("Unsupported method: " + req.method);
            // On ne retourne pas false ici — on laisse ErrorHandler
            // renvoyer un 405 avec le bon header Allow:
            // On marque juste dans la requete
            req.methodNotAllowed = true;
        }

        // HTTP version — on supporte seulement 1.0 et 1.1
        if (!req.version.equals("HTTP/1.1") && !req.version.equals("HTTP/1.0")) {
            log.warn("Unsupported HTTP version: " + req.version);
            return false;
        }

        // Le header Host est obligatoire en HTTP/1.1
        if (req.version.equals("HTTP/1.1") && !req.headers.containsKey("host")) {
            log.warn("Missing Host header");
            return false;
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Body : chunked decoding
    // -------------------------------------------------------------------------

    /**
     * Decode un body en Transfer-Encoding: chunked.
     *
     * Format :
     *   1a\r\n          ← taille du chunk en hexadecimal
     *   <26 bytes>\r\n  ← donnees
     *   5\r\n
     *   hello\r\n
     *   0\r\n           ← chunk final de taille 0
     *   \r\n
     */
    public static byte[] decodeChunked(String raw) {
        StringBuilder result = new StringBuilder();
        int pos = 0;

        while (pos < raw.length()) {
            int lineEnd = raw.indexOf("\r\n", pos);
            if (lineEnd == -1) break;

            String sizeLine = raw.substring(pos, lineEnd).trim();
            if (sizeLine.isEmpty()) break;

            int chunkSize;
            try {
                // Les tailles de chunk sont en hexadecimal
                chunkSize = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                log.warn("Invalid chunk size: '" + sizeLine + "'");
                break;
            }

            if (chunkSize == 0) break; // chunk final

            pos = lineEnd + 2;

            if (pos + chunkSize > raw.length()) break;

            result.append(raw, pos, pos + chunkSize);
            pos += chunkSize + 2; // +2 pour le \r\n apres les donnees
        }

        return result.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}