package comedymonkey.dailies.ui;

import com.google.gson.JsonObject;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The "Players" panel of the editor (specification PLAY-3): everyone in the recording at the tick being shown, each
 * with their face, their name and how far off they are, and four things to do with them. Look at turns the camera to
 * them once; follow keeps the camera with them and turned to them from then on; ride along sees through their eyes;
 * hide leaves them out of the picture, which is a look of the scene and so holds in exports too.
 * <p>
 * Players are known here by their id and looked up afresh each time, never held: a jump takes every entity out of
 * the world and puts new ones in. Someone the recording knows of but who is too far away to be in the world at this
 * tick is listed all the same, with only "hide" to press.
 */
public final class PlayerList {

    /**
     * Someone in the recording.
     *
     * @param inWorld whether they are near enough to be in the world at the tick being shown
     */
    public record Listed(UUID id, String name, boolean inWorld) {
    }

    private PlayerList() {
    }

    // ------------------------------------------------------------------ Who is there

    /** Everyone in the recording at the tick being shown: the recorder first, then by name. Never the viewer */
    public static List<Listed> players() {
        Minecraft minecraft = Minecraft.getInstance();
        List<Listed> found = new ArrayList<>();
        if (minecraft.level == null || minecraft.player == null) {
            return found;
        }
        UUID viewer = minecraft.player.getUUID();
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (player != minecraft.player && !player.getUUID().equals(viewer)) {
                found.add(new Listed(player.getUUID(), player.getGameProfile().getName(), true));
            }
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                UUID id = info.getProfile().getId();
                if (!id.equals(viewer) && found.stream().noneMatch(listed -> listed.id().equals(id))) {
                    found.add(new Listed(id, info.getProfile().getName(), false));
                }
            }
        }
        RecordingManifest manifest = Playback.manifest();
        String recorder = manifest == null ? "" : manifest.recorderId;
        found.sort(Comparator.comparing((Listed listed) -> !listed.id().toString().equalsIgnoreCase(recorder)).thenComparing(Listed::name, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    private static @Nullable Player find(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : minecraft.level.getPlayerByUUID(id);
    }

    private static @Nullable PlayerSkin skin(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (find(id) instanceof AbstractClientPlayer player) {
            return player.getSkin();
        }
        ClientPacketListener connection = minecraft.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
        return info == null ? null : info.getSkin();
    }

    // ------------------------------------------------------------------ What can be done with them

    /** Turns the camera to face a player, once. The camera leaves its track and whoever it was riding with */
    public static void lookAt(UUID id) {
        Player player = find(id);
        if (player == null) {
            EditorState.say(Kit.t("players.gone"));
            return;
        }
        Viewer.ride(null);
        EditorSession.setFollowCamera(false);
        Vec3 from = Viewer.position().add(0.0, PathPreview.EYE, 0.0);
        Vec3 look = player.getEyePosition().subtract(from);
        if (look.lengthSqr() < 1.0e-6) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
        Vec3 at = Viewer.position();
        Viewer.set(at.x, at.y, at.z, yaw, pitch);
    }

    /** Whether the camera is keeping this player in view */
    public static boolean following(UUID id) {
        return id.equals(Viewer.following());
    }

    /** Starts keeping a player in view, or stops if the camera already is */
    public static void follow(UUID id) {
        if (following(id)) {
            Viewer.follow(null);
            return;
        }
        if (find(id) == null) {
            EditorState.say(Kit.t("players.gone"));
            return;
        }
        // The camera can serve its track or a player, not both
        EditorSession.setFollowCamera(false);
        Viewer.follow(id);
    }

    public static boolean riding(UUID id) {
        // By the UUID kept across jumps: through a drag of the playhead they are out of the world now and then
        return id.equals(Viewer.ridingId());
    }

    /** Sees through a player's eyes, or leaves them again */
    public static void ride(UUID id) {
        if (riding(id)) {
            Viewer.ride(null);
            return;
        }
        Player player = find(id);
        if (player == null) {
            EditorState.say(Kit.t("players.gone"));
            return;
        }
        Viewer.ride(player);
    }

    public static boolean hidden(UUID id) {
        return EditorSession.open() && EditorSession.scene().looks().entityOverride(id).hide();
    }

    /** Leaves a player out of the picture in this scene, or shows them again. One step to undo */
    public static void hide(UUID id) {
        JsonObject change = new JsonObject();
        change.addProperty("hide", !hidden(id));
        EditorSession.history().setEntityOverride(id, change);
    }

    // ------------------------------------------------------------------ The panel

    /** A word for what the panel should hold now; when it changes the panel is built afresh */
    static String shape() {
        StringBuilder shape = new StringBuilder();
        for (Listed listed : players()) {
            shape.append(listed.id()).append(listed.inWorld() ? '+' : '-').append(listed.name()).append('|');
        }
        return shape.toString();
    }

    static void build(Form form) {
        form.clear();
        List<Listed> players = players();
        if (players.isEmpty()) {
            form.add(new Gadget.Note(Kit.t("players.none")));
            return;
        }
        for (Listed listed : players) {
            UUID id = listed.id();
            form.add(new Who(listed));
            form.add(new Gadget.Line(
                    new Gadget.Push(Kit.t("players.look"), () -> lookAt(id)).when(() -> find(id) != null).tip(Kit.t("players.look.tip", listed.name())),
                    new Gadget.Push(Kit.t("players.follow"), () -> follow(id)).lit(() -> following(id)).when(() -> find(id) != null || following(id))
                            .tip(Kit.t("players.follow.tip", listed.name())),
                    new Gadget.Push(Kit.t("players.ride"), () -> ride(id)).lit(() -> riding(id)).when(() -> find(id) != null)
                            .tip(Kit.t("players.ride.tip", listed.name())),
                    new Gadget.Push(Kit.t("players.hide"), () -> hide(id)).lit(() -> hidden(id)).tip(Kit.t("players.hide.tip", listed.name()))));
        }
    }

    /** One player: their face, their name, and how far from the camera they are */
    private static final class Who extends Gadget {
        private static final int FACE = 10;
        private final Listed listed;

        Who(Listed listed) {
            this.listed = listed;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return FACE + 4;
        }

        private @Nullable String distance() {
            Player player = find(this.listed.id());
            if (player == null) {
                return null;
            }
            return Kit.t("players.distance", Math.round(player.position().distanceTo(Viewer.position()))).getString();
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            int top = this.y + (this.height - FACE) / 2;
            PlayerSkin skin = skin(this.listed.id());
            if (skin != null) {
                PlayerFaceRenderer.draw(graphics, skin, this.x + 1, top, FACE);
            } else {
                graphics.fill(this.x + 1, top, this.x + 1 + FACE, top + FACE, Controls.FIELD);
            }
            graphics.renderOutline(this.x, top - 1, FACE + 2, FACE + 2, hidden(this.listed.id()) ? Controls.PARCHMENT_DIM : Controls.BRASS);
            String far = this.distance();
            String shown = far == null ? Kit.t("players.out_of_sight").getString() : far;
            int farWidth = font.width(shown);
            int textTop = this.y + (this.height - 8) / 2;
            int nameLeft = this.x + FACE + 6;
            int room = this.width - (FACE + 6) - farWidth - 6;
            Kit.text(graphics, font, Kit.clip(font, this.listed.name(), room), nameLeft, textTop, hidden(this.listed.id()) ? Controls.PARCHMENT_DIM : Controls.PARCHMENT);
            Kit.text(graphics, font, shown, this.x + this.width - farWidth - 1, textTop, Controls.PARCHMENT_DIM);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            return Kit.t(find(this.listed.id()) == null ? "players.who.far.tip" : hidden(this.listed.id()) ? "players.who.hidden.tip" : "players.who.tip", this.listed.name());
        }
    }

}
