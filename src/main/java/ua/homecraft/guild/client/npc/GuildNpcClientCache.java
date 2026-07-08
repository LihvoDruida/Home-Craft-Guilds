package ua.homecraft.guild.client.npc;

import ua.homecraft.guild.client.HomeCraftGuildI18n;

import net.minecraft.client.Minecraft;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.client.skin.GuildNpcSkins;
import ua.homecraft.guild.entity.GuildRegistrarEntity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.HashMap;
import java.util.Set;

/**
 * Client-side cache of authoritative NPC metadata.
 *
 * The server stays authoritative. The client stores the last known snapshot and keeps using it
 * until the server explicitly sends a changed NPC sync packet. This avoids skin/name flicker and
 * lets the renderer recover from early fallback-to-Steve frames before full entity sync lands.
 */
public final class GuildNpcClientCache {
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<String, SkinEntry> SKINS = new LinkedHashMap<>();
    private static long revision = 0L;
    private static long skinRevision = 0L;
    private static final Map<String, String> ENTITY_RENDER_SIGNATURES = new HashMap<>();
    private static final Map<String, Long> ENTITY_RENDER_LAST_NS = new HashMap<>();
    private static boolean loaded = false;
    private static String loadedScope = "";
    private static boolean seenNpcSnapshotThisSession = false;

    private GuildNpcClientCache() {}

    /**
     * Accepts the lightweight visual snapshot appended to GuildVisualSyncPayload.
     * Format: npc2|revision|key|kind|enabled|dimension|x|y|z|yaw|skin|name
     */
    public static synchronized void acceptVisualSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return;
        ensureLoaded();
        boolean skinRowsChanged = acceptSkinRows(snapshot);

        long incomingRevision = highestVisualRevision(snapshot);
        if (incomingRevision > 0L && incomingRevision < revision && seenNpcSnapshotThisSession) {
            // Do not let a delayed packet from the current connection restore old/default textures
            // over a newer cached skin. The first authoritative packet after login is always accepted
            // even if its revision is lower than the persisted disk cache, because servers/configs can
            // be reset while the client cache survives.
            if (skinRowsChanged) save();
            return;
        }

        long previousRevision = revision;
        boolean changed = false;
        Map<String, Entry> next = new LinkedHashMap<>(ENTRIES);
        boolean sawNpcRow = false;
        boolean explicitEmpty = false;

        for (String row : snapshot.split(";")) {
            if (row == null || row.isBlank() || !row.startsWith("npc2|")) continue;
            sawNpcRow = true;
            String[] p = row.split("\\|", -1);
            if (p.length < 5) continue;
            long rowRevision = parseLong(p[1], revision);
            if (rowRevision > revision) revision = rowRevision;
            String key = normalize(p.length > 2 ? p[2] : "");
            boolean enabled = p.length > 4 && ("1".equals(p[4]) || "true".equalsIgnoreCase(p[4]));

            // Server uses npc2|rev||system|0 to mean "there are currently no enabled NPCs".
            // Old code removed only guild_master and left stale trader skins in the cache.
            if (key.isBlank() && !enabled) {
                if (!next.isEmpty()) changed = true;
                next.clear();
                explicitEmpty = true;
                continue;
            }
            if (key.isBlank()) key = "guild_master";
            if (!enabled) {
                if (next.remove(key) != null) changed = true;
                continue;
            }

            Entry e = new Entry(
                    key,
                    p.length > 3 ? normalize(p[3]) : "system",
                    true,
                    rowRevision > 0L ? rowRevision : incomingRevision,
                    p.length > 5 ? safe(p[5]) : "minecraft:overworld",
                    p.length > 6 ? parseDouble(p[6], 0.5D) : 0.5D,
                    p.length > 7 ? parseDouble(p[7], 64D) : 64D,
                    p.length > 8 ? parseDouble(p[8], 0.5D) : 0.5D,
                    p.length > 9 ? (float) parseDouble(p[9], 0D) : 0F,
                    p.length > 10 ? normalize(p[10]) : "guild_registrar",
                    p.length > 11 ? safe(p[11]) : HomeCraftGuildI18n.t("npc.homecraftguild.registrar")
            );
            Entry prev = next.put(key, e);
            if (!Objects.equals(e, prev)) changed = true;
        }

