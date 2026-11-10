package org.Cry_Legende.sHGiveaway.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Giveaways en MariaDB, partagés par tous les serveurs. Chargée seulement avec SHNetwork (HikariCP et le pilote
 * MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <ul>
 *   <li>{@code giveaway_rounds} : tour en cours de chaque giveaway et son état « tiré » ;</li>
 *   <li>{@code giveaway_players} : participants, gagnants et joueurs prévenus d'un tour, et joueurs exclus (blacklist) ;</li>
 *   <li>{@code giveaway_pending} : récompenses de gagnants pas encore données.</li>
 * </ul>
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal, par {@link #async}.</p>
 */
public final class GiveawayDb {

    public static final String PARTICIPANT = "participant";
    public static final String WINNER = "winner";
    public static final String NOTIFIED = "notified";
    public static final String BLACKLIST = "blacklist";
    /** Tour des joueurs exclus : la blacklist vaut pour tous les tours d'un giveaway. */
    public static final String ALL_ROUNDS = "";

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)?");

    /** Une requête, exécutée sur un fil de la base. */
    @FunctionalInterface
    public interface SqlTask<T> {
        T run() throws SQLException;
    }

    /** Tour en base d'un giveaway. */
    public record RoundRow(String roundKey, boolean drawn) {
    }

    /** Ligne de {@code giveaway_players} d'un joueur. */
    public record Entry(String giveawayId, String roundKey, String kind) {
    }

    /** Récompense en attente d'un gagnant. */
    public record PendingRow(String giveawayId, String roundKey, int seq, String command) {
    }

    /** Résultat d'un tirage : faux si un autre serveur a tiré ce giveaway avant. */
    public record DrawOutcome(boolean claimed, List<UUID> winners) {
    }

    private final HikariDataSource pool;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SHGiveaway-Reseau");
        t.setDaemon(true);
        return t;
    });

    public GiveawayDb(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHGiveaway");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(Math.max(timeoutMs, 10_000)));
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    public Connection connection() throws SQLException {
        return pool.getConnection();
    }

    /** Lance la requête sur un fil de la base ; une erreur SQL termine le futur en erreur. */
    public <T> CompletableFuture<T> async(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    public void close() {
        executor.shutdown();
        pool.close();
    }

    /** Crée les tables si elles manquent. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTables() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            ensure(st, "giveaway_rounds", "CREATE TABLE IF NOT EXISTS giveaway_rounds ("
                + "giveaway_id VARCHAR(64) NOT NULL, "
                + "round_key VARCHAR(100) NOT NULL, "
                + "drawn TINYINT(1) NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (giveaway_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensure(st, "giveaway_players", "CREATE TABLE IF NOT EXISTS giveaway_players ("
                + "giveaway_id VARCHAR(64) NOT NULL, "
                + "round_key VARCHAR(100) NOT NULL, "
                + "kind VARCHAR(12) NOT NULL, "
                + "uuid CHAR(36) NOT NULL, "
                + "PRIMARY KEY (giveaway_id, round_key, kind, uuid), "
                + "KEY idx_uuid (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensure(st, "giveaway_pending", "CREATE TABLE IF NOT EXISTS giveaway_pending ("
                + "uuid CHAR(36) NOT NULL, "
                + "giveaway_id VARCHAR(64) NOT NULL, "
                + "round_key VARCHAR(100) NOT NULL, "
                + "seq INT NOT NULL, "
                + "command TEXT NOT NULL, "
                + "PRIMARY KEY (uuid, giveaway_id, round_key, seq)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private static void ensure(Statement st, String table, String ddl) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
            return;
        } catch (SQLException missing) {
            // la table n'existe pas encore : création ci-dessous
        }
        st.executeUpdate(ddl);
    }

    // ------------------------------------------------------------------ tours

    /**
     * Tour en base d'un giveaway, créé s'il manque. Si la config a un autre tour (nouvelle date de tirage), la base
     * passe au tour de la config : participants et gagnants de l'ancien tour restent en base mais ne comptent plus.
     *
     * @param drawnIfNew état « tiré » donné à un tour créé ici : celui que data.yml avait avant le mode réseau, pour ne
     *                   pas tirer une seconde fois un giveaway déjà tiré
     */
    public RoundRow syncRound(String giveawayId, String roundKey, boolean drawnIfNew) throws SQLException {
        try (Connection c = pool.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO giveaway_rounds (giveaway_id, round_key, drawn) VALUES (?, ?, ?)")) {
                ps.setString(1, giveawayId);
                ps.setString(2, roundKey);
                ps.setInt(3, drawnIfNew ? 1 : 0);
                if (ps.executeUpdate() == 1) {
                    return new RoundRow(roundKey, drawnIfNew);
                }
            }
            RoundRow row = readRound(c, giveawayId);
            if (row != null && !row.roundKey().equals(roundKey)) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE giveaway_rounds SET round_key = ?, drawn = 0 WHERE giveaway_id = ? AND round_key = ?")) {
                    ps.setString(1, roundKey);
                    ps.setString(2, giveawayId);
                    ps.setString(3, row.roundKey());
                    if (ps.executeUpdate() == 1) {
                        return new RoundRow(roundKey, false);
                    }
                }
                row = readRound(c, giveawayId);
            }
            return row == null ? new RoundRow(roundKey, false) : row;
        }
    }

    public RoundRow readRound(String giveawayId) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return readRound(c, giveawayId);
        }
    }

    private static RoundRow readRound(Connection c, String giveawayId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT round_key, drawn FROM giveaway_rounds WHERE giveaway_id = ?")) {
            ps.setString(1, giveawayId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new RoundRow(rs.getString(1), rs.getInt(2) != 0) : null;
            }
        }
    }

    // ------------------------------------------------------------------ joueurs

    /** Ajoute la ligne. Vrai si elle n'existait pas : c'est ce qui décide « une seule fois » (message de connexion par exemple). */
    public boolean add(String giveawayId, String roundKey, String kind, UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO giveaway_players (giveaway_id, round_key, kind, uuid) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, giveawayId);
            ps.setString(2, roundKey);
            ps.setString(3, kind);
            ps.setString(4, uuid.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Retire la ligne. Vrai si elle existait. */
    public boolean remove(String giveawayId, String roundKey, String kind, UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM giveaway_players WHERE giveaway_id = ? AND round_key = ? AND kind = ? AND uuid = ?")) {
            ps.setString(1, giveawayId);
            ps.setString(2, roundKey);
            ps.setString(3, kind);
            ps.setString(4, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public boolean has(String giveawayId, String roundKey, String kind, UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM giveaway_players WHERE giveaway_id = ? AND round_key = ? AND kind = ? AND uuid = ?")) {
            ps.setString(1, giveawayId);
            ps.setString(2, roundKey);
            ps.setString(3, kind);
            ps.setString(4, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Toutes les lignes d'un joueur, tous giveaways et tours confondus. */
    public List<Entry> entriesOf(UUID uuid) throws SQLException {
        List<Entry> entries = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT giveaway_id, round_key, kind FROM giveaway_players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return entries;
    }

    public int count(String giveawayId, String roundKey, String kind) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM giveaway_players WHERE giveaway_id = ? AND round_key = ? AND kind = ?")) {
            ps.setString(1, giveawayId);
            ps.setString(2, roundKey);
            ps.setString(3, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Une page de joueurs, triés par UUID. */
    public List<UUID> page(String giveawayId, String roundKey, String kind, int offset, int limit) throws SQLException {
        List<UUID> uuids = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM giveaway_players WHERE giveaway_id = ? AND round_key = ? AND kind = ? ORDER BY uuid LIMIT ? OFFSET ?")) {
            ps.setString(1, giveawayId);
            ps.setString(2, roundKey);
            ps.setString(3, kind);
            ps.setInt(4, limit);
            ps.setInt(5, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    uuids.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return uuids;
    }

    // ------------------------------------------------------------------ tirage

    /**
     * Tire le giveaway, une seule fois pour tout le réseau. Dans une seule transaction : le serveur qui passe
     * {@code drawn} de 0 à 1 est le seul à tirer ; il choisit les gagnants parmi les participants (ni exclus, ni
     * dans {@code excluded}) et écrit leurs récompenses en attente. Une erreur annule tout et le giveaway reste à tirer.
     *
     * @param excluded joueurs que le serveur sait ne plus remplir les conditions (connectés ici, permissions perdues)
     * @param commands commandes de récompense, données à chaque gagnant à sa connexion ou tout de suite s'il est en ligne
     */
    public DrawOutcome draw(String giveawayId, String roundKey, int winnersCount, Set<UUID> excluded, List<String> commands) throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("UPDATE giveaway_rounds SET drawn = 1 WHERE giveaway_id = ? AND round_key = ? AND drawn = 0")) {
                    ps.setString(1, giveawayId);
                    ps.setString(2, roundKey);
                    if (ps.executeUpdate() != 1) {
                        c.rollback();
                        return new DrawOutcome(false, List.of());
                    }
                }

                List<UUID> candidates = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT p.uuid FROM giveaway_players p WHERE p.giveaway_id = ? AND p.round_key = ? AND p.kind = 'participant' "
                    + "AND NOT EXISTS (SELECT 1 FROM giveaway_players b WHERE b.giveaway_id = p.giveaway_id AND b.round_key = '' AND b.kind = 'blacklist' AND b.uuid = p.uuid) "
                    + "AND NOT EXISTS (SELECT 1 FROM giveaway_players w WHERE w.giveaway_id = p.giveaway_id AND w.round_key = p.round_key AND w.kind = 'winner' AND w.uuid = p.uuid)")) {
                    ps.setString(1, giveawayId);
                    ps.setString(2, roundKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = UUID.fromString(rs.getString(1));
                            if (!excluded.contains(uuid)) {
                                candidates.add(uuid);
                            }
                        }
                    }
                }

                Collections.shuffle(candidates, ThreadLocalRandom.current());
                List<UUID> winners = new ArrayList<>(candidates.subList(0, Math.min(Math.max(1, winnersCount), candidates.size())));

                try (PreparedStatement win = c.prepareStatement("INSERT IGNORE INTO giveaway_players (giveaway_id, round_key, kind, uuid) VALUES (?, ?, 'winner', ?)");
                     PreparedStatement pending = c.prepareStatement("INSERT IGNORE INTO giveaway_pending (uuid, giveaway_id, round_key, seq, command) VALUES (?, ?, ?, ?, ?)")) {
                    for (UUID winner : winners) {
                        win.setString(1, giveawayId);
                        win.setString(2, roundKey);
                        win.setString(3, winner.toString());
                        win.addBatch();
                        for (int i = 0; i < commands.size(); i++) {
                            pending.setString(1, winner.toString());
                            pending.setString(2, giveawayId);
                            pending.setString(3, roundKey);
                            pending.setInt(4, i);
                            pending.setString(5, commands.get(i));
                            pending.addBatch();
                        }
                    }
                    win.executeBatch();
                    pending.executeBatch();
                }
                c.commit();
                return new DrawOutcome(true, winners);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ récompenses en attente

    /**
     * Réclame les récompenses en attente du joueur. Chaque ligne est supprimée par son propre {@code DELETE} et n'est
     * rendue que si ce {@code DELETE} l'a supprimée : deux serveurs qui réclament en même temps ne la donnent pas deux fois.
     */
    public List<PendingRow> claimPending(UUID uuid) throws SQLException {
        List<PendingRow> claimed = new ArrayList<>();
        try (Connection c = pool.getConnection()) {
            List<PendingRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT giveaway_id, round_key, seq, command FROM giveaway_pending WHERE uuid = ? ORDER BY giveaway_id, round_key, seq")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new PendingRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
                    }
                }
            }
            for (PendingRow row : rows) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM giveaway_pending WHERE uuid = ? AND giveaway_id = ? AND round_key = ? AND seq = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, row.giveawayId());
                    ps.setString(3, row.roundKey());
                    ps.setInt(4, row.seq());
                    if (ps.executeUpdate() == 1) {
                        claimed.add(row);
                    }
                }
            }
        }
        return claimed;
    }

    /** Remet en attente des récompenses réclamées mais pas données (le joueur est parti entre-temps). */
    public void requeue(UUID uuid, List<PendingRow> rows) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO giveaway_pending (uuid, giveaway_id, round_key, seq, command) VALUES (?, ?, ?, ?, ?)")) {
            for (PendingRow row : rows) {
                ps.setString(1, uuid.toString());
                ps.setString(2, row.giveawayId());
                ps.setString(3, row.roundKey());
                ps.setInt(4, row.seq());
                ps.setString(5, row.command());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------ reprise de data.yml

    /**
     * Reprend les données d'un serveur qui tournait seul. Les lignes déjà en base ne sont pas touchées : la reprise peut
     * être relancée sans rien compter deux fois.
     *
     * @param players lignes {giveaway, tour, genre, uuid}
     * @param pending récompenses en attente {uuid, giveaway, tour, numéro, commande}
     * @param drawn   tours déjà tirés {giveaway, tour}
     * @return {lignes de joueurs ajoutées, récompenses ajoutées}
     */
    public int[] importRows(List<String[]> players, List<String[]> pending, List<String[]> drawn) throws SQLException {
        int addedPlayers = 0;
        int addedPending = 0;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO giveaway_players (giveaway_id, round_key, kind, uuid) VALUES (?, ?, ?, ?)")) {
                    for (String[] row : players) {
                        for (int i = 0; i < 4; i++) {
                            ps.setString(i + 1, row[i]);
                        }
                        addedPlayers += ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO giveaway_pending (uuid, giveaway_id, round_key, seq, command) VALUES (?, ?, ?, ?, ?)")) {
                    for (String[] row : pending) {
                        ps.setString(1, row[0]);
                        ps.setString(2, row[1]);
                        ps.setString(3, row[2]);
                        ps.setInt(4, Integer.parseInt(row[3]));
                        ps.setString(5, row[4]);
                        addedPending += ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE giveaway_rounds SET drawn = 1 WHERE giveaway_id = ? AND round_key = ?")) {
                    for (String[] row : drawn) {
                        ps.setString(1, row[0]);
                        ps.setString(2, row[1]);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return new int[]{addedPlayers, addedPending};
    }

    // ------------------------------------------------------------------ comptes connus

    /**
     * Pseudo exact d'un compte connu du réseau, retrouvé dans la table des comptes (colonnes {@code name} en
     * minuscules et {@code display_name}), ou null.
     */
    public String knownName(String table, String lowerName) throws SQLException {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new SQLException("Nom de table invalide : " + table);
        }
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT display_name FROM " + table + " WHERE name = ?")) {
            ps.setString(1, lowerName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
