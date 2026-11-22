package comedymonkey.dailies.extras;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The recording half of skins that work offline (specification REC-14). For every player in the tab list, the
 * recorder included, the skin, cape and elytra pictures are taken as this game has them loaded and written into the
 * recording as {@code dailies:skins} records: all of them in every snapshot, and one more whenever a player's skin
 * arrives or changes in between (a skin takes a moment to download after its player appears).
 * <p>
 * The pictures are read back from the textures the game has already registered, so nothing is downloaded and no file
 * is read. Each picture is read and turned into a PNG once and kept for as long as the game runs.
 * <p>
 * The record: a version byte (1), the player's id as two longs, the model (0 wide, 1 slim), then three pictures (skin,
 * cape, elytra), each an int length followed by that many bytes of PNG. A length of 0 means "none"; for the skin it
 * means the game's built-in skin for that player.
 */
final class SkinCapture {

    static final String ID = "dailies:skins";
    static final byte VERSION = 1;

    /** How often, in recorded ticks, the tab list is looked through for skins that have arrived or changed */
    private static final int CHECK_EVERY = 10;
    /** No player's picture is anywhere near this wide; anything bigger is not a skin */
    private static final int LARGEST_SIDE = 1024;
    private static final int MOST_KEPT = 2048;

    /** A player's record as last built, and the skin it was built from */
    private record Built(PlayerSkin skin, byte[] record, boolean whole) {
    }

    private static final Map<UUID, Built> BUILT = new HashMap<>();
    /** Each picture as a PNG, by the name the game registered its texture under */
    private static final Map<Identifier, byte[]> PICTURES = new HashMap<>();
    /** What the recording being made holds for each player since its latest snapshot */
    private static final Map<UUID, PlayerSkin> WRITTEN = new HashMap<>();
    /** Stands for a picture that could not be read: written as "none" */
    private static final byte[] UNREADABLE = new byte[0];
    private static final Set<String> FAILED = new HashSet<>();
    private static int ticks = 0;

    private SkinCapture() {
    }

    static void init() {
        SnapshotExtensions.register(ID, SkinCapture::snapshot);
        Recorder.everyRecordedTick(SkinCapture::tick);
    }

    private static void snapshot(Recorder.Out out, GameCodec codec) {
        WRITTEN.clear();
        ticks = 0;
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null) {
            return;
        }
        for (PlayerInfo info : new ArrayList<>(listener.getOnlinePlayers())) {
            try {
                UUID id = info.getProfile().id();
                Built built = build(id, info.getSkin());
                out.extension(ID, built.record());
                if (built.whole()) {
                    WRITTEN.put(id, built.skin());
                }
            } catch (Throwable t) {
                failOnce("describing a player's skin for a snapshot", t);
            }
        }
    }

    private static void tick() {
        if (++ticks % CHECK_EVERY != 0) {
            return;
        }
        try {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null) {
                return;
            }
            for (PlayerInfo info : new ArrayList<>(listener.getOnlinePlayers())) {
                UUID id = info.getProfile().id();
                PlayerSkin skin = info.getSkin();
                if (skin.equals(WRITTEN.get(id))) {
                    continue;
                }
                Built built = build(id, skin);
                if (built.whole()) {
                    // A picture that is not ready yet is looked at again next time, and written once it is
                    Recorder.get().writeExtension(ID, built.record());
                    WRITTEN.put(id, skin);
                }
            }
        } catch (Throwable t) {
            failOnce("recording a player's skin", t);
        }
    }

    private static Built build(UUID id, PlayerSkin skin) throws IOException {
        Built built = BUILT.get(id);
        if (built != null && built.whole() && built.skin().equals(skin)) {
            return built;
        }
        boolean whole = true;
        // Without an address the skin is one of the game's own, which every game has
        byte[] skinPicture = skin.textureUrl() == null ? null : picture(skin.texture());
        whole &= skin.textureUrl() == null || skinPicture != null;
        byte[] cape = skin.capeTexture() == null ? null : picture(skin.capeTexture());
        whole &= skin.capeTexture() == null || cape != null;
        byte[] elytra = skin.elytraTexture() == null ? null : picture(skin.elytraTexture());
        whole &= skin.elytraTexture() == null || elytra != null;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(VERSION);
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        out.writeByte(skin.model() == PlayerSkin.Model.SLIM ? 1 : 0);
        for (byte[] picture : new byte[][]{skinPicture, cape, elytra}) {
            out.writeInt(picture == null ? 0 : picture.length);
            if (picture != null && picture.length > 0) {
                out.write(picture);
            }
        }
        built = new Built(skin, bytes.toByteArray(), whole);
        if (BUILT.size() >= MOST_KEPT) {
            BUILT.clear();
        }
        BUILT.put(id, built);
        return built;
    }

    /**
     * @return the picture a registered texture holds, as a PNG, or null when the game has not finished loading it
     */
    private static @Nullable byte[] picture(Identifier location) {
        byte[] known = PICTURES.get(location);
        if (known != null) {
            return known;
        }
        try {
            AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location, null);
            if (texture == null) {
                return null;
            }
            texture.bind();
            int width = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || width > LARGEST_SIDE || height > LARGEST_SIDE) {
                return null;
            }
            try (NativeImage image = new NativeImage(width, height, false)) {
                image.downloadTexture(0, false);
                byte[] png = image.asByteArray();
                if (PICTURES.size() >= MOST_KEPT) {
                    PICTURES.clear();
                }
                PICTURES.put(location, png);
                return png;
            }
        } catch (Throwable t) {
            failOnce("reading a skin picture back from the game", t);
            // Not tried again: a picture that cannot be read once will not be readable ten ticks later either
            PICTURES.put(location, UNREADABLE);
            return UNREADABLE;
        }
    }

    private static void failOnce(String what, Throwable problem) {
        if (FAILED.add(what)) {
            Host.LOGGER.warn("Dailies: {} failed, so some players may show the game's built-in skins in the recording. Said once", what, problem);
        }
    }

}
