package ua.homecraft.guild.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;

/** Territory/wall/visual-event Server->Client payload. NPC metadata must not be sent here. */
public record GuildTerritoryVisualSyncPayload(String snapshot) implements CustomPacketPayload {
    public static final Type<GuildTerritoryVisualSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "territory_visual_sync"));
    public static final StreamCodec<ByteBuf, GuildTerritoryVisualSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GuildTerritoryVisualSyncPayload::snapshot,
            GuildTerritoryVisualSyncPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
