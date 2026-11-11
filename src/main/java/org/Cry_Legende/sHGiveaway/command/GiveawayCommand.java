package org.Cry_Legende.sHGiveaway.command;

import org.Cry_Legende.sHGiveaway.SHGiveaway;
import org.Cry_Legende.sHGiveaway.giveaway.GiveawayManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * /shgiveaway : administration des giveaways. Chaque sous-commande a sa permission shgiveaway.admin.&lt;nom&gt;.
 */
public class GiveawayCommand implements CommandExecutor, TabCompleter {

    /** Sous-commandes, dans l'ordre d'affichage de la ligne d'usage. */
    private static final List<String> SUBCOMMANDS = List.of(
        "reload", "draw", "status", "active", "history", "participants", "add", "remove", "blacklist", "import", "pending");

    private final SHGiveaway plugin;
    private final GiveawayManager giveawayManager;

    public GiveawayCommand(SHGiveaway plugin, GiveawayManager giveawayManager) {
        this.plugin = plugin;
        this.giveawayManager = giveawayManager;
    }

    private static String permission(String sub) {
        return "shgiveaway.admin." + sub;
    }

    /** Sous-commandes que l'expéditeur a le droit d'utiliser. */
    private static List<String> allowed(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            if (sender.hasPermission(permission(sub))) {
                names.add(sub);
            }
        }
        return names;
    }

    private void send(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(plugin.message("admin." + key, replacements));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            send(sender, "no-permission");
            return true;
        }

        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) {
            send(sender, "usage", "%subcommands%", String.join("|", allowed));
            return true;
        }

        switch (sub) {
            case "reload" -> reload(sender);
            case "draw" -> draw(sender, args);
            case "import" -> giveawayManager.importLocalData(counts -> {
                if (counts == null) {
                    send(sender, "import-unavailable");
                } else {
                    send(sender, "import-done", "%players%", String.valueOf(counts[0]), "%rewards%", String.valueOf(counts[1]));
                }
            });
            case "status" -> {
                if (args.length < 2) {
                    send(sender, "status-usage");
                } else {
                    giveawayManager.getStatus(args[1].toLowerCase(Locale.ROOT), sender::sendMessage);
                }
            }
            case "active" -> giveawayManager.getActiveGiveawaysView(lines -> lines.forEach(sender::sendMessage));
            case "history" -> {
                for (String line : giveawayManager.getHistoryView(parsePage(args, 1))) {
                    sender.sendMessage(line);
                }
            }
            case "pending" -> giveawayManager.getPendingView(parsePage(args, 1), lines -> lines.forEach(sender::sendMessage));
            case "participants" -> {
                if (args.length < 2) {
                    send(sender, "participants-usage");
                } else {
                    giveawayManager.getParticipantsView(args[1].toLowerCase(Locale.ROOT), parsePage(args, 2),
                        lines -> lines.forEach(sender::sendMessage));
                }
            }
            case "add" -> {
                if (args.length < 3) {
                    send(sender, "add-usage");
                } else {
                    giveawayManager.addParticipantManually(args[1].toLowerCase(Locale.ROOT), args[2],
                        result -> sender.sendMessage(manageResult(result, args[2], true)));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    send(sender, "remove-usage");
                } else {
                    giveawayManager.removeParticipantManually(args[1].toLowerCase(Locale.ROOT), args[2],
                        result -> sender.sendMessage(manageResult(result, args[2], false)));
                }
            }
            case "blacklist" -> blacklist(sender, args);
            default -> send(sender, "usage", "%subcommands%", String.join("|", allowed));
        }
        return true;
    }

    private void reload(CommandSender sender) {
        try {
            plugin.applyFiles(plugin.readFiles());
        } catch (SHGiveaway.ConfigException e) {
            plugin.getLogger().log(Level.SEVERE, "Rechargement annulé, " + e.getFile() + " illisible : " + e.getMessage());
            send(sender, "reload-failed", "%file%", e.getFile(), "%error%", String.valueOf(e.getMessage()));
            return;
        }
        giveawayManager.reload();
        send(sender, "reloaded");
    }

    private void draw(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "draw-usage");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!giveawayManager.hasGiveaway(id)) {
            send(sender, "draw-not-found", "%id%", id);
            return;
        }
        giveawayManager.draw(id, true);
        send(sender, "draw-done", "%id%", id);
    }

    private void blacklist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "blacklist-usage");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                if (args.length < 3) {
                    send(sender, "blacklist-list-usage");
                    return;
                }
                giveawayManager.getBlacklistView(args[2].toLowerCase(Locale.ROOT), parsePage(args, 3),
                    lines -> lines.forEach(sender::sendMessage));
            }
            case "add" -> {
                if (args.length < 4) {
                    send(sender, "blacklist-add-usage");
                    return;
                }
                giveawayManager.blacklistPlayer(args[2].toLowerCase(Locale.ROOT), args[3],
                    result -> sender.sendMessage(blacklistResult(result, args[3], true)));
            }
            case "remove" -> {
                if (args.length < 4) {
                    send(sender, "blacklist-remove-usage");
                    return;
                }
                giveawayManager.unblacklistPlayer(args[2].toLowerCase(Locale.ROOT), args[3],
                    result -> sender.sendMessage(blacklistResult(result, args[3], false)));
            }
            default -> send(sender, "blacklist-unknown", "%action%", action);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            return suggestions;
        }

        if (args.length == 1) {
            allowed.forEach(sub -> addIfMatches(suggestions, args[0], sub));
            return suggestions;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) {
            return suggestions;
        }

        if (args.length == 2) {
            if (sub.equals("blacklist")) {
                addIfMatches(suggestions, args[1], "add");
                addIfMatches(suggestions, args[1], "remove");
                addIfMatches(suggestions, args[1], "list");
            } else if (isGiveawaySubcommand(sub)) {
                addGiveawayIds(suggestions, args[1]);
            } else if (sub.equals("history") || sub.equals("pending")) {
                addIfMatches(suggestions, args[1], "1");
            }
            return suggestions;
        }

        if (args.length == 3) {
            if (sub.equals("blacklist")) {
                String action = args[1].toLowerCase(Locale.ROOT);
                if (action.equals("add") || action.equals("remove") || action.equals("list")) {
                    addGiveawayIds(suggestions, args[2]);
                }
                if (action.equals("list")) {
                    addIfMatches(suggestions, args[2], "1");
                }
            } else if (sub.equals("add") || sub.equals("remove")) {
                addOnlinePlayers(suggestions, args[2]);
            }
            return suggestions;
        }

        if (args.length == 4 && sub.equals("blacklist")
            && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            addOnlinePlayers(suggestions, args[3]);
        }

        return suggestions;
    }

    private boolean isGiveawaySubcommand(String sub) {
        return sub.equals("draw") || sub.equals("status") || sub.equals("participants") || sub.equals("add") || sub.equals("remove");
    }

    private void addGiveawayIds(List<String> suggestions, String input) {
        for (String id : giveawayManager.getGiveawayIds()) {
            addIfMatches(suggestions, input, id);
        }
    }

    private void addOnlinePlayers(List<String> suggestions, String input) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            addIfMatches(suggestions, input, online.getName());
        }
    }

    private int parsePage(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }

        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private String manageResult(GiveawayManager.AdminEditResult result, String target, boolean add) {
        return switch (result) {
            case SUCCESS -> plugin.message(add ? "admin.add-success" : "admin.remove-success", "%player%", target);
            case GIVEAWAY_NOT_FOUND -> plugin.message("admin.giveaway-not-found");
            case PLAYER_NOT_FOUND -> plugin.message("admin.player-not-found");
            case ALREADY_PRESENT, NOT_PRESENT -> plugin.message(add ? "admin.add-already" : "admin.remove-not-present", "%player%", target);
            case BLACKLISTED -> plugin.message("admin.blacklisted");
            case ALREADY_BLACKLISTED -> plugin.message("admin.already-blacklisted");
            case NOT_BLACKLISTED -> plugin.message("admin.not-blacklisted");
        };
    }

    private String blacklistResult(GiveawayManager.AdminEditResult result, String target, boolean add) {
        return switch (result) {
            case SUCCESS -> plugin.message(add ? "admin.blacklist-add-success" : "admin.blacklist-remove-success", "%player%", target);
            case GIVEAWAY_NOT_FOUND -> plugin.message("admin.giveaway-not-found");
            case PLAYER_NOT_FOUND -> plugin.message("admin.player-not-found");
            case ALREADY_BLACKLISTED -> plugin.message("admin.already-blacklisted");
            case NOT_BLACKLISTED -> plugin.message("admin.not-blacklisted");
            default -> plugin.message("admin.action-impossible");
        };
    }

    private void addIfMatches(List<String> target, String input, String suggestion) {
        if (suggestion.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) {
            target.add(suggestion);
        }
    }
}
