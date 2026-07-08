package ua.homecraft.guild.server;

import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared trade-name localization rules.
 *
 * Custom HomeCraft trade names are stored as translation keys so they react to Minecraft
 * language changes. Vanilla food/metals keep their original item components and therefore use
 * Mojang's normal translations from the selected language.
 */
public final class GuildTradeLocalization {
    private static final Map<String, String> LEGACY_NAME_KEYS = Map.ofEntries(
            Map.entry("кристал гільдії", "trade_name.homecraftguild.guild_crystal"),
            Map.entry("кам'яна кирка мандрівника", "trade_name.homecraftguild.traveler_stone_pickaxe"),
            Map.entry("кам'яна сокира мандрівника", "trade_name.homecraftguild.traveler_stone_axe"),
            Map.entry("лопата дорожнього майстра", "trade_name.homecraftguild.roadmaster_shovel"),
            Map.entry("кирка шахтарського дозору", "trade_name.homecraftguild.mine_watch_pickaxe"),
            Map.entry("сокира табірного теслі", "trade_name.homecraftguild.camp_carpenter_axe"),
            Map.entry("мотика польового доглядача", "trade_name.homecraftguild.field_keeper_hoe"),
            Map.entry("кирка глибинного дозору", "trade_name.homecraftguild.deep_watch_pickaxe"),
            Map.entry("сокира лісового дозору", "trade_name.homecraftguild.forest_watch_axe"),
            Map.entry("лопата тихого кар'єру", "trade_name.homecraftguild.quiet_quarry_shovel"),
            Map.entry("мотика зеленого бастіону", "trade_name.homecraftguild.green_bastion_hoe"),
            Map.entry("кирка щасливої жили", "trade_name.homecraftguild.lucky_vein_pickaxe"),
            Map.entry("кирка чистого розрізу", "trade_name.homecraftguild.clean_cut_pickaxe"),
            Map.entry("ножиці ремісника", "trade_name.homecraftguild.artisan_shears"),
            Map.entry("вудка мандрівника", "trade_name.homecraftguild.traveler_fishing_rod"),
            Map.entry("кресало", "trade_name.homecraftguild.flint_and_steel"),
            Map.entry("компас дороги", "trade_name.homecraftguild.road_compass"),
            Map.entry("годинник варти", "trade_name.homecraftguild.watch_clock"),
            Map.entry("відро", "trade_name.homecraftguild.bucket"),
            Map.entry("відро води", "trade_name.homecraftguild.water_bucket"),
            Map.entry("лук мисливця", "trade_name.homecraftguild.hunter_bow"),
            Map.entry("арбалет вартового", "trade_name.homecraftguild.watchman_crossbow"),
            Map.entry("стріли гільдії", "trade_name.homecraftguild.guild_arrows"),
            Map.entry("примарні стріли", "trade_name.homecraftguild.spectral_arrows"),
            Map.entry("щит туманної варти", "trade_name.homecraftguild.mistguard_shield"),
            Map.entry("клинок новобранця", "trade_name.homecraftguild.recruit_blade"),
            Map.entry("сокира польового вартового", "trade_name.homecraftguild.field_guard_axe"),
            Map.entry("меч гільдійного дозору", "trade_name.homecraftguild.guild_watch_sword"),
            Map.entry("сокира вартового гаю", "trade_name.homecraftguild.grove_guard_axe"),
            Map.entry("клинок срібного дозору", "trade_name.homecraftguild.silver_watch_blade"),
            Map.entry("сокира розколотого дуба", "trade_name.homecraftguild.split_oak_axe"),
            Map.entry("лук зоряного стежника", "trade_name.homecraftguild.star_pathfinder_bow"),
            Map.entry("арбалет залізного фронту", "trade_name.homecraftguild.iron_front_crossbow"),
            Map.entry("шкіряний каптур", "trade_name.homecraftguild.leather_hood"),
            Map.entry("шкіряна куртка", "trade_name.homecraftguild.leather_jacket"),
            Map.entry("шкіряні штани", "trade_name.homecraftguild.leather_pants"),
            Map.entry("шкіряні чоботи", "trade_name.homecraftguild.leather_boots"),
            Map.entry("кольчужний шолом застави", "trade_name.homecraftguild.outpost_chainmail_helmet"),
            Map.entry("кольчужна кіраса застави", "trade_name.homecraftguild.outpost_chainmail_chestplate"),
            Map.entry("кольчужні поножі застави", "trade_name.homecraftguild.outpost_chainmail_leggings"),
            Map.entry("кольчужні чоботи застави", "trade_name.homecraftguild.outpost_chainmail_boots"),
            Map.entry("шолом новобранця", "trade_name.homecraftguild.recruit_helmet"),
            Map.entry("кіраса дозорного", "trade_name.homecraftguild.watchman_chestplate"),
            Map.entry("поножі дозорного", "trade_name.homecraftguild.watchman_leggings"),
            Map.entry("чоботи новобранця", "trade_name.homecraftguild.recruit_boots"),
            Map.entry("шолом авангарду", "trade_name.homecraftguild.vanguard_helmet"),
            Map.entry("кіраса авангарду", "trade_name.homecraftguild.vanguard_chestplate"),
            Map.entry("поножі авангарду", "trade_name.homecraftguild.vanguard_leggings"),
            Map.entry("чоботи тихого патруля", "trade_name.homecraftguild.silent_patrol_boots"),
            Map.entry("щит новобранця", "trade_name.homecraftguild.recruit_shield"),
            Map.entry("павеза дозорного", "trade_name.homecraftguild.watchman_pavise"),
            Map.entry("щит сапфірової варти", "trade_name.homecraftguild.sapphire_guard_shield"),
            Map.entry("егіда туманного бастіону", "trade_name.homecraftguild.mist_bastion_aegis"),
            Map.entry("панцирний шолом", "trade_name.homecraftguild.turtle_shell_helmet"),
            Map.entry("кінська броня залізна", "trade_name.homecraftguild.iron_horse_armor"),
            Map.entry("кінська броня золота", "trade_name.homecraftguild.golden_horse_armor"),
            Map.entry("кінська броня алмазна", "trade_name.homecraftguild.diamond_horse_armor"),
            Map.entry("крила гільдійного неба", "trade_name.homecraftguild.guild_sky_wings"),
            Map.entry("тотем останнього шансу", "trade_name.homecraftguild.last_chance_totem"),
            Map.entry("досвід гільдії", "trade_name.homecraftguild.guild_experience_bottle"),
            Map.entry("перлини швидкого збору", "trade_name.homecraftguild.quick_rally_pearls"),
            Map.entry("іменна бирка", "trade_name.homecraftguild.name_tag"),
            Map.entry("сідло мандрівника", "trade_name.homecraftguild.traveler_saddle"),
            Map.entry("клинок верховного авангарду", "trade_name.homecraftguild.high_vanguard_blade"),
            Map.entry("сокира королівського бастіону", "trade_name.homecraftguild.royal_bastion_axe"),
            Map.entry("булава грозового владики", "trade_name.homecraftguild.stormlord_mace"),
            Map.entry("лук зоряного вироку", "trade_name.homecraftguild.star_judgment_bow"),
            Map.entry("арбалет штормового фронту", "trade_name.homecraftguild.stormfront_crossbow"),
            Map.entry("спис глибинної присяги", "trade_name.homecraftguild.deep_oath_trident"),
            Map.entry("спис розколотої хвилі", "trade_name.homecraftguild.split_wave_trident"),
            Map.entry("корона верховного хранителя", "trade_name.homecraftguild.high_keeper_crown"),
            Map.entry("кіраса непохитного бастіону", "trade_name.homecraftguild.unyielding_bastion_chestplate"),
            Map.entry("поножі безмовного авангарду", "trade_name.homecraftguild.silent_vanguard_leggings"),
            Map.entry("чоботи зоряного маршу", "trade_name.homecraftguild.star_march_boots"),
            Map.entry("егіда верховного авангарду", "trade_name.homecraftguild.high_vanguard_aegis"),
            Map.entry("кирка серця гори", "trade_name.homecraftguild.mountain_heart_pickaxe"),
            Map.entry("кирка шовкового розрізу", "trade_name.homecraftguild.silk_cut_pickaxe"),
            Map.entry("сокира прадавнього лісу", "trade_name.homecraftguild.ancient_forest_axe"),
            Map.entry("лопата прадавнього русла", "trade_name.homecraftguild.ancient_riverbed_shovel"),
            Map.entry("серп зеленого бастіону", "trade_name.homecraftguild.green_bastion_sickle"),
            Map.entry("вудка глибинного майстра", "trade_name.homecraftguild.deep_master_fishing_rod"),
            Map.entry("ножиці королівського садівника", "trade_name.homecraftguild.royal_gardener_shears")
    );

