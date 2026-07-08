package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

/**
 * Server -> client lightweight visual description.
 * The server sends only static/cheap territory facts; the client renders particles locally.
 */
public record GuildVisualSyncPayload(String snapshot) implements CustomPacketPayload {
    public static final Type<GuildVisualSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "visual_sync"));
    public static final StreamCodec<ByteBuf, GuildVisualSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GuildVisualSyncPayload::snapshot,
            GuildVisualSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
