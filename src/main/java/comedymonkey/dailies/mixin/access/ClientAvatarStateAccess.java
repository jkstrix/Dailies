package comedymonkey.dailies.mixin.access;

import net.minecraft.client.entity.ClientAvatarState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The head bob a player is drawn with, which playback works out for a watched player from how far they moved */
@Mixin(ClientAvatarState.class)
public interface ClientAvatarStateAccess {

    @Accessor("bob")
    void dailies$setBob(float bob);

    @Accessor("bobO")
    void dailies$setBobO(float bobO);

}
