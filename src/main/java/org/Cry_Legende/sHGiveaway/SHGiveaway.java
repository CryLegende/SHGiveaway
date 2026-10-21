package org.Cry_Legende.sHGiveaway;

import org.Cry_Legende.sHGiveaway.command.GiveawayCommand;
import org.Cry_Legende.sHGiveaway.giveaway.GiveawayManager;
import org.Cry_Legende.sHGiveaway.listener.PlayerJoinListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.permission.Permission;

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
