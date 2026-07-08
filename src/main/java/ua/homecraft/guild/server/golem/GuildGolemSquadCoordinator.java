package ua.homecraft.guild.server.golem;

import ua.homecraft.guild.server.GuildStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class GuildGolemSquadCoordinator {
    private GuildGolemSquadCoordinator() {}

    /**
     * Assigns a stable non-combat task. This is intentionally deterministic:
     * golems spread across the guild area and do not all pick the same patrol target
     * until a real danger trigger makes the brain switch to combat/support states.
     */
    public static GuildGolemRole assignRole(GuildStore.Golem record) {
        if (record == null || record.guildId == null) return GuildGolemRole.RECOVERY;
        GuildGolemType type = GuildGolemType.from(record);
        if (type.elite()) return GuildGolemRole.ELITE_COMMAND;

        int index = normalIndex(record);
        return switch (Math.floorMod(index, 6)) {
            case 0 -> GuildGolemRole.BORDER;
            case 1 -> GuildGolemRole.ROAMING;
            case 2 -> GuildGolemRole.TOTEM;
            case 3 -> GuildGolemRole.PLAYER_SUPPORT;
            case 4 -> GuildGolemRole.CENTER;
            default -> GuildGolemRole.BORDER;
        };
    }

    public static int normalIndex(GuildStore.Golem record) {
        if (record == null || record.guildId == null) return 0;
        List<GuildStore.Golem> live = activeNormalGolems(record.guildId);
        for (int i = 0; i < live.size(); i++) {
            GuildStore.Golem other = live.get(i);
            if (sameGolem(record, other)) return i;
        }
        return Math.floorMod(stableHash(record), Math.max(1, live.size() + 1));
    }

    public static int activeNormalCount(String guildId) {
        return activeNormalGolems(guildId).size();
    }

    public static int stableHash(GuildStore.Golem record) {
        if (record == null) return 0;
        return Math.floorMod(String.valueOf(record.guildId).concat(":").concat(String.valueOf(record.uuid)).hashCode(), Integer.MAX_VALUE);
    }

    private static boolean sameGolem(GuildStore.Golem a, GuildStore.Golem b) {
        if (a == null || b == null) return false;
        if (a.uuid != null && b.uuid != null) return Objects.equals(a.uuid, b.uuid);
        return a == b;
    }

    private static List<GuildStore.Golem> activeNormalGolems(String guildId) {
        List<GuildStore.Golem> live = new ArrayList<>(GuildStore.golemsByGuildSnapshot(guildId));
        live.removeIf(g -> g == null || g.dead || g.removed || g.uuid == null || GuildGolemType.from(g).elite());
        live.sort(Comparator.comparing(g -> String.valueOf(g.uuid)));
        return live;
    }
}
