package comedymonkey.dailies.looks;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Shows block change keyframes (specification chapter V, "Kinds of keyframe"): from a keyframe's tick on, the chosen
 * block stands at its position although the recording never put it there.
 * <p>
 * The block is set in the playback world itself, without telling its neighbours, and what stood there is remembered.
 * When a change stops applying (playback is before its tick, the keyframe is gone, another scene is shown) the
 * remembered block goes back, unless the recording has put something else there in the meantime. When the world is
 * rebuilt from the recording everything remembered is dropped and the changes are made afresh.
 * <p>
 * Like every keyframe, block changes move only while playing or exporting (EDIT-5): while playback stands still what
 * is shown is left as it is.
 */
final class BlockChanges {

    /** Redraw the block, and leave its neighbours alone */
    private static final int QUIETLY = 2 | 16;

    /** A block shown in place of another, with what the recording had there */
    private record Shown(BlockState state, BlockState recorded, @Nullable CompoundTag recordedData) {
    }

    private static final Map<BlockPos, Shown> SHOWN = new HashMap<>();
    private static final Map<String, Optional<BlockState>> PARSED = new HashMap<>();
    private static ClientLevel level = null;

    private BlockChanges() {
    }

    /** The world is gone or about to be rebuilt: nothing that was changed in it is there any more */
    static void forget() {
        SHOWN.clear();
        level = null;
    }

    /** Once per drawn frame while a recording is on screen */
    static void frame(Minecraft minecraft, Scene scene) {
        try {
            ClientLevel now = minecraft.level;
            if (now != level) {
                SHOWN.clear();
                level = now;
            }
            if (now == null || Playback.jumping() || Playback.paused() && !Export.active()) {
                return;
            }
            if (SHOWN.isEmpty() && !hasChanges(scene)) {
                return;
            }
            Map<BlockPos, BlockState> wanted = new HashMap<>();
            for (Map.Entry<Evaluator.BlockPosition, String> change : Evaluator.blockChanges(scene, Playback.frameTime()).entrySet()) {
                BlockState state = parse(now, change.getValue());
                if (state != null) {
                    Evaluator.BlockPosition at = change.getKey();
                    wanted.put(new BlockPos(at.x(), at.y(), at.z()), state);
                }
            }

            Iterator<Map.Entry<BlockPos, Shown>> shown = SHOWN.entrySet().iterator();
            while (shown.hasNext()) {
                Map.Entry<BlockPos, Shown> entry = shown.next();
                BlockPos pos = entry.getKey();
                BlockState standing = now.getBlockState(pos);
                if (standing != entry.getValue().state()) {
                    // The recording has changed this block since, or its chunk has gone: there is nothing to put back
                    shown.remove();
                } else if (wanted.get(pos) != standing) {
                    putBack(now, pos, entry.getValue());
                    shown.remove();
                }
            }
            for (Map.Entry<BlockPos, BlockState> entry : wanted.entrySet()) {
                BlockPos pos = entry.getKey();
                if (SHOWN.containsKey(pos) || now.isOutsideBuildHeight(pos) || !now.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                    continue;
                }
                BlockState state = entry.getValue();
                BlockState recorded = now.getBlockState(pos);
                if (recorded == state) {
                    SHOWN.put(pos, new Shown(state, recorded, null));
                    continue;
                }
                BlockEntity blockEntity = now.getBlockEntity(pos);
                CompoundTag data = blockEntity == null ? null : blockEntity.saveWithFullMetadata(now.registryAccess());
                if (now.setBlock(pos, state, QUIETLY)) {
                    SHOWN.put(pos, new Shown(state, recorded, data));
                }
            }
        } catch (Throwable t) {
            LooksRenderer.failOnce("showing block change keyframes", t);
        }
    }

    private static boolean hasChanges(Scene scene) {
        for (Track track : scene.tracks()) {
            if (track.kind() == KeyframeKind.BLOCK_CHANGE && track.enabled() && !track.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void putBack(ClientLevel world, BlockPos pos, Shown shown) {
        if (shown.recorded() == shown.state()) {
            return;
        }
        world.setBlock(pos, shown.recorded(), QUIETLY);
        if (shown.recordedData() != null) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity != null) {
                blockEntity.loadWithComponents(shown.recordedData(), world.registryAccess());
                world.sendBlockUpdated(pos, shown.recorded(), shown.recorded(), QUIETLY);
            }
        }
    }

    /** The block a keyframe names, such as "minecraft:oak_stairs[facing=north]", or null when there is no such block */
    private static @Nullable BlockState parse(ClientLevel world, String text) {
        Optional<BlockState> known = PARSED.get(text);
        if (known == null) {
            try {
                known = Optional.of(BlockStateParser.parseForBlock(world.holderLookup(Registries.BLOCK), text, false).blockState());
            } catch (CommandSyntaxException | RuntimeException e) {
                known = Optional.empty();
                Host.LOGGER.warn("Dailies: a block change keyframe names '{}', which is not a block this game knows, so it shows nothing: {}", text, e.getMessage());
            }
            if (PARSED.size() > 4096) {
                PARSED.clear();
            }
            PARSED.put(text, known);
        }
        return known.orElse(null);
    }

}
