package ua.homecraft.guild.effect;

import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.homecraft.guild.HomeCraftGuildMod;

public final class HomeCraftGuildEffects {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(net.minecraft.core.registries.Registries.MOB_EFFECT, HomeCraftGuildMod.MOD_ID);

    // Kept for backward compatibility with old saved worlds/effects. New level buffs use the effects below.
    public static final DeferredHolder<MobEffect, MobEffect> GUILD_PASSIVE = EFFECTS.register(
            "guild_passive",
            () -> new GuildMarkerEffect(0x9D5CFF)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_PLAYER_XP = EFFECTS.register(
            "guild_player_xp",
            () -> new GuildMarkerEffect(0x7BD7FF)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_POTION_DURATION = EFFECTS.register(
            "guild_potion_duration",
            () -> new GuildMarkerEffect(0xB58CFF)
    );

    // This is intentionally not applied to the player as a potion effect.
    // It exists as a named/iconed guild buff for UI/docs while the actual bonus is stored in GuildStore.
    public static final DeferredHolder<MobEffect, MobEffect> GUILD_MOB_XP = EFFECTS.register(
            "guild_mob_xp",
            () -> new GuildMarkerEffect(0xFFD66B)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_TERRITORY_HEALTH = EFFECTS.register(
            "guild_territory_health",
            () -> new GuildMarkerEffect(0x4EE06C)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_HEALTH = EFFECTS.register(
            "guild_health",
            () -> new GuildMarkerEffect(0x4EE06C)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_ARMOR = EFFECTS.register(
            "guild_armor",
            () -> new GuildMarkerEffect(0x6BA8FF)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_DAMAGE = EFFECTS.register(
            "guild_damage",
            () -> new GuildMarkerEffect(0xFFB347)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_NIGHT_VISION = EFFECTS.register(
            "guild_night_vision",
            () -> new GuildMarkerEffect(0x86FFB7)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_WATER_BREATHING = EFFECTS.register(
            "guild_water_breathing",
            () -> new GuildMarkerEffect(0x5FD7FF)
    );

    public static final DeferredHolder<MobEffect, MobEffect> GUILD_FIRE_RESISTANCE = EFFECTS.register(
            "guild_fire_resistance",
            () -> new GuildMarkerEffect(0xFF8A3D)
    );

    private HomeCraftGuildEffects() {}

    public static void register(IEventBus bus) {
        EFFECTS.register(bus);
    }
}
