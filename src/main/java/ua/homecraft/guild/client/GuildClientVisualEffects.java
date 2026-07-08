package ua.homecraft.guild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.client.bed.GuildBedLabelRenderer;
import ua.homecraft.guild.client.npc.GuildVirtualRegistrarClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Client-side guild visuals. The dedicated server sends only raw guild territory data
 * (dimension, id, guild id, bounds, totem position, color, revision). Border masks,
 * internal hidden segments and mixed-guild colors are calculated and rendered here.
 */
public final class GuildClientVisualEffects {
    private static final List<VisualTerritory> TERRITORIES = new ArrayList<>();
    private static final List<VisualTerritory> PENDING_TERRITORIES = new ArrayList<>();
    private static final int SNAPSHOT_STABILIZE_TICKS = 12;
    private static final int SNAPSHOT_TOPOLOGY_CHANGE_TICKS = 24;
    private static final int SNAPSHOT_EMPTY_CLEAR_TICKS = 18;
    private static long lastSnapshotClientTick = 0L;
    private static long activeSnapshotRevision = -1L;
    private static long pendingSnapshotRevision = -1L;
    private static long pendingSnapshotReadyTick = -1L;
    private static String activeSnapshotKey = "";
    private static String pendingSnapshotKey = "";
    private static boolean hasPendingSnapshot = false;
    private static int pendingSnapshotSeenCount = 0;
    private static int wallCursor = 0;

    private GuildClientVisualEffects() {}

    public static void acceptSnapshot(String snapshot) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level == null ? 0L : mc.level.getGameTime();
        if (snapshot != null && !snapshot.startsWith("event|")) GuildVirtualRegistrarClient.acceptSnapshot(snapshot);
        if (snapshot != null && snapshot.startsWith("event|")) {
            spawnVisualEvent(snapshot);
            return;
        }
        if (isNpcOnlySnapshot(snapshot)) {
            lastSnapshotClientTick = now;
            return;
        }

        SnapshotBundle bundle = parseSnapshotBundle(snapshot);
        if (bundle == null || !bundle.complete) {
            // Never replace a valid visual state with a malformed/truncated packet.
            // This avoids fake borders while chunks/network/state are still catching up.
            lastSnapshotClientTick = now;
            return;
        }

        if (bundle.key.equals(activeSnapshotKey)) {
            hasPendingSnapshot = false;
            PENDING_TERRITORIES.clear();
            pendingSnapshotKey = "";
            pendingSnapshotSeenCount = 0;
            lastSnapshotClientTick = now;
            return;
        }

        if (hasPendingSnapshot && bundle.key.equals(pendingSnapshotKey)) {
            pendingSnapshotSeenCount++;
            // Two identical snapshots means the topology settled; promote almost immediately.
            pendingSnapshotReadyTick = Math.min(pendingSnapshotReadyTick, now + 2L);
            lastSnapshotClientTick = now;
            return;
        }

        PENDING_TERRITORIES.clear();
        PENDING_TERRITORIES.addAll(bundle.territories);
        pendingSnapshotKey = bundle.key;
        pendingSnapshotRevision = bundle.revision;
        hasPendingSnapshot = true;
        pendingSnapshotSeenCount = 1;
        pendingSnapshotReadyTick = now + snapshotGraceTicks(bundle);
        lastSnapshotClientTick = now;
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;
        ClientLevel level = mc.level;
        long tick = level.getGameTime();
        promotePendingSnapshotIfReady(tick);
        if (TERRITORIES.isEmpty()) return;

