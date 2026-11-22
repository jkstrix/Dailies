package comedymonkey.dailies.mixin.effects;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether the game's music is playing just now, for stopping it while a recording is open */
@Mixin(MusicManager.class)
public interface MusicManagerAccess {

    @Accessor("currentMusic")
    SoundInstance dailies$currentMusic();
}
