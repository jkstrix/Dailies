package comedymonkey.dailies.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.editor.SmartCameraDriver;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the "Keyframe" tab of the editor's side panel (specification chapter V). With one keyframe selected it holds
 * a control for every value of that keyframe; with several, what can be done to them all; with none, a button for
 * each kind of keyframe that puts one at the playhead, taking its values from the view where the kind has any to
 * take. Every change goes through the undo history.
 */
final class KeyframeForms {

    /** The height of the viewer's eyes above its feet */
    private static final double EYE_HEIGHT = 1.62;
    private static final double ANY = 1.0e15;

    /** A number of the Smart Camera: what it may be and what it is when the keyframe does not say */
    private record Dial(double min, double max, double otherwise) {
    }

    private static final Map<String, Dial> DIALS = new LinkedHashMap<>();
    private static final Map<String, Boolean> SWITCHES = new LinkedHashMap<>();

    static {
        DIALS.put("distance", new Dial(1, 64, 6));
        DIALS.put("height", new Dial(-32, 64, 2));
        DIALS.put("side_angle", new Dial(-360, 360, 0));
        DIALS.put("position_smoothing", new Dial(0, 5, 0.35));
        DIALS.put("aim_smoothing", new Dial(0, 5, 0.25));
        DIALS.put("radius", new Dial(1, 128, 8));
        DIALS.put("degrees_per_second", new Dial(-360, 360, 20));
        DIALS.put("start_angle", new Dial(-100000, 100000, 0));
        DIALS.put("min_shot_seconds", new Dial(0.5, 60, 3));
        DIALS.put("max_shot_seconds", new Dial(0.5, 120, 10));
        DIALS.put("heading_dead_band", new Dial(0, 90, 15));
        DIALS.put("flight_widen", new Dial(0, 2, 0.6));
        DIALS.put("dead_zone", new Dial(0, 0.8, 0.12));
        DIALS.put("screen_x", new Dial(-1, 1, 0));
        DIALS.put("screen_y", new Dial(-1, 1, 0));
        DIALS.put("lead_room", new Dial(0, 1, 0.25));
        DIALS.put("subject_screen_height", new Dial(0.05, 1, 0.3));
        DIALS.put("min_fov", new Dial(1, 170, 30));
        DIALS.put("max_fov", new Dial(1, 170, 90));
        DIALS.put("fixed_fov", new Dial(0, 170, 0));
        DIALS.put("transition_seconds", new Dial(0, 30, 0.5));
        SWITCHES.put("allow_chase", true);
        SWITCHES.put("allow_tripod", true);
        SWITCHES.put("allow_orbit", true);
        SWITCHES.put("allow_over_shoulder", true);
        SWITCHES.put("allow_side_tracking", true);
        SWITCHES.put("establishing_shots", true);
        SWITCHES.put("auto_zoom", false);
        SWITCHES.put("frame_vehicle", true);
        SWITCHES.put("avoid_obstructions", true);
    }

    private KeyframeForms() {
    }

    // ------------------------------------------------------------------ What to show

    /** A word for what the tab should hold now; when it changes the tab is built afresh */
    static String shape() {
        if (EditorState.audioDraft) {
            return "audio";
        }
        if (EditorState.selection.size() > 1) {
            return "many";
        }
        KeyframeRef ref = EditorState.single();
        Keyframe keyframe = ref == null ? null : EditorState.keyframe(ref);
        if (ref == null || keyframe == null) {
            return "none";
        }
        String shape = "one:" + ref.track() + ":" + keyframe.kind().id();
        if (keyframe instanceof Keyframe.SmartCamera smart) {
            shape += ":" + smart.mode() + ":" + flag(smart.settings(), "auto_zoom");
        }
        return shape;
    }

    static void build(Form form) {
        form.clear();
        if (EditorState.audioDraft) {
            buildAudioDraft(form);
        } else if (EditorState.selection.size() > 1) {
            buildMany(form);
        } else {
            Keyframe keyframe = current();
            if (keyframe == null) {
                buildNone(form);
            } else {
                buildOne(form, keyframe);
            }
        }
    }

    // ------------------------------------------------------------------ Nothing selected

    private static void buildNone(Form form) {
        form.add(new Gadget.Heading(Kit.t("add.heading")));
        form.add(new Gadget.Note(Kit.t("add.note")));
        for (KeyframeKind kind : KeyframeKind.values()) {
            form.add(new Gadget.Push(Kit.t("kind." + kind.id()), () -> add(kind)).tip(Kit.t("add." + kind.id() + ".tip")));
        }
        form.add(new Gadget.Heading(Kit.t("add.more")));
        form.add(new Gadget.Push(Kit.t("pov.link"), KeyframeForms::linkPointsOfView).tip(Kit.t("pov.link.tip")));
        form.add(new Gadget.Push(Kit.t("keyframe.paste"), EditorState::pasteAtPlayhead)
                .when(() -> EditorState.clipboard != null && !EditorState.clipboard.isEmpty()).tip(Kit.t("keyframe.paste.tip")));
        List<String> blends = new ArrayList<>();
        for (Interpolation mode : Interpolation.values()) {
            blends.add(mode.id());
        }
        form.add(new Gadget.Pair(Kit.t("add.default_blend"), new Gadget.Cycle<>(blends, id -> Kit.t("choice." + id),
                () -> EditorState.defaultInterpolation().id(),
                id -> EditorState.saveSetting("defaultInterpolation", id.toUpperCase(java.util.Locale.ROOT)))).tip(Kit.t("add.default_blend.tip")));
    }

