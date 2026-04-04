package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.discord.Embed;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.message.DiscordMainChannelCommandReceivedEvent;
import com.zenith.mc.block.BlockPos;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.PearlPlusConfig;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.DISCORD;
import static dev.zenith.pearlplus.PearlPlusPlugin.LOG;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class OfflineLoadModule extends Module {
    private static final int READY_DISTANCE_BLOCKS = 6;
    private static final char[] LINK_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final PearlManager pearlManager = new PearlManager(this);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingLink> pendingLinksByCode = new HashMap<>();
    private final Map<String, PendingLink> pendingLinksByDiscordId = new HashMap<>();
    private final ListenerAdapter dedicatedDiscordListener = new ListenerAdapter() {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            onDedicatedDiscordMessage(event);
        }
    };

    private StagedOfflineLoad activeRequest;
    private boolean dedicatedDiscordListenerRegistered;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.offlineLoad.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
                of(WhisperChatEvent.class, this::onWhisper),
                of(DiscordMainChannelCommandReceivedEvent.class, this::onDiscordCommand),
                of(ClientBotTick.class, event -> onTick())
        );
    }

    @Override
    public synchronized void onEnable() {
        ensureDedicatedDiscordListenerRegistration();
    }

    @Override
    public synchronized void onDisable() {
        unregisterDedicatedDiscordListener();
        pendingLinksByCode.clear();
        pendingLinksByDiscordId.clear();
        activeRequest = null;
    }

    public synchronized boolean hasActiveRequest() {
        return isEnabled() && activeRequest != null;
    }

    public synchronized String activeRequestSummary() {
        if (activeRequest == null) {
            return "none";
        }
        return activeRequest.playerName + " / " + activeRequest.pearl.pearlId + (activeRequest.armed ? " (armed)" : " (pathing)");
    }

    public synchronized PearlPlusConfig.DiscordBinding findBindingByPlayerUuid(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        return PLUGIN_CONFIG.offlineLoad.discordBindings.values().stream()
                .filter(binding -> playerUuid.equals(binding.playerUuid))
                .findFirst()
                .orElse(null);
    }

    public synchronized boolean removeBindingByPlayerUuid(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        Iterator<Map.Entry<String, PearlPlusConfig.DiscordBinding>> iterator = PLUGIN_CONFIG.offlineLoad.discordBindings.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var binding = entry.getValue();
            if (binding != null && playerUuid.equals(binding.playerUuid)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    public synchronized void putTrustedBinding(String playerName, UUID playerUuid, String discordIdentity) {
        if (playerName == null || playerName.isBlank() || discordIdentity == null || discordIdentity.isBlank()) {
            return;
        }

        String trimmedIdentity = discordIdentity.trim();
        String discordUserId = looksLikeDiscordUserId(trimmedIdentity) ? trimmedIdentity : null;
        String discordUsername = discordUserId == null ? trimmedIdentity : null;
        PLUGIN_CONFIG.offlineLoad.trustedDiscordBindings.put(
                playerName,
                new PearlPlusConfig.TrustedDiscordBinding(playerName, playerUuid, discordUserId, discordUsername)
        );
    }

    public synchronized boolean removeTrustedBinding(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }

        Iterator<Map.Entry<String, PearlPlusConfig.TrustedDiscordBinding>> iterator = PLUGIN_CONFIG.offlineLoad.trustedDiscordBindings.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var trustedBinding = entry.getValue();
            if (entry.getKey().equalsIgnoreCase(playerName)
                    || (trustedBinding != null && trustedBinding.playerName != null && trustedBinding.playerName.equalsIgnoreCase(playerName))) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void onWhisper(WhisperChatEvent event) {
        if (event.outgoing() || !isEnabled()) {
            return;
        }

        String rawMessage = event.message().trim();
        if (rawMessage.isEmpty()) {
            return;
        }

        String[] parts = rawMessage.split("\\s+");
        if (parts.length < 2) {
            return;
        }

        String verb = parts[0].toLowerCase(Locale.ROOT);
        if (!"bind".equals(verb) && !"link".equals(verb)) {
            return;
        }

        String response = completeBinding(event.sender().getProfileId(), event.sender().getName(), parts[1]);
        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(event.sender().getName(), response));
    }

    private void onDiscordCommand(DiscordMainChannelCommandReceivedEvent event) {
        if (!isEnabled() || !PLUGIN_CONFIG.offlineLoad.listenInMainChannel || event.event().getAuthor().isBot()) {
            return;
        }

        if (!handleDiscordMessage(event.event(), event.message())) {
            return;
        }
    }

    private void onDedicatedDiscordMessage(MessageReceivedEvent event) {
        if (!isEnabled() || event.getAuthor().isBot()) {
            return;
        }

        if (!isDedicatedDiscordChannelConfigured() || !isDedicatedDiscordChannelMessage(event)) {
            return;
        }

        if (!hasDedicatedDiscordRole(event)) {
            return;
        }

        handleDiscordMessage(event, event.getMessage().getContentRaw());
    }

    private void onTick() {
        cleanupExpiredPendingLinks();
        ensureDedicatedDiscordListenerRegistration();

        StagedOfflineLoad request;
        synchronized (this) {
            request = activeRequest;
        }

        if (request == null || !request.armed) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= request.expiresAt) {
            expireActiveRequest(request);
            return;
        }

        if (isPlayerOnline(request.playerUuid, request.playerName)) {
            triggerActiveRequest(request);
        }
    }

    private boolean handleDiscordMessage(MessageReceivedEvent event, String rawMessage) {
        cleanupExpiredPendingLinks();

        ParsedCommand command = parseCommand(rawMessage);
        if (command == null) {
            return false;
        }

        switch (command.kind) {
            case DISCORD_LINK -> handleDiscordLink(event);
            case OFFLINE_LOAD -> handleOfflineLoad(event, command.arg());
            case OFFLINE_CANCEL -> handleOfflineCancel(event);
            case OFFLINE_STATUS -> handleOfflineStatus(event);
        }
        return true;
    }

    private synchronized void cleanupExpiredPendingLinks() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingLink>> iterator = pendingLinksByCode.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().expiresAt <= now) {
                pendingLinksByDiscordId.remove(entry.getValue().discordUserId);
                iterator.remove();
            }
        }
    }

    private synchronized void ensureDedicatedDiscordListenerRegistration() {
        if (!isDedicatedDiscordChannelConfigured()) {
            unregisterDedicatedDiscordListener();
            return;
        }

        if (dedicatedDiscordListenerRegistered || DISCORD == null || !DISCORD.isRunning() || DISCORD.jda() == null) {
            return;
        }

        DISCORD.jda().addEventListener(dedicatedDiscordListener);
        dedicatedDiscordListenerRegistered = true;
        LOG.info("Registered PearlPlus dedicated Discord listener for channel {}", PLUGIN_CONFIG.offlineLoad.dedicatedDiscordChannelId);
    }

    private synchronized void unregisterDedicatedDiscordListener() {
        if (!dedicatedDiscordListenerRegistered) {
            return;
        }

        if (DISCORD != null && DISCORD.jda() != null) {
            DISCORD.jda().removeEventListener(dedicatedDiscordListener);
        }
        dedicatedDiscordListenerRegistered = false;
    }

    private boolean isDedicatedDiscordChannelConfigured() {
        return PLUGIN_CONFIG.offlineLoad.dedicatedDiscordChannelId != null
                && !PLUGIN_CONFIG.offlineLoad.dedicatedDiscordChannelId.isBlank();
    }

    private boolean isDedicatedDiscordChannelMessage(MessageReceivedEvent event) {
        return event.getChannel() != null
                && PLUGIN_CONFIG.offlineLoad.dedicatedDiscordChannelId.equals(event.getChannel().getId());
    }

    private boolean hasDedicatedDiscordRole(MessageReceivedEvent event) {
        String requiredRoleId = PLUGIN_CONFIG.offlineLoad.dedicatedDiscordRoleId;
        if (requiredRoleId == null || requiredRoleId.isBlank()) {
            return true;
        }

        return event.getMember() != null
                && event.getMember().getRoles().stream().anyMatch(role -> requiredRoleId.equals(role.getId()));
    }

    private synchronized String completeBinding(UUID playerUuid, String playerName, String rawCode) {
        cleanupExpiredPendingLinks();
        String code = normalizeCode(rawCode);
        PendingLink pendingLink = pendingLinksByCode.remove(code);
        if (pendingLink == null) {
            return "That Discord link code is invalid or expired.";
        }

        pendingLinksByDiscordId.remove(pendingLink.discordUserId);
        removeBindingByPlayerUuid(playerUuid);
        PLUGIN_CONFIG.offlineLoad.discordBindings.remove(pendingLink.discordUserId);
        PLUGIN_CONFIG.offlineLoad.discordBindings.put(
                pendingLink.discordUserId,
                new PearlPlusConfig.DiscordBinding(pendingLink.discordUserId, pendingLink.discordDisplayName, playerUuid, playerName)
        );
        LOG.info("Linked Discord user {} to player {}", pendingLink.discordUserId, playerName);
        return "Discord linked successfully. You can now use `pp offline load` from Discord.";
    }

    private void handleDiscordLink(MessageReceivedEvent event) {
        String discordUserId = event.getAuthor().getId();
        String displayName = event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getName();

        PendingLink pendingLink;
        synchronized (this) {
            PendingLink existing = pendingLinksByDiscordId.remove(discordUserId);
            if (existing != null) {
                pendingLinksByCode.remove(existing.code);
            }

            String code = nextUniqueCode();
            pendingLink = new PendingLink(code, discordUserId, displayName, System.currentTimeMillis() + bindCodeExpiryMs());
            pendingLinksByCode.put(code, pendingLink);
            pendingLinksByDiscordId.put(discordUserId, pendingLink);
        }

        String dmMessage = "Whisper `bind " + pendingLink.code + "` to the pearl bot in game within "
                + PLUGIN_CONFIG.offlineLoad.bindCodeExpiryMinutes + " minutes to link this Discord account.";

        event.getAuthor().openPrivateChannel().queue(channel ->
                        channel.sendMessage(dmMessage).queue(
                                success -> replyToDiscord(event, "I sent you a link code in DMs. Whisper `bind CODE` to the bot in game."),
                                failure -> replyToDiscord(event, "I couldn't send the code DM. Check your Discord privacy settings and try again.")
                        ),
                failure -> replyToDiscord(event, "I couldn't open a DM with you. Check your Discord privacy settings and try again.")
        );
    }

    private void handleOfflineLoad(MessageReceivedEvent event, String requestedPearlArg) {
        PearlPlusConfig.DiscordBinding binding = findOrCreateBinding(event);

        if (binding == null || binding.playerUuid == null) {
            replyToDiscord(event, "Your Discord account is not linked yet. Run `pp discord link`, then whisper `bind CODE` to the bot in game.");
            return;
        }

        synchronized (this) {
            if (activeRequest != null) {
                replyToDiscord(event, "The bot is already reserved for " + activeRequestSummary() + ".");
                return;
            }
        }

        String blocker = pearlManager.validateCanLoad();
        if (blocker != null) {
            replyToDiscord(event, blocker);
            return;
        }

        var playerEntry = PLUGIN_CONFIG.players.get(binding.playerUuid);
        if (playerEntry == null || playerEntry.pearls == null || playerEntry.pearls.isEmpty()) {
            replyToDiscord(event, "No pearls are stored for " + binding.playerName + ".");
            return;
        }

        String pearlId;
        if (requestedPearlArg == null || requestedPearlArg.isBlank()) {
            pearlId = pearlManager.defaultPearlId(binding.playerUuid);
        } else {
            pearlId = pearlManager.resolvePearlId(binding.playerUuid, requestedPearlArg);
        }

        if (pearlId == null || !playerEntry.pearls.containsKey(pearlId)) {
            replyToDiscord(event, "I couldn't find that pearl ID for " + binding.playerName + ".");
            return;
        }

        PearlPlusConfig.StoredPearl pearl = copyPearl(playerEntry.pearls.get(pearlId));
        PearlManager.PreparedLoadTarget preparedLoadTarget = pearlManager.prepareLoadTarget(pearl).orElse(null);
        if (preparedLoadTarget == null) {
            replyToDiscord(event, "I couldn't prepare a click target for that pearl right now.");
            return;
        }

        BlockPos startPos = CACHE != null && CACHE.getPlayerCache() != null && CACHE.getPlayerCache().getThePlayer() != null
                ? CACHE.getPlayerCache().getThePlayer().blockPos()
                : null;
        if (startPos == null) {
            replyToDiscord(event, "I can't tell where the bot is standing right now.");
            return;
        }

        StagedOfflineLoad request = new StagedOfflineLoad(
                event,
                event.getAuthor().getId(),
                binding.discordDisplayName != null ? binding.discordDisplayName : event.getAuthor().getName(),
                binding.playerUuid,
                binding.playerName,
                pearl,
                preparedLoadTarget,
                startPos
        );

        synchronized (this) {
            activeRequest = request;
        }

        replyToDiscord(event, "Pathing to pearl `" + pearl.pearlId + "`. Your 2 minute login window starts once I'm in position.");
        discordAndIngameNotification(Embed.builder()
                .title("Offline Load Requested")
                .addField("Player", binding.playerName)
                .addField("Pearl", pearl.pearlId)
                .primaryColor());

        BARITONE.pathTo((int) preparedLoadTarget.pathPos().x(), (int) preparedLoadTarget.pathPos().z())
                .addExecutedListener(future -> onStagePathComplete(request));
    }

    private void handleOfflineCancel(MessageReceivedEvent event) {
        StagedOfflineLoad request;
        synchronized (this) {
            request = activeRequest;
        }

        if (request == null) {
            replyToDiscord(event, "There isn't an active offline load request right now.");
            return;
        }

        if (!request.discordUserId.equals(event.getAuthor().getId())) {
            replyToDiscord(event, "Only the Discord user who created the offline load can cancel it.");
            return;
        }

        clearActiveRequest(request, "Offline load cancelled.", true);
        replyToDiscord(event, "Cancelled the staged offline load.");
    }

    private void handleOfflineStatus(MessageReceivedEvent event) {
        StagedOfflineLoad request;
        synchronized (this) {
            request = activeRequest;
        }

        if (request == null) {
            replyToDiscord(event, "No offline load is active right now.");
            return;
        }

        if (!request.armed) {
            replyToDiscord(event, "Offline load is still pathing for " + request.playerName + " / `" + request.pearl.pearlId + "`.");
            return;
        }

        long secondsRemaining = Math.max(0L, (request.expiresAt - System.currentTimeMillis() + 999L) / 1000L);
        replyToDiscord(event, "Offline load is armed for " + request.playerName + " / `" + request.pearl.pearlId + "` for another " + secondsRemaining + "s.");
    }

    private void onStagePathComplete(StagedOfflineLoad request) {
        synchronized (this) {
            if (activeRequest != request) {
                return;
            }
        }

        if (!pearlManager.isNearPreparedLoadTarget(request.preparedLoadTarget, READY_DISTANCE_BLOCKS)) {
            clearActiveRequest(request, "I couldn't get into position for the offline load.", true);
            sendChannelMessage(request.sourceEvent, mention(request.discordUserId) + " I couldn't get into position for `" + request.pearl.pearlId + "`.");
            return;
        }

        synchronized (this) {
            if (activeRequest != request) {
                return;
            }
            request.armed = true;
            request.expiresAt = System.currentTimeMillis() + readyWindowMs();
        }

        sendChannelMessage(
                request.sourceEvent,
                mention(request.discordUserId) + " pearl `" + request.pearl.pearlId + "` is armed. You have "
                        + PLUGIN_CONFIG.offlineLoad.readyWindowSeconds + " seconds to join."
        );
        discordAndIngameNotification(Embed.builder()
                .title("Offline Load Armed")
                .addField("Player", request.playerName)
                .addField("Pearl", request.pearl.pearlId)
                .successColor());
    }

    private void expireActiveRequest(StagedOfflineLoad request) {
        clearActiveRequest(request, "Offline load window expired.", true);
        sendChannelMessage(request.sourceEvent, mention(request.discordUserId) + " the offline load window for `" + request.pearl.pearlId + "` expired.");
    }

    private void triggerActiveRequest(StagedOfflineLoad request) {
        synchronized (this) {
            if (activeRequest != request) {
                return;
            }
            activeRequest = null;
        }

        sendChannelMessage(request.sourceEvent, mention(request.discordUserId) + " detected `" + request.playerName + "` online. Activating `" + request.pearl.pearlId + "` now.");

        String blocker = pearlManager.validateCanLoad();
        if (blocker != null) {
            sendChannelMessage(request.sourceEvent, mention(request.discordUserId) + " I couldn't activate the pearl: " + blocker);
            returnToStartPosition(request.startPos);
            return;
        }

        if (!pearlManager.isNearPreparedLoadTarget(request.preparedLoadTarget, READY_DISTANCE_BLOCKS)) {
            sendChannelMessage(request.sourceEvent, mention(request.discordUserId) + " I drifted away from the chamber, so I'm falling back to a normal load.");
            pearlManager.loadPearl(request.pearl, request.playerName);
            return;
        }

        pearlManager.triggerPreparedLoad(request.preparedLoadTarget, request.pearl, request.playerName, request.startPos);
    }

    private synchronized void clearActiveRequest(StagedOfflineLoad request, String reason, boolean returnToStart) {
        if (activeRequest != request) {
            return;
        }
        activeRequest = null;

        if (reason != null && !reason.isBlank()) {
            discordAndIngameNotification(Embed.builder()
                    .title("Offline Load Closed")
                    .description(reason)
                    .errorColor());
        }

        if (returnToStart) {
            returnToStartPosition(request.startPos);
        }
    }

    private void returnToStartPosition(BlockPos startPos) {
        if (!PLUGIN_CONFIG.autoLoad.returnToStartPos || startPos == null) {
            return;
        }
        BARITONE.pathTo(startPos.x(), startPos.y(), startPos.z());
    }

    private boolean isPlayerOnline(UUID playerUuid, String playerName) {
        if (CACHE == null || CACHE.getTabListCache() == null) {
            return false;
        }
        if (playerUuid != null && CACHE.getTabListCache().get(playerUuid).isPresent()) {
            return true;
        }
        return playerName != null && CACHE.getTabListCache().getFromName(playerName).isPresent();
    }

    private void replyToDiscord(MessageReceivedEvent event, String message) {
        event.getMessage().reply(message).queue();
    }

    private void sendChannelMessage(MessageReceivedEvent event, String message) {
        event.getChannel().sendMessage(message).queue();
    }

    private ParsedCommand parseCommand(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }

        String[] parts = rawMessage.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }

        int index = 0;
        String first = normalizeCommandToken(parts[0]);
        if ("pp".equals(first) || "pearlplus".equals(first)) {
            index++;
        }
        if (index >= parts.length) {
            return null;
        }

        String root = normalizeCommandToken(parts[index]);
        if ("discord".equals(root) && index + 1 < parts.length && "link".equalsIgnoreCase(parts[index + 1])) {
            return new ParsedCommand(CommandKind.DISCORD_LINK, null);
        }

        if (!"offline".equals(root)) {
            return null;
        }
        if (index + 1 >= parts.length) {
            return new ParsedCommand(CommandKind.OFFLINE_STATUS, null);
        }

        String action = normalizeCommandToken(parts[index + 1]);
        return switch (action) {
            case "load" -> new ParsedCommand(CommandKind.OFFLINE_LOAD, index + 2 < parts.length ? parts[index + 2] : null);
            case "cancel" -> new ParsedCommand(CommandKind.OFFLINE_CANCEL, null);
            case "status" -> new ParsedCommand(CommandKind.OFFLINE_STATUS, null);
            default -> null;
        };
    }

    private String normalizeCommandToken(String token) {
        if (token == null) {
            return "";
        }
        int start = 0;
        while (start < token.length() && token.charAt(start) == '.') {
            start++;
        }
        return token.substring(start).toLowerCase(Locale.ROOT);
    }

    private synchronized String nextUniqueCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                builder.append(LINK_CODE_ALPHABET[random.nextInt(LINK_CODE_ALPHABET.length)]);
            }
            code = builder.toString();
        } while (pendingLinksByCode.containsKey(code));
        return code;
    }

    private long readyWindowMs() {
        return Math.max(30L, PLUGIN_CONFIG.offlineLoad.readyWindowSeconds) * 1000L;
    }

    private long bindCodeExpiryMs() {
        return Math.max(1L, PLUGIN_CONFIG.offlineLoad.bindCodeExpiryMinutes) * 60_000L;
    }

    private String normalizeCode(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    }

    private String mention(String discordUserId) {
        return "<@" + discordUserId + ">";
    }

    private PearlPlusConfig.DiscordBinding findOrCreateBinding(MessageReceivedEvent event) {
        PearlPlusConfig.DiscordBinding binding;
        synchronized (this) {
            binding = PLUGIN_CONFIG.offlineLoad.discordBindings.get(event.getAuthor().getId());
        }
        if (binding != null && binding.playerUuid != null) {
            return binding;
        }

        PearlPlusConfig.TrustedDiscordBinding trustedBinding = findTrustedBinding(event);
        if (trustedBinding == null) {
            return binding;
        }

        UUID playerUuid = trustedBinding.playerUuid != null ? trustedBinding.playerUuid : resolveStoredPlayerUuid(trustedBinding.playerName);
        String playerName = trustedBinding.playerName != null && !trustedBinding.playerName.isBlank()
                ? trustedBinding.playerName
                : resolveStoredPlayerName(playerUuid);
        if (playerUuid == null || playerName == null || playerName.isBlank()) {
            return binding;
        }

        PearlPlusConfig.DiscordBinding trustedLiveBinding = new PearlPlusConfig.DiscordBinding(
                event.getAuthor().getId(),
                discordDisplayName(event),
                playerUuid,
                playerName
        );

        synchronized (this) {
            removeBindingByPlayerUuid(playerUuid);
            PLUGIN_CONFIG.offlineLoad.discordBindings.put(event.getAuthor().getId(), trustedLiveBinding);
            trustedBinding.playerUuid = playerUuid;
            trustedBinding.playerName = playerName;
            if (trustedBinding.discordUserId == null || trustedBinding.discordUserId.isBlank()) {
                trustedBinding.discordUserId = event.getAuthor().getId();
            }
            if (trustedBinding.discordUsername == null || trustedBinding.discordUsername.isBlank()) {
                trustedBinding.discordUsername = discordDisplayName(event);
            }
        }

        LOG.info("Auto-linked trusted Discord user {} to player {}", event.getAuthor().getId(), playerName);
        return trustedLiveBinding;
    }

    private PearlPlusConfig.TrustedDiscordBinding findTrustedBinding(MessageReceivedEvent event) {
        String discordUserId = event.getAuthor().getId();
        String authorName = event.getAuthor().getName();
        String displayName = discordDisplayName(event);

        synchronized (this) {
            for (PearlPlusConfig.TrustedDiscordBinding trustedBinding : PLUGIN_CONFIG.offlineLoad.trustedDiscordBindings.values()) {
                if (trustedBinding == null || trustedBinding.playerName == null || trustedBinding.playerName.isBlank()) {
                    continue;
                }

                if (trustedBinding.discordUserId != null && !trustedBinding.discordUserId.isBlank()
                        && trustedBinding.discordUserId.equals(discordUserId)) {
                    return trustedBinding;
                }

                if (trustedBinding.discordUsername != null && !trustedBinding.discordUsername.isBlank()) {
                    if (trustedBinding.discordUsername.equalsIgnoreCase(displayName)
                            || trustedBinding.discordUsername.equalsIgnoreCase(authorName)) {
                        return trustedBinding;
                    }
                }
            }
        }
        return null;
    }

    private UUID resolveStoredPlayerUuid(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            var playerPearls = entry.getValue();
            if (playerPearls != null && playerPearls.playerName != null && playerPearls.playerName.equalsIgnoreCase(playerName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String resolveStoredPlayerName(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        var playerPearls = PLUGIN_CONFIG.players.get(playerUuid);
        return playerPearls != null ? playerPearls.playerName : null;
    }

    private String discordDisplayName(MessageReceivedEvent event) {
        return event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getName();
    }

    private boolean looksLikeDiscordUserId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return value.length() >= 16;
    }

    private PearlPlusConfig.StoredPearl copyPearl(PearlPlusConfig.StoredPearl pearl) {
        PearlPlusConfig.StoredPearl copy = new PearlPlusConfig.StoredPearl();
        copy.pearlId = pearl.pearlId;
        copy.x = pearl.x;
        copy.y = pearl.y;
        copy.z = pearl.z;
        return copy;
    }

    private enum CommandKind {
        DISCORD_LINK,
        OFFLINE_LOAD,
        OFFLINE_CANCEL,
        OFFLINE_STATUS
    }

    private record ParsedCommand(CommandKind kind, String arg) {
    }

    private record PendingLink(String code, String discordUserId, String discordDisplayName, long expiresAt) {
    }

    private static final class StagedOfflineLoad {
        private final MessageReceivedEvent sourceEvent;
        private final String discordUserId;
        private final String discordDisplayName;
        private final UUID playerUuid;
        private final String playerName;
        private final PearlPlusConfig.StoredPearl pearl;
        private final PearlManager.PreparedLoadTarget preparedLoadTarget;
        private final BlockPos startPos;
        private boolean armed;
        private long expiresAt;

        private StagedOfflineLoad(
                MessageReceivedEvent sourceEvent,
                String discordUserId,
                String discordDisplayName,
                UUID playerUuid,
                String playerName,
                PearlPlusConfig.StoredPearl pearl,
                PearlManager.PreparedLoadTarget preparedLoadTarget,
                BlockPos startPos
        ) {
            this.sourceEvent = sourceEvent;
            this.discordUserId = discordUserId;
            this.discordDisplayName = discordDisplayName;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.pearl = pearl;
            this.preparedLoadTarget = preparedLoadTarget;
            this.startPos = startPos;
        }
    }
}
