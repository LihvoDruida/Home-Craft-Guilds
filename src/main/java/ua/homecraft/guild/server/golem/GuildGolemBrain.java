package ua.homecraft.guild.server.golem;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import ua.homecraft.guild.server.GuildStore;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.homecraft.guild.network.GuildTerritoryVisualSyncPayload;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

import java.util.Locale;

public final class GuildGolemBrain {
    public record Context(MinecraftServer server, int lagLevel, long frame) {}

    private GuildGolemBrain() {}

    public static boolean tick(Context context, GuildStore.Golem record, Entity entity, boolean runAi) {
        if (!HomeCraftGuildConfig.guildGolemAiEnabled()) {
            stop(entity);
            return true;
        }
        if (record == null || entity == null) return true;
        GuildGolemType type = GuildGolemType.from(record);
        migrateRuntimeDefaults(record, type);
        if (record.removed) {
            setState(record, type.elite() ? GuildGolemState.ELITE_REMOVED : GuildGolemState.NORMAL_REMOVED, GuildGolemStatus.REMOVED, "removed");
            stop(entity);
            return true;
        }
        if (record.dead) {
            setState(record, type.elite() ? GuildGolemState.ELITE_DEAD : GuildGolemState.NORMAL_DEAD, record.respawnPending ? GuildGolemStatus.RESPAWN_PENDING : GuildGolemStatus.DEAD, "dead");
            stop(entity);
            return true;
        }
        if (record.guildId == null || record.guildId.isBlank() || !GuildStore.guildExists(record.guildId)) {
            setState(record, GuildGolemState.DATA_MISSING, GuildGolemStatus.ORPHAN, "missing guild");
            stop(entity);
            return true;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            setState(record, GuildGolemState.SAFE_WAIT, GuildGolemStatus.DATA_MISSING, "not server level");
            stop(entity);
            return true;
        }
        String dimension = GuildStore.dimensionId(level);
        GuildTerritoryCluster cluster = GuildTerritoryUnionCache.cluster(record.guildId, dimension);
        if (cluster == null || cluster.isEmpty()) {
            setState(record, GuildGolemState.SAFE_WAIT, GuildGolemStatus.DATA_MISSING, "no territory cluster");
            stop(entity);
            return true;
        }
        GuildGolemRole role = GuildGolemSquadCoordinator.assignRole(record);
        record.assignedRole = role.name();
        if (!cluster.contains(entity.blockPosition(), 0)) {
            int chaseBuffer = type.elite() ? HomeCraftGuildConfig.guildEliteGolemChaseOutsideTerritoryBlocks() : HomeCraftGuildConfig.guildGolemChaseOutsideTerritoryBlocks();
            if (!cluster.contains(entity.blockPosition(), chaseBuffer)) {
                clearTarget(entity);
                setState(record, type.elite() ? GuildGolemState.RETURN_TO_CORE : GuildGolemState.NORMAL_RETURN_HOME, GuildGolemStatus.RECOVERING, "outside guild cluster");
                GuildGolemTerritoryNavigator.returnHome(record, entity, cluster, context == null ? 0 : context.lagLevel());
                return true;
            }
        }
        rememberSafe(record, entity);
        if (!runAi) {
            setState(record, type.elite() ? GuildGolemState.ELITE_IDLE : GuildGolemState.NORMAL_IDLE, GuildGolemStatus.ALIVE, "safety only");
            return true;
        }
        if (handleHealing(context, record, entity, cluster, type)) {
            return true;
        }
        if (context != null && context.lagLevel() >= 3 && !criticalSlice(record, entity)) {
            setState(record, type.elite() ? GuildGolemState.STRATEGIC_GUARD : GuildGolemState.NORMAL_IDLE, GuildGolemStatus.ALIVE, "lag level 3 idle");
            return true;
        }

        LivingEntity current = entity instanceof Mob mob ? safeTarget(mob) : null;
        LivingEntity target = GuildGolemTargetSelector.select(context, record, entity, cluster, type, current);
        if (target != null) {
            engage(context, record, entity, cluster, type, target);
            return true;
        }
        if (current != null) clearTarget(entity);
        patrol(context, record, entity, cluster, type, role);
        return true;
    }


