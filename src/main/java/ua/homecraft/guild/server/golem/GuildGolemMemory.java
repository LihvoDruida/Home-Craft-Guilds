package ua.homecraft.guild.server.golem;

import ua.homecraft.guild.server.GuildStore;

public final class GuildGolemMemory {
    public final String uuid;
    public final String guildId;
    public final GuildGolemType type;
    public final GuildGolemRole role;
    public final GuildGolemState state;
    public final GuildGolemStatus status;

    private GuildGolemMemory(String uuid, String guildId, GuildGolemType type, GuildGolemRole role, GuildGolemState state, GuildGolemStatus status) {
        this.uuid = uuid;
        this.guildId = guildId;
        this.type = type;
        this.role = role;
        this.state = state;
        this.status = status;
    }

    public static GuildGolemMemory from(GuildStore.Golem record) {
        GuildGolemType type = GuildGolemType.from(record);
        GuildGolemRole role = parseRole(record == null ? null : record.assignedRole, type.elite() ? GuildGolemRole.ELITE_COMMAND : GuildGolemRole.ROAMING);
        GuildGolemState state = parseState(record == null ? null : record.aiState, type.elite() ? GuildGolemState.ELITE_IDLE : GuildGolemState.NORMAL_IDLE);
        GuildGolemStatus status = parseStatus(record == null ? null : record.status, record != null && record.dead ? GuildGolemStatus.DEAD : GuildGolemStatus.ALIVE);
        return new GuildGolemMemory(record == null ? null : record.uuid, record == null ? null : record.guildId, type, role, state, status);
    }

    private static GuildGolemRole parseRole(String raw, GuildGolemRole fallback) {
        try { return raw == null || raw.isBlank() ? fallback : GuildGolemRole.valueOf(raw); }
        catch (Exception ignored) { return fallback; }
    }

    private static GuildGolemState parseState(String raw, GuildGolemState fallback) {
        try { return raw == null || raw.isBlank() ? fallback : GuildGolemState.valueOf(raw); }
        catch (Exception ignored) { return fallback; }
    }

    private static GuildGolemStatus parseStatus(String raw, GuildGolemStatus fallback) {
        try { return raw == null || raw.isBlank() ? fallback : GuildGolemStatus.valueOf(raw); }
        catch (Exception ignored) { return fallback; }
    }
}
