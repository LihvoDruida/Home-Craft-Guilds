package ua.homecraft.guild.server.golem;

import ua.homecraft.guild.server.GuildStore;

/**
 * Immutable combat contract for Home Craft guild golems.
 * These values are gameplay constants, not config knobs: all AI, healing and UI
 * calculations must use the same static numbers so old records cannot fall back
 * to vanilla 100 HP / vanilla damage after restart or migration.
 */
public final class GuildGolemStats {
    public static final int STATS_VERSION = 4;

    /** Normal golem: base +300% max HP = 4x vanilla max HP. */
    public static final double NORMAL_HEALTH_MULTIPLIER = 4.0D;
    /** Normal golem: base +50% damage = 1.5x vanilla damage. */
    public static final double NORMAL_DAMAGE_MULTIPLIER = 1.5D;

    /** Elite golem: base +500% max HP = 6x vanilla max HP. */
    public static final double ELITE_HEALTH_MULTIPLIER = 6.0D;
    /** Elite golem: base +75% damage = 1.75x vanilla damage. */
    public static final double ELITE_DAMAGE_MULTIPLIER = 1.75D;

    private GuildGolemStats() {}

    public static double healthMultiplier(boolean elite) {
        return elite ? ELITE_HEALTH_MULTIPLIER : NORMAL_HEALTH_MULTIPLIER;
    }

    public static double damageMultiplier(boolean elite) {
        return elite ? ELITE_DAMAGE_MULTIPLIER : NORMAL_DAMAGE_MULTIPLIER;
    }

    public static int healthBonusPercent(boolean elite) {
        return elite ? 500 : 300;
    }

    public static int damageBonusPercent(boolean elite) {
        return elite ? 75 : 50;
    }

    public static int effectiveMaxHealth(double baseMaxHealth, boolean elite) {
        return Math.max(1, (int)Math.round(Math.max(1.0D, baseMaxHealth) * healthMultiplier(elite)));
    }

    public static double effectiveDamage(double baseDamage, boolean elite) {
        return Math.max(0.0D, Math.max(0.0D, baseDamage) * damageMultiplier(elite));
    }

    public static void writeStaticRecordStats(GuildStore.Golem golem, double baseMaxHealth, double baseDamage, boolean elite) {
        if (golem == null) return;
        golem.golemStatsVersion = STATS_VERSION;
        golem.elite = elite;
        golem.golemType = elite ? GuildGolemType.ELITE.name() : GuildGolemType.NORMAL.name();
        golem.healthMultiplier = healthMultiplier(elite);
        golem.damageMultiplier = damageMultiplier(elite);
        golem.healthBonusPercent = healthBonusPercent(elite);
        golem.damageBonusPercent = damageBonusPercent(elite);

        double cleanBaseHealth = cleanBaseHealth(golem, baseMaxHealth);
        if (cleanBaseHealth > 0.0D) {
            golem.baseMaxHealth = roundOne(cleanBaseHealth);
            golem.guildMaxHealth = effectiveMaxHealth(cleanBaseHealth, elite);
            golem.talentMaxHealthBonusPercent = GuildStore.guildGolemTalentHealthBonusPercent(golem.guildId);
            golem.finalMaxHealth = Math.max(1, (int)Math.round(golem.guildMaxHealth * (1.0D + golem.talentMaxHealthBonusPercent / 100.0D)));
            golem.maxHealth = golem.finalMaxHealth;
        }

        double cleanBaseDamage = cleanBaseDamage(golem, baseDamage);
        if (cleanBaseDamage >= 0.0D) {
            golem.baseDamage = roundTwo(cleanBaseDamage);
            golem.guildDamage = roundTwo(effectiveDamage(cleanBaseDamage, elite));
            golem.talentDamageBonusPercent = GuildStore.guildGolemTalentDamageBonusPercent(golem.guildId);
            golem.finalDamage = roundTwo(golem.guildDamage * (1.0D + golem.talentDamageBonusPercent / 100.0D));
            golem.talentSpeedBonusPercent = GuildStore.guildGolemTalentSpeedBonusPercent(golem.guildId);
            golem.talentHealingSpeedBonusPercent = GuildStore.guildGolemTalentHealingSpeedBonusPercent(golem.guildId);
        }
    }

    public static void ensureRecordMultipliers(GuildStore.Golem golem) {
        if (golem == null) return;
        boolean elite = GuildGolemType.from(golem).elite();
        writeStaticRecordStats(golem, 0.0D, -1.0D, elite);
    }

    private static double cleanBaseHealth(GuildStore.Golem golem, double proposedBaseHealth) {
        double fallback = fallbackBaseHealth(golem);
        double base = proposedBaseHealth > 0.0D ? proposedBaseHealth : golem == null ? 0.0D : golem.baseMaxHealth;
        if (base <= 0.0D && fallback > 0.0D) base = fallback;
        // Old builds sometimes wrote already-boosted guild HP into the persistent base field.
        // For iron golems this must not become 300/500 -> 1200/3000 after migration.
        if (fallback > 0.0D && base > fallback * 1.75D) base = fallback;
        return base;
    }

    private static double cleanBaseDamage(GuildStore.Golem golem, double proposedBaseDamage) {
        double fallback = fallbackBaseDamage(golem);
        double base = proposedBaseDamage >= 0.0D ? proposedBaseDamage : golem == null ? -1.0D : golem.baseDamage;
        if (base < 0.0D && fallback >= 0.0D) base = fallback;
        if (fallback > 0.0D && base > fallback * 1.75D) base = fallback;
        return base;
    }

    private static double fallbackBaseHealth(GuildStore.Golem golem) {
        String id = normalizedTypeId(golem);
        if (id.contains("snow_golem")) return 4.0D;
        if (id.contains("copper_golem")) return 12.0D;
        return 100.0D; // Home Craft guild golems are iron-golem based unless a future type says otherwise.
    }

    private static double fallbackBaseDamage(GuildStore.Golem golem) {
        String id = normalizedTypeId(golem);
        if (id.contains("snow_golem")) return 0.0D;
        if (id.contains("copper_golem")) return 3.0D;
        return 15.0D;
    }

    private static String normalizedTypeId(GuildStore.Golem golem) {
        if (golem == null) return "minecraft:iron_golem";
        String raw = golem.type == null || golem.type.isBlank() ? golem.golemType : golem.type;
        if (raw == null || raw.isBlank()) return "minecraft:iron_golem";
        return raw.toLowerCase(java.util.Locale.ROOT);
    }

    private static double roundOne(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    private static double roundTwo(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
