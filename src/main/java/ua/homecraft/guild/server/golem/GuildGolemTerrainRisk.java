package ua.homecraft.guild.server.golem;

public enum GuildGolemTerrainRisk {
    SAFE_GROUND(0),
    SOFT_RISK(12),
    WATER_SHALLOW(45),
    WATER_DEEP(90),
    DROP_RISK(120),
    BLOCKED(1000),
    VOID_OR_FATAL_DROP(2000);

    public final int penalty;

    GuildGolemTerrainRisk(int penalty) {
        this.penalty = penalty;
    }

    public boolean blocked() {
        return this == BLOCKED || this == VOID_OR_FATAL_DROP;
    }
}