    /** Puts a keyframe of the kind at the playhead, taking what it can from the view */
    static void add(KeyframeKind kind) {
        Minecraft minecraft = Minecraft.getInstance();
        int tick = Playback.position();
        Interpolation blend = kind.blends() ? EditorState.defaultInterpolation() : Interpolation.HOLD;
        Vec3 feet = Viewer.position();
        switch (kind) {
            case CAMERA -> put(new Keyframe.Camera(tick, blend, feet.x, feet.y, feet.z, Viewer.yaw(), Viewer.pitch(), clamp(Viewer.roll(), -3600, 3600)));
            case ORBIT -> {
                ViewPick.Ray ray = ViewPick.ahead();
                if (ray == null) {
                    ray = new ViewPick.Ray(feet.add(0.0, EYE_HEIGHT, 0.0), Vec3.directionFromRotation(Viewer.pitch(), Viewer.yaw()));
                }
                BlockHitResult hit = ViewPick.block(ray);
                // The block looked at, or a point a little way ahead when the view is of open air
                Vec3 centre = hit != null ? hit.getLocation() : ray.at(8.0);
                double dx = feet.x - centre.x;
                double dz = feet.z - centre.z;
                double distance = clamp(Math.sqrt(dx * dx + dz * dz), 0, 1000);
                double angle = distance < 1.0e-6 ? Viewer.yaw() : Math.toDegrees(Math.atan2(dx, -dz));
                put(new Keyframe.Orbit(tick, blend, round(centre.x), round(centre.y), round(centre.z), round(distance), round(clamp(feet.y - centre.y, -1000, 1000)), round(angle)));
            }
            case FOLLOW_ENTITY -> EditorState.pick = new EditorState.Pick(Kit.t("pick.follow"), false, false, entity -> {
                Vec3 from = entity.position().add(0.0, entity.getEyeHeight(), 0.0);
                Vec3 offset = Viewer.position().subtract(from);
                if (Math.abs(offset.x) > 1000 || Math.abs(offset.y) > 1000 || Math.abs(offset.z) > 1000) {
                    offset = new Vec3(0.0, 1.5, -4.0);
                }
                put(new Keyframe.FollowEntity(Playback.position(), EditorState.defaultInterpolation(), entity.getUUID(), entity.getId(), Keyframe.BodyPart.EYES,
                        round(offset.x), round(offset.y), round(offset.z), Keyframe.FollowMode.KEEP_RELATIVE));
            }, null, List.of());
            case SMART_CAMERA -> EditorState.pick = new EditorState.Pick(Kit.t("pick.smart"), false, true,
                    entity -> addSmartCamera(entity.getUUID().toString()), null,
                    List.of(new Menu.Item(Kit.t("pick.recorder"), () -> {
                        EditorState.pick = null;
                        addSmartCamera("");
                    })));
            case FIELD_OF_VIEW -> put(new Keyframe.FieldOfView(tick, blend, round(clamp(Viewer.fov() > 0.0 ? Viewer.fov() : ViewPick.fieldOfView(), 1, 170))));
            case CAMERA_SHAKE -> put(new Keyframe.CameraShake(tick, blend, 0.5, 2.0));
            case SPEED -> put(new Keyframe.Speed(tick, blend, 20.0));
            case TIMELAPSE -> put(new Keyframe.Timelapse(tick, blend, 5.0));
            case FREEZE -> put(new Keyframe.Freeze(tick, blend, 2.0));
            case TIME_OF_DAY -> put(new Keyframe.TimeOfDay(tick, blend, minecraft.level == null ? 6000.0 : Math.floorMod(minecraft.level.getDayTime(), 24000L)));
            case BLOCK_CHANGE -> EditorState.pick = new EditorState.Pick(Kit.t("pick.block"), true, false, null, KeyframeForms::addBlockChange,
                    List.of(new Menu.Item(Kit.t("pick.block_ahead"), () -> {
                        BlockHitResult hit = ViewPick.block(ViewPick.ahead());
                        if (hit == null) {
                            EditorState.say(Kit.t("notice.no_block"));
                        } else {
                            EditorState.pick = null;
                            addBlockChange(hit.getBlockPos());
                        }
                    })));
            case AUDIO -> {
                EditorState.selection.clear();
                EditorState.audioDraft = true;
                EditorState.tab = EditorState.TAB_KEYFRAME;
            }
            case POINT_OF_VIEW -> {
                List<comedymonkey.dailies.edit.LinkedRecording> linked = EditorState.scene().linked();
                if (linked.isEmpty()) {
                    EditorState.say(Kit.t("pov.none"));
                } else {
                    // The first other point of view; the form then offers every recording the scene holds
                    String shown = comedymonkey.dailies.edit.Evaluator.pointOfView(EditorState.scene(), tick, comedymonkey.dailies.editor.PointOfView.homeId());
                    comedymonkey.dailies.edit.LinkedRecording pick = linked.get(0);
                    for (comedymonkey.dailies.edit.LinkedRecording candidate : linked) {
                        if (!candidate.recordingId().equals(shown)) {
                            pick = candidate;
                            break;
                        }
                    }
                    put(new Keyframe.PointOfView(tick, Interpolation.HOLD, pick.recordingId(), pick.label()));
                }
            }
            default -> {
            }
        }
        if (EditorState.pick != null) {
            EditorState.sideFolded = false;
        }
    }

