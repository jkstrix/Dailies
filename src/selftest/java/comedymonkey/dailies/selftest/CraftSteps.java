package comedymonkey.dailies.selftest;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.compat.sable.SableCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
        String still = "attributes:[{id:\"minecraft:movement_speed\",base:0.0}],PersistenceRequired:1b,Silent:1b";
        if (Host.get().isModLoaded("create")) {
            // An arm of planks with a seat on it, a mob sitting in the seat and another standing on the arm, then a motor
            // and a bearing under it. The bearing assembles by itself once it turns; it is placed last so everything is aboard
            test.command("setblock 14 -58 -6 minecraft:oak_planks");
            test.command("setblock 15 -58 -6 minecraft:oak_planks");
            test.command("setblock 16 -58 -6 minecraft:oak_planks");
            test.command("setblock 17 -58 -6 minecraft:oak_planks");
            test.command("setblock 16 -57 -6 create:white_seat");
            test.command("setblock 15 -57 -6 create:red_seat");
            test.command("summon minecraft:pig 16.5 -56.2 -5.5 {CustomName:\"Rider\"," + still + "}");
            test.command("summon minecraft:pig 17.5 -57 -5.5 {CustomName:\"Stander\"," + still + "}");
            // Blocks only travel with a bearing when they are glued to what it carries
            test.command("create glue 14 -58 -6 17 -57 -6");
            test.waitTicks(25);
            boolean riderSeated = seated(test, minecraft, "Rider");
            // The recorder takes the second seat before the contraption is assembled: whoever is not aboard then is left behind
            test.command("tp @s 15.5 -56 -4.2 180 60");
            test.waitTicks(6);
            boolean recorderSeated = sit(test, minecraft, new BlockPos(15, -57, -6));
            test.command("setblock 14 -60 -6 create:creative_motor[facing=up]");
            // A row of kinetics that turn nothing, in view of the bearing: what Flywheel draws as blocks (WatchingSteps.machines)
            test.command("setblock 10 -60 -9 create:shaft[axis=x]");
            test.command("setblock 11 -60 -9 create:cogwheel[axis=x]");
            test.command("setblock 12 -60 -9 create:shaft[axis=x]");
            test.command("setblock 13 -60 -9 create:large_cogwheel[axis=y]");
            test.command("setblock 15 -60 -9 create:gearbox");
            test.command("setblock 16 -60 -9 create:shaft[axis=x]");
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
                    test.waitTicks(8);
                }
            }
            SelfTest.check(assembled, "the bearing did not assemble its contraption in four tries");
            builtContraption = true;
            test.log("bearing contraption assembled, carrying a standing mob" + (riderSeated ? ", a mob in a Create seat" : "") + (recorderSeated ? ", the recorder in a Create seat" : ""));
            // Create is installed, so a seat that cannot be taken is a fault of the run, not something missing (TEST-1)
            if (!riderSeated) {
                test.fault("the mob did not sit in the Create seat on the contraption, so a seated mob on a contraption was not tried");
            }
            if (!recorderSeated) {
                test.fault("the recorder could not take the Create seat on the contraption, so a seated player on a contraption was not tried");
            }
            contraptionNotesFrom = RecordingSteps.notes.size();
            // Seated first, as the arm turns
            for (int i = 0; i < 4; i++) {
                test.waitTicks(6);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.waitTicks(4);
            }
            // Then standing loose on the turning arm
            test.command("ride @s dismount");
            test.waitTicks(4);
            test.command("tp @s 14.5 -57 -5.5");
            for (int i = 0; i < 4; i++) {
                test.waitTicks(6);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.waitTicks(4);
            }
        } else {
            test.skip("Create is not installed: no bearing contraption was built (MOD-1)");
        }

        if (Host.get().isModLoaded("sable") && SableCompat.available()) {
            // A slab of stone in the air, made into a physics object. It falls, which is the motion to record. Aboard: the
            // recorder standing loose (MOD-4), a mob standing loose, and a mob sitting in a boat
            test.command("fill 20 -50 -8 23 -50 -5 minecraft:stone");
            test.command("summon minecraft:pig 20.5 -49 -5.5 {CustomName:\"Deckhand\"," + still + "}");
            test.command("setblock 22 -49 -8 create:white_seat");
            test.command("summon minecraft:pig 22.5 -48.2 -7.5 {CustomName:\"Sailor\"," + still + "}");
            test.command("create glue 20 -50 -8 23 -49 -5");
            test.command("tp @s 21.5 -49 -6.5");
            test.waitTicks(25);
            if (!seated(test, minecraft, "Sailor")) {
                test.fault("the mob did not sit in the Create seat on the platform, so a seated mob on a physics object was not tried");
            }
            test.command("sable assemble area 20 -50 -8 23 -49 -5");
            test.waitFor(() -> crafts(minecraft) >= 1, 15, "the platform to become a physics object");
            builtPlatform = true;
            test.log("physics platform assembled, carrying the recorder standing, a standing mob and a mob in a Create seat");
            platformNotesFrom = RecordingSteps.notes.size();
            // Note where everything is while it falls: these checks prove recorded motion, not just a resting place
            for (int i = 0; i < 5; i++) {
                test.waitTicks(4);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.waitTicks(4);
            }

            // A second slab, this time with the recorder sitting in a Create seat on it: a seated player on a physics object
            int before = crafts(minecraft);
            test.command("fill 26 -50 -8 29 -50 -5 minecraft:stone");
            test.command("setblock 27 -49 -7 create:white_seat");
            test.command("create glue 26 -50 -8 29 -49 -5");
            test.command("tp @s 28.5 -49 -5.5 135 50");
            test.waitTicks(8);
            boolean seated = sit(test, minecraft, new BlockPos(27, -49, -7));
            test.command("sable assemble area 26 -50 -8 29 -49 -5");
            test.waitFor(() -> crafts(minecraft) > before, 15, "the second platform to become a physics object");
            for (int i = 0; i < 5; i++) {
                test.waitTicks(4);
                RecordingSteps.notes.add(test.onClient(() -> RecordingSteps.Note.take(minecraft)));
                test.waitTicks(4);
            }
            test.command("ride @s dismount");
            test.waitTicks(6);
            if (seated) {
                test.log("second physics platform assembled, carrying the recorder in a Create seat");
            } else {
                test.fault("the recorder could not take the Create seat on the second platform, so a seated player on a physics object was not tried");
            }

            if (Host.get().isModLoaded("aeronautics")) {
                buildBalloon(test, minecraft);
            } else {
                test.skip("Create Aeronautics is not installed: no balloon was built (MOD-2 with a flying craft)");
            }
            loseCraft(test, minecraft);
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
    static boolean sit(SelfTest test, Minecraft minecraft, BlockPos seat) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            test.onClientRun(() -> minecraft.gameMode.useItemOn(minecraft.player, net.minecraft.world.InteractionHand.OFF_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(seat), net.minecraft.core.Direction.UP, seat, false)));
            test.waitTicks(10);
            if (test.onClient(() -> minecraft.player.isPassenger())) {
                return true;
            }
        }
        return false;
    }

    private static void buildBalloon(SelfTest test, Minecraft minecraft) throws Exception {
        buildBalloon(test, minecraft, new BlockPos(-24, -60, -24), true, true);
    }

    /**
     * @param origin the lowest corner of the balloon's scene, in the overworld near the recorder
     * @param goBack whether the recorder is put back where it stood once the flight is noted; the camera route stays aboard
     */
    static void buildBalloon(SelfTest test, Minecraft minecraft, BlockPos origin, boolean goBack) throws Exception {
        buildBalloon(test, minecraft, origin, goBack, false);
    }

    /**
     * @param speedRecord whether the balloon is then pushed along for a speed record (plan 2.1), as the recording steps
     *                    want and the camera route, which films the flight as it is, does not
     */
    private static void buildBalloon(SelfTest test, Minecraft minecraft, BlockPos origin, boolean goBack, boolean speedRecord) throws Exception {
        Identifier name = Identifier.fromNamespaceAndPath("aeronautics", "ponder/hot_air/envelope");
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        // The structure is one of the mod's own demonstration scenes, which live with its client resources
        Identifier file = Identifier.fromNamespaceAndPath("aeronautics", "ponder/hot_air/envelope.nbt");
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
            template.placeInWorld(level, origin, origin, new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(), level.getRandom(), 2);
            return new BlockPos(template.getSize().getX(), template.getSize().getY(), template.getSize().getZ());
        }).get();
        test.waitTicks(10);
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
        String still = "attributes:[{id:\"minecraft:movement_speed\",base:0.0}],PersistenceRequired:1b,Silent:1b";
        BlockPos seat = origin.offset(2, 2, 4);
        test.command("summon minecraft:pig " + (seat.getX() + 0.5) + " " + (seat.getY() + 0.8) + " " + (seat.getZ() + 0.5) + " {CustomName:\"Aeronaut\"," + still + "}");
        test.command("summon minecraft:pig " + (origin.getX() + 5.5) + " " + (origin.getY() + 2) + " " + (origin.getZ() + 5.5) + " {CustomName:\"Ballast\"," + still + "}");
        test.waitTicks(25);
        boolean aeronautSeated = seated(test, minecraft, "Aeronaut");
        if (!aeronautSeated) {
            test.fault("the mob did not sit in the seat of the balloon's basket, so a seated mob on a flying craft was not tried");
        }
        int before = crafts(minecraft);
        // The recorder steps into the basket first, as a player would to fly it. The game only counts a craft as the
        // player's when it appears within sixteen blocks of them (CraftRules), and boarding, the first flight and the
        // altitude record are only logged for someone aboard; from the far platform none of them were
        Vec3 wasAt = test.onClient(() -> minecraft.player.position());
        BlockPos aboard = minecraft.getSingleplayerServer().submit(() -> basketSpot(level, burner, seat, origin.offset(5, 2, 5))).get();
        if (aboard != null) {
            test.command("tp @s " + (aboard.getX() + 0.5) + " " + aboard.getY() + " " + (aboard.getZ() + 0.5));
            test.waitTicks(10);
        } else {
            test.skip("no room was found in the balloon's basket for the recorder: it watched the balloon from beside it");
            test.command("tp @s " + (origin.getX() - 2.5) + " " + (origin.getY() + 1) + " " + (origin.getZ() - 2.5));
            test.waitTicks(10);
        }
        // A region tight around the balloon, from the basket floor up: a loose one takes in whatever is nearby as a second
        // object, and the floor under the basket is dead weight
        // Typed by the player, as in play, so that the game sees the assembly and logs it as a craft moment (plan 2.1)
        PointOfViewSteps.typed(test, "sable assemble area " + origin.getX() + " " + (origin.getY() + 1) + " " + origin.getZ() + " "
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
            test.waitTicks(6);
            RecordingSteps.Note note = test.onClient(() -> flightNote(minecraft));
            flightNotes.add(note);
            test.waitTicks(4);
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
            test.waitTicks(6);
            RecordingSteps.Note note = test.onClient(() -> flightNote(minecraft));
            flightNotes.add(note);
            test.waitTicks(4);
            double now = test.onClient(() -> balloonBase(minecraft)) - ground;
            lastRate = (now - last) * 2.0;
            last = now;
            top = Math.max(top, now);
            after.append(String.format(" %d: %.1f;", note.tick(), now));
        }
        test.log("balloon with its burner turned down at tick " + balloonBurnerOffTick + ":" + after + " (it climbed at " + String.format("%.1f", climbRate)
            + " blocks a second, and was last moving at " + String.format("%.1f", lastRate) + ")");
        SelfTest.check(lastRate < climbRate * 0.5, "turning the burner down did not slow the balloon: it climbed at " + climbRate + " blocks a second and still rises at " + lastRate);
        // After the noted flight, which BalloonSteps holds playback to, so the pushes below move nothing it compares
        if (speedRecord) {
            pushForSpeed(test, minecraft);
        }
        // The flight is noted; what the two do when the basket bumps down again is not part of it, and the recorder goes
        // back to where it stood before, so the steps after this one find it where they always have
        test.command("kill @e[type=minecraft:pig,name=Aeronaut]");
        test.command("kill @e[type=minecraft:pig,name=Ballast]");
        if (goBack) {
            test.command(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f", wasAt.x, wasAt.y, wasAt.z));
        }
        test.waitTicks(30);
    }

    /**
     * A place on the basket floor beside the burner with room for a player to stand: open for two blocks, a floor
     * under it, and not where either mob is. The floor test keeps it inside the basket, which stands a block above the
     * scene's ground. Server thread
     */
    private static BlockPos basketSpot(ServerLevel level, BlockPos burner, BlockPos seat, BlockPos ballast) {
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos pos = burner.offset(dx, 0, dz);
                if ((dx == 0 && dz == 0) || pos.equals(seat) || pos.equals(ballast)) {
                    continue;
                }
                boolean open = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
                boolean floor = !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
                double distance = dx * dx + dz * dz;
                if (open && floor && distance < nearest) {
                    nearest = distance;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Staged for craft moments (plan 2.1)

    /** Whether the balloon was pushed for a speed record, and what each push did, for PointOfViewSteps' check and its message */
    static boolean pushedForSpeed = false;
    static String speedDetail = "";
    /** The slab taken out of the world while it fell, and how fast it was falling then; null when it was not */
    static java.util.UUID lostCraft = null;
    static String lostDetail = "";

    /**
     * A speed record aboard the balloon (speed_record): with the recorder still standing in the basket, Sable's own
     * impulse command shoves the balloon west, then again twice as hard, then stops it. One shove could never make a
     * record: the first speed of four blocks a second or more is only where the best starts, and a record has to beat the
     * best by a tenth (CraftRules.Best). The bests are forgotten first, since the platforms' fall with the recorder aboard
     * may already have set one, and a record within a minute of another is not logged.
     * <p>
     * An impulse is mass times change of speed. The mass is Sable's own, read on the server; in case its units are not
     * blocks a second, the first shove is measured and tried again harder when it falls short, and the second and the
     * stop are scaled from what it did
     */
    private static void pushForSpeed(SelfTest test, Minecraft minecraft) throws Exception {
        java.util.UUID craft = balloon;
        test.onClientRun(() -> {
            if (comedymonkey.dailies.moments.Moments.gameplay() != null) {
                comedymonkey.dailies.moments.Moments.gameplay().crafts().forgetRecordsForSelfTest();
            }
        });
        double mass = serverMass(minecraft, craft);
        double perSpeed = Double.isFinite(mass) && mass > 0.0 ? mass : 1000.0;
        StringBuilder detail = new StringBuilder("mass " + decimal(mass));
        double first = 0.0;
        double impulse = 0.0;
        for (int attempt = 0; attempt < 3 && first < 4.5; attempt++) {
            if (attempt > 0) {
                // Too gentle for the units guessed: aim at five and a half blocks a second from what the last shove did
                perSpeed *= Math.min(100.0, Math.max(2.0, 5.5 / Math.max(first, 0.05)));
            }
            impulse = perSpeed * 5.0;
            first = shove(test, minecraft, craft, -impulse);
            detail.append("; shoved with ").append(decimal(impulse)).append(", reached ").append(decimal(first)).append(" blocks a second");
        }
        // The same way again, twice as hard: at least twice the speed the best started from, whatever is left of the first
        double second = shove(test, minecraft, craft, -impulse * 2.0);
        boolean aboard = test.onClient(() -> {
            Object under = Host.crafts().getSubLevel(minecraft.player);
            return under != null && craft.equals(Host.crafts().craftId(under));
        });
        detail.append("; then with ").append(decimal(impulse * 2.0)).append(", reached ").append(decimal(second)).append(" blocks a second, the recorder ")
            .append(aboard ? "still aboard" : "no longer aboard");
        // Stopped again, so it stays in sight of the steps after this one, which count it among the craft in the world
        Vec3 velocity = velocity(test, minecraft, craft);
        double perBlock = first > 0.0 ? impulse / first : perSpeed;
        if (velocity != null) {
            test.command(String.format(java.util.Locale.ROOT, "sable physics impulse %s linear %.3f 0 %.3f global", craft, -velocity.x * perBlock, -velocity.z * perBlock));
            test.waitTicks(10);
            Vec3 after = velocity(test, minecraft, craft);
            detail.append("; stopped from ").append(decimal(velocity.length())).append(" to ").append(after == null ? "unknown" : decimal(after.length())).append(" blocks a second");
        }
        pushedForSpeed = true;
        speedDetail = detail.toString();
        test.log("balloon pushed for a speed record: " + speedDetail);
    }

    /** Shoves a craft along X with Sable's impulse command; the fastest it then went over the next half second, in blocks a second */
    private static double shove(SelfTest test, Minecraft minecraft, java.util.UUID craft, double impulseX) throws Exception {
        java.util.List<Vec3> path = new java.util.ArrayList<>();
        path.add(test.onClient(() -> craftCentre(minecraft, craft)));
        test.command(String.format(java.util.Locale.ROOT, "sable physics impulse %s linear %.3f 0 0 global", craft, impulseX));
        double fastest = 0.0;
        for (int tick = 0; tick < 10; tick++) {
            test.waitTicks(1);
            path.add(test.onClient(() -> craftCentre(minecraft, craft)));
            fastest = Math.max(fastest, speedOver(path));
        }
        return fastest;
    }

    /** How fast the last five ticks of a path went, in blocks a second; 0 while it is shorter or has a gap */
    private static double speedOver(java.util.List<Vec3> path) {
        if (path.size() < 6) {
            return 0.0;
        }
        Vec3 now = path.get(path.size() - 1);
        Vec3 then = path.get(path.size() - 6);
        return now == null || then == null ? 0.0 : now.distanceTo(then) * 4.0;
    }

    /** A craft's velocity over five ticks, in blocks a second, or null when it cannot be seen */
    private static Vec3 velocity(SelfTest test, Minecraft minecraft, java.util.UUID craft) throws Exception {
        Vec3 from = test.onClient(() -> craftCentre(minecraft, craft));
        test.waitTicks(5);
        Vec3 to = test.onClient(() -> craftCentre(minecraft, craft));
        return from == null || to == null ? null : to.subtract(from).scale(4.0);
    }

    /**
     * A craft lost while it moves (craft_lost): a slab dropped from high up beside the recorder, taken out of the world
     * with Sable's own command while it falls faster than CraftRules.LOST_SPEED. The game sees a craft vanish at speed
     * near the player; the companion, when there is one, sees Sable remove it. Before the recording's pause, so the
     * notes and cuts after it never hold the slab
     */
    private static void loseCraft(SelfTest test, Minecraft minecraft) throws Exception {
        Vec3 wasAt = test.onClient(() -> minecraft.player.position());
        // A ledge for the recorder beside the column the slab falls down, twelve blocks under the slab
        test.command("setblock 38 -31 -7 minecraft:stone");
        test.command("tp @s 38.5 -30 -6.5 -90 -30");
        test.command("fill 40 -18 -8 43 -18 -5 minecraft:stone");
        test.waitTicks(10);
        java.util.Set<java.util.UUID> before = test.onClient(() -> craftIds(minecraft));
        test.command("sable assemble area 40 -18 -8 43 -18 -5");
        test.waitFor(() -> newCraft(minecraft, before) != null, 15, "the falling slab to become a physics object");
        java.util.UUID slab = newCraft(minecraft, before);
        java.util.List<Vec3> path = new java.util.ArrayList<>();
        double speed = 0.0;
        boolean removed = false;
        // Taken away at sixteen blocks a second, twice the speed that counts as lost, so that the game's own look, a quarter
        // of a second earlier, has seen it going fast enough; or before it lands, however fast it is going then
        for (int tick = 0; tick < 100 && !removed; tick++) {
            Vec3 at = test.onClient(() -> craftCentre(minecraft, slab));
            if (at == null) {
                break;
            }
            path.add(at);
            speed = speedOver(path);
            if (speed >= 16.0 || at.y < -45.0) {
                test.command("sable remove " + slab);
                removed = true;
            } else {
                test.waitTicks(1);
            }
        }
        double fallen = path.isEmpty() ? 0.0 : path.get(0).y - path.get(path.size() - 1).y;
        if (removed) {
            test.waitFor(() -> newCraft(minecraft, before) == null, 10, "the slab to be gone once removed");
        }
        test.command("setblock 38 -31 -7 minecraft:air");
        test.command(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f", wasAt.x, wasAt.y, wasAt.z));
        lostDetail = String.format(java.util.Locale.ROOT, "the slab was %s falling at %.1f blocks a second, %.1f blocks below where it was assembled",
            removed ? "taken away" : "not taken away (it was out of sight first)", speed, fallen);
        lostCraft = removed ? slab : null;
        test.log("craft lost: " + lostDetail + " (craft " + slab + ")");
        if (!removed) {
            test.fault("the falling slab was not removed while it fell, so a craft lost while moving was not tried: " + lostDetail);
        }
        test.waitTicks(20);
    }

    /** The ids of the craft this game knows. Client thread */
    private static java.util.Set<java.util.UUID> craftIds(Minecraft minecraft) {
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        for (Host.CraftInfo info : Host.crafts().crafts(minecraft.level)) {
            ids.add(info.uniqueId());
        }
        return ids;
    }

    /** A craft this game knows that is not among these, or null */
    private static java.util.UUID newCraft(Minecraft minecraft, java.util.Set<java.util.UUID> known) {
        try {
            return minecraft.submit(() -> {
                for (java.util.UUID id : craftIds(minecraft)) {
                    if (!known.contains(id)) {
                        return id;
                    }
                }
                return null;
            }).get();
        } catch (Exception e) {
            return null;
        }
    }

    /** The middle of a craft as this game shows it, or null when it is not there. Client thread */
    private static Vec3 craftCentre(Minecraft minecraft, java.util.UUID craft) {
        Object found = minecraft.level == null ? null : Host.crafts().craftById(minecraft.level, craft);
        net.minecraft.world.phys.AABB bounds = found == null ? null : Host.crafts().getSubLevelBounds(found);
        return bounds == null ? null : bounds.getCenter();
    }

    /** The mass Sable gives a craft, read on the server without compiling against Sable; NaN when it cannot be read */
    private static double serverMass(Minecraft minecraft, java.util.UUID craft) throws Exception {
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        return minecraft.getSingleplayerServer().submit(() -> {
            try {
                Object container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer")
                    .getMethod("getContainer", net.minecraft.world.level.Level.class).invoke(null, level);
                for (Object subLevel : (Iterable<?>) invoke(container, "getAllSubLevels")) {
                    if (craft.equals(invoke(subLevel, "getUniqueId"))) {
                        return ((Number) invoke(invoke(subLevel, "getMassTracker"), "getMass")).doubleValue();
                    }
                }
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies self test: the balloon's mass could not be read", t);
            }
            return Double.NaN;
        }).get();
    }

    /** A public method with no arguments, called on an object whose class may be one Sable keeps to itself */
    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        java.lang.reflect.Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
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

    static int contraptions(Minecraft minecraft) {
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

    static int crafts(Minecraft minecraft) {
        try {
            return minecraft.submit(() -> minecraft.level == null ? 0 : SableCompat.craftPositions(minecraft.level, 1.0F).size()).get();
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isContraption(Entity entity) {
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
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
