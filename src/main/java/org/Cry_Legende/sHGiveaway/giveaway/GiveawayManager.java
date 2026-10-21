package org.Cry_Legende.sHGiveaway.giveaway;

import net.milkbowl.vault.permission.Permission;
import org.Cry_Legende.sHGiveaway.SHGiveaway;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class GiveawayManager {

    private static final DateTimeFormatter DRAW_INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SHGiveaway plugin;
    private final Permission permission;

    private final Map<String, GiveawayDefinition> giveaways = new HashMap<>();
    private final Map<String, GiveawayState> states = new HashMap<>();
    private final Map<String, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, List<PendingReward>> pendingRewards = new HashMap<>();
    private final List<String> historyEntries = new ArrayList<>();

    private File dataFile;
    private FileConfiguration dataConfig;
    private File winnersLogFile;

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
        plugin.reloadConfig();
        loadFormatting();
        loadDataFile();
        loadGiveaways();
    }

    public void reload() {
        cancelAllTasks();
        giveaways.clear();
        states.clear();
        pendingRewards.clear();
        historyEntries.clear();
        load();
        scheduleAll();
    }

    public void shutdown() {
        cancelAllTasks();
        saveData();
    }

    public void scheduleAll() {
        for (GiveawayDefinition giveaway : giveaways.values()) {
            GiveawayState state = getOrCreateState(giveaway.getId());
            if (!giveaway.isEnabled() || state.isDrawn()) {
                continue;
            }

            long delayTicks = calculateDelayTicks(giveaway.getDrawAt());
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> draw(giveaway.getId(), false), delayTicks);
            tasks.put(giveaway.getId(), task);
        }
    }

    public void handleJoin(Player player) {
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
                    scheduleJoinMessage(player, giveaway, "messages.join-added", "sounds.join-added");
                }
                saveData();
                continue;
            }

            if (participant && giveaway.isAnnounceOnJoin() && state.getNotified().add(uuid)) {
                scheduleJoinMessage(player, giveaway, "messages.join-already", "sounds.join-already");
                saveData();
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

        List<UUID> candidates = collectCandidates(giveaway, state);
        if (candidates.isEmpty()) {
            state.setDrawn(true);
            saveData();
            broadcastList("messages.no-participants", giveaway, null, null, 0);
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
                sendMessageList(onlineWinner, "messages.winner-personal-online", giveaway, winner, winner.getName(), 1);
                playConfiguredSound(onlineWinner, "sounds.winner-direct");
                appendWinnersLog("DRAW | giveaway=" + giveaway.getId() + " | winner=" + printablePlayer(winner) + " | delivery=immediate");
            } else {
                queueRewardsForLater(winnerUuid, giveaway);
                appendWinnersLog("DRAW | giveaway=" + giveaway.getId() + " | winner=" + printablePlayer(winner) + " | delivery=queued_on_login");
            }
        }

        String winnersText = stringifyWinners(selectedWinners);
        OfflinePlayer firstWinner = selectedWinners.get(0);
        broadcastList("messages.winner-announcement", giveaway, firstWinner, winnersText, selectedWinners.size());
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
        return color("&7&l« &6&lSH &8&l» &fID: &e" + giveaway.getId()
            + " &8| &fParticipants: &e" + state.getParticipants().size()
            + " &8| &fBlacklist: &e" + state.getBlacklisted().size()
            + " &8| &fGagnants: &e" + state.getWinners().size()
            + " &8| &fTiré: &e" + (state.isDrawn() ? "oui" : "non"));
    }

    public List<String> getActiveGiveawaysView() {
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
            GiveawayState state = getOrCreateState(giveaway.getId());
            lines.add(color("&8• &e" + giveaway.getId()
                + " &7| &f" + giveaway.getPrizeDisplay()
                + " &7| &fTirage: &e" + giveaway.getDrawAt().format(dateFormat) + " " + giveaway.getDrawAt().format(timeFormat)
                + " &7| &fParticipants: &e" + state.getParticipants().size()));
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
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
    }

    private void queueRewardsForLater(UUID uuid, GiveawayDefinition giveaway) {
        List<PendingReward> queue = pendingRewards.computeIfAbsent(uuid, ignored -> new ArrayList<>());
        for (String command : giveaway.getRewardCommands()) {
            queue.add(new PendingReward(giveaway.getId(), command));
        }
    }

    private void processPendingRewards(Player player) {
        List<PendingReward> queue = pendingRewards.remove(player.getUniqueId());
        if (queue == null || queue.isEmpty()) {
            return;
        }

        Map<String, Integer> deliveredPerGiveaway = new HashMap<>();
        for (PendingReward reward : queue) {
            String parsed = reward.commandTemplate
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            deliveredPerGiveaway.merge(reward.giveawayId, 1, Integer::sum);

            appendWinnersLog("DELIVER | giveaway=" + reward.giveawayId + " | winner=" + printablePlayer(player) + " | delivery=on_login");
        }

        for (Map.Entry<String, Integer> entry : deliveredPerGiveaway.entrySet()) {
            GiveawayDefinition giveaway = giveaways.get(entry.getKey());
            if (giveaway != null) {
                sendMessageList(player, "messages.winner-personal-online", giveaway, player, player.getName(), 1);
            }
        }
        playConfiguredSound(player, "sounds.winner-direct");
        saveData();
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

        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
            float volume = (float) config.getDouble(path + ".volume", 1.0D);
            float pitch = (float) config.getDouble(path + ".pitch", 1.0D);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Son invalide pour '" + path + "': " + soundName);
        }
    }

    private void appendWinnersLog(String line) {
        String formattedLine = "[" + LocalDateTime.now(zoneId).format(LOG_DATE_FORMAT) + "] " + line + System.lineSeparator();
        try {
            Files.writeString(
                winnersLogFile.toPath(),
                formattedLine,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ex) {
            plugin.getLogger().warning("Impossible d'écrire dans winners.log: " + ex.getMessage());
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
        LocalDateTime now = LocalDateTime.now(zoneId);
        Duration duration = Duration.between(now, target);
        if (duration.isNegative() || duration.isZero()) {
            return 1L;
        }
        long ticks = duration.toMillis() / 50L;
        return Math.max(1L, ticks);
    }

    private void sendMessageList(Player player, String path, GiveawayDefinition giveaway, OfflinePlayer winner, String winnersText, int winnerCount) {
        List<String> messages = plugin.getConfig().getStringList(path);
        for (String line : messages) {
            player.sendMessage(color(applyPlaceholders(line, giveaway, winner, winnersText, winnerCount)));
        }
    }

    private void broadcastList(String path, GiveawayDefinition giveaway, OfflinePlayer winner, String winnersText, int winnerCount) {
        List<String> messages = plugin.getConfig().getStringList(path);
        for (String line : messages) {
            Bukkit.broadcastMessage(color(applyPlaceholders(line, giveaway, winner, winnersText, winnerCount)));
        }
    }

    private String applyPlaceholders(String input, GiveawayDefinition giveaway, OfflinePlayer winner, String winnersText, int winnerCount) {
        String winnerName = winner == null ? "" : (winner.getName() == null ? winner.getUniqueId().toString() : winner.getName());
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
        winnersLogFile = new File(plugin.getDataFolder(), "winners.log");

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

        if (!winnersLogFile.exists()) {
            try {
                if (!winnersLogFile.createNewFile()) {
                    plugin.getLogger().warning("Impossible de créer winners.log.");
                }
            } catch (IOException ex) {
                plugin.getLogger().severe("Erreur création winners.log: " + ex.getMessage());
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadPendingRewards();
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

    private void saveData() {
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

        List<String> lines = new ArrayList<>();
        lines.add(color("&8&m------------------------------------------------"));
        lines.add(color("&7&l« &6&lSH &8&l» &f" + title + " &8( &e" + giveawayId + " &8) &f: &e" + total));
        lines.add(color("&7Page &e" + page + "&7/&e" + maxPage));

        if (total == 0) {
            lines.add(color("&8• &7Aucun joueur."));
            lines.add(color("&8&m------------------------------------------------"));
            return lines;
        }

        for (int i = fromIndex; i < toIndex; i++) {
            UUID uuid = entries.get(i);
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            String name = player.getName() == null ? "unknown" : player.getName();
            lines.add(color("&8• &e" + name + " &7(" + uuid + ")"));
        }
        lines.add(color("&8&m------------------------------------------------"));
        return lines;
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