    private static void put(Keyframe keyframe) {
        int track = EditorState.history().setKeyframe(keyframe);
        EditorState.select(track, keyframe.tick());
        if (keyframe.kind().drivesCamera()) {
            // Someone laying a camera path expects playback to follow it
            EditorSession.setFollowCamera(true);
        }
    }

    private static void addSmartCamera(String target) {
        JsonObject arguments = new JsonObject();
        int tick = Playback.position();
        arguments.addProperty("tick", tick);
        arguments.addProperty("mode", "chase");
        if (!target.isBlank()) {
            arguments.addProperty("target", target);
        }
        int track = SmartCameraDriver.add(arguments);
        EditorState.select(track, tick);
    }

    private static void addBlockChange(BlockPos position) {
        Minecraft minecraft = Minecraft.getInstance();
        // The block that is there now, so that the keyframe changes nothing until another is typed in its place
        String state = minecraft.level == null ? "minecraft:stone" : BlockStateParser.serialize(minecraft.level.getBlockState(position));
        put(new Keyframe.BlockChange(Playback.position(), Interpolation.HOLD, position.getX(), position.getY(), position.getZ(), state));
    }

    // ------------------------------------------------------------------ An audio keyframe about to be added

    private static void buildAudioDraft(Form form) {
        form.add(new Gadget.Heading(Kit.t("audio.heading")));
        form.add(new Gadget.Note(Kit.t("audio.note")));
        form.add(new Gadget.Pair(label("file"), new Entry(() -> EditorState.audioFile, typed -> {
            EditorState.audioFile = typed.trim();
            return null;
        }).hint(Kit.t("audio.hint"))).tip(tip("file")));
        form.add(new Gadget.Pair(label("volume"), Entry.number(() -> EditorState.audioVolume, 0, 2, false, value -> {
            EditorState.audioVolume = value;
            return null;
        })).tip(tip("volume")));
        form.add(new Gadget.Line(
                new Gadget.Push(Kit.t("audio.add"), KeyframeForms::addAudio).tip(Kit.t("audio.add.tip")),
                new Gadget.Push(Kit.t("button.cancel"), () -> EditorState.audioDraft = false).tip(Kit.t("audio.cancel.tip"))));
    }

    private static void addAudio() {
        String file = EditorState.audioFile.trim();
        if (file.length() >= 2 && file.startsWith("\"") && file.endsWith("\"")) {
            // A path copied from a file manager often comes in quotation marks
            file = file.substring(1, file.length() - 1);
        }
        if (file.isBlank()) {
            EditorState.say(Kit.t("audio.no_file"));
            return;
        }
        String wanted = file;
        double volume = EditorState.audioVolume;
        int tick = Playback.position();
        String recording = Playback.recordingId();
        boolean[] there = new boolean[1];
        // Whether the file exists is found out away from the client thread
        Controls.offThread(() -> there[0] = Files.isRegularFile(Path.of(wanted)), Kit.t("audio.unreadable", wanted), () -> {
            if (!Playback.loaded() || !java.util.Objects.equals(recording, Playback.recordingId())) {
                return;
            }
            if (!there[0]) {
                EditorState.say(Kit.t("audio.missing", wanted));
                return;
            }
            EditorState.audioDraft = false;
            put(new Keyframe.Audio(tick, Interpolation.HOLD, wanted, volume));
        });
    }

    // ------------------------------------------------------------------ Several selected

    private static void buildMany(Form form) {
        form.add(new Gadget.Heading(() -> Kit.t("many.heading", EditorState.selection.size())));
        form.add(new Gadget.Note(Kit.t("many.note")));
        form.add(new Gadget.Line(
                new Gadget.Push(Kit.t("keyframe.copy"), EditorState::copySelected).tip(Kit.t("keyframe.copy.tip")),
                new Gadget.Push(Kit.t("keyframe.remove"), EditorState::removeSelected).tip(Kit.t("keyframe.remove.tip"))));
        List<String> blends = new ArrayList<>();
        for (Interpolation mode : Interpolation.values()) {
            blends.add(mode.id());
        }
        form.add(new Gadget.Pair(label("blend"), new Gadget.Cycle<>(blends, id -> Kit.t("choice." + id), () -> {
            String shared = null;
            for (KeyframeRef ref : EditorState.selection) {
                Keyframe keyframe = EditorState.keyframe(ref);
                if (keyframe != null) {
                    if (shared != null && !shared.equals(keyframe.interpolation().id())) {
                        return null;
                    }
                    shared = keyframe.interpolation().id();
                }
            }
            return shared;
        }, id -> {
            Interpolation mode = Interpolation.parse(id);
            EditHistory history = EditorState.history();
            Set<KeyframeRef> all = new LinkedHashSet<>(EditorState.selection);
            history.beginGroup("Set interpolation of " + all.size() + " keyframes to " + mode.id());
            try {
                for (KeyframeRef ref : all) {
                    history.setInterpolation(ref.track(), ref.tick(), mode);
                }
            } finally {
                history.endGroup();
            }
        })).tip(tip("blend")));
        form.add(new Gadget.Push(Kit.t("many.clear"), EditorState.selection::clear).tip(Kit.t("many.clear.tip")));
    }

