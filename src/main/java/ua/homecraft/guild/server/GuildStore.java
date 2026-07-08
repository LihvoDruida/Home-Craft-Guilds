package ua.homecraft.guild.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ua.homecraft.guild.server.bed.GuildBedBinding;
import ua.homecraft.guild.server.bed.GuildBedPositionResolver;
import ua.homecraft.guild.server.bed.GuildBedStatus;
import ua.homecraft.guild.server.golem.GuildGolemStats;
import ua.homecraft.guild.HomeCraftGuildMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class GuildStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Data DATA = new Data();

    // Fast runtime indexes. With hundreds/thousands of guild territories, linear scans in
    // territoryAt()/guildTerritories()/golem(uuid) become the main TPS cost. These indexes
    // are rebuilt only when the underlying collections change, not on every save.
    private static final int TERRITORY_INDEX_CELL_SHIFT = 6; // 64x64 blocks, aligned with default guild claims.
    private static final Map<String, List<Territory>> TERRITORIES_BY_DIM_CELL = new HashMap<>();
    private static final Map<String, List<Territory>> TERRITORIES_BY_GUILD = new HashMap<>();
    private static final Map<String, Territory> TERRITORIES_BY_BANNER = new HashMap<>();
    private static final Map<String, TerritoryStaticInfo> TERRITORY_STATIC_BY_ID = new HashMap<>();
    private static List<TerritoryStaticInfo> TERRITORY_STATIC_SNAPSHOT = Collections.emptyList();
    private static final Map<String, Golem> GOLEMS_BY_UUID = new HashMap<>();
    private static final Map<String, List<Golem>> GOLEMS_BY_GUILD = new HashMap<>();
    private static final Map<String, GuildBedBinding> GUILD_BEDS_BY_KEY = new HashMap<>();
    private static final Map<String, GuildBedBinding> GUILD_BEDS_BY_PLAYER = new HashMap<>();
    private static final Map<String, List<GuildBedBinding>> GUILD_BEDS_BY_LABEL_CELL = new HashMap<>();
    private static boolean guildBedIndexDirty = true;
    private static long guildBedsRevision = 1L;
    private static boolean territoryIndexesDirty = true;
    private static boolean golemIndexDirty = true;
    private static long territoryTopologyRevision = 1L;
    private static long golemTopologyRevision = 1L;
    private static long guildRuntimeRevision = 1L;

    // Hot-path golem position updates must not write the full JSON file every few ticks.
    // A single server with hundreds of golems can otherwise stall the main thread on disk IO.
    private static final long DEFERRED_HOT_SAVE_INTERVAL_MS = 120_000L;
    private static boolean deferredHotSaveDirty = false;
    private static long deferredHotSaveLastMs = 0L;

    private static Path file;
    private static MinecraftServer runtimeServer;
    private static GuildSpawnBoundary.SpawnArea SPAWN_AREA;
    private static final int GOLEM_ROUTE_LEARNING_CELL_SHIFT = 4; // 16x16 blocks: compact enough for many guilds.
    private static final int GOLEM_ROUTE_LEARNING_CELL_SIZE = 1 << GOLEM_ROUTE_LEARNING_CELL_SHIFT;
    private static final int GOLEM_ROUTE_LEARNING_GLOBAL_CAP = 4096;
    private static final int GOLEM_ROUTE_LEARNING_PER_GUILD_DIM_CAP = 96;
    private static final long GOLEM_ROUTE_LEARNING_SAVE_INTERVAL_MS = 180_000L;
    private static long golemRouteLearningLastSaveMs = 0L;
    private static boolean golemRouteLearningDirty = false;

    public static final String ELITE_GOLEM_NAME = "РІК";
    private static final String[] GOLEM_NAME_POOL = {
            "R2-D2", "C-3PO", "BB-8", "Bender", "Calculon", "Clamps", "Robot Devil", "Flexo",
            "WALL-E", "EVE", "Baymax", "Optimus", "Bumblebee", "Johnny 5", "T-800", "Data",
            "Marvin", "Astro", "Rosie", "Robby", "Gort", "K-2SO", "Chappie", "Dot Matrix",
            "Mega Man", "Ultron", "Vision", "Iron Giant", "Canti", "Gir"
    };

    private GuildStore() {}

    public static synchronized void load(MinecraftServer server) {
        runtimeServer = server;
        Path configDir = server.getServerDirectory().resolve("config");
        file = configDir.resolve("homecraft-guilds.json");
        try { Files.createDirectories(configDir); } catch (IOException ignored) {}
        if (Files.exists(file)) {
            try {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) copyFrom(loaded);
            } catch (Exception e) {
                HomeCraftGuildMod.LOGGER.warn("Could not read Home Craft guild store: {}", file, e);
            }
        }
        sanitize();
        refreshSpawnArea(server);
        validateGuildBedsOnServerStart(server);
        int removedSpawnClaims = removeSpawnConflictingTerritories(false);
        if (removedSpawnClaims > 0) {
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: removed {} old territory claim(s) inside spawn area", removedSpawnClaims);
        }
        save();
    }

    private static void copyFrom(Data other) {
        DATA.guilds = other.guilds == null ? new LinkedHashMap<>() : other.guilds;
        DATA.members = other.members == null ? new LinkedHashMap<>() : other.members;
        DATA.territories = other.territories == null ? new ArrayList<>() : other.territories;
        DATA.bedOwners = other.bedOwners == null ? new LinkedHashMap<>() : other.bedOwners;
        DATA.guildBeds = other.guildBeds == null ? new LinkedHashMap<>() : other.guildBeds;
        DATA.invites = other.invites == null ? new LinkedHashMap<>() : other.invites;
        DATA.invitesV2 = other.invitesV2 == null ? new LinkedHashMap<>() : other.invitesV2;
        DATA.golems = other.golems == null ? new ArrayList<>() : other.golems;
        DATA.guildAchievements = other.guildAchievements == null ? new LinkedHashMap<>() : other.guildAchievements;
        DATA.golemRouteLearning = other.golemRouteLearning == null ? new LinkedHashMap<>() : other.golemRouteLearning;
        markTerritoryIndexesDirty();
        markGolemIndexDirty();
        markGuildBedIndexDirty();
    }

    private static void sanitize() {
        if (DATA.guilds == null) DATA.guilds = new LinkedHashMap<>();
        if (DATA.members == null) DATA.members = new LinkedHashMap<>();
        if (DATA.territories == null) DATA.territories = new ArrayList<>();
        if (DATA.bedOwners == null) DATA.bedOwners = new LinkedHashMap<>();
        if (DATA.guildBeds == null) DATA.guildBeds = new LinkedHashMap<>();
        if (DATA.invites == null) DATA.invites = new LinkedHashMap<>();
        if (DATA.invitesV2 == null) DATA.invitesV2 = new LinkedHashMap<>();
        if (DATA.golems == null) DATA.golems = new ArrayList<>();
        if (DATA.guildAchievements == null) DATA.guildAchievements = new LinkedHashMap<>();
        if (DATA.golemRouteLearning == null) DATA.golemRouteLearning = new LinkedHashMap<>();
        DATA.guildAchievements.entrySet().removeIf(e -> e == null || e.getKey() == null || !DATA.guilds.containsKey(e.getKey()));
        for (Map<String, GuildAchievement> map : DATA.guildAchievements.values()) if (map != null) map.entrySet().removeIf(e -> e == null || e.getKey() == null || e.getValue() == null);
        migrateLegacyGuildBeds();
        sanitizeGuildBeds();
        sanitizeGolemRouteLearning();
        cleanupInvalidGuildTotemRecords();
        for (Guild guild : DATA.guilds.values()) {
            sanitizeGuildProgress(guild);
            sanitizeGuildTalents(guild);
        }
        DATA.golems.removeIf(g -> g == null || g.uuid == null);
        migrateGolemRecords();
        for (Golem golem : DATA.golems) ensureGolemDisplayName(golem);
        markTerritoryIndexesDirty();
        markGolemIndexDirty();
        markGuildBedIndexDirty();
    }


    private static void cleanupInvalidGuildTotemRecords() {
        if (DATA.territories == null || DATA.territories.isEmpty()) return;
        List<Territory> kept = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenBannerKeys = new HashSet<>();
        Set<String> removedIds = new HashSet<>();
        int removedMalformed = 0;
        for (Territory territory : DATA.territories) {
            if (territory == null) {
                removedMalformed++;
                continue;
            }
            boolean guildTotem = "GUILD".equals(territory.type);
            String id = territory.id == null ? "" : territory.id.trim();
            String dimension = territory.dimension == null ? "" : territory.dimension.trim();
            boolean remove = id.isBlank()
                    || !seenIds.add(id)
                    || dimension.isBlank()
                    || territory.size <= 0;
            if (guildTotem) {
                remove = remove
                        || territory.guildId == null
                        || territory.guildId.isBlank()
                        || !DATA.guilds.containsKey(territory.guildId);
                String key = bannerKey(dimension, territory.x, territory.y, territory.z);
                if (!seenBannerKeys.add(key)) remove = true;
            }
            if (remove) {
                removedMalformed++;
                if (!id.isBlank()) removedIds.add(id);
                continue;
            }
            territory.id = id;
            territory.dimension = dimension;
            kept.add(territory);
        }
        if (removedMalformed <= 0) return;
        DATA.territories = kept;
        cleanupTerritoryDependents(removedIds);
        markTerritoryIndexesDirty();
        markGuildBedIndexDirty();
        markGuildRuntimeDirty();
        if (HomeCraftGuildConfig.debug()) {
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: removed {} invalid/inactive guild totem territory record(s) during store sanitize", removedMalformed);
        }
    }

    public static synchronized int removeTerritoriesByIds(Collection<String> ids, String reason, boolean saveAfter) {
        if (ids == null || ids.isEmpty() || DATA.territories == null || DATA.territories.isEmpty()) return 0;
        Set<String> removeIds = new HashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) removeIds.add(id);
        }
        if (removeIds.isEmpty()) return 0;
        int before = DATA.territories.size();
        DATA.territories.removeIf(t -> t != null && t.id != null && removeIds.contains(t.id));
        int removed = before - DATA.territories.size();
        if (removed <= 0) return 0;
        cleanupTerritoryDependents(removeIds);
        markTerritoryIndexesDirty();
        markGuildBedIndexDirty();
        markGuildRuntimeDirty();
        if (saveAfter) save();
        if (HomeCraftGuildConfig.debug()) {
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: removed {} guild territory/totem record(s), reason={}", removed, reason == null ? "cleanup" : reason);
        }
        return removed;
    }

    private static void cleanupTerritoryDependents(Set<String> removedTerritoryIds) {
        if (removedTerritoryIds == null || removedTerritoryIds.isEmpty()) return;
        if (DATA.bedOwners != null) {
            DATA.bedOwners.entrySet().removeIf(e -> {
                String key = e == null ? null : e.getKey();
                if (key == null) return false;
                for (String territoryId : removedTerritoryIds) {
                    if (key.startsWith(territoryId + ":")) return true;
                }
                return false;
            });
        }
        if (DATA.guildBeds != null) {
            for (GuildBedBinding bed : DATA.guildBeds.values()) {
                if (bed == null || !removedTerritoryIds.contains(bed.territoryId)) continue;
                freeBedInternal(bed, true);
                bed.status = GuildBedStatus.INVALID_TERRITORY.name();
                bed.lastValidatedAt = Instant.now().toString();
            }
        }
    }


    private static void migrateLegacyGuildBeds() {
        if (DATA.bedOwners == null || DATA.bedOwners.isEmpty()) return;
        if (DATA.guildBeds == null) DATA.guildBeds = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, String> entry : new ArrayList<>(DATA.bedOwners.entrySet())) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
            String[] p = entry.getKey().split(":");
            if (p.length < 4) continue;
            Territory territory = territoryById(p[0]);
            if (territory == null || !"GUILD".equals(territory.type)) continue;
            try {
                int x = Integer.parseInt(p[p.length - 3]);
                int y = Integer.parseInt(p[p.length - 2]);
                int z = Integer.parseInt(p[p.length - 1]);
                String key = guildBedKey(territory.dimension, new BlockPos(x, y, z));
                GuildBedBinding bed = DATA.guildBeds.computeIfAbsent(key, k -> new GuildBedBinding());
                bed.key = key;
                bed.guildId = territory.guildId;
                bed.guildName = territory.guildName;
                bed.territoryId = territory.id;
                bed.dimension = territory.dimension;
                bed.x = x; bed.y = y; bed.z = z;
                bed.ownerUuid = entry.getValue();
                bed.ownerName = memberName(territory.guildId, entry.getValue());
                bed.status = GuildBedStatus.CLAIMED.name();
                bed.claimedAt = bed.claimedAt == null ? Instant.now().toString() : bed.claimedAt;
                changed = true;
            } catch (Exception ignored) {}
        }
        if (changed) DATA.bedOwners.clear();
    }

    private static void sanitizeGuildBeds() {
        if (DATA.guildBeds == null) DATA.guildBeds = new LinkedHashMap<>();
        Map<String, GuildBedBinding> normalized = new LinkedHashMap<>();
        Set<String> claimedPlayers = new HashSet<>();
        boolean changed = false;
        for (GuildBedBinding bed : DATA.guildBeds.values()) {
            if (bed == null || bed.dimension == null || bed.dimension.isBlank()) { changed = true; continue; }
            String key = guildBedKey(bed.dimension, new BlockPos(bed.x, bed.y, bed.z));
            bed.key = key;
            Territory territory = territoryById(bed.territoryId);
            if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, bed.guildId)) {
                markInactiveGuildBed(bed, GuildBedStatus.INVALID_TERRITORY);
                changed = true;
            } else if (isInactiveGuildBedStatus(bed.status)) {
                if ((bed.ownerUuid != null && !bed.ownerUuid.isBlank()) || (bed.ownerName != null && !bed.ownerName.isBlank())) {
                    markInactiveGuildBed(bed, GuildBedStatus.valueOf(bed.status));
                    changed = true;
                }
            }
            if (bed.claimed()) {
                String playerKey = bed.ownerUuid.toLowerCase(Locale.ROOT);
                if (claimedPlayers.contains(playerKey)) {
                    markInactiveGuildBed(bed, GuildBedStatus.FREE);
                    changed = true;
                } else {
                    claimedPlayers.add(playerKey);
                    if (bed.ownerName == null || bed.ownerName.isBlank()) bed.ownerName = memberName(bed.guildId, bed.ownerUuid);
                }
            }
            normalized.put(key, bed);
        }
        if (changed || normalized.size() != DATA.guildBeds.size()) DATA.guildBeds = normalized;
    }

    private static boolean isInactiveGuildBedStatus(String status) {
        return GuildBedStatus.BROKEN.name().equals(status)
                || GuildBedStatus.INVALID_TERRITORY.name().equals(status)
                || GuildBedStatus.ORPHAN.name().equals(status);
    }

    private static void markInactiveGuildBed(GuildBedBinding bed, GuildBedStatus status) {
        if (bed == null || status == null) return;
        bed.ownerUuid = "";
        bed.ownerName = "";
        bed.claimedAt = "";
        bed.status = status.name();
        bed.lastValidatedAt = Instant.now().toString();
    }

    private static void validateGuildBedsOnServerStart(MinecraftServer server) {
        if (server == null || DATA.guildBeds == null || DATA.guildBeds.isEmpty()) return;
        if (!HomeCraftGuildConfig.guildBedsEnabled() || !HomeCraftGuildConfig.guildBedsValidateOnServerStart()) return;
        boolean changed = false;
        for (GuildBedBinding bed : DATA.guildBeds.values()) {
            if (validateGuildBedBinding(server, bed, false)) changed = true;
        }
        if (changed) {
            markGuildBedIndexDirty();
            save();
        }
    }

    public static synchronized boolean validateGuildBedBinding(MinecraftServer server, GuildBedBinding bed, boolean saveImmediately) {
        if (server == null || bed == null || bed.dimension == null || bed.dimension.isBlank()) return false;
        ServerLevel level = levelForDimension(server, bed.dimension);
        if (level == null) return false;
        BlockPos pos = new BlockPos(bed.x, bed.y, bed.z);
        boolean loaded = true;
        try { loaded = level.isLoaded(pos); } catch (Throwable ignored) {}
        if (!loaded) return false;

        boolean changed = false;
        Territory territory = territoryAt(level, pos);
        if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, bed.guildId)) {
            freeBedInternal(bed, true);
            bed.status = GuildBedStatus.INVALID_TERRITORY.name();
            bed.lastValidatedAt = Instant.now().toString();
            changed = true;
        } else {
            BlockState state = level.getBlockState(pos);
            if (!GuildBedPositionResolver.isBed(state)) {
                freeBedInternal(bed, true);
                bed.status = GuildBedStatus.BROKEN.name();
                bed.lastValidatedAt = Instant.now().toString();
                changed = true;
            } else {
                bed.lastValidatedAt = Instant.now().toString();
            }
        }
        if (changed) {
            markGuildBedIndexDirty();
            if (saveImmediately) save();
        }
        return changed;
    }

    private static ServerLevel levelForDimension(MinecraftServer server, String dimension) {
        if (server == null || dimension == null || dimension.isBlank()) return null;
        try {
            for (ServerLevel candidate : server.getAllLevels()) {
                if (candidate != null && dimension.equals(dimensionId(candidate))) return candidate;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Territory territoryById(String id) {
        if (id == null || DATA.territories == null) return null;
        for (Territory territory : DATA.territories) if (territory != null && Objects.equals(id, territory.id)) return territory;
        return null;
    }

    private static String memberName(String guildId, String uuid) {
        Guild guild = guildId == null ? null : DATA.guilds.get(guildId);
        Member member = guild == null || guild.members == null || uuid == null ? null : guild.members.get(uuid);
        return member == null || member.name == null ? "" : member.name;
    }

    private static void sanitizeGuildProgress(Guild guild) {
        if (guild == null) return;
        guild.experience = Math.max(0, guild.experience);
        int levelFromXp = GuildLevelTiers.levelForXp(guild.experience);
        if (guild.level < 1) guild.level = levelFromXp;
        guild.level = GuildLevelTiers.clampLevel(Math.max(guild.level, levelFromXp));
        int minXpForStoredLevel = GuildLevelTiers.totalXpForLevel(guild.level);
        if (guild.experience < minXpForStoredLevel) guild.experience = minXpForStoredLevel;
    }

    private static void sanitizeGuildTalents(Guild guild) {
        if (guild == null) return;
        if (guild.talents == null) guild.talents = new GuildTalentData();
        guild.talents.version = Math.max(1, guild.talents.version);
        if (guild.talents.member == null) guild.talents.member = new GuildTalentBranchData();
        if (guild.talents.golem == null) guild.talents.golem = new GuildTalentBranchData();
        if (guild.talents.member.unlocked == null) guild.talents.member.unlocked = new ArrayList<>();
        if (guild.talents.golem.unlocked == null) guild.talents.golem.unlocked = new ArrayList<>();
        if (guild.talents.unlocked != null && !guild.talents.unlocked.isEmpty()) {
            for (String raw : guild.talents.unlocked) {
                GuildTalents.TalentDefinition definition = GuildTalents.byId(raw);
                if (definition == null) continue;
                List<String> target = GuildTalents.GOLEM.equals(definition.branch()) ? guild.talents.golem.unlocked : guild.talents.member.unlocked;
                if (!target.contains(definition.id())) target.add(definition.id());
            }
            guild.talents.unlocked = null;
        }
        normalizeUnlockedTalentList(guild.talents.member.unlocked, GuildTalents.MEMBER);
        normalizeUnlockedTalentList(guild.talents.golem.unlocked, GuildTalents.GOLEM);
        guild.talents.lastResetAt = Math.max(0L, guild.talents.lastResetAt);
    }

    private static void normalizeUnlockedTalentList(List<String> ids, String branch) {
        if (ids == null) return;
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String raw : ids) {
            GuildTalents.TalentDefinition definition = GuildTalents.byId(raw);
            if (definition != null && definition.branch().equals(branch)) clean.add(definition.id());
        }
        ids.clear();
        ids.addAll(clean);
    }


    private static void migrateGolemRecords() {
        if (DATA.golems == null) return;
        boolean changed = false;
        for (Golem golem : DATA.golems) {
            if (golem == null) continue;
            boolean elite = golem.elite || String.valueOf(golem.golemType == null ? golem.type : golem.golemType).toLowerCase(Locale.ROOT).contains("elite");
            golem.elite = elite;
            if (golem.golemType == null || golem.golemType.isBlank()) {
                golem.golemType = elite ? "ELITE" : "NORMAL";
                changed = true;
            }
            if (golem.behaviorProfile == null || golem.behaviorProfile.isBlank()) {
                golem.behaviorProfile = elite ? "ELITE_DEFENDER" : "NORMAL_GUARD";
                changed = true;
            }
            if (golem.assignedRole == null || golem.assignedRole.isBlank()) {
                golem.assignedRole = elite ? "ELITE_COMMAND" : "ROAMING";
                changed = true;
            }
            if (golem.aiState == null || golem.aiState.isBlank()) {
                golem.aiState = elite ? (golem.dead ? "ELITE_DEAD" : "ELITE_IDLE") : (golem.dead ? "NORMAL_DEAD" : "NORMAL_IDLE");
                changed = true;
            }
            if (golem.status == null || golem.status.isBlank()) {
                if (golem.removed) golem.status = "REMOVED";
                else if (golem.guildId == null || golem.guildId.isBlank() || !DATA.guilds.containsKey(golem.guildId)) golem.status = "ORPHAN";
                else golem.status = golem.dead ? "DEAD" : "ALIVE";
                changed = true;
            }
            int oldStatsVersion = golem.golemStatsVersion;
            double oldHealthMultiplier = golem.healthMultiplier;
            double oldDamageMultiplier = golem.damageMultiplier;
            int oldHealthBonus = golem.healthBonusPercent;
            int oldDamageBonus = golem.damageBonusPercent;
            int oldMaxHealth = golem.maxHealth;
            int oldGuildMaxHealth = golem.guildMaxHealth;
            double oldGuildDamage = golem.guildDamage;
            GuildGolemStats.ensureRecordMultipliers(golem);
            if (oldStatsVersion != golem.golemStatsVersion
                    || Double.compare(oldHealthMultiplier, golem.healthMultiplier) != 0
                    || Double.compare(oldDamageMultiplier, golem.damageMultiplier) != 0
                    || oldHealthBonus != golem.healthBonusPercent
                    || oldDamageBonus != golem.damageBonusPercent
                    || oldMaxHealth != golem.maxHealth
                    || oldGuildMaxHealth != golem.guildMaxHealth
                    || Double.compare(oldGuildDamage, golem.guildDamage) != 0) changed = true;
            if (golem.entityUuid == null || golem.entityUuid.isBlank()) golem.entityUuid = golem.uuid;
            if (golem.lastSafeX == 0 && golem.lastSafeY == 0 && golem.lastSafeZ == 0) {
                golem.lastSafeX = golem.x;
                golem.lastSafeY = golem.y;
                golem.lastSafeZ = golem.z;
            }
        }
        if (changed) HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: migrated guild golem records to state-machine metadata.");
    }

    public static synchronized void refreshSpawnArea(MinecraftServer server) {
        SPAWN_AREA = GuildSpawnBoundary.resolve(server);
    }

    /**
     * Deletes legacy claims whose banner/center is inside spawn or whose whole square is swallowed by spawn.
     * Claims that only cross the spawn border are kept; protection lookup clips them at the spawn boundary.
     */
    public static synchronized int removeSpawnConflictingTerritories(boolean saveAfter) {
        if (SPAWN_AREA == null || DATA.territories == null || DATA.territories.isEmpty()) return 0;
        int before = DATA.territories.size();
        DATA.territories.removeIf(t -> t != null && isSpawnConflictingLegacyClaim(t));
        int removed = before - DATA.territories.size();
        if (removed > 0) markTerritoryIndexesDirty();
        if (removed > 0 && saveAfter) save();
        return removed;
    }

    private static boolean isSpawnConflictingLegacyClaim(Territory territory) {
        if (territory == null || SPAWN_AREA == null) return false;
        if (SPAWN_AREA.contains(territory.dimension, territory.x, territory.z)) return true;
        return SPAWN_AREA.fullyContainsSquare(
                territory.dimension,
                minX(territory), maxX(territory),
                minZ(territory), maxZ(territory)
        );
    }

    public static synchronized boolean isSpawnPosition(LevelAccessor level, BlockPos pos) {
        if (SPAWN_AREA == null) return false;
        return SPAWN_AREA.contains(level, pos);
    }

    private static void markTerritoryIndexesDirty() {
        territoryIndexesDirty = true;
        territoryTopologyRevision++;
    }

    private static void markGolemIndexDirty() {
        golemIndexDirty = true;
        golemTopologyRevision++;
    }

    private static void markGuildBedIndexDirty() {
        guildBedIndexDirty = true;
        guildBedsRevision++;
    }

    private static void markGuildRuntimeDirty() {
        guildRuntimeRevision++;
    }

    public static synchronized long territoryTopologyRevision() {
        return territoryTopologyRevision;
    }

    public static synchronized long golemTopologyRevision() {
        return golemTopologyRevision;
    }

    public static synchronized long guildRuntimeRevision() {
        return guildRuntimeRevision;
    }

    private static String territoryCellKey(String dimension, int cellX, int cellZ) {
        return String.valueOf(dimension == null ? "" : dimension) + ":" + cellX + ":" + cellZ;
    }

    private static void ensureTerritoryIndexes() {
        if (!territoryIndexesDirty) return;
        TERRITORIES_BY_DIM_CELL.clear();
        TERRITORIES_BY_GUILD.clear();
        TERRITORIES_BY_BANNER.clear();
        TERRITORY_STATIC_BY_ID.clear();
        for (Territory territory : DATA.territories) {
            if (territory == null) continue;
            if ("GUILD".equals(territory.type) && territory.guildId != null) {
                TERRITORIES_BY_GUILD.computeIfAbsent(territory.guildId, ignored -> new ArrayList<>()).add(territory);
            }
            String bannerKey = bannerKey(territory.dimension, territory.x, territory.y, territory.z);
            TERRITORIES_BY_BANNER.put(bannerKey, territory);
            String dim = territory.dimension == null ? "" : territory.dimension;
            int minCellX = Math.floorDiv(minX(territory), 1 << TERRITORY_INDEX_CELL_SHIFT);
            int maxCellX = Math.floorDiv(maxX(territory), 1 << TERRITORY_INDEX_CELL_SHIFT);
            int minCellZ = Math.floorDiv(minZ(territory), 1 << TERRITORY_INDEX_CELL_SHIFT);
            int maxCellZ = Math.floorDiv(maxZ(territory), 1 << TERRITORY_INDEX_CELL_SHIFT);
            for (int cx = minCellX; cx <= maxCellX; cx++) {
                for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                    TERRITORIES_BY_DIM_CELL.computeIfAbsent(territoryCellKey(dim, cx, cz), ignored -> new ArrayList<>()).add(territory);
                }
            }
        }
        List<TerritoryStaticInfo> staticInfos = new ArrayList<>();
        for (Territory territory : DATA.territories) {
            if (territory == null) continue;
            TerritoryStaticInfo info = buildTerritoryStaticInfo(territory);
            if (info != null) {
                TERRITORY_STATIC_BY_ID.put(info.id, info);
                staticInfos.add(info);
            }
        }
        TERRITORY_STATIC_SNAPSHOT = Collections.unmodifiableList(staticInfos);
        territoryIndexesDirty = false;
    }

    private static String bannerKey(String dimension, int x, int y, int z) {
        return String.valueOf(dimension == null ? "" : dimension) + ":" + x + ":" + y + ":" + z;
    }

    private static TerritoryStaticInfo buildTerritoryStaticInfo(Territory territory) {
        if (territory == null) return null;
        int minX = minX(territory);
        int maxX = maxX(territory);
        int minZ = minZ(territory);
        int maxZ = maxZ(territory);
        int width = Math.max(0, maxX - minX + 1);
        int depth = Math.max(0, maxZ - minZ + 1);
        // Raw topology only: server keeps identity, bounds and lightweight counters for AI/cache keys.
        // Visual side masks, internal hidden segments and mixed border colors are calculated on client.
        boolean northOpen = true;
        boolean southOpen = true;
        boolean westOpen = true;
        boolean eastOpen = true;
        int openSides = 4;
        int perimeter = width * 2 + depth * 2;
        return new TerritoryStaticInfo(
                territory.id,
                territory.type,
                territory.guildId,
                territory.guildName,
                territory.guildColor,
                territory.dimension,
                territory.x,
                territory.y,
                territory.z,
                Math.max(1, territory.size),
                minX,
                maxX,
                minZ,
                maxZ,
                northOpen,
                southOpen,
                westOpen,
                eastOpen,
                openSides,
                perimeter
        );
    }

    // Border masks are intentionally not calculated on the server.
    // The client receives raw territory rectangles and derives visible/internal/mixed borders locally.

    private static void ensureGolemIndex() {
        if (!golemIndexDirty) return;
        GOLEMS_BY_UUID.clear();
        GOLEMS_BY_GUILD.clear();
        for (Golem golem : DATA.golems) {
            if (golem == null) continue;
            if (golem.uuid != null) GOLEMS_BY_UUID.put(golem.uuid, golem);
            if (golem.guildId != null) GOLEMS_BY_GUILD.computeIfAbsent(golem.guildId, ignored -> new ArrayList<>()).add(golem);
        }
        golemIndexDirty = false;
    }


    private static void ensureGuildBedIndex() {
        if (!guildBedIndexDirty) return;
        GUILD_BEDS_BY_KEY.clear();
        GUILD_BEDS_BY_PLAYER.clear();
        GUILD_BEDS_BY_LABEL_CELL.clear();
        if (DATA.guildBeds != null) {
            int cellSize = Math.max(16, HomeCraftGuildConfig.guildBedsLabelCellSize());
            for (GuildBedBinding bed : DATA.guildBeds.values()) {
                if (bed == null || bed.key == null || bed.key.isBlank()) continue;
                GUILD_BEDS_BY_KEY.put(bed.key, bed);
                if (bed.claimed()) {
                    GUILD_BEDS_BY_PLAYER.put(bed.ownerUuid.toLowerCase(Locale.ROOT), bed);
                    int cellX = Math.floorDiv(bed.x, cellSize);
                    int cellZ = Math.floorDiv(bed.z, cellSize);
                    GUILD_BEDS_BY_LABEL_CELL.computeIfAbsent(guildBedLabelCellKey(bed.dimension, cellX, cellZ), ignored -> new ArrayList<>()).add(bed);
                }
            }
        }
        guildBedIndexDirty = false;
    }

    private static String guildBedLabelCellKey(String dimension, int cellX, int cellZ) {
        return String.valueOf(dimension == null ? "minecraft:overworld" : dimension) + ":" + cellX + ":" + cellZ;
    }

    public static synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(DATA), StandardCharsets.UTF_8);
        } catch (Exception e) {
            HomeCraftGuildMod.LOGGER.warn("Could not save Home Craft guild store: {}", file, e);
        }
    }

    private static void saveHotPathDeferred() {
        // Hot-path updates (golem position/lastSeen) must never write JSON immediately.
        // The server tick pipeline flushes this at a long interval or on explicit force.
        deferredHotSaveDirty = true;
    }

    public static synchronized void flushDeferredHotSave(boolean force) {
        if (!deferredHotSaveDirty) return;
        long now = System.currentTimeMillis();
        if (!force && now - deferredHotSaveLastMs < DEFERRED_HOT_SAVE_INTERVAL_MS) return;
        deferredHotSaveLastMs = now;
        deferredHotSaveDirty = false;
        save();
    }

    public static synchronized boolean isInGuild(ServerPlayer player) {
        return DATA.members.containsKey(uuid(player));
    }

    public static synchronized String playerGuildId(ServerPlayer player) {
        return DATA.members.get(uuid(player));
    }

    public static synchronized Guild guildOf(ServerPlayer player) {
        String id = playerGuildId(player);
        return id == null ? null : DATA.guilds.get(id);
    }

    public static synchronized GuildRank rankOf(ServerPlayer player) {
        Guild guild = guildOf(player);
        if (guild == null || guild.members == null) return null;
        Member member = guild.members.get(uuid(player));
        return member == null ? null : GuildRank.from(member.rank);
    }

    public static synchronized boolean createGuild(ServerPlayer player, String name) {
        return createGuild(player, name, "magenta");
    }

    public static synchronized boolean createGuild(ServerPlayer player, String name, String color) {
        if (isInGuild(player)) return false;
        String clean = cleanName(name);
        if (clean.length() < 3) return false;
        String id = "guild_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Guild guild = new Guild();
        guild.id = id;
        guild.name = clean;
        guild.color = normalizeGuildColor(color);
        guild.createdAt = Instant.now().toString();
        guild.level = 1;
        guild.experience = 0;
        guild.guildmasterUuid = uuid(player);
        guild.guildmasterName = player.getName().getString();
        guild.members = new LinkedHashMap<>();
        Member member = new Member();
        member.uuid = uuid(player);
        member.name = player.getName().getString();
        member.rank = GuildRank.GUILDMASTER.name();
        member.joinedAt = guild.createdAt;
        guild.members.put(member.uuid, member);
        DATA.guilds.put(id, guild);
        DATA.members.put(member.uuid, id);
        markGuildRuntimeDirty();
        save();
        return true;
    }

    public static synchronized String disbandGuild(ServerPlayer actor) {
        Guild guild = guildOf(actor);
        if (guild == null || rankOf(actor) != GuildRank.GUILDMASTER) return null;
        String guildId = guild.id;
        if (guild.members != null) {
            for (String memberUuid : new ArrayList<>(guild.members.keySet())) DATA.members.remove(memberUuid);
        }
        DATA.invites.entrySet().removeIf(e -> Objects.equals(e.getValue(), guildId));
        for (List<Invite> list : DATA.invitesV2.values()) if (list != null) list.removeIf(i -> i != null && Objects.equals(i.guildId, guildId));
        DATA.invitesV2.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
        Set<String> territoryIds = new HashSet<>();
        DATA.territories.removeIf(t -> {
            boolean remove = t != null && Objects.equals(t.guildId, guildId);
            if (remove) territoryIds.add(t.id);
            return remove;
        });
        DATA.bedOwners.entrySet().removeIf(e -> {
            String key = e.getKey();
            if (key == null) return false;
            for (String territoryId : territoryIds) if (key.startsWith(territoryId + ":")) return true;
            return false;
        });
        if (DATA.guildBeds != null) DATA.guildBeds.values().removeIf(b -> b != null && Objects.equals(b.guildId, guildId));
        markGuildBedIndexDirty();
        DATA.golems.removeIf(g -> g != null && Objects.equals(g.guildId, guildId));
        DATA.guilds.remove(guildId);
        markTerritoryIndexesDirty();
        markGolemIndexDirty();
        markGuildRuntimeDirty();
        save();
        return guildId;
    }

    public static synchronized boolean invite(ServerPlayer actor, ServerPlayer target) {
        Guild guild = guildOf(actor);
        if (guild == null || target == null || isInGuild(target)) return false;
        if (rankOf(actor) != GuildRank.GUILDMASTER) return false;
        if (guild.members == null) guild.members = new LinkedHashMap<>();
        if (guild.members.size() >= HomeCraftGuildConfig.maxGuildMembers()) return false;
        String targetUuid = uuid(target);
        DATA.invites.put(targetUuid, guild.id); // legacy compatibility
        List<Invite> list = DATA.invitesV2.computeIfAbsent(targetUuid, k -> new ArrayList<>());
        list.removeIf(i -> i != null && Objects.equals(i.guildId, guild.id));
        Invite invite = new Invite();
        invite.guildId = guild.id;
        invite.guildName = guild.name;
        invite.invitedByUuid = uuid(actor);
        invite.invitedByName = actor.getName().getString();
        invite.createdAt = Instant.now().toString();
        list.add(invite);
        save();
        return true;
    }

    public static synchronized boolean acceptInvite(ServerPlayer player) {
        List<Invite> list = DATA.invitesV2.get(uuid(player));
        String guildId = (list == null || list.isEmpty() || list.get(0) == null) ? DATA.invites.get(uuid(player)) : list.get(0).guildId;
        return acceptInvite(player, guildId);
    }

    public static synchronized boolean acceptInvite(ServerPlayer player, String requestedGuildId) {
        if (isInGuild(player)) return false;
        String playerUuid = uuid(player);
        String guildId = requestedGuildId == null || requestedGuildId.isBlank() ? DATA.invites.get(playerUuid) : requestedGuildId;
        if (guildId == null || guildId.isBlank()) return false;
        Guild guild = DATA.guilds.get(guildId);
        if (guild == null) return false;
        if (guild.members == null) guild.members = new LinkedHashMap<>();
        if (guild.members.size() >= HomeCraftGuildConfig.maxGuildMembers()) return false;
        DATA.invites.remove(playerUuid);
        List<Invite> list = DATA.invitesV2.get(playerUuid);
        if (list != null) list.removeIf(i -> i != null && Objects.equals(i.guildId, guildId));
        if (list == null || list.isEmpty()) DATA.invitesV2.remove(playerUuid);
        Member member = new Member();
        member.uuid = playerUuid;
        member.name = player.getName().getString();
        member.rank = GuildRank.WARRIOR.name();
        member.joinedAt = Instant.now().toString();
        guild.members.put(member.uuid, member);
        DATA.members.put(member.uuid, guild.id);
        markGuildRuntimeDirty();
        save();
        return true;
    }

    public static synchronized boolean declineInvite(ServerPlayer player, String requestedGuildId) {
        String playerUuid = uuid(player);
        boolean removed = false;
        if (requestedGuildId == null || requestedGuildId.isBlank()) {
            removed = DATA.invites.remove(playerUuid) != null;
            removed = DATA.invitesV2.remove(playerUuid) != null || removed;
        } else {
            if (Objects.equals(DATA.invites.get(playerUuid), requestedGuildId)) {
                DATA.invites.remove(playerUuid);
                removed = true;
            }
            List<Invite> list = DATA.invitesV2.get(playerUuid);
            if (list != null) {
                int before = list.size();
                list.removeIf(i -> i != null && Objects.equals(i.guildId, requestedGuildId));
                removed = removed || before != list.size();
                if (list.isEmpty()) DATA.invitesV2.remove(playerUuid);
            }
        }
        if (removed) save();
        return removed;
    }

    public static synchronized boolean leaveGuild(ServerPlayer player) {
        Guild guild = guildOf(player);
        if (guild == null || rankOf(player) == GuildRank.GUILDMASTER) return false;
        String id = uuid(player);
        if (guild.members != null) guild.members.remove(id);
        DATA.members.remove(id);
        freeGuildBedsForMember(guild.id, id);
        markGuildRuntimeDirty();
        save();
        return true;
    }

    public static synchronized boolean kick(ServerPlayer actor, String targetNameOrUuid) {
        Guild guild = guildOf(actor);
        if (guild == null || rankOf(actor) != GuildRank.GUILDMASTER) return false;
        Member target = findMember(guild, targetNameOrUuid);
        if (target == null || Objects.equals(target.uuid, uuid(actor))) return false;
        guild.members.remove(target.uuid);
        DATA.members.remove(target.uuid);
        freeGuildBedsForMember(guild.id, target.uuid);
        markGuildRuntimeDirty();
        save();
        return true;
    }

    public static synchronized boolean setRank(ServerPlayer actor, String targetNameOrUuid, GuildRank newRank) {
        Guild guild = guildOf(actor);
        if (guild == null || rankOf(actor) != GuildRank.GUILDMASTER || newRank == null || !newRank.isAssignableByGuildmaster()) return false;
        Member member = findMember(guild, targetNameOrUuid);
        if (member == null) return false;
        if (Objects.equals(member.uuid, uuid(actor))) return false;
        if (GuildRank.from(member.rank) == GuildRank.GUILDMASTER) return false;
        member.rank = newRank.name();
        markGuildRuntimeDirty();
        save();
        return true;
    }

    private static Member findMember(Guild guild, String targetNameOrUuid) {
        if (guild == null || guild.members == null || targetNameOrUuid == null) return null;
        String raw = targetNameOrUuid.trim();
        for (Member member : guild.members.values()) {
            if (member == null) continue;
            if (member.uuid != null && member.uuid.equalsIgnoreCase(raw)) return member;
            if (member.name != null && member.name.equalsIgnoreCase(raw)) return member;
        }
        return null;
    }

    public static synchronized boolean isGuildMaster(ServerPlayer player) {
        return rankOf(player) == GuildRank.GUILDMASTER;
    }

    public static synchronized String guildMasterUuid(String guildId) {
        Guild guild = guildId == null ? null : DATA.guilds.get(guildId);
        return guild == null || guild.guildmasterUuid == null ? "" : guild.guildmasterUuid;
    }

    public static synchronized boolean isMemberOfGuild(ServerPlayer player, String guildId) {
        return player != null && guildId != null && Objects.equals(DATA.members.get(uuid(player)), guildId);
    }

    public static synchronized boolean canManageFarm(ServerPlayer player, Territory territory) {
        if (player == null || territory == null) return false;
        if (!"GUILD".equals(territory.type)) return canBuild(player, territory);
        if (!isMemberOfGuild(player, territory.guildId)) return false;
        GuildRank rank = rankOf(player);
        return rank == GuildRank.GUILDMASTER || rank == GuildRank.FARMER;
    }

    public static synchronized String guildColor(ServerPlayer player) {
        Guild guild = guildOf(player);
        return guild == null ? "magenta" : normalizeGuildColor(guild.color);
    }

    public static synchronized String guildColorById(String guildId) {
        Guild guild = guildId == null ? null : DATA.guilds.get(guildId);
        return guild == null ? "magenta" : normalizeGuildColor(guild.color);
    }

    public static synchronized boolean setGuildColor(ServerPlayer actor, String color) {
        Guild guild = guildOf(actor);
        if (guild == null || rankOf(actor) != GuildRank.GUILDMASTER) return false;
        guild.color = normalizeGuildColor(color);
        for (Territory territory : DATA.territories) {
            if (territory != null && Objects.equals(territory.guildId, guild.id)) territory.guildColor = guild.color;
        }
        save();
        return true;
    }

    public static String normalizeGuildColor(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "white", "orange", "magenta", "light_blue", "yellow", "lime", "cyan", "purple", "blue", "red" -> value;
            case "lightblue", "light-blue", "sky", "sky_blue" -> "light_blue";
            case "green" -> "lime";
            default -> "magenta";
        };
    }

    private static int guildColorRgb(String color) {
        return switch (normalizeGuildColor(color)) {
            case "white" -> 0xF9FFFE;
            case "orange" -> 0xF9801D;
            case "magenta" -> 0xC74EBD;
            case "light_blue" -> 0x3AB3DA;
            case "yellow" -> 0xFED83D;
            case "lime" -> 0x80C71F;
            case "cyan" -> 0x169C9C;
            case "purple" -> 0x8932B8;
            case "blue" -> 0x3C44AA;
            case "red" -> 0xB02E26;
            default -> 0xC74EBD;
        };
    }

    public static synchronized boolean canAddTerritory(ServerPlayer player, BlockPos pos, LevelAccessor level, String type) {
        return buildTerritoryCandidate(player, pos, level, type, false) != null;
    }

    public static synchronized Territory addTerritory(ServerPlayer player, BlockPos pos, LevelAccessor level, String type) {
        Territory t = buildTerritoryCandidate(player, pos, level, type, true);
        if (t == null) return null;
        DATA.territories.add(t);
        markTerritoryIndexesDirty();
        save();
        return t;
    }

    private static Territory buildTerritoryCandidate(ServerPlayer player, BlockPos pos, LevelAccessor level, String type, boolean allocateId) {
        String owner = uuid(player);
        Guild guild = guildOf(player);
        boolean guildClaim = "GUILD".equals(type);
        int size = guildClaim ? HomeCraftGuildConfig.guildClaimSize() : HomeCraftGuildConfig.personalClaimSize();
        int limit = guildClaim ? HomeCraftGuildConfig.guildBannerLimit() : HomeCraftGuildConfig.personalBannerLimit();

        if (guildClaim && (guild == null || rankOf(player) != GuildRank.GUILDMASTER)) return null;
        if (countTerritories(owner, guildClaim ? guild.id : null, type) >= limit) return null;
        try { refreshSpawnArea(resolveServer(player)); } catch (Exception ignored) {}
        if (isSpawnPosition(level, pos)) return null;

        Territory t = new Territory();
        t.id = allocateId ? "claim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : "__candidate__";
        t.type = type;
        t.ownerUuid = owner;
        t.ownerName = player.getName().getString();
        t.guildId = guildClaim ? guild.id : null;
        t.guildName = guildClaim ? guild.name : null;
        t.guildColor = guildClaim ? normalizeGuildColor(guild.color) : null;
        t.dimension = dimensionId(level);
        t.x = pos.getX();
        t.y = pos.getY();
        t.z = pos.getZ();
        t.size = size;
        t.spawnClipped = SPAWN_AREA != null && SPAWN_AREA.intersectsSquare(t.dimension, minX(t), maxX(t), minZ(t), maxZ(t));
        t.createdAt = Instant.now().toString();

        if (overlaps(t)) return null;
        return t;
    }

    public static synchronized Territory territoryAt(LevelAccessor level, BlockPos pos) {
        if (pos == null) return null;
        if (isSpawnPosition(level, pos)) return null;
        ensureTerritoryIndexes();
        String dim = dimensionId(level);
        int cellX = Math.floorDiv(pos.getX(), 1 << TERRITORY_INDEX_CELL_SHIFT);
        int cellZ = Math.floorDiv(pos.getZ(), 1 << TERRITORY_INDEX_CELL_SHIFT);
        List<Territory> candidates = TERRITORIES_BY_DIM_CELL.get(territoryCellKey(dim, cellX, cellZ));
        if (candidates == null || candidates.isEmpty()) return null;
        for (Territory territory : candidates) {
            if (territory == null || !Objects.equals(territory.dimension, dim)) continue;
            if (containsRaw(territory, pos)) return territory;
        }
        return null;
    }


    public static synchronized Territory nearestGuildTerritoryAround(LevelAccessor level, BlockPos pos, double range) {
        if (level == null || pos == null || range <= 0.0D) return null;
        ensureTerritoryIndexes();
        String dim = dimensionId(level);
        int minCellX = Math.floorDiv((int)Math.floor(pos.getX() - range), 1 << TERRITORY_INDEX_CELL_SHIFT);
        int maxCellX = Math.floorDiv((int)Math.floor(pos.getX() + range), 1 << TERRITORY_INDEX_CELL_SHIFT);
        int minCellZ = Math.floorDiv((int)Math.floor(pos.getZ() - range), 1 << TERRITORY_INDEX_CELL_SHIFT);
        int maxCellZ = Math.floorDiv((int)Math.floor(pos.getZ() + range), 1 << TERRITORY_INDEX_CELL_SHIFT);
        Territory best = null;
        double bestDist = range * range;
        HashSet<String> seen = new HashSet<>();
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                List<Territory> candidates = TERRITORIES_BY_DIM_CELL.get(territoryCellKey(dim, cx, cz));
                if (candidates == null || candidates.isEmpty()) continue;
                for (Territory territory : candidates) {
                    if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.dimension, dim)) continue;
                    String tid = territory.id == null ? String.valueOf(System.identityHashCode(territory)) : territory.id;
                    if (!seen.add(tid)) continue;
                    double dx = 0.0D;
                    if (pos.getX() < minX(territory)) dx = minX(territory) - pos.getX();
                    else if (pos.getX() > maxX(territory)) dx = pos.getX() - maxX(territory);
                    double dz = 0.0D;
                    if (pos.getZ() < minZ(territory)) dz = minZ(territory) - pos.getZ();
                    else if (pos.getZ() > maxZ(territory)) dz = pos.getZ() - maxZ(territory);
                    double dist = dx * dx + dz * dz;
                    if (dist <= bestDist) {
                        bestDist = dist;
                        best = territory;
                    }
                }
            }
        }
        return best;
    }

    public static synchronized Territory territoryByBanner(LevelAccessor level, BlockPos pos) {
        if (pos == null) return null;
        ensureTerritoryIndexes();
        return TERRITORIES_BY_BANNER.get(bannerKey(dimensionId(level), pos.getX(), pos.getY(), pos.getZ()));
    }

    public static synchronized TerritoryStaticInfo territoryStaticInfo(String territoryId) {
        if (territoryId == null) return null;
        ensureTerritoryIndexes();
        return TERRITORY_STATIC_BY_ID.get(territoryId);
    }

    public static synchronized List<TerritoryStaticInfo> territoryStaticInfosSnapshot() {
        ensureTerritoryIndexes();
        return TERRITORY_STATIC_SNAPSHOT;
    }

    public static synchronized List<TerritoryStaticInfo> territoryStaticInfosNear(String dimension, int x, int z, int radius, int limit) {
        ensureTerritoryIndexes();
        if (dimension == null) dimension = "";
        int safeRadius = Math.max(0, radius);
        int max = Math.max(1, limit);
        int minCellX = Math.floorDiv(x - safeRadius, 1 << TERRITORY_INDEX_CELL_SHIFT);
        int maxCellX = Math.floorDiv(x + safeRadius, 1 << TERRITORY_INDEX_CELL_SHIFT);
        int minCellZ = Math.floorDiv(z - safeRadius, 1 << TERRITORY_INDEX_CELL_SHIFT);
        int maxCellZ = Math.floorDiv(z + safeRadius, 1 << TERRITORY_INDEX_CELL_SHIFT);
        LinkedHashMap<String, TerritoryStaticInfo> out = new LinkedHashMap<>();
        long radiusSqr = (long)safeRadius * (long)safeRadius;
        for (int cx = minCellX; cx <= maxCellX && out.size() < max; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ && out.size() < max; cz++) {
                List<Territory> candidates = TERRITORIES_BY_DIM_CELL.get(territoryCellKey(dimension, cx, cz));
                if (candidates == null || candidates.isEmpty()) continue;
                for (Territory territory : candidates) {
                    if (territory == null || territory.id == null || out.containsKey(territory.id)) continue;
                    double dx = 0.0D;
                    if (x < minX(territory)) dx = minX(territory) - x;
                    else if (x > maxX(territory)) dx = x - maxX(territory);
                    double dz = 0.0D;
                    if (z < minZ(territory)) dz = minZ(territory) - z;
                    else if (z > maxZ(territory)) dz = z - maxZ(territory);
                    if ((long)(dx * dx + dz * dz) > radiusSqr) continue;
                    TerritoryStaticInfo info = TERRITORY_STATIC_BY_ID.get(territory.id);
                    if (info != null) out.put(territory.id, info);
                    if (out.size() >= max) break;
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    public static synchronized List<Territory> guildTerritories(String guildId) {
        if (guildId == null) return new ArrayList<>();
        ensureTerritoryIndexes();
        List<Territory> indexed = TERRITORIES_BY_GUILD.get(guildId);
        return indexed == null ? new ArrayList<>() : new ArrayList<>(indexed);
    }

    public static synchronized List<Territory> territoriesSnapshot() {
        return new ArrayList<>(DATA.territories);
    }

    public static synchronized int territoryCount() {
        return DATA.territories == null ? 0 : DATA.territories.size();
    }

    public static synchronized int golemCount() {
        return DATA.golems == null ? 0 : DATA.golems.size();
    }

    public static synchronized Territory firstGuildTerritory(String guildId) {
        if (guildId == null) return null;
        ensureTerritoryIndexes();
        List<Territory> list = TERRITORIES_BY_GUILD.get(guildId);
        if (list == null || list.isEmpty()) return null;
        for (Territory territory : list) {
            if (territory != null && "GUILD".equals(territory.type)) return territory;
        }
        return null;
    }

    public static synchronized Territory guildTerritoryForSpawn(ServerPlayer player) {
        String guildId = playerGuildId(player);
        if (guildId == null) return null;
        Territory atPlayer = territoryAt(player.level(), player.blockPosition());
        if (atPlayer != null && "GUILD".equals(atPlayer.type) && Objects.equals(atPlayer.guildId, guildId)) return atPlayer;
        return firstGuildTerritory(guildId);
    }

    public static boolean isInsideTerritory(Territory territory, BlockPos pos) {
        if (territory == null || pos == null) return false;
        if (GuildSpawnBoundary.isInsideSpawn(SPAWN_AREA, territory.dimension, pos.getX(), pos.getZ())) return false;
        return containsRaw(territory, pos);
    }

    public static synchronized void removeTerritory(Territory territory) {
        if (territory == null || territory.id == null || territory.id.isBlank()) return;
        removeTerritoriesByIds(Collections.singleton(territory.id), "manual_remove", true);
    }

    public static synchronized boolean canBuild(ServerPlayer player, Territory territory) {
        if (player == null || territory == null) return true;
        if (HomeCraftGuildConfig.creativeBypass() && player.getAbilities().instabuild) return true;
        String id = uuid(player);
        if (Objects.equals(id, territory.ownerUuid)) return true;
        if ("GUILD".equals(territory.type)) {
            Guild guild = DATA.guilds.get(territory.guildId);
            if (guild != null && guild.members != null && guild.members.containsKey(id)) {
                GuildRank rank = GuildRank.from(guild.members.get(id).rank);
                return rank == GuildRank.GUILDMASTER || rank == GuildRank.BUILDER;
            }
        }
        return false;
    }


    public static synchronized long guildBedsRevision() {
        return guildBedsRevision;
    }

    public static synchronized GuildBedBinding guildBedAt(String dimension, BlockPos pos) {
        ensureGuildBedIndex();
        return GUILD_BEDS_BY_KEY.get(guildBedKey(dimension, pos));
    }

    public static synchronized GuildBedBinding guildBedForPlayer(String playerUuid) {
        ensureGuildBedIndex();
        if (playerUuid == null) return null;
        return GUILD_BEDS_BY_PLAYER.get(playerUuid.toLowerCase(Locale.ROOT));
    }

    public static synchronized List<GuildBedBinding> guildBedLabelsNear(String dimension, int x, int z, int radius) {
        ensureGuildBedIndex();
        if (dimension == null || DATA.guildBeds == null || DATA.guildBeds.isEmpty()) return Collections.emptyList();
        int r = Math.max(1, radius);
        int r2 = r * r;
        int cellSize = Math.max(16, HomeCraftGuildConfig.guildBedsLabelCellSize());
        int minCellX = Math.floorDiv(x - r, cellSize);
        int maxCellX = Math.floorDiv(x + r, cellSize);
        int minCellZ = Math.floorDiv(z - r, cellSize);
        int maxCellZ = Math.floorDiv(z + r, cellSize);
        List<GuildBedBinding> out = new ArrayList<>();
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                List<GuildBedBinding> cell = GUILD_BEDS_BY_LABEL_CELL.get(guildBedLabelCellKey(dimension, cx, cz));
                if (cell == null || cell.isEmpty()) continue;
                for (GuildBedBinding bed : cell) {
                    if (bed == null || !bed.claimed() || !Objects.equals(dimension, bed.dimension)) continue;
                    int dx = bed.x - x;
                    int dz = bed.z - z;
                    if (dx * dx + dz * dz <= r2) out.add(bed);
                }
            }
        }
        out.sort(Comparator.comparingInt((GuildBedBinding b) -> Math.abs(b.x - x) + Math.abs(b.z - z)).thenComparing(b -> b.ownerName == null ? "" : b.ownerName));
        if (out.size() <= 64) return out;
        return new ArrayList<>(out.subList(0, 64));
    }

    public static synchronized GuildBedResult checkGuildBedPlacement(ServerPlayer player, Territory territory, LevelAccessor level, BlockPos pos, BlockState state) {
        if (!HomeCraftGuildConfig.guildBedsEnabled() || player == null || level == null || pos == null || state == null) return GuildBedResult.allow(false, "");
        if (!GuildBedPositionResolver.isBed(state)) return GuildBedResult.allow(false, "");
        if (territory == null || !"GUILD".equals(territory.type)) return GuildBedResult.allow(false, "");
        if (!HomeCraftGuildConfig.guildBedsRequireGuildTerritory()) return GuildBedResult.allow(false, "");
        if (!isMemberOfGuild(player, territory.guildId)) return GuildBedResult.deny("❌ Ліжка на цій території доступні лише учасникам цієї гільдії.");
        GuildRank rank = rankOf(player);
        if (canPlaceUnlimitedGuildBeds(rank)) return GuildBedResult.allow(false, "");
        if (!HomeCraftGuildConfig.guildBedsOnePlacedBedPerMember()) return GuildBedResult.allow(false, "");
        ensureGuildBedIndex();
        String playerId = uuid(player);
        BlockPos root = GuildBedPositionResolver.rootPos(level, pos, state);
        String newKey = guildBedKey(dimensionId(level), root);
        GuildBedBinding active = GUILD_BEDS_BY_PLAYER.get(playerId);
        if (active != null && active.claimed() && !newKey.equals(active.key)) {
            return GuildBedResult.deny("❌ Ви вже маєте ліжко на території гільдії. Щоб змінити його, натисніть на інше вільне ліжко.");
        }
        for (GuildBedBinding bed : DATA.guildBeds.values()) {
            if (bed == null || !Objects.equals(playerId, bed.placedByUuid)) continue;
            if (!Objects.equals(territory.guildId, bed.guildId)) continue;
            if (newKey.equals(bed.key)) continue;
            if (GuildBedStatus.FREE.name().equals(bed.status) || GuildBedStatus.CLAIMED.name().equals(bed.status)) {
                return GuildBedResult.deny("❌ Ви вже поставили ліжко на території гільдії. Спочатку використайте або звільніть старе.");
            }
        }
        return GuildBedResult.allow(false, "");
    }

    public static synchronized GuildBedResult registerGuildBedPlacement(ServerPlayer player, Territory territory, LevelAccessor level, BlockPos pos, BlockState state) {
        GuildBedResult precheck = checkGuildBedPlacement(player, territory, level, pos, state);
        if (!precheck.allowed()) return precheck;
        if (!HomeCraftGuildConfig.guildBedsEnabled() || player == null || territory == null || level == null || pos == null || state == null) return GuildBedResult.allow(false, "");
        if (!"GUILD".equals(territory.type) || !GuildBedPositionResolver.isBed(state) || !isMemberOfGuild(player, territory.guildId)) return GuildBedResult.allow(false, "");
        BlockPos root = GuildBedPositionResolver.rootPos(level, pos, state);
        String dim = dimensionId(level);
        String key = guildBedKey(dim, root);
        GuildBedBinding existingBed = DATA.guildBeds.get(key);
        if (existingBed != null && existingBed.claimed() && uuid(player).equalsIgnoreCase(String.valueOf(existingBed.ownerUuid))) {
            return GuildBedResult.allow(false, "");
        }
        GuildBedBinding bed = DATA.guildBeds.computeIfAbsent(key, k -> new GuildBedBinding());
        bed.key = key;
        bed.guildId = territory.guildId;
        bed.guildName = territory.guildName;
        bed.territoryId = territory.id;
        bed.dimension = dim;
        bed.x = root.getX();
        bed.y = root.getY();
        bed.z = root.getZ();
        bed.facing = GuildBedPositionResolver.facingName(state);
        bed.placedByUuid = uuid(player);
        bed.placedByName = player.getName().getString();
        bed.placedAt = bed.placedAt == null || bed.placedAt.isBlank() ? Instant.now().toString() : bed.placedAt;
        bed.lastValidatedAt = Instant.now().toString();
        GuildRank rank = rankOf(player);
        if (!canPlaceUnlimitedGuildBeds(rank)) {
            bed.ownerUuid = uuid(player);
            bed.ownerName = player.getName().getString();
            bed.claimedAt = Instant.now().toString();
            bed.status = GuildBedStatus.CLAIMED.name();
            applyRespawnPosition(player, dim, root, false);
        } else if (bed.status == null || bed.status.isBlank()) {
            bed.status = GuildBedStatus.FREE.name();
        }
        markGuildBedIndexDirty();
        save();
        return GuildBedResult.allow(true, canPlaceUnlimitedGuildBeds(rank) ? "✅ Ліжко додано до території гільдії." : "✅ Ліжко закріплено за вами.");
    }

    public static synchronized GuildBedResult handleGuildBedUse(ServerPlayer player, Territory territory, LevelAccessor level, BlockPos pos, BlockState state) {
        if (!HomeCraftGuildConfig.guildBedsEnabled() || player == null || level == null || pos == null || state == null) return GuildBedResult.allow(false, "");
        if (!GuildBedPositionResolver.isBed(state)) return GuildBedResult.allow(false, "");
        if (territory == null || !"GUILD".equals(territory.type)) return GuildBedResult.allow(false, "");
        if (!isMemberOfGuild(player, territory.guildId)) return GuildBedResult.deny("❌ Це ліжко належить іншій гільдії.");
        BlockPos root = GuildBedPositionResolver.rootPos(level, pos, state);
        String dim = dimensionId(level);
        String key = guildBedKey(dim, root);
        ensureGuildBedIndex();
        GuildBedBinding bed = DATA.guildBeds.get(key);
        if (bed == null) {
            bed = new GuildBedBinding();
            bed.key = key;
            bed.guildId = territory.guildId;
            bed.guildName = territory.guildName;
            bed.territoryId = territory.id;
            bed.dimension = dim;
            bed.x = root.getX(); bed.y = root.getY(); bed.z = root.getZ();
            bed.facing = GuildBedPositionResolver.facingName(state);
            bed.placedAt = Instant.now().toString();
            bed.status = GuildBedStatus.FREE.name();
            DATA.guildBeds.put(key, bed);
        }
        if (!Objects.equals(bed.guildId, territory.guildId)) return GuildBedResult.deny("❌ Це ліжко належить іншій гільдії.");
        String playerId = uuid(player);
        if (bed.claimed() && !Objects.equals(bed.ownerUuid, playerId)) {
            return GuildBedResult.deny("❌ Це ліжко закріплене за гравцем: " + safeOwnerName(bed) + ".");
        }
        if (bed.claimed() && Objects.equals(bed.ownerUuid, playerId)) {
            applyRespawnPosition(player, dim, root, false);
            return GuildBedResult.allow(false, "✅ Це ваше ліжко. Точка відродження вже тут.");
        }
        GuildBedBinding old = GUILD_BEDS_BY_PLAYER.get(playerId);
        boolean reclaimed = old != null && old.claimed() && !Objects.equals(old.key, key);
        if (old != null && old.claimed() && !HomeCraftGuildConfig.guildBedsAllowReclaim()) {
            return GuildBedResult.deny("❌ Ви вже маєте закріплене ліжко.");
        }
        if (reclaimed) freeBedInternal(old, false);
        bed.ownerUuid = playerId;
        bed.ownerName = player.getName().getString();
        bed.claimedAt = Instant.now().toString();
        bed.lastValidatedAt = bed.claimedAt;
        bed.status = GuildBedStatus.CLAIMED.name();
        applyRespawnPosition(player, dim, root, false);
        markGuildBedIndexDirty();
        save();
        return GuildBedResult.allow(true, reclaimed ? "✅ Нове ліжко закріплено. Попереднє ліжко звільнено." : "✅ Ліжко закріплено за вами.");
    }

    public static synchronized GuildBedResult handleGuildBedBroken(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!HomeCraftGuildConfig.guildBedsEnabled() || level == null || pos == null || state == null || !GuildBedPositionResolver.isBed(state)) return GuildBedResult.allow(false, "");
        BlockPos root = GuildBedPositionResolver.rootPos(level, pos, state);
        String dim = dimensionId(level);
        ensureGuildBedIndex();
        GuildBedBinding bed = DATA.guildBeds.get(guildBedKey(dim, root));
        if (bed == null) return GuildBedResult.allow(false, "");
        notifyBedOwnerIfOnline(level, bed, "⚠ Ваше ліжко на території гільдії було зруйноване.");
        freeBedInternal(bed, true);
        bed.status = GuildBedStatus.BROKEN.name();
        bed.lastValidatedAt = Instant.now().toString();
        markGuildBedIndexDirty();
        save();
        return GuildBedResult.allow(true, "");
    }

    public static synchronized boolean freeGuildBed(ServerPlayer actor, String bedKey) {
        if (actor == null || bedKey == null || bedKey.isBlank()) return false;
        ensureGuildBedIndex();
        GuildBedBinding bed = DATA.guildBeds.get(bedKey);
        if (bed == null) return false;
        GuildRank rank = rankOf(actor);
        boolean own = bed.ownerUuid != null && bed.ownerUuid.equalsIgnoreCase(uuid(actor));
        boolean admin = (rank == GuildRank.GUILDMASTER && HomeCraftGuildConfig.guildBedsAllowGuildMasterFreeBed()) || (rank == GuildRank.BUILDER && HomeCraftGuildConfig.guildBedsAllowBuilderFreeBed());
        if (!own && !admin) return false;
        freeBedInternal(bed, true);
        markGuildBedIndexDirty();
        save();
        return true;
    }

    public static synchronized void freeGuildBedsForMember(String guildId, String playerUuid) {
        if (guildId == null || playerUuid == null || DATA.guildBeds == null) return;
        boolean changed = false;
        for (GuildBedBinding bed : DATA.guildBeds.values()) {
            if (bed == null || !Objects.equals(guildId, bed.guildId)) continue;
            if (!playerUuid.equalsIgnoreCase(String.valueOf(bed.ownerUuid))) continue;
            freeBedInternal(bed, true);
            changed = true;
        }
        if (changed) { markGuildBedIndexDirty(); save(); }
    }

    private static boolean canPlaceUnlimitedGuildBeds(GuildRank rank) {
        if (rank == GuildRank.GUILDMASTER && HomeCraftGuildConfig.guildBedsGuildMasterUnlimitedPlacement()) return true;
        return rank == GuildRank.BUILDER && HomeCraftGuildConfig.guildBedsBuilderUnlimitedPlacement();
    }

    private static void freeBedInternal(GuildBedBinding bed, boolean clearRespawn) {
        if (bed == null) return;
        if (clearRespawn && bed.ownerUuid != null) clearRespawnForOwner(bed);
        bed.ownerUuid = "";
        bed.ownerName = "";
        bed.claimedAt = "";
        bed.status = GuildBedStatus.FREE.name();
        bed.lastValidatedAt = Instant.now().toString();
    }

    private static void clearRespawnForOwner(GuildBedBinding bed) {
        if (bed == null || bed.ownerUuid == null || bed.ownerUuid.isBlank()) return;
        MinecraftServer ms = serverFromStoredLevels();
        if (ms == null) return;
        try {
            UUID id = UUID.fromString(bed.ownerUuid);
            ServerPlayer player = ms.getPlayerList().getPlayer(id);
            if (player != null) applyRespawnPosition(player, bed.dimension, null, true);
        } catch (Throwable ignored) {}
    }

    private static void notifyBedOwnerIfOnline(LevelAccessor level, GuildBedBinding bed, String message) {
        if (level == null || bed == null || bed.ownerUuid == null || bed.ownerUuid.isBlank() || message == null) return;
        MinecraftServer ms = null;
        try { if (level instanceof ServerLevel sl) ms = sl.getServer(); } catch (Throwable ignored) {}
        if (ms == null) ms = serverFromStoredLevels();
        if (ms == null) return;
        try {
            UUID id = UUID.fromString(bed.ownerUuid);
            ServerPlayer player = ms.getPlayerList().getPlayer(id);
            if (player != null) player.displayClientMessage(net.minecraft.network.chat.Component.literal(message), true);
        } catch (Throwable ignored) {}
    }

    private static MinecraftServer serverFromStoredLevels() {
        return runtimeServer;
    }

    private static void applyRespawnPosition(ServerPlayer player, String dimension, BlockPos pos, boolean clear) {
        if (player == null) return;
        Object dimensionKey = dimensionKeyFor(dimension, player);
        if (clear) {
            if (invokeSetSpawnPoint(player, null, true)) return;
        }
        try {
            for (java.lang.reflect.Method method : player.getClass().getMethods()) {
                if (!"setRespawnPosition".equals(method.getName()) || method.getParameterCount() != 5) continue;
                method.invoke(player, dimensionKey, clear ? null : pos, player.getYRot(), false, true);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private static Object dimensionKeyFor(String dimension, ServerPlayer player) {
        if (player == null) return null;
        MinecraftServer server = null;
        try {
            if (player.level() instanceof ServerLevel sl) server = sl.getServer();
        } catch (Throwable ignored) {}
        if (server == null) server = serverFromStoredLevels();
        if (server != null && dimension != null && !dimension.isBlank()) {
            try {
                for (ServerLevel level : server.getAllLevels()) {
                    if (level != null && dimension.equals(dimensionId(level))) return level.dimension();
                }
            } catch (Throwable ignored) {}
        }
        return player.level().dimension();
    }

    private static boolean invokeSetSpawnPoint(ServerPlayer player, Object respawn, boolean sendMessage) {
        if (player == null) return false;
        try {
            for (java.lang.reflect.Method method : player.getClass().getMethods()) {
                if (!"setSpawnPoint".equals(method.getName()) || method.getParameterCount() != 2) continue;
                method.invoke(player, respawn, sendMessage);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String guildBedKey(String dimension, BlockPos pos) {
        return String.valueOf(dimension == null ? "minecraft:overworld" : dimension) + ":" + (pos == null ? 0 : pos.getX()) + ":" + (pos == null ? 0 : pos.getY()) + ":" + (pos == null ? 0 : pos.getZ());
    }

    private static String safeOwnerName(GuildBedBinding bed) {
        if (bed == null) return "невідомо";
        return bed.ownerName == null || bed.ownerName.isBlank() ? "невідомо" : bed.ownerName;
    }

    public record GuildBedResult(boolean allowed, boolean changed, String message) {
        public static GuildBedResult allow(boolean changed, String message) { return new GuildBedResult(true, changed, message == null ? "" : message); }
        public static GuildBedResult deny(String message) { return new GuildBedResult(false, false, message == null ? "" : message); }
    }

    public static synchronized boolean canInteractStorageOrBed(ServerPlayer player, Territory territory, BlockPos pos, boolean bed) {
        if (player == null || territory == null) return true;
        if (bed) return true; // guild beds are handled by GuildBedManager before vanilla sleep/storage logic.
        if (HomeCraftGuildConfig.creativeBypass() && player.getAbilities().instabuild) return true;
        String id = uuid(player);
        if (Objects.equals(id, territory.ownerUuid)) return true;
        if ("GUILD".equals(territory.type)) {
            Guild guild = DATA.guilds.get(territory.guildId);
            return guild != null && guild.members != null && guild.members.containsKey(id);
        }
        return false;
    }

    private static int countTerritories(String ownerUuid, String guildId, String type) {
        int count = 0;
        for (Territory t : DATA.territories) {
            if (t == null || !Objects.equals(t.type, type)) continue;
            if ("GUILD".equals(type) && Objects.equals(t.guildId, guildId)) count++;
            if (!"GUILD".equals(type) && Objects.equals(t.ownerUuid, ownerUuid)) count++;
        }
        return count;
    }

    private static boolean overlaps(Territory a) {
        for (Territory b : DATA.territories) {
            if (b == null || !Objects.equals(a.dimension, b.dimension)) continue;
            if (Objects.equals(a.id, b.id)) continue;
            int minX = Math.max(minX(a), minX(b));
            int maxX = Math.min(maxX(a), maxX(b));
            int minZ = Math.max(minZ(a), minZ(b));
            int maxZ = Math.min(maxZ(a), maxZ(b));
            if (minX > maxX || minZ > maxZ) continue;
            return true;
        }
        return false;
    }

    public static int minX(Territory territory) {
        int half = Math.max(1, territory.size) / 2;
        return territory.x - half;
    }

    public static int maxX(Territory territory) {
        int half = Math.max(1, territory.size) / 2;
        return territory.x + half - 1;
    }

    public static int minZ(Territory territory) {
        int half = Math.max(1, territory.size) / 2;
        return territory.z - half;
    }

    public static int maxZ(Territory territory) {
        int half = Math.max(1, territory.size) / 2;
        return territory.z + half - 1;
    }

    private static boolean containsRaw(Territory territory, BlockPos pos) {
        return territory != null && pos != null
                && pos.getX() >= minX(territory) && pos.getX() <= maxX(territory)
                && pos.getZ() >= minZ(territory) && pos.getZ() <= maxZ(territory);
    }

    public static synchronized int countGuildGolems(String guildId) {
        if (guildId == null) return 0;
        ensureGolemIndex();
        List<Golem> list = GOLEMS_BY_GUILD.get(guildId);
        return list == null ? 0 : list.size();
    }

    public static synchronized boolean guildExists(String guildId) {
        return guildId != null && DATA.guilds.containsKey(guildId);
    }

    public static synchronized Guild guildById(String guildId) {
        return guildId == null ? null : DATA.guilds.get(guildId);
    }

    public static synchronized String guildNameById(String guildId) {
        Guild guild = guildById(guildId);
        return guild == null ? "гільдія" : guild.name;
    }

    public static synchronized int guildLevel(String guildId) {
        Guild guild = guildById(guildId);
        if (guild == null) return 1;
        sanitizeGuildProgress(guild);
        return guild.level;
    }

    public static synchronized int guildExperience(String guildId) {
        Guild guild = guildById(guildId);
        if (guild == null) return 0;
        sanitizeGuildProgress(guild);
        return guild.experience;
    }

    public static synchronized int mobKillGuildXpBonusPercent(String guildId) {
        Guild guild = guildById(guildId);
        if (guild == null) return GuildLevelTiers.mobKillGuildXpBonusPercent(1);
        sanitizeGuildProgress(guild);
        return GuildLevelTiers.mobKillGuildXpBonusPercent(guild.level);
    }

    public static synchronized int mobKillGuildXpBonusPercentForLevel(int level) {
        return GuildLevelTiers.mobKillGuildXpBonusPercent(level);
    }

    public static synchronized int talentPointsForLevel(int level) {
        if (!HomeCraftGuildConfig.guildTalentsEnabled()) return 0;
        int max = HomeCraftGuildConfig.guildTalentMaxGuildLevel();
        int safe = Math.max(1, Math.min(max, level));
        int total = 0;
        for (int i = 1; i <= safe; i++) total += HomeCraftGuildConfig.guildTalentLevelPoints(i);
        return Math.max(0, total);
    }

    public static synchronized boolean hasTalent(String guildId, String talentId) {
        Guild guild = guildById(guildId);
        if (guild == null || talentId == null || !HomeCraftGuildConfig.guildTalentsEnabled()) return false;
        sanitizeGuildTalents(guild);
        GuildTalents.TalentDefinition definition = GuildTalents.byId(talentId);
        if (definition == null) return false;
        return unlockedTalentIds(guild, definition.branch()).contains(definition.id());
    }

    public static synchronized int spentTalentPoints(String guildId, String branch) {
        Guild guild = guildById(guildId);
        if (guild == null) return 0;
        sanitizeGuildTalents(guild);
        return spentTalentPoints(guild, GuildTalents.normalizeBranch(branch));
    }

    public static synchronized int availableTalentPoints(String guildId, String branch) {
        Guild guild = guildById(guildId);
        if (guild == null) return 0;
        sanitizeGuildProgress(guild);
        sanitizeGuildTalents(guild);
        String normalized = GuildTalents.normalizeBranch(branch);
        return Math.max(0, talentPointsForLevel(guild.level) - spentTalentPoints(guild, normalized));
    }

    private static int spentTalentPoints(Guild guild, String branch) {
        if (guild == null || branch == null) return 0;
        int spent = 0;
        for (String id : unlockedTalentIds(guild, branch)) {
            GuildTalents.TalentDefinition definition = GuildTalents.byId(id);
            if (definition != null && definition.branch().equals(branch)) spent += Math.max(0, definition.cost());
        }
        return Math.max(0, spent);
    }

    private static List<String> unlockedTalentIds(Guild guild, String branch) {
        if (guild == null) return Collections.emptyList();
        sanitizeGuildTalents(guild);
        String normalized = GuildTalents.normalizeBranch(branch);
        if (GuildTalents.GOLEM.equals(normalized)) return guild.talents.golem.unlocked;
        if (GuildTalents.MEMBER.equals(normalized)) return guild.talents.member.unlocked;
        return Collections.emptyList();
    }

    public static synchronized GuildTalentResult unlockTalent(ServerPlayer player, String talentId) {
        if (!HomeCraftGuildConfig.guildTalentsEnabled()) return GuildTalentResult.fail("Система талантів гільдії вимкнена.");
        Guild guild = guildOf(player);
        if (guild == null) return GuildTalentResult.fail("Ти не перебуваєш у гільдії.");
        if (rankOf(player) != GuildRank.GUILDMASTER) return GuildTalentResult.fail("Тільки глава гільдії може розвивати таланти.");
        sanitizeGuildProgress(guild);
        sanitizeGuildTalents(guild);
        GuildTalents.TalentDefinition definition = GuildTalents.byId(talentId);
        if (definition == null) {
            if (HomeCraftGuildConfig.guildTalentDebug()) HomeCraftGuildMod.LOGGER.warn("guild-talent-rejected: guild={} talent={} reason=invalid-id", guild.id, talentId);
            return GuildTalentResult.fail("Невідомий талант гільдії.");
        }
        List<String> unlocked = unlockedTalentIds(guild, definition.branch());
        if (unlocked.contains(definition.id())) return GuildTalentResult.fail("Цей талант уже вивчено.");
        if (guild.level < definition.requiredGuildLevel()) return GuildTalentResult.fail("Потрібен рівень гільдії " + definition.requiredGuildLevel() + ".");
        for (String prerequisiteId : GuildTalents.prerequisites(definition.prerequisite())) {
            if (!unlocked.contains(prerequisiteId)) {
                GuildTalents.TalentDefinition prerequisite = GuildTalents.byId(prerequisiteId);
                String title = prerequisite == null ? prerequisiteId : prerequisite.title();
                return GuildTalentResult.fail("Спочатку потрібен талант: " + title + ".");
            }
        }
        int available = Math.max(0, talentPointsForLevel(guild.level) - spentTalentPoints(guild, definition.branch()));
        if (available < definition.cost()) return GuildTalentResult.fail("Не вистачає очок у гілці " + GuildTalents.branchLabel(definition.branch()) + ".");
        unlocked.add(definition.id());
        markGuildRuntimeDirty();
        save();
        if (HomeCraftGuildConfig.guildTalentDebug()) HomeCraftGuildMod.LOGGER.info("guild-talent-unlock: guild={} talent={} branch={}", guild.id, definition.id(), definition.branch());
        return GuildTalentResult.ok("Талант вивчено: " + definition.title() + ".");
    }

    public static synchronized GuildTalentResult resetTalentBranch(ServerPlayer player, String branch) {
        if (!HomeCraftGuildConfig.guildTalentsEnabled()) return GuildTalentResult.fail("Система талантів гільдії вимкнена.");
        Guild guild = guildOf(player);
        if (guild == null) return GuildTalentResult.fail("Ти не перебуваєш у гільдії.");
        if (rankOf(player) != GuildRank.GUILDMASTER) return GuildTalentResult.fail("Тільки глава гільдії може скидати таланти.");
        String normalized = GuildTalents.normalizeBranch(branch);
        if (!GuildTalents.validBranch(normalized)) {
            if (HomeCraftGuildConfig.guildTalentDebug()) HomeCraftGuildMod.LOGGER.warn("guild-talent-reset-rejected: guild={} branch={} reason=invalid-branch", guild.id, branch);
            return GuildTalentResult.fail("Невідома гілка талантів.");
        }
        sanitizeGuildTalents(guild);
        List<String> unlocked = unlockedTalentIds(guild, normalized);
        if (unlocked.isEmpty()) return GuildTalentResult.fail("У цій гілці немає вивчених талантів для скидання.");
        int cost = HomeCraftGuildConfig.guildTalentResetCostEmeralds();
        if (cost > 0 && !takeTalentResetEmeralds(player, cost)) {
            return GuildTalentResult.fail("Потрібно " + cost + " смарагди для скидання цієї гілки.");
        }
        unlocked.clear();
        guild.talents.lastResetAt = System.currentTimeMillis();
        markGuildRuntimeDirty();
        save();
        if (HomeCraftGuildConfig.guildTalentDebug()) HomeCraftGuildMod.LOGGER.info("guild-talent-reset: guild={} branch={}", guild.id, normalized);
        return GuildTalentResult.ok("Гілку талантів скинуто. Витрачено " + cost + " смарагди.");
    }

    private static boolean takeTalentResetEmeralds(ServerPlayer player, int count) {
        if (player == null || count <= 0) return true;
        if (player.getAbilities().instabuild) return true;
        GuildCurrency.migratePlayerInventory(player);
        int found = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack != null && stack.is(Items.EMERALD)) found += stack.getCount();
        }
        if (found < count) return false;
        int left = count;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || !stack.is(Items.EMERALD)) continue;
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
        try { player.getInventory().setChanged(); } catch (Exception ignored) {}
        return true;
    }

    public static synchronized int guildPlayerXpBonusPercent(String guildId) {
        if (!HomeCraftGuildConfig.guildTalentsEnabled()) return HomeCraftGuildConfig.guildBaseXpBonusPercent();
        int bonus = HomeCraftGuildConfig.guildBaseXpBonusPercent();
        if (hasTalent(guildId, "member_xp_1")) bonus += 5;
        if (hasTalent(guildId, "member_xp_2")) bonus += 5;
        if (hasTalent(guildId, "member_field_training")) bonus += 5;
        return Math.max(0, Math.min(20, bonus));
    }

    public static synchronized int guildPotionDurationBonusPercent(String guildId) {
        if (!HomeCraftGuildConfig.guildTalentsEnabled()) return HomeCraftGuildConfig.guildBasePotionDurationBonusPercent();
        int bonus = HomeCraftGuildConfig.guildBasePotionDurationBonusPercent();
        if (hasTalent(guildId, "member_potion_1")) bonus += 10;
        if (hasTalent(guildId, "member_potion_2")) bonus += 10;
        if (hasTalent(guildId, "member_deep_breath")) bonus += 5;
        if (hasTalent(guildId, "member_fire_ward")) bonus += 5;
        return Math.max(0, Math.min(40, bonus));
    }

    public static synchronized int guildWeaponDamageBonusPercent(String guildId) {
        if (!HomeCraftGuildConfig.guildTalentsEnabled()) return HomeCraftGuildConfig.guildBaseWeaponDamageBonusPercent();
        int bonus = HomeCraftGuildConfig.guildBaseWeaponDamageBonusPercent();
        if (hasTalent(guildId, "member_weapon_1")) bonus += 5;
        if (hasTalent(guildId, "member_weapon_2")) bonus += 5;
        if (hasTalent(guildId, "member_field_training")) bonus += 5;
        if (hasTalent(guildId, "member_swift_strike")) bonus += 5;
        return Math.max(0, Math.min(25, bonus));
    }

    public static synchronized int guildArmorBonusPercent(String guildId) {
        if (!HomeCraftGuildConfig.guildTalentsEnabled()) return HomeCraftGuildConfig.guildBaseArmorBonusPercent();
        int bonus = HomeCraftGuildConfig.guildBaseArmorBonusPercent();
        if (hasTalent(guildId, "member_armor_1")) bonus += 5;
        if (hasTalent(guildId, "member_armor_2")) bonus += 5;
        if (hasTalent(guildId, "member_vanguard_oath")) bonus += 5;
        return Math.max(0, Math.min(20, bonus));
    }

    public static synchronized int guildMemberSpeedBonusPercent(String guildId) {
        int bonus = 0;
        if (hasTalent(guildId, "member_speed_1")) bonus += 5;
        if (hasTalent(guildId, "member_speed_2")) bonus += 5;
        if (hasTalent(guildId, "member_speed_3")) bonus += 10;
        if (hasTalent(guildId, "member_swift_strike")) bonus += 5;
        return Math.max(0, Math.min(HomeCraftGuildConfig.guildMemberMaxSpeedBonusPercent(), bonus));
    }

    public static synchronized double guildMemberJumpBonus(String guildId) {
        double bonus = 0.0D;
        if (hasTalent(guildId, "member_jump_1")) bonus += 0.08D;
        if (hasTalent(guildId, "member_jump_2")) bonus += 0.07D;
        return Math.max(0.0D, Math.min(HomeCraftGuildConfig.guildMemberMaxJumpBonus(), bonus));
    }

    public static synchronized int guildMemberDamageReductionPercent(String guildId) {
        int bonus = 0;
        if (hasTalent(guildId, "member_resilience")) bonus += 5;
        if (hasTalent(guildId, "member_vanguard_oath")) bonus += 5;
        return Math.max(0, Math.min(10, bonus));
    }

    public static synchronized int guildGolemTalentHealthBonusPercent(String guildId) {
        int bonus = 0;
        if (hasTalent(guildId, "golem_health_1")) bonus += 5;
        if (hasTalent(guildId, "golem_health_2")) bonus += 5;
        if (hasTalent(guildId, "golem_guardian_matrix")) bonus += 5;
        if (hasTalent(guildId, "golem_reactive_armor")) bonus += 5;
        if (hasTalent(guildId, "golem_stone_heart")) bonus += 5;
        return Math.max(0, Math.min(HomeCraftGuildConfig.guildGolemTalentMaxHealthBonusPercent(), bonus));
    }

    public static synchronized int guildGolemTalentDamageBonusPercent(String guildId) {
        int bonus = 0;
        if (hasTalent(guildId, "golem_damage_1")) bonus += 5;
        if (hasTalent(guildId, "golem_damage_2")) bonus += 5;
        if (hasTalent(guildId, "golem_guardian_matrix")) bonus += 5;
        return Math.max(0, Math.min(HomeCraftGuildConfig.guildGolemTalentMaxDamageBonusPercent(), bonus));
    }

    public static synchronized int guildGolemCrushingForcePercent(String guildId) {
        int bonus = 0;
        if (hasTalent(guildId, "golem_crushing_force")) bonus += 5;
        if (hasTalent(guildId, "golem_stone_heart")) bonus += 5;
        return Math.max(0, Math.min(10, bonus));
    }

    public static synchronized int guildGolemTalentSpeedBonusPercent(String guildId) {
        int bonus = 0;
        if (hasTalent(guildId, "golem_speed_1")) bonus += 5;
        if (hasTalent(guildId, "golem_speed_2")) bonus += 5;
        if (hasTalent(guildId, "golem_war_march")) bonus += 5;
        return Math.max(0, Math.min(HomeCraftGuildConfig.guildGolemTalentMaxSpeedBonusPercent(), bonus));
    }

    public static synchronized int guildGolemTalentHealingSpeedBonusPercent(String guildId) {
        int bonus = 0;
        if (hasTalent(guildId, "golem_regen_1")) bonus += 10;
        if (hasTalent(guildId, "golem_regen_2")) bonus += 10;
        if (hasTalent(guildId, "golem_reactive_armor")) bonus += 10;
        return Math.max(0, Math.min(HomeCraftGuildConfig.guildGolemTalentMaxHealingSpeedBonusPercent(), bonus));
    }

    public static synchronized int guildGolemOutOfCombatRegenIntervalTicks(String guildId) {
        if (hasTalent(guildId, "golem_out_of_combat_regen_2")) return 20 * 5;
        if (hasTalent(guildId, "golem_out_of_combat_regen_1")) return 20 * 8;
        return 0;
    }

    public static synchronized int guildGolemRouteEfficiencyBonusPercent(String guildId) {
        int bonus = 0;
        if (hasTalent(guildId, "golem_route_1")) bonus += 10;
        if (hasTalent(guildId, "golem_night_watch")) bonus += 5;
        return Math.max(0, Math.min(20, bonus));
    }

    public static synchronized boolean guildGolemInterceptTalent(String guildId) {
        return hasTalent(guildId, "golem_intercept_1") || hasTalent(guildId, "golem_war_march") || hasTalent(guildId, "golem_night_watch");
    }

    public static synchronized boolean guildGolemEliteCommandTalent(String guildId) {
        return hasTalent(guildId, "golem_elite_command");
    }

    public static synchronized boolean guildGolemNightWatchTalent(String guildId) {
        return hasTalent(guildId, "golem_night_watch");
    }

    public static synchronized boolean guildHasNightVision(String guildId) {
        return hasTalent(guildId, "member_night_vision");
    }

    public static synchronized boolean guildHasWaterBreathing(String guildId) {
        return hasTalent(guildId, "member_deep_breath");
    }

    public static synchronized boolean guildHasFireResistance(String guildId) {
        return hasTalent(guildId, "member_fire_ward");
    }

    public static synchronized int guildHealthBonusPercent(ServerPlayer player) {
        return 0;
    }

    public static synchronized int guildArmorBonusPercent(ServerPlayer player) {
        return guildArmorBonusPercent(playerGuildId(player));
    }

    public static synchronized int guildDamageBonusPercent(ServerPlayer player) {
        return guildWeaponDamageBonusPercent(playerGuildId(player));
    }

    public static synchronized boolean guildHasNightVision(ServerPlayer player) {
        return guildHasNightVision(playerGuildId(player));
    }

    public static synchronized GuildBuffSnapshot guildBuffSnapshot(String guildId) {
        int level = guildLevel(guildId);
        return new GuildBuffSnapshot(
                guildId == null ? "" : guildId,
                guildRuntimeRevision,
                level,
                0,
                guildArmorBonusPercent(guildId),
                guildWeaponDamageBonusPercent(guildId),
                guildMemberSpeedBonusPercent(guildId),
                guildMemberJumpBonus(guildId),
                guildMemberDamageReductionPercent(guildId),
                guildHasNightVision(guildId),
                guildHasWaterBreathing(guildId),
                guildHasFireResistance(guildId)
        );
    }

    public static synchronized GuildExperienceGain addGuildExperience(String guildId, int amount) {
        if (guildId == null || amount <= 0) return null;
        Guild guild = DATA.guilds.get(guildId);
        if (guild == null) return null;
        return addGuildExperienceInternal(guildId, guild, amount, false);
    }

    /**
     * Adds guild XP for monster kills and applies the invisible per-level guild XP buff.
     * The fractional part is stored on the guild, so small mob XP values still receive
     * an exact +5% per level over time instead of losing the decimal part forever.
     */
    public static synchronized GuildExperienceGain addMonsterKillGuildExperience(String guildId, int baseAmount) {
        if (guildId == null || baseAmount <= 0) return null;
        Guild guild = DATA.guilds.get(guildId);
        if (guild == null) return null;
        sanitizeGuildProgress(guild);
        int bonusPercent = GuildLevelTiers.mobKillGuildXpBonusPercent(guild.level);
        int previousRemainder = Math.max(0, guild.mobKillXpBonusRemainder);
        int scaledBonus = baseAmount * bonusPercent + previousRemainder;
        int bonus = Math.max(0, scaledBonus / 100);
        guild.mobKillXpBonusRemainder = Math.max(0, scaledBonus % 100);
        return addGuildExperienceInternal(guildId, guild, baseAmount + bonus, true);
    }

    private static GuildExperienceGain addGuildExperienceInternal(String guildId, Guild guild, int amount, boolean preserveBonusRemainder) {
        if (guild == null || amount <= 0) return null;
        sanitizeGuildProgress(guild);
        int oldLevel = guild.level;
        int oldXp = guild.experience;
        guild.experience = Math.max(0, Math.min(Integer.MAX_VALUE - 1024, guild.experience + amount));
        guild.level = GuildLevelTiers.levelForXp(guild.experience);
        if (guild.level < oldLevel) guild.level = oldLevel;
        sanitizeGuildProgress(guild);
        if (guild.level != oldLevel || guild.experience != oldXp || preserveBonusRemainder) markGuildRuntimeDirty();
        save();
        return new GuildExperienceGain(guildId, guild.name, amount, oldXp, guild.experience, oldLevel, guild.level);
    }

    public static synchronized boolean hasAchievement(String guildId, String achievementId) {
        if (guildId == null || achievementId == null) return false;
        Map<String, GuildAchievement> map = DATA.guildAchievements.get(guildId);
        return map != null && map.containsKey(achievementId);
    }

    public static synchronized GuildAchievementUnlock unlockAchievement(String guildId, String achievementId, String unlockedBy) {
        if (guildId == null || achievementId == null || achievementId.isBlank()) return null;
        Guild guild = DATA.guilds.get(guildId);
        AchievementDefinition definition = achievementDefinition(achievementId);
        if (guild == null || definition == null) return null;
        Map<String, GuildAchievement> map = DATA.guildAchievements.computeIfAbsent(guildId, ignored -> new LinkedHashMap<>());
        if (map.containsKey(achievementId)) return null;

        sanitizeGuildProgress(guild);
        int oldLevel = guild.level;
        int oldXp = guild.experience;
        guild.experience = Math.max(0, Math.min(Integer.MAX_VALUE - 1024, guild.experience + definition.xpReward));
        guild.level = GuildLevelTiers.levelForXp(guild.experience);
        if (guild.level < oldLevel) guild.level = oldLevel;
        sanitizeGuildProgress(guild);

        GuildAchievement achievement = new GuildAchievement();
        achievement.id = definition.id;
        achievement.unlockedAt = Instant.now().toString();
        achievement.unlockedBy = unlockedBy == null ? "" : unlockedBy;
        achievement.xpReward = definition.xpReward;
        achievement.emeraldReward = definition.emeraldReward;
        map.put(achievementId, achievement);
        save();
        return new GuildAchievementUnlock(guildId, guild.name, definition, achievement, oldXp, guild.experience, oldLevel, guild.level);
    }

    private static AchievementDefinition achievementDefinition(String id) {
        for (AchievementDefinition definition : ACHIEVEMENTS) {
            if (definition.id.equals(id)) return definition;
        }
        return null;
    }

    private static final List<AchievementDefinition> ACHIEVEMENTS = List.of(
            new AchievementDefinition("portal_nether", "Перший розлом", "Портали", "Гільдія разом відкриває шлях у Незер і проходить крізь перший небезпечний розлом.", "Потрібні щонайменше 2 учасники однієї гільдії. Другий учасник має пройти в Незер протягом 60 секунд після першого.", "Нагорода: 50 досвіду гільдії.", 50, 0),
            new AchievementDefinition("portal_end", "Шлях до Краю", "Портали", "Гільдія командою входить у Край і починає далеку експедицію.", "Потрібні щонайменше 2 учасники однієї гільдії, які входять у Край протягом 60 секунд.", "Нагорода: 125 досвіду гільдії.", 125, 0),
            new AchievementDefinition("portal_return", "Повернення додому", "Портали", "Після подорожі гільдія повертається у звичайний світ без втрати строю.", "Потрібні щонайменше 2 учасники однієї гільдії. Другий учасник має повернутися у звичайний світ протягом 60 секунд після першого.", "Нагорода: 75 досвіду гільдії.", 75, 0),
            new AchievementDefinition("portal_end_gateway", "Брама островів Краю", "Портали", "Гільдія знаходить браму Краю й разом переходить до далеких островів.", "Потрібні щонайменше 2 учасники однієї гільдії, які користуються брамою Краю протягом 60 секунд.", "Нагорода: 150 досвіду гільдії.", 150, 0),
            new AchievementDefinition("kill_ender_dragon", "Крила над Краєм", "Боси", "Гільдія бере участь у перемозі над драконом Краю.", "Потрібні щонайменше 2 учасники однієї гільдії в Краю: поруч із ареною або з недавнім внеском у бій протягом останніх 90 секунд.", "Нагорода: 1750 досвіду гільдії та 35 смарагдів учасникам.", 1750, 35),
            new AchievementDefinition("kill_wither", "Три черепи", "Боси", "Гільдія разом перемагає Візера й утримує бій під контролем.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою або з недавнім внеском у перемогу протягом останніх 90 секунд.", "Нагорода: 1300 досвіду гільдії та 25 смарагдів учасникам.", 1300, 25),
            new AchievementDefinition("kill_warden", "Тиша глибин", "Великі загрози", "Гільдія переживає бій із Варденом і перемагає його без паніки.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою або з недавнім внеском протягом останніх 90 секунд.", "Нагорода: 750 досвіду гільдії та 15 смарагдів учасникам.", 750, 15),
            new AchievementDefinition("kill_elder_guardian", "Серце монумента", "Великі загрози", "Гільдія очищає океанський монумент від старшого стража.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 64 блоків від монумента під час перемоги.", "Нагорода: 650 досвіду гільдії та 12 смарагдів учасникам.", 650, 12),
            new AchievementDefinition("kill_ravager", "Злам рейду", "Великі загрози", "Гільдія зупиняє руйнівника під час рейду й захищає територію від прориву.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від руйнівника під час перемоги.", "Нагорода: 520 досвіду гільдії та 10 смарагдів учасникам.", 520, 10),
            new AchievementDefinition("kill_evoker", "Без тотемів", "Великі загрози", "Гільдія перемагає закликача до того, як його магія переламає бій.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою або з недавнім внеском протягом останніх 90 секунд.", "Нагорода: 500 досвіду гільдії та 10 смарагдів учасникам.", 500, 10),
            new AchievementDefinition("kill_piglin_brute", "Бастіон взято", "Небезпечні вороги", "Гільдія бере під контроль бій у бастіоні піглінів.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою в Незері.", "Нагорода: 420 досвіду гільдії та 8 смарагдів учасникам.", 420, 8),
            new AchievementDefinition("kill_shulker", "Місто Краю", "Небезпечні вороги", "Гільдія долає шалкера під час подорожі містами Краю.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою.", "Нагорода: 350 досвіду гільдії та 7 смарагдів учасникам.", 350, 7),
            new AchievementDefinition("kill_ghast", "Небо Незеру", "Небезпечні вороги", "Гільдія збиває гаста в небезпечних просторах Незеру.", "Потрібні щонайменше 2 учасники однієї гільдії в Незері: поруч із боєм у радіусі 64 блоків або з недавнім внеском протягом останніх 90 секунд.", "Нагорода: 300 досвіду гільдії та 6 смарагдів учасникам.", 300, 6),
            new AchievementDefinition("kill_blaze", "Вогняний стрижень", "Небезпечні вороги", "Гільдія перемагає блейза у фортеці Незеру.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою.", "Нагорода: 250 досвіду гільдії та 5 смарагдів учасникам.", 250, 5),
            new AchievementDefinition("kill_breeze", "Порив у залі", "Небезпечні вороги", "Гільдія долає бриза в кімнатах випробувань.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою.", "Нагорода: 250 досвіду гільдії та 5 смарагдів учасникам.", 250, 5),
            new AchievementDefinition("kill_creaking", "Скрип нічного лісу", "Нічні загрози", "Гільдія перемагає скрипуна під покровом темного лісу.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою вночі або під час темної лісової загрози.", "Нагорода: 450 досвіду гільдії та 8 смарагдів учасникам.", 450, 8),
            new AchievementDefinition("kill_witch", "Зілля закінчились", "Нічні загрози", "Гільдія зупиняє відьму до того, як її зілля зламають бій.", "Потрібні щонайменше 2 учасники однієї гільдії в радіусі 48 блоків від бою або з недавнім внеском протягом останніх 90 секунд.", "Нагорода: 220 досвіду гільдії та 4 смарагди учасникам.", 220, 4)
    );
    public static synchronized int countOrdinaryGolems(String guildId) {
        if (guildId == null) return 0;
        ensureGolemIndex();
        List<Golem> list = GOLEMS_BY_GUILD.get(guildId);
        if (list == null || list.isEmpty()) return 0;
        int count = 0;
        for (Golem golem : list) if (golem != null && !golem.removed && !"ORPHAN".equals(golem.status) && !golem.elite) count++;
        return count;
    }

    public static synchronized int countEliteGolems(String guildId) {
        if (guildId == null) return 0;
        ensureGolemIndex();
        List<Golem> list = GOLEMS_BY_GUILD.get(guildId);
        if (list == null || list.isEmpty()) return 0;
        int count = 0;
        for (Golem golem : list) if (golem != null && !golem.removed && !"ORPHAN".equals(golem.status) && golem.elite) count++;
        return count;
    }

    public static synchronized List<Golem> guildGolems(String guildId) {
        if (guildId == null || guildId.isBlank()) return Collections.emptyList();
        ensureGolemIndex();
        List<Golem> list = GOLEMS_BY_GUILD.get(guildId);
        if (list == null || list.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(list);
    }


    public static synchronized int maxOrdinaryGolems(String guildId) {
        ensureTerritoryIndexes();
        List<Territory> territories = guildId == null ? null : TERRITORIES_BY_GUILD.get(guildId);
        int territoryCount = territories == null ? 0 : territories.size();
        return GuildLevelTiers.maxOrdinaryGolems(guildLevel(guildId), territoryCount);
    }

    public static synchronized int maxEliteGolems(String guildId) {
        return GuildLevelTiers.maxEliteGolems(guildLevel(guildId));
    }

    public static synchronized int nextOrdinaryGolemCost(String guildId) {
        return GuildLevelTiers.ordinaryGolemCost(guildLevel(guildId), countOrdinaryGolems(guildId));
    }

    public static synchronized int eliteGolemCost(String guildId) {
        return GuildLevelTiers.eliteGolemCost(guildLevel(guildId), countEliteGolems(guildId));
    }

    public static synchronized int nextEliteGolemUnlockLevel(String guildId) {
        return GuildLevelTiers.nextEliteGolemUnlockLevel(guildLevel(guildId));
    }

    public static int nextGolemCost(int currentGolemCount) {
        if (currentGolemCount <= 0) return 1;
        if (currentGolemCount == 1) return 3;
        return 10;
    }


    public static synchronized void registerGolem(String guildId, String uuid, String type, String dimension, int x, int y, int z) {
        registerGolem(guildId, uuid, type, dimension, x, y, z, false);
    }

    public static synchronized void registerGolem(String guildId, String uuid, String type, String dimension, int x, int y, int z, boolean elite) {
        registerGolemInternal(guildId, uuid, type, dimension, x, y, z, elite, false);
    }

    public static synchronized void registerPendingGolem(String guildId, String uuid, String type, String dimension, int x, int y, int z, boolean elite) {
        registerGolemInternal(guildId, uuid, type, dimension, x, y, z, elite, true);
    }

    private static void registerGolemInternal(String guildId, String uuid, String type, String dimension, int x, int y, int z, boolean elite, boolean pendingSpawn) {
        if (guildId == null || uuid == null) return;
        Golem golem = new Golem();
        golem.guildId = guildId;
        golem.uuid = uuid;
        golem.type = type;
        golem.name = generateGolemDisplayName(guildId, uuid, elite);
        golem.dimension = dimension;
        golem.x = x;
        golem.y = y;
        golem.z = z;
        golem.elite = elite;
        golem.golemType = elite ? "ELITE" : "NORMAL";
        golem.behaviorProfile = elite ? "ELITE_DEFENDER" : "NORMAL_GUARD";
        golem.assignedRole = elite ? "ELITE_COMMAND" : "ROAMING";
        golem.aiState = pendingSpawn ? (elite ? "ELITE_SPAWN_PENDING" : "NORMAL_SPAWN_PENDING") : (elite ? "ELITE_IDLE" : "NORMAL_IDLE");
        golem.status = pendingSpawn ? "SPAWN_PENDING" : "ALIVE";
        GuildGolemStats.ensureRecordMultipliers(golem);
        golem.healingAtTotem = false;
        golem.lastHealthCheckTick = 0L;
        golem.lastHealingParticleTick = 0L;
        golem.lastHealingApplyTick = 0L;
        golem.entityUuid = uuid;
        golem.lastSafeX = x;
        golem.lastSafeY = y;
        golem.lastSafeZ = z;
        golem.dead = false;
        golem.respawnPending = pendingSpawn;
        golem.removed = false;
        golem.createdAt = Instant.now().toString();
        golem.lastSeenAt = golem.createdAt;
        golem.missingTicks = 0;
        DATA.golems.add(golem);
        markGolemIndexDirty();
        markGuildRuntimeDirty();
        save();
    }

    public static synchronized void updateGolemStaticStats(String uuid, double baseMaxHealth, double baseDamage, boolean elite) {
        if (uuid == null || uuid.isBlank()) return;
        ensureGolemIndex();
        Golem golem = GOLEMS_BY_UUID.get(uuid);
        if (golem == null) return;
        int oldVersion = golem.golemStatsVersion;
        double oldBaseHp = golem.baseMaxHealth;
        int oldGuildHp = golem.guildMaxHealth;
        int oldMaxHp = golem.maxHealth;
        double oldBaseDamage = golem.baseDamage;
        double oldGuildDamage = golem.guildDamage;
        double oldHealthMultiplier = golem.healthMultiplier;
        double oldDamageMultiplier = golem.damageMultiplier;
        int oldHealthBonus = golem.healthBonusPercent;
        int oldDamageBonus = golem.damageBonusPercent;
        boolean oldElite = golem.elite;
        int oldTalentHealth = golem.talentMaxHealthBonusPercent;
        int oldFinalMaxHealth = golem.finalMaxHealth;
        int oldTalentDamage = golem.talentDamageBonusPercent;
        double oldFinalDamage = golem.finalDamage;
        int oldTalentSpeed = golem.talentSpeedBonusPercent;
        int oldTalentHealing = golem.talentHealingSpeedBonusPercent;
        GuildGolemStats.writeStaticRecordStats(golem, baseMaxHealth, baseDamage, elite);
        if (oldVersion != golem.golemStatsVersion
                || Double.compare(oldBaseHp, golem.baseMaxHealth) != 0
                || oldGuildHp != golem.guildMaxHealth
                || oldMaxHp != golem.maxHealth
                || Double.compare(oldBaseDamage, golem.baseDamage) != 0
                || Double.compare(oldGuildDamage, golem.guildDamage) != 0
                || Double.compare(oldHealthMultiplier, golem.healthMultiplier) != 0
                || Double.compare(oldDamageMultiplier, golem.damageMultiplier) != 0
                || oldHealthBonus != golem.healthBonusPercent
                || oldDamageBonus != golem.damageBonusPercent
                || oldTalentHealth != golem.talentMaxHealthBonusPercent
                || oldFinalMaxHealth != golem.finalMaxHealth
                || oldTalentDamage != golem.talentDamageBonusPercent
                || Double.compare(oldFinalDamage, golem.finalDamage) != 0
                || oldTalentSpeed != golem.talentSpeedBonusPercent
                || oldTalentHealing != golem.talentHealingSpeedBonusPercent
                || oldElite != golem.elite) {
            markGuildRuntimeDirty();
            saveHotPathDeferred();
        }
    }

    public static synchronized void ensureGolemStaticStats(String uuid, boolean elite) {
        updateGolemStaticStats(uuid, 0.0D, -1.0D, elite);
    }

    public static synchronized String golemDisplayName(String guildId, String uuid, boolean elite) {
        if (elite) return ELITE_GOLEM_NAME;
        Golem existing = golem(uuid);
        if (existing != null) return ensureGolemDisplayName(existing);
        return generateGolemDisplayName(guildId, uuid, false);
    }

    public static synchronized String ensureGolemDisplayName(Golem golem) {
        if (golem == null) return "";
        String expected = generateGolemDisplayName(golem.guildId, golem.uuid, golem.elite);
        if (golem.name == null || golem.name.isBlank() || (golem.elite && !ELITE_GOLEM_NAME.equals(golem.name))) {
            golem.name = expected;
        }
        return golem.name;
    }

    private static String generateGolemDisplayName(String guildId, String uuid, boolean elite) {
        if (elite) return ELITE_GOLEM_NAME;
        String key = String.valueOf(guildId == null ? "" : guildId) + ":" + String.valueOf(uuid == null ? "" : uuid);
        int index = Math.floorMod(key.hashCode(), GOLEM_NAME_POOL.length);
        return GOLEM_NAME_POOL[index];
    }

    public static synchronized List<Golem> golemsSnapshot() {
        return new ArrayList<>(DATA.golems);
    }

    public static synchronized List<Golem> golemsByGuildSnapshot(String guildId) {
        if (guildId == null) return new ArrayList<>();
        ensureGolemIndex();
        List<Golem> indexed = GOLEMS_BY_GUILD.get(guildId);
        return indexed == null ? new ArrayList<>() : new ArrayList<>(indexed);
    }

    public static synchronized void removeGolems(String guildId) {
        DATA.golems.removeIf(g -> g != null && Objects.equals(g.guildId, guildId));
        markGolemIndexDirty();
        save();
    }

    public static synchronized void removeGolem(String uuid) {
        Golem golem = golem(uuid);
        if (golem == null) return;
        golem.dead = true;
        golem.respawnPending = false;
        golem.removed = true;
        golem.status = "REMOVED";
        golem.healingAtTotem = false;
        golem.aiState = golem.elite ? "ELITE_REMOVED" : "NORMAL_REMOVED";
        golem.lastSeenAt = Instant.now().toString();
        save();
    }

    /**
     * Hard-deletes a golem from the guild roster. This is used by the UI delete button.
     *
     * Older builds only marked a golem as REMOVED, so the button looked successful but the
     * row stayed visible in GOLEMS_BEGIN and could leave stale AI/runtime state behind.
     * For a user-facing delete action the expected behaviour is: entity removed from world,
     * record removed from JSON, slot freed, roster refreshed.
     */
    public static synchronized boolean deleteGolemFromGuild(String guildId, String uuid) {
        if (guildId == null || guildId.isBlank() || uuid == null || uuid.isBlank()) return false;
        boolean removed = DATA.golems.removeIf(g -> g != null && Objects.equals(g.guildId, guildId) && Objects.equals(g.uuid, uuid));
        if (!removed) return false;
        markGolemIndexDirty();
        markGuildRuntimeDirty();
        save();
        return true;
    }

    public static synchronized boolean removeGolemFromGuild(String guildId, String uuid) {
        return deleteGolemFromGuild(guildId, uuid);
    }

    public static synchronized void touchGolemRuntime(String uuid) {
        if (uuid == null) return;
        Golem golem = golem(uuid);
        if (golem == null) return;
        saveHotPathDeferred();
    }

    public static synchronized Golem golem(String uuid) {
        if (uuid == null) return null;
        ensureGolemIndex();
        return GOLEMS_BY_UUID.get(uuid);
    }

    public static synchronized boolean markGolemDead(String uuid) {
        Golem golem = golem(uuid);
        if (golem == null) return false;
        golem.dead = true;
        golem.status = golem.respawnPending ? "RESPAWN_PENDING" : "DEAD";
        golem.aiState = golem.elite ? "ELITE_DEAD" : "NORMAL_DEAD";
        golem.deathTimeMs = System.currentTimeMillis();
        golem.missingTicks = 0;
        golem.lastSeenAt = Instant.now().toString();
        save();
        return true;
    }

    public static synchronized boolean markGolemAlive(String oldUuid, String newUuid, String dimension, int x, int y, int z) {
        if (oldUuid == null || newUuid == null) return false;
        Golem golem = golem(oldUuid);
        if (golem == null) return false;
        golem.uuid = newUuid;
        golem.entityUuid = newUuid;
        golem.dimension = dimension;
        golem.x = x;
        golem.y = y;
        golem.z = z;
        golem.lastSafeX = x;
        golem.lastSafeY = y;
        golem.lastSafeZ = z;
        golem.dead = false;
        golem.respawnPending = false;
        golem.status = "ALIVE";
        golem.aiState = golem.elite ? "ELITE_IDLE" : "NORMAL_IDLE";
        golem.missingTicks = 0;
        golem.lastSeenAt = Instant.now().toString();
        ensureGolemDisplayName(golem);
        markGolemIndexDirty();
        save();
        return true;
    }

    public static synchronized int markGolemMissing(String uuid) {
        Golem golem = golem(uuid);
        if (golem == null || golem.dead) return 0;
        golem.missingTicks = Math.max(0, golem.missingTicks) + 1;
        if (golem.missingTicks == 1 || golem.missingTicks % 5 == 0) saveHotPathDeferred();
        return golem.missingTicks;
    }

    public static synchronized void markGolemSeen(String uuid, String dimension, int x, int y, int z) {
        Golem golem = golem(uuid);
        if (golem == null) return;
        boolean changed = golem.missingTicks != 0
                || !Objects.equals(golem.dimension, dimension)
                || Math.abs(golem.x - x) >= 4
                || Math.abs(golem.y - y) >= 4
                || Math.abs(golem.z - z) >= 4;
        golem.missingTicks = 0;
        golem.dead = false;
        golem.status = "ALIVE";
        golem.dimension = dimension;
        golem.x = x;
        golem.y = y;
        golem.z = z;
        golem.lastSafeX = x;
        golem.lastSafeY = y;
        golem.lastSafeZ = z;
        golem.lastSeenAt = Instant.now().toString();
        ensureGolemDisplayName(golem);
        if (changed) saveHotPathDeferred();
    }

    public static synchronized boolean updateGolemUuid(String oldUuid, String newUuid, String dimension, int x, int y, int z) {
        if (oldUuid == null || newUuid == null) return false;
        Golem golem = golem(oldUuid);
        if (golem == null) return false;
        golem.uuid = newUuid;
        golem.entityUuid = newUuid;
        golem.dimension = dimension;
        golem.x = x;
        golem.y = y;
        golem.z = z;
        golem.lastSafeX = x;
        golem.lastSafeY = y;
        golem.lastSafeZ = z;
        golem.missingTicks = 0;
        golem.dead = false;
        golem.status = "ALIVE";
        golem.aiState = golem.elite ? "ELITE_IDLE" : "NORMAL_IDLE";
        golem.healingAtTotem = false;
        golem.lastHealingParticleTick = 0L;
        golem.lastHealingApplyTick = 0L;
        golem.lastSeenAt = Instant.now().toString();
        ensureGolemDisplayName(golem);
        markGolemIndexDirty();
        save();
        return true;
    }

    public static synchronized String golemStatus(Golem golem) {
        if (golem == null) return "missing";
        if (golem.removed || "REMOVED".equals(golem.status)) return "видалений";
        if ("ORPHAN".equals(golem.status)) return "помилка даних";
        if (golem.dead || "DEAD".equals(golem.status)) return golem.elite ? "елітний голем мертвий" : "загинув";
        if ("RESPAWN_PENDING".equals(golem.status) || golem.respawnPending) return "очікує відродження";
        if (golem.missingTicks > 0) return "відновлює прив’язку";
        if (golem.healingAtTotem || (golem.aiState != null && golem.aiState.contains("HEAL_AT_TOTEM"))) return "лікується біля тотема";
        if (golem.elite && golem.followGuildmasterUuid != null && !golem.followGuildmasterUuid.isBlank()) return "захищає гільдмайстра";
        if (golem.elite && golem.aiState != null && golem.aiState.contains("ENGAGE")) return "елітний захисник у бою";
        if (golem.elite && golem.aiState != null && golem.aiState.contains("TOTEM")) return "охороняє тотем";
        if (golem.elite) return "стратегічна оборона";
        return humanGolemAiState(golem.aiState);
    }

    private static String humanGolemAiState(String raw) {
        String state = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (state.isBlank() || state.endsWith("IDLE")) return "на позиції";
        if (state.contains("HEAL_AT_TOTEM")) return "лікується біля тотема";
        if (state.contains("PATROL")) return "патрулює";
        if (state.contains("DEFEND_MEMBER")) return "захищає учасника";
        if (state.contains("DEFEND_TERRITORY")) return "захищає територію";
        if (state.contains("DEFEND_TOTEM")) return "охороняє тотем";
        if (state.contains("ENGAGE") || state.contains("CHASE")) return "у бою";
        if (state.contains("RETURN") || state.contains("RECOVER")) return "повертається";
        if (state.contains("SAFE_WAIT") || state.contains("DATA_MISSING")) return "очікує дані";
        return "патрулює";
    }

    public static synchronized void recordGolemRouteLearning(String guildId, String dimension, int x, int y, int z, boolean success, String reason) {
        if (guildId == null || guildId.isBlank() || dimension == null || dimension.isBlank()) return;
        if (!DATA.guilds.containsKey(guildId)) return;
        if (DATA.golemRouteLearning == null) DATA.golemRouteLearning = new LinkedHashMap<>();
        String key = golemRouteLearningKey(guildId, dimension, x, z);
        GolemRouteLearning sample = DATA.golemRouteLearning.computeIfAbsent(key, k -> new GolemRouteLearning());
        long nowMs = System.currentTimeMillis();
        sample.guildId = guildId;
        sample.dimension = dimension;
        sample.cellSize = GOLEM_ROUTE_LEARNING_CELL_SIZE;
        sample.cellX = x >> GOLEM_ROUTE_LEARNING_CELL_SHIFT;
        sample.cellZ = z >> GOLEM_ROUTE_LEARNING_CELL_SHIFT;
        sample.lastY = y;
        sample.lastReason = normalizeRouteLearningReason(reason);
        sample.lastSeenAt = Instant.now().toString();
        sample.touchedAtMs = nowMs;
        sample.samples = Math.min(1_000_000, Math.max(0, sample.samples) + 1);
        if (success) sample.successes = Math.min(1_000_000, Math.max(0, sample.successes) + 1);
        else sample.failures = Math.min(1_000_000, Math.max(0, sample.failures) + 1);

        int delta = success ? -2 : routeLearningFailureWeight(sample.lastReason);
        sample.score = Math.max(-60, Math.min(240, sample.score + delta));
        if (sample.samples > 300 && (sample.samples & 31) == 0) {
            sample.samples = Math.max(32, sample.samples / 2);
            sample.successes = Math.max(0, sample.successes / 2);
            sample.failures = Math.max(0, sample.failures / 2);
            sample.score = Math.max(-50, Math.min(220, (int)Math.round(sample.score * 0.82D)));
        }

        golemRouteLearningDirty = true;
        if (!success && DATA.golemRouteLearning.size() > GOLEM_ROUTE_LEARNING_GLOBAL_CAP + 256) sanitizeGolemRouteLearning();
    }

    public static synchronized void flushGolemRouteLearningIfDirty(boolean force) {
        if (!golemRouteLearningDirty) return;
        long nowMs = System.currentTimeMillis();
        if (!force && nowMs - golemRouteLearningLastSaveMs < GOLEM_ROUTE_LEARNING_SAVE_INTERVAL_MS) return;
        sanitizeGolemRouteLearning();
        golemRouteLearningLastSaveMs = nowMs;
        golemRouteLearningDirty = false;
        save();
    }

    public static synchronized int golemRouteLearningPenalty(String guildId, String dimension, int x, int y, int z) {
        if (guildId == null || dimension == null || DATA.golemRouteLearning == null) return 0;
        GolemRouteLearning sample = DATA.golemRouteLearning.get(golemRouteLearningKey(guildId, dimension, x, z));
        if (sample == null) return 0;
        int penalty = sample.score;
        int failures = Math.max(0, sample.failures);
        int successes = Math.max(0, sample.successes);
        if (failures > successes) penalty += Math.min(55, (failures - successes) * 3);
        if (sample.lastReason != null) {
            String reason = sample.lastReason.toLowerCase(Locale.ROOT);
            if (reason.contains("deep-drop")) penalty += 90;
            if (reason.contains("water")) penalty += 45;
            if (reason.contains("stuck")) penalty += 55;
            if (reason.contains("blocked")) penalty += 35;
            if (reason.contains("unsafe")) penalty += 50;
        }
        if (successes >= failures * 3 + 6) penalty -= 20;
        return Math.max(-45, Math.min(190, penalty));
    }

    public static String golemRouteLearningCellKey(String guildId, String dimension, int x, int z) {
        return golemRouteLearningKey(guildId, dimension, x, z);
    }

    private static String golemRouteLearningKey(String guildId, String dimension, int x, int z) {
        return guildId + "|" + dimension + "|" + (x >> GOLEM_ROUTE_LEARNING_CELL_SHIFT) + "|" + (z >> GOLEM_ROUTE_LEARNING_CELL_SHIFT);
    }

    private static String normalizeRouteLearningReason(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("deep")) return "deep-drop";
        if (r.contains("water")) return "water";
        if (r.contains("stuck")) return "stuck";
        if (r.contains("unsafe")) return "unsafe";
        if (r.contains("blocked")) return "blocked";
        if (r.contains("planned") || r.contains("accepted") || r.contains("success")) return "success";
        return r.length() > 32 ? r.substring(0, 32) : r;
    }

    private static int routeLearningFailureWeight(String reason) {
        if (reason == null) return 14;
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("deep-drop")) return 60;
        if (r.contains("water")) return 34;
        if (r.contains("stuck")) return 42;
        if (r.contains("unsafe")) return 46;
        if (r.contains("blocked")) return 28;
        return 18;
    }


    private static void normalizeGolemRouteLearningKeys() {
        if (DATA.golemRouteLearning == null || DATA.golemRouteLearning.isEmpty()) return;
        Map<String, GolemRouteLearning> normalized = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, GolemRouteLearning> entry : DATA.golemRouteLearning.entrySet()) {
            if (entry == null || entry.getValue() == null) continue;
            GolemRouteLearning value = entry.getValue();
            if (value.cellSize <= 0) {
                // v35 stored 8x8 cells. v36 uses 16x16, so fold old cells by 2.
                value.cellX = value.cellX >> 1;
                value.cellZ = value.cellZ >> 1;
                value.cellSize = GOLEM_ROUTE_LEARNING_CELL_SIZE;
                changed = true;
            }
            String key = value.guildId + "|" + value.dimension + "|" + value.cellX + "|" + value.cellZ;
            GolemRouteLearning existing = normalized.get(key);
            if (existing == null) {
                normalized.put(key, value);
            } else {
                mergeGolemRouteLearning(existing, value);
                changed = true;
            }
            if (!Objects.equals(key, entry.getKey())) changed = true;
        }
        if (changed) DATA.golemRouteLearning = normalized;
    }

    private static void mergeGolemRouteLearning(GolemRouteLearning target, GolemRouteLearning source) {
        if (target == null || source == null) return;
        target.samples = Math.min(1_000_000, Math.max(0, target.samples) + Math.max(0, source.samples));
        target.successes = Math.min(1_000_000, Math.max(0, target.successes) + Math.max(0, source.successes));
        target.failures = Math.min(1_000_000, Math.max(0, target.failures) + Math.max(0, source.failures));
        target.score = Math.max(-60, Math.min(240, target.score + source.score));
        if (source.touchedAtMs >= target.touchedAtMs) {
            target.touchedAtMs = source.touchedAtMs;
            target.lastY = source.lastY;
            target.lastReason = source.lastReason;
            target.lastSeenAt = source.lastSeenAt;
        }
        target.cellSize = GOLEM_ROUTE_LEARNING_CELL_SIZE;
    }

    private static void sanitizeGolemRouteLearning() {
        if (DATA.golemRouteLearning == null) {
            DATA.golemRouteLearning = new LinkedHashMap<>();
            return;
        }
        normalizeGolemRouteLearningKeys();
        DATA.golemRouteLearning.entrySet().removeIf(e -> e == null || e.getKey() == null || e.getValue() == null || e.getValue().guildId == null || !DATA.guilds.containsKey(e.getValue().guildId) || e.getValue().samples <= 0 || (e.getValue().failures <= 0 && e.getValue().successes <= 1 && Math.abs(e.getValue().score) < 12));

        Map<String, List<Map.Entry<String, GolemRouteLearning>>> buckets = new HashMap<>();
        for (Map.Entry<String, GolemRouteLearning> entry : DATA.golemRouteLearning.entrySet()) {
            GolemRouteLearning value = entry.getValue();
            if (value == null) continue;
            String bucket = value.guildId + "|" + value.dimension;
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(entry);
        }
        for (List<Map.Entry<String, GolemRouteLearning>> entries : buckets.values()) {
            if (entries == null || entries.size() <= GOLEM_ROUTE_LEARNING_PER_GUILD_DIM_CAP) continue;
            entries.sort(Comparator.comparingInt(e -> routeLearningImportance(e.getValue())));
            int remove = entries.size() - GOLEM_ROUTE_LEARNING_PER_GUILD_DIM_CAP;
            for (int i = 0; i < remove && i < entries.size(); i++) DATA.golemRouteLearning.remove(entries.get(i).getKey());
        }
        if (DATA.golemRouteLearning.size() > GOLEM_ROUTE_LEARNING_GLOBAL_CAP) {
            List<Map.Entry<String, GolemRouteLearning>> entries = new ArrayList<>(DATA.golemRouteLearning.entrySet());
            entries.sort(Comparator.comparingInt(e -> routeLearningImportance(e.getValue())));
            int remove = DATA.golemRouteLearning.size() - GOLEM_ROUTE_LEARNING_GLOBAL_CAP;
            for (int i = 0; i < remove && i < entries.size(); i++) DATA.golemRouteLearning.remove(entries.get(i).getKey());
        }
    }

    private static int routeLearningImportance(GolemRouteLearning value) {
        if (value == null) return 0;
        int importance = Math.abs(value.score) * 2 + Math.max(0, value.failures) * 9 + Math.max(0, value.successes) * 2 + Math.min(80, Math.max(0, value.samples));
        if (value.lastReason != null) {
            String reason = value.lastReason.toLowerCase(Locale.ROOT);
            if (reason.contains("deep-drop") || reason.contains("unsafe")) importance += 120;
            if (reason.contains("stuck") || reason.contains("blocked")) importance += 80;
            if (reason.contains("water")) importance += 55;
        }
        return importance;
    }

    public static synchronized boolean setEliteGolemFollowing(String guildId, String golemUuid, String guildmasterUuid) {
        Golem golem = golem(golemUuid);
        if (guildId == null || guildmasterUuid == null || golem == null || !golem.elite || !Objects.equals(golem.guildId, guildId)) return false;
        golem.followGuildmasterUuid = guildmasterUuid;
        save();
        return true;
    }

    public static synchronized boolean clearEliteGolemFollowing(String guildId, String golemUuid) {
        Golem golem = golem(golemUuid);
        if (guildId == null || golem == null || !golem.elite || !Objects.equals(golem.guildId, guildId)) return false;
        golem.followGuildmasterUuid = "";
        save();
        return true;
    }

    public static synchronized String snapshotFor(ServerPlayer player) {
        Guild guild = guildOf(player);
        if (guild == null) {
            StringBuilder out = new StringBuilder();
            out.append("MODE=INVITES\n");
            out.append("INVITES_BEGIN\n");
            for (Invite invite : invitesFor(player)) {
                Guild invitedGuild = invite.guildId == null ? null : DATA.guilds.get(invite.guildId);
                String name = invitedGuild != null ? invitedGuild.name : invite.guildName;
                out.append(escape(invite.guildId)).append('|')
                        .append(escape(name)).append('|')
                        .append(escape(invite.invitedByName)).append("\n");
            }
            out.append("INVITES_END\n");
            return out.toString();
        }
        sanitizeGuildProgress(guild);
        GuildRank currentRank = rankOf(player);
        StringBuilder out = new StringBuilder();
        out.append("MODE=GUILD\n");
        out.append("GUILD=").append(escape(guild.name)).append("\n");
        out.append("COLOR=").append(escape(normalizeGuildColor(guild.color))).append("\n");
        out.append("ROLE=").append(currentRank == null ? "немає" : escape(currentRank.label)).append("\n");
        out.append("IS_MASTER=").append(currentRank == GuildRank.GUILDMASTER).append("\n");
        Territory activeTerritory = territoryAt(player.level(), player.blockPosition());
        boolean onOwnGuildTerritory = activeTerritory != null && "GUILD".equals(activeTerritory.type) && Objects.equals(activeTerritory.guildId, guild.id);
        out.append("ON_OWN_GUILD_TERRITORY=").append(onOwnGuildTerritory).append("\n");
        out.append("MEMBER_LIMIT=").append(HomeCraftGuildConfig.maxGuildMembers()).append("\n");
        appendGuildLevelSnapshot(out, guild);
        appendTalentSnapshot(out, guild, currentRank == GuildRank.GUILDMASTER);
        appendMembersSnapshot(out, guild, resolveServer(player), true);
        appendTerritoriesSnapshot(out, guild.id);
        appendGolemsSnapshot(out, guild.id, resolveServer(player));
        appendAchievementsSnapshot(out, guild.id);
        return out.toString();
    }

    public static synchronized String snapshotForTerritoryViewer(ServerPlayer viewer, Territory territory) {
        if (territory == null || territory.guildId == null) {
            return "MODE=VIEW\nGUILD=невідома гільдія\nROLE=перегляд\nIS_MASTER=false\nMEMBER_LIMIT=" + HomeCraftGuildConfig.maxGuildMembers() + "\n" + levelSnapshotForLevelOne() + "MEMBERS_BEGIN\nMEMBERS_END\nTERRITORIES_BEGIN\nTERRITORIES_END\nGOLEMS_BEGIN\nGOLEMS_END\n";
        }
        Guild guild = DATA.guilds.get(territory.guildId);
        if (guild == null) {
            return "MODE=VIEW\nGUILD=" + escape(territory.guildName == null ? "невідома гільдія" : territory.guildName) + "\nROLE=перегляд\nIS_MASTER=false\nMEMBER_LIMIT=" + HomeCraftGuildConfig.maxGuildMembers() + "\n" + levelSnapshotForLevelOne() + "MEMBERS_BEGIN\nMEMBERS_END\nTERRITORIES_BEGIN\nTERRITORIES_END\nGOLEMS_BEGIN\nGOLEMS_END\n";
        }
        sanitizeGuildProgress(guild);
        StringBuilder out = new StringBuilder();
        out.append("MODE=VIEW\n");
        out.append("GUILD=").append(escape(guild.name)).append("\n");
        out.append("COLOR=").append(escape(normalizeGuildColor(guild.color))).append("\n");
        out.append("ROLE=перегляд\n");
        out.append("IS_MASTER=false\n");
        out.append("ON_OWN_GUILD_TERRITORY=false\n");
        out.append("MEMBER_LIMIT=").append(HomeCraftGuildConfig.maxGuildMembers()).append("\n");
        appendGuildLevelSnapshot(out, guild);
        appendTalentSnapshot(out, guild, false);
        appendMembersSnapshot(out, guild, resolveServer(viewer), !HomeCraftGuildConfig.guildBedsHideExactCoordsForNonMembers());
        appendTerritoriesSnapshot(out, guild.id);
        appendGolemsSnapshot(out, guild.id, resolveServer(viewer));
        appendAchievementsSnapshot(out, guild.id);
        return out.toString();
    }

    public static synchronized String npcSnapshotFor(ServerPlayer player) {
        Guild guild = guildOf(player);
        if (guild == null) {
            return "MODE=CREATE\nCOST=" + HomeCraftGuildConfig.guildCreateEmeraldCost() + "\nMEMBER_LIMIT=" + HomeCraftGuildConfig.maxGuildMembers() + "\n" + levelSnapshotForLevelOne();
        }
        sanitizeGuildProgress(guild);
        GuildRank rank = rankOf(player);
        if (rank != GuildRank.GUILDMASTER) {
            StringBuilder out = new StringBuilder();
            out.append("MODE=MEMBER\nGUILD=").append(escape(guild.name)).append("\nCOLOR=").append(escape(normalizeGuildColor(guild.color))).append("\nMEMBER_LIMIT=").append(HomeCraftGuildConfig.maxGuildMembers()).append("\n");
            appendGuildLevelSnapshot(out, guild);
            return out.toString();
        }
        int ordinary = countOrdinaryGolems(guild.id);
        int elite = countEliteGolems(guild.id);
        int members = guild.members == null ? 0 : guild.members.size();
        StringBuilder out = new StringBuilder();
        out.append("MODE=MASTER\nGUILD=").append(escape(guild.name)).append("\nCOLOR=").append(escape(normalizeGuildColor(guild.color)))
                .append("\nMEMBERS=").append(members)
                .append("\nMEMBER_LIMIT=").append(HomeCraftGuildConfig.maxGuildMembers())
                .append("\nGOLEMS=").append(ordinary + elite)
                .append("\nORDINARY_GOLEMS=").append(ordinary)
                .append("\nELITE_GOLEMS=").append(elite)
                .append("\nNORMAL_GOLEM_COST=").append(nextOrdinaryGolemCost(guild.id))
                .append("\nELITE_GOLEM_COST=").append(eliteGolemCost(guild.id)).append("\n");
        appendGuildLevelSnapshot(out, guild);
        return out.toString();
    }

    private static void appendMembersSnapshot(StringBuilder out, Guild guild, MinecraftServer server, boolean showExactBedCoords) {
        out.append("MEMBERS_BEGIN\n");
        if (guild != null && guild.members != null) {
            for (Member member : guild.members.values()) {
                GuildRank rank = GuildRank.from(member.rank);
                GuildBedBinding bed = guildBedForPlayer(member.uuid);
                if (bed != null) validateGuildBedBinding(server, bed, false);
                String bedStatus = humanBedStatus(bed);
                String bedCoords = bed != null && bed.claimed() ? (showExactBedCoords ? bed.coords() : "є") : "—";
                out.append(escape(member.uuid)).append('|')
                        .append(escape(member.name)).append('|')
                        .append(escape(rank.name())).append('|')
                        .append(escape(rank.label)).append('|')
                        .append(isOnline(server, member.uuid)).append('|')
                        .append(rank == GuildRank.GUILDMASTER).append('|')
                        .append(escape(bedCoords)).append('|')
                        .append(escape(bedStatus)).append("\n");
            }
        }
        out.append("MEMBERS_END\n");
    }

    private static String humanBedStatus(GuildBedBinding bed) {
        if (bed == null) return "немає";
        String status = bed.status == null ? "" : bed.status.trim().toUpperCase(Locale.ROOT);
        if (bed.claimed()) return "закріплено";
        if (GuildBedStatus.BROKEN.name().equals(status)) return "ліжко зруйноване";
        if (GuildBedStatus.INVALID_TERRITORY.name().equals(status)) return "територія недійсна";
        if (GuildBedStatus.ORPHAN.name().equals(status)) return "потрібна перевірка";
        return "немає";
    }

    private static void appendAchievementsSnapshot(StringBuilder out, String guildId) {
        out.append("ACHIEVEMENTS_BEGIN\n");
        Map<String, GuildAchievement> unlocked = DATA.guildAchievements.get(guildId);
        if (unlocked == null) unlocked = Collections.emptyMap();
        for (AchievementDefinition definition : ACHIEVEMENTS) {
            GuildAchievement achievement = unlocked.get(definition.id);
            out.append(escape(definition.id)).append('|')
                    .append(escape(definition.title)).append('|')
                    .append(escape(definition.category)).append('|')
                    .append(escape(definition.description)).append('|')
                    .append(escape(definition.conditions)).append('|')
                    .append(escape(definition.rewardText)).append('|')
                    .append(definition.xpReward).append('|')
                    .append(definition.emeraldReward).append('|')
                    .append(achievement != null).append('|')
                    .append(escape(achievement == null ? "" : achievement.unlockedAt)).append('|')
                    .append(escape(achievement == null ? "" : achievement.unlockedBy)).append("\n");
        }
        out.append("ACHIEVEMENTS_END\n");
    }

    private static void appendGolemsSnapshot(StringBuilder out, String guildId, MinecraftServer server) {
        out.append("GOLEMS_BEGIN\n");
        for (Golem golem : DATA.golems) {
            if (golem == null || !Objects.equals(golem.guildId, guildId)) continue;
            String displayName = ensureGolemDisplayName(golem);
            GolemHealth health = resolveGolemHealth(server, golem);
            String status = resolveGolemSnapshotStatus(server, golem);
            out.append(escape(golem.uuid)).append('|')
                    .append(escape(golem.elite ? "elite" : golem.type)).append('|')
                    .append(golem.x).append('|').append(golem.y).append('|').append(golem.z).append('|')
                    .append(escape(status)).append('|')
                    .append(golem.elite).append('|')
                    .append(escape(displayName)).append('|')
                    .append(golem.dead).append('|')
                    .append(health.current).append('|')
                    .append(health.max).append('|')
                    .append(escape(golem.assignedRole)).append('|')
                    .append(escape(golem.aiState)).append('|')
                    .append(escape(golem.status)).append("\n");
        }
        out.append("GOLEMS_END\n");
    }

    private static String resolveGolemSnapshotStatus(MinecraftServer server, Golem golem) {
        if (golem == null) return "missing";
        if (golem.dead || golem.removed || "DEAD".equals(golem.status) || "REMOVED".equals(golem.status) || "ORPHAN".equals(golem.status)) return golemStatus(golem);
        if (golem.routeStatus != null && !golem.routeStatus.isBlank()) return golem.routeStatus;
        if (golem.elite && (golem.followGuildmasterUuid == null || golem.followGuildmasterUuid.isBlank()) && isNightGuildmasterOnTerritory(server, golem.guildId)) {
            return "нічна охорона глави";
        }
        return golemStatus(golem);
    }

    private static boolean isNightGuildmasterOnTerritory(MinecraftServer server, String guildId) {
        if (server == null || guildId == null || guildId.isBlank()) return false;
        String masterUuid = guildMasterUuid(guildId);
        if (masterUuid == null || masterUuid.isBlank()) return false;
        for (ServerLevel level : server.getAllLevels()) {
            long day = level.getDayTime() % 24000L;
            if (day < 13000L || day > 23000L) continue;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null || player.level() != level || !player.isAlive()) continue;
                if (!masterUuid.equalsIgnoreCase(player.getUUID().toString())) continue;
                Territory territory = territoryAt(level, player.blockPosition());
                if (territory != null && "GUILD".equals(territory.type) && Objects.equals(territory.guildId, guildId)) return true;
            }
        }
        return false;
    }

    private static GolemHealth resolveGolemHealth(MinecraftServer server, Golem golem) {
        if (golem == null) return new GolemHealth(0, 0);
        if (golem.dead) return new GolemHealth(0, estimatedGolemMaxHealth(golem));
        Entity entity = findLiveGolemEntity(server, golem.uuid);
        if (entity instanceof LivingEntity living) {
            return new GolemHealth(Math.max(0, Math.round(living.getHealth())), Math.max(1, Math.round(living.getMaxHealth())));
        }
        return new GolemHealth(-1, estimatedGolemMaxHealth(golem));
    }

    private static int estimatedGolemMaxHealth(Golem golem) {
        if (golem == null) return 0;
        if (golem.finalMaxHealth > 0) return golem.finalMaxHealth;
        if (golem.guildMaxHealth > 0) return golem.guildMaxHealth;
        if (golem.maxHealth > 0) return golem.maxHealth;
        if (golem.elite) return 600;
        String type = String.valueOf(golem.type == null ? "" : golem.type).toLowerCase(Locale.ROOT);
        if (type.contains("snow_golem")) return 16;
        if (type.contains("copper_golem")) return 48;
        return 400;
    }

    private static Entity findLiveGolemEntity(MinecraftServer server, String uuid) {
        if (server == null || uuid == null || uuid.isBlank()) return null;
        UUID id;
        try { id = UUID.fromString(uuid); } catch (Exception ignored) { return null; }
        for (ServerLevel level : server.getAllLevels()) {
            try {
                Entity entity = level.getEntity(id);
                if (entity != null && entity.isAlive()) return entity;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String levelSnapshotForLevelOne() {
        StringBuilder out = new StringBuilder();
        appendGuildLevelSnapshot(out, null);
        return out.toString();
    }

    private static void appendGuildLevelSnapshot(StringBuilder out, Guild guild) {
        int level = guild == null ? 1 : guild.level;
        int xp = guild == null ? 0 : guild.experience;
        level = GuildLevelTiers.clampLevel(level);
        out.append("GUILD_LEVEL=").append(level).append("\n");
        out.append("GUILD_XP=").append(Math.max(0, xp)).append("\n");
        out.append("GUILD_XP_IN_LEVEL=").append(GuildLevelTiers.xpIntoCurrentLevel(xp, level)).append("\n");
        out.append("GUILD_XP_LEVEL_SIZE=").append(GuildLevelTiers.xpNeededForCurrentLevel(level)).append("\n");
        out.append("GUILD_XP_NEXT_TOTAL=").append(GuildLevelTiers.nextLevelTotalXp(level)).append("\n");
        String guildIdForBonus = guild == null ? null : guild.id;
        out.append("HEALTH_BONUS=").append(0).append("\n");
        out.append("ARMOR_BONUS=").append(guildArmorBonusPercent(guildIdForBonus)).append("\n");
        out.append("DAMAGE_BONUS=").append(guildWeaponDamageBonusPercent(guildIdForBonus)).append("\n");
        out.append("SPEED_BONUS=").append(guildMemberSpeedBonusPercent(guildIdForBonus)).append("\n");
        out.append("JUMP_BONUS=").append(formatDouble(guildMemberJumpBonus(guildIdForBonus))).append("\n");
        out.append("DAMAGE_REDUCTION_BONUS=").append(guildMemberDamageReductionPercent(guildIdForBonus)).append("\n");
        out.append("XP_BONUS=").append(guildPlayerXpBonusPercent(guildIdForBonus)).append("\n");
        out.append("POTION_BONUS=").append(guildPotionDurationBonusPercent(guildIdForBonus)).append("\n");
        out.append("NIGHT_VISION=").append(guildHasNightVision(guildIdForBonus)).append("\n");
        out.append("MOB_KILL_GUILD_XP_BONUS=").append(GuildLevelTiers.mobKillGuildXpBonusPercent(level)).append("\n");
        String guildId = guild == null ? null : guild.id;
        out.append("MAX_GOLEMS=").append(guildId == null ? 1 : maxOrdinaryGolems(guildId) + maxEliteGolems(guildId)).append("\n");
        out.append("MAX_ORDINARY_GOLEMS=").append(guildId == null ? 1 : maxOrdinaryGolems(guildId)).append("\n");
        out.append("MAX_ELITE_GOLEMS=").append(guildId == null ? 0 : maxEliteGolems(guildId)).append("\n");
        out.append("NORMAL_GOLEM_COST=").append(guildId == null ? GuildLevelTiers.ordinaryGolemCost(1, 0) : nextOrdinaryGolemCost(guildId)).append("\n");
        out.append("ELITE_GOLEM_COST=").append(guildId == null ? 0 : eliteGolemCost(guildId)).append("\n");
        out.append("NEXT_ELITE_GOLEM_UNLOCK_LEVEL=").append(guildId == null ? GuildLevelTiers.nextEliteGolemUnlockLevel(1) : nextEliteGolemUnlockLevel(guildId)).append("\n");
        out.append("TERRITORY_LIMIT=").append(HomeCraftGuildConfig.guildBannerLimit()).append("\n");
    }

    private static String formatDouble(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) return String.valueOf((int)Math.rint(value));
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static void appendTalentSnapshot(StringBuilder out, Guild guild, boolean canManage) {
        int level = guild == null ? 1 : GuildLevelTiers.clampLevel(guild.level);
        if (guild != null) sanitizeGuildTalents(guild);
        int total = talentPointsForLevel(level);
        int spentMember = guild == null ? 0 : spentTalentPoints(guild, GuildTalents.MEMBER);
        int spentGolem = guild == null ? 0 : spentTalentPoints(guild, GuildTalents.GOLEM);
        String guildId = guild == null ? null : guild.id;
        out.append("TALENTS_ENABLED=").append(HomeCraftGuildConfig.guildTalentsEnabled()).append("\n");
        out.append("CAN_MANAGE_TALENTS=").append(canManage).append("\n");
        out.append("TALENT_RESET_COST=").append(HomeCraftGuildConfig.guildTalentResetCostEmeralds()).append("\n");
        out.append("MEMBER_TALENT_TOTAL=").append(total).append("\n");
        out.append("MEMBER_TALENT_SPENT=").append(spentMember).append("\n");
        out.append("MEMBER_TALENT_AVAILABLE=").append(Math.max(0, total - spentMember)).append("\n");
        out.append("GOLEM_TALENT_TOTAL=").append(total).append("\n");
        out.append("GOLEM_TALENT_SPENT=").append(spentGolem).append("\n");
        out.append("GOLEM_TALENT_AVAILABLE=").append(Math.max(0, total - spentGolem)).append("\n");
        out.append("GOLEM_TALENT_HEALTH=").append(guildGolemTalentHealthBonusPercent(guildId)).append("\n");
        out.append("GOLEM_TALENT_DAMAGE=").append(guildGolemTalentDamageBonusPercent(guildId)).append("\n");
        out.append("GOLEM_TALENT_SPEED=").append(guildGolemTalentSpeedBonusPercent(guildId)).append("\n");
        out.append("GOLEM_TALENT_HEALING=").append(guildGolemTalentHealingSpeedBonusPercent(guildId)).append("\n");
        out.append("GOLEM_CRUSHING_FORCE=").append(guildGolemCrushingForcePercent(guildId)).append("\n");
        out.append("GOLEM_OOC_REGEN_INTERVAL_SECONDS=").append(guildGolemOutOfCombatRegenIntervalTicks(guildId) <= 0 ? 0 : guildGolemOutOfCombatRegenIntervalTicks(guildId) / 20).append("\n");
        out.append("GOLEM_ROUTE_EFFICIENCY=").append(guildGolemRouteEfficiencyBonusPercent(guildId)).append("\n");
        out.append("GOLEM_INTERCEPT_TALENT=").append(guildGolemInterceptTalent(guildId)).append("\n");
        out.append("GOLEM_ELITE_COMMAND_TALENT=").append(guildGolemEliteCommandTalent(guildId)).append("\n");
        out.append("GOLEM_NIGHT_WATCH_TALENT=").append(guildGolemNightWatchTalent(guildId)).append("\n");
        out.append("MEMBER_WATER_BREATHING=").append(guildHasWaterBreathing(guildId)).append("\n");
        out.append("MEMBER_FIRE_RESISTANCE=").append(guildHasFireResistance(guildId)).append("\n");
        out.append("TALENTS_BEGIN\n");
        for (GuildTalents.TalentDefinition definition : GuildTalents.definitions()) {
            boolean unlocked = guild != null && unlockedTalentIds(guild, definition.branch()).contains(definition.id());
            boolean prerequisiteOk = true;
            for (String prerequisiteId : GuildTalents.prerequisites(definition.prerequisite())) {
                if (guild == null || !unlockedTalentIds(guild, definition.branch()).contains(prerequisiteId)) {
                    prerequisiteOk = false;
                    break;
                }
            }
            int available = GuildTalents.MEMBER.equals(definition.branch()) ? Math.max(0, total - spentMember) : Math.max(0, total - spentGolem);
            String status;
            if (unlocked) status = "unlocked";
            else if (level < definition.requiredGuildLevel()) status = "required_level:" + definition.requiredGuildLevel();
            else if (!prerequisiteOk) status = "missing_prerequisite";
            else if (available < definition.cost()) status = "no_points";
            else status = "available";
            out.append(escape(definition.id())).append('|')
                    .append(escape(definition.branch())).append('|')
                    .append(definition.row()).append('|')
                    .append(escape(definition.title())).append('|')
                    .append(escape(definition.description())).append('|')
                    .append(escape(definition.effect())).append('|')
                    .append(definition.cost()).append('|')
                    .append(definition.requiredGuildLevel()).append('|')
                    .append(escape(definition.prerequisite())).append('|')
                    .append(unlocked).append('|')
                    .append(escape(status)).append('|')
                    .append(escape(definition.icon())).append("\n");
        }
        out.append("TALENTS_END\n");
    }

    private static void appendTerritoriesSnapshot(StringBuilder out, String guildId) {
        out.append("TERRITORIES_BEGIN\n");
        for (Territory territory : DATA.territories) {
            if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, guildId)) continue;
            out.append(escape(territory.id)).append('|')
                    .append(escape(territory.dimension)).append('|')
                    .append(territory.x).append('|').append(territory.y).append('|').append(territory.z).append('|')
                    .append(territory.size).append('|')
                    .append(minX(territory)).append('|').append(maxX(territory)).append('|')
                    .append(minZ(territory)).append('|').append(maxZ(territory)).append('|')
                    .append(territory.spawnClipped).append("\n");
        }
        out.append("TERRITORIES_END\n");
    }

    private static List<Invite> invitesFor(ServerPlayer player) {
        String playerUuid = uuid(player);
        List<Invite> out = new ArrayList<>();
        List<Invite> modern = DATA.invitesV2.get(playerUuid);
        if (modern != null) out.addAll(modern);
        String legacyGuildId = DATA.invites.get(playerUuid);
        if (legacyGuildId != null && out.stream().noneMatch(i -> i != null && Objects.equals(i.guildId, legacyGuildId))) {
            Guild guild = DATA.guilds.get(legacyGuildId);
            Invite invite = new Invite();
            invite.guildId = legacyGuildId;
            invite.guildName = guild == null ? legacyGuildId : guild.name;
            invite.invitedByName = "";
            out.add(invite);
        }
        out.removeIf(Objects::isNull);
        return out;
    }

    private static MinecraftServer resolveServer(ServerPlayer player) {
        if (player == null) return null;
        try {
            Object value = player.getClass().getMethod("getServer").invoke(player);
            if (value instanceof MinecraftServer ms) return ms;
        } catch (Exception ignored) {}
        try {
            Object level = player.level();
            Object value = level.getClass().getMethod("getServer").invoke(level);
            if (value instanceof MinecraftServer ms) return ms;
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isOnline(MinecraftServer server, String uuid) {
        if (server == null || uuid == null) return false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (uuid.equalsIgnoreCase(player.getUUID().toString())) return true;
        }
        return false;
    }

    private static String escape(String value) {
        return String.valueOf(value == null ? "" : value).replace("|", "/").replace("\n", " ").replace("\r", " ").trim();
    }

    public static synchronized String territoriesJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", "homecraft-guild-neoforge");
        root.put("updatedAt", Instant.now().toString());
        root.put("territories", DATA.territories);
        return GSON.toJson(root);
    }

    private static String uuid(ServerPlayer player) {
        return player.getUUID().toString().toLowerCase(Locale.ROOT);
    }

    private static String cleanName(String name) {
        String raw = String.valueOf(name == null ? "" : name).trim();
        raw = raw.replaceAll("[^A-Za-z0-9 А-Яа-яІіЇїЄєҐґ_\\-']", "").trim();
        return raw.length() > 32 ? raw.substring(0, 32) : raw;
    }

    public static String dimensionId(LevelAccessor level) {
        try {
            Object key = level.getClass().getMethod("dimension").invoke(level);
            return String.valueOf(key).replace("ResourceKey[minecraft:dimension / ", "").replace("]", "");
        } catch (Exception ignored) {
            return "minecraft:overworld";
        }
    }

    public static final class AchievementDefinition {
        public final String id;
        public final String title;
        public final String category;
        public final String description;
        public final String conditions;
        public final String rewardText;
        public final int xpReward;
        public final int emeraldReward;

        AchievementDefinition(String id, String title, String category, String description, String conditions, String rewardText, int xpReward, int emeraldReward) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.description = description;
            this.conditions = conditions;
            this.rewardText = rewardText;
            this.xpReward = xpReward;
            this.emeraldReward = emeraldReward;
        }
    }

    public static final class GuildAchievement {
        public String id;
        public String unlockedAt;
        public String unlockedBy;
        public int xpReward;
        public int emeraldReward;
    }

    public static final class GuildAchievementUnlock {
        public final String guildId;
        public final String guildName;
        public final AchievementDefinition definition;
        public final GuildAchievement achievement;
        public final int oldXp;
        public final int newXp;
        public final int oldLevel;
        public final int newLevel;

        GuildAchievementUnlock(String guildId, String guildName, AchievementDefinition definition, GuildAchievement achievement, int oldXp, int newXp, int oldLevel, int newLevel) {
            this.guildId = guildId;
            this.guildName = guildName;
            this.definition = definition;
            this.achievement = achievement;
            this.oldXp = oldXp;
            this.newXp = newXp;
            this.oldLevel = oldLevel;
            this.newLevel = newLevel;
        }

        public boolean leveledUp() { return newLevel > oldLevel; }
    }

    private static final class GolemHealth {
        final int current;
        final int max;

        GolemHealth(int current, int max) {
            this.current = current;
            this.max = Math.max(0, max);
        }
    }

    public static final class GuildBuffSnapshot {
        public final String guildId;
        public final long revision;
        public final int level;
        public final int healthBonusPercent;
        public final int armorBonusPercent;
        public final int damageBonusPercent;
        public final int speedBonusPercent;
        public final double jumpBonus;
        public final int damageReductionPercent;
        public final boolean nightVision;
        public final boolean waterBreathing;
        public final boolean fireResistance;

        public GuildBuffSnapshot(String guildId, long revision, int level, int healthBonusPercent, int armorBonusPercent, int damageBonusPercent, int speedBonusPercent, double jumpBonus, int damageReductionPercent, boolean nightVision, boolean waterBreathing, boolean fireResistance) {
            this.guildId = guildId == null ? "" : guildId;
            this.revision = revision;
            this.level = Math.max(1, level);
            this.healthBonusPercent = Math.max(0, healthBonusPercent);
            this.armorBonusPercent = Math.max(0, armorBonusPercent);
            this.damageBonusPercent = Math.max(0, damageBonusPercent);
            this.speedBonusPercent = Math.max(0, speedBonusPercent);
            this.jumpBonus = Math.max(0.0D, jumpBonus);
            this.damageReductionPercent = Math.max(0, damageReductionPercent);
            this.nightVision = nightVision;
            this.waterBreathing = waterBreathing;
            this.fireResistance = fireResistance;
        }
    }

    public static final class Data {
        public Map<String, Guild> guilds = new LinkedHashMap<>();
        public Map<String, String> members = new LinkedHashMap<>();
        public List<Territory> territories = new ArrayList<>();
        public Map<String, String> bedOwners = new LinkedHashMap<>();
        public Map<String, GuildBedBinding> guildBeds = new LinkedHashMap<>();
        public Map<String, String> invites = new LinkedHashMap<>();
        public Map<String, List<Invite>> invitesV2 = new LinkedHashMap<>();
        public List<Golem> golems = new ArrayList<>();
        public Map<String, Map<String, GuildAchievement>> guildAchievements = new LinkedHashMap<>();
        public Map<String, GolemRouteLearning> golemRouteLearning = new LinkedHashMap<>();
    }

    public static final class GuildTalentData {
        public int version = 1;
        public GuildTalentBranchData member = new GuildTalentBranchData();
        public GuildTalentBranchData golem = new GuildTalentBranchData();
        public long lastResetAt = 0L;
        /** Legacy migration field: old builds may have stored all unlocked ids in one list. */
        public List<String> unlocked;
    }

    public static final class GuildTalentBranchData {
        public List<String> unlocked = new ArrayList<>();
    }

    public static final class GuildTalentResult {
        public final boolean success;
        public final String message;
        private GuildTalentResult(boolean success, String message) { this.success = success; this.message = message == null ? "" : message; }
        public static GuildTalentResult ok(String message) { return new GuildTalentResult(true, message); }
        public static GuildTalentResult fail(String message) { return new GuildTalentResult(false, message); }
    }

    public static final class Guild {
        public String id;
        public String name;
        public String color = "magenta";
        public String createdAt;
        public int level = 1;
        public int experience = 0;
        public int mobKillXpBonusRemainder = 0;
        public GuildTalentData talents = new GuildTalentData();
        public String guildmasterUuid;
        public String guildmasterName;
        public Map<String, Member> members = new LinkedHashMap<>();
    }

    public static final class Member {
        public String uuid;
        public String name;
        public String rank;
        public String joinedAt;
    }

    public static final class Invite {
        public String guildId;
        public String guildName;
        public String invitedByUuid;
        public String invitedByName;
        public String createdAt;
    }

    public static final class GolemRouteLearning {
        public String guildId;
        public String dimension;
        public int cellSize;
        public int cellX;
        public int cellZ;
        public int lastY;
        public int samples;
        public int successes;
        public int failures;
        public int score;
        public long touchedAtMs;
        public String lastReason = "";
        public String lastSeenAt;
    }

    public static final class Golem {
        public String uuid;
        public String guildId;
        public String type;
        public String name;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public boolean elite;
        public boolean dead;
        public String golemType;
        public String behaviorProfile;
        public String assignedRole;
        public String aiState;
        public String status;
        /** Legacy field kept for compatibility; mirrors guildMaxHealth after v97 migration. */
        public int maxHealth;
        public int golemStatsVersion;
        public double baseMaxHealth;
        public int guildMaxHealth;
        public double baseDamage = -1.0D;
        public double guildDamage;
        public int talentMaxHealthBonusPercent;
        public int finalMaxHealth;
        public int talentDamageBonusPercent;
        public double finalDamage;
        public int talentSpeedBonusPercent;
        public int talentHealingSpeedBonusPercent;
        public double healthMultiplier;
        public double damageMultiplier;
        public int healthBonusPercent;
        public int damageBonusPercent;
        public int lastSafeX;
        public int lastSafeY;
        public int lastSafeZ;
        public int lastPatrolX;
        public int lastPatrolY;
        public int lastPatrolZ;
        public int lastStrategicX;
        public int lastStrategicY;
        public int lastStrategicZ;
        public long deathTimeMs;
        public boolean respawnPending;
        public boolean removed;
        public boolean elitePriorityMode;
        public int pathFailureCount;
        public long lastPathRecalcTick;
        public long lastHealthCheckTick;
        public long lastHealingParticleTick;
        public long lastHealingApplyTick;
        public boolean healingAtTotem;
        public String preHealingAiState;
        public String preHealingAssignedRole;
        public int lastHealingTotemX;
        public int lastHealingTotemY;
        public int lastHealingTotemZ;
        public double lastPathTargetX;
        public double lastPathTargetY;
        public double lastPathTargetZ;
        public String routeStatus;
        public String lastRouteFailureReason;
        public String lastBlockedRouteKey;
        public long routeLastCheckTick;
        public double routeLastX;
        public double routeLastY;
        public double routeLastZ;
        public double routeLastDistanceSqr;
        public int routeStuckTicks;
        public long lastAsyncRouteRequestTick;
        public long routeStatusUpdatedTick;
        public String lastTargetUuid;
        public String entityUuid;
        public String followGuildmasterUuid = "";
        public String createdAt;
        public String lastSeenAt;
        public int missingTicks;
    }

    public static final class GuildExperienceGain {
        public final String guildId;
        public final String guildName;
        public final int added;
        public final int oldXp;
        public final int newXp;
        public final int oldLevel;
        public final int newLevel;

        public GuildExperienceGain(String guildId, String guildName, int added, int oldXp, int newXp, int oldLevel, int newLevel) {
            this.guildId = guildId;
            this.guildName = guildName;
            this.added = added;
            this.oldXp = oldXp;
            this.newXp = newXp;
            this.oldLevel = oldLevel;
            this.newLevel = newLevel;
        }

        public boolean leveledUp() { return newLevel > oldLevel; }
    }

    public static final class TerritoryStaticInfo {
        public final String id;
        public final String type;
        public final String guildId;
        public final String guildName;
        public final String guildColor;
        public final String dimension;
        public final int x;
        public final int y;
        public final int z;
        public final int size;
        public final int minX;
        public final int maxX;
        public final int minZ;
        public final int maxZ;
        public final boolean northOpen;
        public final boolean southOpen;
        public final boolean westOpen;
        public final boolean eastOpen;
        public final int openSides;
        public final int perimeterBlocks;
        public TerritoryStaticInfo(String id, String type, String guildId, String guildName, String guildColor, String dimension,
                                   int x, int y, int z, int size, int minX, int maxX, int minZ, int maxZ,
                                   boolean northOpen, boolean southOpen, boolean westOpen, boolean eastOpen,
                                   int openSides, int perimeterBlocks) {
            this.id = id;
            this.type = type;
            this.guildId = guildId;
            this.guildName = guildName;
            this.guildColor = guildColor;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.northOpen = northOpen;
            this.southOpen = southOpen;
            this.westOpen = westOpen;
            this.eastOpen = eastOpen;
            this.openSides = openSides;
            this.perimeterBlocks = perimeterBlocks;
        }
    }

    public static final class Territory {
        public String id;
        public String type;
        public String ownerUuid;
        public String ownerName;
        public String guildId;
        public String guildName;
        public String guildColor;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public int size;
        public boolean spawnClipped;
        public String createdAt;
    }
}
