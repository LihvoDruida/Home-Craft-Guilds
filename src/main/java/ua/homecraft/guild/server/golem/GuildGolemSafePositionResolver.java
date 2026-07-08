package ua.homecraft.guild.server.golem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.homecraft.guild.server.GuildStore;

import java.util.Locale;

public final class GuildGolemSafePositionResolver {
    public record SpawnPoint(ServerLevel level, double x, double y, double z, GuildStore.Territory territory) {}

    private GuildGolemSafePositionResolver() {}

    public static SpawnPoint near(ServerLevel level, GuildTerritoryCluster cluster, double nearX, double nearZ, int maxRadius) {
        if (level == null || cluster == null || cluster.isEmpty()) return null;
        GuildStore.Territory nearest = cluster.nearest(nearX, nearZ);
        if (nearest == null) nearest = cluster.mainTotem;
        if (nearest == null) return null;
        int cx = clamp((int)Math.floor(nearX), GuildStore.minX(nearest), GuildStore.maxX(nearest));
        int cz = clamp((int)Math.floor(nearZ), GuildStore.minZ(nearest), GuildStore.maxZ(nearest));
        SpawnPoint first = validate(level, cluster, cx, cz, nearest, false);
        if (first != null) return first;
        int radiusLimit = Math.max(4, maxRadius);
        for (int radius = 1; radius <= radiusLimit; radius++) {
            int samples = Math.max(8, radius * 8);
            for (int i = 0; i < samples; i++) {
                double a = (Math.PI * 2.0D * i) / samples;
                int x = cx + (int)Math.round(Math.cos(a) * radius);
                int z = cz + (int)Math.round(Math.sin(a) * radius);
                GuildStore.Territory t = cluster.territoryForPoint(x, z);
                if (t == null) continue;
                SpawnPoint point = validate(level, cluster, x, z, t, false);
                if (point != null) return point;
            }
        }
        return null;
    }

    public static SpawnPoint strategic(ServerLevel level, GuildTerritoryCluster cluster) {
        if (level == null || cluster == null || cluster.isEmpty()) return null;
        SpawnPoint center = near(level, cluster, cluster.centerX + 0.5D, cluster.centerZ + 0.5D, 18);
        if (center != null) return center;
        GuildStore.Territory main = cluster.mainTotem;
        return main == null ? null : near(level, cluster, main.x + 0.5D, main.z + 0.5D, 18);
    }

    public static SpawnPoint validate(ServerLevel level, GuildTerritoryCluster cluster, int x, int z, GuildStore.Territory preferred, boolean strictTotemHeight) {
        if (level == null || cluster == null || !isChunkLoaded(level, x, z)) return null;
        GuildStore.Territory territory = cluster.territoryForPoint(x, z);
        if (territory == null) territory = preferred;
        if (territory == null) return null;
        BlockPos pos = safeSurface(level, x, z, strictTotemHeight ? territory.y : Integer.MIN_VALUE, strictTotemHeight ? 4 : Integer.MAX_VALUE);
        if (pos == null) return null;
        if (!cluster.contains(pos)) return null;
        GuildStore.Territory at = GuildStore.territoryAt(level, pos);
        if (at == null || !"GUILD".equals(at.type) || !territory.guildId.equals(at.guildId)) return null;
        return new SpawnPoint(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, at);
    }

