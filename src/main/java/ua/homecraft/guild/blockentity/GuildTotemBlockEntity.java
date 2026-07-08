package ua.homecraft.guild.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class GuildTotemBlockEntity extends BlockEntity {
    public GuildTotemBlockEntity(BlockPos pos, BlockState blockState) {
        super(HomeCraftGuildBlockEntities.GUILD_TOTEM.get(), pos, blockState);
    }
}
