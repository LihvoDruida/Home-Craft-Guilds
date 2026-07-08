package ua.homecraft.guild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.lwjgl.glfw.GLFW;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.effect.HomeCraftGuildEffects;
import ua.homecraft.guild.network.GuildActionPayload;
import ua.homecraft.guild.network.OpenCreateGuildPayload;
import ua.homecraft.guild.network.OpenGuildRosterPayload;
import ua.homecraft.guild.network.OpenGuildNpcAdminPayload;
import ua.homecraft.guild.network.GuildVisualSyncPayload;
import ua.homecraft.guild.network.GuildTerritoryVisualSyncPayload;
import ua.homecraft.guild.network.GuildNpcSyncPayload;
import ua.homecraft.guild.network.GuildNpcSkinRegistryPayload;
import ua.homecraft.guild.network.GuildNpcAdminSnapshotPayload;
import ua.homecraft.guild.network.GuildNpcTradeValidationResultPayload;
import ua.homecraft.guild.client.npc.GuildVirtualRegistrarClient;

import java.util.Locale;

@EventBusSubscriber(modid = HomeCraftGuildMod.MOD_ID, value = Dist.CLIENT)
public final class GuildClientEvents {
    private static boolean gWasDown = false;
    private static boolean bedLabelHookRegistrationTried = false;
    private static boolean bedLabelHookRegistrationQueued = false;
    private static boolean registrarRenderHookRegistrationTried = false;
    private static boolean registrarRenderHookRegistrationQueued = false;

    private GuildClientEvents() {}

    @SubscribeEvent
    public static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(OpenGuildRosterPayload.TYPE, GuildClientPayloadHandler::openRoster);
        event.register(OpenCreateGuildPayload.TYPE, GuildClientPayloadHandler::openCreateGuild);
        event.register(OpenGuildNpcAdminPayload.TYPE, GuildClientPayloadHandler::openNpcAdmin);
        event.register(GuildNpcAdminSnapshotPayload.TYPE, GuildClientPayloadHandler::openNpcAdminSnapshot);
        event.register(GuildNpcTradeValidationResultPayload.TYPE, GuildClientPayloadHandler::openNpcTradeValidationResult);
        event.register(GuildNpcSkinRegistryPayload.TYPE, GuildClientPayloadHandler::syncNpcSkinRegistry);
        event.register(GuildNpcSyncPayload.TYPE, GuildClientPayloadHandler::syncNpcs);
        event.register(GuildTerritoryVisualSyncPayload.TYPE, GuildClientPayloadHandler::syncTerritoryVisuals);
        event.register(GuildVisualSyncPayload.TYPE, GuildClientPayloadHandler::syncVisuals);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        GuildClientVisualEffects.onClientTick();
        GuildVirtualRegistrarClient.onClientTick();


