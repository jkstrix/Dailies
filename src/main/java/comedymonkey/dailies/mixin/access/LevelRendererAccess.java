package comedymonkey.dailies.mixin.access;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sections in view, so playback can tell a world that has drawn nothing from one still being built */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccess {

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> dailies$visibleSections();

}
