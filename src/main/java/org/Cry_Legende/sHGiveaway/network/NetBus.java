package org.Cry_Legende.sHGiveaway.network;

import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ce dont le mode réseau a besoin de SHNetwork. Seule {@link ShNetworkBus} connaît ses classes : sans lui, aucune
 * classe de ce paquet n'est chargée.
 */
public interface NetBus {

    /** Candidature à une tâche de meneur. */
    interface Leader {

        boolean isLeader();

        void release();
    }

    /** Publie sur un canal ; ce serveur reçoit aussi son propre message. Ne bloque jamais. */
    void publish(String channel, JsonObject message);

    /**
     * Écoute un canal.
     *
     * @param mainThread vrai pour recevoir sur le fil principal
     */
    void listen(String channel, Consumer<JsonObject> handler, boolean mainThread);

    /** UUID d'un joueur connecté, sur ce serveur ou sur un autre, retrouvé par son pseudo. */
    Optional<UUID> onlineUuid(String name);

    /** Pseudo d'un joueur connecté, sur ce serveur ou sur un autre. */
    Optional<String> onlineName(UUID uuid);

    /** Se porte candidat pour une tâche qui ne tourne que sur un serveur à la fois. */
    Leader leader(String task, Runnable onAcquire, Runnable onLose);

    /** Exécute sur le fil principal : tout de suite si on y est déjà, sinon au prochain tick. */
    void sync(Runnable task);
}
