package comedymonkey.dailies.mixin.effects;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fire, water and a block in the face, which the game draws over the picture from its own player, drawn in playback
 * from the one whose eyes the view is through ({@link RecorderEyes}), and none where nobody's should show. The game
 * reads its player all through, so the screen effects are drawn here as it draws them, from the ridden player.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectsMixin {

    private static final ResourceLocation DAILIES$UNDERWATER = ResourceLocation.withDefaultNamespace("textures/misc/underwater.png");

    @Shadow
    private static void renderTex(TextureAtlasSprite sprite, PoseStack poseStack) {
        throw new AssertionError();
    }

    @Shadow
    private static void renderFire(Minecraft minecraft, PoseStack poseStack) {
        throw new AssertionError();
    }

    @Inject(method = "renderScreenEffect", at = @At("HEAD"), cancellable = true)
    private static void dailies$throughTheirEyes(Minecraft minecraft, PoseStack poseStack, CallbackInfo ci) {
        if (!RecorderEyes.inPlayback()) {
            return;
        }
        ci.cancel();
        try {
            LivingEntity subject = RecorderEyes.subject();
            if (subject == null || minecraft.level == null) {
                return;
            }
            Player player = subject instanceof Player one ? one : null;
            boolean block = false;
            BlockPos blocking = RecorderEyes.viewBlocking(subject);
            if (blocking != null) {
                block = true;
                BlockState state = subject.level().getBlockState(blocking);
                if (player == null || !ClientHooks.renderBlockOverlay(player, poseStack, RenderBlockScreenEffectEvent.OverlayType.BLOCK, state, blocking)) {
                    renderTex(minecraft.getBlockRenderer().getBlockModelShaper().getTexture(state, subject.level(), blocking), poseStack);
                }
            }
            boolean water = false;
            boolean fire = false;
            if (!subject.isSpectator()) {
                if (subject.isEyeInFluid(FluidTags.WATER)) {
                    water = true;
                    if (player == null || !ClientHooks.renderWaterOverlay(player, poseStack)) {
                        dailies$renderFluid(subject, poseStack, DAILIES$UNDERWATER);
                    }
                } else if (!subject.getEyeInFluidType().isAir()) {
                    // A mod's fluid: its overlay texture, drawn as NeoForge draws it by default (its hook asks for the game's
                    // own player, so a mod that draws its overlay all by itself cannot be asked for the recorder's)
                    ResourceLocation texture = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(subject.getEyeInFluidType())
                        .getRenderOverlayTexture(minecraft);
                    if (texture != null) {
                        dailies$renderFluid(subject, poseStack, texture);
                    }
                }
                if (subject.isOnFire()) {
                    fire = true;
                    if (player == null || !ClientHooks.renderFireOverlay(player, poseStack)) {
                        renderFire(minecraft, poseStack);
                    }
                }
            }
            RecorderEyes.shownScreen(fire, water, block);
        } catch (RuntimeException | LinkageError e) {
            comedymonkey.dailies.effects.RecorderOverlays.failOnce("the screen effects", e);
        }
    }

    /** Water, or a mod's fluid, over the eyes, lit and turned with the one whose eyes they are, as the game draws it for its own player */
    @Unique
    private static void dailies$renderFluid(LivingEntity subject, PoseStack poseStack, ResourceLocation texture) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        BlockPos eyes = BlockPos.containing(subject.getX(), subject.getEyeY(), subject.getZ());
        float brightness = LightTexture.getBrightness(subject.level().dimensionType(), subject.level().getMaxLocalRawBrightness(eyes));
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(brightness, brightness, brightness, 0.1F);
        float u = -subject.getYRot() / 64.0F;
        float v = subject.getXRot() / 64.0F;
        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(matrix, -1.0F, -1.0F, -0.5F).setUv(4.0F + u, 4.0F + v);
        builder.addVertex(matrix, 1.0F, -1.0F, -0.5F).setUv(0.0F + u, 4.0F + v);
        builder.addVertex(matrix, 1.0F, 1.0F, -0.5F).setUv(0.0F + u, 0.0F + v);
        builder.addVertex(matrix, -1.0F, 1.0F, -0.5F).setUv(4.0F + u, 0.0F + v);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }
}