    private static final Set<String> LEGACY_VANILLA_NAMES = Set.of(
            "хліб мандрівника", "печена картопля", "курятина в дорогу", "риба подорожнього",
            "лосось дозорного", "баранина вартового", "свиняча вирізка", "стейк авангарду",
            "гарбузовий пай", "садові яблука", "золота морква гільдії", "кавуновий запас",
            "буряковий суп", "тушкованка травника", "медова фляга",
            "золотий резерв авангарду", "яблуко королівського порятунку"
    );

    private static final Set<String> VANILLA_NAME_ITEMS = Set.of(
            "minecraft:bread", "minecraft:baked_potato", "minecraft:cooked_chicken", "minecraft:cooked_cod",
            "minecraft:cooked_salmon", "minecraft:cooked_mutton", "minecraft:cooked_porkchop", "minecraft:cooked_beef",
            "minecraft:pumpkin_pie", "minecraft:apple", "minecraft:golden_carrot", "minecraft:melon_slice",
            "minecraft:beetroot_soup", "minecraft:suspicious_stew", "minecraft:honey_bottle",
            "minecraft:golden_apple", "minecraft:enchanted_golden_apple",
            "minecraft:copper_ingot", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:netherite_ingot",
            "minecraft:emerald", "minecraft:diamond", "minecraft:coal", "minecraft:redstone", "minecraft:lapis_lazuli",
            "minecraft:raw_copper", "minecraft:raw_iron", "minecraft:raw_gold"
    );

