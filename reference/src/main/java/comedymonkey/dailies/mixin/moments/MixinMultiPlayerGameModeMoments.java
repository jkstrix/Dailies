package comedymonkey.dailies.mixin.moments;

import comedymonkey.dailies.moments.Moments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side block breaking and placing of the local player, for the moments system. The NeoForge block break
 * and place events only fire on the server.
 */
@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameModeMoments {

    @Unique
    private BlockState dailies$momentsDestroying = null;
    @Unique
    private ItemStack dailies$momentsHeld = ItemStack.EMPTY;
    @Unique
    private BlockState dailies$momentsClicked = null;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    public void dailies$momentsDestroyHead(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        this.dailies$momentsDestroying = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (Moments.isTracking() && minecraft.level != null) {
            this.dailies$momentsDestroying = minecraft.level.getBlockState(pos);
        }
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    public void dailies$momentsDestroyReturn(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = this.dailies$momentsDestroying;
        this.dailies$momentsDestroying = null;
        if (state != null && cir.getReturnValue()) {
            Moments.onBlockMined(pos, state);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    public void dailies$momentsUseHead(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        this.dailies$momentsClicked = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (Moments.isTracking() && minecraft.level != null) {
            this.dailies$momentsHeld = player.getItemInHand(hand).copy();
            this.dailies$momentsClicked = minecraft.level.getBlockState(hitResult.getBlockPos());
        }
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    public void dailies$momentsUseReturn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        BlockState clicked = this.dailies$momentsClicked;
        this.dailies$momentsClicked = null;
        if (clicked != null) {
            Moments.onUseItemOn(this.dailies$momentsHeld, clicked, hitResult.getBlockPos(), hitResult.getDirection(), cir.getReturnValue());
            this.dailies$momentsHeld = ItemStack.EMPTY;
        }
    }

}
