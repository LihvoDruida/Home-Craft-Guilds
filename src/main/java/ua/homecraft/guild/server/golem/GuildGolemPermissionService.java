package ua.homecraft.guild.server.golem;

import net.minecraft.server.level.ServerPlayer;
import ua.homecraft.guild.server.GuildRank;
import ua.homecraft.guild.server.GuildStore;

public final class GuildGolemPermissionService {
    private GuildGolemPermissionService() {}

    public static boolean canManage(ServerPlayer player) {
        GuildRank rank = GuildStore.rankOf(player);
        return rank == GuildRank.GUILDMASTER || rank == GuildRank.QUARTERMASTER;
    }

    public static boolean canView(ServerPlayer player, String guildId) {
        return player != null && guildId != null && GuildStore.isMemberOfGuild(player, guildId);
    }
}
