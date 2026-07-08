package ua.homecraft.guild.blockentity;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.block.HomeCraftGuildBlocks;

public final class HomeCraftGuildBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, HomeCraftGuildMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GuildTotemBlockEntity>> GUILD_TOTEM =
            BLOCK_ENTITY_TYPES.register(
                    "guild_totem",
                    () -> new BlockEntityType<>(GuildTotemBlockEntity::new, HomeCraftGuildBlocks.GUILD_BANNER.get())
            );

    private HomeCraftGuildBlockEntities() {}

    public static void register(IEventBus bus) {
        BLOCK_ENTITY_TYPES.register(bus);
    }
}
