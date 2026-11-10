package org.Cry_Legende.sHGiveaway;

import org.Cry_Legende.sHGiveaway.command.GiveawayCommand;
import org.Cry_Legende.sHGiveaway.giveaway.GiveawayManager;
import org.Cry_Legende.sHGiveaway.network.NetworkGiveaways;
import org.Cry_Legende.sHGiveaway.listener.PlayerJoinListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.permission.Permission;

import java.util.logging.Level;

public final class SHGiveaway extends JavaPlugin {

    private Permission permission;
    private GiveawayManager giveawayManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!setupVault()) {
            getLogger().severe("Vault n'est pas disponible ou aucun provider de permissions n'a ete trouve.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        giveawayManager = new GiveawayManager(this, permission);
        if (!enableNetwork()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        giveawayManager.load();
        giveawayManager.scheduleAll();

        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(giveawayManager), this);

        PluginCommand command = getCommand("shgiveaway");
        if (command != null) {
            GiveawayCommand giveawayCommand = new GiveawayCommand(giveawayManager);
            command.setExecutor(giveawayCommand);
            command.setTabCompleter(giveawayCommand);
        }

        getLogger().info("SHGiveaway active.");
    }

    @Override
    public void onDisable() {
        if (giveawayManager != null) {
            giveawayManager.shutdown();
        }
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et que reseau.actif est vrai. Les classes qui touchent à SHNetwork
     * ne sont chargées que dans ce cas. Faux si le mode réseau est demandé mais inutilisable : le plugin ne démarre pas,
     * sinon chaque serveur ferait son propre tirage.
     */
    private boolean enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) {
            return true;
        }
        if (!getServer().getPluginManager().isPluginEnabled("SHNetwork")) {
            return true;
        }
        try {
            NetworkGiveaways network = NetworkGiveaways.open(this);
            giveawayManager.useNetwork(network);
            getLogger().info("SHNetwork détecté : un seul tirage pour tout le réseau, participants et récompenses en base partagée.");
            return true;
        } catch (Throwable e) {
            getLogger().log(Level.SEVERE, "SHNetwork présent mais mode réseau inutilisable, plugin désactivé.", e);
            return false;
        }
    }

    private boolean setupVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }

        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) {
            return false;
        }

        permission = rsp.getProvider();
        return permission != null;
    }
}