        String dim = dimensionId(level);
        drawGuildWalls(mc, level, dim, tick);
        drawGuildTotems(mc, level, dim, tick);
    }

    private static int snapshotGraceTicks(SnapshotBundle bundle) {
        if (bundle == null) return SNAPSHOT_TOPOLOGY_CHANGE_TICKS;
        if (bundle.territories.isEmpty()) return SNAPSHOT_EMPTY_CLEAR_TICKS;
        if (TERRITORIES.isEmpty()) return SNAPSHOT_STABILIZE_TICKS;
        if (bundle.revision != activeSnapshotRevision) return SNAPSHOT_TOPOLOGY_CHANGE_TICKS;
        if (bundle.territories.size() != TERRITORIES.size()) return SNAPSHOT_TOPOLOGY_CHANGE_TICKS;
        return SNAPSHOT_STABILIZE_TICKS;
    }

    private static void promotePendingSnapshotIfReady(long tick) {
        if (!hasPendingSnapshot) return;
        if (pendingSnapshotSeenCount < 2 && tick < pendingSnapshotReadyTick) return;
        TERRITORIES.clear();
        TERRITORIES.addAll(PENDING_TERRITORIES);
        activeSnapshotKey = pendingSnapshotKey;
        activeSnapshotRevision = pendingSnapshotRevision;
        PENDING_TERRITORIES.clear();
        pendingSnapshotKey = "";
        pendingSnapshotRevision = -1L;
        pendingSnapshotReadyTick = -1L;
        pendingSnapshotSeenCount = 0;
        hasPendingSnapshot = false;
        wallCursor = 0;
    }

    private static void drawGuildWalls(Minecraft mc, ClientLevel level, String dim, long tick) {
        if ((tick & 1L) != 0L) return;

        int visibleTerritories = 0;
        for (VisualTerritory t : TERRITORIES) {
            if (t.dimension.equals(dim) && isNearPlayer(mc, t.x + 0.5D, t.y + 0.5D, t.z + 0.5D, 160.0D)) {
                visibleTerritories++;
            }
        }
        if (visibleTerritories <= 0) return;

        int totalColumns = 0;
        int budget = Math.min(224, Math.max(96, visibleTerritories * 80));
        int perTerritoryBudget = Math.max(24, budget / visibleTerritories);
        for (VisualTerritory t : TERRITORIES) {
            if (!t.dimension.equals(dim) || !isNearPlayer(mc, t.x + 0.5D, t.y + 0.5D, t.z + 0.5D, 160.0D)) continue;
            int rgb = t.rgb <= 0 ? 0xB8ECFF : t.rgb;
            DustParticleOptions dust = new DustParticleOptions(rgb, 0.52F);
            // Exact per-block sampling. The client owns border visibility/mixed-color logic,
            // so partial overlaps never hide a whole side.
            int step = 1;
            int perimeter = edgePointCount(t.maxX - t.minX + 1, step)
                    + edgePointCount(t.maxX - t.minX + 1, step)
                    + edgePointCount(t.maxZ - t.minZ + 1, step)
                    + edgePointCount(t.maxZ - t.minZ + 1, step);
            if (perimeter <= 0) continue;
            int localStart = Math.floorMod(wallCursor + stableTerritoryOffset(t), perimeter);
            int localColumns = 0;
            for (int i = 0; i < perimeter && totalColumns < budget && localColumns < perTerritoryBudget; i++) {
                int idx = Math.floorMod(localStart + i, perimeter);
                EdgePoint point = perimeterPoint(t, idx, step);
                if (point == null || !isEdgePointVisible(t, point.side, point.offset)) continue;
                DustParticleOptions pointDust = dust;
                if (isEdgePointMixed(t, point.side, point.offset)) {
                    int sideMixedRgb = mixedRgbForPoint(t, point);
                    if (sideMixedRgb <= 0) sideMixedRgb = blendRgb(rgb, 0xFFFFFF);
                    pointDust = new DustParticleOptions(sideMixedRgb, 0.62F);
                }
                spawnWallColumn(level, pointDust, point.x, point.z, idx);
                totalColumns++;
                localColumns++;
            }
        }
        wallCursor += Math.max(1, perTerritoryBudget);
    }

    private static int edgePointCount(int length, int step) {
        int safeLength = Math.max(0, length);
        int safeStep = Math.max(1, step);
        return safeLength <= 0 ? 0 : (safeLength + safeStep - 1) / safeStep;
    }

    private static EdgePoint perimeterPoint(VisualTerritory t, int idx, int step) {
        int northCount = edgePointCount(t.maxX - t.minX + 1, step);
        if (idx < northCount) {
            int x = Math.min(t.maxX, t.minX + idx * step);
            return new EdgePoint(x, t.minZ, 0, x - t.minX);
        }
        idx -= northCount;

        int southCount = edgePointCount(t.maxX - t.minX + 1, step);
        if (idx < southCount) {
            int x = Math.min(t.maxX, t.minX + idx * step);
            return new EdgePoint(x, t.maxZ, 1, x - t.minX);
        }
        idx -= southCount;

        int westCount = edgePointCount(t.maxZ - t.minZ + 1, step);
        if (idx < westCount) {
            int z = Math.min(t.maxZ, t.minZ + idx * step);
            return new EdgePoint(t.minX, z, 2, z - t.minZ);
        }
        idx -= westCount;

        int eastCount = edgePointCount(t.maxZ - t.minZ + 1, step);
        if (idx < eastCount) {
            int z = Math.min(t.maxZ, t.minZ + idx * step);
            return new EdgePoint(t.maxX, z, 3, z - t.minZ);
        }
        return null;
    }

    private static boolean isEdgePointVisible(VisualTerritory t, int side, int offset) {
        if (t == null || offset < 0) return false;
        EdgePoint point = edgePointFor(t, side, offset);
        if (point == null) return false;
        for (VisualTerritory other : TERRITORIES) {
            if (other == null || other == t) continue;
            if (!sameDimensionAndGuild(t, other)) continue;
            if (sameTerritory(t, other)) continue;
            if (sameGuildHidesPoint(t, side, point.x, point.z, other)) return false;
        }
        return true;
    }

    private static boolean isEdgePointMixed(VisualTerritory t, int side, int offset) {
        if (t == null || offset < 0) return false;
        EdgePoint point = edgePointFor(t, side, offset);
        if (point == null) return false;
        for (VisualTerritory other : TERRITORIES) {
            if (other == null || other == t) continue;
            if (!sameDimension(t, other) || sameGuild(t, other)) continue;
            if (differentGuildConflictsPoint(t, side, point.x, point.z, other)) return true;
        }
        return false;
    }

    private static int mixedRgbForPoint(VisualTerritory t, EdgePoint point) {
        if (t == null || point == null) return 0;
        int r = (safeRgb(t) >> 16) & 255;
        int g = (safeRgb(t) >> 8) & 255;
        int b = safeRgb(t) & 255;
        int count = 1;
        for (VisualTerritory other : TERRITORIES) {
            if (other == null || other == t) continue;
            if (!sameDimension(t, other) || sameGuild(t, other)) continue;
            if (!differentGuildConflictsPoint(t, point.side, point.x, point.z, other)) continue;
            int rgb = safeRgb(other);
            r += (rgb >> 16) & 255;
            g += (rgb >> 8) & 255;
            b += rgb & 255;
            count++;
        }
        return ((r / count) << 16) | ((g / count) << 8) | (b / count);
    }

    private static EdgePoint edgePointFor(VisualTerritory t, int side, int offset) {
        if (t == null || offset < 0) return null;
        return switch (side) {
            case 0 -> offset <= t.maxX - t.minX ? new EdgePoint(t.minX + offset, t.minZ, side, offset) : null;
            case 1 -> offset <= t.maxX - t.minX ? new EdgePoint(t.minX + offset, t.maxZ, side, offset) : null;
            case 2 -> offset <= t.maxZ - t.minZ ? new EdgePoint(t.minX, t.minZ + offset, side, offset) : null;
            case 3 -> offset <= t.maxZ - t.minZ ? new EdgePoint(t.maxX, t.minZ + offset, side, offset) : null;
            default -> null;
        };
    }

    private static boolean sameDimension(VisualTerritory a, VisualTerritory b) {
        return a != null && b != null && a.dimension.equals(b.dimension);
    }

    private static boolean sameGuild(VisualTerritory a, VisualTerritory b) {
        return a != null && b != null && a.guildId.equals(b.guildId);
    }

    private static boolean sameDimensionAndGuild(VisualTerritory a, VisualTerritory b) {
        return sameDimension(a, b) && sameGuild(a, b);
    }

    private static boolean sameTerritory(VisualTerritory a, VisualTerritory b) {
        if (a == null || b == null) return false;
        if (!a.id.isBlank() && a.id.equals(b.id)) return true;
        return a.x == b.x && a.y == b.y && a.z == b.z && a.minX == b.minX && a.maxX == b.maxX && a.minZ == b.minZ && a.maxZ == b.maxZ;
    }

    private static int safeRgb(VisualTerritory t) {
        return t == null || t.rgb <= 0 ? 0xB8ECFF : t.rgb;
    }

    private static boolean sameGuildHidesPoint(VisualTerritory own, int side, int x, int z, VisualTerritory other) {
        if (own == null || other == null) return false;

        // A same-guild wall point is hidden only when the block outside this side is
        // already covered by another territory from the same guild. This renders the
        // outside border of the merged territory cluster instead of deleting a whole
        // totem perimeter when two rectangles touch or partially overlap.
        int outsideX = outsideXForSide(side, x);
        int outsideZ = outsideZForSide(side, z);
        if (containsBlock(other, outsideX, outsideZ)) return true;

        // If two same-guild territories have the exact same external wall sample, keep
        // one deterministic owner. Without this, duplicated external samples flicker;
        // with the old wall-wide check, both sides could hide each other.
        return pointOnTerritorySide(x, z, side, other) && territoryOrder(other, own) < 0;
    }

    private static int outsideXForSide(int side, int x) {
        return switch (side) {
            case 2 -> x - 1;
            case 3 -> x + 1;
            default -> x;
        };
    }

    private static int outsideZForSide(int side, int z) {
        return switch (side) {
            case 0 -> z - 1;
            case 1 -> z + 1;
            default -> z;
        };
    }

    private static boolean containsBlock(VisualTerritory t, int x, int z) {
        return t != null && x >= t.minX && x <= t.maxX && z >= t.minZ && z <= t.maxZ;
    }

    private static boolean pointOnTerritorySide(int x, int z, int side, VisualTerritory t) {
        if (t == null) return false;
        return switch (side) {
            case 0 -> z == t.minZ && x >= t.minX && x <= t.maxX;
            case 1 -> z == t.maxZ && x >= t.minX && x <= t.maxX;
            case 2 -> x == t.minX && z >= t.minZ && z <= t.maxZ;
            case 3 -> x == t.maxX && z >= t.minZ && z <= t.maxZ;
            default -> false;
        };
    }

    private static int territoryOrder(VisualTerritory a, VisualTerritory b) {
        if (a == b) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        int c = a.dimension.compareTo(b.dimension);
        if (c != 0) return c;
        c = a.guildId.compareTo(b.guildId);
        if (c != 0) return c;
        c = a.id.compareTo(b.id);
        if (c != 0) return c;
        c = Integer.compare(a.x, b.x);
        if (c != 0) return c;
        c = Integer.compare(a.y, b.y);
        if (c != 0) return c;
        c = Integer.compare(a.z, b.z);
        if (c != 0) return c;
        c = Integer.compare(a.minX, b.minX);
        if (c != 0) return c;
        c = Integer.compare(a.minZ, b.minZ);
        if (c != 0) return c;
        c = Integer.compare(a.maxX, b.maxX);
        if (c != 0) return c;
        return Integer.compare(a.maxZ, b.maxZ);
    }

    private static int stableTerritoryOffset(VisualTerritory t) {
        if (t == null) return 0;
        int h = 17;
        h = 31 * h + t.dimension.hashCode();
        h = 31 * h + t.guildId.hashCode();
        h = 31 * h + t.id.hashCode();
        h = 31 * h + t.x;
        h = 31 * h + t.y;
        h = 31 * h + t.z;
        h = 31 * h + t.minX;
        h = 31 * h + t.maxX;
        h = 31 * h + t.minZ;
        h = 31 * h + t.maxZ;
        return h & Integer.MAX_VALUE;
    }

    private static boolean differentGuildConflictsPoint(VisualTerritory own, int side, int x, int z, VisualTerritory other) {
        if (own == null || other == null) return false;
        int tolerance = 1;
        boolean nearWall = distanceToTerritoryWallChebyshev(x, z, other) <= tolerance;
        return switch (side) {
            case 0, 1 -> rangesOverlap(x, x, other.minX, other.maxX) && nearWall;
            case 2, 3 -> rangesOverlap(z, z, other.minZ, other.maxZ) && nearWall;
            default -> false;
        };
    }

    private static boolean pointOnTerritoryWall(int x, int z, VisualTerritory t) {
        if (t == null) return false;
        boolean onHorizontal = (z == t.minZ || z == t.maxZ) && x >= t.minX && x <= t.maxX;
        boolean onVertical = (x == t.minX || x == t.maxX) && z >= t.minZ && z <= t.maxZ;
        return onHorizontal || onVertical;
    }

    private static int distanceToTerritoryWallChebyshev(int x, int z, VisualTerritory t) {
        if (t == null) return Integer.MAX_VALUE;
        int clampedX = Math.max(t.minX, Math.min(t.maxX, x));
        int clampedZ = Math.max(t.minZ, Math.min(t.maxZ, z));
        int dx = Math.abs(x - clampedX);
        int dz = Math.abs(z - clampedZ);
        if (x >= t.minX && x <= t.maxX && z >= t.minZ && z <= t.maxZ) {
            return Math.min(Math.min(Math.abs(x - t.minX), Math.abs(x - t.maxX)), Math.min(Math.abs(z - t.minZ), Math.abs(z - t.maxZ)));
        }
        return Math.max(dx, dz);
    }

    private static boolean rangesOverlap(int a0, int a1, int b0, int b1) {
        return Math.max(a0, b0) <= Math.min(a1, b1);
    }

    private static int blendRgb(int a, int b) {
        int ar = (a >> 16) & 255;
        int ag = (a >> 8) & 255;
        int ab = a & 255;
        int br = (b >> 16) & 255;
        int bg = (b >> 8) & 255;
        int bb = b & 255;
        return (((ar + br) / 2) << 16) | (((ag + bg) / 2) << 8) | ((ab + bb) / 2);
    }

    private static void spawnWallColumn(ClientLevel level, DustParticleOptions dust, int blockX, int blockZ, int seed) {
        int surfaceY = localSurfaceY(level, blockX, blockZ);
        double x = blockX + 0.5D;
        double z = blockZ + 0.5D;
        for (int y = surfaceY; y <= surfaceY + 2; y++) {
            int above = y - surfaceY;
            if (above == 2 && (seed & 1) != 0) continue;
            double jitter = ((seed * 31 + y * 7) & 7) * 0.0015D;
            level.addParticle(dust, x + jitter, y + 0.10D, z - jitter, 0.0D, 0.0D, 0.0D);
            if (above == 0) level.addParticle(ParticleTypes.GLOW, x, y + 0.12D, z, 0.0D, 0.0D, 0.0D);
        }
    }

    private static void drawGuildTotems(Minecraft mc, ClientLevel level, String dim, long tick) {
        for (VisualTerritory t : TERRITORIES) {
            if (!t.dimension.equals(dim) || !isNearPlayer(mc, t.x + 0.5D, t.y + 0.5D, t.z + 0.5D, 96.0D)) continue;
            int rgb = t.rgb <= 0 ? 0xB8ECFF : t.rgb;
            DustParticleOptions main = new DustParticleOptions(rgb, 0.80F);
            DustParticleOptions glow = new DustParticleOptions(0xE8FBFF, 0.65F);
            double cx = t.x + 0.5D;
            double cz = t.z + 0.5D;
            double pulse = 0.05D * Math.sin(tick * 0.17D);
            level.addParticle(main, cx, t.y + 1.42D + pulse, cz, 0.0D, 0.012D, 0.0D);
            if ((tick & 1L) == 0L) level.addParticle(ParticleTypes.GLOW, cx, t.y + 1.55D, cz, 0.0D, 0.0D, 0.0D);
            for (int i = 0; i < 4; i++) {
                double angle = tick * 0.06D + (Math.PI / 2.0D) * i;
                double radius = 0.24D;
                double x = cx + Math.cos(angle) * radius;
                double z = cz + Math.sin(angle) * radius;
                double y = t.y + 1.25D + 0.16D * Math.sin(tick * 0.12D + i);
                level.addParticle(glow, x, y, z, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private static void spawnVisualEvent(String snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;
        try {
            String[] p = snapshot.split("\\|", -1);
            if (p.length < 7) return;
            String kind = p[1];
            String dim = p[2];
            if (!dimensionId(mc.level).equals(dim)) return;
            double x = Double.parseDouble(p[3]);
            double y = Double.parseDouble(p[4]);
            double z = Double.parseDouble(p[5]);
            boolean burst = Boolean.parseBoolean(p[6]);
            if ("creeper".equals(kind)) spawnClientCreeperConfetti(mc.level, x, y, z, burst);
            else if ("golem_revive".equals(kind)) spawnClientGolemRevive(mc.level, x, y, z, burst);
            else if ("elite_golem".equals(kind)) spawnClientEliteGolemAura(mc.level, x, y, z);
            else if ("golem_heal".equals(kind)) spawnClientGolemHealing(mc.level, x, y, z, burst);
            else if ("bed_label".equals(kind)) GuildBedLabelRenderer.acceptEvent(p);
            else if ("guild_achievement".equals(kind)) spawnClientGuildAchievement(mc.level, x, y, z);
            else if ("guild_levelup".equals(kind)) spawnClientGuildLevelUp(mc.level, x, y, z);
        } catch (Throwable ignored) { }
    }

    private static void spawnClientGolemRevive(ClientLevel level, double x, double y, double z, boolean finalBurst) {
        if (level == null) return;
        int count = finalBurst ? 34 : 12;
        DustParticleOptions dust = new DustParticleOptions(0xB8ECFF, finalBurst ? 1.15F : 0.75F);
        for (int i = 0; i < count; i++) {
            double ox = randomOffset(i + 11, finalBurst ? 0.95D : 0.55D);
            double oy = randomOffset(i + 23, finalBurst ? 0.90D : 0.45D);
            double oz = randomOffset(i + 37, finalBurst ? 0.95D : 0.55D);
            level.addParticle(i % 3 == 0 ? ParticleTypes.GLOW : dust, x + ox, y + 0.35D + oy, z + oz, 0.0D, finalBurst ? 0.025D : 0.01D, 0.0D);
        }
        if (finalBurst) {
            for (int i = 0; i < 16; i++) {
                level.addParticle(ParticleTypes.END_ROD, x + randomOffset(i + 101, 0.55D), y + 0.45D + randomOffset(i + 109, 0.55D), z + randomOffset(i + 127, 0.55D), 0.0D, 0.025D, 0.0D);
            }
        }
    }


    private static void spawnClientGolemHealing(ClientLevel level, double x, double y, double z, boolean finalPulse) {
        if (level == null) return;
        DustParticleOptions green = new DustParticleOptions(0x63FF8F, finalPulse ? 1.05F : 0.72F);
        DustParticleOptions soft = new DustParticleOptions(0xB7FFD0, finalPulse ? 0.88F : 0.58F);
        int count = finalPulse ? 22 : 9;
        long tick = level.getGameTime();
        for (int i = 0; i < count; i++) {
            double angle = tick * 0.055D + i * (Math.PI * 2.0D / Math.max(1, count));
            double radius = (finalPulse ? 0.28D : 0.16D) + (i % 5) * 0.045D;
            double px = x + Math.cos(angle) * radius + randomOffset(i + 701, 0.08D);
            double py = y + 0.08D + randomOffset(i + 719, finalPulse ? 0.32D : 0.18D);
            double pz = z + Math.sin(angle) * radius + randomOffset(i + 733, 0.08D);
            double vx = Math.cos(angle) * (finalPulse ? 0.018D : 0.008D);
            double vy = finalPulse ? 0.018D : 0.006D;
            double vz = Math.sin(angle) * (finalPulse ? 0.018D : 0.008D);
            level.addParticle(i % 4 == 0 ? soft : green, px, py, pz, vx, vy, vz);
        }
        if (finalPulse) level.addParticle(ParticleTypes.HAPPY_VILLAGER, x, y + 0.35D, z, 0.0D, 0.025D, 0.0D);
    }

    private static void spawnClientEliteGolemAura(ClientLevel level, double x, double y, double z) {
        if (level == null) return;
        DustParticleOptions gold = new DustParticleOptions(0xFFD36B, 0.75F);
        for (int i = 0; i < 5; i++) {
            level.addParticle(gold, x + randomOffset(i + 201, 0.70D), y + randomOffset(i + 211, 0.55D), z + randomOffset(i + 223, 0.70D), 0.0D, 0.005D, 0.0D);
        }
        level.addParticle(ParticleTypes.GLOW, x, y, z, 0.0D, 0.0D, 0.0D);
    }

    private static void spawnClientGuildAchievement(ClientLevel level, double x, double y, double z) {
        if (level == null) return;
        for (int i = 0; i < 14; i++) {
            level.addParticle(ParticleTypes.HAPPY_VILLAGER, x + randomOffset(i + 301, 0.65D), y + randomOffset(i + 317, 0.55D), z + randomOffset(i + 331, 0.65D), 0.0D, 0.01D, 0.0D);
        }
    }

    private static void spawnClientGuildLevelUp(ClientLevel level, double x, double y, double z) {
        if (level == null) return;
        DustParticleOptions gold = new DustParticleOptions(0xFFD66B, 1.0F);
        for (int i = 0; i < 22; i++) {
            level.addParticle(i % 3 == 0 ? ParticleTypes.GLOW : gold, x + randomOffset(i + 401, 0.85D), y + randomOffset(i + 419, 0.65D), z + randomOffset(i + 433, 0.85D), 0.0D, 0.02D, 0.0D);
        }
    }

    private static void spawnClientCreeperConfetti(ClientLevel level, double x, double y, double z, boolean finalBurst) {
        if (level == null) return;
        int fireworkCount = finalBurst ? 28 : 12;
        int sparkleCount = finalBurst ? 34 : 16;
        for (int i = 0; i < fireworkCount; i++) {
            double ox = randomOffset(i, 0.85D);
            double oy = 0.45D + randomOffset(i + 17, 0.75D);
            double oz = randomOffset(i + 31, 0.85D);
            level.addParticle(ParticleTypes.FIREWORK, x + ox, y + oy, z + oz, 0.0D, 0.02D, 0.0D);
        }
        for (int i = 0; i < sparkleCount; i++) {
            double ox = randomOffset(i + 91, 0.95D);
            double oy = 0.25D + randomOffset(i + 43, 0.65D);
            double oz = randomOffset(i + 61, 0.95D);
            level.addParticle(ParticleTypes.HAPPY_VILLAGER, x + ox, y + oy, z + oz, 0.0D, 0.0D, 0.0D);
        }
        int[] colors = {0xFF5BC0, 0x66E0FF, 0xFFE066, 0x78FF8A, 0xC878FF, 0xFF9A3D};
        for (int i = 0; i < colors.length; i++) {
            DustParticleOptions dust = new DustParticleOptions(colors[i], finalBurst ? 1.05F : 0.75F);
            level.addParticle(dust, x + ((i % 3) - 1) * 0.22D, y + 0.8D, z + ((i / 3) - 0.5D) * 0.24D, 0.0D, 0.0D, 0.0D);
        }
    }

    private static double randomOffset(int seed, double scale) {
        int v = (seed * 1103515245 + 12345) >>> 16;
        return (((v & 1023) / 1023.0D) - 0.5D) * scale;
    }


    private static String dimensionId(ClientLevel level) {
        if (level == null) return "minecraft:overworld";
        return dimensionIdFromKey(level.dimension());
    }

    private static String dimensionIdFromKey(Object key) {
        String raw = String.valueOf(key == null ? "" : key).trim();
        if (raw.isEmpty()) return "minecraft:overworld";
        String prefix = "ResourceKey[minecraft:dimension / ";
        if (raw.startsWith(prefix) && raw.endsWith("]")) {
            return raw.substring(prefix.length(), raw.length() - 1);
        }
        int sep = raw.lastIndexOf(" / ");
        if (sep >= 0 && raw.endsWith("]")) {
            return raw.substring(sep + 3, raw.length() - 1);
        }
        return raw;
    }

    private static int localSurfaceY(ClientLevel level, int x, int z) {
        try {
            return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        } catch (Throwable ignored) {
            return 64;
        }
    }

    private static boolean isNearPlayer(Minecraft mc, double x, double y, double z, double radius) {
        double dx = mc.player.getX() - x;
        double dy = mc.player.getY() - y;
        double dz = mc.player.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private static boolean isNpcOnlySnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return false;
        boolean sawNpcCacheRow = false;
        for (String row : snapshot.split(";")) {
            if (row == null || row.isBlank()) continue;
            if (row.startsWith("npc2|") || row.startsWith("skins2|")) {
                sawNpcCacheRow = true;
                continue;
            }
            return false;
        }
        return sawNpcCacheRow;
    }

    private static SnapshotBundle parseSnapshotBundle(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return new SnapshotBundle(Collections.emptyList(), "empty|0", 0L, 0, true);
        }
        List<VisualTerritory> parsed = new ArrayList<>();
        int expectedCount = -1;
        long declaredRevision = -1L;
        try {
            String[] rows = snapshot.split(";");
            for (String row : rows) {
                if (row == null || row.isBlank()) continue;
                if (row.startsWith("npc|")) {
                    GuildVirtualRegistrarClient.acceptNpcRow(row);
                    continue;
                }
                if (row.startsWith("npc2|") || row.startsWith("skins2|")) {
                    // npc2/skins2 rows are handled by GuildNpcClientCache in the payload handler.
                    // They are not territory rows; parsing them as borders makes the snapshot
                    // look incomplete and causes visible client-side visual flicker.
                    continue;
                }
                if (row.startsWith("meta|")) {
                    String[] p = row.split("\\|");
                    // meta|v2|revision|dimension|count|hash|cellX|cellZ
                    if (p.length >= 5) {
                        declaredRevision = parseLong(p[2], declaredRevision);
                        expectedCount = parseInt(p[4], expectedCount);
                    }
                    continue;
                }
                VisualTerritory territory = parse(row);
                if (territory != null) parsed.add(territory);
            }
        } catch (Throwable ignored) {
            return null;
        }
        if (expectedCount >= 0 && expectedCount != parsed.size()) {
            return new SnapshotBundle(parsed, "incomplete|" + expectedCount + "|" + parsed.size(), declaredRevision, expectedCount, false);
        }
        long revision = declaredRevision >= 0L ? declaredRevision : maxRevision(parsed);
        String key = canonicalSnapshotKey(parsed, revision);
        return new SnapshotBundle(parsed, key, revision, expectedCount, true);
    }

    private static long maxRevision(List<VisualTerritory> territories) {
        long revision = 0L;
        if (territories == null) return revision;
        for (VisualTerritory t : territories) if (t != null) revision = Math.max(revision, t.revision);
        return revision;
    }

    private static String canonicalSnapshotKey(List<VisualTerritory> territories, long revision) {
        if (territories == null || territories.isEmpty()) return "empty|" + revision;
        List<VisualTerritory> sorted = new ArrayList<>(territories);
        sorted.sort(GuildClientVisualEffects::territoryOrder);
        StringBuilder key = new StringBuilder(32 + sorted.size() * 64);
        key.append("rev=").append(revision).append('|').append("count=").append(sorted.size());
        for (VisualTerritory t : sorted) {
            if (t == null) continue;
            key.append(';')
               .append(t.dimension).append('|')
               .append(t.id).append('|')
               .append(t.guildId).append('|')
               .append(t.x).append('|').append(t.y).append('|').append(t.z).append('|')
               .append(t.minX).append('|').append(t.maxX).append('|').append(t.minZ).append('|').append(t.maxZ).append('|')
               .append(t.rgb).append('|').append(t.revision);
        }
        return key.toString();
    }

    private static VisualTerritory parse(String row) {
        try {
            String[] p = row.split("\\|");
            if (p.length < 12) return null;
            return new VisualTerritory(
                    p[0], p[1], p[2],
                    parseInt(p[3], 0), parseInt(p[4], 64), parseInt(p[5], 0),
                    parseInt(p[6], 0), parseInt(p[7], 0), parseInt(p[8], 0), parseInt(p[9], 0),
                    parseInt(p[10], 0), parseInt(p[11], 0)
            );
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.debug("Ignored bad guild visual row: {}", row);
            return null;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            if (value == null) return fallback;
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.startsWith("#")) return Integer.parseInt(v.substring(1), 16);
            if (v.startsWith("0x")) return Integer.parseInt(v.substring(2), 16);
            return Integer.parseInt(v);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            if (value == null) return fallback;
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record EdgePoint(int x, int z, int side, int offset) {}

    private record SnapshotBundle(List<VisualTerritory> territories, String key, long revision, int expectedCount, boolean complete) {}

    private record VisualTerritory(String dimension, String id, String guildId, int x, int y, int z,
                                   int minX, int maxX, int minZ, int maxZ, int rgb, int revision) {}
}
