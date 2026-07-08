package ua.homecraft.guild.server;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import ua.homecraft.guild.HomeCraftGuildMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Central optional-integration gate for the Home Craft ecosystem.
 *
 * HomeCraft Guilds must be able to run as a standalone guild addon. Anything that belongs to
 * another HomeCraft addon or to the external site/map pipeline is routed through this class first,
 * so a missing addon never starts reflection, file reads or background sync work by accident.
 */
public final class HomeCraftAddonIntegrations {
    private static final String[] AUTH_MOD_IDS = {
            "homecraftauth", "homecraft_auth", "homecraft_auth_neoforge"
    };
    private static final String[] SPAWN_PROTECTION_MOD_IDS = {
            "homecraftspawnprotection", "homecraft_spawn_protection", "homecraft_spawn_protection_neoforge"
    };
    private static final String[] MAP_OR_SITE_MOD_IDS = {
            "homecraftmap", "homecraft_map", "homecraft_map_neoforge",
            "homecraftsite", "homecraft_site", "homecraft_site_neoforge"
    };

    private static volatile boolean loggedSummary = false;

    private HomeCraftAddonIntegrations() {}

    public static boolean optionalIntegrationsAllowed() {
        return !"standalone".equals(HomeCraftGuildConfig.homecraftIntegrationMode());
    }

    public static boolean optionalIntegrationsForced() {
        return "force".equals(HomeCraftGuildConfig.homecraftIntegrationMode());
    }

    public static boolean authAvailable() {
        return isAnyModLoaded(AUTH_MOD_IDS)
                || isClassPresent("ua.homecraft.auth.server.SpawnResolver")
                || isClassPresent("ua.homecraft.auth.server.HomeCraftServerConfig");
    }

    public static boolean spawnProtectionAvailable() {
        return isAnyModLoaded(SPAWN_PROTECTION_MOD_IDS)
                || isClassPresent("ua.homecraft.spawnprotection.HomeCraftSpawnProtectionMod")
                || isClassPresent("ua.homecraft.spawn_protection.HomeCraftSpawnProtectionMod");
    }

    public static boolean mapOrSiteAvailable() {
        return isAnyModLoaded(MAP_OR_SITE_MOD_IDS);
    }

    public static boolean shouldUseSpawnBoundaryIntegration() {
        if (!HomeCraftGuildConfig.spawnBoundaryIntegrationEnabled()) return false;
        if (!optionalIntegrationsAllowed()) return false;
        return optionalIntegrationsForced() || authAvailable() || spawnProtectionAvailable();
    }

    public static boolean shouldRunTerritorySiteSync(MinecraftServer server) {
        if (!HomeCraftGuildConfig.territorySyncEnabled()) return false;
        if (!optionalIntegrationsAllowed()) return false;
        if (!HomeCraftGuildConfig.territorySyncRequiresHomeCraftMap()) return true;
        return optionalIntegrationsForced() || mapOrSiteAvailable() || siteRuntimeFilesPresent(server);
    }

    public static void logSummaryOnce(MinecraftServer server) {
        if (loggedSummary) return;
        loggedSummary = true;
        HomeCraftGuildMod.LOGGER.info(
                "Home Craft Guild integrations: mode={} auth={} spawnProtection={} mapOrSite={} spawnBoundary={} territorySync={}",
                HomeCraftGuildConfig.homecraftIntegrationMode(),
                authAvailable(),
                spawnProtectionAvailable(),
                mapOrSiteAvailable() || siteRuntimeFilesPresent(server),
                shouldUseSpawnBoundaryIntegration(),
                shouldRunTerritorySiteSync(server)
        );
    }

    private static boolean siteRuntimeFilesPresent(MinecraftServer server) {
        if (server == null) return false;
        try {
            Path root = server.getServerDirectory();
            if (root == null) return false;
            return Files.exists(root.resolve("start-homecraft-site.bat"))
                    || Files.exists(root.resolve("site"))
                    || Files.exists(root.resolve("SITE"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isAnyModLoaded(String... ids) {
        if (ids == null || ids.length == 0) return false;
        try {
            ModList list = ModList.get();
            if (list == null) return false;
            for (String id : ids) {
                if (id == null || id.isBlank()) continue;
                String safe = id.trim().toLowerCase(Locale.ROOT);
                try {
                    if (list.isLoaded(safe)) return true;
                } catch (RuntimeException ignored) {
                    // Invalid/unknown ids must never break standalone startup.
                }
            }
        } catch (Throwable ignored) {
            // ModList is unavailable in a few test/bootstrap contexts. Reflection checks below still work.
        }
        return false;
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, HomeCraftAddonIntegrations.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
