package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The game's fonts (26.3 keeps no cache of the last one used), for the scripted test to prove that a resource reload left it a live one */
@Mixin(FontManager.class)
public interface FontManagerAccess {

    @Accessor("fontSets")
    Map<Identifier, FontSet> dailies$fontSets();

}
