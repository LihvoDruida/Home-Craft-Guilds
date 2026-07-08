package ua.homecraft.guild.client.npc;

import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;

/**
 * Lazy render/input hook for the client-owned guild registrar.
 * Registered lazily after the client world exists so early model/resource reload cannot discover it too soon.
 */
public final class GuildVirtualRegistrarRenderHook {
    private static boolean registered = false;

    private GuildVirtualRegistrarRenderHook() {}

    public static synchronized Boolean register() {
        if (registered) return Boolean.TRUE;
        NeoForge.EVENT_BUS.addListener(GuildVirtualRegistrarRenderHook::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(GuildVirtualRegistrarRenderHook::onInteractionKey);
        registered = true;
        return Boolean.TRUE;
    }


    private static void onRenderLevel(RenderLevelStageEvent event) {
        GuildVirtualRegistrarClient.render(event);
    }

    private static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (event == null) return;
        if (!isUseItemInput(event)) return;
        if (GuildVirtualRegistrarClient.consumeClientUseInput()) {
            event.setCanceled(true);
        }
    }

    private static boolean isUseItemInput(InputEvent.InteractionKeyMappingTriggered event) {
        Object value = invokeNoArg(event, "isUseItem", "isUse", "isRightClick");
        if (value instanceof Boolean b) return b;
        // Fallback: this event is already the very first interaction-key hook. If method names changed,
        // only consume it while the crosshair is on the registrar, so normal gameplay stays untouched.
        return GuildVirtualRegistrarClient.isLookingAtRegistrar();
    }

    private static Object invokeNoArg(Object target, String... names) {
        if (target == null || names == null) return null;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                    type = type.getSuperclass();
                }
            }
        }
        return null;
    }
}
