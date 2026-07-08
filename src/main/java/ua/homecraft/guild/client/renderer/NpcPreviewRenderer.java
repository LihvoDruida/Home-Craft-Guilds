package ua.homecraft.guild.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.client.skin.GuildNpcSkins;
import ua.homecraft.guild.entity.GuildRegistrarEntity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * NPC preview renderer for the MC/NeoForge 1.21.11 GUI submission pipeline.
 *
 * v153 restores the real in-GUI preview path first: submit the selected NPC/player-skin
 * model to the GUI picture-in-picture renderer when the current mapping exposes it.
 * If the exact PiP method name differs, reflection probes the known 1.21.11 names.
 * The paper-doll skin preview remains only as a safe fallback and is clipped/scaled.
 */
public final class NpcPreviewRenderer {
    private NpcPreviewRenderer() {}

    public static boolean render(GuiGraphics graphics, int x, int y, int w, int h, int scale, float yaw, GuildRegistrarEntity entity) {
        return render(graphics, x, y, w, h, scale, yaw, x + w / 2.0D, y + h * 0.45D, entity);
    }

    public static boolean render(GuiGraphics graphics, int x, int y, int w, int h, int scale, float yaw,
                                 double mouseX, double mouseY, GuildRegistrarEntity entity) {
        if (graphics == null || entity == null || w <= 8 || h <= 8) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return false;
        GuildNpcSkins.SkinProfile profile = GuildNpcSkins.resolve(entity.homecraft$npcSkinId());
        if (profile == null || profile.texture() == null) return false;

        boolean scissor = false;
        try {
            graphics.enableScissor(x, y, x + w, y + h);
            scissor = true;
            // First try the same helper path used by the vanilla inventory player preview.
            // This keeps the model proportions/pose consistent with the inventory screen while
            // the GuildRegistrarRenderer still supplies the selected HomeCraft skin.
            float mouseYaw = clamp((float) ((mouseX - (x + w * 0.50D)) * 0.75D), -55.0F, 55.0F);
            float mousePitch = clamp((float) ((mouseY - (y + h * 0.46D)) * 0.55D), -38.0F, 38.0F);
            float renderYaw = yaw + mouseYaw;
            if (submitInventoryScreenPreview(graphics, entity, x, y, w, h, scale, renderYaw, mouseX, mouseY)) return true;
            if (submitEntityPip(mc, graphics, entity, x, y, w, h, scale, renderYaw, mousePitch)) return true;
            if (submitPlayerSkinPip(mc, graphics, entity, profile, x, y, w, h, scale, renderYaw, mousePitch)) return true;
            return renderPaperDollFallback(graphics, profile.texture(), x, y, w, h);
        } catch (Throwable ignored) {
            return renderPaperDollFallback(graphics, profile.texture(), x, y, w, h);
        } finally {
            if (scissor) {
                try { graphics.disableScissor(); } catch (Throwable ignored) { }
            }
        }
    }

