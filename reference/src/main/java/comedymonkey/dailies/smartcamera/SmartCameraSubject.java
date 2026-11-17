package comedymonkey.dailies.smartcamera;

import comedymonkey.dailies.Host;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What the smart camera looks at this frame: where the followed entity is and how much space it (and, when asked,
 * whatever it is riding) takes up. Everything is in real world coordinates, also for entities on a Sable physics object.
 *
 * @param position the entity's feet
 * @param bounds the entity's bounding box, joined with its vehicles and physics object when those are framed
 * @param framed what else is in the bounds: none, vehicle, contraption or physics_object
 * @param subLevel the Sable physics object the entity is part of, rides or stands on, or null
 * @param subLevelRemembered whether that is only assumed, because the entity was on it a moment ago and is still next to it
 * @param riding whether the entity is a passenger or on a physics object
 * @param gliding whether the entity is flying with an elytra
 * @param airborne whether the entity is off the ground without riding anything
 * @param tickVelocity blocks per second over the last tick, for a first estimate before the solver has its own
 */
public record SmartCameraSubject(Vec3 position, AABB bounds, String framed, @Nullable Object subLevel, boolean subLevelRemembered,
                                 boolean riding, boolean gliding, boolean airborne, double bodyYaw, Vec3 tickVelocity) {

    public static final String FRAMED_NONE = "none";
    public static final String FRAMED_VEHICLE = "vehicle";
    public static final String FRAMED_CONTRAPTION = "contraption";
    public static final String FRAMED_PHYSICS_OBJECT = "physics_object";

    private static final double MAX_FRAMED_SIZE = 512.0;

    public boolean framesMore() {
        return !this.framed.equals(FRAMED_NONE);
    }

    /** The point the camera aims at: the middle of everything framed, or around the chest of a player on their own */
    public Vec3 aimPoint() {
        if (this.framesMore()) {
            return this.bounds.getCenter();
        }
        return new Vec3(this.position.x, this.bounds.minY + this.bounds.getYsize() * 0.75, this.position.z);
    }

    /** The largest extent of the bounds, which is what auto zoom fits into the frame */
    public double size() {
        return Math.max(this.bounds.getYsize(), Math.max(this.bounds.getXsize(), this.bounds.getZsize()));
    }

    /**
     * @param recentSubLevel the physics object the entity was on a moment ago, or null. Someone jumping on the deck
     *                       of a craft is still on that craft
     */
    public static SmartCameraSubject of(ClientLevel level, Entity entity, float partialTick, boolean frameVehicle, @Nullable Object recentSubLevel) {
        // Entities that are part of a physics object are positioned in Sable's hidden plot region
        boolean inPlot = Host.crafts().isInPlot(level, entity.getX(), entity.getZ());

        // Same interpolation as KeyframeChangeTrackEntity. Except in the tick an entity joins or leaves a physics
        // object: its last position and its current one are then millions of blocks apart
        Vec3 localPosition = entity.getPosition(partialTick);
        if (inPlot != Host.crafts().isInPlot(level, entity.xo, entity.zo)) {
            localPosition = entity.position();
        }
        Vec3 position = inPlot ? Host.crafts().projectOutOfSubLevel(level, localPosition, partialTick) : localPosition;

        double halfWidth = entity.getBbWidth() / 2.0;
        AABB bounds = new AABB(position.x - halfWidth, position.y, position.z - halfWidth,
            position.x + halfWidth, position.y + entity.getBbHeight(), position.z + halfWidth);
        String framed = FRAMED_NONE;

        Object subLevel = Host.crafts().getSubLevel(entity);
        boolean subLevelRemembered = false;
        if (subLevel == null && recentSubLevel != null && !inPlot && Host.crafts().isNearSubLevel(recentSubLevel, position, 2.0)) {
            subLevel = recentSubLevel;
            subLevelRemembered = true;
        }

        if (frameVehicle) {
            for (Entity vehicle = entity.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle()) {
                Vec3 vehiclePosition = vehicle.getPosition(partialTick);
                if (Host.crafts().isInPlot(level, vehiclePosition.x, vehiclePosition.z)) {
                    // Its box is in plot coordinates. The physics object's own bounds cover it
                    continue;
                }
                AABB box = vehicle.getBoundingBox().move(vehiclePosition.subtract(vehicle.position()));
                if (!isUsable(box)) {
                    continue;
                }
                bounds = bounds.minmax(box);
                if (isContraption(vehicle)) {
                    framed = FRAMED_CONTRAPTION;
                } else if (framed.equals(FRAMED_NONE)) {
                    framed = FRAMED_VEHICLE;
                }
            }

            AABB subLevelBounds = Host.crafts().getSubLevelBounds(subLevel);
            if (subLevelBounds != null && isUsable(subLevelBounds)) {
                bounds = bounds.minmax(subLevelBounds);
                framed = FRAMED_PHYSICS_OBJECT;
            }
        }

        double bodyYaw;
        if (entity instanceof LivingEntity livingEntity) {
            bodyYaw = Mth.rotLerp(partialTick, livingEntity.yBodyRotO, livingEntity.yBodyRot);
        } else {
            bodyYaw = entity.getViewYRot(partialTick);
        }
        if (inPlot) {
            // The rotation is relative to the physics object too
            double yawRadians = Math.toRadians(bodyYaw);
            Vec3 heading = Host.crafts().projectDirectionOutOfSubLevel(level, localPosition,
                new Vec3(-Math.sin(yawRadians), 0.0, Math.cos(yawRadians)), partialTick);
            if (heading.x * heading.x + heading.z * heading.z > 1.0e-6) {
                bodyYaw = SmartCameraMath.yawOf(heading.x, heading.z);
            }
        }

        boolean riding = entity.isPassenger() || subLevel != null;
        boolean gliding = entity instanceof LivingEntity livingEntity && livingEntity.isFallFlying();
        boolean airborne = !riding && !entity.onGround() && !entity.isInWater();

        Vec3 tickVelocity = Vec3.ZERO;
        if (!inPlot) {
            tickVelocity = new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo).scale(20.0);
            if (tickVelocity.lengthSqr() > 100.0 * 100.0) {
                tickVelocity = Vec3.ZERO;
            }
        }

        return new SmartCameraSubject(position, bounds, framed, subLevel, subLevelRemembered, riding, gliding, airborne, bodyYaw, tickVelocity);
    }

    private static boolean isUsable(AABB box) {
        return !box.hasNaN() && box.getXsize() <= MAX_FRAMED_SIZE && box.getYsize() <= MAX_FRAMED_SIZE && box.getZsize() <= MAX_FRAMED_SIZE;
    }

    /** Create's moving contraptions, found by name so that Create stays optional */
    private static boolean isContraption(Entity entity) {
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return type.getNamespace().equals("create") && type.getPath().contains("contraption");
    }

}
