package comedymonkey.dailiescompat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.schematics.client.SchematicRenderer;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.utility.CreatePaths;
import comedymonkey.dailies.api.compat.DailiesCompats;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * The blocks of a placed schematic, which Create draws inside its box from the schematic's file. The file is on the
 * recorder's disk and a state rides in every snapshot, so the file's bytes are stored once in the recording
 * (DailiesCompats.storeOnce) and the schematic's state names them by their handle; playback reads them back from the
 * recording, builds the schematic's blocks as Create's SchematicHandler does (as it is, and mirrored either way) and
 * draws the copy Create was drawing with Create's own SchematicRenderer, inside the recorded box.
 */
final class SchematicBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The largest file stored, as Dailies takes */
    private static final long MOST_BYTES = 16L * 1024 * 1024;
    /** How often the file is handed to Dailies again while held, in ticks, so a recording started meanwhile holds it too */
    private static final int STORE_AGAIN_TICKS = 40;
    /** A pause between two captures this long means a recording of its own began */
    private static final long NEW_RECORDING_NANOS = 250_000_000L;

    /** The file last stored, by its path, size and time, with its bytes and handle */
    private static @Nullable String storedKey = null;
    private static byte @Nullable [] storedBytes = null;
    private static String storedHandle = "";
    private static int ticksSinceStored = 0;
    private static long lastCapture = 0;
    private static boolean storeFailed = false;

    /** What playback built, for which handle and world */
    private static @Nullable String builtFor = null;
    private static @Nullable Level builtIn = null;
    private static SchematicRenderer @Nullable [] renderers = null;

    private SchematicBlocks() {
    }

    // --- Recording ---

    /**
     * The handle of a schematic file in the recorder's schematics folder, stored once in the recording being made; empty
     * when there is no such file, it is too large, or this Dailies cannot store (it predates storing once)
     */
    static String handle(@Nullable String name) {
        long now = System.nanoTime();
        boolean newRecording = now - lastCapture > NEW_RECORDING_NANOS;
        lastCapture = now;
        if (name == null || !name.endsWith(".nbt") || storeFailed) {
            return "";
        }
        // Where SchematicItem.loadSchematic reads it from on the player's game, kept inside that folder
        Path folder = CreatePaths.SCHEMATICS_DIR;
        Path path = folder.resolve(name).normalize();
        if (!path.startsWith(folder)) {
            return "";
        }
        try {
            long size = Files.size(path);
            String key = path + "|" + size + "|" + Files.getLastModifiedTime(path).toMillis();
            if (size > MOST_BYTES) {
                return "";
            }
            if (!key.equals(storedKey)) {
                storedBytes = Files.readAllBytes(path);
                storedKey = key;
                newRecording = true;
            }
            if (newRecording || ++ticksSinceStored >= STORE_AGAIN_TICKS) {
                storedHandle = DailiesCompats.storeOnce(storedBytes);
                ticksSinceStored = 0;
            }
            return storedHandle;
        } catch (IOException | IllegalArgumentException gone) {
            return "";
        } catch (LinkageError old) {
            storeFailed = true;
            LOGGER.info("Dailies Create compat: this Dailies cannot store a schematic once in a recording, so a placed schematic's blocks are not recorded");
            return "";
        }
    }

    // --- Playback ---

    /** Draws the schematic's blocks, already placed by the pose stack as Create's transformation places them */
    static void draw(PoseStack ms, SuperRenderTypeBuffer buffer, String handle, int mirror) {
        SchematicRenderer renderer = renderer(handle, mirror);
        if (renderer != null) {
            renderer.render(ms, buffer);
        }
    }

    /** Lets go of what was built, so the playback world it was built in can go */
    static void forget() {
        builtFor = null;
        builtIn = null;
        renderers = null;
    }

    private static @Nullable SchematicRenderer renderer(String handle, int mirror) {
        Level level = Minecraft.getInstance().level;
        if (handle.isEmpty() || level == null) {
            return null;
        }
        if (!handle.equals(builtFor) || level != builtIn) {
            builtFor = handle;
            builtIn = level;
            renderers = null;
            try {
                byte[] bytes = DailiesCompats.stored(handle);
                StructureTemplate schematic = bytes == null ? null : read(level, bytes);
                renderers = schematic == null ? null : build(level, schematic);
            } catch (LinkageError old) {
                renderers = null;
            } catch (RuntimeException | IOException unreadable) {
                LOGGER.warn("Dailies Create compat: a recorded schematic could not be built for playback", unreadable);
                renderers = null;
            }
        }
        SchematicRenderer[] built = renderers;
        if (built == null) {
            return null;
        }
        SchematicRenderer picked = mirror == 2 ? built[2] : mirror == 1 ? built[1] : null;
        return picked != null ? picked : built[0];
    }

    /** As SchematicItem.loadSchematic reads the file */
    private static StructureTemplate read(Level level, byte[] bytes) throws IOException {
        StructureTemplate schematic = new StructureTemplate();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes))))) {
            CompoundTag nbt = NbtIo.read(in, NbtAccounter.create(0x20000000L));
            schematic.load(level.holderLookup(Registries.BLOCK), nbt);
        }
        return schematic;
    }

    /** As SchematicHandler.setupRenderer builds the three copies: as it is, mirrored front to back and left to right */
    private static SchematicRenderer @Nullable [] build(Level level, StructureTemplate schematic) {
        Vec3i size = schematic.getSize();
        if (size.equals(Vec3i.ZERO)) {
            return null;
        }
        SchematicLevel plain = new SchematicLevel(level);
        SchematicLevel frontBack = new SchematicLevel(level);
        SchematicLevel leftRight = new SchematicLevel(level);
        StructurePlaceSettings settings = new StructurePlaceSettings();

        BlockPos pos = BlockPos.ZERO;
        schematic.placeInWorld(plain, pos, pos, settings, plain.getRandom(), Block.UPDATE_CLIENTS);
        for (BlockEntity blockEntity : plain.getBlockEntities()) {
            blockEntity.setLevel(plain);
        }
        fixControllers(plain);

        settings.setMirror(Mirror.FRONT_BACK);
        pos = BlockPos.ZERO.east(size.getX() - 1);
        schematic.placeInWorld(frontBack, pos, pos, settings, frontBack.getRandom(), Block.UPDATE_CLIENTS);
        StructureTransform transform = new StructureTransform(settings.getRotationPivot(), Direction.Axis.Y, Rotation.NONE, settings.getMirror());
        for (BlockEntity blockEntity : frontBack.getRenderedBlockEntities()) {
            transform.apply(blockEntity);
        }
        fixControllers(frontBack);

        settings.setMirror(Mirror.LEFT_RIGHT);
        pos = BlockPos.ZERO.south(size.getZ() - 1);
        schematic.placeInWorld(leftRight, pos, pos, settings, frontBack.getRandom(), Block.UPDATE_CLIENTS);
        transform = new StructureTransform(settings.getRotationPivot(), Direction.Axis.Y, Rotation.NONE, settings.getMirror());
        for (BlockEntity blockEntity : leftRight.getRenderedBlockEntities()) {
            transform.apply(blockEntity);
        }
        fixControllers(leftRight);

        return new SchematicRenderer[]{new SchematicRenderer(plain), new SchematicRenderer(frontBack), new SchematicRenderer(leftRight)};
    }

    /** As SchematicHandler.fixControllerBlockEntities: a multiblock's parts point at their controller where it now is */
    private static void fixControllers(SchematicLevel level) {
        for (BlockEntity blockEntity : level.getBlockEntities()) {
            if (!(blockEntity instanceof IMultiBlockEntityContainer multiBlockEntity)) {
                continue;
            }
            BlockPos lastKnown = multiBlockEntity.getLastKnownPos();
            BlockPos current = blockEntity.getBlockPos();
            if (lastKnown == null || current == null || multiBlockEntity.isController()) {
                continue;
            }
            if (!lastKnown.equals(current)) {
                BlockPos controller = multiBlockEntity.getController().offset(current.subtract(lastKnown));
                if (multiBlockEntity instanceof SmartBlockEntity smart) {
                    smart.markVirtual();
                }
                multiBlockEntity.setController(controller);
            }
        }
    }

}
