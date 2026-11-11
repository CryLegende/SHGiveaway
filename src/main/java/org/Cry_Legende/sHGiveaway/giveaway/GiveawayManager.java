package org.Cry_Legende.sHGiveaway.giveaway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.milkbowl.vault.permission.Permission;
import org.Cry_Legende.sHGiveaway.SHGiveaway;
import org.Cry_Legende.sHGiveaway.network.GiveawayDb;
import org.Cry_Legende.sHGiveaway.network.NetworkGiveaways;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;

public class GiveawayManager {

    private static final DateTimeFormatter DRAW_INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String WINNERS_LOG_FILE = "gagnants";

    private final SHGiveaway plugin;
    private final Permission permission;

    private final Map<String, GiveawayDefinition> giveaways = new HashMap<>();
    private final Map<String, GiveawayState> states = new HashMap<>();
    private final Map<String, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, List<PendingReward>> pendingRewards = new HashMap<>();
    private final List<String> historyEntries = new ArrayList<>();
    private BukkitTask saveTask;

    // Non nul seulement avec SHNetwork : participants, gagnants et récompenses en base partagée, un seul tirage pour le réseau
    private NetworkGiveaways net;
    // Tirages déjà annoncés sur ce serveur (giveaway|tour), pour ne les annoncer qu'une fois
    private final Set<String> announced = new HashSet<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    private ZoneId zoneId;
    private DateTimeFormatter dateFormat;
    private DateTimeFormatter timeFormat;

    public GiveawayManager(SHGiveaway plugin, Permission permission) {
        this.plugin = plugin;
        this.permission = permission;
    }

    public enum AdminEditResult {
        SUCCESS,
        GIVEAWAY_NOT_FOUND,
        PLAYER_NOT_FOUND,
        ALREADY_PRESENT,
        NOT_PRESENT,
        BLACKLISTED,
        ALREADY_BLACKLISTED,
        NOT_BLACKLISTED
    }

    public void load() {
        loadFormatting();
        loadDataFile();
        loadGiveaways();
        if (net != null) {
            syncRounds();
        }
    }

    /**
     * Applique les définitions de config.yml déjà relues par le plugin. L'état (participants, gagnants, récompenses
     * en attente, historique) reste en mémoire : data.yml n'est ni relu ni réécrit. Les tirages sont replanifiés.
     */
    public void reload() {
        cancelAllTasks();
        giveaways.clear();
        loadFormatting();
        loadGiveaways();
        if (net != null) {
            syncRounds();
        } else {
            scheduleAll();
        }
    }

    public void shutdown() {
        cancelAllTasks();
        saveData();
        if (net != null) {
            net.close();
        }
    }

    public void scheduleAll() {
        if (net != null && !net.isLeader()) {
            return; // avec SHNetwork, seul le serveur meneur lance les tirages planifiés
        }
        for (GiveawayDefinition giveaway : giveaways.values()) {
            GiveawayState state = getOrCreateState(giveaway.getId());
            if (!giveaway.isEnabled() || state.isDrawn()) {
                continue;
            }

            cancelTask(giveaway.getId());
            scheduleDraw(giveaway);
        }
    }

