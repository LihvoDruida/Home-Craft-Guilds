package ua.homecraft.guild.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import ua.homecraft.guild.network.OpenCreateGuildPayload;
import ua.homecraft.guild.network.OpenGuildRosterPayload;
import ua.homecraft.guild.network.OpenGuildNpcAdminPayload;
import ua.homecraft.guild.network.GuildVisualSyncPayload;
import ua.homecraft.guild.network.GuildTerritoryVisualSyncPayload;
import ua.homecraft.guild.network.GuildNpcSyncPayload;
import ua.homecraft.guild.network.GuildNpcSkinRegistryPayload;
import ua.homecraft.guild.network.GuildNpcAdminSnapshotPayload;
import ua.homecraft.guild.network.GuildNpcTradeValidationResultPayload;
import ua.homecraft.guild.client.npc.GuildNpcClientCache;

import java.util.HashMap;
import java.util.Map;

public final class GuildClientPayloadHandler {
    private static final Map<String, ChunkBuffer> NPC_ADMIN_CHUNKS = new HashMap<>();

    private GuildClientPayloadHandler() {}

    public static void openRoster(OpenGuildRosterPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof GuildTalentScreen talentScreen) {
                minecraft.setScreen(talentScreen.withSnapshot(payload.snapshot()));
            } else {
                minecraft.setScreen(new GuildRosterScreen(payload.snapshot()));
            }
        });
    }

    public static void openCreateGuild(OpenCreateGuildPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new CreateGuildScreen(payload.message())));
    }

    public static void openNpcAdmin(OpenGuildNpcAdminPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> applyNpcAdminSnapshot(payload.snapshot(), true));
    }


    public static void openNpcAdminSnapshot(GuildNpcAdminSnapshotPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            String snapshot = assembleNpcAdminSnapshot(payload.snapshot());
            if (snapshot != null) applyNpcAdminSnapshot(snapshot, true);
        });
    }

    public static void openNpcTradeValidationResult(GuildNpcTradeValidationResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> applyNpcAdminSnapshot(payload.snapshot(), false));
    }

    public static void syncNpcSkinRegistry(GuildNpcSkinRegistryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> GuildNpcClientCache.acceptSkinRegistrySnapshot(payload.snapshot()));
    }

    public static void syncNpcs(GuildNpcSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> GuildNpcClientCache.acceptNpcSnapshot(payload.snapshot()));
    }

    public static void syncTerritoryVisuals(GuildTerritoryVisualSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> GuildClientVisualEffects.acceptSnapshot(payload.snapshot()));
    }

    private static String assembleNpcAdminSnapshot(String raw) {
        if (raw == null) return null;
        if (!raw.startsWith("chunk|npc_admin_snapshot|")) return raw;
        String[] p = raw.split("\\|", 6);
        if (p.length < 6) return null;
        String id = p[2];
        int index;
        int total;
        try {
            index = Integer.parseInt(p[3]);
            total = Integer.parseInt(p[4]);
        } catch (Exception ignored) {
            return null;
        }
        if (id == null || id.isBlank() || total <= 0 || total > 64 || index < 0 || index >= total) return null;
        ChunkBuffer buffer = NPC_ADMIN_CHUNKS.computeIfAbsent(id, k -> new ChunkBuffer(total));
        if (buffer.total != total) {
            NPC_ADMIN_CHUNKS.remove(id);
            buffer = new ChunkBuffer(total);
            NPC_ADMIN_CHUNKS.put(id, buffer);
        }
        buffer.parts[index] = p[5];
        if (!buffer.complete()) return null;
        NPC_ADMIN_CHUNKS.remove(id);
        return buffer.join();
    }

    private static void applyNpcAdminSnapshot(String snapshot, boolean openIfNeeded) {
        GuildNpcClientCache.acceptAdminSnapshot(snapshot);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof GuildNpcAdminScreen screen) {
            screen.acceptServerSnapshot(snapshot);
        } else if (minecraft.screen instanceof GuildNpcAdminScreen.TradeEditScreen tradeEditScreen) {
            tradeEditScreen.acceptServerSnapshot(snapshot);
        } else if (minecraft.screen instanceof GuildNpcAdminScreen.CreateNpcScreen createNpcScreen) {
            createNpcScreen.acceptServerSnapshot(snapshot);
        } else if (openIfNeeded) {
            minecraft.setScreen(new GuildNpcAdminScreen(snapshot));
        }
    }

    public static void syncVisuals(GuildVisualSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            String snapshot = payload.snapshot();
            GuildNpcClientCache.acceptVisualSnapshot(snapshot);
            if (!isNpcOnlySnapshot(snapshot)) {
                GuildClientVisualEffects.acceptSnapshot(snapshot);
            }
        });
    }

    private static boolean isNpcOnlySnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return false;
        boolean sawNpc = false;
        for (String row : snapshot.split(";")) {
            if (row == null || row.isBlank()) continue;
            if (row.startsWith("npc2|") || row.startsWith("skins2|")) {
                sawNpc = true;
                continue;
            }
            return false;
        }
        return sawNpc;
    }

    private static final class ChunkBuffer {
        final int total;
        final String[] parts;
        ChunkBuffer(int total) {
            this.total = total;
            this.parts = new String[total];
        }
        boolean complete() {
            for (String part : parts) if (part == null) return false;
            return true;
        }
        String join() {
            StringBuilder sb = new StringBuilder();
            for (String part : parts) sb.append(part);
            return sb.toString();
        }
    }
}
