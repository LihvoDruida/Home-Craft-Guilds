package ua.homecraft.guild.server.golem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.server.GuildStore;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Low-cost route correction for guild golems. The server thread validates world
 * positions and prepares a tiny numeric snapshot; the background worker only
 * sorts already validated candidate coordinates and never touches Minecraft
 * world/entity API.
 */
public final class GuildGolemRoutePlanner {
    private static final int MAX_ROUTE_STARTS_PER_TICK = 4;
    private static final int MAX_SAFE_CHECKS_PER_ROUTE = 40;
    private static final long CACHE_TTL_TICKS = 20L * 30L;
    private static final int CACHE_MAX_SIZE = 2048;
    private static final Map<String, CacheEntry> BLOCKED_ROUTE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CacheEntry> UNSAFE_POINT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CacheEntry> WATER_PENALTY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CacheEntry> DETOUR_CANDIDATE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, PendingRoute> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService ROUTE_WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "HomeCraft-Guild-Golem-RoutePlanner");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        }
    });
    private static long budgetTick = -1L;
    private static int routeStartsThisTick = 0;
    private static int cleanupCursor = 0;

    private GuildGolemRoutePlanner() {}

    public static boolean moveSmart(GuildStore.Golem record, Entity entity, GuildTerritoryCluster cluster, GuildGolemSafePositionResolver.SpawnPoint destination, double speed, int lagLevel, GuildGolemRouteMode mode) {
        if (record == null || entity == null || destination == null || !(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        resetTickBudget(now);
        cleanupSliced(now);

        GuildGolemRouteMode safeMode = mode == null ? GuildGolemRouteMode.DIRECT_PATH : mode;
        if (!HomeCraftGuildConfig.guildGolemRouteFallbackEnabled()) return vanillaMove(record, mob, destination, speed, lagLevel, safeMode, now);
        if (lagLevel >= 3 && !isCritical(safeMode)) {
            record.routeStatus = "Очікує безпечний маршрут";
            return false;
        }

        BlockPos targetPos = blockPos(destination.x(), destination.y(), destination.z());
        int targetScore = cachedScore(WATER_PENALTY_CACHE, level, targetPos, safeMode, now);
        if (targetScore >= GuildGolemTerrainRisk.BLOCKED.penalty || GuildGolemSafePositionResolver.isDangerousDrop(level, targetPos, safeMode)) {
            rememberUnsafe(level, targetPos, now, "destination unsafe");
            GuildGolemSafePositionResolver.SpawnPoint fallback = GuildGolemSafePositionResolver.findNearestReachableSafePointNearTarget(level, cluster, destination.x(), destination.z(), isCritical(safeMode) ? GuildGolemRouteMode.INTERCEPT_NEAREST_REACHABLE : safeMode, isCritical(safeMode) ? 20 : 12);
            if (fallback != null) {
                destination = fallback;
                targetPos = blockPos(destination.x(), destination.y(), destination.z());
                safeMode = isCritical(safeMode) ? GuildGolemRouteMode.INTERCEPT_NEAREST_REACHABLE : GuildGolemRouteMode.SAFE_ANCHOR_ROUTE;
                record.routeStatus = isCritical(mode) ? "Йде на тривогу" : terrainStatus(level, targetPos, safeMode);
            } else {
                record.routeStatus = "Очікує безпечний маршрут";
                return false;
            }
        }

        if (isStuck(record, entity, destination, now, mob)) {
            record.pathFailureCount = Math.min(1000, Math.max(0, record.pathFailureCount) + 1);
            markBlocked(record, level, destination, now, "stuck");
            GuildGolemDebugLogger.path(record.uuid, "stuck detected failures=" + record.pathFailureCount + " mode=" + safeMode);
            GuildGolemSafePositionResolver.SpawnPoint detour = findDetour(record, level, cluster, entity, destination, safeMode, now);
            if (detour != null) {
                destination = detour;
                safeMode = record.pathFailureCount >= 3 ? GuildGolemRouteMode.WIDE_DETOUR : GuildGolemRouteMode.SOFT_DETOUR;
                record.routeStatus = "Застряг — перебудова маршруту";
            } else if (isCritical(safeMode)) {
                GuildGolemSafePositionResolver.SpawnPoint intercept = GuildGolemSafePositionResolver.findNearestReachableSafePointNearTarget(level, cluster, destination.x(), destination.z(), GuildGolemRouteMode.INTERCEPT_NEAREST_REACHABLE, 24);
                if (intercept != null) {
                    destination = intercept;
                    safeMode = GuildGolemRouteMode.INTERCEPT_NEAREST_REACHABLE;
                    record.routeStatus = "Йде на тривогу";
                } else {
                    record.routeStatus = "Очікує безпечний маршрут";
                    return false;
                }
            } else {
                GuildGolemSafePositionResolver.SpawnPoint guard = GuildGolemSafePositionResolver.near(level, cluster, record.lastSafeX, record.lastSafeZ, 12);
                if (guard != null) {
                    destination = guard;
                    safeMode = GuildGolemRouteMode.FAIL_IDLE_GUARD;
                    record.routeStatus = "Очікує безпечний маршрут";
                } else return false;
            }
        }

        PendingRoute pending = pollPending(record, level, cluster, now);
        if (pending != null && pending.result != null) {
            GuildGolemSafePositionResolver.SpawnPoint planned = GuildGolemSafePositionResolver.validate(level, cluster, pending.result.x, pending.result.z, cluster.territoryForPoint(pending.result.x, pending.result.z), false);
            if (planned != null) {
                destination = planned;
                safeMode = pending.result.mode;
                record.routeStatus = routeStatusFor(pending.result.risk, safeMode, false);
            }
        } else if (shouldSubmitAsync(record, now, lagLevel, safeMode)) {
            submitAsync(record, level, cluster, entity, destination, safeMode, now);
        }

        BlockPos finalPos = blockPos(destination.x(), destination.y(), destination.z());
        GuildGolemTerrainRisk risk = GuildGolemSafePositionResolver.classifyNavigationPoint(level, finalPos, safeMode);
        if (risk == GuildGolemTerrainRisk.WATER_DEEP && !isCritical(safeMode)) {
            GuildGolemSafePositionResolver.SpawnPoint dry = findDetour(record, level, cluster, entity, destination, GuildGolemRouteMode.SAFE_ANCHOR_ROUTE, now);
            if (dry != null) {
                destination = dry;
                safeMode = GuildGolemRouteMode.SAFE_ANCHOR_ROUTE;
                risk = GuildGolemSafePositionResolver.classifyNavigationPoint(level, blockPos(dry.x(), dry.y(), dry.z()), safeMode);
            } else {
                record.routeStatus = "Уникає води";
                return false;
            }
        }
        if (risk == GuildGolemTerrainRisk.DROP_RISK || risk == GuildGolemTerrainRisk.VOID_OR_FATAL_DROP) {
            GuildGolemSafePositionResolver.SpawnPoint safe = findDetour(record, level, cluster, entity, destination, GuildGolemRouteMode.WIDE_DETOUR, now);
            if (safe != null) {
                destination = safe;
                safeMode = GuildGolemRouteMode.WIDE_DETOUR;
                risk = GuildGolemSafePositionResolver.classifyNavigationPoint(level, blockPos(safe.x(), safe.y(), safe.z()), safeMode);
            } else if (!isCritical(safeMode)) {
                record.routeStatus = "Уникає провалу";
                return false;
            }
        }

        boolean moved = vanillaMove(record, mob, destination, speed, lagLevel, safeMode, now);
        if (!moved) {
            markBlocked(record, level, destination, now, "navigation rejected");
            record.routeStatus = isCritical(safeMode) ? "Йде на тривогу" : "Шукає обхід";
        } else {
            record.routeStatus = routeStatusFor(risk, safeMode, true);
        }
        return moved;
    }

    private static boolean vanillaMove(GuildStore.Golem record, Mob mob, GuildGolemSafePositionResolver.SpawnPoint point, double speed, int lagLevel, GuildGolemRouteMode mode, long now) {
        int baseCooldown = GuildGolemType.from(record).elite() ? HomeCraftGuildConfig.guildEliteGolemPathRecalcCooldownTicks() : HomeCraftGuildConfig.guildGolemPathRecalcCooldownTicks();
        int cooldown = lagLevel >= 3 ? baseCooldown * HomeCraftGuildConfig.guildGolemLagLevel3PathRecalcMultiplier() : (lagLevel >= 2 ? baseCooldown * 2 : baseCooldown);
        if (isCritical(mode)) cooldown = Math.max(6, cooldown / 2);
        double dx = point.x() - record.lastPathTargetX;
        double dy = point.y() - record.lastPathTargetY;
        double dz = point.z() - record.lastPathTargetZ;
        boolean movedTarget = dx * dx + dy * dy + dz * dz > 3.0D * 3.0D;
        if (!mob.getNavigation().isDone() && !movedTarget && now - record.lastPathRecalcTick < Math.max(1, cooldown)) return true;
        if (!consumeRouteBudget(now, mode)) return !mob.getNavigation().isDone();
        record.lastPathRecalcTick = now;
        record.lastPathTargetX = point.x();
        record.lastPathTargetY = point.y();
        record.lastPathTargetZ = point.z();
        boolean ok;
        try { ok = mob.getNavigation().moveTo(point.x(), point.y(), point.z(), speed); }
        catch (Throwable ignored) { ok = false; }
        if (!ok) {
            record.pathFailureCount = Math.min(1000, Math.max(0, record.pathFailureCount) + 1);
            GuildGolemDebugLogger.path(record.uuid, "move failed mode=" + mode + " x=" + point.x() + " y=" + point.y() + " z=" + point.z() + " failures=" + record.pathFailureCount);
        } else {
            record.pathFailureCount = 0;
        }
        return ok;
    }

    private static GuildGolemSafePositionResolver.SpawnPoint findDetour(GuildStore.Golem record, ServerLevel level, GuildTerritoryCluster cluster, Entity entity, GuildGolemSafePositionResolver.SpawnPoint destination, GuildGolemRouteMode mode, long now) {
        String key = detourKey(record, level, destination, mode);
        CacheEntry cached = DETOUR_CANDIDATE_CACHE.get(key);
        if (cached != null && cached.valid(now)) {
            GuildGolemSafePositionResolver.SpawnPoint point = GuildGolemSafePositionResolver.validate(level, cluster, cached.x, cached.z, cluster.territoryForPoint(cached.x, cached.z), false);
            if (point != null) return point;
        }
        int radius = mode == GuildGolemRouteMode.WIDE_DETOUR || record.pathFailureCount >= 3 ? 18 : 10;
        GuildGolemSafePositionResolver.SpawnPoint point = GuildGolemSafePositionResolver.findNearbySafeDetourPoint(level, cluster, entity.getX(), entity.getZ(), destination.x(), destination.z(), mode, radius);
        if (point != null) {
            DETOUR_CANDIDATE_CACHE.put(key, new CacheEntry((int)Math.floor(point.x()), (int)Math.floor(point.y()), (int)Math.floor(point.z()), now, "detour"));
        }
        return point;
    }

    private static boolean isStuck(GuildStore.Golem record, Entity entity, GuildGolemSafePositionResolver.SpawnPoint destination, long now, Mob mob) {
        if (record.routeLastCheckTick > 0L && now - record.routeLastCheckTick < 20L) return false;
        double moved = distanceSqr(entity.getX(), entity.getY(), entity.getZ(), record.routeLastX, record.routeLastY, record.routeLastZ);
        double dist = entity.distanceToSqr(destination.x(), destination.y(), destination.z());
        boolean noProgress = record.routeLastDistanceSqr > 0.0D && dist >= record.routeLastDistanceSqr - 1.0D;
        boolean active = true;
        try { active = !mob.getNavigation().isDone(); } catch (Throwable ignored) {}
        boolean stuck = active && moved < 0.35D * 0.35D && noProgress;
        record.routeLastCheckTick = now;
        record.routeLastX = entity.getX();
        record.routeLastY = entity.getY();
        record.routeLastZ = entity.getZ();
        record.routeLastDistanceSqr = dist;
        if (stuck) record.routeStuckTicks = Math.min(200, record.routeStuckTicks + 20);
        else record.routeStuckTicks = Math.max(0, record.routeStuckTicks - 10);
        return record.routeStuckTicks >= 40;
    }

    private static boolean shouldSubmitAsync(GuildStore.Golem record, long now, int lagLevel, GuildGolemRouteMode mode) {
        if (lagLevel >= 3 && !isCritical(mode)) return false;
        if (!HomeCraftGuildConfig.guildGolemRouteAsyncEnabled()) return false;
        if (record.uuid == null || PENDING.containsKey(record.uuid)) return false;
        if (record.lastAsyncRouteRequestTick > 0L && now - record.lastAsyncRouteRequestTick < (isCritical(mode) ? 20L : 80L)) return false;
        return record.pathFailureCount > 0 || mode == GuildGolemRouteMode.PATROL || mode == GuildGolemRouteMode.MEMBER_GUARD;
    }

    private static void submitAsync(GuildStore.Golem record, ServerLevel level, GuildTerritoryCluster cluster, Entity entity, GuildGolemSafePositionResolver.SpawnPoint destination, GuildGolemRouteMode mode, long now) {
        if (record.uuid == null) return;
        RouteCandidate[] candidates = collectCandidates(level, cluster, entity.getX(), entity.getZ(), destination.x(), destination.z(), mode);
        if (candidates.length == 0) return;
        long revision = cluster.revision;
        String uuid = record.uuid;
        record.lastAsyncRouteRequestTick = now;
        CompletableFuture<RouteCandidate> future = CompletableFuture.supplyAsync(() -> chooseBest(candidates), ROUTE_WORKER);
        PENDING.put(uuid, new PendingRoute(uuid, revision, now, future));
    }

    private static PendingRoute pollPending(GuildStore.Golem record, ServerLevel level, GuildTerritoryCluster cluster, long now) {
        if (record == null || record.uuid == null) return null;
        PendingRoute pending = PENDING.get(record.uuid);
        if (pending == null) return null;
        if (pending.createdTick + 100L < now || pending.revision != cluster.revision) {
            PENDING.remove(record.uuid);
            return null;
        }
        if (!pending.future.isDone()) return null;
        try { pending.result = pending.future.getNow(null); }
        catch (Throwable ignored) { pending.result = null; }
        PENDING.remove(record.uuid);
        if (pending.result == null) return null;
        return pending;
    }

    private static RouteCandidate[] collectCandidates(ServerLevel level, GuildTerritoryCluster cluster, double fromX, double fromZ, double targetX, double targetZ, GuildGolemRouteMode mode) {
        java.util.ArrayList<RouteCandidate> out = new java.util.ArrayList<>();
        int checks = 0;
        for (int radius : new int[]{0, 4, 8, 12, 18}) {
            int samples = radius == 0 ? 1 : Math.max(8, radius * 4);
            for (int i = 0; i < samples && checks++ < Math.max(8, HomeCraftGuildConfig.guildGolemRouteMaxSafeChecks()); i++) {
                double a = samples == 1 ? 0.0D : Math.PI * 2.0D * i / samples;
                int x = (int)Math.floor(targetX + Math.cos(a) * radius);
                int z = (int)Math.floor(targetZ + Math.sin(a) * radius);
                GuildStore.Territory territory = cluster.territoryForPoint(x, z);
                if (territory == null) continue;
                GuildGolemSafePositionResolver.SpawnPoint point = GuildGolemSafePositionResolver.validate(level, cluster, x, z, territory, false);
                if (point == null) continue;
                BlockPos bp = blockPos(point.x(), point.y(), point.z());
                GuildGolemTerrainRisk risk = GuildGolemSafePositionResolver.classifyNavigationPoint(level, bp, mode);
                if (risk.blocked()) continue;
                int score = risk.penalty + radius * 4 + (int)Math.min(300, distanceSqr(fromX, 0, fromZ, point.x(), 0, point.z()) / 16.0D);
                out.add(new RouteCandidate((int)Math.floor(point.x()), (int)Math.floor(point.y()), (int)Math.floor(point.z()), mode, risk, score));
            }
        }
        return out.toArray(new RouteCandidate[0]);
    }

    private static RouteCandidate chooseBest(RouteCandidate[] candidates) {
        RouteCandidate best = null;
        for (RouteCandidate candidate : candidates) {
            if (candidate == null) continue;
            if (best == null || candidate.score < best.score) best = candidate;
        }
        return best;
    }

    private static int cachedScore(Map<String, CacheEntry> cache, ServerLevel level, BlockPos pos, GuildGolemRouteMode mode, long now) {
        String key = pointKey(level, pos, mode);
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.valid(now)) return entry.score;
        int score = GuildGolemSafePositionResolver.scoreNavigationPoint(level, pos, mode);
        cache.put(key, new CacheEntry(pos.getX(), pos.getY(), pos.getZ(), now, score, "score"));
        return score;
    }

    private static void rememberUnsafe(ServerLevel level, BlockPos pos, long now, String reason) {
        UNSAFE_POINT_CACHE.put(pointKey(level, pos, GuildGolemRouteMode.DIRECT_PATH), new CacheEntry(pos.getX(), pos.getY(), pos.getZ(), now, GuildGolemTerrainRisk.BLOCKED.penalty, reason));
    }

    private static void markBlocked(GuildStore.Golem record, ServerLevel level, GuildGolemSafePositionResolver.SpawnPoint destination, long now, String reason) {
        String key = routeKey(record, level, destination);
        BLOCKED_ROUTE_CACHE.put(key, new CacheEntry((int)Math.floor(destination.x()), (int)Math.floor(destination.y()), (int)Math.floor(destination.z()), now, GuildGolemTerrainRisk.BLOCKED.penalty, reason));
        record.lastRouteFailureReason = reason;
        record.lastBlockedRouteKey = key;
    }

    private static boolean consumeRouteBudget(long now, GuildGolemRouteMode mode) {
        resetTickBudget(now);
        if (isCritical(mode)) return true;
        if (routeStartsThisTick >= Math.max(1, HomeCraftGuildConfig.guildGolemRouteMaxStartsPerTick())) return false;
        routeStartsThisTick++;
        return true;
    }

    private static void resetTickBudget(long now) {
        if (budgetTick != now) {
            budgetTick = now;
            routeStartsThisTick = 0;
        }
    }

    private static void cleanupSliced(long now) {
        if (cleanupCursor++ % 40 != 0) return;
        cleanupMap(BLOCKED_ROUTE_CACHE, now);
        cleanupMap(UNSAFE_POINT_CACHE, now);
        cleanupMap(WATER_PENALTY_CACHE, now);
        cleanupMap(DETOUR_CANDIDATE_CACHE, now);
    }

    private static void cleanupMap(Map<String, CacheEntry> map, long now) {
        if (map.size() <= CACHE_MAX_SIZE) return;
        int removed = 0;
        for (String key : new java.util.ArrayList<>(map.keySet())) {
            CacheEntry entry = map.get(key);
            if (entry == null || !entry.valid(now) || removed < 64) {
                map.remove(key);
                removed++;
            }
            if (removed >= 128) break;
        }
    }

    private static boolean isCritical(GuildGolemRouteMode mode) {
        return mode == GuildGolemRouteMode.RESPONSE || mode == GuildGolemRouteMode.INTERCEPT_NEAREST_REACHABLE;
    }

    private static String terrainStatus(ServerLevel level, BlockPos pos, GuildGolemRouteMode mode) {
        GuildGolemTerrainRisk risk = GuildGolemSafePositionResolver.classifyNavigationPoint(level, pos, mode);
        return routeStatusFor(risk, mode, true);
    }

    private static String routeStatusFor(GuildGolemTerrainRisk risk, GuildGolemRouteMode mode, boolean moving) {
        if (mode == GuildGolemRouteMode.RECOVERY) return "Відновлюється";
        if (mode == GuildGolemRouteMode.RESPONSE || mode == GuildGolemRouteMode.INTERCEPT_NEAREST_REACHABLE) return "Йде на тривогу";
        if (mode == GuildGolemRouteMode.MEMBER_GUARD) return "Захищає учасника";
        if (mode == GuildGolemRouteMode.SOFT_DETOUR || mode == GuildGolemRouteMode.WIDE_DETOUR || mode == GuildGolemRouteMode.SAFE_ANCHOR_ROUTE) return "Шукає обхід";
        if (risk == GuildGolemTerrainRisk.WATER_SHALLOW || risk == GuildGolemTerrainRisk.WATER_DEEP) return "Уникає води";
        if (risk == GuildGolemTerrainRisk.DROP_RISK || risk == GuildGolemTerrainRisk.VOID_OR_FATAL_DROP) return "Уникає провалу";
        if (!moving) return "Очікує безпечний маршрут";
        return "Патрулює";
    }

    private static String routeKey(GuildStore.Golem record, ServerLevel level, GuildGolemSafePositionResolver.SpawnPoint point) {
        return (record.guildId == null ? "?" : record.guildId) + '|' + dimension(level) + '|' + cell(record.x) + ',' + cell(record.z) + "->" + cell((int)Math.floor(point.x())) + ',' + cell((int)Math.floor(point.z()));
    }

    private static String detourKey(GuildStore.Golem record, ServerLevel level, GuildGolemSafePositionResolver.SpawnPoint point, GuildGolemRouteMode mode) {
        return routeKey(record, level, point) + '|' + mode.name() + '|' + Math.max(0, record.pathFailureCount / 2);
    }

    private static String pointKey(ServerLevel level, BlockPos pos, GuildGolemRouteMode mode) {
        return dimension(level) + '|' + pos.getX() + ',' + pos.getY() + ',' + pos.getZ() + '|' + (mode == null ? "" : mode.name());
    }

    private static String dimension(ServerLevel level) {
        try { return GuildStore.dimensionId(level); } catch (Throwable ignored) { return "?"; }
    }

    private static int cell(int value) { return Math.floorDiv(value, 8); }

    private static BlockPos blockPos(double x, double y, double z) {
        return new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
    }

    private static double distanceSqr(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class CacheEntry {
        final int x, y, z;
        final long tick;
        final int score;
        final String reason;
        CacheEntry(int x, int y, int z, long tick, String reason) { this(x, y, z, tick, 0, reason); }
        CacheEntry(int x, int y, int z, long tick, int score, String reason) {
            this.x = x; this.y = y; this.z = z; this.tick = tick; this.score = score; this.reason = reason;
        }
        boolean valid(long now) { return now - tick <= Math.max(40, HomeCraftGuildConfig.guildGolemRouteCacheTtlTicks()); }
    }

    private static final class PendingRoute {
        final String uuid;
        final long revision;
        final long createdTick;
        final CompletableFuture<RouteCandidate> future;
        RouteCandidate result;
        PendingRoute(String uuid, long revision, long createdTick, CompletableFuture<RouteCandidate> future) {
            this.uuid = uuid;
            this.revision = revision;
            this.createdTick = createdTick;
            this.future = future;
        }
    }

    private static final class RouteCandidate {
        final int x, y, z, score;
        final GuildGolemRouteMode mode;
        final GuildGolemTerrainRisk risk;
        RouteCandidate(int x, int y, int z, GuildGolemRouteMode mode, GuildGolemTerrainRisk risk, int score) {
            this.x = x; this.y = y; this.z = z; this.mode = mode; this.risk = risk; this.score = score;
        }
    }
}