    private static boolean submitInventoryScreenPreview(GuiGraphics graphics, GuildRegistrarEntity entity,
                                                        int x, int y, int w, int h, int scale, float yaw,
                                                        double mouseX, double mouseY) {
        if (graphics == null || entity == null) return false;
        try {
            Class<?> inventoryScreen = Class.forName("net.minecraft.client.gui.screens.inventory.InventoryScreen");
            Method[] methods = inventoryScreen.getMethods();
            for (Method method : methods) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("renderentityininventory")) continue;
                Object[] args = inventoryPreviewArgs(method.getParameterTypes(), graphics, entity, x, y, w, h, scale, yaw, mouseX, mouseY);
                if (args == null) continue;
                try {
                    method.invoke(null, args);
                    return true;
                } catch (Throwable ignored) {
                    // Try the next overload. 1.21.x has several close inventory-preview helpers.
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static Object[] inventoryPreviewArgs(Class<?>[] types, GuiGraphics graphics, GuildRegistrarEntity entity,
                                                  int x, int y, int w, int h, int scale, float yaw,
                                                  double mouseX, double mouseY) {
        if (types == null || types.length == 0) return null;
        Object[] args = new Object[types.length];
        int safeScale = Math.max(18, Math.min(96, scale));
        int cx = x + Math.max(1, w / 2);
        int cy = y + Math.max(1, h / 2) + Math.max(18, Math.min(48, safeScale / 2));
        int x2 = x + Math.max(1, w);
        int y2 = y + Math.max(1, h);
        float followX = clamp((float) (mouseX - (x + w * 0.50D)), -80.0F, 80.0F);
        float followY = clamp((float) (mouseY - (y + h * 0.46D)), -70.0F, 70.0F);
        int intIndex = 0;
        int floatIndex = 0;
        int entityCount = 0;
        for (Class<?> type : types) if (type.isInstance(entity)) entityCount++;
        if (entityCount <= 0) return null;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type.isInstance(graphics)) {
                args[i] = graphics;
            } else if (type.isInstance(entity)) {
                args[i] = entity;
            } else if (type == int.class || type == Integer.class) {
                int value;
                // Common vanilla overloads are either:
                // (GuiGraphics, x, y, scale, mouseX, mouseY, entity)
                // or (GuiGraphics, x1, y1, x2, y2, scale, mouseX, mouseY, entity).
                if (countPrimitive(types, int.class) >= 5) {
                    value = switch (intIndex) {
                        case 0 -> x;
                        case 1 -> y;
                        case 2 -> x2;
                        case 3 -> y2;
                        case 4 -> safeScale;
                        default -> 0;
                    };
                } else {
                    value = switch (intIndex) {
                        case 0 -> cx;
                        case 1 -> cy;
                        case 2 -> safeScale;
                        default -> 0;
                    };
                }
                args[i] = Integer.valueOf(value);
                intIndex++;
            } else if (type == float.class || type == Float.class) {
                float value = switch (floatIndex) {
                    case 0 -> followX;
                    case 1 -> followY;
                    case 2 -> yaw;
                    default -> 0.0F;
                };
                args[i] = Float.valueOf(value);
                floatIndex++;
            } else if (type == double.class || type == Double.class) {
                args[i] = Double.valueOf(0.0D);
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = Boolean.FALSE;
            } else if (type.getName().equals("org.joml.Quaternionf")) {
                args[i] = newQuaternion(0.0F, 180.0F - yaw, 0.0F);
            } else if (type.getName().equals("org.joml.Vector3f")) {
                args[i] = newVector3f(0.0F, -0.03F, 0.0F);
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int countPrimitive(Class<?>[] types, Class<?> primitive) {
        int count = 0;
        if (types == null) return 0;
        for (Class<?> type : types) if (type == primitive) count++;
        return count;
    }

    private static boolean submitEntityPip(Minecraft mc, GuiGraphics graphics, GuildRegistrarEntity entity,
                                           int x, int y, int w, int h, int scale, float yaw, float pitch) {
        try {
            Object renderer = rendererFor(mc, entity);
            if (renderer == null) return false;
            Object renderState = invokeNoArg(renderer, "createRenderState");
            if (renderState == null) return false;
            if (!extractRenderState(renderer, entity, renderState)) return false;

            Object translation = newVector3f(0.0F, -0.04F, 0.0F);
            Object rotation = newQuaternion(pitch * 0.35F, 180.0F - yaw, 0.0F);
            Object camera = newQuaternion(pitch * 0.18F, 0.0F, 0.0F);
            float pipScale = Math.max(32.0F, Math.min(92.0F, (float) scale));

            if (submitEntityDirect(graphics, renderState, pipScale, translation, rotation, camera, x, y, x + w, y + h)) return true;

            Object scissor = currentScissor(graphics);
            Object state = constructSpecialState(new String[]{
                            "net.minecraft.client.gui.render.state.special.EntityGuiElementRenderState",
                            "net.minecraft.client.gui.render.state.pip.EntityGuiElementRenderState"
                    },
                    new Object[]{renderState, translation, rotation, camera, x, y, x + w, y + h, pipScale, scissor},
                    new Object[]{renderState, translation, rotation, camera, x, y, x + w, y + h, pipScale, scissor, null});
            return state != null && submitSpecialState(graphics, state);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean submitPlayerSkinPip(Minecraft mc, GuiGraphics graphics, GuildRegistrarEntity entity,
                                               GuildNpcSkins.SkinProfile profile, int x, int y, int w, int h,
                                               int scale, float yaw, float pitch) {
        try {
            Object renderer = rendererFor(mc, entity);
            Object model = findPlayerModel(renderer);
            if (model == null) return false;
            Identifier texture = profile.texture();
            float pipScale = Math.max(34.0F, Math.min(96.0F, (float) scale));
            float xRot = 10.0F + pitch * 0.35F;
            float yRot = yaw;
            float yPivot = 0.0F;

            if (submitPlayerSkinDirect(graphics, model, texture, pipScale, xRot, yRot, yPivot, x, y, x + w, y + h)) return true;

            Object scissor = currentScissor(graphics);
            Object state = constructSpecialState(new String[]{
                            "net.minecraft.client.gui.render.state.special.PlayerSkinGuiElementRenderState",
                            "net.minecraft.client.gui.render.state.pip.PlayerSkinGuiElementRenderState"
                    },
                    new Object[]{model, texture, xRot, yRot, yPivot, x, y, x + w, y + h, pipScale, scissor},
                    new Object[]{model, texture, xRot, yRot, yPivot, x, y, x + w, y + h, pipScale, scissor, null});
            return state != null && submitSpecialState(graphics, state);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object rendererFor(Minecraft mc, GuildRegistrarEntity entity) {
        try {
            Object dispatcher = invokeNoArg(mc, "getEntityRenderDispatcher");
            if (dispatcher == null) return null;
            for (Method method : dispatcher.getClass().getMethods()) {
                if (!"getRenderer".equals(method.getName()) || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isInstance(entity)) continue;
                return method.invoke(dispatcher, entity);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object findPlayerModel(Object renderer) {
        if (renderer == null) return null;
        Class<?> type = renderer.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(renderer);
                    if (value == null) continue;
                    String name = value.getClass().getName().toLowerCase(Locale.ROOT);
                    if (name.contains("playermodel") || name.contains("playerentitymodel")) return value;
                } catch (Throwable ignored) { }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null || name == null) return null;
        try {
            for (Method method : target.getClass().getMethods()) {
                if (name.equals(method.getName()) && method.getParameterCount() == 0) return method.invoke(target);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean extractRenderState(Object renderer, GuildRegistrarEntity entity, Object renderState) {
        try {
            for (Method method : renderer.getClass().getMethods()) {
                if (!"extractRenderState".equals(method.getName()) || method.getParameterCount() != 3) continue;
                Class<?>[] types = method.getParameterTypes();
                if (!types[0].isInstance(entity) || !types[1].isInstance(renderState)) continue;
                method.invoke(renderer, entity, renderState, 1.0F);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean submitEntityDirect(GuiGraphics graphics, Object renderState, float scale,
                                              Object translation, Object rotation, Object camera,
                                              int x1, int y1, int x2, int y2) {
        try {
            for (Method method : graphics.getClass().getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (!(name.contains("entity") || name.contains("renderstate"))) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 9 || !types[0].isInstance(renderState)) continue;
                method.invoke(graphics, renderState, scale, translation, rotation, camera, x1, y1, x2, y2);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean submitPlayerSkinDirect(GuiGraphics graphics, Object model, Identifier texture, float scale,
                                                  float xRot, float yRot, float yPivot,
                                                  int x1, int y1, int x2, int y2) {
        try {
            for (Method method : graphics.getClass().getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (!(name.contains("playerskin") || name.equals("addplayerskin"))) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 10 || !types[0].isInstance(model) || !types[1].isInstance(texture)) continue;
                method.invoke(graphics, model, texture, scale, xRot, yRot, yPivot, x1, y1, x2, y2);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static Object constructSpecialState(String[] classNames, Object[] args10, Object[] args11) {
        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className);
                Object state = constructMatching(cls, args10);
                if (state != null) return state;
                state = constructMatching(cls, args11);
                if (state != null) return state;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Object constructMatching(Class<?> cls, Object[] args) {
        if (cls == null || args == null) return null;
        for (Constructor<?> constructor : cls.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != args.length) continue;
            Object[] adapted = new Object[args.length];
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    adapted[i] = null;
                } else if (types[i].isInstance(arg)) {
                    adapted[i] = arg;
                } else if (types[i] == int.class && arg instanceof Integer) {
                    adapted[i] = arg;
                } else if (types[i] == float.class && arg instanceof Float) {
                    adapted[i] = arg;
                } else {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            try { return constructor.newInstance(adapted); } catch (Throwable ignored) { }
        }
        return null;
    }

    private static boolean submitSpecialState(GuiGraphics graphics, Object state) {
        try {
            for (Method method : graphics.getClass().getMethods()) {
                if (method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                if (!param.isInstance(state)) continue;
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (!(name.contains("submit") || name.contains("picture") || name.contains("skin") || name.contains("entity"))) continue;
                method.invoke(graphics, state);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static Object currentScissor(GuiGraphics graphics) {
        try {
            for (Method method : graphics.getClass().getMethods()) {
                String name = method.getName();
                if (("peekScissorStack".equals(name) || "getScissor".equals(name)) && method.getParameterCount() == 0) {
                    return method.invoke(graphics);
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object newVector3f(float x, float y, float z) {
        try {
            Class<?> cls = Class.forName("org.joml.Vector3f");
            Constructor<?> constructor = cls.getConstructor(float.class, float.class, float.class);
            return constructor.newInstance(x, y, z);
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object newQuaternion(float xDegrees, float yDegrees, float zDegrees) {
        try {
            Class<?> cls = Class.forName("org.joml.Quaternionf");
            Object q = cls.getConstructor().newInstance();
            float x = (float) Math.toRadians(xDegrees);
            float y = (float) Math.toRadians(yDegrees);
            float z = (float) Math.toRadians(zDegrees);
            try {
                Method method = cls.getMethod("rotationXYZ", float.class, float.class, float.class);
                method.invoke(q, x, y, z);
                return q;
            } catch (Throwable ignored) { }
            try {
                Method method = cls.getMethod("rotateYXZ", float.class, float.class, float.class);
                method.invoke(q, y, x, z);
                return q;
            } catch (Throwable ignored) { }
            return q;
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean renderPaperDollFallback(GuiGraphics graphics, Identifier tex, int x, int y, int w, int h) {
        if (graphics == null || tex == null) return false;
        int pixelScale = Math.max(2, Math.min(8, Math.min(Math.max(1, w) / 18, Math.max(1, h) / 34)));
        int modelW = 16 * pixelScale;
        int modelH = 32 * pixelScale;
        int ox = x + Math.max(0, (w - modelW) / 2);
        int oy = y + Math.max(0, (h - modelH) / 2);

        Object pose = invokeNoArg(graphics, "pose");
        if (pose != null && pushMatrix(pose)) {
            try {
                translate(pose, ox, oy);
                scale(pose, pixelScale, pixelScale);
                drawDollUnit(graphics, tex, 0, 0);
                return true;
            } catch (Throwable ignored) {
                return false;
            } finally {
                popMatrix(pose);
            }
        }

        // Last fallback: try known scaled blits; if unavailable it still draws the selected skin parts at 1x.
        part(graphics, tex, ox + 4 * pixelScale, oy, 8, 8, 8, 8, pixelScale);
        part(graphics, tex, ox + 4 * pixelScale, oy + 8 * pixelScale, 20, 20, 8, 12, pixelScale);
        part(graphics, tex, ox, oy + 8 * pixelScale, 44, 20, 4, 12, pixelScale);
        part(graphics, tex, ox + 12 * pixelScale, oy + 8 * pixelScale, 36, 52, 4, 12, pixelScale);
        part(graphics, tex, ox + 4 * pixelScale, oy + 20 * pixelScale, 4, 20, 4, 12, pixelScale);
        part(graphics, tex, ox + 8 * pixelScale, oy + 20 * pixelScale, 20, 52, 4, 12, pixelScale);
        part(graphics, tex, ox + 4 * pixelScale, oy, 40, 8, 8, 8, pixelScale);
        return true;
    }

    private static boolean pushMatrix(Object pose) {
        return invokePose(pose, "pushMatrix") || invokePose(pose, "pushPose");
    }

    private static void popMatrix(Object pose) {
        if (!invokePose(pose, "popMatrix")) invokePose(pose, "popPose");
    }

    private static boolean translate(Object pose, float x, float y) {
        for (String name : new String[]{"translate"}) {
            if (invokePose(pose, name, x, y)) return true;
            if (invokePose(pose, name, (double) x, (double) y)) return true;
            if (invokePose(pose, name, x, y, 0.0F)) return true;
            if (invokePose(pose, name, (double) x, (double) y, 0.0D)) return true;
        }
        return false;
    }

    private static boolean scale(Object pose, float x, float y) {
        if (invokePose(pose, "scale", x, y)) return true;
        return invokePose(pose, "scale", x, y, 1.0F);
    }

    private static boolean invokePose(Object pose, String name, Object... args) {
        try {
            for (Method method : pose.getClass().getMethods()) {
                if (!name.equals(method.getName()) || method.getParameterCount() != args.length) continue;
                Class<?>[] types = method.getParameterTypes();
                Object[] adapted = new Object[args.length];
                boolean ok = true;
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (types[i] == float.class && arg instanceof Number n) adapted[i] = n.floatValue();
                    else if (types[i] == double.class && arg instanceof Number n) adapted[i] = n.doubleValue();
                    else if (types[i] == int.class && arg instanceof Number n) adapted[i] = n.intValue();
                    else if (arg == null || types[i].isInstance(arg)) adapted[i] = arg;
                    else { ok = false; break; }
                }
                if (!ok) continue;
                method.invoke(pose, adapted);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static void drawDollUnit(GuiGraphics graphics, Identifier tex, int ox, int oy) {
        partClassic(graphics, tex, ox + 4, oy, 8, 8, 8, 8);
        partClassic(graphics, tex, ox + 4, oy + 8, 20, 20, 8, 12);
        partClassic(graphics, tex, ox, oy + 8, 44, 20, 4, 12);
        partClassic(graphics, tex, ox + 12, oy + 8, 36, 52, 4, 12);
        partClassic(graphics, tex, ox + 4, oy + 20, 4, 20, 4, 12);
        partClassic(graphics, tex, ox + 8, oy + 20, 20, 52, 4, 12);
        partClassic(graphics, tex, ox + 4, oy, 40, 8, 8, 8);
        partClassic(graphics, tex, ox + 4, oy + 8, 20, 36, 8, 12);
        partClassic(graphics, tex, ox, oy + 8, 44, 36, 4, 12);
        partClassic(graphics, tex, ox + 12, oy + 8, 52, 52, 4, 12);
    }

    private static void part(GuiGraphics graphics, Identifier texture, int x, int y, int u, int v, int sw, int sh, int scale) {
        int dw = Math.max(1, sw * scale);
        int dh = Math.max(1, sh * scale);
        if (tryScaledBlit(graphics, texture, x, y, dw, dh, u, v, sw, sh)) return;
        partClassic(graphics, texture, x, y, u, v, sw, sh);
    }

    private static void partClassic(GuiGraphics graphics, Identifier texture, int x, int y, int u, int v, int sw, int sh) {
        graphics.blit(texture, x, y, u, v, sw, sh, 64, 64);
    }

    private static boolean tryScaledBlit(GuiGraphics graphics, Identifier texture, int x, int y, int dw, int dh, int u, int v, int sw, int sh) {
        try {
            for (Method method : graphics.getClass().getMethods()) {
                if (!"blit".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 11
                        && types[0].isInstance(texture)
                        && types[1] == int.class && types[2] == int.class
                        && types[3] == int.class && types[4] == int.class
                        && (types[5] == float.class || types[5] == int.class)
                        && (types[6] == float.class || types[6] == int.class)
                        && types[7] == int.class && types[8] == int.class
                        && types[9] == int.class && types[10] == int.class) {
                    Object uu = types[5] == int.class ? Integer.valueOf(u) : Float.valueOf((float) u);
                    Object vv = types[6] == int.class ? Integer.valueOf(v) : Float.valueOf((float) v);
                    method.invoke(graphics, texture, x, y, dw, dh, uu, vv, sw, sh, 64, 64);
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }
}
