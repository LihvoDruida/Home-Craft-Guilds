package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

/** Trade validation result Server->Client payload. The body is schema-versioned snapshot text for v136 compatibility. */
public record GuildNpcTradeValidationResultPayload(String snapshot) implements CustomPacketPayload {
    public static final Type<GuildNpcTradeValidationResultPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "npc_trade_validation_result"));
    public static final StreamCodec<ByteBuf, GuildNpcTradeValidationResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GuildNpcTradeValidationResultPayload::snapshot,
            GuildNpcTradeValidationResultPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
