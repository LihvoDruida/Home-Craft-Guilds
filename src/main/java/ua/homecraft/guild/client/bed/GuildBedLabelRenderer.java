package ua.homecraft.guild.client.bed;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionfc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class GuildBedLabelRenderer {
    private static final Map<String, BedLabel> LABELS = new LinkedHashMap<>();
    private static final int MAX_LABELS = 256;
    private static final long LABEL_TTL_MS = 120_000L;
    private static final double FALLBACK_MAX_DISTANCE = 32.0D;
    private static final double FALLBACK_Y_OFFSET = 1.35D;

    private GuildBedLabelRenderer() {}

    public static void acceptEvent(String[] p) {
        if (p == null || p.length < 8) return;
        String dim = normalizeDimension(p[2]);
        int x = parseInt(p[3], 0);
        int y = parseInt(p[4], 64);
        int z = parseInt(p[5], 0);
        boolean active = Boolean.parseBoolean(p[6]);
        String owner = p[7] == null ? "" : p[7].trim();
        String key = key(dim, x, y, z);
        if (!active || owner.isBlank()) {
            LABELS.remove(key);
            return;
        }
        LABELS.put(key, new BedLabel(dim, x, y, z, owner, System.currentTimeMillis() + LABEL_TTL_MS));
        while (LABELS.size() > MAX_LABELS) {
            Iterator<String> it = LABELS.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (event == null || !isRenderableStage(event)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null || LABELS.isEmpty()) return;

        String dim = dimensionId(mc.level.dimension());
        long now = System.currentTimeMillis();
        LABELS.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expireAtMs < now || !dim.equals(e.getValue().dimension));
        if (LABELS.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        if (pose == null) return;

        Object camera = resolveCamera(event, mc);
        Vec3 cam = resolveCameraPos(camera, mc);
        if (cam == null) return;

        Font font = mc.font;
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        double maxDistanceSqr = FALLBACK_MAX_DISTANCE * FALLBACK_MAX_DISTANCE;
        for (BedLabel label : LABELS.values()) {
            double lx = label.x + 0.5D;
            double ly = label.y + 0.75D + FALLBACK_Y_OFFSET;
            double lz = label.z + 0.5D;
            double dx = mc.player.getX() - lx;
            double dy = mc.player.getY() - ly;
            double dz = mc.player.getZ() - lz;
            if (dx * dx + dy * dy + dz * dz > maxDistanceSqr) continue;

            String text = "Ліжко: " + label.owner;
            pose.pushPose();
            pose.translate(lx - cam.x, ly - cam.y, lz - cam.z);
            applyCameraRotation(pose, camera);
            pose.scale(-0.025F, -0.025F, 0.025F);
            float width = -font.width(text) / 2.0F;
            font.drawInBatch(text, width, 0.0F, 0xE8FFFFFF, false, pose.last().pose(), buffer, Font.DisplayMode.SEE_THROUGH, 0x55000000, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        buffer.endBatch();
    }

    private static boolean isRenderableStage(RenderLevelStageEvent event) {
        Object stage = invokeNoArg(event, "getStage", "stage");
        if (stage == null) return true;
        String name = String.valueOf(stage).toUpperCase(Locale.ROOT);
        return name.contains("AFTER_LEVEL") || name.contains("AFTER_ENTITIES") || name.contains("AFTER_TRANSLUCENT") || name.contains("AFTER_PARTICLES");
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
        if (mc != null && mc.player != null) {
            return new Vec3(mc.player.getX(), mc.player.getY() + 1.62D, mc.player.getZ());
        }
        return null;
    }

    private static void applyCameraRotation(PoseStack pose, Object camera) {
        Object rot = invokeNoArg(camera, "rotation", "getRotation");
        if (rot instanceof Quaternionfc q) {
            pose.mulPose(q);
        }
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
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object readField(Object target, String... names) {
        if (target == null || names == null) return null;
        Class<?> type = target.getClass();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Class<?> t = type;
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

    private static String key(String dim, int x, int y, int z) {
        return normalizeDimension(dim) + '|' + x + '|' + y + '|' + z;
    }

    private static int parseInt(String raw, int fallback) {
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; }
    }

    private static String normalizeDimension(String raw) {
        if (raw == null || raw.isBlank()) return "minecraft:overworld";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String dimensionId(Object key) {
        String raw = String.valueOf(key == null ? "" : key).trim();
        if (raw.isEmpty()) return "minecraft:overworld";
        String prefix = "ResourceKey[minecraft:dimension / ";
        if (raw.startsWith(prefix) && raw.endsWith("]")) return normalizeDimension(raw.substring(prefix.length(), raw.length() - 1));
        int sep = raw.lastIndexOf(" / ");
        if (sep >= 0 && raw.endsWith("]")) return normalizeDimension(raw.substring(sep + 3, raw.length() - 1));
        return normalizeDimension(raw);
    }

    private record BedLabel(String dimension, int x, int y, int z, String owner, long expireAtMs) {}
}