    public static BlockPos safeSurface(ServerLevel level, int x, int z, int maxReferenceY, int maxAboveReference) {
        if (level == null || !isChunkLoaded(level, x, z)) return null;
        int y;
        try { y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z); }
        catch (Throwable ignored) { return null; }
        BlockPos base = new BlockPos(x, y, z);
        if (maxReferenceY != Integer.MIN_VALUE && base.getY() > maxReferenceY + Math.max(0, maxAboveReference)) return null;
        if (isWalkable(level, base)) return base;
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos up = base.above(dy);
            if (maxReferenceY != Integer.MIN_VALUE && up.getY() > maxReferenceY + Math.max(0, maxAboveReference)) continue;
            if (isWalkable(level, up)) return up;
        }
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos down = base.below(dy);
            if (isWalkable(level, down)) return down;
        }
        return null;
    }

    public static boolean isWalkable(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null || !isChunkLoaded(level, feet.getX(), feet.getZ())) return false;
        try {
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(feet.above());
            BlockState groundState = level.getBlockState(feet.below());
            if (!feetState.getCollisionShape(level, feet).isEmpty()) return false;
            if (!headState.getCollisionShape(level, feet.above()).isEmpty()) return false;
            if (groundState.getCollisionShape(level, feet.below()).isEmpty()) return false;
            if (danger(level, feet) || danger(level, feet.above()) || danger(level, feet.below())) return false;
            String feetId = blockId(level, feet);
            String headId = blockId(level, feet.above());
            String groundId = blockId(level, feet.below());
            if (feetId.contains("water") || headId.contains("water") || groundId.contains("water")) return false;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean danger(ServerLevel level, BlockPos pos) {
        String id = blockId(level, pos);
        return id.contains("lava") || id.contains("fire") || id.contains("cactus") || id.contains("campfire") || id.contains("magma") || id.contains("powder_snow") || id.contains("sweet_berry") || id.contains("void");
    }

    private static String blockId(ServerLevel level, BlockPos pos) {
        try { return String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())).toLowerCase(Locale.ROOT); }
        catch (Throwable ignored) { return ""; }
    }

    public static boolean isChunkLoaded(ServerLevel level, int blockX, int blockZ) {
        if (level == null) return false;
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        for (String methodName : new String[]{"hasChunk", "isLoaded"}) {
            try {
                Object value = level.getClass().getMethod(methodName, int.class, int.class).invoke(level, chunkX, chunkZ);
                if (value instanceof Boolean b) return b;
            } catch (Throwable ignored) {}
        }
        try {
            Object chunkSource = level.getClass().getMethod("getChunkSource").invoke(level);
            Object chunk = chunkSource.getClass().getMethod("getChunkNow", int.class, int.class).invoke(chunkSource, chunkX, chunkZ);
            return chunk != null;
        } catch (Throwable ignored) {
            return false;
        }
    }



    public static boolean isSafeGround(ServerLevel level, BlockPos pos) {
        return scoreNavigationPoint(level, pos, GuildGolemRouteMode.PATROL) < GuildGolemTerrainRisk.WATER_SHALLOW.penalty;
    }

    public static int estimateDropDepth(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !isChunkLoaded(level, pos.getX(), pos.getZ())) return 999;
        try {
            BlockPos cursor = pos.below();
            for (int depth = 0; depth <= 12; depth++) {
                if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) return depth;
                if (danger(level, cursor)) return 999;
                cursor = cursor.below();
            }
        } catch (Throwable ignored) {
            return 999;
        }
        return 999;
    }

    public static boolean isDangerousDrop(ServerLevel level, BlockPos pos, GuildGolemRouteMode mode) {
        int drop = estimateDropDepth(level, pos);
        int safeLimit = switch (mode == null ? GuildGolemRouteMode.PATROL : mode) {
            case RESPONSE, INTERCEPT_NEAREST_REACHABLE -> 4;
            case DIRECT_PATH, SOFT_DETOUR, WIDE_DETOUR -> 3;
            case RECOVERY, SAFE_ANCHOR_ROUTE, MEMBER_GUARD, PATROL -> 2;
            default -> 2;
        };
        return drop > safeLimit;
    }

    public static boolean isWaterPenalty(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !isChunkLoaded(level, pos.getX(), pos.getZ())) return true;
        String feetId = blockId(level, pos);
        String belowId = blockId(level, pos.below());
        String aboveId = blockId(level, pos.above());
        return feetId.contains("water") || belowId.contains("water") || aboveId.contains("water");
    }

    public static boolean isDeepWater(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !isChunkLoaded(level, pos.getX(), pos.getZ())) return false;
        int water = 0;
        for (int dy = -2; dy <= 1; dy++) {
            if (blockId(level, pos.offset(0, dy, 0)).contains("water")) water++;
        }
        return water >= 2;
    }

    public static int scoreNavigationPoint(ServerLevel level, BlockPos pos, GuildGolemRouteMode mode) {
        if (level == null || pos == null || !isChunkLoaded(level, pos.getX(), pos.getZ())) return GuildGolemTerrainRisk.BLOCKED.penalty;
        if (!isWalkable(level, pos)) {
            if (isWaterPenalty(level, pos) && canUseWater(mode)) return isDeepWater(level, pos) ? GuildGolemTerrainRisk.WATER_DEEP.penalty : GuildGolemTerrainRisk.WATER_SHALLOW.penalty;
            return GuildGolemTerrainRisk.BLOCKED.penalty;
        }
        int score = GuildGolemTerrainRisk.SAFE_GROUND.penalty;
        if (isWaterPenalty(level, pos)) score += isDeepWater(level, pos) ? GuildGolemTerrainRisk.WATER_DEEP.penalty : GuildGolemTerrainRisk.WATER_SHALLOW.penalty;
        int drop = estimateDropDepth(level, pos);
        if (drop >= 999) return GuildGolemTerrainRisk.VOID_OR_FATAL_DROP.penalty;
        if (isDangerousDrop(level, pos, mode)) score += GuildGolemTerrainRisk.DROP_RISK.penalty + Math.min(200, drop * 8);
        if (danger(level, pos) || danger(level, pos.below()) || danger(level, pos.above())) return GuildGolemTerrainRisk.VOID_OR_FATAL_DROP.penalty;
        return score;
    }

    public static GuildGolemTerrainRisk classifyNavigationPoint(ServerLevel level, BlockPos pos, GuildGolemRouteMode mode) {
        int score = scoreNavigationPoint(level, pos, mode);
        if (score >= GuildGolemTerrainRisk.VOID_OR_FATAL_DROP.penalty) return GuildGolemTerrainRisk.VOID_OR_FATAL_DROP;
        if (score >= GuildGolemTerrainRisk.BLOCKED.penalty) return GuildGolemTerrainRisk.BLOCKED;
        if (score >= GuildGolemTerrainRisk.DROP_RISK.penalty) return GuildGolemTerrainRisk.DROP_RISK;
        if (isDeepWater(level, pos)) return GuildGolemTerrainRisk.WATER_DEEP;
        if (isWaterPenalty(level, pos)) return GuildGolemTerrainRisk.WATER_SHALLOW;
        if (score > 0) return GuildGolemTerrainRisk.SOFT_RISK;
        return GuildGolemTerrainRisk.SAFE_GROUND;
    }

    public static SpawnPoint findNearbySafeDetourPoint(ServerLevel level, GuildTerritoryCluster cluster, double fromX, double fromZ, double targetX, double targetZ, GuildGolemRouteMode mode, int radius) {
        if (level == null || cluster == null || cluster.isEmpty()) return null;
        int samples = 0;
        int limitRadius = Math.max(3, Math.min(24, radius));
        SpawnPoint best = null;
        int bestScore = Integer.MAX_VALUE;
        double mx = (fromX + targetX) * 0.5D;
        double mz = (fromZ + targetZ) * 0.5D;
        for (int r = 3; r <= limitRadius; r += 3) {
            for (int side : new int[]{-1, 1}) {
                for (int step = -1; step <= 1; step++) {
                    if (++samples > 36) return best;
                    double dx = targetX - fromX;
                    double dz = targetZ - fromZ;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len < 0.001D) len = 1.0D;
                    double nx = -dz / len;
                    double nz = dx / len;
                    int x = (int)Math.floor(mx + nx * r * side + dx / len * step * 2.0D);
                    int z = (int)Math.floor(mz + nz * r * side + dz / len * step * 2.0D);
                    GuildStore.Territory territory = cluster.territoryForPoint(x, z);
                    if (territory == null) continue;
                    SpawnPoint point = validate(level, cluster, x, z, territory, false);
                    if (point == null) continue;
                    BlockPos bp = new BlockPos((int)Math.floor(point.x()), (int)Math.floor(point.y()), (int)Math.floor(point.z()));
                    int score = scoreNavigationPoint(level, bp, mode) + (int)Math.round(pointDistanceSqr(point.x(), point.z(), targetX, targetZ) / 8.0D);
                    if (score < bestScore) {
                        bestScore = score;
                        best = point;
                    }
                }
            }
        }
        return best;
    }

    public static SpawnPoint findNearestReachableSafePointNearTarget(ServerLevel level, GuildTerritoryCluster cluster, double targetX, double targetZ, GuildGolemRouteMode mode, int radius) {
        if (level == null || cluster == null || cluster.isEmpty()) return null;
        int limitRadius = Math.max(4, Math.min(28, radius));
        SpawnPoint best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int r = 0; r <= limitRadius; r += Math.max(1, r < 4 ? 1 : 2)) {
            int samples = Math.max(8, r * 6);
            for (int i = 0; i < samples; i++) {
                double a = Math.PI * 2.0D * i / samples;
                int x = (int)Math.floor(targetX + Math.cos(a) * r);
                int z = (int)Math.floor(targetZ + Math.sin(a) * r);
                GuildStore.Territory territory = cluster.territoryForPoint(x, z);
                if (territory == null) continue;
                SpawnPoint point = validate(level, cluster, x, z, territory, false);
                if (point == null) continue;
                BlockPos bp = new BlockPos((int)Math.floor(point.x()), (int)Math.floor(point.y()), (int)Math.floor(point.z()));
                int score = scoreNavigationPoint(level, bp, mode) + r * 3;
                if (score < bestScore) {
                    bestScore = score;
                    best = point;
                }
            }
            if (best != null && bestScore < GuildGolemTerrainRisk.WATER_SHALLOW.penalty) return best;
        }
        return best;
    }

    private static boolean canUseWater(GuildGolemRouteMode mode) {
        return mode == GuildGolemRouteMode.RESPONSE || mode == GuildGolemRouteMode.INTERCEPT_NEAREST_REACHABLE || mode == GuildGolemRouteMode.DIRECT_PATH;
    }

    private static double pointDistanceSqr(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
