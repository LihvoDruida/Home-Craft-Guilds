package ua.homecraft.guild.block;

import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.homecraft.guild.HomeCraftGuildMod;

public final class HomeCraftGuildBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(HomeCraftGuildMod.MOD_ID);

    public static final DeferredBlock<GuildBannerBlock> GUILD_BANNER = BLOCKS.registerBlock(
            "guild_banner",
            GuildBannerBlock::new,
            props -> props
                    .strength(1.0F, 3.0F)
                    .sound(SoundType.STONE)
                    .noOcclusion()
                    .lightLevel(state -> 14)
    );

    private HomeCraftGuildBlocks() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }
}
