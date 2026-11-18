package comedymonkey.dailies.extras;

import comedymonkey.dailies.ui.LinesScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Lists how the mods installed now differ from those the open recording was made with (specification PLAY-5): the
 * ones missing here, the ones extra here and the ones in another version. The recording keeps playing behind it.
 */
final class ModDifferencesScreen extends LinesScreen {

    ModDifferencesScreen(@Nullable ModDifferences.Differences differences) {
        // No blur and no dimming of the world: only the plate the list sits on
        super(Component.translatable("dailies.extras.mods.title"), Component.translatable("dailies.extras.mods.explanation"),
            Component.translatable("dailies.extras.mods.done"), null, false);
        if (differences == null) {
            this.words(Component.translatable("dailies.extras.mods.same"));
            return;
        }
        this.group("dailies.extras.mods.missing", differences.missing().stream()
            .map(mod -> Component.translatable("dailies.extras.mods.entry", mod.id(), mod.version())).toList());
        this.group("dailies.extras.mods.changed", differences.changed().stream()
            .map(mod -> Component.translatable("dailies.extras.mods.entry_changed", mod.id(), mod.recorded(), mod.installed())).toList());
        this.group("dailies.extras.mods.extra", differences.extra().stream()
            .map(mod -> Component.translatable("dailies.extras.mods.entry", mod.id(), mod.version())).toList());
    }

    private void group(String heading, List<? extends Component> entries) {
        if (entries.isEmpty()) {
            return;
        }
        this.section(Component.translatable(heading, entries.size()));
        for (Component entry : entries) {
            this.entry(entry);
        }
    }

}
