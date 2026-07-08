package ua.homecraft.guild.server.bed;

public final class GuildBedBinding {
    public String key;
    public String guildId;
    public String guildName;
    public String territoryId;
    public String dimension;
    public int x;
    public int y;
    public int z;
    public String facing;
    public String ownerUuid;
    public String ownerName;
    public String placedByUuid;
    public String placedByName;
    public String claimedAt;
    public String placedAt;
    public String lastValidatedAt;
    public String status = GuildBedStatus.FREE.name();

    public boolean claimed() {
        return GuildBedStatus.CLAIMED.name().equals(status) && ownerUuid != null && !ownerUuid.isBlank();
    }

    public boolean free() {
        return status == null || status.isBlank() || GuildBedStatus.FREE.name().equals(status);
    }

    public String coords() {
        String coords = x + ", " + y + ", " + z;
        String dim = dimension == null ? "" : dimension.replace("minecraft:", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (dim.isBlank() || "overworld".equals(dim)) return coords;
        if ("the_nether".equals(dim) || "nether".equals(dim)) return "Незер · " + coords;
        if ("the_end".equals(dim) || "end".equals(dim)) return "Енд · " + coords;
        return dim + " · " + coords;
    }
}
