package ua.homecraft.guild.server.golem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.homecraft.guild.server.GuildStore;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class GuildGolemTargetSelector {
    private GuildGolemTargetSelector() {}

    public static LivingEntity select(GuildGolemBrain.Context context, GuildStore.Golem record, Entity golem, GuildTerritoryCluster cluster, GuildGolemType type, LivingEntity currentTarget) {
        if (context == null || record == null || golem == null || cluster == null || cluster.isEmpty() || !(golem.level() instanceof ServerLevel level)) return null;
        if (currentTarget != null && isAllowedTarget(record, golem, currentTarget, cluster, type)) return currentTarget;
        int radius = type.elite() ? HomeCraftGuildConfig.guildEliteGolemMaxThreatScanRadius() : HomeCraftGuildConfig.guildGolemMaxThreatScanRadius();
        if (context.lagLevel() >= 3) radius = Math.max(14, radius / HomeCraftGuildConfig.guildGolemLagLevel3TargetScanMultiplier());
        AABB box = golem.getBoundingBox().inflate(radius, Math.max(8, radius / 2), radius);
        List<Entity> entities = level.getEntities(golem, box, e -> e instanceof LivingEntity && e.isAlive());
        LivingEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Entity candidateEntity : entities) {
            if (!(candidateEntity instanceof LivingEntity candidate)) continue;
            if (!isAllowedTarget(record, golem, candidate, cluster, type)) continue;
            int score = score(context, record, golem, candidate, cluster, type);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null && HomeCraftGuildConfig.guildGolemDebugTargetSelection()) {
            GuildGolemDebugLogger.info("Home Craft Guilds golem target: {} -> {} score={}", record.uuid, entityId(best), bestScore);
        }
        return bestScore <= 0 ? null : best;
    }

    public static boolean isAllowedTarget(GuildStore.Golem record, Entity golem, LivingEntity target, GuildTerritoryCluster cluster, GuildGolemType type) {
        if (record == null || golem == null || target == null || cluster == null || !target.isAlive() || target == golem) return false;
        if (isSameGuildGolem(record.guildId, target)) return false;
        if (golem.level() instanceof ServerLevel level && isUndergroundThreat(level, target)) return false;
        if (target instanceof ServerPlayer player) {
            if (GuildStore.isMemberOfGuild(player, record.guildId)) return false;
            if (!isPlayerActivelyThreateningGuild(record.guildId, target, golem)) return false;
        } else if (target.getType().getCategory() != MobCategory.MONSTER) {
            return false;
        }
        int buffer = type.elite() ? HomeCraftGuildConfig.guildEliteGolemChaseOutsideTerritoryBlocks() : HomeCraftGuildConfig.guildGolemChaseOutsideTerritoryBlocks();
        return cluster.contains(target.blockPosition(), buffer);
    }

    private static boolean isUndergroundThreat(ServerLevel level, LivingEntity target) {
        if (level == null || target == null) return true;
        int x = target.blockPosition().getX();
        int z = target.blockPosition().getZ();
        if (!GuildGolemSafePositionResolver.isChunkLoaded(level, x, z)) return true;
        try {
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int feetY = target.blockPosition().getY();
            return feetY < surfaceY - 2;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static int score(GuildGolemBrain.Context context, GuildStore.Golem record, Entity golem, LivingEntity target, GuildTerritoryCluster cluster, GuildGolemType type) {
        int score = 0;
        if (lastHurtBy(target) == golem) score += 120;
        Entity targetVictim = lastHurtMob(target);
        if (targetVictim instanceof ServerPlayer victim && GuildStore.isMemberOfGuild(victim, record.guildId)) score += type.elite() ? 220 : 150;
        if (isSameGuildGolem(record.guildId, targetVictim)) score += 130;
        if (target.getType().getCategory() == MobCategory.MONSTER) score += 55;
        if (cluster.contains(target.blockPosition())) score += 35;
        GuildStore.Territory nearest = cluster.nearest(target.getX(), target.getZ());
        if (nearest != null) {
            double dx = target.getX() - (nearest.x + 0.5D);
            double dz = target.getZ() - (nearest.z + 0.5D);
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 8.0D) score += type.elite() ? 85 : 45;
        }
        if (type.elite()) {
            String id = entityId(target);
            if (id.contains("warden") || id.contains("ravager") || id.contains("enderman") || id.contains("witch") || id.contains("creeper")) score += 75;
            if (id.contains("zombie") || id.contains("spider") || id.contains("slime")) score -= 10;
        } else {
            if (!cluster.contains(target.blockPosition())) score -= 30;
        }
        double distSqr = golem.distanceToSqr(target);
        score -= (int)Math.min(70, Math.sqrt(Math.max(0.0D, distSqr)) * (type.elite() ? 1.1D : 1.6D));
        if (context != null && context.lagLevel() >= 3 && score < 150) score -= 200;
        return score;
    }

    private static boolean isSameGuildGolem(String guildId, Entity entity) {
        if (guildId == null || entity == null) return false;
        if (!entity.getTags().contains("homecraft_guild_golem")) return false;
        for (String tag : entity.getTags()) {
            if (tag != null && tag.equals("homecraft_guild_id_" + guildId)) return true;
        }
        GuildStore.Golem record = GuildStore.golem(entity.getUUID().toString());
        return record != null && Objects.equals(record.guildId, guildId);
    }

    private static boolean isPlayerActivelyThreateningGuild(String guildId, LivingEntity player, Entity golem) {
        Entity victim = lastHurtMob(player);
        if (victim instanceof ServerPlayer serverPlayer && GuildStore.isMemberOfGuild(serverPlayer, guildId)) return true;
        if (isSameGuildGolem(guildId, victim)) return true;
        Entity attacker = lastHurtBy(player);
        return attacker == golem;
    }

    private static Entity lastHurtBy(LivingEntity entity) {
        return reflectEntity(entity, "getLastHurtByMob", "getKillCredit");
    }

    private static Entity lastHurtMob(LivingEntity entity) {
        return reflectEntity(entity, "getLastHurtMob");
    }

    private static Entity reflectEntity(Object object, String... names) {
        if (object == null || names == null) return null;
        for (String name : names) {
            try {
                Method method = object.getClass().getMethod(name);
                Object value = method.invoke(object);
                if (value instanceof Entity entity) return entity;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String entityId(Entity entity) {
        try { return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).toLowerCase(Locale.ROOT); }
        catch (Throwable ignored) { return String.valueOf(entity == null ? "" : entity.getType()).toLowerCase(Locale.ROOT); }
    }
}
