package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

/** NPC metadata Server->Client payload. Contains only npc2 rows. */
public record GuildNpcSyncPayload(String snapshot) implements CustomPacketPayload {
    public static final Type<GuildNpcSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "npc_sync"));
    public static final StreamCodec<ByteBuf, GuildNpcSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GuildNpcSyncPayload::snapshot,
            GuildNpcSyncPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