    // ------------------------------------------------------------------ One selected

    private static void buildOne(Form form, Keyframe keyframe) {
        KeyframeKind kind = keyframe.kind();
        form.add(new Gadget.Heading(Kit.t("one.heading", Kit.t("kind." + kind.id()))));
        form.add(new Gadget.Pair(label("tick"), Entry.number(() -> {
            Keyframe now = current();
            return now == null ? null : (double) now.tick();
        }, 0, Keyframe.MAX_TICK, true, value -> moveTo((int) Math.rint(value)))).tip(tip("tick")));
        if (kind.blends()) {
            List<String> blends = new ArrayList<>();
            for (Interpolation mode : Interpolation.values()) {
                blends.add(mode.id());
            }
            form.add(new Gadget.Pair(label("blend"), new Gadget.Cycle<>(blends, id -> Kit.t("choice." + id), () -> {
                Keyframe now = current();
                return now == null ? null : now.interpolation().id();
            }, id -> {
                KeyframeRef ref = EditorState.single();
                if (ref != null) {
                    EditorState.history().setInterpolation(ref.track(), ref.tick(), Interpolation.parse(id));
                }
            })).tip(tip("blend")));
        }
        switch (kind) {
            case CAMERA -> {
                numbers(form, "x", "y", "z", "yaw", "pitch", "roll");
                form.add(new Gadget.Line(
                        new Gadget.Push(Kit.t("camera.take"), KeyframeForms::takeView).tip(Kit.t("camera.take.tip")),
                        new Gadget.Push(Kit.t("camera.go"), KeyframeForms::goToCamera).tip(Kit.t("camera.go.tip"))));
            }
            case ORBIT -> {
                boolean onEntity = !text("entity_uuid").isEmpty();
                form.add(new Gadget.Note(() -> text("entity_uuid").isEmpty() ? Kit.t("orbit.round_point") : Kit.t("orbit.round", entityName(text("entity_uuid"))),
                        Controls.PARCHMENT));
                form.add(new Gadget.Line(
                        new Gadget.Push(Kit.t("orbit.pick"), () -> {
                            EditorState.pick = new EditorState.Pick(Kit.t("pick.orbit"), false, false, entity -> {
                                // Round the middle of the entity, wherever it goes
                                JsonObject values = new JsonObject();
                                values.addProperty("entity_uuid", entity.getUUID().toString());
                                values.addProperty("entity_id", entity.getId());
                                values.addProperty("centre_x", 0.0);
                                values.addProperty("centre_y", 0.0);
                                values.addProperty("centre_z", 0.0);
                                refuse(changeAll(values));
                            }, null, List.of());
                            EditorState.sideFolded = false;
                        }).tip(Kit.t("orbit.pick.tip")),
                        new Gadget.Push(Kit.t("orbit.point"), KeyframeForms::orbitRoundPoint).tip(Kit.t("orbit.point.tip"))));
                numbers(form, "centre_x", "centre_y", "centre_z", "distance", "height", "angle");
                if (onEntity) {
                    form.add(new Gadget.Note(Kit.t("orbit.offset_note")));
                }
            }
            case FOLLOW_ENTITY -> {
                form.add(new Gadget.Note(() -> Kit.t("follow.who", entityName(text("entity_uuid"))), Controls.PARCHMENT));
                form.add(new Gadget.Push(Kit.t("follow.pick"), () -> {
                    EditorState.pick = new EditorState.Pick(Kit.t("pick.follow"), false, false, entity -> {
                        JsonObject values = new JsonObject();
                        values.addProperty("entity_uuid", entity.getUUID().toString());
                        values.addProperty("entity_id", entity.getId());
                        refuse(changeAll(values));
                    }, null, List.of());
                    EditorState.sideFolded = false;
                }).tip(Kit.t("follow.pick.tip")));
                choice(form, "part", "part", List.of("feet", "body", "head", "eyes"));
                numbers(form, "offset_x", "offset_y", "offset_z");
                choice(form, "mode", "follow_mode", List.of("keep_relative", "watch_from_fixed"));
            }
            case SMART_CAMERA -> buildSmartCamera(form, (Keyframe.SmartCamera) keyframe);
            case FIELD_OF_VIEW -> numbers(form, "fov");
            case CAMERA_SHAKE -> numbers(form, "strength", "frequency");
            case SPEED -> numbers(form, "ticks_per_second");
            case TIMELAPSE -> {
                numbers(form, "real_seconds");
                form.add(new Gadget.Note(Kit.t("timelapse.note")));
            }
            case FREEZE -> numbers(form, "hold_seconds");
            case TIME_OF_DAY -> numbers(form, "time");
            case BLOCK_CHANGE -> {
                whole(form, "x");
                whole(form, "y");
                whole(form, "z");
                form.add(new Gadget.Pair(label("block_state"), new Entry(() -> text("block_state"), typed -> {
                    Component wrong = checkBlockState(typed.trim());
                    return wrong != null ? wrong : change("block_state", new JsonPrimitive(typed.trim()));
                })).tip(tip("block_state")));
                form.add(new Gadget.Push(Kit.t("block.pick"), () -> {
                    EditorState.pick = new EditorState.Pick(Kit.t("pick.block"), true, false, null, position -> {
                        JsonObject values = new JsonObject();
                        values.addProperty("x", position.getX());
                        values.addProperty("y", position.getY());
                        values.addProperty("z", position.getZ());
                        refuse(changeAll(values));
                    }, List.of());
                    EditorState.sideFolded = false;
                }).tip(Kit.t("block.pick.tip")));
            }
            case AUDIO -> {
                form.add(new Gadget.Pair(label("file"), new Entry(() -> text("file"), typed -> change("file", new JsonPrimitive(typed.trim())))).tip(tip("file")));
                numbers(form, "volume");
            }
            case POINT_OF_VIEW -> {
                form.add(new Gadget.Note(Kit.t("pov.note")));
                form.add(new Gadget.Pair(label("recording_id"), new Gadget.Cycle<String>(KeyframeForms::pointsOfView, KeyframeForms::pointOfViewName,
                        () -> text("recording_id"), id -> {
                            JsonObject values = new JsonObject();
                            values.addProperty("recording_id", id);
                            values.addProperty("label", pointOfViewName(id).getString());
                            refuse(changeAll(values));
                        })).tip(tip("recording_id")));
            }
            default -> {
            }
        }
        form.add(new Gadget.Line(
                new Gadget.Push(Kit.t("keyframe.jump"), () -> {
                    Keyframe now = current();
                    if (now != null) {
                        Controls.jumpTo(now.tick());
                    }
                }).tip(Kit.t("keyframe.jump.tip")),
                new Gadget.Push(Kit.t("keyframe.copy"), EditorState::copySelected).tip(Kit.t("keyframe.copy.tip")),
                new Gadget.Push(Kit.t("keyframe.remove"), EditorState::removeSelected).tip(Kit.t("keyframe.remove.tip"))));
        form.add(new Gadget.Push(Kit.t("one.deselect"), EditorState.selection::clear).tip(Kit.t("one.deselect.tip")));
    }