    private GuildTradeLocalization() {}

    public static String legacyNameKey(String rawName) {
        String value = normalizeLegacyName(rawName);
        if (value.isBlank()) return "";
        return LEGACY_NAME_KEYS.getOrDefault(value, "");
    }

    public static boolean shouldUseVanillaName(String itemId, String rawName, String rawNameKey) {
        String item = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
        String name = normalizeLegacyName(rawName);
        if (item.isBlank()) return false;
        if (rawNameKey != null && !rawNameKey.isBlank()) return false;
        return VANILLA_NAME_ITEMS.contains(item) && (name.isBlank() || LEGACY_VANILLA_NAMES.contains(name));
    }

    public static Component nameComponent(String rawName, String rawNameKey, Component fallback) {
        String key = normalizeTranslationKey(rawNameKey);
        if (key.isBlank()) key = legacyNameKey(rawName);
        if (!key.isBlank()) return Component.translatable(key);
        if (rawName != null && rawName.startsWith("lang:")) {
            String langKey = normalizeTranslationKey(rawName.substring("lang:".length()));
            if (!langKey.isBlank()) return Component.translatable(langKey);
        }
        String text = rawName == null ? "" : rawName.trim();
        if (!text.isBlank()) return Component.literal(text);
        return fallback == null ? Component.empty() : fallback;
    }

    public static Component npcNameComponent(String rawName, Component fallback) {
        if (rawName != null && rawName.startsWith("lang:")) {
            String key = normalizeTranslationKey(rawName.substring("lang:".length()));
            if (!key.isBlank()) return Component.translatable(key);
        }
        String text = rawName == null ? "" : rawName.trim();
        if (!text.isBlank()) return Component.literal(text);
        return fallback == null ? Component.translatable("npc.homecraftguild.trader") : fallback;
    }

    public static String normalizeTranslationKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("lang:")) value = value.substring("lang:".length()).trim();
        if (value.length() > 96) value = value.substring(0, 96);
        if (!value.matches("[a-z0-9_.-]+")) return "";
        return value;
    }

    private static String normalizeLegacyName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace("’", "'");
    }
}
