package ua.homecraft.guild.server.golem;

import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

public final class GuildGolemDebugLogger {
    private GuildGolemDebugLogger() {}

    public static void state(String uuid, String from, String to, String reason) {
        if (!HomeCraftGuildConfig.guildGolemDebugStateChanges()) return;
        HomeCraftGuildMod.LOGGER.info("Home Craft Guilds golem AI: {} {} -> {} ({})", uuid, from, to, reason);
    }

    public static void path(String uuid, String reason) {
        if (!HomeCraftGuildConfig.guildGolemDebugPathFailures()) return;
        HomeCraftGuildMod.LOGGER.info("Home Craft Guilds golem path: {} {}", uuid, reason);
    }

    public static void info(String message, Object... args) {
        if (!HomeCraftGuildConfig.guildGolemDebug()) return;
        HomeCraftGuildMod.LOGGER.info(message, args);
    }
}