    private static boolean handleHealing(Context context, GuildStore.Golem record, Entity entity, GuildTerritoryCluster cluster, GuildGolemType type) {
        if (!HomeCraftGuildConfig.guildGolemHealingEnabled()) return false;
        if (record == null || entity == null || cluster == null || cluster.isEmpty()) return false;
        if (!(entity instanceof Mob mob) || !(entity instanceof LivingEntity living)) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;

        long now = level.getGameTime();
        boolean alreadyHealing = record.healingAtTotem || (record.aiState != null && record.aiState.contains("HEAL_AT_TOTEM"));
        int checkInterval = HomeCraftGuildConfig.guildGolemHealingCheckIntervalTicks();
        if (!alreadyHealing) {
            int safeInterval = Math.max(5, checkInterval);
            int hash = Math.floorMod(String.valueOf(record.uuid == null ? entity.getUUID() : record.uuid).hashCode(), safeInterval);
            if (record.lastHealthCheckTick > 0L && now - record.lastHealthCheckTick < safeInterval) return false;
            if (Math.floorMod((int)(now % safeInterval) + hash, safeInterval) != 0) return false;
        }
        record.lastHealthCheckTick = now;

        float maxHealth = Math.max(living.getMaxHealth(), record.guildMaxHealth > 0 ? (float)record.guildMaxHealth : 0.0F);
        if (maxHealth <= 0.0F) return false;
        float health = living.getHealth();
        double ratio = health / Math.max(1.0D, maxHealth);
        double startRatio = HomeCraftGuildConfig.guildGolemHealingStartHealthPercent() / 100.0D;
        boolean day = isDay(level);
        double configuredStopRatio = Math.max(startRatio + 0.01D, HomeCraftGuildConfig.guildGolemHealingStopHealthPercent() / 100.0D);
        double stopRatio = day && HomeCraftGuildConfig.guildGolemDayHealingRequiresFullHealth() ? 0.999D : configuredStopRatio;

        if (!alreadyHealing && ratio >= startRatio) return false;
        if (!alreadyHealing) rememberPreHealingTask(record, type);
        boolean fullyHealed = day && HomeCraftGuildConfig.guildGolemDayHealingRequiresFullHealth()
                ? health >= maxHealth - 0.01F
                : ratio >= stopRatio;
        if (alreadyHealing && fullyHealed) {
            finishHealing(context, level, entity, record, type, now);
            return true;
        }

        GuildStore.Territory totem = cluster.nearest(entity.getX(), entity.getZ());
        if (totem == null) totem = cluster.mainTotem;
        if (totem == null) return false;

        record.healingAtTotem = true;
        record.lastHealingTotemX = totem.x;
        record.lastHealingTotemY = totem.y;
        record.lastHealingTotemZ = totem.z;
        clearTarget(entity);

        GuildGolemSafePositionResolver.SpawnPoint healPoint = GuildGolemSafePositionResolver.near(level, cluster, totem.x + 0.5D, totem.z + 0.5D, 10);
        if (healPoint == null) {
            try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
            setState(record, GuildGolemState.SAFE_WAIT, GuildGolemStatus.RECOVERING, "no safe healing point near totem");
            return true;
        }

        double radius = Math.max(2.0D, HomeCraftGuildConfig.guildGolemHealingRadiusBlocks());
        double dist = entity.distanceToSqr(healPoint.x(), healPoint.y(), healPoint.z());
        if (dist <= radius * radius) {
            try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
            int applyInterval = HomeCraftGuildConfig.guildGolemHealingApplyIntervalTicks();
            if (record.lastHealingApplyTick <= 0L || now - record.lastHealingApplyTick >= Math.max(5, applyInterval)) {
                float heal = (float)Math.max(HomeCraftGuildConfig.guildGolemHealingMinPerSlice(), maxHealth * (HomeCraftGuildConfig.guildGolemHealingMaxHealthPercentPerSlice() / 100.0D));
                living.heal(heal);
                if (day && HomeCraftGuildConfig.guildGolemDayHealingRequiresFullHealth() && living.getHealth() > maxHealth - 0.05F) {
                    living.setHealth(maxHealth);
                }
                record.lastHealingApplyTick = now;
                if (living.getHealth() >= maxHealth - 0.01F || (living.getHealth() / Math.max(1.0F, maxHealth)) >= stopRatio) {
                    if (day && HomeCraftGuildConfig.guildGolemDayHealingRequiresFullHealth()) living.setHealth(maxHealth);
                    finishHealing(context, level, entity, record, type, now);
                    return true;
                }
            }
            rememberSafe(record, entity);
            setState(record, type.elite() ? GuildGolemState.ELITE_HEAL_AT_TOTEM : GuildGolemState.NORMAL_HEAL_AT_TOTEM, GuildGolemStatus.RECOVERING, "healing at totem");
            sendHealingVisual(context, level, entity.getX(), entity.getY() + 0.8D, entity.getZ(), false, now, record, false);
            return true;
        }

        GuildGolemTerritoryNavigator.moveTo(record, entity, cluster, healPoint, type.elite() ? 0.86D : 0.78D, context == null ? 0 : context.lagLevel(), GuildGolemRouteMode.RECOVERY);
        setState(record, type.elite() ? GuildGolemState.ELITE_HEAL_AT_TOTEM : GuildGolemState.NORMAL_HEAL_AT_TOTEM, GuildGolemStatus.RECOVERING, "go to totem for healing");
        return true;
    }

