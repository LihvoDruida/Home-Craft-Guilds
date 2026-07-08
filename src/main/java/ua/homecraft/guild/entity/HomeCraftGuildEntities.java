package ua.homecraft.guild.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.homecraft.guild.HomeCraftGuildMod;

public final class HomeCraftGuildEntities {
    private static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(HomeCraftGuildMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<GuildRegistrarEntity>> GUILD_REGISTRAR = ENTITIES.registerEntityType(
            "guild_registrar",
            GuildRegistrarEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.6F, 1.95F).eyeHeight(1.75F).clientTrackingRange(10).updateInterval(3).noLootTable()
    );

    private HomeCraftGuildEntities() {}

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
        bus.addListener(HomeCraftGuildEntities::registerAttributes);
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(GUILD_REGISTRAR.get(), GuildRegistrarEntity.createAttributes().build());
    }
}
