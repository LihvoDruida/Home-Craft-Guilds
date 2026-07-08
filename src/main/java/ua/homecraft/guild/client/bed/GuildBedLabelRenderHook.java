package ua.homecraft.guild.client.bed;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Registers bed label rendering lazily after the client has finished early model/resource reload.
 *
 * Do not annotate this class with @EventBusSubscriber and do not add a static @SubscribeEvent
 * RenderLevelStageEvent method to GuildClientEvents. In NeoForge 21.11.42 that early discovery
 * path was confirmed to crash before the main menu during ModelManager.reload.
 */
public final class GuildBedLabelRenderHook {
    private static boolean registered = false;

    private GuildBedLabelRenderHook() {}

    public static synchronized Boolean register() {
        if (registered) return Boolean.TRUE;
        NeoForge.EVENT_BUS.addListener(GuildBedLabelRenderHook::onRenderLevel);
        registered = true;
        return Boolean.TRUE;
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        GuildBedLabelRenderer.render(event);
    }
}
