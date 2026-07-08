package ua.homecraft.guild.client;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

public final class GuildClientLayers {
    public static final ModelLayerLocation GUILD_REGISTRAR = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_registrar"),
            "main"
    );

    private GuildClientLayers() {}
}
