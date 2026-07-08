package ua.homecraft.guild.server;

import net.minecraft.server.MinecraftServer;
import ua.homecraft.guild.HomeCraftGuildMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HomeCraftGuildConfig {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NPC_TRADE_TEMPLATE_VERSION = "v151";
    private static Path loadedPath;
    private static final Properties P = new Properties();

    private HomeCraftGuildConfig() {}

    public static synchronized void load(MinecraftServer server) {
        Path configDir = server.getServerDirectory().resolve("config");
        loadedPath = configDir.resolve("homecraft-guild.properties");
        try { Files.createDirectories(configDir); } catch (IOException ignored) {}
        if (!Files.exists(loadedPath)) writeDefault(loadedPath);
        try (var reader = Files.newBufferedReader(loadedPath, StandardCharsets.UTF_8)) {
            P.clear();
            P.load(reader);
        } catch (Exception e) {
            HomeCraftGuildMod.LOGGER.warn("Could not read Home Craft guild config: {}", loadedPath, e);
        }
        ensureDefaults();
        sanitizeConfigValues();
        save();
        HomeCraftGuildMod.LOGGER.info("Home Craft Guild config loaded: {} api={} maxMembers={} personal={} guild={} creativeBypass={} guildBlockMonsterSpawns={}",
                loadedPath, apiBaseUrl(), maxGuildMembers(), personalClaimSize(), guildClaimSize(), creativeBypass(), guildBlockMonsterSpawns());
    }

    private static void writeDefault(Path path) {
        P.clear();
        ensureDefaults();
        save(path);
    }


    private static String defaultNpcSkinIds() {
        return "guild_master,guild_registrar,trader_armor,medieval_armor,medieval_knight,fighter_guy,boy_green";
    }

    private static String defaultNpcKeys() {
        return "guild_master,trader_basic,trader_food,trader_tools,trader_weapons,trader_armor,trader_elite";
    }

    private static String defaultGuildTraderTrades() {
        return "buy=minecraft:emerald,buyCount=40,sell=homecraftguild:guild_banner,sellCount=1,max=4,xp=5,role=guildmaster,limit=4,name=Кристал гільдії";
    }

    private static String defaultFoodTrades() {
        return String.join("~",
                "buy=minecraft:emerald,buyCount=1,sell=minecraft:bread,sellCount=8,max=32,xp=0,role=any,limit=0,name=Хліб мандрівника",
                "buy=minecraft:emerald,buyCount=1,sell=minecraft:baked_potato,sellCount=6,max=32,xp=0,role=any,limit=0,name=Печена картопля",
                "buy=minecraft:emerald,buyCount=2,sell=minecraft:cooked_chicken,sellCount=6,max=28,xp=0,role=any,limit=0,name=Курятина в дорогу",
                "buy=minecraft:emerald,buyCount=2,sell=minecraft:cooked_cod,sellCount=6,max=28,xp=0,role=any,limit=0,name=Риба подорожнього",
                "buy=minecraft:emerald,buyCount=3,sell=minecraft:cooked_salmon,sellCount=6,max=24,xp=1,role=any,limit=0,name=Лосось дозорного",
                "buy=minecraft:emerald,buyCount=3,sell=minecraft:cooked_mutton,sellCount=6,max=24,xp=1,role=any,limit=0,name=Баранина вартового",
                "buy=minecraft:emerald,buyCount=3,sell=minecraft:cooked_porkchop,sellCount=6,max=24,xp=1,role=any,limit=0,name=Свиняча вирізка",
                "buy=minecraft:emerald,buyCount=4,sell=minecraft:cooked_beef,sellCount=6,max=24,xp=1,role=any,limit=0,name=Стейк авангарду",
                "buy=minecraft:emerald,buyCount=3,sell=minecraft:pumpkin_pie,sellCount=4,max=24,xp=1,role=any,limit=0,name=Гарбузовий пай",
                "buy=minecraft:emerald,buyCount=4,sell=minecraft:apple,sellCount=8,max=24,xp=1,role=any,limit=0,name=Садові яблука",
                "buy=minecraft:emerald,buyCount=5,sell=minecraft:golden_carrot,sellCount=6,max=20,xp=2,role=member,limit=0,name=Золота морква гільдії",
                "buy=minecraft:emerald,buyCount=2,sell=minecraft:melon_slice,sellCount=12,max=24,xp=0,role=any,limit=0,name=Кавуновий запас",
                "buy=minecraft:emerald,buyCount=3,sell=minecraft:beetroot_soup,sellCount=3,max=18,xp=1,role=any,limit=0,name=Буряковий суп",
                "buy=minecraft:emerald,buyCount=5,sell=minecraft:suspicious_stew,sellCount=2,max=12,xp=2,role=member,limit=0,name=Тушкованка травника",
                "buy=minecraft:emerald,buyCount=3,sell=minecraft:honey_bottle,sellCount=4,max=18,xp=1,role=any,limit=0,name=Медова фляга"
        );
    }

    private static String defaultToolTrades() {
        return joinTrades(String.join("~",
                "buy=minecraft:emerald,buyCount=4,sell=minecraft:stone_pickaxe,sellCount=1,max=24,xp=0,role=any,limit=0,name=Кам'яна кирка мандрівника",
                "buy=minecraft:emerald,buyCount=4,sell=minecraft:stone_axe,sellCount=1,max=24,xp=0,role=any,limit=0,name=Кам'яна сокира мандрівника",
                "buy=minecraft:emerald,buyCount=9,sell=minecraft:iron_shovel,sellCount=1,max=20,xp=1,role=member,limit=0,name=Лопата дорожнього майстра,ench=efficiency:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=10,sell=minecraft:iron_pickaxe,sellCount=1,max=20,xp=1,role=member,limit=0,name=Кирка шахтарського дозору,ench=efficiency:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=10,sell=minecraft:iron_axe,sellCount=1,max=20,xp=1,role=member,limit=0,name=Сокира табірного теслі,ench=efficiency:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=8,sell=minecraft:iron_hoe,sellCount=1,max=20,xp=1,role=member,limit=0,name=Мотика польового доглядача,ench=efficiency:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=26,sell=minecraft:diamond_pickaxe,sellCount=1,max=14,xp=3,role=member,limit=0,name=Кирка глибинного дозору,ench=efficiency:4+unbreaking:3",
                "buy=minecraft:emerald,buyCount=24,sell=minecraft:diamond_axe,sellCount=1,max=14,xp=3,role=member,limit=0,name=Сокира лісового дозору,ench=efficiency:4+unbreaking:3",
                "buy=minecraft:emerald,buyCount=20,sell=minecraft:diamond_shovel,sellCount=1,max=14,xp=2,role=member,limit=0,name=Лопата тихого кар'єру,ench=efficiency:4+unbreaking:3",
                "buy=minecraft:emerald,buyCount=20,sell=minecraft:diamond_hoe,sellCount=1,max=14,xp=2,role=member,limit=0,name=Мотика зеленого бастіону,ench=efficiency:4+unbreaking:3",
                "buy=minecraft:emerald,buyCount=42,sell=minecraft:diamond_pickaxe,sellCount=1,max=8,xp=5,role=member,limit=0,name=Кирка щасливої жили,ench=efficiency:4+fortune:3+unbreaking:3",
                "buy=minecraft:emerald,buyCount=46,sell=minecraft:diamond_pickaxe,sellCount=1,max=8,xp=5,role=member,limit=0,name=Кирка чистого розрізу,ench=efficiency:4+silk_touch:1+unbreaking:3",
                "buy=minecraft:emerald,buyCount=6,sell=minecraft:shears,sellCount=1,max=32,xp=0,role=any,limit=0,name=Ножиці ремісника,ench=unbreaking:2",
                "buy=minecraft:emerald,buyCount=10,sell=minecraft:fishing_rod,sellCount=1,max=24,xp=1,role=any,limit=0,name=Вудка мандрівника,ench=lure:2+luck_of_the_sea:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=3,sell=minecraft:flint_and_steel,sellCount=1,max=24,xp=0,role=any,limit=0,name=Кресало",
                "buy=minecraft:emerald,buyCount=5,sell=minecraft:compass,sellCount=1,max=24,xp=1,role=any,limit=0,name=Компас дороги",
                "buy=minecraft:emerald,buyCount=5,sell=minecraft:clock,sellCount=1,max=24,xp=1,role=any,limit=0,name=Годинник варти",
                "buy=minecraft:emerald,buyCount=4,sell=minecraft:bucket,sellCount=1,max=32,xp=0,role=any,limit=0,name=Відро",
                "buy=minecraft:emerald,buyCount=6,sell=minecraft:water_bucket,sellCount=1,max=24,xp=1,role=any,limit=0,name=Відро води"
        ));
    }

    private static String joinTrades(String... chunks) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (chunks == null) return "";
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) continue;
            for (String trade : chunk.split("~")) {
                String value = trade == null ? "" : trade.trim();
                if (!value.isBlank()) out.add(value);
                if (out.size() >= 64) break;
            }
            if (out.size() >= 64) break;
        }
        return String.join("~", out);
    }

    private static String defaultMetalBuybackTrades() {
        return String.join("~",
                "buy=minecraft:copper_ingot,buyCount=10,sell=minecraft:emerald,sellCount=1,max=64,xp=0,role=any,limit=0",
                "buy=minecraft:iron_ingot,buyCount=10,sell=minecraft:emerald,sellCount=2,max=48,xp=0,role=any,limit=0",
                "buy=minecraft:gold_ingot,buyCount=10,sell=minecraft:emerald,sellCount=3,max=40,xp=1,role=any,limit=0",
                "buy=minecraft:netherite_ingot,buyCount=10,sell=minecraft:emerald,sellCount=5,max=8,xp=2,role=member,limit=0"
        );
    }

    private static String defaultWeaponTrades() {
        return joinTrades(String.join("~",
                "buy=minecraft:emerald,buyCount=8,sell=minecraft:bow,sellCount=1,max=20,xp=1,role=member,limit=0,name=Лук мисливця,ench=power:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=11,sell=minecraft:crossbow,sellCount=1,max=18,xp=1,role=member,limit=0,name=Арбалет вартового,ench=quick_charge:1+unbreaking:2",
                "buy=minecraft:emerald,buyCount=2,sell=minecraft:arrow,sellCount=32,max=32,xp=0,role=any,limit=0,name=Стріли гільдії",
                "buy=minecraft:emerald,buyCount=6,sell=minecraft:spectral_arrow,sellCount=16,max=20,xp=1,role=member,limit=0,name=Примарні стріли",
                "buy=minecraft:emerald,buyCount=12,sell=minecraft:shield,sellCount=1,max=18,xp=1,role=member,limit=0,name=Щит туманної варти,ench=unbreaking:2",
                "buy=minecraft:emerald,buyCount=16,sell=minecraft:iron_sword,sellCount=1,max=18,xp=1,role=member,limit=0,name=Клинок новобранця,ench=sharpness:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=18,sell=minecraft:iron_axe,sellCount=1,max=18,xp=1,role=member,limit=0,name=Сокира польового вартового,ench=sharpness:2+efficiency:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=34,sell=minecraft:diamond_sword,sellCount=1,max=12,xp=3,role=member,limit=0,name=Меч гільдійного дозору,ench=sharpness:3+unbreaking:3",
                "buy=minecraft:emerald,buyCount=38,sell=minecraft:diamond_axe,sellCount=1,max=12,xp=3,role=member,limit=0,name=Сокира вартового гаю,ench=sharpness:3+efficiency:3+unbreaking:3",
                "buy=minecraft:emerald,buyCount=52,sell=minecraft:diamond_sword,sellCount=1,max=8,xp=6,role=member,limit=0,name=Клинок срібного дозору,ench=sharpness:4+looting:2+unbreaking:3",
                "buy=minecraft:emerald,buyCount=58,sell=minecraft:diamond_axe,sellCount=1,max=8,xp=6,role=member,limit=0,name=Сокира розколотого дуба,ench=sharpness:4+efficiency:4+unbreaking:3",
                "buy=minecraft:emerald,buyCount=60,sell=minecraft:bow,sellCount=1,max=8,xp=7,role=member,limit=0,name=Лук зоряного стежника,ench=power:4+punch:2+unbreaking:3",
                "buy=minecraft:emerald,buyCount=64,sell=minecraft:crossbow,sellCount=1,max=8,xp=7,role=member,limit=0,name=Арбалет залізного фронту,ench=quick_charge:3+piercing:3+unbreaking:3"
        ), defaultMetalBuybackTrades());
    }

    private static String defaultArmorTrades() {
        return joinTrades(String.join("~",
                "buy=minecraft:emerald,buyCount=6,sell=minecraft:leather_helmet,sellCount=1,max=24,xp=0,role=any,limit=0,name=Шкіряний каптур",
                "buy=minecraft:emerald,buyCount=8,sell=minecraft:leather_chestplate,sellCount=1,max=24,xp=0,role=any,limit=0,name=Шкіряна куртка",
                "buy=minecraft:emerald,buyCount=7,sell=minecraft:leather_leggings,sellCount=1,max=24,xp=0,role=any,limit=0,name=Шкіряні штани",
                "buy=minecraft:emerald,buyCount=5,sell=minecraft:leather_boots,sellCount=1,max=24,xp=0,role=any,limit=0,name=Шкіряні чоботи",
                "buy=minecraft:emerald,buyCount=10,sell=minecraft:chainmail_helmet,sellCount=1,max=18,xp=1,role=member,limit=0,name=Кольчужний шолом застави",
                "buy=minecraft:emerald,buyCount=15,sell=minecraft:chainmail_chestplate,sellCount=1,max=18,xp=1,role=member,limit=0,name=Кольчужна кіраса застави",
                "buy=minecraft:emerald,buyCount=13,sell=minecraft:chainmail_leggings,sellCount=1,max=18,xp=1,role=member,limit=0,name=Кольчужні поножі застави",
                "buy=minecraft:emerald,buyCount=9,sell=minecraft:chainmail_boots,sellCount=1,max=18,xp=1,role=member,limit=0,name=Кольчужні чоботи застави",
                "buy=minecraft:emerald,buyCount=14,sell=minecraft:iron_helmet,sellCount=1,max=18,xp=1,role=member,limit=0,name=Шолом новобранця,ench=protection:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=22,sell=minecraft:iron_chestplate,sellCount=1,max=18,xp=1,role=member,limit=0,name=Кіраса дозорного,ench=protection:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=20,sell=minecraft:iron_leggings,sellCount=1,max=18,xp=1,role=member,limit=0,name=Поножі дозорного,ench=protection:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=14,sell=minecraft:iron_boots,sellCount=1,max=18,xp=1,role=member,limit=0,name=Чоботи новобранця,ench=protection:2+feather_falling:2+unbreaking:2",
                "buy=minecraft:emerald,buyCount=28,sell=minecraft:diamond_helmet,sellCount=1,max=12,xp=3,role=member,limit=0,name=Шолом авангарду,ench=protection:3+unbreaking:3",
                "buy=minecraft:emerald,buyCount=44,sell=minecraft:diamond_chestplate,sellCount=1,max=12,xp=3,role=member,limit=0,name=Кіраса авангарду,ench=protection:3+unbreaking:3",
                "buy=minecraft:emerald,buyCount=40,sell=minecraft:diamond_leggings,sellCount=1,max=12,xp=3,role=member,limit=0,name=Поножі авангарду,ench=protection:3+unbreaking:3",
                "buy=minecraft:emerald,buyCount=28,sell=minecraft:diamond_boots,sellCount=1,max=12,xp=3,role=member,limit=0,name=Чоботи тихого патруля,ench=protection:3+feather_falling:3+unbreaking:3",
                "buy=minecraft:emerald,buyCount=16,sell=minecraft:shield,sellCount=1,max=18,xp=1,role=member,limit=0,name=Щит новобранця,ench=unbreaking:2",
                "buy=minecraft:emerald,buyCount=28,sell=minecraft:shield,sellCount=1,max=14,xp=2,role=member,limit=0,name=Павеза дозорного,ench=unbreaking:3",
                "buy=minecraft:emerald,buyCount=48,sell=minecraft:shield,sellCount=1,max=10,xp=5,role=member,limit=0,name=Щит сапфірової варти,ench=unbreaking:3+mending:1",
                "buy=minecraft:emerald,buyCount=64,buy2=minecraft:emerald,buy2Count=16,sell=minecraft:shield,sellCount=1,max=6,xp=8,role=member,limit=1,name=Егіда туманного бастіону,ench=unbreaking:3+mending:1",
                "buy=minecraft:emerald,buyCount=16,sell=minecraft:turtle_helmet,sellCount=1,max=12,xp=2,role=member,limit=0,name=Панцирний шолом",
                "buy=minecraft:emerald,buyCount=8,sell=minecraft:iron_horse_armor,sellCount=1,max=12,xp=1,role=member,limit=0,name=Кінська броня залізна",
                "buy=minecraft:emerald,buyCount=10,sell=minecraft:golden_horse_armor,sellCount=1,max=12,xp=1,role=member,limit=0,name=Кінська броня золота",
                "buy=minecraft:emerald,buyCount=18,sell=minecraft:diamond_horse_armor,sellCount=1,max=8,xp=2,role=member,limit=0,name=Кінська броня алмазна"
        ), defaultMetalBuybackTrades());
    }

    private static String maxEmeraldTrade(String sell, String name, String ench, int xp) {
        return "buy=minecraft:emerald,buyCount=64,buy2=minecraft:emerald,buy2Count=64,sell=" + sell
                + ",sellCount=1,max=2,xp=" + xp + ",role=member,limit=1,name=" + name + ",ench=" + ench;
    }

    private static String maxEmeraldTrade(String sell, String name, String ench, int xp, String extra) {
        String base = maxEmeraldTrade(sell, name, ench, xp);
        return extra == null || extra.isBlank() ? base : base + "," + extra;
    }

    private static String defaultEliteWeaponTrades() {
        return String.join("~",
                maxEmeraldTrade("minecraft:netherite_sword", "Клинок верховного авангарду", "sharpness:5+looting:3+sweeping_edge:3+fire_aspect:2+knockback:2+unbreaking:3+mending:1", 36, "damage=2"),
                maxEmeraldTrade("minecraft:netherite_axe", "Сокира королівського бастіону", "sharpness:5+efficiency:5+unbreaking:3+mending:1", 36, "damage=2"),
                maxEmeraldTrade("minecraft:mace", "Булава грозового владики", "density:5+breach:4+wind_burst:3+unbreaking:3+mending:1", 38, "damage=2"),
                maxEmeraldTrade("minecraft:bow", "Лук зоряного вироку", "power:5+punch:2+flame:1+unbreaking:3+mending:1", 34),
                maxEmeraldTrade("minecraft:crossbow", "Арбалет штормового фронту", "quick_charge:3+piercing:4+unbreaking:3+mending:1", 34),
                maxEmeraldTrade("minecraft:trident", "Спис глибинної присяги", "impaling:5+loyalty:3+channeling:1+unbreaking:3+mending:1", 38),
                maxEmeraldTrade("minecraft:trident", "Спис розколотої хвилі", "impaling:5+riptide:3+unbreaking:3+mending:1", 38)
        );
    }

    private static String defaultEliteArmorTrades() {
        return String.join("~",
                maxEmeraldTrade("minecraft:netherite_helmet", "Корона верховного хранителя", "protection:4+respiration:3+aqua_affinity:1+thorns:3+unbreaking:3+mending:1", 34),
                maxEmeraldTrade("minecraft:netherite_chestplate", "Кіраса непохитного бастіону", "protection:4+thorns:3+unbreaking:3+mending:1", 36),
                maxEmeraldTrade("minecraft:netherite_leggings", "Поножі безмовного авангарду", "protection:4+swift_sneak:3+thorns:3+unbreaking:3+mending:1", 36),
                maxEmeraldTrade("minecraft:netherite_boots", "Чоботи зоряного маршу", "protection:4+feather_falling:4+depth_strider:3+soul_speed:3+thorns:3+unbreaking:3+mending:1", 36),
                maxEmeraldTrade("minecraft:shield", "Егіда верховного авангарду", "unbreaking:3+mending:1", 28),
                "buy=minecraft:emerald,buyCount=64,buy2=minecraft:emerald,buy2Count=48,sell=minecraft:elytra,sellCount=1,max=1,xp=40,role=guildmaster,limit=1,name=Крила гільдійного неба,ench=unbreaking:3+mending:1"
        );
    }

    private static String defaultEliteToolTrades() {
        return String.join("~",
                maxEmeraldTrade("minecraft:netherite_pickaxe", "Кирка серця гори", "efficiency:5+fortune:3+unbreaking:3+mending:1", 34),
                maxEmeraldTrade("minecraft:netherite_pickaxe", "Кирка шовкового розрізу", "efficiency:5+silk_touch:1+unbreaking:3+mending:1", 34),
                maxEmeraldTrade("minecraft:netherite_axe", "Сокира прадавнього лісу", "efficiency:5+sharpness:5+unbreaking:3+mending:1", 34),
                maxEmeraldTrade("minecraft:netherite_shovel", "Лопата прадавнього русла", "efficiency:5+unbreaking:3+mending:1", 32),
                maxEmeraldTrade("minecraft:netherite_hoe", "Серп зеленого бастіону", "efficiency:5+fortune:3+unbreaking:3+mending:1", 32),
                maxEmeraldTrade("minecraft:fishing_rod", "Вудка глибинного майстра", "luck_of_the_sea:3+lure:3+unbreaking:3+mending:1", 24),
                maxEmeraldTrade("minecraft:shears", "Ножиці королівського садівника", "efficiency:5+unbreaking:3+mending:1", 20)
        );
    }

    private static String defaultEliteUtilityTrades() {
        return String.join("~",
                "buy=minecraft:emerald,buyCount=64,buy2=minecraft:emerald,buy2Count=32,sell=minecraft:totem_of_undying,sellCount=1,max=3,xp=24,role=guildmaster,limit=1,name=Тотем останнього шансу",
                "buy=minecraft:emerald,buyCount=64,buy2=minecraft:emerald,buy2Count=16,sell=minecraft:golden_apple,sellCount=4,max=6,xp=12,role=member,limit=2,name=Золотий резерв авангарду",
                "buy=minecraft:emerald,buyCount=64,buy2=minecraft:emerald,buy2Count=64,sell=minecraft:enchanted_golden_apple,sellCount=1,max=1,xp=40,role=guildmaster,limit=1,name=Яблуко королівського порятунку",
                "buy=minecraft:emerald,buyCount=32,sell=minecraft:experience_bottle,sellCount=16,max=10,xp=8,role=member,limit=0,name=Досвід гільдії",
                "buy=minecraft:emerald,buyCount=24,sell=minecraft:ender_pearl,sellCount=8,max=10,xp=4,role=member,limit=0,name=Перлини швидкого збору",
                "buy=minecraft:emerald,buyCount=18,sell=minecraft:name_tag,sellCount=1,max=14,xp=2,role=member,limit=0,name=Іменна бирка",
                "buy=minecraft:emerald,buyCount=18,sell=minecraft:saddle,sellCount=1,max=10,xp=2,role=member,limit=0,name=Сідло мандрівника"
        );
    }

    private static String defaultEliteTrades() {
        return joinTrades(defaultEliteUtilityTrades(), defaultEliteWeaponTrades(), defaultEliteArmorTrades(), defaultEliteToolTrades(), defaultMetalBuybackTrades());
    }

    private static void ensureDefaults() {
        put("homecraftIntegrationMode", "auto");
        put("spawnBoundaryIntegrationEnabled", "true");
        put("territorySyncRequiresHomeCraftMap", "true");
        put("apiBaseUrl", "http://127.0.0.1:3000");
        put("serverSecret", randomSecret());
        put("guildCreateEmeraldCost", "3");
        put("maxGuildMembers", "20");
        put("guildNpcName", "Гільдійний Реєстратор");
        put("guildNpcSkin", "guild_registrar");
        put("guildNpcEnabled", "false");
        put("guildNpcDimension", "minecraft:overworld");
        put("guildNpcX", "0.5");
        put("guildNpcY", "64.0");
        put("guildNpcZ", "0.5");
        put("guildNpcYaw", "0.0");
        put("guildNpcRegistryRevision", "0");
        put("npcVillagerSoundsEnabled", "true");
        put("npcAmbientSoundCooldownTicks", "120");
        put("npcInteractionSoundEnabled", "true");
        put("guildNpcSkinIds", defaultNpcSkinIds());
        put("guildNpcKeys", defaultNpcKeys());
        put("guildNpc.guild_master.enabled", P.getProperty("guildNpcEnabled", "false"));
        put("guildNpc.guild_master.kind", "system");
        put("guildNpc.guild_master.system", "true");
        put("guildNpc.guild_master.skinId", "guild_master");
        put("guildNpc.guild_master.name", P.getProperty("guildNpcName", "Гільдійний Майстер"));
        put("guildNpc.guild_master.dimension", P.getProperty("guildNpcDimension", "minecraft:overworld"));
        put("guildNpc.guild_master.x", P.getProperty("guildNpcX", "0.5"));
        put("guildNpc.guild_master.y", P.getProperty("guildNpcY", "64.0"));
        put("guildNpc.guild_master.z", P.getProperty("guildNpcZ", "0.5"));
        put("guildNpc.guild_master.yaw", P.getProperty("guildNpcYaw", "0.0"));
        put("guildNpc.trader_basic.enabled", "false");
        put("guildNpc.trader_basic.kind", "trader");
        put("guildNpc.trader_basic.system", "false");
        put("guildNpc.trader_basic.skinId", "guild_registrar");
        put("guildNpc.trader_basic.name", "Гільдійний Торговець");
        put("guildNpc.trader_basic.trades", defaultGuildTraderTrades());
        put("guildNpc.trader_food.enabled", "false");
        put("guildNpc.trader_food.kind", "trader");
        put("guildNpc.trader_food.system", "false");
        put("guildNpc.trader_food.skinId", "boy_green");
        put("guildNpc.trader_food.name", "Продуктовий торговець");
        put("guildNpc.trader_food.trades", defaultFoodTrades());
        put("guildNpc.trader_tools.enabled", "false");
        put("guildNpc.trader_tools.kind", "trader");
        put("guildNpc.trader_tools.system", "false");
        put("guildNpc.trader_tools.skinId", "fighter_guy");
        put("guildNpc.trader_tools.name", "Торговець інструментами");
        put("guildNpc.trader_tools.trades", defaultToolTrades());
        put("guildNpc.trader_weapons.enabled", "false");
        put("guildNpc.trader_weapons.kind", "trader");
        put("guildNpc.trader_weapons.system", "false");
        put("guildNpc.trader_weapons.skinId", "medieval_knight");
        put("guildNpc.trader_weapons.name", "Героїчний зброяр");
        put("guildNpc.trader_weapons.trades", defaultWeaponTrades());
        put("guildNpc.trader_armor.enabled", "false");
        put("guildNpc.trader_armor.kind", "trader");
        put("guildNpc.trader_armor.system", "false");
        put("guildNpc.trader_armor.skinId", "trader_armor");
        put("guildNpc.trader_armor.name", "Майстер броні");
        put("guildNpc.trader_armor.trades", defaultArmorTrades());
        put("guildNpc.trader_elite.enabled", "false");
        put("guildNpc.trader_elite.kind", "trader");
        put("guildNpc.trader_elite.system", "false");
        put("guildNpc.trader_elite.skinId", "medieval_armor");
        put("guildNpc.trader_elite.name", "Елітний гільдійний торговець");
        put("guildNpc.trader_elite.trades", defaultEliteTrades());
        put("personalBannerLimit", "2");
        put("personalClaimSize", "48");
        put("guildBannerLimit", "4");
        put("guildClaimSize", "64");
        put("creativeBypass", "true");
        put("territorySyncEnabled", "false");
        put("territorySyncIntervalSeconds", "30");
        put("maxGuildGolems", "5");
        put("golemProtectRange", "24");
        put("golemAiIntervalTicks", "20");
        put("guildGolemAiEnabled", "true");
        put("guildGolemMaxPerGuild", "5");
        put("guildGolemHealthMultiplier", "4.0");
        put("guildGolemDamageMultiplier", "1.5");
        put("guildGolemChaseOutsideTerritoryBlocks", "16");
        put("guildGolemTargetScanIntervalTicks", "20");
        put("guildGolemPathRecalcCooldownTicks", "20");
        put("guildGolemTargetStickinessTicks", "80");
        put("guildEliteGolemEnabled", "true");
        put("guildEliteGolemMaxPerGuild", "1");
        put("guildEliteGolemHealthMultiplier", "6.0");
        put("guildEliteGolemDamageMultiplier", "1.75");
        put("guildEliteGolemChaseOutsideTerritoryBlocks", "24");
        put("guildEliteGolemTargetScanIntervalTicks", "20");
        put("guildEliteGolemPathRecalcCooldownTicks", "30");
        put("guildEliteGolemTargetStickinessTicks", "120");
        put("guildEliteGolemProtectGuildMasterPriority", "true");
        put("guildEliteGolemIgnoreWeakTargetsWhenNormalGolemsAvailable", "true");
        put("guildEliteGolemStrategicPatrolEnabled", "true");
        put("guildGolemPatrolEnabled", "true");
        put("guildGolemNightPatrolEnabled", "true");
        put("guildGolemNightPatrolNearMembers", "true");
        put("guildGolemNightMemberSupportRadiusBlocks", "14");
        put("guildGolemNightIgnoreUndergroundMembers", "true");
        put("guildGolemSquadCoordinatorEnabled", "true");
        put("guildGolemSquadUpdateIntervalTicks", "40");
        put("guildGolemPatrolReplanIntervalTicks", "80");
        put("guildGolemLowPriorityIntervalTicks", "100");
        put("guildGolemMaxThreatScanRadius", "32");
        put("guildEliteGolemMaxThreatScanRadius", "40");
        put("guildGolemMaxPathFailuresBeforeRecover", "3");
        put("guildGolemMaxPathFailuresBeforeTeleportRecover", "6");
        put("guildGolemHealingEnabled", "true");
        put("guildGolemHealingStartHealthPercent", "50");
        put("guildGolemHealingStopHealthPercent", "98");
        put("guildGolemDayHealingRequiresFullHealth", "true");
        put("guildGolemHealingCheckIntervalTicks", "40");
        put("guildGolemHealingRadiusBlocks", "5");
        put("guildGolemHealingMinPerSlice", "1.0");
        put("guildGolemHealingMaxHealthPercentPerSlice", "3.5");
        put("guildGolemHealingApplyIntervalTicks", "20");
        put("guildGolemHealingParticleIntervalTicks", "16");
        put("guildGolemHealingRequireSafeTotemPoint", "true");
        put("guildGolemHealingCancelUnsafeFallback", "true");
        put("guildGolemRespawnEnabled", "true");
        put("guildGolemRespawnCostEnabled", "false");
        put("guildEliteGolemRespawnEnabled", "true");
        put("guildEliteGolemRespawnCostEnabled", "true");
        put("guildGolemLagLevel2Throttle", "true");
        put("guildGolemLagLevel3CriticalOnly", "true");
        put("guildGolemLagLevel3DisableCosmetics", "true");
        put("guildGolemLagLevel3TargetScanMultiplier", "3");
        put("guildGolemLagLevel3PathRecalcMultiplier", "3");
        put("guildGolemDebug", "false");
        put("guildGolemDebugStateChanges", "false");
        put("guildGolemDebugPathFailures", "false");
        put("guildGolemDebugTargetSelection", "false");
        put("guildGolemRouteFallbackEnabled", "true");
        put("guildGolemRouteAvoidWater", "true");
        put("guildGolemRouteAvoidDeepDrops", "true");
        put("guildGolemRouteMaxStartsPerTick", "4");
        put("guildGolemRouteMaxSafeChecks", "40");
        put("guildGolemRouteAsyncEnabled", "true");
        put("guildGolemRouteCacheTtlTicks", "600");
        put("guildGolemRouteDebug", "false");
        put("guildBlockMonsterSpawns", "true");
        put("guildBedsEnabled", "true");
        put("guildBedsRequireGuildTerritory", "true");
        put("guildBedsOneClaimedBedPerMember", "true");
        put("guildBedsOnePlacedBedPerMember", "true");
        put("guildBedsGuildMasterUnlimitedPlacement", "true");
        put("guildBedsBuilderUnlimitedPlacement", "true");
        put("guildBedsAllowReclaim", "true");
        put("guildBedsShowOwnerLabel", "true");
        put("guildBedsOwnerLabelMaxDistance", "32");
        put("guildBedsOwnerLabelYOffset", "1.35");
        put("guildBedsLabelCellSize", "64");
        put("guildBedsHideExactCoordsForNonMembers", "true");
        put("guildBedsAllowGuildMasterFreeBed", "true");
        put("guildBedsAllowBuilderFreeBed", "true");
        put("guildBedsValidateOnServerStart", "true");
        put("guildBedsDebug", "false");
        put("guildTotemCleanupEnabled", "true");
        put("guildTotemCleanupOnServerStart", "true");
        put("guildTotemCleanupIntervalSeconds", "120");
        put("guildTotemCleanupMaxChecksPerPass", "32");
        put("guildTalentsEnabled", "true");
        put("guildTalentMaxGuildLevel", "7");
        put("guildTalentResetCostEmeralds", "3");
        put("guildTalentDebug", "false");
        put("guildTalentLevel1Points", "1");
        put("guildTalentLevel2Points", "1");
        put("guildTalentLevel3Points", "1");
        put("guildTalentLevel4Points", "1");
        put("guildTalentLevel5Points", "2");
        put("guildTalentLevel6Points", "1");
        put("guildTalentLevel7Points", "2");
        put("guildBaseXpBonusPercent", "5");
        put("guildBasePotionDurationBonusPercent", "10");
        put("guildBaseWeaponDamageBonusPercent", "5");
        put("guildBaseArmorBonusPercent", "5");
        put("guildMemberMaxSpeedBonusPercent", "25");
        put("guildMemberMaxJumpBonus", "0.15");
        put("guildGolemTalentMaxHealthBonusPercent", "25");
        put("guildGolemTalentMaxDamageBonusPercent", "15");
        put("guildGolemTalentMaxSpeedBonusPercent", "15");
        put("guildGolemTalentMaxHealingSpeedBonusPercent", "30");
        put("debug", "false");
    }

    private static void put(String key, String value) {
        if (P.getProperty(key) == null || P.getProperty(key).isBlank()) P.setProperty(key, value);
    }

    private static void sanitizeConfigValues() {
        sanitizeText("guildNpcName", "Гільдійний Реєстратор");
        sanitizeText("guildNpcDimension", "minecraft:overworld");
        sanitizeText("guildNpc.guild_master.name", "Гільдійний Майстер");
        sanitizeText("guildNpc.guild_master.dimension", "minecraft:overworld");
        sanitizeIntMinimum("maxGuildGolems", 1);
        sanitizeIntMinimum("guildGolemMaxPerGuild", 1);
        sanitizeIntMinimum("territorySyncIntervalSeconds", 30);
        extendNpcSkinIds();
        migrateBundledNpcSkinIds();
        migrateNpcPresetMetadata();
        migrateNpcTradeCurrencies();
        upgradeDefaultTradeTemplates();
    }

    private static void extendNpcSkinIds() {
        // v0.1.136 deliberately replaces the old bundled skin list instead of merging it.
        // Keeping removed ids in config made UI show duplicate/fallback skins under different names.
        Set<String> skins = new LinkedHashSet<>();
        for (String part : defaultNpcSkinIds().split(",")) {
            String id = normalizeNpcKey(part);
            if (!id.isBlank()) skins.add(id);
        }
        P.setProperty("guildNpcSkinIds", String.join(",", skins));
    }

    private static void migrateBundledNpcSkinIds() {
        migrateBundledNpcSkinId("guild_master", "guild_master");
        migrateBundledNpcSkinId("trader_basic", "guild_registrar");
        migrateBundledNpcSkinId("trader_food", "boy_green");
        migrateBundledNpcSkinId("trader_tools", "fighter_guy");
        migrateBundledNpcSkinId("trader_weapons", "medieval_knight");
        migrateBundledNpcSkinId("trader_armor", "trader_armor");
        migrateBundledNpcSkinId("trader_elite", "medieval_armor");
    }

    private static void migrateBundledNpcSkinId(String npcKey, String skinId) {
        String prefix = npcPrefix(npcKey);
        String current = normalizeNpcKey(P.getProperty(prefix + "skinId", ""));
        boolean removedBundledSkin = "default".equals(current)
                || "trader_basic".equals(current)
                || "trader_food".equals(current)
                || "trader_tools".equals(current)
                || "trader_weapons".equals(current)
                || "trader_elite".equals(current)
                || current.startsWith("guild_registrar_medieval");
        if (current.isBlank() || !npcAvailableSkins().contains(current) || removedBundledSkin) {
            P.setProperty(prefix + "skinId", skinId);
        }
    }


    private static void migrateNpcPresetMetadata() {
        for (String key : npcKeys()) {
            String safe = normalizeNpcKey(key);
            if (safe.isBlank()) continue;
            String prefix = npcPrefix(safe);
            boolean system = bool(prefix + "system", "system".equals(P.getProperty(prefix + "kind", "system")));
            if (system) {
                put(prefix + "traderPresetId", "");
                put(prefix + "customTrades", "false");
                put(prefix + "tradesHash", "0");
                continue;
            }
            String preset = normalizeNpcKey(P.getProperty(prefix + "traderPresetId", ""));
            if (preset.isBlank()) {
                preset = defaultTradesForPreset(safe).isBlank() ? "trader_basic" : safe;
                P.setProperty(prefix + "traderPresetId", preset);
            }
            String rawTrades = P.getProperty(prefix + "trades", "");
            String trades = sanitizeNpcTrades(rawTrades);
            if (!trades.equals(rawTrades)) P.setProperty(prefix + "trades", trades);
            String presetTrades = defaultTradesForPreset(preset);
            boolean custom = !trades.isBlank() && !trades.equals(presetTrades);
            P.setProperty(prefix + "customTrades", P.getProperty(prefix + "customTrades", String.valueOf(custom)));
            P.setProperty(prefix + "tradesHash", stableNpcTradesHash(trades));
            if (P.getProperty(prefix + "tradesVersion") == null || P.getProperty(prefix + "tradesVersion").isBlank()) {
                P.setProperty(prefix + "tradesVersion", NPC_TRADE_TEMPLATE_VERSION + ":" + preset);
            }
        }
    }

    private static void migrateNpcTradeCurrencies() {
        boolean changed = false;
        for (String key : npcKeys()) {
            String safe = normalizeNpcKey(key);
            if (safe.isBlank() || npcSystem(safe)) continue;
            String prefix = npcPrefix(safe);
            String raw = P.getProperty(prefix + "trades", "");
            String normalized = sanitizeNpcTrades(raw);
            if (!normalized.equals(raw)) {
                P.setProperty(prefix + "trades", normalized);
                P.setProperty(prefix + "tradesHash", stableNpcTradesHash(normalized));
                changed = true;
                HomeCraftGuildMod.LOGGER.info("[HomeCraft NPC] Migrated legacy currency aliases to minecraft:emerald in trader {}", safe);
            }
        }
        if (changed) bumpGuildNpcRegistryRevision();
    }

    private static void upgradeDefaultTradeTemplates() {
        upgradeBuiltInTradeTemplate("trader_basic", defaultGuildTraderTrades());
        upgradeBuiltInTradeTemplate("trader_food", defaultFoodTrades());
        upgradeBuiltInTradeTemplate("trader_tools", defaultToolTrades());
        upgradeBuiltInTradeTemplate("trader_weapons", defaultWeaponTrades());
        upgradeBuiltInTradeTemplate("trader_armor", defaultArmorTrades());
        upgradeBuiltInTradeTemplate("trader_elite", defaultEliteTrades());
    }

    private static void upgradeBuiltInTradeTemplate(String npcKey, String replacement) {
        String safe = normalizeNpcKey(npcKey);
        String prefix = npcPrefix(safe);
        String versionKey = prefix + "tradesVersion";
        String currentVersion = P.getProperty(versionKey, "").trim();
        if (bool(prefix + "customTrades", false)) return;
        if (!currentVersion.startsWith(NPC_TRADE_TEMPLATE_VERSION)) {
            String trades = sanitizeNpcTrades(replacement);
            P.setProperty(prefix + "trades", trades);
            P.setProperty(prefix + "traderPresetId", safe);
            P.setProperty(prefix + "customTrades", "false");
            P.setProperty(prefix + "tradesHash", stableNpcTradesHash(trades));
            P.setProperty(versionKey, NPC_TRADE_TEMPLATE_VERSION + ":" + safe);
            HomeCraftGuildMod.LOGGER.info("[HomeCraft NPC] Updated built-in trader preset {} to {}", safe, NPC_TRADE_TEMPLATE_VERSION);
        }
    }

    private static void sanitizeText(String key, String fallback) {
        String raw = P.getProperty(key);
        if (raw == null || raw.isBlank() || looksCorrupted(raw)) {
            P.setProperty(key, fallback);
        }
    }

    private static void sanitizeIntExact(String key, int exact) {
        String raw = P.getProperty(key);
        try {
            int value = raw == null ? exact : Integer.parseInt(raw.trim());
            if (value != exact) P.setProperty(key, String.valueOf(exact));
        } catch (Exception ignored) {
            P.setProperty(key, String.valueOf(exact));
        }
    }

    private static void sanitizeIntMinimum(String key, int min) {
        String raw = P.getProperty(key);
        try {
            int value = raw == null ? min : Integer.parseInt(raw.trim());
            if (value < min) P.setProperty(key, String.valueOf(min));
        } catch (Exception ignored) {
            P.setProperty(key, String.valueOf(min));
        }
    }

    private static boolean looksCorrupted(String raw) {
        if (raw == null) return true;
        if (raw.indexOf('�') >= 0) return true;
        int controls = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) controls++;
        }
        return controls > 0;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static synchronized void save() { save(loadedPath); }

    private static synchronized void save(Path path) {
        if (path == null) return;
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            P.store(writer, "Home Craft Guild server/client mod config");
        } catch (Exception e) {
            HomeCraftGuildMod.LOGGER.warn("Could not write Home Craft guild config: {}", path, e);
        }
    }

    public static String apiBaseUrl() { return P.getProperty("apiBaseUrl", "http://127.0.0.1:3000").trim().replaceAll("/+$", ""); }
    public static String serverSecret() { return P.getProperty("serverSecret", "").trim(); }
    public static String guildNpcName() { return npcName("guild_master"); }
    public static boolean guildNpcEnabled() { return npcEnabled("guild_master"); }
    public static String guildNpcDimension() { return npcDimension("guild_master"); }
    public static double guildNpcX() { return npcX("guild_master"); }
    public static double guildNpcY() { return npcY("guild_master"); }
    public static double guildNpcZ() { return npcZ("guild_master"); }
    public static float guildNpcYaw() { return npcYaw("guild_master"); }

    public static synchronized void setGuildNpcPosition(String dimension, double x, double y, double z, float yaw) {
        setNpcPosition("guild_master", "system", "guild_master", guildNpcName(), true, dimension, x, y, z, yaw);
    }

    public static synchronized void clearGuildNpcPosition() {
        clearNpcPosition("guild_master");
    }

    public static long guildNpcRegistryRevision() { return longValue("guildNpcRegistryRevision", 0L, 0L, Long.MAX_VALUE); }
    public static boolean npcVillagerSoundsEnabled() { return bool("npcVillagerSoundsEnabled", true); }
    public static int npcAmbientSoundCooldownTicks() { return intValue("npcAmbientSoundCooldownTicks", 120, 20, 2400); }
    public static boolean npcInteractionSoundEnabled() { return bool("npcInteractionSoundEnabled", true); }

    public static synchronized void bumpGuildNpcRegistryRevision() {
        long next = guildNpcRegistryRevision() + 1L;
        P.setProperty("guildNpcRegistryRevision", String.valueOf(next));
    }

    public static List<String> npcKeys() {
        Set<String> out = new LinkedHashSet<>();
        out.add("guild_master");
        String raw = P.getProperty("guildNpcKeys", "guild_master");
        for (String part : raw.split(",")) {
            String key = normalizeNpcKey(part);
            if (!key.isBlank()) out.add(key);
        }
        out.add("trader_basic");
        out.add("trader_food");
        out.add("trader_tools");
        out.add("trader_weapons");
        out.add("trader_armor");
        out.add("trader_elite");
        return new ArrayList<>(out);
    }

    public static String normalizeNpcKey(String key) {
        if (key == null || key.isBlank()) return "";
        return key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private static String npcPrefix(String key) {
        String safe = normalizeNpcKey(key);
        return "guildNpc." + (safe.isBlank() ? "guild_master" : safe) + ".";
    }

    public static boolean npcEnabled(String key) {
        String safe = normalizeNpcKey(key);
        if ("guild_master".equals(safe)) return bool(npcPrefix(safe) + "enabled", bool("guildNpcEnabled", false));
        return bool(npcPrefix(safe) + "enabled", false);
    }

    public static String npcKind(String key) {
        String raw = P.getProperty(npcPrefix(key) + "kind", "guild_master".equals(normalizeNpcKey(key)) ? "system" : "trader").trim().toLowerCase(Locale.ROOT);
        return raw.equals("trader") ? "trader" : "system";
    }

    public static boolean npcSystem(String key) {
        return bool(npcPrefix(key) + "system", "system".equals(npcKind(key)));
    }

    public static String npcSkinId(String key) {
        boolean guildMaster = "guild_master".equals(normalizeNpcKey(key));
        String fallback = guildMaster ? normalizeAllowedNpcSkinId(P.getProperty("guildNpcSkin", "guild_master"), "guild_master") : "guild_registrar";
        String raw = P.getProperty(npcPrefix(key) + "skinId", fallback).trim();
        if (looksCorrupted(raw) || raw.isBlank()) return fallback;
        return normalizeAllowedNpcSkinId(raw, fallback);
    }

    public static List<String> npcAvailableSkins() {
        String raw = P.getProperty("guildNpcSkinIds", defaultNpcSkinIds());
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String id = normalizeNpcKey(part);
            if (!id.isBlank() && !out.contains(id)) out.add(id);
        }
        if (out.isEmpty()) out.add("guild_master");
        // Keep the configured skin list unique: one skin id maps to one texture.
        for (String builtIn : defaultNpcSkinIds().split(",")) {
            String id = normalizeNpcKey(builtIn);
            if (!id.isBlank() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    public static String normalizeAllowedNpcSkinId(String skinId, String fallback) {
        String safe = normalizeNpcKey(skinId);
        String fb = normalizeNpcKey(fallback);
        if (fb.isBlank()) fb = "guild_master";
        for (String allowed : npcAvailableSkins()) {
            if (allowed.equals(safe)) return safe;
        }
        return fb;
    }

    public static String npcName(String key) {
        String fallback = "guild_master".equals(normalizeNpcKey(key)) ? P.getProperty("guildNpcName", "Гільдійний Майстер") : "Гільдійний Торговець";
        String raw = P.getProperty(npcPrefix(key) + "name", fallback).trim();
        return looksCorrupted(raw) || raw.isBlank() ? fallback : raw;
    }

    public static String npcDimension(String key) {
        String fallback = "guild_master".equals(normalizeNpcKey(key)) ? P.getProperty("guildNpcDimension", "minecraft:overworld") : "minecraft:overworld";
        String raw = P.getProperty(npcPrefix(key) + "dimension", fallback).trim();
        return looksCorrupted(raw) || raw.isBlank() ? "minecraft:overworld" : raw.toLowerCase(Locale.ROOT);
    }

    public static double npcX(String key) { return doubleValue(npcPrefix(key) + "x", "guild_master".equals(normalizeNpcKey(key)) ? doubleValue("guildNpcX", 0.5D, -30_000_000.0D, 30_000_000.0D) : 0.5D, -30_000_000.0D, 30_000_000.0D); }
    public static double npcY(String key) { return doubleValue(npcPrefix(key) + "y", "guild_master".equals(normalizeNpcKey(key)) ? doubleValue("guildNpcY", 64.0D, -4096.0D, 4096.0D) : 64.0D, -4096.0D, 4096.0D); }
    public static double npcZ(String key) { return doubleValue(npcPrefix(key) + "z", "guild_master".equals(normalizeNpcKey(key)) ? doubleValue("guildNpcZ", 0.5D, -30_000_000.0D, 30_000_000.0D) : 0.5D, -30_000_000.0D, 30_000_000.0D); }
    public static float npcYaw(String key) { return (float) doubleValue(npcPrefix(key) + "yaw", "guild_master".equals(normalizeNpcKey(key)) ? doubleValue("guildNpcYaw", 0.0D, -3600.0D, 3600.0D) : 0.0D, -3600.0D, 3600.0D); }

    public static synchronized void setNpcPosition(String key, String kind, String skinId, String name, boolean system, String dimension, double x, double y, double z, float yaw) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank()) safe = "guild_master";
        ensureNpcKey(safe);
        String prefix = npcPrefix(safe);
        P.setProperty(prefix + "enabled", "true");
        P.setProperty(prefix + "kind", ("trader".equalsIgnoreCase(kind) && !system) ? "trader" : "system");
        P.setProperty(prefix + "system", String.valueOf(system));
        P.setProperty(prefix + "skinId", normalizeAllowedNpcSkinId(skinId, system ? "guild_master" : "guild_registrar"));
        P.setProperty(prefix + "name", name == null || name.isBlank() ? (system ? "Гільдійний Майстер" : "Гільдійний Торговець") : name.trim());
        P.setProperty(prefix + "dimension", dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension.trim().toLowerCase(Locale.ROOT));
        P.setProperty(prefix + "x", String.format(Locale.ROOT, "%.3f", x));
        P.setProperty(prefix + "y", String.format(Locale.ROOT, "%.3f", y));
        P.setProperty(prefix + "z", String.format(Locale.ROOT, "%.3f", z));
        P.setProperty(prefix + "yaw", String.format(Locale.ROOT, "%.2f", yaw));
        if ("guild_master".equals(safe)) {
            P.setProperty("guildNpcEnabled", "true");
            P.setProperty("guildNpcName", P.getProperty(prefix + "name"));
            P.setProperty("guildNpcDimension", P.getProperty(prefix + "dimension"));
            P.setProperty("guildNpcX", P.getProperty(prefix + "x"));
            P.setProperty("guildNpcY", P.getProperty(prefix + "y"));
            P.setProperty("guildNpcZ", P.getProperty(prefix + "z"));
            P.setProperty("guildNpcYaw", P.getProperty(prefix + "yaw"));
        }
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static synchronized void clearNpcPosition(String key) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank()) safe = "guild_master";
        P.setProperty(npcPrefix(safe) + "enabled", "false");
        if ("guild_master".equals(safe)) P.setProperty("guildNpcEnabled", "false");
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static String npcTrades(String key) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank()) safe = "guild_master";
        if (npcSystem(safe)) return "";
        String raw = P.getProperty(npcPrefix(safe) + "trades", "").trim();
        return sanitizeNpcTrades(raw);
    }

    public static synchronized void createNpcDefinition(String key, String kind, String skinId, String name, boolean system) {
        createNpcDefinition(key, kind, skinId, name, system, key);
    }

    public static synchronized void createNpcDefinition(String key, String kind, String skinId, String name, boolean system, String presetId) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank()) return;
        ensureNpcKey(safe);
        String prefix = npcPrefix(safe);
        boolean finalSystem = system || "system".equalsIgnoreCase(kind);
        P.setProperty(prefix + "kind", finalSystem ? "system" : "trader");
        P.setProperty(prefix + "system", String.valueOf(finalSystem));
        P.setProperty(prefix + "skinId", normalizeAllowedNpcSkinId(skinId, finalSystem ? "guild_master" : "guild_registrar"));
        P.setProperty(prefix + "name", safeNpcName(name, finalSystem ? "Гільдійний Майстер" : "Гільдійний Торговець"));
        P.setProperty(prefix + "enabled", P.getProperty(prefix + "enabled", "false"));
        P.setProperty(prefix + "dimension", P.getProperty(prefix + "dimension", "minecraft:overworld"));
        P.setProperty(prefix + "x", P.getProperty(prefix + "x", "0.5"));
        P.setProperty(prefix + "y", P.getProperty(prefix + "y", "64.0"));
        P.setProperty(prefix + "z", P.getProperty(prefix + "z", "0.5"));
        P.setProperty(prefix + "yaw", P.getProperty(prefix + "yaw", "0.0"));
        if (!finalSystem) {
            String preset = normalizeNpcKey(P.getProperty(prefix + "traderPresetId", presetId));
            if (preset.isBlank()) preset = normalizeNpcKey(presetId);
            if (preset.isBlank()) preset = "trader_basic";
            String currentTrades = sanitizeNpcTrades(P.getProperty(prefix + "trades", ""));
            String effectiveTrades = currentTrades.isBlank() ? defaultTradesForPreset(preset) : currentTrades;
            P.setProperty(prefix + "traderPresetId", preset);
            P.setProperty(prefix + "trades", effectiveTrades);
            P.setProperty(prefix + "customTrades", P.getProperty(prefix + "customTrades", String.valueOf(!effectiveTrades.equals(defaultTradesForPreset(preset)))));
            P.setProperty(prefix + "tradesHash", stableNpcTradesHash(effectiveTrades));
            P.setProperty(prefix + "tradesVersion", P.getProperty(prefix + "tradesVersion", NPC_TRADE_TEMPLATE_VERSION + ":" + preset));
        }
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static synchronized void setNpcMetadata(String key, String skinId, String name) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank()) safe = "guild_master";
        ensureNpcKey(safe);
        String prefix = npcPrefix(safe);
        boolean system = npcSystem(safe);
        P.setProperty(prefix + "skinId", normalizeAllowedNpcSkinId(skinId, system ? "guild_master" : "guild_registrar"));
        P.setProperty(prefix + "name", safeNpcName(name, system ? "Гільдійний Майстер" : "Гільдійний Торговець"));
        if (system) {
            P.setProperty(prefix + "kind", "system");
            P.setProperty(prefix + "system", "true");
        } else {
            P.setProperty(prefix + "kind", "trader");
            P.setProperty(prefix + "system", "false");
        }
        if ("guild_master".equals(safe)) P.setProperty("guildNpcName", P.getProperty(prefix + "name"));
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static synchronized void appendNpcTrade(String key, String tradeSpec) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe)) return;
        ensureNpcKey(safe);
        String prefix = npcPrefix(safe);
        String one = sanitizeNpcTradeSpec(tradeSpec);
        if (one.isBlank()) return;
        String current = sanitizeNpcTrades(P.getProperty(prefix + "trades", ""));
        String trades = current.isBlank() ? one : current + "~" + one;
        P.setProperty(prefix + "trades", trades);
        P.setProperty(prefix + "customTrades", "true");
        P.setProperty(prefix + "tradesHash", stableNpcTradesHash(trades));
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static synchronized void setNpcTrade(String key, int index, String tradeSpec) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe) || index < 0) return;
        ensureNpcKey(safe);
        String one = sanitizeNpcTradeSpec(tradeSpec);
        if (one.isBlank()) return;
        String[] parts = sanitizeNpcTrades(P.getProperty(npcPrefix(safe) + "trades", "")).split("~", -1);
        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            String part = sanitizeNpcTradeSpec(parts[i]);
            if (part.isBlank()) continue;
            if (i == index) {
                out.add(one);
                changed = true;
            } else {
                out.add(part);
            }
            if (out.size() >= 64) break;
        }
        if (!changed && index >= out.size() && out.size() < 64) {
            out.add(one);
            changed = true;
        }
        if (!changed) return;
        String trades = String.join("~", out);
        P.setProperty(npcPrefix(safe) + "trades", trades);
        P.setProperty(npcPrefix(safe) + "customTrades", "true");
        P.setProperty(npcPrefix(safe) + "tradesHash", stableNpcTradesHash(trades));
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static synchronized void removeNpcTrade(String key, int index) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe)) return;
        String[] parts = sanitizeNpcTrades(P.getProperty(npcPrefix(safe) + "trades", "")).split("~", -1);
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String part = sanitizeNpcTradeSpec(parts[i]);
            if (part.isBlank() || i == index) continue;
            kept.add(part);
        }
        String trades = String.join("~", kept);
        P.setProperty(npcPrefix(safe) + "trades", trades);
        P.setProperty(npcPrefix(safe) + "customTrades", "true");
        P.setProperty(npcPrefix(safe) + "tradesHash", stableNpcTradesHash(trades));
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static synchronized void setNpcTrades(String key, String trades) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe)) return;
        ensureNpcKey(safe);
        String sanitized = sanitizeNpcTrades(trades);
        P.setProperty(npcPrefix(safe) + "trades", sanitized);
        P.setProperty(npcPrefix(safe) + "customTrades", "true");
        P.setProperty(npcPrefix(safe) + "tradesHash", stableNpcTradesHash(sanitized));
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static String defaultTradesForPreset(String presetKey) {
        return sanitizeNpcTrades(switch (normalizeNpcKey(presetKey)) {
            case "trader_food" -> defaultFoodTrades();
            case "trader_tools" -> defaultToolTrades();
            case "trader_weapons" -> defaultWeaponTrades();
            case "trader_armor" -> defaultArmorTrades();
            case "trader_elite" -> defaultEliteTrades();
            case "trader_basic" -> defaultGuildTraderTrades();
            default -> defaultGuildTraderTrades();
        });
    }

    public static String npcTraderPresetId(String key) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe)) return "";
        String preset = normalizeNpcKey(P.getProperty(npcPrefix(safe) + "traderPresetId", safe));
        return preset.isBlank() ? "trader_basic" : preset;
    }

    public static boolean npcCustomTrades(String key) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe)) return false;
        return bool(npcPrefix(safe) + "customTrades", false);
    }

    public static String npcTradesHash(String key) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe)) return "0";
        String trades = sanitizeNpcTrades(P.getProperty(npcPrefix(safe) + "trades", ""));
        String hash = P.getProperty(npcPrefix(safe) + "tradesHash", "");
        if (hash == null || hash.isBlank()) return stableNpcTradesHash(trades);
        return hash.trim();
    }

    public static synchronized void setNpcTraderPresetId(String key, String presetId) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe)) return;
        ensureNpcKey(safe);
        String preset = normalizeNpcKey(presetId);
        if (preset.isBlank()) preset = "trader_basic";
        P.setProperty(npcPrefix(safe) + "traderPresetId", preset);
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static int npcTradeCount(String key) {
        String raw = npcTrades(key);
        if (raw == null || raw.isBlank()) return 0;
        int count = 0;
        for (String part : raw.split("~")) if (!sanitizeNpcTradeSpec(part).isBlank()) count++;
        return count;
    }

    public static synchronized void duplicateNpcTrade(String key, int index) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe) || index < 0) return;
        String[] parts = sanitizeNpcTrades(P.getProperty(npcPrefix(safe) + "trades", "")).split("~", -1);
        List<String> out = new ArrayList<>();
        String duplicate = "";
        for (int i = 0; i < parts.length; i++) {
            String part = sanitizeNpcTradeSpec(parts[i]);
            if (part.isBlank()) continue;
            out.add(part);
            if (i == index) duplicate = part;
            if (i == index && !duplicate.isBlank() && out.size() < 64) out.add(duplicate);
            if (out.size() >= 64) break;
        }
        if (duplicate.isBlank()) return;
        String trades = String.join("~", out);
        P.setProperty(npcPrefix(safe) + "trades", trades);
        P.setProperty(npcPrefix(safe) + "customTrades", "true");
        P.setProperty(npcPrefix(safe) + "tradesHash", stableNpcTradesHash(trades));
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static synchronized void moveNpcTrade(String key, int index, int delta) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe) || index < 0 || delta == 0) return;
        String[] parts = sanitizeNpcTrades(P.getProperty(npcPrefix(safe) + "trades", "")).split("~", -1);
        List<String> out = new ArrayList<>();
        for (String raw : parts) {
            String part = sanitizeNpcTradeSpec(raw);
            if (!part.isBlank()) out.add(part);
        }
        int target = index + delta;
        if (index >= out.size() || target < 0 || target >= out.size()) return;
        String tmp = out.get(index);
        out.set(index, out.get(target));
        out.set(target, tmp);
        String trades = String.join("~", out);
        P.setProperty(npcPrefix(safe) + "trades", trades);
        P.setProperty(npcPrefix(safe) + "customTrades", "true");
        P.setProperty(npcPrefix(safe) + "tradesHash", stableNpcTradesHash(trades));
        bumpGuildNpcRegistryRevision();
        save();
    }

    public static synchronized boolean applyNpcTradePreset(String key, String presetKey) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank() || npcSystem(safe)) return false;
        ensureNpcKey(safe);
        String preset = normalizeNpcKey(presetKey);
        if (preset.isBlank()) preset = npcTraderPresetId(safe);
        if (preset.isBlank()) preset = safe;
        String trades = defaultTradesForPreset(preset);
        if (trades.isBlank()) return false;
        P.setProperty(npcPrefix(safe) + "kind", "trader");
        P.setProperty(npcPrefix(safe) + "system", "false");
        P.setProperty(npcPrefix(safe) + "traderPresetId", preset);
        P.setProperty(npcPrefix(safe) + "customTrades", "false");
        P.setProperty(npcPrefix(safe) + "trades", trades);
        P.setProperty(npcPrefix(safe) + "tradesHash", stableNpcTradesHash(trades));
        P.setProperty(npcPrefix(safe) + "tradesVersion", NPC_TRADE_TEMPLATE_VERSION + ":" + preset);
        bumpGuildNpcRegistryRevision();
        save();
        return true;
    }

    private static String stableNpcTradesHash(String trades) {
        String value = sanitizeNpcTrades(trades);
        return Integer.toHexString(value.hashCode());
    }

    private static String safeNpcName(String value, String fallback) {
        String raw = value == null ? "" : value.trim();
        raw = raw.replace('|', ' ').replace(';', ' ').replace('~', ' ');
        if (looksCorrupted(raw) || raw.isBlank()) return fallback;
        if (raw.length() > 48) raw = raw.substring(0, 48);
        return raw;
    }

    private static String sanitizeNpcTrades(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> out = new ArrayList<>();
        for (String part : raw.split("~")) {
            String trade = sanitizeNpcTradeSpec(part);
            if (!trade.isBlank()) out.add(trade);
            if (out.size() >= 64) break;
        }
        return String.join("~", out);
    }

    private static String sanitizeNpcTradeSpec(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Map<String, String> values = new HashMap<>();
        for (String part : raw.replace(';', ',').split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = part.substring(eq + 1).trim();
            values.put(k, v);
        }
        String buy = sanitizeItemId(values.getOrDefault("buy", values.getOrDefault("buyitem", "minecraft:emerald")), "minecraft:emerald");
        String buy2 = sanitizeItemId(values.getOrDefault("buy2", values.getOrDefault("secondbuy", values.getOrDefault("secondbuyitem", ""))), "");
        String sell = sanitizeItemId(values.getOrDefault("sell", values.getOrDefault("sellitem", "minecraft:bread")), "minecraft:bread");
        int buyCount = clampInt(values.get("buycount"), 1, 1, 64);
        int buy2Count = buy2.isBlank() ? 0 : clampInt(values.getOrDefault("buy2count", values.getOrDefault("secondbuycount", "1")), 1, 1, 64);
        int sellCount = clampInt(values.get("sellcount"), 1, 1, 64);
        int max = clampInt(values.getOrDefault("max", values.get("maxuses")), 16, 1, 9999);
        int xp = clampInt(values.get("xp"), 0, 0, 9999);
        int limit = clampInt(values.getOrDefault("limit", values.get("perplayer")), 0, 0, 9999);
        String role = sanitizeNpcRole(values.getOrDefault("role", values.getOrDefault("rank", "any")));
        String name = safeTradeText(values.getOrDefault("name", ""), 48);
        String ench = sanitizeEnchantSpec(values.getOrDefault("ench", values.getOrDefault("enchant", "")));
        if (GuildCurrency.isVanillaEmeraldId(sell)) {
            name = "";
            ench = "";
        }
        StringBuilder out = new StringBuilder(160);
        out.append("buy=").append(buy)
                .append(",buyCount=").append(buyCount);
        if (!buy2.isBlank()) out.append(",buy2=").append(buy2).append(",buy2Count=").append(buy2Count);
        out.append(",sell=").append(sell)
                .append(",sellCount=").append(sellCount)
                .append(",max=").append(max)
                .append(",xp=").append(xp)
                .append(",role=").append(role)
                .append(",limit=").append(limit);
        if (!name.isBlank()) out.append(",name=").append(name);
        if (!ench.isBlank()) out.append(",ench=").append(ench);
        return out.toString();
    }

    private static String sanitizeNpcRole(String raw) {
        String value = raw == null ? "any" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (value.isBlank()) return "any";
        value = value.replaceAll("[^a-z0-9_]+", "_");
        return switch (value) {
            case "any", "all", "member", "guild_member", "guildmaster", "guild_master", "gm", "builder", "quartermaster", "warrior", "farmer" -> value;
            default -> "any";
        };
    }

    private static String safeTradeText(String raw, int maxLen) {
        String value = raw == null ? "" : raw.trim().replace('|', ' ').replace(';', ' ').replace('~', ' ').replace(',', ' ');
        if (looksCorrupted(value)) return "";
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    private static String sanitizeEnchantSpec(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> out = new ArrayList<>();
        for (String part : raw.replace(';', '+').split("\\+")) {
            String v = part.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
            String[] p = v.split(":");
            if (p.length < 2) continue;
            String name = p[0].replaceAll("[^a-z0-9_]+", "_");
            int lvl = clampInt(p[p.length - 1], 1, 1, 10);
            if (name.matches("[a-z0-9_/.-]+")) out.add(name + ":" + lvl);
            if (out.size() >= 8) break;
        }
        return String.join("+", out);
    }

    private static String sanitizeItemId(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!value.contains(":")) value = "minecraft:" + value;
        value = GuildCurrency.normalizeItemId(value);
        if (value.isBlank() || !value.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) return fallback;
        return value;
    }

    private static int clampInt(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(String.valueOf(raw == null ? "" : raw).trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static synchronized void ensureNpcKey(String key) {
        String safe = normalizeNpcKey(key);
        if (safe.isBlank()) return;
        Set<String> keys = new LinkedHashSet<>(npcKeys());
        if (keys.add(safe)) {
            P.setProperty("guildNpcKeys", String.join(",", keys));
        }
    }

    public static int guildCreateEmeraldCost() { return intValue("guildCreateEmeraldCost", 3, 0, 64); }
    public static int maxGuildMembers() { return intValue("maxGuildMembers", 20, 1, 100); }
    public static int personalBannerLimit() { return intValue("personalBannerLimit", 2, 0, 64); }
    public static int personalClaimSize() { return intValue("personalClaimSize", 48, 8, 512); }
    public static int guildBannerLimit() { return intValue("guildBannerLimit", 4, 0, 64); }
    public static int guildClaimSize() { return intValue("guildClaimSize", 64, 8, 512); }
    public static boolean creativeBypass() { return bool("creativeBypass", true); }
    public static String homecraftIntegrationMode() {
        String raw = P.getProperty("homecraftIntegrationMode", "auto").trim().toLowerCase(Locale.ROOT);
        if (raw.equals("standalone") || raw.equals("force")) return raw;
        return "auto";
    }
    public static boolean spawnBoundaryIntegrationEnabled() { return bool("spawnBoundaryIntegrationEnabled", true); }
    public static boolean territorySyncEnabled() { return bool("territorySyncEnabled", false); }
    public static boolean territorySyncRequiresHomeCraftMap() { return bool("territorySyncRequiresHomeCraftMap", true); }
    public static int territorySyncIntervalSeconds() { return intValue("territorySyncIntervalSeconds", 30, 30, 600); }
    public static int maxGuildGolems() { return intValue("maxGuildGolems", 5, 1, 64); }
    public static int golemProtectRange() { return intValue("golemProtectRange", 24, 4, 96); }
    public static int golemAiIntervalTicks() { return intValue("golemAiIntervalTicks", 20, 1, 200); }
    public static boolean guildGolemAiEnabled() { return bool("guildGolemAiEnabled", true); }
    public static int guildGolemMaxPerGuild() { return intValue("guildGolemMaxPerGuild", 5, 0, 64); }
    public static double guildGolemHealthMultiplier() { return 4.0D; }
    public static double guildGolemDamageMultiplier() { return 1.5D; }
    public static int guildGolemChaseOutsideTerritoryBlocks() { return intValue("guildGolemChaseOutsideTerritoryBlocks", 16, 0, 128); }
    public static int guildGolemTargetScanIntervalTicks() { return intValue("guildGolemTargetScanIntervalTicks", 20, 1, 400); }
    public static int guildGolemPathRecalcCooldownTicks() { return intValue("guildGolemPathRecalcCooldownTicks", 20, 1, 400); }
    public static int guildGolemTargetStickinessTicks() { return intValue("guildGolemTargetStickinessTicks", 80, 1, 1200); }
    public static boolean guildEliteGolemEnabled() { return bool("guildEliteGolemEnabled", true); }
    public static int guildEliteGolemMaxPerGuild() { return intValue("guildEliteGolemMaxPerGuild", 1, 0, 8); }
    public static double guildEliteGolemHealthMultiplier() { return 6.0D; }
    public static double guildEliteGolemDamageMultiplier() { return 1.75D; }
    public static int guildEliteGolemChaseOutsideTerritoryBlocks() { return intValue("guildEliteGolemChaseOutsideTerritoryBlocks", 24, 0, 192); }
    public static int guildEliteGolemTargetScanIntervalTicks() { return intValue("guildEliteGolemTargetScanIntervalTicks", 20, 1, 400); }
    public static int guildEliteGolemPathRecalcCooldownTicks() { return intValue("guildEliteGolemPathRecalcCooldownTicks", 30, 1, 600); }
    public static int guildEliteGolemTargetStickinessTicks() { return intValue("guildEliteGolemTargetStickinessTicks", 120, 1, 1600); }
    public static boolean guildEliteGolemProtectGuildMasterPriority() { return bool("guildEliteGolemProtectGuildMasterPriority", true); }
    public static boolean guildEliteGolemIgnoreWeakTargetsWhenNormalGolemsAvailable() { return bool("guildEliteGolemIgnoreWeakTargetsWhenNormalGolemsAvailable", true); }
    public static boolean guildEliteGolemStrategicPatrolEnabled() { return bool("guildEliteGolemStrategicPatrolEnabled", true); }
    public static boolean guildGolemPatrolEnabled() { return bool("guildGolemPatrolEnabled", true); }
    public static boolean guildGolemNightPatrolEnabled() { return bool("guildGolemNightPatrolEnabled", true); }
    public static boolean guildGolemNightPatrolNearMembers() { return bool("guildGolemNightPatrolNearMembers", true); }
    public static int guildGolemNightMemberSupportRadiusBlocks() { return intValue("guildGolemNightMemberSupportRadiusBlocks", 14, 4, 64); }
    public static boolean guildGolemNightIgnoreUndergroundMembers() { return bool("guildGolemNightIgnoreUndergroundMembers", true); }
    public static boolean guildGolemSquadCoordinatorEnabled() { return bool("guildGolemSquadCoordinatorEnabled", true); }
    public static int guildGolemSquadUpdateIntervalTicks() { return intValue("guildGolemSquadUpdateIntervalTicks", 40, 1, 1200); }
    public static int guildGolemPatrolReplanIntervalTicks() { return intValue("guildGolemPatrolReplanIntervalTicks", 80, 1, 2400); }
    public static int guildGolemLowPriorityIntervalTicks() { return intValue("guildGolemLowPriorityIntervalTicks", 100, 1, 2400); }
    public static int guildGolemMaxThreatScanRadius() { return intValue("guildGolemMaxThreatScanRadius", 32, 4, 128); }
    public static int guildEliteGolemMaxThreatScanRadius() { return intValue("guildEliteGolemMaxThreatScanRadius", 40, 4, 160); }
    public static int guildGolemMaxPathFailuresBeforeRecover() { return intValue("guildGolemMaxPathFailuresBeforeRecover", 3, 1, 64); }
    public static int guildGolemMaxPathFailuresBeforeTeleportRecover() { return intValue("guildGolemMaxPathFailuresBeforeTeleportRecover", 6, 1, 128); }
    public static boolean guildGolemHealingEnabled() { return bool("guildGolemHealingEnabled", true); }
    public static int guildGolemHealingStartHealthPercent() { return intValue("guildGolemHealingStartHealthPercent", 50, 1, 99); }
    public static int guildGolemHealingStopHealthPercent() { return intValue("guildGolemHealingStopHealthPercent", 98, 2, 100); }
    public static boolean guildGolemDayHealingRequiresFullHealth() { return bool("guildGolemDayHealingRequiresFullHealth", true); }
    public static int guildGolemHealingCheckIntervalTicks() { return intValue("guildGolemHealingCheckIntervalTicks", 40, 5, 1200); }
    public static int guildGolemHealingRadiusBlocks() { return intValue("guildGolemHealingRadiusBlocks", 5, 2, 24); }
    public static double guildGolemHealingMinPerSlice() { return doubleValue("guildGolemHealingMinPerSlice", 1.0D, 0.1D, 100.0D); }
    public static double guildGolemHealingMaxHealthPercentPerSlice() { return doubleValue("guildGolemHealingMaxHealthPercentPerSlice", 3.5D, 0.1D, 50.0D); }
    public static int guildGolemHealingApplyIntervalTicks() { return intValue("guildGolemHealingApplyIntervalTicks", 20, 5, 400); }
    public static int guildGolemHealingParticleIntervalTicks() { return intValue("guildGolemHealingParticleIntervalTicks", 16, 4, 200); }
    public static boolean guildGolemHealingRequireSafeTotemPoint() { return bool("guildGolemHealingRequireSafeTotemPoint", true); }
    public static boolean guildGolemHealingCancelUnsafeFallback() { return bool("guildGolemHealingCancelUnsafeFallback", true); }
    public static boolean guildGolemRespawnEnabled() { return bool("guildGolemRespawnEnabled", true); }
    public static boolean guildGolemRespawnCostEnabled() { return bool("guildGolemRespawnCostEnabled", false); }
    public static boolean guildEliteGolemRespawnEnabled() { return bool("guildEliteGolemRespawnEnabled", true); }
    public static boolean guildEliteGolemRespawnCostEnabled() { return bool("guildEliteGolemRespawnCostEnabled", true); }
    public static boolean guildGolemLagLevel2Throttle() { return bool("guildGolemLagLevel2Throttle", true); }
    public static boolean guildGolemLagLevel3CriticalOnly() { return bool("guildGolemLagLevel3CriticalOnly", true); }
    public static boolean guildGolemLagLevel3DisableCosmetics() { return bool("guildGolemLagLevel3DisableCosmetics", true); }
    public static int guildGolemLagLevel3TargetScanMultiplier() { return intValue("guildGolemLagLevel3TargetScanMultiplier", 3, 1, 20); }
    public static int guildGolemLagLevel3PathRecalcMultiplier() { return intValue("guildGolemLagLevel3PathRecalcMultiplier", 3, 1, 20); }
    public static boolean guildGolemDebug() { return bool("guildGolemDebug", false); }
    public static boolean guildGolemDebugStateChanges() { return bool("guildGolemDebugStateChanges", false); }
    public static boolean guildGolemDebugPathFailures() { return bool("guildGolemDebugPathFailures", false); }
    public static boolean guildGolemDebugTargetSelection() { return bool("guildGolemDebugTargetSelection", false); }
    public static boolean guildGolemRouteFallbackEnabled() { return bool("guildGolemRouteFallbackEnabled", true); }
    public static boolean guildGolemRouteAvoidWater() { return bool("guildGolemRouteAvoidWater", true); }
    public static boolean guildGolemRouteAvoidDeepDrops() { return bool("guildGolemRouteAvoidDeepDrops", true); }
    public static int guildGolemRouteMaxStartsPerTick() { return intValue("guildGolemRouteMaxStartsPerTick", 4, 1, 32); }
    public static int guildGolemRouteMaxSafeChecks() { return intValue("guildGolemRouteMaxSafeChecks", 40, 8, 256); }
    public static boolean guildGolemRouteAsyncEnabled() { return bool("guildGolemRouteAsyncEnabled", true); }
    public static int guildGolemRouteCacheTtlTicks() { return intValue("guildGolemRouteCacheTtlTicks", 600, 40, 12000); }
    public static boolean guildGolemRouteDebug() { return bool("guildGolemRouteDebug", false); }
    public static boolean guildBlockMonsterSpawns() { return bool("guildBlockMonsterSpawns", true); }
    public static boolean guildBedsEnabled() { return bool("guildBedsEnabled", true); }
    public static boolean guildBedsRequireGuildTerritory() { return bool("guildBedsRequireGuildTerritory", true); }
    public static boolean guildBedsOneClaimedBedPerMember() { return bool("guildBedsOneClaimedBedPerMember", true); }
    public static boolean guildBedsOnePlacedBedPerMember() { return bool("guildBedsOnePlacedBedPerMember", true); }
    public static boolean guildBedsGuildMasterUnlimitedPlacement() { return bool("guildBedsGuildMasterUnlimitedPlacement", true); }
    public static boolean guildBedsBuilderUnlimitedPlacement() { return bool("guildBedsBuilderUnlimitedPlacement", true); }
    public static boolean guildBedsAllowReclaim() { return bool("guildBedsAllowReclaim", true); }
    public static boolean guildBedsShowOwnerLabel() { return bool("guildBedsShowOwnerLabel", true); }
    public static int guildBedsOwnerLabelMaxDistance() { return intValue("guildBedsOwnerLabelMaxDistance", 32, 4, 128); }
    public static double guildBedsOwnerLabelYOffset() { return doubleValue("guildBedsOwnerLabelYOffset", 1.35D, 0.25D, 4.0D); }
    public static int guildBedsLabelCellSize() { return intValue("guildBedsLabelCellSize", 64, 16, 256); }
    public static boolean guildBedsHideExactCoordsForNonMembers() { return bool("guildBedsHideExactCoordsForNonMembers", true); }
    public static boolean guildBedsAllowGuildMasterFreeBed() { return bool("guildBedsAllowGuildMasterFreeBed", true); }
    public static boolean guildBedsAllowBuilderFreeBed() { return bool("guildBedsAllowBuilderFreeBed", true); }
    public static boolean guildBedsValidateOnServerStart() { return bool("guildBedsValidateOnServerStart", true); }
    public static boolean guildBedsDebug() { return bool("guildBedsDebug", false); }
    public static boolean guildTotemCleanupEnabled() { return bool("guildTotemCleanupEnabled", true); }
    public static boolean guildTotemCleanupOnServerStart() { return bool("guildTotemCleanupOnServerStart", true); }
    public static int guildTotemCleanupIntervalSeconds() { return intValue("guildTotemCleanupIntervalSeconds", 120, 30, 3600); }
    public static int guildTotemCleanupMaxChecksPerPass() { return intValue("guildTotemCleanupMaxChecksPerPass", 32, 1, 512); }
    public static boolean guildTalentsEnabled() { return bool("guildTalentsEnabled", true); }
    public static int guildTalentMaxGuildLevel() { return intValue("guildTalentMaxGuildLevel", 7, 1, 7); }
    public static int guildTalentResetCostEmeralds() { return intValue("guildTalentResetCostEmeralds", 3, 0, 64); }
    public static boolean guildTalentDebug() { return bool("guildTalentDebug", false); }
    public static int guildTalentLevelPoints(int level) {
        int safe = Math.max(1, Math.min(7, level));
        int fallback = (safe == 5 || safe == 7) ? 2 : 1;
        return intValue("guildTalentLevel" + safe + "Points", fallback, 0, 16);
    }
    public static int guildBaseXpBonusPercent() { return intValue("guildBaseXpBonusPercent", 5, 0, 100); }
    public static int guildBasePotionDurationBonusPercent() { return intValue("guildBasePotionDurationBonusPercent", 10, 0, 200); }
    public static int guildBaseWeaponDamageBonusPercent() { return intValue("guildBaseWeaponDamageBonusPercent", 5, 0, 100); }
    public static int guildBaseArmorBonusPercent() { return intValue("guildBaseArmorBonusPercent", 5, 0, 100); }
    public static int guildMemberMaxSpeedBonusPercent() { return intValue("guildMemberMaxSpeedBonusPercent", 25, 0, 100); }
    public static double guildMemberMaxJumpBonus() { return doubleValue("guildMemberMaxJumpBonus", 0.15D, 0.0D, 2.0D); }
    public static int guildGolemTalentMaxHealthBonusPercent() { return intValue("guildGolemTalentMaxHealthBonusPercent", 25, 0, 200); }
    public static int guildGolemTalentMaxDamageBonusPercent() { return intValue("guildGolemTalentMaxDamageBonusPercent", 15, 0, 200); }
    public static int guildGolemTalentMaxSpeedBonusPercent() { return intValue("guildGolemTalentMaxSpeedBonusPercent", 15, 0, 100); }
    public static int guildGolemTalentMaxHealingSpeedBonusPercent() { return intValue("guildGolemTalentMaxHealingSpeedBonusPercent", 30, 0, 200); }
    public static boolean debug() { return bool("debug", false); }

    private static long longValue(String key, long fallback, long min, long max) {
        try { return Math.max(min, Math.min(max, Long.parseLong(P.getProperty(key, String.valueOf(fallback)).trim()))); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(String key, boolean fallback) {
        String raw = P.getProperty(key, String.valueOf(fallback)).trim().toLowerCase(java.util.Locale.ROOT);
        return raw.equals("true") || raw.equals("1") || raw.equals("yes") || raw.equals("on");
    }

    private static int intValue(String key, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(P.getProperty(key, String.valueOf(fallback)).trim()))); }
        catch (Exception ignored) { return fallback; }
    }

    private static double doubleValue(String key, double fallback, double min, double max) {
        try { return Math.max(min, Math.min(max, Double.parseDouble(P.getProperty(key, String.valueOf(fallback)).trim()))); }
        catch (Exception ignored) { return fallback; }
    }
}
