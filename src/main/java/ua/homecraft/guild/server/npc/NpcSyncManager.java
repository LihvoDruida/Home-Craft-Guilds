package ua.homecraft.guild.server.npc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import ua.homecraft.guild.server.GuildServerEvents;
import ua.homecraft.guild.server.GuildStore;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side coordinator for NPC-only Server->Client metadata sync.
 *
 * This intentionally does not own guild territory/border sync. NPC skin/name/trade changes must not
 * clear the territory visual cache or force a full wall snapshot. v136 sends typed NPC skin and NPC
 * metadata payloads through GuildNpcSkinRegistryPayload/GuildNpcSyncPayload.
 */
public final class NpcSyncManager {
    private static final Map<UUID, Long> LAST_SENT_NPC_REVISION = new HashMap<>();
    private static final Map<UUID, Long> LAST_SENT_SKIN_REVISION = new HashMap<>();
    private static final Map<UUID, String> LAST_SENT_DIMENSION = new HashMap<>();

    private NpcSyncManager() {}

    public static void onPlayerJoin(ServerPlayer player) {
        sendNpcBootstrap(player, "join-immediate");
    }

    public static void onPlayerChangedDimension(ServerPlayer player) {
        sendNpcBootstrap(player, "dimension-change");
    }

    public static void onNpcMetadataChanged(MinecraftServer server, String npcKeyOrReason) {
        if (server == null || server.getPlayerList() == null) return;
        String reason = npcKeyOrReason == null || npcKeyOrReason.isBlank() ? "npc-metadata" : npcKeyOrReason;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendNpcBootstrap(player, reason);
        }
    }

    public static void onNpcPositionChanged(MinecraftServer server, String npcKey) {
        onNpcMetadataChanged(server, npcKey == null ? "npc-position" : "npc-position:" + npcKey);
    }

    public static void onNpcRemoved(MinecraftServer server, String npcKey) {
        onNpcMetadataChanged(server, npcKey == null ? "npc-removed" : "npc-removed:" + npcKey);
    }

    public static void sendNpcBootstrap(ServerPlayer player, String reason) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        UUID uuid = player.getUUID();
        long revision = HomeCraftGuildConfig.guildNpcRegistryRevision();
        String dim = GuildStore.dimensionId(level);
        Long lastNpc = LAST_SENT_NPC_REVISION.get(uuid);
        Long lastSkin = LAST_SENT_SKIN_REVISION.get(uuid);
        String lastDim = LAST_SENT_DIMENSION.get(uuid);
        boolean changed = lastNpc == null || lastNpc.longValue() != revision
                || lastSkin == null || lastSkin.longValue() != revision
                || lastDim == null || !lastDim.equals(dim)
                || (reason != null && reason.toLowerCase(Locale.ROOT).contains("force"));
        if (!changed) return;
        GuildServerEvents.sendNpcBootstrapSnapshot(player, level, reason == null ? "npc-sync" : reason);
        LAST_SENT_NPC_REVISION.put(uuid, revision);
        LAST_SENT_SKIN_REVISION.put(uuid, revision);
        LAST_SENT_DIMENSION.put(uuid, dim);
    }

    public static void sendNpcDelta(ServerPlayer player, Iterable<String> keys, String reason) {
        // v135 keeps the compatible full NPC bootstrap body. The manager still deduplicates by revision
        // so a later typed delta payload can be introduced without touching call sites again.
        sendNpcBootstrap(player, reason == null ? "npc-delta" : reason);
    }

    public static void sendSkinRegistryIfNeeded(ServerPlayer player) {
        sendNpcBootstrap(player, "skin-registry");
    }

    public static void forgetPlayer(UUID uuid) {
        if (uuid == null) return;
        LAST_SENT_NPC_REVISION.remove(uuid);
        LAST_SENT_SKIN_REVISION.remove(uuid);
        LAST_SENT_DIMENSION.remove(uuid);
    }
}
