package ua.homecraft.guild.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import ua.homecraft.guild.server.GuildServerPayloadHandler;

public final class GuildNetworking {
    private GuildNetworking() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(OpenGuildRosterPayload.TYPE, OpenGuildRosterPayload.STREAM_CODEC);
        registrar.playToClient(OpenCreateGuildPayload.TYPE, OpenCreateGuildPayload.STREAM_CODEC);
        registrar.playToClient(OpenGuildNpcAdminPayload.TYPE, OpenGuildNpcAdminPayload.STREAM_CODEC); // legacy open screen
        registrar.playToClient(GuildNpcAdminSnapshotPayload.TYPE, GuildNpcAdminSnapshotPayload.STREAM_CODEC);
        registrar.playToClient(GuildNpcTradeValidationResultPayload.TYPE, GuildNpcTradeValidationResultPayload.STREAM_CODEC);
        registrar.playToClient(GuildNpcSkinRegistryPayload.TYPE, GuildNpcSkinRegistryPayload.STREAM_CODEC);
        registrar.playToClient(GuildNpcSyncPayload.TYPE, GuildNpcSyncPayload.STREAM_CODEC);
        registrar.playToClient(GuildTerritoryVisualSyncPayload.TYPE, GuildTerritoryVisualSyncPayload.STREAM_CODEC);
        registrar.playToClient(GuildVisualSyncPayload.TYPE, GuildVisualSyncPayload.STREAM_CODEC); // legacy visual/event transport
        registrar.playToServer(GuildActionPayload.TYPE, GuildActionPayload.STREAM_CODEC, GuildServerPayloadHandler::handleAction);
    }
}
