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
            player.displayClientMessage(Component.literal("Home Craft Guilds: гільдійний тотем не можна ставити у spawn-зоні. Предмет повернуто."), true);
            return InteractionResult.FAIL;
        }
        if (!level.getBlockState(replaceCheckPos).canBeReplaced(checkedContext)) return InteractionResult.FAIL;

        if (!GuildStore.canAddTerritory(player, replaceCheckPos, level, "GUILD")) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: гільдійний тотем не встановлено. Перевір: ти маєш бути Гілдмайстром, не має бути перетину територій і не має бути вичерпаний ліміт."), true);
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
        player.displayClientMessage(Component.literal("Home Craft Guilds: гільдійний тотем встановлено."), true);
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.literal("Гільдійний кристалічний тотем").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.accept(Component.literal("Ставить гільдійну територію. У світі це синьо-білий кристал приблизно на 2 блоки.").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("Рецепт: ABA / BEB / ABA").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.accept(Component.literal("A — уламок аметисту, B — будь-який банер, E — смарагд.").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.empty());
        tooltip.accept(Component.literal("Що відкриває гільдія:").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.accept(Component.literal("• територію, бафи, ліжка, големів і розвиток рівнів").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("• База: +5% XP, +10% зіль; вище — через таланти").withStyle(ChatFormatting.GREEN));
        tooltip.accept(Component.literal("• бонуси здоров’я, броні, шкоди й нічний зір через рівні").withStyle(ChatFormatting.AQUA));
        tooltip.accept(Component.empty());
        tooltip.accept(Component.literal("Ставити може лише Гілдмайстер поза spawn-зоною і без перетину територій.").withStyle(ChatFormatting.GOLD));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
