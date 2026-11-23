package comedymonkey.dailies.mixin.access;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * How far up each of the player's own hands is (1 fully up), as the game keeps it, for the recording; and the game's
 * drawing of one arm with what it holds, for drawing someone else's hands through their eyes in playback
 */
@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccess {

    @Accessor("mainHandHeight")
    float dailies$mainHandHeight();

    @Accessor("offHandHeight")
    float dailies$offHandHeight();

    @Invoker("renderArmWithItem")
    void dailies$renderArmWithItem(AbstractClientPlayer player, float partialTicks, float pitch, InteractionHand hand, float swingProgress,
                                   ItemStack stack, float equippedProgress, PoseStack poseStack, MultiBufferSource buffers, int light);
}
