package comedymonkey.dailiescompat.supplementaries.client;

import comedymonkey.dailiescompat.supplementaries.state.SuppStates;
import net.mehvahdjukaar.supplementaries.client.screens.BlackBoardScreen;
import net.mehvahdjukaar.supplementaries.client.screens.DoormatScreen;
import net.mehvahdjukaar.supplementaries.client.screens.SignPostScreen;
import net.mehvahdjukaar.supplementaries.client.screens.SpeakerBlockScreen;
import net.mehvahdjukaar.supplementaries.client.screens.TextHolderEditScreen;
import net.mehvahdjukaar.supplementaries.client.screens.widgets.ISlider;
import net.mehvahdjukaar.supplementaries.common.block.tiles.BlackboardBlockTile;
import net.mehvahdjukaar.supplementaries.common.block.tiles.DoormatBlockTile;
import net.mehvahdjukaar.supplementaries.common.block.tiles.SignPostBlockTile;
import net.mehvahdjukaar.supplementaries.common.block.tiles.SpeakerBlockTile;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Supplementaries' screens that only the player's game knows, where a block is written or drawn on: a doormat, a sign
 * post, a blackboard and a speaker block. Each is recorded as what is being written or set, as it stands while the player
 * types, and drawn in playback with Supplementaries' own screen, built on a block entity of the compat's own made for it
 * and in no world (the playback world's block may already hold what was sent when the screen closed), never sending
 * anything: the screen is never closed or told it was removed, which is where each sends what was typed.
 */
final class SuppScreens {

    static final String DOORMAT = "doormat";
    static final String SIGN_POST = "sign_post";
    static final String BLACKBOARD = "blackboard";
    static final String SPEAKER = "speaker_block";

    private static final ResourceLocation BLACKBOARD_ID = ResourceLocation.fromNamespaceAndPath("supplementaries", "blackboard");
    private static final ResourceLocation SPEAKER_ID = ResourceLocation.fromNamespaceAndPath("supplementaries", "speaker_block");

    private SuppScreens() {
    }

    /** One of the mod's screens, by the constructor it keeps to itself */
    private static <T> T made(Class<T> type, Class<?>[] parameters, Object... arguments) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName() + " could not be made", e);
        }
    }

    // --- A doormat or a sign post ---

    static byte[] captureTextHolder(Screen screen) {
        TextHolderEditScreen<?> editing = (TextHolderEditScreen<?>) screen;
        BlockEntity tile = Reflect.get(editing, "tile");
        String[][] messages = Reflect.get(editing, "messages");
        TextFieldHelper field = Reflect.get(editing, "textInputUtil");
        List<List<String>> lines = new ArrayList<>(messages.length);
        for (String[] holder : messages) {
            lines.add(Arrays.asList(holder));
        }
        ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(tile.getType());
        return SuppStates.textHolder(new SuppStates.TextHolder(type == null ? "" : type.toString(), tile.getBlockPos().asLong(), Texts.block(tile.getBlockState()),
            Texts.nbt(tile.saveWithoutMetadata(Texts.lookup())), lines, Reflect.get(editing, "textHolderIndex"), Reflect.get(editing, "lineIndex"),
            field == null ? 0 : field.getCursorPos(), field == null ? 0 : field.getSelectionPos(), Reflect.get(editing, "updateCounter")));
    }

    /** The block entity a text holder's screen was built on, made again from its saved data, in no world */
    private static @Nullable BlockEntity tile(SuppStates.TextHolder recorded) {
        ResourceLocation key = ResourceLocation.tryParse(recorded.type());
        BlockEntityType<?> type = key == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.get(key);
        BlockState block = Texts.block(recorded.block());
        if (type == null || block == null) {
            return null;
        }
        BlockEntity tile = type.create(BlockPos.of(recorded.pos()), block);
        if (tile != null) {
            tile.loadWithComponents(Texts.nbt(recorded.data()), Texts.lookup());
        }
        return tile;
    }

    static @Nullable Screen buildDoormat(byte[] state) {
        SuppStates.TextHolder recorded = SuppStates.readTextHolder(state);
        if (!(tile(recorded) instanceof DoormatBlockTile doormat)) {
            return null;
        }
        return textHolder(made(DoormatScreen.class, new Class<?>[]{DoormatBlockTile.class}, doormat), recorded);
    }

    static @Nullable Screen buildSignPost(byte[] state) {
        SuppStates.TextHolder recorded = SuppStates.readTextHolder(state);
        if (!(tile(recorded) instanceof SignPostBlockTile post)) {
            return null;
        }
        return textHolder(made(SignPostScreen.class, new Class<?>[]{SignPostBlockTile.class, int.class}, post, recorded.holder()), recorded);
    }

    /** The lines as typed, and the line being typed on, put on the screen; the cursor once it is laid out, which makes its field */
    private static Screen textHolder(TextHolderEditScreen<?> screen, SuppStates.TextHolder recorded) {
        String[][] messages = Reflect.get(screen, "messages");
        for (int holder = 0; holder < messages.length && holder < recorded.lines().size(); holder++) {
            List<String> lines = recorded.lines().get(holder);
            for (int line = 0; line < messages[holder].length && line < lines.size(); line++) {
                messages[holder][line] = lines.get(line);
            }
        }
        int holder = Math.clamp(recorded.holder(), 0, Math.max(0, messages.length - 1));
        int line = messages.length == 0 ? 0 : Math.clamp(recorded.line(), 0, Math.max(0, messages[holder].length - 1));
        Reflect.set(screen, "textHolderIndex", holder);
        Reflect.set(screen, "lineIndex", line);
        Reflect.set(screen, "updateCounter", recorded.ticks());
        return new Shown<>(screen, laidOut -> {
            TextFieldHelper field = Reflect.get(laidOut, "textInputUtil");
            if (field != null && messages.length > 0) {
                int length = messages[holder][line].length();
                field.setSelectionRange(Math.clamp(recorded.cursor(), 0, length), Math.clamp(recorded.selection(), 0, length));
            }
        });
    }

    // --- A blackboard ---

    static byte[] captureBlackboard(Screen screen) {
        BlackboardBlockTile used = Reflect.get(screen, "tile");
        BlackboardBlockTile[][] boards = Reflect.get(screen, "connectedBoards");
        int columns = boards.length;
        int rows = columns == 0 ? 0 : boards[0].length;
        List<byte[]> pixels = new ArrayList<>(columns * rows);
        int usedAt = 0;
        for (int column = 0; column < columns; column++) {
            for (int row = 0; row < rows; row++) {
                BlackboardBlockTile board = boards[column][row];
                if (board == used) {
                    usedAt = column * rows + row;
                }
                pixels.add(board == null ? null : pixels(board));
            }
        }
        Button undo = Reflect.get(screen, "historyButton");
        return SuppStates.blackboard(new SuppStates.Blackboard(columns, rows, pixels, usedAt, ((BlackBoardScreen) screen).getSelectedColor(), undo != null && undo.active));
    }

    /** A board's pixels as the screen draws them: the screen puts each stroke on the board as it is drawn */
    private static byte[] pixels(BlackboardBlockTile board) {
        byte[] pixels = new byte[SuppStates.PIXELS];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                pixels[x * 16 + y] = board.getPixel(x, y);
            }
        }
        return pixels;
    }

    /** Built on boards of the compat's own holding the pixels as drawn, laid out as they were joined */
    static @Nullable Screen buildBlackboard(byte[] state) {
        SuppStates.Blackboard recorded = SuppStates.readBlackboard(state);
        BlockState block = BuiltInRegistries.BLOCK.get(BLACKBOARD_ID).defaultBlockState();
        BlackboardBlockTile[][] boards = new BlackboardBlockTile[recorded.columns()][recorded.rows()];
        BlackboardBlockTile used = null;
        for (int column = 0; column < recorded.columns(); column++) {
            for (int row = 0; row < recorded.rows(); row++) {
                byte[] pixels = recorded.board(column, row);
                if (pixels == null) {
                    continue;
                }
                BlackboardBlockTile board = new BlackboardBlockTile(new BlockPos(column, -row, 0), block);
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        board.setPixel(x, y, pixels[x * 16 + y]);
                    }
                }
                boards[column][row] = board;
                if (used == null || column * recorded.rows() + row == recorded.used()) {
                    used = board;
                }
            }
        }
        if (used == null) {
            return null;
        }
        BlackBoardScreen screen = made(BlackBoardScreen.class, new Class<?>[]{BlackboardBlockTile.class, BlackboardBlockTile[][].class}, used, boards);
        screen.setSelectedColor(recorded.colour());
        return new Shown<>(screen, laidOut -> {
            Button undo = Reflect.get(laidOut, "historyButton");
            if (undo != null) {
                undo.active = recorded.undo();
            }
        });
    }

    // --- A speaker block ---

    static byte[] captureSpeaker(Screen screen) {
        EditBox box = Reflect.get(screen, "editBox");
        SpeakerBlockTile.Mode mode = Reflect.get(screen, "mode");
        ISlider volume = Reflect.get(screen, "volumeSlider");
        int highlight = box == null ? 0 : Reflect.get(box, "highlightPos");
        return SuppStates.speaker(new SuppStates.Speaker(box == null ? "" : box.getValue(), box == null ? 0 : box.getCursorPosition(), highlight,
            mode == null ? SpeakerBlockTile.Mode.CHAT.name() : mode.name(), volume == null ? 0.0 : volume.getValue()));
    }

    /** Built on a speaker of the compat's own holding the message, its way of saying it and how far it carries */
    static @Nullable Screen buildSpeaker(byte[] state) {
        SuppStates.Speaker recorded = SuppStates.readSpeaker(state);
        BlockState block = BuiltInRegistries.BLOCK.get(SPEAKER_ID).defaultBlockState();
        SpeakerBlockTile speaker = new SpeakerBlockTile(BlockPos.ZERO, block);
        speaker.setMessage(Component.literal(recorded.message()));
        SpeakerBlockTile.Mode mode = SpeakerBlockTile.Mode.CHAT;
        for (SpeakerBlockTile.Mode each : SpeakerBlockTile.Mode.values()) {
            if (each.name().equals(recorded.mode())) {
                mode = each;
            }
        }
        speaker.setMode(mode);
        speaker.setVolume(recorded.volume());
        SpeakerBlockScreen screen = new SpeakerBlockScreen(speaker);
        return new Shown<>(screen, laidOut -> {
            EditBox box = Reflect.get(laidOut, "editBox");
            if (box != null) {
                int length = box.getValue().length();
                box.setCursorPosition(Math.clamp(recorded.cursor(), 0, length));
                box.setHighlightPos(Math.clamp(recorded.selection(), 0, length));
            }
        });
    }

}
