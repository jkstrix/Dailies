package comedymonkey.dailies.mixin.access;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Deque;

/** The light a world has been sent and not yet taken in, which playback takes from exactly as the live game did */
@Mixin(ClientLevel.class)
public interface ClientLevelAccess {

    @Accessor("lightUpdateQueue")
    Deque<Runnable> dailies$lightQueue();

}
