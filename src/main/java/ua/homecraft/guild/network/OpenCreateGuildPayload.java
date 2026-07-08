package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

public record OpenCreateGuildPayload(String message) implements CustomPacketPayload {
    public static final Type<OpenCreateGuildPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "open_create_guild"));
    public static final StreamCodec<ByteBuf, OpenCreateGuildPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, OpenCreateGuildPayload::message,
            OpenCreateGuildPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
