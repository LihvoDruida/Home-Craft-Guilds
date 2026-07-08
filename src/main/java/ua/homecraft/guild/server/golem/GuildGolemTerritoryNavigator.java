package ua.homecraft.guild.server.golem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import ua.homecraft.guild.server.GuildStore;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

public final class GuildGolemTerritoryNavigator {
    private GuildGolemTerritoryNavigator() {}

    public static boolean moveTo(GuildStore.Golem record, Entity entity, GuildGolemSafePositionResolver.SpawnPoint point, double speed, int lagLevel) {
        if (record == null || entity == null || point == null) return false;
        GuildTerritoryCluster cluster = null;
        if (entity.level() instanceof ServerLevel level && record.guildId != null && !record.guildId.isBlank()) {
            cluster = GuildTerritoryUnionCache.cluster(record.guildId, GuildStore.dimensionId(level));
        }
        return moveTo(record, entity, cluster, point, speed, lagLevel, GuildGolemRouteMode.DIRECT_PATH);
    }

    public static boolean moveTo(GuildStore.Golem record, Entity entity, GuildTerritoryCluster cluster, GuildGolemSafePositionResolver.SpawnPoint point, double speed, int lagLevel, GuildGolemRouteMode mode) {
        if (record == null || entity == null || point == null || !(entity instanceof Mob mob)) return false;
        if (cluster != null && entity.level() instanceof ServerLevel) {
            return GuildGolemRoutePlanner.moveSmart(record, entity, cluster, point, speed, lagLevel, mode);
        }
        long now = entity.level().getGameTime();
        int baseCooldown = GuildGolemType.from(record).elite() ? HomeCraftGuildConfig.guildEliteGolemPathRecalcCooldownTicks() : HomeCraftGuildConfig.guildGolemPathRecalcCooldownTicks();
        int cooldown = lagLevel >= 3 ? baseCooldown * HomeCraftGuildConfig.guildGolemLagLevel3PathRecalcMultiplier() : (lagLevel >= 2 ? baseCooldown * 2 : baseCooldown);
        double dx = point.x() - record.lastPathTargetX;
        double dy = point.y() - record.lastPathTargetY;
        double dz = point.z() - record.lastPathTargetZ;
        boolean movedTarget = dx * dx + dy * dy + dz * dz > 3.0D * 3.0D;
        if (!mob.getNavigation().isDone() && !movedTarget && now - record.lastPathRecalcTick < Math.max(1, cooldown)) return true;
        record.lastPathRecalcTick = now;
        record.lastPathTargetX = point.x();
        record.lastPathTargetY = point.y();
        record.lastPathTargetZ = point.z();
        boolean ok = false;
        try { ok = mob.getNavigation().moveTo(point.x(), point.y(), point.z(), speed); }
        catch (Throwable ignored) { ok = false; }
        if (!ok) {
            record.pathFailureCount = Math.min(1000, Math.max(0, record.pathFailureCount) + 1);
            GuildGolemDebugLogger.path(record.uuid, "move failed x=" + point.x() + " y=" + point.y() + " z=" + point.z() + " failures=" + record.pathFailureCount);
        } else {
            record.pathFailureCount = 0;
            record.routeStatus = "Патрулює";
        }
        return ok;
    }

    public static boolean returnHome(GuildStore.Golem record, Entity entity, GuildTerritoryCluster cluster, int lagLevel) {
        if (record == null || entity == null || cluster == null || !(entity.level() instanceof ServerLevel level)) return false;
        GuildGolemSafePositionResolver.SpawnPoint point = GuildGolemSafePositionResolver.near(level, cluster, entity.getX(), entity.getZ(), 20);
        if (point == null) point = GuildGolemSafePositionResolver.strategic(level, cluster);
        if (point == null) return false;
        boolean moved = moveTo(record, entity, point, GuildGolemType.from(record).elite() ? 1.05D : 0.90D, lagLevel);
        int teleportAfter = HomeCraftGuildConfig.guildGolemMaxPathFailuresBeforeTeleportRecover();
        if (!moved && record.pathFailureCount >= teleportAfter && GuildGolemSafePositionResolver.isWalkable(level, entity.blockPosition())) return false;
        if (!moved && record.pathFailureCount >= teleportAfter) return teleport(record, entity, point);
        return moved;
    }

    public static boolean teleport(GuildStore.Golem record, Entity entity, GuildGolemSafePositionResolver.SpawnPoint point) {
        if (record == null || entity == null || point == null) return false;
        try {
            entity.teleportTo(point.x(), point.y(), point.z());
            resetFallDistance(entity);
            record.pathFailureCount = 0;
            record.lastSafeX = (int)Math.floor(point.x());
            record.lastSafeY = (int)Math.floor(point.y());
            record.lastSafeZ = (int)Math.floor(point.z());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private static void resetFallDistance(Entity entity) {
        if (entity == null) return;
        try { entity.getClass().getMethod("resetFallDistance").invoke(entity); return; } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field field = Entity.class.getDeclaredField("fallDistance");
            field.setAccessible(true);
            field.setFloat(entity, 0.0F);
        } catch (Throwable ignored) {}
    }

}
