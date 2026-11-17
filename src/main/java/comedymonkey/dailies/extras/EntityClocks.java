package comedymonkey.dailies.extras;

import com.mojang.math.Transformation;
import comedymonkey.dailies.capture.Snapshots;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a game counts for itself about an entity and no packet ever says: how long it has existed, how far through
 * dying it is (the Ender Dragon takes ten seconds over it), how far through an animation that was started once (a
 * warden rising out of the ground), and where a display entity is on its way from. A snapshot is built from packets,
 * and an entity made from them starts all of these at nought, so a jump into the middle of a dragon's death showed
 * it only beginning to die. They are written at the end of every snapshot as a {@code dailies:clocks} record and put
 * back when one is read. A recording without the record plays as before.
 * <p>
 * The record: a version byte (1), then for each entity its number and age in ticks as ints, followed by any of these,
 * each led by a byte, and a 0 byte to end the entity: 1, an int, ticks into dying; 2, an int, ticks into a dragon's
 * death; 3, a name and an int, the entity's animation of that name and how many ticks of it have run; 4, an int and
 * fourteen floats, how many ticks ago a display entity began to change and the shape it began from (translation,
 * left rotation, scale, right rotation).
 */
public final class EntityClocks {

    static final String ID = "dailies:clocks";
    private static final byte VERSION = 1;
    private static final Map<Class<?>, Field[]> ANIMATIONS = new ConcurrentHashMap<>();

    private EntityClocks() {
    }

    static void init() {
        SnapshotExtensions.register(ID, (out, codec) -> {
            byte[] record = write();
            if (record != null) {
                out.extension(ID, record);
            }
        }, true);
        PlaybackExtensions.register(ID, (data, from, length, skipping) -> read(data, from, length));
    }

    /** The public animations an entity of this kind has, by the names the game gives them */
    private static Field[] animations(Class<?> type) {
        return ANIMATIONS.computeIfAbsent(type, key -> {
            List<Field> found = new ArrayList<>();
            for (Field field : key.getFields()) {
                if (field.getType() == AnimationState.class && !Modifier.isStatic(field.getModifiers())) {
                    found.add(field);
                }
            }
            return found.toArray(new Field[0]);
        });
    }

    private static byte[] write() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(VERSION);
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.getId() == Snapshots.VIEWER_ENTITY_ID || entity.isRemoved()) {
                continue;
            }
            out.writeInt(entity.getId());
            out.writeInt(entity.tickCount);
            if (entity instanceof LivingEntity living && living.deathTime > 0) {
                out.writeByte(1);
                out.writeInt(living.deathTime);
            }
            if (entity instanceof EnderDragon dragon && dragon.dragonDeathTime > 0) {
                out.writeByte(2);
                out.writeInt(dragon.dragonDeathTime);
            }
            for (Field field : animations(entity.getClass())) {
                try {
                    AnimationState animation = (AnimationState) field.get(entity);
                    if (animation != null && animation.isStarted()) {
                        // Brought up to this tick, as drawing the entity would: one that is out of sight has not been counting
                        animation.updateTime(entity.tickCount, 1.0F);
                        out.writeByte(3);
                        out.writeUTF(field.getName());
                        out.writeInt((int) Math.min(Integer.MAX_VALUE, animation.getAccumulatedTime() / 50L));
                    }
                } catch (IllegalAccessException ignored) {
                    // Not ours to read: the animation starts afresh after a jump, as it did before
                }
            }
            if (entity instanceof Display display && display.renderState != null && display.renderState.transformation() instanceof Display.TransformationInterpolator moving
                && display.calculateInterpolationProgress(0.0F) < 1.0F) {
                out.writeByte(4);
                out.writeInt((int) (display.tickCount - display.interpolationStartClientTick));
                Transformation from = moving.previous();
                vector(out, from.getTranslation());
                rotation(out, from.getLeftRotation());
                vector(out, from.getScale());
                rotation(out, from.getRightRotation());
            }
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static void vector(DataOutputStream out, Vector3f vector) throws IOException {
        out.writeFloat(vector.x());
        out.writeFloat(vector.y());
        out.writeFloat(vector.z());
    }

    private static void rotation(DataOutputStream out, Quaternionf rotation) throws IOException {
        out.writeFloat(rotation.x());
        out.writeFloat(rotation.y());
        out.writeFloat(rotation.z());
        out.writeFloat(rotation.w());
    }

    private static void read(byte[] data, int from, int length) throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, from, length));
        if (minecraft.level == null || length < 1 || in.readByte() != VERSION) {
            return;
        }
        while (in.available() > 0) {
            Entity entity = minecraft.level.getEntity(in.readInt());
            int age = in.readInt();
            if (entity != null) {
                entity.tickCount = age;
            }
            for (int kind = in.readByte(); kind != 0; kind = in.readByte()) {
                switch (kind) {
                    case 1 -> {
                        int dying = in.readInt();
                        if (entity instanceof LivingEntity living) {
                            living.deathTime = dying;
                        }
                    }
                    case 2 -> {
                        int dying = in.readInt();
                        if (entity instanceof EnderDragon dragon) {
                            dragon.dragonDeathTime = dying;
                        }
                    }
                    case 3 -> {
                        String name = in.readUTF();
                        int run = in.readInt();
                        if (entity != null) {
                            for (Field field : animations(entity.getClass())) {
                                if (field.getName().equals(name)) {
                                    try {
                                        AnimationState animation = (AnimationState) field.get(entity);
                                        animation.start(entity.tickCount);
                                        animation.fastForward(run, 1.0F);
                                    } catch (IllegalAccessException ignored) {
                                        // It starts afresh, as it did before
                                    }
                                }
                            }
                        }
                    }
                    case 4 -> {
                        int ago = in.readInt();
                        Transformation began = new Transformation(new Vector3f(in.readFloat(), in.readFloat(), in.readFloat()), new Quaternionf(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()),
                            new Vector3f(in.readFloat(), in.readFloat(), in.readFloat()), new Quaternionf(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()));
                        if (entity instanceof Display display) {
                            // One tick takes in what the snapshot said of it: how long the change takes and where it ends
                            display.tick();
                            Display.RenderState now = display.renderState;
                            if (now != null) {
                                display.renderState = new Display.RenderState(new Display.TransformationInterpolator(began, now.transformation().get(1.0F)), now.billboardConstraints(),
                                    now.brightnessOverride(), now.shadowRadius(), now.shadowStrength(), now.glowColorOverride());
                                display.interpolationStartClientTick = (long) display.tickCount - ago;
                            }
                        }
                    }
                    default -> {
                        // A kind from a later version, of unknown length: nothing after it can be read
                        return;
                    }
                }
            }
        }
    }

}
