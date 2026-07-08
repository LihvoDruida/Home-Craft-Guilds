package ua.homecraft.guild.server;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.item.HomeCraftGuildItems;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/** Server-authoritative villager-like trading for HomeCraft NPCs. */
public final class GuildNpcTradeService {
    /**
     * Runtime fallback only. Persistent limits are stored in the player's server-side
     * persistent NBT so they survive dedicated-server restarts.
     */
    private static final Map<String, Integer> PLAYER_PURCHASES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> WARNED_INVALID_IDS = ConcurrentHashMap.newKeySet();
    private static final long SERVER_PURCHASE_SESSION = System.currentTimeMillis();
    private static final String PURCHASE_NBT_PREFIX = "homecraftguildNpcTradeLimit_";
    private static final Identifier NPC_TRADE_EXTRA_DAMAGE_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "npc_trade_extra_damage");

    private GuildNpcTradeService() {}


    public record ValidationResult(boolean success, String error, String normalizedSpec) {
        public static ValidationResult ok(String normalizedSpec) { return new ValidationResult(true, "", normalizedSpec == null ? "" : normalizedSpec); }
        public static ValidationResult error(String message) { return new ValidationResult(false, message == null ? "Невалідний товар" : message, ""); }
    }

    public static ValidationResult validateAndNormalizeTrade(ServerPlayer player, String raw) {
        Map<String, String> m = parseMap(raw);
        String buy = normalizeItemId(m.getOrDefault("buy", m.getOrDefault("buyitem", "minecraft:emerald")));
        String buy2 = normalizeItemId(m.getOrDefault("buy2", m.getOrDefault("secondbuy", m.getOrDefault("secondbuyitem", ""))));
        String sell = normalizeItemId(m.getOrDefault("sell", m.getOrDefault("sellitem", "minecraft:bread")));
        Item buyItem = itemById(buy);
        Item buy2Item = buy2.isBlank() ? null : itemById(buy2);
        Item sellItem = itemById(sell);
        if (buyItem == null || buyItem == Items.AIR) return invalidId("item", buy, "Невалідний buy item id: " + buy);
        if (!buy2.isBlank() && (buy2Item == null || buy2Item == Items.AIR)) return invalidId("item", buy2, "Невалідний second buy item id: " + buy2);
        if (sellItem == null || sellItem == Items.AIR) return invalidId("item", sell, "Невалідний sell item id: " + sell);
        int buyCount = num(m.get("buycount"), 1, 1, 64);
        int buy2Count = buy2.isBlank() ? 0 : num(m.getOrDefault("buy2count", m.getOrDefault("secondbuycount", "1")), 1, 1, 64);
        int sellCount = num(m.get("sellcount"), 1, 1, 64);
        int max = num(m.getOrDefault("max", m.get("maxuses")), 16, 1, 9999);
        int xp = num(m.get("xp"), 0, 0, 9999);
        int limit = num(m.getOrDefault("limit", m.get("perplayer")), 0, 0, 9999);
        String role = normalizeRole(m.getOrDefault("role", m.getOrDefault("rank", "any")));
        if (role.isBlank()) return ValidationResult.error("Невалідна роль доступу");
        String name = safeText(m.getOrDefault("name", ""), 48);
        String ench = normalizeEnchants(player, m.getOrDefault("ench", m.getOrDefault("enchant", "")), sell);
        if (ench == null) return ValidationResult.error("Невалідний enchant id або рівень зачарування");
        String reset = normalizeResetPolicy(m.getOrDefault("reset", m.getOrDefault("resetpolicy", "never")));
        double extraDamage = decimal(m.getOrDefault("damage", m.getOrDefault("extradamage", m.getOrDefault("bonusdamage", "0"))), 0.0D, 0.0D, 16.0D);
        if (GuildCurrency.isVanillaEmeraldId(sell)) {
            name = "";
            ench = "";
            extraDamage = 0.0D;
        }

        StringBuilder out = new StringBuilder(180);
        out.append("buy=").append(buy).append(",buyCount=").append(buyCount);
        if (!buy2.isBlank()) out.append(",buy2=").append(buy2).append(",buy2Count=").append(buy2Count);
        out.append(",sell=").append(sell)
                .append(",sellCount=").append(sellCount)
                .append(",max=").append(max)
                .append(",xp=").append(xp)
                .append(",role=").append(role)
                .append(",limit=").append(limit)
                .append(",reset=").append(reset);
        if (!name.isBlank()) out.append(",name=").append(name);
        if (extraDamage > 0.0D) out.append(",damage=").append(formatDecimal(extraDamage));
        if (!ench.isBlank()) out.append(",ench=").append(ench);
        return ValidationResult.ok(out.toString());
    }

    private static ValidationResult invalidId(String kind, String id, String message) {
        String key = kind + ":" + String.valueOf(id);
        if (WARNED_INVALID_IDS.add(key)) {
            HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: rejected NPC trade invalid {} id '{}'", kind, id);
        }
        return ValidationResult.error(message);
    }

    private static int num(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(String.valueOf(raw).trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double decimal(String raw, double fallback, double min, double max) {
        try {
            double value = Double.parseDouble(String.valueOf(raw).trim().replace(',', '.'));
            if (!Double.isFinite(value)) return fallback;
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String formatDecimal(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) return String.valueOf((int)Math.rint(value));
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static Map<String, String> parseMap(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.replace(';', ',').split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = part.substring(eq + 1).trim();
            if (!k.isBlank()) m.put(k, v);
        }
        return m;
    }

    private static String normalizeItemId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (id.isBlank()) return "";
        if ("homecraftguild:guild_crystal".equals(id) || "homecraftguild:guild_totem_crystal".equals(id)) id = "homecraftguild:guild_banner";
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) return "";
        return GuildCurrency.normalizeItemId(id);
    }

    private static String normalizeEnchants(ServerPlayer player, String raw, String sellItemId) {
        if (raw == null || raw.isBlank()) return "";
        List<String> out = new ArrayList<>();
        for (String entry : raw.replace(';', '+').split("\\+")) {
            String[] p = entry.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").split(":");
            if (p.length < 2) continue;
            String name = p[0].replaceAll("[^a-z0-9_/.-]+", "_");
            int level;
            try { level = Integer.parseInt(p[p.length - 1]); } catch (Exception ex) { return null; }
            if (level < 1 || level > 10) return null;
            ResourceKey<Enchantment> key = enchantmentKey(name);
            if (key == null) return null;
            if (player != null) {
                try {
                    Holder.Reference<Enchantment> holder = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
                    if (!isEnchantAllowedForSell(holder.value(), sellItemId)) {
                        invalidId("enchantment-item", name + "@" + sellItemId, "Зачарування " + name + " не підходить для " + sellItemId);
                        return null;
                    }
                } catch (Throwable t) {
                    invalidId("enchantment", name, "Невалідний enchant id: " + name);
                    return null;
                }
            }
            out.add(name + ":" + level);
            if (out.size() >= 8) break;
        }
        return String.join("+", out);
    }


    private static boolean isEnchantAllowedForSell(Object enchantment, String sellItemId) {
        if (enchantment == null || sellItemId == null || sellItemId.isBlank()) return true;
        ItemStack target = stackFromId(sellItemId, 1);
        if (target.isEmpty()) return false;
        for (String methodName : new String[]{"canEnchant", "isAcceptableItem"}) {
            try {
                Method method = enchantment.getClass().getMethod(methodName, ItemStack.class);
                Object result = method.invoke(enchantment, target);
                if (result instanceof Boolean b) return b;
            } catch (NoSuchMethodException ignored) {
                // Mapping/API difference. If no direct compatibility API exists, keep registry validation authoritative.
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeRole(String raw) {
        String value = raw == null ? "any" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replaceAll("[^a-z0-9_]+", "_");
        return switch (value) {
            case "", "any", "all" -> "any";
            case "member", "guild_member", "guildmaster", "guild_master", "gm", "builder", "quartermaster", "warrior", "farmer" -> value;
            default -> "";
        };
    }

    private static String normalizeResetPolicy(String raw) {
        String value = raw == null ? "never" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "daily", "weekly", "server_restart" -> value;
            default -> "never";
        };
    }

    private static String safeText(String raw, int max) {
        String value = raw == null ? "" : raw.trim().replace('|', ' ').replace(';', ' ').replace('~', ' ').replace(',', ' ');
        return value.length() > max ? value.substring(0, max) : value;
    }

    public static void openTrader(ServerPlayer player, String npcKey, Entity npcEntity) {
        if (player == null || npcKey == null || npcKey.isBlank()) return;
        GuildCurrency.migrateAndNotify(player, "npc_trade_open");
        if (!"trader".equalsIgnoreCase(HomeCraftGuildConfig.npcKind(npcKey))) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: цей NPC не є торговцем."), false);
            return;
        }
        List<TradeSpec> visibleSpecs = new ArrayList<>();
        MerchantOffers offers = buildOffersFor(player, npcKey, visibleSpecs);
        if (offers.isEmpty()) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: у цього торговця немає доступних товарів для твоїх прав."), false);
            if (HomeCraftGuildConfig.npcInteractionSoundEnabled()) {
                player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.8F, 1.0F);
            }
            return;
        }
        GuildMerchant merchant = new GuildMerchant(player, npcKey, npcEntity, offers, visibleSpecs);
        merchant.openTradingScreen(player, Component.literal(HomeCraftGuildConfig.npcName(npcKey)), 0);
    }

    private static MerchantOffers buildOffersFor(ServerPlayer player, String npcKey, List<TradeSpec> visibleSpecs) {
        MerchantOffers offers = new MerchantOffers();
        for (TradeSpec spec : specsFor(npcKey)) {
            if (!isRoleAllowed(player, spec.role)) continue;
            int remaining = remainingFor(player, npcKey, spec);
            if (remaining == 0) continue;
            ItemStack buy = stackFromId(spec.buy, spec.buyCount);
            ItemStack sell = stackFromSpec(player, spec.sell, spec.sellCount, spec.name, spec.enchants, spec.extraDamage);
            if (buy.isEmpty() || sell.isEmpty()) continue;
            int maxUses = spec.maxUses;
            if (remaining > 0) maxUses = Math.min(maxUses, remaining);
            maxUses = Math.max(1, maxUses);
            MerchantOffer offer = spec.buy2 == null || spec.buy2.isBlank()
                    ? new MerchantOffer(new ItemCost(buy.getItem(), buy.getCount()), sell, maxUses, spec.xp, 0.0F)
                    : new MerchantOffer(new ItemCost(buy.getItem(), buy.getCount()), Optional.of(new ItemCost(itemById(spec.buy2), Math.max(1, Math.min(64, spec.buy2Count)))), sell, maxUses, spec.xp, 0.0F);
            offers.add(offer);
            visibleSpecs.add(spec);
        }
        return offers;
    }

    private static List<TradeSpec> specsFor(String npcKey) {
        String raw = HomeCraftGuildConfig.npcTrades(npcKey);
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        List<TradeSpec> out = new ArrayList<>();
        for (String part : raw.split("~")) {
            TradeSpec spec = TradeSpec.parse(part);
            if (spec != null) out.add(spec);
            if (out.size() >= 64) break;
        }
        return out;
    }

    private static boolean isRoleAllowed(ServerPlayer player, String roleRaw) {
        String role = roleRaw == null ? "any" : roleRaw.trim().toLowerCase(Locale.ROOT);
        if (role.isBlank() || "any".equals(role) || "all".equals(role)) return true;
        if ("member".equals(role) || "guild_member".equals(role)) return GuildStore.playerGuildId(player) != null;
        if ("guildmaster".equals(role) || "guild_master".equals(role) || "gm".equals(role)) return GuildStore.isGuildMaster(player);
        GuildRank rank = GuildStore.rankOf(player);
        return rank != null && rank.name().equalsIgnoreCase(role);
    }

    private static int remainingFor(ServerPlayer player, String npcKey, TradeSpec spec) {
        if (spec.perPlayerLimit <= 0 || player == null) return -1;
        int used = persistentPurchaseCount(player, purchaseKey(player, npcKey, spec));
        return Math.max(0, spec.perPlayerLimit - used);
    }

    private static void recordPurchase(ServerPlayer player, String npcKey, TradeSpec spec) {
        if (player == null || spec == null || spec.perPlayerLimit <= 0) return;
        String key = purchaseKey(player, npcKey, spec);
        persistentPurchaseIncrement(player, key);
        PLAYER_PURCHASES.merge(key, 1, Integer::sum);
    }

    private static String purchaseKey(ServerPlayer player, String npcKey, TradeSpec spec) {
        return player.getUUID() + "|" + npcKey + "|" + spec.id + "|" + resetBucket(spec.resetPolicy);
    }

    private static String resetBucket(String resetPolicyRaw) {
        String reset = resetPolicyRaw == null ? "never" : resetPolicyRaw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("daily".equals(reset)) {
            return "daily:" + LocalDate.now(ZoneOffset.UTC);
        }
        if ("weekly".equals(reset)) {
            LocalDate date = LocalDate.now(ZoneOffset.UTC);
            WeekFields iso = WeekFields.ISO;
            return "weekly:" + date.get(iso.weekBasedYear()) + "-W" + String.format(Locale.ROOT, "%02d", date.get(iso.weekOfWeekBasedYear()));
        }
        if ("server_restart".equals(reset)) {
            return "server_restart:" + SERVER_PURCHASE_SESSION;
        }
        return "never";
    }

    private static int persistentPurchaseCount(ServerPlayer player, String fullKey) {
        if (player == null || fullKey == null || fullKey.isBlank()) return 0;
        String tag = purchaseNbtTag(fullKey);
        try {
            CompoundTag data = player.getPersistentData();
            if (data != null) return Math.max(0, data.getInt(tag).orElse(0));
        } catch (Throwable ignored) { }
        return Math.max(0, PLAYER_PURCHASES.getOrDefault(fullKey, 0));
    }

    private static void persistentPurchaseIncrement(ServerPlayer player, String fullKey) {
        if (player == null || fullKey == null || fullKey.isBlank()) return;
        String tag = purchaseNbtTag(fullKey);
        try {
            CompoundTag data = player.getPersistentData();
            if (data != null) {
                int next = Math.max(0, data.getInt(tag).orElse(0)) + 1;
                data.putInt(tag, next);
                return;
            }
        } catch (Throwable ignored) { }
    }

    private static String purchaseNbtTag(String fullKey) {
        CRC32 crc = new CRC32();
        byte[] bytes = String.valueOf(fullKey).getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return PURCHASE_NBT_PREFIX + Long.toHexString(crc.getValue());
    }

    private static ItemStack stackFromId(String id, int count) {
        String normalized = GuildCurrency.normalizeItemId(id);
        if (GuildCurrency.isVanillaEmeraldId(normalized)) {
            return GuildCurrency.vanillaEmeraldStack(count);
        }
        Item item = itemById(normalized);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, Math.max(1, Math.min(64, count)));
    }

    private static ItemStack stackFromSpec(ServerPlayer player, String id, int count, String name, String enchants, double extraDamage) {
        String normalized = GuildCurrency.normalizeItemId(id);
        ItemStack stack = stackFromId(normalized, count);
        if (stack.isEmpty()) return stack;
        if (GuildCurrency.isVanillaEmeraldId(normalized) || stack.is(Items.EMERALD)) {
            // Never rename/enchant/modify currency. NPC buyback must output the exact
            // same original vanilla emerald that normal trades accept.
            return GuildCurrency.vanillaEmeraldStack(count);
        }
        if (name != null && !name.isBlank()) stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        applyExtraAttackDamage(stack, extraDamage);
        applyEnchants(player, stack, enchants);
        return stack;
    }

    private static void applyExtraAttackDamage(ItemStack stack, double extraDamage) {
        if (stack == null || stack.isEmpty() || stack.is(Items.EMERALD) || extraDamage <= 0.0D) return;
        try {
            ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            modifiers = modifiers.withModifierAdded(
                    Attributes.ATTACK_DAMAGE,
                    new AttributeModifier(NPC_TRADE_EXTRA_DAMAGE_ID, extraDamage, AttributeModifier.Operation.ADD_VALUE),
                    EquipmentSlotGroup.MAINHAND
            );
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: could not apply NPC trade extra damage {} to {}", extraDamage, stack, t);
        }
    }

    private static Item itemById(String raw) {
        String id = raw == null || raw.isBlank() ? "minecraft:air" : raw.trim().toLowerCase(Locale.ROOT);
        if ("homecraftguild:guild_crystal".equals(id) || "homecraftguild:guild_totem_crystal".equals(id)) id = "homecraftguild:guild_banner";
        if (!id.contains(":")) id = "minecraft:" + id;
        id = GuildCurrency.normalizeItemId(id);
        if ("homecraftguild:guild_banner".equals(id)) return HomeCraftGuildItems.GUILD_BANNER.get();
        try { return BuiltInRegistries.ITEM.getValue(Identifier.parse(id)); }
        catch (Throwable ignored) { return Items.AIR; }
    }

    private static void applyEnchants(ServerPlayer player, ItemStack stack, String raw) {
        if (player == null || stack.isEmpty() || stack.is(Items.EMERALD) || raw == null || raw.isBlank()) return;
        for (String entry : raw.replace(';', '+').split("\\+")) {
            String[] p = entry.trim().toLowerCase(Locale.ROOT).split(":");
            if (p.length < 2) continue;
            String name = p.length == 2 ? p[0] : p[p.length - 2];
            int level = 1;
            try { level = Integer.parseInt(p[p.length - 1]); } catch (Exception ignored) {}
            ResourceKey<Enchantment> key = enchantmentKey(name);
            if (key == null) continue;
            try {
                Holder.Reference<Enchantment> holder = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
                stack.enchant(holder, Math.max(1, Math.min(10, level)));
            } catch (Throwable t) {
                HomeCraftGuildMod.LOGGER.debug("Skipped NPC trade enchant {}:{}", name, level, t);
            }
        }
    }

    private static ResourceKey<Enchantment> enchantmentKey(String id) {
        String n = id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (n.isBlank() || !n.matches("[a-z0-9_/.-]+")) return null;
        try {
            return ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse("minecraft:" + n));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class GuildMerchant implements Merchant {
        private final ServerPlayer player;
        private final String npcKey;
        private final Entity entity;
        private final MerchantOffers offers;
        private final Map<MerchantOffer, TradeSpec> byOffer = new IdentityHashMap<>();

        GuildMerchant(ServerPlayer player, String npcKey, Entity entity, MerchantOffers offers, List<TradeSpec> specs) {
            this.player = player;
            this.npcKey = npcKey;
            this.entity = entity;
            this.offers = offers;
            for (int i = 0; i < offers.size() && i < specs.size(); i++) byOffer.put(offers.get(i), specs.get(i));
        }

        @Override public Player getTradingPlayer() { return player; }
        @Override public void setTradingPlayer(Player player) { }
        @Override public MerchantOffers getOffers() { return offers; }
        @Override public void overrideOffers(MerchantOffers merchantOffers) { }
        @Override public void notifyTrade(MerchantOffer merchantOffer) { merchantOffer.increaseUses(); recordPurchase(player, npcKey, byOffer.get(merchantOffer)); }
        @Override public void notifyTradeUpdated(ItemStack itemStack) { }
        @Override public int getVillagerXp() { return 0; }
        @Override public void overrideXp(int xp) { }
        @Override public boolean showProgressBar() { return true; }
        @Override public SoundEvent getNotifyTradeSound() { return SoundEvents.VILLAGER_TRADE; }
        @Override public boolean isClientSide() { return false; }
        @Override public boolean stillValid(Player player) { return entity != null && entity.isAlive() && player.distanceToSqr(entity) <= 64.0D; }
        @Override public void openTradingScreen(Player player, Component name, int containerId) {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, p) -> new MerchantMenu(id, inventory, this), name));
            serverPlayer.sendMerchantOffers(serverPlayer.containerMenu.containerId, offers, 0, 1, true, false);
        }
    }

    private record TradeSpec(String id, String buy, int buyCount, String buy2, int buy2Count, String sell, int sellCount, int maxUses, int xp, String role, int perPlayerLimit, String resetPolicy, String name, String enchants, double extraDamage) {
        static TradeSpec parse(String raw) {
            Map<String,String> m = new HashMap<>();
            if (raw == null) return null;
            for (String part : raw.replace(';', ',').split(",")) {
                int eq = part.indexOf('='); if (eq <= 0) continue;
                m.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1).trim());
            }
            String buy = GuildCurrency.normalizeItemId(m.getOrDefault("buy", "minecraft:emerald"));
            String buy2 = m.getOrDefault("buy2", m.getOrDefault("secondbuy", ""));
            buy2 = buy2 == null || buy2.isBlank() ? "" : GuildCurrency.normalizeItemId(buy2);
            String sell = GuildCurrency.normalizeItemId(m.getOrDefault("sell", "minecraft:bread"));
            int buyCount = num(m.get("buycount"), 1, 1, 64);
            int buy2Count = buy2 == null || buy2.isBlank() ? 0 : num(m.getOrDefault("buy2count", m.getOrDefault("secondbuycount", "1")), 1, 1, 64);
            int sellCount = num(m.get("sellcount"), 1, 1, 64);
            int max = num(m.getOrDefault("max", m.get("maxuses")), 16, 1, 9999);
            int xp = num(m.get("xp"), 0, 0, 9999);
            int limit = num(m.getOrDefault("limit", m.get("perplayer")), 0, 0, 9999);
            String role = m.getOrDefault("role", m.getOrDefault("rank", "any"));
            String name = m.getOrDefault("name", "");
            String ench = m.getOrDefault("ench", m.getOrDefault("enchant", ""));
            String reset = m.getOrDefault("reset", m.getOrDefault("resetpolicy", "never"));
            double extraDamage = decimal(m.getOrDefault("damage", m.getOrDefault("extradamage", m.getOrDefault("bonusdamage", "0"))), 0.0D, 0.0D, 16.0D);
            if (GuildCurrency.isVanillaEmeraldId(sell)) {
                name = "";
                ench = "";
                extraDamage = 0.0D;
            }
            String id = stableTradeId(buy, buyCount, buy2, buy2Count, sell, sellCount, max, xp, role, limit, reset, name, ench, extraDamage);
            return new TradeSpec(id, buy, buyCount, buy2, buy2Count, sell, sellCount, max, xp, role, limit, reset, name, ench, extraDamage);
        }
        private static String stableTradeId(Object... values) {
            CRC32 crc = new CRC32();
            for (Object value : values) {
                byte[] bytes = String.valueOf(value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                crc.update(bytes, 0, bytes.length);
                crc.update(31);
            }
            return Long.toHexString(crc.getValue());
        }
        private static int num(String raw, int fallback, int min, int max) { try { return Math.max(min, Math.min(max, Integer.parseInt(String.valueOf(raw).trim()))); } catch (Exception ignored) { return fallback; } }
    }
}