    private static void buildSmartCamera(Form form, Keyframe.SmartCamera keyframe) {
        String mode = keyframe.mode();
        form.add(new Gadget.Note(() -> Kit.t("smart.who", playerName(text("target"))), Controls.PARCHMENT));
        form.add(new Gadget.Pair(label("target"), new Entry(() -> text("target"), typed -> {
            String wanted = typed.trim();
            if (resolvePlayer(wanted) == null) {
                return Kit.t("smart.unknown_player", wanted);
            }
            return change("target", new JsonPrimitive(wanted));
        })).tip(tip("target")));
        form.add(new Gadget.Push(Kit.t("smart.pick"), () -> {
            EditorState.pick = new EditorState.Pick(Kit.t("pick.smart"), false, true,
                    entity -> refuse(change("target", new JsonPrimitive(entity.getUUID().toString()))), null,
                    List.of(new Menu.Item(Kit.t("pick.recorder"), () -> {
                        RecordingManifest manifest = Playback.manifest();
                        if (manifest != null) {
                            EditorState.pick = null;
                            refuse(change("target", new JsonPrimitive(manifest.recorderId)));
                        }
                    })));
            EditorState.sideFolded = false;
        }).tip(Kit.t("smart.pick.tip")));
        form.add(new Gadget.Pair(label("smart_mode"), new Gadget.Cycle<>(Keyframe.SmartCamera.MODES, id -> Kit.t("choice." + id), () -> text("mode"), id -> {
            JsonObject values = new JsonObject();
            values.addProperty("mode", id);
            JsonObject now = json();
            if (id.equals("tripod") && !now.has("position")) {
                // A tripod picks its own places; the viewer's place is kept for when it is set to stay fixed
                Vec3 feet = Viewer.position();
                JsonObject position = new JsonObject();
                position.addProperty("x", round(feet.x));
                position.addProperty("y", round(feet.y + EYE_HEIGHT));
                position.addProperty("z", round(feet.z));
                values.add("position", position);
                values.addProperty("tripod_placement", "auto");
            }
            if (id.equals("orbit") && !now.has("start_angle")) {
                values.addProperty("start_angle", round(Viewer.yaw()));
            }
            refuse(changeAll(values));
        })).tip(tip("smart_mode")));

        form.add(new Gadget.Heading(Kit.t("smart.mode." + mode)));
        switch (mode) {
            case "chase" -> {
                dials(form, "distance", "height", "side_angle", "position_smoothing", "heading_dead_band", "flight_widen");
                choice(form, "heading_source", "heading_source", List.of("movement", "body"));
            }
            case "tripod" -> {
                form.add(new Gadget.Pair(label("tripod_placement"), new Gadget.Cycle<>(List.of("auto", "fixed"), id -> Kit.t("choice.placement_" + id),
                        KeyframeForms::placement, id -> refuse(change("tripod_placement", new JsonPrimitive(id))))).tip(tip("tripod_placement")));
                for (int axis = 0; axis < 3; axis++) {
                    int which = axis;
                    String name = "position_" + "xyz".charAt(axis);
                    form.add(new Gadget.Pair(label(name), Entry.number(() -> position(which), -Keyframe.MAX_POSITION, Keyframe.MAX_POSITION, false,
                            value -> setPosition(which, value))).tip(tip(name)));
                }
                form.add(new Gadget.Push(Kit.t("smart.stand_here"), () -> {
                    Vec3 feet = Viewer.position();
                    JsonObject position = new JsonObject();
                    position.addProperty("x", round(feet.x));
                    position.addProperty("y", round(feet.y + EYE_HEIGHT));
                    position.addProperty("z", round(feet.z));
                    JsonObject values = new JsonObject();
                    values.add("position", position);
                    values.addProperty("tripod_placement", "fixed");
                    refuse(changeAll(values));
                }).tip(Kit.t("smart.stand_here.tip")));
                dials(form, "aim_smoothing", "dead_zone");
            }
            case "orbit" -> dials(form, "radius", "height", "degrees_per_second", "start_angle");
            default -> {
                dials(form, "min_shot_seconds", "max_shot_seconds");
                switches(form, "allow_chase", "allow_over_shoulder", "allow_side_tracking", "allow_tripod", "allow_orbit", "establishing_shots");
                form.add(new Gadget.Pair(label("seed"), Entry.number(() -> {
                    JsonObject json = json();
                    return json.has("seed") ? number(json.get("seed")) : Double.valueOf(0.0);
                }, Integer.MIN_VALUE, Integer.MAX_VALUE, true, value -> change("seed", new JsonPrimitive((int) Math.rint(value))))).tip(tip("seed")));
                form.add(new Gadget.Note(Kit.t("smart.director.note")));
                dials(form, "distance", "height", "radius");
            }
        }

        form.add(new Gadget.Heading(Kit.t("smart.framing")));
        dials(form, "screen_x", "screen_y", "lead_room");
        switches(form, "auto_zoom");
        if (flag(keyframe.settings(), "auto_zoom")) {
            dials(form, "subject_screen_height", "min_fov", "max_fov");
        } else {
            dials(form, "fixed_fov");
        }
        switches(form, "frame_vehicle", "avoid_obstructions");
        dials(form, "transition_seconds");
    }

