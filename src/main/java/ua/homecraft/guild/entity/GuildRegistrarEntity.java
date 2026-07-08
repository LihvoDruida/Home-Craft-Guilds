package ua.homecraft.guild.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import ua.homecraft.guild.server.HomeCraftGuildConfig;

/**
 * HomeCraft NPC entity, rewritten after the Easy-NPC approach:
 * - real tracked server entity, not a client-only fake point;
 * - all mutable visual metadata is entity-synced to the client;
 * - the renderer only uses built-in addon skin ids, never arbitrary external skins.
 */
public class GuildRegistrarEntity extends PathfinderMob implements GuildNpcSkinnable, Npc {
    public static final String DEFAULT_KEY = "guild_master";
    public static final String DEFAULT_KIND = "system";
    public static final String DEFAULT_SKIN = "guild_master";

    private static final EntityDataAccessor<String> DATA_NPC_KEY = SynchedEntityData.defineId(GuildRegistrarEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_NPC_KIND = SynchedEntityData.defineId(GuildRegistrarEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SKIN_ID = SynchedEntityData.defineId(GuildRegistrarEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_SYSTEM_NPC = SynchedEntityData.defineId(GuildRegistrarEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_SLIM_ARMS = SynchedEntityData.defineId(GuildRegistrarEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_DISPLAY_NAME = SynchedEntityData.defineId(GuildRegistrarEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> DATA_NPC_REVISION = SynchedEntityData.defineId(GuildRegistrarEntity.class, EntityDataSerializers.LONG);

    private boolean idleBaseYawCaptured = false;
    private float idleBaseYaw = 0.0F;
    private int homecraftLastVillagerReactionSoundTick = -200;

    public GuildRegistrarEntity(EntityType<? extends GuildRegistrarEntity> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.setInvulnerable(true);
        this.setPersistenceRequired();
        this.addTag("homecraft_guild_npc");
        this.addTag("homecraft_guild_registrar");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_NPC_KEY, DEFAULT_KEY);
        builder.define(DATA_NPC_KIND, DEFAULT_KIND);
        builder.define(DATA_SKIN_ID, DEFAULT_SKIN);
        builder.define(DATA_SYSTEM_NPC, true);
        builder.define(DATA_SLIM_ARMS, false);
        builder.define(DATA_DISPLAY_NAME, "lang:npc.homecraftguild.guild_registrar");
        builder.define(DATA_NPC_REVISION, 0L);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 16.0D)
                .add(Attributes.SCALE, 0.965D);
    }

    @Override
    protected void registerGoals() {
        // Static HomeCraft NPC: no wandering, no combat AI, no panic goals.
    }

    @Override
    public void tick() {
        super.tick();
        runClientIdleAnimation();
        runServerVillagerLikeSoundReaction();
    }

    private void runServerVillagerLikeSoundReaction() {
        if (this.level().isClientSide()) return;
        if (!HomeCraftGuildConfig.npcVillagerSoundsEnabled()) return;
        int cooldown = Math.max(20, HomeCraftGuildConfig.npcAmbientSoundCooldownTicks());
        if (this.tickCount - homecraftLastVillagerReactionSoundTick < cooldown) return;
        Player nearest = this.level().getNearestPlayer(this, 4.5D);
        if (nearest == null) return;
        if (this.getRandom().nextInt(42) != 0) return;
        homecraftLastVillagerReactionSoundTick = this.tickCount;
        this.level().playSound(null, this.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.65F, 0.92F + this.getRandom().nextFloat() * 0.16F);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.75F;
    }

    private void runClientIdleAnimation() {
        if (!this.level().isClientSide()) return;
        if (!idleBaseYawCaptured) {
            idleBaseYaw = this.getYRot();
            idleBaseYawCaptured = true;
        }

        float time = (float) this.tickCount + (float) (this.getId() * 7);
        float idleBody = idleBaseYaw + (float) Math.sin(time * 0.018F) * 0.55F;
        float idleHead = idleBody + (float) Math.sin(time * 0.034F) * 1.25F;
        float bodyYaw = Mth.rotLerp(0.12F, this.getYRot(), idleBody);
        float headYaw = Mth.rotLerp(0.16F, this.getYHeadRot(), idleHead);
        float headPitch = Mth.lerp(0.14F, this.getXRot(), (float) Math.sin(time * 0.027F) * 0.55F);

        Player nearest = this.level().getNearestPlayer(this, 8.0D);
        if (nearest != null) {
            double dx = nearest.getX() - this.getX();
            double dz = nearest.getZ() - this.getZ();
            double dy = nearest.getEyeY() - (this.getY() + this.getEyeHeight());
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float lookYaw = (float)(Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            float relative = Mth.wrapDegrees(lookYaw - idleBaseYaw);
            float clampedHeadRel = Mth.clamp(relative, -72.0F, 72.0F);
            float clampedBodyRel = Mth.clamp(relative, -26.0F, 26.0F);
            float targetBodyYaw = idleBaseYaw + clampedBodyRel * 0.42F + (float) Math.sin(time * 0.018F) * 0.25F;
            float targetHeadYaw = idleBaseYaw + clampedHeadRel;
            float targetHeadPitch = (float)(-(Mth.atan2(dy, Math.max(0.001D, horizontal)) * (180.0D / Math.PI)));
            bodyYaw = Mth.rotLerp(0.20F, this.getYRot(), targetBodyYaw);
            headYaw = Mth.rotLerp(0.28F, this.getYHeadRot(), targetHeadYaw);
            headYaw = bodyYaw + Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -74.0F, 74.0F);
            headPitch = Mth.lerp(0.24F, this.getXRot(), Mth.clamp(targetHeadPitch, -26.0F, 22.0F));
        }

        this.setYRot(bodyYaw);
        this.setYBodyRot(bodyYaw);
        this.setYHeadRot(headYaw);
        this.setXRot(headPitch);
        this.yRotO = bodyYaw;
        this.yBodyRotO = bodyYaw;
        this.yHeadRotO = headYaw;
        this.xRotO = headPitch;

        // Static NPC: no fake walk cycle, legs stay still.
        try { this.walkAnimation.update(0.0F, 0.0F, 1.0F); } catch (Throwable ignored) {}
    }


    @Override
    public void addAdditionalSaveData(ValueOutput tag) {
        super.addAdditionalSaveData(tag);
        if (tag == null) return;
        tag.putString("homecraft:NpcKey", homecraft$npcKey());
        tag.putString("homecraft:NpcKind", homecraft$npcKind());
        tag.putString("homecraft:NpcSkinId", homecraft$npcSkinId());
        tag.putString("homecraft:DisplayName", homecraft$displayName());
        tag.putBoolean("homecraft:SystemNpc", homecraft$isSystemNpc());
        tag.putBoolean("homecraft:SlimArms", homecraft$npcSlimArms());
        tag.putLong("homecraft:NpcRevision", homecraft$npcRevision());
    }

    @Override
    public void readAdditionalSaveData(ValueInput tag) {
        super.readAdditionalSaveData(tag);
        if (tag == null) return;
        String key = tag.getString("homecraft:NpcKey").orElse(DEFAULT_KEY);
        String kind = tag.getString("homecraft:NpcKind").orElse(DEFAULT_KIND);
        String skin = tag.getString("homecraft:NpcSkinId").orElse(DEFAULT_SKIN);
        String name = tag.getString("homecraft:DisplayName").orElse("lang:npc.homecraftguild.guild_registrar");
        boolean system = readBooleanValue(tag, "homecraft:SystemNpc", DEFAULT_KEY.equals(normalize(key, DEFAULT_KEY)));
        boolean slim = readBooleanValue(tag, "homecraft:SlimArms", false);
        long revision = tag.getLong("homecraft:NpcRevision").orElse(0L);

        // Very old v13x entities did not save SynchedData, but entity tags are persisted.
        // Recover npcKey from the persistent tag before falling back to guild_master; otherwise
        // every old trader reloads as a default registrar and visually overlaps the real trader.
        if (DEFAULT_KEY.equals(normalize(key, DEFAULT_KEY))) {
            for (String entityTag : this.getTags()) {
                if (entityTag != null && entityTag.startsWith("homecraft_npc_key_")) {
                    key = entityTag.substring("homecraft_npc_key_".length());
                    break;
                }
            }
        }
        setupHomeCraftNpc(key, kind, skin, name, system, slim, revision);
    }

    private static boolean readBooleanValue(ValueInput tag, String key, boolean fallback) {
        if (tag == null || key == null || key.isBlank()) return fallback;
        // Minecraft 1.21.11 ValueInput does not expose getBoolean(String) in this mapping,
        // while older/adjacent mappings may expose optional or direct boolean accessors.
        // Use reflection only for load-time compatibility and keep fallback deterministic.
        try {
            for (java.lang.reflect.Method method : tag.getClass().getMethods()) {
                String name = method.getName();
                Class<?>[] params = method.getParameterTypes();
                if (("getBoolean".equals(name) || "readBoolean".equals(name) || "getBool".equals(name) || "readBool".equals(name))
                        && params.length == 1 && params[0] == String.class) {
                    Object value = method.invoke(tag, key);
                    if (value instanceof Boolean b) return b;
                    if (value instanceof java.util.Optional<?> opt) {
                        Object inner = opt.orElse(null);
                        if (inner instanceof Boolean b) return b;
                    }
                }
                if (("getBooleanOr".equals(name) || "readBooleanOr".equals(name) || "getBoolOr".equals(name) || "readBoolOr".equals(name))
                        && params.length == 2 && params[0] == String.class && (params[1] == boolean.class || params[1] == Boolean.class)) {
                    Object value = method.invoke(tag, key, fallback);
                    if (value instanceof Boolean b) return b;
                }
            }
        } catch (Throwable ignored) {
            // Fall back to deterministic defaults; server config refresh will re-apply exact flags.
        }
        return fallback;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    public String homecraft$npcKey() {
        return normalize(this.entityData.get(DATA_NPC_KEY), DEFAULT_KEY);
    }

    public String homecraft$npcKind() {
        return normalize(this.entityData.get(DATA_NPC_KIND), DEFAULT_KIND);
    }

    @Override
    public String homecraft$npcSkinId() {
        return normalize(this.entityData.get(DATA_SKIN_ID), DEFAULT_SKIN);
    }

    @Override
    public boolean homecraft$npcSlimArms() {
        return this.entityData.get(DATA_SLIM_ARMS);
    }

    public boolean homecraft$isSystemNpc() {
        return this.entityData.get(DATA_SYSTEM_NPC);
    }

    public long homecraft$npcRevision() {
        return this.entityData.get(DATA_NPC_REVISION);
    }

    public String homecraft$displayName() {
        return normalizeText(this.entityData.get(DATA_DISPLAY_NAME), "lang:npc.homecraftguild.guild_registrar");
    }

    public void setupRegistrar(String name) {
        setupHomeCraftNpc(DEFAULT_KEY, DEFAULT_KIND, DEFAULT_SKIN, name, true, false, HomeCraftGuildConfig.guildNpcRegistryRevision());
    }

    public void setupHomeCraftNpc(String key, String kind, String skinId, String name, boolean systemNpc, boolean slimArms) {
        setupHomeCraftNpc(key, kind, skinId, name, systemNpc, slimArms, 0L);
    }

    public void setupHomeCraftNpc(String key, String kind, String skinId, String name, boolean systemNpc, boolean slimArms, long npcRevision) {
        String safeKey = normalize(key, DEFAULT_KEY);
        String safeKind = normalize(kind, DEFAULT_KIND);
        String safeSkin = normalize(skinId, DEFAULT_SKIN);
        this.setNoAi(true);
        this.setInvulnerable(true);
        this.setPersistenceRequired();
        this.addTag("homecraft_guild_npc");
        removeOldNpcKeyTags();
        this.addTag("homecraft_npc_key_" + safeKey);
        if (DEFAULT_KEY.equals(safeKey)) this.addTag("homecraft_guild_registrar");
        else this.removeTag("homecraft_guild_registrar");
        this.entityData.set(DATA_NPC_KEY, safeKey);
        this.entityData.set(DATA_NPC_KIND, safeKind);
        this.entityData.set(DATA_SKIN_ID, safeSkin);
        this.entityData.set(DATA_SYSTEM_NPC, systemNpc);
        this.entityData.set(DATA_SLIM_ARMS, slimArms);
        String displayName = normalizeText(name, defaultDisplayNameKey(safeKey, systemNpc));
        this.entityData.set(DATA_DISPLAY_NAME, displayName);
        this.entityData.set(DATA_NPC_REVISION, Math.max(0L, npcRevision));
        this.setCustomName(displayNameComponent(displayName, safeKey, systemNpc));
        this.setCustomNameVisible(true);
    }


    private void removeOldNpcKeyTags() {
        try {
            java.util.List<String> remove = new java.util.ArrayList<>();
            for (String tag : this.getTags()) {
                if (tag != null && tag.startsWith("homecraft_npc_key_")) remove.add(tag);
            }
            for (String tag : remove) this.removeTag(tag);
        } catch (Throwable ignored) { }
    }

    private static String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().replace('|', ' ').replace(';', ' ').replace('~', ' ');
    }

    private static String defaultDisplayNameKey(String key, boolean systemNpc) {
        String safe = normalize(key, DEFAULT_KEY);
        return switch (safe) {
            case "guild_master" -> "lang:npc.homecraftguild.guild_master";
            case "guild_registrar" -> "lang:npc.homecraftguild.guild_registrar";
            case "trader_food" -> "lang:npc.homecraftguild.trader_food";
            case "trader_tools" -> "lang:npc.homecraftguild.trader_tools";
            case "trader_weapons" -> "lang:npc.homecraftguild.trader_weapons";
            case "trader_armor" -> "lang:npc.homecraftguild.trader_armor";
            case "trader_elite" -> "lang:npc.homecraftguild.trader_elite";
            default -> systemNpc ? "lang:npc.homecraftguild.guild_master" : "lang:npc.homecraftguild.trader";
        };
    }

    private static Component displayNameComponent(String rawName, String key, boolean systemNpc) {
        String name = normalizeText(rawName, defaultDisplayNameKey(key, systemNpc));
        String mapped = legacyDefaultNameKey(name, key, systemNpc);
        if (mapped.startsWith("lang:")) return Component.translatable(mapped.substring("lang:".length()));
        return Component.literal(mapped);
    }

    private static String legacyDefaultNameKey(String name, String key, boolean systemNpc) {
        String value = name == null ? "" : name.trim();
        if (value.startsWith("lang:")) return value;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("гільдійний майстер") || lower.equals("guild master")) return "lang:npc.homecraftguild.guild_master";
        if (lower.equals("гільдійний реєстратор") || lower.equals("guild registrar")) return "lang:npc.homecraftguild.guild_registrar";
        if (lower.equals("гільдійний торговець") || lower.equals("guild trader")) return "lang:npc.homecraftguild.trader";
        if (lower.equals("продуктовий торговець") || lower.equals("food trader")) return "lang:npc.homecraftguild.trader_food";
        if (lower.equals("торговець інструментами") || lower.equals("tool trader")) return "lang:npc.homecraftguild.trader_tools";
        if (lower.equals("героїчний зброяр") || lower.equals("heroic weaponsmith")) return "lang:npc.homecraftguild.trader_weapons";
        if (lower.equals("майстер броні") || lower.equals("armor master")) return "lang:npc.homecraftguild.trader_armor";
        if (lower.equals("елітний гільдійний торговець") || lower.equals("elite guild trader")) return "lang:npc.homecraftguild.trader_elite";
        return value.isBlank() ? defaultDisplayNameKey(key, systemNpc) : value;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }
}
