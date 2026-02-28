package com.localserver;

import com.localserver.utils.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public class Server {

    private static final Logger log = Logger.getLogger(Server.class);

    // Taille du buffer de lecture — 8KB par lecture
    private static final int BUFFER_SIZE = 8192;

    // La config de ce serveur (host, ports, routes, etc.)
    private final ConfigLoader.ServerConfig config;

    // Le Selector — le coeur de l'event loop
    // Il surveille tous les channels (serveur + clients)
    private Selector selector;

    // Un ServerSocketChannel par port ecoute
    // Ex: si config.ports = [8080, 8081], on aura 2 ServerSocketChannels
    private final List<ServerSocketChannel> serverChannels = new ArrayList<>();

    // Flag pour arreter proprement le serveur
    private volatile boolean running = false;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    public Server(ConfigLoader.ServerConfig config) {
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // Demarrage du serveur
    // -------------------------------------------------------------------------

    /**
     * Initialise le Selector, ouvre un ServerSocketChannel par port,
     * et lance l'event loop.
     */
    public void start() throws IOException {

        // 1. Creer le Selector — le "tableau de bord" central
        selector = Selector.open();

        // 2. Pour chaque port dans la config, ouvrir un ServerSocketChannel
        for (int port : config.ports) {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();

            // NON-BLOCKING : le channel ne bloque pas sur accept()
            // Sans ca, accept() attendrait indefiniment une connexion
            serverChannel.configureBlocking(false);

            // SO_REUSEADDR : permet de relancer le serveur immediatement
            // apres un crash sans attendre que l'OS libere le port
            serverChannel.socket().setReuseAddress(true);

            // Bind : associe ce channel a l'adresse host:port
            serverChannel.bind(new InetSocketAddress(config.host, port));

            // Enregistrer ce channel dans le Selector
            // OP_ACCEPT = "previens-moi quand un client veut se connecter"
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            serverChannels.add(serverChannel);
            log.info("Listening on " + config.host + ":" + port);
        }

        running = true;
        log.info("Server started — entering event loop");

        // 3. Lancer l'event loop
        eventLoop();
    }

    // -------------------------------------------------------------------------
    // L'Event Loop
    // -------------------------------------------------------------------------

    /**
     * Boucle principale du serveur.
     * Tourne indefiniment, traite les evenements au fur et a mesure.
     */
    private void eventLoop() throws IOException {

        // Buffer de lecture reutilise pour toutes les connexions
        // On l'alloue une seule fois pour eviter le GC pressure
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

        while (running) {
            // select() BLOQUE jusqu'a ce qu'au moins un channel soit pret
            // C'est le point d'attente central — le thread dort ici
            // quand il n'y a rien a faire
            int readyCount = selector.select(1000); // timeout 1s pour pouvoir verifier running

            if (readyCount == 0) continue; // timeout, rien a faire

            // Recuperer les cles des channels prets
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectedKeys.iterator();

            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();

                // IMPORTANT : retirer la cle du set immediatement
                // Si on ne le fait pas, elle sera retraitee au prochain select()
                iterator.remove();

                // Verifier que la cle est encore valide
                // (le client peut s'etre deconnecte entre-temps)
                if (!key.isValid()) continue;

                try {
                    if (key.isAcceptable()) {
                        // Nouveau client qui veut se connecter
                        handleAccept(key);

                    } else if (key.isReadable()) {
                        // Un client a envoye des donnees
                        handleRead(key, buffer);

                    } else if (key.isWritable()) {
                        // On peut envoyer une reponse a ce client
                        handleWrite(key);
                    }

                } catch (IOException e) {
                    // Ne jamais laisser une exception tuer l'event loop
                    // On ferme juste ce client et on continue
                    log.error("Error handling client, closing connection", e);
                    closeChannel(key);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gestion des evenements
    // -------------------------------------------------------------------------

    /**
     * OP_ACCEPT : un nouveau client veut se connecter.
     * On accepte la connexion et on enregistre le nouveau channel
     * dans le Selector pour surveiller ses donnees.
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();

        // accept() retourne le SocketChannel du client
        // Non-null car le Selector nous a dit que c'etait pret
        SocketChannel clientChannel = serverChannel.accept();

        if (clientChannel == null) return; // ne devrait pas arriver

        // Le channel client doit aussi etre non-bloquant
        clientChannel.configureBlocking(false);

        // Enregistrer ce client dans le Selector
        // OP_READ = "previens-moi quand ce client envoie des donnees"
        // On attache un objet ConnectionHandler a cette cle —
        // il sera recupere dans handleRead() pour ce client specifique
        ConnectionHandler handler = new ConnectionHandler(clientChannel, config);
        clientChannel.register(selector, SelectionKey.OP_READ, handler);

        log.debug("New connection from: " +
            clientChannel.getRemoteAddress());
    }

    /**
     * OP_READ : un client a envoye des donnees, on peut les lire.
     */
    private void handleRead(SelectionKey key, ByteBuffer buffer) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();

        // Recuperer le handler associe a ce client
        ConnectionHandler handler = (ConnectionHandler) key.attachment();

        // Preparer le buffer pour une nouvelle lecture
        buffer.clear();

        // Lire les donnees disponibles
        int bytesRead = clientChannel.read(buffer);

        if (bytesRead == -1) {
            // -1 = le client a ferme la connexion proprement
            log.debug("Client closed connection: " + clientChannel.getRemoteAddress());
            closeChannel(key);
            return;
        }

        if (bytesRead == 0) return; // rien a lire

        // Passer les bytes au ConnectionHandler
        // Il accumule les donnees et parse la requete HTTP
        buffer.flip(); // passer en mode lecture (limit = position, position = 0)
        boolean requestComplete = handler.process(buffer);

        if (requestComplete) {
            // La requete est complete — on est pret a envoyer une reponse
            // On change l'interet de ce channel : OP_WRITE
            // "previens-moi quand je peux ecrire vers ce client"
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    /**
     * OP_WRITE : le channel est pret, on envoie la reponse.
     */
    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ConnectionHandler handler = (ConnectionHandler) key.attachment();

        // Demander au handler d'envoyer sa reponse
        boolean done = handler.writeResponse(clientChannel);

        if (done) {
            // Reponse envoyee completement
            if (handler.shouldKeepAlive()) {
                // HTTP keep-alive : reutiliser la connexion pour la prochaine requete
                handler.reset();
                key.interestOps(SelectionKey.OP_READ);
            } else {
                // Fermer la connexion
                closeChannel(key);
            }
        }
        // Si pas done : la reponse est grosse, on reviendra au prochain cycle
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private void closeChannel(SelectionKey key) {
        key.cancel(); // retirer du Selector
        try {
            key.channel().close();
        } catch (IOException e) {
            log.error("Error closing channel", e);
        }
    }

    /**
     * Arret propre du serveur.
     */
    public void stop() {
        log.info("Stopping server...");
        running = false;

        try {
            for (ServerSocketChannel ch : serverChannels) ch.close();
            if (selector != null) selector.close();
        } catch (IOException e) {
            log.error("Error during shutdown", e);
        }

        log.info("Server stopped");
    }
}
