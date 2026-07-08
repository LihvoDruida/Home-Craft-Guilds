package ua.homecraft.guild.server;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import ua.homecraft.guild.network.GuildActionPayload;

public final class GuildServerPayloadHandler {
    private GuildServerPayloadHandler() {}

    public static void handleAction(GuildActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            GuildServerEvents.handleClientAction(player, payload.action(), payload.a(), payload.b());
        });
    }
}
