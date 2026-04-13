package dev.zenith.pearlplus.feature.autodetect;

import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.ClientEventLoopPacketHandler;
import dev.zenith.pearlplus.event.EnderPearlSpawnEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket;

import static com.zenith.Globals.EVENT_BUS;

public class AutoDetectAddEntityHandler implements ClientEventLoopPacketHandler<ClientboundAddEntityPacket, ClientSession> {
    @Override
    public boolean applyAsync(final ClientboundAddEntityPacket packet, final ClientSession session) {
        if (packet.getType() != EntityType.ENDER_PEARL) {
            return true;
        }

        int ownerEntityId = packet.getData() instanceof ProjectileData projectileData
                ? projectileData.getOwnerId()
                : -1;
        EVENT_BUS.post(new EnderPearlSpawnEvent(
                packet.getEntityId(),
                ownerEntityId,
                packet.getX(),
                packet.getY(),
                packet.getZ()
        ));
        return true;
    }
}
