package ua.homecraft.guild.server.bed;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class GuildBedPositionResolver {
    private GuildBedPositionResolver() {}

    public static boolean isBed(BlockState state) {
        if (state == null) return false;
        try {
            return state.hasProperty(BlockStateProperties.BED_PART);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static BlockPos rootPos(LevelAccessor level, BlockPos pos, BlockState state) {
        if (pos == null || state == null) return pos;
        try {
            if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
                Direction facing = facing(state);
                return pos.relative(facing);
            }
        } catch (Throwable ignored) {}
        return pos;
    }

    public static String facingName(BlockState state) {
        Direction facing = facing(state);
        return facing == null ? "" : facing.getName();
    }

    private static Direction facing(BlockState state) {
        try {
            if (state != null && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        } catch (Throwable ignored) {}
        return Direction.NORTH;
    }
}
