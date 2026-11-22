package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.CreateClient;
import com.simibubi.create.content.decoration.slidingDoor.DoorControl;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.AssemblyScreen;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.station.StationScreen;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.UUID;

/**
 * A train station's screens, which only the player's game knows: the station's own (its name and the train's as typed,
 * what the doors do, the train standing there or coming in) and its assembly screen (the bogeys found, why assembly
 * failed). Each is recorded as the station and what is being set; the rest (the station's block, the bogeys counted, a
 * failure) is read from the playback world's station, as the recording holds it, and Create's own screen draws it,
 * never sending anything. The train is drawn when the playback game knows it by its id.
 */
final class StationScreens {

    static final String STATION = "station";
    static final String ASSEMBLY = "train_assembly";

    private StationScreens() {
    }

    static byte[] capture(Screen screen) {
        StationBlockEntity be = Reflect.get(screen, "blockEntity");
        String name = "";
        String trainName = "";
        String doors = "";
        String train = "";
        boolean present = false;
        if (screen instanceof StationScreen) {
            EditBox nameBox = Reflect.get(screen, "nameBox");
            EditBox trainBox = Reflect.get(screen, "trainNameBox");
            DoorControl doorControl = Reflect.get(screen, "doorControl");
            WeakReference<Train> shown = Reflect.get(screen, "displayedTrain");
            Train displayed = shown == null ? null : shown.get();
            name = nameBox == null ? "" : nameBox.getValue();
            trainName = trainBox != null && trainBox.active ? trainBox.getValue() : "";
            doors = doorControl == null ? "" : doorControl.name();
            train = displayed == null || displayed.id == null ? "" : displayed.id.toString();
            present = Reflect.get(be, "trainPresent");
        } else {
            GlobalStation station = Reflect.get(screen, "station");
            name = station == null || station.name == null ? "" : station.name;
        }
        return States.station(new States.Station(be.getBlockPos().asLong(), screen instanceof AssemblyScreen, name, trainName, doors, train, present));
    }

    /** Built on the playback world's station, only read, with a station of its own holding the name as typed */
    static @Nullable Screen build(byte[] state) {
        States.Station recorded = States.readStation(state);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(BlockPos.of(recorded.pos())) instanceof StationBlockEntity be)) {
            return null;
        }
        GlobalStation station = new GlobalStation();
        station.name = recorded.name();
        return recorded.assembling() ? new RecordedAssembly(be, station) : new RecordedStation(be, station, recorded);
    }

    static final class RecordedStation extends StationScreen implements RecordedScreen {
        private final States.Station recorded;

        RecordedStation(StationBlockEntity be, GlobalStation station, States.Station recorded) {
            super(be, station);
            this.recorded = recorded;
            // Before the screen lays itself out: its door setting is made from this
            for (DoorControl doors : DoorControl.values()) {
                if (doors.name().equals(recorded.doorControl())) {
                    Reflect.set(this, "doorControl", doors);
                }
            }
        }

        @Override
        protected void init() {
            super.init();
            ((EditBox) Reflect.get(this, "nameBox")).setValue(this.recorded.name());
            EditBox trainBox = Reflect.get(this, "trainNameBox");
            if (trainBox.active) {
                trainBox.setValue(this.recorded.trainName());
            }
        }

        /** The train shown, as the playback game knows it */
        @Override
        protected @Nullable Train getImminent() {
            if (this.recorded == null || this.recorded.train().isEmpty()) {
                return null;
            }
            try {
                return CreateClient.RAILWAYS.trains.get(UUID.fromString(this.recorded.train()));
            } catch (IllegalArgumentException notAnId) {
                return null;
            }
        }

        @Override
        protected boolean trainPresent() {
            return this.recorded != null && this.recorded.trainPresent();
        }

        @Override
        public void tick() {
        }

        @Override
        public void removed() {
            // Never sends the name, the doors or the train's name
        }
    }

    static final class RecordedAssembly extends AssemblyScreen implements RecordedScreen {
        RecordedAssembly(StationBlockEntity be, GlobalStation station) {
            super(be, station);
        }

        /** None: a train coming in would make the live screen send and open the station's own */
        @Override
        protected @Nullable Train getImminent() {
            return null;
        }

        @Override
        public void tick() {
        }

        @Override
        public void removed() {
        }
    }

}
