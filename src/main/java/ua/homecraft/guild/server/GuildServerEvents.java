package ua.homecraft.guild.server;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import ua.homecraft.guild.entity.GuildRegistrarEntity;
import ua.homecraft.guild.entity.HomeCraftGuildEntities;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import ua.homecraft.guild.block.HomeCraftGuildBlocks;
import ua.homecraft.guild.item.HomeCraftGuildItems;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.TriState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.network.OpenCreateGuildPayload;
import ua.homecraft.guild.network.OpenGuildRosterPayload;
import ua.homecraft.guild.network.OpenGuildNpcAdminPayload;
import ua.homecraft.guild.network.GuildVisualSyncPayload;
import ua.homecraft.guild.network.GuildTerritoryVisualSyncPayload;
import ua.homecraft.guild.network.GuildNpcSyncPayload;
import ua.homecraft.guild.network.GuildNpcSkinRegistryPayload;
import ua.homecraft.guild.network.GuildNpcAdminSnapshotPayload;
import ua.homecraft.guild.network.GuildNpcTradeValidationResultPayload;
import ua.homecraft.guild.effect.HomeCraftGuildEffects;
import ua.homecraft.guild.server.golem.GuildGolemBrain;
import ua.homecraft.guild.server.golem.GuildGolemSafePositionResolver;
import ua.homecraft.guild.server.golem.GuildGolemStats;
import ua.homecraft.guild.server.bed.GuildBedManager;
import ua.homecraft.guild.server.npc.NpcSyncManager;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@EventBusSubscriber(modid = HomeCraftGuildMod.MOD_ID)
public final class GuildServerEvents {
    private static MinecraftServer server;
    private static long lastSyncMs = 0L;
    private static final int NPC_ADMIN_SNAPSHOT_CHUNK_CHARS = 28_000;
    private static final AtomicLong NPC_ADMIN_SNAPSHOT_SEQUENCE = new AtomicLong();
    private static int golemTickCounter = 0;
    private static int golemSafetyTickCounter = 0;
    private static int golemThreatAlarmTickCounter = 0;
    private static int guildMemberThreatScanCursor = 0;
    private static int golemAiScanCursor = 0;
    private static int golemSafetyScanCursor = 0;
    private static int guildBuffTickCounter = 0;
    private static int guildVisualSyncTickCounter = 0;
    private static int portalTravelTickCounter = 0;
    private static int combatCleanupTickCounter = 0;
    private static int golemReviveTickCounter = 0;
    private static int golemRouteLearningFlushTickCounter = 0;
    private static int guildBuffPlayerCursor = 0;
    private static int guildVisualSyncPlayerCursor = 0;
    private static int guildWallTerritoryCursor = 0;
    private static int guildTotemTerritoryCursor = 0;
    private static int guildRepairTerritoryCursor = 0;
    private static int cleanupUnboundGolemCursor = 0;
    private static int cleanupUnboundTickCounter = 0;
    private static final Map<String, GolemStuckState> GOLEM_STUCK = new HashMap<>();
    private static final Map<String, List<GuildCombatAlert>> GUILD_COMBAT_ALERTS = new HashMap<>();
    private static final Map<String, Long> GUILD_MEMBER_THREAT_ALERT_COOLDOWN = new HashMap<>();
    private static final int GOLEM_STUCK_CHECKS_BEFORE_RETURN = 6;
    private static final double GOLEM_STUCK_MIN_MOVED_SQR = 0.45D * 0.45D;
    private static final int GOLEM_SOFT_REROUTE_BLOCKED_CHECKS = 4;
    private static final int GOLEM_HARD_RETURN_BLOCKED_CHECKS = 16;
    private static final long GOLEM_POST_TELEPORT_GRACE_TICKS = 30L;
    private static final long GOLEM_CHASE_REPATH_MIN_TICKS = 10L;
    private static final int GOLEM_UNDERGROUND_IGNORE_SURFACE_DELTA = 7;
    private static final String GOLEM_PATROL_SYSTEM_VERSION = "5.3.0-client-borders-strict-golem-leash";
    private static final int GOLEM_PATROL_WATER_AVOID_RADIUS = 2;
    private static final int GOLEM_PATROL_MAX_POINT_ATTEMPTS = 56;
    private static final int GOLEM_ROUTE_DETOUR_OUTSIDE_BUFFER_BLOCKS = 2;
    private static final int GOLEM_CONNECTED_TERRITORY_MAX_BOUNDARY_GAP = 2;
    private static final int GOLEM_EMERGENCY_OUTSIDE_CHASE_BUFFER_BLOCKS = 12;
    private static final double GOLEM_EMERGENCY_ALERT_TARGET_RADIUS = 18.0D;
    private static final int GOLEM_TERRAIN_PROFILE_CACHE_TICKS = 80;
    private static final int GOLEM_PATROL_COVERAGE_MAX_CELLS = 96;
    private static final int GOLEM_PATROL_COVERAGE_MIN_CELLS = 16;
    private static final double GOLEM_PATROL_DAY_MIN_DISTANCE = 20.0D;
    private static final double GOLEM_PATROL_NIGHT_MIN_DISTANCE = 14.0D;
    private static final int GOLEM_DEEP_DROP_CHECK_RADIUS = 1;
    private static final int GOLEM_DEEP_DROP_DANGER_HEIGHT = 7;
    private static final int GOLEM_STRUCTURE_SCAN_RADIUS = 2;
    private static final int GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE = 140;
    private static final int GOLEM_BALCONY_TERRACE_DROP_HEIGHT = 4;
    private static final int GOLEM_UNEVEN_RECOVERY_MAX_RADIUS = 18;
    private static final int GOLEM_UNEVEN_RECOVERY_MAX_VERTICAL_DELTA = 5;
    private static final int GOLEM_RECOVERY_ATTEMPTS_BEFORE_TELEPORT = 3;
    private static final double GOLEM_DAY_RECOVERY_START_HEALTH_RATIO = 0.50D;
    private static final double GOLEM_DAY_RECOVERY_STOP_HEALTH_RATIO = 0.999D;
    private static final double GOLEM_DAY_RECOVERY_CHARGE_RADIUS = 4.75D;
    private static final double GOLEM_DAY_RECOVERY_HEAL_FRACTION_PER_AI_SLICE = 0.035D;
    private static final double GOLEM_DAY_RECOVERY_MIN_HEAL_PER_AI_SLICE = 1.0D;
    private static final int GOLEM_TRAPPED_RECOVERY_ATTEMPTS_BEFORE_TELEPORT = 2;
    private static final int GOLEM_MANUAL_TOTEM_TELEPORT_SEARCH_RADIUS = 20;
    private static final int GOLEM_SWARM_MIN_DESTINATION_SEPARATION = 14;
    private static final int GOLEM_SWARM_ROUTE_RESERVATION_TICKS = 20 * 9;
    private static final int GOLEM_SWARM_TRAIL_REPATH_DISTANCE = 7;
    private static final int GOLEM_SWARM_NORMAL_RESPONDERS = 1;
    private static final int GOLEM_SWARM_MEMBER_DANGER_RESPONDERS = 2;
    private static final int GOLEM_SWARM_HIGH_ALERT_RESPONDERS = 2;
    private static final int GOLEM_SWARM_ASSIGNMENT_TICKS = 20 * 6;
    private static final int GOLEM_TERRITORY_ANALYSIS_CELL_SIZE = 8;
    private static final int GOLEM_TERRITORY_ANALYSIS_CACHE_TICKS = 20 * 45;
    private static final int GOLEM_TERRITORY_ANALYSIS_MAX_SAMPLES = 192;
    private static final int GOLEM_TERRITORY_ANALYSIS_ROUTE_REJECT = 170;
    private static final long GUILD_COMBAT_ALERT_DURATION_TICKS = 20L * 20L;
    private static final double GUILD_COMBAT_ALERT_REACT_RANGE = 112.0D;
    private static final Map<String, PendingGolemRevive> PENDING_GOLEM_REVIVES = new HashMap<>();
    private static final Map<String, GolemPatrolMemory> GOLEM_PATROL_MEMORY = new HashMap<>();
    private static final Map<String, Long> GOLEM_LAST_FORCED_TELEPORT_TICK = new HashMap<>();
    private static final Map<String, PlayerTravelMemory> PLAYER_TRAVEL_MEMORY = new HashMap<>();
    private static final Map<String, List<PendingPortalEntry>> PENDING_PORTAL_ENTRIES = new HashMap<>();
    private static final Map<String, Map<String, CombatContribution>> ENTITY_COMBAT_CONTRIBUTORS = new HashMap<>();
    private static final Map<String, Long> GOLEM_ALERT_CHATTER_TICK = new HashMap<>();
    private static final Map<String, GolemNavigationMemory> GOLEM_NAV_MEMORY = new HashMap<>();
    private static final Map<String, GolemTargetCache> GOLEM_TARGET_CACHE = new HashMap<>();
    private static final Map<String, GolemPatrolPlan> GOLEM_PATROL_PLAN = new HashMap<>();
    private static final Map<String, LearningPenaltyCache> GOLEM_LEARNING_PENALTY_CACHE = new HashMap<>();
    private static final Map<String, Long> GOLEM_ROUTE_LEARNING_SAMPLE_TICK = new HashMap<>();
    private static final Map<String, TerrainProfileCache> GOLEM_TERRAIN_PROFILE_CACHE = new HashMap<>();
    private static final Map<String, GolemRouteReservation> GOLEM_ROUTE_RESERVATIONS = new HashMap<>();
    private static final Map<String, GolemThreatAssignment> GOLEM_THREAT_ASSIGNMENTS = new HashMap<>();
    private static final Map<String, GolemTaskState> GOLEM_TASK_STATE = new HashMap<>();
    private static final Map<String, TerritoryIntelligenceCache> GOLEM_TERRITORY_INTELLIGENCE_CACHE = new HashMap<>();
    private static final Map<String, TerritoryActivityCache> GOLEM_TERRITORY_ACTIVITY_CACHE = new HashMap<>();
    private static final Map<UUID, EntityLookupCache> GOLEM_ENTITY_LOOKUP_CACHE = new HashMap<>();
    private static final Map<String, Long> GOLEM_GUILD_HOT_UNTIL_TICK = new HashMap<>();
    private static final Map<String, Long> GOLEM_GUILD_HOT_CHECK_TICK = new HashMap<>();
    private static final Map<String, WallSurfaceYCache> GUILD_WALL_SURFACE_Y_CACHE = new HashMap<>();
    private static final Map<UUID, PlayerVisualSyncState> GUILD_VISUAL_SYNC_STATE = new HashMap<>();
    private static final Map<UUID, PendingNpcLoginSync> PENDING_NPC_LOGIN_SYNCS = new HashMap<>();
    private static final Map<String, GuildBuffState> GUILD_BUFF_STATE_CACHE = new HashMap<>();
    private static final Set<String> GUILD_POTION_EXTENSION_GUARD = ConcurrentHashMap.newKeySet();
    private static PlayerHeatSnapshot GOLEM_PLAYER_HEAT_CACHE = null;
    private static final Map<String, GuildAwarenessSnapshot> GOLEM_GUILD_AWARENESS_CACHE = new HashMap<>();
    private static long guildStoreTerritoryRevisionSeen = -1L;
    private static long guildStoreGolemRevisionSeen = -1L;
    private static long guildRuntimeRevisionSeen = -1L;

    // Adaptive TPS governor: reads real wall-clock tick cadence and turns expensive guild/golem
    // logic into a circuit-breaker pipeline when the server is already behind. This is intentionally
    // global and automatic, because a large server can have thousands of golem records but only a
    // small number of hot/loaded/player-near territories at any moment.
    private static long golemGovernorLastPostTickNs = 0L;
    private static double golemGovernorTickEwmaMs = 50.0D;
    private static int golemGovernorLagLevel = 0;
    private static long golemGovernorRescueUntilFrame = 0L;
    private static long golemGovernorLastLogFrame = 0L;
    private static long golemGovernorCriticalTripCount = 0L;
    private static long golemGovernorQuarantineUntilFrame = 0L;
    private static int golemGovernorLastLoggedLevel = -1;
    private static long golemScaleLastRefreshFrame = -200L;
    private static long guildLastTotemRepairRevision = -1L;
    private static long guildLastTotemRepairFrame = 0L;
    private static long guildLastTotemCleanupMs = 0L;

    private static final int GOLEM_SAFETY_PASS_INTERVAL_TICKS = 20;
    private static final int GOLEM_THREAT_ALARM_SCAN_INTERVAL_TICKS = 40;
    private static final int GUILD_MEMBER_THREAT_SCAN_INTERVAL_TICKS = 10;
    private static final int GUILD_MEMBER_THREAT_SCAN_RADIUS_DAY = 18;
    private static final int GUILD_MEMBER_THREAT_SCAN_RADIUS_NIGHT = 28;
    private static final long GUILD_MEMBER_THREAT_ALERT_COOLDOWN_TICKS = 20L;
    private static final long GUILD_MEMBER_DEFENSE_HOT_TICKS = 20L * 18L;
    private static final int GOLEM_ENTITY_SNAPSHOT_CACHE_TICKS = 16;
    private static final int GOLEM_ENTITY_LOOKUP_CACHE_TICKS = 80;
    private static final int GOLEM_ENTITY_LOOKUP_CACHE_MAX = 8192;
    private static final int GOLEM_MASSIVE_MODE_GOLEM_THRESHOLD = 128;
    private static final int GOLEM_EXTREME_MODE_GOLEM_THRESHOLD = 512;
    private static final int GOLEM_MASSIVE_MODE_TERRITORY_THRESHOLD = 256;
    private static final int GOLEM_EXTREME_MODE_TERRITORY_THRESHOLD = 1024;
    private static final int GOLEM_ULTRA_MODE_GOLEM_THRESHOLD = 1500;
    private static final int GOLEM_ULTRA_MODE_TERRITORY_THRESHOLD = 2048;
    private static final int GOLEM_SCALE_NORMAL = 0;
    private static final int GOLEM_SCALE_MASSIVE = 1;
    private static final int GOLEM_SCALE_EXTREME = 2;
    private static final int GOLEM_SCALE_ULTRA = 3;
    private static int golemScaleMode = GOLEM_SCALE_NORMAL;
    private static int golemScaleGolemCount = 0;
    private static int golemScaleTerritoryCount = 0;
    private static long golemScaleFrame = -1L;
    private static final long GOLEM_SERVER_TICK_BUDGET_NS_NORMAL = 1_800_000L;
    private static final long GOLEM_SERVER_TICK_BUDGET_NS_MASSIVE = 900_000L;
    private static final long GOLEM_SERVER_TICK_BUDGET_NS_EXTREME = 500_000L;
    private static final long GOLEM_SERVER_TICK_BUDGET_NS_ULTRA = 280_000L;
    private static final double GOLEM_GOVERNOR_LAG_WARN_MS = 80.0D;
    private static final double GOLEM_GOVERNOR_LAG_STRONG_MS = 150.0D;
    private static final double GOLEM_GOVERNOR_LAG_CRITICAL_MS = 250.0D;
    private static final double GOLEM_GOVERNOR_EWMA_WARN_MS = 62.0D;
    private static final double GOLEM_GOVERNOR_EWMA_STRONG_MS = 90.0D;
    private static final double GOLEM_GOVERNOR_EWMA_CRITICAL_MS = 140.0D;
    private static final long GOLEM_GOVERNOR_RESCUE_FRAMES_LIGHT = 20L * 3L;
    private static final long GOLEM_GOVERNOR_RESCUE_FRAMES_STRONG = 20L * 8L;
    private static final long GOLEM_GOVERNOR_RESCUE_FRAMES_CRITICAL = 20L * 18L;
    private static final int GOLEM_HOT_GUILD_CHECK_TICKS_NORMAL = 40;
    private static final int GOLEM_HOT_GUILD_CHECK_TICKS_MASSIVE = 100;
    private static final int GOLEM_HOT_GUILD_CHECK_TICKS_EXTREME = 180;
    private static final int GOLEM_HOT_GUILD_CHECK_TICKS_ULTRA = 300;
    private static final int GOLEM_HOT_GUILD_TTL_TICKS = 20 * 12;
    private static final int GOLEM_HOT_GUILD_RADIUS_NORMAL = 144;
    private static final int GOLEM_HOT_GUILD_RADIUS_SCALE = 192;
    private static final int GOLEM_PLAYER_HEAT_CELL_SHIFT = 8; // 256x256: cheap player-near lookups for thousands of guild zones.
    private static final int GOLEM_PLAYER_HEAT_CELL_SIZE = 1 << GOLEM_PLAYER_HEAT_CELL_SHIFT;
    private static final int GOLEM_EXISTING_ROUTE_MIN_TICKS_NORMAL = 20 * 8;
    private static final int GOLEM_EXISTING_ROUTE_MIN_TICKS_MASSIVE = 20 * 18;
    private static final int GOLEM_EXISTING_ROUTE_MIN_TICKS_EXTREME = 20 * 35;
    private static final int GOLEM_EXISTING_ROUTE_MIN_TICKS_ULTRA = 20 * 60;
    private static final int GOLEM_EXISTING_ROUTE_MIN_TICKS_RESCUE = 20 * 120;
    private static final int GOLEM_PLAYER_VISIBILITY_RADIUS = 96;
    private static final int GOLEM_COLD_IDLE_HEARTBEAT_TICKS_NORMAL = 20 * 240;
    private static final int GOLEM_COLD_IDLE_HEARTBEAT_TICKS_MASSIVE = 20 * 360;
    private static final int GOLEM_COLD_IDLE_HEARTBEAT_TICKS_RESCUE = 20 * 600;

    // Multicore actor-planner. Minecraft world/entity access stays on the server thread only.
    // Worker threads receive immutable numeric snapshots (territory rectangles, role, tick, load mode)
    // and return only macro X/Z route intents. The main thread validates Y/safety/path before applying.
    private static final int GOLEM_ASYNC_PLANNER_THREADS = Math.max(1, Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1)));
    private static final int GOLEM_ASYNC_PLANNER_QUEUE_CAP = 384;
    private static final int GOLEM_ASYNC_PLANNER_RESULT_TTL_FRAMES = 20 * 45;
    private static final int GOLEM_ASYNC_PLANNER_PENDING_TTL_FRAMES = 20 * 8;
    private static final int GOLEM_ASYNC_PLANNER_RETRY_FRAMES = 20 * 5;
    private static final int GOLEM_ASYNC_PLANNER_MAX_TERRITORIES_PER_JOB = 32;
    private static final int GOLEM_ASYNC_PLANNER_PER_GUILD_PENDING_CAP = 8;
    private static final int GOLEM_ASYNC_PRIORITY_CRITICAL = 100;
    private static final int GOLEM_ASYNC_PRIORITY_HIGH = 75;
    private static final int GOLEM_ASYNC_PRIORITY_NORMAL = 45;
    private static final int GOLEM_ASYNC_PRIORITY_BACKGROUND = 15;
    private static final AtomicLong GOLEM_ASYNC_TASK_SEQUENCE = new AtomicLong(0L);
    private static long golemAsyncSubmitFrame = -1L;
    private static int golemAsyncSubmissionsThisFrame = 0;
    private static final ThreadPoolExecutor GOLEM_ASYNC_PLANNER = new ThreadPoolExecutor(
            GOLEM_ASYNC_PLANNER_THREADS,
            GOLEM_ASYNC_PLANNER_THREADS,
            45L,
            TimeUnit.SECONDS,
            new PriorityBlockingQueue<>(),
            new ThreadFactory() {
                private final AtomicInteger ids = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "homecraft-guild-golem-planner-" + ids.getAndIncrement());
                    thread.setDaemon(true);
                    thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                    return thread;
                }
            }
    );
    private static final AtomicInteger GOLEM_ASYNC_PLANNER_QUEUED = new AtomicInteger(0);
    private static final Map<String, AsyncPatrolPlan> GOLEM_ASYNC_PATROL_RESULTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> GOLEM_ASYNC_PATROL_PENDING = new ConcurrentHashMap<>();
    private static final Map<String, Long> GOLEM_ASYNC_PATROL_LAST_REQUEST = new ConcurrentHashMap<>();
    private static final Map<String, PrecomputedTerritoryPlan> GOLEM_PRECOMPUTED_TERRITORY_PLANS = new ConcurrentHashMap<>();
    private static long golemPrecomputeTerritoryRevisionSeen = -1L;
    private static int golemOfflinePrecomputeCursor = 0;
    private static int golemOfflineTrainingTickCounter = 0;
    private static final int GOLEM_OFFLINE_PRECOMPUTE_INTERVAL_TICKS = 20 * 60;
    private static final int GOLEM_OFFLINE_PRECOMPUTE_BATCH_NORMAL = 24;
    private static final int GOLEM_OFFLINE_PRECOMPUTE_BATCH_MASSIVE = 12;
    private static final int GOLEM_OFFLINE_PRECOMPUTE_ROUTE_POINTS = 24;

    private static Method SERVER_LEVEL_GET_ENTITY_UUID_METHOD;

    // Runtime-only AI caches. These avoid rebuilding entity snapshots, golem rosters,
    // connected territory clusters and navigation probes dozens of times inside the same server tick.
    private static long GOLEM_AI_FRAME = 0L;
    private static int GOLEM_NATIVE_PATH_CHECKS_THIS_FRAME = 0;
    private static final int GOLEM_NATIVE_PATH_CHECK_BUDGET_PER_FRAME = 14;
    private static final Map<String, EntitySnapshot> ENTITY_SNAPSHOT_CACHE = new HashMap<>();
    private static final Map<String, GolemRosterCache> GOLEM_ROSTER_CACHE = new HashMap<>();
    private static final Map<String, ClusterCache> GOLEM_CLUSTER_CACHE = new HashMap<>();
    private static final Map<String, SurfaceSpotCache> GOLEM_SURFACE_CACHE = new HashMap<>();
    private static final Map<String, PathReachabilityCache> GOLEM_PATH_CACHE = new HashMap<>();
    private static final Map<String, LastGolemSeen> GOLEM_SEEN_CACHE = new HashMap<>();
    private static final Map<String, Long> GOLEM_LAST_STAT_SYNC_TICK = new HashMap<>();
    private static final Map<String, Long> GOLEM_POST_TELEPORT_TICK = new HashMap<>();

    private static final int MAX_COMBAT_ALERTS_PER_GUILD = 8;
    private static final long GOLEM_FORCED_TELEPORT_COOLDOWN_TICKS = 20L * 12L;
    private static final int GOLEM_REVIVE_TICKS = 20 * 5;
    private static final long PORTAL_PAIR_WINDOW_TICKS = 20L * 60L;
    private static final long COMBAT_CONTRIBUTION_TICKS = 20L * 180L;
    private static final int GOLEM_REVIVE_EMERALD_COST = 3;
    private static final int GOLEM_REVIVE_IRON_COST = 10;
    private static final String TAG_GUILD_BANNER_RECIPE_UNLOCKED = "homecraftguild.guild_banner_recipe_unlocked";
    private static final Identifier GUILD_LEVEL_HEALTH_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_level_health_bonus");
    private static final Identifier GUILD_LEVEL_ARMOR_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_level_armor_bonus");
    private static final Identifier GUILD_LEVEL_DAMAGE_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_level_damage_bonus");
    private static final Identifier GUILD_TERRITORY_HEALTH_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_territory_health_bonus");
    private static final Identifier GUILD_GOLEM_HEALTH_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_golem_health_bonus");
    private static final Identifier GUILD_GOLEM_ATTACK_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_golem_attack_bonus");
    private static final Identifier GUILD_GOLEM_SPEED_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_golem_speed_bonus");
    private static final Identifier GUILD_MEMBER_SPEED_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_member_speed_bonus");
    private static final Identifier GUILD_MEMBER_JUMP_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_member_jump_bonus");
    private static final Identifier GUILD_ELITE_GOLEM_SCALE_MODIFIER_ID = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "guild_elite_golem_scale_bonus");
    private static final int GOLEM_RESTORE_GRACE_TICKS = 15;
    private static final Map<String, Double> DEFAULT_ATTRIBUTE_BASE_CACHE = new HashMap<>();
    private static final String TAG_LAST_GUILD_TERRITORY = "homecraftguild.last_guild_territory";
    private static final String TAG_LAST_GUILD_TERRITORY_NAME = "homecraftguild.last_guild_territory_name";
    private static final String TAG_LAST_GUILD_TERRITORY_OWN = "homecraftguild.last_guild_territory_own";
    private static final String TAG_GUILD_NIGHT_VISION_APPLIED = "homecraftguild.guild_night_vision_applied";
    private static final String TAG_GUILD_WATER_BREATHING_APPLIED = "homecraftguild.guild_water_breathing_applied";
    private static final String TAG_GUILD_FIRE_RESISTANCE_APPLIED = "homecraftguild.guild_fire_resistance_applied";
    private static final String TAG_GUILD_BUFF_SIGNATURE = "homecraftguild.guild_buff_signature";
    private static final String TAG_GUILD_BUFF_LAST_APPLY_TICK = "homecraftguild.guild_buff_last_apply_tick";
    private static final String TAG_GUILD_XP_BONUS_REMAINDER = "homecraftguild.guild_xp_bonus_remainder";
    private static final String TAG_GUILD_TERRITORY_HEALTH_UNTIL_TICK = "homecraftguild.guild_territory_health_until_tick";
    private static final String GUILD_BUFF_PIPELINE_VERSION = "v12-guild-special-talents";
    private static final int GUILD_PASSIVE_XP_BONUS_PERCENT = 5;
    private static final int GUILD_POTION_DURATION_BONUS_PERCENT = 10;
    private static final int GUILD_TERRITORY_HEALTH_BONUS_PERCENT = 30;
    private static final long GUILD_TERRITORY_HEALTH_DURATION_TICKS = 20L * 60L * 30L;
    private static final long GUILD_TERRITORY_HEALTH_REFRESH_MARGIN_TICKS = 20L * 60L * 5L;
    private static final String TAG_GUILD_TELEPORT_LAST_TICK = "homecraftguild.guild_teleport_last_tick";
    private static final long GUILD_TELEPORT_COOLDOWN_TICKS = 20L * 60L * 10L;
    private static int guildNpcEnsureTickCounter = 0;
    private static long guildNpcLastSpawnMs = 0L;
    private static long guildNpcLastEnsuredRevision = -1L;
    private static long guildNpcLastPeriodicEnsureMs = 0L;
    private static final Map<String, UUID> NPC_ENTITY_UUID_INDEX = new ConcurrentHashMap<>();
    private static final Set<String> NPC_DUPLICATE_LOG_ONCE = ConcurrentHashMap.newKeySet();
    private static Path npcEntityIndexFile;
    private static final Map<String, List<GolemSafeSpawn>> GUILD_GOLEM_SAFE_SPAWN_CACHE = new ConcurrentHashMap<>();
    private static long guildGolemSafeSpawnRevisionSeen = -1L;
    private static int guildGolemSafeSpawnTickCounter = 0;
    private static int pendingPurchasedGolemSpawnTickCounter = 0;

    private GuildServerEvents() {}

    public static MinecraftServer server() { return server; }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        HomeCraftGuildConfig.load(server);
        HomeCraftAddonIntegrations.logSummaryOnce(server);
        GuildStore.load(server);
        loadNpcEntityIndex(server);
        rebuildPrecomputedTerritoryPlans(false, 512);
        int removedSpawnClaims = GuildStore.removeSpawnConflictingTerritories(true);
        if (removedSpawnClaims > 0) {
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: startup cleanup removed {} claim(s) inside spawn", removedSpawnClaims);
        }
        cleanupInactiveGuildTotems(server, 0L, true, "startup");
        rebuildGuildGolemSafeSpawnCache("startup", 4096);
        ensureGuildNpcEntities(server, "startup");
        processPendingPurchasedGolemSpawns(0L, true);
        GuildSiteSync.pushTerritoriesAsync();
        HomeCraftGuildMod.LOGGER.info("Home Craft Guilds server ready: npc='{}' maxMembers={} personal={}x{} limit={} guild={}x{} limit={} maxGolems={}",
                HomeCraftGuildConfig.guildNpcName(), HomeCraftGuildConfig.maxGuildMembers(), HomeCraftGuildConfig.personalClaimSize(), HomeCraftGuildConfig.personalClaimSize(), HomeCraftGuildConfig.personalBannerLimit(),
                HomeCraftGuildConfig.guildClaimSize(), HomeCraftGuildConfig.guildClaimSize(), HomeCraftGuildConfig.guildBannerLimit(), HomeCraftGuildConfig.maxGuildGolems());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        GuildSiteSync.shutdown();
        shutdownGolemAsyncPlanner();
        GuildStore.flushGolemRouteLearningIfDirty(true);
        GuildStore.flushDeferredHotSave(true);
        saveNpcEntityIndex();
        clearTransientRuntimeStateOnStop();
        server = null;
    }

    private static void shutdownGolemAsyncPlanner() {
        GOLEM_ASYNC_PATROL_RESULTS.clear();
        GOLEM_ASYNC_PATROL_PENDING.clear();
        GOLEM_ASYNC_PATROL_LAST_REQUEST.clear();
        GOLEM_ASYNC_PLANNER_QUEUED.set(0);
        if (!GOLEM_ASYNC_PLANNER.isShutdown()) {
            GOLEM_ASYNC_PLANNER.shutdownNow();
        }
    }

    private static void clearTransientRuntimeStateOnStop() {
        GOLEM_STUCK.clear();
        GUILD_COMBAT_ALERTS.clear();
        GUILD_MEMBER_THREAT_ALERT_COOLDOWN.clear();
        PENDING_GOLEM_REVIVES.clear();
        GOLEM_PATROL_MEMORY.clear();
        GOLEM_LAST_FORCED_TELEPORT_TICK.clear();
        PLAYER_TRAVEL_MEMORY.clear();
        PENDING_PORTAL_ENTRIES.clear();
        ENTITY_COMBAT_CONTRIBUTORS.clear();
        GOLEM_ALERT_CHATTER_TICK.clear();
        GOLEM_NAV_MEMORY.clear();
        GOLEM_TARGET_CACHE.clear();
        GOLEM_PATROL_PLAN.clear();
        GOLEM_LEARNING_PENALTY_CACHE.clear();
        GOLEM_ROUTE_LEARNING_SAMPLE_TICK.clear();
        GOLEM_TERRAIN_PROFILE_CACHE.clear();
        GOLEM_ROUTE_RESERVATIONS.clear();
        GOLEM_THREAT_ASSIGNMENTS.clear();
        GOLEM_TASK_STATE.clear();
        GOLEM_TERRITORY_INTELLIGENCE_CACHE.clear();
        GOLEM_TERRITORY_ACTIVITY_CACHE.clear();
        GOLEM_ENTITY_LOOKUP_CACHE.clear();
        GOLEM_GUILD_HOT_UNTIL_TICK.clear();
        GOLEM_GUILD_HOT_CHECK_TICK.clear();
        GUILD_WALL_SURFACE_Y_CACHE.clear();
        GUILD_VISUAL_SYNC_STATE.clear();
        GUILD_BUFF_STATE_CACHE.clear();
        GUILD_POTION_EXTENSION_GUARD.clear();
        GOLEM_GUILD_AWARENESS_CACHE.clear();
        ENTITY_SNAPSHOT_CACHE.clear();
        GOLEM_ROSTER_CACHE.clear();
        GOLEM_CLUSTER_CACHE.clear();
        GOLEM_SURFACE_CACHE.clear();
        GOLEM_PATH_CACHE.clear();
        GOLEM_SEEN_CACHE.clear();
        GOLEM_LAST_STAT_SYNC_TICK.clear();
        GOLEM_POST_TELEPORT_TICK.clear();
        GOLEM_PRECOMPUTED_TERRITORY_PLANS.clear();
        GOLEM_PLAYER_HEAT_CACHE = null;
        SERVER_LEVEL_GET_ENTITY_UUID_METHOD = null;
        GUILD_GOLEM_SAFE_SPAWN_CACHE.clear();
        guildGolemSafeSpawnRevisionSeen = -1L;
        guildStoreTerritoryRevisionSeen = -1L;
        guildStoreGolemRevisionSeen = -1L;
        guildRuntimeRevisionSeen = -1L;
        golemGovernorLagLevel = 0;
        golemGovernorRescueUntilFrame = 0L;
        golemGovernorCriticalTripCount = 0L;
        golemGovernorQuarantineUntilFrame = 0L;
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("homecraftguild")
                .then(Commands.literal("npc")
                        .requires(GuildServerEvents::isStrictOpCommandSource)
                        .executes(ctx -> openNpcAdminUi(ctx.getSource()))
                        // Legacy aliases for the default system NPC.
                        .then(Commands.literal("create")
                                .executes(ctx -> runNpcCommand(ctx.getSource(), "guild_master", "create", null))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> runNpcCommand(ctx.getSource(), "guild_master", "create", StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("remove")
                                .executes(ctx -> runNpcCommand(ctx.getSource(), "guild_master", "remove", null)))
                        .then(Commands.literal("tp")
                                .executes(ctx -> runNpcCommand(ctx.getSource(), "guild_master", "tp", null))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> runNpcCommand(ctx.getSource(), "guild_master", "tp", StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("reload")
                                .executes(ctx -> runNpcReloadCommand(ctx.getSource())))
                        .then(Commands.literal("refresh")
                                .then(Commands.literal("all")
                                        .executes(ctx -> runNpcRefreshAllCommand(ctx.getSource())))
                                .then(Commands.literal("preset")
                                        .then(Commands.argument("preset", StringArgumentType.word())
                                                .executes(ctx -> runNpcRefreshPresetCommand(ctx.getSource(), StringArgumentType.getString(ctx, "preset"))))))
                        .then(Commands.literal("list")
                                .executes(ctx -> runNpcListCommand(ctx.getSource())))
                        .then(Commands.literal("debug")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(ctx -> runNpcDebugCommand(ctx.getSource(), StringArgumentType.getString(ctx, "key")))))
                        // New Easy-NPC-style form: /homecraftguild npc <key> create|tp|remove
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.literal("create")
                                        .executes(ctx -> runNpcCommand(ctx.getSource(), StringArgumentType.getString(ctx, "key"), "create", null))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> runNpcCommand(ctx.getSource(), StringArgumentType.getString(ctx, "key"), "create", StringArgumentType.getString(ctx, "player")))))
                                .then(Commands.literal("tp")
                                        .executes(ctx -> runNpcCommand(ctx.getSource(), StringArgumentType.getString(ctx, "key"), "tp", null))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> runNpcCommand(ctx.getSource(), StringArgumentType.getString(ctx, "key"), "tp", StringArgumentType.getString(ctx, "player")))))
                                .then(Commands.literal("remove")
                                        .executes(ctx -> runNpcCommand(ctx.getSource(), StringArgumentType.getString(ctx, "key"), "remove", null)))))
                .then(Commands.literal("totems")
                        .then(Commands.literal("cleanup")
                                .requires(source -> hasPermission(source, 4))
                                .executes(ctx -> {
                                    int removed = cleanupInactiveGuildTotems(ctx.getSource().getServer(), 0L, true, "command");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Home Craft Guilds: cleanup тотемів завершено. Видалено записів: " + removed + "."), true);
                                    return Math.max(1, removed);
                                })))
                .then(Commands.literal("accept")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (GuildStore.acceptInvite(player)) {
                                player.displayClientMessage(Component.literal("Home Craft Guilds: запрошення прийнято."), false);
                                GuildSiteSync.pushTerritoriesAsync();
                                sendRoster(player);
                                return 1;
                            }
                            player.displayClientMessage(Component.literal("Home Craft Guilds: немає активного запрошення або гільдія вже заповнена до ліміту " + HomeCraftGuildConfig.maxGuildMembers() + "."), false);
                            return 0;
                        }))
                .then(Commands.literal("rank")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("rank", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer actor = ctx.getSource().getPlayerOrException();
                                            String target = StringArgumentType.getString(ctx, "player");
                                            GuildRank rank = GuildRank.assignableFrom(StringArgumentType.getString(ctx, "rank"));
                                            boolean ok = GuildStore.setRank(actor, target, rank);
                                            actor.displayClientMessage(Component.literal(ok ? "Home Craft Guilds: роль змінено." : "Home Craft Guilds: роль не змінено. Ролі видає лише Гілдмайстер. Доступні: " + GuildRank.assignableLabels()), false);
                                            return ok ? 1 : 0;
                                        }))))) ;
        event.getDispatcher().register(Commands.literal("hcguild")
                .requires(source -> true)
                .redirect(event.getDispatcher().getRoot().getChild("homecraftguild")));
    }

    private static int runNpcReloadCommand(CommandSourceStack source) {
        if (!isStrictOpCommandSource(source)) {
            source.sendFailure(Component.literal("Home Craft Guilds: ця команда доступна лише OP."));
            return 0;
        }
        HomeCraftGuildConfig.load(source.getServer());
        ensureGuildNpcEntities(source.getServer(), "command_reload");
        forceAllNpcSync("command_reload");
        source.sendSuccess(() -> Component.literal("Home Craft Guilds: NPC-конфіг перезавантажено, поставлені NPC синхронізовано."), true);
        return 1;
    }

    private static int runNpcRefreshAllCommand(CommandSourceStack source) {
        if (!isStrictOpCommandSource(source)) {
            source.sendFailure(Component.literal("Home Craft Guilds: ця команда доступна лише OP."));
            return 0;
        }
        int updated = 0;
        for (String key : HomeCraftGuildConfig.npcKeys()) {
            if (HomeCraftGuildConfig.npcSystem(key) || HomeCraftGuildConfig.npcCustomTrades(key)) continue;
            String preset = HomeCraftGuildConfig.npcTraderPresetId(key);
            if (HomeCraftGuildConfig.applyNpcTradePreset(key, preset)) updated++;
            ensureGuildNpcEntity(source.getServer(), key, "command_refresh_all");
        }
        forceAllNpcSync("command_refresh_all");
        int result = updated;
        source.sendSuccess(() -> Component.literal("Home Craft Guilds: оновлено торгових NPC з їх пресетів: " + result + "."), true);
        return Math.max(1, updated);
    }

    private static int runNpcRefreshPresetCommand(CommandSourceStack source, String presetRaw) {
        if (!isStrictOpCommandSource(source)) {
            source.sendFailure(Component.literal("Home Craft Guilds: ця команда доступна лише OP."));
            return 0;
        }
        String preset = HomeCraftGuildConfig.normalizeNpcKey(presetRaw);
        int updated = 0;
        for (String key : HomeCraftGuildConfig.npcKeys()) {
            if (HomeCraftGuildConfig.npcSystem(key) || HomeCraftGuildConfig.npcCustomTrades(key)) continue;
            if (!preset.equals(HomeCraftGuildConfig.npcTraderPresetId(key))) continue;
            if (HomeCraftGuildConfig.applyNpcTradePreset(key, preset)) updated++;
            ensureGuildNpcEntity(source.getServer(), key, "command_refresh_preset");
        }
        forceAllNpcSync("command_refresh_preset");
        int result = updated;
        source.sendSuccess(() -> Component.literal("Home Craft Guilds: preset " + preset + " оновлено для стоячих NPC: " + result + "."), true);
        return Math.max(1, updated);
    }

    private static int runNpcListCommand(CommandSourceStack source) {
        if (!isStrictOpCommandSource(source)) {
            source.sendFailure(Component.literal("Home Craft Guilds: ця команда доступна лише OP."));
            return 0;
        }
        StringBuilder sb = new StringBuilder("Home Craft Guilds NPC: ");
        int count = 0;
        for (String key : HomeCraftGuildConfig.npcKeys()) {
            if (count++ > 0) sb.append("; ");
            sb.append(key)
                    .append(" [")
                    .append(HomeCraftGuildConfig.npcKind(key))
                    .append(HomeCraftGuildConfig.npcEnabled(key) ? ", placed" : ", off")
                    .append(", skin=")
                    .append(HomeCraftGuildConfig.npcSkinId(key))
                    .append("]");
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return Math.max(1, count);
    }

    private static int runNpcDebugCommand(CommandSourceStack source, String keyRaw) {
        if (!isStrictOpCommandSource(source)) {
            source.sendFailure(Component.literal("Home Craft Guilds: ця команда доступна лише OP."));
            return 0;
        }
        String key = HomeCraftGuildConfig.normalizeNpcKey(keyRaw);
        if (key.isBlank()) key = "guild_master";
        String msg = "NPC " + key + ": kind=" + HomeCraftGuildConfig.npcKind(key)
                + ", system=" + HomeCraftGuildConfig.npcSystem(key)
                + ", enabled=" + HomeCraftGuildConfig.npcEnabled(key)
                + ", skin=" + HomeCraftGuildConfig.npcSkinId(key)
                + ", name=" + HomeCraftGuildConfig.npcName(key)
                + ", pos=" + HomeCraftGuildConfig.npcDimension(key) + " "
                + String.format(Locale.ROOT, "%.2f %.2f %.2f", HomeCraftGuildConfig.npcX(key), HomeCraftGuildConfig.npcY(key), HomeCraftGuildConfig.npcZ(key))
                + ", trades=" + (HomeCraftGuildConfig.npcTrades(key).isBlank() ? 0 : HomeCraftGuildConfig.npcTrades(key).split("~").length);
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runNpcCommand(CommandSourceStack source, String key, String action, String playerName) {
        ServerPlayer actor = resolveCommandPlayer(source, playerName);
        if (actor == null) {
            source.sendFailure(Component.literal("Home Craft Guilds: NPC-команду треба виконувати в грі від OP. З консолі використовуй: /homecraftguild npc create <нік> або /homecraftguild npc tp <нік>."));
            return 0;
        }
        if (!isStrictOpPlayer(actor)) {
            source.sendFailure(Component.literal("Home Craft Guilds: ця команда доступна лише OP."));
            HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: denied NPC command action={} actor={} source={}", action, actor.getName().getString(), source);
            return 0;
        }
        final String normalizedNpcKey = HomeCraftGuildConfig.normalizeNpcKey(key);
        final String npcKey = normalizedNpcKey.isBlank() ? "guild_master" : normalizedNpcKey;
        final String npcAction = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("remove".equals(npcAction)) {
            removeGuildNpc(resolveServer(actor), npcKey);
            HomeCraftGuildConfig.clearNpcPosition(npcKey);
            forceAllNpcSync("command_remove");
            final String message = "Home Craft Guilds: NPC '" + npcKey + "' видалено.";
            source.sendSuccess(() -> Component.literal(message), true);
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: NPC key={} removed by {}", npcKey, actor.getName().getString());
            return 1;
        }
        if ("create".equals(npcAction) || "tp".equals(npcAction)) {
            saveGuildNpcPoint(actor, npcKey);
            boolean spawned = ensureGuildNpcEntity(resolveServer(actor), npcKey, npcAction);
            forceAllNpcSync("command_" + npcAction);
            final String verb = "tp".equals(npcAction) ? "перенесено" : "створено";
            final String message = "Home Craft Guilds: NPC '" + npcKey + "' " + verb + "." + (spawned ? "" : " Перевір dimension/chunk.");
            source.sendSuccess(() -> Component.literal(message), true);
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: tracked NPC key={} action={} by {} at {} {} {} yaw={}", npcKey, npcAction, actor.getName().getString(), actor.getX(), actor.getY(), actor.getZ(), actor.getYRot());
            return 1;
        }
        source.sendFailure(Component.literal("Home Craft Guilds: unknown NPC action."));
        return 0;
    }

    private static int openNpcAdminUi(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!isStrictOpPlayer(player)) {
                source.sendFailure(Component.literal("Home Craft Guilds: NPC UI доступний лише OP."));
                return 0;
            }
            sendNpcAdminUi(player);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Home Craft Guilds: /homecraftguild npc UI треба відкривати в грі від OP."));
            return 0;
        }
    }

    private static ServerPlayer resolveCommandPlayer(CommandSourceStack source, String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            ServerPlayer byName = findOnlinePlayer(playerName);
            if (byName != null) return byName;
            source.sendFailure(Component.literal("Home Craft Guilds: гравця '" + playerName + "' не знайдено онлайн."));
            return null;
        }
        try {
            return source.getPlayerOrException();
        } catch (Exception ignored) {
            return null;
        }
    }


    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        server = event.getServer();
        long tickStartNs = System.nanoTime();
        beginGolemAiFrame();
        if (++guildNpcEnsureTickCounter >= 1200) {
            guildNpcEnsureTickCounter = 0;
            long npcRevision = HomeCraftGuildConfig.guildNpcRegistryRevision();
            long nowMs = System.currentTimeMillis();
            // Do not spam world-wide NPC scans every minute. NPC entities are real synced entities;
            // the server only verifies them immediately after config/data changes, plus a slow safety pass.
            boolean revisionChanged = npcRevision != guildNpcLastEnsuredRevision;
            boolean slowSafetyPass = nowMs - guildNpcLastPeriodicEnsureMs >= 300_000L;
            if (revisionChanged || slowSafetyPass) {
                ensureGuildNpcEntities(server, revisionChanged ? "revision" : "periodic");
                guildNpcLastEnsuredRevision = npcRevision;
                guildNpcLastPeriodicEnsureMs = nowMs;
            }
        }

        processPendingNpcLoginSyncs(tickStartNs);
        if (++guildGolemSafeSpawnTickCounter >= 600 && !serverTickBudgetExceeded(tickStartNs)) {
            guildGolemSafeSpawnTickCounter = 0;
            if (guildGolemSafeSpawnRevisionSeen != GuildStore.territoryTopologyRevision()) rebuildGuildGolemSafeSpawnCache("territory-revision", 2048);
        }
        if (++pendingPurchasedGolemSpawnTickCounter >= 80 && !serverTickBudgetExceeded(tickStartNs)) {
            pendingPurchasedGolemSpawnTickCounter = 0;
            processPendingPurchasedGolemSpawns(tickStartNs, false);
        }

        if (serverHasNoOnlinePlayers()) {
            runNoPlayerGuildIdlePipeline(tickStartNs);
            return;
        }

        if (isGolemGovernorHardRescue()) {
            runGuildGovernorRescueTick(tickStartNs);
            return;
        }

        // Recipes are static datapack resources now. The guild banner recipe is unlocked by the
        // bundled advancement (minecraft:tick), not by a recurring server command scan.

        if (!PENDING_GOLEM_REVIVES.isEmpty() && ++golemReviveTickCounter >= dynamicGolemReviveIntervalTicks() && !serverTickBudgetExceeded(tickStartNs)) {
            golemReviveTickCounter = 0;
            processPendingGolemRevives(dynamicGolemReviveIntervalTicks());
        }
        if (++portalTravelTickCounter >= dynamicPortalTravelIntervalTicks() && !serverTickBudgetExceeded(tickStartNs)) {
            portalTravelTickCounter = 0;
            trackGuildPortalTravel();
        }
        if (++combatCleanupTickCounter >= dynamicCombatCleanupIntervalTicks() && !serverTickBudgetExceeded(tickStartNs)) {
            combatCleanupTickCounter = 0;
            cleanupCombatContributors();
        }

        if (++golemRouteLearningFlushTickCounter >= dynamicHotDataFlushIntervalTicks()) {
            golemRouteLearningFlushTickCounter = 0;
            GuildStore.flushGolemRouteLearningIfDirty(false);
            GuildStore.flushDeferredHotSave(false);
        }

        long now = System.currentTimeMillis();
        if (now - lastSyncMs >= HomeCraftGuildConfig.territorySyncIntervalSeconds() * 1000L && mayRunSecondaryTickWork(tickStartNs, TickWorkPriority.BACKGROUND_SYNC)) {
            lastSyncMs = now;
            maybeCleanupInactiveGuildTotems(server, tickStartNs);
            if (!isGolemGovernorThrottled() && mayRunSecondaryTickWork(tickStartNs, TickWorkPriority.BACKGROUND_SYNC)) GuildSiteSync.pushTerritoriesAsync();
        }

        // Server-scale safety pass: never scan every golem every tick on large servers.
        // Work is sliced across ticks and hard-limited by a per-tick time budget.
        if (++golemSafetyTickCounter >= dynamicGolemSafetyIntervalTicks() && !serverTickBudgetExceeded(tickStartNs)) {
            golemSafetyTickCounter = 0;
            enforceGuildGolems(false, tickStartNs);
        }

        if (++golemTickCounter >= dynamicGolemAiIntervalTicks() && !serverTickBudgetExceeded(tickStartNs)) {
            golemTickCounter = 0;
            if (++golemThreatAlarmTickCounter >= dynamicThreatAlarmIntervalFrames()) {
                golemThreatAlarmTickCounter = 0;
                if (!serverTickBudgetExceeded(tickStartNs)) scanGuildMemberDefenseAlarms(tickStartNs);
            }
            if (!serverTickBudgetExceeded(tickStartNs)) enforceGuildGolems(true, tickStartNs);
            if (++cleanupUnboundTickCounter >= dynamicCleanupUnboundIntervalFrames() && !serverTickBudgetExceeded(tickStartNs)) {
                cleanupUnboundTickCounter = 0;
                cleanupUnboundGuildGolems(tickStartNs);
            }
        }

        if (++guildBuffTickCounter >= dynamicGuildBuffIntervalTicks()) {
            guildBuffTickCounter = 0;
            // Guild buffs must remain reliable even when AI is throttled. The updater itself is sliced.
            updateGuildPassiveBuffs(tickStartNs);
        }
        if (++guildVisualSyncTickCounter >= dynamicGuildVisualSyncIntervalTicks()) {
            guildVisualSyncTickCounter = 0;
            syncGuildClientVisuals(tickStartNs);
        }
    }

    private static void beginGolemAiFrame() {
        GOLEM_AI_FRAME++;
        GOLEM_NATIVE_PATH_CHECKS_THIS_FRAME = 0;
        refreshStaticGuildStoreRevisions();
        refreshGolemScaleSnapshot();
        updateAdaptiveGolemGovernor();
        if ((GOLEM_AI_FRAME & (isGolemGovernorThrottled() ? 255L : 127L)) == 0L) cleanupGolemAiRuntimeCaches();
    }

    private static void refreshStaticGuildStoreRevisions() {
        long territoryRevision = GuildStore.territoryTopologyRevision();
        if (territoryRevision != guildStoreTerritoryRevisionSeen) {
            guildStoreTerritoryRevisionSeen = territoryRevision;
            dropStaleAsyncPatrolJobs();
            GOLEM_CLUSTER_CACHE.clear();
            GOLEM_TERRITORY_INTELLIGENCE_CACHE.clear();
            GOLEM_TERRITORY_ACTIVITY_CACHE.clear();
            GOLEM_GUILD_AWARENESS_CACHE.clear();
            GOLEM_TERRAIN_PROFILE_CACHE.clear();
            GUILD_WALL_SURFACE_Y_CACHE.clear();
            GOLEM_PRECOMPUTED_TERRITORY_PLANS.clear();
            golemPrecomputeTerritoryRevisionSeen = -1L;
        }
        long golemRevision = GuildStore.golemTopologyRevision();
        if (golemRevision != guildStoreGolemRevisionSeen) {
            guildStoreGolemRevisionSeen = golemRevision;
            dropStaleAsyncPatrolJobs();
            GOLEM_ROSTER_CACHE.clear();
            GOLEM_GUILD_HOT_CHECK_TICK.clear();
        }
        long guildRevision = GuildStore.guildRuntimeRevision();
        if (guildRevision != guildRuntimeRevisionSeen) {
            guildRuntimeRevisionSeen = guildRevision;
            GUILD_BUFF_STATE_CACHE.clear();
            GUILD_VISUAL_SYNC_STATE.clear();
            GOLEM_GUILD_AWARENESS_CACHE.clear();
        }
    }



    private static void dropStaleAsyncPatrolJobs() {
        GOLEM_ASYNC_PATROL_RESULTS.clear();
        GOLEM_ASYNC_PATROL_PENDING.clear();
        GOLEM_ASYNC_PATROL_LAST_REQUEST.clear();
        GOLEM_ASYNC_PLANNER.getQueue().clear();
        GOLEM_ASYNC_PLANNER_QUEUED.set(Math.max(0, GOLEM_ASYNC_PLANNER.getActiveCount()));
    }

    private static boolean serverHasNoOnlinePlayers() {
        return server == null || server.getPlayerList() == null || server.getPlayerList().getPlayers().isEmpty();
    }

    private static void runNoPlayerGuildIdlePipeline(long tickStartNs) {
        // No online players: do not run live AI, visual sync, buff scans or frequent offline training.
        // Keep only rare deferred-save flushes and a tiny precompute slice after topology changes.
        if (++golemOfflineTrainingTickCounter >= GOLEM_OFFLINE_PRECOMPUTE_INTERVAL_TICKS && !serverTickBudgetExceeded(tickStartNs)) {
            golemOfflineTrainingTickCounter = 0;
            if (golemPrecomputeTerritoryRevisionSeen != GuildStore.territoryTopologyRevision()) {
                int batch = isMassiveGolemServerMode() ? GOLEM_OFFLINE_PRECOMPUTE_BATCH_MASSIVE : GOLEM_OFFLINE_PRECOMPUTE_BATCH_NORMAL;
                rebuildPrecomputedTerritoryPlans(true, batch);
            }
        }
        if (++golemRouteLearningFlushTickCounter >= 20 * 60) {
            golemRouteLearningFlushTickCounter = 0;
            GuildStore.flushGolemRouteLearningIfDirty(false);
            GuildStore.flushDeferredHotSave(false);
        }
        if ((GOLEM_AI_FRAME & 1023L) == 0L) cleanupGolemAiRuntimeCaches();
    }

    private static void rebuildPrecomputedTerritoryPlans(boolean incremental, int maxTerritories) {
        List<GuildStore.TerritoryStaticInfo> infos = GuildStore.territoryStaticInfosSnapshot();
        long revision = GuildStore.territoryTopologyRevision();
        if (!incremental && revision == golemPrecomputeTerritoryRevisionSeen && !GOLEM_PRECOMPUTED_TERRITORY_PLANS.isEmpty()) return;
        if (!incremental) {
            GOLEM_PRECOMPUTED_TERRITORY_PLANS.clear();
            golemOfflinePrecomputeCursor = 0;
        }
        if (infos == null || infos.isEmpty()) {
            golemPrecomputeTerritoryRevisionSeen = revision;
            return;
        }
        int processed = 0;
        int start = Math.floorMod(golemOfflinePrecomputeCursor, infos.size());
        for (int i = 0; i < infos.size() && processed < Math.max(1, maxTerritories); i++) {
            GuildStore.TerritoryStaticInfo info = infos.get(Math.floorMod(start + i, infos.size()));
            if (info == null || info.id == null || info.guildId == null || !"GUILD".equals(info.type)) continue;
            PrecomputedTerritoryPlan plan = buildPrecomputedTerritoryPlan(info, revision);
            if (plan != null) {
                GOLEM_PRECOMPUTED_TERRITORY_PLANS.put(info.id, plan);
                if (incremental) seedRouteLearningFromPrecomputedPlan(plan);
                processed++;
            }
        }
        golemOfflinePrecomputeCursor = Math.floorMod(start + Math.max(1, processed), infos.size());
        if (!incremental || GOLEM_PRECOMPUTED_TERRITORY_PLANS.size() >= infos.size()) golemPrecomputeTerritoryRevisionSeen = revision;
    }

    private static PrecomputedTerritoryPlan buildPrecomputedTerritoryPlan(GuildStore.TerritoryStaticInfo info, long revision) {
        if (info == null || info.id == null) return null;
        int width = Math.max(1, info.maxX - info.minX + 1);
        int depth = Math.max(1, info.maxZ - info.minZ + 1);
        int points = Math.max(8, Math.min(GOLEM_OFFLINE_PRECOMPUTE_ROUTE_POINTS, Math.max(8, (width * depth) / 192)));
        List<PrecomputedPoint> day = new ArrayList<>();
        List<PrecomputedPoint> night = new ArrayList<>();
        int columns = Math.max(2, (int)Math.ceil(Math.sqrt(points * (width / Math.max(1.0D, (double)depth)))));
        int rows = Math.max(2, (int)Math.ceil(points / (double)columns));
        int slots = Math.max(points, columns * rows);
        int hash = Math.abs(String.valueOf(info.id).hashCode());
        for (int slot = 0; slot < slots && (day.size() < points || night.size() < points); slot++) {
            int cx = slot % columns;
            int cz = slot / columns;
            if ((cz & 1) == 1) cx = columns - 1 - cx;
            int x = interpolate(info.minX + 2, info.maxX - 2, (cx + 0.5D) / columns);
            int z = interpolate(info.minZ + 2, info.maxZ - 2, (cz + 0.5D) / rows);
            x += Math.floorMod(hash + slot * 13, 7) - 3;
            z += Math.floorMod(hash / 5 + slot * 17, 7) - 3;
            x = Math.max(info.minX + 1, Math.min(info.maxX - 1, x));
            z = Math.max(info.minZ + 1, Math.min(info.maxZ - 1, z));
            int edgeDist = Math.min(Math.min(x - info.minX, info.maxX - x), Math.min(z - info.minZ, info.maxZ - z));
            if (day.size() < points && edgeDist >= Math.max(3, Math.min(width, depth) / 12)) {
                day.add(new PrecomputedPoint(x, z, info.y, 80 + Math.min(40, edgeDist * 3)));
            }
            if (night.size() < points) {
                boolean goodEdge = false;
                if (info.northOpen && z - info.minZ <= Math.max(3, depth / 5)) goodEdge = true;
                if (info.southOpen && info.maxZ - z <= Math.max(3, depth / 5)) goodEdge = true;
                if (info.westOpen && x - info.minX <= Math.max(3, width / 5)) goodEdge = true;
                if (info.eastOpen && info.maxX - x <= Math.max(3, width / 5)) goodEdge = true;
                if (goodEdge || info.openSides <= 1) night.add(new PrecomputedPoint(x, z, info.y, 90 + Math.max(0, 24 - edgeDist)));
            }
        }
        if (day.isEmpty()) day.add(new PrecomputedPoint(info.x, info.z, info.y, 50));
        if (night.isEmpty()) night.addAll(day);
        return new PrecomputedTerritoryPlan(info.id, info.guildId, info.dimension, revision, day, night);
    }

    private static void seedRouteLearningFromPrecomputedPlan(PrecomputedTerritoryPlan plan) {
        if (plan == null || plan.guildId == null || plan.dimension == null) return;
        int limit = 0;
        for (PrecomputedPoint point : plan.dayPoints) {
            if (point == null || ++limit > 4) break;
            GuildStore.recordGolemRouteLearning(plan.guildId, plan.dimension, point.x, point.y, point.z, true, "offline-pretrained-day");
        }
        limit = 0;
        for (PrecomputedPoint point : plan.nightPoints) {
            if (point == null || ++limit > 4) break;
            GuildStore.recordGolemRouteLearning(plan.guildId, plan.dimension, point.x, point.y, point.z, true, "offline-pretrained-night");
        }
    }

    private static GolemSpawn precomputedPatrolPoint(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster, GolemPatrolContext context, int attempt) {
        if (record == null || level == null || cluster == null || cluster.isEmpty()) return null;
        if (golemPrecomputeTerritoryRevisionSeen != GuildStore.territoryTopologyRevision()) rebuildPrecomputedTerritoryPlans(true, 16);
        boolean night = context == null ? isNightTime(level) : context.night;
        int index = Math.floorMod(stableGuildGolemIndex(record) + attempt, cluster.size());
        for (int t = 0; t < cluster.size(); t++) {
            GuildStore.Territory territory = cluster.get(Math.floorMod(index + t, cluster.size()));
            if (territory == null || territory.id == null) continue;
            PrecomputedTerritoryPlan plan = GOLEM_PRECOMPUTED_TERRITORY_PLANS.get(territory.id);
            if (plan == null) continue;
            List<PrecomputedPoint> points = night ? plan.nightPoints : plan.dayPoints;
            if (points == null || points.isEmpty()) continue;
            int pointIndex = Math.floorMod(stableGuildGolemIndex(record) * 3 + attempt * 5 + (int)(level.getGameTime() / (night ? 700L : 1600L)), points.size());
            for (int i = 0; i < Math.min(4, points.size()); i++) {
                PrecomputedPoint pre = points.get(Math.floorMod(pointIndex + i, points.size()));
                if (pre == null) continue;
                GolemSpawn spawn = safeClusterPatrolPointNearY(level, cluster, pre.x, pre.z, pre.y);
                spawn = acceptWaterPolicy(spawn, context);
                if (spawn != null) return spawn;
            }
        }
        return null;
    }


    private enum TickWorkPriority {
        CRITICAL_GAMEPLAY,
        PLAYER_FEEDBACK,
        CLIENT_SYNC,
        BACKGROUND_SYNC
    }

    private static boolean mayRunSecondaryTickWork(long tickStartNs, TickWorkPriority priority) {
        if (priority == TickWorkPriority.CRITICAL_GAMEPLAY) return !serverTickBudgetExceeded(tickStartNs);
        if (priority == TickWorkPriority.CLIENT_SYNC) return true;
        if (isGolemGovernorHardRescue()) return false;
        if (serverTickBudgetExceeded(tickStartNs)) return false;
        if (priority == TickWorkPriority.PLAYER_FEEDBACK) return true;
        if (isGolemGovernorThrottled()) {
            // Throttled mode defers background/API work only. Client visual topology sync is
            // handled before this branch and is not part of golem AI throttling.
            return false;
        }
        // Background work should never start on the same frame as AI rescue/large path planning.
        return GOLEM_ASYNC_PLANNER_QUEUED.get() < Math.max(1, GOLEM_ASYNC_PLANNER_THREADS * 3)
                && (GOLEM_AI_FRAME & (isMassiveGolemServerMode() ? 7L : 3L)) == 0L;
    }

    private static void updateAdaptiveGolemGovernor() {
        long nowNs = System.nanoTime();
        if (golemGovernorLastPostTickNs > 0L) {
            double ms = Math.max(0.0D, (nowNs - golemGovernorLastPostTickNs) / 1_000_000.0D);
            golemGovernorTickEwmaMs = golemGovernorTickEwmaMs <= 0.0D ? ms : (golemGovernorTickEwmaMs * 0.92D + ms * 0.08D);
            int spikeLevel = ms >= GOLEM_GOVERNOR_LAG_CRITICAL_MS ? 3 : (ms >= GOLEM_GOVERNOR_LAG_STRONG_MS ? 2 : (ms >= GOLEM_GOVERNOR_LAG_WARN_MS ? 1 : 0));
            int smoothLevel = golemGovernorTickEwmaMs >= GOLEM_GOVERNOR_EWMA_CRITICAL_MS ? 3 : (golemGovernorTickEwmaMs >= GOLEM_GOVERNOR_EWMA_STRONG_MS ? 2 : (golemGovernorTickEwmaMs >= GOLEM_GOVERNOR_EWMA_WARN_MS ? 1 : 0));
            int level = Math.max(spikeLevel, smoothLevel);
            if (level > 0) {
                golemGovernorLagLevel = Math.max(golemGovernorLagLevel, level);
                long rescue = level >= 3 ? GOLEM_GOVERNOR_RESCUE_FRAMES_CRITICAL : (level == 2 ? GOLEM_GOVERNOR_RESCUE_FRAMES_STRONG : GOLEM_GOVERNOR_RESCUE_FRAMES_LIGHT);
                golemGovernorRescueUntilFrame = Math.max(golemGovernorRescueUntilFrame, GOLEM_AI_FRAME + rescue);
                if (level >= 3) {
                    golemGovernorCriticalTripCount++;
                    long quarantineFrames = ms >= 9000.0D ? 20L * 180L : (ms >= 2500.0D ? 20L * 120L : 20L * 60L);
                    // Hard circuit breaker: logs showed repeated 6-10s spikes. Waiting for several
                    // critical trips means the server is already falling behind. A single multi-second
                    // spike now pauses expensive guild AI immediately; golems keep vanilla idle/combat.
                    if (ms >= 900.0D || golemGovernorCriticalTripCount >= 2L) {
                        golemGovernorQuarantineUntilFrame = Math.max(golemGovernorQuarantineUntilFrame, GOLEM_AI_FRAME + quarantineFrames);
                        golemGovernorCriticalTripCount = 0L;
                    }
                }
                boolean shouldLog = HomeCraftGuildConfig.debug()
                        && (GOLEM_AI_FRAME - golemGovernorLastLogFrame > 20L * 60L || golemGovernorLastLoggedLevel != golemGovernorLagLevel);
                if (shouldLog) {
                    golemGovernorLastLogFrame = GOLEM_AI_FRAME;
                    golemGovernorLastLoggedLevel = golemGovernorLagLevel;
                    HomeCraftGuildMod.LOGGER.info("Home Craft Guilds governor: lagLevel={} lastTick={}ms ewma={}ms rescueFramesLeft={} quarantineFramesLeft={}",
                            golemGovernorLagLevel, Math.round(ms), Math.round(golemGovernorTickEwmaMs), Math.max(0L, golemGovernorRescueUntilFrame - GOLEM_AI_FRAME), Math.max(0L, golemGovernorQuarantineUntilFrame - GOLEM_AI_FRAME));
                }
            } else if (GOLEM_AI_FRAME > golemGovernorRescueUntilFrame && golemGovernorLagLevel > 0) {
                golemGovernorLagLevel--;
                if (golemGovernorLagLevel <= 0) golemGovernorCriticalTripCount = 0L;
            }
        }
        golemGovernorLastPostTickNs = nowNs;
    }

    private static boolean isGolemGovernorThrottled() {
        return golemGovernorLagLevel > 0 || GOLEM_AI_FRAME < golemGovernorRescueUntilFrame;
    }

    private static boolean isGolemGovernorHardRescue() {
        return golemGovernorLagLevel >= 3 || (golemGovernorLagLevel >= 2 && GOLEM_AI_FRAME < golemGovernorRescueUntilFrame) || isGolemGovernorQuarantined();
    }

    private static boolean isGolemGovernorQuarantined() {
        return GOLEM_AI_FRAME < golemGovernorQuarantineUntilFrame;
    }

    private static void runGuildGovernorRescueTick(long tickStartNs) {
        // Critical TPS rescue: only tiny safety slices and deferred saves are allowed.
        // Visual systems use precomputed/static topology and a one-object budget, so players
        // still see whose zone/crystal it is without scanning all territories again.
        if (++golemRouteLearningFlushTickCounter >= dynamicHotDataFlushIntervalTicks()) {
            golemRouteLearningFlushTickCounter = 0;
            GuildStore.flushGolemRouteLearningIfDirty(false);
            GuildStore.flushDeferredHotSave(false);
        }
        // Buffs are player-local and very cheap. Do not let hard rescue/quarantine leave players
        // without guild effects for minutes; repair one player per rescue tick without any scans.
        updateOneGuildPassiveBuffForRescue();
        if (isGolemGovernorQuarantined()) {
            // During quarantine do not touch pathfinding, full entity scans, visual rebuilds or totem repair.
            // Vanilla entity idle keeps golems visually alive; the mod wakes back up after the cooldown.
            return;
        }
        if (++golemSafetyTickCounter >= dynamicGolemSafetyIntervalTicks() && !serverTickBudgetExceeded(tickStartNs)) {
            golemSafetyTickCounter = 0;
            enforceGuildGolems(false, tickStartNs);
        }
        // Hard rescue never runs full guild golem AI. It only performs tiny safety slices;
        // full AI resumes after TPS recovery. This prevents the recovery system from causing
        // the next lag spike by doing patrol/target scans while the server is already behind.
    }

    private static void refreshGolemScaleSnapshot() {
        if (golemScaleFrame == GOLEM_AI_FRAME) return;
        if (golemScaleLastRefreshFrame > 0L && GOLEM_AI_FRAME - golemScaleLastRefreshFrame < 20L) {
            golemScaleFrame = GOLEM_AI_FRAME;
            return;
        }
        golemScaleFrame = GOLEM_AI_FRAME;
        golemScaleLastRefreshFrame = GOLEM_AI_FRAME;
        golemScaleGolemCount = Math.max(0, GuildStore.golemCount());
        golemScaleTerritoryCount = Math.max(0, GuildStore.territoryCount());
        if (golemScaleGolemCount >= GOLEM_ULTRA_MODE_GOLEM_THRESHOLD || golemScaleTerritoryCount >= GOLEM_ULTRA_MODE_TERRITORY_THRESHOLD) golemScaleMode = GOLEM_SCALE_ULTRA;
        else if (golemScaleGolemCount >= GOLEM_EXTREME_MODE_GOLEM_THRESHOLD || golemScaleTerritoryCount >= GOLEM_EXTREME_MODE_TERRITORY_THRESHOLD) golemScaleMode = GOLEM_SCALE_EXTREME;
        else if (golemScaleGolemCount >= GOLEM_MASSIVE_MODE_GOLEM_THRESHOLD || golemScaleTerritoryCount >= GOLEM_MASSIVE_MODE_TERRITORY_THRESHOLD) golemScaleMode = GOLEM_SCALE_MASSIVE;
        else golemScaleMode = GOLEM_SCALE_NORMAL;
    }

    private static boolean isMassiveGolemServerMode() {
        return golemScaleMode >= GOLEM_SCALE_MASSIVE;
    }

    private static boolean isExtremeGolemServerMode() {
        return golemScaleMode >= GOLEM_SCALE_EXTREME;
    }

    private static boolean isUltraGolemServerMode() {
        return golemScaleMode >= GOLEM_SCALE_ULTRA;
    }

    private static boolean serverTickBudgetExceeded(long tickStartNs) {
        if (tickStartNs <= 0L) return false;
        long budget = switch (golemScaleMode) {
            case GOLEM_SCALE_ULTRA -> GOLEM_SERVER_TICK_BUDGET_NS_ULTRA;
            case GOLEM_SCALE_EXTREME -> GOLEM_SERVER_TICK_BUDGET_NS_EXTREME;
            case GOLEM_SCALE_MASSIVE -> GOLEM_SERVER_TICK_BUDGET_NS_MASSIVE;
            default -> GOLEM_SERVER_TICK_BUDGET_NS_NORMAL;
        };
        if (isGolemGovernorHardRescue()) budget = Math.min(budget, 180_000L);
        else if (isGolemGovernorThrottled()) budget = Math.min(budget, 360_000L);
        return System.nanoTime() - tickStartNs >= budget;
    }

    private static int dynamicGolemAiIntervalTicks() {
        int configured = Math.max(5, HomeCraftGuildConfig.golemAiIntervalTicks());
        if (isGolemGovernorHardRescue()) return Math.max(configured, 180);
        if (isGolemGovernorThrottled()) return Math.max(configured, 110);
        if (isUltraGolemServerMode()) return Math.max(configured, 80);
        if (isExtremeGolemServerMode()) return Math.max(configured, 50);
        if (isMassiveGolemServerMode()) return Math.max(configured, 30);
        return configured;
    }

    private static int dynamicGolemSafetyIntervalTicks() {
        if (isGolemGovernorHardRescue()) return 600;
        if (isGolemGovernorThrottled()) return 120;
        if (isUltraGolemServerMode()) return 40;
        if (isExtremeGolemServerMode()) return 25;
        if (isMassiveGolemServerMode()) return 12;
        return GOLEM_SAFETY_PASS_INTERVAL_TICKS;
    }

    private static int dynamicThreatAlarmIntervalFrames() {
        int aiInterval = Math.max(1, dynamicGolemAiIntervalTicks());
        int base = Math.max(1, GOLEM_THREAT_ALARM_SCAN_INTERVAL_TICKS / aiInterval);
        if (isGolemGovernorHardRescue()) return Math.max(base, 12);
        if (isGolemGovernorThrottled()) return Math.max(base, 8);
        if (isUltraGolemServerMode()) return Math.max(base, 6);
        if (isExtremeGolemServerMode()) return Math.max(base, 4);
        if (isMassiveGolemServerMode()) return Math.max(base, 2);
        return base;
    }

    private static int dynamicCleanupUnboundIntervalFrames() {
        if (isGolemGovernorHardRescue()) return 48;
        if (isGolemGovernorThrottled()) return 32;
        if (isUltraGolemServerMode()) return 24;
        if (isExtremeGolemServerMode()) return 16;
        if (isMassiveGolemServerMode()) return 8;
        return 1;
    }

    private static int dynamicGuildBuffIntervalTicks() {
        // Most guild buffs are now infinite marker effects and are also repaired from join/respawn
        // and effect/item events. The tick pass is only a cheap safety net for territory HP expiry
        // and boundary messages, so it must be sliced and slower than normal gameplay ticks.
        if (isGolemGovernorHardRescue()) return 100;
        if (isGolemGovernorThrottled()) return 100;
        if (isUltraGolemServerMode()) return 160;
        if (isExtremeGolemServerMode()) return 120;
        if (isMassiveGolemServerMode()) return 80;
        return 40;
    }

    private static int dynamicGuildVisualSyncIntervalTicks() {
        // Guild border rendering is client-owned. The server only ships a small raw
        // territory snapshot so the client can decide which edge points are visible.
        // Do not throttle this cadence with the golem/AI governor: visual borders are
        // not server AI work and must not disappear just because golem logic is slowed.
        if (isUltraGolemServerMode()) return 60;
        if (isExtremeGolemServerMode()) return 45;
        if (isMassiveGolemServerMode()) return 35;
        return 20;
    }

    private static int dynamicGolemReviveIntervalTicks() {
        // Revive animations are not AI-critical. Process them in small scheduled slices instead
        // of touching the pending map every server tick.
        if (isGolemGovernorHardRescue()) return 20;
        if (isGolemGovernorThrottled()) return 10;
        return 5;
    }

    private static int dynamicPortalTravelIntervalTicks() {
        // Portal achievements do not need per-tick polling; vanilla dimension state is stable.
        if (isGolemGovernorHardRescue()) return 80;
        if (isGolemGovernorThrottled()) return 40;
        return 40;
    }

    private static int dynamicCombatCleanupIntervalTicks() {
        if (isGolemGovernorHardRescue()) return 20 * 60;
        if (isGolemGovernorThrottled()) return 20 * 30;
        return 20 * 10;
    }

    private static int dynamicHotDataFlushIntervalTicks() {
        if (isGolemGovernorHardRescue()) return 20 * 90;
        if (isGolemGovernorThrottled()) return 20 * 60;
        if (isUltraGolemServerMode()) return 20 * 30;
        if (isExtremeGolemServerMode()) return 20 * 20;
        if (isMassiveGolemServerMode()) return 20 * 15;
        return 200;
    }

    private static int golemProcessingBudget(int total, boolean runAi) {
        if (total <= 0) return 0;
        if (isGolemGovernorHardRescue()) return runAi ? 0 : Math.max(1, Math.min(2, total));
        if (isGolemGovernorThrottled()) return runAi ? Math.max(1, Math.min(4, total / 256)) : Math.max(1, Math.min(6, total / 128));
        if (total <= 48) return total;
        if (runAi) {
            if (isUltraGolemServerMode()) return Math.max(4, Math.min(28, total / 160));
            if (isExtremeGolemServerMode()) return Math.max(8, Math.min(48, total / 80));
            if (isMassiveGolemServerMode()) return Math.max(14, Math.min(72, total / 48));
            return Math.max(20, Math.min(112, total / 20));
        }
        if (isUltraGolemServerMode()) return Math.max(8, Math.min(48, total / 96));
        if (isExtremeGolemServerMode()) return Math.max(16, Math.min(96, total / 56));
        if (isMassiveGolemServerMode()) return Math.max(28, Math.min(144, total / 32));
        return Math.max(40, Math.min(220, total / 12));
    }

    private static int nativePathBudgetThisFrame() {
        if (isGolemGovernorHardRescue()) return 0;
        if (isGolemGovernorThrottled()) return 1;
        if (isUltraGolemServerMode()) return 2;
        if (isExtremeGolemServerMode()) return 4;
        if (isMassiveGolemServerMode()) return 8;
        return GOLEM_NATIVE_PATH_CHECK_BUDGET_PER_FRAME;
    }

    private static int dynamicPatrolPointAttempts(boolean night) {
        if (isGolemGovernorHardRescue()) return night ? 2 : 3;
        if (isGolemGovernorThrottled()) return night ? 4 : 5;
        if (isUltraGolemServerMode()) return night ? 6 : 8;
        if (isExtremeGolemServerMode()) return night ? 10 : 12;
        if (isMassiveGolemServerMode()) return night ? 16 : 20;
        return Math.min(GOLEM_PATROL_MAX_POINT_ATTEMPTS, 36);
    }

    private static int dynamicTerritoryAnalysisMaxSamples() {
        if (isGolemGovernorHardRescue()) return 4;
        if (isGolemGovernorThrottled()) return 8;
        if (isUltraGolemServerMode()) return 16;
        if (isExtremeGolemServerMode()) return 32;
        if (isMassiveGolemServerMode()) return 64;
        return GOLEM_TERRITORY_ANALYSIS_MAX_SAMPLES;
    }

    private static int dynamicTerritoryAnalysisCacheTicks() {
        if (isUltraGolemServerMode()) return 20 * 420;
        if (isExtremeGolemServerMode()) return 20 * 240;
        if (isMassiveGolemServerMode()) return 20 * 160;
        return GOLEM_TERRITORY_ANALYSIS_CACHE_TICKS;
    }

    private static int dynamicTerrainProfileCacheTicks() {
        if (isUltraGolemServerMode()) return 20 * 90;
        if (isExtremeGolemServerMode()) return 20 * 60;
        if (isMassiveGolemServerMode()) return 20 * 30;
        return GOLEM_TERRAIN_PROFILE_CACHE_TICKS;
    }

private static int dynamicRuntimeCacheMax() {
        if (isUltraGolemServerMode()) return 2048;
        if (isExtremeGolemServerMode()) return 4096;
        if (isMassiveGolemServerMode()) return 8192;
        return 16384;
    }

    private static <K, V> void trimRuntimeMap(Map<K, V> map, int max) {
        if (map == null || max <= 0 || map.size() <= max) return;
        int remove = map.size() - max;
        var iterator = map.keySet().iterator();
        while (remove-- > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static void cleanupGolemAiRuntimeCaches() {
        long frame = GOLEM_AI_FRAME;
        ENTITY_SNAPSHOT_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 80L);
        GOLEM_CLUSTER_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 4L);
        GOLEM_SURFACE_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 160L);
        GOLEM_PATH_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 120L);
        GOLEM_TARGET_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 32L);
        if (GOLEM_PLAYER_HEAT_CACHE != null && frame - GOLEM_PLAYER_HEAT_CACHE.frame > 2L) GOLEM_PLAYER_HEAT_CACHE = null;
        GOLEM_ROUTE_RESERVATIONS.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > GOLEM_SWARM_ROUTE_RESERVATION_TICKS);
        GOLEM_THREAT_ASSIGNMENTS.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > GOLEM_SWARM_ASSIGNMENT_TICKS);
        GOLEM_ROSTER_CACHE.clear();
        if ((frame & 255L) == 0L) {
            GOLEM_LAST_STAT_SYNC_TICK.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_SEEN_CACHE.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_STUCK.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_PATROL_MEMORY.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_LAST_FORCED_TELEPORT_TICK.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_NAV_MEMORY.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_TARGET_CACHE.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_POST_TELEPORT_TICK.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_PATROL_PLAN.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_TERRAIN_PROFILE_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || GOLEM_AI_FRAME - e.getValue().frame > 48L);
            GOLEM_ROUTE_LEARNING_SAMPLE_TICK.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue() > 2400L);
            GOLEM_ROUTE_RESERVATIONS.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_THREAT_ASSIGNMENTS.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_TASK_STATE.entrySet().removeIf(e -> e == null || e.getKey() == null || GuildStore.golem(e.getKey()) == null);
            GOLEM_TERRITORY_INTELLIGENCE_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 720L);
            GOLEM_TERRITORY_ACTIVITY_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 160L);
            GOLEM_GUILD_AWARENESS_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 12L);
            GOLEM_ENTITY_LOOKUP_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || e.getValue().entity == null || !e.getValue().entity.isAlive() || frame - e.getValue().frame > 400L);
            GUILD_WALL_SURFACE_Y_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 20L * 180L);
            GOLEM_GUILD_HOT_UNTIL_TICK.entrySet().removeIf(e -> e == null || e.getValue() == null || e.getValue() < currentServerTickFallback());
            GOLEM_GUILD_HOT_CHECK_TICK.entrySet().removeIf(e -> e == null || e.getValue() == null || currentServerTickFallback() - e.getValue() > 20L * 600L);
            GUILD_MEMBER_THREAT_ALERT_COOLDOWN.entrySet().removeIf(e -> e == null || e.getValue() == null || currentServerTickFallback() - e.getValue() > 20L * 120L);
            GOLEM_ASYNC_PATROL_RESULTS.entrySet().removeIf(e -> e == null || e.getKey() == null || e.getValue() == null || GuildStore.golem(e.getKey()) == null || frame - e.getValue().frame > GOLEM_ASYNC_PLANNER_RESULT_TTL_FRAMES);
            GOLEM_ASYNC_PATROL_PENDING.entrySet().removeIf(e -> e == null || e.getKey() == null || e.getValue() == null || GuildStore.golem(e.getKey()) == null || frame - e.getValue() > GOLEM_ASYNC_PLANNER_PENDING_TTL_FRAMES);
            GOLEM_ASYNC_PATROL_LAST_REQUEST.entrySet().removeIf(e -> e == null || e.getKey() == null || e.getValue() == null || GuildStore.golem(e.getKey()) == null || frame - e.getValue() > GOLEM_ASYNC_PLANNER_RESULT_TTL_FRAMES * 2L);
            if (GOLEM_ENTITY_LOOKUP_CACHE.size() > GOLEM_ENTITY_LOOKUP_CACHE_MAX) {
                GOLEM_ENTITY_LOOKUP_CACHE.clear();
            }
        }
        if ((frame & 63L) == 0L) {
            GOLEM_LEARNING_PENALTY_CACHE.entrySet().removeIf(e -> e == null || e.getValue() == null || frame - e.getValue().frame > 240L);
        }
        if ((frame & 127L) == 0L) {
            int max = dynamicRuntimeCacheMax();
            trimRuntimeMap(ENTITY_SNAPSHOT_CACHE, Math.max(16, max / 128));
            trimRuntimeMap(GOLEM_SURFACE_CACHE, max);
            trimRuntimeMap(GOLEM_PATH_CACHE, max);
            trimRuntimeMap(GOLEM_TERRAIN_PROFILE_CACHE, max);
            trimRuntimeMap(GOLEM_TERRITORY_INTELLIGENCE_CACHE, Math.max(512, max / 2));
            trimRuntimeMap(GOLEM_TERRITORY_ACTIVITY_CACHE, Math.max(512, max / 2));
            trimRuntimeMap(GOLEM_GUILD_AWARENESS_CACHE, Math.max(256, max / 4));
            trimRuntimeMap(GOLEM_LEARNING_PENALTY_CACHE, max);
            trimRuntimeMap(GOLEM_TASK_STATE, max);
            trimRuntimeMap(GOLEM_ROUTE_RESERVATIONS, max);
            trimRuntimeMap(GOLEM_THREAT_ASSIGNMENTS, max);
            trimRuntimeMap(GOLEM_ASYNC_PATROL_RESULTS, Math.max(512, max));
            trimRuntimeMap(GOLEM_ASYNC_PATROL_PENDING, Math.max(512, max));
            trimRuntimeMap(GOLEM_ASYNC_PATROL_LAST_REQUEST, Math.max(512, max));
            trimRuntimeMap(GUILD_WALL_SURFACE_Y_CACHE, Math.max(512, max / 2));
        }
    }

    private static boolean shouldSyncGuildGolemStats(GuildStore.Golem record, Entity entity, boolean runAi) {
        if (record == null || entity == null || !(entity instanceof LivingEntity living)) return false;
        if (record.golemStatsVersion != GuildGolemStats.STATS_VERSION
                || Double.compare(record.healthMultiplier, GuildGolemStats.healthMultiplier(record.elite)) != 0
                || Double.compare(record.damageMultiplier, GuildGolemStats.damageMultiplier(record.elite)) != 0
                || record.healthBonusPercent != GuildGolemStats.healthBonusPercent(record.elite)
                || record.damageBonusPercent != GuildGolemStats.damageBonusPercent(record.elite)
                || record.talentMaxHealthBonusPercent != GuildStore.guildGolemTalentHealthBonusPercent(record.guildId)
                || record.talentDamageBonusPercent != GuildStore.guildGolemTalentDamageBonusPercent(record.guildId)
                || record.talentSpeedBonusPercent != GuildStore.guildGolemTalentSpeedBonusPercent(record.guildId)
                || record.talentHealingSpeedBonusPercent != GuildStore.guildGolemTalentHealingSpeedBonusPercent(record.guildId)) return true;
        long now = entity.level().getGameTime();
        Long last = GOLEM_LAST_STAT_SYNC_TICK.get(record.uuid);
        if (last == null) {
            GOLEM_LAST_STAT_SYNC_TICK.put(record.uuid, now);
            return true;
        }
        if (living.getHealth() > living.getMaxHealth() + 0.05F) {
            GOLEM_LAST_STAT_SYNC_TICK.put(record.uuid, now);
            return true;
        }
        long interval = record.elite ? 60L : 100L;
        if (runAi && now - last >= interval) {
            GOLEM_LAST_STAT_SYNC_TICK.put(record.uuid, now);
            return true;
        }
        return false;
    }

    private static void maybeMarkGolemSeen(GuildStore.Golem record, Entity entity, boolean runAi) {
        if (record == null || record.uuid == null || entity == null) return;
        long now = entity.level().getGameTime();
        BlockPos pos = entity.blockPosition();
        String dimension = GuildStore.dimensionId(entity.level());
        LastGolemSeen last = GOLEM_SEEN_CACHE.get(record.uuid);
        int movementThreshold = isMassiveGolemServerMode() ? 16 : 8;
        boolean changed = last == null
                || !Objects.equals(last.dimension, dimension)
                || Math.abs(last.x - pos.getX()) >= movementThreshold
                || Math.abs(last.y - pos.getY()) >= 8
                || Math.abs(last.z - pos.getZ()) >= movementThreshold;
        boolean periodic = last == null || now - last.tick >= (runAi ? (isMassiveGolemServerMode() ? 240L : 100L) : 600L);
        if (!changed && !periodic) return;
        GOLEM_SEEN_CACHE.put(record.uuid, new LastGolemSeen(dimension, pos.getX(), pos.getY(), pos.getZ(), now));
        GuildStore.markGolemSeen(record.uuid, dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    // Guild banner recipe unlock is fully static through datapack resources.

    private static boolean runSilentServerCommand(ServerPlayer player, String command) {
        if (player == null || command == null || command.isBlank() || server == null) return false;
        try {
            Object source = createServerCommandSource(player);
            if (source == null) return false;
            Object commands = server.getCommands();
            for (Method method : commands.getClass().getMethods()) {
                if (!method.getName().equals("performPrefixedCommand")) continue;
                if (method.getParameterCount() != 2) continue;
                method.invoke(commands, source, command);
                return true;
            }
            for (Method method : commands.getClass().getMethods()) {
                if (!method.getName().equals("performCommand")) continue;
                if (method.getParameterCount() != 2) continue;
                method.invoke(commands, source, command);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static Object createServerCommandSource(ServerPlayer player) {
        try {
            Object source = server.getClass().getMethod("createCommandSourceStack").invoke(server);
            source = suppressCommandOutput(source);
            source = elevateCommandSource(source);
            return source;
        } catch (Throwable ignored) { }

        try {
            Object source = player.createCommandSourceStack();
            source = suppressCommandOutput(source);
            source = elevateCommandSource(source);
            return source;
        } catch (Throwable ignored) { }

        return null;
    }

    private static Object suppressCommandOutput(Object source) {
        if (source == null) return null;
        try {
            Method suppress = source.getClass().getMethod("withSuppressedOutput");
            return suppress.invoke(source);
        } catch (Throwable ignored) {
            return source;
        }
    }

    private static Object elevateCommandSource(Object source) {
        if (source == null) return null;
        for (Method method : source.getClass().getMethods()) {
            if (!method.getName().equals("withPermission") || method.getParameterCount() != 1) continue;
            Class<?> type = method.getParameterTypes()[0];
            try {
                if (type == int.class || type == Integer.class) return method.invoke(source, 4);
            } catch (Throwable ignored) { }
        }
        return source;
    }

    private static void processPendingGolemRevives() {
        processPendingGolemRevives(1);
    }

    private static void processPendingGolemRevives(int elapsedTicks) {
        if (server == null || PENDING_GOLEM_REVIVES.isEmpty()) return;
        int decrement = Math.max(1, elapsedTicks);
        List<String> done = new ArrayList<>();
        for (PendingGolemRevive pending : new ArrayList<>(PENDING_GOLEM_REVIVES.values())) {
            if (pending == null) continue;
            ServerLevel level = levelForDimension(pending.dimension);
            if (level == null) continue;
            if (pending.ticksLeft % 20 == 0) {
                spawnGolemReviveParticles(level, pending.pos, false);
                ServerPlayer actor = findOnlinePlayerByUuid(pending.actorUuid);
                if (actor != null) playGolemReviveSound(actor, pending.pos, false);
            }
            pending.ticksLeft -= decrement;
            if (pending.ticksLeft > 0) continue;

            spawnGolemReviveParticles(level, pending.pos, true);
            GolemSpawn spawn = new GolemSpawn(level, pending.pos.getX() + 0.5D, pending.pos.getY(), pending.pos.getZ() + 0.5D, GuildStore.territoryAt(level, pending.pos));
            Entity entity = spawnGuildGolemEntity(pending.guildId, pending.type, spawn, 0.0F, true, pending.elite);
            ServerPlayer actor = findOnlinePlayerByUuid(pending.actorUuid);
            if (entity != null) {
                GuildStore.markGolemAlive(pending.oldUuid, entity.getUUID().toString(), GuildStore.dimensionId(level), pending.pos.getX(), pending.pos.getY(), pending.pos.getZ());
                GuildGolemAppliedStats appliedStats = applyGuildGolemStats(entity, true, pending.elite);
                GuildStore.updateGolemStaticStats(entity.getUUID().toString(), appliedStats.baseMaxHealth(), appliedStats.baseDamage(), pending.elite);
                GuildStore.Golem record = GuildStore.golem(entity.getUUID().toString());
                ensureGuildGolemIdentity(record, entity);
                if (actor != null) {
                    playGolemReviveSound(actor, pending.pos, true);
                    actor.displayClientMessage(Component.literal("Home Craft Guilds: голема відроджено біля тотема."), false);
                    sendRoster(actor);
                }
            } else if (actor != null) {
                giveItems(actor, Items.EMERALD, GOLEM_REVIVE_EMERALD_COST);
                giveItems(actor, Items.IRON_INGOT, GOLEM_REVIVE_IRON_COST);
                actor.displayClientMessage(Component.literal("Home Craft Guilds: відродження не вдалося, ресурси повернуто."), false);
                sendRoster(actor);
            }
            done.add(pending.oldUuid);
        }
        for (String uuid : done) PENDING_GOLEM_REVIVES.remove(uuid);
    }

    private static void spawnGolemReviveParticles(ServerLevel level, BlockPos pos, boolean finalBurst) {
        if (level == null || pos == null) return;
        sendClientVisualEventNear(level, "golem_revive", pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, finalBurst, 48.0D);
    }

    private static void sendClientVisualEventNear(ServerLevel level, String kind, double x, double y, double z, boolean burst, double radius) {
        if (level == null || kind == null || kind.isBlank() || server == null || server.getPlayerList() == null) return;
        String snapshot = String.format(Locale.ROOT, "event|%s|%s|%.3f|%.3f|%.3f|%s", kind, GuildStore.dimensionId(level), x, y, z, Boolean.toString(burst));
        double max = Math.max(8.0D, radius) * Math.max(8.0D, radius);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || player.level() != level) continue;
            if (player.distanceToSqr(x, y, z) > max) continue;
            PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload(snapshot));
        }
    }

    private static void playGolemReviveSound(ServerPlayer sourcePlayer, BlockPos pos, boolean finalSound) {
        if (sourcePlayer == null || pos == null) return;
        String sound = finalSound ? "minecraft:block.anvil.place" : "minecraft:block.amethyst_block.chime";
        float volume = finalSound ? 0.75F : 0.45F;
        float pitch = finalSound ? 0.72F : 0.95F;
        String command = String.format(Locale.ROOT,
                "playsound %s master @a[x=%d,y=%d,z=%d,distance=..32] %d %d %d %.2f %.2f",
                sound, pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), volume, pitch);
        runSilentServerCommand(sourcePlayer, command);
    }

    @SubscribeEvent
    public static void onXpChange(PlayerXpEvent.XpChange event) {
        if (event == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        int amount = event.getAmount();
        if (amount <= 0 || !isActiveGuildMember(player)) return;

        String guildId = GuildStore.playerGuildId(player);
        // Member buffs are membership-bound only. Territory is not required for XP bonuses.
        int xpBonusPercent = GuildStore.guildPlayerXpBonusPercent(guildId);
        if (xpBonusPercent <= 0) return;
        // Exact percent bonus over time without doubling tiny 1-XP orbs: remainder is stored per player.
        CompoundTag data = player.getPersistentData();
        int previousRemainder = data == null ? 0 : Math.max(0, data.getInt(TAG_GUILD_XP_BONUS_REMAINDER).orElse(0));
        int scaled = amount * xpBonusPercent + previousRemainder;
        int bonus = Math.max(0, scaled / 100);
        int nextRemainder = Math.max(0, scaled % 100);
        if (data != null) data.putInt(TAG_GUILD_XP_BONUS_REMAINDER, nextRemainder);
        if (bonus > 0) event.setAmount(amount + bonus);
    }

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (event == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!isActiveGuildMember(player)) return;
        MobEffectInstance instance = event.getEffectInstance();
        if (!isExtendablePotionEffect(instance)) return;

        String guardKey = potionExtensionGuardKey(player, instance);
        if (GUILD_POTION_EXTENSION_GUARD.remove(guardKey)) return;

        String guildId = GuildStore.playerGuildId(player);
        // Member buffs are membership-bound only. Territory is not required for potion duration bonuses.
        int duration = instance.getDuration();
        int potionBonusPercent = GuildStore.guildPotionDurationBonusPercent(guildId);
        int extendedDuration = safeBoostedDuration(duration, potionBonusPercent);
        if (extendedDuration <= duration) return;

        // Apply on the next server task so the original potion application cannot overwrite
        // the longer duration. The guard prevents our own replacement effect from being extended again.
        GUILD_POTION_EXTENSION_GUARD.add(guardKey);
        MinecraftServer targetServer = resolveServer(player);
        Runnable apply = () -> {
            try {
                MobEffectInstance current = player.getEffect(instance.getEffect());
                if (current == null || current.getDuration() < extendedDuration - 1) {
                    player.addEffect(new MobEffectInstance(
                            instance.getEffect(),
                            extendedDuration,
                            instance.getAmplifier(),
                            instance.isAmbient(),
                            instance.isVisible(),
                            instance.showIcon()
                    ));
                }
            } finally {
                GUILD_POTION_EXTENSION_GUARD.remove(guardKey);
                if (GUILD_POTION_EXTENSION_GUARD.size() > 4096) GUILD_POTION_EXTENSION_GUARD.clear();
            }
        };
        if (targetServer != null) targetServer.execute(apply);
        else apply.run();
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (isGuildGolem(victim)) {
            GuildStore.markGolemDead(victim.getUUID().toString());
            if (victim instanceof Mob mob) clearGuildGolemAggro(mob);
            return;
        }
        DamageSource source = event.getSource();
        Entity attacker = source == null ? null : source.getEntity();
        if (attacker == null && source != null) attacker = source.getDirectEntity();

        // Death may be caused by fall, fire, poison, wither, lava, thorns or another indirect effect
        // after a player only tagged the mob once. Record the final combat entity when it exists,
        // then resolve guild XP from the full recent contribution ledger, not only from the final blow.
        registerCombatContribution(victim, attacker, 1.0F);

        ThreatProfile threat = threatProfile(victim);
        String guildId = resolveGuildForKill(victim, attacker, threat);
        if (guildId == null && source != null) guildId = guildIdFromCombatEntity(source.getDirectEntity());
        if (guildId == null) {
            ENTITY_COMBAT_CONTRIBUTORS.remove(victim.getUUID().toString());
            return;
        }

        handleGuildKillAchievement(guildId, victim, attacker, threat);

        int xp;
        String reason;
        boolean monsterKill;
        if (victim instanceof ServerPlayer victimPlayer) {
            String victimGuild = GuildStore.playerGuildId(victimPlayer);
            if (victimGuild == null || Objects.equals(victimGuild, guildId)) {
                ENTITY_COMBAT_CONTRIBUTORS.remove(victim.getUUID().toString());
                return;
            }
            xp = GuildLevelTiers.playerKillExperience();
            reason = "ворожого гравця";
            monsterKill = false;
        } else {
            xp = GuildLevelTiers.monsterExperience(victim);
            if (xp <= 0) {
                ENTITY_COMBAT_CONTRIBUTORS.remove(victim.getUUID().toString());
                return;
            }
            reason = "монстра";
            monsterKill = true;
        }

        List<GuildExperienceShare> shares = guildExperienceSharesForKill(victim, guildId, xp);
        GuildStore.GuildExperienceGain bestGain = null;
        for (GuildExperienceShare share : shares) {
            if (share == null || share.guildId == null || share.xp <= 0) continue;
            GuildStore.GuildExperienceGain gain = monsterKill
                    ? GuildStore.addMonsterKillGuildExperience(share.guildId, share.xp)
                    : GuildStore.addGuildExperience(share.guildId, share.xp);
            if (gain == null) continue;
            if (bestGain == null || gain.added > bestGain.added || gain.leveledUp()) bestGain = gain;
            int appliedBonus = Math.max(0, gain.added - share.xp);
            notifyGuildExperienceParticipants(victim, share, gain, reason, appliedBonus, shares.size() > 1);
            if (gain.leveledUp()) announceGuildLevelUp(gain);
        }
        ENTITY_COMBAT_CONTRIBUTORS.remove(victim.getUUID().toString());
    }

    @SubscribeEvent
    public static void onNpcInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (handleEliteGolemInteract(player, target)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (!isHomeCraftNpc(target)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        String npcKey = npcKeyOf(target);
        playGuildNpcVillagerResponse(target, "trader".equalsIgnoreCase(HomeCraftGuildConfig.npcKind(npcKey)));
        if ("trader".equalsIgnoreCase(HomeCraftGuildConfig.npcKind(npcKey))) {
            GuildNpcTradeService.openTrader(player, npcKey, target);
            return;
        }
        // For real tracked system NPCs, open directly from the clicked entity. Do not re-check the
        // legacy virtual guild_master point, otherwise extra system NPCs can react with sound but
        // fail to open because their key/position differs from the old singleton config.
        PacketDistributor.sendToPlayer(player, new OpenCreateGuildPayload(GuildStore.npcSnapshotFor(player)));
    }

    private static void playGuildNpcVillagerResponse(Entity npc, boolean trader) {
        if (npc == null || npc.level() == null || !HomeCraftGuildConfig.npcInteractionSoundEnabled()) return;
        try {
            npc.level().playSound(null, npc.blockPosition(), trader ? SoundEvents.VILLAGER_TRADE : SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.75F, trader ? 1.02F : 0.96F);
        } catch (Throwable ignored) { }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        invalidateGolemTerritoryIntelligence(event.getLevel(), event.getPos());

        boolean guildTotemBlock = isGuildTotemBlock(event.getState());
        GuildStore.Territory banner = GuildStore.territoryByBanner(event.getLevel(), event.getPos());

        if (banner != null && (guildTotemBlock || isStandingBanner(event.getState()))) {
            if ("GUILD".equals(banner.type) && !(GuildStore.isGuildMaster(player) && GuildStore.isMemberOfGuild(player, banner.guildId))) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("Home Craft Guilds: гільдійний тотем може зруйнувати лише глава гільдії."), true);
                logDenied("break_guild_banner", player, banner, event.getPos());
                return;
            }

            GuildStore.removeTerritory(banner);
            if ("GUILD".equals(banner.type) && event.getLevel() instanceof ServerLevel changedLevel) {
                forceGuildVisualTopologyRefresh(changedLevel, event.getPos(), 256);
            }
            if ("GUILD".equals(banner.type) && !player.getAbilities().instabuild) {
                player.getInventory().add(new ItemStack(HomeCraftGuildItems.GUILD_BANNER.get(), 1));
            }
            GuildSiteSync.pushTerritoriesAsync();
            player.displayClientMessage(Component.literal("Home Craft Guilds: територію знято. Тотем повернуто Гілдмайстру."), true);
            return;
        }

        GuildStore.Territory territory = GuildStore.territoryAt(event.getLevel(), event.getPos());
        if (territory == null) return;
        if ("GUILD".equals(territory.type) && isGuildFarmManagedBlock(event.getState())) {
            if (!GuildStore.canManageFarm(player, territory)) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("Home Craft Guilds: фермою можуть керувати лише Гілдмайстер і Фермер."), true);
                logDenied("farm_break", player, territory, event.getPos());
                return;
            }
            // Farmer is intentionally allowed to harvest crops / remove farm soil without generic builder rights.
            return;
        }
        if (!GuildStore.canBuild(player, territory)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("Home Craft Guilds: ця територія приватна."), true);
            logDenied("break", player, territory, event.getPos());
            return;
        }
        if (GuildBedManager.isBed(event.getState())) {
            GuildBedManager.bedBroken(event.getLevel(), event.getPos(), event.getState());
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        invalidateGolemTerritoryIntelligence(event.getLevel(), pos);

        // The custom guild totem is handled by GuildBannerBlockItem. Do not treat it as a vanilla personal banner.
        if (isGuildTotemBlock(event.getPlacedBlock())) return;

        if (isStandingBanner(event.getPlacedBlock()) && GuildStore.isSpawnPosition(event.getLevel(), pos)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("Home Craft Guilds: у spawn-зоні не можна створювати приват або гільдійну територію."), true);
            return;
        }

        GuildStore.Territory existing = GuildStore.territoryAt(event.getLevel(), pos);
        if (existing != null && "GUILD".equals(existing.type) && isGuildFarmManagedBlock(event.getPlacedBlock())) {
            if (!GuildStore.canManageFarm(player, existing)) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("Home Craft Guilds: садити та змінювати ферму можуть лише Гілдмайстер і Фермер."), true);
                logDenied("farm_place", player, existing, pos);
                return;
            }
            // Allow FARMER to plant crops and convert dirt/grass/path to farmland with a hoe.
            // This bypasses the generic builder check only for farm-managed blocks.
            return;
        }
        if (existing != null && "GUILD".equals(existing.type) && GuildBedManager.isBed(event.getPlacedBlock())) {
            if (!GuildBedManager.validateBedPlacement(player, existing, event.getLevel(), pos, event.getPlacedBlock())) {
                event.setCanceled(true);
                logDenied("guild_bed_place", player, existing, pos);
                return;
            }
            GuildBedManager.registerBedPlacement(player, existing, event.getLevel(), pos, event.getPlacedBlock());
            return;
        }

        if (existing != null && !GuildStore.canBuild(player, existing)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("Home Craft Guilds: тут не можна будувати."), true);
            logDenied("place", player, existing, pos);
            return;
        }

        if (isStandingBanner(event.getPlacedBlock())) {
            if (!GuildStore.canAddTerritory(player, pos, event.getLevel(), "PERSONAL")) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("Home Craft Guilds: особистий банер не створено. Перевір ліміт або перетин територій."), true);
                return;
            }
            GuildStore.Territory claim = GuildStore.addTerritory(player, pos, event.getLevel(), "PERSONAL");
            if (claim == null) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("Home Craft Guilds: особистий банер не створено. Перевір ліміт або перетин територій."), true);
                return;
            }
            GuildSiteSync.pushTerritoriesAsync();
            player.displayClientMessage(Component.literal("Home Craft Guilds: особиста територія 48x48 створена."), true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!isGuildGolem(target)) return;
        String guildId = guildIdFromGuildGolem(target);
        if (guildId == null || !GuildStore.isMemberOfGuild(player, guildId)) return;

        GuildStore.Golem record = GuildStore.golem(target.getUUID().toString());
        if (record != null && record.elite) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        playGolemInteractWhine(player, target);
        if (target instanceof Mob mob) {
            try { mob.getLookControl().setLookAt(player, 30.0F, 30.0F); } catch (Throwable ignored) {}
        }
    }

    private static void playGolemInteractWhine(ServerPlayer player, Entity golem) {
        if (player == null || golem == null) return;
        String sound = (Math.abs(golem.getUUID().hashCode()) & 1) == 0 ? "homecraftguild:golem.whine_1" : "homecraftguild:golem.whine_2";
        String command = String.format(Locale.ROOT,
                "playsound %s neutral @a[x=%.2f,y=%.2f,z=%.2f,distance=..24] %.2f %.2f %.2f 0.85 %.2f",
                sound, golem.getX(), golem.getY(), golem.getZ(), golem.getX(), golem.getY(), golem.getZ(), 0.92F + (Math.abs(golem.getUUID().hashCode()) % 12) / 100.0F);
        runSilentServerCommand(player, command);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!isLookingAtVirtualGuildNpc(player)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (isLookingAtVirtualGuildNpc(player)) {
            event.setCanceled(true);
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = player.level().getBlockState(pos);

        boolean guildTotemBlock = isGuildTotemBlock(state);
        GuildStore.Territory bannerTerritory = guildTotemBlock ? GuildStore.territoryByBanner(player.level(), pos) : null;
        if (guildTotemBlock && bannerTerritory != null && "GUILD".equals(bannerTerritory.type)) {
            event.setCanceled(true);
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (GuildStore.isMemberOfGuild(player, bannerTerritory.guildId)) {
                PacketDistributor.sendToPlayer(player, new OpenGuildRosterPayload(GuildStore.snapshotFor(player)));
            } else {
                PacketDistributor.sendToPlayer(player, new OpenGuildRosterPayload(GuildStore.snapshotForTerritoryViewer(player, bannerTerritory)));
            }
            return;
        }

        GuildStore.Territory territory = GuildStore.territoryAt(player.level(), pos);
        if (territory == null) return;
        if ("GUILD".equals(territory.type) && (isGuildFarmManagedBlock(state) || isHoeOnTillableGuildFarmBase(player, state))) {
            if (!GuildStore.canManageFarm(player, territory)) {
                event.setCanceled(true);
                event.setUseBlock(TriState.FALSE);
                event.setUseItem(TriState.FALSE);
                event.setCancellationResult(InteractionResult.FAIL);
                player.displayClientMessage(Component.literal("Home Craft Guilds: фермою можуть керувати лише Гілдмайстер і Фермер."), true);
                logDenied("farm_interact", player, territory, pos);
                return;
            }
            // FARMER/GUILDMASTER may use hoes, seeds, bonemeal and crop interactions here. Do not
            // cancel; vanilla item/block logic will do the actual work and fire normal block events.
        }
        if (GuildBedManager.isBed(state) && "GUILD".equals(territory.type)) {
            InteractionResult bedResult = GuildBedManager.useBed(player, territory, player.level(), pos, state);
            if (bedResult == InteractionResult.FAIL) {
                event.setCanceled(true);
                event.setUseBlock(TriState.FALSE);
                event.setUseItem(TriState.FALSE);
                event.setCancellationResult(InteractionResult.FAIL);
                logDenied("guild_bed_use", player, territory, pos);
            }
            return;
        }

        boolean chestOrBed = isChestLike(state) || isBed(state);
        if (!chestOrBed) return;
        if (!GuildStore.canInteractStorageOrBed(player, territory, pos, isBed(state))) {
            event.setCanceled(true);
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
            event.setCancellationResult(InteractionResult.FAIL);
            player.displayClientMessage(Component.literal("Home Craft Guilds: доступ до цього блоку закритий."), true);
            logDenied("interact", player, territory, pos);
        }
    }

    @SubscribeEvent
    public static void onFarmlandTrample(net.neoforged.neoforge.event.level.BlockEvent.FarmlandTrampleEvent event) {
        if (event == null) return;
        LevelAccessor level = event.getLevel();
        BlockPos pos = event.getPos();
        GuildStore.Territory territory = GuildStore.territoryAt(level, pos);
        if (territory == null || !"GUILD".equals(territory.type)) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (event.getLevel().isClientSide()) return;
        if (entity instanceof ServerPlayer player) {
            GuildCurrency.migrateAndNotify(player, "player_join");
            // Immediate repair on login/respawn/dimension join. Do not wait for the sliced tick pass;
            // otherwise players can think guild buffs are broken after a lag spike or reconnect.
            updatePlayerGuildBuffs(player);
            if (player.level() instanceof ServerLevel level) scheduleNpcLoginSync(player, level);
            trySpawnPendingGolemsForJoinedMember(player);
            return;
        }
        if (!HomeCraftGuildConfig.guildBlockMonsterSpawns()) return;
        if (entity.getType().getCategory() != MobCategory.MONSTER) return;
        if (isGuildGolem(entity)) return;
        GuildStore.Territory territory = GuildStore.territoryAt(event.getLevel(), entity.blockPosition());
        if (territory == null || !"GUILD".equals(territory.type)) return;

        // Блокуємо саме появу нового hostile mob у гільдійній території.
        // Моб, який вже існує у світі й сам зайшов/заблукав у територію, не проходить через цей join-spawn шлях
        // і тому не видаляється автоматично. Це навмисно: за планом моби можуть заходити, але не можуть з'являтися всередині.
        event.setCanceled(true);
        if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.debug("guild-monster-spawn-blocked: type={} pos={} territory={} guild={}", entity.getType(), entity.blockPosition(), territory.id, territory.guildName);
    }

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        Object explosion = event.getExplosion();
        Entity source = explosionSource(explosion);

        if (source instanceof Creeper creeper && source.level() instanceof ServerLevel level) {
            GuildStore.Territory territory = GuildStore.territoryAt(level, source.blockPosition());
            if (territory != null && "GUILD".equals(territory.type)) {
                event.setCanceled(true);
                spawnCreeperConfetti(level, source.getX(), source.getY() + 0.35D, source.getZ(), true);
                playCreeperConfettiSound(level, source.getX(), source.getY(), source.getZ());
                creeper.discard();
                if (HomeCraftGuildConfig.debug()) {
                    HomeCraftGuildMod.LOGGER.debug("guild-creeper-confetti: pos={} territory={} guild={}", source.blockPosition(), territory.id, territory.guildName);
                }
                return;
            }
        }

        // Будь-який вибух, що народжується прямо на території гільдії, повністю блокується.
        if (isExplosionInsideGuildTerritory(event.getLevel(), explosion, source)) {
            event.setCanceled(true);
            if (HomeCraftGuildConfig.debug()) {
                HomeCraftGuildMod.LOGGER.debug("guild-explosion-blocked-start: source={} pos={}", source == null ? "null" : source.getType(), source == null ? "unknown" : source.blockPosition());
            }
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Object explosion = event.getExplosion();
        Entity source = explosionSource(explosion);

        // Захист блоків гільдії від будь-яких вибухів, навіть якщо вибух поза межами і лише зачіпає територію.
        event.getAffectedBlocks().removeIf(pos -> isGuildProtectedFromExplosion(event.getLevel(), pos));
        removeProtectedEntitiesFromExplosion(event);

        if (source instanceof Creeper) {
            BlockPos pos = source.blockPosition();
            GuildStore.Territory territory = GuildStore.territoryAt(event.getLevel(), pos);
            if (territory != null && "GUILD".equals(territory.type) && source.level() instanceof ServerLevel level) {
                spawnCreeperConfetti(level, source.getX(), source.getY() + 0.35D, source.getZ(), false);
                playCreeperConfettiSound(level, source.getX(), source.getY(), source.getZ());
            }
        }
    }

    private static boolean isGuildProtectedFromExplosion(LevelAccessor level, BlockPos pos) {
        if (level == null || pos == null) return false;
        GuildStore.Territory territory = GuildStore.territoryAt(level, pos);
        return territory != null && "GUILD".equals(territory.type);
    }

    private static boolean isExplosionInsideGuildTerritory(LevelAccessor level, Object explosion, Entity source) {
        if (level == null) return false;
        if (source != null && isGuildProtectedFromExplosion(level, source.blockPosition())) return true;
        BlockPos pos = explosionPosition(explosion);
        return pos != null && isGuildProtectedFromExplosion(level, pos);
    }

    private static BlockPos explosionPosition(Object explosion) {
        if (explosion == null) return null;
        try {
            Object value = explosion.getClass().getMethod("center").invoke(explosion);
            BlockPos pos = vec3ToBlockPos(value);
            if (pos != null) return pos;
        } catch (Throwable ignored) { }
        try {
            Object value = explosion.getClass().getMethod("getPosition").invoke(explosion);
            BlockPos pos = vec3ToBlockPos(value);
            if (pos != null) return pos;
        } catch (Throwable ignored) { }
        try {
            Object value = explosion.getClass().getMethod("getPositionToBlow").invoke(explosion);
            BlockPos pos = vec3ToBlockPos(value);
            if (pos != null) return pos;
        } catch (Throwable ignored) { }
        return null;
    }

    private static BlockPos vec3ToBlockPos(Object value) {
        if (value == null) return null;
        try {
            Method x = value.getClass().getMethod("x");
            Method y = value.getClass().getMethod("y");
            Method z = value.getClass().getMethod("z");
            return new BlockPos((int)Math.floor(((Number)x.invoke(value)).doubleValue()), (int)Math.floor(((Number)y.invoke(value)).doubleValue()), (int)Math.floor(((Number)z.invoke(value)).doubleValue()));
        } catch (Throwable ignored) { }
        try {
            Method x = value.getClass().getMethod("getX");
            Method y = value.getClass().getMethod("getY");
            Method z = value.getClass().getMethod("getZ");
            return new BlockPos((int)Math.floor(((Number)x.invoke(value)).doubleValue()), (int)Math.floor(((Number)y.invoke(value)).doubleValue()), (int)Math.floor(((Number)z.invoke(value)).doubleValue()));
        } catch (Throwable ignored) { }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeProtectedEntitiesFromExplosion(ExplosionEvent.Detonate event) {
        if (event == null) return;
        try {
            for (Method method : event.getClass().getMethods()) {
                if (!method.getName().equals("getAffectedEntities") || method.getParameterCount() != 0) continue;
                Object value = method.invoke(event);
                if (!(value instanceof List list)) return;
                list.removeIf(obj -> obj instanceof Entity entity && isGuildProtectedFromExplosion(event.getLevel(), entity.blockPosition()));
                return;
            }
        } catch (Throwable ignored) { }
    }

    private static boolean isExplosionDamage(DamageSource source) {
        if (source == null) return false;
        try {
            String msg = source.getMsgId();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("explosion")) return true;
        } catch (Throwable ignored) { }
        try {
            Entity direct = source.getDirectEntity();
            if (direct instanceof net.minecraft.world.entity.item.PrimedTnt || direct instanceof Creeper) return true;
        } catch (Throwable ignored) { }
        try {
            Entity attacker = source.getEntity();
            if (attacker instanceof net.minecraft.world.entity.item.PrimedTnt || attacker instanceof Creeper) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static Entity explosionSource(Object explosion) {
        if (explosion == null) return null;
        String[] names = {"getDirectSourceEntity", "getIndirectSourceEntity", "getExploder", "source", "getSourceMob"};
        for (String name : names) {
            try {
                Object value = explosion.getClass().getMethod(name).invoke(explosion);
                if (value instanceof Entity entity) return entity;
            } catch (Throwable ignored) { }
        }
        try {
            java.lang.reflect.Field field = explosion.getClass().getDeclaredField("source");
            field.setAccessible(true);
            Object value = field.get(explosion);
            if (value instanceof Entity entity) return entity;
        } catch (Throwable ignored) { }
        return null;
    }

    private static void spawnCreeperConfetti(ServerLevel level, double x, double y, double z, boolean finalBurst) {
        // Visual-only: keep explosion protection authoritative on server, but render the confetti
        // on each nearby client instead of sending heavy server particle bursts.
        sendGuildClientVisualEvent(level, "creeper", x, y, z, finalBurst);
    }

    private static void sendGuildClientVisualEvent(ServerLevel level, String kind, double x, double y, double z, boolean finalBurst) {
        if (level == null || kind == null) return;
        String payload = String.format(Locale.ROOT, "event|%s|%s|%.3f|%.3f|%.3f|%s", kind, GuildStore.dimensionId(level), x, y, z, finalBurst);
        double maxSqr = 64.0D * 64.0D;
        for (ServerPlayer player : playersNearPoint(level, x, z, 64)) {
            if (player == null || player.level() != level) continue;
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            if (dx * dx + dy * dy + dz * dz > maxSqr) continue;
            PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload(payload));
        }
    }

    private static void playCreeperConfettiSound(ServerLevel level, double x, double y, double z) {
        if (level == null) return;
        level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 1.05F, 1.18F);
        level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.HOSTILE, 0.85F, 1.28F);
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        DamageSource source = event.getSource();
        Entity attacker = source == null ? null : source.getEntity();
        if (attacker == null && source != null) attacker = source.getDirectEntity();

        if (isExplosionDamage(source) && isGuildProtectedFromExplosion(victim.level(), victim.blockPosition())) {
            event.setCanceled(true);
            return;
        }

        if (isGuildGolem(victim)) {
            String guildId = guildIdFromGuildGolem(victim);
            if (guildId != null && attacker instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, guildId)) {
                if (victim instanceof Mob mob) clearGuildGolemAggro(mob);
                event.setCanceled(true);
                return;
            }
        }

        if (attacker != null && isGuildGolem(attacker) && victim instanceof ServerPlayer player) {
            String guildId = guildIdFromGuildGolem(attacker);
            if (guildId != null && GuildStore.isMemberOfGuild(player, guildId)) {
                if (attacker instanceof Mob mob) clearGuildGolemAggro(mob);
                event.setCanceled(true);
                return;
            }
        }

        registerCombatContribution(victim, attacker, event.getAmount());
        registerGuildCombatAlert(victim, attacker);

        if (victim instanceof ServerPlayer defender && attacker instanceof LivingEntity hostile && hostile.getType().getCategory() == MobCategory.MONSTER) {
            String defenderGuildId = GuildStore.playerGuildId(defender);
            int reduction = GuildStore.guildMemberDamageReductionPercent(defenderGuildId);
            // Member defensive buffs are membership-bound only. PvP is still excluded by the hostile mob check above.
            if (reduction > 0) {
                event.setAmount(event.getAmount() * Math.max(0.0F, 1.0F - reduction / 100.0F));
            }
        }

        if (attacker != null && isGuildGolem(attacker) && victim.getType().getCategory() == MobCategory.MONSTER) {
            String golemGuildId = guildIdFromGuildGolem(attacker);
            int crushing = GuildStore.guildGolemCrushingForcePercent(golemGuildId);
            if (crushing > 0) {
                event.setAmount(event.getAmount() * (1.0F + crushing / 100.0F));
            }
        }

        if (attacker instanceof ServerPlayer player && !isGuildGolem(victim)) {
            String attackerGuildId = GuildStore.playerGuildId(player);
            int bonus = attackerGuildId == null ? 0 : guildBuffState(attackerGuildId, hasArmorEquipped(player)).damageBonusPercent;
            // v145: primary path is an ATTACK_DAMAGE attribute modifier, so the inventory value and
            // final combat damage use the same number. Keep event scaling only as a safety fallback
            // for the short moment before the passive buff tick reapplies the attribute modifier.
            if (bonus > 0 && isWeaponLike(player.getMainHandItem()) && !hasAttributeModifier(player, Attributes.ATTACK_DAMAGE, GUILD_LEVEL_DAMAGE_MODIFIER_ID)) {
                event.setAmount(event.getAmount() * (1.0F + bonus / 100.0F));
            }
        }
    }

    private static void registerGuildCombatAlert(LivingEntity victim, Entity attacker) {
        if (!(victim instanceof ServerPlayer member) || attacker == null || attacker == member) return;
        String guildId = GuildStore.playerGuildId(member);
        if (guildId == null) return;
        if (isGuildGolem(attacker) && Objects.equals(guildIdFromGuildGolem(attacker), guildId)) return;
        if (attacker instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, guildId)) return;

        GuildStore.Territory territory = GuildStore.territoryAt(member.level(), member.blockPosition());
        if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, guildId)) return;

        long nowTick = member.level().getGameTime();
        String dimension = GuildStore.dimensionId(member.level());
        String attackerUuid = attacker.getUUID() == null ? "" : attacker.getUUID().toString();
        List<GuildCombatAlert> alerts = GUILD_COMBAT_ALERTS.computeIfAbsent(guildId, ignored -> new ArrayList<>());
        alerts.removeIf(alert -> alert == null || nowTick > alert.expiresAtTick || !Objects.equals(alert.dimension, dimension));
        alerts.add(new GuildCombatAlert(
                guildId,
                dimension,
                member.getX(),
                member.getY(),
                member.getZ(),
                attackerUuid,
                nowTick + GUILD_COMBAT_ALERT_DURATION_TICKS
        ));
        if (alerts.size() > MAX_COMBAT_ALERTS_PER_GUILD) {
            alerts.sort(Comparator.comparingLong(alert -> alert.expiresAtTick));
            while (alerts.size() > MAX_COMBAT_ALERTS_PER_GUILD) alerts.remove(0);
        }
        markGuildDefenseHot(guildId, member.level(), nowTick, GUILD_MEMBER_DEFENSE_HOT_TICKS);
        if (HomeCraftGuildConfig.debug()) {
            HomeCraftGuildMod.LOGGER.debug("guild-combat-alert: guild={} alerts={} victim={} attacker={} pos={} {} {}", guildId, alerts.size(), member.getName().getString(), attacker.getType(), member.getX(), member.getY(), member.getZ());
        }
    }

    private static GuildCombatAlert activeCombatAlert(String guildId, Entity entity) {
        if (guildId == null || entity == null) return null;
        List<GuildCombatAlert> alerts = GUILD_COMBAT_ALERTS.get(guildId);
        if (alerts == null || alerts.isEmpty()) return null;
        long nowTick = entity.level().getGameTime();
        String dimension = GuildStore.dimensionId(entity.level());
        alerts.removeIf(alert -> alert == null || nowTick > alert.expiresAtTick || !Objects.equals(alert.dimension, dimension));
        if (alerts.isEmpty()) {
            GUILD_COMBAT_ALERTS.remove(guildId);
            return null;
        }

        // Якщо бій у різних частинах суміжної території, големи не всі біжать в одну точку.
        // Кожен голем стабільно отримує найближчу або свою хеш-розподілену бойову точку.
        GuildCombatAlert nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (GuildCombatAlert alert : alerts) {
            double dist = distanceSqr(entity.getX(), entity.getY(), entity.getZ(), alert.x, alert.y, alert.z);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = alert;
            }
        }
        if (nearestDist <= 48.0D * 48.0D) return nearest;

        int index = Math.floorMod(String.valueOf(entity.getUUID()).hashCode(), alerts.size());
        return alerts.get(index);
    }

    private static LivingEntity alertTarget(GuildCombatAlert alert, Entity golem) {
        if (alert == null || golem == null) return null;
        UUID uuid = parseUuid(alert.attackerUuid);
        if (uuid != null) {
            Entity entity = findEntity(uuid);
            if (entity instanceof LivingEntity living && living.isAlive() && entity.level() == golem.level()) {
                if (!isGuildGolem(entity) && !shouldIgnoreUndergroundGolemTarget(entity)) return living;
            }
        }
        return findNearestHostileAroundAlert(alert, golem);
    }

    private static LivingEntity findNearestHostileAroundAlert(GuildCombatAlert alert, Entity golem) {
        if (alert == null || golem == null) return null;
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double maxSqr = GUILD_COMBAT_ALERT_REACT_RANGE * GUILD_COMBAT_ALERT_REACT_RANGE;
        for (Entity entity : entitiesNear(golem.level(), alert.x, alert.y, alert.z, Math.max(32.0D, GUILD_COMBAT_ALERT_REACT_RANGE), dynamicGolemTargetScanBudget())) {
            if (!(entity instanceof LivingEntity living) || entity == golem || !entity.isAlive()) continue;
            if (isGuildGolem(entity)) continue;
            if (entity instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, alert.guildId)) continue;
            boolean hostile = entity.getType().getCategory() == MobCategory.MONSTER || entity instanceof ServerPlayer;
            if (!hostile) continue;
            if (shouldIgnoreUndergroundGolemTarget(entity)) continue;
            double alertDist = distanceSqr(entity.getX(), entity.getY(), entity.getZ(), alert.x, alert.y, alert.z);
            double golemDist = entity.distanceToSqr(golem);
            boolean elevated = isAerialOrElevatedThreat(entity);
            double allowed = maxSqr * (isHighAlertThreat(entity) ? 2.0D : (elevated ? 1.45D : 1.0D));
            if (alertDist > allowed && golemDist > allowed) continue;
            int priority = golemThreatPriority(entity);
            double score = priority + (entity instanceof ServerPlayer ? 220.0D : 0.0D) + (isHighAlertThreat(entity) ? 500.0D : 0.0D) + (elevated ? 120.0D : 0.0D);
            score -= Math.sqrt(Math.min(alertDist, golemDist)) * 2.1D;
            if (score > bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    public static boolean registerGuildBannerClaim(ServerPlayer player, BlockPos pos, BlockState state) {
        if (!GuildStore.isGuildMaster(player)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: гільдійний тотем може ставити лише Гілдмайстер."), true);
            if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.info("guild-banner-denied: player={} reason=not_guildmaster pos={}", player.getName().getString(), pos);
            return false;
        }
        if (GuildStore.isSpawnPosition(player.level(), pos)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: гільдійний тотем не можна ставити у spawn-зоні. Територія спавну має пріоритет."), true);
            if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.info("guild-banner-denied: player={} reason=spawn_area pos={}", player.getName().getString(), pos);
            return false;
        }
        GuildStore.Territory existing = GuildStore.territoryAt(player.level(), pos);
        if (existing != null && !GuildStore.canBuild(player, existing)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: гільдійний тотем не можна ставити на чужій території."), true);
            return false;
        }
        GuildStore.Territory territory = GuildStore.addTerritory(player, pos, player.level(), "GUILD");
        if (territory == null) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: гільдійну територію не створено. Потрібен Гілдмайстер, не вичерпаний ліміт і без перетину територій."), true);
            return false;
        }
        if (player.level() instanceof ServerLevel changedLevel) {
            forceGuildVisualTopologyRefresh(changedLevel, pos, 256);
        }
        GuildSiteSync.pushTerritoriesAsync();
        return true;
    }

    public static void handleClientAction(ServerPlayer player, String action, String a, String b) {
        String op = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (op.startsWith("npc_admin")) { handleNpcAdminAction(player, op, a, b); return; }
        if ("request".equals(op)) { sendRoster(player); return; }
        if ("npc_interact".equals(op)) { openVirtualGuildNpcFor(player); return; }
        if ("create".equals(op)) { createGuildFromClient(player, a, b); return; }
        if ("invite".equals(op)) { inviteByName(player, a); return; }
        if ("kick".equals(op)) { kickByName(player, a); return; }
        if ("rank".equals(op)) { setRankByName(player, a, b); return; }
        if ("leave".equals(op)) { leaveGuild(player); return; }
        if ("disband".equals(op)) { disbandGuild(player); return; }
        if ("hire_golem".equals(op)) { hireGolem(player, b == null || b.isBlank() ? a : b); return; }
        if ("delete_golem".equals(op)) { deleteGolem(player, a); return; }
        if ("teleport_golem_totem".equals(op)) { teleportGolemToNearestTotem(player, a); return; }
        if ("revive_golem".equals(op)) { reviveGolem(player, a); return; }
        if ("unlock_talent".equals(op)) { unlockGuildTalent(player, a); return; }
        if ("reset_talent_branch".equals(op)) { resetGuildTalentBranch(player, a); return; }
        if ("teleport_guild".equals(op)) { teleportPlayerToGuildTerritory(player); return; }
        if ("accept".equals(op)) {
            boolean ok = GuildStore.acceptInvite(player, a);
            player.displayClientMessage(Component.literal(ok ? "Home Craft Guilds: запрошення прийнято." : "Home Craft Guilds: запрошення не прийнято. Можливо, гільдія вже заповнена: " + HomeCraftGuildConfig.maxGuildMembers() + "/" + HomeCraftGuildConfig.maxGuildMembers() + "."), false);
            sendRoster(player);
            return;
        }
        if ("decline".equals(op)) {
            boolean ok = GuildStore.declineInvite(player, a);
            player.displayClientMessage(Component.literal(ok ? "Home Craft Guilds: запрошення відхилено." : "Home Craft Guilds: запрошення не знайдено."), false);
            sendRoster(player);
        }
    }

    private static void handleNpcAdminAction(ServerPlayer player, String op, String a, String b) {
        if (player == null) return;
        if (!isStrictOpPlayer(player)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: NPC Admin доступний лише OP."), false);
            HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: denied NPC admin action={} actor={}", op, player.getName().getString());
            return;
        }
        String key = HomeCraftGuildConfig.normalizeNpcKey(a);
        Map<String, String> params = parseNpcAdminParams(b);
        if ("npc_admin_refresh".equals(op)) {
            sendNpcAdminUi(player);
            return;
        }
        if ("npc_admin_repair_all".equals(op)) {
            ensureGuildNpcEntities(resolveServer(player), "admin_repair_all");
            forceAllNpcSync("admin_repair_all");
            player.displayClientMessage(Component.literal("Home Craft Guilds: repair усіх NPC виконано."), false);
            sendNpcAdminUi(player);
            return;
        }
        if ("npc_admin_create_check".equals(op)) {
            String normalized = key.isBlank() ? "" : key;
            if (normalized.isBlank()) {
                sendNpcAdminUi(player, npcCreateResultLine("", "error", "Ключ NPC порожній", ""));
            } else if (HomeCraftGuildConfig.npcKeys().contains(normalized)) {
                sendNpcAdminUi(player, npcCreateResultLine(normalized, "error", "NPC з таким ключем уже існує", normalized));
            } else {
                sendNpcAdminUi(player, npcCreateResultLine(normalized, "success", "Ключ доступний", normalized));
            }
            return;
        }
        if ("npc_admin_create_trader".equals(op)) {
            if (key.isBlank()) {
                player.displayClientMessage(Component.literal("Home Craft Guilds: ключ торговця порожній."), false);
                sendNpcAdminUi(player, npcCreateResultLine("", "error", "Ключ торговця порожній", ""));
                return;
            }
            if (HomeCraftGuildConfig.npcKeys().contains(key)) {
                player.displayClientMessage(Component.literal("Home Craft Guilds: NPC з ключем '" + key + "' уже існує."), false);
                sendNpcAdminUi(player, npcCreateResultLine(key, "error", "NPC з таким ключем уже існує", key));
                return;
            }
            String skin = params.getOrDefault("skin", "guild_registrar");
            String name = params.getOrDefault("name", "Гільдійний Торговець");
            String preset = params.getOrDefault("preset", key);
            HomeCraftGuildConfig.createNpcDefinition(key, "trader", skin, name, false, preset);
            saveGuildNpcPoint(player, key);
            ensureGuildNpcEntity(resolveServer(player), key, "admin_create_trader");
            forceAllNpcSync("admin_create_trader");
            player.displayClientMessage(Component.literal("Home Craft Guilds: торговця '" + key + "' створено."), false);
            sendNpcAdminUi(player, npcCreateResultLine(key, "success", "NPC створено", key));
            return;
        }
        if (key.isBlank()) key = "guild_master";
        if ("npc_admin_save".equals(op)) {
            String skin = params.getOrDefault("skin", HomeCraftGuildConfig.npcSkinId(key));
            String name = params.getOrDefault("name", HomeCraftGuildConfig.npcName(key));
            HomeCraftGuildConfig.setNpcMetadata(key, skin, name);
            if (!HomeCraftGuildConfig.npcSystem(key) && params.containsKey("preset")) HomeCraftGuildConfig.setNpcTraderPresetId(key, params.get("preset"));
            ensureGuildNpcEntity(resolveServer(player), key, "admin_save");
            forceAllNpcSync("admin_save");
            player.displayClientMessage(Component.literal("Home Craft Guilds: NPC '" + key + "' оновлено."), false);
            sendNpcAdminUi(player);
            return;
        }
        if ("npc_admin_tp".equals(op)) {
            saveGuildNpcPoint(player, key);
            ensureGuildNpcEntity(resolveServer(player), key, "admin_tp");
            forceAllNpcSync("admin_tp");
            player.displayClientMessage(Component.literal("Home Craft Guilds: NPC '" + key + "' перенесено."), false);
            sendNpcAdminUi(player);
            return;
        }
        if ("npc_admin_remove".equals(op)) {
            removeGuildNpc(resolveServer(player), key);
            HomeCraftGuildConfig.clearNpcPosition(key);
            forceAllNpcSync("admin_remove");
            player.displayClientMessage(Component.literal("Home Craft Guilds: NPC '" + key + "' прибрано зі світу."), false);
            sendNpcAdminUi(player);
            return;
        }
        if ("npc_admin_trade_add".equals(op) || "npc_admin_trade_check".equals(op)) {
            if (HomeCraftGuildConfig.npcSystem(key)) {
                player.displayClientMessage(Component.literal("Home Craft Guilds: системний NPC не має редактора торгівлі."), false);
                sendNpcAdminUi(player, tradeResultLine(key, -1, "error", "save", "Системний NPC не має товарів", ""));
                return;
            }
            GuildNpcTradeService.ValidationResult validation = GuildNpcTradeService.validateAndNormalizeTrade(player, b);
            if (!validation.success()) {
                player.displayClientMessage(Component.literal("Home Craft Guilds: товар відхилено: " + validation.error()), false);
                sendNpcAdminUi(player, tradeResultLine(key, -1, "error", "npc_admin_trade_check".equals(op) ? "check" : "save", validation.error(), ""));
                return;
            }
            if ("npc_admin_trade_check".equals(op)) {
                sendNpcAdminUi(player, tradeResultLine(key, -1, "success", "check", "Товар валідний", validation.normalizedSpec()));
                return;
            }
            int newIndex = HomeCraftGuildConfig.npcTradeCount(key);
            HomeCraftGuildConfig.appendNpcTrade(key, validation.normalizedSpec());
            player.displayClientMessage(Component.literal("Home Craft Guilds: товар додано для '" + key + "'."), false);
            sendNpcAdminUi(player, tradeResultLine(key, newIndex, "success", "save", "Товар додано", validation.normalizedSpec()));
            return;
        }
        if ("npc_admin_trade_set".equals(op)) {
            if (HomeCraftGuildConfig.npcSystem(key)) {
                player.displayClientMessage(Component.literal("Home Craft Guilds: системний NPC не має редактора торгівлі."), false);
                sendNpcAdminUi(player, tradeResultLine(key, -1, "error", "save", "Системний NPC не має товарів", ""));
                return;
            }
            int sep = b == null ? -1 : b.indexOf(';');
            int index = -1;
            String spec = "";
            if (sep >= 0) {
                try { index = Integer.parseInt(b.substring(0, sep).trim()); } catch (Exception ignored) {}
                spec = b.substring(sep + 1);
            }
            if (index < 0 || spec.isBlank()) {
                String error = "Неправильний індекс або порожні дані";
                player.displayClientMessage(Component.literal("Home Craft Guilds: товар не оновлено, " + error + "."), false);
                sendNpcAdminUi(player, tradeResultLine(key, index, "error", "save", error, ""));
                return;
            }
            GuildNpcTradeService.ValidationResult validation = GuildNpcTradeService.validateAndNormalizeTrade(player, spec);
            if (!validation.success()) {
                player.displayClientMessage(Component.literal("Home Craft Guilds: товар #" + index + " відхилено: " + validation.error()), false);
                sendNpcAdminUi(player, tradeResultLine(key, index, "error", "save", validation.error(), ""));
                return;
            }
            HomeCraftGuildConfig.setNpcTrade(key, index, validation.normalizedSpec());
            player.displayClientMessage(Component.literal("Home Craft Guilds: товар #" + index + " оновлено для '" + key + "'."), false);
            sendNpcAdminUi(player, tradeResultLine(key, index, "success", "save", "Товар оновлено", validation.normalizedSpec()));
            return;
        }
        if ("npc_admin_apply_preset".equals(op)) {
            if (HomeCraftGuildConfig.npcSystem(key)) {
                player.displayClientMessage(Component.literal("Home Craft Guilds: системному NPC не можна застосувати торговий пресет."), false);
                sendNpcAdminUi(player);
                return;
            }
            String preset = params.getOrDefault("preset", HomeCraftGuildConfig.npcTraderPresetId(key));
            boolean ok = HomeCraftGuildConfig.applyNpcTradePreset(key, preset);
            ensureGuildNpcEntity(resolveServer(player), key, "admin_apply_preset");
            forceAllNpcSync("admin_apply_preset");
            player.displayClientMessage(Component.literal(ok ? "Home Craft Guilds: товари NPC '" + key + "' оновлено з пресету '" + preset + "'." : "Home Craft Guilds: пресет не застосовано."), false);
            sendNpcAdminUi(player);
            return;
        }

        if ("npc_admin_trade_duplicate".equals(op)) {
            if (!HomeCraftGuildConfig.npcSystem(key)) {
                int index = 0;
                try { index = Integer.parseInt(String.valueOf(b == null ? "0" : b).trim()); } catch (Exception ignored) {}
                HomeCraftGuildConfig.duplicateNpcTrade(key, index);
                player.displayClientMessage(Component.literal("Home Craft Guilds: товар #" + index + " продубльовано для '" + key + "'."), false);
            }
            sendNpcAdminUi(player);
            return;
        }
        if ("npc_admin_trade_move".equals(op)) {
            if (!HomeCraftGuildConfig.npcSystem(key)) {
                String[] mv = String.valueOf(b == null ? "" : b).split(";", -1);
                int index = 0;
                int delta = 0;
                try { index = Integer.parseInt(mv.length > 0 ? mv[0].trim() : "0"); } catch (Exception ignored) {}
                try { delta = Integer.parseInt(mv.length > 1 ? mv[1].trim() : "0"); } catch (Exception ignored) {}
                HomeCraftGuildConfig.moveNpcTrade(key, index, delta);
                player.displayClientMessage(Component.literal("Home Craft Guilds: товар #" + index + " переміщено."), false);
            }
            sendNpcAdminUi(player);
            return;
        }
        if ("npc_admin_trade_remove".equals(op)) {
            if (!HomeCraftGuildConfig.npcSystem(key)) {
                int index = 0;
                try { index = Integer.parseInt(String.valueOf(b == null ? "0" : b).trim()); } catch (Exception ignored) {}
                HomeCraftGuildConfig.removeNpcTrade(key, index);
                player.displayClientMessage(Component.literal("Home Craft Guilds: товар #" + index + " видалено для '" + key + "'."), false);
            }
            sendNpcAdminUi(player);
            return;
        }
    }

    private static void sendNpcAdminUi(ServerPlayer player) {
        sendNpcAdminUi(player, "");
    }

    private static void sendNpcAdminUi(ServerPlayer player, String resultLine) {
        if (player == null) return;
        String snapshot = buildNpcAdminSnapshot();
        if (resultLine != null && !resultLine.isBlank()) snapshot = snapshot + resultLine + "\n";
        sendNpcAdminSnapshotPayload(player, snapshot);
    }

    private static void sendNpcAdminSnapshotPayload(ServerPlayer player, String snapshot) {
        if (player == null || snapshot == null) return;
        if (snapshot.length() <= NPC_ADMIN_SNAPSHOT_CHUNK_CHARS) {
            PacketDistributor.sendToPlayer(player, new GuildNpcAdminSnapshotPayload(snapshot));
            return;
        }
        String id = Long.toString(System.currentTimeMillis(), 36) + "_" + Long.toString(NPC_ADMIN_SNAPSHOT_SEQUENCE.incrementAndGet(), 36);
        int total = Math.max(1, (snapshot.length() + NPC_ADMIN_SNAPSHOT_CHUNK_CHARS - 1) / NPC_ADMIN_SNAPSHOT_CHUNK_CHARS);
        for (int i = 0; i < total; i++) {
            int from = i * NPC_ADMIN_SNAPSHOT_CHUNK_CHARS;
            int to = Math.min(snapshot.length(), from + NPC_ADMIN_SNAPSHOT_CHUNK_CHARS);
            String chunk = "chunk|npc_admin_snapshot|" + id + "|" + i + "|" + total + "|" + snapshot.substring(from, to);
            PacketDistributor.sendToPlayer(player, new GuildNpcAdminSnapshotPayload(chunk));
        }
        if (HomeCraftGuildConfig.debug()) {
            HomeCraftGuildMod.LOGGER.debug("Home Craft Guilds: sent NPC admin snapshot in {} chunks, chars={}", total, snapshot.length());
        }
    }

    private static String tradeResultLine(String npcKey, int index, String status, String mode, String message, String normalized) {
        return "result|trade_validation|"
                + sanitizeSnapshotField(npcKey) + "|"
                + index + "|"
                + sanitizeSnapshotField(status) + "|"
                + sanitizeSnapshotField(mode) + "|"
                + encodeSnapshotField(message) + "|"
                + encodeSnapshotField(normalized);
    }

    private static String npcCreateResultLine(String npcKey, String status, String message, String normalizedKey) {
        return "result|npc_create|"
                + sanitizeSnapshotField(npcKey) + "|"
                + sanitizeSnapshotField(status) + "|"
                + encodeSnapshotField(message) + "|"
                + sanitizeSnapshotField(normalizedKey);
    }

    private static Map<String, String> parseNpcAdminParams(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) return map;
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim().replace('|', ' ').replace('~', ' ');
            if (!key.isBlank()) map.put(key, value);
        }
        return map;
    }

    private static void unlockGuildTalent(ServerPlayer player, String talentId) {
        GuildStore.GuildTalentResult result = GuildStore.unlockTalent(player, talentId);
        player.displayClientMessage(Component.literal("Home Craft Guilds: " + result.message), false);
        if (result.success) resyncLiveGuildGolems(GuildStore.playerGuildId(player));
        sendRoster(player);
    }

    private static void resetGuildTalentBranch(ServerPlayer player, String branch) {
        GuildStore.GuildTalentResult result = GuildStore.resetTalentBranch(player, branch);
        player.displayClientMessage(Component.literal("Home Craft Guilds: " + result.message), false);
        if (result.success) resyncLiveGuildGolems(GuildStore.playerGuildId(player));
        sendRoster(player);
    }

    private static Entity findLiveGolemEntity(String uuid) {
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

    private static void resyncLiveGuildGolems(String guildId) {
        if (guildId == null || guildId.isBlank() || server == null) return;
        for (GuildStore.Golem record : GuildStore.guildGolems(guildId)) {
            if (record == null || record.dead || record.removed) continue;
            Entity entity = findLiveGolemEntity(record.uuid);
            if (entity != null) {
                GuildGolemAppliedStats appliedStats = applyGuildGolemStats(entity, false, record.elite);
                GuildStore.updateGolemStaticStats(record.uuid, appliedStats.baseMaxHealth(), appliedStats.baseDamage(), record.elite);
            }
        }
    }

    private static void createGuildFromClient(ServerPlayer player, String name, String b) {
        if (GuildStore.isInGuild(player)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: ти вже у гільдії."), false);
            return;
        }
        int cost = HomeCraftGuildConfig.guildCreateEmeraldCost();
        if (!takeEmeralds(player, cost)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: потрібно " + cost + " смарагди."), false);
            return;
        }
        if (GuildStore.createGuild(player, name, b)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: гільдію створено."), false);
            GuildSiteSync.pushTerritoriesAsync();
            sendRoster(player);
        } else {
            giveEmeralds(player, cost);
            player.displayClientMessage(Component.literal("Home Craft Guilds: не вдалося створити гільдію."), false);
        }
    }

    private static void leaveGuild(ServerPlayer player) {
        boolean ok = GuildStore.leaveGuild(player);
        if (ok) clearAllGuildBuffs(player, player.getPersistentData());
        player.displayClientMessage(Component.literal(ok ? "Home Craft Guilds: ти вийшов з гільдії." : "Home Craft Guilds: Гілдмайстер не може вийти. Розпусти гільдію через NPC."), false);
        sendRoster(player);
    }

    private static void disbandGuild(ServerPlayer player) {
        String guildId = GuildStore.disbandGuild(player);
        if (guildId == null) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: розпустити гільдію може лише Гілдмайстер."), false);
            sendRoster(player);
            return;
        }
        removeGuildGolems(guildId);
        GuildSiteSync.pushTerritoriesAsync();
        player.displayClientMessage(Component.literal("Home Craft Guilds: гільдію розпущено."), false);
        sendRoster(player);
    }

    private static void teleportPlayerToGuildTerritory(ServerPlayer player) {
        if (player == null) return;
        String guildId = GuildStore.playerGuildId(player);
        if (guildId == null) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: ти не перебуваєш у гільдії."), false);
            return;
        }
        long nowTick = player.level().getGameTime();
        CompoundTag data = player.getPersistentData();
        long lastTick = data.getLong(TAG_GUILD_TELEPORT_LAST_TICK).orElse(0L);
        long leftTicks = lastTick <= 0L ? 0L : GUILD_TELEPORT_COOLDOWN_TICKS - (nowTick - lastTick);
        if (leftTicks > 0L) {
            long leftSeconds = Math.max(1L, (leftTicks + 19L) / 20L);
            long minutes = leftSeconds / 60L;
            long seconds = leftSeconds % 60L;
            player.displayClientMessage(Component.literal("Home Craft Guilds: телепорт буде доступний через " + minutes + " хв " + seconds + " с."), false);
            return;
        }

        GuildTeleportTarget target = findSafeGuildTeleportTarget(player, guildId);
        if (target == null) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: не знайдено безпечну точку телепортації на території гільдії. Телепорт не виконано, cooldown не списано."), false);
            return;
        }

        resetPlayerTeleportPhysics(player);
        teleportPlayerToLevel(player, target.level(), target.pos().getX() + 0.5D, target.pos().getY(), target.pos().getZ() + 0.5D);
        resetPlayerTeleportPhysics(player);
        data.putLong(TAG_GUILD_TELEPORT_LAST_TICK, nowTick);
        player.displayClientMessage(Component.literal("Home Craft Guilds: телепортація до безпечної точки гільдії виконана. Cooldown: 10 хвилин."), false);
    }

    private record GuildTeleportTarget(ServerLevel level, BlockPos pos, GuildStore.Territory territory) {}

    private static GuildTeleportTarget findSafeGuildTeleportTarget(ServerPlayer player, String guildId) {
        if (player == null || guildId == null) return null;
        List<GuildStore.Territory> territories = new ArrayList<>(GuildStore.guildTerritories(guildId));
        if (territories.isEmpty()) return null;
        String currentDimension = GuildStore.dimensionId(player.level());
        territories.sort(Comparator
                .comparing((GuildStore.Territory t) -> t == null || !Objects.equals(t.dimension, currentDimension))
                .thenComparingDouble(t -> t == null ? Double.MAX_VALUE : distanceToTerritoryCenterSqr(player.getX(), player.getZ(), t)));

        int[][] offsets = {
                {0,0},{2,0},{-2,0},{0,2},{0,-2},{3,1},{-3,1},{3,-1},{-3,-1},
                {1,3},{-1,3},{1,-3},{-1,-3},{4,0},{-4,0},{0,4},{0,-4},
                {5,2},{-5,2},{5,-2},{-5,-2},{2,5},{-2,5},{2,-5},{-2,-5}
        };
        for (GuildStore.Territory territory : territories) {
            if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, guildId)) continue;
            ServerLevel level = levelForTerritory(player, territory);
            if (level == null) continue;
            for (int[] off : offsets) {
                BlockPos pos = resolveStrictPlayerTeleportSpot(level, territory, territory.x + off[0], territory.z + off[1]);
                if (pos != null) return new GuildTeleportTarget(level, pos, territory);
            }
            int minX = GuildStore.minX(territory);
            int maxX = GuildStore.maxX(territory);
            int minZ = GuildStore.minZ(territory);
            int maxZ = GuildStore.maxZ(territory);
            int step = Math.max(2, Math.min(6, Math.max(1, Math.min(maxX - minX, maxZ - minZ) / 6)));
            for (int x = minX + 1; x <= maxX - 1; x += step) {
                for (int z = minZ + 1; z <= maxZ - 1; z += step) {
                    BlockPos pos = resolveStrictPlayerTeleportSpot(level, territory, x, z);
                    if (pos != null) return new GuildTeleportTarget(level, pos, territory);
                }
            }
        }
        return null;
    }

    private static double distanceToTerritoryCenterSqr(double x, double z, GuildStore.Territory territory) {
        if (territory == null) return Double.MAX_VALUE;
        double dx = x - (territory.x + 0.5D);
        double dz = z - (territory.z + 0.5D);
        return dx * dx + dz * dz;
    }

    private static BlockPos resolveStrictPlayerTeleportSpot(ServerLevel level, GuildStore.Territory territory, int x, int z) {
        if (level == null || territory == null) return null;
        if (x < GuildStore.minX(territory) || x > GuildStore.maxX(territory) || z < GuildStore.minZ(territory) || z > GuildStore.maxZ(territory)) return null;
        ensureChunkLoaded(level, x, z);
        BlockPos pos = GuildGolemSafePositionResolver.safeSurface(level, x, z, territory.y, 4);
        if (pos == null) return null;
        if (pos.getX() < GuildStore.minX(territory) || pos.getX() > GuildStore.maxX(territory) || pos.getZ() < GuildStore.minZ(territory) || pos.getZ() > GuildStore.maxZ(territory)) return null;
        GuildStore.Territory at = GuildStore.territoryAt(level, pos);
        if (at == null || !"GUILD".equals(at.type) || !Objects.equals(at.guildId, territory.guildId)) return null;
        if (!isStrictPlayerTeleportSpot(level, pos)) return null;
        return pos;
    }

    private static boolean isStrictPlayerTeleportSpot(ServerLevel level, BlockPos feet) {
        return GuildGolemSafePositionResolver.isWalkable(level, feet);
    }

    private static void resetPlayerTeleportPhysics(ServerPlayer player) {
        if (player == null) return;
        try { player.stopRiding(); } catch (Throwable ignored) {}
        try { player.setDeltaMovement(0.0D, 0.0D, 0.0D); } catch (Throwable ignored) {}
        try { player.getClass().getMethod("resetFallDistance").invoke(player); } catch (Throwable ignored) {}
    }

    private static void teleportPlayerToLevel(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        if (player == null || level == null) return;
        try {
            Method method = player.getClass().getMethod("teleportTo", ServerLevel.class, double.class, double.class, double.class, float.class, float.class);
            method.invoke(player, level, x, y, z, player.getYRot(), player.getXRot());
            return;
        } catch (Throwable ignored) { }
        if (player.level() == level) {
            teleportEntity(player, x, y, z);
            return;
        }
        try {
            Object transition = buildDimensionTransition(level, x, y, z, player.getYRot(), player.getXRot());
            if (transition != null) {
                for (Method m : player.getClass().getMethods()) {
                    if (!m.getName().equals("changeDimension") || m.getParameterCount() != 1) continue;
                    m.invoke(player, transition);
                    return;
                }
            }
        } catch (Throwable ignored) { }
    }

    private static Object buildDimensionTransition(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        try {
            Class<?> clazz = Class.forName("net.minecraft.world.level.portal.DimensionTransition");
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            Object pos = vec3.getMethod("atCenterOf", net.minecraft.core.Vec3i.class).invoke(null, new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z)));
            Object zero = vec3.getField("ZERO").get(null);
            for (java.lang.reflect.Constructor<?> c : clazz.getConstructors()) {
                Class<?>[] t = c.getParameterTypes();
                try {
                    if (t.length == 7) return c.newInstance(level, pos, zero, yaw, pitch, false, null);
                    if (t.length == 6) return c.newInstance(level, pos, zero, yaw, pitch, false);
                    if (t.length == 5) return c.newInstance(level, pos, zero, yaw, pitch);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static int minBuildHeight(ServerLevel level) {
        if (level == null) return -64;
        try {
            Object value = level.getClass().getMethod("getMinBuildHeight").invoke(level);
            if (value instanceof Number n) return n.intValue();
        } catch (Throwable ignored) { }
        try {
            Object value = level.getClass().getMethod("getMinY").invoke(level);
            if (value instanceof Number n) return n.intValue();
        } catch (Throwable ignored) { }
        try {
            Object dimensionType = level.dimensionType();
            Object value = dimensionType.getClass().getMethod("minY").invoke(dimensionType);
            if (value instanceof Number n) return n.intValue();
        } catch (Throwable ignored) { }
        return -64;
    }

    private static boolean isNightTime(ServerLevel level) {
        if (level == null) return false;
        long dayTime = level.getDayTime() % 24000L;
        return dayTime >= 13000L && dayTime <= 23000L;
    }

    private static boolean isSafeTeleportSpot(ServerLevel level, BlockPos pos) {
        return isGolemWalkableSpot(level, pos);
    }

    private static void hireGolem(ServerPlayer player, String requestedType) {
        if (!GuildStore.isGuildMaster(player)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: големів наймає лише Гілдмайстер."), false);
            return;
        }
        String guildId = GuildStore.playerGuildId(player);
        if (guildId == null) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: спершу створи гільдію."), false);
            return;
        }
        boolean elite = String.valueOf(requestedType == null ? "" : requestedType).toLowerCase(Locale.ROOT).contains("elite");
        int current = elite ? GuildStore.countEliteGolems(guildId) : GuildStore.countOrdinaryGolems(guildId);
        int limit = elite ? GuildStore.maxEliteGolems(guildId) : GuildStore.maxOrdinaryGolems(guildId);
        if (limit <= 0 || current >= limit) {
            int level = GuildStore.guildLevel(guildId);
            player.displayClientMessage(Component.literal(elite
                    ? "Home Craft Guilds: елітний голем відкривається з 5 рівня. Ліміт — 1 на всю гільдію. Поточний рівень " + level + ", стан " + current + "/" + limit + "."
                    : "Home Craft Guilds: ліміт звичайних големів для рівня " + level + " — " + current + "/" + limit + ". Максимум 4 на гільдію."), false);
            return;
        }
        int cost = elite ? GuildStore.eliteGolemCost(guildId) : GuildStore.nextOrdinaryGolemCost(guildId);
        if (!takeEmeralds(player, cost)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: для найму цього голема потрібно " + cost + " смарагдів."), false);
            return;
        }
        String typeId = elite ? "minecraft:iron_golem" : normalizeGolemType(requestedType);
        EntityType<?> entityType = entityTypeById(typeId);
        if (entityType == null) {
            giveEmeralds(player, cost);
            player.displayClientMessage(Component.literal("Home Craft Guilds: цей тип голема недоступний у цій версії гри: " + requestedType), false);
            return;
        }

        // The purchase must reserve the guild slot even if the target guild territory is far away
        // or unloaded. We first resolve/cached a native safe point for a guild crystal, then spawn
        // immediately only when an actual guild member is currently on guild territory.
        GolemSpawn spawn = findGuildGolemSpawnNearActiveMember(guildId);
        if (spawn == null) spawn = findPreferredGuildSafeGolemSpawn(guildId, GuildStore.dimensionId(player.level()), true);
        if (spawn == null) {
            giveEmeralds(player, cost);
            player.displayClientMessage(Component.literal("Home Craft Guilds: для найму голема потрібен гільдійний кристал із безпечною точкою появи."), false);
            return;
        }

        if (isGuildMemberOnAnyGuildTerritory(guildId)) {
            Entity entity = spawnGuildGolemEntity(guildId, typeId, spawn, player.getYRot(), true, elite);
            if (entity == null) {
                giveEmeralds(player, cost);
                player.displayClientMessage(Component.literal("Home Craft Guilds: світ не прийняв голема."), false);
                return;
            }
            GuildStore.registerGolem(guildId, entity.getUUID().toString(), typeId, GuildStore.dimensionId(spawn.level), (int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z), elite);
            GuildGolemAppliedStats appliedStats = applyGuildGolemStats(entity, true, elite);
            GuildStore.updateGolemStaticStats(entity.getUUID().toString(), appliedStats.baseMaxHealth(), appliedStats.baseDamage(), elite);
            player.displayClientMessage(Component.literal(elite
                    ? "Home Craft Guilds: елітного голема найнято за " + cost + " смарагдів. Він з'явився на безпечній точці кристала."
                    : "Home Craft Guilds: голема найнято за " + cost + " смарагдів. Він з'явився на території гільдії."), false);
        } else {
            String pendingUuid = UUID.randomUUID().toString();
            GuildStore.registerPendingGolem(guildId, pendingUuid, typeId, GuildStore.dimensionId(spawn.level), (int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z), elite);
            player.displayClientMessage(Component.literal(elite
                    ? "Home Craft Guilds: елітного голема оплачено за " + cost + " смарагдів. Він з'явиться, коли учасник гільдії зайде на територію кристала."
                    : "Home Craft Guilds: голема оплачено за " + cost + " смарагдів. Він автоматично з'явиться, коли учасник гільдії зайде на територію."), false);
        }
        sendRoster(player);
    }

    private static Entity spawnGuildGolemEntity(String guildId, String typeId, GolemSpawn spawn, float yaw, boolean healToFull) {
        return spawnGuildGolemEntity(guildId, typeId, spawn, yaw, healToFull, false);
    }

    private static Entity spawnGuildGolemEntity(String guildId, String typeId, GolemSpawn spawn, float yaw, boolean healToFull, boolean elite) {
        if (spawn == null || spawn.level == null || guildId == null) return null;
        String normalizedType = normalizeGolemType(typeId);
        EntityType<?> entityType = entityTypeById(normalizedType);
        if (entityType == null) return null;
        Entity entity = createEntity(entityType, spawn.level);
        if (entity == null) return null;
        teleportEntity(entity, spawn.x, spawn.y, spawn.z);
        setEntityRotation(entity, yaw, 0.0F);
        String displayName = GuildStore.golemDisplayName(guildId, entity.getUUID().toString(), elite);
        applyGuildGolemIdentity(entity, guildId, normalizedType, elite, displayName);
        GuildGolemAppliedStats appliedStats = applyGuildGolemStats(entity, healToFull, elite);
        if (!spawn.level.addFreshEntity(entity)) return null;
        GuildStore.updateGolemStaticStats(entity.getUUID().toString(), appliedStats.baseMaxHealth(), appliedStats.baseDamage(), elite);
        return entity;
    }

    private record GolemSpawn(ServerLevel level, double x, double y, double z, GuildStore.Territory territory) {}
    private record GolemSafeSpawn(String guildId, String dimension, BlockPos pos, String territoryId) {}

    private static void rebuildGuildGolemSafeSpawnCache(String reason, int maxTerritories) {
        if (server == null) return;
        Map<String, List<GolemSafeSpawn>> rebuilt = new HashMap<>();
        int processed = 0;
        for (GuildStore.Territory territory : GuildStore.territoriesSnapshot()) {
            if (territory == null || !"GUILD".equals(territory.type) || territory.guildId == null || territory.guildId.isBlank()) continue;
            if (processed++ >= Math.max(16, maxTerritories)) break;
            ServerLevel level = levelForTerritory(null, territory);
            if (level == null) continue;
            BlockPos safe = resolveGuildCrystalSafeSpot(level, territory, true);
            if (safe == null) continue;
            rebuilt.computeIfAbsent(territory.guildId, k -> new ArrayList<>()).add(new GolemSafeSpawn(territory.guildId, GuildStore.dimensionId(level), safe, territory.id));
        }
        GUILD_GOLEM_SAFE_SPAWN_CACHE.clear();
        for (Map.Entry<String, List<GolemSafeSpawn>> entry : rebuilt.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                GUILD_GOLEM_SAFE_SPAWN_CACHE.put(entry.getKey(), entry.getValue());
            }
        }
        guildGolemSafeSpawnRevisionSeen = GuildStore.territoryTopologyRevision();
        if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: rebuilt {} guild golem safe spawn point group(s), reason={}", GUILD_GOLEM_SAFE_SPAWN_CACHE.size(), reason);
    }

    private static BlockPos resolveGuildCrystalSafeSpot(ServerLevel level, GuildStore.Territory territory, boolean forceLoad) {
        if (level == null || territory == null) return null;
        int[][] offsets = {
                {0,0},{2,0},{-2,0},{0,2},{0,-2},{3,1},{-3,1},{3,-1},{-3,-1},
                {1,3},{-1,3},{1,-3},{-1,-3},{4,0},{-4,0},{0,4},{0,-4},
                {5,2},{-5,2},{5,-2},{-5,-2},{2,5},{-2,5},{2,-5},{-2,-5}
        };
        for (int[] off : offsets) {
            int x = territory.x + off[0];
            int z = territory.z + off[1];
            if (x < GuildStore.minX(territory) || x > GuildStore.maxX(territory) || z < GuildStore.minZ(territory) || z > GuildStore.maxZ(territory)) continue;
            if (forceLoad) ensureChunkLoaded(level, x, z);
            else if (!isChunkLoaded(level, x, z)) continue;
            BlockPos pos = GuildGolemSafePositionResolver.safeSurface(level, x, z, territory.y, 4);
            if (pos == null || !GuildGolemSafePositionResolver.isWalkable(level, pos)) continue;
            GuildStore.Territory at = GuildStore.territoryAt(level, pos);
            if (at != null && "GUILD".equals(at.type) && Objects.equals(at.guildId, territory.guildId)) return pos;
        }
        return null;
    }

    private static GolemSpawn findPreferredGuildSafeGolemSpawn(String guildId, String preferredDimension, boolean forceRefresh) {
        if (guildId == null || guildId.isBlank()) return null;
        if (forceRefresh && guildGolemSafeSpawnRevisionSeen != GuildStore.territoryTopologyRevision()) rebuildGuildGolemSafeSpawnCache("preferred-spawn", 2048);
        List<GolemSafeSpawn> cached = GUILD_GOLEM_SAFE_SPAWN_CACHE.get(guildId);
        if (cached != null && !cached.isEmpty()) {
            GolemSafeSpawn chosen = null;
            for (GolemSafeSpawn safe : cached) {
                if (safe == null) continue;
                if (chosen == null) chosen = safe;
                if (preferredDimension != null && preferredDimension.equals(safe.dimension)) { chosen = safe; break; }
            }
            if (chosen != null) {
                ServerLevel level = levelForDimension(chosen.dimension);
                if (level != null && chosen.pos != null) {
                    ensureChunkLoaded(level, chosen.pos.getX(), chosen.pos.getZ());
                    GuildStore.Territory at = GuildStore.territoryAt(level, chosen.pos);
                    if (at != null && "GUILD".equals(at.type) && Objects.equals(at.guildId, guildId)) return new GolemSpawn(level, chosen.pos.getX() + 0.5D, chosen.pos.getY(), chosen.pos.getZ() + 0.5D, at);
                }
            }
        }
        return findRandomGuildGolemSpawn(guildId, preferredDimension);
    }

    private static GolemSpawn findGuildGolemSpawnNearActiveMember(String guildId) {
        if (server == null || guildId == null || guildId.isBlank()) return null;
        for (ServerPlayer member : server.getPlayerList().getPlayers()) {
            if (member == null || !GuildStore.isMemberOfGuild(member, guildId) || !(member.level() instanceof ServerLevel level)) continue;
            GuildStore.Territory territory = GuildStore.territoryAt(level, member.blockPosition());
            if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, guildId)) continue;
            BlockPos safe = resolveGuildCrystalSafeSpot(level, territory, true);
            if (safe != null) return new GolemSpawn(level, safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D, territory);
        }
        return null;
    }

    private static boolean isGuildMemberOnAnyGuildTerritory(String guildId) {
        if (server == null || guildId == null || guildId.isBlank()) return false;
        for (ServerPlayer member : server.getPlayerList().getPlayers()) {
            if (member == null || !GuildStore.isMemberOfGuild(member, guildId) || !(member.level() instanceof ServerLevel level)) continue;
            GuildStore.Territory territory = GuildStore.territoryAt(level, member.blockPosition());
            if (territory != null && "GUILD".equals(territory.type) && Objects.equals(territory.guildId, guildId)) return true;
        }
        return false;
    }

    private static void trySpawnPendingGolemsForJoinedMember(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        String guildId = GuildStore.playerGuildId(player);
        if (guildId == null || guildId.isBlank()) return;
        GuildStore.Territory territory = GuildStore.territoryAt(level, player.blockPosition());
        if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, guildId)) return;
        processPendingPurchasedGolemsForGuild(guildId, "member-join");
    }

    private static void processPendingPurchasedGolemSpawns(long tickStartNs, boolean force) {
        if (server == null) return;
        int processed = 0;
        for (GuildStore.Golem record : GuildStore.golemsSnapshot()) {
            if (record == null || record.removed || !record.respawnPending || !"SPAWN_PENDING".equals(record.status)) continue;
            if (!force && processed >= 4) break;
            if (!force && serverTickBudgetExceeded(tickStartNs)) break;
            processed++;
            spawnPendingPurchasedGolem(record, "tick");
        }
    }

    private static void processPendingPurchasedGolemsForGuild(String guildId, String reason) {
        if (guildId == null || guildId.isBlank()) return;
        int processed = 0;
        for (GuildStore.Golem record : GuildStore.golemsByGuildSnapshot(guildId)) {
            if (record == null || record.removed || !record.respawnPending || !"SPAWN_PENDING".equals(record.status)) continue;
            if (processed++ >= 4) break;
            spawnPendingPurchasedGolem(record, reason);
        }
    }

    private static boolean spawnPendingPurchasedGolem(GuildStore.Golem record, String reason) {
        if (record == null || record.guildId == null || !record.respawnPending || !"SPAWN_PENDING".equals(record.status)) return false;
        Entity existing = null;
        try { existing = findEntity(UUID.fromString(record.uuid)); } catch (Exception ignored) { }
        if (existing != null && existing.isAlive()) {
            GuildStore.markGolemSeen(record.uuid, GuildStore.dimensionId(existing.level()), existing.getBlockX(), existing.getBlockY(), existing.getBlockZ());
            return true;
        }
        GolemSpawn spawn = findGuildGolemSpawnNearActiveMember(record.guildId);
        if (spawn == null) return false;
        Entity entity = spawnGuildGolemEntity(record.guildId, record.type, spawn, 0.0F, true, record.elite);
        if (entity == null) return false;
        String newUuid = entity.getUUID().toString();
        GuildStore.markGolemAlive(record.uuid, newUuid, GuildStore.dimensionId(spawn.level), (int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z));
        GuildGolemAppliedStats appliedStats = applyGuildGolemStats(entity, true, record.elite);
        GuildStore.updateGolemStaticStats(newUuid, appliedStats.baseMaxHealth(), appliedStats.baseDamage(), record.elite);
        if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: spawned pending purchased golem {} for guild {} reason={}", newUuid, record.guildId, reason);
        return true;
    }


    private static GolemSpawn findRandomGuildGolemSpawn(ServerPlayer player, String guildId) {
        if (player == null || guildId == null) return null;
        List<GuildStore.Territory> territories = GuildStore.guildTerritories(guildId);
        if (territories.isEmpty()) return null;

        String playerDimension = GuildStore.dimensionId(player.level());
        List<GuildStore.Territory> sameDimension = new ArrayList<>();
        for (GuildStore.Territory territory : territories) {
            if (territory != null && Objects.equals(territory.dimension, playerDimension)) sameDimension.add(territory);
        }
        List<GuildStore.Territory> candidates = sameDimension.isEmpty() ? territories : sameDimension;

        int candidateCount = Math.max(1, candidates.size());
        for (int attempt = 0; attempt < Math.max(32, candidateCount * 12); attempt++) {
            GuildStore.Territory territory = candidates.get(player.getRandom().nextInt(candidateCount));
            ServerLevel level = levelForTerritory(player, territory);
            if (level == null) continue;
            int minX = GuildStore.minX(territory);
            int maxX = GuildStore.maxX(territory);
            int minZ = GuildStore.minZ(territory);
            int maxZ = GuildStore.maxZ(territory);
            int x = minX + player.getRandom().nextInt(Math.max(1, maxX - minX + 1));
            int z = minZ + player.getRandom().nextInt(Math.max(1, maxZ - minZ + 1));
            BlockPos pos = safeSurfaceBlockPos(level, x, z);
            if (pos == null) continue;
            GuildStore.Territory at = GuildStore.territoryAt(level, pos);
            if (at == null || !"GUILD".equals(at.type) || !Objects.equals(at.guildId, guildId)) continue;
            return new GolemSpawn(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, at);
        }

        for (GuildStore.Territory territory : candidates) {
            ServerLevel level = levelForTerritory(player, territory);
            if (level == null) continue;
            BlockPos pos = safeSurfaceBlockPos(level, territory.x, territory.z);
            if (pos == null) continue;
            GuildStore.Territory at = GuildStore.territoryAt(level, pos);
            if (at != null && "GUILD".equals(at.type) && Objects.equals(at.guildId, guildId)) {
                return new GolemSpawn(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, at);
            }
        }
        return null;
    }

    private static GolemSpawn findRandomGuildGolemSpawn(String guildId, String preferredDimension) {
        if (guildId == null) return null;
        List<GuildStore.Territory> territories = GuildStore.guildTerritories(guildId);
        if (territories.isEmpty()) return null;
        List<GuildStore.Territory> candidates = new ArrayList<>();
        if (preferredDimension != null && !preferredDimension.isBlank()) {
            for (GuildStore.Territory territory : territories) {
                if (territory != null && Objects.equals(territory.dimension, preferredDimension)) candidates.add(territory);
            }
        }
        if (candidates.isEmpty()) candidates.addAll(territories);
        int candidateCount = Math.max(1, candidates.size());
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int attempt = 0; attempt < Math.max(32, candidateCount * 12); attempt++) {
            GuildStore.Territory territory = candidates.get(random.nextInt(candidateCount));
            GolemSpawn spawn = randomPointInTerritory(territory, random);
            if (spawn != null) return spawn;
        }
        for (GuildStore.Territory territory : candidates) {
            ServerLevel level = levelForTerritory(null, territory);
            if (level == null) continue;
            BlockPos pos = safeSurfaceBlockPos(level, territory.x, territory.z);
            if (pos == null) continue;
            GuildStore.Territory at = GuildStore.territoryAt(level, pos);
            if (at != null && "GUILD".equals(at.type) && Objects.equals(at.guildId, guildId)) return new GolemSpawn(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, at);
        }
        return null;
    }

    private static GolemSpawn findRandomPointInTerritory(GuildStore.Territory territory) {
        return randomPointInTerritory(territory, java.util.concurrent.ThreadLocalRandom.current());
    }

    private static GolemSpawn randomPointInTerritory(GuildStore.Territory territory, java.util.concurrent.ThreadLocalRandom random) {
        if (territory == null || random == null) return null;
        ServerLevel level = levelForTerritory(null, territory);
        if (level == null) return null;
        int minX = GuildStore.minX(territory);
        int maxX = GuildStore.maxX(territory);
        int minZ = GuildStore.minZ(territory);
        int maxZ = GuildStore.maxZ(territory);
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = minX + random.nextInt(Math.max(1, maxX - minX + 1));
            int z = minZ + random.nextInt(Math.max(1, maxZ - minZ + 1));
            BlockPos pos = safeSurfaceBlockPos(level, x, z);
            if (pos == null) continue;
            GuildStore.Territory at = GuildStore.territoryAt(level, pos);
            if (at != null && "GUILD".equals(at.type) && Objects.equals(at.guildId, territory.guildId)) return new GolemSpawn(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, at);
        }
        return null;
    }

    private static ServerLevel levelForTerritory(ServerPlayer player, GuildStore.Territory territory) {
        if (territory == null) return null;
        if (player != null && player.level() instanceof ServerLevel playerLevel && Objects.equals(GuildStore.dimensionId(playerLevel), territory.dimension)) {
            return playerLevel;
        }
        MinecraftServer srv = server;
        if (srv == null && player != null && player.level() instanceof ServerLevel fallbackLevel) srv = fallbackLevel.getServer();
        if (srv == null) return null;
        try {
            Identifier dimensionId = Identifier.parse(territory.dimension == null || territory.dimension.isBlank() ? "minecraft:overworld" : territory.dimension);
            return srv.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static GuildGolemAppliedStats applyGuildGolemStats(Entity entity, boolean healToFull) {
        return applyGuildGolemStats(entity, healToFull, isEliteGuildGolem(entity));
    }

    private static GuildGolemAppliedStats applyGuildGolemStats(Entity entity, boolean healToFull, boolean elite) {
        double baseMaxHealthValue = 0.0D;
        double effectiveMaxHealthValue = 0.0D;
        double baseDamageValue = -1.0D;
        double effectiveDamageValue = 0.0D;
        if (!(entity instanceof LivingEntity living)) return new GuildGolemAppliedStats(baseMaxHealthValue, effectiveMaxHealthValue, baseDamageValue, effectiveDamageValue);
        AttributeInstance maxHealth = living.getAttribute(Attributes.MAX_HEALTH);
        String guildId = guildIdFromGuildGolem(entity);
        GuildStore.Golem record = GuildStore.golem(entity.getUUID().toString());
        if ((guildId == null || guildId.isBlank()) && record != null) guildId = record.guildId;
        int talentHealthBonus = GuildStore.guildGolemTalentHealthBonusPercent(guildId);
        int talentDamageBonus = GuildStore.guildGolemTalentDamageBonusPercent(guildId);
        int talentSpeedBonus = GuildStore.guildGolemTalentSpeedBonusPercent(guildId);
        if (maxHealth != null) {
            normalizePossiblyStackedBase(entity, Attributes.MAX_HEALTH, maxHealth);
            baseMaxHealthValue = Math.max(1.0D, maxHealth.getBaseValue());
            float oldMax = Math.max(1.0F, living.getMaxHealth());
            float oldHealth = Math.max(0.0F, living.getHealth());
            maxHealth.removeModifier(GUILD_GOLEM_HEALTH_MODIFIER_ID);
            double finalHealthMultiplier = GuildGolemStats.healthMultiplier(elite) * (1.0D + talentHealthBonus / 100.0D);
            double healthBonus = Math.max(0.0D, finalHealthMultiplier - 1.0D);
            maxHealth.addOrUpdateTransientModifier(new AttributeModifier(GUILD_GOLEM_HEALTH_MODIFIER_ID, healthBonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            effectiveMaxHealthValue = living.getMaxHealth();
            if (healToFull) living.setHealth(living.getMaxHealth());
            else if (Math.abs(oldMax - living.getMaxHealth()) > 0.05F) living.setHealth(Math.max(1.0F, Math.min(living.getMaxHealth(), living.getMaxHealth() * Math.max(0.0F, Math.min(1.0F, oldHealth / oldMax)))));
            else if (living.getHealth() > living.getMaxHealth()) living.setHealth(living.getMaxHealth());
        }
        AttributeInstance attackDamage = living.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage != null) {
            normalizePossiblyStackedBase(entity, Attributes.ATTACK_DAMAGE, attackDamage);
            baseDamageValue = Math.max(0.0D, attackDamage.getBaseValue());
            attackDamage.removeModifier(GUILD_GOLEM_ATTACK_MODIFIER_ID);
            double finalDamageMultiplier = GuildGolemStats.damageMultiplier(elite) * (1.0D + talentDamageBonus / 100.0D);
            double damageBonus = Math.max(0.0D, finalDamageMultiplier - 1.0D);
            attackDamage.addOrUpdateTransientModifier(new AttributeModifier(GUILD_GOLEM_ATTACK_MODIFIER_ID, damageBonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            effectiveDamageValue = baseDamageValue * finalDamageMultiplier;
        }
        AttributeInstance movementSpeed = living.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movementSpeed != null) {
            movementSpeed.removeModifier(GUILD_GOLEM_SPEED_MODIFIER_ID);
            if (talentSpeedBonus > 0) movementSpeed.addOrUpdateTransientModifier(new AttributeModifier(GUILD_GOLEM_SPEED_MODIFIER_ID, talentSpeedBonus / 100.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        try {
            AttributeInstance scale = living.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.removeModifier(GUILD_ELITE_GOLEM_SCALE_MODIFIER_ID);
                if (elite) scale.addOrUpdateTransientModifier(new AttributeModifier(GUILD_ELITE_GOLEM_SCALE_MODIFIER_ID, 0.20D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        } catch (Throwable ignored) {}
        return new GuildGolemAppliedStats(baseMaxHealthValue, effectiveMaxHealthValue, baseDamageValue, effectiveDamageValue);
    }

    private record GuildGolemAppliedStats(double baseMaxHealth, double effectiveMaxHealth, double baseDamage, double effectiveDamage) {}

    private static void normalizePossiblyStackedBase(Entity entity, Holder<Attribute> attribute, AttributeInstance instance) {
        double defaultBase = defaultAttributeBase(entity, attribute, instance.getBaseValue());
        if (defaultBase <= 0.0D) return;
        double currentBase = instance.getBaseValue();
        // Older builds multiplied the attribute base itself. If such an entity is loaded after restart,
        // reset only clearly stacked guild values before applying the stable modifier-based bonus.
        if (currentBase > defaultBase * 1.75D) instance.setBaseValue(defaultBase);
    }

    private static double defaultAttributeBase(Entity entity, Holder<Attribute> attribute, double fallback) {
        if (entity == null || attribute == null || !(entity.level() instanceof ServerLevel level)) return fallback;
        String key = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())) + "|" + String.valueOf(attribute);
        Double cached = DEFAULT_ATTRIBUTE_BASE_CACHE.get(key);
        if (cached != null) return cached;
        double value = fallback;
        try {
            Entity probe = createEntity(entity.getType(), level);
            if (probe instanceof LivingEntity living) {
                AttributeInstance instance = living.getAttribute(attribute);
                if (instance != null) value = instance.getBaseValue();
            }
            if (probe != null) probe.discard();
        } catch (Exception ignored) {}
        DEFAULT_ATTRIBUTE_BASE_CACHE.put(key, value);
        return value;
    }

    private static void deleteGolem(ServerPlayer player, String golemUuid) {
        if (!GuildStore.isGuildMaster(player)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: видаляти големів може лише Гілдмайстер."), false);
            sendRoster(player);
            return;
        }
        String guildId = GuildStore.playerGuildId(player);
        String requestedUuid = golemUuid == null ? "" : golemUuid.trim();
        GuildStore.Golem record = GuildStore.golem(requestedUuid);
        if (guildId == null || record == null || !Objects.equals(record.guildId, guildId)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: голема не знайдено у твоїй гільдії."), false);
            sendRoster(player);
            return;
        }

        String uuid = record.uuid;
        Entity entity = null;
        UUID parsedUuid = parseUuid(uuid);
        if (parsedUuid != null) entity = findEntity(parsedUuid);
        if (entity != null) {
            try { entity.discard(); } catch (Throwable ignored) {}
            GOLEM_ENTITY_LOOKUP_CACHE.remove(parsedUuid);
        }

        cleanupGolemRuntimeState(uuid);
        PENDING_GOLEM_REVIVES.remove(uuid);
        boolean removed = GuildStore.deleteGolemFromGuild(guildId, uuid);
        if (removed) {
            GOLEM_ROSTER_CACHE.remove(guildId);
            player.displayClientMessage(Component.literal("Home Craft Guilds: голема повністю видалено зі списку гільдії."), false);
        } else {
            player.displayClientMessage(Component.literal("Home Craft Guilds: голема не вдалося видалити."), false);
        }
        sendRoster(player);
    }


    private static void teleportGolemToNearestTotem(ServerPlayer player, String golemUuid) {
        if (!GuildStore.isGuildMaster(player)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: керувати големами може лише Гілдмайстер."), false);
            sendRoster(player);
            return;
        }
        String guildId = GuildStore.playerGuildId(player);
        GuildStore.Golem record = GuildStore.golem(golemUuid);
        if (guildId == null || record == null || !Objects.equals(record.guildId, guildId)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: голема не знайдено у твоїй гільдії."), false);
            sendRoster(player);
            return;
        }
        if (record.dead) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: цей голем загинув. Спочатку відроди його."), false);
            sendRoster(player);
            return;
        }
        Entity entity = null;
        try { entity = findEntity(UUID.fromString(record.uuid)); } catch (Exception ignored) {}
        if (entity == null || !entity.isAlive()) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: голема зараз не знайдено у завантажених чанках."), false);
            sendRoster(player);
            return;
        }
        clearGuildGolemRuntimeBeforeManualTeleport(record, entity);
        boolean teleported = forceTeleportGolemToNearestGuildTotem(record, entity, "manual-button");
        if (teleported) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: голема миттєво перенесено до найближчого тотема гільдії. Старий маршрут, ціль і stuck-стан повністю скинуто."), false);
        } else {
            player.displayClientMessage(Component.literal("Home Craft Guilds: не вдалося знайти безпечну точку біля найближчого тотема для голема."), false);
        }
        sendRoster(player);
    }

    private static void reviveGolem(ServerPlayer player, String golemUuid) {
        if (!GuildStore.isGuildMaster(player)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: відроджувати големів може лише Гілдмайстер."), false);
            sendRoster(player);
            return;
        }
        String guildId = GuildStore.playerGuildId(player);
        GuildStore.Golem record = GuildStore.golem(golemUuid);
        if (guildId == null || record == null || !Objects.equals(record.guildId, guildId)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: голема не знайдено у твоїй гільдії."), false);
            sendRoster(player);
            return;
        }
        if (!record.dead) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: цей голем ще живий."), false);
            sendRoster(player);
            return;
        }
        if (PENDING_GOLEM_REVIVES.containsKey(record.uuid)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: відродження цього голема вже триває."), false);
            sendRoster(player);
            return;
        }
        if (!hasItems(player, Items.EMERALD, GOLEM_REVIVE_EMERALD_COST) || !hasItems(player, Items.IRON_INGOT, GOLEM_REVIVE_IRON_COST)) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: для відродження потрібно 3 смарагди та 10 залізних злитків."), false);
            sendRoster(player);
            return;
        }

        GuildStore.Territory nearest = nearestGuildTotem(record.guildId, record.dimension, record.x, record.y, record.z);
        if (nearest == null) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: немає активного тотема для відродження."), false);
            sendRoster(player);
            return;
        }
        ServerLevel level = levelForTerritory(player, nearest);
        if (level == null) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: вимір тотема недоступний."), false);
            sendRoster(player);
            return;
        }
        BlockPos spawnPos = findSafeAdjacentTotemSpot(level, nearest);
        if (spawnPos == null) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: не знайдено безпечну точку для відродження біля тотема. Ресурси не списано."), false);
            sendRoster(player);
            return;
        }

        record.respawnPending = true;
        record.status = "RESPAWN_PENDING";
        record.aiState = record.elite ? "ELITE_DEAD" : "NORMAL_DEAD";
        GuildStore.touchGolemRuntime(record.uuid);

        takeItems(player, Items.EMERALD, GOLEM_REVIVE_EMERALD_COST);
        takeItems(player, Items.IRON_INGOT, GOLEM_REVIVE_IRON_COST);
        PENDING_GOLEM_REVIVES.put(record.uuid, new PendingGolemRevive(record.uuid, record.guildId, record.type, record.elite, GuildStore.dimensionId(level), spawnPos, player.getUUID().toString(), GOLEM_REVIVE_TICKS));
        playGolemReviveSound(player, spawnPos, true);
        player.displayClientMessage(Component.literal("Home Craft Guilds: відродження голема почалось. Завершення через 5 секунд."), false);
        sendRoster(player);
    }

    private static void setRankByName(ServerPlayer actor, String name, String rankName) {
        GuildRank rank = GuildRank.assignableFrom(rankName);
        boolean ok = GuildStore.setRank(actor, name, rank);
        actor.displayClientMessage(Component.literal(ok ? "Home Craft Guilds: роль змінено на " + rank.label + "." : "Home Craft Guilds: роль не змінено. Ролі видає лише Гілдмайстер. Доступні: " + GuildRank.assignableLabels()), false);
        sendRoster(actor);
    }

    private static void inviteByName(ServerPlayer actor, String name) {
        ServerPlayer target = findOnlinePlayer(name);
        boolean ok = GuildStore.invite(actor, target);
        actor.displayClientMessage(Component.literal(ok ? "Home Craft Guilds: запрошення відправлено." : "Home Craft Guilds: не вдалося запросити. Запрошувати може лише Гілдмайстер, ціль має бути онлайн, без гільдії, і в гільдії має бути місце до ліміту " + HomeCraftGuildConfig.maxGuildMembers() + "."), false);
        if (ok && target != null) {
            target.displayClientMessage(Component.literal("Home Craft Guilds: тебе запросили до гільдії. Натисни G або /homecraftguild accept."), false);
            sendRoster(target);
        }
    }

    private static void kickByName(ServerPlayer actor, String name) {
        boolean ok = GuildStore.kick(actor, name);
        actor.displayClientMessage(Component.literal(ok ? "Home Craft Guilds: гравця прибрано з гільдії." : "Home Craft Guilds: не вдалося прибрати гравця."), false);
        sendRoster(actor);
    }

    private static void sendRoster(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new OpenGuildRosterPayload(GuildStore.snapshotFor(player)));
    }

    private static void enforceGuildGolems(boolean runAi) {
        enforceGuildGolems(runAi, 0L);
    }

    private static boolean shouldContinueExistingGolemRoute(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (record == null || entity == null || territory == null || !(entity instanceof Mob mob)) return false;
        if (record.elite && record.followGuildmasterUuid != null && !record.followGuildmasterUuid.isBlank()) return false;
        if (currentGolemTarget(mob) != null) return false;
        if (activeCombatAlert(record.guildId, entity) != null) return false;
        if (mob.getNavigation().isDone()) return false;
        GolemTaskState state = GOLEM_TASK_STATE.get(record.uuid);
        if (state == null || !("PATROL".equals(state.task) || "RESUME".equals(state.task) || "SLEEP".equals(state.task))) return false;
        long now = entity.level().getGameTime();
        long minTicks = dynamicExistingRouteMinTicks();
        if (!isGolemGovernorHardRescue() && isGuildHotForAi(record, entity, territory, now)) {
            // Hot territories should still look alive: keep the predictive fast-path, but
            // force coverage refresh often enough that golems visibly patrol the whole claim.
            minTicks = Math.min(minTicks, 20L * 28L);
        }
        if (now - state.tick >= minTicks) return false;
        // Predictive hot-path: while Minecraft navigation is already following a valid route, do not
        // rebuild target scans/patrol context every AI slice. This keeps golems moving naturally, but
        // removes the worst server-thread cost when thousands of hot golems already have orders.
        return true;
    }

    private static long dynamicExistingRouteMinTicks() {
        if (isGolemGovernorHardRescue()) return GOLEM_EXISTING_ROUTE_MIN_TICKS_RESCUE;
        if (isGolemGovernorThrottled()) return GOLEM_EXISTING_ROUTE_MIN_TICKS_EXTREME;
        if (isUltraGolemServerMode()) return GOLEM_EXISTING_ROUTE_MIN_TICKS_ULTRA;
        if (isExtremeGolemServerMode()) return GOLEM_EXISTING_ROUTE_MIN_TICKS_EXTREME;
        if (isMassiveGolemServerMode()) return GOLEM_EXISTING_ROUTE_MIN_TICKS_MASSIVE;
        return GOLEM_EXISTING_ROUTE_MIN_TICKS_NORMAL;
    }

    private static boolean shouldRunFullGolemAi(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (record == null || entity == null || territory == null) return true;
        if (record.elite && record.followGuildmasterUuid != null && !record.followGuildmasterUuid.isBlank()) return true;
        if (entity instanceof Mob mob && currentGolemTarget(mob) != null) return true;
        if (activeCombatAlert(record.guildId, entity) != null) return true;
        long now = entity.level().getGameTime();
        if (isGuildHotForAi(record, entity, territory, now)) return true;
        int heartbeat = dynamicColdGolemHeartbeatTicks();
        int hash = Math.abs(record.uuid == null ? 0 : record.uuid.hashCode());
        return heartbeat <= 1 || ((now + hash) % heartbeat) == 0L;
    }

    private static int dynamicColdGolemHeartbeatTicks() {
        if (isGolemGovernorHardRescue()) return GOLEM_COLD_IDLE_HEARTBEAT_TICKS_RESCUE;
        if (isGolemGovernorThrottled()) return GOLEM_COLD_IDLE_HEARTBEAT_TICKS_MASSIVE;
        if (isUltraGolemServerMode() || isExtremeGolemServerMode() || isMassiveGolemServerMode()) return GOLEM_COLD_IDLE_HEARTBEAT_TICKS_MASSIVE;
        return GOLEM_COLD_IDLE_HEARTBEAT_TICKS_NORMAL;
    }

    private static boolean shouldUseObserverLodSleep(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (record == null || entity == null || territory == null) return false;
        if (record.elite && record.followGuildmasterUuid != null && !record.followGuildmasterUuid.isBlank()) return false;
        if (entity instanceof Mob mob && currentGolemTarget(mob) != null) return false;
        if (activeCombatAlert(record.guildId, entity) != null) return false;
        long now = entity.level().getGameTime();
        if (isGuildHotForAi(record, entity, territory, now)) return false;
        return !isGolemObservedByAnyPlayer(entity, GOLEM_PLAYER_VISIBILITY_RADIUS);
    }

    private static boolean isGolemObservedByAnyPlayer(Entity entity, int radius) {
        if (entity == null || entity.level() == null) return false;
        double max = (double)Math.max(1, radius) * (double)Math.max(1, radius);
        for (ServerPlayer player : playersNearPoint(entity.level(), entity.getX(), entity.getZ(), Math.max(1, radius))) {
            if (player != null && player.level() == entity.level() && player.distanceToSqr(entity) <= max) return true;
        }
        return false;
    }

    private static boolean isGuildHotForAi(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, long now) {
        if (server == null || record == null || record.guildId == null || entity == null || territory == null) return true;
        String dim = GuildStore.dimensionId(entity.level());
        String key = record.guildId + "|" + dim;
        Long hotUntil = GOLEM_GUILD_HOT_UNTIL_TICK.get(key);
        if (hotUntil != null && hotUntil > now) return true;
        Long lastCheck = GOLEM_GUILD_HOT_CHECK_TICK.get(key);
        int checkInterval = isUltraGolemServerMode() ? GOLEM_HOT_GUILD_CHECK_TICKS_ULTRA : (isExtremeGolemServerMode() ? GOLEM_HOT_GUILD_CHECK_TICKS_EXTREME : (isMassiveGolemServerMode() ? GOLEM_HOT_GUILD_CHECK_TICKS_MASSIVE : GOLEM_HOT_GUILD_CHECK_TICKS_NORMAL));
        if (isGolemGovernorThrottled()) checkInterval *= 2;
        if (lastCheck != null && now - lastCheck < checkInterval) return false;
        GOLEM_GUILD_HOT_CHECK_TICK.put(key, now);
        int radius = isMassiveGolemServerMode() || isGolemGovernorThrottled() ? GOLEM_HOT_GUILD_RADIUS_SCALE : GOLEM_HOT_GUILD_RADIUS_NORMAL;
        double radiusSqr = (double) radius * (double) radius;
        double guildMemberRadiusSqr = radiusSqr * 2.25D;
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, territory, dim);
        if (cluster.isEmpty()) cluster = List.of(territory);
        GuildAwarenessSnapshot awareness = guildAwarenessSnapshot(record.guildId, entity.level(), cluster, radius);
        if (awareness.memberCount > 0 || awareness.otherPlayerCount > 0 || awareness.hostileCount > 0) {
            GOLEM_GUILD_HOT_UNTIL_TICK.put(key, now + GOLEM_HOT_GUILD_TTL_TICKS);
            return true;
        }
        for (GuildStore.Territory t : cluster) {
            for (ServerPlayer player : playersNearTerritory(entity.level(), t, Math.max(radius, (int)Math.ceil(Math.sqrt(guildMemberRadiusSqr))))) {
                if (player == null || player.level() != entity.level()) continue;
                double dist = distanceToTerritorySqr(player, t);
                boolean sameGuildNear = Objects.equals(record.guildId, GuildStore.playerGuildId(player)) && dist <= guildMemberRadiusSqr;
                boolean anyPlayerNear = dist <= radiusSqr;
                if (sameGuildNear || anyPlayerNear) {
                    GOLEM_GUILD_HOT_UNTIL_TICK.put(key, now + GOLEM_HOT_GUILD_TTL_TICKS);
                    return true;
                }
            }
        }
        return false;
    }

    private static PlayerHeatSnapshot playerHeatSnapshot() {
        if (GOLEM_PLAYER_HEAT_CACHE != null && GOLEM_PLAYER_HEAT_CACHE.frame == GOLEM_AI_FRAME) return GOLEM_PLAYER_HEAT_CACHE;
        Map<String, List<ServerPlayer>> byCell = new HashMap<>();
        if (server != null && server.getPlayerList() != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null || player.level() == null) continue;
                String key = playerHeatCellKey(GuildStore.dimensionId(player.level()), playerHeatCell(player.getX()), playerHeatCell(player.getZ()));
                byCell.computeIfAbsent(key, ignored -> new ArrayList<>()).add(player);
            }
        }
        GOLEM_PLAYER_HEAT_CACHE = new PlayerHeatSnapshot(GOLEM_AI_FRAME, byCell);
        return GOLEM_PLAYER_HEAT_CACHE;
    }

    private static int playerHeatCell(double value) {
        return Math.floorDiv((int)Math.floor(value), GOLEM_PLAYER_HEAT_CELL_SIZE);
    }

    private static String playerHeatCellKey(String dimension, int cellX, int cellZ) {
        return String.valueOf(dimension == null ? "" : dimension) + ":" + cellX + ":" + cellZ;
    }

    private static Iterable<ServerPlayer> playersNearTerritory(LevelAccessor level, GuildStore.Territory territory, int radius) {
        if (level == null || territory == null) return Collections.emptyList();
        PlayerHeatSnapshot snapshot = playerHeatSnapshot();
        if (snapshot.playersByCell.isEmpty()) return Collections.emptyList();
        String dim = GuildStore.dimensionId(level);
        int minCellX = playerHeatCell(GuildStore.minX(territory) - Math.max(0, radius));
        int maxCellX = playerHeatCell(GuildStore.maxX(territory) + Math.max(0, radius));
        int minCellZ = playerHeatCell(GuildStore.minZ(territory) - Math.max(0, radius));
        int maxCellZ = playerHeatCell(GuildStore.maxZ(territory) + Math.max(0, radius));
        List<ServerPlayer> out = new ArrayList<>();
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                List<ServerPlayer> list = snapshot.playersByCell.get(playerHeatCellKey(dim, cx, cz));
                if (list != null && !list.isEmpty()) out.addAll(list);
            }
        }
        return out;
    }

    private static Iterable<ServerPlayer> playersNearPoint(LevelAccessor level, double x, double z, int radius) {
        if (level == null) return Collections.emptyList();
        PlayerHeatSnapshot snapshot = playerHeatSnapshot();
        if (snapshot.playersByCell.isEmpty()) return Collections.emptyList();
        String dim = GuildStore.dimensionId(level);
        int minCellX = playerHeatCell(x - Math.max(0, radius));
        int maxCellX = playerHeatCell(x + Math.max(0, radius));
        int minCellZ = playerHeatCell(z - Math.max(0, radius));
        int maxCellZ = playerHeatCell(z + Math.max(0, radius));
        List<ServerPlayer> out = new ArrayList<>();
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                List<ServerPlayer> list = snapshot.playersByCell.get(playerHeatCellKey(dim, cx, cz));
                if (list != null && !list.isEmpty()) out.addAll(list);
            }
        }
        return out;
    }

    private static double distanceToTerritorySqr(Entity entity, GuildStore.Territory territory) {
        if (entity == null || territory == null) return Double.MAX_VALUE;
        double dx = 0.0D;
        if (entity.getX() < GuildStore.minX(territory)) dx = GuildStore.minX(territory) - entity.getX();
        else if (entity.getX() > GuildStore.maxX(territory)) dx = entity.getX() - GuildStore.maxX(territory);
        double dz = 0.0D;
        if (entity.getZ() < GuildStore.minZ(territory)) dz = GuildStore.minZ(territory) - entity.getZ();
        else if (entity.getZ() > GuildStore.maxZ(territory)) dz = entity.getZ() - GuildStore.maxZ(territory);
        return dx * dx + dz * dz;
    }

    private static void maintainSleepingGolem(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (record == null || entity == null || territory == null) return;
        markGolemTask(record, entity, "SLEEP", null, territory.id);
        // Observer LOD: if nobody can see this golem and there is no alert/target, keep it visually
        // alive only by vanilla idle animation and do not spend native pathfinding CPU.
        if (entity instanceof Mob mob && !isGolemObservedByAnyPlayer(entity, GOLEM_PLAYER_VISIBILITY_RADIUS)) {
            try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
            clearGuildGolemAggro(mob);
        }
        // Sleeping golems are reactivated by player proximity, combat alerts, target lock, manual
        // teleport, guildmaster-follow logic, or rare staggered heartbeat patrol.
    }

    private static void enforceGuildGolems(boolean runAi, long tickStartNs) {
        if (server == null) return;
        List<GuildStore.Golem> golems = GuildStore.golemsSnapshot();
        int total = golems.size();
        if (total <= 0) return;
        int budget = golemProcessingBudget(total, runAi);
        int start = runAi ? golemAiScanCursor : golemSafetyScanCursor;
        int processed = 0;
        for (int n = 0; n < total && processed < budget; n++) {
            if (serverTickBudgetExceeded(tickStartNs)) break;
            int index = Math.floorMod(start + n, total);
            GuildStore.Golem record = golems.get(index);
            processed++;
            if (record == null || record.uuid == null || record.guildId == null) continue;
            if (!GuildStore.guildExists(record.guildId)) {
                GuildStore.removeGolem(record.uuid);
                continue;
            }

            UUID uuid = parseUuid(record.uuid);
            if (uuid == null) {
                GuildStore.removeGolem(record.uuid);
                continue;
            }

            Entity entity = findEntity(uuid);
            if (record.dead) {
                if (entity != null) entity.discard();
                continue;
            }
            if (entity == null || !entity.isAlive()) {
                if (!isGolemGovernorHardRescue()) recoverMissingGuildGolem(record);
                continue;
            }

            if (isGolemInVoidOrInvalidY(entity)) {
                returnGolemToNearestGuildTotem(record, entity);
                resetGolemStuckState(record.uuid, entity);
                continue;
            }

            ensureGuildGolemIdentity(record, entity);
            if (shouldSyncGuildGolemStats(record, entity, runAi)) {
                GuildGolemAppliedStats appliedStats = applyGuildGolemStats(entity, false, record.elite);
                GuildStore.updateGolemStaticStats(record.uuid, appliedStats.baseMaxHealth(), appliedStats.baseDamage(), record.elite);
            } else {
                GuildStore.ensureGolemStaticStats(record.uuid, record.elite);
            }
            if (runAi && record.elite && !isGolemGovernorThrottled() && isGolemObservedByAnyPlayer(entity, GOLEM_PLAYER_VISIBILITY_RADIUS)) spawnEliteGolemParticles(entity);
            maybeMarkGolemSeen(record, entity, runAi);

            if (GuildGolemBrain.tick(new GuildGolemBrain.Context(server, golemGovernorLagLevel, GOLEM_AI_FRAME), record, entity, runAi)) {
                continue;
            }

            if (runAi && record.elite && entity.level() instanceof ServerLevel eliteLevel && isNightTime(eliteLevel)) {
                ServerPlayer guildmaster = findGuildmasterOnGuildTerritory(record.guildId, eliteLevel);
                if (guildmaster != null && handleEliteGolemLeaderGuard(record, entity, guildmaster, true, true)) continue;
            }

            if (record.elite && record.followGuildmasterUuid != null && !record.followGuildmasterUuid.isBlank()) {
                if (handleEliteGolemFollow(record, entity, runAi)) continue;
            }

            GuildStore.Territory territory = GuildStore.territoryAt(entity.level(), entity.blockPosition());
            if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(record.guildId, territory.guildId)) {
                // Головне правило Patrol 2.0: кінцева точка маршруту завжди всередині гільдії,
                // але Minecraft navigation інколи мусить вивести голема на кілька блоків за межу,
                // щоб обійти воду, стіну, яму або складний рельєф. Такий вихід дозволений тільки
                // як короткий detour до вже запланованої внутрішньої точки.
                if (runAi && isAllowedRouteDetourOutsideGuildTerritory(record, entity)) {
                    continue;
                }
                if (runAi && handleAllowedOutsideGuildTerritory(record, entity)) {
                    continue;
                }
                returnGolemToNearestGuildTotem(record, entity);
                clearInvalidGuildGolemTarget(record.guildId, entity, null);
                resetGolemStuckState(record.uuid, entity);
                continue;
            }

            regenerateGolemOnGuildTerritory(record, entity, territory);

            if (runAi && shouldReturnStuckGolem(record, entity)) {
                if (tryRecoverStuckGolem(record, entity, territory)) {
                    continue;
                }
                if (!shouldForceTeleportAfterRecovery(record, entity)) {
                    continue;
                }
                returnGolemToNearestGuildTotem(record, entity, "stuck-fallback");
                clearInvalidGuildGolemTarget(record.guildId, entity, null);
                resetGolemStuckState(record.uuid, entity);
                continue;
            }

            clearInvalidGuildGolemTarget(record.guildId, entity, territory);
            if (!runAi) continue;

            if (shouldContinueExistingGolemRoute(record, entity, territory)) {
                continue;
            }

            if (shouldUseObserverLodSleep(record, entity, territory)) {
                maintainSleepingGolem(record, entity, territory);
                continue;
            }

            if (!shouldRunFullGolemAi(record, entity, territory)) {
                maintainSleepingGolem(record, entity, territory);
                continue;
            }

            if (entity instanceof Mob golemMob) {
                LivingEntity lockedTarget = currentGolemTarget(golemMob);
                if (shouldKeepCurrentGolemTarget(record, entity, lockedTarget, territory)) {
                    steerGolemToTarget(record, entity, lockedTarget, true);
                    continue;
                }
            }

            GuildCombatAlert alert = activeCombatAlert(record.guildId, entity);
            if (alert != null && handleGolemCombatAlert(record, entity, alert)) continue;

            resumeGolemAfterInterruptIfNeeded(record, entity, territory);
            if (handleGolemDaytimeRecovery(record, entity, territory)) continue;

            LivingEntity target = findGolemTarget(record, entity, territory);
            if (target != null) steerGolemToTarget(record, entity, target, false);
            else patrolGuildTerritory(record, entity, territory);
        }
        if (runAi) golemAiScanCursor = total <= 0 ? 0 : Math.floorMod(start + processed, total);
        else golemSafetyScanCursor = total <= 0 ? 0 : Math.floorMod(start + processed, total);
    }


    private static boolean handleGolemDaytimeRecovery(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (record == null || entity == null || record.dead) return false;
        if (!(entity instanceof Mob mob) || !(entity instanceof LivingEntity living)) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (isNightTime(level)) return false;

        LivingEntity lockedTarget = currentGolemTarget(mob);

        // Daytime recovery is the highest-priority golem behavior below 50% HP.
        // It must interrupt combat/alerts and use the boosted guild max HP, not vanilla 100 HP.
        GuildGolemAppliedStats appliedStats = applyGuildGolemStats(entity, false, record.elite);
        GuildStore.updateGolemStaticStats(record.uuid, appliedStats.baseMaxHealth(), appliedStats.baseDamage(), record.elite);
        float maxHealth = living.getMaxHealth();
        if (maxHealth <= 0.0F) return false;
        double ratio = living.getHealth() / Math.max(1.0D, maxHealth);
        GolemTaskState task = record.uuid == null ? null : GOLEM_TASK_STATE.get(record.uuid);
        boolean alreadyRecovering = task != null && "RECOVERY".equals(task.task);

        if (ratio >= GOLEM_DAY_RECOVERY_STOP_HEALTH_RATIO) return false;
        if (!alreadyRecovering && ratio > GOLEM_DAY_RECOVERY_START_HEALTH_RATIO) return false;

        GuildStore.Territory nearest = nearestGuildTotem(record.guildId, GuildStore.dimensionId(level), entity.getX(), entity.getY(), entity.getZ());
        if (nearest == null) nearest = territory;
        if (nearest == null) return false;

        if (lockedTarget != null) clearGuildGolemAggro(mob);
        GOLEM_TARGET_CACHE.remove(record.uuid);
        GOLEM_ROUTE_RESERVATIONS.remove(record.uuid);

        double tx = nearest.x + 0.5D;
        double ty = nearest.y + 1.0D;
        double tz = nearest.z + 0.5D;
        double distSqr = distanceSqr(entity.getX(), entity.getY(), entity.getZ(), tx, ty, tz);
        long now = level.getGameTime();

        if (distSqr <= GOLEM_DAY_RECOVERY_CHARGE_RADIUS * GOLEM_DAY_RECOVERY_CHARGE_RADIUS) {
            try { mob.getNavigation().stop(); } catch (Exception ignored) {}
            clearEntityVelocity(entity);
            int healingTalent = GuildStore.guildGolemTalentHealingSpeedBonusPercent(record.guildId);
            float heal = (float)Math.max(GOLEM_DAY_RECOVERY_MIN_HEAL_PER_AI_SLICE, maxHealth * GOLEM_DAY_RECOVERY_HEAL_FRACTION_PER_AI_SLICE);
            if (healingTalent > 0) heal *= (1.0F + healingTalent / 100.0F);
            living.heal(heal);
            if (living.getHealth() >= maxHealth - 0.05F) living.setHealth(maxHealth);
            markGolemTask(record, entity, "RECOVERY", new GolemSpawn(level, tx, ty, tz, nearest), null);
            return true;
        }

        int hash = Math.abs(String.valueOf(record.uuid == null ? entity.getUUID() : record.uuid).hashCode());
        if (!alreadyRecovering || mob.getNavigation().isDone() || (now + hash) % 12L == 0L) {
            GolemSpawn chargePoint = findSafeGolemReturnSpawn(level, nearest, entity.getX(), entity.getZ());
            if (chargePoint == null) chargePoint = new GolemSpawn(level, tx, ty, tz, nearest);
            if (!moveGolemToPoint(record, mob, chargePoint, record.elite ? 0.92D : 0.82D, false)) {
                setWantedMovePosition(mob, tx, ty, tz, record.elite ? 0.92D : 0.82D);
            }
            markGolemTask(record, entity, "RECOVERY", chargePoint, null);
        }
        return true;
    }


    private static boolean handleGolemCombatAlert(GuildStore.Golem record, Entity entity, GuildCombatAlert alert) {
        if (record == null || entity == null || alert == null) return false;
        if (record.elite && record.followGuildmasterUuid != null && !record.followGuildmasterUuid.isBlank()) return false;
        if (!(entity instanceof Mob mob)) return false;

        LivingEntity target = alertTarget(alert, entity);
        if (target != null && !isGolemTargetAllowedByLeash(record, entity, target, true)) target = null;
        if (!shouldGolemJoinAlert(record, entity, alert, target)) return false;
        markGolemTask(record, entity, "ALERT", null, target == null ? alert.attackerUuid : target.getUUID().toString());
        if (target != null && target.isAlive()) {
            steerGolemToTarget(record, entity, target, false);
        }

        double x = target != null ? target.getX() : alert.x;
        double y = target != null ? target.getY() : alert.y;
        double z = target != null ? target.getZ() : alert.z;
        maybePlayGolemAlertChatter(record, entity, target);
        double speed = record.elite ? 1.06D : 0.92D;
        double distToAlert = distanceSqr(entity.getX(), entity.getY(), entity.getZ(), x, y, z);

        boolean refreshPath = mob.getNavigation().isDone()
                || distToAlert > 10.0D * 10.0D
                || (target != null && entity.distanceToSqr(target) > 4.0D * 4.0D);
        if (refreshPath) {
            GolemSpawn approach = target == null ? null : smartTargetApproachPoint(record, entity, target);
            if (approach != null) {
                moveGolemToPoint(record, mob, approach, speed, false);
            } else if (entity.level() instanceof ServerLevel level) {
                BlockPos pos = safeSurfaceBlockPos(level, (int)Math.floor(x), (int)Math.floor(z));
                if (pos != null) moveGolemToPoint(record, mob, new GolemSpawn(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, GuildStore.territoryAt(level, pos)), speed, false);
                else if (target == null || isEntityInsideGolemGuildCluster(record, entity, target)) { try { mob.getNavigation().moveTo(x, y, z, speed); } catch (Exception ignored) {} }
            } else if (target == null || isEntityInsideGolemGuildCluster(record, entity, target)) {
                try { mob.getNavigation().moveTo(x, y, z, speed); } catch (Exception ignored) {}
            }
        }

        // Бойовий сигнал активний незалежно від дистанції.
        // Якщо голем застряг, окрема stuck-логіка поверне його до тотема,
        // після чого цей самий сигнал знову потягне його до бою.
        return true;
    }

    private static void maybePlayGolemAlertChatter(GuildStore.Golem record, Entity entity, LivingEntity target) {
        if (record == null || entity == null || !(entity.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        long last = GOLEM_ALERT_CHATTER_TICK.getOrDefault(record.uuid, 0L);
        long cooldown = record.elite ? 80L : 140L;
        if (now - last < cooldown) return;
        boolean nearMember = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level && GuildStore.isMemberOfGuild(player, record.guildId) && player.distanceToSqr(entity) <= 24.0D * 24.0D) {
                nearMember = true;
                break;
            }
        }
        if (!nearMember && Math.floorMod(String.valueOf(record.uuid).hashCode() + (int)now, 5) != 0) return;
        GOLEM_ALERT_CHATTER_TICK.put(record.uuid, now);
        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.IRON_GOLEM_HURT, SoundSource.NEUTRAL, record.elite ? 0.92F : 0.68F, record.elite ? 0.78F : 0.92F);
        if (!nearMember || Math.floorMod((int)now + String.valueOf(record.uuid).hashCode(), 4) != 0) return;
        String name = record.name == null || record.name.isBlank() ? (record.elite ? "РІК" : "Голем") : record.name;
        String[] lines = record.elite
                ? new String[]{"Тривога. Тримаю главу й периметр.", "Висока загроза. Приймаю ціль.", "РІК на зв'язку. Перехоплюю."}
                : new String[]{"Тривога на території.", "Патруль зміщено до загрози.", "Бачу ціль. Тримаю сектор."};
        String line = lines[Math.floorMod((int)(now / 20L) + String.valueOf(record.uuid).hashCode(), lines.length)];
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level && GuildStore.isMemberOfGuild(player, record.guildId) && player.distanceToSqr(entity) <= 28.0D * 28.0D) {
                player.displayClientMessage(Component.literal(name + ": " + line), true);
            }
        }
    }

    private static boolean shouldKeepCurrentGolemTarget(GuildStore.Golem record, Entity golem, LivingEntity target, GuildStore.Territory territory) {
        if (record == null || golem == null || target == null || !target.isAlive()) return false;
        if (isGuildGolem(target)) return false;
        if (target instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, record.guildId)) return false;
        if (shouldIgnoreUndergroundGolemTarget(target)) return false;
        if (!isGolemTargetAllowedByLeash(record, golem, target, true)) return false;

        boolean hostileMob = target.getType().getCategory() == MobCategory.MONSTER;
        boolean enemyPlayer = target instanceof ServerPlayer player && !GuildStore.isMemberOfGuild(player, record.guildId)
                && (isNearGuildMember(record.guildId, player) || isEmergencyOutsideCombatTarget(record, golem, target, activeCombatAlert(record.guildId, golem)));
        if (!hostileMob && !enemyPlayer) return false;
        if (!shouldGolemJoinThreat(record, golem, target, false)) return false;
        double protectRange = HomeCraftGuildConfig.golemProtectRange();
        double multiplier = isHighAlertThreat(target) ? 1.65D : (isAerialOrElevatedThreat(target) ? 1.35D : 1.0D);
        double maxSqr = protectRange * protectRange * multiplier;
        if (golem.distanceToSqr(target) > maxSqr && !isEmergencyOutsideCombatTarget(record, golem, target, activeCombatAlert(record.guildId, golem))) return false;
        return true;
    }

    private static boolean isGolemTargetAllowedByLeash(GuildStore.Golem record, Entity golem, LivingEntity target, boolean allowEmergency) {
        if (record == null || golem == null || target == null || target.level() != golem.level()) return false;
        GuildStore.Territory base = GuildStore.territoryAt(golem.level(), golem.blockPosition());
        if (base == null || !"GUILD".equals(base.type) || !Objects.equals(base.guildId, record.guildId)) {
            base = nearestGuildTotem(record.guildId, GuildStore.dimensionId(golem.level()), golem.getX(), golem.getY(), golem.getZ());
        }
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, base, GuildStore.dimensionId(golem.level()));
        if (cluster.isEmpty() && base != null) cluster = List.of(base);
        if (!cluster.isEmpty() && isInsideCluster(cluster, target.blockPosition())) return true;
        return allowEmergency && isEmergencyOutsideCombatTarget(record, golem, target, activeCombatAlert(record.guildId, golem));
    }

    private static boolean isEntityInsideGolemGuildCluster(GuildStore.Golem record, Entity golem, Entity target) {
        if (record == null || golem == null || target == null || target.level() != golem.level()) return false;
        GuildStore.Territory base = GuildStore.territoryAt(golem.level(), golem.blockPosition());
        if (base == null || !"GUILD".equals(base.type) || !Objects.equals(base.guildId, record.guildId)) {
            base = nearestGuildTotem(record.guildId, GuildStore.dimensionId(golem.level()), golem.getX(), golem.getY(), golem.getZ());
        }
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, base, GuildStore.dimensionId(golem.level()));
        if (cluster.isEmpty() && base != null) cluster = List.of(base);
        return !cluster.isEmpty() && isInsideCluster(cluster, target.blockPosition());
    }

    private static void steerGolemToTarget(GuildStore.Golem record, Entity golem, LivingEntity target, boolean lightRefresh) {
        if (!(golem instanceof Mob mob) || target == null || !target.isAlive()) return;
        if (shouldIgnoreUndergroundGolemTarget(target)) {
            clearGuildGolemAggro(mob);
            return;
        }
        if (record != null && !isGolemTargetAllowedByLeash(record, golem, target, true)) {
            clearGuildGolemAggro(mob);
            markGolemTask(record, golem, "PATROL", null, null);
            return;
        }
        if (record != null && !shouldGolemJoinThreat(record, golem, target, true)) {
            clearGuildGolemAggro(mob);
            markGolemTask(record, golem, "PATROL", null, null);
            return;
        }
        rememberGolemThreatAssignment(record, golem, target);
        markGolemTask(record, golem, "COMBAT", null, target.getUUID().toString());
        boolean targetInsideLeash = record == null || isEntityInsideGolemGuildCluster(record, golem, target);
        if (targetInsideLeash) setMobTarget(golem, target);
        else clearGuildGolemAggro(mob);
        double dist = golem.distanceToSqr(target);
        long tick = golem.level().getGameTime();
        int hash = Math.abs(String.valueOf(record == null ? golem.getUUID() : record.uuid).hashCode());
        long refresh = lightRefresh ? 18L + (hash % 13) : 8L + (hash % 7);
        boolean scheduled = (tick + hash) % refresh == 0L;
        boolean urgent = isHighAlertThreat(target) || isAerialOrElevatedThreat(target) || dist > (lightRefresh ? 7.0D * 7.0D : 4.0D * 4.0D);
        if (!mob.getNavigation().isDone() && !scheduled && !urgent && !shouldRefreshChaseBecauseBlocked(record, mob, target)) return;

        double speed = (record != null && record.elite) ? 1.08D : 0.92D;
        if (isHighAlertThreat(target)) speed += 0.10D;
        if (isAerialOrElevatedThreat(target)) speed += 0.05D;
        speed = Math.min(1.22D, speed);

        GolemSpawn approach = smartTargetApproachPoint(record, golem, target);
        if (approach != null) {
            moveGolemToPoint(record, mob, approach, speed, true);
            rememberTargetRoute(record, target, approach);
            if (isAerialOrElevatedThreat(target) && dist <= 30.0D * 30.0D) {
                setWantedMovePosition(mob, approach.x, approach.y, approach.z, Math.min(1.12D, speed));
            }
            return;
        }

        if (record != null && !isEntityInsideGolemGuildCluster(record, golem, target)) {
            // Emergency outside targets are allowed only as a short defense leash. If no safe
            // in-territory approach point exists, do not path deeper outside the guild border.
            clearGuildGolemAggro(mob);
            return;
        }
        try {
            mob.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speed);
        } catch (Exception ignored) {
            setWantedMovePosition(mob, target.getX(), target.getY(), target.getZ(), speed);
        }
    }

    private static boolean shouldRefreshChaseBecauseBlocked(GuildStore.Golem record, Mob mob, LivingEntity target) {
        if (record == null || mob == null || target == null) return false;
        GolemNavigationMemory memory = GOLEM_NAV_MEMORY.get(record.uuid);
        if (memory == null) return false;
        long now = mob.level().getGameTime();
        if (now - memory.lastRepathTick < GOLEM_CHASE_REPATH_MIN_TICKS) return false;
        if (memory.blockedChecks >= GOLEM_SOFT_REROUTE_BLOCKED_CHECKS) return true;
        GolemTargetCache cached = GOLEM_TARGET_CACHE.get(record.uuid);
        if (cached == null || now - cached.tick > 40L) return false;
        String targetId = target.getUUID() == null ? "" : target.getUUID().toString();
        if (!Objects.equals(cached.targetUuid, targetId)) return true;
        double drift = distanceSqr(target.getX(), target.getY(), target.getZ(), cached.targetX, cached.targetY, cached.targetZ);
        return drift > 5.0D * 5.0D;
    }

    private static void rememberTargetRoute(GuildStore.Golem record, LivingEntity target, GolemSpawn approach) {
        if (record == null || target == null || approach == null) return;
        long now = target.level().getGameTime();
        GOLEM_TARGET_CACHE.put(record.uuid, new GolemTargetCache(GOLEM_AI_FRAME, now, target.getUUID() == null ? "" : target.getUUID().toString(), target.getX(), target.getY(), target.getZ(), approach.x, approach.y, approach.z));
    }

    private static boolean handleAllowedOutsideGuildTerritory(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null || record.dead) return false;
        if (record.elite && record.followGuildmasterUuid != null && !record.followGuildmasterUuid.isBlank()) return false;
        if (!(entity instanceof Mob mob)) return false;

        GuildCombatAlert alert = activeCombatAlert(record.guildId, entity);
        LivingEntity target = alert == null ? null : alertTarget(alert, entity);
        if (!isEmergencyOutsideCombatTarget(record, entity, target, alert)) {
            clearGuildGolemAggro(mob);
            return false;
        }

        steerGolemToTarget(record, entity, target, false);
        return true;
    }

    private static boolean isEmergencyOutsideCombatTarget(GuildStore.Golem record, Entity golem, LivingEntity target, GuildCombatAlert alert) {
        if (record == null || golem == null || target == null || alert == null || !target.isAlive()) return false;
        if (target.level() != golem.level()) return false;
        if (!Objects.equals(alert.guildId, record.guildId)) return false;
        if (isGuildGolem(target)) return false;
        if (target instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, record.guildId)) return false;
        if (!(target.getType().getCategory() == MobCategory.MONSTER || target instanceof ServerPlayer)) return false;
        if (shouldIgnoreUndergroundGolemTarget(target)) return false;
        double alertRadius = GOLEM_EMERGENCY_ALERT_TARGET_RADIUS * GOLEM_EMERGENCY_ALERT_TARGET_RADIUS;
        if (distanceSqr(target.getX(), target.getY(), target.getZ(), alert.x, alert.y, alert.z) > alertRadius) return false;
        GuildStore.Territory nearest = nearestGuildTotem(record.guildId, GuildStore.dimensionId(golem.level()), alert.x, alert.y, alert.z);
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, nearest, GuildStore.dimensionId(golem.level()));
        if (cluster.isEmpty() && nearest != null) cluster = List.of(nearest);
        if (cluster.isEmpty()) return false;
        if (!isInsideClusterBuffer(cluster, target.blockPosition(), GOLEM_EMERGENCY_OUTSIDE_CHASE_BUFFER_BLOCKS)) return false;
        if (!isInsideClusterBuffer(cluster, golem.blockPosition(), GOLEM_EMERGENCY_OUTSIDE_CHASE_BUFFER_BLOCKS)) return false;
        return true;
    }

    private static LivingEntity currentGolemTarget(Mob mob) {
        if (mob == null) return null;
        try {
            return mob.getTarget();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isGolemInVoidOrInvalidY(Entity entity) {
        if (entity == null) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        int minY = minBuildHeight(level);
        return entity.getY() < minY + 2.0D || entity.getY() < -96.0D;
    }

    private static List<GuildStore.Territory> contiguousGuildCluster(String guildId, GuildStore.Territory start, String dimension) {
        if (guildId == null || guildId.isBlank() || dimension == null) return Collections.emptyList();
        String key = guildId + "|" + dimension + "|" + (start == null ? "*" : String.valueOf(start.id));
        ClusterCache cached = GOLEM_CLUSTER_CACHE.get(key);
        if (cached != null && cached.frame == GOLEM_AI_FRAME) return cached.cluster;
        List<GuildStore.Territory> cluster = buildContiguousGuildCluster(guildId, start, dimension);
        GOLEM_CLUSTER_CACHE.put(key, new ClusterCache(GOLEM_AI_FRAME, cluster));
        return cluster;
    }

    private static List<GuildStore.Territory> buildContiguousGuildCluster(String guildId, GuildStore.Territory start, String dimension) {
        // AI leash: golems defend only a physically connected guild network. A second totem
        // belongs to the same movement cluster only when the boundary gap is <= 2 blocks.
        List<GuildStore.Territory> all = GuildStore.guildTerritories(guildId);
        List<GuildStore.Territory> sameDimension = new ArrayList<>();
        for (GuildStore.Territory territory : all) {
            if (territory != null && Objects.equals(territory.dimension, dimension) && "GUILD".equals(territory.type)) sameDimension.add(territory);
        }
        sameDimension.sort(Comparator.comparing(t -> String.valueOf(t.id)));
        if (sameDimension.isEmpty() || start == null) return Collections.emptyList();

        GuildStore.Territory root = null;
        for (GuildStore.Territory territory : sameDimension) {
            if (Objects.equals(territory.id, start.id)) { root = territory; break; }
        }
        if (root == null) root = start;

        List<GuildStore.Territory> cluster = new ArrayList<>();
        ArrayDeque<GuildStore.Territory> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            GuildStore.Territory current = queue.removeFirst();
            if (containsTerritory(cluster, current)) continue;
            cluster.add(current);
            for (GuildStore.Territory candidate : sameDimension) {
                if (candidate == null || containsTerritory(cluster, candidate)) continue;
                if (guildTerritoriesTraversable(current, candidate, GOLEM_CONNECTED_TERRITORY_MAX_BOUNDARY_GAP)) queue.add(candidate);
            }
        }
        cluster.sort(Comparator.comparing(t -> String.valueOf(t.id)));
        return cluster;
    }

    private static boolean guildTerritoriesTraversable(GuildStore.Territory a, GuildStore.Territory b, int maxGap) {
        if (a == null || b == null) return false;
        if (!Objects.equals(a.guildId, b.guildId) || !Objects.equals(a.dimension, b.dimension)) return false;
        int dx = separatedAxisGap(GuildStore.minX(a), GuildStore.maxX(a), GuildStore.minX(b), GuildStore.maxX(b));
        int dz = separatedAxisGap(GuildStore.minZ(a), GuildStore.maxZ(a), GuildStore.minZ(b), GuildStore.maxZ(b));
        return dx <= Math.max(0, maxGap) && dz <= Math.max(0, maxGap);
    }

    private static int separatedAxisGap(int aMin, int aMax, int bMin, int bMax) {
        if (aMax < bMin) return Math.max(0, bMin - aMax - 1);
        if (bMax < aMin) return Math.max(0, aMin - bMax - 1);
        return 0;
    }

    private static boolean containsTerritory(List<GuildStore.Territory> list, GuildStore.Territory territory) {
        if (list == null || territory == null) return false;
        for (GuildStore.Territory existing : list) {
            if (existing != null && Objects.equals(existing.id, territory.id)) return true;
        }
        return false;
    }

    private static boolean territoriesTouchOrOverlap(GuildStore.Territory a, GuildStore.Territory b) {
        if (a == null || b == null) return false;
        if (!Objects.equals(a.guildId, b.guildId) || !Objects.equals(a.dimension, b.dimension)) return false;
        int gap = 1;
        return GuildStore.minX(a) <= GuildStore.maxX(b) + gap
                && GuildStore.maxX(a) + gap >= GuildStore.minX(b)
                && GuildStore.minZ(a) <= GuildStore.maxZ(b) + gap
                && GuildStore.maxZ(a) + gap >= GuildStore.minZ(b);
    }

    private static boolean isInsideConnectedGuildBuffer(String guildId, Entity entity, int buffer) {
        if (guildId == null || entity == null) return false;
        String dimension = GuildStore.dimensionId(entity.level());
        BlockPos pos = entity.blockPosition();
        for (GuildStore.Territory territory : GuildStore.guildTerritories(guildId)) {
            if (territory == null || !Objects.equals(territory.dimension, dimension)) continue;
            if (pos.getX() >= GuildStore.minX(territory) - buffer && pos.getX() <= GuildStore.maxX(territory) + buffer
                    && pos.getZ() >= GuildStore.minZ(territory) - buffer && pos.getZ() <= GuildStore.maxZ(territory) + buffer) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideCluster(List<GuildStore.Territory> cluster, BlockPos pos) {
        if (cluster == null || pos == null) return false;
        for (GuildStore.Territory territory : cluster) if (GuildStore.isInsideTerritory(territory, pos)) return true;
        return false;
    }

    private static boolean isAllowedRouteDetourOutsideGuildTerritory(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null || !(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) return false;
        if (mob.getNavigation().isDone()) return false;
        if (isGolemPhysicallyTrapped(entity) || isGolemInVoidOrInvalidY(entity)) return false;
        BlockPos currentPos = entity.blockPosition();
        if (isNearDeepDrop(level, currentPos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT + 2)) return false;
        if (hasWaterNear(level, currentPos, GOLEM_PATROL_WATER_AVOID_RADIUS) && !isGuildMemberInImmediateDangerNearWater(record.guildId, level, currentPos)) return false;

        GuildStore.Territory nearest = nearestGuildTotem(record.guildId, GuildStore.dimensionId(level), entity.getX(), entity.getY(), entity.getZ());
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, nearest, GuildStore.dimensionId(level));
        if (cluster.isEmpty() && nearest != null) cluster = List.of(nearest);
        if (cluster.isEmpty()) return false;
        if (!isInsideClusterBuffer(cluster, currentPos, GOLEM_ROUTE_DETOUR_OUTSIDE_BUFFER_BLOCKS)) return false;

        GolemNavigationMemory nav = GOLEM_NAV_MEMORY.get(record.uuid);
        if (nav == null) return false;
        BlockPos planned = new BlockPos((int)Math.floor(nav.lastTargetX), (int)Math.floor(nav.lastTargetY), (int)Math.floor(nav.lastTargetZ));
        if (!isInsideCluster(cluster, planned)) return false;
        if (!isGolemWalkableSpot(level, planned)) return false;
        return true;
    }

    private static boolean isInsideClusterBuffer(List<GuildStore.Territory> cluster, BlockPos pos, int buffer) {
        if (cluster == null || pos == null) return false;
        int b = Math.max(0, buffer);
        for (GuildStore.Territory territory : cluster) {
            if (territory == null) continue;
            if (pos.getX() >= GuildStore.minX(territory) - b && pos.getX() <= GuildStore.maxX(territory) + b
                    && pos.getZ() >= GuildStore.minZ(territory) - b && pos.getZ() <= GuildStore.maxZ(territory) + b) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGuildMemberInImmediateDangerNearWater(String guildId, ServerLevel level, BlockPos pos) {
        if (guildId == null || level == null || pos == null || server == null) return false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || player.level() != level || !player.isAlive()) continue;
            if (!GuildStore.isMemberOfGuild(player, guildId)) continue;
            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) > 18.0D * 18.0D) continue;
            if (!hasWaterNear(level, player.blockPosition(), GOLEM_PATROL_WATER_AVOID_RADIUS + 2)) continue;
            if (nearbyHostileMobs(player, level) > 0 || player.getHealth() < player.getMaxHealth() * 0.70F) return true;
        }
        return false;
    }

    private static GuildStore.Territory territoryAtCluster(List<GuildStore.Territory> cluster, int x, int z) {
        if (cluster == null) return null;
        BlockPos pos = new BlockPos(x, 0, z);
        for (GuildStore.Territory territory : cluster) {
            if (territory == null) continue;
            if (x >= GuildStore.minX(territory) && x <= GuildStore.maxX(territory)
                    && z >= GuildStore.minZ(territory) && z <= GuildStore.maxZ(territory)) {
                return territory;
            }
        }
        return null;
    }

    private static ClusterBounds clusterBounds(List<GuildStore.Territory> cluster) {
        if (cluster == null || cluster.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (GuildStore.Territory territory : cluster) {
            if (territory == null) continue;
            minX = Math.min(minX, GuildStore.minX(territory));
            minZ = Math.min(minZ, GuildStore.minZ(territory));
            maxX = Math.max(maxX, GuildStore.maxX(territory));
            maxZ = Math.max(maxZ, GuildStore.maxZ(territory));
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new ClusterBounds(minX, minZ, maxX, maxZ);
    }

    private static int stableGuildGolemIndex(GuildStore.Golem record) {
        if (record == null || record.guildId == null) return 0;
        GolemRosterCache cache = golemRosterCache(record.guildId);
        Integer index = cache.indexByUuid.get(record.uuid);
        if (index != null) return index;
        return Math.floorMod(String.valueOf(record.uuid).hashCode(), Math.max(1, cache.activeCount));
    }

    private static int activeGuildGolemCount(String guildId) {
        return Math.max(1, golemRosterCache(guildId).activeCount);
    }

    private static GolemRosterCache golemRosterCache(String guildId) {
        if (guildId == null || guildId.isBlank()) return GolemRosterCache.EMPTY;
        GolemRosterCache cached = GOLEM_ROSTER_CACHE.get(guildId);
        if (cached != null && cached.frame == GOLEM_AI_FRAME) return cached;
        List<GuildStore.Golem> golems = GuildStore.golemsByGuildSnapshot(guildId);
        golems.removeIf(g -> g == null || g.dead);
        golems.sort(Comparator.comparing(g -> String.valueOf(g.uuid)));
        Map<String, Integer> indexByUuid = new HashMap<>();
        for (int i = 0; i < golems.size(); i++) {
            GuildStore.Golem golem = golems.get(i);
            if (golem != null && golem.uuid != null) indexByUuid.put(golem.uuid, i);
        }
        GolemRosterCache cache = new GolemRosterCache(GOLEM_AI_FRAME, Math.max(1, golems.size()), indexByUuid);
        GOLEM_ROSTER_CACHE.put(guildId, cache);
        return cache;
    }

    private static int interpolate(int min, int max, double t) {
        if (max <= min) return min;
        return min + (int)Math.round((max - min) * Math.max(0.0D, Math.min(1.0D, t)));
    }

    private static int golemsNearTerritory(String guildId, GuildStore.Territory territory, LevelAccessor level) {
        return territoryActivityStats(guildId, territory, level).golems;
    }

    private static int nearbyGuildGolems(Entity target, String guildId, LevelAccessor level) {
        int count = 0;
        if (target == null) return 0;
        if (isMassiveGolemServerMode()) {
            return activeResponderAssignments(guildId, target.getUUID().toString(), target.level().getGameTime(), null);
        }
        for (Entity entity : entitiesNear(target, 16.0D, dynamicNearbyGolemScanBudget())) {
            if (entity == null || entity == target || !entity.isAlive() || !isGuildGolem(entity)) continue;
            if (!Objects.equals(guildIdFromGuildGolem(entity), guildId)) continue;
            if (entity.distanceToSqr(target) <= 14.0D * 14.0D) count++;
        }
        return count;
    }

    private static void rememberPatrolDestination(GuildStore.Golem record, GolemSpawn destination) {
        if (record == null || destination == null) return;
        GOLEM_PATROL_MEMORY.put(record.uuid, new GolemPatrolMemory(destination.x, destination.y, destination.z, destination.territory == null ? "" : destination.territory.id, destination.level.getGameTime()));
    }

    private static boolean isForcedTeleportOnCooldown(String uuid, Entity entity) {
        if (uuid == null || uuid.isBlank() || entity == null) return false;
        Long last = GOLEM_LAST_FORCED_TELEPORT_TICK.get(uuid);
        return last != null && entity.level().getGameTime() - last < GOLEM_FORCED_TELEPORT_COOLDOWN_TICKS;
    }

    private static void rememberForcedTeleport(String uuid, Entity entity) {
        if (uuid == null || uuid.isBlank() || entity == null) return;
        GOLEM_LAST_FORCED_TELEPORT_TICK.put(uuid, entity.level().getGameTime());
    }

    private static boolean shouldReturnStuckGolem(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null) return false;
        if (isGolemInVoidOrInvalidY(entity)) return true;
        if (!(entity instanceof Mob mob)) return false;
        String key = record.uuid;
        if (key == null || key.isBlank()) return false;

        long now = entity.level().getGameTime();
        boolean physicallyTrapped = isGolemPhysicallyTrapped(entity);
        boolean unevenTrap = entity.level() instanceof ServerLevel stuckLevel && isGolemInUnevenFallTrap(stuckLevel, entity.blockPosition());
        Long teleportedAt = GOLEM_POST_TELEPORT_TICK.get(key);
        boolean postTeleportGrace = teleportedAt != null && now - teleportedAt <= GOLEM_POST_TELEPORT_GRACE_TICKS;
        if (postTeleportGrace && !physicallyTrapped && !unevenTrap) {
            resetGolemStuckState(key, entity);
            return false;
        }

        GolemStuckState state = GOLEM_STUCK.computeIfAbsent(key, ignored -> new GolemStuckState(entity.getX(), entity.getY(), entity.getZ()));
        double moved = distanceSqr(entity.getX(), entity.getY(), entity.getZ(), state.x, state.y, state.z);
        boolean hasPath = !mob.getNavigation().isDone();
        GuildStore.Territory nearest = nearestGuildTotem(record.guildId, GuildStore.dimensionId(entity.level()), entity.getX(), entity.getY(), entity.getZ());
        boolean highOnStructure = nearest != null && entity.getY() - nearest.y >= 11.0D;
        boolean noProgress = hasPath && moved < GOLEM_STUCK_MIN_MOVED_SQR;
        boolean hardBlocked = physicallyTrapped || unevenTrap || isHorizontallyBlocked(entity);

        if (isForcedTeleportOnCooldown(record.uuid, entity) && !hardBlocked && !highOnStructure) return false;

        GolemNavigationMemory nav = GOLEM_NAV_MEMORY.computeIfAbsent(key, ignored -> new GolemNavigationMemory(entity.getX(), entity.getY(), entity.getZ(), now));
        if (noProgress || highOnStructure || hardBlocked) {
            state.stuckChecks += hardBlocked ? 2 : 1;
            nav.blockedChecks += hardBlocked ? 2 : 1;
        } else {
            state.stuckChecks = 0;
            nav.blockedChecks = 0;
            state.x = entity.getX();
            state.y = entity.getY();
            state.z = entity.getZ();
            nav.lastProgressX = entity.getX();
            nav.lastProgressY = entity.getY();
            nav.lastProgressZ = entity.getZ();
            nav.lastProgressTick = now;
        }

        int required = highOnStructure ? GOLEM_STUCK_CHECKS_BEFORE_RETURN + 2 : GOLEM_STUCK_CHECKS_BEFORE_RETURN;
        if (physicallyTrapped) required = Math.max(2, GOLEM_SOFT_REROUTE_BLOCKED_CHECKS);
        return state.stuckChecks >= required;
    }

    private static boolean shouldForceTeleportAfterRecovery(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null) return false;
        if (isGolemInVoidOrInvalidY(entity)) return true;
        GolemNavigationMemory nav = GOLEM_NAV_MEMORY.get(record.uuid);
        GolemStuckState state = GOLEM_STUCK.get(record.uuid);
        int navBlocked = nav == null ? 0 : nav.blockedChecks;
        int stuckChecks = state == null ? 0 : state.stuckChecks;
        int recoveryAttempts = state == null ? 0 : state.recoveryAttempts;
        boolean physicallyTrapped = isGolemPhysicallyTrapped(entity);
        boolean unevenTrap = entity.level() instanceof ServerLevel level && isGolemInUnevenFallTrap(level, entity.blockPosition());
        int requiredAttempts = (physicallyTrapped || unevenTrap) ? GOLEM_TRAPPED_RECOVERY_ATTEMPTS_BEFORE_TELEPORT : GOLEM_RECOVERY_ATTEMPTS_BEFORE_TELEPORT;
        if (recoveryAttempts < requiredAttempts) return false;
        if ((physicallyTrapped || unevenTrap) && navBlocked >= Math.max(6, GOLEM_SOFT_REROUTE_BLOCKED_CHECKS + 2)) return true;
        return navBlocked >= GOLEM_HARD_RETURN_BLOCKED_CHECKS || stuckChecks >= GOLEM_STUCK_CHECKS_BEFORE_RETURN + GOLEM_HARD_RETURN_BLOCKED_CHECKS;
    }

    private static void resetGolemStuckState(String uuid, Entity entity) {
        if (uuid == null || uuid.isBlank() || entity == null) return;
        GolemStuckState state = GOLEM_STUCK.computeIfAbsent(uuid, ignored -> new GolemStuckState(entity.getX(), entity.getY(), entity.getZ()));
        state.x = entity.getX();
        state.y = entity.getY();
        state.z = entity.getZ();
        state.stuckChecks = 0;
        state.recoveryAttempts = 0;
        state.lastRecoveryTick = 0L;
        GolemNavigationMemory nav = GOLEM_NAV_MEMORY.get(uuid);
        if (nav != null) {
            nav.blockedChecks = 0;
            nav.lastProgressX = entity.getX();
            nav.lastProgressY = entity.getY();
            nav.lastProgressZ = entity.getZ();
            nav.lastProgressTick = entity.level().getGameTime();
        }
    }

    private static void regenerateGolemOnGuildTerritory(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (record == null || entity == null || territory == null || record.dead || record.removed) return;
        if (!(entity instanceof LivingEntity living)) return;
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (!"GUILD".equals(territory.type) || !Objects.equals(record.guildId, territory.guildId)) return;
        int interval = GuildStore.guildGolemOutOfCombatRegenIntervalTicks(record.guildId);
        if (interval <= 0) return;
        if (entity instanceof Mob mob && currentGolemTarget(mob) != null) return;
        if (living.getHealth() >= living.getMaxHealth()) return;
        int hash = Math.abs(record.uuid == null ? entity.getUUID().toString().hashCode() : record.uuid.hashCode());
        if ((level.getGameTime() + hash) % interval != 0L) return;
        living.heal(1.0F);
    }

    private static void cleanupUnboundGuildGolems() {
        cleanupUnboundGuildGolems(0L);
    }

    private static void cleanupUnboundGuildGolems(long tickStartNs) {
        if (server == null) return;
        int budget = isUltraGolemServerMode() ? 32 : (isExtremeGolemServerMode() ? 64 : (isMassiveGolemServerMode() ? 128 : 512));
        int processed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> entities = allEntityList(level);
            if (entities.isEmpty()) continue;
            int start = Math.floorMod(cleanupUnboundGolemCursor, entities.size());
            for (int n = 0; n < entities.size() && processed < budget; n++) {
                if (serverTickBudgetExceeded(tickStartNs)) { cleanupUnboundGolemCursor = Math.floorMod(start + n, Math.max(1, entities.size())); return; }
                Entity entity = entities.get(Math.floorMod(start + n, entities.size()));
                processed++;
                if (!isGuildGolem(entity)) continue;
                GuildStore.Golem record = GuildStore.golem(entity.getUUID().toString());
                if (record == null || record.guildId == null || !GuildStore.guildExists(record.guildId)) {
                    entity.discard();
                }
            }
            cleanupUnboundGolemCursor = Math.floorMod(start + processed, Math.max(1, entities.size()));
            if (processed >= budget) return;
        }
    }

    private static void ensureGuildGolemIdentity(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null) return;
        String displayName = GuildStore.ensureGolemDisplayName(record);
        applyGuildGolemIdentity(entity, record.guildId, record.type, record.elite, displayName);
    }

    private static void applyGuildGolemIdentity(Entity entity, String guildId, String typeId, boolean elite) {
        applyGuildGolemIdentity(entity, guildId, typeId, elite, GuildStore.golemDisplayName(guildId, entity == null ? "" : entity.getUUID().toString(), elite));
    }

    private static void applyGuildGolemIdentity(Entity entity, String guildId, String typeId, boolean elite, String displayName) {
        if (entity == null || guildId == null) return;
        entity.addTag("homecraft_guild_golem");
        entity.addTag("homecraft_guild_id_" + guildId);
        if (elite) entity.addTag("homecraft_guild_elite_golem");
        if (typeId != null && !typeId.isBlank()) entity.addTag("homecraft_guild_golem_type_" + typeId.replace(':', '_'));
        String name = displayName == null || displayName.isBlank() ? GuildStore.golemDisplayName(guildId, entity.getUUID().toString(), elite) : displayName;
        entity.setCustomName(Component.literal(name));
        entity.setCustomNameVisible(true);
    }


    private static void spawnEliteGolemParticles(Entity entity) {
        // Client-rendered only. Do not emit server particles from the AI loop.
        if (!(entity.level() instanceof ServerLevel level)) return;
        if ((GOLEM_AI_FRAME & 15L) != 0L) return;
        sendClientVisualEventNear(level, "elite_golem", entity.getX(), entity.getY() + entity.getBbHeight() * 0.65D, entity.getZ(), false, 56.0D);
    }

    private static void recoverMissingGuildGolem(GuildStore.Golem record) {
        if (record == null || record.uuid == null || record.guildId == null) return;
        if (!GuildStore.guildExists(record.guildId)) {
            GuildStore.removeGolem(record.uuid);
            return;
        }

        ServerLevel originalLevel = levelForDimension(record.dimension);
        if (originalLevel != null && !isChunkLoaded(originalLevel, record.x, record.z)) {
            // Entity can be temporarily absent from getEntity() while its chunk is not loaded after restart.
            // Do not delete the record and do not duplicate the golem.
            return;
        }

        int missingTicks = GuildStore.markGolemMissing(record.uuid);
        if (missingTicks < GOLEM_RESTORE_GRACE_TICKS) return;

        GolemSpawn spawn = findRandomGuildGolemSpawn(record.guildId, record.dimension);
        if (spawn == null) return;
        Entity restored = spawnGuildGolemEntity(record.guildId, record.type, spawn, 0.0F, true, record.elite);
        if (restored != null) {
            String oldUuid = record.uuid;
            GuildStore.updateGolemUuid(record.uuid, restored.getUUID().toString(), GuildStore.dimensionId(spawn.level), (int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z));
            cleanupGolemRuntimeState(oldUuid);
            afterGuildGolemTeleport(restored.getUUID().toString(), restored, spawn.level, new BlockPos((int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z)));
            ensureGuildGolemIdentity(record, restored);
            if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.debug("guild-golem-restored: oldUuid={} newUuid={} guild={} at={} {} {}", oldUuid, restored.getUUID(), record.guildId, spawn.x, spawn.y, spawn.z);
        }
    }


    private static boolean forceTeleportGolemToNearestGuildTotem(GuildStore.Golem record, Entity entity, String reason) {
        if (record == null || entity == null) return false;
        String preferredDimension = GuildStore.dimensionId(entity.level());
        GuildStore.Territory nearest = nearestGuildTotem(record.guildId, preferredDimension, entity.getX(), entity.getY(), entity.getZ());
        if (nearest == null) nearest = nearestGuildTotem(record.guildId, null, entity.getX(), entity.getY(), entity.getZ());
        if (nearest == null) return false;
        ServerLevel level = levelForTerritory(null, nearest);
        if (level == null) return false;

        GolemSpawn target = findManualTotemTeleportSpawn(level, nearest);
        if (target == null) target = findSafeGolemReturnSpawn(level, nearest, nearest.x, nearest.z);
        if (target == null) return false;

        clearGuildGolemRuntimeBeforeManualTeleport(record, entity);
        return relocateGuildGolem(record, entity, target, reason == null || reason.isBlank() ? "manual-button" : reason);
    }

    private static GolemSpawn findManualTotemTeleportSpawn(ServerLevel level, GuildStore.Territory territory) {
        if (level == null || territory == null) return null;
        int[][] preferred = {
                {2,0},{-2,0},{0,2},{0,-2},{2,2},{2,-2},{-2,2},{-2,-2},
                {3,0},{-3,0},{0,3},{0,-3},{4,1},{-4,1},{1,4},{1,-4},
                {5,0},{-5,0},{0,5},{0,-5}
        };
        for (int[] offset : preferred) {
            GolemSpawn spawn = safeClusterPatrolPoint(level, List.of(territory), territory.x + offset[0], territory.z + offset[1]);
            if (isValidManualTotemTeleportSpawn(level, territory, spawn)) return spawn;
        }
        int maxRadius = Math.max(8, Math.min(GOLEM_MANUAL_TOTEM_TELEPORT_SEARCH_RADIUS, Math.max(8, territory.size / 2)));
        int seed = Math.abs(String.valueOf(territory.id == null ? territory.guildId : territory.id).hashCode());
        for (int radius = 3; radius <= maxRadius; radius++) {
            int samples = Math.max(12, radius * 6);
            for (int i = 0; i < samples; i++) {
                double angle = ((seed + i * 31 + radius * 13) % 360) * Math.PI / 180.0D;
                int x = territory.x + (int)Math.round(Math.cos(angle) * radius);
                int z = territory.z + (int)Math.round(Math.sin(angle) * radius);
                GolemSpawn spawn = safeClusterPatrolPoint(level, List.of(territory), x, z);
                if (isValidManualTotemTeleportSpawn(level, territory, spawn)) return spawn;
            }
        }
        return null;
    }

    private static boolean isValidManualTotemTeleportSpawn(ServerLevel level, GuildStore.Territory territory, GolemSpawn spawn) {
        if (level == null || territory == null || spawn == null || spawn.level != level) return false;
        BlockPos pos = new BlockPos((int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z));
        if (!GuildStore.isInsideTerritory(territory, pos)) return false;
        if (!isGolemWalkableSpot(level, pos)) return false;
        if (isGolemPhysicallyTrappedAt(level, pos)) return false;
        if (isNearDeepDrop(level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) return false;
        if (hasWaterNear(level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS)) return false;
        return true;
    }

    private static boolean returnGolemToNearestGuildTotem(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null) return false;
        return returnGolemToNearestGuildTotem(record, entity, "nearest-totem");
    }

    private static boolean returnGolemToNearestGuildTotem(GuildStore.Golem record, Entity entity, String reason) {
        if (record == null || entity == null) return false;
        return returnGolemToNearestGuildTotem(record, entity, GuildStore.dimensionId(entity.level()), entity.getX(), entity.getY(), entity.getZ(), reason);
    }

    private static boolean returnGolemToNearestGuildTotem(GuildStore.Golem record, Entity entity, String preferredDimension, double nearX, double nearY, double nearZ) {
        return returnGolemToNearestGuildTotem(record, entity, preferredDimension, nearX, nearY, nearZ, "nearest-totem");
    }

    private static boolean returnGolemToNearestGuildTotem(GuildStore.Golem record, Entity entity, String preferredDimension, double nearX, double nearY, double nearZ, String reason) {
        if (record == null || entity == null) return false;
        GuildStore.Territory nearest = nearestGuildTotem(record.guildId, preferredDimension, nearX, nearY, nearZ);
        if (nearest == null) return returnGolemToRandomGuildTotem(record, entity);
        ServerLevel level = levelForTerritory(null, nearest);
        if (level == null) return returnGolemToRandomGuildTotem(record, entity);
        GolemSpawn target = findSafeGolemReturnSpawn(level, nearest, nearX, nearZ);
        if (target == null) return returnGolemToRandomGuildTotem(record, entity);
        return relocateGuildGolem(record, entity, target, reason == null || reason.isBlank() ? "nearest-totem" : reason) || returnGolemToRandomGuildTotem(record, entity);
    }

    private static GuildStore.Territory nearestGuildTotem(String guildId, String preferredDimension, double x, double y, double z) {
        List<GuildStore.Territory> territories = GuildStore.guildTerritories(guildId);
        GuildStore.Territory best = null;
        double bestDist = Double.MAX_VALUE;
        for (GuildStore.Territory territory : territories) {
            if (territory == null) continue;
            if (preferredDimension != null && !preferredDimension.isBlank() && !Objects.equals(preferredDimension, territory.dimension)) continue;
            double dist = distanceSqr(territory.x + 0.5D, territory.y + 0.5D, territory.z + 0.5D, x, y, z);
            if (dist < bestDist) {
                bestDist = dist;
                best = territory;
            }
        }
        if (best != null) return best;
        for (GuildStore.Territory territory : territories) {
            if (territory == null) continue;
            double dist = distanceSqr(territory.x + 0.5D, territory.y + 0.5D, territory.z + 0.5D, x, y, z);
            if (dist < bestDist) {
                bestDist = dist;
                best = territory;
            }
        }
        return best;
    }

    private static BlockPos findSafeAdjacentTotemSpot(ServerLevel level, GuildStore.Territory territory) {
        GolemSpawn spawn = findSafeGolemReturnSpawn(level, territory, territory == null ? 0.0D : territory.x, territory == null ? 0.0D : territory.z);
        if (spawn == null) return null;
        return new BlockPos((int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z));
    }

    private static GolemSpawn findSafeGolemReturnSpawn(ServerLevel level, GuildStore.Territory territory, double nearX, double nearZ) {
        if (level == null || territory == null) return null;
        int nearBlockX = (int)Math.floor(nearX);
        int nearBlockZ = (int)Math.floor(nearZ);
        int centerX = Math.max(GuildStore.minX(territory), Math.min(GuildStore.maxX(territory), nearBlockX));
        int centerZ = Math.max(GuildStore.minZ(territory), Math.min(GuildStore.maxZ(territory), nearBlockZ));
        if (!GuildStore.isInsideTerritory(territory, new BlockPos(centerX, territory.y, centerZ))) {
            centerX = territory.x;
            centerZ = territory.z;
        }
        int[][] preferred = {{0,0},{2,0},{-2,0},{0,2},{0,-2},{2,2},{2,-2},{-2,2},{-2,-2},{3,0},{0,3},{-3,0},{0,-3},{4,1},{-4,-1},{1,4},{-1,-4}};
        for (int[] offset : preferred) {
            GolemSpawn spawn = safeClusterPatrolPoint(level, List.of(territory), centerX + offset[0], centerZ + offset[1]);
            if (spawn != null && !isGolemPhysicallyTrappedAt(level, new BlockPos((int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z)))) return spawn;
        }
        int hash = Math.abs((territory.id == null ? territory.guildId : territory.id).hashCode());
        for (int radius = 2; radius <= Math.max(10, Math.min(28, territory.size / 2)); radius++) {
            int attempts = Math.max(8, radius * 4);
            for (int i = 0; i < attempts; i++) {
                double angle = ((hash + i * 37 + radius * 11) % 360) * Math.PI / 180.0D;
                int x = centerX + (int)Math.round(Math.cos(angle) * radius);
                int z = centerZ + (int)Math.round(Math.sin(angle) * radius);
                GolemSpawn spawn = safeClusterPatrolPoint(level, List.of(territory), x, z);
                if (spawn != null && !isGolemPhysicallyTrappedAt(level, new BlockPos((int)Math.floor(spawn.x), (int)Math.floor(spawn.y), (int)Math.floor(spawn.z)))) return spawn;
            }
        }
        BlockPos surface = safeSurfaceBlockPos(level, centerX, centerZ);
        if (surface != null) {
            GuildStore.Territory at = GuildStore.territoryAt(level, surface);
            if (at != null && "GUILD".equals(at.type) && Objects.equals(at.guildId, territory.guildId)) {
                return new GolemSpawn(level, surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D, at);
            }
        }
        return null;
    }

    private static boolean returnGolemToRandomGuildTotem(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null) return false;
        List<GuildStore.Territory> territories = GuildStore.guildTerritories(record.guildId);
        if (territories.isEmpty()) return returnGolemToGuildTerritory(record, entity);
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        int start = random.nextInt(territories.size());
        for (int i = 0; i < territories.size(); i++) {
            GuildStore.Territory territory = territories.get((start + i) % territories.size());
            if (territory == null) continue;
            ServerLevel level = levelForTerritory(null, territory);
            if (level == null) continue;
            GolemSpawn spawn = findSafeGolemReturnSpawn(level, territory, entity.getX(), entity.getZ());
            if (spawn != null && relocateGuildGolem(record, entity, spawn, "random-totem")) return true;
        }
        return returnGolemToGuildTerritory(record, entity);
    }

    private static boolean returnGolemToGuildTerritory(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null) return false;
        GolemSpawn fallback = findRandomGuildGolemSpawn(record.guildId, record.dimension);
        if (fallback != null && relocateGuildGolem(record, entity, fallback, "guild-territory")) {
            if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.debug("guild-golem-returned: uuid={} guild={} to={}, {}, {}", record.uuid, record.guildId, fallback.x, fallback.y, fallback.z);
            return true;
        } else if (GuildStore.guildTerritories(record.guildId).isEmpty()) {
            entity.discard();
            GuildStore.removeGolem(record.uuid);
            if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.debug("guild-golem-removed-no-territory: uuid={} guild={}", record.uuid, record.guildId);
        }
        return false;
    }

    private static boolean relocateGuildGolem(GuildStore.Golem record, Entity entity, GolemSpawn target, String reason) {
        if (record == null || entity == null || target == null || target.level == null) return false;
        target = normalizeGolemRelocationTarget(record, entity, target);
        if (target == null || target.level == null) return false;
        BlockPos pos = new BlockPos((int)Math.floor(target.x), (int)Math.floor(target.y), (int)Math.floor(target.z));
        if (!isGolemWalkableSpot(target.level, pos)) return false;
        if (entity instanceof Mob mob) {
            clearGuildGolemAggro(mob);
            try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
        }
        boolean manualButtonTeleport = reason != null && reason.toLowerCase(Locale.ROOT).contains("manual-button");
        if (entity.level() == target.level) {
            if (!teleportEntityVerified(entity, target.x, target.y, target.z)) return false;
            afterGuildGolemTeleport(record.uuid, entity, target.level, pos, !manualButtonTeleport);
            GuildStore.markGolemSeen(record.uuid, GuildStore.dimensionId(target.level), pos.getX(), pos.getY(), pos.getZ());
            if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.debug("guild-golem-teleport: reason={} uuid={} guild={} to={} {} {}", reason, record.uuid, record.guildId, pos.getX(), pos.getY(), pos.getZ());
            return true;
        }

        float healthRatio = 1.0F;
        if (entity instanceof LivingEntity oldLiving && oldLiving.getMaxHealth() > 0.0F) {
            healthRatio = Math.max(0.05F, Math.min(1.0F, oldLiving.getHealth() / oldLiving.getMaxHealth()));
        }
        String oldUuid = record.uuid;
        Entity replacement = spawnGuildGolemEntity(record.guildId, record.type, target, entity.getYRot(), true, record.elite);
        if (replacement == null) return false;
        if (replacement instanceof LivingEntity newLiving) {
            newLiving.setHealth(Math.max(1.0F, newLiving.getMaxHealth() * healthRatio));
        }
        entity.discard();
        GuildStore.updateGolemUuid(oldUuid, replacement.getUUID().toString(), GuildStore.dimensionId(target.level), pos.getX(), pos.getY(), pos.getZ());
        GuildStore.Golem updated = GuildStore.golem(replacement.getUUID().toString());
        if (updated != null) ensureGuildGolemIdentity(updated, replacement);
        cleanupGolemRuntimeState(oldUuid);
        afterGuildGolemTeleport(replacement.getUUID().toString(), replacement, target.level, pos, !manualButtonTeleport);
        if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.debug("guild-golem-dimension-relocate: reason={} oldUuid={} newUuid={} guild={} to={} {} {}", reason, oldUuid, replacement.getUUID(), record.guildId, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    private static GolemSpawn normalizeGolemRelocationTarget(GuildStore.Golem record, Entity entity, GolemSpawn target) {
        if (record == null || target == null || target.level == null) return null;
        BlockPos pos = new BlockPos((int)Math.floor(target.x), (int)Math.floor(target.y), (int)Math.floor(target.z));
        GuildStore.Territory at = GuildStore.territoryAt(target.level, pos);
        if (at != null && "GUILD".equals(at.type) && Objects.equals(at.guildId, record.guildId)) {
            return new GolemSpawn(target.level, target.x, target.y, target.z, at);
        }
        GuildStore.Territory nearest = nearestGuildTotem(record.guildId, GuildStore.dimensionId(target.level), target.x, target.y, target.z);
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, nearest, GuildStore.dimensionId(target.level));
        if (cluster.isEmpty() && nearest != null) cluster = List.of(nearest);
        if (cluster.isEmpty()) return null;
        return nearestSafePointInsideCluster(target.level, cluster, pos, null);
    }

    private static void afterGuildGolemTeleport(String uuid, Entity entity, ServerLevel level, BlockPos pos) {
        afterGuildGolemTeleport(uuid, entity, level, pos, true);
    }

    private static void afterGuildGolemTeleport(String uuid, Entity entity, ServerLevel level, BlockPos pos, boolean planFreshRoute) {
        if (uuid == null || uuid.isBlank() || entity == null) return;
        long now = entity.level().getGameTime();
        GOLEM_POST_TELEPORT_TICK.put(uuid, now);
        rememberForcedTeleport(uuid, entity);
        resetGolemStuckState(uuid, entity);
        GOLEM_TARGET_CACHE.remove(uuid);
        GOLEM_PATROL_MEMORY.remove(uuid);
        GOLEM_PATROL_PLAN.remove(uuid);
        GOLEM_ROUTE_RESERVATIONS.remove(uuid);
        GOLEM_THREAT_ASSIGNMENTS.remove(uuid);
        GOLEM_TASK_STATE.remove(uuid);
        GOLEM_ASYNC_PATROL_RESULTS.remove(uuid);
        GOLEM_ASYNC_PATROL_PENDING.remove(uuid);
        GOLEM_ASYNC_PATROL_LAST_REQUEST.remove(uuid);
        clearGolemPathCache(uuid);
        GolemNavigationMemory nav = GOLEM_NAV_MEMORY.computeIfAbsent(uuid, ignored -> new GolemNavigationMemory(entity.getX(), entity.getY(), entity.getZ(), now));
        nav.lastTargetX = entity.getX();
        nav.lastTargetY = entity.getY();
        nav.lastTargetZ = entity.getZ();
        nav.lastRepathTick = 0L;
        nav.blockedChecks = 0;
        if (entity instanceof Mob mob) {
            clearGuildGolemAggro(mob);
            try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
        }
        clearEntityVelocity(entity);
        tryInvoke(entity, "setOnGround", new Class<?>[]{boolean.class}, true);
        try {
            java.lang.reflect.Field fall = Entity.class.getDeclaredField("fallDistance");
            fall.setAccessible(true);
            fall.setFloat(entity, 0.0F);
        } catch (Throwable ignored) {}
        if (planFreshRoute) planFreshRouteAfterTeleport(uuid, entity);
    }

    private static void planFreshRouteAfterTeleport(String uuid, Entity entity) {
        if (uuid == null || entity == null || !(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) return;
        GuildStore.Golem record = GuildStore.golem(uuid);
        if (record == null || record.dead) return;
        GuildStore.Territory territory = GuildStore.territoryAt(level, entity.blockPosition());
        if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, record.guildId)) return;
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, territory, GuildStore.dimensionId(level));
        if (cluster.isEmpty()) cluster = List.of(territory);
        GolemPatrolContext context = buildGolemPatrolContext(record, entity, territory, cluster);
        GolemPatrolRole role = patrolRole(record, context);
        GolemSpawn next = findDistributedPatrolPoint(record, entity, territory, cluster, context, role);
        if (next == null) next = findSafeGolemReturnSpawn(level, territory, entity.getX(), entity.getZ());
        if (next == null) return;
        moveGolemToPoint(record, mob, next, patrolSpeed(record, entity, role, context), true);
        GOLEM_POST_TELEPORT_TICK.put(uuid, level.getGameTime());
    }

    private static void clearEntityVelocity(Entity entity) {
        if (entity == null) return;
        try {
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            Object zero = vec3.getConstructor(double.class, double.class, double.class).newInstance(0.0D, 0.0D, 0.0D);
            entity.getClass().getMethod("setDeltaMovement", vec3).invoke(entity, zero);
            return;
        } catch (Throwable ignored) {}
        tryInvoke(entity, "setDeltaMovement", new Class<?>[]{double.class, double.class, double.class}, 0.0D, 0.0D, 0.0D);
    }

    private static void cleanupGolemRuntimeState(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        PENDING_GOLEM_REVIVES.remove(uuid);
        UUID parsedUuid = parseUuid(uuid);
        if (parsedUuid != null) GOLEM_ENTITY_LOOKUP_CACHE.remove(parsedUuid);
        GOLEM_STUCK.remove(uuid);
        GOLEM_PATROL_MEMORY.remove(uuid);
        GOLEM_LAST_FORCED_TELEPORT_TICK.remove(uuid);
        GOLEM_NAV_MEMORY.remove(uuid);
        GOLEM_TARGET_CACHE.remove(uuid);
        GOLEM_ALERT_CHATTER_TICK.remove(uuid);
        GOLEM_SEEN_CACHE.remove(uuid);
        GOLEM_LAST_STAT_SYNC_TICK.remove(uuid);
        GOLEM_POST_TELEPORT_TICK.remove(uuid);
        GOLEM_PATROL_PLAN.remove(uuid);
        GOLEM_ROUTE_RESERVATIONS.remove(uuid);
        GOLEM_THREAT_ASSIGNMENTS.remove(uuid);
        GOLEM_TASK_STATE.remove(uuid);
        GOLEM_ASYNC_PATROL_RESULTS.remove(uuid);
        GOLEM_ASYNC_PATROL_PENDING.remove(uuid);
        GOLEM_ASYNC_PATROL_LAST_REQUEST.remove(uuid);
        clearGolemPathCache(uuid);
    }

    private static void clearGuildGolemRuntimeBeforeManualTeleport(GuildStore.Golem record, Entity entity) {
        if (record == null || entity == null) return;
        if (entity instanceof Mob mob) {
            clearGuildGolemAggro(mob);
            try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
        }
        clearEntityVelocity(entity);
        GOLEM_STUCK.remove(record.uuid);
        GOLEM_TARGET_CACHE.remove(record.uuid);
        GOLEM_NAV_MEMORY.remove(record.uuid);
        GOLEM_PATROL_MEMORY.remove(record.uuid);
        GOLEM_PATROL_PLAN.remove(record.uuid);
        GOLEM_POST_TELEPORT_TICK.remove(record.uuid);
        GOLEM_LAST_FORCED_TELEPORT_TICK.remove(record.uuid);
        GOLEM_ROUTE_RESERVATIONS.remove(record.uuid);
        GOLEM_THREAT_ASSIGNMENTS.remove(record.uuid);
        GOLEM_TASK_STATE.remove(record.uuid);
        GOLEM_ASYNC_PATROL_RESULTS.remove(record.uuid);
        GOLEM_ASYNC_PATROL_PENDING.remove(record.uuid);
        GOLEM_ASYNC_PATROL_LAST_REQUEST.remove(record.uuid);
        clearGolemPathCache(record.uuid);
    }

    private static void clearGolemPathCache(String uuid) {
        if (uuid == null || uuid.isBlank() || GOLEM_PATH_CACHE.isEmpty()) return;
        String prefix = uuid + "|";
        GOLEM_PATH_CACHE.keySet().removeIf(key -> key != null && key.startsWith(prefix));
    }

    private static boolean clearInvalidGuildGolemTarget(String guildId, Entity golem, GuildStore.Territory territory) {
        if (!(golem instanceof Mob mob)) return false;
        LivingEntity target = mob.getTarget();
        if (target == null) return false;
        boolean invalid = !target.isAlive() || isGuildGolem(target);
        if (!invalid && target instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, guildId)) invalid = true;
        if (!invalid && shouldIgnoreUndergroundGolemTarget(target)) invalid = true;
        if (!invalid) {
            GuildStore.Golem record = guildId == null ? null : GuildStore.golem(golem.getUUID().toString());
            if (record != null && !isGolemTargetAllowedByLeash(record, golem, target, true)) invalid = true;
            else if (record == null && territory != null) {
                List<GuildStore.Territory> cluster = contiguousGuildCluster(guildId, territory, GuildStore.dimensionId(golem.level()));
                if (cluster.isEmpty()) cluster = List.of(territory);
                if (!isInsideCluster(cluster, target.blockPosition())) invalid = true;
            }
        }
        if (!invalid) return false;
        clearGuildGolemAggro(mob);
        return true;
    }

    private static void clearGuildGolemAggro(Mob mob) {
        if (mob == null) return;
        try { mob.setTarget(null); } catch (Exception ignored) {}
        try { mob.getNavigation().stop(); } catch (Exception ignored) {}
        tryInvoke(mob, "setLastHurtByMob", new Class<?>[]{LivingEntity.class}, new Object[]{null});
        tryInvoke(mob, "setLastHurtByPlayer", new Class<?>[]{ServerPlayer.class}, new Object[]{null});
        tryInvoke(mob, "setPersistentAngerTarget", new Class<?>[]{UUID.class}, new Object[]{null});
        tryInvoke(mob, "setRemainingPersistentAngerTime", new Class<?>[]{int.class}, 0);
        tryInvoke(mob, "stopBeingAngry", new Class<?>[]{}, new Object[]{});
    }

    private static void patrolGuildTerritory(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (!(entity instanceof Mob mob)) return;
        if (record == null || record.dead) return;

        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, territory, GuildStore.dimensionId(entity.level()));
        if (cluster.isEmpty()) cluster = territory == null ? Collections.emptyList() : List.of(territory);
        if (cluster.isEmpty()) return;

        GolemPatrolContext context = buildGolemPatrolContext(record, entity, territory, cluster);
        GolemPatrolRole role = patrolRole(record, context);
        long tick = entity.level().getGameTime();
        GolemPatrolMemory memory = GOLEM_PATROL_MEMORY.get(record.uuid);
        GolemPatrolPlan plan = GOLEM_PATROL_PLAN.get(record.uuid);

        if (!mob.getNavigation().isDone()) {
            if (!shouldReroutePatrol(record, entity, territory, cluster, role, memory, plan, context, tick)) {
                requestAsyncPatrolPlan(record, entity, territory, cluster, context, role, tick, false);
                return;
            }
            try { mob.getNavigation().stop(); } catch (Exception ignored) {}
        }

        if (tryApplyAsyncPatrolPlan(record, mob, territory, cluster, context, role, tick)) return;
        if (shouldPreferAsyncPatrolPlanning() && requestAsyncPatrolPlan(record, entity, territory, cluster, context, role, tick, true)) {
            // Do not block the server thread while a worker creates the macro route. The golem keeps
            // its current state for a few ticks; if the plan is not ready in time, the normal cheap
            // fallback below will still produce a route.
            return;
        }

        GuildStore.Territory patrolTerritory = choosePatrolTerritory(record, entity, territory, cluster, context, role);
        if (patrolTerritory == null) return;
        GolemSpawn destination = findDistributedPatrolPoint(record, entity, patrolTerritory, cluster, context, role);
        if (destination == null) destination = findRandomPointInTerritory(patrolTerritory);
        if (destination == null) return;

        double speed = patrolSpeed(record, entity, role, context);
        if (moveGolemToPoint(record, mob, destination, speed, true)) {
            GOLEM_PATROL_PLAN.put(record.uuid, new GolemPatrolPlan(GOLEM_PATROL_SYSTEM_VERSION, role, patrolTerritory.id, destination.x, destination.y, destination.z, tick, context.dangerNearGuildMember, context.waterDangerMode));
            markGolemTask(record, entity, "PATROL", destination, null);
        }
    }


    private static boolean shouldPreferAsyncPatrolPlanning() {
        if (isGolemGovernorHardRescue()) return false;
        // Normal servers can still use async for non-urgent patrol route generation, but urgent
        // reaction/guard decisions stay fast and validated on the server thread.
        return isMassiveGolemServerMode() || GOLEM_ASYNC_PLANNER_QUEUED.get() > GOLEM_ASYNC_PLANNER_THREADS * 2;
    }

    private static int asyncPatrolPriority(GuildStore.Golem record, GolemPatrolContext context, GolemPatrolRole role) {
        if (context != null && context.bestThreatScore >= 450) return GOLEM_ASYNC_PRIORITY_CRITICAL;
        if (context != null && context.dangerNearGuildMember) return GOLEM_ASYNC_PRIORITY_CRITICAL;
        if (record != null && record.elite) return GOLEM_ASYNC_PRIORITY_HIGH;
        if (role == GolemPatrolRole.RESPONSE || role == GolemPatrolRole.INTERCEPTOR || role == GolemPatrolRole.MEMBER_GUARD) return GOLEM_ASYNC_PRIORITY_HIGH;
        if (role == GolemPatrolRole.WATER_GUARD) return GOLEM_ASYNC_PRIORITY_NORMAL;
        if (role == GolemPatrolRole.SWEEPER || role == GolemPatrolRole.ANCHOR) return GOLEM_ASYNC_PRIORITY_BACKGROUND;
        return GOLEM_ASYNC_PRIORITY_NORMAL;
    }

    private static int asyncPlannerSubmissionsPerFrameLimit() {
        if (isGolemGovernorHardRescue()) return 0;
        if (isGolemGovernorThrottled()) return 1;
        if (isUltraGolemServerMode()) return 2;
        if (isExtremeGolemServerMode()) return 3;
        if (isMassiveGolemServerMode()) return 4;
        return Math.max(2, Math.min(8, GOLEM_ASYNC_PLANNER_THREADS * 2));
    }

    private static boolean tryReserveAsyncPlannerSlot(int priority) {
        if (GOLEM_ASYNC_PLANNER.isShutdown() || GOLEM_ASYNC_PLANNER.isTerminating()) return false;
        if (GOLEM_AI_FRAME != golemAsyncSubmitFrame) {
            golemAsyncSubmitFrame = GOLEM_AI_FRAME;
            golemAsyncSubmissionsThisFrame = 0;
        }
        if (GOLEM_ASYNC_PLANNER_QUEUED.get() >= GOLEM_ASYNC_PLANNER_QUEUE_CAP) return false;
        int limit = asyncPlannerSubmissionsPerFrameLimit();
        if (limit <= 0) return false;
        boolean critical = priority >= GOLEM_ASYNC_PRIORITY_CRITICAL;
        if (!critical && golemAsyncSubmissionsThisFrame >= limit) return false;
        if (critical && golemAsyncSubmissionsThisFrame >= limit + Math.max(1, GOLEM_ASYNC_PLANNER_THREADS)) return false;
        golemAsyncSubmissionsThisFrame++;
        GOLEM_ASYNC_PLANNER_QUEUED.incrementAndGet();
        return true;
    }

    private static void releaseAsyncPlannerSlot() {
        GOLEM_ASYNC_PLANNER_QUEUED.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static boolean tryApplyAsyncPatrolPlan(GuildStore.Golem record, Mob mob, GuildStore.Territory territory, List<GuildStore.Territory> cluster, GolemPatrolContext context, GolemPatrolRole role, long tick) {
        if (record == null || mob == null || record.uuid == null || cluster == null || cluster.isEmpty()) return false;
        AsyncPatrolPlan plan = GOLEM_ASYNC_PATROL_RESULTS.remove(record.uuid);
        GOLEM_ASYNC_PATROL_PENDING.remove(record.uuid);
        if (plan == null) return false;
        if (!GOLEM_PATROL_SYSTEM_VERSION.equals(plan.version)) return false;
        if (plan.territoryRevision != GuildStore.territoryTopologyRevision()) return false;
        if (plan.golemRevision != GuildStore.golemTopologyRevision()) return false;
        if (!Objects.equals(record.guildId, plan.guildId)) return false;
        if (!Objects.equals(GuildStore.dimensionId(mob.level()), plan.dimension)) return false;
        if (GOLEM_AI_FRAME - plan.frame > GOLEM_ASYNC_PLANNER_RESULT_TTL_FRAMES) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;
        GolemSpawn point = safeClusterPatrolPoint(level, cluster, plan.x, plan.z);
        if (point == null) return false;
        point = acceptPatrolPoint(record, mob, point, false, context);
        if (point == null) return false;
        double speed = patrolSpeed(record, mob, role, context);
        if (!moveGolemToPoint(record, mob, point, speed, true)) return false;
        GuildStore.Territory patrolTerritory = point.territory != null ? point.territory : territory;
        GOLEM_PATROL_PLAN.put(record.uuid, new GolemPatrolPlan(GOLEM_PATROL_SYSTEM_VERSION, role, patrolTerritory == null ? plan.territoryId : patrolTerritory.id, point.x, point.y, point.z, tick, context != null && context.dangerNearGuildMember, context != null && context.waterDangerMode));
        markGolemTask(record, mob, "PATROL", point, null);
        return true;
    }

    private static boolean requestAsyncPatrolPlan(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, List<GuildStore.Territory> cluster, GolemPatrolContext context, GolemPatrolRole role, long tick, boolean mayDelayMainThread) {
        if (record == null || entity == null || record.uuid == null || cluster == null || cluster.isEmpty()) return false;
        if (!shouldPreferAsyncPatrolPlanning() && !mayDelayMainThread) return false;
        if (GOLEM_ASYNC_PATROL_RESULTS.containsKey(record.uuid)) return mayDelayMainThread;
        Long pendingAt = GOLEM_ASYNC_PATROL_PENDING.get(record.uuid);
        if (pendingAt != null && GOLEM_AI_FRAME - pendingAt < GOLEM_ASYNC_PLANNER_PENDING_TTL_FRAMES) return mayDelayMainThread;
        Long lastRequest = GOLEM_ASYNC_PATROL_LAST_REQUEST.get(record.uuid);
        if (lastRequest != null && GOLEM_AI_FRAME - lastRequest < GOLEM_ASYNC_PLANNER_RETRY_FRAMES) return false;
        int priority = asyncPatrolPriority(record, context, role);
        if (priority < GOLEM_ASYNC_PRIORITY_CRITICAL && pendingAsyncPatrolJobsForGuild(record.guildId) >= asyncPlannerPerGuildPendingCap(priority)) return false;
        if (!tryReserveAsyncPlannerSlot(priority)) return false;
        AsyncPatrolRequest request = snapshotAsyncPatrolRequest(record, entity, territory, cluster, context, role, tick);
        if (request == null || request.territories.isEmpty()) {
            releaseAsyncPlannerSlot();
            return false;
        }
        GOLEM_ASYNC_PATROL_PENDING.put(record.uuid, GOLEM_AI_FRAME);
        GOLEM_ASYNC_PATROL_LAST_REQUEST.put(record.uuid, GOLEM_AI_FRAME);
        try {
            GOLEM_ASYNC_PLANNER.execute(new PrioritizedAsyncTask(priority, () -> {
                try {
                    AsyncPatrolPlan plan = computeAsyncPatrolPlan(request);
                    if (plan != null) GOLEM_ASYNC_PATROL_RESULTS.put(request.uuid, plan);
                } catch (Throwable error) {
                    if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds async golem planner failed", error);
                } finally {
                    releaseAsyncPlannerSlot();
                    GOLEM_ASYNC_PATROL_PENDING.remove(request.uuid);
                }
            }));
            return mayDelayMainThread;
        } catch (RejectedExecutionException error) {
            releaseAsyncPlannerSlot();
            GOLEM_ASYNC_PATROL_PENDING.remove(record.uuid);
            return false;
        } catch (Throwable error) {
            releaseAsyncPlannerSlot();
            GOLEM_ASYNC_PATROL_PENDING.remove(record.uuid);
            return false;
        }
    }

    private static int asyncPlannerPerGuildPendingCap(int priority) {
        if (priority >= GOLEM_ASYNC_PRIORITY_HIGH) return Math.max(2, GOLEM_ASYNC_PLANNER_PER_GUILD_PENDING_CAP / 2);
        if (priority <= GOLEM_ASYNC_PRIORITY_BACKGROUND) return Math.max(1, GOLEM_ASYNC_PLANNER_PER_GUILD_PENDING_CAP / 4);
        return Math.max(2, GOLEM_ASYNC_PLANNER_PER_GUILD_PENDING_CAP);
    }

    private static int pendingAsyncPatrolJobsForGuild(String guildId) {
        if (guildId == null || guildId.isBlank()) return 0;
        int count = 0;
        for (String uuid : GOLEM_ASYNC_PATROL_PENDING.keySet()) {
            GuildStore.Golem pending = GuildStore.golem(uuid);
            if (pending != null && Objects.equals(guildId, pending.guildId)) count++;
        }
        return count;
    }

    private static AsyncPatrolRequest snapshotAsyncPatrolRequest(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, List<GuildStore.Territory> cluster, GolemPatrolContext context, GolemPatrolRole role, long tick) {
        if (record == null || entity == null || cluster == null || cluster.isEmpty()) return null;
        String dimension = GuildStore.dimensionId(entity.level());
        List<AsyncTerritorySnapshot> territories = new ArrayList<>();
        int count = 0;
        int start = Math.floorMod(String.valueOf(record.uuid).hashCode() + (int)(GOLEM_AI_FRAME / 20L), cluster.size());
        for (int i = 0; i < cluster.size() && count < GOLEM_ASYNC_PLANNER_MAX_TERRITORIES_PER_JOB; i++) {
            GuildStore.Territory t = cluster.get(Math.floorMod(start + i, cluster.size()));
            if (t == null) continue;
            territories.add(new AsyncTerritorySnapshot(t.id, GuildStore.minX(t), GuildStore.minZ(t), GuildStore.maxX(t), GuildStore.maxZ(t), t.x, t.z));
            count++;
        }
        if (territories.isEmpty() && territory != null) territories.add(new AsyncTerritorySnapshot(territory.id, GuildStore.minX(territory), GuildStore.minZ(territory), GuildStore.maxX(territory), GuildStore.maxZ(territory), territory.x, territory.z));
        boolean night = context != null && context.night;
        int roleOrdinal = role == null ? 0 : role.ordinal();
        int active = Math.max(1, activeGuildGolemCount(record.guildId));
        return new AsyncPatrolRequest(record.uuid, record.guildId, dimension, entity.getX(), entity.getZ(), tick, GOLEM_AI_FRAME, GuildStore.territoryTopologyRevision(), GuildStore.golemTopologyRevision(), night, context != null && context.dangerNearGuildMember, context != null && context.waterDangerMode, roleOrdinal, stableGuildGolemIndex(record), active, golemScaleMode, territories);
    }

    private static AsyncPatrolPlan computeAsyncPatrolPlan(AsyncPatrolRequest request) {
        if (request == null || request.territories == null || request.territories.isEmpty()) return null;
        AsyncTerritorySnapshot bounds = mergedAsyncBounds(request.territories);
        if (bounds == null) return null;
        int width = Math.max(1, bounds.maxX - bounds.minX + 1);
        int depth = Math.max(1, bounds.maxZ - bounds.minZ + 1);
        int area = Math.max(1, width * depth);
        int maxCells = request.scaleMode >= GOLEM_SCALE_ULTRA ? 48 : (request.scaleMode >= GOLEM_SCALE_EXTREME ? 64 : GOLEM_PATROL_COVERAGE_MAX_CELLS);
        int slots = Math.max(GOLEM_PATROL_COVERAGE_MIN_CELLS, Math.min(maxCells, Math.max(area / (request.night ? 224 : 288), request.activeGolems * (request.night ? 8 : 12))));
        int columns = Math.max(1, (int)Math.ceil(Math.sqrt(slots * (width / Math.max(1.0D, (double)depth)))));
        int rows = Math.max(1, (int)Math.ceil(slots / (double)columns));
        slots = Math.max(1, columns * rows);
        int stride = asyncCoprimeStride(slots, request.activeGolems, request.golemIndex);
        int phase = (int)Math.floorMod(request.tick / (request.night ? 420L : 1200L), Math.max(1, slots));
        int roleBias = request.roleOrdinal * 11 + (request.danger ? 17 : 0) + (request.waterMode ? 23 : 0);
        int bestScore = Integer.MIN_VALUE;
        int bestX = (bounds.minX + bounds.maxX) / 2;
        int bestZ = (bounds.minZ + bounds.maxZ) / 2;
        String bestTerritoryId = bounds.id;
        int attempts = request.scaleMode >= GOLEM_SCALE_ULTRA ? 10 : (request.scaleMode >= GOLEM_SCALE_EXTREME ? 14 : 22);
        int base = Math.floorMod(request.golemIndex * Math.max(1, slots / Math.max(1, request.activeGolems)) + phase * stride + roleBias, slots);
        int hash = Math.abs(String.valueOf(request.uuid).hashCode());
        for (int i = 0; i < attempts; i++) {
            int cell = Math.floorMod(base + i * stride, slots);
            int cx = cell % columns;
            int cz = cell / columns;
            if ((cz & 1) == 1) cx = columns - 1 - cx;
            int x = asyncInterpolate(bounds.minX, bounds.maxX, (cx + 0.5D) / columns);
            int z = asyncInterpolate(bounds.minZ, bounds.maxZ, (cz + 0.5D) / rows);
            int spread = request.night ? 3 + (i & 3) * 2 : 5 + (i % 5) * 3;
            x += Math.floorMod(hash + i * 17 + roleBias, spread * 2 + 1) - spread;
            z += Math.floorMod(hash / 7 + i * 19 + roleBias, spread * 2 + 1) - spread;
            AsyncTerritorySnapshot owner = asyncTerritoryContaining(request.territories, x, z);
            if (owner == null) owner = asyncNearestTerritory(request.territories, x, z);
            if (owner == null) continue;
            x = Math.max(owner.minX + 1, Math.min(owner.maxX - 1, x));
            z = Math.max(owner.minZ + 1, Math.min(owner.maxZ - 1, z));
            int edgeDist = Math.min(Math.min(x - owner.minX, owner.maxX - x), Math.min(z - owner.minZ, owner.maxZ - z));
            int score = 50;
            if (request.night) score += Math.max(0, 42 - edgeDist * 3); else score += Math.min(50, edgeDist * 3);
            double dist = Math.sqrt((request.currentX - x) * (request.currentX - x) + (request.currentZ - z) * (request.currentZ - z));
            if (dist < (request.night ? 12.0D : 18.0D)) score -= 35; else score += Math.min(request.night ? 26 : 46, (int)(dist * 0.8D));
            score += Math.floorMod(hash + i * 31 + owner.id.hashCode(), 19);
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestZ = z;
                bestTerritoryId = owner.id;
            }
        }
        return new AsyncPatrolPlan(GOLEM_PATROL_SYSTEM_VERSION, request.uuid, request.guildId, request.dimension, bestTerritoryId, bestX, bestZ, request.frame, request.tick, request.territoryRevision, request.golemRevision);
    }

    private static AsyncTerritorySnapshot mergedAsyncBounds(List<AsyncTerritorySnapshot> territories) {
        if (territories == null || territories.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        String id = territories.get(0).id;
        for (AsyncTerritorySnapshot territory : territories) {
            if (territory == null) continue;
            minX = Math.min(minX, territory.minX);
            minZ = Math.min(minZ, territory.minZ);
            maxX = Math.max(maxX, territory.maxX);
            maxZ = Math.max(maxZ, territory.maxZ);
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new AsyncTerritorySnapshot(id, minX, minZ, maxX, maxZ, (minX + maxX) / 2, (minZ + maxZ) / 2);
    }

    private static AsyncTerritorySnapshot asyncTerritoryContaining(List<AsyncTerritorySnapshot> territories, int x, int z) {
        if (territories == null) return null;
        for (AsyncTerritorySnapshot territory : territories) {
            if (territory != null && x >= territory.minX && x <= territory.maxX && z >= territory.minZ && z <= territory.maxZ) return territory;
        }
        return null;
    }

    private static AsyncTerritorySnapshot asyncNearestTerritory(List<AsyncTerritorySnapshot> territories, int x, int z) {
        if (territories == null || territories.isEmpty()) return null;
        AsyncTerritorySnapshot best = null;
        long bestDist = Long.MAX_VALUE;
        for (AsyncTerritorySnapshot territory : territories) {
            if (territory == null) continue;
            int cx = Math.max(territory.minX, Math.min(territory.maxX, x));
            int cz = Math.max(territory.minZ, Math.min(territory.maxZ, z));
            long dx = (long)cx - x;
            long dz = (long)cz - z;
            long dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = territory;
            }
        }
        return best;
    }

    private static int asyncCoprimeStride(int slots, int active, int index) {
        if (slots <= 2) return 1;
        int stride = Math.max(3, slots / Math.max(2, active) + 1 + Math.floorMod(index * 2 + 1, 7));
        if ((stride & 1) == 0) stride++;
        while (asyncGcd(stride, slots) != 1 && stride < slots * 2) stride += 2;
        return Math.max(1, Math.floorMod(stride, slots));
    }

    private static int asyncGcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.max(1, a);
    }

    private static int asyncInterpolate(int min, int max, double fraction) {
        if (max <= min) return min;
        return min + (int)Math.round(Math.max(0.0D, Math.min(1.0D, fraction)) * (max - min));
    }

    private static GuildStore.Territory choosePatrolTerritory(GuildStore.Golem record, Entity entity, GuildStore.Territory current) {
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record == null ? null : record.guildId, current, entity == null ? "" : GuildStore.dimensionId(entity.level()));
        GolemPatrolContext context = buildGolemPatrolContext(record, entity, current, cluster);
        return choosePatrolTerritory(record, entity, current, cluster, context, patrolRole(record, context));
    }

    private static GuildStore.Territory choosePatrolTerritory(GuildStore.Golem record, Entity entity, GuildStore.Territory current, List<GuildStore.Territory> cluster) {
        GolemPatrolContext context = buildGolemPatrolContext(record, entity, current, cluster);
        return choosePatrolTerritory(record, entity, current, cluster, context, patrolRole(record, context));
    }

    private static GuildStore.Territory choosePatrolTerritory(GuildStore.Golem record, Entity entity, GuildStore.Territory current, List<GuildStore.Territory> cluster, GolemPatrolContext context, GolemPatrolRole role) {
        if (record == null || entity == null) return current;
        if (cluster == null || cluster.isEmpty()) return current;
        if (context == null) context = buildGolemPatrolContext(record, entity, current, cluster);
        if (role == null) role = patrolRole(record, context);

        boolean night = context.night;
        GuildStore.Territory best = null;
        int bestScore = Integer.MIN_VALUE;
        for (GuildStore.Territory candidate : cluster) {
            if (candidate == null) continue;
            int score = territoryPatrolScore(record, entity, candidate, current, context, role);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null && (bestScore > 0 || night || role == GolemPatrolRole.RESPONSE || role == GolemPatrolRole.INTERCEPTOR || role == GolemPatrolRole.MEMBER_GUARD)) return best;

        if (role == GolemPatrolRole.ANCHOR) {
            GuildStore.Territory assigned = assignedTerritoryByIndex(record, entity, cluster);
            if (assigned != null) return assigned;
        }
        if ((role == GolemPatrolRole.PERIMETER || role == GolemPatrolRole.WATER_GUARD) && current != null) return current;
        return chooseEvenPatrolTerritory(record, entity, cluster);
    }

    private static int territoryPatrolScore(GuildStore.Golem record, Entity entity, GuildStore.Territory candidate, GuildStore.Territory current, GolemPatrolContext context, GolemPatrolRole role) {
        if (record == null || entity == null || candidate == null) return Integer.MIN_VALUE / 2;
        int score = 0;
        score += patrolThreatScore(record.guildId, candidate, entity.level());
        score += borderActivityScore(record.guildId, candidate, entity.level());
        score += guildMembersInTerritory(record.guildId, candidate, entity.level()) * (context != null && context.night ? 7 : 4);
        score -= golemsNearTerritory(record.guildId, candidate, entity.level()) * (context != null && context.night ? 6 : 9);
        if (current != null && Objects.equals(current.id, candidate.id)) score -= context != null && context.night ? 1 : 4;
        if (context != null && context.focus != null && GuildStore.isInsideTerritory(candidate, context.focus.blockPosition())) score += context.dangerNearGuildMember ? 95 : 35;
        if (context != null && context.waterDangerMode && waterActivityScore(record.guildId, candidate, entity.level()) > 0) score += 55;
        if (role == GolemPatrolRole.ANCHOR) {
            GuildStore.Territory assigned = assignedTerritoryByIndex(record, entity, context == null ? List.of(candidate) : context.cluster);
            if (assigned != null && Objects.equals(assigned.id, candidate.id)) score += 80;
        }
        if (role == GolemPatrolRole.PERIMETER || role == GolemPatrolRole.INTERCEPTOR) score += borderActivityScore(record.guildId, candidate, entity.level()) * 2;
        if (role == GolemPatrolRole.MEMBER_GUARD) score += guildMembersInTerritory(record.guildId, candidate, entity.level()) * 9;
        if (role == GolemPatrolRole.WATER_GUARD) score += waterActivityScore(record.guildId, candidate, entity.level()) * 3;
        if (role == GolemPatrolRole.SWEEPER) score += 12;
        if (entity.level() instanceof ServerLevel sl) score += territoryPreparedDefenseScore(record, sl, context == null || context.cluster.isEmpty() ? List.of(candidate) : context.cluster, candidate, context, role);
        return score;
    }

    private static GuildStore.Territory chooseEvenPatrolTerritory(GuildStore.Golem record, Entity entity, List<GuildStore.Territory> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        int golemIndex = stableGuildGolemIndex(record);
        long rotation = Math.max(0L, entity.level().getGameTime() / 1200L);
        return candidates.get(Math.floorMod(golemIndex + (int)(rotation % candidates.size()), candidates.size()));
    }

    private static GolemSpawn findDistributedPatrolPoint(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, List<GuildStore.Territory> cluster) {
        GolemPatrolContext context = buildGolemPatrolContext(record, entity, territory, cluster);
        return findDistributedPatrolPoint(record, entity, territory, cluster, context, patrolRole(record, context));
    }

    private static GolemSpawn findDistributedPatrolPoint(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, List<GuildStore.Territory> cluster, GolemPatrolContext context, GolemPatrolRole role) {
        if (record == null || entity == null || territory == null || !(entity.level() instanceof ServerLevel level)) return null;
        if (cluster == null || cluster.isEmpty()) cluster = List.of(territory);
        if (context == null) context = buildGolemPatrolContext(record, entity, territory, cluster);
        if (role == null) role = patrolRole(record, context);

        Entity focus = context.focus;
        if (focus != null) {
            GolemSpawn nearFocus = acceptPatrolPoint(record, entity, pointNearFocusInsideCluster(record, level, cluster, focus.blockPosition(), context), context.night || context.dangerNearGuildMember, context);
            if (nearFocus != null) return nearFocus;
        }

        GolemSpawn rolePoint = switch (role) {
            case ANCHOR -> pointAroundAssignedTotem(record, entity, territory, cluster, context);
            case PERIMETER -> pointOnClusterPerimeter(record, level, cluster, context);
            case MEMBER_GUARD -> pointNearExposedGuildMember(record, level, cluster, context);
            case RESPONSE -> context.focus != null ? pointNearFocusInsideCluster(record, level, cluster, context.focus.blockPosition(), context) : coverageGridPatrolPoint(record, entity, cluster, 0, context);
            case INTERCEPTOR -> interceptorPatrolPoint(record, entity, territory, cluster, context);
            case WATER_GUARD -> waterAwarePatrolPoint(record, entity, territory, cluster, context);
            case SWEEPER -> coverageGridPatrolPoint(record, entity, cluster, 0, context);
        };
        rolePoint = acceptPatrolPoint(record, entity, rolePoint, false, context);
        if (rolePoint != null) return rolePoint;

        for (int attempt = 0; attempt < 10; attempt++) {
            GolemSpawn coverage = acceptPatrolPoint(record, entity, coverageGridPatrolPoint(record, entity, cluster, attempt, context), attempt >= 7, context);
            if (coverage != null) return coverage;
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            int width = Math.max(1, GuildStore.maxX(territory) - GuildStore.minX(territory) + 1);
            int depth = Math.max(1, GuildStore.maxZ(territory) - GuildStore.minZ(territory) + 1);
            int fx = GuildStore.minX(territory) + Math.floorMod(String.valueOf(record.uuid).hashCode() + attempt * 17, width);
            int fz = GuildStore.minZ(territory) + Math.floorMod(String.valueOf(record.uuid).hashCode() / 11 + attempt * 19, depth);
            GolemSpawn fallback = safeClusterPatrolPoint(level, cluster, fx, fz);
            fallback = acceptPatrolPoint(record, entity, fallback, true, context);
            if (fallback != null) return fallback;
        }
        return null;
    }

    private static GolemPatrolContext buildGolemPatrolContext(GuildStore.Golem record, Entity entity, GuildStore.Territory current, List<GuildStore.Territory> cluster) {
        if (record == null || entity == null) return GolemPatrolContext.EMPTY;
        List<GuildStore.Territory> safeCluster = cluster == null ? Collections.emptyList() : cluster;
        boolean night = entity.level() instanceof ServerLevel level && isNightTime(level);
        Entity focus = null;
        int bestThreat = 0;
        boolean dangerNearMember = false;
        boolean waterDanger = false;
        if (!safeCluster.isEmpty()) {
            for (GuildStore.Territory territory : safeCluster) {
                Entity candidate = findPatrolFocus(record.guildId, territory, entity.level());
                if (candidate == null) continue;
                int threat = Math.max(1, golemThreatPriority(candidate));
                boolean nearMember = isNearGuildMember(record.guildId, candidate);
                boolean nearWater = candidate.level() instanceof ServerLevel sl && hasWaterNear(sl, candidate.blockPosition(), GOLEM_PATROL_WATER_AVOID_RADIUS + 2);
                int score = threat + (nearMember ? 260 : 0) + (nearWater ? 80 : 0) + (night ? 55 : 0);
                if (score > bestThreat) {
                    bestThreat = score;
                    focus = candidate;
                    dangerNearMember = nearMember;
                    waterDanger = nearWater && nearMember;
                }
            }
        }
        GuildAwarenessSnapshot awareness = guildAwarenessSnapshot(record.guildId, entity.level(), safeCluster, night ? 40 : 28);
        if (focus == null && current != null) focus = findExposedGuildMember(record.guildId, safeCluster, entity.level());
        if (focus == null && awareness != null) focus = awareness.focus;
        if (awareness != null && awareness.hostileNearMember) dangerNearMember = true;
        if (awareness != null && awareness.waterDanger) waterDanger = true;
        bestThreat = Math.max(bestThreat, awareness == null ? 0 : awareness.threatScore);
        if (focus != null && focus.level() instanceof ServerLevel sl && hasWaterNear(sl, focus.blockPosition(), GOLEM_PATROL_WATER_AVOID_RADIUS + 2) && isNearGuildMember(record.guildId, focus)) waterDanger = true;
        return new GolemPatrolContext(safeCluster, night, focus, bestThreat, dangerNearMember, waterDanger);
    }

    private static GolemSpawn pointNearFocusInsideCluster(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster, BlockPos focusPos) {
        return pointNearFocusInsideCluster(record, level, cluster, focusPos, null);
    }

    private static GolemSpawn pointNearFocusInsideCluster(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster, BlockPos focusPos, GolemPatrolContext context) {
        if (record == null || level == null || cluster == null || cluster.isEmpty() || focusPos == null) return null;
        int golemIndex = stableGuildGolemIndex(record);
        int active = Math.max(1, activeGuildGolemCount(record.guildId));
        double baseAngle = (Math.PI * 2.0D) * (Math.floorMod(golemIndex, active) / (double)active);
        boolean night = context == null ? isNightTime(level) : context.night;
        int baseRadius = context != null && context.dangerNearGuildMember ? 4 : (night ? 7 : 5);
        for (int attempt = 0, maxAttempts = dynamicPatrolPointAttempts(night); attempt < maxAttempts; attempt++) {
            double angle = baseAngle + attempt * 0.61D + (level.getGameTime() % 900L) * 0.0017D;
            int radius = baseRadius + (attempt % 9) * (night ? 2 : 3);
            int x = focusPos.getX() + (int)Math.round(Math.cos(angle) * radius);
            int z = focusPos.getZ() + (int)Math.round(Math.sin(angle) * radius);
            GolemSpawn point = safeClusterPatrolPoint(level, cluster, x, z);
            point = acceptWaterPolicy(point, context);
            if (point != null) return point;
        }
        return null;
    }

    private enum GolemPatrolRole {
        ANCHOR,
        PERIMETER,
        SWEEPER,
        MEMBER_GUARD,
        RESPONSE,
        INTERCEPTOR,
        WATER_GUARD
    }

    private static GolemPatrolRole patrolRole(GuildStore.Golem record) {
        return patrolRole(record, null);
    }

    private static GolemPatrolRole patrolRole(GuildStore.Golem record, GolemPatrolContext context) {
        if (record != null && record.elite) return GolemPatrolRole.RESPONSE;
        if (context != null && context.dangerNearGuildMember) return GolemPatrolRole.INTERCEPTOR;
        if (context != null && context.waterDangerMode) return GolemPatrolRole.WATER_GUARD;
        int index = stableGuildGolemIndex(record);
        int salt = Math.floorMod(String.valueOf(record == null ? "" : record.guildId).hashCode(), 7);
        int bucket = Math.floorMod(index + salt, 8);

        // Вдень головна ціль — повне покриття всієї площі: sweeper/perimeter/member guard.
        // Вночі гільдія переходить у захисний режим: більше периметра, перехоплення й реакції.
        if (context != null && context.night) {
            return switch (bucket) {
                case 0 -> GolemPatrolRole.PERIMETER;
                case 1, 2 -> GolemPatrolRole.MEMBER_GUARD;
                case 3 -> GolemPatrolRole.INTERCEPTOR;
                case 4 -> GolemPatrolRole.RESPONSE;
                case 5 -> GolemPatrolRole.WATER_GUARD;
                case 6 -> GolemPatrolRole.SWEEPER;
                default -> GolemPatrolRole.ANCHOR;
            };
        }

        return switch (bucket) {
            case 0 -> GolemPatrolRole.ANCHOR;
            case 1, 2, 3 -> GolemPatrolRole.SWEEPER;
            case 4, 5 -> GolemPatrolRole.MEMBER_GUARD;
            case 6 -> GolemPatrolRole.RESPONSE;
            default -> GolemPatrolRole.PERIMETER;
        };
    }

    private static boolean shouldReroutePatrol(GuildStore.Golem record, Entity entity, GuildStore.Territory current, List<GuildStore.Territory> cluster, GolemPatrolRole role, GolemPatrolMemory memory, long tick) {
        return shouldReroutePatrol(record, entity, current, cluster, role, memory, GOLEM_PATROL_PLAN.get(record == null ? "" : record.uuid), buildGolemPatrolContext(record, entity, current, cluster), tick);
    }

    private static boolean shouldReroutePatrol(GuildStore.Golem record, Entity entity, GuildStore.Territory current, List<GuildStore.Territory> cluster, GolemPatrolRole role, GolemPatrolMemory memory, GolemPatrolPlan plan, GolemPatrolContext context, long tick) {
        if (record == null || entity == null || !(entity instanceof Mob mob)) return false;
        if (memory == null || plan == null) return true;
        if (!GOLEM_PATROL_SYSTEM_VERSION.equals(plan.version)) return true;
        if (context == null) context = buildGolemPatrolContext(record, entity, current, cluster);
        boolean night = context.night;
        long maxAge = switch (role) {
            case RESPONSE -> night ? 90L : 190L;
            case INTERCEPTOR -> night ? 80L : 170L;
            case WATER_GUARD -> night ? 130L : 245L;
            case PERIMETER -> night ? 170L : 360L;
            case MEMBER_GUARD -> night ? 135L : 255L;
            case ANCHOR -> night ? 220L : 430L;
            case SWEEPER -> night ? 190L : 390L;
        };
        if (tick - memory.tick >= maxAge) return true;
        if (context.dangerNearGuildMember && !plan.dangerMode) return true;
        if (context.waterDangerMode && !plan.waterMode) return true;
        if (context.bestThreatScore >= 250 && (role == GolemPatrolRole.RESPONSE || role == GolemPatrolRole.INTERCEPTOR || role == GolemPatrolRole.MEMBER_GUARD)) return true;
        if (night && current != null && patrolThreatScore(record.guildId, current, entity.level()) > 0 && (role == GolemPatrolRole.RESPONSE || role == GolemPatrolRole.INTERCEPTOR)) return true;
        if (cluster != null && !cluster.isEmpty() && currentGolemTarget(mob) != null) return true;
        if (mob.getNavigation().isDone()) return true;
        GolemNavigationMemory nav = GOLEM_NAV_MEMORY.get(record.uuid);
        if (nav != null && nav.blockedChecks >= GOLEM_SOFT_REROUTE_BLOCKED_CHECKS) return true;
        if (memory != null && entity instanceof Mob patrolMob && entity.level() instanceof ServerLevel patrolLevel) {
            GolemSpawn planned = new GolemSpawn(patrolLevel, memory.x, memory.y, memory.z, GuildStore.territoryAt(patrolLevel, new BlockPos((int)Math.floor(memory.x), (int)Math.floor(memory.y), (int)Math.floor(memory.z))));
            if (isGolemRouteCongested(record, patrolMob, planned, context)) return true;
        }
        if (entity.level() instanceof ServerLevel level) {
            BlockPos target = new BlockPos((int)Math.floor(memory.x), (int)Math.floor(memory.y), (int)Math.floor(memory.z));
            if (!isGolemWalkableSpot(level, target)) return true;
            if (!context.waterDangerMode && hasWaterNear(level, target, GOLEM_PATROL_WATER_AVOID_RADIUS)) return true;
        }
        return false;
    }

    private static double patrolSpeed(GuildStore.Golem record, Entity entity, GolemPatrolRole role) {
        return patrolSpeed(record, entity, role, null);
    }

    private static double patrolSpeed(GuildStore.Golem record, Entity entity, GolemPatrolRole role, GolemPatrolContext context) {
        boolean night = context != null ? context.night : (entity != null && entity.level() instanceof ServerLevel level && isNightTime(level));
        double speed = night ? 0.84D : 0.68D;
        if (record != null && record.elite) speed += night ? 0.17D : 0.12D;
        if (role == GolemPatrolRole.RESPONSE || role == GolemPatrolRole.INTERCEPTOR) speed += night ? 0.12D : 0.07D;
        if (role == GolemPatrolRole.WATER_GUARD) speed += 0.03D;
        if (role == GolemPatrolRole.ANCHOR) speed -= night ? 0.02D : 0.06D;
        if (context != null && context.dangerNearGuildMember) speed += 0.08D;
        return Math.max(0.55D, Math.min(1.12D, speed));
    }

    private static GuildStore.Territory assignedTerritoryByIndex(GuildStore.Golem record, Entity entity, List<GuildStore.Territory> cluster) {
        if (cluster == null || cluster.isEmpty()) return null;
        int index = stableGuildGolemIndex(record);
        long phase = entity == null ? 0L : entity.level().getGameTime() / 4800L;
        return cluster.get(Math.floorMod(index + (int)(phase % cluster.size()), cluster.size()));
    }

    private static GolemSpawn coverageGridPatrolPoint(GuildStore.Golem record, Entity entity, List<GuildStore.Territory> cluster, int attempt) {
        return coverageGridPatrolPoint(record, entity, cluster, attempt, null);
    }

    private static GolemSpawn coverageGridPatrolPoint(GuildStore.Golem record, Entity entity, List<GuildStore.Territory> cluster, int attempt, GolemPatrolContext context) {
        if (record == null || entity == null || cluster == null || cluster.isEmpty() || !(entity.level() instanceof ServerLevel level)) return null;
        ClusterBounds bounds = clusterBounds(cluster);
        if (bounds == null) return null;

        boolean night = context == null ? isNightTime(level) : context.night;
        GolemSpawn prepared = precomputedPatrolPoint(record, level, cluster, context, attempt);
        if (prepared != null && (attempt <= 1 || isGolemGovernorThrottled() || isMassiveGolemServerMode())) return prepared;
        int active = Math.max(1, activeGuildGolemCount(record.guildId));
        int area = clusterAreaBlocks(cluster);

        // Patrol 3.0: широка deterministic sweep-сітка. Клітин більше, ніж големів,
        // тому маршрути не крутяться в короткому колі й поступово покривають всю територію.
        int areaCells = Math.max(GOLEM_PATROL_COVERAGE_MIN_CELLS, area / (night ? 192 : 256));
        int roleCells = Math.max(active * (night ? 8 : 12), cluster.size() * (night ? 8 : 14));
        int slots = Math.max(GOLEM_PATROL_COVERAGE_MIN_CELLS, Math.min(GOLEM_PATROL_COVERAGE_MAX_CELLS, Math.max(areaCells, roleCells)));
        int columns = Math.max(1, (int)Math.ceil(Math.sqrt(slots * Math.max(1.0D, (bounds.maxX - bounds.minX + 1.0D) / Math.max(1.0D, bounds.maxZ - bounds.minZ + 1.0D)))));
        int rows = Math.max(1, (int)Math.ceil(slots / (double)columns));
        slots = Math.max(1, columns * rows);

        long dayTime = level.getDayTime() % 24000L;
        long segmentTicks = night ? 520L : 1500L;
        int phase = (int)((level.getGameTime() / segmentTicks + (dayTime / (night ? 3000L : 6000L))) % slots);
        int index = stableGuildGolemIndex(record);
        int stride = coprimeCoverageStride(slots, active, index);
        int baseCell = Math.floorMod(index * Math.max(1, slots / active) + phase * stride + attempt * (stride + 3), slots);

        GolemSpawn best = null;
        int bestScore = Integer.MIN_VALUE;
        int candidateBudget = Math.min(dynamicPatrolPointAttempts(night), night ? 32 : 40);
        for (int i = 0; i < candidateBudget; i++) {
            int cell = Math.floorMod(baseCell + i * stride, slots);
            int cx = cell % columns;
            int cz = cell / columns;
            if ((cz & 1) == 1) cx = columns - 1 - cx; // snake-прохід без коротких стрибків між рядами

            int x = interpolate(bounds.minX, bounds.maxX, (cx + 0.5D) / columns);
            int z = interpolate(bounds.minZ, bounds.maxZ, (cz + 0.5D) / rows);
            int spread = night ? 3 + (i % 4) * 2 : 5 + (i % 5) * 3;
            int hash = String.valueOf(record.uuid).hashCode();
            x += Math.floorMod(hash + i * 17 + attempt * 31, spread * 2 + 1) - spread;
            z += Math.floorMod(hash / 7 + i * 19 + attempt * 23, spread * 2 + 1) - spread;

            GolemSpawn point = safeClusterPatrolPoint(level, cluster, x, z);
            point = acceptWaterPolicy(point, context);
            if (point == null) continue;

            int score = patrolPointScore(record, entity, point, context);
            score += coverageNoveltyScore(record, entity, point, context);
            if (score > bestScore) {
                bestScore = score;
                best = point;
            }
            if (score >= (night ? 105 : 115)) return point;
        }
        return best;
    }

    private static int clusterAreaBlocks(List<GuildStore.Territory> cluster) {
        if (cluster == null || cluster.isEmpty()) return 0;
        int area = 0;
        for (GuildStore.Territory territory : cluster) {
            if (territory == null) continue;
            int width = Math.max(1, GuildStore.maxX(territory) - GuildStore.minX(territory) + 1);
            int depth = Math.max(1, GuildStore.maxZ(territory) - GuildStore.minZ(territory) + 1);
            area = Math.min(1_000_000, area + width * depth);
        }
        return Math.max(1, area);
    }

    private static int coprimeCoverageStride(int slots, int active, int index) {
        if (slots <= 2) return 1;
        int stride = Math.max(3, slots / Math.max(2, active) + 1 + Math.floorMod(index * 2 + 1, 7));
        if ((stride & 1) == 0) stride++;
        while (gcd(stride, slots) != 1 && stride < slots * 2) stride += 2;
        return Math.max(1, Math.floorMod(stride, slots));
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.max(1, a);
    }

    private static int coverageNoveltyScore(GuildStore.Golem record, Entity entity, GolemSpawn point, GolemPatrolContext context) {
        if (record == null || entity == null || point == null) return 0;
        boolean night = context != null && context.night;
        double distance = Math.sqrt(distanceSqr(entity.getX(), entity.getY(), entity.getZ(), point.x, point.y, point.z));
        double minDistance = night ? GOLEM_PATROL_NIGHT_MIN_DISTANCE : GOLEM_PATROL_DAY_MIN_DISTANCE;
        int score = 0;
        if (distance < minDistance) score -= (int)((minDistance - distance) * (night ? 5.0D : 7.0D));
        else score += Math.min(night ? 28 : 46, (int)((distance - minDistance) * (night ? 1.1D : 1.6D)));

        GolemPatrolMemory memory = GOLEM_PATROL_MEMORY.get(record.uuid);
        if (memory != null) {
            double last = Math.sqrt(distanceSqr(memory.x, memory.y, memory.z, point.x, point.y, point.z));
            if (last < minDistance * 0.85D) score -= night ? 18 : 34;
            else score += Math.min(night ? 20 : 36, (int)(last * 0.75D));
        }
        if (context != null && context.focus != null) {
            double focusDistance = Math.sqrt(distanceSqr(point.x, point.y, point.z, context.focus.getX(), context.focus.getY(), context.focus.getZ()));
            if (night) score += Math.max(0, 42 - (int)(focusDistance * 1.8D));
        }
        score -= routeReservationPenalty(record, point, context);
        return score;
    }

    private static int patrolPointScore(GuildStore.Golem record, Entity entity, GolemSpawn point, GolemPatrolContext context) {
        if (record == null || entity == null || point == null || point.level == null) return Integer.MIN_VALUE / 2;
        int score = 60;
        BlockPos pos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
        TerrainProfile terrain = terrainProfile(point.level, pos);
        PatrolCellInsight insight = patrolCellInsight(record, point, context);
        if (insight != null) {
            score -= insight.penalty;
            score += context != null && context.night ? insight.nightBonus : insight.dayBonus;
            if (insight.routeReject) score -= GOLEM_TERRITORY_ANALYSIS_ROUTE_REJECT;
        }

        if (context != null && context.focus != null) {
            double d = distanceSqr(point.x, point.y, point.z, context.focus.getX(), context.focus.getY(), context.focus.getZ());
            score += Math.max(0, (context.night ? 105 : 78) - (int)(Math.sqrt(d) * (context.night ? 5.2D : 3.7D)));
        }
        if (isPatrolDestinationCrowded(record, point, context != null && context.dangerNearGuildMember ? 5.0D : 8.0D)) score -= 80;
        if (terrain != null) {
            score -= terrain.penalty;
            if (terrain.openSides >= 3) score += 12;
            if (terrain.roughness <= 2) score += 10;
            if (terrain.roughness >= 6) score -= 28;
            if (terrain.water > 0) score += context != null && context.waterDangerMode ? Math.min(24, terrain.water * 3) : -Math.min(110, terrain.water * 18);
            if (terrain.deepDrops > 0) score -= 170;
            if (terrain.structureBlockers > 0) score -= Math.min(260, terrain.structureBlockers * 38);
            if (terrain.narrowPassages > 0) score -= 150;
            if (terrain.balconyEdges > 0) score -= 210;
            if (terrain.buildingShells > 0 && terrain.openSides <= 1) score -= 95;
            else if (terrain.buildingShells > 0) score -= Math.min(46, terrain.buildingShells * 7);
        } else {
            if (hasWaterNear(point.level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS)) score += context != null && context.waterDangerMode ? 20 : -90;
            if (isNearDeepDrop(point.level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) score -= 150;
        }
        score -= learnedRoutePenalty(record, point);
        score -= routeReservationPenalty(record, point, context);

        double dist = Math.sqrt(distanceSqr(entity.getX(), entity.getY(), entity.getZ(), point.x, point.y, point.z));
        if (dist < 10.0D) score -= 35;
        if (dist > 18.0D) score += Math.min(context != null && context.night ? 24 : 44, (int)(dist * 0.9D));
        // Дорогий native pathfinding запускається тільки для обраної точки в acceptPatrolPoint.
        // Тут лишається дешева оцінка рельєфу, щоб багато гільдій не просаджували TPS.
        if (terrain != null && dist > 48.0D && terrain.roughness >= 6) score -= 18;
        return score;
    }

    private static GolemSpawn pointOnClusterPerimeter(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster) {
        return pointOnClusterPerimeter(record, level, cluster, null);
    }

    private static GolemSpawn pointOnClusterPerimeter(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster, GolemPatrolContext context) {
        if (record == null || level == null || cluster == null || cluster.isEmpty()) return null;
        ClusterBounds bounds = clusterBounds(cluster);
        if (bounds == null) return null;
        int width = Math.max(1, bounds.maxX - bounds.minX);
        int depth = Math.max(1, bounds.maxZ - bounds.minZ);
        int perimeter = Math.max(4, width * 2 + depth * 2);
        boolean night = (context != null && context.night) || isNightTime(level);
        long phase = level.getGameTime() / (night ? 520L : 1200L);
        int active = Math.max(1, activeGuildGolemCount(record.guildId));
        int sectorStride = Math.max(6, perimeter / Math.max(2, active * (night ? 2 : 3)));
        int step = Math.floorMod(stableGuildGolemIndex(record) * sectorStride + (int)(phase % perimeter) * sectorStride, perimeter);
        int x;
        int z;
        if (step < width) {
            x = bounds.minX + step;
            z = bounds.minZ;
        } else if (step < width + depth) {
            x = bounds.maxX;
            z = bounds.minZ + (step - width);
        } else if (step < width * 2 + depth) {
            x = bounds.maxX - (step - width - depth);
            z = bounds.maxZ;
        } else {
            x = bounds.minX;
            z = bounds.maxZ - (step - width * 2 - depth);
        }

        int centerX = (bounds.minX + bounds.maxX) / 2;
        int centerZ = (bounds.minZ + bounds.maxZ) / 2;
        for (int inset = 0; inset <= 14; inset++) {
            int tx = x + Integer.compare(centerX, x) * inset;
            int tz = z + Integer.compare(centerZ, z) * inset;
            GolemSpawn point = safeClusterPatrolPoint(level, cluster, tx, tz);
            point = acceptWaterPolicy(point, context);
            if (point != null) return point;
        }
        return null;
    }

    private static GolemSpawn pointAroundAssignedTotem(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, List<GuildStore.Territory> cluster) {
        return pointAroundAssignedTotem(record, entity, territory, cluster, null);
    }

    private static GolemSpawn pointAroundAssignedTotem(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, List<GuildStore.Territory> cluster, GolemPatrolContext context) {
        if (record == null || entity == null || territory == null || !(entity.level() instanceof ServerLevel level)) return null;
        GuildStore.Territory assigned = assignedTerritoryByIndex(record, entity, cluster);
        if (assigned == null) assigned = territory;
        int active = Math.max(1, activeGuildGolemCount(record.guildId));
        double angle = (Math.PI * 2.0D) * (Math.floorMod(stableGuildGolemIndex(record), active) / (double)active)
                + (level.getGameTime() / 2400.0D);
        int maxRadius = Math.max(5, Math.min(20, Math.max(Math.abs(GuildStore.maxX(assigned) - GuildStore.minX(assigned)), Math.abs(GuildStore.maxZ(assigned) - GuildStore.minZ(assigned))) / 3));
        for (int r = maxRadius; r >= 2; r--) {
            int x = assigned.x + (int)Math.round(Math.cos(angle) * r);
            int z = assigned.z + (int)Math.round(Math.sin(angle) * r);
            GolemSpawn point = safeClusterPatrolPoint(level, cluster, x, z);
            point = acceptWaterPolicy(point, context);
            if (point != null) return point;
            angle += 0.71D;
        }
        return acceptWaterPolicy(safeClusterPatrolPoint(level, cluster, assigned.x, assigned.z), context);
    }

    private static GolemSpawn pointNearExposedGuildMember(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster) {
        return pointNearExposedGuildMember(record, level, cluster, null);
    }

    private static GolemSpawn pointNearExposedGuildMember(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster, GolemPatrolContext context) {
        Entity member = findExposedGuildMember(record == null ? null : record.guildId, cluster, level);
        if (member == null) return null;
        return pointNearFocusInsideCluster(record, level, cluster, member.blockPosition(), context);
    }

    private static GolemSpawn interceptorPatrolPoint(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, List<GuildStore.Territory> cluster, GolemPatrolContext context) {
        if (record == null || entity == null || territory == null || !(entity.level() instanceof ServerLevel level)) return null;
        if (context != null && context.focus != null) {
            GolemSpawn p = pointNearFocusInsideCluster(record, level, cluster, context.focus.blockPosition(), context);
            if (p != null) return p;
        }
        GolemSpawn perimeter = pointOnClusterPerimeter(record, level, cluster, context);
        if (perimeter != null) return perimeter;
        return coverageGridPatrolPoint(record, entity, cluster, 1, context);
    }

    private static GolemSpawn waterAwarePatrolPoint(GuildStore.Golem record, Entity entity, GuildStore.Territory territory, List<GuildStore.Territory> cluster, GolemPatrolContext context) {
        if (record == null || entity == null || territory == null || !(entity.level() instanceof ServerLevel level)) return null;
        Entity focus = context == null ? null : context.focus;
        if (focus != null) {
            GolemSpawn shore = nearestDryPointAround(level, cluster, focus.blockPosition(), context, 3, 18);
            if (shore != null) return shore;
        }
        GolemSpawn dry = driestPointInTerritory(record, level, cluster, territory, context);
        if (dry != null) return dry;
        return coverageGridPatrolPoint(record, entity, cluster, 2, context);
    }

    private static GolemSpawn driestPointInTerritory(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster, GuildStore.Territory territory, GolemPatrolContext context) {
        if (record == null || level == null || territory == null) return null;
        int hash = String.valueOf(record.uuid).hashCode();
        GolemSpawn best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int attempt = 0, maxAttempts = dynamicPatrolPointAttempts(context == null ? false : context.night); attempt < maxAttempts; attempt++) {
            int x = GuildStore.minX(territory) + Math.floorMod(hash + attempt * 17, Math.max(1, GuildStore.maxX(territory) - GuildStore.minX(territory) + 1));
            int z = GuildStore.minZ(territory) + Math.floorMod(hash / 13 + attempt * 23, Math.max(1, GuildStore.maxZ(territory) - GuildStore.minZ(territory) + 1));
            GolemSpawn point = safeClusterPatrolPoint(level, cluster, x, z);
            point = acceptWaterPolicy(point, context);
            if (point == null) continue;
            int score = -nearbyWaterCount(level, new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z)), 4) * 12;
            if (score > bestScore) {
                bestScore = score;
                best = point;
            }
            if (score >= 0) return point;
        }
        return best;
    }

    private static GolemSpawn nearestDryPointAround(ServerLevel level, List<GuildStore.Territory> cluster, BlockPos center, GolemPatrolContext context, int minRadius, int maxRadius) {
        if (level == null || cluster == null || cluster.isEmpty() || center == null) return null;
        for (int radius = Math.max(1, minRadius); radius <= maxRadius; radius++) {
            for (int i = 0; i < 16; i++) {
                double angle = (Math.PI * 2.0D) * (i / 16.0D);
                int x = center.getX() + (int)Math.round(Math.cos(angle) * radius);
                int z = center.getZ() + (int)Math.round(Math.sin(angle) * radius);
                GolemSpawn point = safeClusterPatrolPoint(level, cluster, x, z);
                point = acceptWaterPolicy(point, context);
                if (point != null && !hasWaterNear(level, new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z)), GOLEM_PATROL_WATER_AVOID_RADIUS)) return point;
            }
        }
        return null;
    }


    private static GuildAwarenessSnapshot guildAwarenessSnapshot(String guildId, LevelAccessor level, List<GuildStore.Territory> cluster, int radius) {
        if (guildId == null || guildId.isBlank() || level == null || cluster == null || cluster.isEmpty()) return GuildAwarenessSnapshot.EMPTY;
        String dimension = GuildStore.dimensionId(level);
        String key = guildId + "|" + dimension + "|" + GuildStore.territoryTopologyRevision() + "|" + Math.max(1, radius);
        GuildAwarenessSnapshot cached = GOLEM_GUILD_AWARENESS_CACHE.get(key);
        if (cached != null && cached.frame == GOLEM_AI_FRAME) return cached;
        int members = 0;
        int others = 0;
        int villagers = 0;
        int hostiles = 0;
        int threatScore = 0;
        boolean hostileNearMember = false;
        boolean waterDanger = false;
        Entity bestFocus = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        if (server != null && server.getPlayerList() != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null || player.level() != level || !player.isAlive()) continue;
                boolean inNetwork = isInsideClusterBuffer(cluster, player.blockPosition(), Math.max(8, radius));
                if (!inNetwork) continue;
                boolean member = GuildStore.isMemberOfGuild(player, guildId);
                if (member) members++; else others++;
                double score = member ? 120.0D : 35.0D;
                if (player.getHealth() < player.getMaxHealth() * 0.65F) score += 100.0D;
                if (player.level() instanceof ServerLevel sl && hasWaterNear(sl, player.blockPosition(), GOLEM_PATROL_WATER_AVOID_RADIUS + 2)) {
                    score += member ? 35.0D : 10.0D;
                    if (member) waterDanger = true;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestFocus = player;
                }
            }
        }

        Entity scanCenter = bestFocus;
        if (scanCenter != null) {
            int budget = isGolemGovernorThrottled() ? 18 : 36;
            for (Entity entity : entitiesNear(scanCenter, Math.max(24.0D, radius), budget)) {
                if (entity == null || entity == scanCenter || !entity.isAlive()) continue;
                if (isGuildGolem(entity)) continue;
                if (entity instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, guildId)) continue;
                if (shouldIgnoreUndergroundGolemTarget(entity)) continue;
                String id = entityTypeId(entity);
                if (id.contains("villager") || id.contains("wandering_trader") || id.contains("allay")) {
                    villagers++;
                    continue;
                }
                boolean hostile = entity.getType().getCategory() == MobCategory.MONSTER || isHighAlertThreat(entity);
                if (!hostile) continue;
                if (!isInsideClusterBuffer(cluster, entity.blockPosition(), Math.max(12, radius))) continue;
                int priority = Math.max(1, golemThreatPriority(entity));
                hostiles++;
                threatScore += priority;
                boolean nearMember = isNearGuildMember(guildId, entity);
                if (nearMember) hostileNearMember = true;
                double score = priority + (nearMember ? 300.0D : 0.0D) + (isHighAlertThreat(entity) ? 450.0D : 0.0D) - (bestFocus == null ? 0.0D : Math.sqrt(entity.distanceToSqr(bestFocus)) * 2.0D);
                if (score > bestScore) {
                    bestScore = score;
                    bestFocus = entity;
                }
            }
        }
        GuildAwarenessSnapshot out = new GuildAwarenessSnapshot(GOLEM_AI_FRAME, members, others, villagers, hostiles, threatScore, hostileNearMember, waterDanger, bestFocus);
        GOLEM_GUILD_AWARENESS_CACHE.put(key, out);
        return out;
    }

    private static Entity findExposedGuildMember(String guildId, List<GuildStore.Territory> cluster, LevelAccessor level) {
        if (guildId == null || cluster == null || cluster.isEmpty() || server == null || server.getPlayerList() == null) return null;
        Entity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || player.level() != level || !player.isAlive()) continue;
            if (!GuildStore.isMemberOfGuild(player, guildId)) continue;
            if (!isInsideCluster(cluster, player.blockPosition())) continue;
            int hostileCount = nearbyHostileMobs(player, level);
            int score = 20;
            if (isNearClusterBorder(cluster, player)) score += 45;
            score += hostileCount * 18;
            score -= nearbyGuildGolems(player, guildId, level) * 22;
            if (player.getHealth() < player.getMaxHealth() * 0.60F) score += 30;
            if (level instanceof ServerLevel sl && isNightTime(sl)) score += 22;
            if (player.level() instanceof ServerLevel sl && hasWaterNear(sl, player.blockPosition(), GOLEM_PATROL_WATER_AVOID_RADIUS + 2)) score += 10;
            if (score > bestScore) {
                bestScore = score;
                best = player;
            }
        }
        return best;
    }

    private static int nearbyHostileMobs(Entity origin, LevelAccessor level) {
        if (origin == null) return 0;
        int count = 0;
        for (Entity entity : entitiesNear(origin, 18.0D, 32)) {
            if (entity == null || entity == origin || !entity.isAlive()) continue;
            if (shouldIgnoreUndergroundGolemTarget(entity)) continue;
            if (golemThreatPriority(entity) <= 0) continue;
            if (entity.distanceToSqr(origin) <= 18.0D * 18.0D) count += isHighAlertThreat(entity) ? 3 : 1;
        }
        return count;
    }

    private static GolemSpawn acceptPatrolPoint(GuildStore.Golem record, Entity entity, GolemSpawn point, boolean allowCrowded) {
        return acceptPatrolPoint(record, entity, point, allowCrowded, null);
    }

    private static GolemSpawn acceptPatrolPoint(GuildStore.Golem record, Entity entity, GolemSpawn point, boolean allowCrowded, GolemPatrolContext context) {
        point = clampPointIntoGuildCluster(record, entity, point, context);
        point = acceptWaterPolicy(point, context);
        if (point == null || point.level == null) return null;
        BlockPos pos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
        if (!isSafeTeleportSpot(point.level, pos)) {
            rememberRouteLearning(record, entity, point, false, "unsafe-spot");
            return null;
        }
        TerrainProfile terrain = terrainProfile(point.level, pos);
        PatrolCellInsight insight = patrolCellInsight(record, point, context);
        if (insight != null && insight.routeReject && !(context != null && context.dangerNearGuildMember)) {
            rememberRouteLearning(record, entity, point, false, "pretrained-structure-reject");
            return null;
        }
        if ((terrain != null && (terrain.deepDrops > 0 || terrain.roughness > GOLEM_DEEP_DROP_DANGER_HEIGHT || terrain.obstacles >= 5))
                || isNearDeepDrop(point.level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) {
            rememberRouteLearning(record, entity, point, false, "deep-drop");
            return null;
        }
        if (terrain != null && (terrain.structureBlockers >= 2 || terrain.narrowPassages > 0 || terrain.balconyEdges > 0)) {
            rememberRouteLearning(record, entity, point, false, terrain.balconyEdges > 0 ? "structure-balcony" : (terrain.narrowPassages > 0 ? "structure-narrow" : "structure-blocker"));
            return null;
        }
        if (!allowCrowded && isPatrolDestinationCrowded(record, point, context != null && context.dangerNearGuildMember ? 7.0D : 12.0D)) return null;
        if (!allowCrowded && isRouteReservedTooClose(record, point, context)) return null;
        if (entity instanceof Mob mob && !hasNavigablePatrolPath(mob, point)) {
            rememberRouteLearning(record, entity, point, false, "blocked-path");
            GolemSpawn detour = findRouteDetourPoint(record, mob, point, context);
            if (detour == null || !hasNavigablePatrolPath(mob, detour)) return null;
            return detour;
        }
        return point;
    }

    private static GolemSpawn clampPointIntoGuildCluster(GuildStore.Golem record, Entity entity, GolemSpawn point, GolemPatrolContext context) {
        if (record == null || point == null || point.level == null) return point;
        List<GuildStore.Territory> cluster = context == null ? Collections.emptyList() : context.cluster;
        if (cluster == null || cluster.isEmpty()) {
            GuildStore.Territory territory = point.territory;
            if (territory == null && entity != null) territory = GuildStore.territoryAt(entity.level(), entity.blockPosition());
            if (territory != null) cluster = contiguousGuildCluster(record.guildId, territory, GuildStore.dimensionId(point.level));
            if ((cluster == null || cluster.isEmpty()) && territory != null) cluster = List.of(territory);
        }
        if (cluster == null || cluster.isEmpty()) return point;
        BlockPos pos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
        if (isInsideCluster(cluster, pos)) return point;
        GolemSpawn nearest = nearestSafePointInsideCluster(point.level, cluster, pos, context);
        return nearest == null ? null : nearest;
    }

    private static GolemSpawn nearestSafePointInsideCluster(ServerLevel level, List<GuildStore.Territory> cluster, BlockPos around, GolemPatrolContext context) {
        if (level == null || cluster == null || cluster.isEmpty() || around == null) return null;
        for (int radius = 1; radius <= Math.max(8, GOLEM_ROUTE_DETOUR_OUTSIDE_BUFFER_BLOCKS + 4); radius++) {
            int samples = Math.max(8, radius * 8);
            for (int i = 0; i < samples; i++) {
                double angle = (Math.PI * 2.0D) * (i / (double)samples);
                int x = around.getX() + (int)Math.round(Math.cos(angle) * radius);
                int z = around.getZ() + (int)Math.round(Math.sin(angle) * radius);
                GolemSpawn candidate = safeClusterPatrolPoint(level, cluster, x, z);
                candidate = acceptWaterPolicy(candidate, context);
                if (candidate == null) continue;
                BlockPos cpos = new BlockPos((int)Math.floor(candidate.x), (int)Math.floor(candidate.y), (int)Math.floor(candidate.z));
                if (!isNearDeepDrop(level, cpos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) return candidate;
            }
        }
        return null;
    }

    private static void rememberRouteLearning(GuildStore.Golem record, Entity entity, GolemSpawn point, boolean success, String reason) {
        if (record == null || point == null || point.level == null || record.guildId == null) return;
        long now = point.level.getGameTime();
        int x = (int)Math.floor(point.x);
        int y = (int)Math.floor(point.y);
        int z = (int)Math.floor(point.z);
        String dimension = GuildStore.dimensionId(point.level);
        String cellKey = GuildStore.golemRouteLearningCellKey(record.guildId, dimension, x, z);
        int hash = Math.abs((cellKey + "|" + String.valueOf(record.uuid)).hashCode());
        Long last = GOLEM_ROUTE_LEARNING_SAMPLE_TICK.get(cellKey + "|" + (success ? "ok" : normalizeLearningReasonForKey(reason)));
        if (success) {
            // Успіхів завжди набагато більше, ніж збоїв. Для великої кількості гільдій
            // семплюємо їх рідко й агрегуємо по 16x16-комірках у GuildStore.
            if (Math.floorMod((int)GOLEM_AI_FRAME + hash, 12) != 0) return;
            if (last != null && now - last < 20L * 24L) return;
        } else {
            // Помилки важливіші, але теж debounce, щоб один застряглий голем не засмітив файл.
            if (last != null && now - last < 20L * 3L) return;
        }
        GOLEM_ROUTE_LEARNING_SAMPLE_TICK.put(cellKey + "|" + (success ? "ok" : normalizeLearningReasonForKey(reason)), now);
        GOLEM_LEARNING_PENALTY_CACHE.remove(cellKey);
        GuildStore.recordGolemRouteLearning(record.guildId, dimension, x, y, z, success, reason);
    }

    private static String normalizeLearningReasonForKey(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("deep")) return "deep";
        if (r.contains("water")) return "water";
        if (r.contains("stuck")) return "stuck";
        if (r.contains("unsafe")) return "unsafe";
        if (r.contains("blocked")) return "blocked";
        return r.length() > 16 ? r.substring(0, 16) : r;
    }

    private static int learnedRoutePenalty(GuildStore.Golem record, GolemSpawn point) {
        if (record == null || point == null || point.level == null || record.guildId == null) return 0;
        int x = (int)Math.floor(point.x);
        int y = (int)Math.floor(point.y);
        int z = (int)Math.floor(point.z);
        String dimension = GuildStore.dimensionId(point.level);
        String key = GuildStore.golemRouteLearningCellKey(record.guildId, dimension, x, z);
        LearningPenaltyCache cached = GOLEM_LEARNING_PENALTY_CACHE.get(key);
        if (cached != null && GOLEM_AI_FRAME - cached.frame <= 80L) return cached.penalty;
        int penalty = GuildStore.golemRouteLearningPenalty(record.guildId, dimension, x, y, z);
        GOLEM_LEARNING_PENALTY_CACHE.put(key, new LearningPenaltyCache(GOLEM_AI_FRAME, penalty));
        return penalty;
    }

    private static GolemSpawn acceptWaterPolicy(GolemSpawn point, GolemPatrolContext context) {
        if (point == null || point.level == null) return null;
        if (context != null && context.waterDangerMode) return point;
        BlockPos pos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
        if (hasWaterNear(point.level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS)) return null;
        return point;
    }

    private static int guildMembersInTerritory(String guildId, GuildStore.Territory territory, LevelAccessor level) {
        return territoryActivityStats(guildId, territory, level).members;
    }

    private static int borderActivityScore(String guildId, GuildStore.Territory territory, LevelAccessor level) {
        return territoryActivityStats(guildId, territory, level).borderScore;
    }

    private static Entity findPatrolFocus(String guildId, GuildStore.Territory territory, LevelAccessor level) {
        return territoryActivityStats(guildId, territory, level).focus;
    }

    private static int patrolThreatScore(String guildId, GuildStore.Territory territory, LevelAccessor level) {
        return territoryActivityStats(guildId, territory, level).threatScore;
    }

    private static int waterActivityScore(String guildId, GuildStore.Territory territory, LevelAccessor level) {
        return territoryActivityStats(guildId, territory, level).waterScore;
    }

    private static TerritoryActivityStats territoryActivityStats(String guildId, GuildStore.Territory territory, LevelAccessor level) {
        if (guildId == null || territory == null || level == null) return TerritoryActivityStats.EMPTY;
        long now = level instanceof ServerLevel sl ? sl.getGameTime() : GOLEM_AI_FRAME;
        String key = guildId + "|" + GuildStore.dimensionId(level) + "|" + String.valueOf(territory.id);
        TerritoryActivityCache cached = GOLEM_TERRITORY_ACTIVITY_CACHE.get(key);
        long ttl = isUltraGolemServerMode() ? 100L : (isExtremeGolemServerMode() ? 70L : (isMassiveGolemServerMode() ? 40L : 8L));
        if (cached != null && now - cached.tick <= ttl) return cached.stats;

        if (isUltraGolemServerMode() || isGolemGovernorThrottled()) {
            TerritoryActivityStats fast = fastPlayerTerritoryActivityStats(guildId, territory, level);
            GOLEM_TERRITORY_ACTIVITY_CACHE.put(key, new TerritoryActivityCache(now, GOLEM_AI_FRAME, fast));
            return fast;
        }

        int members = 0;
        int golems = 0;
        int borderScore = 0;
        int threatScore = 0;
        int waterScore = 0;
        Entity best = null;
        int bestScore = Integer.MIN_VALUE;
        double centerX = territory.x + 0.5D;
        double centerZ = territory.z + 0.5D;

        int scanned = 0;
        int scanBudget = dynamicTerritoryActivityScanBudget();
        for (Entity entity : scaledEntityScan(level, "territory-activity-" + String.valueOf(territory.id), scanBudget)) {
            if (++scanned > scanBudget) break;
            if (entity == null || !entity.isAlive() || !GuildStore.isInsideTerritory(territory, entity.blockPosition())) continue;
            if (isGuildGolem(entity) && Objects.equals(guildIdFromGuildGolem(entity), guildId)) golems++;

            boolean member = entity instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, guildId);
            if (member) members++;
            if (shouldIgnoreUndergroundGolemTarget(entity)) continue;

            int threat = golemThreatPriority(entity);
            if (threat > 0) threatScore += Math.max(6, threat / 95);
            if (member) threatScore += 3;

            if (isNearGuildBorder(territory, entity)) {
                if (threat > 0) borderScore += Math.max(8, threat / 110);
                if (member) borderScore += 4;
            }

            if (entity.level() instanceof ServerLevel sl && hasWaterNear(sl, entity.blockPosition(), GOLEM_PATROL_WATER_AVOID_RADIUS + 2)) {
                if (member) waterScore += 10;
                if (threat > 0) waterScore += Math.max(4, threat / 150);
            }

            int focusScore = 0;
            if (threat > 0) focusScore += Math.max(100, threat / 8);
            if (member) focusScore += 65;
            if (focusScore > 0) {
                double edgeWeight = Math.min(
                        Math.min(Math.abs(entity.getX() - GuildStore.minX(territory)), Math.abs(entity.getX() - GuildStore.maxX(territory))),
                        Math.min(Math.abs(entity.getZ() - GuildStore.minZ(territory)), Math.abs(entity.getZ() - GuildStore.maxZ(territory)))
                );
                double centerDist = Math.sqrt((entity.getX() - centerX) * (entity.getX() - centerX) + (entity.getZ() - centerZ) * (entity.getZ() - centerZ));
                focusScore += (int)Math.min(24, centerDist / 4.0D);
                focusScore -= (int)Math.min(18, edgeWeight / 3.0D);
                if (focusScore > bestScore) {
                    bestScore = focusScore;
                    best = entity;
                }
            }
        }

        TerritoryActivityStats stats = new TerritoryActivityStats(members, golems, borderScore, threatScore, waterScore, best);
        GOLEM_TERRITORY_ACTIVITY_CACHE.put(key, new TerritoryActivityCache(now, GOLEM_AI_FRAME, stats));
        return stats;
    }

    private static boolean hasNavigablePatrolPath(Mob mob, GolemSpawn point) {
        if (mob == null || point == null || point.level == null) return false;
        if (mob.level() != point.level) return false;
        double distance = distanceSqr(mob.getX(), mob.getY(), mob.getZ(), point.x, point.y, point.z);
        if (distance <= 3.0D * 3.0D) return true;

        long now = point.level.getGameTime();
        int tx = (int)Math.floor(point.x);
        int ty = (int)Math.floor(point.y);
        int tz = (int)Math.floor(point.z);
        String key = mob.getUUID() + "|" + (mob.blockPosition().getX() >> 3) + ":" + (mob.blockPosition().getZ() >> 3) + "|" + tx + ":" + ty + ":" + tz;
        PathReachabilityCache cached = GOLEM_PATH_CACHE.get(key);
        if (cached != null && now - cached.tick <= (cached.reachable ? 90L : 45L)) return cached.reachable;

        BlockPos targetPos = new BlockPos(tx, ty, tz);
        TerrainProfile targetTerrain = terrainProfile(point.level, targetPos);
        PatrolCellInsight routeInsight = patrolCellInsight(null, point, null);
        if (routeInsight != null && routeInsight.routeReject) {
            GOLEM_PATH_CACHE.put(key, new PathReachabilityCache(now, GOLEM_AI_FRAME, false));
            return false;
        }
        if (targetTerrain != null && (targetTerrain.deepDrops > 0
                || targetTerrain.obstacles >= 6
                || targetTerrain.roughness > GOLEM_DEEP_DROP_DANGER_HEIGHT + 2
                || targetTerrain.structureBlockers >= 3
                || targetTerrain.narrowPassages > 0
                || targetTerrain.balconyEdges > 0)) {
            GOLEM_PATH_CACHE.put(key, new PathReachabilityCache(now, GOLEM_AI_FRAME, false));
            return false;
        }

        boolean rough = roughWalkableCorridor(mob, point);
        boolean waterLine = corridorHasWaterHazard(mob, point);
        Boolean nativeResult = null;
        if ((rough || distance <= 38.0D * 38.0D || (waterLine && distance <= 70.0D * 70.0D)) && GOLEM_NATIVE_PATH_CHECKS_THIS_FRAME < nativePathBudgetThisFrame()) {
            GOLEM_NATIVE_PATH_CHECKS_THIS_FRAME++;
            nativeResult = tryNativeNavigationReachability(mob, point);
        }
        boolean result = nativeResult != null ? nativeResult : rough;
        GOLEM_PATH_CACHE.put(key, new PathReachabilityCache(now, GOLEM_AI_FRAME, result));
        return result;
    }

    private static Boolean tryNativeNavigationReachability(Mob mob, GolemSpawn point) {
        try {
            Object navigation = mob.getNavigation();
            if (navigation == null) return null;
            BlockPos target = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
            Object path = null;
            boolean foundCreatePath = false;
            for (Method method : navigation.getClass().getMethods()) {
                if (!method.getName().equals("createPath")) continue;
                Class<?>[] types = method.getParameterTypes();
                try {
                    if (types.length == 2 && BlockPos.class.isAssignableFrom(types[0]) && (types[1] == Integer.TYPE || types[1] == Integer.class)) {
                        path = method.invoke(navigation, target, 1);
                        foundCreatePath = true;
                        break;
                    }
                    if (types.length == 4 && isNumericParameter(types[0]) && isNumericParameter(types[1]) && isNumericParameter(types[2]) && (types[3] == Integer.TYPE || types[3] == Integer.class)) {
                        Object ax = numericArg(types[0], point.x);
                        Object ay = numericArg(types[1], point.y);
                        Object az = numericArg(types[2], point.z);
                        path = method.invoke(navigation, ax, ay, az, 1);
                        foundCreatePath = true;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (!foundCreatePath) return null;
            if (path == null) return false;
            for (String name : new String[]{"canReach", "reachesTarget"}) {
                try {
                    Object value = path.getClass().getMethod(name).invoke(path);
                    if (value instanceof Boolean b) return b;
                } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isNumericParameter(Class<?> type) {
        return type == Double.TYPE || type == Double.class || type == Float.TYPE || type == Float.class || type == Integer.TYPE || type == Integer.class;
    }

    private static Object numericArg(Class<?> type, double value) {
        if (type == Integer.TYPE || type == Integer.class) return (int)Math.floor(value);
        if (type == Float.TYPE || type == Float.class) return (float)value;
        return value;
    }

    private static boolean roughWalkableCorridor(Mob mob, GolemSpawn point) {
        if (mob == null || point == null || point.level == null) return false;
        int samples = Math.max(4, Math.min(30, (int)Math.ceil(Math.sqrt(distanceSqr(mob.getX(), mob.getY(), mob.getZ(), point.x, point.y, point.z)) / 2.5D)));
        int lastY = (int)Math.floor(mob.getY());
        for (int i = 1; i <= samples; i++) {
            double t = i / (double)samples;
            int x = (int)Math.floor(mob.getX() + (point.x - mob.getX()) * t);
            int z = (int)Math.floor(mob.getZ() + (point.z - mob.getZ()) * t);
            BlockPos pos = safeSurfaceBlockPos(point.level, x, z);
            if (pos == null) return false;
            GuildStore.Territory at = GuildStore.territoryAt(point.level, pos);
            if (point.territory != null && (at == null || !Objects.equals(at.guildId, point.territory.guildId))) {
                if (!isAllowedSurfaceDetourStep(point.level, point.territory.guildId, pos)) return false;
            }
            int dy = Math.abs(pos.getY() - lastY);
            if (dy > 4 && !canStepAroundVerticalBreak(point.level, x, z, lastY, pos.getY())) return false;
            if (dy > GOLEM_DEEP_DROP_DANGER_HEIGHT) return false;
            TerrainProfile terrain = terrainProfile(point.level, pos);
            if (terrain != null) {
                if (terrain.deepDrops > 0 || terrain.obstacles >= 4 || terrain.roughness > GOLEM_DEEP_DROP_DANGER_HEIGHT) return false;
                if (terrain.structureBlockers >= 2 || terrain.narrowPassages > 0 || terrain.balconyEdges > 0) return false;
                if (terrain.water > 0) return false;
            } else {
                if (isHardObstacleColumn(point.level, pos)) return false;
                if (structureRoutePenalty(point.level, pos) >= GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE) return false;
                if (isNearDeepDrop(point.level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) return false;
                if (hasWaterNear(point.level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS)) return false;
            }
            lastY = pos.getY();
        }
        return true;
    }

    private static boolean corridorHasWaterHazard(Mob mob, GolemSpawn point) {
        if (mob == null || point == null || point.level == null) return false;
        int samples = Math.max(4, Math.min(22, (int)Math.ceil(Math.sqrt(distanceSqr(mob.getX(), mob.getY(), mob.getZ(), point.x, point.y, point.z)) / 3.0D)));
        for (int i = 1; i <= samples; i++) {
            double t = i / (double)samples;
            int x = (int)Math.floor(mob.getX() + (point.x - mob.getX()) * t);
            int z = (int)Math.floor(mob.getZ() + (point.z - mob.getZ()) * t);
            BlockPos pos = rawSurfaceBlockPos(point.level, x, z);
            if (pos != null && hasWaterNear(point.level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS)) return true;
        }
        return false;
    }


    private static boolean corridorHasDeepDropHazard(Mob mob, GolemSpawn point) {
        if (mob == null || point == null || point.level == null) return true;
        int samples = Math.max(4, Math.min(26, (int)Math.ceil(Math.sqrt(distanceSqr(mob.getX(), mob.getY(), mob.getZ(), point.x, point.y, point.z)) / 2.75D)));
        int lastY = (int)Math.floor(mob.getY());
        for (int i = 1; i <= samples; i++) {
            double t = i / (double)samples;
            int x = (int)Math.floor(mob.getX() + (point.x - mob.getX()) * t);
            int z = (int)Math.floor(mob.getZ() + (point.z - mob.getZ()) * t);
            BlockPos pos = rawSurfaceBlockPos(point.level, x, z);
            if (pos == null) return true;
            int dy = Math.abs(pos.getY() - lastY);
            if (dy > GOLEM_DEEP_DROP_DANGER_HEIGHT) return true;
            if (dy > 4 && !canStepAroundVerticalBreak(point.level, x, z, lastY, pos.getY())) return true;
            if (isNearDeepDrop(point.level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) return true;
            if (isLikelyBalconyOrTerrace(point.level, pos) || isNarrowStructurePassage(point.level, pos)) return true;
            lastY = pos.getY();
        }
        return false;
    }

    private static boolean isAllowedSurfaceDetourStep(ServerLevel level, String guildId, BlockPos pos) {
        if (level == null || guildId == null || pos == null) return false;
        if (!isGolemWalkableSpot(level, pos)) return false;
        if (isNearDeepDrop(level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) return false;
        if (hasWaterNear(level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS)) return false;
        for (GuildStore.Territory territory : GuildStore.guildTerritories(guildId)) {
            if (territory == null || !Objects.equals(territory.dimension, GuildStore.dimensionId(level))) continue;
            if (pos.getX() >= GuildStore.minX(territory) - GOLEM_ROUTE_DETOUR_OUTSIDE_BUFFER_BLOCKS
                    && pos.getX() <= GuildStore.maxX(territory) + GOLEM_ROUTE_DETOUR_OUTSIDE_BUFFER_BLOCKS
                    && pos.getZ() >= GuildStore.minZ(territory) - GOLEM_ROUTE_DETOUR_OUTSIDE_BUFFER_BLOCKS
                    && pos.getZ() <= GuildStore.maxZ(territory) + GOLEM_ROUTE_DETOUR_OUTSIDE_BUFFER_BLOCKS) {
                return true;
            }
        }
        return false;
    }

    private static boolean canStepAroundVerticalBreak(ServerLevel level, int x, int z, int fromY, int toY) {
        if (level == null) return false;
        BlockPos from = new BlockPos(x, fromY, z);
        BlockPos to = new BlockPos(x, toY, z);
        if ((isLikelyBalconyOrTerrace(level, from) || isLikelyBalconyOrTerrace(level, to))
                && !hasNearbyStairOrTerraceExit(level, from)
                && !hasNearbyStairOrTerraceExit(level, to)) {
            return false;
        }
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] off : offsets) {
            BlockPos pos = safeSurfaceBlockPos(level, x + off[0], z + off[1]);
            if (pos == null) continue;
            int dyFrom = Math.abs(pos.getY() - fromY);
            int dyTo = Math.abs(pos.getY() - toY);
            if (dyFrom <= 3 || dyTo <= 3) return true;
        }
        return false;
    }

    private static boolean isHardObstacleColumn(ServerLevel level, BlockPos feet) {
        return !isGolemWalkableSpot(level, feet);
    }

    private static boolean isGolemWalkableSpot(ServerLevel level, BlockPos feet) {
        return isGolemWalkableSpot(level, feet, false);
    }

    private static boolean isGolemWalkableSpot(ServerLevel level, BlockPos feet, boolean allowWater) {
        if (level == null || feet == null) return false;
        try {
            if (!isChunkLoaded(level, feet.getX(), feet.getZ())) return false;
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(feet.above());
            BlockState groundState = level.getBlockState(feet.below());
            if (!feetState.getCollisionShape(level, feet).isEmpty()) return false;
            if (!headState.getCollisionShape(level, feet.above()).isEmpty()) return false;
            if (groundState.getCollisionShape(level, feet.below()).isEmpty()) return false;
            if (isHardNoWalkStructureBlock(level, feet) || isHardNoWalkStructureBlock(level, feet.above()) || isHardNoWalkStructureGround(level, feet.below())) return false;
            if (!allowWater && (isWaterAt(level, feet) || isWaterAt(level, feet.above()) || isWaterAt(level, feet.below()))) return false;
            if (isDangerousGolemBlock(level, feet) || isDangerousGolemBlock(level, feet.above()) || isDangerousGolemBlock(level, feet.below())) return false;
            if (isNearDeepDrop(level, feet, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) return false;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isDangerousGolemBlock(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return true;
        try {
            String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())).toLowerCase(Locale.ROOT);
            return id.contains("lava") || id.contains("fire") || id.contains("cactus") || id.contains("campfire") || id.contains("magma") || id.contains("powder_snow") || id.contains("sweet_berry");
        } catch (Throwable ignored) {
            return false;
        }
    }


    private static String blockId(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return "";
        try {
            return String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isStructureBarrierId(String id) {
        if (id == null || id.isEmpty()) return false;
        if (id.contains("wall_torch") || id.contains("sign") || id.contains("button") || id.contains("pressure_plate")) return false;
        return id.contains("fence")
                || id.contains("fence_gate")
                || id.contains("gate")
                || id.contains("wall")
                || id.contains("door")
                || id.contains("trapdoor")
                || id.contains("iron_bars")
                || id.contains("glass_pane")
                || id.contains("chain")
                || id.contains("ladder")
                || id.contains("vine")
                || id.contains("scaffolding")
                || id.contains("cobweb")
                || id.contains("bamboo")
                || id.contains("azalea") && id.contains("leaves");
    }

    private static boolean isBuildingMaterialId(String id) {
        if (id == null || id.isEmpty()) return false;
        return id.contains("brick")
                || id.contains("planks")
                || id.contains("log")
                || id.contains("wood")
                || id.contains("stone")
                || id.contains("deepslate")
                || id.contains("cobblestone")
                || id.contains("concrete")
                || id.contains("terracotta")
                || id.contains("quartz")
                || id.contains("sandstone")
                || id.contains("prismarine")
                || id.contains("copper")
                || id.contains("packed_mud")
                || id.contains("mud_brick")
                || id.contains("blackstone")
                || id.contains("basalt")
                || id.contains("nether_brick")
                || id.contains("purpur")
                || id.contains("wool");
    }

    private static boolean isStairOrSlabId(String id) {
        return id != null && (id.contains("stairs") || id.contains("slab"));
    }

    private static boolean isHardNoWalkStructureBlock(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        String id = blockId(level, pos);
        if (id.isEmpty()) return false;
        if (isStructureBarrierId(id)) return true;
        return false;
    }

    private static boolean isHardNoWalkStructureGround(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        String id = blockId(level, pos);
        if (id.isEmpty()) return false;
        if (isStructureBarrierId(id) && !isStairOrSlabId(id)) return true;
        return false;
    }

    private static boolean isSolidCollision(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !isChunkLoaded(level, pos.getX(), pos.getZ())) return true;
        try {
            return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static int structureRoutePenalty(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null) return GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE;
        int blockers = 0;
        int building = 0;
        int rails = 0;
        int roof = 0;
        for (int dx = -GOLEM_STRUCTURE_SCAN_RADIUS; dx <= GOLEM_STRUCTURE_SCAN_RADIUS; dx++) {
            for (int dz = -GOLEM_STRUCTURE_SCAN_RADIUS; dz <= GOLEM_STRUCTURE_SCAN_RADIUS; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > GOLEM_STRUCTURE_SCAN_RADIUS + 1) continue;
                BlockPos p = feet.offset(dx, 0, dz);
                String idFeet = blockId(level, p);
                String idHead = blockId(level, p.above());
                String idGround = blockId(level, p.below());
                if (isStructureBarrierId(idFeet) || isStructureBarrierId(idHead) || isStructureBarrierId(idGround)) {
                    blockers++;
                    if (idFeet.contains("fence") || idFeet.contains("gate") || idFeet.contains("wall") || idHead.contains("fence") || idHead.contains("gate") || idHead.contains("wall")) rails++;
                }
                if (isBuildingMaterialId(idFeet) || isBuildingMaterialId(idHead) || isBuildingMaterialId(idGround)) building++;
                if (isSolidCollision(level, p.above(2)) || isSolidCollision(level, p.above(3))) roof++;
            }
        }
        int penalty = blockers * 34 + rails * 22 + Math.max(0, building - 3) * 5 + roof * 9;
        if (isNarrowStructurePassage(level, feet)) penalty += 150;
        if (isLikelyBalconyOrTerrace(level, feet)) penalty += 190;
        if (isLikelyBuildingShell(level, feet)) penalty += 30;
        return penalty;
    }

    private static boolean isNarrowStructurePassage(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null) return true;
        boolean east = isStructureSideBlocked(level, feet.offset(1, 0, 0));
        boolean west = isStructureSideBlocked(level, feet.offset(-1, 0, 0));
        boolean north = isStructureSideBlocked(level, feet.offset(0, 0, -1));
        boolean south = isStructureSideBlocked(level, feet.offset(0, 0, 1));
        if ((east && west) || (north && south)) return true;
        int blocked = 0;
        if (east) blocked++;
        if (west) blocked++;
        if (north) blocked++;
        if (south) blocked++;
        return blocked >= 3;
    }

    private static boolean isStructureSideBlocked(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return true;
        String id = blockId(level, pos);
        String idHead = blockId(level, pos.above());
        if (isStructureBarrierId(id) || isStructureBarrierId(idHead)) return true;
        return isSolidCollision(level, pos) || isSolidCollision(level, pos.above());
    }

    private static boolean isLikelyBuildingShell(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null) return false;
        int solidBuildingSides = 0;
        int roofBlocks = 0;
        int[][] sides = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] side : sides) {
            BlockPos p = feet.offset(side[0], 0, side[1]);
            String id0 = blockId(level, p);
            String id1 = blockId(level, p.above());
            if ((isBuildingMaterialId(id0) && isSolidCollision(level, p)) || (isBuildingMaterialId(id1) && isSolidCollision(level, p.above()))) solidBuildingSides++;
        }
        for (int dy = 2; dy <= 4; dy++) {
            if (isSolidCollision(level, feet.above(dy)) && isBuildingMaterialId(blockId(level, feet.above(dy)))) roofBlocks++;
        }
        return solidBuildingSides >= 3 || (solidBuildingSides >= 2 && roofBlocks > 0);
    }

    private static boolean isLikelyBalconyOrTerrace(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null) return false;
        if (!isChunkLoaded(level, feet.getX(), feet.getZ())) return true;
        if (!isGolemWalkableSpot(level, feet, true)) return false;
        int dropEdges = 0;
        int railOrWall = 0;
        int buildingTouch = 0;
        int walkableNeighbors = 0;
        int[][] sides = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] side : sides) {
            BlockPos p = feet.offset(side[0], 0, side[1]);
            if (verticalDropHeight(level, p) >= GOLEM_BALCONY_TERRACE_DROP_HEIGHT) dropEdges++;
            String id0 = blockId(level, p);
            String id1 = blockId(level, p.above());
            if (isStructureBarrierId(id0) || isStructureBarrierId(id1)) railOrWall++;
            if (isBuildingMaterialId(id0) || isBuildingMaterialId(id1) || isBuildingMaterialId(blockId(level, p.below()))) buildingTouch++;
            if (isGolemWalkableSpot(level, p, true)) walkableNeighbors++;
        }
        boolean elevated = false;
        try {
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
            elevated = feet.getY() >= surfaceY || dropEdges > 0;
        } catch (Throwable ignored) {
            elevated = dropEdges > 0;
        }
        return elevated && dropEdges > 0 && (railOrWall > 0 || buildingTouch >= 2) && walkableNeighbors <= 2;
    }

    private static boolean hasNearbyStairOrTerraceExit(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null) return false;
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) != r) continue;
                    BlockPos p = feet.offset(dx, 0, dz);
                    String groundId = blockId(level, p.below());
                    if (!isStairOrSlabId(groundId)) continue;
                    if (isGolemWalkableSpot(level, p, true) && verticalDropHeight(level, p) < GOLEM_BALCONY_TERRACE_DROP_HEIGHT) return true;
                }
            }
        }
        return false;
    }

    private static boolean isNearDeepDrop(ServerLevel level, BlockPos center, int radius, int dangerHeight) {
        if (level == null || center == null) return true;
        int r = Math.max(0, radius);
        int danger = Math.max(4, dangerHeight);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > r + 1) continue;
                BlockPos p = center.offset(dx, 0, dz);
                if (!isChunkLoaded(level, p.getX(), p.getZ())) return true;
                if (verticalDropHeight(level, p) >= danger) return true;
            }
        }
        return false;
    }

    private static int verticalDropHeight(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null) return GOLEM_DEEP_DROP_DANGER_HEIGHT + 1;
        int minY = minBuildHeight(level);
        int drop = 0;
        for (BlockPos p = feet.below(); p.getY() > minY && drop <= GOLEM_DEEP_DROP_DANGER_HEIGHT + 4; p = p.below()) {
            BlockState state = level.getBlockState(p);
            if (!state.getCollisionShape(level, p).isEmpty()) return drop;
            if (isDangerousGolemBlock(level, p) || isWaterAt(level, p)) return GOLEM_DEEP_DROP_DANGER_HEIGHT + 4;
            drop++;
        }
        return drop;
    }

    private static boolean isGolemInUnevenFallTrap(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null) return false;
        if (!isChunkLoaded(level, feet.getX(), feet.getZ())) return false;
        if (!isGolemWalkableSpot(level, feet, true)) return true;
        try {
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
            if (surfaceY - feet.getY() >= GOLEM_UNEVEN_RECOVERY_MAX_VERTICAL_DELTA + 2) return true;
        } catch (Throwable ignored) { }
        int blockedSides = 0;
        int openSameLevel = 0;
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] off : offsets) {
            BlockPos p = feet.offset(off[0], 0, off[1]);
            if (isGolemWalkableSpot(level, p, true)) openSameLevel++;
            else blockedSides++;
        }
        return blockedSides >= 3 && openSameLevel == 0;
    }

    private static boolean isWaterAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        try {
            String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())).toLowerCase(Locale.ROOT);
            if (id.contains("water") || id.contains("kelp") || id.contains("seagrass") || id.contains("bubble_column")) return true;
            Object fluid = level.getFluidState(pos);
            return fluid != null && String.valueOf(fluid).toLowerCase(Locale.ROOT).contains("water");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static TerrainProfile terrainProfile(ServerLevel level, BlockPos center) {
        if (level == null || center == null) return null;
        long now = level.getGameTime();
        String key = GuildStore.dimensionId(level) + "|terrain|" + (center.getX() >> 2) + "|" + (center.getZ() >> 2) + "|" + (center.getY() >> 2);
        TerrainProfileCache cached = GOLEM_TERRAIN_PROFILE_CACHE.get(key);
        if (cached != null && now - cached.tick <= dynamicTerrainProfileCacheTicks()) return cached.profile;

        int water = 0;
        int deepDrops = 0;
        int obstacles = 0;
        int structureBlockers = 0;
        int narrowPassages = 0;
        int buildingShells = 0;
        int balconyEdges = 0;
        int openSides = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int[][] offsets = {{0,0},{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1},{2,0},{-2,0},{0,2},{0,-2}};
        for (int[] off : offsets) {
            int x = center.getX() + off[0];
            int z = center.getZ() + off[1];
            BlockPos p = rawSurfaceBlockPos(level, x, z);
            if (p == null) {
                obstacles++;
                continue;
            }
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            if (hasWaterNear(level, p, 1)) water++;
            if (isNearDeepDrop(level, p, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) deepDrops++;
            int structurePenalty = structureRoutePenalty(level, p);
            if (structurePenalty >= GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE) structureBlockers++;
            if (isNarrowStructurePassage(level, p)) narrowPassages++;
            if (isLikelyBuildingShell(level, p)) buildingShells++;
            if (isLikelyBalconyOrTerrace(level, p)) balconyEdges++;
            if (isHardObstacleColumn(level, p) || !isGolemWalkableSpot(level, p, false)) obstacles++;
            else if (Math.abs(off[0]) + Math.abs(off[1]) == 1 && structurePenalty < GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE) openSides++;
        }
        int roughness = minY == Integer.MAX_VALUE ? 99 : Math.max(0, maxY - minY);
        int penalty = water * 12
                + deepDrops * 55
                + obstacles * 18
                + structureBlockers * 34
                + narrowPassages * 90
                + buildingShells * 8
                + balconyEdges * 130
                + Math.max(0, roughness - 3) * 10;
        TerrainProfile profile = new TerrainProfile(water, deepDrops, obstacles, structureBlockers, narrowPassages, buildingShells, balconyEdges, roughness, openSides, penalty);
        GOLEM_TERRAIN_PROFILE_CACHE.put(key, new TerrainProfileCache(now, GOLEM_AI_FRAME, profile));
        return profile;
    }


    private static void invalidateGolemTerritoryIntelligence(LevelAccessor level, BlockPos pos) {
        if (level == null || pos == null) return;
        GuildStore.Territory territory = GuildStore.territoryAt(level, pos);
        if (territory == null || territory.guildId == null) return;
        String prefix = territory.guildId + "|" + territory.dimension + "|";
        GOLEM_TERRITORY_INTELLIGENCE_CACHE.entrySet().removeIf(e -> e != null && e.getKey() != null && e.getKey().startsWith(prefix));
        GOLEM_TERRAIN_PROFILE_CACHE.clear();
        GOLEM_PATH_CACHE.clear();
    }

    private static int territoryPreparedDefenseScore(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster, GuildStore.Territory candidate, GolemPatrolContext context, GolemPatrolRole role) {
        TerritoryIntelligence intelligence = territoryIntelligence(record, level, cluster);
        if (intelligence == null || candidate == null) return 0;
        int score = 0;
        if (context != null && context.night) {
            score += Math.min(34, intelligence.edgeExposure / 2);
            score += Math.min(22, intelligence.entranceScore / 3);
            score -= Math.min(28, intelligence.structureRisk / 4);
        } else {
            score += Math.min(34, intelligence.openFieldScore / 3);
            score -= Math.min(24, intelligence.structureRisk / 5);
        }
        if (role == GolemPatrolRole.PERIMETER || role == GolemPatrolRole.INTERCEPTOR) score += Math.min(28, intelligence.edgeExposure / 3);
        if (role == GolemPatrolRole.SWEEPER) score += Math.min(26, intelligence.coverageQuality / 4);
        if (role == GolemPatrolRole.ANCHOR && intelligence.centralSafety > 0) score += Math.min(18, intelligence.centralSafety / 5);
        return score;
    }

    private static PatrolCellInsight patrolCellInsight(GuildStore.Golem record, GolemSpawn point, GolemPatrolContext context) {
        if (point == null || point.level == null) return null;
        BlockPos pos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
        String guildId = record == null ? null : record.guildId;
        if ((guildId == null || guildId.isBlank()) && point.territory != null) guildId = point.territory.guildId;
        List<GuildStore.Territory> cluster = context == null ? Collections.emptyList() : context.cluster;
        if ((cluster == null || cluster.isEmpty()) && point.territory != null) cluster = List.of(point.territory);
        TerritoryIntelligence intelligence = territoryIntelligence(guildId, point.level, cluster);
        if (intelligence != null) {
            PatrolCellInsight cell = intelligence.cell(pos);
            if (cell != null) return cell;
        }
        return quickPatrolCellInsight(point.level, pos, territoryAtCluster(cluster, pos.getX(), pos.getZ()));
    }

    private static TerritoryIntelligence territoryIntelligence(GuildStore.Golem record, ServerLevel level, List<GuildStore.Territory> cluster) {
        return territoryIntelligence(record == null ? null : record.guildId, level, cluster);
    }

    private static TerritoryIntelligence territoryIntelligence(String guildId, ServerLevel level, List<GuildStore.Territory> cluster) {
        if (level == null || guildId == null || guildId.isBlank() || cluster == null || cluster.isEmpty()) return null;
        long now = level.getGameTime();
        String dimension = GuildStore.dimensionId(level);
        String signature = clusterSignature(cluster);
        String key = guildId + "|" + dimension + "|" + signature;
        TerritoryIntelligenceCache cached = GOLEM_TERRITORY_INTELLIGENCE_CACHE.get(key);
        if (cached != null && now - cached.tick <= dynamicTerritoryAnalysisCacheTicks()) return cached.intelligence;

        ClusterBounds bounds = clusterBounds(cluster);
        if (bounds == null) return null;
        Map<String, PatrolCellInsight> cells = new HashMap<>();
        int openFieldScore = 0;
        int structureRisk = 0;
        int edgeExposure = 0;
        int entranceScore = 0;
        int centralSafety = 0;
        int coverageQuality = 0;
        int samples = 0;
        int rejected = 0;
        int step = GOLEM_TERRITORY_ANALYSIS_CELL_SIZE;
        int seed = Math.floorMod((guildId + dimension + signature).hashCode(), Math.max(1, step));
        for (int x = bounds.minX + seed; x <= bounds.maxX && samples < dynamicTerritoryAnalysisMaxSamples(); x += step) {
            for (int z = bounds.minZ + Math.floorMod(seed * 3 + x, step); z <= bounds.maxZ && samples < dynamicTerritoryAnalysisMaxSamples(); z += step) {
                GuildStore.Territory territory = territoryAtCluster(cluster, x, z);
                if (territory == null) continue;
                BlockPos surface = rawSurfaceBlockPos(level, x, z);
                if (surface == null) continue;
                PatrolCellInsight insight = pretrainedPatrolCellInsight(level, surface, territory);
                cells.put(cellKeyForPosition(surface.getX(), surface.getZ()), insight);
                samples++;
                if (insight.routeReject) rejected++;
                openFieldScore += Math.max(0, insight.openScore - insight.structureScore);
                structureRisk += insight.structureScore + insight.riskScore;
                edgeExposure += insight.edgeScore;
                entranceScore += insight.entranceScore;
                centralSafety += insight.centralSafety;
                coverageQuality += Math.max(0, insight.dayBonus + insight.nightBonus / 2 - insight.penalty / 8);
            }
        }
        TerritoryIntelligence intelligence = new TerritoryIntelligence(guildId, dimension, signature, samples, rejected, openFieldScore, structureRisk, edgeExposure, entranceScore, centralSafety, coverageQuality, cells);
        GOLEM_TERRITORY_INTELLIGENCE_CACHE.put(key, new TerritoryIntelligenceCache(now, GOLEM_AI_FRAME, intelligence));
        return intelligence;
    }

    private static String clusterSignature(List<GuildStore.Territory> cluster) {
        if (cluster == null || cluster.isEmpty()) return "empty";
        int hash = 1;
        int count = 0;
        for (GuildStore.Territory territory : cluster) {
            if (territory == null) continue;
            count++;
            hash = 31 * hash + String.valueOf(territory.id).hashCode();
            hash = 31 * hash + GuildStore.minX(territory);
            hash = 31 * hash + GuildStore.maxX(territory);
            hash = 31 * hash + GuildStore.minZ(territory);
            hash = 31 * hash + GuildStore.maxZ(territory);
        }
        return count + ":" + Integer.toHexString(hash);
    }

    private static String cellKeyForPosition(int x, int z) {
        return Math.floorDiv(x, GOLEM_TERRITORY_ANALYSIS_CELL_SIZE) + ":" + Math.floorDiv(z, GOLEM_TERRITORY_ANALYSIS_CELL_SIZE);
    }


    private static PatrolCellInsight quickPatrolCellInsight(ServerLevel level, BlockPos pos, GuildStore.Territory territory) {
        TerrainProfile terrain = terrainProfile(level, pos);
        int open = 0;
        int structure = 0;
        int risk = 0;
        int edge = 0;
        int central = 0;
        int penalty = 0;
        boolean routeReject = false;
        boolean pathLike = isPathLikeId(blockId(level, pos.below())) || isPathLikeId(blockId(level, pos));
        if (terrain != null) {
            open += terrain.openSides * 8;
            structure += terrain.structureBlockers * 20 + terrain.buildingShells * 10 + terrain.narrowPassages * 35 + terrain.balconyEdges * 60;
            risk += terrain.deepDrops * 70 + terrain.water * 20 + terrain.obstacles * 12 + Math.max(0, terrain.roughness - 3) * 12;
            if (terrain.deepDrops > 0 || terrain.narrowPassages > 0 || terrain.balconyEdges > 0 || terrain.structureBlockers >= 3) routeReject = true;
        }
        if (pathLike) open += 24;
        if (!isGolemWalkableSpot(level, pos, false)) {
            risk += 120;
            routeReject = true;
        }
        if (territory != null) {
            int toEdge = Math.min(Math.min(Math.abs(pos.getX() - GuildStore.minX(territory)), Math.abs(pos.getX() - GuildStore.maxX(territory))), Math.min(Math.abs(pos.getZ() - GuildStore.minZ(territory)), Math.abs(pos.getZ() - GuildStore.maxZ(territory))));
            if (toEdge <= 6) edge += 24;
            else if (toEdge >= 14) central += 16;
        }
        penalty += Math.max(0, risk / 2 + structure / 3 - open / 4 - (pathLike ? 10 : 0));
        int dayBonus = Math.max(0, open / 3 + central / 2 - risk / 10 - structure / 14);
        int nightBonus = Math.max(0, edge / 2 + (pathLike ? 8 : 0) - risk / 12 - structure / 18);
        return new PatrolCellInsight(penalty, dayBonus, nightBonus, open, structure, risk, edge, 0, central, pathLike, false, routeReject);
    }

    private static PatrolCellInsight pretrainedPatrolCellInsight(ServerLevel level, BlockPos pos, GuildStore.Territory territory) {
        TerrainProfile terrain = terrainProfile(level, pos);
        int structure = 0;
        int risk = 0;
        int open = 0;
        int edge = 0;
        int entrance = 0;
        int central = 0;
        int penalty = 0;
        boolean pathLike = false;
        boolean routeReject = false;
        if (terrain != null) {
            structure += terrain.structureBlockers * 18 + terrain.buildingShells * 12 + terrain.narrowPassages * 28 + terrain.balconyEdges * 42;
            risk += terrain.deepDrops * 52 + terrain.water * 18 + Math.max(0, terrain.roughness - 3) * 12 + terrain.obstacles * 10;
            open += terrain.openSides * 10 + Math.max(0, 8 - terrain.roughness) * 3;
            if (terrain.deepDrops > 0 || terrain.narrowPassages > 0 || terrain.balconyEdges > 0 || terrain.structureBlockers >= 3) routeReject = true;
        }
        String ground = blockId(level, pos.below());
        String feet = blockId(level, pos);
        String head = blockId(level, pos.above());
        if (isPathLikeId(ground) || isPathLikeId(feet)) {
            pathLike = true;
            open += 24;
            entrance += 10;
        }
        int buildingRing = 0;
        int barrierRing = 0;
        int doorGateRing = 0;
        int roofRing = 0;
        int walkableRing = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3) continue;
                BlockPos p = pos.offset(dx, 0, dz);
                String id0 = blockId(level, p);
                String id1 = blockId(level, p.above());
                String id2 = blockId(level, p.below());
                if (isBuildingMaterialId(id0) || isBuildingMaterialId(id1) || isBuildingMaterialId(id2)) buildingRing++;
                if (isStructureBarrierId(id0) || isStructureBarrierId(id1)) barrierRing++;
                if (id0.contains("door") || id1.contains("door") || id0.contains("gate") || id1.contains("gate")) doorGateRing++;
                if (isSolidCollision(level, p.above(2)) && isBuildingMaterialId(blockId(level, p.above(2)))) roofRing++;
                if (isGolemWalkableSpot(level, p, true)) walkableRing++;
            }
        }
        structure += buildingRing * 4 + barrierRing * 15 + roofRing * 10;
        entrance += doorGateRing * 16;
        if (walkableRing >= 7 && barrierRing <= 1) open += 24;
        if (isLikelyBuildingShell(level, pos)) {
            structure += 55;
            penalty += 50;
        }
        if (isLikelyBalconyOrTerrace(level, pos)) {
            risk += 100;
            penalty += 150;
            routeReject = true;
        }
        if (isNearDeepDrop(level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) {
            risk += 120;
            penalty += 160;
            routeReject = true;
        }
        if (hasWaterNear(level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS)) {
            risk += 45;
            penalty += 65;
        }
        if (!isGolemWalkableSpot(level, pos, false)) {
            risk += 120;
            penalty += 130;
            routeReject = true;
        }
        if (territory != null) {
            int toEdge = Math.min(Math.min(Math.abs(pos.getX() - GuildStore.minX(territory)), Math.abs(pos.getX() - GuildStore.maxX(territory))), Math.min(Math.abs(pos.getZ() - GuildStore.minZ(territory)), Math.abs(pos.getZ() - GuildStore.maxZ(territory))));
            if (toEdge <= 6) edge += 26;
            else if (toEdge >= 14) central += 18;
        }
        boolean houseApproach = buildingRing >= 4 && walkableRing >= 5 && !routeReject;
        int dayBonus = Math.max(0, open / 3 + central / 2 + (pathLike ? 14 : 0) - risk / 8 - structure / 12);
        int nightBonus = Math.max(0, edge / 2 + entrance + (houseApproach ? 16 : 0) - risk / 10 - (routeReject ? 30 : 0));
        penalty += Math.max(0, risk / 2 + structure / 3 - open / 4 - (pathLike ? 12 : 0));
        return new PatrolCellInsight(penalty, dayBonus, nightBonus, open, structure, risk, edge, entrance, central, pathLike, houseApproach, routeReject);
    }

    private static boolean isPathLikeId(String id) {
        if (id == null || id.isEmpty()) return false;
        return id.contains("path") || id.contains("gravel") || id.contains("paved") || id.contains("polished") || id.contains("smooth_stone") || id.contains("stone_brick") || id.contains("cobblestone") || id.contains("andesite") || id.contains("diorite") || id.contains("granite");
    }

    private static boolean hasWaterNear(ServerLevel level, BlockPos center, int radius) {
        return nearbyWaterCount(level, center, radius) > 0;
    }

    private static int nearbyWaterCount(ServerLevel level, BlockPos center, int radius) {
        if (level == null || center == null) return 0;
        int count = 0;
        int r = Math.max(0, radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > r + 1) continue;
                BlockPos p = center.offset(dx, 0, dz);
                if (isWaterAt(level, p) || isWaterAt(level, p.below()) || isWaterAt(level, p.above())) count++;
            }
        }
        return count;
    }

    private static boolean isPatrolDestinationCrowded(GuildStore.Golem record, GolemSpawn point, double minDistance) {
        if (record == null || point == null || point.level == null) return false;
        double minSqr = minDistance * minDistance;
        long now = point.level.getGameTime();
        for (Map.Entry<String, GolemPatrolMemory> entry : GOLEM_PATROL_MEMORY.entrySet()) {
            if (entry == null || Objects.equals(entry.getKey(), record.uuid)) continue;
            GolemPatrolMemory memory = entry.getValue();
            if (memory == null || now - memory.tick > 20L * 75L) continue;
            if (distanceSqr(point.x, point.y, point.z, memory.x, memory.y, memory.z) <= minSqr) return true;
        }
        for (Entity entity : entitiesNear(point.level, point.x, point.y, point.z, Math.max(8.0D, minDistance + 4.0D), 24)) {
            if (entity == null || !entity.isAlive() || !isGuildGolem(entity)) continue;
            if (Objects.equals(entity.getUUID().toString(), record.uuid)) continue;
            if (!Objects.equals(guildIdFromGuildGolem(entity), record.guildId)) continue;
            if (distanceSqr(point.x, point.y, point.z, entity.getX(), entity.getY(), entity.getZ()) <= minSqr * 0.55D) return true;
        }
        return isRouteReservedTooClose(record, point, null);
    }

    private static int routeReservationPenalty(GuildStore.Golem record, GolemSpawn point, GolemPatrolContext context) {
        if (record == null || point == null || point.level == null || record.guildId == null) return 0;
        long now = point.level.getGameTime();
        int penalty = 0;
        double soft = context != null && context.dangerNearGuildMember ? 6.0D : GOLEM_SWARM_MIN_DESTINATION_SEPARATION;
        double hardSqr = Math.max(4.0D, soft * soft);
        for (Map.Entry<String, GolemRouteReservation> entry : GOLEM_ROUTE_RESERVATIONS.entrySet()) {
            if (entry == null || Objects.equals(entry.getKey(), record.uuid)) continue;
            GolemRouteReservation reservation = entry.getValue();
            if (reservation == null || !Objects.equals(reservation.guildId, record.guildId)) continue;
            if (!Objects.equals(reservation.dimension, GuildStore.dimensionId(point.level))) continue;
            if (now - reservation.tick > GOLEM_SWARM_ROUTE_RESERVATION_TICKS) continue;
            double endDist = distanceSqr(point.x, point.y, point.z, reservation.x, reservation.y, reservation.z);
            if (endDist <= hardSqr) penalty += context != null && context.dangerNearGuildMember ? 35 : 95;
            if (sameRouteLane(point, reservation)) penalty += context != null && context.dangerNearGuildMember ? 18 : 55;
            if (GuildStore.guildGolemEliteCommandTalent(record.guildId)) penalty = Math.max(0, penalty - 18);
        }
        return Math.min(220, penalty);
    }

    private static boolean sameRouteLane(GolemSpawn point, GolemRouteReservation reservation) {
        if (point == null || reservation == null) return false;
        double dx = point.x - reservation.startX;
        double dz = point.z - reservation.startZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 4.0D) return false;
        double rx = reservation.x - reservation.startX;
        double rz = reservation.z - reservation.startZ;
        double cross = Math.abs(dx * rz - dz * rx) / len;
        double dot = dx * rx + dz * rz;
        return cross <= 3.0D && dot > 0.0D;
    }

    private static boolean isRouteReservedTooClose(GuildStore.Golem record, GolemSpawn point, GolemPatrolContext context) {
        return routeReservationPenalty(record, point, context) >= (context != null && context.dangerNearGuildMember ? 140 : 80);
    }

    private static void reserveGolemRoute(GuildStore.Golem record, Mob mob, GolemSpawn point, String reason) {
        if (record == null || mob == null || point == null || point.level == null || record.uuid == null) return;
        GOLEM_ROUTE_RESERVATIONS.put(record.uuid, new GolemRouteReservation(
                GOLEM_AI_FRAME,
                point.level.getGameTime(),
                record.guildId,
                GuildStore.dimensionId(point.level),
                mob.getX(),
                mob.getY(),
                mob.getZ(),
                point.x,
                point.y,
                point.z,
                reason == null ? "" : reason
        ));
    }

    private static boolean isGolemRouteCongested(GuildStore.Golem record, Mob mob, GolemSpawn point, GolemPatrolContext context) {
        if (record == null || mob == null || point == null || point.level == null || record.guildId == null) return false;
        long now = point.level.getGameTime();
        double safety = context != null && context.dangerNearGuildMember ? 3.5D : GOLEM_SWARM_TRAIL_REPATH_DISTANCE;
        double safetySqr = safety * safety;
        for (Entity entity : entitiesNear(mob, Math.max(8.0D, safety + 6.0D), 24)) {
            if (entity == null || entity == mob || !entity.isAlive() || !isGuildGolem(entity)) continue;
            if (!Objects.equals(guildIdFromGuildGolem(entity), record.guildId)) continue;
            if (entity.distanceToSqr(mob) <= safetySqr && isEntityBetween(mob, point, entity, safety + 1.0D)) return true;
        }
        for (Map.Entry<String, GolemRouteReservation> entry : GOLEM_ROUTE_RESERVATIONS.entrySet()) {
            if (entry == null || Objects.equals(entry.getKey(), record.uuid)) continue;
            GolemRouteReservation reservation = entry.getValue();
            if (reservation == null || !Objects.equals(reservation.guildId, record.guildId)) continue;
            if (!Objects.equals(reservation.dimension, GuildStore.dimensionId(point.level))) continue;
            if (now - reservation.tick > GOLEM_SWARM_ROUTE_RESERVATION_TICKS) continue;
            if (distanceSqr(mob.getX(), mob.getY(), mob.getZ(), reservation.startX, reservation.startY, reservation.startZ) <= safetySqr && sameRouteLane(point, reservation)) return true;
        }
        return false;
    }

    private static boolean isEntityBetween(Mob mob, GolemSpawn point, Entity other, double width) {
        if (mob == null || point == null || other == null) return false;
        double ax = mob.getX();
        double az = mob.getZ();
        double bx = point.x;
        double bz = point.z;
        double dx = bx - ax;
        double dz = bz - az;
        double lenSqr = dx * dx + dz * dz;
        if (lenSqr < 4.0D) return false;
        double t = ((other.getX() - ax) * dx + (other.getZ() - az) * dz) / lenSqr;
        if (t <= 0.05D || t >= 0.88D) return false;
        double px = ax + dx * t;
        double pz = az + dz * t;
        double lateral = (other.getX() - px) * (other.getX() - px) + (other.getZ() - pz) * (other.getZ() - pz);
        return lateral <= width * width;
    }

    private static GolemSpawn findAntiStackDetourPoint(GuildStore.Golem record, Mob mob, GolemSpawn finalPoint, GolemPatrolContext context) {
        if (record == null || mob == null || finalPoint == null || finalPoint.level == null || mob.level() != finalPoint.level) return null;
        ServerLevel level = finalPoint.level;
        List<GuildStore.Territory> cluster = context == null ? Collections.emptyList() : context.cluster;
        if (cluster == null || cluster.isEmpty()) {
            GuildStore.Territory t = finalPoint.territory == null ? GuildStore.territoryAt(level, mob.blockPosition()) : finalPoint.territory;
            if (t != null) cluster = contiguousGuildCluster(record.guildId, t, GuildStore.dimensionId(level));
            if ((cluster == null || cluster.isEmpty()) && t != null) cluster = List.of(t);
        }
        if (cluster == null || cluster.isEmpty()) return null;
        double dx = finalPoint.x - mob.getX();
        double dz = finalPoint.z - mob.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        double nx = -dz / len;
        double nz = dx / len;
        int hash = Math.floorMod(String.valueOf(record.uuid).hashCode(), 2) == 0 ? 1 : -1;
        int[] sideOffsets = {5, -5, 8, -8, 12, -12, 16, -16};
        for (int offset : sideOffsets) {
            int off = offset * hash;
            double t = offset == 5 || offset == -5 ? 0.38D : 0.55D;
            int x = (int)Math.floor(mob.getX() + dx * t + nx * off);
            int z = (int)Math.floor(mob.getZ() + dz * t + nz * off);
            GolemSpawn point = safeClusterPatrolPoint(level, cluster, x, z);
            point = acceptWaterPolicy(point, context);
            if (point == null) continue;
            if (isPatrolDestinationCrowded(record, point, context != null && context.dangerNearGuildMember ? 4.0D : 7.0D)) continue;
            if (routeReservationPenalty(record, point, context) >= 70) continue;
            if (hasNavigablePatrolPath(mob, point)) return point;
        }
        return null;
    }

    private static boolean shouldGolemJoinAlert(GuildStore.Golem record, Entity golem, GuildCombatAlert alert, LivingEntity target) {
        if (record == null || golem == null || alert == null) return false;
        if (target != null && target.isAlive()) {
            if (!isGolemTargetAllowedByLeash(record, golem, target, true)) return false;
            return shouldGolemJoinThreat(record, golem, target, true);
        }
        String key = alert.attackerUuid == null || alert.attackerUuid.isBlank()
                ? "alert:" + alert.dimension + ":" + ((int)Math.floor(alert.x) >> 4) + ":" + ((int)Math.floor(alert.z) >> 4)
                : alert.attackerUuid;
        int desired = alertRequiresPackResponse(record, golem, alert) ? GOLEM_SWARM_MEMBER_DANGER_RESPONDERS : GOLEM_SWARM_NORMAL_RESPONDERS;
        int active = activeResponderAssignments(record.guildId, key, golem.level().getGameTime(), record.uuid);
        if (active < desired || isAssignedToThreat(record.uuid, key, golem.level().getGameTime())) {
            GOLEM_THREAT_ASSIGNMENTS.put(record.uuid, new GolemThreatAssignment(GOLEM_AI_FRAME, golem.level().getGameTime(), record.guildId, key, "alert"));
            return true;
        }
        return false;
    }

    private static boolean alertRequiresPackResponse(GuildStore.Golem record, Entity golem, GuildCombatAlert alert) {
        if (record == null || golem == null || alert == null) return false;
        if (record.elite) return true;
        if (distanceSqr(golem.getX(), golem.getY(), golem.getZ(), alert.x, alert.y, alert.z) <= 20.0D * 20.0D) return true;
        return false;
    }

    private static boolean shouldGolemJoinThreat(GuildStore.Golem record, Entity golem, LivingEntity target, boolean reserve) {
        if (record == null || golem == null || target == null || !target.isAlive()) return false;
        String targetKey = target.getUUID().toString();
        long now = golem.level().getGameTime();
        if (isAssignedToThreat(record.uuid, targetKey, now)) return true;
        int desired = desiredRespondersForThreat(record, target);
        int active = activeResponderAssignments(record.guildId, targetKey, now, record.uuid);
        if (active < desired || (record.elite && isHighAlertThreat(target) && active < desired + 1)) {
            if (reserve) rememberGolemThreatAssignment(record, golem, target);
            return true;
        }
        return false;
    }

    private static int desiredRespondersForThreat(GuildStore.Golem record, Entity target) {
        if (target == null) return GOLEM_SWARM_NORMAL_RESPONDERS;
        if (isHighAlertThreat(target)) return GOLEM_SWARM_HIGH_ALERT_RESPONDERS;
        int threat = golemThreatPriority(target);
        boolean memberDanger = record != null && isNearGuildMember(record.guildId, target);
        if (memberDanger || isAerialOrElevatedThreat(target) || threat >= 900) return GOLEM_SWARM_MEMBER_DANGER_RESPONDERS;
        return GOLEM_SWARM_NORMAL_RESPONDERS;
    }

    private static int activeResponderAssignments(String guildId, String targetKey, long now, String excludeUuid) {
        if (guildId == null || targetKey == null || targetKey.isBlank()) return 0;
        int count = 0;
        for (Map.Entry<String, GolemThreatAssignment> entry : GOLEM_THREAT_ASSIGNMENTS.entrySet()) {
            if (entry == null || Objects.equals(entry.getKey(), excludeUuid)) continue;
            GolemThreatAssignment assignment = entry.getValue();
            if (assignment == null || !Objects.equals(assignment.guildId, guildId) || !Objects.equals(assignment.targetKey, targetKey)) continue;
            if (now - assignment.tick > GOLEM_SWARM_ASSIGNMENT_TICKS) continue;
            if (GuildStore.golem(entry.getKey()) == null) continue;
            count++;
        }
        return count;
    }

    private static boolean isAssignedToThreat(String golemUuid, String targetKey, long now) {
        if (golemUuid == null || targetKey == null) return false;
        GolemThreatAssignment assignment = GOLEM_THREAT_ASSIGNMENTS.get(golemUuid);
        return assignment != null && Objects.equals(assignment.targetKey, targetKey) && now - assignment.tick <= GOLEM_SWARM_ASSIGNMENT_TICKS;
    }

    private static void rememberGolemThreatAssignment(GuildStore.Golem record, Entity golem, LivingEntity target) {
        if (record == null || golem == null || target == null || record.uuid == null) return;
        GOLEM_THREAT_ASSIGNMENTS.put(record.uuid, new GolemThreatAssignment(GOLEM_AI_FRAME, golem.level().getGameTime(), record.guildId, target.getUUID().toString(), isHighAlertThreat(target) ? "high" : "normal"));
    }

    private static void markGolemTask(GuildStore.Golem record, Entity entity, String task, GolemSpawn destination, String targetKey) {
        if (record == null || record.uuid == null || entity == null) return;
        GOLEM_TASK_STATE.put(record.uuid, new GolemTaskState(
                GOLEM_AI_FRAME,
                entity.level().getGameTime(),
                task == null ? "" : task,
                targetKey == null ? "" : targetKey,
                destination == null ? entity.getX() : destination.x,
                destination == null ? entity.getY() : destination.y,
                destination == null ? entity.getZ() : destination.z
        ));
    }

    private static void resumeGolemAfterInterruptIfNeeded(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (record == null || entity == null || record.uuid == null) return;
        GolemTaskState state = GOLEM_TASK_STATE.get(record.uuid);
        if (state == null) return;
        if (!"ALERT".equals(state.task) && !"COMBAT".equals(state.task)) return;
        long now = entity.level().getGameTime();
        if (now - state.tick < 10L) return;
        LivingEntity target = entity instanceof Mob mob ? currentGolemTarget(mob) : null;
        if (target != null && shouldKeepCurrentGolemTarget(record, entity, target, territory)) return;
        if (entity instanceof Mob mob) clearGuildGolemAggro(mob);
        GOLEM_TARGET_CACHE.remove(record.uuid);
        GOLEM_PATROL_PLAN.remove(record.uuid);
        GOLEM_ROUTE_RESERVATIONS.remove(record.uuid);
        GOLEM_TASK_STATE.put(record.uuid, new GolemTaskState(GOLEM_AI_FRAME, now, "RESUME", "", entity.getX(), entity.getY(), entity.getZ()));
    }

    private static GolemSpawn safeClusterPatrolPoint(ServerLevel level, List<GuildStore.Territory> cluster, int x, int z) {
        if (level == null || cluster == null || cluster.isEmpty()) return null;
        GuildStore.Territory at = territoryAtCluster(cluster, x, z);
        if (at == null) return null;
        BlockPos pos = safeSurfaceBlockPos(level, x, z);
        if (pos == null) return null;
        GuildStore.Territory actual = GuildStore.territoryAt(level, pos);
        if (actual == null || !"GUILD".equals(actual.type) || !Objects.equals(actual.guildId, at.guildId)) return null;
        return new GolemSpawn(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, actual);
    }

    private static GolemSpawn safeClusterPatrolPointNearY(ServerLevel level, List<GuildStore.Territory> cluster, int x, int z, int preferredY) {
        if (level == null || cluster == null || cluster.isEmpty()) return null;
        GuildStore.Territory at = territoryAtCluster(cluster, x, z);
        if (at == null) return null;
        int[] dyOrder = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5};
        for (int dy : dyOrder) {
            BlockPos pos = new BlockPos(x, preferredY + dy, z);
            if (!isGolemWalkableSpot(level, pos)) continue;
            GuildStore.Territory actual = GuildStore.territoryAt(level, pos);
            if (actual == null || !"GUILD".equals(actual.type) || !Objects.equals(actual.guildId, at.guildId)) continue;
            return new GolemSpawn(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, actual);
        }
        return null;
    }

    private static BlockPos rawSurfaceBlockPos(ServerLevel level, int x, int z) {
        if (level == null || !isChunkLoaded(level, x, z)) return null;
        try {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            return new BlockPos(x, y, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BlockPos safeSurfaceBlockPos(ServerLevel level, int x, int z) {
        if (level == null) return null;
        long now = level.getGameTime();
        String key = GuildStore.dimensionId(level) + "|dry|" + x + "|" + z;
        SurfaceSpotCache cached = GOLEM_SURFACE_CACHE.get(key);
        if (cached != null && now - cached.tick <= 40L) return cached.pos;
        BlockPos result = computeSafeSurfaceBlockPos(level, x, z);
        GOLEM_SURFACE_CACHE.put(key, new SurfaceSpotCache(now, GOLEM_AI_FRAME, result));
        return result;
    }

    private static BlockPos computeSafeSurfaceBlockPos(ServerLevel level, int x, int z) {
        if (level == null) return null;
        if (!isChunkLoaded(level, x, z)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos base = new BlockPos(x, y, z);
        if (isGolemWalkableSpot(level, base) && structureRoutePenalty(level, base) < GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE && !isLikelyBalconyOrTerrace(level, base)) return base;
        for (int dy = 1; dy <= 6; dy++) {
            BlockPos up = base.above(dy);
            if (isGolemWalkableSpot(level, up) && structureRoutePenalty(level, up) < GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE && !isLikelyBalconyOrTerrace(level, up)) return up;
        }
        for (int dy = 1; dy <= 10; dy++) {
            BlockPos down = base.below(dy);
            if (isGolemWalkableSpot(level, down) && structureRoutePenalty(level, down) < GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE && !isLikelyBalconyOrTerrace(level, down)) return down;
        }
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1},{2,0},{-2,0},{0,2},{0,-2},{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{-1,2},{1,-2},{-1,-2},{3,0},{-3,0},{0,3},{0,-3},{4,0},{-4,0},{0,4},{0,-4},{5,0},{-5,0},{0,5},{0,-5},{6,0},{-6,0},{0,6},{0,-6}};
        for (int[] off : offsets) {
            int nx = x + off[0];
            int nz = z + off[1];
            if (!isChunkLoaded(level, nx, nz)) continue;
            int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
            BlockPos pos = new BlockPos(nx, ny, nz);
            if (isGolemWalkableSpot(level, pos) && structureRoutePenalty(level, pos) < GOLEM_STRUCTURE_BLOCKER_REJECT_SCORE && !isLikelyBalconyOrTerrace(level, pos)) return pos;
        }
        return null;
    }

    private static GolemSpawn findRouteDetourPoint(GuildStore.Golem record, Mob mob, GolemSpawn finalPoint, GolemPatrolContext context) {
        if (record == null || mob == null || finalPoint == null || finalPoint.level == null || mob.level() != finalPoint.level) return null;
        ServerLevel level = finalPoint.level;
        List<GuildStore.Territory> cluster = context == null ? Collections.emptyList() : context.cluster;
        if (cluster == null || cluster.isEmpty()) {
            GuildStore.Territory t = finalPoint.territory == null ? GuildStore.territoryAt(level, mob.blockPosition()) : finalPoint.territory;
            if (t != null) cluster = List.of(t);
        }
        if (cluster == null || cluster.isEmpty()) return null;
        double sx = mob.getX();
        double sz = mob.getZ();
        double tx = finalPoint.x;
        double tz = finalPoint.z;
        double midX = (sx + tx) * 0.5D;
        double midZ = (sz + tz) * 0.5D;
        double dx = tx - sx;
        double dz = tz - sz;
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        double nx = -dz / len;
        double nz = dx / len;
        int[] offsets = GuildStore.guildGolemRouteEfficiencyBonusPercent(record.guildId) > 0
                ? new int[]{5, -5, 8, -8, 12, -12, 18, -18, 24, -24}
                : new int[]{6, -6, 10, -10, 16, -16, 24, -24};
        int learnedPenaltyLimit = GuildStore.guildGolemRouteEfficiencyBonusPercent(record.guildId) > 0 ? 150 : 120;
        for (int off : offsets) {
            int x = (int)Math.floor(midX + nx * off);
            int z = (int)Math.floor(midZ + nz * off);
            GolemSpawn p = safeClusterPatrolPoint(level, cluster, x, z);
            p = acceptWaterPolicy(p, context);
            if (p == null) continue;
            if (learnedRoutePenalty(record, p) >= learnedPenaltyLimit) continue;
            if (roughWalkableCorridor(mob, p) || tryNativeNavigationReachability(mob, p) == Boolean.TRUE) return p;
        }
        return null;
    }

    private static LivingEntity findGolemTarget(GuildStore.Golem record, Entity golem, GuildStore.Territory territory) {
        String guildId = record == null ? null : record.guildId;
        if (guildId == null || golem == null) return null;
        GolemTargetCache cached = GOLEM_TARGET_CACHE.get(golem.getUUID().toString());
        long now = golem.level().getGameTime();
        if (cached != null && now - cached.tick <= 12L) {
            UUID targetUuid = parseUuid(cached.targetUuid);
            Entity cachedEntity = targetUuid == null ? null : findEntity(targetUuid);
            if (cachedEntity instanceof LivingEntity living && shouldKeepCurrentGolemTarget(record, golem, living, territory)) {
                return living;
            }
        }

        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double protectRange = HomeCraftGuildConfig.golemProtectRange();
        double maxSqr = protectRange * protectRange;
        List<GuildStore.Territory> cluster = contiguousGuildCluster(guildId, territory, GuildStore.dimensionId(golem.level()));
        if (cluster.isEmpty() && territory != null) cluster = List.of(territory);

        for (Entity entity : entitiesNear(golem, Math.max(32.0D, protectRange * 2.20D), dynamicGolemTargetScanBudget())) {
            if (!(entity instanceof LivingEntity living) || entity == golem || !entity.isAlive()) continue;
            if (isGuildGolem(entity)) continue;
            if (shouldIgnoreUndergroundGolemTarget(entity)) continue;

            boolean hostileMob = entity.getType().getCategory() == MobCategory.MONSTER;
            boolean enemyPlayer = entity instanceof ServerPlayer player && !GuildStore.isMemberOfGuild(player, guildId) && isNearGuildMember(guildId, player);
            if (!hostileMob && !enemyPlayer) continue;

            int threatPriority = golemThreatPriority(entity);
            boolean insideCluster = isInsideCluster(cluster, entity.blockPosition());
            if (!insideCluster) continue;
            boolean needsThisGolem = shouldGolemJoinThreat(record, golem, living, false);
            if (!needsThisGolem && !isHighAlertThreat(entity) && !(enemyPlayer && isNearGuildMember(guildId, entity))) continue;

            double dist = entity.distanceToSqr(golem);
            boolean elevated = isAerialOrElevatedThreat(entity);
            double rangeMultiplier = isHighAlertThreat(entity) ? 2.10D : (elevated ? 1.60D : 1.0D);
            if (dist > maxSqr * rangeMultiplier) continue;

            double score = 1000.0D - Math.sqrt(dist);
            score += threatPriority;
            if (enemyPlayer) score += 180.0D;
            if (hostileMob && isNearGuildMember(guildId, entity)) score += 135.0D;
            if (isHighAlertThreat(entity)) score += 360.0D;
            if (elevated) score += 150.0D;
            if (isNearClusterBorder(cluster, entity)) score += 35.0D;
            score -= nearbyGuildGolems(entity, guildId, golem.level()) * (threatPriority >= 1500 ? 12.0D : 28.0D);
            if (!needsThisGolem) score -= isHighAlertThreat(entity) ? 220.0D : 520.0D;

            if (golem instanceof Mob mob && !elevated && !isHighAlertThreat(entity)) {
                GolemSpawn approach = smartTargetApproachPoint(record, golem, living);
                if (approach == null || !hasNavigablePatrolPath(mob, approach)) score -= 260.0D;
            }

            if (score > bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private static boolean isAerialOrElevatedThreat(Entity entity) {
        if (entity == null) return false;
        String id = entityTypeId(entity);
        if (id.contains("ghast") || id.contains("phantom") || id.contains("blaze") || id.contains("breeze") || id.contains("shulker") || id.contains("vex") || id.contains("wither") || id.contains("ender_dragon")) return true;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        try {
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, entity.blockPosition().getX(), entity.blockPosition().getZ());
            return entity.getY() - groundY >= 4.0D;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean shouldIgnoreUndergroundGolemTarget(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return false;
        boolean targetType = entity.getType().getCategory() == MobCategory.MONSTER || entity instanceof ServerPlayer;
        if (!targetType) return false;
        return isUndergroundGolemIgnoredPosition(level, entity.blockPosition());
    }

    private static boolean isUndergroundGolemIgnoredPosition(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        try {
            // Це саме фільтр AI големів, а не маршрут до печер. Якщо ціль значно нижче
            // поверхні X/Z-колонки, големи її повністю ігнорують і не будують до неї шлях.
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
            return pos.getY() <= surfaceY - GOLEM_UNDERGROUND_IGNORE_SURFACE_DELTA;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static GolemSpawn smartTargetApproachPoint(GuildStore.Golem record, Entity golem, LivingEntity target) {
        if (golem == null || target == null || !(golem.level() instanceof ServerLevel level)) return null;
        if (golem.level() != target.level()) return null;
        if (shouldIgnoreUndergroundGolemTarget(target)) return null;
        GuildStore.Territory golemTerritory = GuildStore.territoryAt(golem.level(), golem.blockPosition());
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record == null ? guildIdFromGuildGolem(golem) : record.guildId, golemTerritory, GuildStore.dimensionId(level));
        if (cluster.isEmpty() && golemTerritory != null) cluster = List.of(golemTerritory);

        int tx = (int)Math.floor(target.getX());
        int tz = (int)Math.floor(target.getZ());
        boolean elevated = isAerialOrElevatedThreat(target);
        int hash = Math.abs(String.valueOf(record == null ? golem.getUUID() : record.uuid).hashCode());
        int[] radii = elevated ? new int[]{0, 3, 5, 8, 11, 15} : new int[]{0, 2, 4, 6, 9};
        for (int radius : radii) {
            int attempts = radius == 0 ? 1 : 10;
            for (int i = 0; i < attempts; i++) {
                double angle = ((hash + i * 37) % 360) * Math.PI / 180.0D;
                int x = tx + (int)Math.round(Math.cos(angle) * radius);
                int z = tz + (int)Math.round(Math.sin(angle) * radius);
                GolemSpawn point = null;
                if (!cluster.isEmpty()) point = safeClusterPatrolPoint(level, cluster, x, z);
                if (point == null && cluster.isEmpty()) {
                    BlockPos pos = safeSurfaceBlockPos(level, x, z);
                    if (pos != null) {
                        GuildStore.Territory at = GuildStore.territoryAt(level, pos);
                        if (at != null) {
                            point = new GolemSpawn(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, at);
                        }
                    }
                }
                if (point == null) continue;
                if (golem instanceof Mob mob && !hasNavigablePatrolPath(mob, point) && !elevated && !isHighAlertThreat(target)) continue;
                return point;
            }
        }
        return null;
    }

    private static boolean moveGolemToPoint(GuildStore.Golem record, Mob mob, GolemSpawn point, double speed, boolean rememberPatrol) {
        if (record == null || mob == null || point == null || point.level == null || mob.level() != point.level) return false;
        GolemPatrolContext context = buildGolemPatrolContext(record, mob, point.territory, point.territory == null ? Collections.emptyList() : List.of(point.territory));
        point = clampPointIntoGuildCluster(record, mob, point, context);
        if (point == null || point.level == null) return false;
        BlockPos pointPos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
        if (!isGolemWalkableSpot(point.level, pointPos)) {
            rememberRouteLearning(record, mob, point, false, "move-unsafe");
            return false;
        }
        boolean corridorWaterBlocked = (context == null || !context.waterDangerMode) && corridorHasWaterHazard(mob, point);
        boolean corridorDropBlocked = corridorHasDeepDropHazard(mob, point);
        if (corridorWaterBlocked || corridorDropBlocked || !hasNavigablePatrolPath(mob, point)) {
            rememberRouteLearning(record, mob, point, false, corridorDropBlocked ? "move-deep-drop" : (corridorWaterBlocked ? "move-water" : "move-blocked"));
            GolemSpawn detour = findRouteDetourPoint(record, mob, point, context);
            if (detour != null) point = detour;
            else if (corridorWaterBlocked || corridorDropBlocked || !roughWalkableCorridor(mob, point)) return false;
        }
        if (isGolemRouteCongested(record, mob, point, context)) {
            rememberRouteLearning(record, mob, point, false, "swarm-congestion");
            GolemSpawn antiStack = findAntiStackDetourPoint(record, mob, point, context);
            if (antiStack != null) point = antiStack;
            else if (rememberPatrol) return false;
        }
        long now = point.level.getGameTime();
        GolemNavigationMemory nav = GOLEM_NAV_MEMORY.computeIfAbsent(record.uuid, ignored -> new GolemNavigationMemory(mob.getX(), mob.getY(), mob.getZ(), now));
        if (now - nav.lastRepathTick < 4L && distanceSqr(point.x, point.y, point.z, nav.lastTargetX, nav.lastTargetY, nav.lastTargetZ) < 2.0D * 2.0D) return true;
        nav.lastRepathTick = now;
        Long tpAt = GOLEM_POST_TELEPORT_TICK.get(record.uuid);
        if (tpAt == null || now - tpAt > GOLEM_POST_TELEPORT_GRACE_TICKS) GOLEM_POST_TELEPORT_TICK.remove(record.uuid);
        nav.lastTargetX = point.x;
        nav.lastTargetY = point.y;
        nav.lastTargetZ = point.z;
        try {
            mob.getNavigation().moveTo(point.x, point.y, point.z, speed);
        } catch (Exception ignored) {
            setWantedMovePosition(mob, point.x, point.y, point.z, speed);
        }
        reserveGolemRoute(record, mob, point, rememberPatrol ? "patrol" : "task");
        if (rememberPatrol) rememberPatrolDestination(record, point);
        rememberRouteLearning(record, mob, point, true, "route-accepted");
        return true;
    }

    private static void setWantedMovePosition(Mob mob, double x, double y, double z, double speed) {
        if (mob == null) return;
        try {
            Object control = mob.getClass().getMethod("getMoveControl").invoke(mob);
            if (control != null) control.getClass().getMethod("setWantedPosition", double.class, double.class, double.class, double.class).invoke(control, x, y, z, speed);
        } catch (Throwable ignored) {}
    }

    private static boolean tryRecoverStuckGolem(GuildStore.Golem record, Entity entity, GuildStore.Territory territory) {
        if (record == null || entity == null || !(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) return false;
        boolean physicallyTrapped = isGolemPhysicallyTrapped(entity);
        boolean unevenTrap = isGolemInUnevenFallTrap(level, entity.blockPosition());
        boolean teleportCooldown = isForcedTeleportOnCooldown(record.uuid, entity) && !physicallyTrapped && !unevenTrap;
        long now = level.getGameTime();
        GolemNavigationMemory nav = GOLEM_NAV_MEMORY.computeIfAbsent(record.uuid, ignored -> new GolemNavigationMemory(entity.getX(), entity.getY(), entity.getZ(), now));
        GolemStuckState stuck = GOLEM_STUCK.computeIfAbsent(record.uuid, ignored -> new GolemStuckState(entity.getX(), entity.getY(), entity.getZ()));
        nav.blockedChecks++;
        if (now - stuck.lastRecoveryTick >= 5L) {
            stuck.recoveryAttempts++;
            stuck.lastRecoveryTick = now;
        }
        rememberRouteLearning(record, entity, new GolemSpawn(level, nav.lastTargetX, nav.lastTargetY, nav.lastTargetZ, GuildStore.territoryAt(level, new BlockPos((int)Math.floor(nav.lastTargetX), (int)Math.floor(nav.lastTargetY), (int)Math.floor(nav.lastTargetZ)))), false, physicallyTrapped ? "stuck-trapped" : (unevenTrap ? "stuck-uneven-drop" : "stuck"));
        int requiredAttempts = (physicallyTrapped || unevenTrap) ? GOLEM_TRAPPED_RECOVERY_ATTEMPTS_BEFORE_TELEPORT : GOLEM_RECOVERY_ATTEMPTS_BEFORE_TELEPORT;
        if (nav.blockedChecks >= GOLEM_HARD_RETURN_BLOCKED_CHECKS && stuck.recoveryAttempts >= requiredAttempts) return teleportCooldown;
        try { mob.getNavigation().stop(); } catch (Exception ignored) {}
        clearEntityVelocity(entity);
        tryJump(mob);

        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, territory, GuildStore.dimensionId(level));
        if (cluster.isEmpty() && territory != null) cluster = List.of(territory);

        GolemSpawn ledgeExit = findLedgeOrRavineRecoveryPoint(record, mob, cluster, territory);
        if (ledgeExit != null && moveGolemToPoint(record, mob, ledgeExit, record.elite ? 1.12D : 0.98D, true)) return true;

        if (physicallyTrapped || unevenTrap) {
            GolemSpawn stepOut = findImmediateStepOutPoint(record, mob, territory);
            if (stepOut != null && moveGolemToPoint(record, mob, stepOut, record.elite ? 1.10D : 0.96D, true)) return true;
            GolemSpawn elevation = findElevationRecoveryPoint(record, mob, cluster, territory);
            if (elevation != null && moveGolemToPoint(record, mob, elevation, record.elite ? 1.12D : 0.98D, true)) return true;
        }

        LivingEntity target = currentGolemTarget(mob);
        if (target != null && target.isAlive() && shouldKeepCurrentGolemTarget(record, entity, target, territory)) {
            GolemSpawn flank = smartTargetApproachPoint(record, entity, target);
            if (flank != null && moveGolemToPoint(record, mob, flank, record.elite ? 1.14D : 0.98D, false)) return true;
        }

        GolemSpawn escape = findUnstuckEscapePoint(record, mob, cluster, territory);
        if (escape != null && moveGolemToPoint(record, mob, escape, record.elite ? 1.05D : 0.90D, true)) return true;
        GolemSpawn fallback = findRandomPointInTerritory(territory);
        if (fallback != null && nav.blockedChecks >= GOLEM_SOFT_REROUTE_BLOCKED_CHECKS && moveGolemToPoint(record, mob, fallback, record.elite ? 1.02D : 0.88D, true)) return true;
        return teleportCooldown || nav.blockedChecks < GOLEM_HARD_RETURN_BLOCKED_CHECKS;
    }

    private static GolemSpawn findLedgeOrRavineRecoveryPoint(GuildStore.Golem record, Mob mob, List<GuildStore.Territory> cluster, GuildStore.Territory territory) {
        if (record == null || mob == null || !(mob.level() instanceof ServerLevel level)) return null;
        if ((cluster == null || cluster.isEmpty()) && territory != null) cluster = List.of(territory);
        if (cluster == null || cluster.isEmpty()) return null;
        BlockPos origin = mob.blockPosition();
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

        GolemSpawn best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int step = 1; step <= 12; step++) {
            for (int[] dir : dirs) {
                int x = origin.getX() + dir[0] * step;
                int z = origin.getZ() + dir[1] * step;
                for (int dy : new int[]{0, 1, 2, 3, -1, 4, -2, 5}) {
                    GolemSpawn point = safeClusterPatrolPointNearY(level, cluster, x, z, origin.getY() + dy);
                    if (point == null) continue;
                    BlockPos pos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
                    if (isNearDeepDrop(level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) continue;
                    if (hasWaterNear(level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS)) continue;
                    int vertical = Math.abs(pos.getY() - origin.getY());
                    if (vertical > GOLEM_UNEVEN_RECOVERY_MAX_VERTICAL_DELTA + 1) continue;
                    int score = 180 - step * 7 - vertical * 13 - learnedRoutePenalty(record, point);
                    if (hasNavigablePatrolPath(mob, point)) score += 55;
                    else if (roughWalkableCorridor(mob, point)) score += 25;
                    else continue;
                    if (isPatrolDestinationCrowded(record, point, 2.0D)) score -= 25;
                    if (score > bestScore) {
                        bestScore = score;
                        best = point;
                    }
                    if (score >= 190) return point;
                }
            }
        }
        return best;
    }

    private static GolemSpawn findImmediateStepOutPoint(GuildStore.Golem record, Mob mob, GuildStore.Territory territory) {
        if (record == null || mob == null || !(mob.level() instanceof ServerLevel level)) return null;
        GuildStore.Territory current = territory == null ? GuildStore.territoryAt(level, mob.blockPosition()) : territory;
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, current, GuildStore.dimensionId(level));
        if (cluster.isEmpty() && current != null) cluster = List.of(current);
        int x0 = mob.blockPosition().getX();
        int y0 = mob.blockPosition().getY();
        int z0 = mob.blockPosition().getZ();
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1},{2,0},{-2,0},{0,2},{0,-2},{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{-1,2},{1,-2},{-1,-2}};

        // Спочатку шукаємо точку на поточному/сусідньому Y. Це важливо для ям, сходинок,
        // печерних країв і місць падіння: голем не має одразу телепортуватися нагору,
        // якщо може сам вийти або відійти в робочу позицію.
        for (int[] off : offsets) {
            GolemSpawn point = safeClusterPatrolPointNearY(level, cluster, x0 + off[0], z0 + off[1], y0);
            if (point == null && current != null) point = safeClusterPatrolPointNearY(level, List.of(current), x0 + off[0], z0 + off[1], y0);
            if (point == null) continue;
            if (!isPatrolDestinationCrowded(record, point, 2.25D)) return point;
        }

        // Якщо локального виходу нема — пробуємо поверхню/край, але все одно всередині гільдії.
        for (int[] off : offsets) {
            GolemSpawn point = cluster.isEmpty() ? null : safeClusterPatrolPoint(level, cluster, x0 + off[0], z0 + off[1]);
            if (point == null && current != null) point = safeClusterPatrolPoint(level, List.of(current), x0 + off[0], z0 + off[1]);
            if (point == null) continue;
            if (!isPatrolDestinationCrowded(record, point, 2.25D)) return point;
        }
        return null;
    }

    private static GolemSpawn findElevationRecoveryPoint(GuildStore.Golem record, Mob mob, List<GuildStore.Territory> cluster, GuildStore.Territory territory) {
        if (record == null || mob == null || !(mob.level() instanceof ServerLevel level)) return null;
        if ((cluster == null || cluster.isEmpty()) && territory != null) cluster = List.of(territory);
        if (cluster == null || cluster.isEmpty()) return null;
        BlockPos origin = mob.blockPosition();
        int hash = Math.abs(String.valueOf(record.uuid).hashCode());

        GolemSpawn best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int radius = 2; radius <= GOLEM_UNEVEN_RECOVERY_MAX_RADIUS; radius += radius < 8 ? 2 : 4) {
            int samples = Math.max(8, radius * 4);
            for (int i = 0; i < samples; i++) {
                double angle = ((hash + i * 23 + radius * 41) % 360) * Math.PI / 180.0D;
                int x = origin.getX() + (int)Math.round(Math.cos(angle) * radius);
                int z = origin.getZ() + (int)Math.round(Math.sin(angle) * radius);
                GolemSpawn local = safeClusterPatrolPointNearY(level, cluster, x, z, origin.getY());
                GolemSpawn surface = safeClusterPatrolPoint(level, cluster, x, z);
                GolemSpawn[] candidates = local == null ? new GolemSpawn[]{surface} : new GolemSpawn[]{local, surface};
                for (GolemSpawn point : candidates) {
                    if (point == null) continue;
                    BlockPos pos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
                    if (isNearDeepDrop(level, pos, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT)) continue;
                    int vertical = Math.abs(pos.getY() - origin.getY());
                    boolean reachable = vertical <= GOLEM_UNEVEN_RECOVERY_MAX_VERTICAL_DELTA || tryNativeNavigationReachability(mob, point) == Boolean.TRUE || roughWalkableCorridor(mob, point);
                    if (!reachable) continue;
                    int score = 120 - radius * 4 - vertical * 9 - nearbyWaterCount(level, pos, GOLEM_PATROL_WATER_AVOID_RADIUS) * 20 - learnedRoutePenalty(record, point);
                    if (hasNavigablePatrolPath(mob, point)) score += 40;
                    if (score > bestScore) {
                        bestScore = score;
                        best = point;
                    }
                    if (score >= 110) return point;
                }
            }
        }
        return best;
    }

    private static GolemSpawn findUnstuckEscapePoint(GuildStore.Golem record, Mob mob, List<GuildStore.Territory> cluster, GuildStore.Territory territory) {
        if (record == null || mob == null || !(mob.level() instanceof ServerLevel level)) return null;
        int baseX = mob.blockPosition().getX();
        int baseZ = mob.blockPosition().getZ();
        int hash = Math.abs(String.valueOf(record.uuid).hashCode());
        for (int radius : new int[]{4, 7, 11, 16, 22}) {
            for (int i = 0; i < 12; i++) {
                double angle = ((hash + i * 31 + radius * 17) % 360) * Math.PI / 180.0D;
                int x = baseX + (int)Math.round(Math.cos(angle) * radius);
                int z = baseZ + (int)Math.round(Math.sin(angle) * radius);
                GolemSpawn point = cluster == null || cluster.isEmpty() ? null : safeClusterPatrolPoint(level, cluster, x, z);
                if (point == null && territory != null) point = safeClusterPatrolPoint(level, List.of(territory), x, z);
                if (point == null) continue;
                if (hasNavigablePatrolPath(mob, point) || roughWalkableCorridor(mob, point)) return point;
            }
        }
        if (territory != null) return findRandomPointInTerritory(territory);
        return null;
    }

    private static void tryJump(Mob mob) {
        if (mob == null) return;
        try {
            Object control = mob.getClass().getMethod("getJumpControl").invoke(mob);
            if (control != null) control.getClass().getMethod("jump").invoke(control);
        } catch (Throwable ignored) {}
    }

    private static boolean isNearGuildBorder(GuildStore.Territory territory, Entity entity) {
        if (territory == null || entity == null) return false;
        double minEdge = Math.min(
                Math.min(Math.abs(entity.getX() - GuildStore.minX(territory)), Math.abs(entity.getX() - GuildStore.maxX(territory))),
                Math.min(Math.abs(entity.getZ() - GuildStore.minZ(territory)), Math.abs(entity.getZ() - GuildStore.maxZ(territory)))
        );
        return minEdge <= 6.0D;
    }

    private static boolean isNearClusterBorder(List<GuildStore.Territory> cluster, Entity entity) {
        if (cluster == null || cluster.isEmpty() || entity == null) return false;
        for (GuildStore.Territory territory : cluster) {
            if (GuildStore.isInsideTerritory(territory, entity.blockPosition())) return isNearGuildBorder(territory, entity);
        }
        return false;
    }

    private static String guildIdFromCombatEntity(Entity entity) {
        ServerPlayer player = playerFromCombatEntity(entity);
        if (player != null) return GuildStore.playerGuildId(player);
        if (isGuildGolem(entity)) return guildIdFromGuildGolem(entity);
        return null;
    }

    /**
     * Resolves the real player behind indirect combat sources: arrows, tridents, thrown potions,
     * fireballs, TNT-like entities and tame/summoned entities when the mapping exposes an owner.
     * Reflection is intentionally read-only here so adjacent 1.21.x mappings do not break compile.
     */
    private static ServerPlayer playerFromCombatEntity(Entity entity) {
        if (entity == null) return null;
        if (entity instanceof ServerPlayer player) return player;
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile && projectile.getOwner() instanceof ServerPlayer owner) return owner;
        Entity reflectedOwner = reflectedOwnerEntity(entity);
        if (reflectedOwner instanceof ServerPlayer owner) return owner;
        if (reflectedOwner != null && reflectedOwner != entity) {
            ServerPlayer nested = playerFromCombatEntity(reflectedOwner);
            if (nested != null) return nested;
        }
        UUID ownerUuid = reflectedOwnerUuid(entity);
        return ownerUuid == null ? null : onlinePlayer(ownerUuid);
    }

    private static Entity reflectedOwnerEntity(Entity entity) {
        if (entity == null) return null;
        String[] names = {"getOwner", "getOwnerEntity", "getOwnerLivingEntity", "getThrower", "getShooter", "getControllingPassenger"};
        for (String name : names) {
            try {
                Method method = entity.getClass().getMethod(name);
                if (method.getParameterCount() != 0) continue;
                Object value = method.invoke(entity);
                if (value instanceof Entity owner && owner != entity) return owner;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static UUID reflectedOwnerUuid(Entity entity) {
        if (entity == null) return null;
        String[] names = {"getOwnerUUID", "getOwnerUuid", "getOwnerId", "getCreator", "getThrowerUUID", "getShooterUUID"};
        for (String name : names) {
            try {
                Method method = entity.getClass().getMethod(name);
                if (method.getParameterCount() != 0) continue;
                Object value = method.invoke(entity);
                if (value instanceof UUID uuid) return uuid;
                if (value instanceof java.util.Optional<?> optional) {
                    Object inner = optional.orElse(null);
                    if (inner instanceof UUID uuid) return uuid;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static ServerPlayer onlinePlayer(UUID uuid) {
        if (uuid == null || server == null || server.getPlayerList() == null) return null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != null && uuid.equals(player.getUUID())) return player;
        }
        return null;
    }

    private static void trackGuildPortalTravel() {
        if (server == null || server.getPlayerList() == null) return;
        Set<String> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null) continue;
            String uuid = player.getUUID().toString();
            online.add(uuid);
            String guildId = GuildStore.playerGuildId(player);
            String dim = GuildStore.dimensionId(player.level());
            long tick = player.level().getGameTime();
            PlayerTravelMemory old = PLAYER_TRAVEL_MEMORY.get(uuid);
            if (old != null && guildId != null) {
                if (!Objects.equals(old.dimension, dim)) {
                    String kind = portalKindFromDimensionChange(old.dimension, dim);
                    if (kind != null) recordPortalEntry(player, guildId, kind, tick);
                } else if ("minecraft:the_end".equals(dim) && tick - old.tick <= 12L) {
                    double dist = distanceSqr(old.x, old.y, old.z, player.getX(), player.getY(), player.getZ());
                    if (dist >= 96.0D * 96.0D) recordPortalEntry(player, guildId, "end_gateway", tick);
                }
            }
            PLAYER_TRAVEL_MEMORY.put(uuid, new PlayerTravelMemory(dim, player.getX(), player.getY(), player.getZ(), tick));
        }
        PLAYER_TRAVEL_MEMORY.keySet().removeIf(uuid -> !online.contains(uuid));
        for (List<PendingPortalEntry> entries : PENDING_PORTAL_ENTRIES.values()) {
            if (entries != null) entries.removeIf(entry -> entry == null || entry.tick + PORTAL_PAIR_WINDOW_TICKS < currentServerTickFallback());
        }
        PENDING_PORTAL_ENTRIES.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
    }

    private static long currentServerTickFallback() {
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) return level.getGameTime();
        }
        return 0L;
    }

    private static String portalKindFromDimensionChange(String from, String to) {
        if (to == null) return null;
        if ("minecraft:the_nether".equals(to)) return "nether";
        if ("minecraft:the_end".equals(to)) return "end";
        if ("minecraft:overworld".equals(to) && ("minecraft:the_nether".equals(from) || "minecraft:the_end".equals(from))) return "return";
        return null;
    }

    private static void recordPortalEntry(ServerPlayer player, String guildId, String kind, long tick) {
        if (player == null || guildId == null || kind == null) return;
        String achievementId = switch (kind) {
            case "nether" -> "portal_nether";
            case "end" -> "portal_end";
            case "return" -> "portal_return";
            case "end_gateway" -> "portal_end_gateway";
            default -> null;
        };
        if (achievementId == null || GuildStore.hasAchievement(guildId, achievementId)) return;
        String key = guildId + ":" + kind;
        List<PendingPortalEntry> entries = PENDING_PORTAL_ENTRIES.computeIfAbsent(key, ignored -> new ArrayList<>());
        entries.removeIf(entry -> entry == null || tick - entry.tick > PORTAL_PAIR_WINDOW_TICKS);
        String uuid = player.getUUID().toString();
        for (PendingPortalEntry entry : entries) {
            if (entry == null || Objects.equals(entry.playerUuid, uuid)) continue;
            GuildStore.GuildAchievementUnlock unlock = GuildStore.unlockAchievement(guildId, achievementId, player.getName().getString());
            if (unlock != null) announceGuildAchievement(unlock, List.of(player));
            entries.clear();
            return;
        }
        entries.add(new PendingPortalEntry(guildId, kind, uuid, tick));
    }

    private static void registerCombatContribution(LivingEntity victim, Entity attacker) {
        registerCombatContribution(victim, attacker, 1.0F);
    }

    private static void registerCombatContribution(LivingEntity victim, Entity attacker, float amount) {
        if (victim == null || attacker == null || victim instanceof ServerPlayer) return;
        ServerPlayer player = playerFromCombatEntity(attacker);
        if (player == null) return;
        String guildId = GuildStore.playerGuildId(player);
        if (guildId == null) return;
        String victimUuid = victim.getUUID().toString();
        Map<String, CombatContribution> map = ENTITY_COMBAT_CONTRIBUTORS.computeIfAbsent(victimUuid, ignored -> new HashMap<>());
        String playerUuid = player.getUUID().toString();
        CombatContribution current = map.get(playerUuid);
        long tick = victim.level() == null ? player.level().getGameTime() : victim.level().getGameTime();
        float damage = Math.max(1.0F, amount);
        if (current == null) current = new CombatContribution(guildId, player.getName().getString(), GuildStore.dimensionId(player.level()), player.getX(), player.getY(), player.getZ(), tick, damage);
        else current = current.add(damage, player.getX(), player.getY(), player.getZ(), tick);
        map.put(playerUuid, current);
    }

    private static void cleanupCombatContributors() {
        if (ENTITY_COMBAT_CONTRIBUTORS.isEmpty()) return;
        long now = currentServerTickFallback();
        ENTITY_COMBAT_CONTRIBUTORS.entrySet().removeIf(entry -> {
            Map<String, CombatContribution> map = entry.getValue();
            if (map == null) return true;
            map.entrySet().removeIf(e -> e.getValue() == null || now - e.getValue().tick > COMBAT_CONTRIBUTION_TICKS);
            return map.isEmpty();
        });
    }

    private static String resolveGuildForKill(LivingEntity victim, Entity attacker, ThreatProfile threat) {
        Map<String, Integer> scores = new HashMap<>();
        String directGuildId = guildIdFromCombatEntity(attacker);
        if (directGuildId != null) scores.put(directGuildId, scores.getOrDefault(directGuildId, 0) + 5);
        Map<String, CombatContribution> contributors = ENTITY_COMBAT_CONTRIBUTORS.get(victim.getUUID().toString());
        long now = victim.level() == null ? currentServerTickFallback() : victim.level().getGameTime();
        if (contributors != null) {
            for (CombatContribution c : contributors.values()) {
                if (isActiveContribution(c, now)) scores.put(c.guildId, scores.getOrDefault(c.guildId, 0) + 3);
            }
        }
        // Nearby members are only a fallback for old worlds or edge cases where an event chain
        // does not expose the source entity. Real XP distribution below uses contributors only.
        if (scores.isEmpty()) {
            for (ServerPlayer player : eligibleNearbyPlayersForAnyGuild(victim, threat)) {
                String id = GuildStore.playerGuildId(player);
                if (id != null) scores.put(id, scores.getOrDefault(id, 0) + 1);
            }
        }
        String best = null;
        int bestScore = 0;
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                best = e.getKey();
                bestScore = e.getValue();
            }
        }
        return best;
    }

    private static boolean isActiveContribution(CombatContribution contribution, long now) {
        return contribution != null
                && contribution.guildId != null
                && !contribution.guildId.isBlank()
                && now - contribution.tick <= COMBAT_CONTRIBUTION_TICKS;
    }

    private static List<CombatContribution> activeCombatContributions(LivingEntity victim) {
        List<CombatContribution> out = new ArrayList<>();
        if (victim == null) return out;
        Map<String, CombatContribution> contributors = ENTITY_COMBAT_CONTRIBUTORS.get(victim.getUUID().toString());
        if (contributors == null || contributors.isEmpty()) return out;
        long now = victim.level() == null ? currentServerTickFallback() : victim.level().getGameTime();
        for (CombatContribution c : contributors.values()) if (isActiveContribution(c, now)) out.add(c);
        out.sort(Comparator
                .comparing((CombatContribution c) -> c.guildId == null ? "" : c.guildId)
                .thenComparing(c -> c.playerName == null ? "" : c.playerName));
        return out;
    }

    private static List<GuildExperienceShare> guildExperienceSharesForKill(LivingEntity victim, String fallbackGuildId, int baseXp) {
        List<GuildExperienceShare> shares = new ArrayList<>();
        if (baseXp <= 0) return shares;
        List<CombatContribution> contributors = activeCombatContributions(victim);
        if (contributors.isEmpty()) {
            if (fallbackGuildId != null) shares.add(new GuildExperienceShare(fallbackGuildId, baseXp, Collections.emptyList()));
            return shares;
        }
        Map<String, GuildExperienceShareBuilder> byGuild = new HashMap<>();
        int count = contributors.size();
        int baseShare = Math.max(0, baseXp / count);
        int remainder = Math.max(0, baseXp % count);
        for (int i = 0; i < contributors.size(); i++) {
            CombatContribution c = contributors.get(i);
            int amount = baseShare + (i < remainder ? 1 : 0);
            if (amount <= 0) continue;
            GuildExperienceShareBuilder builder = byGuild.computeIfAbsent(c.guildId, GuildExperienceShareBuilder::new);
            builder.xp += amount;
            builder.names.add(c.playerName == null || c.playerName.isBlank() ? "учасник" : c.playerName);
        }
        for (GuildExperienceShareBuilder builder : byGuild.values()) {
            if (builder != null && builder.xp > 0) shares.add(builder.build());
        }
        shares.sort(Comparator.comparing(share -> share.guildId == null ? "" : share.guildId));
        if (shares.isEmpty() && fallbackGuildId != null) shares.add(new GuildExperienceShare(fallbackGuildId, baseXp, Collections.emptyList()));
        return shares;
    }

    private static void notifyGuildExperienceParticipants(LivingEntity victim, GuildExperienceShare share, GuildStore.GuildExperienceGain gain, String reason, int appliedBonus, boolean split) {
        if (share == null || gain == null || server == null || server.getPlayerList() == null) return;
        String bonusText = appliedBonus > 0 ? " +" + appliedBonus + " бонусом рівня" : "";
        String splitText = split ? " (розділено між учасниками бою)" : "";
        String names = share.names == null || share.names.isEmpty() ? "" : " · " + String.join(", ", share.names);
        Component message = Component.literal("Home Craft Guilds: +" + gain.added + " досвіду гільдії за " + reason + bonusText + splitText + ". Рівень " + gain.newLevel + "." + names);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || !GuildStore.isMemberOfGuild(player, share.guildId)) continue;
            if (victim != null && player.level() == victim.level() && player.distanceToSqr(victim) <= 96.0D * 96.0D) {
                player.displayClientMessage(message, true);
            }
        }
    }

    private static List<ServerPlayer> eligibleNearbyPlayersForAnyGuild(LivingEntity victim, ThreatProfile threat) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null || victim == null) return out;
        double range = threat == null ? 64.0D : threat.participationRange;
        double maxSqr = range * range;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || !player.isAlive() || player.level() != victim.level()) continue;
            if (GuildStore.playerGuildId(player) == null) continue;
            if (player.distanceToSqr(victim) <= maxSqr) out.add(player);
        }
        return out;
    }

    private static void handleGuildKillAchievement(String guildId, LivingEntity victim, Entity attacker, ThreatProfile threat) {
        if (guildId == null || victim == null || threat == null || threat.achievementId == null) return;
        if (GuildStore.hasAchievement(guildId, threat.achievementId)) return;
        List<ServerPlayer> participants = eligibleGuildParticipants(guildId, victim, threat);
        if (participants.size() < 2) return;
        String unlockedBy = attacker instanceof ServerPlayer player ? player.getName().getString() : participants.get(0).getName().getString();
        GuildStore.GuildAchievementUnlock unlock = GuildStore.unlockAchievement(guildId, threat.achievementId, unlockedBy);
        if (unlock == null) return;
        for (ServerPlayer participant : participants) giveEmeralds(participant, threat.emeraldReward);
        announceGuildAchievement(unlock, participants);
        if (unlock.leveledUp()) announceGuildLevelUp(new GuildStore.GuildExperienceGain(unlock.guildId, unlock.guildName, unlock.definition.xpReward, unlock.oldXp, unlock.newXp, unlock.oldLevel, unlock.newLevel));
    }

    private static List<ServerPlayer> eligibleGuildParticipants(String guildId, LivingEntity victim, ThreatProfile threat) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null || guildId == null || victim == null) return out;
        double range = threat == null ? 64.0D : threat.participationRange;
        double maxSqr = range * range;
        Set<String> added = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || !player.isAlive() || player.level() != victim.level()) continue;
            if (!GuildStore.isMemberOfGuild(player, guildId)) continue;
            if (player.distanceToSqr(victim) <= maxSqr) {
                out.add(player);
                added.add(player.getUUID().toString());
            }
        }
        Map<String, CombatContribution> contributors = ENTITY_COMBAT_CONTRIBUTORS.get(victim.getUUID().toString());
        if (contributors != null) {
            long now = victim.level().getGameTime();
            for (Map.Entry<String, CombatContribution> entry : contributors.entrySet()) {
                CombatContribution c = entry.getValue();
                if (c == null || !Objects.equals(c.guildId, guildId) || now - c.tick > COMBAT_CONTRIBUTION_TICKS) continue;
                if (added.contains(entry.getKey())) continue;
                ServerPlayer player = findOnlinePlayerByUuid(entry.getKey());
                if (player != null && player.isAlive() && player.level() == victim.level()) {
                    out.add(player);
                    added.add(entry.getKey());
                }
            }
        }
        return out;
    }

    private static void announceGuildAchievement(GuildStore.GuildAchievementUnlock unlock, List<ServerPlayer> directParticipants) {
        if (server == null || unlock == null || unlock.definition == null) return;
        String text = "Home Craft Guilds: ачивку гільдії відкрито — " + unlock.definition.title + "! Нагорода: " + unlock.definition.rewardText + ".";
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (GuildStore.isMemberOfGuild(player, unlock.guildId)) {
                player.displayClientMessage(Component.literal(text), false);
                playGuildAchievementSound(player);
            }
        }
        if (directParticipants != null && unlock.definition.emeraldReward > 0) {
            for (ServerPlayer player : directParticipants) {
                if (player != null) player.displayClientMessage(Component.literal("Home Craft Guilds: +" + unlock.definition.emeraldReward + " смарагдів за участь."), true);
            }
        }
    }

    private static void playGuildAchievementSound(ServerPlayer player) {
        if (player == null) return;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.75F, 1.35F);
        if (player.level() instanceof ServerLevel level) sendClientVisualEventNear(level, "guild_achievement", player.getX(), player.getY() + 1.0D, player.getZ(), false, 32.0D);
    }

    private static ThreatProfile threatProfile(Entity entity) {
        String id = entityTypeId(entity);
        return switch (id) {
            case "minecraft:ender_dragon" -> new ThreatProfile("SSS", 320.0D, 2600, "kill_ender_dragon", 1750, 35, true);
            case "minecraft:wither" -> new ThreatProfile("SSS", 192.0D, 2400, "kill_wither", 1300, 25, true);
            case "minecraft:warden" -> new ThreatProfile("SSS", 128.0D, 2300, "kill_warden", 750, 15, true);
            case "minecraft:elder_guardian" -> new ThreatProfile("SS", 112.0D, 1750, "kill_elder_guardian", 650, 12, true);
            case "minecraft:ravager" -> new ThreatProfile("SS", 96.0D, 1550, "kill_ravager", 520, 10, true);
            case "minecraft:evoker" -> new ThreatProfile("SS", 96.0D, 1500, "kill_evoker", 500, 10, true);
            case "minecraft:piglin_brute" -> new ThreatProfile("S", 80.0D, 1180, "kill_piglin_brute", 420, 8, false);
            case "minecraft:vex" -> new ThreatProfile("S", 72.0D, 1120, null, 0, 0, false);
            case "minecraft:shulker" -> new ThreatProfile("S", 96.0D, 1080, "kill_shulker", 350, 7, false);
            case "minecraft:ghast" -> new ThreatProfile("S", 112.0D, 1020, "kill_ghast", 300, 6, false);
            case "minecraft:blaze" -> new ThreatProfile("S", 72.0D, 930, "kill_blaze", 250, 5, false);
            case "minecraft:breeze" -> new ThreatProfile("S", 72.0D, 930, "kill_breeze", 250, 5, false);
            case "minecraft:creaking" -> new ThreatProfile("S", 80.0D, 1050, "kill_creaking", 450, 8, false);
            case "minecraft:witch" -> new ThreatProfile("A", 64.0D, 780, "kill_witch", 220, 4, false);
            case "minecraft:creeper" -> new ThreatProfile("A", 64.0D, 860, null, 0, 0, false);
            case "minecraft:vindicator", "minecraft:pillager", "minecraft:illusioner" -> new ThreatProfile("A", 64.0D, 720, null, 0, 0, false);
            case "minecraft:drowned" -> hasItemInHands(entity, "trident") ? new ThreatProfile("A", 72.0D, 760, null, 0, 0, false) : new ThreatProfile("B", 52.0D, 460, null, 0, 0, false);
            case "minecraft:enderman", "minecraft:stray", "minecraft:bogged", "minecraft:skeleton" -> new ThreatProfile("A", 64.0D, 680, null, 0, 0, false);
            case "minecraft:hoglin", "minecraft:zoglin", "minecraft:phantom", "minecraft:cave_spider" -> new ThreatProfile("B", 56.0D, 560, null, 0, 0, false);
            case "minecraft:zombie_nautilus", "minecraft:camel_husk", "minecraft:parched" -> new ThreatProfile("B", 56.0D, 540, null, 0, 0, false);
            case "minecraft:magma_cube", "minecraft:slime", "minecraft:spider", "minecraft:zombie", "minecraft:husk", "minecraft:silverfish", "minecraft:endermite" -> new ThreatProfile("B", 48.0D, 420, null, 0, 0, false);
            default -> {
                if (entity != null && entity.getType().getCategory() == MobCategory.MONSTER) yield new ThreatProfile("B", 48.0D, 420, null, 0, 0, false);
                yield ThreatProfile.NONE;
            }
        };
    }

    private static String entityTypeId(Entity entity) {
        try { return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).toLowerCase(Locale.ROOT); }
        catch (Throwable ignored) { return String.valueOf(entity == null ? "" : entity.getType()).toLowerCase(Locale.ROOT); }
    }

    private static boolean hasItemInHands(Entity entity, String itemToken) {
        if (!(entity instanceof LivingEntity living) || itemToken == null) return false;
        try {
            String main = String.valueOf(BuiltInRegistries.ITEM.getKey(living.getMainHandItem().getItem())).toLowerCase(Locale.ROOT);
            String off = String.valueOf(BuiltInRegistries.ITEM.getKey(living.getOffhandItem().getItem())).toLowerCase(Locale.ROOT);
            return main.contains(itemToken) || off.contains(itemToken);
        } catch (Throwable ignored) { return false; }
    }

    private static int golemThreatPriority(Entity entity) {
        ThreatProfile profile = threatProfile(entity);
        return profile == null ? 0 : profile.golemPriority;
    }

    private static boolean isHighAlertThreat(Entity entity) {
        ThreatProfile profile = threatProfile(entity);
        return profile != null && profile.highAlert;
    }

    private static void scanGuildMemberDefenseAlarms(long tickStartNs) {
        if (server == null || server.getPlayerList() == null || golemScaleGolemCount <= 0) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players == null || players.isEmpty()) return;
        int total = players.size();
        int maxPlayers = isGolemGovernorHardRescue() ? 1 : (isGolemGovernorThrottled() ? 2 : Math.min(6, total));
        int start = Math.floorMod(guildMemberThreatScanCursor, total);
        int processed = 0;
        for (int i = 0; i < total && processed < maxPlayers; i++) {
            if (serverTickBudgetExceeded(tickStartNs)) break;
            ServerPlayer member = players.get(Math.floorMod(start + i, total));
            processed++;
            scanGuildMemberDefenseAlarm(member, tickStartNs);
        }
        guildMemberThreatScanCursor = Math.floorMod(start + Math.max(1, processed), total);
    }

    private static void scanGuildMemberDefenseAlarm(ServerPlayer member, long tickStartNs) {
        if (member == null || !member.isAlive()) return;
        String guildId = GuildStore.playerGuildId(member);
        if (guildId == null) return;
        GuildStore.Territory territory = GuildStore.territoryAt(member.level(), member.blockPosition());
        if (territory == null || !"GUILD".equals(territory.type) || !Objects.equals(territory.guildId, guildId)) return;
        if (!(member.level() instanceof ServerLevel level)) return;

        boolean night = isNightTime(level);
        double radius = night ? GUILD_MEMBER_THREAT_SCAN_RADIUS_NIGHT : GUILD_MEMBER_THREAT_SCAN_RADIUS_DAY;
        int budget = night ? 36 : 22;
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Entity entity : entitiesNear(member, radius, budget)) {
            if (serverTickBudgetExceeded(tickStartNs)) return;
            if (!(entity instanceof LivingEntity living) || entity == member || !entity.isAlive()) continue;
            if (isGuildGolem(entity)) continue;
            if (entity instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, guildId)) continue;
            if (shouldIgnoreUndergroundGolemTarget(entity)) continue;
            if (entity.getType().getCategory() != MobCategory.MONSTER && !isHighAlertThreat(entity)) continue;
            if (!night && !isHighAlertThreat(entity) && entity.distanceToSqr(member) > 14.0D * 14.0D) continue;
            int priority = Math.max(1, golemThreatPriority(entity));
            double score = priority + (night ? 110.0D : 0.0D) + (isHighAlertThreat(entity) ? 420.0D : 0.0D);
            score -= Math.sqrt(entity.distanceToSqr(member)) * 3.0D;
            if (score > bestScore) {
                bestScore = score;
                best = living;
            }
        }
        if (best != null) addMemberDefenseCombatAlert(guildId, member, best, night);
    }

    private static void addMemberDefenseCombatAlert(String guildId, ServerPlayer member, LivingEntity threat, boolean night) {
        if (guildId == null || member == null || threat == null || !threat.isAlive()) return;
        long nowTick = member.level().getGameTime();
        String dimension = GuildStore.dimensionId(member.level());
        String threatUuid = threat.getUUID() == null ? "" : threat.getUUID().toString();
        String cooldownKey = guildId + "|" + dimension + "|" + member.getUUID() + "|" + threatUuid;
        Long last = GUILD_MEMBER_THREAT_ALERT_COOLDOWN.get(cooldownKey);
        long cooldown = night ? Math.max(8L, GUILD_MEMBER_THREAT_ALERT_COOLDOWN_TICKS / 2L) : GUILD_MEMBER_THREAT_ALERT_COOLDOWN_TICKS;
        if (GuildStore.guildGolemInterceptTalent(guildId)) cooldown = Math.max(8L, cooldown * 2L / 3L);
        if (last != null && nowTick - last < cooldown) return;
        GUILD_MEMBER_THREAT_ALERT_COOLDOWN.put(cooldownKey, nowTick);

        List<GuildCombatAlert> alerts = GUILD_COMBAT_ALERTS.computeIfAbsent(guildId, ignored -> new ArrayList<>());
        alerts.removeIf(alert -> alert == null || nowTick > alert.expiresAtTick || !Objects.equals(alert.dimension, dimension));
        boolean already = false;
        for (GuildCombatAlert alert : alerts) {
            if (Objects.equals(alert.attackerUuid, threatUuid) && distanceSqr(alert.x, alert.y, alert.z, member.getX(), member.getY(), member.getZ()) <= 10.0D * 10.0D) {
                already = true;
                break;
            }
        }
        if (!already) {
            long ttl = nowTick + (night ? GUILD_COMBAT_ALERT_DURATION_TICKS + 20L * 12L : GUILD_COMBAT_ALERT_DURATION_TICKS);
            alerts.add(new GuildCombatAlert(guildId, dimension, member.getX(), member.getY(), member.getZ(), threatUuid, ttl));
        }
        if (alerts.size() > MAX_COMBAT_ALERTS_PER_GUILD) {
            alerts.sort(Comparator.comparingLong(alert -> alert.expiresAtTick));
            while (alerts.size() > MAX_COMBAT_ALERTS_PER_GUILD) alerts.remove(0);
        }
        markGuildDefenseHot(guildId, member.level(), nowTick, GUILD_MEMBER_DEFENSE_HOT_TICKS);
    }

    private static void markGuildDefenseHot(String guildId, LevelAccessor level, long nowTick, long ttlTicks) {
        if (guildId == null || level == null) return;
        String key = guildId + "|" + GuildStore.dimensionId(level);
        GOLEM_GUILD_HOT_UNTIL_TICK.put(key, nowTick + Math.max(20L, ttlTicks));
    }

    private static void announceGuildLevelUp(GuildStore.GuildExperienceGain gain) {
        if (server == null || gain == null) return;
        String text = "Home Craft Guilds: гільдія " + gain.guildName + " отримала " + gain.newLevel + " рівень!";
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (GuildStore.isMemberOfGuild(player, gain.guildId)) {
                player.displayClientMessage(Component.literal(text), false);
                playGuildLevelUpSound(player);
            }
        }
    }

    private static void playGuildLevelUpSound(ServerPlayer player) {
        if (player == null) return;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.9F, 1.05F);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.65F, 1.2F);
        if (player.level() instanceof ServerLevel level) sendClientVisualEventNear(level, "guild_levelup", player.getX(), player.getY() + 1.0D, player.getZ(), true, 40.0D);
    }

    private static boolean isWeaponLike(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        if (path.isBlank()) return false;
        // Do not use contains("axe"): it incorrectly grants the weapon buff to pickaxes.
        // Only real weapon ids are accepted. Hoes, shovels, pickaxes, blocks and raw materials are rejected.
        if (path.endsWith("_hoe") || path.endsWith("_shovel") || path.endsWith("_pickaxe")) return false;
        if (path.endsWith("_sword") || path.endsWith("_axe")) return true;
        if (path.equals("mace") || path.equals("trident") || path.equals("bow") || path.equals("crossbow")) return true;
        return path.endsWith("_mace")
                || path.endsWith("_trident")
                || path.endsWith("_bow")
                || path.endsWith("_crossbow")
                || path.endsWith("_dagger")
                || path.endsWith("_knife")
                || path.endsWith("_spear")
                || path.endsWith("_halberd")
                || path.endsWith("_rapier")
                || path.endsWith("_saber")
                || path.endsWith("_katana")
                || path.endsWith("_greatsword")
                || path.endsWith("_battleaxe")
                || path.endsWith("_warhammer");
    }

    private static boolean isNearGuildMember(String guildId, Entity threat) {
        if (server == null || threat == null) return false;
        double max = HomeCraftGuildConfig.golemProtectRange();
        double maxSqr = max * max;
        for (ServerPlayer player : playersNearPoint(threat.level(), threat.getX(), threat.getZ(), (int)Math.ceil(max))) {
            if (player == null || player.level() != threat.level()) continue;
            if (!GuildStore.isMemberOfGuild(player, guildId)) continue;
            if (player.distanceToSqr(threat) <= maxSqr) return true;
        }
        return false;
    }

    private static boolean handleEliteGolemInteract(ServerPlayer player, Entity target) {
        if (player == null || target == null || !isGuildGolem(target) || !isEliteGuildGolem(target)) return false;
        String guildId = guildIdFromGuildGolem(target);
        if (guildId == null || !GuildStore.isMemberOfGuild(player, guildId) || !GuildStore.isGuildMaster(player)) return false;
        GuildStore.Golem record = GuildStore.golem(target.getUUID().toString());
        if (record == null || !record.elite) return false;
        String playerUuid = player.getUUID().toString().toLowerCase(Locale.ROOT);
        if (playerUuid.equalsIgnoreCase(String.valueOf(record.followGuildmasterUuid))) {
            GuildStore.clearEliteGolemFollowing(guildId, record.uuid);
            returnGolemToRandomGuildTotem(record, target);
            player.displayClientMessage(Component.literal("Home Craft Guilds: елітний голем повернувся до патрулю території."), true);
        } else {
            GuildStore.setEliteGolemFollowing(guildId, record.uuid, playerUuid);
            if (target instanceof Mob mob) clearGuildGolemAggro(mob);
            player.displayClientMessage(Component.literal("Home Craft Guilds: елітний голем супроводжує главу гільдії."), true);
        }
        return true;
    }

    private static boolean handleEliteGolemFollow(GuildStore.Golem record, Entity entity, boolean runAi) {
        ServerPlayer leader = findOnlinePlayerByUuid(record.followGuildmasterUuid);
        if (leader == null || !leader.isAlive() || !GuildStore.isMemberOfGuild(leader, record.guildId)) {
            GuildStore.clearEliteGolemFollowing(record.guildId, record.uuid);
            returnGolemToRandomGuildTotem(record, entity);
            return true;
        }
        if (leader.level() != entity.level()) {
            GuildStore.clearEliteGolemFollowing(record.guildId, record.uuid);
            returnGolemToRandomGuildTotem(record, entity);
            return true;
        }
        GuildStore.Territory leaderTerritory = GuildStore.territoryAt(leader.level(), leader.blockPosition());
        if (leaderTerritory == null || !"GUILD".equals(leaderTerritory.type) || !Objects.equals(leaderTerritory.guildId, record.guildId)) {
            GuildStore.clearEliteGolemFollowing(record.guildId, record.uuid);
            returnGolemToRandomGuildTotem(record, entity);
            return true;
        }
        return handleEliteGolemLeaderGuard(record, entity, leader, runAi, false);
    }

    private static boolean handleEliteGolemLeaderGuard(GuildStore.Golem record, Entity entity, ServerPlayer leader, boolean runAi, boolean nightAutoGuard) {
        if (record == null || entity == null || leader == null || !leader.isAlive()) return false;
        if (leader.level() != entity.level()) return false;
        if (!GuildStore.isMemberOfGuild(leader, record.guildId)) return false;
        GuildStore.Territory leaderTerritory = GuildStore.territoryAt(leader.level(), leader.blockPosition());
        if (nightAutoGuard && (leaderTerritory == null || !"GUILD".equals(leaderTerritory.type) || !Objects.equals(leaderTerritory.guildId, record.guildId))) return false;
        if (!(entity instanceof Mob mob)) return true;

        LivingEntity target = findLeaderProtectionTarget(record.guildId, entity, leader);
        if (target != null) {
            steerGolemToTarget(record, entity, target, false);
        } else {
            clearInvalidGuildGolemTarget(record.guildId, entity, leaderTerritory);
        }

        if (!runAi) return true;

        double dist = entity.distanceToSqr(leader);
        if (dist > (nightAutoGuard ? 64.0D * 64.0D : 48.0D * 48.0D)) {
            teleportNearLeader(entity, leader);
            resetGolemStuckState(record.uuid, entity);
            return true;
        }

        long tick = entity.level().getGameTime();
        boolean urgent = dist > (nightAutoGuard ? 12.0D * 12.0D : 8.0D * 8.0D);
        boolean periodicWander = (tick + Math.abs(record.uuid == null ? 0 : record.uuid.hashCode())) % (nightAutoGuard ? 70L : 110L) == 0L;
        if (target == null && (urgent || mob.getNavigation().isDone() || periodicWander)) {
            GolemSpawn guardPoint = smartPointNearLeader(record, leader, leaderTerritory, nightAutoGuard);
            if (guardPoint != null) {
                double speed = target != null ? 1.08D : (nightAutoGuard ? 0.92D : 0.86D);
                try { mob.getNavigation().moveTo(guardPoint.x, guardPoint.y, guardPoint.z, speed); } catch (Exception ignored) {}
                rememberPatrolDestination(record, guardPoint);
            }
        }
        return true;
    }

    private static ServerPlayer findGuildmasterOnGuildTerritory(String guildId, ServerLevel level) {
        if (guildId == null || level == null || server == null) return null;
        String masterUuid = GuildStore.guildMasterUuid(guildId);
        if (masterUuid == null || masterUuid.isBlank()) return null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || !player.isAlive() || player.level() != level) continue;
            if (!masterUuid.equalsIgnoreCase(player.getUUID().toString())) continue;
            GuildStore.Territory territory = GuildStore.territoryAt(level, player.blockPosition());
            if (territory != null && "GUILD".equals(territory.type) && Objects.equals(territory.guildId, guildId)) return player;
        }
        return null;
    }

    private static GolemSpawn smartPointNearLeader(GuildStore.Golem record, ServerPlayer leader, GuildStore.Territory leaderTerritory, boolean nightAutoGuard) {
        if (record == null || leader == null || !(leader.level() instanceof ServerLevel level)) return null;
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, leaderTerritory, GuildStore.dimensionId(level));
        if (cluster.isEmpty() && leaderTerritory != null) cluster = List.of(leaderTerritory);
        if (cluster.isEmpty()) return null;
        int index = stableGuildGolemIndex(record);
        int active = Math.max(1, activeGuildGolemCount(record.guildId));
        double baseAngle = (Math.PI * 2.0D) * (Math.floorMod(index, active) / (double)active);
        double slowOrbit = (level.getGameTime() % 1600L) / 1600.0D * Math.PI * 2.0D;
        int baseRadius = nightAutoGuard ? 5 + Math.floorMod(index, 5) : 3 + Math.floorMod(index, 4);
        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = baseAngle + slowOrbit * 0.35D + attempt * 0.62D;
            int radius = baseRadius + (attempt % 6) * (nightAutoGuard ? 2 : 1);
            int x = (int)Math.floor(leader.getX() + Math.cos(angle) * radius);
            int z = (int)Math.floor(leader.getZ() + Math.sin(angle) * radius);
            GolemSpawn point = safeClusterPatrolPoint(level, cluster, x, z);
            if (point != null && !isPatrolDestinationCrowded(record, point, nightAutoGuard ? 4.0D : 3.0D)) return point;
        }
        return pointNearFocusInsideCluster(record, level, cluster, leader.blockPosition());
    }

    private static LivingEntity findLeaderProtectionTarget(String guildId, Entity golem, ServerPlayer leader) {
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double max = HomeCraftGuildConfig.golemProtectRange();
        double maxSqr = max * max;
        for (Entity entity : allEntities(leader.level())) {
            if (!(entity instanceof LivingEntity living) || entity == golem || entity == leader || !entity.isAlive()) continue;
            if (isGuildGolem(entity)) continue;
            if (entity instanceof ServerPlayer player && GuildStore.isMemberOfGuild(player, guildId)) continue;
            if (shouldIgnoreUndergroundGolemTarget(entity)) continue;
            boolean hostile = entity.getType().getCategory() == MobCategory.MONSTER;
            boolean enemyPlayer = entity instanceof ServerPlayer;
            if (!hostile && !enemyPlayer) continue;
            double dist = entity.distanceToSqr(leader);
            boolean elevated = isAerialOrElevatedThreat(entity);
            double allowed = maxSqr * (isHighAlertThreat(entity) ? 1.85D : (elevated ? 1.40D : 1.0D));
            if (dist > allowed) continue;
            int priority = golemThreatPriority(entity);
            double score = priority + (enemyPlayer ? 240.0D : 0.0D) + (isHighAlertThreat(entity) ? 500.0D : 0.0D) + (elevated ? 120.0D : 0.0D) - Math.sqrt(dist) * 3.0D;
            if (entity.distanceToSqr(golem) <= 10.0D * 10.0D) score += 80.0D;
            if (score > bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private static void teleportNearLeader(Entity entity, ServerPlayer leader) {
        if (entity == null || leader == null || !(leader.level() instanceof ServerLevel level)) return;
        GuildStore.Golem record = GuildStore.golem(entity.getUUID().toString());
        if (record == null) {
            double angle = leader.getRandom().nextDouble() * Math.PI * 2.0D;
            double radius = 2.5D + leader.getRandom().nextDouble() * 2.5D;
            BlockPos safe = safeSurfaceBlockPos(level, (int)Math.floor(leader.getX() + Math.cos(angle) * radius), (int)Math.floor(leader.getZ() + Math.sin(angle) * radius));
            if (safe != null) teleportEntity(entity, safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D);
            return;
        }
        GuildStore.Territory territory = GuildStore.territoryAt(level, leader.blockPosition());
        List<GuildStore.Territory> cluster = contiguousGuildCluster(record.guildId, territory, GuildStore.dimensionId(level));
        if (cluster.isEmpty() && territory != null) cluster = List.of(territory);
        GolemSpawn spawn = null;
        if (!cluster.isEmpty()) spawn = pointNearFocusInsideCluster(record, level, cluster, leader.blockPosition());
        if (spawn == null) {
            double angle = leader.getRandom().nextDouble() * Math.PI * 2.0D;
            double radius = 3.0D + leader.getRandom().nextDouble() * 4.0D;
            BlockPos safe = safeSurfaceBlockPos(level, (int)Math.floor(leader.getX() + Math.cos(angle) * radius), (int)Math.floor(leader.getZ() + Math.sin(angle) * radius));
            if (safe != null) {
                GuildStore.Territory at = GuildStore.territoryAt(level, safe);
                if (at != null && "GUILD".equals(at.type) && Objects.equals(at.guildId, record.guildId)) {
                    spawn = new GolemSpawn(level, safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D, at);
                }
            }
        }
        if (spawn != null) relocateGuildGolem(record, entity, spawn, "leader-guard");
    }

    private static boolean isGolemPhysicallyTrapped(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return false;
        BlockPos feet = entity.blockPosition();
        if (isDangerousGolemBlock(level, feet) || isDangerousGolemBlock(level, feet.above()) || isDangerousGolemBlock(level, feet.below())) return true;
        if (isNearDeepDrop(level, feet, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT + 2)) return true;
        if (!isChunkLoaded(level, feet.getX(), feet.getZ())) return false;
        try {
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(feet.above());
            boolean feetBlocked = !feetState.getCollisionShape(level, feet).isEmpty();
            boolean headBlocked = !headState.getCollisionShape(level, feet.above()).isEmpty();
            boolean noFloor = level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
            return feetBlocked || headBlocked || noFloor;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isGolemPhysicallyTrappedAt(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null) return true;
        if (!isChunkLoaded(level, feet.getX(), feet.getZ())) return true;
        try {
            if (isDangerousGolemBlock(level, feet) || isDangerousGolemBlock(level, feet.above()) || isDangerousGolemBlock(level, feet.below())) return true;
            if (isNearDeepDrop(level, feet, GOLEM_DEEP_DROP_CHECK_RADIUS, GOLEM_DEEP_DROP_DANGER_HEIGHT + 2)) return true;
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(feet.above());
            BlockState below = level.getBlockState(feet.below());
            return !feetState.getCollisionShape(level, feet).isEmpty()
                    || !headState.getCollisionShape(level, feet.above()).isEmpty()
                    || below.getCollisionShape(level, feet.below()).isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isHorizontallyBlocked(Entity entity) {
        if (entity == null) return false;
        for (String name : new String[]{"horizontalCollision", "collidedHorizontally"}) {
            try {
                java.lang.reflect.Field field = Entity.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value instanceof Boolean b) return b;
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field field = entity.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value instanceof Boolean b) return b;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static double distanceSqr(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }


    private static final class GuildAwarenessSnapshot {
        static final GuildAwarenessSnapshot EMPTY = new GuildAwarenessSnapshot(-1L, 0, 0, 0, 0, 0, false, false, null);
        final long frame;
        final int memberCount;
        final int otherPlayerCount;
        final int villagerCount;
        final int hostileCount;
        final int threatScore;
        final boolean hostileNearMember;
        final boolean waterDanger;
        final Entity focus;

        GuildAwarenessSnapshot(long frame, int memberCount, int otherPlayerCount, int villagerCount, int hostileCount, int threatScore, boolean hostileNearMember, boolean waterDanger, Entity focus) {
            this.frame = frame;
            this.memberCount = Math.max(0, memberCount);
            this.otherPlayerCount = Math.max(0, otherPlayerCount);
            this.villagerCount = Math.max(0, villagerCount);
            this.hostileCount = Math.max(0, hostileCount);
            this.threatScore = Math.max(0, threatScore);
            this.hostileNearMember = hostileNearMember;
            this.waterDanger = waterDanger;
            this.focus = focus;
        }
    }

    private static final class PlayerHeatSnapshot {
        final long frame;
        final Map<String, List<ServerPlayer>> playersByCell;

        PlayerHeatSnapshot(long frame, Map<String, List<ServerPlayer>> playersByCell) {
            this.frame = frame;
            this.playersByCell = playersByCell == null ? Collections.emptyMap() : playersByCell;
        }
    }

    private static final class WallSurfaceYCache {
        final long frame;
        final int y;

        WallSurfaceYCache(long frame, int y) {
            this.frame = frame;
            this.y = y;
        }
    }

    private static final class EntityLookupCache {
        final long frame;
        final Entity entity;

        EntityLookupCache(long frame, Entity entity) {
            this.frame = frame;
            this.entity = entity;
        }
    }

    private static final class EntitySnapshot {
        final long tick;
        final long frame;
        final List<Entity> entities;

        EntitySnapshot(long tick, long frame, List<Entity> entities) {
            this.tick = tick;
            this.frame = frame;
            this.entities = entities == null ? Collections.emptyList() : entities;
        }
    }

    private static final class GolemRosterCache {
        static final GolemRosterCache EMPTY = new GolemRosterCache(-1L, 1, Collections.emptyMap());
        final long frame;
        final int activeCount;
        final Map<String, Integer> indexByUuid;

        GolemRosterCache(long frame, int activeCount, Map<String, Integer> indexByUuid) {
            this.frame = frame;
            this.activeCount = Math.max(1, activeCount);
            this.indexByUuid = indexByUuid == null ? Collections.emptyMap() : indexByUuid;
        }
    }

    private static final class ClusterCache {
        final long frame;
        final List<GuildStore.Territory> cluster;

        ClusterCache(long frame, List<GuildStore.Territory> cluster) {
            this.frame = frame;
            this.cluster = cluster == null ? Collections.emptyList() : cluster;
        }
    }

    private static final class SurfaceSpotCache {
        final long tick;
        final long frame;
        final BlockPos pos;

        SurfaceSpotCache(long tick, long frame, BlockPos pos) {
            this.tick = tick;
            this.frame = frame;
            this.pos = pos;
        }
    }

    private static final class PathReachabilityCache {
        final long tick;
        final long frame;
        final boolean reachable;

        PathReachabilityCache(long tick, long frame, boolean reachable) {
            this.tick = tick;
            this.frame = frame;
            this.reachable = reachable;
        }
    }

    private static final class LastGolemSeen {
        final String dimension;
        final int x;
        final int y;
        final int z;
        final long tick;

        LastGolemSeen(String dimension, int x, int y, int z, long tick) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tick = tick;
        }
    }

    private static final class GolemNavigationMemory {
        double lastProgressX;
        double lastProgressY;
        double lastProgressZ;
        double lastTargetX;
        double lastTargetY;
        double lastTargetZ;
        long lastProgressTick;
        long lastRepathTick;
        int blockedChecks;

        GolemNavigationMemory(double x, double y, double z, long tick) {
            this.lastProgressX = x;
            this.lastProgressY = y;
            this.lastProgressZ = z;
            this.lastTargetX = x;
            this.lastTargetY = y;
            this.lastTargetZ = z;
            this.lastProgressTick = tick;
            this.lastRepathTick = 0L;
            this.blockedChecks = 0;
        }
    }

    private static final class GolemTargetCache {
        final long frame;
        final long tick;
        final String targetUuid;
        final double targetX;
        final double targetY;
        final double targetZ;
        final double approachX;
        final double approachY;
        final double approachZ;

        GolemTargetCache(long frame, long tick, String targetUuid, double targetX, double targetY, double targetZ, double approachX, double approachY, double approachZ) {
            this.frame = frame;
            this.tick = tick;
            this.targetUuid = targetUuid == null ? "" : targetUuid;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.approachX = approachX;
            this.approachY = approachY;
            this.approachZ = approachZ;
        }
    }


    private static final class PlayerVisualSyncState {
        final String key;
        final int hash;
        long tick;

        PlayerVisualSyncState(String key, int hash, long tick) {
            this.key = key;
            this.hash = hash;
            this.tick = tick;
        }
    }

    private static final class PendingNpcLoginSync {
        final int attempts;
        final long nextFrame;
        final long revision;

        PendingNpcLoginSync(int attempts, long nextFrame, long revision) {
            this.attempts = attempts;
            this.nextFrame = nextFrame;
            this.revision = revision;
        }
    }

    private static final class GuildBuffState {
        final String guildId;
        final long revision;
        final int level;
        final int healthBonusPercent;
        final int armorBonusPercent;
        final int damageBonusPercent;
        final int speedBonusPercent;
        final double jumpBonus;
        final int damageReductionPercent;
        final boolean nightVision;
        final boolean waterBreathing;
        final boolean fireResistance;

        GuildBuffState(String guildId, long revision, int level, int healthBonusPercent, int armorBonusPercent, int damageBonusPercent, int speedBonusPercent, double jumpBonus, int damageReductionPercent, boolean nightVision, boolean waterBreathing, boolean fireResistance) {
            this.guildId = guildId == null ? "" : guildId;
            this.revision = revision;
            this.level = level;
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

        String signature(boolean armorEquipped) {
            return GUILD_BUFF_PIPELINE_VERSION + "|" + guildId + "|" + revision + "|" + level + "|"
                    + healthBonusPercent + "|" + (armorEquipped ? armorBonusPercent : 0) + "|" + damageBonusPercent + "|" + speedBonusPercent + "|" + jumpBonus + "|" + damageReductionPercent + "|" + nightVision + "|" + waterBreathing + "|" + fireResistance;
        }
    }

    private static final class GolemStuckState {
        double x;
        double y;
        double z;
        int stuckChecks;
        int recoveryAttempts;
        long lastRecoveryTick;

        GolemStuckState(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.stuckChecks = 0;
            this.recoveryAttempts = 0;
            this.lastRecoveryTick = 0L;
        }
    }


    private static final class PrioritizedAsyncTask implements Runnable, Comparable<PrioritizedAsyncTask> {
        final int priority;
        final long sequence;
        final Runnable delegate;

        PrioritizedAsyncTask(int priority, Runnable delegate) {
            this.priority = priority;
            this.sequence = GOLEM_ASYNC_TASK_SEQUENCE.incrementAndGet();
            this.delegate = delegate;
        }

        @Override
        public void run() {
            if (delegate != null) delegate.run();
        }

        @Override
        public int compareTo(PrioritizedAsyncTask other) {
            if (other == null) return -1;
            int byPriority = Integer.compare(other.priority, this.priority);
            if (byPriority != 0) return byPriority;
            return Long.compare(this.sequence, other.sequence);
        }
    }

    private static final class AsyncTerritorySnapshot {
        final String id;
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        final int centerX;
        final int centerZ;

        AsyncTerritorySnapshot(String id, int minX, int minZ, int maxX, int maxZ, int centerX, int centerZ) {
            this.id = id == null ? "" : id;
            this.minX = Math.min(minX, maxX);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxZ = Math.max(minZ, maxZ);
            this.centerX = centerX;
            this.centerZ = centerZ;
        }
    }

    private static final class AsyncPatrolRequest {
        final String uuid;
        final String guildId;
        final String dimension;
        final double currentX;
        final double currentZ;
        final long tick;
        final long frame;
        final long territoryRevision;
        final long golemRevision;
        final boolean night;
        final boolean danger;
        final boolean waterMode;
        final int roleOrdinal;
        final int golemIndex;
        final int activeGolems;
        final int scaleMode;
        final List<AsyncTerritorySnapshot> territories;

        AsyncPatrolRequest(String uuid, String guildId, String dimension, double currentX, double currentZ, long tick, long frame, long territoryRevision, long golemRevision, boolean night, boolean danger, boolean waterMode, int roleOrdinal, int golemIndex, int activeGolems, int scaleMode, List<AsyncTerritorySnapshot> territories) {
            this.uuid = uuid == null ? "" : uuid;
            this.guildId = guildId == null ? "" : guildId;
            this.dimension = dimension == null ? "" : dimension;
            this.currentX = currentX;
            this.currentZ = currentZ;
            this.tick = tick;
            this.frame = frame;
            this.territoryRevision = territoryRevision;
            this.golemRevision = golemRevision;
            this.night = night;
            this.danger = danger;
            this.waterMode = waterMode;
            this.roleOrdinal = roleOrdinal;
            this.golemIndex = Math.max(0, golemIndex);
            this.activeGolems = Math.max(1, activeGolems);
            this.scaleMode = Math.max(0, scaleMode);
            this.territories = territories == null ? Collections.emptyList() : territories;
        }
    }

    private static final class AsyncPatrolPlan {
        final String version;
        final String uuid;
        final String guildId;
        final String dimension;
        final String territoryId;
        final int x;
        final int z;
        final long frame;
        final long tick;
        final long territoryRevision;
        final long golemRevision;

        AsyncPatrolPlan(String version, String uuid, String guildId, String dimension, String territoryId, int x, int z, long frame, long tick, long territoryRevision, long golemRevision) {
            this.version = version == null ? "" : version;
            this.uuid = uuid == null ? "" : uuid;
            this.guildId = guildId == null ? "" : guildId;
            this.dimension = dimension == null ? "" : dimension;
            this.territoryId = territoryId == null ? "" : territoryId;
            this.x = x;
            this.z = z;
            this.frame = frame;
            this.tick = tick;
            this.territoryRevision = territoryRevision;
            this.golemRevision = golemRevision;
        }
    }

    private static final class GuildCombatAlert {
        final String guildId;
        final String dimension;
        final double x;
        final double y;
        final double z;
        final String attackerUuid;
        final long expiresAtTick;

        GuildCombatAlert(String guildId, String dimension, double x, double y, double z, String attackerUuid, long expiresAtTick) {
            this.guildId = guildId;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.attackerUuid = attackerUuid;
            this.expiresAtTick = expiresAtTick;
        }
    }

    private static final class ThreatProfile {
        static final ThreatProfile NONE = new ThreatProfile("NONE", 48.0D, 0, null, 0, 0, false);
        final String tier;
        final double participationRange;
        final int golemPriority;
        final String achievementId;
        final int xpReward;
        final int emeraldReward;
        final boolean highAlert;

        ThreatProfile(String tier, double participationRange, int golemPriority, String achievementId, int xpReward, int emeraldReward, boolean highAlert) {
            this.tier = tier;
            this.participationRange = participationRange;
            this.golemPriority = golemPriority;
            this.achievementId = achievementId;
            this.xpReward = xpReward;
            this.emeraldReward = emeraldReward;
            this.highAlert = highAlert;
        }
    }

    private static final class PlayerTravelMemory {
        final String dimension;
        final double x;
        final double y;
        final double z;
        final long tick;

        PlayerTravelMemory(String dimension, double x, double y, double z, long tick) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tick = tick;
        }
    }

    private static final class PendingPortalEntry {
        final String guildId;
        final String kind;
        final String playerUuid;
        final long tick;

        PendingPortalEntry(String guildId, String kind, String playerUuid, long tick) {
            this.guildId = guildId;
            this.kind = kind;
            this.playerUuid = playerUuid;
            this.tick = tick;
        }
    }

    private static final class CombatContribution {
        final String guildId;
        final String playerName;
        final String dimension;
        final double x;
        final double y;
        final double z;
        final long tick;
        final float damage;

        CombatContribution(String guildId, String playerName, String dimension, double x, double y, double z, long tick, float damage) {
            this.guildId = guildId;
            this.playerName = playerName;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tick = tick;
            this.damage = damage;
        }

        CombatContribution add(float amount, double x, double y, double z, long tick) {
            return new CombatContribution(guildId, playerName, dimension, x, y, z, tick, damage + Math.max(0.0F, amount));
        }
    }

    private static final class GuildExperienceShare {
        final String guildId;
        final int xp;
        final List<String> names;

        GuildExperienceShare(String guildId, int xp, List<String> names) {
            this.guildId = guildId;
            this.xp = Math.max(0, xp);
            this.names = names == null ? Collections.emptyList() : List.copyOf(names);
        }
    }

    private static final class GuildExperienceShareBuilder {
        final String guildId;
        int xp;
        final List<String> names = new ArrayList<>();

        GuildExperienceShareBuilder(String guildId) {
            this.guildId = guildId;
        }

        GuildExperienceShare build() {
            return new GuildExperienceShare(guildId, xp, names);
        }
    }

    private static final class ClusterBounds {
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;

        ClusterBounds(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }
    }

    private static final class LearningPenaltyCache {
        final long frame;
        final int penalty;

        LearningPenaltyCache(long frame, int penalty) {
            this.frame = frame;
            this.penalty = penalty;
        }
    }

    private static final class TerrainProfile {
        final int water;
        final int deepDrops;
        final int obstacles;
        final int structureBlockers;
        final int narrowPassages;
        final int buildingShells;
        final int balconyEdges;
        final int roughness;
        final int openSides;
        final int penalty;

        TerrainProfile(int water, int deepDrops, int obstacles, int structureBlockers, int narrowPassages, int buildingShells, int balconyEdges, int roughness, int openSides, int penalty) {
            this.water = water;
            this.deepDrops = deepDrops;
            this.obstacles = obstacles;
            this.structureBlockers = structureBlockers;
            this.narrowPassages = narrowPassages;
            this.buildingShells = buildingShells;
            this.balconyEdges = balconyEdges;
            this.roughness = roughness;
            this.openSides = openSides;
            this.penalty = penalty;
        }
    }

    private static final class TerrainProfileCache {
        final long tick;
        final long frame;
        final TerrainProfile profile;

        TerrainProfileCache(long tick, long frame, TerrainProfile profile) {
            this.tick = tick;
            this.frame = frame;
            this.profile = profile;
        }
    }


    private static final class TerritoryActivityStats {
        static final TerritoryActivityStats EMPTY = new TerritoryActivityStats(0, 0, 0, 0, 0, null);
        final int members;
        final int golems;
        final int borderScore;
        final int threatScore;
        final int waterScore;
        final Entity focus;

        TerritoryActivityStats(int members, int golems, int borderScore, int threatScore, int waterScore, Entity focus) {
            this.members = Math.max(0, members);
            this.golems = Math.max(0, golems);
            this.borderScore = Math.max(0, borderScore);
            this.threatScore = Math.max(0, threatScore);
            this.waterScore = Math.max(0, waterScore);
            this.focus = focus;
        }
    }

    private static final class TerritoryActivityCache {
        final long tick;
        final long frame;
        final TerritoryActivityStats stats;

        TerritoryActivityCache(long tick, long frame, TerritoryActivityStats stats) {
            this.tick = tick;
            this.frame = frame;
            this.stats = stats == null ? TerritoryActivityStats.EMPTY : stats;
        }
    }

    private static final class PatrolCellInsight {
        final int penalty;
        final int dayBonus;
        final int nightBonus;
        final int openScore;
        final int structureScore;
        final int riskScore;
        final int edgeScore;
        final int entranceScore;
        final int centralSafety;
        final boolean pathLike;
        final boolean houseApproach;
        final boolean routeReject;

        PatrolCellInsight(int penalty, int dayBonus, int nightBonus, int openScore, int structureScore, int riskScore, int edgeScore, int entranceScore, int centralSafety, boolean pathLike, boolean houseApproach, boolean routeReject) {
            this.penalty = Math.max(0, penalty);
            this.dayBonus = Math.max(0, dayBonus);
            this.nightBonus = Math.max(0, nightBonus);
            this.openScore = Math.max(0, openScore);
            this.structureScore = Math.max(0, structureScore);
            this.riskScore = Math.max(0, riskScore);
            this.edgeScore = Math.max(0, edgeScore);
            this.entranceScore = Math.max(0, entranceScore);
            this.centralSafety = Math.max(0, centralSafety);
            this.pathLike = pathLike;
            this.houseApproach = houseApproach;
            this.routeReject = routeReject;
        }
    }

    private static final class TerritoryIntelligence {
        final String guildId;
        final String dimension;
        final String signature;
        final int samples;
        final int rejected;
        final int openFieldScore;
        final int structureRisk;
        final int edgeExposure;
        final int entranceScore;
        final int centralSafety;
        final int coverageQuality;
        final Map<String, PatrolCellInsight> cells;

        TerritoryIntelligence(String guildId, String dimension, String signature, int samples, int rejected, int openFieldScore, int structureRisk, int edgeExposure, int entranceScore, int centralSafety, int coverageQuality, Map<String, PatrolCellInsight> cells) {
            this.guildId = guildId == null ? "" : guildId;
            this.dimension = dimension == null ? "" : dimension;
            this.signature = signature == null ? "" : signature;
            this.samples = Math.max(0, samples);
            this.rejected = Math.max(0, rejected);
            this.openFieldScore = Math.max(0, openFieldScore);
            this.structureRisk = Math.max(0, structureRisk);
            this.edgeExposure = Math.max(0, edgeExposure);
            this.entranceScore = Math.max(0, entranceScore);
            this.centralSafety = Math.max(0, centralSafety);
            this.coverageQuality = Math.max(0, coverageQuality);
            this.cells = cells == null ? Collections.emptyMap() : cells;
        }

        PatrolCellInsight cell(BlockPos pos) {
            if (pos == null || cells.isEmpty()) return null;
            return cells.get(cellKeyForPosition(pos.getX(), pos.getZ()));
        }
    }

    private static final class TerritoryIntelligenceCache {
        final long tick;
        final long frame;
        final TerritoryIntelligence intelligence;

        TerritoryIntelligenceCache(long tick, long frame, TerritoryIntelligence intelligence) {
            this.tick = tick;
            this.frame = frame;
            this.intelligence = intelligence;
        }
    }

    private static final class GolemRouteReservation {
        final long frame;
        final long tick;
        final String guildId;
        final String dimension;
        final double startX;
        final double startY;
        final double startZ;
        final double x;
        final double y;
        final double z;
        final String reason;

        GolemRouteReservation(long frame, long tick, String guildId, String dimension, double startX, double startY, double startZ, double x, double y, double z, String reason) {
            this.frame = frame;
            this.tick = tick;
            this.guildId = guildId == null ? "" : guildId;
            this.dimension = dimension == null ? "" : dimension;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.x = x;
            this.y = y;
            this.z = z;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final class GolemThreatAssignment {
        final long frame;
        final long tick;
        final String guildId;
        final String targetKey;
        final String mode;

        GolemThreatAssignment(long frame, long tick, String guildId, String targetKey, String mode) {
            this.frame = frame;
            this.tick = tick;
            this.guildId = guildId == null ? "" : guildId;
            this.targetKey = targetKey == null ? "" : targetKey;
            this.mode = mode == null ? "" : mode;
        }
    }

    private static final class GolemTaskState {
        final long frame;
        final long tick;
        final String task;
        final String targetKey;
        final double x;
        final double y;
        final double z;

        GolemTaskState(long frame, long tick, String task, String targetKey, double x, double y, double z) {
            this.frame = frame;
            this.tick = tick;
            this.task = task == null ? "" : task;
            this.targetKey = targetKey == null ? "" : targetKey;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class GolemPatrolContext {
        static final GolemPatrolContext EMPTY = new GolemPatrolContext(Collections.emptyList(), false, null, 0, false, false);
        final List<GuildStore.Territory> cluster;
        final boolean night;
        final Entity focus;
        final int bestThreatScore;
        final boolean dangerNearGuildMember;
        final boolean waterDangerMode;

        GolemPatrolContext(List<GuildStore.Territory> cluster, boolean night, Entity focus, int bestThreatScore, boolean dangerNearGuildMember, boolean waterDangerMode) {
            this.cluster = cluster == null ? Collections.emptyList() : cluster;
            this.night = night;
            this.focus = focus;
            this.bestThreatScore = bestThreatScore;
            this.dangerNearGuildMember = dangerNearGuildMember;
            this.waterDangerMode = waterDangerMode;
        }
    }

    private static final class GolemPatrolPlan {
        final String version;
        final GolemPatrolRole role;
        final String territoryId;
        final double x;
        final double y;
        final double z;
        final long tick;
        final boolean dangerMode;
        final boolean waterMode;

        GolemPatrolPlan(String version, GolemPatrolRole role, String territoryId, double x, double y, double z, long tick, boolean dangerMode, boolean waterMode) {
            this.version = version == null ? "" : version;
            this.role = role;
            this.territoryId = territoryId == null ? "" : territoryId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tick = tick;
            this.dangerMode = dangerMode;
            this.waterMode = waterMode;
        }
    }

    private static final class GolemPatrolMemory {
        final double x;
        final double y;
        final double z;
        final String territoryId;
        final long tick;

        GolemPatrolMemory(double x, double y, double z, String territoryId, long tick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.territoryId = territoryId;
            this.tick = tick;
        }
    }

    private static final class PendingGolemRevive {
        final String oldUuid;
        final String guildId;
        final String type;
        final boolean elite;
        final String dimension;
        final BlockPos pos;
        final String actorUuid;
        int ticksLeft;

        PendingGolemRevive(String oldUuid, String guildId, String type, boolean elite, String dimension, BlockPos pos, String actorUuid, int ticksLeft) {
            this.oldUuid = oldUuid;
            this.guildId = guildId;
            this.type = type;
            this.elite = elite;
            this.dimension = dimension;
            this.pos = pos;
            this.actorUuid = actorUuid;
            this.ticksLeft = ticksLeft;
        }
    }

    private static UUID parseUuid(String raw) {
        try { return raw == null ? null : UUID.fromString(raw); }
        catch (Exception ignored) { return null; }
    }

    private static ServerLevel levelForDimension(String dimension) {
        if (server == null) return null;
        try {
            Identifier dimensionId = Identifier.parse(dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension);
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void ensureChunkLoaded(ServerLevel level, int blockX, int blockZ) {
        if (level == null) return;
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        try {
            level.getClass().getMethod("getChunk", int.class, int.class).invoke(level, chunkX, chunkZ);
            return;
        } catch (Throwable ignored) { }
        try {
            Object chunkSource = level.getClass().getMethod("getChunkSource").invoke(level);
            for (String methodName : new String[]{"getChunk", "getChunkNow"}) {
                try {
                    chunkSource.getClass().getMethod(methodName, int.class, int.class).invoke(chunkSource, chunkX, chunkZ);
                    return;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static boolean isChunkLoaded(ServerLevel level, int blockX, int blockZ) {
        if (level == null) return false;
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        for (String methodName : new String[]{"hasChunk", "isLoaded"}) {
            try {
                Object value = level.getClass().getMethod(methodName, int.class, int.class).invoke(level, chunkX, chunkZ);
                if (value instanceof Boolean b) return b;
            } catch (Exception ignored) {}
        }
        try {
            Object chunkSource = level.getClass().getMethod("getChunkSource").invoke(level);
            Object value = chunkSource.getClass().getMethod("hasChunk", int.class, int.class).invoke(chunkSource, chunkX, chunkZ);
            if (value instanceof Boolean b) return b;
        } catch (Exception ignored) {}
        try {
            Object chunkSource = level.getClass().getMethod("getChunkSource").invoke(level);
            Object chunk = chunkSource.getClass().getMethod("getChunkNow", int.class, int.class).invoke(chunkSource, chunkX, chunkZ);
            if (chunk != null) return true;
        } catch (Exception ignored) {}
        return false;
    }


    private static int dynamicGolemTargetScanBudget() {
        if (isGolemGovernorHardRescue()) return 0;
        if (isGolemGovernorThrottled()) return 16;
        if (isUltraGolemServerMode()) return 24;
        if (isExtremeGolemServerMode()) return 40;
        if (isMassiveGolemServerMode()) return 72;
        return 128;
    }

    private static TerritoryActivityStats fastPlayerTerritoryActivityStats(String guildId, GuildStore.Territory territory, LevelAccessor level) {
        if (guildId == null || territory == null || level == null) return TerritoryActivityStats.EMPTY;
        int members = 0;
        int borderScore = 0;
        Entity focus = null;
        for (ServerPlayer player : playersNearTerritory(level, territory, 16)) {
            if (player == null || player.level() != level || !GuildStore.isMemberOfGuild(player, guildId)) continue;
            if (!GuildStore.isInsideTerritory(territory, player.blockPosition())) continue;
            members++;
            if (focus == null) focus = player;
            if (isNearGuildBorder(territory, player)) borderScore += 4;
        }
        return new TerritoryActivityStats(members, 0, borderScore, 0, 0, focus);
    }

    private static int dynamicTerritoryActivityScanBudget() {
        if (isGolemGovernorHardRescue()) return 0;
        if (isGolemGovernorThrottled()) return 24;
        if (isUltraGolemServerMode()) return 48;
        if (isExtremeGolemServerMode()) return 80;
        if (isMassiveGolemServerMode()) return 128;
        return 192;
    }

    private static int dynamicNearbyGolemScanBudget() {
        if (isGolemGovernorHardRescue()) return 0;
        if (isGolemGovernorThrottled()) return 12;
        if (isUltraGolemServerMode()) return 18;
        if (isExtremeGolemServerMode()) return 30;
        if (isMassiveGolemServerMode()) return 48;
        return 96;
    }

    private static int dynamicEntitySnapshotCacheTicks() {
        if (isGolemGovernorHardRescue()) return 120;
        if (isGolemGovernorThrottled()) return 90;
        if (isUltraGolemServerMode()) return 80;
        if (isExtremeGolemServerMode()) return 65;
        if (isMassiveGolemServerMode()) return 45;
        return 30;
    }

    private static Iterable<Entity> entitiesNear(Entity origin, double radius, int limit) {
        if (origin == null) return Collections.emptyList();
        return entitiesNear(origin.level(), origin.getX(), origin.getY(), origin.getZ(), radius, limit, origin);
    }

    private static Iterable<Entity> entitiesNear(ServerLevel level, double x, double y, double z, double radius, int limit) {
        return entitiesNear((LevelAccessor)level, x, y, z, radius, limit, null);
    }

    private static Iterable<Entity> entitiesNear(LevelAccessor level, double x, double y, double z, double radius, int limit) {
        return entitiesNear(level, x, y, z, radius, limit, null);
    }

    private static Iterable<Entity> entitiesNear(LevelAccessor level, double x, double y, double z, double radius, int limit, Entity except) {
        if (level == null || limit <= 0) return Collections.emptyList();
        double r = Math.max(1.0D, radius);
        ArrayList<Entity> out = new ArrayList<>(Math.min(Math.max(1, limit), 64));
        if (level instanceof ServerLevel serverLevel) {
            try {
                AABB box = new AABB(x - r, y - Math.max(8.0D, r * 0.5D), z - r, x + r, y + Math.max(8.0D, r * 0.5D), z + r);
                for (Entity entity : serverLevel.getEntities(except, box, entity -> entity != null && entity.isAlive())) {
                    if (entity == null || entity == except) continue;
                    out.add(entity);
                    if (out.size() >= limit) break;
                }
                return out;
            } catch (Throwable ignored) { }
        }
        int scanned = 0;
        int fallbackLimit = Math.max(limit, Math.min(96, limit * 4));
        for (Entity entity : scaledEntityScan(level, "near-" + ((int)x) + ":" + ((int)z), fallbackLimit)) {
            if (entity == null || entity == except || !entity.isAlive()) continue;
            if (++scanned > fallbackLimit) break;
            if (distanceSqr(x, y, z, entity.getX(), entity.getY(), entity.getZ()) > r * r) continue;
            out.add(entity);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static List<Entity> allEntityList(LevelAccessor level) {
        Iterable<Entity> iterable = allEntities(level);
        if (iterable instanceof List<?> list) {
            List<Entity> out = new ArrayList<>(list.size());
            for (Object value : list) if (value instanceof Entity entity) out.add(entity);
            return out;
        }
        List<Entity> out = new ArrayList<>();
        for (Entity entity : iterable) out.add(entity);
        return out;
    }

    private static Iterable<Entity> scaledEntityScan(LevelAccessor level, String salt, int limit) {
        if (limit <= 0 || level == null) return Collections.emptyList();
        List<Entity> entities = allEntityList(level);
        if (entities.isEmpty()) return entities;
        if (limit == Integer.MAX_VALUE || entities.size() <= limit) return entities;
        int size = entities.size();
        int start = Math.floorMod(String.valueOf(salt).hashCode() + (int)(GOLEM_AI_FRAME * 31L), size);
        int count = Math.max(0, Math.min(limit, size));
        ArrayList<Entity> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(entities.get(Math.floorMod(start + i, size)));
        return out;
    }

    private static Iterable<Entity> allEntities(LevelAccessor level) {
        if (level == null) return Collections.emptyList();
        if (level instanceof ServerLevel serverLevel) {
            String key = GuildStore.dimensionId(serverLevel);
            long tick = serverLevel.getGameTime();
            EntitySnapshot cached = ENTITY_SNAPSHOT_CACHE.get(key);
            if (cached != null && tick - cached.tick <= dynamicEntitySnapshotCacheTicks()) return cached.entities;
            List<Entity> out = collectAllEntities(level);
            ENTITY_SNAPSHOT_CACHE.put(key, new EntitySnapshot(tick, GOLEM_AI_FRAME, out));
            return out;
        }
        return collectAllEntities(level);
    }

    private static List<Entity> collectAllEntities(LevelAccessor level) {
        List<Entity> out = new ArrayList<>();
        if (level == null) return out;
        try {
            Method m = level.getClass().getMethod("getAllEntities");
            Object value = m.invoke(level);
            if (value instanceof Iterable<?> it) {
                for (Object o : it) if (o instanceof Entity e) out.add(e);
                return out;
            }
        } catch (Exception ignored) {}
        try {
            Object entities = level.getClass().getMethod("getEntities").invoke(level);
            Method m = entities.getClass().getMethod("getAll");
            Object value = m.invoke(entities);
            if (value instanceof Iterable<?> it) {
                for (Object o : it) if (o instanceof Entity e) out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static Entity findEntity(UUID uuid) {
        if (server == null || uuid == null) return null;
        EntityLookupCache cached = GOLEM_ENTITY_LOOKUP_CACHE.get(uuid);
        if (cached != null && cached.entity != null && cached.entity.isAlive() && GOLEM_AI_FRAME - cached.frame <= GOLEM_ENTITY_LOOKUP_CACHE_TICKS) {
            return cached.entity;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = findEntityInLevel(level, uuid);
            if (found != null) {
                GOLEM_ENTITY_LOOKUP_CACHE.put(uuid, new EntityLookupCache(GOLEM_AI_FRAME, found));
                return found;
            }
        }
        GOLEM_ENTITY_LOOKUP_CACHE.remove(uuid);
        return null;
    }

    private static Entity findEntityInLevel(ServerLevel level, UUID uuid) {
        if (level == null || uuid == null) return null;
        try {
            if (SERVER_LEVEL_GET_ENTITY_UUID_METHOD == null) {
                SERVER_LEVEL_GET_ENTITY_UUID_METHOD = level.getClass().getMethod("getEntity", UUID.class);
            }
            Object value = SERVER_LEVEL_GET_ENTITY_UUID_METHOD.invoke(level, uuid);
            if (value instanceof Entity e) return e;
        } catch (Exception ignored) {
            SERVER_LEVEL_GET_ENTITY_UUID_METHOD = null;
        }
        return null;
    }

    private static void removeGuildGolems(String guildId) {
        for (GuildStore.Golem record : GuildStore.golemsSnapshot()) {
            if (record == null || !Objects.equals(record.guildId, guildId)) continue;
            try {
                Entity entity = findEntity(UUID.fromString(record.uuid));
                if (entity != null) entity.discard();
            } catch (Exception ignored) {}
        }
        GuildStore.removeGolems(guildId);
    }

    private static boolean isGuildGolem(Entity entity) {
        return entity != null && entity.getTags().contains("homecraft_guild_golem");
    }

    private static boolean isEliteGuildGolem(Entity entity) {
        return entity != null && entity.getTags().contains("homecraft_guild_elite_golem");
    }

    private static String guildIdFromGuildGolem(Entity entity) {
        if (entity == null) return null;
        for (String tag : entity.getTags()) {
            if (tag != null && tag.startsWith("homecraft_guild_id_")) return tag.substring("homecraft_guild_id_".length());
        }
        GuildStore.Golem record = GuildStore.golem(entity.getUUID().toString());
        return record == null ? null : record.guildId;
    }

    private static void setMobTarget(Entity mobEntity, LivingEntity target) {
        if (mobEntity instanceof Mob mob) {
            try { mob.setTarget(target); } catch (Exception ignored) {}
        }
    }

    private static boolean teleportEntityVerified(Entity entity, double x, double y, double z) {
        if (entity == null) return false;
        teleportEntity(entity, x, y, z);
        if (distanceSqr(entity.getX(), entity.getY(), entity.getZ(), x, y, z) <= 3.0D * 3.0D) return true;
        if (invokePositionMethod(entity, x, y, z)) {
            return distanceSqr(entity.getX(), entity.getY(), entity.getZ(), x, y, z) <= 3.0D * 3.0D;
        }
        return false;
    }

    private static void teleportEntity(Entity entity, double x, double y, double z) {
        if (entity == null) return;
        try {
            entity.teleportTo(x, y, z);
            return;
        } catch (Exception ignored) {}
        invokePositionMethod(entity, x, y, z);
    }

    private static void setEntityRotation(Entity entity, float yaw, float pitch) {
        if (entity == null) return;
        tryInvoke(entity, "setYRot", new Class<?>[]{float.class}, yaw);
        tryInvoke(entity, "setXRot", new Class<?>[]{float.class}, pitch);
        tryInvoke(entity, "setYHeadRot", new Class<?>[]{float.class}, yaw);
    }

    private static boolean invokePositionMethod(Entity entity, double x, double y, double z) {
        String[] names = {"moveTo", "absMoveTo", "setPos", "setPosition"};
        for (String name : names) {
            for (Method method : entity.getClass().getMethods()) {
                if (!method.getName().equals(name)) continue;
                try {
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length == 3) {
                        method.invoke(entity, x, y, z);
                        return true;
                    }
                    if (types.length == 5) {
                        method.invoke(entity, x, y, z, entity.getYRot(), entity.getXRot());
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static boolean tryInvoke(Object target, String name, Class<?>[] signature, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, signature);
            method.invoke(target, args);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static EntityType<?> entityTypeById(String id) {
        try {
            String[] parts = id.split(":", 2);
            Identifier key = parts.length == 2 ? Identifier.fromNamespaceAndPath(parts[0], parts[1]) : Identifier.fromNamespaceAndPath("minecraft", id);
            Object registry = BuiltInRegistries.ENTITY_TYPE;
            Object rawType = null;
            for (String methodName : new String[]{"getValue", "get"}) {
                try {
                    Method method = registry.getClass().getMethod(methodName, key.getClass());
                    rawType = method.invoke(registry, key);
                    if (rawType != null) break;
                } catch (Exception ignored) {}
            }
            if (!(rawType instanceof EntityType)) return null;
            @SuppressWarnings("unchecked") EntityType<?> type = (EntityType<?>) rawType;
            Object resolved = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (resolved == null || !String.valueOf(resolved).equals(String.valueOf(key))) return null;
            return type;
        } catch (Exception e) {
            return null;
        }
    }

    private static Entity createEntity(EntityType<?> type, ServerLevel level) {
        if (type == null || level == null) return null;
        for (Method method : type.getClass().getMethods()) {
            if (!method.getName().equals("create")) continue;
            try {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(level.getClass())) {
                    Object result = method.invoke(type, level);
                    if (result instanceof Entity e) return e;
                }
                if (method.getParameterCount() == 2 && method.getParameterTypes()[0].isAssignableFrom(level.getClass())) {
                    Object second = null;
                    if (method.getParameterTypes()[1].isEnum()) {
                        Object[] values = method.getParameterTypes()[1].getEnumConstants();
                        if (values != null && values.length > 0) second = values[0];
                        for (Object v : values == null ? new Object[0] : values) {
                            if (String.valueOf(v).equalsIgnoreCase("COMMAND")) { second = v; break; }
                        }
                    }
                    Object result = method.invoke(type, level, second);
                    if (result instanceof Entity e) return e;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String normalizeGolemType(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.equals("iron") || value.equals("iron_golem") || value.equals("minecraft:iron_golem")) return "minecraft:iron_golem";
        if (value.equals("snow") || value.equals("snow_golem") || value.equals("minecraft:snow_golem")) return "minecraft:snow_golem";
        if (value.equals("copper") || value.equals("copper_golem") || value.equals("minecraft:copper_golem")) return "minecraft:copper_golem";
        if (!value.contains(":")) value = "minecraft:" + value;
        return value;
    }

    private static boolean isStrictOpCommandSource(CommandSourceStack source) {
        if (source == null) return false;
        try {
            ServerPlayer player = source.getPlayerOrException();
            return isStrictOpPlayer(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isStrictOpPlayer(ServerPlayer player) {
        return isOpPlayer(player);
    }

    private static boolean hasPermission(Object source, int level) {
        if (source == null) return false;
        boolean checked = false;
        for (String name : new String[]{"hasPermission", "hasPermissionLevel", "hasPermissions"}) {
            try {
                Object result = source.getClass().getMethod(name, int.class).invoke(source, level);
                if (result instanceof Boolean b) {
                    checked = true;
                    if (b) return true;
                }
            } catch (Exception ignored) {}
        }
        ServerPlayer player = null;
        try {
            Object obj = source.getClass().getMethod("getPlayerOrException").invoke(source);
            if (obj instanceof ServerPlayer sp) player = sp;
        } catch (Exception ignored) {}
        if (player == null) {
            try {
                Object obj = source.getClass().getMethod("getPlayer").invoke(source);
                if (obj instanceof ServerPlayer sp) player = sp;
            } catch (Exception ignored) {}
        }
        if (player != null) {
            for (String name : new String[]{"hasPermissions", "hasPermission", "hasPermissionLevel"}) {
                try {
                    Object result = player.getClass().getMethod(name, int.class).invoke(player, level);
                    if (result instanceof Boolean b && b) return true;
                } catch (Exception ignored) {}
            }
            if (isOpPlayer(player)) return true;
            return false;
        }
        // Console / command blocks have no player. Allow them for setup commands.
        return true;
    }

    private static boolean isOpPlayer(ServerPlayer player) {
        if (player == null) return false;
        MinecraftServer srv = server;
        if (srv == null) {
            try {
                Object level = player.getClass().getMethod("level").invoke(player);
                Object maybeServer = level.getClass().getMethod("getServer").invoke(level);
                if (maybeServer instanceof MinecraftServer minecraftServer) srv = minecraftServer;
            } catch (Throwable ignored) {}
        }
        if (srv == null) return false;
        try {
            Object list = srv.getClass().getMethod("getPlayerList").invoke(srv);
            Object profile = player.getClass().getMethod("getGameProfile").invoke(player);
            for (Method method : list.getClass().getMethods()) {
                if (!method.getName().equals("isOp")) continue;
                if (method.getParameterCount() != 1) continue;
                Object arg = adaptOpArgument(method.getParameterTypes()[0], profile, player);
                if (arg == null) continue;
                Object result = method.invoke(list, arg);
                if (result instanceof Boolean b && b) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Object adaptOpArgument(Class<?> parameterType, Object profile, ServerPlayer player) {
        if (parameterType == null) return null;
        if (profile != null && parameterType.isInstance(profile)) return profile;
        if (parameterType.isInstance(player)) return player;

        String simpleName = parameterType.getSimpleName();
        if (!"NameAndId".equals(simpleName)) return null;

        UUID uuid = null;
        String name = null;
        try {
            Object id = profile.getClass().getMethod("getId").invoke(profile);
            if (id instanceof UUID u) uuid = u;
        } catch (Throwable ignored) {}
        try {
            Object profileName = profile.getClass().getMethod("getName").invoke(profile);
            if (profileName instanceof String n) name = n;
        } catch (Throwable ignored) {}
        if (name == null) {
            try { name = player.getName().getString(); } catch (Throwable ignored) {}
        }

        try {
            return parameterType.getConstructor(profile.getClass()).newInstance(profile);
        } catch (Throwable ignored) {}
        if (uuid != null && name != null) {
            try {
                return parameterType.getConstructor(UUID.class, String.class).newInstance(uuid, name);
            } catch (Throwable ignored) {}
            try {
                return parameterType.getConstructor(String.class, UUID.class).newInstance(name, uuid);
            } catch (Throwable ignored) {}
            for (Method method : parameterType.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() == 2 && method.getReturnType().isAssignableFrom(parameterType)) {
                    Class<?>[] types = method.getParameterTypes();
                    try {
                        if (types[0] == UUID.class && types[1] == String.class) return method.invoke(null, uuid, name);
                        if (types[0] == String.class && types[1] == UUID.class) return method.invoke(null, name, uuid);
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (uuid != null) {
            try {
                return parameterType.getConstructor(UUID.class).newInstance(uuid);
            } catch (Throwable ignored) {}
        }
        if (name != null) {
            try {
                return parameterType.getConstructor(String.class).newInstance(name);
            } catch (Throwable ignored) {}
        }
        return null;
    }


    private static boolean hasItems(ServerPlayer player, net.minecraft.world.item.Item item, int count) {
        if (player == null || item == null || count <= 0 || player.getAbilities().instabuild) return true;
        if (item == Items.EMERALD) GuildCurrency.migratePlayerInventory(player);
        int found = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) found += stack.getCount();
        }
        return found >= count;
    }

    private static boolean takeItems(ServerPlayer player, net.minecraft.world.item.Item item, int count) {
        if (player == null || item == null || count <= 0 || player.getAbilities().instabuild) return true;
        if (item == Items.EMERALD) GuildCurrency.migratePlayerInventory(player);
        if (!hasItems(player, item, count)) return false;
        int left = count;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
        return true;
    }

    private static void giveItems(ServerPlayer player, net.minecraft.world.item.Item item, int count) {
        if (player == null || item == null || count <= 0 || player.getAbilities().instabuild) return;
        if (item == Items.EMERALD) item = Items.EMERALD;
        player.getInventory().add(new ItemStack(item, count));
    }

    private static boolean takeEmeralds(ServerPlayer player, int count) {
        if (count <= 0 || player.getAbilities().instabuild) return true;
        GuildCurrency.migratePlayerInventory(player);
        int found = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.EMERALD)) found += stack.getCount();
        }
        if (found < count) return false;
        int left = count;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(Items.EMERALD)) continue;
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
        return true;
    }

    private static void giveEmeralds(ServerPlayer player, int count) {
        if (count <= 0 || player.getAbilities().instabuild) return;
        GuildCurrency.migratePlayerInventory(player);
        player.getInventory().add(new ItemStack(Items.EMERALD, count));
    }

    private static ServerPlayer findOnlinePlayer(String name) {
        if (server == null || name == null) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(name.trim())) return p;
        }
        return null;
    }

    private static ServerPlayer findOnlinePlayerByUuid(String uuid) {
        if (server == null || uuid == null) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().toString().equalsIgnoreCase(uuid.trim())) return p;
        }
        return null;
    }


    private static void loadNpcEntityIndex(MinecraftServer minecraftServer) {
        NPC_ENTITY_UUID_INDEX.clear();
        NPC_DUPLICATE_LOG_ONCE.clear();
        GUILD_GOLEM_SAFE_SPAWN_CACHE.clear();
        guildGolemSafeSpawnRevisionSeen = -1L;
        npcEntityIndexFile = null;
        if (minecraftServer == null) return;
        try {
            Path dir = minecraftServer.getServerDirectory().resolve("config");
            Files.createDirectories(dir);
            npcEntityIndexFile = dir.resolve("homecraft-guild-npc-entity-index.properties");
            if (!Files.exists(npcEntityIndexFile)) return;
            Properties props = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(npcEntityIndexFile, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String name : props.stringPropertyNames()) {
                if (name == null || !name.startsWith("npc.")) continue;
                String key = HomeCraftGuildConfig.normalizeNpcKey(name.substring("npc.".length()));
                if (key.isBlank()) continue;
                try {
                    NPC_ENTITY_UUID_INDEX.put(key, UUID.fromString(props.getProperty(name, "").trim()));
                } catch (Exception ignored) { }
            }
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: failed to load NPC entity index", t);
        }
    }

    private static void saveNpcEntityIndex() {
        if (npcEntityIndexFile == null) return;
        try { Files.createDirectories(npcEntityIndexFile.getParent()); } catch (Throwable ignored) { }
        Properties props = new Properties();
        for (Map.Entry<String, UUID> entry : NPC_ENTITY_UUID_INDEX.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            props.setProperty("npc." + entry.getKey(), entry.getValue().toString());
        }
        try (java.io.Writer writer = Files.newBufferedWriter(npcEntityIndexFile, StandardCharsets.UTF_8)) {
            props.store(writer, "Home Craft Guild NPC entity UUID index");
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.debug("Home Craft Guilds: failed to save NPC entity index", t);
        }
    }

    private static void indexNpcEntity(String npcKey, Entity entity) {
        if (entity == null) return;
        String key = HomeCraftGuildConfig.normalizeNpcKey(npcKey);
        if (key.isBlank()) key = npcKeyOf(entity);
        if (key == null || key.isBlank()) return;
        UUID uuid = entity.getUUID();
        if (uuid == null) return;
        UUID previous = NPC_ENTITY_UUID_INDEX.put(key, uuid);
        if (!uuid.equals(previous)) saveNpcEntityIndex();
    }

    private static GuildRegistrarEntity findIndexedNpcEntity(MinecraftServer minecraftServer, String npcKey, ServerLevel expectedLevel) {
        if (minecraftServer == null || npcKey == null || expectedLevel == null) return null;
        UUID uuid = NPC_ENTITY_UUID_INDEX.get(npcKey);
        if (uuid == null) return null;
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            Entity entity = null;
            try { entity = level.getEntity(uuid); } catch (Throwable ignored) { }
            if (entity == null) continue;
            if (!entity.isAlive() || !isHomeCraftNpc(entity) || (!npcKey.equals(npcKeyOf(entity)) && !entityHasNpcKey(entity, npcKey))) {
                NPC_ENTITY_UUID_INDEX.remove(npcKey);
                saveNpcEntityIndex();
                return null;
            }
            if (level != expectedLevel) {
                entity.discard();
                NPC_ENTITY_UUID_INDEX.remove(npcKey);
                saveNpcEntityIndex();
                return null;
            }
            return entity instanceof GuildRegistrarEntity registrar ? registrar : null;
        }
        NPC_ENTITY_UUID_INDEX.remove(npcKey);
        saveNpcEntityIndex();
        return null;
    }

    private static GuildRegistrarEntity findNearbyNpcEntity(ServerLevel level, String npcKey, double x, double y, double z, double radius) {
        if (level == null || npcKey == null) return null;
        double max = radius * radius;
        GuildRegistrarEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : entitiesNear(level, x, y, z, radius, 48)) {
            if (!(entity instanceof GuildRegistrarEntity registrar)) continue;
            if (!isHomeCraftNpc(entity) || (!npcKey.equals(npcKeyOf(entity)) && !entityHasNpcKey(entity, npcKey))) continue;
            double d = distanceSqr(x, y, z, entity.getX(), entity.getY(), entity.getZ());
            if (d > max) continue;
            if (d < bestDist) {
                best = registrar;
                bestDist = d;
            }
        }
        return best;
    }


    private static boolean ensureGuildNpcEntities(MinecraftServer minecraftServer, String reason) {
        if (minecraftServer == null) return false;
        boolean any = false;
        for (String key : HomeCraftGuildConfig.npcKeys()) {
            if (HomeCraftGuildConfig.npcEnabled(key)) {
                any |= ensureGuildNpcEntity(minecraftServer, key, reason);
            } else {
                removeGuildNpc(minecraftServer, key);
            }
        }
        if (!"periodic".equalsIgnoreCase(String.valueOf(reason))) {
            guildNpcLastEnsuredRevision = HomeCraftGuildConfig.guildNpcRegistryRevision();
            guildNpcLastPeriodicEnsureMs = System.currentTimeMillis();
        }
        return any;
    }

    private static boolean ensureGuildNpcEntity(MinecraftServer minecraftServer, String key, String reason) {
        if (minecraftServer == null) return false;
        String npcKey = HomeCraftGuildConfig.normalizeNpcKey(key);
        if (npcKey.isBlank()) npcKey = "guild_master";
        boolean periodic = "periodic".equalsIgnoreCase(String.valueOf(reason));
        if (periodic && minecraftServer.getPlayerList().getPlayerCount() <= 0) return false;
        if (!HomeCraftGuildConfig.npcEnabled(npcKey)) {
            removeGuildNpc(minecraftServer, npcKey);
            return false;
        }
        ServerLevel level = resolveGuildNpcLevel(minecraftServer, npcKey);
        if (level == null) return false;

        double x = HomeCraftGuildConfig.npcX(npcKey);
        double y = HomeCraftGuildConfig.npcY(npcKey);
        double z = HomeCraftGuildConfig.npcZ(npcKey);
        float yaw = HomeCraftGuildConfig.npcYaw(npcKey);

        GuildRegistrarEntity existing = findIndexedNpcEntity(minecraftServer, npcKey, level);
        int removed = 0;
        if (existing == null) {
            existing = findNearbyNpcEntity(level, npcKey, x, y, z, 8.0D);
        }
        boolean allowFullScan = !periodic || "startup".equalsIgnoreCase(String.valueOf(reason)) || String.valueOf(reason).toLowerCase(Locale.ROOT).contains("repair") || String.valueOf(reason).toLowerCase(Locale.ROOT).contains("command");
        if (existing == null && allowFullScan) {
            for (ServerLevel scanLevel : minecraftServer.getAllLevels()) {
                for (Entity entity : collectAllEntities(scanLevel)) {
                    if (!isHomeCraftNpc(entity)) continue;
                    String entityKey = npcKeyOf(entity);
                    boolean staleUnkeyedAtTarget = isStaleUnkeyedNpcAt(entity, entityKey, npcKey, x, y, z);
                    if (!npcKey.equals(entityKey) && !entityHasNpcKey(entity, npcKey) && !staleUnkeyedAtTarget) continue;
                    if (scanLevel != level) {
                        entity.discard();
                        removed++;
                        continue;
                    }
                    if (existing == null && entity instanceof GuildRegistrarEntity registrar) {
                        existing = registrar;
                        continue;
                    }
                    entity.discard();
                    removed++;
                }
            }
        }

        if (existing != null) {
            configureGuildNpcEntity(existing, npcKey, x, y, z, yaw);
            indexNpcEntity(npcKey, existing);
            removed += cleanupDuplicateNpcEntities(minecraftServer, npcKey, existing, level, x, y, z, allowFullScan);
            if (removed > 0 && NPC_DUPLICATE_LOG_ONCE.add(npcKey)) HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: cleaned {} duplicate NPC(s) for key={}", removed, npcKey);
            return true;
        }

        long nowMs = System.currentTimeMillis();
        if (periodic && nowMs - guildNpcLastSpawnMs < 60_000L) return false;

        try {
            GuildRegistrarEntity entity = new GuildRegistrarEntity(HomeCraftGuildEntities.GUILD_REGISTRAR.get(), level);
            configureGuildNpcEntity(entity, npcKey, x, y, z, yaw);
            boolean added = level.addFreshEntity(entity);
            if (added) {
                guildNpcLastSpawnMs = nowMs;
                indexNpcEntity(npcKey, entity);
                int cleaned = cleanupDuplicateNpcEntities(minecraftServer, npcKey, entity, level, x, y, z, allowFullScan);
                if (cleaned > 0 && NPC_DUPLICATE_LOG_ONCE.add(npcKey)) HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: cleaned {} duplicate NPC(s) after spawn for key={}", cleaned, npcKey);
                HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: spawned tracked NPC key={} reason={} dim={} pos={}, {}, {} yaw={}", npcKey, reason, GuildStore.dimensionId(level), x, y, z, yaw);
            } else {
                HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: failed to add tracked NPC key={} reason={} dim={} pos={}, {}, {}", npcKey, reason, GuildStore.dimensionId(level), x, y, z);
            }
            return added;
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: failed to spawn tracked NPC key={}", npcKey, t);
            return false;
        }
    }


    private static int cleanupDuplicateNpcEntities(MinecraftServer minecraftServer, String npcKey, GuildRegistrarEntity keep, ServerLevel expectedLevel, double x, double y, double z, boolean allowFullScan) {
        if (minecraftServer == null || expectedLevel == null || npcKey == null || npcKey.isBlank()) return 0;
        int removed = 0;
        java.util.List<Entity> candidates = new java.util.ArrayList<>();

        // Fast path: duplicates literally stacked in/near the configured NPC position.
        for (Entity entity : entitiesNear(expectedLevel, x, y, z, 3.25D, 96)) {
            if (!isHomeCraftNpc(entity)) continue;
            String entityKey = npcKeyOf(entity);
            if (!npcKey.equals(entityKey) && !entityHasNpcKey(entity, npcKey) && !isStaleUnkeyedNpcAt(entity, entityKey, npcKey, x, y, z)) continue;
            candidates.add(entity);
        }

        // Slow path only for startup/commands/repair/admin, never for frequent periodic ensure.
        if (allowFullScan) {
            for (ServerLevel scanLevel : minecraftServer.getAllLevels()) {
                for (Entity entity : collectAllEntities(scanLevel)) {
                    if (!isHomeCraftNpc(entity)) continue;
                    String entityKey = npcKeyOf(entity);
                    if (!npcKey.equals(entityKey) && !entityHasNpcKey(entity, npcKey) && !isStaleUnkeyedNpcAt(entity, entityKey, npcKey, x, y, z)) continue;
                    if (!candidates.contains(entity)) candidates.add(entity);
                }
            }
        }

        if (candidates.size() <= 1) return 0;
        Entity best = keep;
        double bestScore = best == null ? Double.MAX_VALUE : distanceSqr(x, y, z, best.getX(), best.getY(), best.getZ());
        for (Entity entity : candidates) {
            if (!(entity instanceof GuildRegistrarEntity)) continue;
            double score = distanceSqr(x, y, z, entity.getX(), entity.getY(), entity.getZ());
            if (best == null || score < bestScore) {
                best = entity;
                bestScore = score;
            }
        }
        if (best == null) return 0;

        for (Entity entity : candidates) {
            if (entity == null || entity == best) continue;
            entity.discard();
            removed++;
        }
        if (best instanceof GuildRegistrarEntity registrar) {
            configureGuildNpcEntity(registrar, npcKey, x, y, z, HomeCraftGuildConfig.npcYaw(npcKey));
            indexNpcEntity(npcKey, registrar);
        }
        if (removed > 0) saveNpcEntityIndex();
        return removed;
    }

    private static ServerLevel resolveGuildNpcLevel(MinecraftServer minecraftServer, String key) {
        if (minecraftServer == null) return null;
        String expected = HomeCraftGuildConfig.npcDimension(key);
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            if (Objects.equals(GuildStore.dimensionId(level), expected)) return level;
        }
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            String dim = GuildStore.dimensionId(level);
            if (dim != null && (dim.endsWith(":" + expected) || dim.contains(expected))) return level;
        }
        return minecraftServer.overworld();
    }

    private static void configureGuildNpcEntity(GuildRegistrarEntity entity, String key, double x, double y, double z, float yaw) {
        if (entity == null) return;
        String npcKey = HomeCraftGuildConfig.normalizeNpcKey(key);
        if (npcKey.isBlank()) npcKey = "guild_master";
        entity.setupHomeCraftNpc(
                npcKey,
                HomeCraftGuildConfig.npcKind(npcKey),
                HomeCraftGuildConfig.npcSkinId(npcKey),
                HomeCraftGuildConfig.npcName(npcKey),
                HomeCraftGuildConfig.npcSystem(npcKey),
                false,
                HomeCraftGuildConfig.guildNpcRegistryRevision()
        );
        entity.setNoAi(true);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setPersistenceRequired();
        entity.setPos(x, y, z);
        entity.xo = x;
        entity.yo = y;
        entity.zo = z;
        entity.setYRot(yaw);
        entity.setYBodyRot(yaw);
        entity.setYHeadRot(yaw);
        entity.yRotO = yaw;
        entity.yBodyRotO = yaw;
        entity.yHeadRotO = yaw;
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0.0F;
        entity.addTag("homecraft_guild_npc");
        entity.addTag("homecraft_npc_key_" + npcKey);
        if ("guild_master".equals(npcKey)) entity.addTag("homecraft_guild_registrar");
    }

    private static void removeGuildNpc(MinecraftServer server, String key) {
        if (server == null) return;
        String npcKey = HomeCraftGuildConfig.normalizeNpcKey(key);
        if (npcKey.isBlank()) npcKey = "guild_master";
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : collectAllEntities(level)) {
                if (isHomeCraftNpc(entity) && (npcKey.equals(npcKeyOf(entity)) || entityHasNpcKey(entity, npcKey))) {
                    entity.discard();
                    removed++;
                }
            }
        }
        if (removed > 0) HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: removed {} NPC entity/entities for key={}", removed, npcKey);
        if (removed > 0) {
            NPC_ENTITY_UUID_INDEX.remove(npcKey);
            saveNpcEntityIndex();
        }
    }

    private static boolean isHomeCraftNpc(Entity entity) {
        if (entity == null) return false;
        if (entity instanceof GuildRegistrarEntity) return true;
        if (entity.getTags().contains("homecraft_guild_npc")) return true;
        try {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return id != null && HomeCraftGuildMod.MOD_ID.equals(id.getNamespace()) && "guild_registrar".equals(id.getPath());
        } catch (Exception ignored) {
            return false;
        }
    }


    private static boolean isStaleUnkeyedNpcAt(Entity entity, String entityKey, String expectedKey, double x, double y, double z) {
        if (entity == null || expectedKey == null || "guild_master".equals(expectedKey)) return false;
        if (entityKey != null && !entityKey.isBlank() && !"guild_master".equals(entityKey)) return false;
        // Do not hijack an entity that explicitly carries another npc key tag.
        for (String tag : entity.getTags()) {
            if (tag != null && tag.startsWith("homecraft_npc_key_")) return false;
        }
        return distanceSqr(x, y, z, entity.getX(), entity.getY(), entity.getZ()) <= 9.0D;
    }


    private static boolean entityHasNpcKey(Entity entity, String expectedKey) {
        if (entity == null || expectedKey == null || expectedKey.isBlank()) return false;
        String expected = HomeCraftGuildConfig.normalizeNpcKey(expectedKey);
        if (expected.isBlank()) return false;
        try {
            for (String tag : entity.getTags()) {
                if (tag != null && tag.startsWith("homecraft_npc_key_")) {
                    String key = HomeCraftGuildConfig.normalizeNpcKey(tag.substring("homecraft_npc_key_".length()));
                    if (expected.equals(key)) return true;
                }
            }
        } catch (Throwable ignored) { }
        if (entity instanceof GuildRegistrarEntity registrar) {
            return expected.equals(HomeCraftGuildConfig.normalizeNpcKey(registrar.homecraft$npcKey()));
        }
        return false;
    }

    private static String npcKeyOf(Entity entity) {
        // Prefer the persistent entity tag. v133-v138 did not save custom SynchedData to NBT, so
        // after server restart old trader entities can reload with DATA_NPC_KEY=guild_master while
        // still carrying homecraft_npc_key_trader_*. If we trust SynchedData first, ensure/spawn
        // fails to find the old entity and spawns a second NPC at the same position; visually this
        // looks like a custom skin is drawn over a default registrar.
        if (entity != null) {
            for (String tag : entity.getTags()) {
                if (tag != null && tag.startsWith("homecraft_npc_key_")) {
                    String key = HomeCraftGuildConfig.normalizeNpcKey(tag.substring("homecraft_npc_key_".length()));
                    if (!key.isBlank()) return key;
                }
            }
        }
        if (entity instanceof GuildRegistrarEntity registrar) return registrar.homecraft$npcKey();
        return "guild_master";
    }

    private static void saveGuildNpcPoint(ServerPlayer player, String key) {
        if (player == null) return;
        String npcKey = HomeCraftGuildConfig.normalizeNpcKey(key);
        if (npcKey.isBlank()) npcKey = "guild_master";
        boolean system = "guild_master".equals(npcKey) || HomeCraftGuildConfig.npcSystem(npcKey);
        String kind = system ? "system" : "trader";
        // Keep the selected skin when moving/replacing a system NPC. Older code forced
        // guild_master here, so pressing "Поставити тут" silently reverted medieval_armor or
        // another selected skin and caused a client-side stale/default texture flash.
        String skin = HomeCraftGuildConfig.npcSkinId(npcKey);
        String name = HomeCraftGuildConfig.npcName(npcKey);
        HomeCraftGuildConfig.setNpcPosition(
                npcKey,
                kind,
                skin,
                name,
                system,
                GuildStore.dimensionId(player.level()),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot()
        );
    }

    private static String buildNpcAdminSnapshot() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("schemaVersion=4").append('\n');
        sb.append("revision=").append(HomeCraftGuildConfig.guildNpcRegistryRevision()).append('\n');
        sb.append("presets=trader_basic,trader_food,trader_tools,trader_weapons,trader_armor,trader_elite").append('\n');
        sb.append("skins=").append(String.join(",", HomeCraftGuildConfig.npcAvailableSkins())).append('\n');
        for (String key : HomeCraftGuildConfig.npcKeys()) {
            if (key == null || key.isBlank()) continue;
            sb.append("npc|")
                    .append(sanitizeSnapshotField(key)).append('|')
                    .append(HomeCraftGuildConfig.npcEnabled(key) ? "1" : "0").append('|')
                    .append(sanitizeSnapshotField(HomeCraftGuildConfig.npcKind(key))).append('|')
                    .append(HomeCraftGuildConfig.npcSystem(key) ? "1" : "0").append('|')
                    .append(sanitizeSnapshotField(HomeCraftGuildConfig.npcSkinId(key))).append('|')
                    .append(sanitizeSnapshotField(HomeCraftGuildConfig.npcDimension(key))).append('|')
                    .append(String.format(Locale.ROOT, "%.2f|%.2f|%.2f|%.1f|", HomeCraftGuildConfig.npcX(key), HomeCraftGuildConfig.npcY(key), HomeCraftGuildConfig.npcZ(key), HomeCraftGuildConfig.npcYaw(key)))
                    .append(encodeSnapshotField(HomeCraftGuildConfig.npcName(key))).append('|')
                    .append(encodeSnapshotField(HomeCraftGuildConfig.npcTrades(key))).append('|')
                    .append(sanitizeSnapshotField(HomeCraftGuildConfig.npcTraderPresetId(key))).append('|')
                    .append(HomeCraftGuildConfig.npcCustomTrades(key) ? "1" : "0").append('|')
                    .append(sanitizeSnapshotField(HomeCraftGuildConfig.npcTradesHash(key))).append('\n');
        }
        return sb.toString();
    }


    private static boolean ensureGuildRegistrarEntity(MinecraftServer minecraftServer, String reason) {
        if (minecraftServer == null) return false;
        boolean periodic = "periodic".equalsIgnoreCase(String.valueOf(reason));
        if (periodic && minecraftServer.getPlayerList().getPlayerCount() <= 0) return false;
        if (!HomeCraftGuildConfig.guildNpcEnabled()) {
            removeGuildNpcs(minecraftServer);
            return false;
        }
        ServerLevel level = resolveGuildNpcLevel(minecraftServer);
        if (level == null) return false;

        double x = HomeCraftGuildConfig.guildNpcX();
        double y = HomeCraftGuildConfig.guildNpcY();
        double z = HomeCraftGuildConfig.guildNpcZ();
        float yaw = HomeCraftGuildConfig.guildNpcYaw();

        GuildRegistrarEntity existing = null;
        int removed = 0;
        for (ServerLevel scanLevel : minecraftServer.getAllLevels()) {
            for (Entity entity : collectAllEntities(scanLevel)) {
                if (!isGuildRegistrarNpc(entity)) continue;
                if (scanLevel != level) {
                    entity.discard();
                    removed++;
                    continue;
                }
                if (existing == null && entity instanceof GuildRegistrarEntity registrar) {
                    existing = registrar;
                    continue;
                }
                entity.discard();
                removed++;
            }
        }

        if (existing != null) {
            configureGuildRegistrarEntity(existing, x, y, z, yaw);
            if (removed > 0) HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: cleaned {} duplicate guild registrar NPC(s)", removed);
            return true;
        }

        long nowMs = System.currentTimeMillis();
        if (periodic && nowMs - guildNpcLastSpawnMs < 60_000L) return false;

        try {
            GuildRegistrarEntity entity = new GuildRegistrarEntity(HomeCraftGuildEntities.GUILD_REGISTRAR.get(), level);
            configureGuildRegistrarEntity(entity, x, y, z, yaw);
            boolean added = level.addFreshEntity(entity);
            if (added) {
                guildNpcLastSpawnMs = nowMs;
                HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: spawned tracked guild registrar NPC reason={} dim={} pos={}, {}, {} yaw={}", reason, GuildStore.dimensionId(level), x, y, z, yaw);
            } else {
                HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: failed to add guild registrar NPC reason={} dim={} pos={}, {}, {}", reason, GuildStore.dimensionId(level), x, y, z);
            }
            return added;
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.warn("Home Craft Guilds: failed to spawn guild registrar NPC", t);
            return false;
        }
    }

    private static ServerLevel resolveGuildNpcLevel(MinecraftServer minecraftServer) {
        if (minecraftServer == null) return null;
        String expected = HomeCraftGuildConfig.guildNpcDimension();
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            if (Objects.equals(GuildStore.dimensionId(level), expected)) return level;
        }
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            String dim = GuildStore.dimensionId(level);
            if (dim != null && (dim.endsWith(":" + expected) || dim.contains(expected))) return level;
        }
        return minecraftServer.overworld();
    }

    private static void configureGuildRegistrarEntity(GuildRegistrarEntity entity, double x, double y, double z, float yaw) {
        if (entity == null) return;
        entity.setupRegistrar(HomeCraftGuildConfig.guildNpcName());
        entity.setNoAi(true);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setPersistenceRequired();
        entity.setPos(x, y, z);
        entity.xo = x;
        entity.yo = y;
        entity.zo = z;
        entity.setYRot(yaw);
        entity.setYBodyRot(yaw);
        entity.setYHeadRot(yaw);
        entity.yRotO = yaw;
        entity.yBodyRotO = yaw;
        entity.yHeadRotO = yaw;
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0.0F;
        entity.addTag("homecraft_guild_npc");
        entity.addTag("homecraft_guild_registrar");
    }

    private static void removeGuildNpcs(MinecraftServer server) {
        if (server == null) return;
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : collectAllEntities(level)) {
                if (isGuildRegistrarNpc(entity)) {
                    entity.discard();
                    removed++;
                }
            }
        }
        if (removed > 0) HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: removed {} guild registrar NPC entity/entities", removed);
    }

    private static boolean isGuildRegistrarNpc(Entity entity) {
        if (entity == null) return false;
        if (entity instanceof GuildRegistrarEntity) return true;
        if (entity.getTags().contains("homecraft_guild_npc") || entity.getTags().contains("homecraft_guild_registrar")) return true;
        try {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return id != null && HomeCraftGuildMod.MOD_ID.equals(id.getNamespace()) && "guild_registrar".equals(id.getPath());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLookingAtVirtualGuildNpc(ServerPlayer player) {
        if (player == null || !HomeCraftGuildConfig.guildNpcEnabled()) return false;
        String expectedDim = HomeCraftGuildConfig.guildNpcDimension();
        String actualDim = GuildStore.dimensionId(player.level());
        if (!Objects.equals(expectedDim, actualDim)) return false;
        double x = HomeCraftGuildConfig.guildNpcX();
        double y = HomeCraftGuildConfig.guildNpcY();
        double z = HomeCraftGuildConfig.guildNpcZ();
        double maxDistance = 4.85D;
        if (player.distanceToSqr(x, y, z) > maxDistance * maxDistance) return false;
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 view = player.getViewVector(1.0F).normalize();
        Vec3 end = eye.add(view.x * maxDistance, view.y * maxDistance, view.z * maxDistance);
        AABB box = new AABB(x - 0.42D, y, z - 0.42D, x + 0.42D, y + 2.02D, z + 0.42D).inflate(0.16D);
        return rayHitsBox(eye, end, box);
    }

    private static boolean rayHitsBox(Vec3 start, Vec3 end, AABB box) {
        if (start == null || end == null || box == null) return false;
        double[] s = {start.x, start.y, start.z};
        double[] d = {end.x - start.x, end.y - start.y, end.z - start.z};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        double tMin = 0.0D;
        double tMax = 1.0D;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-7D) {
                if (s[i] < min[i] || s[i] > max[i]) return false;
            } else {
                double inv = 1.0D / d[i];
                double t1 = (min[i] - s[i]) * inv;
                double t2 = (max[i] - s[i]) * inv;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return false;
            }
        }
        return true;
    }

    private static void saveVirtualGuildNpcPoint(ServerPlayer player) {
        if (player == null) return;
        HomeCraftGuildConfig.setGuildNpcPosition(
                GuildStore.dimensionId(player.level()),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot()
        );
    }

    private static void openVirtualGuildNpcFor(ServerPlayer player) {
        if (player == null) return;
        if (!HomeCraftGuildConfig.guildNpcEnabled()) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: NPC-точка ще не створена. OP: /homecraftguild npc create"), false);
            return;
        }
        String expectedDim = HomeCraftGuildConfig.guildNpcDimension();
        String actualDim = GuildStore.dimensionId(player.level());
        double x = HomeCraftGuildConfig.guildNpcX();
        double y = HomeCraftGuildConfig.guildNpcY();
        double z = HomeCraftGuildConfig.guildNpcZ();
        if (!Objects.equals(expectedDim, actualDim) || player.distanceToSqr(x, y, z) > 7.0D * 7.0D) {
            player.displayClientMessage(Component.literal("Home Craft Guilds: підійди ближче до гільдійного реєстратора."), true);
            return;
        }
        PacketDistributor.sendToPlayer(player, new OpenCreateGuildPayload(GuildStore.npcSnapshotFor(player)));
    }

    private static void forceAllNpcSync(String reason) {
        NpcSyncManager.onNpcMetadataChanged(server, "force:" + String.valueOf(reason == null ? "npc_changed" : reason));
    }

    private static void forceAllGuildVisualSync() {
        GUILD_VISUAL_SYNC_STATE.clear();
        if (server == null || server.getPlayerList() == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || !(player.level() instanceof ServerLevel level)) continue;
            syncGuildClientVisualsForPlayer(player, level);
        }
    }

    public static BlockState guildBannerStateFor(ServerPlayer player) {
        return HomeCraftGuildBlocks.GUILD_BANNER.get().defaultBlockState();
    }

    private static Block guildBannerBlock(String color) {
        return switch (GuildStore.normalizeGuildColor(color)) {
            case "white" -> Blocks.WHITE_BANNER;
            case "orange" -> Blocks.ORANGE_BANNER;
            case "magenta" -> Blocks.MAGENTA_BANNER;
            case "light_blue" -> Blocks.LIGHT_BLUE_BANNER;
            case "yellow" -> Blocks.YELLOW_BANNER;
            case "lime" -> Blocks.LIME_BANNER;
            case "cyan" -> Blocks.CYAN_BANNER;
            case "purple" -> Blocks.PURPLE_BANNER;
            case "blue" -> Blocks.BLUE_BANNER;
            case "red" -> Blocks.RED_BANNER;
            default -> Blocks.MAGENTA_BANNER;
        };
    }

    private static boolean hasNearbyPlayer(ServerLevel level, double x, double y, double z, double range) {
        if (level == null) return false;
        double max = range * range;
        for (ServerPlayer player : playersNearPoint(level, x, z, (int)Math.ceil(range))) {
            if (player != null && player.level() == level && player.distanceToSqr(x, y, z) <= max) return true;
        }
        return false;
    }


    private static void syncGuildClientVisuals(long tickStartNs) {
        if (server == null || server.getPlayerList() == null) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players == null || players.isEmpty()) return;
        // This is raw topology sync, not server-side wall rendering. Keep it independent
        // from the golem governor, while still slicing very large player lists.
        int max = isUltraGolemServerMode() ? 4 : (isExtremeGolemServerMode() ? 8 : (isMassiveGolemServerMode() ? 16 : players.size()));
        int start = Math.floorMod(guildVisualSyncPlayerCursor, players.size());
        int processed = 0;
        for (int n = 0; n < players.size() && processed < max; n++) {
            ServerPlayer player = players.get(Math.floorMod(start + n, players.size()));
            processed++;
            if (player == null || !(player.level() instanceof ServerLevel level)) continue;
            syncGuildClientVisualsForPlayer(player, level);
        }
        guildVisualSyncPlayerCursor = Math.floorMod(start + Math.max(1, processed), players.size());
    }

    private static void scheduleNpcLoginSync(ServerPlayer player, ServerLevel level) {
        if (player == null || level == null) return;
        UUID uuid = player.getUUID();
        NpcSyncManager.onPlayerJoin(player);
        // Do not invalidate territory visual cache here. NPC skin/bootstrap sync is separate from
        // guild territory walls and must not force a full border snapshot on every login.
        PENDING_NPC_LOGIN_SYNCS.put(uuid, new PendingNpcLoginSync(0, GOLEM_AI_FRAME + 12L, HomeCraftGuildConfig.guildNpcRegistryRevision()));
    }

    private static void processPendingNpcLoginSyncs(long tickStartNs) {
        if (server == null || PENDING_NPC_LOGIN_SYNCS.isEmpty()) return;
        List<UUID> done = new ArrayList<>();
        int processed = 0;
        for (Map.Entry<UUID, PendingNpcLoginSync> entry : new ArrayList<>(PENDING_NPC_LOGIN_SYNCS.entrySet())) {
            if (processed >= 8 || serverTickBudgetExceeded(tickStartNs)) break;
            UUID uuid = entry.getKey();
            PendingNpcLoginSync pending = entry.getValue();
            if (uuid == null || pending == null) { done.add(uuid); continue; }
            if (GOLEM_AI_FRAME < pending.nextFrame) continue;
            ServerPlayer player = server.getPlayerList() == null ? null : server.getPlayerList().getPlayer(uuid);
            if (player == null || !(player.level() instanceof ServerLevel level)) { done.add(uuid); continue; }
            processed++;
            NpcSyncManager.sendNpcBootstrap(player, "join-delayed-" + pending.attempts);
            if (pending.attempts >= 1) {
                done.add(uuid);
            } else {
                PENDING_NPC_LOGIN_SYNCS.put(uuid, new PendingNpcLoginSync(pending.attempts + 1, GOLEM_AI_FRAME + (pending.attempts == 0 ? 40L : 100L), HomeCraftGuildConfig.guildNpcRegistryRevision()));
            }
        }
        for (UUID uuid : done) PENDING_NPC_LOGIN_SYNCS.remove(uuid);
    }

    public static void sendNpcBootstrapSnapshot(ServerPlayer player, ServerLevel level, String reason) {
        if (player == null || level == null) return;
        String skinsRow = buildNpcSkinCacheRow();
        if (skinsRow != null && !skinsRow.isBlank()) {
            PacketDistributor.sendToPlayer(player, new GuildNpcSkinRegistryPayload(skinsRow));
        }
        String npcRows = buildVirtualNpcVisualRow();
        if (npcRows != null && !npcRows.isBlank()) {
            PacketDistributor.sendToPlayer(player, new GuildNpcSyncPayload(npcRows));
        }
        if (HomeCraftGuildConfig.debug()) {
            HomeCraftGuildMod.LOGGER.debug("Home Craft Guilds: sent typed NPC bootstrap to {} reason={} revision={}", player.getName().getString(), reason, HomeCraftGuildConfig.guildNpcRegistryRevision());
        }
    }

    private static void syncGuildClientVisualsForPlayer(ServerPlayer player, ServerLevel level) {
        if (player == null || level == null) return;
        long revision = GuildStore.territoryTopologyRevision();
        long bedRevision = GuildStore.guildBedsRevision();
        String dim = GuildStore.dimensionId(level);
        int cellX = Math.floorDiv(player.blockPosition().getX(), 96);
        int cellZ = Math.floorDiv(player.blockPosition().getZ(), 96);
        long tick = level.getGameTime();
        String key = revision + "|beds=" + bedRevision + "|" + dim + "|" + cellX + "|" + cellZ;
        UUID uuid = player.getUUID();
        PlayerVisualSyncState state = GUILD_VISUAL_SYNC_STATE.get(uuid);
        if (state != null && Objects.equals(state.key, key)) {
            // Smart cache: do not rebuild or resend raw topology while the player is in the
            // same visual cell and territoryTopologyRevision has not changed. Bed labels are
            // lightweight and may be refreshed periodically so walking into label range inside
            // the same cell still shows the owner without forcing a full territory snapshot.
            if (tick - state.tick >= 80L) {
                GuildBedManager.syncLabelsForPlayer(player, level, dim);
                state.tick = tick;
            }
            return;
        }
        List<GuildStore.TerritoryStaticInfo> near = completeGuildVisualTopologyForPlayer(player, level, dim);
        String snapshot = buildGuildVisualSnapshotFor(player, level, near);
        int hash = snapshot.hashCode();
        PacketDistributor.sendToPlayer(player, new GuildTerritoryVisualSyncPayload(snapshot));
        GuildBedManager.syncLabelsForPlayer(player, level, dim);
        GUILD_VISUAL_SYNC_STATE.put(uuid, new PlayerVisualSyncState(key, hash, tick));
    }

    private static void forceGuildVisualTopologyRefresh(ServerLevel changedLevel, BlockPos changedPos, int radius) {
        if (changedLevel == null) return;
        // Rebuild the static topology immediately, then invalidate all client snapshot cache keys.
        // This prevents newly placed/removed adjacent territories from waiting for the next slow visual pass.
        GuildStore.territoryStaticInfosSnapshot();
        GUILD_VISUAL_SYNC_STATE.clear();
        if (server == null || server.getPlayerList() == null) return;
        double radiusSqr = Math.max(32, radius) * (double)Math.max(32, radius);
        double cx = changedPos == null ? 0.0D : changedPos.getX() + 0.5D;
        double cy = changedPos == null ? 0.0D : changedPos.getY() + 0.5D;
        double cz = changedPos == null ? 0.0D : changedPos.getZ() + 0.5D;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || player.level() != changedLevel) continue;
            if (changedPos != null && player.distanceToSqr(cx, cy, cz) > radiusSqr) continue;
            syncGuildClientVisualsForPlayer(player, changedLevel);
        }
    }

    private static List<GuildStore.TerritoryStaticInfo> completeGuildVisualTopologyForPlayer(ServerPlayer player, ServerLevel level, String dim) {
        if (player == null || level == null || dim == null) return Collections.emptyList();
        int px = player.blockPosition().getX();
        int pz = player.blockPosition().getZ();
        List<GuildStore.TerritoryStaticInfo> base = GuildStore.territoryStaticInfosNear(dim, px, pz, 224, 128);
        // Use a wider indexed lookup for topology completion instead of scanning every stored
        // territory on every visual sync. This keeps the anti-fake snapshot fix cheap.
        List<GuildStore.TerritoryStaticInfo> all = GuildStore.territoryStaticInfosNear(dim, px, pz, 384, 256);
        List<GuildStore.TerritoryStaticInfo> out = new ArrayList<>();
        Set<String> added = new HashSet<>();

        for (GuildStore.TerritoryStaticInfo t : base) {
            if (!isVisualGuildTerritory(t, dim)) continue;
            if (distanceSqrToTerritory(player.getX(), player.getZ(), t) > 224.0D * 224.0D) continue;
            addVisualTerritory(out, added, t);
        }

        // Complete every visible same-guild connected cluster. The client must receive all
        // neighbouring totem rectangles at once; otherwise it may briefly draw a fake full
        // perimeter for the first totem and then suppress the second one.
        boolean changed;
        do {
            changed = false;
            for (GuildStore.TerritoryStaticInfo candidate : all) {
                if (!isVisualGuildTerritory(candidate, dim) || added.contains(candidate.id)) continue;
                for (GuildStore.TerritoryStaticInfo existing : out) {
                    if (!sameGuildInfo(candidate, existing)) continue;
                    if (!territoriesTouchOrOverlap(candidate, existing, 1)) continue;
                    addVisualTerritory(out, added, candidate);
                    changed = true;
                    break;
                }
            }
        } while (changed && out.size() < 128);

        // Include nearby different-guild walls that can visually conflict/mix with the cluster.
        // This keeps color blending stable without moving border math back to the server.
        for (GuildStore.TerritoryStaticInfo candidate : all) {
            if (!isVisualGuildTerritory(candidate, dim) || added.contains(candidate.id)) continue;
            if (distanceSqrToTerritory(player.getX(), player.getZ(), candidate) > 288.0D * 288.0D) continue;
            for (GuildStore.TerritoryStaticInfo existing : out) {
                if (sameGuildInfo(candidate, existing)) continue;
                if (territoryGap(candidate, existing) > 2) continue;
                addVisualTerritory(out, added, candidate);
                break;
            }
        }

        out.sort((a, b) -> {
            int c = Double.compare(distanceSqrToTerritory(player.getX(), player.getZ(), a), distanceSqrToTerritory(player.getX(), player.getZ(), b));
            if (c != 0) return c;
            c = safeString(a.guildId).compareTo(safeString(b.guildId));
            if (c != 0) return c;
            c = safeString(a.id).compareTo(safeString(b.id));
            if (c != 0) return c;
            c = Integer.compare(a.minX, b.minX);
            if (c != 0) return c;
            return Integer.compare(a.minZ, b.minZ);
        });
        if (out.size() <= 96) return out;
        return new ArrayList<>(out.subList(0, 96));
    }

    private static boolean addVisualTerritory(List<GuildStore.TerritoryStaticInfo> out, Set<String> added, GuildStore.TerritoryStaticInfo t) {
        if (out == null || added == null || t == null || t.id == null || added.contains(t.id)) return false;
        added.add(t.id);
        out.add(t);
        return true;
    }

    private static boolean isVisualGuildTerritory(GuildStore.TerritoryStaticInfo t, String dim) {
        return t != null && "GUILD".equals(t.type) && Objects.equals(dim, t.dimension) && t.id != null;
    }

    private static boolean sameGuildInfo(GuildStore.TerritoryStaticInfo a, GuildStore.TerritoryStaticInfo b) {
        return a != null && b != null && a.guildId != null && a.guildId.equals(b.guildId);
    }

    private static boolean territoriesTouchOrOverlap(GuildStore.TerritoryStaticInfo a, GuildStore.TerritoryStaticInfo b, int gap) {
        if (a == null || b == null) return false;
        int g = Math.max(0, gap);
        return a.maxX + g >= b.minX && b.maxX + g >= a.minX
                && a.maxZ + g >= b.minZ && b.maxZ + g >= a.minZ;
    }

    private static int territoryGap(GuildStore.TerritoryStaticInfo a, GuildStore.TerritoryStaticInfo b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        int dx = 0;
        if (a.maxX < b.minX) dx = b.minX - a.maxX;
        else if (b.maxX < a.minX) dx = a.minX - b.maxX;
        int dz = 0;
        if (a.maxZ < b.minZ) dz = b.minZ - a.maxZ;
        else if (b.maxZ < a.minZ) dz = a.minZ - b.maxZ;
        return Math.max(dx, dz);
    }

    private static double distanceSqrToTerritory(double x, double z, GuildStore.TerritoryStaticInfo t) {
        if (t == null) return Double.MAX_VALUE;
        double dx = 0.0D;
        if (x < t.minX) dx = t.minX - x;
        else if (x > t.maxX) dx = x - t.maxX;
        double dz = 0.0D;
        if (z < t.minZ) dz = t.minZ - z;
        else if (z > t.maxZ) dz = z - t.maxZ;
        return dx * dx + dz * dz;
    }

    private static String buildGuildVisualSnapshotFor(ServerPlayer player, ServerLevel level, List<GuildStore.TerritoryStaticInfo> territories) {
        String dim = GuildStore.dimensionId(level);
        long revision = GuildStore.territoryTopologyRevision();
        int cellX = player == null ? 0 : Math.floorDiv(player.blockPosition().getX(), 96);
        int cellZ = player == null ? 0 : Math.floorDiv(player.blockPosition().getZ(), 96);
        StringBuilder body = new StringBuilder(1024);
        int added = 0;
        if (territories != null) {
            for (GuildStore.TerritoryStaticInfo t : territories) {
                if (t == null || !"GUILD".equals(t.type) || !Objects.equals(dim, t.dimension)) continue;
                if (added++ > 0) body.append(';');
                appendGuildVisualRow(body, t, revision);
                if (added >= 96) break;
            }
        }
        String bodyText = body.toString();
        String meta = "meta|v2|" + revision + "|" + dim + "|" + added + "|" + bodyText.hashCode() + "|" + cellX + "|" + cellZ;
        return bodyText.isEmpty() ? meta : meta + ";" + bodyText;
    }

    private static String buildNpcSkinCacheRow() {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        long revision = HomeCraftGuildConfig.guildNpcRegistryRevision();
        for (String skin : HomeCraftGuildConfig.npcAvailableSkins()) {
            String id = sanitizeSnapshotField(skin);
            if (id.isBlank()) continue;
            if (any) sb.append(',');
            any = true;
            sb.append(id).append("~0~").append(HomeCraftGuildMod.MOD_ID).append(":textures/entity/npc/").append(id).append(".png");
        }
        return any ? "skins2|" + revision + "|" + sb : "";
    }

    private static String buildVirtualNpcVisualRow() {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (String key : HomeCraftGuildConfig.npcKeys()) {
            if (!HomeCraftGuildConfig.npcEnabled(key)) continue;
            if (any) sb.append(';');
            any = true;
            sb.append(String.format(Locale.ROOT,
                    "npc2|%d|%s|%s|1|%s|%.3f|%.3f|%.3f|%.2f|%s|%s",
                    HomeCraftGuildConfig.guildNpcRegistryRevision(),
                    sanitizeSnapshotField(key),
                    sanitizeSnapshotField(HomeCraftGuildConfig.npcKind(key)),
                    sanitizeSnapshotField(HomeCraftGuildConfig.npcDimension(key)),
                    HomeCraftGuildConfig.npcX(key),
                    HomeCraftGuildConfig.npcY(key),
                    HomeCraftGuildConfig.npcZ(key),
                    HomeCraftGuildConfig.npcYaw(key),
                    sanitizeSnapshotField(HomeCraftGuildConfig.npcSkinId(key)),
                    sanitizeSnapshotField(HomeCraftGuildConfig.npcName(key))));
        }
        if (!any) return "npc2|" + HomeCraftGuildConfig.guildNpcRegistryRevision() + "||system|0";
        return sb.toString();
    }

    private static String sanitizeSnapshotField(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replace('|', ' ').replace(';', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String encodeSnapshotField(String value) {
        if (value == null || value.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void appendGuildVisualRow(StringBuilder out, GuildStore.TerritoryStaticInfo t, long revision) {
        if (out == null || t == null) return;
        int rgb = guildColorRgb(t.guildColor == null ? GuildStore.guildColorById(t.guildId) : t.guildColor);
        // Raw-only visual row. The client calculates side visibility, hidden internal
        // segments and mixed-guild border colors locally from these values.
        out.append(t.dimension).append('|')
           .append(t.id == null ? "" : t.id).append('|')
           .append(t.guildId == null ? "" : t.guildId).append('|')
           .append(t.x).append('|').append(t.y).append('|').append(t.z).append('|')
           .append(t.minX).append('|').append(t.maxX).append('|').append(t.minZ).append('|').append(t.maxZ).append('|')
           .append(rgb).append('|').append(revision);
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    // Guild border and totem visuals are fully client-side. The server sends raw territory
    // values and lightweight visual-event packets; it does not prebuild wall masks or render particles.

    private static boolean isGuildFarmSoil(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.FARMLAND) || state.is(Blocks.SOUL_SAND);
    }

    private static boolean isGuildFarmManagedBlock(BlockState state) {
        return isGuildFarmSoil(state) || isGuildFarmBlock(state) || isGuildFarmUtilityBlock(state);
    }

    private static boolean isGuildFarmUtilityBlock(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.COMPOSTER)
                || state.is(Blocks.PUMPKIN)
                || state.is(Blocks.MELON)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.BAMBOO_SAPLING);
    }

    private static boolean isHoeOnTillableGuildFarmBase(ServerPlayer player, BlockState state) {
        if (player == null || state == null) return false;
        ItemStack stack = player.getMainHandItem();
        if (!isHoeItem(stack)) stack = player.getOffhandItem();
        return isHoeItem(stack) && isTillableGuildFarmBase(state);
    }

    private static boolean isHoeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.is(Items.WOODEN_HOE)
                || stack.is(Items.STONE_HOE)
                || stack.is(Items.IRON_HOE)
                || stack.is(Items.GOLDEN_HOE)
                || stack.is(Items.DIAMOND_HOE)
                || stack.is(Items.NETHERITE_HOE);
    }

    private static boolean isTillableGuildFarmBase(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT);
    }

    private static boolean isGuildFarmBlock(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        return block instanceof net.minecraft.world.level.block.CropBlock
                || block instanceof net.minecraft.world.level.block.StemBlock
                || block instanceof net.minecraft.world.level.block.AttachedStemBlock
                || block instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                || block instanceof net.minecraft.world.level.block.NetherWartBlock
                || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                || block instanceof net.minecraft.world.level.block.CactusBlock
                || block instanceof net.minecraft.world.level.block.CocoaBlock;
    }



    private static void maybeCleanupInactiveGuildTotems(MinecraftServer server, long tickStartNs) {
        if (server == null || !HomeCraftGuildConfig.guildTotemCleanupEnabled()) return;
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(30L, HomeCraftGuildConfig.guildTotemCleanupIntervalSeconds()) * 1000L;
        boolean topologyChanged = GuildStore.territoryTopologyRevision() != guildLastTotemRepairRevision;
        if (!topologyChanged && now - guildLastTotemCleanupMs < intervalMs) return;
        if (isGolemGovernorHardRescue()) return;
        if (!mayRunSecondaryTickWork(tickStartNs, TickWorkPriority.BACKGROUND_SYNC)) return;
        guildLastTotemRepairRevision = GuildStore.territoryTopologyRevision();
        guildLastTotemRepairFrame = GOLEM_AI_FRAME;
        guildLastTotemCleanupMs = now;
        cleanupInactiveGuildTotems(server, tickStartNs, false, topologyChanged ? "topology_changed" : "periodic");
    }

    private static int cleanupInactiveGuildTotems(MinecraftServer server, long tickStartNs, boolean fullPass, String reason) {
        if (server == null || !HomeCraftGuildConfig.guildTotemCleanupEnabled()) return 0;
        if (fullPass && !HomeCraftGuildConfig.guildTotemCleanupOnServerStart() && "startup".equals(reason)) return 0;
        List<GuildStore.Territory> territories = GuildStore.territoriesSnapshot();
        if (territories == null || territories.isEmpty()) return 0;
        int max = fullPass ? territories.size() : Math.min(territories.size(), HomeCraftGuildConfig.guildTotemCleanupMaxChecksPerPass());
        if (max <= 0) return 0;
        int start = fullPass ? 0 : Math.floorMod(guildRepairTerritoryCursor, territories.size());
        int processed = 0;
        int skippedUnloaded = 0;
        Set<String> missingIds = new HashSet<>();
        for (int n = 0; n < territories.size() && processed < max; n++) {
            if (!fullPass && serverTickBudgetExceeded(tickStartNs)) break;
            GuildStore.Territory territory = territories.get(Math.floorMod(start + n, territories.size()));
            processed++;
            if (territory == null || !"GUILD".equals(territory.type)) continue;
            if (territory.id == null || territory.id.isBlank()) continue;
            if (territory.dimension == null || territory.dimension.isBlank()) {
                missingIds.add(territory.id);
                continue;
            }
            try {
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(territory.dimension)));
                if (level == null) {
                    missingIds.add(territory.id);
                    continue;
                }
                BlockPos pos = new BlockPos(territory.x, territory.y, territory.z);
                boolean loaded = true;
                try { loaded = level.isLoaded(pos); } catch (Throwable ignored) {}
                if (!loaded) {
                    skippedUnloaded++;
                    continue;
                }
                BlockState current = level.getBlockState(pos);
                if (!isPhysicalGuildTotemAnchor(current)) missingIds.add(territory.id);
            } catch (Throwable ex) {
                // Bad dimension IDs or corrupted coordinates should not keep a fake active territory alive.
                missingIds.add(territory.id);
            }
        }
        if (!fullPass && !territories.isEmpty()) {
            guildRepairTerritoryCursor = Math.floorMod(start + Math.max(1, processed), territories.size());
        }
        int removed = GuildStore.removeTerritoriesByIds(missingIds, reason == null ? "missing_totem_block" : reason, true);
        if (removed > 0) {
            for (ServerLevel level : server.getAllLevels()) {
                try { forceGuildVisualTopologyRefresh(level, null, 512); } catch (Throwable ignored) {}
            }
            rebuildPrecomputedTerritoryPlans(false, Math.max(64, removed * 8));
            GuildSiteSync.pushTerritoriesAsync();
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: cleaned {} inactive guild totem territory record(s); reason={} checked={} skippedUnloaded={}", removed, reason, processed, skippedUnloaded);
        } else if (HomeCraftGuildConfig.debug() && processed > 0) {
            HomeCraftGuildMod.LOGGER.info("Home Craft Guilds: guild totem cleanup checked={} skippedUnloaded={} reason={} removed=0", processed, skippedUnloaded, reason);
        }
        return removed;
    }

    private static int guildColorRgb(String color) {
        return switch (GuildStore.normalizeGuildColor(color)) {
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

    private static boolean isGuildMarkerEffect(MobEffectInstance effect) {
        if (effect == null) return false;
        Object type = effect.getEffect();
        if (Objects.equals(type, HomeCraftGuildEffects.GUILD_PASSIVE)
                || Objects.equals(type, HomeCraftGuildEffects.GUILD_PLAYER_XP)
                || Objects.equals(type, HomeCraftGuildEffects.GUILD_POTION_DURATION)
                || Objects.equals(type, HomeCraftGuildEffects.GUILD_MOB_XP)
                || Objects.equals(type, HomeCraftGuildEffects.GUILD_TERRITORY_HEALTH)) return true;
        String key = String.valueOf(type);
        return key.contains("homecraftguild:guild_passive")
                || key.contains("homecraftguild:guild_player_xp")
                || key.contains("homecraftguild:guild_potion_duration")
                || key.contains("homecraftguild:guild_mob_xp")
                || key.contains("homecraftguild:guild_territory_health")
                || key.contains("homecraftguild:guild_health")
                || key.contains("homecraftguild:guild_armor")
                || key.contains("homecraftguild:guild_damage")
                || key.contains("homecraftguild:guild_night_vision")
                || key.contains("homecraftguild:guild_water_breathing")
                || key.contains("homecraftguild:guild_fire_resistance");
    }

    private static void updateOneGuildPassiveBuffForRescue() {
        if (server == null || server.getPlayerList() == null) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players == null || players.isEmpty()) return;
        int start = Math.floorMod(guildBuffPlayerCursor, players.size());
        ServerPlayer player = players.get(start);
        guildBuffPlayerCursor = Math.floorMod(start + 1, players.size());
        updatePlayerGuildBuffs(player);
    }

    private static void updateGuildPassiveBuffs() {
        updateGuildPassiveBuffs(0L);
    }

    private static void updateGuildPassiveBuffs(long tickStartNs) {
        if (server == null) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players == null || players.isEmpty()) return;
        int max = isUltraGolemServerMode() ? 24 : (isExtremeGolemServerMode() ? 48 : (isMassiveGolemServerMode() ? 96 : players.size()));
        int start = Math.floorMod(guildBuffPlayerCursor, players.size());
        int processed = 0;
        for (int n = 0; n < players.size() && processed < max; n++) {
            // Always repair at least one player. Buffs are cheap and must not disappear just because
            // the golem/territory systems are throttled by the governor.
            if (processed > 0 && serverTickBudgetExceeded(tickStartNs)) break;
            ServerPlayer player = players.get(Math.floorMod(start + n, players.size()));
            updatePlayerGuildBuffs(player);
            processed++;
        }
        guildBuffPlayerCursor = Math.floorMod(start + Math.max(1, processed), players.size());
    }

    private static void updatePlayerGuildBuffs(ServerPlayer player) {
        if (player == null) return;
        CompoundTag data = player.getPersistentData();
        String guildId = GuildStore.playerGuildId(player);
        GuildStore.Territory territory = GuildStore.territoryAt(player.level(), player.blockPosition());
        updateGuildTerritoryBoundaryMessage(player, territory, data);
        if (guildId == null || guildId.isBlank()) {
            if (!data.getString(TAG_GUILD_BUFF_SIGNATURE).orElse("").isBlank() || hasAnyGuildBuffMarker(player)) {
                clearAllGuildBuffs(player, data);
            }
            if (data != null) data.remove(TAG_GUILD_XP_BONUS_REMAINDER);
            return;
        }

        long now = player.level().getGameTime();
        boolean ownTerritory = isOwnGuildTerritory(player, guildId, territory);
        // Member buffs are membership-bound only. Territory is used only for boundary messages
        // and the separate timed GUILD_TERRITORY_HEALTH bonus.

        boolean armorEquipped = hasArmorEquipped(player);
        GuildBuffState state = guildBuffState(guildId, armorEquipped);
        String signature = state.signature(armorEquipped);
        String previous = data.getString(TAG_GUILD_BUFF_SIGNATURE).orElse("");
        long lastApplied = data.getLong(TAG_GUILD_BUFF_LAST_APPLY_TICK).orElse(0L);
        boolean periodicRepair = lastApplied <= 0L || now < lastApplied || now - lastApplied >= 20L * 300L;

        applyPassiveGuildMarker(player);
        refreshOrExpireTerritoryHealthBuff(player, data, now, ownTerritory);

        if (signature.equals(previous) && !periodicRepair && guildBuffMarkersLookApplied(player, state)) {
            return;
        }

        applyPercentAttributeModifier(player, Attributes.MAX_HEALTH, GUILD_LEVEL_HEALTH_MODIFIER_ID, state.healthBonusPercent);
        applyPercentAttributeModifier(player, Attributes.ARMOR, GUILD_LEVEL_ARMOR_MODIFIER_ID, armorEquipped ? state.armorBonusPercent : 0);
        applyWeaponDamageAttributeModifier(player, state.damageBonusPercent);
        applyPercentAttributeModifier(player, Attributes.MOVEMENT_SPEED, GUILD_MEMBER_SPEED_MODIFIER_ID, state.speedBonusPercent);
        applyJumpAttributeModifier(player, state.jumpBonus);

        if (state.healthBonusPercent > 0) applyInfiniteGuildMarker(player, HomeCraftGuildEffects.GUILD_HEALTH, 0);
        else try { player.removeEffect(HomeCraftGuildEffects.GUILD_HEALTH); } catch (Exception ignored) {}

        if (armorEquipped && state.armorBonusPercent > 0) applyInfiniteGuildMarker(player, HomeCraftGuildEffects.GUILD_ARMOR, amplifierForArmorBonus(state.armorBonusPercent));
        else try { player.removeEffect(HomeCraftGuildEffects.GUILD_ARMOR); } catch (Exception ignored) {}

        if (state.damageBonusPercent > 0) applyInfiniteGuildMarker(player, HomeCraftGuildEffects.GUILD_DAMAGE, amplifierForDamageBonus(state.damageBonusPercent));
        else try { player.removeEffect(HomeCraftGuildEffects.GUILD_DAMAGE); } catch (Exception ignored) {}

        syncSpecialGuildBuff(player, data, state.nightVision, MobEffects.NIGHT_VISION, HomeCraftGuildEffects.GUILD_NIGHT_VISION, TAG_GUILD_NIGHT_VISION_APPLIED);
        syncSpecialGuildBuff(player, data, state.waterBreathing, MobEffects.WATER_BREATHING, HomeCraftGuildEffects.GUILD_WATER_BREATHING, TAG_GUILD_WATER_BREATHING_APPLIED);
        syncSpecialGuildBuff(player, data, state.fireResistance, MobEffects.FIRE_RESISTANCE, HomeCraftGuildEffects.GUILD_FIRE_RESISTANCE, TAG_GUILD_FIRE_RESISTANCE_APPLIED);

        data.putString(TAG_GUILD_BUFF_SIGNATURE, signature);
        data.putLong(TAG_GUILD_BUFF_LAST_APPLY_TICK, now);
    }

    private static boolean isActiveGuildMember(ServerPlayer player) {
        if (player == null) return false;
        String guildId = GuildStore.playerGuildId(player);
        return guildId != null && !guildId.isBlank();
    }

    private static boolean isPlayerOnOwnGuildTerritory(ServerPlayer player, String guildId) {
        if (player == null || guildId == null || guildId.isBlank()) return false;
        GuildStore.Territory territory = GuildStore.territoryAt(player.level(), player.blockPosition());
        return isOwnGuildTerritory(player, guildId, territory);
    }

    private static boolean isOwnGuildTerritory(ServerPlayer player, String guildId, GuildStore.Territory territory) {
        if (player == null || guildId == null || guildId.isBlank() || territory == null) return false;
        if (!"GUILD".equals(territory.type) || territory.guildId == null || territory.guildId.isBlank()) return false;
        return Objects.equals(guildId, territory.guildId);
    }

    private static void applyPassiveGuildMarker(ServerPlayer player) {
        // Legacy passive marker is removed so the player does not see duplicate generic icons.
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_PASSIVE); } catch (Exception ignored) {}
        applyInfiniteGuildMarker(player, HomeCraftGuildEffects.GUILD_PLAYER_XP, 0);
        applyInfiniteGuildMarker(player, HomeCraftGuildEffects.GUILD_POTION_DURATION, 0);
        // Marker/icon only; the real guild XP bonus is applied inside GuildStore.addMonsterKillGuildExperience.
        applyInfiniteGuildMarker(player, HomeCraftGuildEffects.GUILD_MOB_XP, 0);
    }

    private static void syncSpecialGuildBuff(ServerPlayer player, CompoundTag data, boolean enabled, Holder<MobEffect> vanillaEffect, Holder<MobEffect> markerEffect, String appliedTag) {
        if (player == null || data == null || vanillaEffect == null || markerEffect == null || appliedTag == null) return;
        boolean appliedByGuild = data.getBoolean(appliedTag).orElse(false);
        if (enabled) {
            MobEffectInstance current = player.getEffect(vanillaEffect);
            // Do not overwrite a finite vanilla/mod potion effect and then delete it on territory leave.
            // If the player has no such effect, the guild grants its own infinite version and tags it.
            if (current == null || appliedByGuild) {
                if (current == null || !current.isInfiniteDuration()) {
                    player.addEffect(new MobEffectInstance(vanillaEffect, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
                }
                data.putBoolean(appliedTag, true);
            }
            applyInfiniteGuildMarker(player, markerEffect, 0);
            return;
        }
        if (appliedByGuild) removeGuildOwnedVanillaEffect(player, data, vanillaEffect, appliedTag);
        try { player.removeEffect(markerEffect); } catch (Exception ignored) {}
    }

    private static void removeGuildOwnedVanillaEffect(ServerPlayer player, CompoundTag data, Holder<MobEffect> effect, String appliedTag) {
        if (player == null || data == null || effect == null || appliedTag == null) return;
        MobEffectInstance current = player.getEffect(effect);
        // Only remove the infinite effect that this system created. If a normal potion replaced it,
        // keep that potion instead of deleting the player's external buff on territory leave/reset.
        if (current != null && current.isInfiniteDuration()) {
            try { player.removeEffect(effect); } catch (Exception ignored) {}
        }
        data.remove(appliedTag);
    }

    private static void applyInfiniteGuildMarker(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
        if (player == null || effect == null) return;
        MobEffectInstance current = player.getEffect(effect);
        int safeAmplifier = Math.max(0, amplifier);
        // Permanent guild marker buffs must not show countdown timers.
        // Only GUILD_TERRITORY_HEALTH remains timed because it is the 30-minute territory bonus.
        if (current == null || current.getAmplifier() != safeAmplifier || !current.isInfiniteDuration()) {
            player.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, safeAmplifier, true, false, true));
        }
    }

    private static void refreshOrExpireTerritoryHealthBuff(ServerPlayer player, CompoundTag data, long now, boolean ownTerritory) {
        long until = data == null ? 0L : data.getLong(TAG_GUILD_TERRITORY_HEALTH_UNTIL_TICK).orElse(0L);
        MobEffectInstance marker = player.getEffect(HomeCraftGuildEffects.GUILD_TERRITORY_HEALTH);

        if (ownTerritory && (until <= now || until - now <= GUILD_TERRITORY_HEALTH_REFRESH_MARGIN_TICKS || marker == null)) {
            until = now + GUILD_TERRITORY_HEALTH_DURATION_TICKS;
            if (data != null) data.putLong(TAG_GUILD_TERRITORY_HEALTH_UNTIL_TICK, until);
        }

        if (until > now) {
            int remaining = (int)Math.min(Integer.MAX_VALUE - 1L, Math.max(1L, until - now));
            applyPercentAttributeModifier(player, Attributes.MAX_HEALTH, GUILD_TERRITORY_HEALTH_MODIFIER_ID, GUILD_TERRITORY_HEALTH_BONUS_PERCENT);
            if (marker == null || marker.getDuration() < Math.min(remaining, (int)GUILD_TERRITORY_HEALTH_REFRESH_MARGIN_TICKS)) {
                player.addEffect(new MobEffectInstance(HomeCraftGuildEffects.GUILD_TERRITORY_HEALTH, remaining, 0, false, false, true));
            }
            return;
        }

        clearTerritoryHealthBuff(player, data);
    }

    private static void clearTerritoryHealthBuff(ServerPlayer player, CompoundTag data) {
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_TERRITORY_HEALTH); } catch (Exception ignored) {}
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.removeModifier(GUILD_TERRITORY_HEALTH_MODIFIER_ID);
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }
        if (data != null) data.remove(TAG_GUILD_TERRITORY_HEALTH_UNTIL_TICK);
    }

    private static void removeLegacyGuildLevelModifiers(ServerPlayer player) {
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) maxHealth.removeModifier(GUILD_LEVEL_HEALTH_MODIFIER_ID);
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor != null) armor.removeModifier(GUILD_LEVEL_ARMOR_MODIFIER_ID);
        AttributeInstance attackDamage = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage != null) attackDamage.removeModifier(GUILD_LEVEL_DAMAGE_MODIFIER_ID);
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(GUILD_MEMBER_SPEED_MODIFIER_ID);
        try {
            AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
            if (jump != null) jump.removeModifier(GUILD_MEMBER_JUMP_MODIFIER_ID);
        } catch (Throwable ignored) {}
    }

    private static boolean isExtendablePotionEffect(MobEffectInstance instance) {
        if (instance == null || instance.isInfiniteDuration()) return false;
        if (instance.getDuration() < 20) return false;
        if (isGuildMarkerEffect(instance)) return false;
        MobEffect effect = instance.getEffect().value();
        if (effect == null) return false;
        return effect.getCategory() == MobEffectCategory.BENEFICIAL;
    }

    private static String potionExtensionGuardKey(ServerPlayer player, MobEffectInstance instance) {
        return player.getUUID() + "|" + String.valueOf(instance.getEffect()) + "|" + instance.getAmplifier();
    }

    private static int safeBoostedDuration(int duration, int bonusPercent) {
        if (duration <= 0 || bonusPercent <= 0) return duration;
        long boosted = duration + Math.max(1L, (duration * (long)bonusPercent) / 100L);
        return (int)Math.min(Integer.MAX_VALUE - 1L, Math.max(duration, boosted));
    }

    private static GuildBuffState guildBuffState(String guildId, boolean armorEquipped) {
        long revision = GuildStore.guildRuntimeRevision();
        String key = String.valueOf(guildId == null ? "" : guildId) + "|" + revision;
        GuildBuffState cached = GUILD_BUFF_STATE_CACHE.get(key);
        if (cached != null) return cached;
        GuildStore.GuildBuffSnapshot snapshot = GuildStore.guildBuffSnapshot(guildId);
        GuildBuffState state = new GuildBuffState(
                snapshot.guildId, snapshot.revision, snapshot.level,
                snapshot.healthBonusPercent, snapshot.armorBonusPercent,
                snapshot.damageBonusPercent, snapshot.speedBonusPercent,
                snapshot.jumpBonus, snapshot.damageReductionPercent, snapshot.nightVision,
                snapshot.waterBreathing, snapshot.fireResistance
        );
        GUILD_BUFF_STATE_CACHE.put(key, state);
        if (GUILD_BUFF_STATE_CACHE.size() > 4096) trimRuntimeMap(GUILD_BUFF_STATE_CACHE, 2048);
        return state;
    }

    private static boolean hasAnyGuildBuffMarker(ServerPlayer player) {
        if (player == null) return false;
        return player.getEffect(HomeCraftGuildEffects.GUILD_PASSIVE) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_PLAYER_XP) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_POTION_DURATION) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_MOB_XP) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_TERRITORY_HEALTH) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_HEALTH) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_ARMOR) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_DAMAGE) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_NIGHT_VISION) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_WATER_BREATHING) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_FIRE_RESISTANCE) != null;
    }

    private static boolean hasAnyTerritoryBoundGuildBuffMarker(ServerPlayer player) {
        if (player == null) return false;
        return player.getEffect(HomeCraftGuildEffects.GUILD_PASSIVE) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_PLAYER_XP) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_POTION_DURATION) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_MOB_XP) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_HEALTH) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_ARMOR) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_DAMAGE) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_NIGHT_VISION) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_WATER_BREATHING) != null
                || player.getEffect(HomeCraftGuildEffects.GUILD_FIRE_RESISTANCE) != null;
    }

    private static boolean guildBuffMarkersLookApplied(ServerPlayer player, GuildBuffState state) {
        if (player == null || state == null) return false;
        if (player.getEffect(HomeCraftGuildEffects.GUILD_PLAYER_XP) == null) return false;
        if (player.getEffect(HomeCraftGuildEffects.GUILD_POTION_DURATION) == null) return false;
        if (player.getEffect(HomeCraftGuildEffects.GUILD_MOB_XP) == null) return false;
        if (state.healthBonusPercent > 0) {
            if (player.getEffect(HomeCraftGuildEffects.GUILD_HEALTH) == null) return false;
            if (!hasAttributeModifier(player, Attributes.MAX_HEALTH, GUILD_LEVEL_HEALTH_MODIFIER_ID)) return false;
        }
        if (state.armorBonusPercent > 0 && hasArmorEquipped(player)) {
            if (player.getEffect(HomeCraftGuildEffects.GUILD_ARMOR) == null) return false;
            if (!hasAttributeModifier(player, Attributes.ARMOR, GUILD_LEVEL_ARMOR_MODIFIER_ID)) return false;
        }
        if (state.damageBonusPercent > 0 && player.getEffect(HomeCraftGuildEffects.GUILD_DAMAGE) == null) return false;
        boolean weaponDamageActive = state.damageBonusPercent > 0 && isWeaponLike(player.getMainHandItem());
        if (weaponDamageActive && !hasAttributeModifier(player, Attributes.ATTACK_DAMAGE, GUILD_LEVEL_DAMAGE_MODIFIER_ID)) return false;
        if (!weaponDamageActive && hasAttributeModifier(player, Attributes.ATTACK_DAMAGE, GUILD_LEVEL_DAMAGE_MODIFIER_ID)) return false;
        if (state.speedBonusPercent > 0 && !hasAttributeModifier(player, Attributes.MOVEMENT_SPEED, GUILD_MEMBER_SPEED_MODIFIER_ID)) return false;
        if (state.jumpBonus > 0.0D && !hasJumpAttributeModifier(player)) return false;
        if (state.nightVision && player.getEffect(HomeCraftGuildEffects.GUILD_NIGHT_VISION) == null) return false;
        if (state.waterBreathing && player.getEffect(HomeCraftGuildEffects.GUILD_WATER_BREATHING) == null) return false;
        if (state.fireResistance && player.getEffect(HomeCraftGuildEffects.GUILD_FIRE_RESISTANCE) == null) return false;
        return true;
    }

    private static boolean hasAttributeModifier(ServerPlayer player, Holder<Attribute> attribute, Identifier id) {
        try {
            AttributeInstance instance = player == null ? null : player.getAttribute(attribute);
            if (instance == null || id == null) return false;
            Object value = instance.getClass().getMethod("getModifier", Identifier.class).invoke(instance, id);
            return value != null;
        } catch (Throwable ignored) {
            // If mappings change and getModifier is unavailable, force a safe reapply pass.
            return false;
        }
    }

    private static void applyPercentAttributeModifier(ServerPlayer player, Holder<Attribute> attribute, Identifier id, int percent) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        instance.removeModifier(id);
        if (percent > 0) instance.addOrUpdateTransientModifier(new AttributeModifier(id, percent / 100.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        if (attribute == Attributes.MAX_HEALTH && player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
    }

    private static void applyWeaponDamageAttributeModifier(ServerPlayer player, int percent) {
        AttributeInstance instance = player == null ? null : player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (instance == null) return;
        instance.removeModifier(GUILD_LEVEL_DAMAGE_MODIFIER_ID);
        if (percent > 0 && isWeaponLike(player.getMainHandItem())) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(GUILD_LEVEL_DAMAGE_MODIFIER_ID, percent / 100.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void applyJumpAttributeModifier(ServerPlayer player, double jumpBonus) {
        try {
            AttributeInstance instance = player.getAttribute(Attributes.JUMP_STRENGTH);
            if (instance == null) return;
            instance.removeModifier(GUILD_MEMBER_JUMP_MODIFIER_ID);
            if (jumpBonus > 0.0D) instance.addOrUpdateTransientModifier(new AttributeModifier(GUILD_MEMBER_JUMP_MODIFIER_ID, jumpBonus, AttributeModifier.Operation.ADD_VALUE));
        } catch (Throwable ignored) {}
    }

    private static boolean hasJumpAttributeModifier(ServerPlayer player) {
        try {
            return hasAttributeModifier(player, Attributes.JUMP_STRENGTH, GUILD_MEMBER_JUMP_MODIFIER_ID);
        } catch (Throwable ignored) { return false; }
    }

    private static int amplifierForDamageBonus(int bonus) {
        if (bonus >= 15) return 2;
        if (bonus >= 10) return 1;
        return 0;
    }

    private static int amplifierForArmorBonus(int bonus) {
        if (bonus >= 15) return 2;
        if (bonus >= 10) return 1;
        return 0;
    }

    private static boolean hasArmorEquipped(ServerPlayer player) {
        if (player == null) return false;
        return isEquippedArmorPiece(player.getItemBySlot(EquipmentSlot.HEAD), EquipmentSlot.HEAD)
                || isEquippedArmorPiece(player.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST)
                || isEquippedArmorPiece(player.getItemBySlot(EquipmentSlot.LEGS), EquipmentSlot.LEGS)
                || isEquippedArmorPiece(player.getItemBySlot(EquipmentSlot.FEET), EquipmentSlot.FEET);
    }

    private static boolean isEquippedArmorPiece(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty() || slot == null) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        if (path.isBlank()) return false;
        return switch (slot) {
            case HEAD -> path.endsWith("helmet");
            case CHEST -> path.endsWith("chestplate");
            case LEGS -> path.endsWith("leggings");
            case FEET -> path.endsWith("boots");
            default -> false;
        };
    }

    private static void clearTerritoryBoundMemberGuildBuffs(ServerPlayer player, CompoundTag data) {
        if (player == null) return;
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_PASSIVE); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_PLAYER_XP); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_POTION_DURATION); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_MOB_XP); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_HEALTH); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_ARMOR); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_DAMAGE); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_NIGHT_VISION); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_WATER_BREATHING); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_FIRE_RESISTANCE); } catch (Exception ignored) {}
        if (data != null && data.getBoolean(TAG_GUILD_WATER_BREATHING_APPLIED).orElse(false)) {
            removeGuildOwnedVanillaEffect(player, data, MobEffects.WATER_BREATHING, TAG_GUILD_WATER_BREATHING_APPLIED);
        }
        if (data != null && data.getBoolean(TAG_GUILD_FIRE_RESISTANCE_APPLIED).orElse(false)) {
            removeGuildOwnedVanillaEffect(player, data, MobEffects.FIRE_RESISTANCE, TAG_GUILD_FIRE_RESISTANCE_APPLIED);
        }
        if (data != null && data.getBoolean(TAG_GUILD_NIGHT_VISION_APPLIED).orElse(false)) {
            removeGuildOwnedVanillaEffect(player, data, MobEffects.NIGHT_VISION, TAG_GUILD_NIGHT_VISION_APPLIED);
        }
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.removeModifier(GUILD_LEVEL_HEALTH_MODIFIER_ID);
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor != null) armor.removeModifier(GUILD_LEVEL_ARMOR_MODIFIER_ID);
        AttributeInstance attackDamage = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage != null) attackDamage.removeModifier(GUILD_LEVEL_DAMAGE_MODIFIER_ID);
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(GUILD_MEMBER_SPEED_MODIFIER_ID);
        try {
            AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
            if (jump != null) jump.removeModifier(GUILD_MEMBER_JUMP_MODIFIER_ID);
        } catch (Throwable ignored) {}
        if (data != null) {
            data.remove(TAG_GUILD_BUFF_SIGNATURE);
            data.remove(TAG_GUILD_BUFF_LAST_APPLY_TICK);
            data.remove(TAG_GUILD_XP_BONUS_REMAINDER);
        }
    }

    private static void clearAllGuildBuffs(ServerPlayer player, CompoundTag data) {
        if (player == null) return;
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_PASSIVE); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_PLAYER_XP); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_POTION_DURATION); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_MOB_XP); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_TERRITORY_HEALTH); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_HEALTH); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_ARMOR); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_DAMAGE); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_NIGHT_VISION); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_WATER_BREATHING); } catch (Exception ignored) {}
        try { player.removeEffect(HomeCraftGuildEffects.GUILD_FIRE_RESISTANCE); } catch (Exception ignored) {}
        if (data != null && data.getBoolean(TAG_GUILD_WATER_BREATHING_APPLIED).orElse(false)) {
            removeGuildOwnedVanillaEffect(player, data, MobEffects.WATER_BREATHING, TAG_GUILD_WATER_BREATHING_APPLIED);
        }
        if (data != null && data.getBoolean(TAG_GUILD_FIRE_RESISTANCE_APPLIED).orElse(false)) {
            removeGuildOwnedVanillaEffect(player, data, MobEffects.FIRE_RESISTANCE, TAG_GUILD_FIRE_RESISTANCE_APPLIED);
        }
        if (data != null && data.getBoolean(TAG_GUILD_NIGHT_VISION_APPLIED).orElse(false)) {
            removeGuildOwnedVanillaEffect(player, data, MobEffects.NIGHT_VISION, TAG_GUILD_NIGHT_VISION_APPLIED);
        }
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.removeModifier(GUILD_LEVEL_HEALTH_MODIFIER_ID);
            maxHealth.removeModifier(GUILD_TERRITORY_HEALTH_MODIFIER_ID);
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor != null) armor.removeModifier(GUILD_LEVEL_ARMOR_MODIFIER_ID);
        AttributeInstance attackDamage = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage != null) attackDamage.removeModifier(GUILD_LEVEL_DAMAGE_MODIFIER_ID);
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(GUILD_MEMBER_SPEED_MODIFIER_ID);
        try {
            AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
            if (jump != null) jump.removeModifier(GUILD_MEMBER_JUMP_MODIFIER_ID);
        } catch (Throwable ignored) {}
        if (data != null) {
            data.remove(TAG_GUILD_BUFF_SIGNATURE);
            data.remove(TAG_GUILD_BUFF_LAST_APPLY_TICK);
            data.remove(TAG_GUILD_XP_BONUS_REMAINDER);
            data.remove(TAG_GUILD_TERRITORY_HEALTH_UNTIL_TICK);
        }
    }

    private static void updateGuildTerritoryBoundaryMessage(ServerPlayer player, GuildStore.Territory territory, CompoundTag data) {
        boolean inGuildTerritory = territory != null && "GUILD".equals(territory.type) && territory.guildId != null;
        String currentId = inGuildTerritory ? String.valueOf(territory.guildId) : "";
        String previousId = data.getString(TAG_LAST_GUILD_TERRITORY).orElse("");
        if (Objects.equals(currentId, previousId)) return;

        String previousName = data.getString(TAG_LAST_GUILD_TERRITORY_NAME).orElse("гільдії");
        boolean previousOwn = data.getBoolean(TAG_LAST_GUILD_TERRITORY_OWN).orElse(false);
        if (!previousId.isBlank()) {
            player.displayClientMessage(Component.literal(previousOwn
                    ? "Home Craft Guilds: ти покинув територію своєї гільдії " + previousName + "."
                    : "Home Craft Guilds: ти покинув територію гільдії " + previousName + "."), true);
        }

        if (inGuildTerritory) {
            boolean own = GuildStore.isMemberOfGuild(player, territory.guildId);
            String guildName = territory.guildName == null || territory.guildName.isBlank() ? "гільдії" : territory.guildName;
            player.displayClientMessage(Component.literal(own
                    ? "Home Craft Guilds: ти увійшов на територію своєї гільдії " + guildName + "."
                    : "Home Craft Guilds: ти увійшов на територію гільдії " + guildName + ". Це чужа зона."), true);
            data.putString(TAG_LAST_GUILD_TERRITORY, currentId);
            data.putString(TAG_LAST_GUILD_TERRITORY_NAME, guildName);
            data.putBoolean(TAG_LAST_GUILD_TERRITORY_OWN, own);
        } else {
            data.remove(TAG_LAST_GUILD_TERRITORY);
            data.remove(TAG_LAST_GUILD_TERRITORY_NAME);
            data.remove(TAG_LAST_GUILD_TERRITORY_OWN);
        }
    }

    private static MinecraftServer resolveServer(ServerPlayer player) {
        if (player != null) {
            try {
                if (player.level() instanceof ServerLevel level) {
                    MinecraftServer fromLevel = level.getServer();
                    if (fromLevel != null) return fromLevel;
                }
            } catch (Exception ignored) {}
        }
        return server;
    }

    private static String safeNpcName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isBlank() || name.indexOf('�') >= 0 || name.contains("����")) return "Гільдійний Реєстратор";
        return name.replace("\\", "").replace("\"", "'");
    }

    private static boolean isGuildTotemBlock(BlockState state) {
        try {
            return state != null && state.is(HomeCraftGuildBlocks.GUILD_BANNER.get());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isPhysicalGuildTotemAnchor(BlockState state) {
        return isGuildTotemBlock(state) || isStandingBanner(state);
    }

    private static boolean isStandingBanner(BlockState state) {
        if (isGuildTotemBlock(state)) return false;
        String id = blockId(state);
        return id.startsWith("minecraft:") && id.endsWith("_banner") && !id.endsWith("_wall_banner");
    }

    private static boolean isChestLike(BlockState state) {
        String id = blockId(state);
        return id.endsWith("chest") || id.endsWith("barrel") || id.endsWith("shulker_box");
    }

    private static boolean isBed(BlockState state) {
        String id = blockId(state);
        if (!id.endsWith("_bed")) return false;
        try {
            return state.hasProperty(BlockStateProperties.BED_PART);
        } catch (Exception ignored) {}
        return true;
    }

    private static String blockId(BlockState state) {
        try { return String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())).toLowerCase(Locale.ROOT); }
        catch (Exception ignored) { return String.valueOf(state.getBlock()).toLowerCase(Locale.ROOT); }
    }

    private static void logDenied(String action, ServerPlayer player, GuildStore.Territory territory, BlockPos pos) {
        if (!HomeCraftGuildConfig.debug()) return;
        HomeCraftGuildMod.LOGGER.info("guild-territory-denied: action={} player={} pos={} territory={} type={} owner={} guild={}", action, player.getName().getString(), pos, territory.id, territory.type, territory.ownerName, territory.guildName);
    }

    private static final class PrecomputedTerritoryPlan {
        final String territoryId;
        final String guildId;
        final String dimension;
        final long revision;
        final List<PrecomputedPoint> dayPoints;
        final List<PrecomputedPoint> nightPoints;

        PrecomputedTerritoryPlan(String territoryId, String guildId, String dimension, long revision, List<PrecomputedPoint> dayPoints, List<PrecomputedPoint> nightPoints) {
            this.territoryId = territoryId;
            this.guildId = guildId;
            this.dimension = dimension;
            this.revision = revision;
            this.dayPoints = dayPoints == null ? List.of() : List.copyOf(dayPoints);
            this.nightPoints = nightPoints == null ? List.of() : List.copyOf(nightPoints);
        }
    }

    private static final class PrecomputedPoint {
        final int x;
        final int z;
        final int y;
        final int score;

        PrecomputedPoint(int x, int z, int y, int score) {
            this.x = x;
            this.z = z;
            this.y = y;
            this.score = score;
        }
    }

}