    // ------------------------------------------------------------------ Points of view (PLAY-19)

    /** The recordings a point of view keyframe can show: the scene's own, then the linked ones */
    private static List<String> pointsOfView() {
        List<String> ids = new ArrayList<>();
        String own = comedymonkey.dailies.editor.PointOfView.homeId();
        if (own != null) {
            ids.add(own);
        }
        for (comedymonkey.dailies.edit.LinkedRecording linked : EditorState.scene().linked()) {
            if (!ids.contains(linked.recordingId())) {
                ids.add(linked.recordingId());
            }
        }
        String current = text("recording_id");
        if (!current.isBlank() && !ids.contains(current)) {
            ids.add(current);
        }
        return ids;
    }

    private static Component pointOfViewName(String id) {
        if (id.equals(comedymonkey.dailies.editor.PointOfView.homeId())) {
            RecordingManifest manifest = Playback.manifest();
            String recorder = manifest == null || comedymonkey.dailies.editor.PointOfView.away() ? "" : manifest.recorderName;
            return recorder == null || recorder.isBlank() ? Kit.t("pov.own") : Kit.t("pov.own_named", recorder);
        }
        comedymonkey.dailies.edit.LinkedRecording linked = EditorState.scene().linked(id);
        return linked != null ? Component.literal(linked.label()) : Kit.t("pov.unlinked");
    }

    /**
     * Links every other player's recording of the same stretch to the scene, as lanes and as choices for point of view
     * keyframes. The library is searched away from the client thread.
     */
    static void linkPointsOfView() {
        String own = comedymonkey.dailies.editor.PointOfView.homeId();
        if (own == null) {
            return;
        }
        List<comedymonkey.dailies.edit.LinkedRecording> found = new ArrayList<>();
        Controls.offThread(() -> {
            for (comedymonkey.dailies.library.Links.Link link : comedymonkey.dailies.library.Links.linked(own)) {
                found.add(new comedymonkey.dailies.edit.LinkedRecording(link.id(), link.manifest().name, link.manifest().recorderName));
            }
        }, Kit.t("pov.link.failed"), () -> {
            if (!EditorSession.open() || !own.equals(comedymonkey.dailies.editor.PointOfView.homeId())) {
                return;
            }
            List<comedymonkey.dailies.edit.LinkedRecording> fresh = new ArrayList<>();
            for (comedymonkey.dailies.edit.LinkedRecording recording : found) {
                if (EditorState.scene().linked(recording.recordingId()) == null) {
                    fresh.add(recording);
                }
            }
            if (fresh.isEmpty()) {
                EditorState.say(found.isEmpty() ? Kit.t("pov.link.none_found") : Kit.t("pov.link.all_there"));
                return;
            }
            EditorState.history().beginGroup(Kit.t("pov.link.step").getString());
            try {
                for (comedymonkey.dailies.edit.LinkedRecording recording : fresh) {
                    EditorState.history().linkRecording(recording);
                }
            } finally {
                EditorState.history().endGroup();
            }
            comedymonkey.dailies.editor.PointOfView.prepare(EditorState.scene());
            EditorState.say(Kit.t("pov.link.done", fresh.size()));
        });
    }

    // ------------------------------------------------------------------ Rows

    private static Component label(String field) {
        return Kit.t("field." + field);
    }

    private static Component tip(String field) {
        return Kit.t("field." + field + ".tip");
    }

