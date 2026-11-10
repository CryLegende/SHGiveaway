package org.Cry_Legende.sHGiveaway.network;

import org.Cry_Legende.sHGiveaway.SHGiveaway;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.SQLException;

/**
 * SHGiveaway sur plusieurs serveurs de jeu (SHNetwork) : la base partagée ({@link GiveawayDb}) tient participants,
 * gagnants et récompenses en attente, le bus annonce les tirages et un seul serveur, le meneur, lance les tirages
 * planifiés. Seule {@link ShNetworkBus} connaît SHNetwork : sans lui, aucune classe de ce paquet n'est chargée.
 */
public final class NetworkGiveaways {

    private static final String TASK = "shgiveaway";

    private final NetBus bus;
    private final GiveawayDb db;
    private final String channel;
    private final String accountsTable;
    private volatile NetBus.Leader leader;

    private NetworkGiveaways(NetBus bus, GiveawayDb db, String channel, String accountsTable) {
        this.bus = bus;
        this.db = db;
        this.channel = channel;
        this.accountsTable = accountsTable;
    }

    /** Ouvre la base partagée et le pont vers SHNetwork. Une base injoignable lève une exception. */
    public static NetworkGiveaways open(SHGiveaway plugin) throws SQLException {
        FileConfiguration config = plugin.getConfig();
        String base = "reseau.base-de-donnees.";
        GiveawayDb db = new GiveawayDb(
            config.getString(base + "url"),
            config.getString(base + "utilisateur"),
            config.getString(base + "mot-de-passe"),
            Math.max(250, config.getInt(base + "delai-ms", 5000)));
        try {
            db.createTables();
            NetBus bus = ShNetworkBus.open();
            return new NetworkGiveaways(bus, db, config.getString("reseau.canal", "giveaway"), config.getString("reseau.table-comptes", ""));
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e;
        }
    }

    public NetBus bus() {
        return bus;
    }

    public GiveawayDb db() {
        return db;
    }

    /** Canal du bus où les tirages sont annoncés. */
    public String channel() {
        return channel;
    }

    /** Table des comptes connus du réseau, vide si elle n'est pas configurée. */
    public String accountsTable() {
        return accountsTable;
    }

    /** Candidature au rôle de meneur : lui seul lance les tirages planifiés. */
    public void candidate(Runnable onAcquire, Runnable onLose) {
        leader = bus.leader(TASK, onAcquire, onLose);
    }

    public boolean isLeader() {
        NetBus.Leader current = leader;
        return current != null && current.isLeader();
    }

    /** Arrêt du plugin : rend le rôle de meneur et ferme la base. */
    public void close() {
        NetBus.Leader current = leader;
        if (current != null) {
            current.release();
        }
        db.close();
    }
}
