package ua.homecraft.guild.server.golem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.homecraft.guild.server.GuildStore;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

import java.util.HashMap;
import java.util.Map;

public final class GuildGolemPatrolPlanner {
    private static final Map<String, Reservation> PATROL_POINT_RESERVATIONS = new HashMap<>();
    private static final long RESERVATION_TTL_TICKS = 120L;

    private GuildGolemPatrolPlanner() {}

    public static GuildGolemSafePositionResolver.SpawnPoint nextPoint(ServerLevel level, GuildStore.Golem record, Entity entity, GuildTerritoryCluster cluster, GuildGolemType type, GuildGolemRole role) {
        if (level == null || record == null || entity == null || cluster == null || cluster.isEmpty()) return null;
        if (type.elite()) return elitePoint(level, record, entity, cluster, role);
        return normalPoint(level, record, cluster, role);
    }

    private static GuildGolemSafePositionResolver.SpawnPoint elitePoint(ServerLevel level, GuildStore.Golem record, Entity entity, GuildTerritoryCluster cluster, GuildGolemRole role) {
        GuildStore.Territory totem = cluster.mainTotem == null ? cluster.nearest(entity.getX(), entity.getZ()) : cluster.mainTotem;
        GuildGolemSafePositionResolver.SpawnPoint point = null;
        if (role == GuildGolemRole.ELITE_COMMAND || role == GuildGolemRole.STRATEGIC_DEFENDER || role == GuildGolemRole.TOTEM) {
            if (totem != null) point = GuildGolemSafePositionResolver.near(level, cluster, totem.x + 0.5D, totem.z + 0.5D, 10);
        }
        if (point == null) point = GuildGolemSafePositionResolver.strategic(level, cluster);
        point = reservePatrolPoint(level, record, cluster, point);
        rememberStrategic(record, point);
        return point;
    }

    private static GuildGolemSafePositionResolver.SpawnPoint normalPoint(ServerLevel level, GuildStore.Golem record, GuildTerritoryCluster cluster, GuildGolemRole role) {
        int index = GuildGolemSquadCoordinator.normalIndex(record);
        int count = Math.max(1, GuildGolemSquadCoordinator.activeNormalCount(record.guildId));
        int territoryCount = Math.max(1, cluster.territories.size());
        GuildStore.Territory territory = cluster.territories.get(Math.floorMod(index, territoryCount));
        int localIndex = Math.max(0, index / territoryCount);
        int localCount = Math.max(1, (count + territoryCount - 1) / territoryCount);

        int minX = GuildStore.minX(territory);
        int maxX = GuildStore.maxX(territory);
        int minZ = GuildStore.minZ(territory);
        int maxZ = GuildStore.maxZ(territory);
        int innerMinX = Math.min(maxX, minX + 3);
        int innerMaxX = Math.max(minX, maxX - 3);
        int innerMinZ = Math.min(maxZ, minZ + 3);
        int innerMaxZ = Math.max(minZ, maxZ - 3);

        if (isNight(level) && HomeCraftGuildConfig.guildGolemNightPatrolEnabled() && HomeCraftGuildConfig.guildGolemNightPatrolNearMembers()) {
            GuildGolemSafePositionResolver.SpawnPoint memberPoint = nightMemberSupportPoint(level, record, cluster, role, index);
            if (memberPoint != null) {
                memberPoint = reservePatrolPoint(level, record, cluster, memberPoint);
                rememberPatrol(record, memberPoint);
                return memberPoint;
            }
        }

        int x;
        int z;

        switch (role) {
            case BORDER -> {
                int side = Math.floorMod(index, 4);
                int spanX = Math.max(1, innerMaxX - innerMinX + 1);
                int spanZ = Math.max(1, innerMaxZ - innerMinZ + 1);
                int laneX = evenlySpaced(innerMinX, innerMaxX, localIndex, localCount);
                int laneZ = evenlySpaced(innerMinZ, innerMaxZ, localIndex, localCount);
                if (side == 0) { x = laneX; z = innerMinZ; }
                else if (side == 1) { x = laneX; z = innerMaxZ; }
                else if (side == 2) { x = innerMinX; z = laneZ; }
                else { x = innerMaxX; z = laneZ; }
                // Keep the compiler from optimizing the documented span variables away as dead intent.
                if (spanX <= 0 || spanZ <= 0) { x = territory.x; z = territory.z; }
            }
            case TOTEM -> {
                int[][] ring = {{5,0},{0,5},{-5,0},{0,-5},{4,4},{-4,4},{4,-4},{-4,-4}};
                int[] off = ring[Math.floorMod(index + localIndex, ring.length)];
                x = territory.x + off[0];
                z = territory.z + off[1];
            }
            case PLAYER_SUPPORT -> {
                // Not tied to one live player until danger triggers. This keeps normal patrol work
                // distributed between likely player routes instead of stacking on the totem.
                int lane = Math.floorMod(index * 3 + localIndex, 9) - 4;
                x = (territory.x * 2 + cluster.centerX) / 3 + lane;
                z = (territory.z + cluster.centerZ * 2) / 3 - lane;
            }
            case CENTER -> {
                int[][] core = {{0,0},{6,0},{-6,0},{0,6},{0,-6},{5,5},{-5,5},{5,-5},{-5,-5}};
                int[] off = core[Math.floorMod(index, core.length)];
                x = cluster.centerX + off[0];
                z = cluster.centerZ + off[1];
                GuildStore.Territory owner = cluster.territoryForPoint(x, z);
                if (owner != null) territory = owner;
            }
            case ROAMING, SUPPORT -> {
                int columns = Math.max(1, (int)Math.ceil(Math.sqrt(localCount + 1.0D)));
                int rows = Math.max(1, (localCount + columns - 1) / columns);
                int gx = Math.floorMod(localIndex, columns);
                int gz = Math.floorMod(localIndex / columns, rows);
                x = evenlySpaced(innerMinX, innerMaxX, gx, columns);
                z = evenlySpaced(innerMinZ, innerMaxZ, gz, rows);
            }
            default -> {
                x = evenlySpaced(innerMinX, innerMaxX, localIndex, localCount);
                z = evenlySpaced(innerMinZ, innerMaxZ, index + localIndex, localCount + 1);
            }
        }

        x = clamp(x, GuildStore.minX(territory), GuildStore.maxX(territory));
        z = clamp(z, GuildStore.minZ(territory), GuildStore.maxZ(territory));
        GuildGolemSafePositionResolver.SpawnPoint point = GuildGolemSafePositionResolver.near(level, cluster, x + 0.5D, z + 0.5D, 12);
        point = reservePatrolPoint(level, record, cluster, point);
        rememberPatrol(record, point);
        return point;
    }

