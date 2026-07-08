package ua.homecraft.guild.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

public final class GuildMarkerEffect extends MobEffect {
    public GuildMarkerEffect(int color) {
        super(MobEffectCategory.BENEFICIAL, color);
    }
}
