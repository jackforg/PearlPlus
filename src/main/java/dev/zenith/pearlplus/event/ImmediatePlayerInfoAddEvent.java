package dev.zenith.pearlplus.event;

import java.util.UUID;

public record ImmediatePlayerInfoAddEvent(UUID playerUuid, String playerName, long detectedAt) {
}
