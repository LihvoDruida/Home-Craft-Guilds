package ua.homecraft.guild.server.golem;

/**
 * High-level intent for guild golem path correction. The mode intentionally
 * stores only gameplay intent, not Minecraft objects, so it can be used by
 * lightweight route snapshots and caches safely.
 */
public enum GuildGolemRouteMode {
    DIRECT_PATH,
    SOFT_DETOUR,
    WIDE_DETOUR,
    SAFE_ANCHOR_ROUTE,
    INTERCEPT_NEAREST_REACHABLE,
    FAIL_IDLE_GUARD,
    PATROL,
    MEMBER_GUARD,
    RESPONSE,
    RECOVERY,
    IDLE
}