    private static GuildGolemSafePositionResolver.SpawnPoint nightMemberSupportPoint(ServerLevel level, GuildStore.Golem record, GuildTerritoryCluster cluster, GuildGolemRole role, int index) {
        if (level == null || record == null || record.guildId == null || cluster == null || cluster.isEmpty()) return null;
        // Keep one or two golems on hard strategic jobs. The rest bias closer to live guild members at night.
        if (role == GuildGolemRole.TOTEM || role == GuildGolemRole.CENTER || role == GuildGolemRole.ELITE_COMMAND || role == GuildGolemRole.STRATEGIC_DEFENDER) return null;
        ServerPlayer member = assignedVisibleGuildMember(level, record.guildId, cluster, index);
        if (member == null) return null;
        int radius = HomeCraftGuildConfig.guildGolemNightMemberSupportRadiusBlocks();
        int[][] offsets = {
                {radius, 0}, {-radius, 0}, {0, radius}, {0, -radius},
                {radius / 2, radius / 2}, {-radius / 2, radius / 2}, {radius / 2, -radius / 2}, {-radius / 2, -radius / 2}
        };
        int[] off = offsets[Math.floorMod(index, offsets.length)];
        GuildGolemSafePositionResolver.SpawnPoint point = GuildGolemSafePositionResolver.near(level, cluster, member.getX() + off[0], member.getZ() + off[1], Math.max(10, radius + 4));
        if (point == null) point = GuildGolemSafePositionResolver.near(level, cluster, member.getX(), member.getZ(), Math.max(10, radius + 4));
        return point;
    }