    private static void finishHealing(GuildGolemBrain.Context context, ServerLevel level, Entity entity, GuildStore.Golem record, GuildGolemType type, long now) {
        if (record == null || entity == null) return;
        record.healingAtTotem = false;
        record.lastHealingParticleTick = 0L;
        record.lastHealingApplyTick = 0L;
        restoreAfterHealing(record, type);
        sendHealingVisual(context, level, entity.getX(), entity.getY() + 0.8D, entity.getZ(), true, now, record, true);
    }

    private static void rememberPreHealingTask(GuildStore.Golem record, GuildGolemType type) {
        if (record == null) return;
        String state = record.aiState;
        if (state != null && state.contains("HEAL_AT_TOTEM")) return;
        record.preHealingAiState = (state == null || state.isBlank())
                ? (type.elite() ? GuildGolemState.STRATEGIC_GUARD.name() : GuildGolemState.NORMAL_PATROL.name())
                : state;
        record.preHealingAssignedRole = (record.assignedRole == null || record.assignedRole.isBlank())
                ? (type.elite() ? GuildGolemRole.ELITE_COMMAND.name() : GuildGolemRole.ROAMING.name())
                : record.assignedRole;
    }

    private static void restoreAfterHealing(GuildStore.Golem record, GuildGolemType type) {
        if (record == null) return;
        GuildGolemRole fallbackRole = type.elite() ? GuildGolemRole.ELITE_COMMAND : GuildGolemRole.ROAMING;
        GuildGolemState fallbackState = type.elite() ? GuildGolemState.STRATEGIC_GUARD : GuildGolemState.NORMAL_PATROL;
        GuildGolemRole role = parseRole(record.preHealingAssignedRole, fallbackRole);
        GuildGolemState state = parseState(record.preHealingAiState, fallbackState);
        record.assignedRole = role.name();
        record.preHealingAssignedRole = null;
        record.preHealingAiState = null;
        setState(record, state, GuildGolemStatus.ALIVE, "healed and restored previous task");
    }

    private static GuildGolemRole parseRole(String raw, GuildGolemRole fallback) {
        try { return raw == null || raw.isBlank() ? fallback : GuildGolemRole.valueOf(raw); }
        catch (Exception ignored) { return fallback; }
    }

    private static GuildGolemState parseState(String raw, GuildGolemState fallback) {
        try { return raw == null || raw.isBlank() ? fallback : GuildGolemState.valueOf(raw); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean isDay(ServerLevel level) {
        if (level == null) return true;
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time < 12542L || time > 23458L;
    }

    private static void sendHealingVisual(Context context, ServerLevel level, double x, double y, double z, boolean burst, long now, GuildStore.Golem record, boolean force) {
        if (context == null || context.server() == null || level == null || record == null) return;
        int interval = HomeCraftGuildConfig.guildGolemHealingParticleIntervalTicks();
        if (!force && record.lastHealingParticleTick > 0L && now - record.lastHealingParticleTick < Math.max(4, interval)) return;
        record.lastHealingParticleTick = now;
        String payload = String.format(Locale.ROOT, "event|golem_heal|%s|%.3f|%.3f|%.3f|%s", GuildStore.dimensionId(level), x, y, z, Boolean.toString(burst));
        double maxSqr = 48.0D * 48.0D;
        for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
            if (player == null || player.level() != level) continue;
            if (player.distanceToSqr(x, y, z) > maxSqr) continue;
            PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload(payload));
        }
    }

    private static void engage(Context context, GuildStore.Golem record, Entity entity, GuildTerritoryCluster cluster, GuildGolemType type, LivingEntity target) {
        if (!(entity instanceof Mob mob)) return;
        try { mob.setTarget(target); } catch (Throwable ignored) {}
        GuildGolemSafePositionResolver.SpawnPoint approach = null;
        if (entity.level() instanceof ServerLevel level) approach = GuildGolemSafePositionResolver.near(level, cluster, target.getX(), target.getZ(), type.elite() ? 18 : 12);
        double speed = type.elite() ? 1.08D : 0.92D;
        boolean moved = approach != null && GuildGolemTerritoryNavigator.moveTo(record, entity, cluster, approach, speed, context == null ? 0 : context.lagLevel(), GuildGolemRouteMode.RESPONSE);
        if (!moved) {
            try { mob.getNavigation().moveTo(target, speed); } catch (Throwable ignored) {}
        }
        record.lastTargetUuid = target.getUUID().toString();
        setState(record, type.elite() ? GuildGolemState.ELITE_ENGAGE : GuildGolemState.NORMAL_DEFEND_TERRITORY, GuildGolemStatus.ALIVE, "target");
    }

