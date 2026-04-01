package dev.zenith.pearlplus;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PearlPlusConfig {
    public final AutoLoadConfig autoLoad = new AutoLoadConfig();
    public final AutoDetectConfig autoDetect = new AutoDetectConfig();
    public final OfflineLoadConfig offlineLoad = new OfflineLoadConfig();

    public String defaultPearlId = "Base";

    public final Map<UUID, PlayerPearls> players = new LinkedHashMap<>();
    public final Map<UUID, WhitelistedPlayer> whitelist = new LinkedHashMap<>();

    public static class AutoLoadConfig {
        public boolean enabled = true;
        public boolean allowNoiseAfterPearl = true;
        public boolean returnToStartPos = true;
        public boolean autoDefaultToPresent = true;
        public boolean whitelistEnabled = false;
        public boolean dropPearlAfterLoad = true;
    }

    public static final class AutoDetectConfig {
        public boolean enabled = true;
        public boolean temporaryMode = false;
        public boolean distanceCheck = false;
        public int temporaryRemovalRange = 32; //blocks
    }

    public static final class OfflineLoadConfig {
        public boolean enabled = true;
        public int readyWindowSeconds = 120;
        public int bindCodeExpiryMinutes = 10;
        public final Map<String, DiscordBinding> discordBindings = new LinkedHashMap<>();
        public final Map<String, TrustedDiscordBinding> trustedDiscordBindings = new LinkedHashMap<>();
    }

    public static final class PlayerPearls {
        public String playerName;
        public String defaultPearlId;
        public Map<String, StoredPearl> pearls = new LinkedHashMap<>();
    }

    public static final class StoredPearl {
        public String pearlId;
        public int x;
        public int y;
        public int z;
    }

    public static final class DiscordBinding {
        public String discordUserId;
        public String discordDisplayName;
        public UUID playerUuid;
        public String playerName;
        public long linkedAtEpochMs;

        public DiscordBinding(String discordUserId, String discordDisplayName, UUID playerUuid, String playerName) {
            this.discordUserId = discordUserId;
            this.discordDisplayName = discordDisplayName;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.linkedAtEpochMs = System.currentTimeMillis();
        }
    }

    public static final class TrustedDiscordBinding {
        public String playerName;
        public UUID playerUuid;
        public String discordUserId;
        public String discordUsername;

        public TrustedDiscordBinding() {
        }

        public TrustedDiscordBinding(String playerName, UUID playerUuid, String discordUserId, String discordUsername) {
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.discordUserId = discordUserId;
            this.discordUsername = discordUsername;
        }
    }

    public static final class WhitelistedPlayer {
        public String username;
        public UUID uuid;
        
        public WhitelistedPlayer(String username, UUID uuid) {
            this.username = username;
            this.uuid = uuid;
        }
    }
}
