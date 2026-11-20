package comedymonkey.dailiescompat.template;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The example note: using it opens a screen only the player's game knows, the kind a compat has to record itself */
public final class ExampleNoteItem extends Item {

    /** Set by the client half; nothing on a server */
    public static Runnable opener = () -> {
    };

    public ExampleNoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            opener.run();
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

}
