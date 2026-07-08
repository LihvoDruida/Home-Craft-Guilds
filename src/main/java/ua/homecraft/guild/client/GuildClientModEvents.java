package ua.homecraft.guild.client;

import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.blockentity.HomeCraftGuildBlockEntities;
import ua.homecraft.guild.client.renderer.GuildRegistrarRenderer;
import ua.homecraft.guild.client.renderer.GuildTotemBlockEntityRenderer;
import ua.homecraft.guild.entity.HomeCraftGuildEntities;

public final class GuildClientModEvents {
    private GuildClientModEvents() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GuildClientModEvents::registerLayerDefinitions);
        modEventBus.addListener(GuildClientModEvents::registerEntityRenderers);
    }

    private static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
                GuildClientLayers.GUILD_REGISTRAR,
                () -> LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64)
        );
        HomeCraftGuildMod.LOGGER.info("Home Craft Guilds client: registered guild registrar player model layer");
    }

    private static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(HomeCraftGuildEntities.GUILD_REGISTRAR.get(), GuildRegistrarRenderer::new);
        event.registerBlockEntityRenderer(HomeCraftGuildBlockEntities.GUILD_TOTEM.get(), GuildTotemBlockEntityRenderer::new);
        HomeCraftGuildMod.LOGGER.info("Home Craft Guilds client: registered textured renderer for homecraftguild:guild_registrar");
    }
}
