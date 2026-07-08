package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

public record OpenGuildNpcAdminPayload(String snapshot) implements CustomPacketPayload {
    public static final Type<OpenGuildNpcAdminPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "open_guild_npc_admin"));
    public static final StreamCodec<ByteBuf, OpenGuildNpcAdminPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, OpenGuildNpcAdminPayload::snapshot,
            OpenGuildNpcAdminPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
