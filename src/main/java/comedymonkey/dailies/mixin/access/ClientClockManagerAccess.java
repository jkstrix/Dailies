package comedymonkey.dailies.mixin.access;

import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Every world clock the game has been told of, which a snapshot has to repeat as a server would on joining */
@Mixin(ClientClockManager.class)
public interface ClientClockManagerAccess {

    @Accessor("clocks")
    Map<Holder<WorldClock>, ClientClockManager.ClientClockInstance> dailies$clocks();

}
