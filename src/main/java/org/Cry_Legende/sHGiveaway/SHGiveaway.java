package org.Cry_Legende.sHGiveaway;

import org.Cry_Legende.sHGiveaway.command.GiveawayCommand;
import org.Cry_Legende.sHGiveaway.giveaway.GiveawayManager;
import org.Cry_Legende.sHGiveaway.network.NetworkGiveaways;
import org.Cry_Legende.sHGiveaway.listener.PlayerJoinListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.permission.Permission;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public final class SHGiveaway extends JavaPlugin {

    private Permission permission;
    private GiveawayManager giveawayManager;
    private FileConfiguration settings;
    private FileConfiguration messages;

    /** Erreur de lecture d'un fichier de configuration : nom du fichier et cause. */
    public static final class ConfigException extends Exception {
        private final String file;

        ConfigException(String file, String message) {
            super(message);
            this.file = file;
        }

        public String getFile() {
            return file;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveMessagesIfAbsent();
        try {
            applyFiles(readFiles());
        } catch (ConfigException e) {
            getLogger().severe("Fichier " + e.getFile() + " illisible, valeurs du jar utilisées : " + e.getMessage());
            settings = bundled("config.yml");
            messages = bundled("messages.yml");
        }

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
            GiveawayCommand giveawayCommand = new GiveawayCommand(this, giveawayManager);
            command.setExecutor(giveawayCommand);
            command.setTabCompleter(giveawayCommand);
        }

    }

    @Override
    public void onDisable() {
        if (giveawayManager != null) {
            giveawayManager.shutdown();
        }
    }

    /** config.yml (giveaways, réglages, sons), tel que lu par le dernier chargement réussi. */
    @Override
    public FileConfiguration getConfig() {
        return settings != null ? settings : super.getConfig();
    }

    /** messages.yml, tel que lu par le dernier chargement réussi. */
    public FileConfiguration getMessages() {
        return messages;
    }

    /** Texte de messages.yml, couleurs appliquées ; les paires de remplacements sont (placeholder, valeur). */
    public String message(String key, String... replacements) {
        String text = messages == null ? null : messages.getString(key);
        if (text == null) {
            return ChatColor.RED + "Message manquant : " + key;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Lit config.yml et messages.yml sans rien remplacer : si l'un des deux est invalide, l'exception porte le fichier
     * en cause et l'ancienne configuration reste en place. Les fichiers lus sont appliqués par {@link #applyFiles}.
     */
    public FileConfiguration[] readFiles() throws ConfigException {
        return new FileConfiguration[]{readYaml("config.yml"), readYaml("messages.yml")};
    }

    public void applyFiles(FileConfiguration[] files) {
        settings = files[0];
        messages = files[1];
    }

    private YamlConfiguration readYaml(String name) throws ConfigException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), name));
        } catch (IOException | InvalidConfigurationException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse("");
            throw new ConfigException(name, reason);
        }
        yaml.setDefaults(bundled(name));
        return yaml;
    }

    private YamlConfiguration bundled(String name) {
        try (InputStream in = getResource(name)) {
            return in == null ? new YamlConfiguration()
                : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /**
     * Premier démarrage avec ce fichier : messages.yml est créé à partir de la section messages de config.yml
     * (textes déjà en place), complétée par les textes du jar. La section de config.yml n'est plus lue ensuite.
     */
    private void saveMessagesIfAbsent() {
        File file = new File(getDataFolder(), "messages.yml");
        if (file.exists()) {
            return;
        }
        saveResource("messages.yml", false);
        var old = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml")).getConfigurationSection("messages");
        if (old == null) {
            return;
        }
        YamlConfiguration created = YamlConfiguration.loadConfiguration(file);
        for (String key : old.getKeys(false)) {
            if (old.isString(key) || old.isList(key)) {
                created.set(key, old.get(key));
            }
        }
        try {
            created.save(file);
            getLogger().info("Messages repris de config.yml dans messages.yml (la section messages de config.yml ne sert plus).");
        } catch (IOException e) {
            getLogger().warning("messages.yml non écrit : " + e.getMessage());
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