        Minecraft mc = Minecraft.getInstance();
        tryRegisterBedLabelHook(mc);
        tryRegisterVirtualRegistrarHook(mc);
        if (mc == null || mc.getWindow() == null) return;
        long handle = windowHandle(mc.getWindow());
        if (handle == 0L) return;
        boolean down = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_G) == GLFW.GLFW_PRESS;
        if (down && !gWasDown && mc.screen == null && mc.player != null) {
            ClientPacketDistributor.sendToServer(new GuildActionPayload("request", "", ""));
        }
        gWasDown = down;
    }


    @SubscribeEvent
    public static void onClientRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event == null) return;
        if (GuildVirtualRegistrarClient.consumeClientUseInput()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onClientRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event == null) return;
        if (GuildVirtualRegistrarClient.consumeClientUseInput()) {
            event.setCanceled(true);
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }


    private static void tryRegisterBedLabelHook(Minecraft mc) {
        if (bedLabelHookRegistrationTried || bedLabelHookRegistrationQueued) return;
        if (Boolean.getBoolean("homecraftguild.bedLabels.disable")) {
            bedLabelHookRegistrationTried = true;
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds client: bed owner labels disabled by JVM property");
            return;
        }
        // Do not register the RenderLevelStageEvent hook through static @SubscribeEvent.
        // On NeoForge 21.11.42 it can be discovered during early client startup and crash inside ModelManager.reload.
        // Register lazily only after a real client world/player exists, and queue the registration so we
        // do not mutate NeoForge.EVENT_BUS while ClientTickEvent is being dispatched.
        if (mc == null || mc.level == null || mc.player == null) return;
        bedLabelHookRegistrationQueued = true;
        mc.execute(() -> {
            if (bedLabelHookRegistrationTried) return;
            bedLabelHookRegistrationTried = true;
            try {
                Class<?> hook = Class.forName("ua.homecraft.guild.client.bed.GuildBedLabelRenderHook");
                Object result = hook.getMethod("register").invoke(null);
                if (Boolean.TRUE.equals(result)) {
                    HomeCraftGuildMod.LOGGER.info("Home Craft Guilds client: bed owner label renderer registered lazily");
                }
            } catch (Throwable t) {
                HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds client: bed owner label renderer could not be registered; labels stay disabled", t);
            }
        });
    }


    private static void tryRegisterVirtualRegistrarHook(Minecraft mc) {
        if (registrarRenderHookRegistrationTried || registrarRenderHookRegistrationQueued) return;
        if (mc == null || mc.level == null || mc.player == null) return;
        registrarRenderHookRegistrationQueued = true;
        mc.execute(() -> {
            if (registrarRenderHookRegistrationTried) return;
            registrarRenderHookRegistrationTried = true;
            try {
                Class<?> hook = Class.forName("ua.homecraft.guild.client.npc.GuildVirtualRegistrarRenderHook");
                Object result = hook.getMethod("register").invoke(null);
                if (Boolean.TRUE.equals(result)) {
                    HomeCraftGuildMod.LOGGER.info("Home Craft Guilds client: virtual registrar render/input hook registered lazily");
                }
            } catch (Throwable t) {
                HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds client: virtual registrar renderer could not be registered", t);
            }
        });
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || event == null) return;
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) return;

        if (isWeaponLike(stack)) {
            MobEffectInstance effect = mc.player.getEffect(HomeCraftGuildEffects.GUILD_DAMAGE);
            int percent = damagePercentFromEffect(effect);
            if (percent > 0) {
                double baseDamage = estimatedWeaponDamage(stack);
                String added = baseDamage > 0.0D
                        ? HomeCraftGuildI18n.t("tooltip.homecraftguild.weapon_bonus.calculated", formatOneDecimal(baseDamage), formatOneDecimal(baseDamage * percent / 100.0D), formatOneDecimal(baseDamage * (1.0D + percent / 100.0D)))
                        : HomeCraftGuildI18n.t("tooltip.homecraftguild.weapon_bonus.added", percent);
                event.getToolTip().add(HomeCraftGuildI18n.c("tooltip.homecraftguild.weapon_bonus.line", percent, added).withStyle(ChatFormatting.GOLD));
            }
        }

        if (isArmorPiece(stack)) {
            MobEffectInstance effect = mc.player.getEffect(HomeCraftGuildEffects.GUILD_ARMOR);
            int percent = armorPercentFromEffect(effect);
            if (percent > 0) {
                double armor = estimatedArmorValue(stack);
                String added = armor > 0.0D
                        ? HomeCraftGuildI18n.t("tooltip.homecraftguild.armor_bonus.calculated", formatOneDecimal(armor), formatOneDecimal(armor * percent / 100.0D), formatOneDecimal(armor * (1.0D + percent / 100.0D)))
                        : HomeCraftGuildI18n.t("tooltip.homecraftguild.armor_bonus.added", percent);
                event.getToolTip().add(HomeCraftGuildI18n.c("tooltip.homecraftguild.armor_bonus.line", percent, added).withStyle(ChatFormatting.AQUA));
            }
        }
    }

    private static int damagePercentFromEffect(MobEffectInstance effect) {
        if (effect == null) return 0;
        return switch (effect.getAmplifier()) {
            case 0 -> 5;
            case 1 -> 10;
            default -> 15;
        };
    }

    private static int armorPercentFromEffect(MobEffectInstance effect) {
        if (effect == null) return 0;
        return switch (effect.getAmplifier()) {
            case 0 -> 5;
            case 1 -> 10;
            default -> 15;
        };
    }

    private static boolean isWeaponLike(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        if (path.isBlank()) return false;
        if (path.endsWith("_hoe") || path.endsWith("_shovel") || path.endsWith("_pickaxe")) return false;
        if (path.endsWith("_sword") || path.endsWith("_axe")) return true;
        if (path.equals("mace") || path.equals("trident") || path.equals("bow") || path.equals("crossbow")) return true;
        return path.endsWith("_mace")
                || path.endsWith("_trident")
                || path.endsWith("_bow")
                || path.endsWith("_crossbow")
                || path.endsWith("_dagger")
                || path.endsWith("_knife")
                || path.endsWith("_spear")
                || path.endsWith("_halberd")
                || path.endsWith("_rapier")
                || path.endsWith("_saber")
                || path.endsWith("_katana")
                || path.endsWith("_greatsword")
                || path.endsWith("_battleaxe")
                || path.endsWith("_warhammer");
    }

    private static boolean isArmorPiece(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith("helmet") || path.endsWith("chestplate") || path.endsWith("leggings") || path.endsWith("boots");
    }

    private static double estimatedWeaponDamage(ItemStack stack) {
        Identifier id = stack == null ? null : BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        if (path.equals("mace")) return 6.0D;
        if (path.equals("trident")) return 9.0D;
        if (path.endsWith("_sword")) {
            if (path.startsWith("wooden_") || path.startsWith("golden_")) return 4.0D;
            if (path.startsWith("stone_")) return 5.0D;
            if (path.startsWith("iron_")) return 6.0D;
            if (path.startsWith("diamond_")) return 7.0D;
            if (path.startsWith("netherite_")) return 8.0D;
        }
        if (path.endsWith("_axe") && !path.endsWith("_pickaxe")) {
            if (path.startsWith("wooden_") || path.startsWith("golden_")) return 7.0D;
            if (path.startsWith("stone_") || path.startsWith("iron_") || path.startsWith("diamond_")) return 9.0D;
            if (path.startsWith("netherite_")) return 10.0D;
        }
        return 0.0D;
    }

    private static double estimatedArmorValue(ItemStack stack) {
        Identifier id = stack == null ? null : BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        if (path.equals("turtle_helmet")) return 2.0D;
        if (path.startsWith("leather_")) {
            if (path.endsWith("helmet")) return 1.0D;
            if (path.endsWith("chestplate")) return 3.0D;
            if (path.endsWith("leggings")) return 2.0D;
            if (path.endsWith("boots")) return 1.0D;
        }
        if (path.startsWith("golden_") || path.startsWith("chainmail_")) {
            if (path.endsWith("helmet")) return 2.0D;
            if (path.endsWith("chestplate")) return 5.0D;
            if (path.endsWith("leggings")) return 3.0D;
            if (path.endsWith("boots")) return 1.0D;
        }
        if (path.startsWith("iron_")) {
            if (path.endsWith("helmet")) return 2.0D;
            if (path.endsWith("chestplate")) return 6.0D;
            if (path.endsWith("leggings")) return 5.0D;
            if (path.endsWith("boots")) return 2.0D;
        }
        if (path.startsWith("diamond_") || path.startsWith("netherite_")) {
            if (path.endsWith("helmet")) return 3.0D;
            if (path.endsWith("chestplate")) return 8.0D;
            if (path.endsWith("leggings")) return 6.0D;
            if (path.endsWith("boots")) return 3.0D;
        }
        return 0.0D;
    }

    private static String formatOneDecimal(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05D) return String.valueOf((int)Math.rint(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static long windowHandle(Object window) {
        if (window == null) return 0L;
        String[] methods = {"getWindow", "getWindowHandle", "getHandle", "window"};
        for (String name : methods) {
            try {
                Object value = window.getClass().getMethod(name).invoke(window);
                if (value instanceof Number n) return n.longValue();
            } catch (Exception ignored) {}
        }
        String[] fields = {"window", "windowHandle", "handle"};
        for (String name : fields) {
            try {
                var field = window.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(window);
                if (value instanceof Number n) return n.longValue();
            } catch (Exception ignored) {}
        }
        return 0L;
    }
}
