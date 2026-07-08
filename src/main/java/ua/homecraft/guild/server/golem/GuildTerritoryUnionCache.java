package ua.homecraft.guild.server.golem;

import ua.homecraft.guild.server.GuildStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GuildTerritoryUnionCache {
    private static final Map<String, GuildTerritoryCluster> CACHE = new HashMap<>();

    private GuildTerritoryUnionCache() {}

    public static synchronized GuildTerritoryCluster cluster(String guildId, String dimension) {
        long revision = GuildStore.territoryTopologyRevision();
        String key = String.valueOf(guildId) + "|" + String.valueOf(dimension) + "|" + revision;
        GuildTerritoryCluster cached = CACHE.get(key);
        if (cached != null) return cached;
        if (CACHE.size() > 256) CACHE.clear();
        List<GuildStore.Territory> territories = GuildStore.guildTerritories(guildId);
        GuildTerritoryCluster cluster = new GuildTerritoryCluster(guildId, dimension, revision, territories);
        CACHE.put(key, cluster);
        return cluster;
    }

    public static synchronized void invalidate() {
        CACHE.clear();
    }
}
