package ua.homecraft.guild.server;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ua.homecraft.guild.HomeCraftGuildMod;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for HomeCraft Guild currency.
 * NPCs must use the vanilla, unmodified Minecraft emerald. Older custom/named
 * guild currencies are migrated to plain minecraft:emerald in player inventories
 * and in NPC trade configs.
 */
public final class GuildCurrency {
    public static final String VANILLA_EMERALD_ID = "minecraft:emerald";

    private static final Set<String> LEGACY_CURRENCY_IDS = Set.of(
            "homecraftguild:emerald",
            "homecraftguild:guild_emerald",
            "homecraftguild:guild_smaragd",
            "homecraftguild:smaragd",
            "homecraftguild:currency",
            "homecraftguild:guild_currency",
            "homecraftguild:coin",
            "homecraftguild:guild_coin",
            "homecraftguild:emerald_coin",
            "homecraftguild:trade_coin",
            "homecraftguild:merchant_coin",
            "homecraft:emerald",
            "homecraft:guild_emerald",
            "homecraft:guild_coin",
            "homecraft:currency"
    );

    private GuildCurrency() {}

    public static String normalizeItemId(String raw) {
        String id = normalizeRawItemId(raw);
        if (id.isBlank()) return "";
        return isCurrencyAliasId(id) ? VANILLA_EMERALD_ID : id;
    }

    public static boolean isCurrencyAliasId(String raw) {
        String id = normalizeRawItemId(raw);
        return VANILLA_EMERALD_ID.equals(id) || LEGACY_CURRENCY_IDS.contains(id);
    }

    public static boolean isLegacyCurrencyId(String raw) {
        return LEGACY_CURRENCY_IDS.contains(normalizeRawItemId(raw));
    }

    public static boolean isVanillaEmeraldId(String raw) {
        return VANILLA_EMERALD_ID.equals(normalizeRawItemId(raw));
    }

    public static boolean isCurrencyItemId(String raw) {
        return isCurrencyAliasId(raw);
    }

    public static ItemStack vanillaEmeraldStack(int count) {
        return new ItemStack(Items.EMERALD, Math.max(1, Math.min(64, count)));
    }

    public static boolean isVanillaEmeraldStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.EMERALD);
    }

    private static String normalizeRawItemId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (id.isBlank()) return "";
        if (!id.contains(":")) id = "minecraft:" + id;
        return id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") ? id : "";
    }

    public static int migratePlayerInventory(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) return 0;
        int converted = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            if (!isLegacyCurrencyStack(stack) && !isMutatedVanillaEmerald(stack)) continue;
            int count = stack.getCount();
            player.getInventory().setItem(i, vanillaEmeraldStack(count));
            converted += count;
        }
        if (converted > 0) {
            try { player.getInventory().setChanged(); } catch (Exception ignored) {}
        }
        return converted;
    }

    public static void migrateAndNotify(ServerPlayer player, String reason) {
        int converted = migratePlayerInventory(player);
        if (converted <= 0 || player == null) return;
        player.displayClientMessage(Component.literal("Home Craft Guilds: стару валюту замінено на оригінальні смарагди ×" + converted + "."), true);
        if (HomeCraftGuildConfig.debug()) {
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: migrated {} legacy currency item(s) to vanilla emerald for {} reason={}", converted, player.getName().getString(), reason);
        }
    }

    private static boolean isLegacyCurrencyStack(ItemStack stack) {
        try {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && isLegacyCurrencyId(id.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMutatedVanillaEmerald(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.EMERALD)) return false;
        try {
            // Currency must stay the original vanilla item. Any custom-named emerald
            // produced by older NPC buyback trades is normalized back to a plain emerald.
            Component customName = stack.get(DataComponents.CUSTOM_NAME);
            return customName != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