    /** Rows for numbers the keyframe itself checks */
    private static void numbers(Form form, String... fields) {
        for (String field : fields) {
            form.add(new Gadget.Pair(label(field), Entry.number(() -> number(json().get(field)), -ANY, ANY, false,
                    value -> change(field, new JsonPrimitive(value)))).tip(tip(field)));
        }
    }

    private static void whole(Form form, String field) {
        form.add(new Gadget.Pair(label(field), Entry.number(() -> number(json().get(field)), -Keyframe.MAX_POSITION, Keyframe.MAX_POSITION, true,
                value -> change(field, new JsonPrimitive((int) Math.rint(value))))).tip(tip("block_" + field)));
    }

    /** Rows for numbers of the Smart Camera, which are checked here because the keyframe does not check them */
    private static void dials(Form form, String... fields) {
        for (String field : fields) {
            Dial dial = DIALS.get(field);
            form.add(new Gadget.Pair(label(field), Entry.number(() -> {
                Double set = number(json().get(field));
                return set != null ? set : Double.valueOf(dial.otherwise());
            }, dial.min(), dial.max(), false, value -> change(field, new JsonPrimitive(value)))).tip(tip(field)));
        }
    }

    private static void switches(Form form, String... fields) {
        for (String field : fields) {
            form.add(new Gadget.Check(label(field), () -> flag(json(), field), on -> refuse(change(field, new JsonPrimitive(on)))).tip(tip(field)));
        }
    }

    private static void choice(Form form, String field, String labelKey, List<String> ids) {
        form.add(new Gadget.Pair(label(labelKey), new Gadget.Cycle<>(ids, id -> Kit.t("choice." + id), () -> {
            String now = text(field);
            return now.isEmpty() ? ids.get(0) : now;
        }, id -> refuse(change(field, new JsonPrimitive(id))))).tip(tip(labelKey)));
    }

    // ------------------------------------------------------------------ Reading and changing the selected keyframe

    private static @Nullable Keyframe current() {
        KeyframeRef ref = EditorState.single();
        return ref == null ? null : EditorState.keyframe(ref);
    }

    private static JsonObject json() {
        Keyframe keyframe = current();
        return keyframe == null ? new JsonObject() : keyframe.toJson();
    }

