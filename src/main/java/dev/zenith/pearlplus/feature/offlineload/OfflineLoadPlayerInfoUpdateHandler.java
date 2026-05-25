package dev.zenith.pearlplus.feature.offlineload;

import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.ClientEventLoopPacketHandler;
import dev.zenith.pearlplus.event.ImmediatePlayerInfoAddEvent;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;

import static com.zenith.Globals.EVENT_BUS;

public class OfflineLoadPlayerInfoUpdateHandler implements ClientEventLoopPacketHandler<ClientboundPlayerInfoUpdatePacket, ClientSession> {
    @Override
    public boolean applyAsync(final ClientboundPlayerInfoUpdatePacket packet, final ClientSession session) {
        if (!packet.getActions().contains(PlayerListEntryAction.ADD_PLAYER)) {
            return true;
        }

        long detectedAt = System.currentTimeMillis();
        for (var entry : packet.getEntries()) {
            EVENT_BUS.post(new ImmediatePlayerInfoAddEvent(
                    entry.getProfileId(),
                    entry.getName(),
                    detectedAt
            ));
        }
        return true;
    }
}
