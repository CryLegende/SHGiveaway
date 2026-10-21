package org.Cry_Legende.sHGiveaway.command;

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

public class GiveawayCommand implements CommandExecutor, TabCompleter {

    private final GiveawayManager giveawayManager;

    public GiveawayCommand(GiveawayManager giveawayManager) {
        this.giveawayManager = giveawayManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("shgiveaway.admin")) {
            sender.sendMessage(giveawayManager.color("&cVous n'avez pas la permission."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            giveawayManager.reload();
            sender.sendMessage(giveawayManager.color("&aConfiguration rechargée."));
            return true;
        }

        if (sub.equals("draw")) {
            if (args.length < 2) {
                sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway draw <id>"));
                return true;
            }

            String id = args[1].toLowerCase(Locale.ROOT);
            if (!giveawayManager.hasGiveaway(id)) {
                sender.sendMessage(giveawayManager.color("&cGiveaway introuvable: " + id));
                return true;
            }

            giveawayManager.draw(id, true);
            sender.sendMessage(giveawayManager.color("&aTirage effectué pour " + id + "."));
            return true;
        }

        if (sub.equals("status")) {
            if (args.length < 2) {
                sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway status <id>"));
                return true;
            }

            sender.sendMessage(giveawayManager.getStatus(args[1].toLowerCase(Locale.ROOT)));
            return true;
        }

        if (sub.equals("active")) {
            for (String line : giveawayManager.getActiveGiveawaysView()) {
                sender.sendMessage(line);
            }
            return true;
        }

        if (sub.equals("history")) {
            int page = parsePage(args, 1);
            for (String line : giveawayManager.getHistoryView(page)) {
                sender.sendMessage(line);
            }
            return true;
        }

        if (sub.equals("participants")) {
            if (args.length < 2) {
                sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway participants <id> [page]"));
                return true;
            }

            int page = parsePage(args, 2);
            for (String line : giveawayManager.getParticipantsView(args[1].toLowerCase(Locale.ROOT), page)) {
                sender.sendMessage(line);
            }
            return true;
        }

        if (sub.equals("add")) {
            if (args.length < 3) {
                sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway add <id> <joueur|uuid>"));
                return true;
            }

            GiveawayManager.AdminEditResult result = giveawayManager.addParticipantManually(args[1].toLowerCase(Locale.ROOT), args[2]);
            sender.sendMessage(formatManageResult(result, args[2], "ajouté", "déjà inscrit", "dans la blacklist"));
            return true;
        }

        if (sub.equals("remove")) {
            if (args.length < 3) {
                sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway remove <id> <joueur|uuid>"));
                return true;
            }

            GiveawayManager.AdminEditResult result = giveawayManager.removeParticipantManually(args[1].toLowerCase(Locale.ROOT), args[2]);
            sender.sendMessage(formatManageResult(result, args[2], "retiré", "pas inscrit", "dans la blacklist"));
            return true;
        }

        if (sub.equals("blacklist")) {
            if (args.length < 2) {
                sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway blacklist <add|remove|list> <id> [joueur|page]"));
                return true;
            }

            String action = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("list")) {
                if (args.length < 3) {
                    sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway blacklist list <id> [page]"));
                    return true;
                }

                int page = parsePage(args, 3);
                for (String line : giveawayManager.getBlacklistView(args[2].toLowerCase(Locale.ROOT), page)) {
                    sender.sendMessage(line);
                }
                return true;
            }

            if (action.equals("add")) {
                if (args.length < 4) {
                    sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway blacklist add <id> <joueur|uuid>"));
                    return true;
                }

                GiveawayManager.AdminEditResult result = giveawayManager.blacklistPlayer(args[2].toLowerCase(Locale.ROOT), args[3]);
                sender.sendMessage(formatBlacklistResult(result, args[3], true));
                return true;
            }

            if (action.equals("remove")) {
                if (args.length < 4) {
                    sender.sendMessage(giveawayManager.color("&cUtilisation: /shgiveaway blacklist remove <id> <joueur|uuid>"));
                    return true;
                }

                GiveawayManager.AdminEditResult result = giveawayManager.unblacklistPlayer(args[2].toLowerCase(Locale.ROOT), args[3]);
                sender.sendMessage(formatBlacklistResult(result, args[3], false));
                return true;
            }

            sender.sendMessage(giveawayManager.color("&cAction blacklist inconnue: " + action));
            return true;
        }

        sender.sendMessage(giveawayManager.color("&cSous-commande inconnue."));
        sendHelp(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (!sender.hasPermission("shgiveaway.admin")) {
            return suggestions;
        }

        if (args.length == 1) {
            addIfMatches(suggestions, args[0], "reload");
            addIfMatches(suggestions, args[0], "draw");
            addIfMatches(suggestions, args[0], "status");
            addIfMatches(suggestions, args[0], "active");
            addIfMatches(suggestions, args[0], "history");
            addIfMatches(suggestions, args[0], "participants");
            addIfMatches(suggestions, args[0], "add");
            addIfMatches(suggestions, args[0], "remove");
            addIfMatches(suggestions, args[0], "blacklist");
            return suggestions;
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("blacklist")) {
                addIfMatches(suggestions, args[1], "add");
                addIfMatches(suggestions, args[1], "remove");
                addIfMatches(suggestions, args[1], "list");
                return suggestions;
            }

            if (isGiveawaySubcommand(args[0])) {
                addGiveawayIds(suggestions, args[1]);
                return suggestions;
            }

            if (args[0].equalsIgnoreCase("history")) {
                addIfMatches(suggestions, args[1], "1");
                return suggestions;
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("blacklist")) {
                if (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("list")) {
                    addGiveawayIds(suggestions, args[2]);
                }
                if (args[1].equalsIgnoreCase("list")) {
                    addIfMatches(suggestions, args[2], "1");
                }
                return suggestions;
            }

            if (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")) {
                addOnlinePlayers(suggestions, args[2]);
                return suggestions;
            }
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("blacklist") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            addOnlinePlayers(suggestions, args[3]);
        }

        return suggestions;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(giveawayManager.color("&e/shgiveaway reload"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway draw <id>"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway status <id>"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway active"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway history [page]"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway participants <id> [page]"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway add <id> <joueur|uuid>"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway remove <id> <joueur|uuid>"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway blacklist add <id> <joueur|uuid>"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway blacklist remove <id> <joueur|uuid>"));
        sender.sendMessage(giveawayManager.color("&e/shgiveaway blacklist list <id> [page]"));
    }

    private boolean isGiveawaySubcommand(String sub) {
        return sub.equalsIgnoreCase("draw")
            || sub.equalsIgnoreCase("status")
            || sub.equalsIgnoreCase("participants")
            || sub.equalsIgnoreCase("add")
            || sub.equalsIgnoreCase("remove");
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

    private String formatManageResult(GiveawayManager.AdminEditResult result, String target, String successVerb, String alreadyText, String blacklistedText) {
        return switch (result) {
            case SUCCESS -> giveawayManager.color("&aJoueur " + target + " " + successVerb + " avec succès.");
            case GIVEAWAY_NOT_FOUND -> giveawayManager.color("&cGiveaway introuvable.");
            case PLAYER_NOT_FOUND -> giveawayManager.color("&cJoueur introuvable (ou jamais connecté).");
            case ALREADY_PRESENT -> giveawayManager.color("&eJoueur " + target + " " + alreadyText + ".");
            case NOT_PRESENT -> giveawayManager.color("&eJoueur " + target + " " + alreadyText + ".");
            case BLACKLISTED -> giveawayManager.color("&cImpossible, ce joueur est " + blacklistedText + ".");
            case ALREADY_BLACKLISTED -> giveawayManager.color("&eJoueur déjà blacklisté.");
            case NOT_BLACKLISTED -> giveawayManager.color("&eJoueur non blacklisté.");
        };
    }

    private String formatBlacklistResult(GiveawayManager.AdminEditResult result, String target, boolean add) {
        if (add) {
            return switch (result) {
                case SUCCESS -> giveawayManager.color("&aJoueur " + target + " blacklisté avec succès.");
                case GIVEAWAY_NOT_FOUND -> giveawayManager.color("&cGiveaway introuvable.");
                case PLAYER_NOT_FOUND -> giveawayManager.color("&cJoueur introuvable (ou jamais connecté).");
                case ALREADY_BLACKLISTED -> giveawayManager.color("&eJoueur déjà blacklisté.");
                default -> giveawayManager.color("&cAction impossible.");
            };
        }

        return switch (result) {
            case SUCCESS -> giveawayManager.color("&aJoueur " + target + " retiré de la blacklist.");
            case GIVEAWAY_NOT_FOUND -> giveawayManager.color("&cGiveaway introuvable.");
            case PLAYER_NOT_FOUND -> giveawayManager.color("&cJoueur introuvable (ou jamais connecté).");
            case NOT_BLACKLISTED -> giveawayManager.color("&eCe joueur n'est pas blacklisté.");
            default -> giveawayManager.color("&cAction impossible.");
        };
    }

    private void addIfMatches(List<String> target, String input, String suggestion) {
        if (suggestion.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) {
            target.add(suggestion);
        }
    }
}
