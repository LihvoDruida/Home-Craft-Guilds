package ua.homecraft.guild.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Small, dependency-free bridge to the Home Craft spawn area.
 * The guild mod must not depend on the spawn-protection mod at compile time, so this reads the same
 * auth/protection config and also tries Home Craft Auth reflection when that mod is present.
 */
public final class GuildSpawnBoundary {
    private GuildSpawnBoundary() {}

    public record SpawnArea(ServerLevel level, BlockPos center, int radius, String dimensionId) {
        public boolean contains(LevelAccessor checkLevel, BlockPos pos) {
            if (checkLevel == null || pos == null || level == null || radius <= 0) return false;
            if (!dimensionMatches(checkLevel, dimensionId)) return false;
            double dx = pos.getX() + 0.5D - (center.getX() + 0.5D);
            double dz = pos.getZ() + 0.5D - (center.getZ() + 0.5D);
            double r = Math.max(0, radius);
            return dx * dx + dz * dz <= r * r;
        }

        public boolean contains(String dimension, int x, int z) {
            if (dimension == null || !dimensionMatches(dimension, dimensionId) || radius <= 0) return false;
            double dx = x + 0.5D - (center.getX() + 0.5D);
            double dz = z + 0.5D - (center.getZ() + 0.5D);
            double r = Math.max(0, radius);
            return dx * dx + dz * dz <= r * r;
        }

        public boolean intersectsSquare(String dimension, int minX, int maxX, int minZ, int maxZ) {
            if (!dimensionMatches(dimension, dimensionId) || radius <= 0) return false;
            double cx = center.getX() + 0.5D;
            double cz = center.getZ() + 0.5D;
            double closestX = clamp(cx, minX, maxX + 1.0D);
            double closestZ = clamp(cz, minZ, maxZ + 1.0D);
            double dx = closestX - cx;
            double dz = closestZ - cz;
            return dx * dx + dz * dz <= (double) radius * radius;
        }

        public boolean fullyContainsSquare(String dimension, int minX, int maxX, int minZ, int maxZ) {
            if (!dimensionMatches(dimension, dimensionId) || radius <= 0) return false;
            return contains(dimension, minX, minZ)
                    && contains(dimension, minX, maxZ)
                    && contains(dimension, maxX, minZ)
                    && contains(dimension, maxX, maxZ);
        }
    }

    public static SpawnArea resolve(MinecraftServer server) {
        if (server == null) return null;
        if (!HomeCraftAddonIntegrations.shouldUseSpawnBoundaryIntegration()) return null;
        SpawnArea reflected = resolveFromHomeCraftAuth(server);
        if (reflected != null) return reflected;

        Properties auth = load(server.getServerDirectory().resolve("config").resolve("homecraft-auth.properties"));
        String source = prop(auth, "spawnSource", "config").toLowerCase(Locale.ROOT);
        String wantedDimension = prop(auth, "spawnDimension", prop(auth, "spawnOverrideDimension", "minecraft:overworld"));
        ServerLevel level = resolveLevel(server, wantedDimension);
        if (level == null) return null;

        BlockPos center;
        if (source.equals("config") || source.equals("manual") || source.equals("fixed") || source.equals("override")) {
            center = new BlockPos(
                    intProp(auth, "spawnX", intProp(auth, "spawnOverrideX", 78)),
                    intProp(auth, "spawnY", intProp(auth, "spawnOverrideY", 64)),
                    intProp(auth, "spawnZ", intProp(auth, "spawnOverrideZ", 4))
            );
        } else {
            center = resolveLevelSpawn(level);
        }
        int radius = Math.max(0, Math.min(5000, intProp(auth, "spawnSafeRadius", 64)));
        return new SpawnArea(level, center, radius, dimensionId(level));
    }

