package ua.homecraft.guild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Small client-side localization bridge for GUI text that still has to be drawn as plain strings.
 *
 * Translation files live in assets/homecraftguild/lang/*.json.  New languages only need to add
 * another locale file with the same key set as en_us.json and uk_ua.json.
 */
public final class HomeCraftGuildI18n {
    private HomeCraftGuildI18n() {}


    /**
     * A tiny translated marker used by open screens to detect runtime language changes.
     * The value comes from the active language file, so it changes immediately after
     * Minecraft reloads language resources.
     */
    public static String languageStamp() {
        String value = t("language.homecraftguild.current");
        String language = "unknown";
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.getLanguageManager() != null) {
                language = String.valueOf(minecraft.getLanguageManager().getSelected());
            }
        } catch (Throwable ignored) {
        }
        return language + "|" + value;
    }

    public static boolean languageChanged(String previousStamp) {
        return previousStamp == null || !previousStamp.equals(languageStamp());
    }

    public static String shownRange(int start, int end, int total) {
        return t("screen.homecraftguild.common.shown_range", start, end, total);
    }

    public static Component c(String key, Object... args) {
        return Component.translatable(key, args);
    }

    public static String t(String key, Object... args) {
        return c(key, args).getString();
    }

    public static String fallback(String key, String fallback, Object... args) {
        String value = t(key, args);
        return value.equals(key) ? (fallback == null ? "" : fallback) : value;
    }

    public static String skinName(String skinId) {
        String id = normalizeId(skinId);
        return fallback("skin.homecraftguild." + id, id);
    }

    public static String npcPresetLabel(String presetId, String fallback) {
        return fallback("npc_preset.homecraftguild." + normalizeId(presetId) + ".label", fallback == null ? normalizeId(presetId) : fallback);
    }

    public static String npcPresetName(String presetId, String fallback) {
        return fallback("npc_preset.homecraftguild." + normalizeId(presetId) + ".name", fallback == null ? normalizeId(presetId) : fallback);
    }

    public static String talentTitle(String id, String fallback) {
        return fallback("talent.homecraftguild." + normalizeId(id) + ".title", fallback);
    }

    public static String talentDescription(String id, String fallback) {
        return fallback("talent.homecraftguild." + normalizeId(id) + ".description", fallback);
    }

    public static String talentEffect(String id, String fallback) {
        return fallback("talent.homecraftguild." + normalizeId(id) + ".effect", fallback);
    }

    public static String talentStatus(String rawStatus) {
        String normalized = normalizeId(rawStatus);
        if (normalized.isBlank()) normalized = "locked";
        if (normalized.equals("доступний")) normalized = "available";
        if (normalized.equals("вивчено")) normalized = "unlocked";
        if (normalized.equals("заблоковано")) normalized = "locked";
        return fallback("talent_status.homecraftguild." + normalized, rawStatus == null || rawStatus.isBlank() ? "locked" : rawStatus);
    }

    public static String achievementTitle(String id, String fallback) {
        return fallback("achievement.homecraftguild." + normalizeId(id) + ".title", fallback);
    }

    public static String achievementCategory(String id, String fallback) {
        return fallback("achievement.homecraftguild." + normalizeId(id) + ".category", fallback);
    }

    public static String achievementDescription(String id, String fallback) {
        return fallback("achievement.homecraftguild." + normalizeId(id) + ".description", fallback);
    }

    public static String achievementConditions(String id, String fallback) {
        return fallback("achievement.homecraftguild." + normalizeId(id) + ".conditions", fallback);
    }

    public static String achievementReward(String id, String fallback) {
        return fallback("achievement.homecraftguild." + normalizeId(id) + ".reward", fallback);
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
