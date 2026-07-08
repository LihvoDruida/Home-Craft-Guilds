package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

public record GuildActionPayload(String action, String a, String b) implements CustomPacketPayload {
    public static final Type<GuildActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_action"));
    public static final StreamCodec<ByteBuf, GuildActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GuildActionPayload::action,
            ByteBufCodecs.STRING_UTF8, GuildActionPayload::a,
            ByteBufCodecs.STRING_UTF8, GuildActionPayload::b,
            GuildActionPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
