package comedymonkey.dailies.mixin.sound;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The way from the game's sound manager to the engine behind it */
@Mixin(SoundManager.class)
public interface SoundManagerAccess {

    @Accessor("soundEngine")
    SoundEngine dailies$engine();

}
