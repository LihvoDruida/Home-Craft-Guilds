package ua.homecraft.guild.server.golem;

import ua.homecraft.guild.server.GuildStore;

public final class GuildGolemMigration {
    private GuildGolemMigration() {}

    public static void normalize(GuildStore.Golem record) {
        if (record == null) return;
        GuildGolemType type = GuildGolemType.from(record);
        record.golemType = type.name();
        record.behaviorProfile = type.elite() ? GuildGolemBehaviorProfile.ELITE_DEFENDER.name() : GuildGolemBehaviorProfile.NORMAL_GUARD.name();
        record.elite = type.elite();
        GuildGolemStats.ensureRecordMultipliers(record);
        if (record.status == null || record.status.isBlank()) record.status = record.dead ? GuildGolemStatus.DEAD.name() : GuildGolemStatus.ALIVE.name();
        if (record.aiState == null || record.aiState.isBlank()) record.aiState = type.elite() ? GuildGolemState.ELITE_IDLE.name() : GuildGolemState.NORMAL_IDLE.name();
    }
}
