package com.localserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ConnectionHandler {

    private final SocketChannel channel;
    private final ConfigLoader.ServerConfig config;

    // Reponse preparee, prete a etre envoyee
    private ByteBuffer responseBuffer;

    public ConnectionHandler(SocketChannel channel, ConfigLoader.ServerConfig config) {
        this.channel = channel;
        this.config  = config;
    }

    // Recoit des bytes du Selector, retourne true quand la requete est complete.
    public boolean process(ByteBuffer buffer) throws IOException {
        // Pour l'instant on renvoie une reponse hardcodee pour tester
        String response =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html\r\n" +
            "Content-Length: 13\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "Hello, World!";

        responseBuffer = ByteBuffer.wrap(response.getBytes());
        return true; // requete "complete" immediatement pour le test
    }

    // Envoie la reponse preparee. Retourne true quand tout est envoye
    public boolean writeResponse(SocketChannel ch) throws IOException {
        if (responseBuffer == null) return true;
        ch.write(responseBuffer);
        return !responseBuffer.hasRemaining(); // true = tout envoye
    }

    public boolean shouldKeepAlive() {
        return false; // TODO
    }

    public void reset() {
        responseBuffer = null;
    }
}