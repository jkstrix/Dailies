package comedymonkey.dailies.mixin.sound;

import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The parts of the game's sound engine an export needs: whether it started, its playing sounds, and its own thread's queue */
@Mixin(SoundEngine.class)
public interface SoundEngineAccess {

    @Accessor("loaded")
    boolean dailies$loaded();

    @Accessor("channelAccess")
    ChannelAccess dailies$channels();

    @Accessor("executor")
    SoundEngineExecutor dailies$executor();

}
