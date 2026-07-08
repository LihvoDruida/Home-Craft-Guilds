package ua.homecraft.guild.client.npc;

import ua.homecraft.guild.client.HomeCraftGuildI18n;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.client.skin.GuildNpcSkins;
import ua.homecraft.guild.entity.GuildRegistrarEntity;
import ua.homecraft.guild.entity.HomeCraftGuildEntities;
import ua.homecraft.guild.network.GuildActionPayload;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Legacy client-owned registrar fallback.
 *
 * v135 uses real tracked GuildRegistrarEntity + npc2 cache rows. This class is kept only for old
 * npc|dimension|enabled|x|y|z|yaw|name packets from legacy servers. If a modern npc2 row arrives,
 * the fallback point is cleared so it cannot intercept right-clicks over a real server entity.
 */
public final class GuildVirtualRegistrarClient {
    private static final double RENDER_RADIUS = 96.0D;
    private static final double LOOK_RADIUS = 9.0D;
    private static final double INTERACT_RADIUS = 4.75D;
    private static final long STATE_TTL_MS = 180_000L;
    private static final int LOCAL_DUMMY_ENTITY_ID = 721106321;
    private static final UUID LOCAL_DUMMY_UUID = UUID.nameUUIDFromBytes("homecraftguild:virtual_guild_registrar".getBytes(StandardCharsets.UTF_8));

    private static NpcPoint point;
    private static GuildRegistrarEntity dummy;
    private static ClientLevel dummyLevel;
    private static String dummyName = "";
    private static boolean dummyAttachedToClientLevel = false;
    private static boolean useWasDown = false;
    private static long interactionCooldownUntilTick = 0L;
    private static long lastAmbientTick = 0L;
    private static long lastInteractionTick = 0L;
    private static float bodyYaw = 0.0F;
    private static float headYaw = 0.0F;
    private static float headPitch = 0.0F;
    private static Method cachedRenderMethod;
    private static boolean dispatcherRenderFailed = false;

    private GuildVirtualRegistrarClient() {}

