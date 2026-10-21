package org.Cry_Legende.sHGiveaway.giveaway;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GiveawayState {

    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> winners = new HashSet<>();
    private final Set<UUID> notified = new HashSet<>();
    private final Set<UUID> blacklisted = new HashSet<>();
    private boolean drawn;
    private String roundKey;

    public Set<UUID> getParticipants() {
        return participants;
    }

    public Set<UUID> getWinners() {
        return winners;
    }

    public Set<UUID> getNotified() {
        return notified;
    }

    public Set<UUID> getBlacklisted() {
        return blacklisted;
    }

    public boolean isDrawn() {
        return drawn;
    }

    public void setDrawn(boolean drawn) {
        this.drawn = drawn;
    }

    public String getRoundKey() {
        return roundKey;
    }

    public void setRoundKey(String roundKey) {
        this.roundKey = roundKey;
    }
}
