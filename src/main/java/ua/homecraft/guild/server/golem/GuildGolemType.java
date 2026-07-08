package ua.homecraft.guild.server.golem;

import ua.homecraft.guild.server.GuildStore;

import java.util.Locale;

public enum GuildGolemType {
    NORMAL,
    ELITE;

    public static GuildGolemType from(GuildStore.Golem record) {
        if (record == null) return NORMAL;
        String raw = String.valueOf(record.golemType == null ? record.type : record.golemType).toLowerCase(Locale.ROOT);
        if (record.elite || raw.contains("elite")) return ELITE;
        return NORMAL;
    }

    public boolean elite() {
        return this == ELITE;
    }
}
