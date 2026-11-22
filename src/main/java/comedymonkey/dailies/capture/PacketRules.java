package comedymonkey.dailies.capture;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;

import java.util.Set;

/**
 * Which packets a recording leaves out (specification REC-11). The test for each is: would showing this again need
 * the original server, act on the recorder's own screen or body, or leak something about the recorder's account?
 */
final class PacketRules {

    private static final Set<Class<?>> DROPPED = Set.of(
        // About the connection, not the world. The resource pack steps are not replayed either, but what became of
        // each pack is recorded, with its bytes, by PackCapture (REC-19)
        ClientboundKeepAlivePacket.class, ClientboundPingPacket.class, ClientboundPongResponsePacket.class, ClientboundDisconnectPacket.class,
        ClientboundResourcePackPushPacket.class, ClientboundResourcePackPopPacket.class, ClientboundCookieRequestPacket.class,
        ClientboundStoreCookiePacket.class, ClientboundTransferPacket.class, ClientboundCustomReportDetailsPacket.class,
        ClientboundServerLinksPacket.class, ClientboundServerDataPacket.class, ClientboundChunkBatchStartPacket.class,
        ClientboundChunkBatchFinishedPacket.class, ClientboundBlockChangedAckPacket.class, ClientboundDebugSamplePacket.class,
        ClientboundTickingStatePacket.class, ClientboundTickingStepPacket.class, ClientboundLowDiskSpaceWarningPacket.class,
        // Chat signing
        ClientboundDeleteChatPacket.class, ClientboundCustomChatCompletionsPacket.class,
        // The recorder's own screens and windows
        ClientboundOpenScreenPacket.class, ClientboundContainerClosePacket.class, ClientboundContainerSetContentPacket.class,
        ClientboundContainerSetDataPacket.class, ClientboundContainerSetSlotPacket.class, ClientboundMountScreenOpenPacket.class,
        ClientboundMerchantOffersPacket.class, ClientboundOpenBookPacket.class, ClientboundOpenSignEditorPacket.class,
        ClientboundPlaceGhostRecipePacket.class, ClientboundRecipeBookAddPacket.class, ClientboundRecipeBookRemovePacket.class,
        ClientboundRecipeBookSettingsPacket.class, ClientboundUpdateRecipesPacket.class, ClientboundShowDialogPacket.class, ClientboundClearDialogPacket.class,
        ClientboundGameRuleValuesPacket.class, ClientboundTestInstanceBlockStatus.class,
        ClientboundUpdateAdvancementsPacket.class, ClientboundSelectAdvancementsTabPacket.class, ClientboundAwardStatsPacket.class,
        ClientboundCommandSuggestionsPacket.class, ClientboundCommandsPacket.class, ClientboundTagQueryPacket.class,
        ClientboundCooldownPacket.class, ClientboundSetCursorItemPacket.class, ClientboundSetPlayerInventoryPacket.class,
        // Things done to the recorder's own body or view. Where they went and what they held is recorded from the player itself
        ClientboundPlayerPositionPacket.class, ClientboundPlayerAbilitiesPacket.class, ClientboundPlayerLookAtPacket.class,
        ClientboundSetCameraPacket.class, ClientboundSetHeldSlotPacket.class, ClientboundPlayerRotationPacket.class, ClientboundSetHealthPacket.class,
        ClientboundSetExperiencePacket.class, ClientboundMoveVehiclePacket.class, ClientboundPlayerCombatEndPacket.class,
        ClientboundPlayerCombatEnterPacket.class, ClientboundPlayerCombatKillPacket.class
    );

    private PacketRules() {
    }

    static boolean dropped(Packet<?> packet) {
        return DROPPED.contains(packet.getClass());
    }

    /** Game events that act on the recorder's own game: their game mode, the credits, the demo screen and the like */
    static boolean privateEvent(ClientboundGameEventPacket packet) {
        ClientboundGameEventPacket.Type event = packet.getEvent();
        return event == ClientboundGameEventPacket.CHANGE_GAME_MODE || event == ClientboundGameEventPacket.WIN_GAME
            || event == ClientboundGameEventPacket.DEMO_EVENT || event == ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND
            || event == ClientboundGameEventPacket.PUFFER_FISH_STING || event == ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT
            || event == ClientboundGameEventPacket.IMMEDIATE_RESPAWN || event == ClientboundGameEventPacket.LIMITED_CRAFTING;
    }

}
