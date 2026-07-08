package ua.homecraft.guild.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.homecraft.guild.blockentity.GuildTotemBlockEntity;

public class GuildTotemBlockEntityRenderer implements BlockEntityRenderer<GuildTotemBlockEntity, GuildTotemBlockEntityRenderer.State> {
    public GuildTotemBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(GuildTotemBlockEntity blockEntity, State state, float partialTick, Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        long seed = blockEntity.getBlockPos().asLong();
        state.phase = (float) (((seed & 1023L) / 1023.0D) * (Math.PI * 2.0D));
        state.speed = 0.0145F + (float) (((seed >> 10) & 15L) * 0.00035F);
        state.rotationSpeed = 0.16F + (float) (((seed >> 14) & 7L) * 0.012F);
        state.age = (blockEntity.getLevel() == null ? 0.0F : blockEntity.getLevel().getGameTime()) + partialTick;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState) {
        // The totem is now rendered by the single supplied block model only.
        // No extra helper/crystal block is submitted here, so there are no duplicate visual layers.
    }

    public static class State extends BlockEntityRenderState {
        public float age;
        public float phase;
        public float speed;
        public float rotationSpeed;
    }
}
