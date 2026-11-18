package comedymonkey.dailies.mixin.access;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What the server said about view and simulation distance, which a snapshot has to repeat */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccess {

    @Accessor("serverChunkRadius")
    int dailies$serverChunkRadius();

    @Accessor("serverSimulationDistance")
    int dailies$serverSimulationDistance();

}
