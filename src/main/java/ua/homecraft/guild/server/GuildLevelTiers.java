package ua.homecraft.guild.server;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;

public final class GuildLevelTiers {
    public static final int MAX_LEVEL = 7;

    // Total guild XP required to reach the level with the same index.
    // The guild system has 7 levels. Old 10-level rewards are compressed into these tiers.
    private static final int[] LEVEL_TOTAL_XP = {
            0,      // unused
            0,      // level 1
            500,    // level 2
            2_000,  // level 3
            5_000,  // level 4
            11_000, // level 5
            21_000, // level 6
            37_000  // level 7, maximum
    };

    private GuildLevelTiers() {}

    public static int clampLevel(int level) {
        return Math.max(1, Math.min(MAX_LEVEL, level));
    }

    public static int levelForXp(int totalXp) {
        int xp = Math.max(0, totalXp);
        int level = 1;
        for (int i = 2; i <= MAX_LEVEL; i++) {
            if (xp >= LEVEL_TOTAL_XP[i]) level = i;
            else break;
        }
        return level;
    }

    public static int totalXpForLevel(int level) {
        return LEVEL_TOTAL_XP[clampLevel(level)];
    }

    public static int nextLevelTotalXp(int level) {
        int clamped = clampLevel(level);
        if (clamped >= MAX_LEVEL) return LEVEL_TOTAL_XP[MAX_LEVEL];
        return LEVEL_TOTAL_XP[clamped + 1];
    }

    public static int xpIntoCurrentLevel(int totalXp, int level) {
        return Math.max(0, Math.max(0, totalXp) - totalXpForLevel(level));
    }

    public static int xpNeededForCurrentLevel(int level) {
        int clamped = clampLevel(level);
        if (clamped >= MAX_LEVEL) return 0;
        return Math.max(1, LEVEL_TOTAL_XP[clamped + 1] - LEVEL_TOTAL_XP[clamped]);
    }

    public static int healthBonusPercent(int level) {
        return switch (clampLevel(level)) {
            case 1 -> 10;
            case 2 -> 15;
            case 3 -> 20;
            case 4 -> 25;
            case 5 -> 30;
            case 6 -> 35;
            default -> 40;
        };
    }

    public static int armorBonusPercent(int level) {
        return switch (clampLevel(level)) {
            case 1 -> 0;
            case 2 -> 5;
            case 3 -> 10;
            case 4 -> 15;
            case 5 -> 20;
            case 6 -> 25;
            default -> 35;
        };
    }

    public static int weaponDamageBonusPercent(int level) {
        return switch (clampLevel(level)) {
            case 1, 2 -> 0;
            case 3 -> 10;
            case 4 -> 15;
            case 5 -> 20;
            case 6 -> 25;
            default -> 30;
        };
    }

    public static boolean hasNightVision(int level) {
        return clampLevel(level) >= 5;
    }

    /** Invisible guild XP buff: +5% guild XP from monster kills per current guild level. */
    public static int mobKillGuildXpBonusPercent(int level) {
        return clampLevel(level) * 5;
    }

    /**
     * Ordinary golems are a fixed guild-level progression, not a territory multiplier.
     * Final cap: 4 ordinary golems per guild across 7 guild levels.
     *
     * Level 1-2: 1 ordinary golem
     * Level 3-4: 2 ordinary golems
     * Level 5-6: 3 ordinary golems
     * Level 7:   4 ordinary golems
     */
    public static int maxOrdinaryGolems(int level, int territoryCount) {
        int l = clampLevel(level);
        if (l >= 7) return 4;
        if (l >= 5) return 3;
        if (l >= 3) return 2;
        return 1;
    }

    /**
     * Elite golems are a strategic guild guardian, not a stackable army.
     * Final cap: 1 elite golem for the whole guild.
     */
    public static int maxEliteGolems(int level) {
        return clampLevel(level) >= 5 ? 1 : 0;
    }

    public static int nextEliteGolemUnlockLevel(int level) {
        return clampLevel(level) < 5 ? 5 : MAX_LEVEL;
    }

    public static int ordinaryGolemCost(int level, int currentOrdinaryGolems) {
        int current = Math.max(0, currentOrdinaryGolems);
        return switch (Math.min(current, 3)) {
            case 0 -> 1;
            case 1 -> 3;
            case 2 -> 10;
            default -> 20;
        };
    }

    public static int eliteGolemCost(int level) {
        return eliteGolemCost(level, 0);
    }

    public static int eliteGolemCost(int level, int currentEliteGolems) {
        int l = clampLevel(level);
        if (l < 5) return 0;
        return l >= 7 ? 70 : (l >= 6 ? 60 : 50);
    }

    public static int playerKillExperience() {
        return 18;
    }

    public static int monsterExperience(LivingEntity entity) {
        if (entity == null || entity.getType().getCategory() != MobCategory.MONSTER) return 0;
        float maxHealth = Math.max(1.0F, entity.getMaxHealth());
        int xp = 4 + (int)Math.ceil(maxHealth / 5.0F);
        String type = String.valueOf(entity.getType()).toLowerCase(java.util.Locale.ROOT);
        if (type.contains("wither") || type.contains("ender_dragon")) return 300;
        if (maxHealth >= 150.0F) xp += 90;
        else if (maxHealth >= 80.0F) xp += 35;
        else if (maxHealth >= 40.0F) xp += 15;
        return Math.max(5, Math.min(250, xp));
    }
}
