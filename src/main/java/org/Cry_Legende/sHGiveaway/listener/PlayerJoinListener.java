package org.Cry_Legende.sHGiveaway.listener;

import org.Cry_Legende.sHGiveaway.giveaway.GiveawayManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final GiveawayManager giveawayManager;

    public PlayerJoinListener(GiveawayManager giveawayManager) {
        this.giveawayManager = giveawayManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        giveawayManager.handleJoin(event.getPlayer());
    }
}
