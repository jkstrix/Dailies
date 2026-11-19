package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter.ScrollOptionSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;
import java.util.List;

/**
 * Create's value settings board, opened by holding use on a block's value box: where a speed and its direction, an
 * amount or a mode is picked. The value picked follows the player's pointer, so what is recorded is the board as shown
 * and the value under the pointer as the board last drew it (its row and column), with that value's label. In playback
 * the board is Create's own screen, built from those, with its cursor held on the recorded value whatever the pointer
 * does, fully faded in, and silent.
 */
final class ValueBoard {

    static final String NAME = "value_board";

    private ValueBoard() {
    }

    static byte[] capture(Screen screen) {
        ValueSettingsScreen board = (ValueSettingsScreen) screen;
        ValueSettingsBoard shown = Reflect.get(board, "board");
        ValueSettings picked = Reflect.get(board, "lastHovered");
        if (picked == null || picked.row() < 0) {
            // Not drawn with its cursor yet: the block's own setting, where the board opens
            picked = Reflect.get(board, "initialSettings");
        }
        ValueSettingsFormatter formatter = shown.formatter();
        List<String> keys = new ArrayList<>();
        List<String> icons = new ArrayList<>();
        String label = "";
        if (formatter instanceof ScrollOptionSettingsFormatter) {
            INamedIconOptions[] options = Reflect.get(formatter, "options");
            for (INamedIconOptions option : options) {
                keys.add(option.getTranslationKey());
                icons.add(Texts.icon(option.getIcon()));
            }
        } else {
            label = Texts.json(formatter.format(picked));
        }
        return States.board(new States.Board(Texts.json(shown.title()), Texts.jsons(shown.rows()), shown.maxValue(), shown.milestoneInterval(), picked.row(),
            picked.value(), label, keys, icons));
    }

    static Screen build(byte[] state) {
        return new Recorded(States.readBoard(state));
    }

    /** The board as the recording shows it: Create's own screen, its cursor on the recorded value, taking no part in play */
    static final class Recorded extends ValueSettingsScreen implements RecordedScreen {

        private final States.Board recorded;

        Recorded(States.Board recorded) {
            super(BlockPos.ZERO, board(recorded), new ValueSettings(recorded.row(), recorded.value()), hovered -> {
            }, 0);
            this.recorded = recorded;
        }

        private static ValueSettingsBoard board(States.Board recorded) {
            List<Component> rows = new ArrayList<>(Texts.reads(recorded.rows()));
            if (rows.isEmpty()) {
                rows.add(Component.empty());
            }
            ValueSettingsFormatter formatter;
            if (recorded.iconMode()) {
                // A mode for every column, so the board measures every name as Create's does
                INamedIconOptions[] options = new INamedIconOptions[Math.max(recorded.maxValue() + 1, recorded.optionKeys().size())];
                for (int i = 0; i < options.length; i++) {
                    int at = Math.min(i, recorded.optionKeys().size() - 1);
                    String key = recorded.optionKey(at);
                    AllIcons icon = Texts.icon(recorded.optionIcon(at));
                    options[i] = new INamedIconOptions() {
                        @Override
                        public AllIcons getIcon() {
                            return icon != null ? icon : AllIcons.I_CONFIRM;
                        }

                        @Override
                        public String getTranslationKey() {
                            return key;
                        }
                    };
                }
                formatter = new ScrollOptionSettingsFormatter(options);
            } else {
                MutableComponent label = Texts.read(recorded.label());
                formatter = new ValueSettingsFormatter(settings -> label.copy());
            }
            return new ValueSettingsBoard(Texts.read(recorded.title()), Math.max(0, recorded.maxValue()), Math.max(1, recorded.milestoneInterval()), rows,
                formatter);
        }

        @Override
        protected void init() {
            super.init();
            // Fully faded in, as the board stood once it had been open a moment, and already on the recorded value, so
            // drawing it never plays the board's tick or tells a block what is hovered
            Reflect.setInt(this, "ticksOpen", 20);
            Reflect.set(this, "lastHovered", new ValueSettings(this.recorded.row(), this.recorded.value()));
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // The board picks the value nearest the pointer it is drawn with: drawn with the recorded value's own spot
            Vec2 at = this.getCoordinateOfValue(this.recorded.row(), this.recorded.value());
            super.render(graphics, (int) at.x, (int) at.y, partialTicks);
        }

        @Override
        public void tick() {
        }

        @Override
        protected void saveAndClose(double mouseX, double mouseY) {
            // Never sends the value to a server
        }

    }

}
