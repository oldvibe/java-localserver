package com.localserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import com.localserver.utils.Logger;
import com.localserver.utils.Metrics;

import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public class Server {
    
    private Selector selector; // Il surveille tous les channels (serveur + clients)
    private static final Logger log = Logger.getLogger(Server.class);
    
    // buffer de lecture : 8KB par lecture
    private static final int BUFFER_SIZE = 8192; 

    // La config de ce serveur (host, ports, routes, etc.)
    private final ConfigLoader.ServerConfig config;

    // Mappe chaque port vers la liste des configs serveur qui l'ecoutent.
    // Utile pour le virtual hosting : plusieurs ServerConfig sur le meme port,
    // differencies par le header Host.
    private final Map<Integer, List<ConfigLoader.ServerConfig>> portConfigs = new HashMap<>();

    // Un ServerSocketChannel par port ecoute
    // Ex: si config.ports = [8080, 8081], on aura 2 ServerSocketChannels
    private final List<ServerSocketChannel> serverChannels = new ArrayList<>();

    private volatile boolean running = false;



    public Server(ConfigLoader.ServerConfig config) {
        this.config = config;
        for (int port : config.ports) {
            portConfigs.computeIfAbsent(port, k -> new ArrayList<>()).add(config);
        }
    }

    /**
     * Constructeur multi-serveur : toutes les configs sont passees d'un coup.
     * Permet a plusieurs ServerConfig de partager le meme port.
     */
    public Server(List<ConfigLoader.ServerConfig> configs) {
        // On garde une reference au premier comme config principale
        this.config = configs.get(0);

        // Construire la map port → [configs]
        for (ConfigLoader.ServerConfig sc : configs) {
            for (int port : sc.ports) {
                portConfigs.computeIfAbsent(port, k -> new ArrayList<>()).add(sc);
            }
        }
    }

    // Demarrage du serveur Initialise le Selector, ouvre un ServerSocketChannel par port, et lance l'event loop
    public void start() throws IOException {

        selector = Selector.open();

        // Si portConfigs est vide => on le remplit
        if (portConfigs.isEmpty()) {
            for (int port : config.ports) {
                portConfigs.computeIfAbsent(port, k -> new ArrayList<>()).add(config);
            }
        }

        // On bind chaque port independamment
        // Si un port echoue, on log et on continue — les autres ports restent actifs.
        for (int port : portConfigs.keySet()) {
            try {
                bindPort(port);
            } catch (IOException e) {
                log.error("Failed to bind port " + port + " — skipping: " + e.getMessage());
                // On continue
            }
        }

        if (serverChannels.isEmpty()) {
            throw new IOException("No ports could be bound — server cannot start");
        }
        
        running = true;
        log.info("Server started — entering event loop");
        log.info(Metrics.getJson());

        eventLoop();
        }

        private void bindPort(int port) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().setReuseAddress(true);
        serverChannel.bind(new InetSocketAddress(config.host, port));

        // On attache le numero de port a la cle pour le retrouver dans handleAccept
        serverChannel.register(selector, SelectionKey.OP_ACCEPT, port);
        serverChannels.add(serverChannel);
        log.info("Listening on " + config.host + ":" + port);
    }

    /**
     * Boucle principale du serveur
     * Tourne indefiniment, traite les evenements au fur et a mesure
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

            if (readyCount == 0) {
                checkTimeouts();
                continue;   
            } 

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
                    Metrics.totalErrors.incrementAndGet();
                    closeChannel(key);
                }
            }
        }
    }

    private void handleRead(SelectionKey key, ByteBuffer buffer) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ConnectionHandler handler   = (ConnectionHandler) key.attachment();

        buffer.clear();
        try {
            int bytesRead = clientChannel.read(buffer);

            if (bytesRead == -1) {
                com.localserver.utils.Metrics.activeConnections.decrementAndGet();
                clientChannel.close();
                return;
            }

            if (bytesRead == 0) return;

            buffer.flip();
            boolean requestComplete = handler.process(buffer);

            if (requestComplete) {
                Metrics.totalRequests.incrementAndGet();
                key.interestOps(SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            try { clientChannel.close(); } catch (IOException ignored) {}
        }
    }


    // OP_WRITE : le channel est pret, on envoie la reponse.
    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ConnectionHandler handler   = (ConnectionHandler) key.attachment();
        boolean done = handler.writeResponse(clientChannel);

        if (done) {
            if (handler.shouldKeepAlive()) {
                // Reutiliser la connexion pour la prochaine requete
                handler.reset();
                key.interestOps(SelectionKey.OP_READ);
            } else {
                closeChannel(key);
            }
        }
    }

    /**
     * OP_ACCEPT : un nouveau client veut se connecter.
     * On accepte la connexion et on enregistre le nouveau channel
     * dans le Selector pour surveiller ses donnees.
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();

        // On recupere le port depuis l'attachment pour trouver
        // quelle(s) config(s) correspondent a ce port
        int port = (int) key.attachment();
        // accept() retourne le SocketChannel du client
        // Non-null car le Selector nous a dit que c'etait pret
        SocketChannel clientChannel = serverChannel.accept();

        if (clientChannel == null) return; // ne devrait pas arriver

        clientChannel.configureBlocking(false);

        // Selectionner la bonne config pour ce port
        // (par defaut la premiere — le header Host sera analyse dans ConnectionHandler)
        List<ConfigLoader.ServerConfig> configs = portConfigs.getOrDefault(
            port, List.of(config)
        );
        ConfigLoader.ServerConfig selectedConfig = selectConfig(configs, null);
        // Enregistrer ce client dans le Selector
        // OP_READ = "previens-moi quand ce client envoie des donnees"
        // On attache un objet ConnectionHandler a cette cle —
        // il sera recupere dans handleRead() pour ce client specifique
        ConnectionHandler handler = new ConnectionHandler(clientChannel, configs, this);
        clientChannel.register(selector, SelectionKey.OP_READ, handler);

        Metrics.activeConnections.incrementAndGet();
        log.debug("New connection from: " + clientChannel.getRemoteAddress() +
                  " on port " + port +
                  " | Active: " + Metrics.activeConnections.get());
    }

     /**
     * Parmi les configs qui ecoutent sur ce port,
     * choisit celle dont le serverName correspond au header Host.
     * Si aucune ne correspond, retourne la config marquee default,
     * ou a defaut la premiere.
     */
    public ConfigLoader.ServerConfig selectConfig(
            List<ConfigLoader.ServerConfig> configs, String hostHeader) {
        if (configs.size() == 1) return configs.get(0);

        if (hostHeader != null) {
            // Retirer le port du header Host ("localhost:8080" → "localhost")
            String hostOnly = hostHeader.split(":")[0].trim().toLowerCase();

            for (ConfigLoader.ServerConfig sc : configs) {
                if (sc.serverName.equalsIgnoreCase(hostOnly)) return sc;
            }
        }

        // Fallback : config marquee default
        for (ConfigLoader.ServerConfig sc : configs) {
            if (sc.isDefault) return sc;
        }

        return configs.get(0);
    }

    /***************🌟 Close Channel 🌟**************/
    private void closeChannel(SelectionKey key) {
        if (key.attachment() instanceof ConnectionHandler) {
            Metrics.activeConnections.decrementAndGet();
        }
        try {
            key.channel().close();
        } catch (IOException e) {
            log.error("Error closing channel", e);
        }
    }

    
    /***************🌟 Stop Server 🌟**************/
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

    /**
     * Parcourt toutes les cles actives et ferme celles qui ont depasse le timeout d'inactivite.
     * Appele pendant les periodes idle de l'event loop.
     */
    private void checkTimeouts() {
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid()) continue;
            if (!(key.attachment() instanceof ConnectionHandler)) continue;

            ConnectionHandler handler = (ConnectionHandler) key.attachment();
            if (handler.isTimedOut()) {
                log.debug("Closing timed-out connection");
                closeChannel(key);
            }
        }
    }
}
