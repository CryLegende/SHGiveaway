package org.Cry_Legende.sHGiveaway.giveaway;

import java.time.LocalDateTime;
import java.util.List;

public class GiveawayDefinition {

    private final String id;
    private final boolean enabled;
    private final String prizeDisplay;
    private final int winnersCount;
    private final LocalDateTime drawAt;
    private final String roundKey;
    private final boolean announceOnJoin;
    private final int joinMessageDelaySeconds;
    private final List<String> excludedGroups;
    private final List<String> excludedPermissions;
    private final List<String> requiredPermissions;
    private final int minPlaytimeMinutes;
    private final int minAccountAgeHours;
    private final List<String> rewardCommands;

    public GiveawayDefinition(
        String id,
        boolean enabled,
        String prizeDisplay,
        int winnersCount,
        LocalDateTime drawAt,
        String roundKey,
        boolean announceOnJoin,
        int joinMessageDelaySeconds,
        List<String> excludedGroups,
        List<String> excludedPermissions,
        List<String> requiredPermissions,
        int minPlaytimeMinutes,
        int minAccountAgeHours,
        List<String> rewardCommands
    ) {
        this.id = id;
        this.enabled = enabled;
        this.prizeDisplay = prizeDisplay;
        this.winnersCount = winnersCount;
        this.drawAt = drawAt;
        this.roundKey = roundKey;
        this.announceOnJoin = announceOnJoin;
        this.joinMessageDelaySeconds = joinMessageDelaySeconds;
        this.excludedGroups = excludedGroups;
        this.excludedPermissions = excludedPermissions;
        this.requiredPermissions = requiredPermissions;
        this.minPlaytimeMinutes = minPlaytimeMinutes;
        this.minAccountAgeHours = minAccountAgeHours;
        this.rewardCommands = rewardCommands;
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPrizeDisplay() {
        return prizeDisplay;
    }

    public int getWinnersCount() {
        return winnersCount;
    }

    public LocalDateTime getDrawAt() {
        return drawAt;
    }

    public String getRoundKey() {
        return roundKey;
    }

    public boolean isAnnounceOnJoin() {
        return announceOnJoin;
    }

    public int getJoinMessageDelaySeconds() {
        return joinMessageDelaySeconds;
    }

    public List<String> getExcludedGroups() {
        return excludedGroups;
    }

    public List<String> getExcludedPermissions() {
        return excludedPermissions;
    }

    public List<String> getRequiredPermissions() {
        return requiredPermissions;
    }

    public int getMinPlaytimeMinutes() {
        return minPlaytimeMinutes;
    }

    public int getMinAccountAgeHours() {
        return minAccountAgeHours;
    }

    public List<String> getRewardCommands() {
        return rewardCommands;
    }
}
