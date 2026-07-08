package ua.homecraft.guild.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import ua.homecraft.guild.server.GuildServerEvents;
import ua.homecraft.guild.server.GuildStore;

import java.util.function.Consumer;

public final class GuildBannerBlockItem extends BlockItem {
    public GuildBannerBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;

        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(clickedPos);
        BlockPos replaceCheckPos = clickedState.canBeReplaced(placeContext)
                ? clickedPos
                : clickedPos.relative(context.getClickedFace());
        BlockPlaceContext checkedContext = BlockPlaceContext.at(placeContext, replaceCheckPos, context.getClickedFace());

        if (GuildStore.isSpawnPosition(level, replaceCheckPos)) {
            player.displayClientMessage(Component.translatable("message.homecraftguild.guild_banner.spawn_denied"), true);
            return InteractionResult.FAIL;
        }
        if (!level.getBlockState(replaceCheckPos).canBeReplaced(checkedContext)) return InteractionResult.FAIL;

        if (!GuildStore.canAddTerritory(player, replaceCheckPos, level, "GUILD")) {
            player.displayClientMessage(Component.translatable("message.homecraftguild.guild_banner.claim_denied"), true);
            return InteractionResult.FAIL;
        }

        InteractionResult result = super.useOn(context);
        if (!result.consumesAction()) return result;

        BlockPos placedPos = level.getBlockState(clickedPos).is(this.getBlock()) ? clickedPos : replaceCheckPos;
        BlockState placedState = level.getBlockState(placedPos);
        boolean ok = GuildServerEvents.registerGuildBannerClaim(player, placedPos, placedState);
        if (!ok) {
            level.removeBlock(placedPos, false);
            if (!player.getAbilities().instabuild) {
                player.getInventory().add(this.getDefaultInstance());
            }
            return InteractionResult.FAIL;
        }
        player.displayClientMessage(Component.translatable("message.homecraftguild.guild_banner.placed"), true);
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.title").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.description").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.recipe").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.recipe_items").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.empty());
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.unlocks_title").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.unlocks_systems").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.unlocks_base_buffs").withStyle(ChatFormatting.GREEN));
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.unlocks_level_buffs").withStyle(ChatFormatting.AQUA));
        tooltip.accept(Component.empty());
        tooltip.accept(Component.translatable("tooltip.homecraftguild.guild_banner.placement_rule").withStyle(ChatFormatting.GOLD));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