    private void scheduleDraw(GiveawayDefinition giveaway) {
        // Attente découpée : un délai en ticks dérive dès que le serveur rame, on revérifie l'heure réelle au plus toutes les 10 minutes
        long step = Math.min(calculateDelayTicks(giveaway.getDrawAt()), 12000L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (LocalDateTime.now(zoneId).isBefore(giveaway.getDrawAt())) {
                scheduleDraw(giveaway);
            } else {
                draw(giveaway.getId(), false);
            }
        }, step);
        tasks.put(giveaway.getId(), task);
    }

    public void handleJoin(Player player) {
        if (net != null) {
            handleJoinNetwork(player);
            return;
        }
        processPendingRewards(player);

        for (GiveawayDefinition giveaway : giveaways.values()) {
            GiveawayState state = getOrCreateState(giveaway.getId());
            if (!giveaway.isEnabled() || state.isDrawn()) {
                continue;
            }

            if (LocalDateTime.now(zoneId).isAfter(giveaway.getDrawAt())) {
                continue;
            }

            UUID uuid = player.getUniqueId();
            if (state.getBlacklisted().contains(uuid)) {
                continue;
            }

            boolean participant = state.getParticipants().contains(uuid);
            if (!participant && isEligibleOnJoin(player, giveaway)) {
                state.getParticipants().add(uuid);

                if (giveaway.isAnnounceOnJoin() && state.getNotified().add(uuid)) {
                    scheduleJoinMessage(player, giveaway, "join-added", "sounds.join-added");
                }
                scheduleSave();
                continue;
            }

            if (participant && giveaway.isAnnounceOnJoin() && state.getNotified().add(uuid)) {
                scheduleJoinMessage(player, giveaway, "join-already", "sounds.join-already");
                scheduleSave();
            }
        }
    }

    public boolean draw(String id, boolean manual) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            return false;
        }

        GiveawayState state = getOrCreateState(giveaway.getId());
        if (state.isDrawn()) {
            return true;
        }

        if (net != null) {
            drawNetwork(giveaway, manual);
            return true;
        }

        List<UUID> candidates = collectCandidates(giveaway, state);
        if (candidates.isEmpty()) {
            state.setDrawn(true);
            saveData();
            broadcastList("no-participants", giveaway, null, null, 0);
            playConfiguredSoundForAll("sounds.no-participants");
            cancelTask(giveaway.getId());
            return true;
        }

        Collections.shuffle(candidates, ThreadLocalRandom.current());
        int winnersCount = Math.min(giveaway.getWinnersCount(), candidates.size());
        List<OfflinePlayer> selectedWinners = new ArrayList<>();

        for (int i = 0; i < winnersCount; i++) {
            UUID winnerUuid = candidates.get(i);
            OfflinePlayer winner = Bukkit.getOfflinePlayer(winnerUuid);
            state.getWinners().add(winnerUuid);
            selectedWinners.add(winner);

            Player onlineWinner = winner.getPlayer();
            if (onlineWinner != null && onlineWinner.isOnline()) {
                executeRewardCommandsNow(onlineWinner, giveaway);
                sendMessageList(onlineWinner, "winner-personal-online", giveaway, winner, winner.getName(), 1);
                playConfiguredSound(onlineWinner, "sounds.winner-direct");
                ActionLog.write(plugin, WINNERS_LOG_FILE, "DRAW | giveaway=" + giveaway.getId() + " | winner=" + printablePlayer(winner) + " | delivery=immediate");
            } else {
                queueRewardsForLater(winnerUuid, giveaway);
                ActionLog.write(plugin, WINNERS_LOG_FILE, "DRAW | giveaway=" + giveaway.getId() + " | winner=" + printablePlayer(winner) + " | delivery=queued_on_login");
            }
        }

        String winnersText = stringifyWinners(selectedWinners);
        OfflinePlayer firstWinner = selectedWinners.get(0);
        broadcastList("winner-announcement", giveaway, firstWinner, winnersText, selectedWinners.size());
        playConfiguredSoundForAll("sounds.winner-announcement");
        appendHistoryEntry(giveaway, winnersText, selectedWinners.size());

        state.setDrawn(true);
        saveData();
        cancelTask(giveaway.getId());

        if (manual) {
            plugin.getLogger().info("Giveaway " + giveaway.getId() + " tiré manuellement.");
        }
        return true;
    }

    public Collection<String> getGiveawayIds() {
        return giveaways.keySet();
    }

    public boolean hasGiveaway(String id) {
        return giveaways.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public String getStatus(String id) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            return color("&7&l« &6&lSH &8&l» &cGiveaway introuvable : &e" + id);
        }

        GiveawayState state = getOrCreateState(giveaway.getId());
        return formatStatus(giveaway.getId(), state.getParticipants().size(), state.getBlacklisted().size(), state.getWinners().size(), state.isDrawn());
    }

    private String formatStatus(String id, int participants, int blacklisted, int winners, boolean drawn) {
        return color("&7&l« &6&lSH &8&l» &fID: &e" + id
            + " &8| &fParticipants: &e" + participants
            + " &8| &fBlacklist: &e" + blacklisted
            + " &8| &fGagnants: &e" + winners
            + " &8| &fTiré: &e" + (drawn ? "oui" : "non"));
    }

    /** Comme {@link #getStatus(String)}, avec SHNetwork les compteurs sont lus en base sans bloquer le fil principal. */
    public void getStatus(String id, Consumer<String> done) {
        GiveawayDefinition giveaway = net == null ? null : giveaways.get(id.toLowerCase(Locale.ROOT));
        if (net == null || giveaway == null) {
            done.accept(getStatus(id));
            return;
        }
        String round = roundOf(giveaway);
        net.db().async(() -> {
            GiveawayDb db = net.db();
            GiveawayDb.RoundRow row = db.readRound(giveaway.getId());
            return formatStatus(giveaway.getId(),
                db.count(giveaway.getId(), round, GiveawayDb.PARTICIPANT),
                db.count(giveaway.getId(), GiveawayDb.ALL_ROUNDS, GiveawayDb.BLACKLIST),
                db.count(giveaway.getId(), round, GiveawayDb.WINNER),
                row != null && row.drawn());
        }).whenComplete((line, error) -> net.bus().sync(() -> done.accept(error != null ? databaseError(error) : line)));
    }

    public List<String> getActiveGiveawaysView() {
        return buildActiveView(giveaway -> getOrCreateState(giveaway.getId()).getParticipants().size());
    }

    /** Comme {@link #getActiveGiveawaysView()}, avec SHNetwork les participants sont comptés en base. */
    public void getActiveGiveawaysView(Consumer<List<String>> done) {
        if (net == null) {
            done.accept(getActiveGiveawaysView());
            return;
        }
        LocalDateTime now = LocalDateTime.now(zoneId);
        List<GiveawayDefinition> active = new ArrayList<>();
        for (GiveawayDefinition giveaway : giveaways.values()) {
            if (giveaway.isEnabled() && !getOrCreateState(giveaway.getId()).isDrawn() && !now.isAfter(giveaway.getDrawAt())) {
                active.add(giveaway);
            }
        }
        net.db().async(() -> {
            Map<String, Integer> counts = new HashMap<>();
            for (GiveawayDefinition giveaway : active) {
                counts.put(giveaway.getId(), net.db().count(giveaway.getId(), roundOf(giveaway), GiveawayDb.PARTICIPANT));
            }
            return counts;
        }).whenComplete((counts, error) -> net.bus().sync(() -> done.accept(error != null
            ? List.of(databaseError(error))
            : buildActiveView(giveaway -> counts.getOrDefault(giveaway.getId(), 0)))));
    }

    private List<String> buildActiveView(java.util.function.ToIntFunction<GiveawayDefinition> participants) {
        List<String> lines = new ArrayList<>();
        lines.add(color("&8&m------------------------------------------------"));
        lines.add(color("&7&l« &6&lSH &8&l» &fGiveaways actifs"));

        List<GiveawayDefinition> active = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(zoneId);
        for (GiveawayDefinition giveaway : giveaways.values()) {
            GiveawayState state = getOrCreateState(giveaway.getId());
            if (giveaway.isEnabled() && !state.isDrawn() && !now.isAfter(giveaway.getDrawAt())) {
                active.add(giveaway);
            }
        }
        active.sort(Comparator.comparing(GiveawayDefinition::getDrawAt));

        if (active.isEmpty()) {
            lines.add(color("&8• &7Aucun giveaway actif."));
            lines.add(color("&8&m------------------------------------------------"));
            return lines;
        }

        for (GiveawayDefinition giveaway : active) {
            lines.add(color("&8• &e" + giveaway.getId()
                + " &7| &f" + giveaway.getPrizeDisplay()
                + " &7| &fTirage: &e" + giveaway.getDrawAt().format(dateFormat) + " " + giveaway.getDrawAt().format(timeFormat)
                + " &7| &fParticipants: &e" + participants.applyAsInt(giveaway)));
        }
        lines.add(color("&8&m------------------------------------------------"));
        return lines;
    }

    public List<String> getHistoryView(int requestedPage) {
        int pageSize = 7;
        int total = historyEntries.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int page = Math.min(Math.max(1, requestedPage), maxPage);

        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(total, fromIndex + pageSize);

        List<String> lines = new ArrayList<>();
        lines.add(color("&8&m------------------------------------------------"));
        lines.add(color("&7&l« &6&lSH &8&l» &fHistorique des giveaways"));
        lines.add(color("&7Page &e" + page + "&7/&e" + maxPage + " &8| &fEntrées: &e" + total));

        if (historyEntries.isEmpty()) {
            lines.add(color("&8• &7Aucun historique."));
            lines.add(color("&8&m------------------------------------------------"));
            return lines;
        }

        for (int i = fromIndex; i < toIndex; i++) {
            lines.add(color("&8• &f" + historyEntries.get(i)));
        }
        lines.add(color("&8&m------------------------------------------------"));
        return lines;
    }

    public List<String> getParticipantsView(String id, int page) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            return List.of(color("&7&l« &6&lSH &8&l» &cGiveaway introuvable : &e" + id));
        }
        GiveawayState state = getOrCreateState(giveaway.getId());
        return buildUuidListView("Participants", giveaway.getId(), state.getParticipants(), page);
    }

    public List<String> getBlacklistView(String id, int page) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            return List.of(color("&7&l« &6&lSH &8&l» &cGiveaway introuvable : &e" + id));
        }
        GiveawayState state = getOrCreateState(giveaway.getId());
        return buildUuidListView("Blacklist", giveaway.getId(), state.getBlacklisted(), page);
    }

    /** Comme {@link #getParticipantsView(String, int)}, avec SHNetwork la page est lue en base. */
    public void getParticipantsView(String id, int page, Consumer<List<String>> done) {
        if (net == null) {
            done.accept(getParticipantsView(id, page));
            return;
        }
        networkUuidListView("Participants", id, GiveawayDb.PARTICIPANT, page, done);
    }

    /** Comme {@link #getBlacklistView(String, int)}, avec SHNetwork la page est lue en base. */
    public void getBlacklistView(String id, int page, Consumer<List<String>> done) {
        if (net == null) {
            done.accept(getBlacklistView(id, page));
            return;
        }
        networkUuidListView("Blacklist", id, GiveawayDb.BLACKLIST, page, done);
    }

    private void networkUuidListView(String title, String id, String kind, int requestedPage, Consumer<List<String>> done) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            done.accept(List.of(color("&7&l« &6&lSH &8&l» &cGiveaway introuvable : &e" + id)));
            return;
        }
        int pageSize = 10;
        String round = GiveawayDb.BLACKLIST.equals(kind) ? GiveawayDb.ALL_ROUNDS : roundOf(giveaway);
        net.db().async(() -> {
            int total = net.db().count(giveaway.getId(), round, kind);
            int maxPage = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            int page = Math.min(Math.max(1, requestedPage), maxPage);
            return new UuidPage(total, page, maxPage, net.db().page(giveaway.getId(), round, kind, (page - 1) * pageSize, pageSize));
        }).whenComplete((shown, error) -> net.bus().sync(() -> done.accept(error != null
            ? List.of(databaseError(error))
            : formatUuidListView(title, giveaway.getId(), shown.total(), shown.page(), shown.maxPage(), shown.uuids()))));
    }

    private record UuidPage(int total, int page, int maxPage, List<UUID> uuids) {
    }

    public AdminEditResult addParticipantManually(String id, String target) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            return AdminEditResult.GIVEAWAY_NOT_FOUND;
        }

        OfflinePlayer targetPlayer = resolveTarget(target);
        if (targetPlayer == null) {
            return AdminEditResult.PLAYER_NOT_FOUND;
        }

        GiveawayState state = getOrCreateState(giveaway.getId());
        UUID uuid = targetPlayer.getUniqueId();
        if (state.getBlacklisted().contains(uuid)) {
            return AdminEditResult.BLACKLISTED;
        }
        if (!state.getParticipants().add(uuid)) {
            return AdminEditResult.ALREADY_PRESENT;
        }

        saveData();
        return AdminEditResult.SUCCESS;
    }

    public AdminEditResult removeParticipantManually(String id, String target) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            return AdminEditResult.GIVEAWAY_NOT_FOUND;
        }

        OfflinePlayer targetPlayer = resolveTarget(target);
        if (targetPlayer == null) {
            return AdminEditResult.PLAYER_NOT_FOUND;
        }

        GiveawayState state = getOrCreateState(giveaway.getId());
        UUID uuid = targetPlayer.getUniqueId();
        if (!state.getParticipants().remove(uuid)) {
            return AdminEditResult.NOT_PRESENT;
        }

        state.getNotified().remove(uuid);
        saveData();
        return AdminEditResult.SUCCESS;
    }

    public AdminEditResult blacklistPlayer(String id, String target) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            return AdminEditResult.GIVEAWAY_NOT_FOUND;
        }

        OfflinePlayer targetPlayer = resolveTarget(target);
        if (targetPlayer == null) {
            return AdminEditResult.PLAYER_NOT_FOUND;
        }

        GiveawayState state = getOrCreateState(giveaway.getId());
        UUID uuid = targetPlayer.getUniqueId();
        if (!state.getBlacklisted().add(uuid)) {
            return AdminEditResult.ALREADY_BLACKLISTED;
        }

        state.getParticipants().remove(uuid);
        state.getNotified().remove(uuid);
        saveData();
        return AdminEditResult.SUCCESS;
    }

    public AdminEditResult unblacklistPlayer(String id, String target) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            return AdminEditResult.GIVEAWAY_NOT_FOUND;
        }

        OfflinePlayer targetPlayer = resolveTarget(target);
        if (targetPlayer == null) {
            return AdminEditResult.PLAYER_NOT_FOUND;
        }

        GiveawayState state = getOrCreateState(giveaway.getId());
        if (!state.getBlacklisted().remove(targetPlayer.getUniqueId())) {
            return AdminEditResult.NOT_BLACKLISTED;
        }

        saveData();
        return AdminEditResult.SUCCESS;
    }

    /** Opération d'administration sur un joueur et un giveaway, avec SHNetwork : exécutée en base. */
    private interface NetworkEdit {
        AdminEditResult run(GiveawayDefinition giveaway, String round, UUID uuid) throws java.sql.SQLException;
    }

    public void addParticipantManually(String id, String target, Consumer<AdminEditResult> done) {
        if (net == null) {
            done.accept(addParticipantManually(id, target));
            return;
        }
        networkEdit(id, target, done, (giveaway, round, uuid) -> {
            if (net.db().has(giveaway.getId(), GiveawayDb.ALL_ROUNDS, GiveawayDb.BLACKLIST, uuid)) {
                return AdminEditResult.BLACKLISTED;
            }
            return net.db().add(giveaway.getId(), round, GiveawayDb.PARTICIPANT, uuid) ? AdminEditResult.SUCCESS : AdminEditResult.ALREADY_PRESENT;
        });
    }

    public void removeParticipantManually(String id, String target, Consumer<AdminEditResult> done) {
        if (net == null) {
            done.accept(removeParticipantManually(id, target));
            return;
        }
        networkEdit(id, target, done, (giveaway, round, uuid) -> {
            if (!net.db().remove(giveaway.getId(), round, GiveawayDb.PARTICIPANT, uuid)) {
                return AdminEditResult.NOT_PRESENT;
            }
            net.db().remove(giveaway.getId(), round, GiveawayDb.NOTIFIED, uuid);
            return AdminEditResult.SUCCESS;
        });
    }

    public void blacklistPlayer(String id, String target, Consumer<AdminEditResult> done) {
        if (net == null) {
            done.accept(blacklistPlayer(id, target));
            return;
        }
        networkEdit(id, target, done, (giveaway, round, uuid) -> {
            if (!net.db().add(giveaway.getId(), GiveawayDb.ALL_ROUNDS, GiveawayDb.BLACKLIST, uuid)) {
                return AdminEditResult.ALREADY_BLACKLISTED;
            }
            net.db().remove(giveaway.getId(), round, GiveawayDb.PARTICIPANT, uuid);
            net.db().remove(giveaway.getId(), round, GiveawayDb.NOTIFIED, uuid);
            return AdminEditResult.SUCCESS;
        });
    }

    public void unblacklistPlayer(String id, String target, Consumer<AdminEditResult> done) {
        if (net == null) {
            done.accept(unblacklistPlayer(id, target));
            return;
        }
        networkEdit(id, target, done, (giveaway, round, uuid) ->
            net.db().remove(giveaway.getId(), GiveawayDb.ALL_ROUNDS, GiveawayDb.BLACKLIST, uuid) ? AdminEditResult.SUCCESS : AdminEditResult.NOT_BLACKLISTED);
    }

    private void networkEdit(String id, String target, Consumer<AdminEditResult> done, NetworkEdit edit) {
        GiveawayDefinition giveaway = giveaways.get(id.toLowerCase(Locale.ROOT));
        if (giveaway == null) {
            done.accept(AdminEditResult.GIVEAWAY_NOT_FOUND);
            return;
        }
        String round = roundOf(giveaway);
        net.db().async(() -> {
            UUID uuid = resolveTargetNetwork(target);
            return uuid == null ? AdminEditResult.PLAYER_NOT_FOUND : edit.run(giveaway, round, uuid);
        }).whenComplete((result, error) -> net.bus().sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Modification du giveaway " + id + " impossible, base partagée injoignable.", error);
                done.accept(AdminEditResult.PLAYER_NOT_FOUND);
                return;
            }
            done.accept(result);
        }));
    }

    /**
     * Joueur visé par une commande d'administration, avec SHNetwork : un UUID, un joueur connecté sur le réseau, un
     * compte connu de la table des comptes ({@code reseau.table-comptes}), sinon un joueur déjà venu sur ce serveur.
     * Bloque : hors du fil principal.
     */
    private UUID resolveTargetNetwork(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {
        }
        Optional<UUID> online = net.bus().onlineUuid(target);
        if (online.isPresent()) {
            return online.get();
        }
        String table = net.accountsTable();
        if (table != null && !table.isBlank()) {
            try {
                String known = net.db().knownName(table, target.toLowerCase(Locale.ROOT));
                return known == null ? null : offlineUuid(known);
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("Table des comptes " + table + " illisible, joueurs retrouvés sur ce serveur seulement : " + e.getMessage());
            }
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(target);
        if (cached == null || cached.getName() == null || !(cached.hasPlayedBefore() || cached.isOnline())) {
            return null;
        }
        return offlineUuid(cached.getName());
    }

    /** UUID « hors ligne » d'un pseudo, celui de tout le réseau (pas de recherche Mojang). */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private String databaseError(Throwable error) {
        plugin.getLogger().log(Level.WARNING, "Base partagée des giveaways injoignable.", error);
        return color("&7&l« &c&lSH &8&l» &cBase partagée injoignable.");
    }

    public String color(String input) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', input);
    }

    private void loadFormatting() {
        String timezone = plugin.getConfig().getString("settings.timezone", "Europe/Paris");
        zoneId = ZoneId.of(timezone);

        String datePattern = plugin.getConfig().getString("settings.date-format", "dd/MM/yyyy");
        String timePattern = plugin.getConfig().getString("settings.time-format", "HH:mm");

        dateFormat = DateTimeFormatter.ofPattern(datePattern);
        timeFormat = DateTimeFormatter.ofPattern(timePattern);
    }

    private void loadGiveaways() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("giveaways");
        if (section == null) {
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection g = section.getConfigurationSection(id);
            if (g == null) {
                continue;
            }

            try {
                GiveawayDefinition definition = new GiveawayDefinition(
                    id.toLowerCase(Locale.ROOT),
                    g.getBoolean("enabled", false),
                    g.getString("prize-display", id),
                    Math.max(1, g.getInt("winners", 1)),
                    LocalDateTime.parse(g.getString("draw-at"), DRAW_INPUT_FORMAT),
                    g.getString("round-key", g.getString("draw-at", "")),
                    g.getBoolean("announce-on-join", true),
                    Math.max(0, g.getInt("join-message-delay-seconds", 3)),
                    g.getStringList("excluded-groups"),
                    g.getStringList("excluded-permissions"),
                    g.getStringList("required-permissions"),
                    Math.max(0, g.getInt("min-playtime-minutes", 0)),
                    Math.max(0, g.getInt("min-account-age-hours", 0)),
                    g.getStringList("reward-commands")
                );
                giveaways.put(definition.getId(), definition);
                GiveawayState state = getOrCreateState(definition.getId());
                ensureRoundState(definition, state);
            } catch (Exception ex) {
                plugin.getLogger().warning("Impossible de charger le giveaway '" + id + "': " + ex.getMessage());
            }
        }
    }

    private GiveawayState getOrCreateState(String id) {
        GiveawayState existing = states.get(id);
        if (existing != null) {
            return existing;
        }

        GiveawayState state = new GiveawayState();
        if (net != null) {
            // Avec SHNetwork, participants et gagnants sont en base : l'état d'ici ne garde que le tour et « tiré »
            states.put(id, state);
            return state;
        }

        List<String> participants = dataConfig.getStringList("giveaways." + id + ".participants");
        for (String raw : participants) {
            try {
                state.getParticipants().add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<String> winners = dataConfig.getStringList("giveaways." + id + ".winners");
        for (String raw : winners) {
            try {
                state.getWinners().add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<String> notified = dataConfig.getStringList("giveaways." + id + ".notified");
        for (String raw : notified) {
            try {
                state.getNotified().add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<String> blacklisted = dataConfig.getStringList("giveaways." + id + ".blacklisted");
        for (String raw : blacklisted) {
            try {
                state.getBlacklisted().add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }

        state.setDrawn(dataConfig.getBoolean("giveaways." + id + ".drawn", false));
        state.setRoundKey(dataConfig.getString("giveaways." + id + ".round-key", null));
        states.put(id, state);
        return state;
    }

    private void ensureRoundState(GiveawayDefinition definition, GiveawayState state) {
        if (net != null) {
            return; // le tour est lu en base par syncRounds
        }
        String currentRoundKey = definition.getRoundKey();
        if (currentRoundKey == null) {
            currentRoundKey = "";
        }

        String previousRoundKey = state.getRoundKey();
        if (previousRoundKey == null) {
            state.setRoundKey(currentRoundKey);
            saveData();
            return;
        }

        if (!previousRoundKey.equals(currentRoundKey)) {
            state.getParticipants().clear();
            state.getWinners().clear();
            state.getNotified().clear();
            state.setDrawn(false);
            state.setRoundKey(currentRoundKey);
            saveData();
        }
    }

    private boolean isEligibleOnJoin(Player player, GiveawayDefinition giveaway) {
        for (String required : giveaway.getRequiredPermissions()) {
            if (!player.hasPermission(required)) {
                return false;
            }
        }

        for (String excluded : giveaway.getExcludedPermissions()) {
            if (player.hasPermission(excluded)) {
                return false;
            }
        }

        for (String group : giveaway.getExcludedGroups()) {
            if (permission.playerInGroup(player, group)) {
                return false;
            }
        }

        if (giveaway.getMinPlaytimeMinutes() > 0) {
            long playedTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            long playedMinutes = (playedTicks / 20L) / 60L;
            if (playedMinutes < giveaway.getMinPlaytimeMinutes()) {
                return false;
            }
        }

        if (giveaway.getMinAccountAgeHours() > 0) {
            long firstPlayed = player.getFirstPlayed();
            if (firstPlayed <= 0L) {
                return false;
            }

            long ageHours = Duration.between(Instant.ofEpochMilli(firstPlayed), Instant.now()).toHours();
            if (ageHours < giveaway.getMinAccountAgeHours()) {
                return false;
            }
        }

        return true;
    }

    private List<UUID> collectCandidates(GiveawayDefinition giveaway, GiveawayState state) {
        List<UUID> candidates = new ArrayList<>();

        for (UUID uuid : state.getParticipants()) {
            if (state.getWinners().contains(uuid) || state.getBlacklisted().contains(uuid)) {
                continue;
            }

            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            if (!isEligibleAtDraw(player, giveaway)) {
                continue;
            }

            candidates.add(uuid);
        }

        return candidates;
    }

    private boolean isEligibleAtDraw(OfflinePlayer player, GiveawayDefinition giveaway) {
        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            // The player was already validated on join; avoid false negatives from
            // offline permission/group lookups depending on the Vault provider.
            return true;
        }

        for (String required : giveaway.getRequiredPermissions()) {
            if (!onlinePlayer.hasPermission(required)) {
                return false;
            }
        }

        for (String excluded : giveaway.getExcludedPermissions()) {
            if (onlinePlayer.hasPermission(excluded)) {
                return false;
            }
        }

        for (String group : giveaway.getExcludedGroups()) {
            if (permission.playerInGroup(onlinePlayer, group)) {
                return false;
            }
        }

        return true;
    }

    private void executeRewardCommandsNow(Player player, GiveawayDefinition giveaway) {
        for (String command : giveaway.getRewardCommands()) {
            String parsed = command
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString());
            dispatchReward(parsed, giveaway.getId());
        }
    }

    private void dispatchReward(String command, String giveawayId) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException ex) {
            // Une commande en échec ne doit pas interrompre le tirage ni faire perdre les récompenses suivantes
            plugin.getLogger().warning("Commande de récompense en échec (giveaway " + giveawayId + ") '" + command + "' : " + ex.getMessage());
        }
    }

    private void queueRewardsForLater(UUID uuid, GiveawayDefinition giveaway) {
        List<PendingReward> queue = pendingRewards.computeIfAbsent(uuid, ignored -> new ArrayList<>());
        for (String command : giveaway.getRewardCommands()) {
            queue.add(new PendingReward(giveaway.getId(), command));
        }
    }

    private void processPendingRewards(Player player) {
        if (net != null) {
            claimPendingRewards(player, true);
            return;
        }
        List<PendingReward> queue = pendingRewards.remove(player.getUniqueId());
        if (queue == null || queue.isEmpty()) {
            return;
        }

        deliverRewards(player, queue, true);
        saveData();
    }

    /** Donne les récompenses au joueur : commandes console, message et son de félicitations. */
    private void deliverRewards(Player player, List<PendingReward> queue, boolean logDelivery) {
        Map<String, Integer> deliveredPerGiveaway = new HashMap<>();
        for (PendingReward reward : queue) {
            String parsed = reward.commandTemplate
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString());
            dispatchReward(parsed, reward.giveawayId);
            deliveredPerGiveaway.merge(reward.giveawayId, 1, Integer::sum);

            if (logDelivery) {
                ActionLog.write(plugin, WINNERS_LOG_FILE, "DELIVER | giveaway=" + reward.giveawayId + " | winner=" + printablePlayer(player) + " | delivery=on_login");
            }
        }

        for (Map.Entry<String, Integer> entry : deliveredPerGiveaway.entrySet()) {
            GiveawayDefinition giveaway = giveaways.get(entry.getKey());
            if (giveaway != null) {
                sendMessageList(player, "winner-personal-online", giveaway, player, player.getName(), 1);
            }
        }
        playConfiguredSound(player, "sounds.winner-direct");
    }

    private void scheduleJoinMessage(Player player, GiveawayDefinition giveaway, String path, String soundPath) {
        long delayTicks = Math.max(0L, giveaway.getJoinMessageDelaySeconds() * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline()) {
                return;
            }
            sendMessageList(online, path, giveaway, null, null, 0);
            playConfiguredSound(online, soundPath);
        }, delayTicks);
    }

    private void playConfiguredSoundForAll(String path) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            playConfiguredSound(player, path);
        }
    }

    private void playConfiguredSound(Player player, String path) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean(path + ".enabled", false)) {
            return;
        }

        String soundName = config.getString(path + ".sound", "");
        if (soundName == null || soundName.isBlank()) {
            return;
        }

        Sound sound = sound(soundName);
        if (sound == null) {
            plugin.getLogger().warning("Son invalide pour '" + path + "': " + soundName);
            return;
        }
        float volume = (float) config.getDouble(path + ".volume", 1.0D);
        float pitch = (float) config.getDouble(path + ".pitch", 1.0D);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private void appendHistoryEntry(GiveawayDefinition giveaway, String winnersText, int winnersCount) {
        String entry = "&7[" + LocalDateTime.now(zoneId).format(LOG_DATE_FORMAT) + "] "
            + "&e" + giveaway.getId()
            + " &8| &f" + giveaway.getPrizeDisplay()
            + " &8| &fGagnant(s): &e" + winnersCount
            + " &8| &f" + winnersText;
        historyEntries.add(0, entry);

        int max = Math.max(10, plugin.getConfig().getInt("settings.history-limit", 100));
        while (historyEntries.size() > max) {
            historyEntries.remove(historyEntries.size() - 1);
        }
    }

    private String printablePlayer(OfflinePlayer player) {
        return (player.getName() == null ? "unknown" : player.getName()) + "(" + player.getUniqueId() + ")";
    }

    private long calculateDelayTicks(LocalDateTime target) {
        // Calculé avec le fuseau : entre deux dates locales, un changement d'heure décalerait le tirage d'une heure
        Duration duration = Duration.between(ZonedDateTime.now(zoneId), target.atZone(zoneId));
        if (duration.isNegative() || duration.isZero()) {
            return 1L;
        }
        long ticks = duration.toMillis() / 50L;
        return Math.max(1L, ticks);
    }

    private void sendMessageList(Player player, String path, GiveawayDefinition giveaway, OfflinePlayer winner, String winnersText, int winnerCount) {
        List<String> messages = plugin.getMessages().getStringList(path);
        for (String line : messages) {
            player.sendMessage(color(applyPlaceholders(line, giveaway, winner, winnersText, winnerCount)));
        }
    }

    private void broadcastList(String path, GiveawayDefinition giveaway, OfflinePlayer winner, String winnersText, int winnerCount) {
        List<String> messages = plugin.getMessages().getStringList(path);
        for (String line : messages) {
            Bukkit.broadcastMessage(color(applyPlaceholders(line, giveaway, winner, winnersText, winnerCount)));
        }
    }

    private String applyPlaceholders(String input, GiveawayDefinition giveaway, OfflinePlayer winner, String winnersText, int winnerCount) {
        String winnerName = winner == null ? "" : (winner.getName() == null ? winner.getUniqueId().toString() : winner.getName());
        return applyPlaceholdersNamed(input, giveaway, winnerName, winnersText, winnerCount);
    }

    private String applyPlaceholdersNamed(String input, GiveawayDefinition giveaway, String winnerName, String winnersText, int winnerCount) {
        String safeWinners = winnersText == null ? winnerName : winnersText;

        return input
            .replace("%giveaway_id%", giveaway.getId())
            .replace("%prize%", giveaway.getPrizeDisplay())
            .replace("%amount%", String.valueOf(giveaway.getWinnersCount()))
            .replace("%draw_date%", giveaway.getDrawAt().format(dateFormat))
            .replace("%draw_time%", giveaway.getDrawAt().format(timeFormat))
            .replace("%winner%", winnerName)
            .replace("%winners%", safeWinners)
            .replace("%winner_count%", String.valueOf(Math.max(0, winnerCount)));
    }

    private String stringifyWinners(List<OfflinePlayer> winners) {
        List<String> names = new ArrayList<>();
        for (OfflinePlayer winner : winners) {
            names.add(winner.getName() == null ? winner.getUniqueId().toString() : winner.getName());
        }
        return String.join(", ", names);
    }

    private void loadDataFile() {
        dataFile = new File(plugin.getDataFolder(), "data.yml");

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Impossible de créer le dossier du plugin.");
        }

        if (!dataFile.exists()) {
            try {
                if (!dataFile.createNewFile()) {
                    plugin.getLogger().warning("Impossible de créer data.yml.");
                }
            } catch (IOException ex) {
                plugin.getLogger().severe("Erreur création data.yml: " + ex.getMessage());
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        if (net == null) {
            loadPendingRewards();
        }
        loadHistory();
    }

    private void loadPendingRewards() {
        ConfigurationSection section = dataConfig.getConfigurationSection("pending-rewards");
        if (section == null) {
            return;
        }

        for (String rawUuid : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                List<String> entries = dataConfig.getStringList("pending-rewards." + rawUuid);
                List<PendingReward> rewards = new ArrayList<>();

                for (String entry : entries) {
                    String[] parts = entry.split("\\|\\|", 2);
                    if (parts.length == 2) {
                        rewards.add(new PendingReward(parts[0], parts[1]));
                    }
                }

                if (!rewards.isEmpty()) {
                    pendingRewards.put(uuid, rewards);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void loadHistory() {
        historyEntries.clear();
        historyEntries.addAll(dataConfig.getStringList("history"));
    }

    /** Regroupe les écritures déclenchées par les connexions (data.yml est réécrit en entier sur le thread principal). */
    private void scheduleSave() {
        if (saveTask != null) {
            return;
        }
        saveTask = Bukkit.getScheduler().runTaskLater(plugin, this::saveData, 100L);
    }

    private void saveData() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (net == null) {
            for (Map.Entry<String, GiveawayState> entry : states.entrySet()) {
                String id = entry.getKey();
                GiveawayState state = entry.getValue();

                dataConfig.set("giveaways." + id + ".participants", stringifySet(state.getParticipants()));
                dataConfig.set("giveaways." + id + ".winners", stringifySet(state.getWinners()));
                dataConfig.set("giveaways." + id + ".notified", stringifySet(state.getNotified()));
                dataConfig.set("giveaways." + id + ".blacklisted", stringifySet(state.getBlacklisted()));
                dataConfig.set("giveaways." + id + ".round-key", state.getRoundKey());
                dataConfig.set("giveaways." + id + ".drawn", state.isDrawn());
            }

            dataConfig.set("pending-rewards", null);
            for (Map.Entry<UUID, List<PendingReward>> entry : pendingRewards.entrySet()) {
                List<String> serialized = new ArrayList<>();
                for (PendingReward reward : entry.getValue()) {
                    serialized.add(reward.giveawayId + "||" + reward.commandTemplate);
                }
                dataConfig.set("pending-rewards." + entry.getKey(), serialized);
            }
        }
        // Avec SHNetwork, seul l'historique reste dans data.yml : le reste est en base, et ce qui y était avant
        // (participants, récompenses en attente) est gardé tel quel pour /shgiveaway import
        dataConfig.set("history", historyEntries);

        try {
            dataConfig.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Impossible de sauvegarder data.yml: " + ex.getMessage());
        }
    }

    private List<String> stringifySet(Set<UUID> source) {
        List<String> values = new ArrayList<>();
        for (UUID uuid : source) {
            values.add(uuid.toString());
        }
        return values;
    }

    private OfflinePlayer resolveTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }

        try {
            UUID uuid = UUID.fromString(target);
            return Bukkit.getOfflinePlayer(uuid);
        } catch (IllegalArgumentException ignored) {
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(target);
        if (player == null) {
            return null;
        }
        if (!player.hasPlayedBefore() && !player.isOnline()) {
            return null;
        }
        return player;
    }

    private List<String> buildUuidListView(String title, String giveawayId, Set<UUID> source, int requestedPage) {
        int pageSize = 10;
        List<UUID> entries = new ArrayList<>(new LinkedHashSet<>(source));
        entries.sort(Comparator.comparing(UUID::toString));

        int total = entries.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int page = Math.min(Math.max(1, requestedPage), maxPage);

        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(total, fromIndex + pageSize);

        return formatUuidListView(title, giveawayId, total, page, maxPage, entries.subList(fromIndex, toIndex));
    }

    private List<String> formatUuidListView(String title, String giveawayId, int total, int page, int maxPage, List<UUID> shown) {
        List<String> lines = new ArrayList<>();
        lines.add(color("&8&m------------------------------------------------"));
        lines.add(color("&7&l« &6&lSH &8&l» &f" + title + " &8( &e" + giveawayId + " &8) &f: &e" + total));
        lines.add(color("&7Page &e" + page + "&7/&e" + maxPage));

        if (total == 0) {
            lines.add(color("&8• &7Aucun joueur."));
            lines.add(color("&8&m------------------------------------------------"));
            return lines;
        }

        for (UUID uuid : shown) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            String name = player.getName() == null ? "unknown" : player.getName();
            lines.add(color("&8• &e" + name + " &7(" + uuid + ")"));
        }
        lines.add(color("&8&m------------------------------------------------"));
        return lines;
    }

    /** Récompenses de gagnants pas encore données : un joueur et un giveaway par ligne, 10 lignes par page. */
    public void getPendingView(int requestedPage, Consumer<List<String>> done) {
        if (net == null) {
            List<GiveawayDb.PendingSummary> rows = new ArrayList<>();
            for (Map.Entry<UUID, List<PendingReward>> entry : pendingRewards.entrySet()) {
                Map<String, Integer> perGiveaway = new java.util.TreeMap<>();
                for (PendingReward reward : entry.getValue()) {
                    perGiveaway.merge(reward.giveawayId, 1, Integer::sum);
                }
                perGiveaway.forEach((id, count) -> rows.add(new GiveawayDb.PendingSummary(entry.getKey(), id, count)));
            }
            rows.sort(Comparator.comparing(GiveawayDb.PendingSummary::giveawayId).thenComparing(row -> row.uuid().toString()));
            done.accept(formatPendingView(rows, requestedPage));
            return;
        }
        net.db().async(() -> net.db().pendingSummary())
            .whenComplete((rows, error) -> net.bus().sync(() -> done.accept(error != null
                ? List.of(databaseError(error))
                : formatPendingView(rows, requestedPage))));
    }

    private List<String> formatPendingView(List<GiveawayDb.PendingSummary> rows, int requestedPage) {
        int pageSize = 10;
        int total = rows.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int page = Math.min(Math.max(1, requestedPage), maxPage);
        List<String> lines = new ArrayList<>();
        lines.add(plugin.message("admin.pending-header", "%total%", String.valueOf(total),
            "%page%", String.valueOf(page), "%pages%", String.valueOf(maxPage)));
        if (total == 0) {
            lines.add(plugin.message("admin.pending-empty"));
            return lines;
        }
        for (GiveawayDb.PendingSummary row : rows.subList((page - 1) * pageSize, Math.min(total, page * pageSize))) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(row.uuid());
            lines.add(plugin.message("admin.pending-line", "%player%", player.getName() == null ? "unknown" : player.getName(),
                "%uuid%", row.uuid().toString(), "%giveaway%", row.giveawayId(), "%count%", String.valueOf(row.count())));
        }
        return lines;
    }

    // ------------------------------------------------------------------ mode réseau (SHNetwork)

    /**
     * Passe en mode réseau : participants, gagnants et récompenses en attente sont en base partagée, le bus annonce
     * les tirages et le serveur meneur lance les tirages planifiés. À appeler avant {@link #load()}.
     */
    public void useNetwork(NetworkGiveaways network) {
        this.net = network;
        network.bus().listen(network.channel(), this::onNetMessage, true);
        network.candidate(this::syncRounds, this::cancelAllTasks);
    }

    private static String roundOf(GiveawayDefinition giveaway) {
        return giveaway.getRoundKey() == null ? "" : giveaway.getRoundKey();
    }

    /**
     * Lit en base le tour de chaque giveaway (le crée, ou passe au tour de la config si la date de tirage a changé),
     * puis, sur le serveur meneur, planifie les tirages. Appelée au chargement et quand ce serveur devient meneur.
     */
    private void syncRounds() {
        List<GiveawayDefinition> definitions = new ArrayList<>(giveaways.values());
        // Tirages déjà faits avant le mode réseau (data.yml) : un tour créé en base à leur place est déjà tiré
        Map<String, Boolean> legacyDrawn = new HashMap<>();
        for (GiveawayDefinition giveaway : definitions) {
            String path = "giveaways." + giveaway.getId();
            legacyDrawn.put(giveaway.getId(), dataConfig.getBoolean(path + ".drawn", false)
                && roundOf(giveaway).equals(dataConfig.getString(path + ".round-key", "")));
        }
        net.db().async(() -> {
            Map<String, GiveawayDb.RoundRow> rows = new HashMap<>();
            for (GiveawayDefinition giveaway : definitions) {
                rows.put(giveaway.getId(), net.db().syncRound(giveaway.getId(), roundOf(giveaway), legacyDrawn.get(giveaway.getId())));
            }
            return rows;
        }).whenComplete((rows, error) -> net.bus().sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Tours des giveaways illisibles dans la base partagée.", error);
                return;
            }
            for (GiveawayDefinition giveaway : definitions) {
                if (giveaways.get(giveaway.getId()) != giveaway) {
                    continue; // config rechargée entre-temps
                }
                GiveawayState state = getOrCreateState(giveaway.getId());
                GiveawayDb.RoundRow row = rows.get(giveaway.getId());
                state.setRoundKey(row.roundKey());
                state.setDrawn(row.drawn());
            }
            scheduleAll();
        }));
    }

    private record JoinMessage(GiveawayDefinition giveaway, String path, String soundPath) {
    }

    /**
     * Connexion d'un joueur, avec SHNetwork : participation et message « une seule fois » décidés en base, quel que
     * soit le serveur où il se connecte. Les conditions (permissions, temps de jeu) sont jugées ici, sur le fil principal.
     */
    private void handleJoinNetwork(Player player) {
        processPendingRewards(player);

        LocalDateTime now = LocalDateTime.now(zoneId);
        List<GiveawayDefinition> active = new ArrayList<>();
        Map<String, Boolean> eligible = new HashMap<>();
        for (GiveawayDefinition giveaway : giveaways.values()) {
            GiveawayState state = getOrCreateState(giveaway.getId());
            if (!giveaway.isEnabled() || state.isDrawn() || now.isAfter(giveaway.getDrawAt())) {
                continue;
            }
            active.add(giveaway);
            eligible.put(giveaway.getId(), isEligibleOnJoin(player, giveaway));
        }
        if (active.isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        net.db().async(() -> {
            GiveawayDb db = net.db();
            List<GiveawayDb.Entry> entries = db.entriesOf(uuid);
            List<JoinMessage> messages = new ArrayList<>();
            for (GiveawayDefinition giveaway : active) {
                String id = giveaway.getId();
                String round = roundOf(giveaway);
                if (entries.contains(new GiveawayDb.Entry(id, GiveawayDb.ALL_ROUNDS, GiveawayDb.BLACKLIST))) {
                    continue;
                }

                boolean participant = entries.contains(new GiveawayDb.Entry(id, round, GiveawayDb.PARTICIPANT));
                if (!participant && eligible.get(id)) {
                    db.add(id, round, GiveawayDb.PARTICIPANT, uuid);
                    if (giveaway.isAnnounceOnJoin() && db.add(id, round, GiveawayDb.NOTIFIED, uuid)) {
                        messages.add(new JoinMessage(giveaway, "join-added", "sounds.join-added"));
                    }
                    continue;
                }

                if (participant && giveaway.isAnnounceOnJoin() && db.add(id, round, GiveawayDb.NOTIFIED, uuid)) {
                    messages.add(new JoinMessage(giveaway, "join-already", "sounds.join-already"));
                }
            }
            return messages;
        }).whenComplete((messages, error) -> net.bus().sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Participation de " + player.getName() + " impossible, base partagée injoignable.", error);
                return;
            }
            for (JoinMessage message : messages) {
                scheduleJoinMessage(player, message.giveaway(), message.path(), message.soundPath());
            }
        }));
    }

    /**
     * Tirage, avec SHNetwork : la base désigne le seul serveur qui tire (le premier qui passe {@code drawn} de 0 à 1) ;
     * celui-là publie le résultat, tous les serveurs l'annoncent. Un échec laisse le giveaway à tirer et, pour un tirage
     * planifié, le meneur réessaie une minute plus tard.
     */
    private void drawNetwork(GiveawayDefinition giveaway, boolean manual) {
        Set<UUID> excluded = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!isEligibleAtDraw(online, giveaway)) {
                excluded.add(online.getUniqueId());
            }
        }
        String round = roundOf(giveaway);
        net.db().async(() -> net.db().draw(giveaway.getId(), round, giveaway.getWinnersCount(), excluded, giveaway.getRewardCommands()))
            .whenComplete((outcome, error) -> net.bus().sync(() -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Tirage du giveaway " + giveaway.getId() + " impossible, base partagée injoignable.", error);
                    if (!manual && net.isLeader()) {
                        cancelTask(giveaway.getId());
                        tasks.put(giveaway.getId(), Bukkit.getScheduler().runTaskLater(plugin, () -> draw(giveaway.getId(), false), 1200L));
                    }
                    return;
                }
                if (!outcome.claimed()) {
                    // Un autre serveur a tiré avant : son annonce arrive par le bus
                    getOrCreateState(giveaway.getId()).setDrawn(true);
                    cancelTask(giveaway.getId());
                    return;
                }
                publishDraw(giveaway, round, outcome.winners());
                if (manual) {
                    plugin.getLogger().info("Giveaway " + giveaway.getId() + " tiré manuellement.");
                }
            }));
    }

    private String nameOf(UUID uuid) {
        Optional<String> online = net.bus().onlineName(uuid);
        if (online.isPresent()) {
            return online.get();
        }
        String known = Bukkit.getOfflinePlayer(uuid).getName();
        return known == null ? uuid.toString() : known;
    }

    private void publishDraw(GiveawayDefinition giveaway, String round, List<UUID> winners) {
        JsonObject message = new JsonObject();
        message.addProperty("event", "draw");
        message.addProperty("id", giveaway.getId());
        message.addProperty("round", round);
        JsonArray list = new JsonArray();
        for (UUID uuid : winners) {
            String name = nameOf(uuid);
            JsonObject winner = new JsonObject();
            winner.addProperty("uuid", uuid.toString());
            winner.addProperty("name", name);
            list.add(winner);
            boolean online = net.bus().onlineName(uuid).isPresent();
            ActionLog.write(plugin, WINNERS_LOG_FILE, "DRAW | giveaway=" + giveaway.getId() + " | winner=" + name + "(" + uuid + ") | delivery=" + (online ? "immediate" : "queued_on_login"));
        }
        message.add("winners", list);
        net.bus().publish(net.channel(), message);
    }

    /** Fil principal : message du bus. */
    private void onNetMessage(JsonObject o) {
        try {
            JsonElement event = o.get("event");
            if (event != null && "draw".equals(event.getAsString())) {
                onDraw(o);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Message illisible sur " + net.channel() + " : " + e);
        }
    }

    /** Tirage annoncé par le serveur qui l'a fait : annonce, sons, historique, récompenses des gagnants connectés ici. */
    private void onDraw(JsonObject o) {
        String id = o.get("id").getAsString();
        String round = o.get("round").getAsString();
        GiveawayDefinition giveaway = giveaways.get(id);
        if (giveaway == null || !roundOf(giveaway).equals(round) || !announced.add(id + "|" + round)) {
            return;
        }

        getOrCreateState(id).setDrawn(true);
        cancelTask(id);

        List<UUID> uuids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (JsonElement element : o.getAsJsonArray("winners")) {
            JsonObject winner = element.getAsJsonObject();
            uuids.add(UUID.fromString(winner.get("uuid").getAsString()));
            names.add(winner.get("name").getAsString());
        }

        if (uuids.isEmpty()) {
            broadcastList("no-participants", giveaway, null, null, 0);
            playConfiguredSoundForAll("sounds.no-participants");
            return;
        }

        String winnersText = String.join(", ", names);
        for (String line : plugin.getMessages().getStringList("winner-announcement")) {
            Bukkit.broadcastMessage(color(applyPlaceholdersNamed(line, giveaway, names.get(0), winnersText, names.size())));
        }
        playConfiguredSoundForAll("sounds.winner-announcement");
        appendHistoryEntry(giveaway, winnersText, names.size());
        saveData();

        for (UUID uuid : uuids) {
            Player winner = Bukkit.getPlayer(uuid);
            if (winner != null) {
                claimPendingRewards(winner, false);
            }
        }
    }

    /**
     * Réclame en base les récompenses en attente du joueur, une seule fois même si deux serveurs réclament en même
     * temps, puis les lui donne. S'il est parti entre-temps, elles sont remises en attente.
     */
    private void claimPendingRewards(Player player, boolean logDelivery) {
        UUID uuid = player.getUniqueId();
        net.db().async(() -> net.db().claimPending(uuid)).whenComplete((rows, error) -> net.bus().sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Récompenses en attente de " + player.getName() + " illisibles, base partagée injoignable.", error);
                return;
            }
            if (rows.isEmpty()) {
                return;
            }
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                net.db().async(() -> {
                    net.db().requeue(uuid, rows);
                    return null;
                }).exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Récompenses de " + uuid + " non remises en attente : " + rows, failure);
                    return null;
                });
                return;
            }
            List<PendingReward> queue = new ArrayList<>();
            for (GiveawayDb.PendingRow row : rows) {
                queue.add(new PendingReward(row.giveawayId(), row.command()));
            }
            deliverRewards(online, queue, logDelivery);
        }));
    }

    /**
     * Reprend dans la base partagée ce que data.yml gardait avant le mode réseau : participants, gagnants, joueurs
     * prévenus, joueurs exclus, tirages faits et récompenses en attente. Peut être relancée sans rien compter deux fois.
     *
     * @param done reçoit {lignes de joueurs reprises, récompenses reprises}, ou null en cas d'échec
     */
    public void importLocalData(Consumer<int[]> done) {
        if (net == null) {
            done.accept(null);
            return;
        }
        List<String[]> players = new ArrayList<>();
        List<String[]> pending = new ArrayList<>();
        List<String[]> drawn = new ArrayList<>();
        Map<String, String> rounds = new HashMap<>();

        ConfigurationSection section = dataConfig.getConfigurationSection("giveaways");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String path = "giveaways." + id;
                String round = dataConfig.getString(path + ".round-key", "");
                rounds.put(id, round);
                addImportRows(players, id, round, GiveawayDb.PARTICIPANT, dataConfig.getStringList(path + ".participants"));
                addImportRows(players, id, round, GiveawayDb.WINNER, dataConfig.getStringList(path + ".winners"));
                addImportRows(players, id, round, GiveawayDb.NOTIFIED, dataConfig.getStringList(path + ".notified"));
                addImportRows(players, id, GiveawayDb.ALL_ROUNDS, GiveawayDb.BLACKLIST, dataConfig.getStringList(path + ".blacklisted"));
                if (dataConfig.getBoolean(path + ".drawn", false)) {
                    drawn.add(new String[]{id, round});
                }
            }
        }

        ConfigurationSection queued = dataConfig.getConfigurationSection("pending-rewards");
        if (queued != null) {
            for (String rawUuid : queued.getKeys(false)) {
                try {
                    UUID.fromString(rawUuid);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                Map<String, Integer> next = new HashMap<>();
                for (String entry : dataConfig.getStringList("pending-rewards." + rawUuid)) {
                    String[] parts = entry.split("\\|\\|", 2);
                    if (parts.length == 2) {
                        int seq = next.merge(parts[0], 1, Integer::sum) - 1;
                        pending.add(new String[]{rawUuid, parts[0], rounds.getOrDefault(parts[0], ""), String.valueOf(seq), parts[1]});
                    }
                }
            }
        }

        net.db().async(() -> net.db().importRows(players, pending, drawn)).whenComplete((counts, error) -> net.bus().sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Reprise de data.yml impossible.", error);
                done.accept(null);
                return;
            }
            done.accept(counts);
        }));
    }

    private static void addImportRows(List<String[]> rows, String giveawayId, String round, String kind, List<String> uuids) {
        for (String raw : uuids) {
            try {
                rows.add(new String[]{giveawayId, round, kind, UUID.fromString(raw).toString()});
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void cancelTask(String id) {
        BukkitTask task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private void cancelAllTasks() {
        for (BukkitTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }

    private static class PendingReward {
        private final String giveawayId;
        private final String commandTemplate;

        private PendingReward(String giveawayId, String commandTemplate) {
            this.giveawayId = giveawayId;
            this.commandTemplate = commandTemplate;
        }
    }
}
