package ua.homecraft.guild;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.homecraft.guild.client.GuildClientModEvents;
import ua.homecraft.guild.item.HomeCraftGuildItems;
import ua.homecraft.guild.block.HomeCraftGuildBlocks;
import ua.homecraft.guild.entity.HomeCraftGuildEntities;
import ua.homecraft.guild.network.GuildNetworking;
import ua.homecraft.guild.effect.HomeCraftGuildEffects;
import ua.homecraft.guild.blockentity.HomeCraftGuildBlockEntities;

@Mod(HomeCraftGuildMod.MOD_ID)
public final class HomeCraftGuildMod {
    public static final String MOD_ID = "homecraftguild";
    public static final Logger LOGGER = LoggerFactory.getLogger("Home Craft Guilds");

    public HomeCraftGuildMod(IEventBus modEventBus, ModContainer modContainer) {
        HomeCraftGuildBlocks.register(modEventBus);
        HomeCraftGuildItems.register(modEventBus);
        HomeCraftGuildEntities.register(modEventBus);
        HomeCraftGuildBlockEntities.register(modEventBus);
        HomeCraftGuildEffects.register(modEventBus);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            GuildClientModEvents.register(modEventBus);
        }
        modEventBus.addListener(GuildNetworking::registerPayloads);
        LOGGER.info("Home Craft Guilds loaded");
    }
}
