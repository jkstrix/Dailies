package comedymonkey.dailies.mixin.access;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's pause flag, which a jump has to lift for the ticks it runs itself */
@Mixin(Minecraft.class)
public interface MinecraftAccess {

    @Accessor("pause")
    void dailies$setPause(boolean pause);

}