    private static @Nullable Double number(@Nullable JsonElement element) {
        try {
            return element != null && element.isJsonPrimitive() ? Double.valueOf(element.getAsDouble()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(String field) {
        JsonElement element = json().get(field);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean flag(JsonObject json, String field) {
        JsonElement element = json.get(field);
        try {
            if (element != null && element.isJsonPrimitive()) {
                return element.getAsBoolean();
            }
        } catch (RuntimeException e) {
            // Falls through to what the Smart Camera would take
        }
        return SWITCHES.getOrDefault(field, false);
    }

    /** An orbit round an entity goes back to circling a fixed point: where the entity's middle is now */
    private static void orbitRoundPoint() {
        JsonObject now = json();
        if (!now.has("entity_uuid")) {
            return;
        }
        double x = now.has("centre_x") ? now.get("centre_x").getAsDouble() : 0.0;
        double y = now.has("centre_y") ? now.get("centre_y").getAsDouble() : 0.0;
        double z = now.has("centre_z") ? now.get("centre_z").getAsDouble() : 0.0;
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.world.entity.Entity entity = null;
        try {
            entity = minecraft.level == null ? null : minecraft.level.getEntities().get(java.util.UUID.fromString(now.get("entity_uuid").getAsString()));
        } catch (IllegalArgumentException ignored) {
            // Gone from the world: the circle stays round where the camera is
        }
        Vec3 middle = entity != null ? entity.position().add(0.0, entity.getBbHeight() / 2.0, 0.0) : Viewer.position();
        JsonObject values = new JsonObject();
        values.add("entity_uuid", com.google.gson.JsonNull.INSTANCE);
        values.add("entity_id", com.google.gson.JsonNull.INSTANCE);
        values.addProperty("centre_x", round(middle.x + x));
        values.addProperty("centre_y", round(middle.y + y));
        values.addProperty("centre_z", round(middle.z + z));
        refuse(changeAll(values));
    }

    /** Whether a tripod picks its own places: as stored, or as the Smart Camera reads a keyframe that does not say */
    private static String placement() {
        String stored = text("tripod_placement");
        if (!stored.isEmpty()) {
            return stored;
        }
        return json().has("position") ? "fixed" : "auto";
    }

    /** One coordinate of a tripod's position, which is stored as a list or as an object */
    private static @Nullable Double position(int axis) {
        JsonElement position = json().get("position");
        try {
            if (position != null && position.isJsonArray()) {
                return position.getAsJsonArray().get(axis).getAsDouble();
            }
            if (position != null && position.isJsonObject()) {
                return position.getAsJsonObject().get(String.valueOf("xyz".charAt(axis))).getAsDouble();
            }
        } catch (RuntimeException e) {
            // Shown as empty
        }
        return null;
    }

    private static @Nullable Component setPosition(int axis, double value) {
        JsonObject position = new JsonObject();
        for (int i = 0; i < 3; i++) {
            Double old = position(i);
            position.addProperty(String.valueOf("xyz".charAt(i)), i == axis ? value : old == null ? 0.0 : old);
        }
        return change("position", position);
    }

    /**
     * Changes one value of the selected keyframe, as one step to undo.
     *
     * @return null when it was done, else why it was refused
     */
    private static @Nullable Component change(String field, JsonElement value) {
        JsonObject values = new JsonObject();
        values.add(field, value);
        return changeAll(values);
    }

    private static @Nullable Component changeAll(JsonObject values) {
        KeyframeRef ref = EditorState.single();
        Keyframe old = ref == null ? null : EditorState.keyframe(ref);
        if (ref == null || old == null) {
            return Kit.t("error.gone");
        }
        JsonObject json = old.toJson();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            json.add(entry.getKey(), entry.getValue());
        }
        try {
            Keyframe next = Keyframe.fromJson(old.kind(), json);
            if (!next.equals(old)) {
                EditorState.history().setKeyframe(ref.track(), next);
            }
            return null;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return Component.literal(refused.getMessage() == null ? refused.toString() : refused.getMessage());
        }
    }

    /** For changes made by a button, which has no line of its own to explain a refusal on */
    private static void refuse(@Nullable Component why) {
        if (why != null) {
            EditorState.say(why);
        }
    }

    private static @Nullable Component moveTo(int tick) {
        KeyframeRef ref = EditorState.single();
        if (ref == null || EditorState.keyframe(ref) == null) {
            return Kit.t("error.gone");
        }
        if (tick < 0 || tick > Playback.total()) {
            return Kit.t("error.range", 0, Playback.total());
        }
        if (tick != ref.tick()) {
            EditorState.history().moveKeyframe(ref.track(), ref.tick(), tick);
            EditorState.selection.clear();
            EditorState.selection.add(new KeyframeRef(ref.track(), tick));
        }
        return null;
    }

    private static void takeView() {
        Vec3 feet = Viewer.position();
        JsonObject values = new JsonObject();
        values.addProperty("x", feet.x);
        values.addProperty("y", feet.y);
        values.addProperty("z", feet.z);
        values.addProperty("yaw", Viewer.yaw());
        values.addProperty("pitch", Viewer.pitch());
        values.addProperty("roll", clamp(Viewer.roll(), -3600, 3600));
        refuse(changeAll(values));
    }

    private static void goToCamera() {
        if (current() instanceof Keyframe.Camera camera) {
            if (EditorSession.followCamera() && !Playback.paused()) {
                EditorSession.setFollowCamera(false);
            }
            Viewer.ride(null);
            Viewer.set(camera.x(), camera.y(), camera.z(), (float) camera.yaw(), (float) camera.pitch());
            Viewer.setRoll((float) camera.roll());
        }
    }

    // ------------------------------------------------------------------ Names and checks

    /** A name for the entity with this UUID, as far as the world at this tick knows it */
    static String entityName(String uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            UUID id = UUID.fromString(uuid);
            if (minecraft.level != null) {
                for (Entity entity : minecraft.level.entitiesForRendering()) {
                    if (entity.getUUID().equals(id)) {
                        return entity.getName().getString() + " (" + entity.getType().getDescription().getString() + ")";
                    }
                }
            }
            RecordingManifest manifest = Playback.manifest();
            if (manifest != null && manifest.recorderId.equalsIgnoreCase(uuid)) {
                return manifest.recorderName;
            }
        } catch (IllegalArgumentException notAnId) {
            return uuid;
        }
        return Kit.t("entity.absent", uuid.length() > 8 ? uuid.substring(0, 8) : uuid).getString();
    }

    private static String playerName(String target) {
        UUID id = resolvePlayer(target);
        if (id == null) {
            return target;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            Player player = minecraft.level.getPlayerByUUID(id);
            if (player != null) {
                return player.getGameProfile().getName();
            }
        }
        RecordingManifest manifest = Playback.manifest();
        if (manifest != null && manifest.recorderId.equalsIgnoreCase(id.toString())) {
            return manifest.recorderName;
        }
        return Kit.t("entity.absent", target.length() > 8 ? target.substring(0, 8) : target).getString();
    }

    /** The player a Smart Camera target names: a UUID, a player in the world now, or whoever made the recording */
    private static @Nullable UUID resolvePlayer(String target) {
        Minecraft minecraft = Minecraft.getInstance();
        RecordingManifest manifest = Playback.manifest();
        try {
            return UUID.fromString(target.trim());
        } catch (IllegalArgumentException notAnId) {
            if (minecraft.level != null) {
                for (Player player : minecraft.level.players()) {
                    if (player.getGameProfile().getName().equalsIgnoreCase(target.trim())) {
                        return player.getUUID();
                    }
                }
            }
            if (manifest != null && !target.isBlank() && manifest.recorderName.equalsIgnoreCase(target.trim())) {
                try {
                    return UUID.fromString(manifest.recorderId);
                } catch (IllegalArgumentException noRecorder) {
                    return null;
                }
            }
            return null;
        }
    }

    private static @Nullable Component checkBlockState(String typed) {
        Minecraft minecraft = Minecraft.getInstance();
        if (typed.isEmpty()) {
            return Kit.t("block.empty");
        }
        if (minecraft.level == null) {
            return null;
        }
        try {
            BlockStateParser.parseForBlock(minecraft.level.holderLookup(Registries.BLOCK), typed, false);
            return null;
        } catch (CommandSyntaxException e) {
            return Kit.t("block.unknown", e.getMessage());
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Three decimals are plenty for a position and far easier to read */
    private static double round(double value) {
        return Math.rint(value * 1000.0) / 1000.0;
    }

}