    private static SpawnArea resolveFromHomeCraftAuth(MinecraftServer server) {
        try {
            Class<?> resolver = Class.forName("ua.homecraft.auth.server.SpawnResolver");
            Object rawLevel = resolver.getMethod("resolveSpawnLevel", MinecraftServer.class).invoke(null, server);
            if (!(rawLevel instanceof ServerLevel level)) return null;
            Object rawPos = resolver.getMethod("resolveSpawnPos", ServerLevel.class).invoke(null, level);
            if (!(rawPos instanceof BlockPos center)) return null;

            int radius = 64;
            try {
                Class<?> config = Class.forName("ua.homecraft.auth.server.HomeCraftServerConfig");
                Object rawRadius = config.getMethod("spawnSafeRadius").invoke(null);
                if (rawRadius instanceof Number number) radius = Math.max(0, Math.min(5000, number.intValue()));
            } catch (ReflectiveOperationException ignored) {
            }

            String dimension = dimensionId(level);
            try {
                Object rawDimension = resolver.getMethod("dimensionId", ServerLevel.class).invoke(null, level);
                if (rawDimension != null && !String.valueOf(rawDimension).isBlank()) dimension = String.valueOf(rawDimension);
            } catch (ReflectiveOperationException ignored) {
            }
            return new SpawnArea(level, center, radius, dimension);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static boolean isInsideSpawn(MinecraftServer server, LevelAccessor level, BlockPos pos) {
        SpawnArea area = resolve(server);
        return area != null && area.contains(level, pos);
    }

    public static boolean isInsideSpawn(SpawnArea area, LevelAccessor level, BlockPos pos) {
        return area != null && area.contains(level, pos);
    }

    public static boolean isInsideSpawn(SpawnArea area, String dimension, int x, int z) {
        return area != null && area.contains(dimension, x, z);
    }

    public static boolean dimensionMatches(LevelAccessor level, String expected) {
        return dimensionMatches(GuildStore.dimensionId(level), expected);
    }

    public static boolean dimensionMatches(String actualRaw, String expectedRaw) {
        String actual = String.valueOf(actualRaw == null ? "" : actualRaw).trim().toLowerCase(Locale.ROOT);
        String expected = String.valueOf(expectedRaw == null ? "" : expectedRaw).trim().toLowerCase(Locale.ROOT);
        if (actual.isBlank() || expected.isBlank() || expected.equals("unknown")) return false;
        if (actual.equals(expected) || actual.endsWith("/" + expected) || actual.contains(expected)) return true;
        return expected.equals("overworld") && (actual.contains("minecraft:overworld") || actual.contains("overworld"));
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String wantedDimension) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                if (dimensionMatches(dimensionId(level), wantedDimension)) return level;
            }
        } catch (Exception ignored) {
        }
        try {
            Object value = server.getClass().getMethod("overworld").invoke(server);
            if (value instanceof ServerLevel level) return level;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Object value = server.getClass().getMethod("getOverworld").invoke(server);
            if (value instanceof ServerLevel level) return level;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            for (ServerLevel level : server.getAllLevels()) return level;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static BlockPos resolveLevelSpawn(ServerLevel level) {
        if (level == null) return BlockPos.ZERO;
        for (String method : new String[] {"getSharedSpawnPos", "getSharedSpawnPosition", "getSpawnPos", "getSpawnPosition"}) {
            try {
                Object value = level.getClass().getMethod(method).invoke(level);
                if (value instanceof BlockPos pos) return pos;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return BlockPos.ZERO;
    }

    private static String dimensionId(ServerLevel level) {
        try {
            Object key = level.dimension();
            if (key != null) {
                try {
                    Object location = key.getClass().getMethod("location").invoke(key);
                    if (location != null) return String.valueOf(location);
                } catch (ReflectiveOperationException ignored) {
                }
                return String.valueOf(key).replace("ResourceKey[minecraft:dimension / ", "").replace("]", "");
            }
        } catch (Throwable ignored) {
        }
        return "minecraft:overworld";
    }

    private static Properties load(Path path) {
        Properties props = new Properties();
        if (path == null || !Files.exists(path)) return props;
        try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException ignored) {
        }
        return props;
    }

    private static String prop(Properties props, String key, String fallback) {
        String value = props == null ? null : props.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intProp(Properties props, String key, int fallback) {
        try {
            return Integer.parseInt(prop(props, key, String.valueOf(fallback)).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