    public static void acceptSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return;
        try {
            String[] rows = snapshot.split(";");
            boolean sawNpc = false;
            for (String row : rows) {
                if (row == null) continue;
                if (row.startsWith("npc2|")) {
                    clear();
                    return;
                }
                if (!row.startsWith("npc|")) continue;
                sawNpc = true;
                acceptNpcRow(row);
            }
            // Old servers do not send npc rows; keep current point until TTL instead of flickering.
            if (!sawNpc && point != null && point.expireAtMs < System.currentTimeMillis()) clear();
        } catch (Throwable ignored) { }
    }

    public static void acceptNpcRow(String row) {
        try {
            String[] p = row.split("\\|", -1);
            if (p.length < 3 || !"npc".equals(p[0])) return;
            boolean enabled = "1".equals(p[2]) || "true".equalsIgnoreCase(p[2]);
            if (!enabled) {
                clear();
                return;
            }
            if (p.length < 8) return;
            String dimension = normalizeDimension(p[1]);
            double x = parseDouble(p[3], 0.5D);
            double y = parseDouble(p[4], 64.0D);
            double z = parseDouble(p[5], 0.5D);
            float yaw = (float) parseDouble(p[6], 0.0D);
            String name = p[7] == null || p[7].isBlank() ? HomeCraftGuildI18n.t("npc.homecraftguild.registrar") : p[7].trim();
            point = new NpcPoint(dimension, x, y, z, yaw, name, System.currentTimeMillis() + STATE_TTL_MS);
            if (dummy == null) {
                bodyYaw = yaw;
                headYaw = yaw;
                headPitch = 0.0F;
            }
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.debug("Ignored bad guild registrar npc row: {}", row);
        }
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;
        if (point != null && point.expireAtMs < System.currentTimeMillis()) clear();
        if (!isActiveInCurrentDimension(mc)) {
            useWasDown = isUseKeyDown(mc);
            return;
        }
        long tick = mc.level.getGameTime();
        updatePose(mc, tick);
        handleInteraction(mc, tick);
        maybeAmbientSound(mc, tick);
    }

    public static void render(RenderLevelStageEvent event) {
        // v114: visual rendering is handled by the normal tracked GuildRegistrarEntity renderer.
        // Keep the virtual point only for local sounds, early right-click suppression and server-side distance checks.
    }

    private static GuildRegistrarEntity ensureDummy(ClientLevel level, String name) {
        if (level == null) return null;
        if (dummy == null || dummyLevel != level) {
            dummyLevel = level;
            detachDummyFromClientLevel();
            dummy = new GuildRegistrarEntity(HomeCraftGuildEntities.GUILD_REGISTRAR.get(), level);
            dummy.setId(LOCAL_DUMMY_ENTITY_ID);
            invoke(dummy, "setUUID", new Class<?>[]{UUID.class}, new Object[]{LOCAL_DUMMY_UUID});
            dummy.setupRegistrar(name == null || name.isBlank() ? HomeCraftGuildI18n.t("npc.homecraftguild.registrar") : name);
            dummy.setNoGravity(true);
            dummy.setSilent(true);
            dummyName = name;
            // Do not rely on ClientLevel entity lists for visibility: some 1.21.11 builds accept
            // a local entity but never include it in the render list. We keep the dummy only as
            // render-state data for the manual dispatcher path and the skin-billboard fallback.
            dummyAttachedToClientLevel = false;
            if (point != null) {
                bodyYaw = point.yaw;
                headYaw = point.yaw;
                headPitch = 0.0F;
            }
        }
        if (name != null && !name.equals(dummyName)) {
            dummy.setupRegistrar(name);
            dummyName = name;
        }
        return dummy;
    }

    private static void updateDummyPosition(GuildRegistrarEntity entity, NpcPoint p) {
        if (entity == null || p == null) return;
        entity.setPos(p.x, p.y, p.z);
        entity.xo = p.x;
        entity.yo = p.y;
        entity.zo = p.z;
        entity.setYRot(bodyYaw);
        entity.setYBodyRot(bodyYaw);
        entity.setYHeadRot(headYaw);
        entity.setXRot(headPitch);
        entity.yRotO = bodyYaw;
        entity.yBodyRotO = bodyYaw;
        entity.yHeadRotO = headYaw;
        entity.xRotO = headPitch;
    }

    private static boolean attachDummyToClientLevel(ClientLevel level, GuildRegistrarEntity entity) {
        if (level == null || entity == null) return false;
        try {
            if (tryAttachWithMethod(level, entity, "addEntity")) return true;
            if (tryAttachWithMethod(level, entity, "putNonPlayerEntity")) return true;
            if (tryAttachWithMethod(level, entity, "addFreshEntity")) return true;
            if (tryAttachWithMethod(level, entity, "addEntityToWorld")) return true;
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.debug("Virtual guild registrar could not be attached to client level", t);
        }
        return false;
    }

    private static boolean tryAttachWithMethod(ClientLevel level, GuildRegistrarEntity entity, String name) {
        Class<?> type = level.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                try {
                    method.setAccessible(true);
                    if (params.length == 1 && Entity.class.isAssignableFrom(params[0])) {
                        method.invoke(level, entity);
                        return true;
                    }
                    if (params.length == 2 && (params[0] == Integer.TYPE || params[0] == Integer.class) && Entity.class.isAssignableFrom(params[1])) {
                        method.invoke(level, LOCAL_DUMMY_ENTITY_ID, entity);
                        return true;
                    }
                } catch (Throwable ignored) { }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static void detachDummyFromClientLevel() {
        if (dummy == null) {
            dummyAttachedToClientLevel = false;
            return;
        }
        try {
            dummy.remove(RemovalReason.DISCARDED);
        } catch (Throwable ignored) {
            try { dummy.discard(); } catch (Throwable ignoredAgain) { }
        }
        dummyAttachedToClientLevel = false;
    }

    public static boolean consumeClientUseInput() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != null || mc.level == null || mc.player == null) return false;
        if (!isActiveInCurrentDimension(mc) || !crosshairHitsNpc(mc)) return false;
        long tick = mc.level.getGameTime();
        suppressVanillaItemUse(mc);
        triggerInteraction(mc, tick);
        useWasDown = true;
        return true;
    }

    public static boolean isLookingAtRegistrar() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.screen == null && isActiveInCurrentDimension(mc) && crosshairHitsNpc(mc);
    }

    private static void updatePose(Minecraft mc, long tick) {
        NpcPoint p = point;
        if (p == null || mc.player == null) return;
        double dx = mc.player.getX() - p.x;
        double dy = (mc.player.getEyeY() - (p.y + 1.62D));
        double dz = mc.player.getZ() - p.z;
        double distSqr = dx * dx + dy * dy + dz * dz;
        boolean attentive = distSqr <= LOOK_RADIUS * LOOK_RADIUS || tick - lastInteractionTick <= 80L;

        float idleBody = p.yaw + (float) Math.sin(tick * 0.020D) * 1.2F;
        float targetBody = idleBody;
        float targetHead = idleBody + (float) Math.sin(tick * 0.037D) * 3.0F;
        float targetPitch = (float) Math.sin(tick * 0.029D) * 1.0F;
        if (attentive) {
            float lookYaw = yawTo(dx, dz);
            float relative = wrapDegrees(lookYaw - p.yaw);
            float clampedRelative = clamp(relative, -72.0F, 72.0F);
            targetHead = p.yaw + clampedRelative;
            targetBody = p.yaw + clamp(relative, -28.0F, 28.0F) * 0.35F + (float) Math.sin(tick * 0.035D) * 0.45F;
            double flat = Math.sqrt(dx * dx + dz * dz);
            targetPitch = clamp((float) (-Math.toDegrees(Math.atan2(dy, Math.max(0.001D, flat)))), -32.0F, 24.0F);
        }

        bodyYaw = rotateToward(bodyYaw, targetBody, attentive ? 3.2F : 1.1F);
        headYaw = rotateToward(headYaw, targetHead, attentive ? 8.0F : 2.0F);
        headYaw = bodyYaw + clamp(wrapDegrees(headYaw - bodyYaw), -78.0F, 78.0F);
        headPitch = rotateToward(headPitch, targetPitch, attentive ? 4.0F : 1.2F);
    }

    private static void handleInteraction(Minecraft mc, long tick) {
        boolean down = isUseKeyDown(mc);
        boolean hitsNpc = down && crosshairHitsNpc(mc);
        if (hitsNpc) {
            // The virtual NPC is not a real server entity, so vanilla would otherwise treat the same
            // right-click as "use item in air/block". Suppress it locally every tick while the use
            // key is held over the registrar, so food, potions, blocks, bows, shields, etc. are not used.
            suppressVanillaItemUse(mc);
            if (!useWasDown) triggerInteraction(mc, tick);
            useWasDown = true;
            return;
        }
        useWasDown = down;
    }

    private static void triggerInteraction(Minecraft mc, long tick) {
        if (mc == null || mc.level == null || mc.player == null) return;
        if (tick < interactionCooldownUntilTick) return;
        interactionCooldownUntilTick = tick + 8L;
        lastInteractionTick = tick;
        playVillagerSound(mc, true);
        ClientPacketDistributor.sendToServer(new GuildActionPayload("npc_interact", "", ""));
    }

    private static void suppressVanillaItemUse(Minecraft mc) {
        if (mc == null) return;
        try { invokeNoArg(mc.player, "stopUsingItem", "releaseUsingItem"); } catch (Throwable ignored) { }
        try {
            Object options = readField(mc, "options");
            Object key = readField(options, "keyUse", "useKey", "keyUseMapping");
            if (!tryInvokeBoolean(key, "setDown", false)) tryInvokeBoolean(key, "setPressed", false);
            trySetBooleanField(key, false, "isDown", "down", "pressed");
            trySetIntField(key, 0, "clickCount", "timesPressed");
        } catch (Throwable ignored) { }
    }

    private static boolean tryInvokeBoolean(Object target, String name, boolean value) {
        if (target == null || name == null) return false;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name, Boolean.TYPE);
                method.setAccessible(true);
                method.invoke(target, value);
                return true;
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return false;
    }

    private static void maybeAmbientSound(Minecraft mc, long tick) {
        NpcPoint p = point;
        if (p == null || mc.player == null) return;
        if (tick - lastAmbientTick < 180L) return;
        if (distanceSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ(), p.x, p.y, p.z) > LOOK_RADIUS * LOOK_RADIUS) return;
        long jitter = Math.abs((long) (p.x * 31.0D + p.z * 17.0D)) % 80L;
        if (tick - lastAmbientTick < 180L + jitter) return;
        lastAmbientTick = tick;
        playVillagerSound(mc, false);
    }

    private static void playVillagerSound(Minecraft mc, boolean interact) {
        NpcPoint p = point;
        if (mc == null || mc.level == null || p == null) return;
        float pitch = interact ? 0.95F + (float) (Math.random() * 0.10D) : 0.88F + (float) (Math.random() * 0.16D);
        try {
            mc.level.playLocalSound(p.x, p.y + 1.0D, p.z, interact ? SoundEvents.VILLAGER_TRADE : SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, interact ? 0.72F : 0.36F, pitch, false);
        } catch (Throwable ignored) { }
    }

    private static boolean crosshairHitsNpc(Minecraft mc) {
        NpcPoint p = point;
        if (mc == null || mc.player == null || p == null) return false;
        if (distanceSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ(), p.x, p.y, p.z) > INTERACT_RADIUS * INTERACT_RADIUS) return false;
        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 view = mc.player.getViewVector(1.0F).normalize();
        Vec3 end = eye.add(view.x * INTERACT_RADIUS, view.y * INTERACT_RADIUS, view.z * INTERACT_RADIUS);
        AABB box = new AABB(p.x - 0.36D, p.y, p.z - 0.36D, p.x + 0.36D, p.y + 1.95D, p.z + 0.36D).inflate(0.12D);
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

    private static boolean isActiveInCurrentDimension(Minecraft mc) {
        NpcPoint p = point;
        return p != null && mc != null && mc.level != null && p.dimension.equals(dimensionId(mc.level));
    }

    private static void clear() {
        point = null;
        detachDummyFromClientLevel();
        dummy = null;
        dummyLevel = null;
        dummyName = "";
        cachedRenderMethod = null;
        dispatcherRenderFailed = false;
    }

    private static void renderViaDispatcher(Minecraft mc, GuildRegistrarEntity entity, NpcPoint p, Vec3 cam, RenderLevelStageEvent event, PoseStack pose) {
        if (mc == null || entity == null || p == null || cam == null || pose == null || dispatcherRenderFailed) return;
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        try {
            Object dispatcher = invokeNoArg(mc, "getEntityRenderDispatcher", "entityRenderDispatcher");
            if (dispatcher == null) dispatcher = readField(mc, "entityRenderDispatcher");
            if (dispatcher == null) return;
            invokeDispatcherRender(dispatcher, entity, p.x - cam.x, p.y - cam.y, p.z - cam.z, partialTick(event), pose, buffer);
        } catch (Throwable t) {
            dispatcherRenderFailed = true;
            HomeCraftGuildMod.LOGGER.debug("Virtual guild registrar entity dispatcher render unavailable; using skin billboard fallback", t);
        } finally {
            try { buffer.endBatch(); } catch (Throwable ignored) { }
        }
    }

    private static void renderManualPlayerSkinModel(Minecraft mc, NpcPoint p, Object camera, Vec3 cam, PoseStack pose) {
        if (mc == null || p == null || cam == null || pose == null) return;
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        try {
            Identifier texture = GuildNpcSkins.guildRegistrar().texture();
            VertexConsumer vc = buffer.getBuffer(RenderTypes.entityCutoutNoCull(texture));

            pose.pushPose();
            try {
                pose.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
                pose.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));

                float breathe = (float) Math.sin((mc.level == null ? 0L : mc.level.getGameTime()) * 0.045D) * 0.012F;
                pose.translate(0.0D, breathe, 0.0D);

                // Base player-skin cuboids. Dimensions are close to the vanilla player shape,
                // but intentionally simple so the virtual NPC does not require a real Entity in the world.
                drawSkinBox(vc, pose, -0.25F, 1.42F, -0.25F, 0.25F, 1.92F, 0.25F,
                        8, 8, 16, 16, 0, 8, 8, 16, 16, 8, 24, 16, 8, 0, 16, 8, 16, 0, 24, 8); // head
                drawSkinBox(vc, pose, -0.255F, 1.415F, -0.255F, 0.255F, 1.925F, 0.255F,
                        40, 8, 48, 16, 32, 8, 40, 16, 48, 8, 56, 16, 40, 0, 48, 8, 48, 0, 56, 8); // hat overlay

                drawSkinBox(vc, pose, -0.25F, 0.72F, -0.13F, 0.25F, 1.42F, 0.13F,
                        20, 20, 28, 32, 16, 20, 20, 32, 28, 20, 40, 32, 20, 16, 28, 20, 28, 16, 36, 20); // body
                drawSkinBox(vc, pose, -0.268F, 0.705F, -0.145F, 0.268F, 1.435F, 0.145F,
                        20, 36, 28, 48, 16, 36, 20, 48, 28, 36, 40, 48, 20, 32, 28, 36, 28, 32, 36, 36); // jacket overlay

                drawSkinBox(vc, pose, -0.43F, 0.72F, -0.13F, -0.25F, 1.42F, 0.13F,
                        44, 20, 48, 32, 40, 20, 44, 32, 48, 20, 56, 32, 44, 16, 48, 20, 48, 16, 52, 20); // right arm
                drawSkinBox(vc, pose, 0.25F, 0.72F, -0.13F, 0.43F, 1.42F, 0.13F,
                        36, 52, 40, 64, 32, 52, 36, 64, 40, 52, 48, 64, 36, 48, 40, 52, 40, 48, 44, 52); // left arm
                drawSkinBox(vc, pose, -0.445F, 0.705F, -0.145F, -0.245F, 1.435F, 0.145F,
                        44, 36, 48, 48, 40, 36, 44, 48, 48, 36, 56, 48, 44, 32, 48, 36, 48, 32, 52, 36); // right sleeve overlay
                drawSkinBox(vc, pose, 0.245F, 0.705F, -0.145F, 0.445F, 1.435F, 0.145F,
                        52, 52, 56, 64, 48, 52, 52, 64, 56, 52, 64, 64, 52, 48, 56, 52, 56, 48, 60, 52); // left sleeve overlay

                drawSkinBox(vc, pose, -0.25F, 0.02F, -0.13F, 0.0F, 0.72F, 0.13F,
                        4, 20, 8, 32, 0, 20, 4, 32, 8, 20, 16, 32, 4, 16, 8, 20, 8, 16, 12, 20); // right leg
                drawSkinBox(vc, pose, 0.0F, 0.02F, -0.13F, 0.25F, 0.72F, 0.13F,
                        20, 52, 24, 64, 16, 52, 20, 64, 24, 52, 32, 64, 20, 48, 24, 52, 24, 48, 28, 52); // left leg
                drawSkinBox(vc, pose, -0.265F, 0.005F, -0.145F, 0.005F, 0.735F, 0.145F,
                        4, 36, 8, 48, 0, 36, 4, 48, 8, 36, 16, 48, 4, 32, 8, 36, 8, 32, 12, 36); // right pants overlay
                drawSkinBox(vc, pose, -0.005F, 0.005F, -0.145F, 0.265F, 0.735F, 0.145F,
                        4, 52, 8, 64, 0, 52, 4, 64, 8, 52, 16, 64, 4, 48, 8, 52, 8, 48, 12, 52); // left pants overlay
            } finally {
                pose.popPose();
            }

            renderFallbackNameTag(mc, p, camera, cam, pose, buffer);
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.debug("Virtual guild registrar manual player model render failed", t);
            renderFallbackNameTag(mc, p, camera, cam, pose, buffer);
        } finally {
            try { buffer.endBatch(); } catch (Throwable ignored) { }
        }
    }

    private static void drawSkinBox(VertexConsumer vc, PoseStack pose,
                                    float x1, float y1, float z1, float x2, float y2, float z2,
                                    int frontU1, int frontV1, int frontU2, int frontV2,
                                    int rightU1, int rightV1, int rightU2, int rightV2,
                                    int backU1, int backV1, int backU2, int backV2,
                                    int topU1, int topV1, int topU2, int topV2,
                                    int bottomU1, int bottomV1, int bottomU2, int bottomV2) {
        if (vc == null || pose == null) return;
        // front/back are intentionally mapped to the local Z faces. RenderType is no-cull,
        // so the NPC remains visible even if a resource pack flips face winding.
        drawFace(vc, pose, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, frontU1, frontV2, frontU2, frontV1, 0.0F, 0.0F, -1.0F);
        drawFace(vc, pose, x2, y1, z2, x1, y1, z2, x1, y2, z2, x2, y2, z2, backU1, backV2, backU2, backV1, 0.0F, 0.0F, 1.0F);
        drawFace(vc, pose, x1, y1, z2, x1, y1, z1, x1, y2, z1, x1, y2, z2, rightU1, rightV2, rightU2, rightV1, -1.0F, 0.0F, 0.0F);
        drawFace(vc, pose, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1, rightU1, rightV2, rightU2, rightV1, 1.0F, 0.0F, 0.0F);
        drawFace(vc, pose, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2, topU1, topV2, topU2, topV1, 0.0F, 1.0F, 0.0F);
        drawFace(vc, pose, x1, y1, z2, x2, y1, z2, x2, y1, z1, x1, y1, z1, bottomU1, bottomV2, bottomU2, bottomV1, 0.0F, -1.0F, 0.0F);
    }

    private static void drawFace(VertexConsumer vc, PoseStack pose,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float x4, float y4, float z4,
                                 int u1, int v1, int u2, int v2,
                                 float nx, float ny, float nz) {
        Matrix4f matrix = pose.last().pose();
        float fu1 = u1 / 64.0F;
        float fv1 = v1 / 64.0F;
        float fu2 = u2 / 64.0F;
        float fv2 = v2 / 64.0F;
        vertex(vc, pose, matrix, x1, y1, z1, fu1, fv1, nx, ny, nz);
        vertex(vc, pose, matrix, x2, y2, z2, fu2, fv1, nx, ny, nz);
        vertex(vc, pose, matrix, x3, y3, z3, fu2, fv2, nx, ny, nz);
        vertex(vc, pose, matrix, x4, y4, z4, fu1, fv2, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vc, PoseStack pose, Matrix4f matrix,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz) {
        vc.addVertex(matrix, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose.last(), nx, ny, nz);
    }

    private static void renderFallbackNameTag(Minecraft mc, NpcPoint p, Object camera, Vec3 cam, PoseStack pose, MultiBufferSource buffer) {
        try {
            if (mc == null || mc.font == null || p == null || cam == null || pose == null || buffer == null) return;
            String name = p.name == null || p.name.isBlank() ? HomeCraftGuildI18n.t("npc.homecraftguild.registrar") : p.name;
            pose.pushPose();
            try {
                pose.translate(p.x - cam.x, p.y + 2.12D - cam.y, p.z - cam.z);
                applyCameraRotation(pose, camera);
                pose.scale(-0.025F, -0.025F, 0.025F);
                float width = -mc.font.width(name) / 2.0F;
                mc.font.drawInBatch(name, width, 0.0F, 0xF2FFFFFF, false, pose.last().pose(), buffer, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, 0x66000000, LightTexture.FULL_BRIGHT);
            } finally {
                pose.popPose();
            }
        } catch (Throwable ignored) { }
    }

    private static void applyCameraRotation(PoseStack pose, Object camera) {
        Object rot = invokeNoArg(camera, "rotation", "getRotation");
        if (rot instanceof Quaternionfc q) {
            pose.mulPose(q);
        }
    }

    private static void invokeDispatcherRender(Object dispatcher, Entity entity, double x, double y, double z, float partialTick, PoseStack pose, MultiBufferSource buffer) throws Exception {
        Method method = cachedRenderMethod;
        if (method == null || !method.getDeclaringClass().isInstance(dispatcher)) {
            method = findRenderMethod(dispatcher);
            cachedRenderMethod = method;
        }
        if (method == null) return;
        int pc = method.getParameterCount();
        if (pc == 9) {
            method.invoke(dispatcher, entity, x, y, z, entity.getYRot(), partialTick, pose, buffer, LightTexture.FULL_BRIGHT);
        } else if (pc == 8) {
            method.invoke(dispatcher, entity, x, y, z, entity.getYRot(), pose, buffer, LightTexture.FULL_BRIGHT);
        }
    }

    private static Method findRenderMethod(Object dispatcher) {
        if (dispatcher == null) return null;
        for (Method method : dispatcher.getClass().getMethods()) {
            if (!"render".equals(method.getName())) continue;
            int pc = method.getParameterCount();
            if (pc != 8 && pc != 9) continue;
            Class<?>[] types = method.getParameterTypes();
            if (!Entity.class.isAssignableFrom(types[0])) continue;
            if (types[1] != Double.TYPE || types[2] != Double.TYPE || types[3] != Double.TYPE) continue;
            try {
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static float partialTick(RenderLevelStageEvent event) {
        Object value = invokeNoArg(event, "getPartialTick", "partialTick");
        if (value instanceof Number number) return number.floatValue();
        Object delta = value != null ? value : invokeNoArg(Minecraft.getInstance(), "getDeltaTracker", "deltaTracker");
        Object number = invoke(delta, "getGameTimeDeltaPartialTick", new Class<?>[]{Boolean.TYPE}, new Object[]{Boolean.FALSE});
        if (number instanceof Number n) return n.floatValue();
        number = invokeNoArg(delta, "getGameTimeDeltaTicks", "getRealtimeDeltaTicks");
        if (number instanceof Number n) return n.floatValue();
        return 1.0F;
    }

    private static boolean isRenderableStage(RenderLevelStageEvent event) {
        Object stage = invokeNoArg(event, "getStage", "stage");
        if (stage == null) return true;
        String name = String.valueOf(stage).toUpperCase(Locale.ROOT);
        return name.contains("AFTER_ENTITIES") || name.contains("AFTER_LEVEL") || name.contains("AFTER_TRANSLUCENT") || name.contains("AFTER_PARTICLES");
    }

    private static Object resolveCamera(RenderLevelStageEvent event, Minecraft mc) {
        Object camera = invokeNoArg(event, "getCamera", "camera");
        if (camera != null) return camera;
        Object gameRenderer = mc == null ? null : readField(mc, "gameRenderer");
        camera = invokeNoArg(gameRenderer, "getMainCamera", "mainCamera", "camera");
        if (camera != null) return camera;
        return readField(gameRenderer, "mainCamera", "camera");
    }

    private static Vec3 resolveCameraPos(Object camera, Minecraft mc) {
        Object pos = invokeNoArg(camera, "getPosition", "getPos", "position", "pos");
        if (pos instanceof Vec3 vec) return vec;
        pos = readField(camera, "position", "pos");
        if (pos instanceof Vec3 vec) return vec;
        if (mc != null && mc.player != null) return new Vec3(mc.player.getX(), mc.player.getY() + 1.62D, mc.player.getZ());
        return null;
    }

    private static boolean isUseKeyDown(Minecraft mc) {
        Object options = readField(mc, "options");
        Object key = readField(options, "keyUse", "useKey", "keyUseMapping");
        Object down = invokeNoArg(key, "isDown", "isPressed");
        return down instanceof Boolean b && b;
    }

    private static Object invokeNoArg(Object target, String... names) {
        if (target == null || names == null) return null;
        Class<?> type = target.getClass();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object[] args) {
        if (target == null || name == null) return null;
        try {
            Method method = target.getClass().getMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Object target, String... names) {
        if (target == null || names == null) return null;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Class<?> t = target.getClass();
            while (t != null) {
                try {
                    Field field = t.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                    t = t.getSuperclass();
                }
            }
        }
        return null;
    }

    private static void trySetBooleanField(Object target, boolean value, String... names) {
        if (target == null || names == null) return;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Class<?> t = target.getClass();
            while (t != null) {
                try {
                    Field field = t.getDeclaredField(name);
                    if (field.getType() == Boolean.TYPE || field.getType() == Boolean.class) {
                        field.setAccessible(true);
                        field.set(target, value);
                        return;
                    }
                    t = t.getSuperclass();
                } catch (Throwable ignored) {
                    t = t.getSuperclass();
                }
            }
        }
    }

    private static void trySetIntField(Object target, int value, String... names) {
        if (target == null || names == null) return;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Class<?> t = target.getClass();
            while (t != null) {
                try {
                    Field field = t.getDeclaredField(name);
                    if (field.getType() == Integer.TYPE || field.getType() == Integer.class) {
                        field.setAccessible(true);
                        field.set(target, value);
                        return;
                    }
                    t = t.getSuperclass();
                } catch (Throwable ignored) {
                    t = t.getSuperclass();
                }
            }
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try { return raw == null ? fallback : Double.parseDouble(raw.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static String normalizeDimension(String raw) {
        if (raw == null || raw.isBlank()) return "minecraft:overworld";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String dimensionId(ClientLevel level) {
        if (level == null) return "minecraft:overworld";
        String raw = String.valueOf(level.dimension()).trim();
        String prefix = "ResourceKey[minecraft:dimension / ";
        if (raw.startsWith(prefix) && raw.endsWith("]")) return normalizeDimension(raw.substring(prefix.length(), raw.length() - 1));
        int sep = raw.lastIndexOf(" / ");
        if (sep >= 0 && raw.endsWith("]")) return normalizeDimension(raw.substring(sep + 3, raw.length() - 1));
        return normalizeDimension(raw);
    }

    private static double distanceSqr(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static float yawTo(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    private static float rotateToward(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        delta = clamp(delta, -maxStep, maxStep);
        return current + delta;
    }

    private static float wrapDegrees(float degrees) {
        float value = degrees % 360.0F;
        if (value >= 180.0F) value -= 360.0F;
        if (value < -180.0F) value += 360.0F;
        return value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record NpcPoint(String dimension, double x, double y, double z, float yaw, String name, long expireAtMs) {}
}
