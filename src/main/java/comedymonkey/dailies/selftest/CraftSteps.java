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

    /** The name, among the noted positions, of the recorder's place on the craft under their feet */
    static final String RECORDER_ON_CRAFT = "recorder on craft";

    /** Ends the name of an entry that says whether something is riding (1) or loose (0), not where it is */
    static final String SEATED = " is seated";

    static boolean builtContraption = false;
    static boolean builtPlatform = false;
    static boolean builtBalloon = false;
    /** Where in the list of noted positions the first platform's fall begins, and the bearing's turning; -1 when not built */
    static int platformNotesFrom = -1;
    static int contraptionNotesFrom = -1;

    /** Everything about the machines in the world at one moment that a trimmed recording has to get right. Client thread */
    record Machines(Map<String, Vec3> positions, int entities, int crafts, int contraptions, Map<Integer, Float> angles) {
        static Machines take(Minecraft minecraft, String recorderId) {
            int entities = 0;
            int contraptions = 0;
            Map<Integer, Float> angles = new TreeMap<>();
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity == minecraft.player || entity instanceof net.minecraft.world.entity.ExperienceOrb) {
                    continue;
                }
                entities++;
                if (isContraption(entity)) {
                    contraptions++;
                    Float angle = angleOf(entity);
                    if (angle != null) {
                        angles.put(entity.getId(), angle);
                    }
                }
            }
            return new Machines(CraftSteps.positions(minecraft, recorderId), entities, SableCompat.craftPositions(minecraft.level, 1.0F).size(), contraptions, angles);
        }
    }

    /** The angle a turning contraption has reached, read without compiling against Create. Null when it has none */
    private static Float angleOf(Entity contraptionEntity) {
        for (Class<?> type = contraptionEntity.getClass(); type != null && type != Entity.class; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field angle = type.getDeclaredField("angle");
                if (angle.getType() == float.class) {
                    angle.setAccessible(true);
                    return angle.getFloat(contraptionEntity);
                }
            } catch (ReflectiveOperationException | RuntimeException notHere) {
                // Look in the class above
            }
        }
        return null;
    }

    private CraftSteps() {
    }

    static void build(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        // Mobs that stay where they are put but still fall and can be carried: no walking speed, gravity left on
        String still = "attributes:[{id:\"minecraft:generic.movement_speed\",base:0.0}],PersistenceRequired:1b,Silent:1b";
        if (Host.get().isModLoaded("create")) {
            // An arm of planks with a seat on it, a mob sitting in the seat and another standing on the arm, then a motor
            // and a bearing under it. The bearing assembles by itself once it turns; it is placed last so everything is aboard
            test.command("setblock 14 -58 -6 minecraft:oak_planks");
            test.command("setblock 15 -58 -6 minecraft:oak_planks");
            test.command("setblock 16 -58 -6 minecraft:oak_planks");
            test.command("setblock 17 -58 -6 minecraft:oak_planks");
            test.command("setblock 16 -57 -6 create:white_seat");
            test.command("setblock 15 -57 -6 create:red_seat");
            test.command("summon minecraft:pig 16.5 -56.2 -5.5 {CustomName:'\"Rider\"'," + still + "}");
            test.command("summon minecraft:pig 17.5 -57 -5.5 {CustomName:'\"Stander\"'," + still + "}");
            // Blocks only travel with a bearing when they are glued to what it carries
            test.command("create glue 14 -58 -6 17 -57 -6");
            test.sleepTicks(25);
            boolean riderSeated = seated(test, minecraft, "Rider");
            // The recorder takes the second seat before the contraption is assembled: whoever is not aboard then is left behind
            test.command("tp @s 15.5 -56 -4.2 180 60");
            test.sleepTicks(6);
            boolean recorderSeated = sit(test, minecraft, new BlockPos(15, -57, -6));
            test.command("setblock 14 -60 -6 create:creative_motor[facing=up]");
            boolean assembled = false;
            for (int attempt = 0; attempt < 4 && !assembled; attempt++) {
                test.command("setblock 14 -59 -6 create:mechanical_bearing[facing=up]");
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline && contraptions(minecraft) == 0) {
                    Thread.sleep(100);
                }
                assembled = contraptions(minecraft) > 0;
                if (!assembled) {
                    // It sometimes does not take the first time. Putting the bearing down again makes it look once more
                    test.command("setblock 14 -59 -6 minecraft:air");
                    test.sleepTicks(8);
                }
            }
            SelfTest.check(assembled, "the bearing did not assemble its contraption in four tries");
            builtContraption = true;
            test.log("bearing contraption assembled, carrying a standing mob" + (riderSeated ? ", a mob in a Create seat" : "") + (recorderSeated ? ", the recorder in a Create seat" : ""));
            if (!riderSeated) {
                test.skip("the mob did not sit in the Create seat on the contraption: a seated mob on a contraption was not tried");
            }
            if (!recorderSeated) {
                test.skip("the recorder could not take the Create seat on the contraption: a seated player on a contraption was not tried");
            }
            contraptionNotesFrom = RecordingSteps.notes.size();
            // Seated first, as the arm turns
            for (int i = 0; i < 4; i++) {
                test.sleepTicks(6);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.sleepTicks(4);
            }
            // Then standing loose on the turning arm
            test.command("ride @s dismount");
            test.sleepTicks(4);
            test.command("tp @s 14.5 -57 -5.5");
            for (int i = 0; i < 4; i++) {
                test.sleepTicks(6);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.sleepTicks(4);
            }
        } else {
            test.skip("Create is not installed: no bearing contraption was built (MOD-1)");
        }

        if (Host.get().isModLoaded("sable") && SableCompat.available()) {
            // A slab of stone in the air, made into a physics object. It falls, which is the motion to record. Aboard: the
            // recorder standing loose (MOD-4), a mob standing loose, and a mob sitting in a boat
            test.command("fill 20 -50 -8 23 -50 -5 minecraft:stone");
            test.command("summon minecraft:pig 20.5 -49 -5.5 {CustomName:'\"Deckhand\"'," + still + "}");
            test.command("setblock 22 -49 -8 create:white_seat");
            test.command("summon minecraft:pig 22.5 -48.2 -7.5 {CustomName:'\"Sailor\"'," + still + "}");
            test.command("create glue 20 -50 -8 23 -49 -5");
            test.command("tp @s 21.5 -49 -6.5");
            test.sleepTicks(25);
            if (!seated(test, minecraft, "Sailor")) {
                test.skip("the mob did not sit in the Create seat on the platform: a seated mob on a physics object was not tried");
            }
            test.command("sable assemble area 20 -50 -8 23 -49 -5");
            test.waitFor(() -> crafts(minecraft) >= 1, 15, "the platform to become a physics object");
            builtPlatform = true;
            test.log("physics platform assembled, carrying the recorder standing, a standing mob and a mob in a Create seat");
            platformNotesFrom = RecordingSteps.notes.size();
            // Note where everything is while it falls: these checks prove recorded motion, not just a resting place
            for (int i = 0; i < 5; i++) {
                test.sleepTicks(4);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.sleepTicks(4);
            }

            // A second slab, this time with the recorder sitting in a Create seat on it: a seated player on a physics object
            int before = crafts(minecraft);
            test.command("fill 26 -50 -8 29 -50 -5 minecraft:stone");
            test.command("setblock 27 -49 -7 create:white_seat");
            test.command("create glue 26 -50 -8 29 -49 -5");
            test.command("tp @s 28.5 -49 -5.5 135 50");
            test.sleepTicks(8);
            boolean seated = sit(test, minecraft, new BlockPos(27, -49, -7));
            test.command("sable assemble area 26 -50 -8 29 -49 -5");
            test.waitFor(() -> crafts(minecraft) > before, 15, "the second platform to become a physics object");
            for (int i = 0; i < 5; i++) {
                test.sleepTicks(4);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.sleepTicks(4);
            }
            test.command("ride @s dismount");
            test.sleepTicks(6);
            if (seated) {
                test.log("second physics platform assembled, carrying the recorder in a Create seat");
            } else {
                test.skip("the recorder could not take the Create seat on the second platform: a seated player on a physics object was not tried");
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

    /** Whether the entity with this name is riding something, as this game sees it */
    private static boolean seated(SelfTest test, Minecraft minecraft, String name) throws Exception {
        return test.onClient(() -> {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity.hasCustomName() && entity.getCustomName().getString().equals(name)) {
                    return entity.isPassenger();
                }
            }
            return false;
        });
    }

    /** Right-clicks a seat the way the game does when a player clicks it, and says whether the player is now sitting */
    private static boolean sit(SelfTest test, Minecraft minecraft, BlockPos seat) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            test.onClientRun(() -> minecraft.gameMode.useItemOn(minecraft.player, net.minecraft.world.InteractionHand.OFF_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(seat), net.minecraft.core.Direction.UP, seat, false)));
            test.sleepTicks(10);
            if (test.onClient(() -> minecraft.player.isPassenger())) {
                return true;
            }
        }
        return false;
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
        // How this balloon is built and flown, for anyone who wants the same by hand:
        //  - it is Aeronautics' own demonstration balloon (the "envelope" ponder scene): a five by five basket of andesite
        //    casing with dark oak rails, an adjustable burner standing in the middle of the basket floor, four fence posts
        //    up to an envelope of white and orange envelope blocks, open underneath, right above the burner
        //  - the burner burns no fuel. It gives hot air while it has a redstone signal, in proportion to the signal, up to
        //    the amount set on its own dial (500 cubic metres here, which is its most). A redstone block against it is a
        //    full signal: that is "lit". Take the signal away and the air cools and the balloon comes down
        //  - only the balloon is made into a craft. The scene's floor of snow and concrete is left on the ground: taken
        //    along, it is far too heavy for the envelope, which is why the balloon would not leave the ground before
        BlockPos power = burner.below();
        test.command("setblock " + power.getX() + " " + power.getY() + " " + power.getZ() + " minecraft:redstone_block");
        // Aboard: a mob sitting in the basket's seat and one standing on its floor
        String still = "attributes:[{id:\"minecraft:generic.movement_speed\",base:0.0}],PersistenceRequired:1b,Silent:1b";
        BlockPos seat = origin.offset(2, 2, 4);
        test.command("summon minecraft:pig " + (seat.getX() + 0.5) + " " + (seat.getY() + 0.8) + " " + (seat.getZ() + 0.5) + " {CustomName:'\"Aeronaut\"'," + still + "}");
        test.command("summon minecraft:pig " + (origin.getX() + 5.5) + " " + (origin.getY() + 2) + " " + (origin.getZ() + 5.5) + " {CustomName:'\"Ballast\"'," + still + "}");
        test.sleepTicks(25);
        boolean aeronautSeated = seated(test, minecraft, "Aeronaut");
        if (!aeronautSeated) {
            test.skip("the mob did not sit in the seat of the balloon's basket: a seated mob on a flying craft was not tried");
        }
        int before = crafts(minecraft);
        // A region tight around the balloon, from the basket floor up: a loose one takes in whatever is nearby as a second
        // object, and the floor under the basket is dead weight
        test.command("sable assemble area " + origin.getX() + " " + (origin.getY() + 1) + " " + origin.getZ() + " "
            + (origin.getX() + size.getX() - 1) + " " + (origin.getY() + size.getY() - 1) + " " + (origin.getZ() + size.getZ() - 1));
        test.waitFor(() -> crafts(minecraft) > before, 15, "the balloon to become a physics object");
        builtBalloon = true;

        // Which craft it is, and where its burner now really is (a craft's blocks live in a region of their own)
        Vec3 burnerCentre = Vec3.atCenterOf(burner);
        balloon = test.onClient(() -> {
            java.util.UUID nearest = null;
            double best = 24.0;
            for (Host.CraftInfo craft : Host.crafts().crafts(minecraft.level)) {
                double distance = craft.bounds().getCenter().distanceTo(burnerCentre);
                if (distance < best) {
                    best = distance;
                    nearest = craft.uniqueId();
                }
            }
            return nearest;
        });
        SelfTest.check(balloon != null, "the balloon could not be told apart from the other craft");
        burnerPlot = test.onClient(() -> {
            Vec3 inPlot = SableCompat.plotPosition(minecraft.level, balloon, burnerCentre);
            if (inPlot == null) {
                return null;
            }
            // The craft may have stirred since it was assembled: the burner is the block entity of its kind right there
            for (BlockPos near : BlockPos.betweenClosed(BlockPos.containing(inPlot).offset(-1, -1, -1), BlockPos.containing(inPlot).offset(1, 1, 1))) {
                net.minecraft.world.level.block.entity.BlockEntity found = minecraft.level.getBlockEntity(near);
                if (found != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(found.getType()).getPath().contains("burner")) {
                    return near.immutable();
                }
            }
            return null;
        });
        SelfTest.check(burnerPlot != null, "the balloon's burner was not found on the craft");
        balloonAssembledTick = comedymonkey.dailies.capture.Recorder.get().recordingTick();
        test.log("hot air balloon assembled as craft " + balloon + " at tick " + balloonAssembledTick + ", burner at " + burner.toShortString() + " with a redstone block under it"
            + (aeronautSeated ? ", a mob in its seat" : "") + " and a mob standing in the basket");

        // The climb: under its own lift, at least ten blocks
        double ground = test.onClient(() -> balloonBase(minecraft));
        StringBuilder heights = new StringBuilder();
        double height = 0.0;
        long deadline = System.currentTimeMillis() + 60_000;
        while (height < 12.0 && System.currentTimeMillis() < deadline) {
            test.sleepTicks(6);
            RecordingSteps.Note note = test.onClient(() -> flightNote(minecraft));
            flightNotes.add(note);
            test.sleepTicks(4);
            height = test.onClient(() -> balloonBase(minecraft)) - ground;
            if (flightNotes.size() % 2 == 0) {
                heights.append(String.format(" %d: %.1f;", note.tick(), height));
            }
        }
        balloonClimbedTick = comedymonkey.dailies.capture.Recorder.get().recordingTick();
        test.log("balloon " + balloon + " height above where it stood, in blocks by recording tick:" + heights);
        SelfTest.check(height >= 10.0, "the balloon did not fly: with its burner lit it rose " + String.format("%.1f", height) + " blocks in a minute (" + heights + ")");
        double climbRate = height / Math.max(1, balloonClimbedTick - balloonAssembledTick) * 20.0;

        // The burner turned down: the redstone block is taken away, as a player would break it
        test.command("setblock " + burnerPlot.getX() + " " + (burnerPlot.getY() - 1) + " " + burnerPlot.getZ() + " minecraft:air");
        balloonBurnerOffTick = comedymonkey.dailies.capture.Recorder.get().recordingTick();
        StringBuilder after = new StringBuilder();
        double top = height;
        double last = height;
        double lastRate = climbRate;
        for (int i = 0; i < 16; i++) {
            test.sleepTicks(6);
            RecordingSteps.Note note = test.onClient(() -> flightNote(minecraft));
            flightNotes.add(note);
            test.sleepTicks(4);
            double now = test.onClient(() -> balloonBase(minecraft)) - ground;
            lastRate = (now - last) * 2.0;
            last = now;
            top = Math.max(top, now);
            after.append(String.format(" %d: %.1f;", note.tick(), now));
        }
        test.log("balloon with its burner turned down at tick " + balloonBurnerOffTick + ":" + after + " (it climbed at " + String.format("%.1f", climbRate)
            + " blocks a second, and was last moving at " + String.format("%.1f", lastRate) + ")");
        SelfTest.check(lastRate < climbRate * 0.5, "turning the burner down did not slow the balloon: it climbed at " + climbRate + " blocks a second and still rises at " + lastRate);
        // The flight is noted; what the two do when the basket bumps down again is not part of it
        test.command("kill @e[type=minecraft:pig,name=Aeronaut]");
        test.command("kill @e[type=minecraft:pig,name=Ballast]");
        test.sleepTicks(30);
    }

    /** Where everything was at ticks of the balloon's flight, with the balloon's own state. Checked by BalloonSteps */
    static final java.util.List<RecordingSteps.Note> flightNotes = new java.util.ArrayList<>();

    /** Call on the client thread, so the tick and the positions belong together */
    static RecordingSteps.Note flightNote(Minecraft minecraft) {
        return new RecordingSteps.Note(comedymonkey.dailies.capture.Recorder.get().recordingTick(), flightPositions(minecraft, null), System.currentTimeMillis(), minecraft.level.getGameTime());
    }

    /**
     * {@link #positions} and the balloon's own state: which way it points, and its burner's flame and filling. Those
     * are not places, but they are held to the same third of a block: the nose within four degrees, the signal exact,
     * the air within fifteen cubic metres.
     */
    static Map<String, Vec3> flightPositions(Minecraft minecraft, String recorderId) {
        Map<String, Vec3> positions = positions(minecraft, recorderId);
        Object flying = balloon == null ? null : Host.crafts().craftById(minecraft.level, balloon);
        if (flying != null) {
            Vec3 nose = Host.crafts().craftForward(flying, 1.0F);
            if (nose != null) {
                positions.put("balloon nose", nose.scale(5.0));
            }
            double[] burner = burnerState(minecraft);
            if (burner != null) {
                positions.put("balloon burner signal", new Vec3(burner[0], 0.0, 0.0));
                positions.put("balloon air", new Vec3(burner[1] / 50.0, burner[2] / 50.0, 0.0));
            }
        }
        return positions;
    }

    /** The balloon's unique id, where its burner is in the craft's own region, and the ticks of its flight */
    static java.util.UUID balloon = null;
    static BlockPos burnerPlot = null;
    static int balloonAssembledTick = -1;
    static int balloonClimbedTick = -1;
    static int balloonBurnerOffTick = -1;

    /** The height of the underside of the balloon. Client thread */
    static double balloonBase(Minecraft minecraft) {
        Object craft = balloon == null || minecraft.level == null ? null : Host.crafts().craftById(minecraft.level, balloon);
        net.minecraft.world.phys.AABB bounds = craft == null ? null : Host.crafts().getSubLevelBounds(craft);
        return bounds == null ? Double.NaN : bounds.minY;
    }

    /** Everything the burner holds, for the message of a check that failed on it. Client thread */
    static String burnerDetail(Minecraft minecraft) {
        try {
            net.minecraft.world.level.block.entity.BlockEntity burner = burnerPlot == null ? null : minecraft.level.getBlockEntity(burnerPlot);
            if (burner == null) {
                return "; there is no block entity at the burner's place " + burnerPlot + ", where the block is " + (burnerPlot == null ? "unknown" : minecraft.level.getBlockState(burnerPlot));
            }
            return "; the burner " + burner.getClass().getSimpleName() + " at " + burnerPlot + " holds " + java.util.Arrays.toString(burnerState(minecraft)) + " and saves as "
                + burner.saveWithoutMetadata(minecraft.level.registryAccess());
        } catch (Throwable t) {
            return "; the burner could not be read: " + t;
        }
    }

    /** The burner's signal strength (0 is out, 15 full) and the balloon's size and filling as the game was last told, or null */
    static double[] burnerState(Minecraft minecraft) {
        if (burnerPlot == null || minecraft.level == null) {
            return null;
        }
        net.minecraft.world.level.block.entity.BlockEntity burner = minecraft.level.getBlockEntity(burnerPlot);
        if (burner == null) {
            return null;
        }
        try {
            double signal = ((Number) burner.getClass().getMethod("getSignalStrength").invoke(burner)).doubleValue();
            double volume = 0.0;
            double filled = 0.0;
            for (Class<?> at = burner.getClass(); at != null; at = at.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = at.getDeclaredField("clientBalloonInfo");
                    field.setAccessible(true);
                    Object info = field.get(burner);
                    if (info != null) {
                        java.lang.reflect.Method volumeOf = info.getClass().getMethod("clientBalloonVolume");
                        java.lang.reflect.Method filledOf = info.getClass().getMethod("clientBalloonFilled");
                        volumeOf.setAccessible(true);
                        filledOf.setAccessible(true);
                        volume = ((Number) volumeOf.invoke(info)).doubleValue();
                        filled = ((Number) filledOf.invoke(info)).doubleValue();
                    }
                    break;
                } catch (NoSuchFieldException higher) {
                    // Look further up
                }
            }
            return new double[]{signal, volume, filled};
        } catch (Throwable t) {
            return null;
        }
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
                positions.put("recorder" + SEATED, new Vec3(entity.isPassenger() ? 1 : 0, 0, 0));
                // Where they stand on a craft's deck, measured from the middle of the craft, when they stand on one
                Vec3 onCraft = SableCompat.offsetOnCraft(entity);
                if (onCraft != null) {
                    positions.put(RECORDER_ON_CRAFT, onCraft);
                }
            } else if (entity.hasCustomName()) {
                positions.put(entity.getCustomName().getString(), entity.position());
                positions.put(entity.getCustomName().getString() + SEATED, new Vec3(entity.isPassenger() ? 1 : 0, 0, 0));
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
