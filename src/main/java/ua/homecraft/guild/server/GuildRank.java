package ua.homecraft.guild.server;

import java.util.Locale;

public enum GuildRank {
    GUILDMASTER("Гілдмайстер", false),
    BUILDER("Будівельник", true),
    QUARTERMASTER("Завгосп", true),
    WARRIOR("Воїн", true),
    FARMER("Фермер", true);

    public final String label;
    public final boolean assignableByGuildmaster;

    GuildRank(String label, boolean assignableByGuildmaster) {
        this.label = label;
        this.assignableByGuildmaster = assignableByGuildmaster;
    }

    public boolean isAssignableByGuildmaster() {
        return assignableByGuildmaster;
    }

    public static GuildRank from(String raw) {
        if (raw == null || raw.isBlank()) return WARRIOR;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try { return GuildRank.valueOf(normalized); }
        catch (Exception ignored) {
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "будівельник", "будивельник", "builder" -> BUILDER;
                case "завгосп", "quartermaster" -> QUARTERMASTER;
                case "фермер", "farmer" -> FARMER;
                case "воїн", "воин", "warrior" -> WARRIOR;
                case "гілдмайстер", "гильдмайстер", "guildmaster", "guild_master", "gm" -> GUILDMASTER;
                default -> WARRIOR;
            };
        }
    }

    public static GuildRank assignableFrom(String raw) {
        GuildRank rank = from(raw);
        return rank.isAssignableByGuildmaster() ? rank : WARRIOR;
    }

    public static String assignableLabels() {
        return "Будівельник, Завгосп, Воїн, Фермер";
    }
}
