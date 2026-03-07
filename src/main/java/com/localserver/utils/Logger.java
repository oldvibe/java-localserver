package com.localserver.utils;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {

    public enum Level {
        DEBUG,   // details internes utiles pendant le developpement
        INFO,    // evenements normaux
        WARN,    // quelque chose d'anormal mais pas fatal
        ERROR    // quelque chose a vraiment mal tourne
    }

    private static Level currentLevel = Level.DEBUG;

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static PrintWriter fileWriter = null;

    private final String className;

    private Logger(String className) {
        this.className = className;
    }

    public static Logger getLogger(Class<?> clazz) {
        return new Logger(clazz.getSimpleName());
    }

    /**
     * Configure le niveau minimum de log.
     * Appele une fois au demarrage depuis Main.java
     */
    public static void setLevel(Level level) {
        currentLevel = level;
    }

    // Active l'ecriture des logs dans un fichier.
    // Appele une fois au demarrage si on veut persister les logs

    public static void enableFileLogging(String filePath) {
        try {
            fileWriter = new PrintWriter(new FileWriter(filePath, true), true);
        } catch (IOException e) {
            System.err.println("[WARN] Could not open log file: " + filePath);
        }
    }

    public void debug(String message) {
        log(Level.DEBUG, message);
    }

    public void info(String message) {
        log(Level.INFO, message);
    }

    public void warn(String message) {
        log(Level.WARN, message);
    }

    public void error(String message) {
        log(Level.ERROR, message);
    }

    public void error(String message, Throwable t) {
        log(Level.ERROR, message + " — " + t.getMessage());
        // Affiche le stack trace complet pour les erreurs
        t.printStackTrace(System.out);
        if (fileWriter != null) t.printStackTrace(fileWriter);
    }

    private void log(Level level, String message) {
        if (level.ordinal() < currentLevel.ordinal()) return;

        String timestamp = LocalDateTime.now().format(FORMATTER);
        String line = String.format("[%s] [%-5s] [%s] %s",
            timestamp,   
            level,     
            className,   // Server / ConnectionHandler / ...
            message
        );

        System.out.println(line);

        if (fileWriter != null) {
            fileWriter.println(line);
        }
    }
}