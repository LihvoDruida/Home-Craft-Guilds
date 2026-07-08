package ua.homecraft.guild.client.renderer;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import ua.homecraft.guild.client.GuildClientLayers;
import ua.homecraft.guild.client.skin.GuildNpcSkins;
import ua.homecraft.guild.client.npc.GuildNpcClientCache;
import ua.homecraft.guild.entity.GuildRegistrarEntity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public class GuildRegistrarRenderer extends HumanoidMobRenderer<GuildRegistrarEntity, AvatarRenderState, PlayerModel> {
    /**
     * Minecraft 1.21.11 ties PlayerModel to AvatarRenderState directly.
     * Do not subclass AvatarRenderState for this renderer: PlayerModel is not
     * assignable to HumanoidModel<CustomAvatarState>. Keep per-state NPC skin
     * metadata here and let the vanilla player model render the modern 64x64 skin.
     */
    private static final Map<AvatarRenderState, GuildNpcSkins.SkinProfile> STATE_SKINS =
            Collections.synchronizedMap(new WeakHashMap<>());

    public GuildRegistrarRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(GuildClientLayers.GUILD_REGISTRAR), false), 0.5F);
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        GuildNpcSkins.SkinProfile profile = STATE_SKINS.get(state);
        return profile != null ? profile.texture() : GuildNpcSkins.guildRegistrar().texture();
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(GuildRegistrarEntity entity, AvatarRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);

        // Resolve the authoritative skin by NPC key, not by a global/default entity state.
        // The key can come from SynchedData, persistent tags, or the cached position map. This is
        // required for v133-v138 entities that were saved without custom SynchedData and then
        // reloaded as guild_master while still physically being trader_* NPCs.
        String npcKey = GuildNpcClientCache.keyForEntity(entity);
        String cachedSkin = GuildNpcClientCache.skinIdForEntity(entity);
        long cacheRevision = GuildNpcClientCache.revisionForEntity(entity);
        long entityRevision = Math.max(0L, entity.homecraft$npcRevision());
        String entitySkin = entity.homecraft$npcSkinId();

        GuildNpcSkins.SkinProfile profile;
        if (cachedSkin != null && !cachedSkin.isBlank()
                && (entityRevision <= 0L || cacheRevision >= entityRevision || !GuildNpcSkins.isKnown(entitySkin))) {
            profile = GuildNpcSkins.resolve(cachedSkin);
        } else {
            profile = GuildNpcSkins.resolve(entity);
        }

        // Persist entity metadata only after the render skin has been resolved. The cache refuses
        // to downgrade a server snapshot with a zero/default entity revision, so this cannot roll
        // the visible NPC back to Steve/guild_registrar during first-frame render.
        GuildNpcClientCache.acceptEntityMetadata(entity);
        applyResolvedSkin(state, profile);
    }

    private static void applyResolvedSkin(AvatarRenderState state, GuildNpcSkins.SkinProfile profile) {
        if (state == null) return;
        GuildNpcSkins.SkinProfile safe = profile == null ? GuildNpcSkins.guildRegistrar() : profile;
        STATE_SKINS.put(state, safe);

        // In 1.21.11 the player model reads AvatarRenderState.skin. Set exactly one authoritative
        // PlayerSkin here. Do not use the old reflective field sweep: it can leave stale texture
        // aliases on the render state and visually look like a custom skin is being drawn over
        // a default/previous skin. getTextureLocation(state) and state.skin now point to the
        // same resolved texture every frame.
        // vanilla extraction replaces the default player skin instead of drawing our texture as
        // an extra layer over a fallback state.
        PlayerModelType modelType = safe.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        Identifier texture = safe.texture();
        state.skin = new PlayerSkin(new ClientAsset.ResourceTexture(texture, texture), null, null, modelType, false);

        state.isSpectator = false;
        state.showHat = true;
        state.showJacket = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showCape = false;
        state.showExtraEars = false;
    }
}