        if (sawNpcRow) seenNpcSnapshotThisSession = true;
        if (changed || skinRowsChanged || explicitEmpty || (sawNpcRow && revision != previousRevision)) {
            ENTRIES.clear();
            ENTRIES.putAll(next);
            if (incomingRevision > revision) revision = incomingRevision;
            save();
        }
    }


    public static synchronized void acceptNpcSnapshot(String snapshot) {
        acceptVisualSnapshot(snapshot);
    }

    public static synchronized void acceptSkinRegistrySnapshot(String snapshot) {
        acceptVisualSnapshot(snapshot);
    }

    /**
     * Accepts the full OP admin snapshot. This arrives immediately after save/skin changes, often
     * before nearby tracked entities resend their SynchedEntityData. Using it here removes the
     * one-frame/default-skin flicker in the world renderer and preview.
     * Format row: npc|key|enabled|kind|system|skin|dimension|x|y|z|yaw|name|trades
     */
    public static synchronized void acceptAdminSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return;
        ensureLoaded();
        boolean skinRowsChanged = acceptSkinRows(snapshot);

        long incomingRevision = adminRevision(snapshot);
        if (incomingRevision > 0L && incomingRevision < revision && seenNpcSnapshotThisSession) {
            if (skinRowsChanged) save();
            return;
        }

        int schema = adminSchemaVersion(snapshot);
        long previousRevision = revision;
        Map<String, Entry> next = new LinkedHashMap<>();
        boolean sawNpc = false;
        for (String line : snapshot.split("\\n")) {
            if (line == null || line.isBlank()) continue;
            if (!line.startsWith("npc|")) continue;
            String[] p = line.split("\\|", -1);
            if (p.length < 12) continue;
            sawNpc = true;
            String key = normalize(p[1]);
            if (key.isBlank()) continue;
            boolean enabled = "1".equals(p[2]) || "true".equalsIgnoreCase(p[2]);
            if (!enabled) continue;
            next.put(key, new Entry(
                    key,
                    normalize(p[3]),
                    true,
                    incomingRevision > 0L ? incomingRevision : revision,
                    safe(p[6]),
                    parseDouble(p[7], 0.5D),
                    parseDouble(p[8], 64D),
                    parseDouble(p[9], 0.5D),
                    (float) parseDouble(p[10], 0D),
                    normalize(p[5]),
                    (schema >= 3 ? decodeSnapshotField(p[11]) : safe(p[11]))
            ));
        }
        if (sawNpc) seenNpcSnapshotThisSession = true;
        if (!sawNpc) {
            if (skinRowsChanged) save();
            return;
        }
        if (incomingRevision > revision) revision = incomingRevision;
        if (!next.equals(ENTRIES) || skinRowsChanged || revision != previousRevision) {
            ENTRIES.clear();
            ENTRIES.putAll(next);
            save();
        }
    }

    /**
     * Persists authoritative metadata that arrived through SynchedEntityData.
     * This makes the next login render the correct skin even before the first network snapshot.
     */
    public static synchronized void acceptEntityMetadata(GuildRegistrarEntity entity) {
        if (entity == null) return;
        ensureLoaded();
        String key = keyForEntity(entity);
        if (key == null || key.isBlank()) return;
        long entityRevision = Math.max(0L, entity.homecraft$npcRevision());
        String signature = key + "|" + entityRevision + "|" + normalize(entity.homecraft$npcKind()) + "|" + normalize(entity.homecraft$npcSkinId()) + "|" + safe(entity.homecraft$displayName());
        long nowNs = System.nanoTime();
        String previousSignature = ENTITY_RENDER_SIGNATURES.get(key);
        Long previousNs = ENTITY_RENDER_LAST_NS.get(key);
        if (signature.equals(previousSignature) && previousNs != null && nowNs - previousNs.longValue() < 1_000_000_000L) return;
        ENTITY_RENDER_SIGNATURES.put(key, signature);
        ENTITY_RENDER_LAST_NS.put(key, nowNs);
        Entry previous = ENTRIES.get(key);
        String entitySkin = normalize(entity.homecraft$npcSkinId());
        if (previous != null) {
            if (entityRevision <= 0L || previous.revision > entityRevision) {
                // A cached server snapshot is newer or the entity has not received authoritative
                // SynchedData yet. Do not let a default-loaded legacy entity overwrite the
                // persisted npcKey->skinId mapping used for first-frame rendering.
                return;
            }
            if (previous.revision == entityRevision && !Objects.equals(previous.skinId, entitySkin)) {
                // Equal-revision entity metadata can arrive from a just-loaded default entity before
                // the server re-applies config metadata. The server snapshot/disk cache is more
                // authoritative for skin assignment, so do not replace trader_weapons->medieval_knight
                // with guild_master/guild_registrar only because the entity rendered once.
                if (isDefaultishSkin(entitySkin) || !GuildNpcSkins.isKnown(entitySkin)) return;
            }
        }

        String dimension = previous == null ? "minecraft:overworld" : previous.dimension;
        try {
            if (entity.level() != null && entity.level().dimension() != null) dimension = dimensionId(entity.level().dimension());
        } catch (Throwable ignored) { }

        // Renderer-side metadata persistence must not write every frame. Client idle animation mutates
        // yaw locally, so keep the cached position/yaw when it already exists and only update the
        // authoritative fields: kind, skin, display name, enabled flag and revision.
        Entry next = new Entry(
                key,
                normalize(entity.homecraft$npcKind()),
                true,
                entityRevision > 0L ? entityRevision : revision,
                dimension,
                previous == null ? entity.getX() : previous.x,
                previous == null ? entity.getY() : previous.y,
                previous == null ? entity.getZ() : previous.z,
                previous == null ? entity.getYRot() : previous.yaw,
                entitySkin,
                safe(entity.homecraft$displayName())
        );
        if (!Objects.equals(previous, next)) {
            ENTRIES.put(key, next);
            revision = Math.max(revision, next.revision);
            cacheLocalSkin(next.skinId);
            save();
        }
    }

    public static synchronized String skinIdFor(String key) {
        ensureLoaded();
        Entry e = ENTRIES.get(normalize(key));
        return e == null ? null : e.skinId;
    }

    public static synchronized String skinIdForEntity(GuildRegistrarEntity entity) {
        ensureLoaded();
        String key = keyForEntity(entity);
        Entry e = ENTRIES.get(normalize(key));
        if (e != null && e.skinId != null && !e.skinId.isBlank()) return e.skinId;
        if (entity != null) return normalize(entity.homecraft$npcSkinId());
        return null;
    }

    public static synchronized long revisionForEntity(GuildRegistrarEntity entity) {
        ensureLoaded();
        String key = keyForEntity(entity);
        Entry e = ENTRIES.get(normalize(key));
        return e == null ? 0L : e.revision;
    }

    public static synchronized String keyForEntity(GuildRegistrarEntity entity) {
        ensureLoaded();
        if (entity == null) return "";
        String tagged = keyFromTags(entity);
        if (!tagged.isBlank()) return tagged;
        String dataKey = normalize(entity.homecraft$npcKey());
        if (!dataKey.isBlank() && !"guild_master".equals(dataKey)) return dataKey;
        String nearest = nearestCachedKey(entity);
        if (!nearest.isBlank()) return nearest;
        return dataKey;
    }


    public static synchronized long revisionFor(String key) {
        ensureLoaded();
        Entry e = ENTRIES.get(normalize(key));
        return e == null ? 0L : e.revision;
    }


    private static String keyFromTags(GuildRegistrarEntity entity) {
        if (entity == null) return "";
        try {
            for (String tag : entity.getTags()) {
                if (tag != null && tag.startsWith("homecraft_npc_key_")) {
                    String key = normalize(tag.substring("homecraft_npc_key_".length()));
                    if (!key.isBlank()) return key;
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }


    private static boolean isDefaultishSkin(String skin) {
        String s = normalize(skin);
        return s.isBlank() || "guild_master".equals(s) || "guild_registrar".equals(s) || "default".equals(s) || "steve".equals(s);
    }

    private static String nearestCachedKey(GuildRegistrarEntity entity) {
        if (entity == null || ENTRIES.isEmpty()) return "";
        String dim = "";
        try {
            if (entity.level() != null && entity.level().dimension() != null) dim = dimensionId(entity.level().dimension());
        } catch (Throwable ignored) { }
        double best = Double.MAX_VALUE;
        String bestKey = "";
        for (Entry e : ENTRIES.values()) {
            if (e == null || !e.enabled) continue;
            if (dim != null && !dim.isBlank() && e.dimension != null && !e.dimension.isBlank() && !dim.equals(e.dimension)) continue;
            double dx = entity.getX() - e.x;
            double dy = entity.getY() - e.y;
            double dz = entity.getZ() - e.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best && d <= 16.0D) {
                best = d;
                bestKey = e.key;
            }
        }
        return bestKey;
    }

    public static synchronized void clearRuntimeCache() {
        ENTRIES.clear();
        SKINS.clear();
        revision = 0L;
        skinRevision = 0L;
        ENTITY_RENDER_SIGNATURES.clear();
        ENTITY_RENDER_LAST_NS.clear();
        loaded = false;
        loadedScope = "";
        seenNpcSnapshotThisSession = false;
    }

    public static synchronized String displayNameFor(String key) {
        ensureLoaded();
        Entry e = ENTRIES.get(normalize(key));
        return e == null ? null : e.name;
    }

    public static synchronized long revision() {
        ensureLoaded();
        return revision;
    }

    private static long highestVisualRevision(String snapshot) {
        long max = -1L;
        if (snapshot == null) return max;
        for (String row : snapshot.split(";")) {
            if (row == null || !row.startsWith("npc2|")) continue;
            String[] p = row.split("\\|", -1);
            if (p.length > 1) max = Math.max(max, parseLong(p[1], -1L));
        }
        return max;
    }

    private static int adminSchemaVersion(String snapshot) {
        if (snapshot == null) return 1;
        for (String line : snapshot.split("\n")) {
            if (line != null && line.startsWith("schemaVersion=")) {
                try { return Integer.parseInt(line.substring("schemaVersion=".length()).trim()); } catch (Exception ignored) { return 1; }
            }
        }
        return 1;
    }

    private static String decodeSnapshotField(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return encoded;
        }
    }

    private static long adminRevision(String snapshot) {
        if (snapshot == null) return -1L;
        for (String line : snapshot.split("\\n")) {
            if (line != null && line.startsWith("revision=")) {
                return parseLong(line.substring("revision=".length()), -1L);
            }
        }
        return -1L;
    }

    private static void ensureLoaded() {
        String scope = cacheScope();
        if (loaded && Objects.equals(scope, loadedScope)) return;
        if (!Objects.equals(scope, loadedScope)) {
            ENTRIES.clear();
            SKINS.clear();
            revision = 0L;
        }
        loaded = true;
        loadedScope = scope;
        Path file = cacheFile();
        if (file == null || !Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
            if (!"v142".equals(p.getProperty("cacheSchema", ""))) {
                cacheLocalKnownSkins(true);
                return;
            }
            revision = parseLong(p.getProperty("revision"), 0L);
            skinRevision = parseLong(p.getProperty("skinRevision"), revision);
            for (String name : p.stringPropertyNames()) {
                if (!name.startsWith("skin.") || !name.endsWith(".texture")) continue;
                String id = normalize(name.substring(5, name.length() - 8));
                if (id.isBlank()) continue;
                SKINS.put(id, new SkinEntry(
                        id,
                        Boolean.parseBoolean(p.getProperty("skin." + id + ".slim", "false")),
                        p.getProperty("skin." + id + ".texture", defaultSkinTexture(id)),
                        parseLong(p.getProperty("skin." + id + ".revision"), revision)
                ));
            }
            if (SKINS.isEmpty()) cacheLocalKnownSkins(false);
            for (String name : p.stringPropertyNames()) {
                if (!name.startsWith("npc.") || !name.endsWith(".skin")) continue;
                String key = normalize(name.substring(4, name.length() - 5));
                if (key.isBlank()) continue;
                ENTRIES.put(key, new Entry(
                        key,
                        normalize(p.getProperty("npc." + key + ".kind", "system")),
                        Boolean.parseBoolean(p.getProperty("npc." + key + ".enabled", "true")),
                        parseLong(p.getProperty("npc." + key + ".revision"), revision),
                        p.getProperty("npc." + key + ".dimension", "minecraft:overworld"),
                        parseDouble(p.getProperty("npc." + key + ".x"), 0.5D),
                        parseDouble(p.getProperty("npc." + key + ".y"), 64D),
                        parseDouble(p.getProperty("npc." + key + ".z"), 0.5D),
                        (float) parseDouble(p.getProperty("npc." + key + ".yaw"), 0D),
                        normalize(p.getProperty("npc." + key + ".skin", "guild_registrar")),
                        p.getProperty("npc." + key + ".name", key)
                ));
            }
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.debug("Failed to load NPC client cache", t);
        }
    }

    private static void save() {
        Path file = cacheFile();
        if (file == null) return;
        try { Files.createDirectories(file.getParent()); } catch (IOException ignored) {}
        Properties p = new Properties();
        p.setProperty("cacheSchema", "v142");
        p.setProperty("revision", String.valueOf(revision));
        p.setProperty("skinRevision", String.valueOf(skinRevision));
        cacheLocalKnownSkins(false);
        for (SkinEntry skin : SKINS.values()) {
            p.setProperty("skin." + skin.id + ".slim", String.valueOf(skin.slim));
            p.setProperty("skin." + skin.id + ".texture", skin.texture);
            p.setProperty("skin." + skin.id + ".revision", String.valueOf(skin.revision));
        }
        for (Entry e : ENTRIES.values()) {
            p.setProperty("npc." + e.key + ".kind", e.kind);
            p.setProperty("npc." + e.key + ".enabled", String.valueOf(e.enabled));
            p.setProperty("npc." + e.key + ".revision", String.valueOf(e.revision));
            p.setProperty("npc." + e.key + ".dimension", e.dimension);
            p.setProperty("npc." + e.key + ".x", String.format(Locale.ROOT, "%.3f", e.x));
            p.setProperty("npc." + e.key + ".y", String.format(Locale.ROOT, "%.3f", e.y));
            p.setProperty("npc." + e.key + ".z", String.format(Locale.ROOT, "%.3f", e.z));
            p.setProperty("npc." + e.key + ".yaw", String.format(Locale.ROOT, "%.2f", e.yaw));
            p.setProperty("npc." + e.key + ".skin", e.skinId);
            p.setProperty("npc." + e.key + ".name", e.name);
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(writer, "Home Craft guild NPC cache");
        } catch (Throwable t) {
            HomeCraftGuildMod.LOGGER.debug("Failed to save NPC client cache", t);
        }
    }

    private static boolean acceptSkinRows(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return false;
        boolean changed = false;
        long previousSkinRevision = skinRevision;
        for (String row : snapshot.split("[;\n]")) {
            if (row == null || row.isBlank()) continue;
            if (row.startsWith("skins2|")) {
                String[] p = row.split("\\|", -1);
                long rowRevision = p.length > 1 ? parseLong(p[1], skinRevision) : skinRevision;
                if (rowRevision > skinRevision) skinRevision = rowRevision;
                String body = p.length > 2 ? p[2] : "";
                Set<String> serverSkinIds = skinIdsFromBody(body);
                changed |= acceptSkinBody(body, rowRevision, true);
                if (!serverSkinIds.isEmpty()) {
                    java.util.Iterator<String> it = SKINS.keySet().iterator();
                    while (it.hasNext()) {
                        String existing = it.next();
                        if (!serverSkinIds.contains(existing)) {
                            it.remove();
                            changed = true;
                        }
                    }
                }
            } else if (row.startsWith("skins=")) {
                changed |= acceptSkinBody(row.substring("skins=".length()), skinRevision, false);
            }
        }
        if (skinRevision != previousSkinRevision) changed = true;
        if (changed) cacheLocalKnownSkins(false);
        return changed;
    }

    private static Set<String> skinIdsFromBody(String body) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        if (body == null || body.isBlank()) return ids;
        for (String raw : body.split(",")) {
            if (raw == null || raw.isBlank()) continue;
            String id = raw;
            int tilde = raw.indexOf('~');
            if (tilde >= 0) id = raw.substring(0, tilde);
            id = normalize(id);
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }

    private static boolean acceptSkinBody(String body, long rowRevision, boolean detailed) {
        if (body == null || body.isBlank()) return false;
        boolean changed = false;
        for (String raw : body.split(",")) {
            if (raw == null || raw.isBlank()) continue;
            String id;
            boolean slim = false;
            String texture;
            if (detailed && raw.contains("~")) {
                String[] parts = raw.split("~", -1);
                id = normalize(parts.length > 0 ? parts[0] : "");
                slim = parts.length > 1 && ("1".equals(parts[1]) || "true".equalsIgnoreCase(parts[1]));
                texture = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : defaultSkinTexture(id);
            } else {
                id = normalize(raw);
                GuildNpcSkins.SkinProfile profile = GuildNpcSkins.resolve(id);
                slim = profile.slim();
                texture = String.valueOf(profile.texture());
            }
            if (id.isBlank()) continue;
            SkinEntry next = new SkinEntry(id, slim, texture, Math.max(0L, rowRevision));
            SkinEntry prev = SKINS.get(id);
            if (!Objects.equals(prev, next)) {
                SKINS.put(id, next);
                changed = true;
            }
        }
        return changed;
    }

    private static void cacheLocalSkin(String id) {
        id = normalize(id);
        if (id.isBlank()) return;
        GuildNpcSkins.SkinProfile profile = GuildNpcSkins.resolve(id);
        SKINS.put(id, new SkinEntry(id, profile.slim(), String.valueOf(profile.texture()), skinRevision));
    }

    private static void cacheLocalKnownSkins(boolean overwrite) {
        try {
            for (GuildNpcSkins.SkinProfile profile : GuildNpcSkins.registrySnapshot().values()) {
                if (profile == null || profile.id() == null || profile.id().isBlank()) continue;
                String id = normalize(profile.id());
                if (!overwrite && SKINS.containsKey(id)) continue;
                SKINS.put(id, new SkinEntry(id, profile.slim(), String.valueOf(profile.texture()), skinRevision));
            }
        } catch (Throwable ignored) { }
    }

    private static String defaultSkinTexture(String id) {
        id = normalize(id);
        if (id.isBlank()) id = "guild_registrar";
        try { return String.valueOf(GuildNpcSkins.resolve(id).texture()); } catch (Throwable ignored) { }
        return HomeCraftGuildMod.MOD_ID + ":textures/entity/npc/" + id + ".png";
    }

    private static Path cacheFile() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return null;
            return mc.gameDirectory.toPath().resolve("config")
                    .resolve("homecraft-guild-npc-cache-" + cacheScope() + ".properties");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String cacheScope() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return "unknown";
            StringBuilder b = new StringBuilder();
            b.append(HomeCraftGuildMod.MOD_ID);
            try {
                Object serverData = mc.getClass().getMethod("getCurrentServer").invoke(mc);
                if (serverData != null) {
                    Object ip = serverData.getClass().getField("ip").get(serverData);
                    b.append('_').append(ip == null ? "server" : String.valueOf(ip));
                } else {
                    b.append("_singleplayer");
                }
            } catch (Throwable ignored) {
                b.append("_singleplayer");
            }
            b.append("_npc_registry_v3");
            return b.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String dimensionId(Object key) {
        String raw = String.valueOf(key == null ? "" : key).trim();
        if (raw.isEmpty()) return "minecraft:overworld";
        String prefix = "ResourceKey[minecraft:dimension / ";
        if (raw.startsWith(prefix) && raw.endsWith("]")) {
            return raw.substring(prefix.length(), raw.length() - 1).toLowerCase(Locale.ROOT);
        }
        int sep = raw.lastIndexOf(" / ");
        if (sep >= 0 && raw.endsWith("]")) {
            return raw.substring(sep + 3, raw.length() - 1).toLowerCase(Locale.ROOT);
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static double parseDouble(String s, double fallback) {
        try { return s == null ? fallback : Double.parseDouble(s.trim()); } catch (Exception ignored) { return fallback; }
    }

    private static long parseLong(String s, long fallback) {
        try { return s == null ? fallback : Long.parseLong(s.trim()); } catch (Exception ignored) { return fallback; }
    }

    private record Entry(String key, String kind, boolean enabled, long revision, String dimension, double x, double y, double z, float yaw, String skinId, String name) {}
    private record SkinEntry(String id, boolean slim, String texture, long revision) {}
}
