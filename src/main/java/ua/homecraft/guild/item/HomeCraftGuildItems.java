package ua.homecraft.guild.item;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.block.HomeCraftGuildBlocks;

public final class HomeCraftGuildItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(HomeCraftGuildMod.MOD_ID);

    public static final DeferredItem<GuildBannerBlockItem> GUILD_BANNER = ITEMS.registerItem(
            "guild_banner",
            props -> new GuildBannerBlockItem(HomeCraftGuildBlocks.GUILD_BANNER.get(), props),
            props -> props.stacksTo(1).useBlockDescriptionPrefix()
    );

    private HomeCraftGuildItems() {}

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
