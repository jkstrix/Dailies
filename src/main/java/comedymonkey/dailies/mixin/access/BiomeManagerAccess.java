package comedymonkey.dailies.mixin.access;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The number that decides where biome edges are drawn, so playback draws them where the recorder saw them */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccess {

    @Accessor("biomeZoomSeed")
    long dailies$biomeZoomSeed();

}