    private static void patrol(Context context, GuildStore.Golem record, Entity entity, GuildTerritoryCluster cluster, GuildGolemType type, GuildGolemRole role) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        GuildGolemSafePositionResolver.SpawnPoint point = GuildGolemPatrolPlanner.nextPoint(level, record, entity, cluster, type, role);
        if (point == null) {
            setState(record, GuildGolemState.SAFE_WAIT, GuildGolemStatus.DATA_MISSING, "no safe patrol point");
            return;
        }
        double dist = entity.distanceToSqr(point.x(), point.y(), point.z());
        if (dist <= 3.0D * 3.0D) {
            setState(record, type.elite() ? GuildGolemState.STRATEGIC_GUARD : GuildGolemState.NORMAL_IDLE, GuildGolemStatus.ALIVE, "at patrol point");
            stop(entity);
            return;
        }
        GuildGolemTerritoryNavigator.moveTo(record, entity, cluster, point, type.elite() ? 0.86D : 0.78D, context == null ? 0 : context.lagLevel(), patrolRouteMode(role));
        record.lastPatrolX = (int)Math.floor(point.x());
        record.lastPatrolY = (int)Math.floor(point.y());
        record.lastPatrolZ = (int)Math.floor(point.z());
        setState(record, type.elite() ? GuildGolemState.STRATEGIC_GUARD : GuildGolemState.NORMAL_PATROL, GuildGolemStatus.ALIVE, "patrol");
    }

    private static GuildGolemRouteMode patrolRouteMode(GuildGolemRole role) {
        if (role == null) return GuildGolemRouteMode.PATROL;
        return switch (role) {
            case PLAYER_SUPPORT, SUPPORT -> GuildGolemRouteMode.MEMBER_GUARD;
            case RECOVERY -> GuildGolemRouteMode.RECOVERY;
            case TOTEM, CENTER, BORDER, ROAMING, STRATEGIC_DEFENDER, ELITE_COMMAND -> GuildGolemRouteMode.PATROL;
        };
    }

    private static boolean criticalSlice(GuildStore.Golem record, Entity entity) {
        if (entity instanceof Mob mob && safeTarget(mob) != null) return true;
        if (record == null || entity == null) return false;
        return record.aiState != null && (record.aiState.contains("DEFEND") || record.aiState.contains("CHASE") || record.aiState.contains("RETURN") || record.aiState.contains("RECOVER") || record.aiState.contains("HEAL") || record.aiState.contains("ENGAGE"));
    }

    private static void migrateRuntimeDefaults(GuildStore.Golem record, GuildGolemType type) {
        if (record == null) return;
        record.golemType = type.name();
        record.elite = type.elite();
        if (record.behaviorProfile == null || record.behaviorProfile.isBlank()) record.behaviorProfile = type.elite() ? GuildGolemBehaviorProfile.ELITE_DEFENDER.name() : GuildGolemBehaviorProfile.NORMAL_GUARD.name();
        if (record.status == null || record.status.isBlank()) record.status = record.dead ? GuildGolemStatus.DEAD.name() : GuildGolemStatus.ALIVE.name();
        if (record.aiState == null || record.aiState.isBlank()) record.aiState = type.elite() ? GuildGolemState.ELITE_IDLE.name() : GuildGolemState.NORMAL_IDLE.name();
    }

    private static void setState(GuildStore.Golem record, GuildGolemState state, GuildGolemStatus status, String reason) {
        if (record == null || state == null || status == null) return;
        String old = record.aiState;
        record.aiState = state.name();
        record.status = status.name();
        if (old == null || !old.equals(record.aiState)) {
            GuildGolemDebugLogger.state(record.uuid, old, record.aiState, reason);
            GuildStore.touchGolemRuntime(record.uuid);
        }
    }

    private static void rememberSafe(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null) return;
        record.dimension = GuildStore.dimensionId(entity.level());
        record.x = (int)Math.floor(entity.getX());
        record.y = (int)Math.floor(entity.getY());
        record.z = (int)Math.floor(entity.getZ());
        record.lastSafeX = record.x;
        record.lastSafeY = record.y;
        record.lastSafeZ = record.z;
    }

    private static LivingEntity safeTarget(Mob mob) {
        try { return mob.getTarget(); }
        catch (Throwable ignored) { return null; }
    }

    private static void clearTarget(Entity entity) {
        if (entity instanceof Mob mob) {
            try { mob.setTarget(null); } catch (Throwable ignored) {}
        }
    }

    private static void stop(Entity entity) {
        if (entity instanceof Mob mob) {
            try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
            try { mob.setTarget(null); } catch (Throwable ignored) {}
        }
    }
}