    private static ServerPlayer assignedVisibleGuildMember(ServerLevel level, String guildId, GuildTerritoryCluster cluster, int index) {
        if (level == null || guildId == null || level.getServer() == null || cluster == null) return null;
        java.util.List<ServerPlayer> members = new java.util.ArrayList<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player == null || player.level() != level) continue;
            if (player.isSpectator()) continue;
            try { if (player.getAbilities().instabuild) continue; } catch (Throwable ignored) {}
            if (!GuildStore.isMemberOfGuild(player, guildId)) continue;
            if (!cluster.contains(player.blockPosition(), 0)) continue;
            if (HomeCraftGuildConfig.guildGolemNightIgnoreUndergroundMembers() && isUndergroundMember(level, player)) continue;
            members.add(player);
        }
        if (members.isEmpty()) return null;
        members.sort(java.util.Comparator.comparing(p -> p.getUUID().toString()));
        return members.get(Math.floorMod(index, members.size()));
    }

    private static boolean isUndergroundMember(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) return true;
        int x = player.blockPosition().getX();
        int z = player.blockPosition().getZ();
        if (!GuildGolemSafePositionResolver.isChunkLoaded(level, x, z)) return true;
        try {
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            return player.blockPosition().getY() < surfaceY - 2;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static GuildGolemSafePositionResolver.SpawnPoint reservePatrolPoint(ServerLevel level, GuildStore.Golem record, GuildTerritoryCluster cluster, GuildGolemSafePositionResolver.SpawnPoint point) {
        if (level == null || record == null || point == null) return point;
        long now = level.getGameTime();
        String uuid = record.uuid == null ? "" : record.uuid;
        cleanupReservations(now);
        if (canReserve(point, uuid, now)) {
            PATROL_POINT_RESERVATIONS.put(pointKey(point), new Reservation(uuid, now + RESERVATION_TTL_TICKS));
            return point;
        }
        int[][] offsets = {{4,0},{-4,0},{0,4},{0,-4},{3,3},{-3,3},{3,-3},{-3,-3},{7,0},{0,7},{-7,0},{0,-7}};
        for (int[] off : offsets) {
            GuildGolemSafePositionResolver.SpawnPoint alternative = GuildGolemSafePositionResolver.near(level, cluster, point.x() + off[0], point.z() + off[1], 8);
            if (alternative == null) continue;
            if (!canReserve(alternative, uuid, now)) continue;
            PATROL_POINT_RESERVATIONS.put(pointKey(alternative), new Reservation(uuid, now + RESERVATION_TTL_TICKS));
            return alternative;
        }
        return point;
    }

    private static boolean canReserve(GuildGolemSafePositionResolver.SpawnPoint point, String uuid, long now) {
        Reservation reservation = PATROL_POINT_RESERVATIONS.get(pointKey(point));
        return reservation == null || reservation.expireTick() <= now || reservation.golemUuid().equals(uuid);
    }

    private static void cleanupReservations(long now) {
        if (PATROL_POINT_RESERVATIONS.size() > 4096) PATROL_POINT_RESERVATIONS.clear();
        PATROL_POINT_RESERVATIONS.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expireTick() <= now);
    }

    private static String pointKey(GuildGolemSafePositionResolver.SpawnPoint point) {
        int x = (int)Math.floor(point.x());
        int y = (int)Math.floor(point.y());
        int z = (int)Math.floor(point.z());
        return GuildStore.dimensionId(point.level()) + '|' + x + '|' + y + '|' + z;
    }

    private record Reservation(String golemUuid, long expireTick) {}

    private static boolean isNight(ServerLevel level) {
        if (level == null) return false;
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 12542L && time <= 23458L;
    }

    private static int evenlySpaced(int min, int max, int index, int count) {
        if (max <= min) return min;
        int slots = Math.max(1, count);
        double fraction = (Math.floorMod(index, slots) + 0.5D) / slots;
        return clamp((int)Math.round(min + (max - min) * fraction), min, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void rememberPatrol(GuildStore.Golem record, GuildGolemSafePositionResolver.SpawnPoint point) {
        if (record == null || point == null) return;
        record.lastPatrolX = (int)Math.floor(point.x());
        record.lastPatrolY = (int)Math.floor(point.y());
        record.lastPatrolZ = (int)Math.floor(point.z());
    }

    private static void rememberStrategic(GuildStore.Golem record, GuildGolemSafePositionResolver.SpawnPoint point) {
        if (record == null || point == null) return;
        record.lastStrategicX = (int)Math.floor(point.x());
        record.lastStrategicY = (int)Math.floor(point.y());
        record.lastStrategicZ = (int)Math.floor(point.z());
    }
}
