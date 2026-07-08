package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

public record OpenGuildRosterPayload(String snapshot) implements CustomPacketPayload {
    public static final Type<OpenGuildRosterPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "open_roster"));
    public static final StreamCodec<ByteBuf, OpenGuildRosterPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, OpenGuildRosterPayload::snapshot,
            OpenGuildRosterPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
