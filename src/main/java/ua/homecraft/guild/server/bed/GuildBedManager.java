package ua.homecraft.guild.server.bed;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.homecraft.guild.network.GuildTerritoryVisualSyncPayload;
import ua.homecraft.guild.server.GuildStore;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

import java.util.List;

public final class GuildBedManager {
    private GuildBedManager() {}

    public static boolean isBed(BlockState state) {
        return GuildBedPositionResolver.isBed(state);
    }

    public static boolean validateBedPlacement(ServerPlayer player, GuildStore.Territory territory, LevelAccessor level, BlockPos pos, BlockState state) {
        GuildStore.GuildBedResult result = GuildStore.checkGuildBedPlacement(player, territory, level, pos, state);
        if (!result.allowed()) {
            player.displayClientMessage(Component.literal(result.message()), true);
            return false;
        }
        return true;
    }

    public static void registerBedPlacement(ServerPlayer player, GuildStore.Territory territory, LevelAccessor level, BlockPos pos, BlockState state) {
        GuildStore.GuildBedResult result = GuildStore.registerGuildBedPlacement(player, territory, level, pos, state);
        if (result.message() != null && !result.message().isBlank()) player.displayClientMessage(Component.literal(result.message()), true);
        if (level instanceof ServerLevel serverLevel) syncAround(serverLevel, GuildBedPositionResolver.rootPos(level, pos, state));
    }

    public static InteractionResult useBed(ServerPlayer player, GuildStore.Territory territory, LevelAccessor level, BlockPos pos, BlockState state) {
        GuildBedBinding previous = player == null ? null : GuildStore.guildBedForPlayer(player.getUUID().toString().toLowerCase(java.util.Locale.ROOT));
        GuildStore.GuildBedResult result = GuildStore.handleGuildBedUse(player, territory, level, pos, state);
        if (result.message() != null && !result.message().isBlank()) player.displayClientMessage(Component.literal(result.message()), true);
        if (level instanceof ServerLevel serverLevel && result.changed()) {
            BlockPos root = GuildBedPositionResolver.rootPos(level, pos, state);
            if (previous != null && (previous.x != root.getX() || previous.y != root.getY() || previous.z != root.getZ())) clearAround(serverLevel, previous);
            syncAround(serverLevel, root);
        }
        return result.allowed() ? InteractionResult.PASS : InteractionResult.FAIL;
    }

    public static void bedBroken(LevelAccessor level, BlockPos pos, BlockState state) {
        GuildStore.GuildBedResult result = GuildStore.handleGuildBedBroken(level, pos, state);
        if (level instanceof ServerLevel serverLevel && result.changed()) syncAround(serverLevel, GuildBedPositionResolver.rootPos(level, pos, state));
    }

    public static void syncLabelsForPlayer(ServerPlayer player, ServerLevel level, String dimension) {
        if (!HomeCraftGuildConfig.guildBedsEnabled() || !HomeCraftGuildConfig.guildBedsShowOwnerLabel()) return;
        if (player == null || level == null || dimension == null) return;
        List<GuildBedBinding> labels = GuildStore.guildBedLabelsNear(dimension, player.blockPosition().getX(), player.blockPosition().getZ(), HomeCraftGuildConfig.guildBedsOwnerLabelMaxDistance() + 32);
        for (GuildBedBinding bed : labels) {
            if (bed == null || !bed.claimed()) continue;
            if (GuildStore.validateGuildBedBinding(level.getServer(), bed, true)) {
                PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload("event|bed_label|" + bed.dimension + "|" + bed.x + "|" + bed.y + "|" + bed.z + "|false|"));
                continue;
            }
            PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload(labelEvent(bed, true)));
        }
    }


    private static void clearAround(ServerLevel level, GuildBedBinding bed) {
        if (level == null || bed == null || level.getServer() == null) return;
        BlockPos pos = new BlockPos(bed.x, bed.y, bed.z);
        double radiusSqr = Math.pow(Math.max(32, HomeCraftGuildConfig.guildBedsOwnerLabelMaxDistance() + 24), 2);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player == null || player.level() != level) continue;
            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > radiusSqr) continue;
            PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload("event|bed_label|" + bed.dimension + "|" + bed.x + "|" + bed.y + "|" + bed.z + "|false|"));
        }
    }

    private static void syncAround(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || level.getServer() == null) return;
        String dim = GuildStore.dimensionId(level);
        double radiusSqr = Math.pow(Math.max(32, HomeCraftGuildConfig.guildBedsOwnerLabelMaxDistance() + 24), 2);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player == null || player.level() != level) continue;
            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > radiusSqr) continue;
            GuildBedBinding bed = GuildStore.guildBedAt(dim, pos);
            if (bed != null && bed.claimed()) PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload(labelEvent(bed, true)));
            else PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload("event|bed_label|" + dim + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ() + "|false|"));
        }
    }

    private static String labelEvent(GuildBedBinding bed, boolean active) {
        String owner = bed.ownerName == null ? "" : bed.ownerName.replace("|", " ").replace(";", " ");
        return "event|bed_label|" + bed.dimension + "|" + bed.x + "|" + bed.y + "|" + bed.z + "|" + active + "|" + owner;
    }
}
