package ua.homecraft.guild.client.skin;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.entity.GuildNpcSkinnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class GuildNpcSkins {
    /**
     * Skin registry for HomeCraft guild NPCs.
     *
     * To add a new NPC skin in the future:
     * Skins are addon-owned only. The server sends skinId, not external image data.
     * To add a future skin, include a 64x64 PNG in assets/homecraftguild/textures/entity/npc/
     * and register its id here. Admin UI may select only ids from this registry.
     */
    public record SkinProfile(String id, Identifier texture, boolean slim) {}

    private static final Map<String, SkinProfile> REGISTRY = new LinkedHashMap<>();
    private static final SkinProfile DEFAULT_PROFILE;
    private static final SkinProfile GUILD_REGISTRAR;

    static {
        DEFAULT_PROFILE = registerDefaults();
        GUILD_REGISTRAR = resolve("guild_registrar");
    }

    private GuildNpcSkins() {}

    public static SkinProfile guildRegistrar() {
        return GUILD_REGISTRAR;
    }

    public static SkinProfile registerLocalSkin(String id, boolean slim) {
        return register(id, slim, "textures/entity/npc/" + normalize(id) + ".png");
    }

    public static SkinProfile resolve(String id) {
        if (id == null || id.isBlank()) return DEFAULT_PROFILE;
        return REGISTRY.getOrDefault(normalize(id), DEFAULT_PROFILE);
    }

    public static Map<String, SkinProfile> registrySnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(REGISTRY));
    }

    public static boolean isKnown(String id) {
        return id != null && REGISTRY.containsKey(normalize(id));
    }

    public static SkinProfile resolve(GuildNpcSkinnable skinnable) {
        if (skinnable == null) return DEFAULT_PROFILE;
        SkinProfile profile = resolve(skinnable.homecraft$npcSkinId());
        if (profile.slim() == skinnable.homecraft$npcSlimArms()) return profile;
        return new SkinProfile(profile.id(), profile.texture(), skinnable.homecraft$npcSlimArms());
    }

    public static void applyToAvatarState(AvatarRenderState state, SkinProfile profile) {
        if (state == null || profile == null) return;
        Identifier texture = profile.texture();
        // Minecraft 1.21.11 AvatarRenderState commonly stores a PlayerSkin object, not a raw Identifier.
        // If we only set Identifier-like fields, the renderer can silently fall back to the vanilla Steve skin.
        tryApplyPlayerSkinState(state, profile);
        applyIdentifier(state, texture,
                "skinTexture", "skin", "texture", "playerSkin", "skinLocation", "textureLocation");
        applyBoolean(state, profile.slim(), "slim", "modelSlim", "isSlim");
    }

    private static void tryApplyPlayerSkinState(Object state, SkinProfile profile) {
        if (state == null || profile == null) return;
        Object playerSkin = createPlayerSkin(profile);
        if (playerSkin == null) return;

        Class<?> clazz = state.getClass();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                String typeName = field.getType().getName().toLowerCase(Locale.ROOT);
                String fieldName = field.getName().toLowerCase(Locale.ROOT);
                if (!typeName.endsWith("playerskin") && !fieldName.equals("skin") && !fieldName.contains("playerskin")) continue;
                if (!field.getType().isInstance(playerSkin)) continue;
                try {
                    field.setAccessible(true);
                    field.set(state, playerSkin);
                    return;
                } catch (Throwable ignored) { }
            }
            clazz = clazz.getSuperclass();
        }

        for (Method method : state.getClass().getMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!(name.contains("skin") || name.contains("playerskin"))) continue;
            if (method.getParameterCount() != 1) continue;
            if (!method.getParameterTypes()[0].isInstance(playerSkin)) continue;
            try {
                method.setAccessible(true);
                method.invoke(state, playerSkin);
                return;
            } catch (Throwable ignored) { }
        }
    }

    private static Object createPlayerSkin(SkinProfile profile) {
        try {
            Class<?> skinClass = Class.forName("net.minecraft.client.resources.PlayerSkin");
            for (java.lang.reflect.Constructor<?> constructor : skinClass.getDeclaredConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                Object[] args = new Object[types.length];
                int identifiers = 0;
                boolean supported = true;
                for (int i = 0; i < types.length; i++) {
                    Class<?> type = types[i];
                    if (Identifier.class.isAssignableFrom(type)) {
                        args[i] = identifiers++ == 0 ? profile.texture() : null;
                    } else if (type == String.class) {
                        args[i] = null;
                    } else if (type == Boolean.TYPE || type == Boolean.class) {
                        args[i] = Boolean.FALSE;
                    } else if (type.isEnum()) {
                        args[i] = chooseSkinModel(type, profile.slim());
                    } else if (java.util.Optional.class.isAssignableFrom(type)) {
                        args[i] = java.util.Optional.empty();
                    } else {
                        args[i] = null;
                    }
                }
                if (!supported) continue;
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(args);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object chooseSkinModel(Class<?> enumType, boolean slim) {
        Object[] values = enumType.getEnumConstants();
        if (values == null || values.length == 0) return null;
        String want = slim ? "SLIM" : "WIDE";
        for (Object value : values) {
            String name = String.valueOf(value).toUpperCase(Locale.ROOT);
            if (name.contains(want) || (!slim && name.contains("CLASSIC"))) return value;
        }
        return values[0];
    }

    private static SkinProfile registerDefaults() {
        // v0.1.136: the bundled skin registry is intentionally one-id-per-texture.
        // There is no separate "default" duplicate; missing/old ids fall back to guild_registrar.
        SkinProfile fallback = register("guild_registrar", false, "textures/entity/npc/guild_registrar.png");
        register("guild_master", false, "textures/entity/npc/guild_master.png");
        register("trader_armor", false, "textures/entity/npc/trader_armor.png");
        register("medieval_armor", false, "textures/entity/npc/medieval_armor.png");
        register("medieval_knight", false, "textures/entity/npc/medieval_knight.png");
        register("fighter_guy", false, "textures/entity/npc/fighter_guy.png");
        register("boy_green", false, "textures/entity/npc/boy_green.png");
        return fallback;
    }

    private static SkinProfile register(String id, boolean slim, String texturePath) {
        SkinProfile profile = new SkinProfile(normalize(id), Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, texturePath), slim);
        REGISTRY.put(profile.id(), profile);
        return profile;
    }

    private static String normalize(String id) {
        return String.valueOf(id).trim().toLowerCase(Locale.ROOT);
    }

    private static void applyIdentifier(Object target, Identifier value, String... candidates) {
        if (value == null) return;
        if (tryMethods(target, value, candidates)) return;
        tryFields(target, Identifier.class, value, candidates);
    }

    private static void applyBoolean(Object target, boolean value, String... candidates) {
        if (tryMethods(target, value, candidates)) return;
        tryFields(target, Boolean.TYPE, value, candidates);
        tryFields(target, Boolean.class, value, candidates);
    }

    private static boolean tryMethods(Object target, Object value, String... candidates) {
        if (target == null) return false;
        Class<?> clazz = target.getClass();
        for (String candidate : candidates) {
            for (Method method : clazz.getMethods()) {
                if (!method.getName().equalsIgnoreCase(candidate)) continue;
                if (method.getParameterCount() != 1) continue;
                Class<?> type = method.getParameterTypes()[0];
                try {
                    if (value instanceof Identifier && Identifier.class.isAssignableFrom(type)) {
                        method.invoke(target, value);
                        return true;
                    }
                    if (value instanceof Boolean bool && (type == Boolean.TYPE || type == Boolean.class)) {
                        method.invoke(target, bool);
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static void tryFields(Object target, Class<?> targetType, Object value, String... candidates) {
        if (target == null) return;
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!targetType.isAssignableFrom(field.getType()) && !(field.getType().isPrimitive() && targetType == Boolean.TYPE && field.getType() == Boolean.TYPE)) {
                    continue;
                }
                String lowerName = field.getName().toLowerCase(Locale.ROOT);
                boolean nameMatch = false;
                for (String candidate : candidates) {
                    if (lowerName.contains(candidate.toLowerCase(Locale.ROOT))) {
                        nameMatch = true;
                        break;
                    }
                }
                if (!nameMatch) continue;
                try {
                    field.setAccessible(true);
                    field.set(target, value);
                } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
    }
}
