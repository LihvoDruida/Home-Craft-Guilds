package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

/** Typed replacement for the old open_npc_admin body. Kept separate from territory visuals. */
public record GuildNpcAdminSnapshotPayload(String snapshot) implements CustomPacketPayload {
    public static final Type<GuildNpcAdminSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "npc_admin_snapshot"));
    public static final StreamCodec<ByteBuf, GuildNpcAdminSnapshotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GuildNpcAdminSnapshotPayload::snapshot,
            GuildNpcAdminSnapshotPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
