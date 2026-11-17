package comedymonkey.dailies.selftest;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.compat.sable.SableCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Chapter XIV step 2, "Build machines": a Create contraption on a bearing, a Sable physics platform and a hot air
 * balloon, built by script while the recording runs. Each is skipped, with a line saying so, when its mod is absent.
 * Also where the test reads everything's position, live and in playback alike.
 */
final class CraftSteps {

    static boolean builtContraption = false;
    static boolean builtPlatform = false;
    static boolean builtBalloon = false;

    private CraftSteps() {
    }

    static void build(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (Host.get().isModLoaded("create")) {
            // A motor facing up, a bearing on it, and a few blocks for it to carry. The bearing assembles by itself
            test.command("setblock 14 -60 -6 create:creative_motor[facing=up]");
            test.command("setblock 14 -59 -6 create:mechanical_bearing[facing=up]");
            test.command("setblock 14 -58 -6 minecraft:oak_planks");
            test.command("setblock 15 -58 -6 minecraft:oak_planks");
            test.command("setblock 16 -58 -6 minecraft:red_wool");
            test.waitFor(() -> contraptions(minecraft) > 0, 15, "the bearing to assemble its contraption");
            builtContraption = true;
            test.log("bearing contraption assembled");
        } else {
            test.skip("Create is not installed: no bearing contraption was built (MOD-1)");
        }

        if (Host.get().isModLoaded("sable") && SableCompat.available()) {
            // A slab of stone in the air, made into a physics object. It falls, which is the motion to record
            test.command("fill 20 -50 -8 23 -50 -5 minecraft:stone");
            test.sleepTicks(5);
            test.command("sable assemble area 20 -50 -8 23 -50 -5");
            test.waitFor(() -> crafts(minecraft) >= 1, 15, "the platform to become a physics object");
            builtPlatform = true;
            test.log("physics platform assembled");
            // Note where it is while it falls: these are the checks that prove recorded motion, not just a resting place
            for (int i = 0; i < 5; i++) {
                test.sleepTicks(4);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.sleepTicks(4);
            }

            if (Host.get().isModLoaded("aeronautics")) {
                buildBalloon(test, minecraft);
            } else {
                test.skip("Create Aeronautics is not installed: no balloon was built (MOD-2 with a flying craft)");
            }
        } else {
            test.skip("Sable is not installed or not the known version: no physics craft were built (MOD-2 to MOD-5)");
        }
    }

    private static void buildBalloon(SelfTest test, Minecraft minecraft) throws Exception {
        ResourceLocation name = ResourceLocation.fromNamespaceAndPath("aeronautics", "ponder/hot_air/envelope");
        BlockPos origin = new BlockPos(-24, -60, -24);
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        // The structure is one of the mod's own demonstration scenes, which live with its client resources
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath("aeronautics", "ponder/hot_air/envelope.nbt");
        Optional<net.minecraft.server.packs.resources.Resource> resource = minecraft.getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            test.skip("the balloon structure " + file + " is not in this version of Aeronautics: no balloon was built");
            return;
        }
        net.minecraft.nbt.CompoundTag tag;
        try (java.io.InputStream in = resource.get().open()) {
            tag = net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        }
        StructureTemplate template = new StructureTemplate();
        BlockPos size = minecraft.getSingleplayerServer().submit(() -> {
            template.load(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            template.placeInWorld(level, origin, origin, new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(), level.random, 2);
            return new BlockPos(template.getSize());
        }).get();
        test.sleepTicks(10);
        BlockPos burner = minecraft.getSingleplayerServer().submit(() -> {
            for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(size))) {
                if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath().contains("burner")) {
                    return pos.immutable();
                }
            }
            return null;
        }).get();
        if (burner == null) {
            test.skip("no burner was found in the balloon structure: no balloon was built");
            return;
        }
        // The burner only heats with redstone against it, and starts as soon as it has it
        BlockPos power = burner.below();
        test.command("setblock " + power.getX() + " " + power.getY() + " " + power.getZ() + " minecraft:redstone_block");
        test.sleepTicks(10);
        int before = crafts(minecraft);
        // A region tight around the balloon: a loose one takes in whatever is nearby as a second object
        test.command("sable assemble area " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " "
            + (origin.getX() + size.getX() - 1) + " " + (origin.getY() + size.getY() - 1) + " " + (origin.getZ() + size.getZ() - 1));
        test.waitFor(() -> crafts(minecraft) > before, 15, "the balloon to become a physics object");
        builtBalloon = true;
        test.log("hot air balloon assembled, burner at " + burner.toShortString());
    }

    private static int contraptions(Minecraft minecraft) {
        try {
            return minecraft.submit(() -> {
                int count = 0;
                if (minecraft.level != null) {
                    for (Entity entity : minecraft.level.entitiesForRendering()) {
                        if (isContraption(entity)) {
                            count++;
                        }
                    }
                }
                return count;
            }).get();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int crafts(Minecraft minecraft) {
        try {
            return minecraft.submit(() -> minecraft.level == null ? 0 : SableCompat.craftPositions(minecraft.level, 1.0F).size()).get();
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isContraption(Entity entity) {
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return type.getNamespace().equals("create") && type.getPath().contains("contraption");
    }

    /**
     * Where everything worth checking is, by a name that is the same live and in playback. Client thread.
     *
     * @param recorderId the recorder's uuid in playback, or null in the live game, where the recorder is the player
     */
    static Map<String, Vec3> positions(Minecraft minecraft, String recorderId) {
        Map<String, Vec3> positions = new TreeMap<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (recorderId == null ? entity == minecraft.player : entity.getUUID().toString().equals(recorderId)) {
                positions.put("recorder", entity.position());
            } else if (entity.hasCustomName()) {
                positions.put(entity.getCustomName().getString(), entity.position());
            } else if (isContraption(entity)) {
                positions.put("contraption " + entity.getId() + " (" + blocksOf(entity) + " blocks)", entity.position());
            }
        }
        for (Object[] craft : SableCompat.craftPositions(minecraft.level, 1.0F)) {
            positions.put("craft " + craft[0], (Vec3) craft[1]);
        }
        return positions;
    }

    /** How many blocks a Create contraption carries, read without compiling against Create. -1 when it cannot be read */
    private static int blocksOf(Entity contraptionEntity) {
        try {
            Object contraption = contraptionEntity.getClass().getMethod("getContraption").invoke(contraptionEntity);
            Object blocks = contraption.getClass().getMethod("getBlocks").invoke(contraption);
            return ((Map<?, ?>) blocks).size();
        } catch (Throwable t) {
            return -1;
        }
    }

}
