package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

/** NPC skin registry Server->Client payload. Contains only skins2 rows. */
public record GuildNpcSkinRegistryPayload(String snapshot) implements CustomPacketPayload {
    public static final Type<GuildNpcSkinRegistryPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "npc_skin_registry"));
    public static final StreamCodec<ByteBuf, GuildNpcSkinRegistryPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GuildNpcSkinRegistryPayload::snapshot,
            GuildNpcSkinRegistryPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
