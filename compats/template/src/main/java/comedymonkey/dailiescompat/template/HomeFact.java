package comedymonkey.dailiescompat.template;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * What the server half sends and the client half reads (specification SRV-9): where the player respawns, as a block
 * position packed into eight bytes (BlockPos.asLong), or nothing when they have none set. Apart from both halves, so the
 * client half never loads a class that refers to the companion.
 */
public final class HomeFact {

    /** The server half's plugin id, which the client half asks for */
    public static final String PLUGIN = TemplateMod.ID + ":home";
    /** Raise when the data changes shape */
    public static final int VERSION = 1;

    private HomeFact() {
    }

    public static byte[] write(@Nullable BlockPos home) {
        return home == null ? new byte[0] : ByteBuffer.allocate(8).putLong(home.asLong()).array();
    }

    /** The position the data names, or null for none */
    public static @Nullable BlockPos read(byte[] data) {
        return data == null || data.length < 8 ? null : BlockPos.of(ByteBuffer.wrap(data).getLong());
    }

}
