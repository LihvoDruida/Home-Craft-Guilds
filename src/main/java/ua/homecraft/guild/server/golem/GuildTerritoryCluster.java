package ua.homecraft.guild.server.golem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import ua.homecraft.guild.server.GuildStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class GuildTerritoryCluster {
    public final String guildId;
    public final String dimension;
    public final long revision;
    public final List<GuildStore.Territory> territories;
    public final int minX;
    public final int maxX;
    public final int minZ;
    public final int maxZ;
    public final int centerX;
    public final int centerZ;
    public final GuildStore.Territory mainTotem;

    GuildTerritoryCluster(String guildId, String dimension, long revision, List<GuildStore.Territory> territories) {
        this.guildId = guildId;
        this.dimension = dimension;
        this.revision = revision;
        List<GuildStore.Territory> normalized = new ArrayList<>();
        if (territories != null) {
            for (GuildStore.Territory territory : territories) {
                if (territory != null && Objects.equals(guildId, territory.guildId) && Objects.equals(dimension, territory.dimension) && "GUILD".equals(territory.type)) normalized.add(territory);
            }
        }
        normalized.sort(Comparator.comparing(t -> String.valueOf(t.id)));
        this.territories = Collections.unmodifiableList(normalized);
        int mnX = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE, mnZ = Integer.MAX_VALUE, mxZ = Integer.MIN_VALUE;
        GuildStore.Territory first = normalized.isEmpty() ? null : normalized.get(0);
        for (GuildStore.Territory territory : normalized) {
            mnX = Math.min(mnX, GuildStore.minX(territory));
            mxX = Math.max(mxX, GuildStore.maxX(territory));
            mnZ = Math.min(mnZ, GuildStore.minZ(territory));
            mxZ = Math.max(mxZ, GuildStore.maxZ(territory));
        }
        if (normalized.isEmpty()) {
            mnX = mxX = mnZ = mxZ = 0;
        }
        this.minX = mnX;
        this.maxX = mxX;
        this.minZ = mnZ;
        this.maxZ = mxZ;
        this.centerX = normalized.isEmpty() ? 0 : (mnX + mxX) / 2;
        this.centerZ = normalized.isEmpty() ? 0 : (mnZ + mxZ) / 2;
        this.mainTotem = first;
    }

    public boolean isEmpty() {
        return territories.isEmpty();
    }

    public boolean contains(BlockPos pos) {
        return contains(pos, 0);
    }

    public boolean contains(BlockPos pos, int buffer) {
        if (pos == null) return false;
        int b = Math.max(0, buffer);
        for (GuildStore.Territory territory : territories) {
            if (territory == null) continue;
            if (pos.getX() >= GuildStore.minX(territory) - b && pos.getX() <= GuildStore.maxX(territory) + b
                    && pos.getZ() >= GuildStore.minZ(territory) - b && pos.getZ() <= GuildStore.maxZ(territory) + b) return true;
        }
        return false;
    }

    public GuildStore.Territory territoryAt(BlockPos pos) {
        if (pos == null) return null;
        for (GuildStore.Territory territory : territories) {
            if (GuildStore.isInsideTerritory(territory, pos)) return territory;
        }
        return null;
    }

    public GuildStore.Territory territoryForPoint(int x, int z) {
        for (GuildStore.Territory territory : territories) {
            if (territory == null) continue;
            if (x >= GuildStore.minX(territory) && x <= GuildStore.maxX(territory) && z >= GuildStore.minZ(territory) && z <= GuildStore.maxZ(territory)) return territory;
        }
        return mainTotem;
    }

    public GuildStore.Territory nearest(double x, double z) {
        GuildStore.Territory best = null;
        double bestDist = Double.MAX_VALUE;
        for (GuildStore.Territory territory : territories) {
            if (territory == null) continue;
            double dx = territory.x + 0.5D - x;
            double dz = territory.z + 0.5D - z;
            double dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = territory;
            }
        }
        return best;
    }

    public ServerLevel levelFrom(ServerLevel fallback) {
        return fallback;
    }
}
