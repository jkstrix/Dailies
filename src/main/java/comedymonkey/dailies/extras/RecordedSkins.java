package comedymonkey.dailies.extras;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The playback half of skins that work offline (specification REC-14). The pictures a recording holds for its
 * players are registered as textures of Dailies' own, and while a recording is open the game is given those for
 * every player the recording knows, in place of asking the network (see the mixins in {@code mixin.extras}).
 * <p>
 * Once a recording has shown that it holds skins, a player it holds none for yet (one who has only just appeared)
 * gets the game's built-in skin and the network is still not asked. A recording made before skins were kept holds
 * none at all, and for those the game looks skins up as it always has.
 */
public final class RecordedSkins implements PlaybackExtensions.Extension {

    /** A player's skin as the game wants it, the textures registered for it, and a mark of the record it came from */
    private record Held(PlayerSkin skin, List<ResourceLocation> textures, int mark) {
    }

    private static final RecordedSkins INSTANCE = new RecordedSkins();

    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    private volatile boolean recordingHoldsSkins = false;
    private int counter = 0;
    private boolean failed = false;

    private RecordedSkins() {
    }

    static void init() {
        PlaybackExtensions.register(SkinCapture.ID, INSTANCE);
        Playback.onClosing(INSTANCE::free);
    }

    /**
     * The skin to show for a player in place of the game's own lookup, or null to leave the game to it. Asked by the
     * mixins for every skin the game wants.
     *
     * @param fallBack whether a player the recording holds nothing for gets the built-in skin (true for players in
     *                 the tab list) or is left to the game (false for anything else, such as a player's head on a wall)
     */
    public static @Nullable PlayerSkin replacement(@Nullable GameProfile profile, boolean fallBack) {
        RecordedSkins skins = INSTANCE;
        if (profile == null || profile.getId() == null || !skins.recordingHoldsSkins || !Playback.active()) {
            return null;
        }
        Held held = skins.held.get(profile.getId());
        if (held != null) {
            return held.skin();
        }
        return fallBack ? DefaultPlayerSkin.get(profile) : null;
    }

    @Override
    public void play(byte[] data, int from, int length, boolean skipping) {
        try {
            ByteBuffer in = ByteBuffer.wrap(data, from, length);
            if (in.get() != SkinCapture.VERSION) {
                return;
            }
            this.recordingHoldsSkins = true;
            UUID id = new UUID(in.getLong(), in.getLong());
            // Every snapshot repeats every player. One that says nothing new changes nothing
            int mark = Arrays.hashCode(Arrays.copyOfRange(data, from, from + length));
            Held before = this.held.get(id);
            if (before != null && before.mark() == mark) {
                return;
            }
            boolean slim = in.get() == 1;
            List<ResourceLocation> textures = new ArrayList<>();
            ResourceLocation skin = this.register(id, "skin", in, textures);
            ResourceLocation cape = this.register(id, "cape", in, textures);
            ResourceLocation elytra = this.register(id, "elytra", in, textures);
            PlayerSkin builtIn = DefaultPlayerSkin.get(id);
            PlayerSkin shown = skin == null
                ? new PlayerSkin(builtIn.texture(), null, cape, elytra, builtIn.model(), true)
                : new PlayerSkin(skin, null, cape, elytra, slim ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, true);
            this.held.put(id, new Held(shown, textures, mark));
            if (before != null) {
                this.release(before);
            }
        } catch (Throwable t) {
            if (!this.failed) {
                this.failed = true;
                Host.LOGGER.warn("Dailies: a player's skin in this recording could not be read, so the built-in skin is shown. Said once", t);
            }
        }
    }

    /** Reads one picture and registers it as a texture. Null when the record holds none */
    private @Nullable ResourceLocation register(UUID id, String kind, ByteBuffer in, List<ResourceLocation> textures) throws Exception {
        int length = in.getInt();
        if (length <= 0) {
            return null;
        }
        byte[] png = new byte[length];
        in.get(png);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Dailies.ID, "recorded_skins/" + id + "/" + kind + "_" + this.counter++);
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(NativeImage.read(png)));
        textures.add(location);
        return location;
    }

    private void release(Held old) {
        TextureManager manager = Minecraft.getInstance().getTextureManager();
        old.textures().forEach(manager::release);
    }

    /** The recording is closing: its pictures are no longer wanted */
    private void free() {
        this.recordingHoldsSkins = false;
        List<Held> all = new ArrayList<>(this.held.values());
        this.held.clear();
        all.forEach(this::release);
    }

}
