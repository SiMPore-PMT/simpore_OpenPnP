package org.openpnp.gui.operator;

import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.model.PlacementsHolderLocation;

/** An immutable, mutually exclusive operator camera navigation target. */
public final class CameraTarget {
    public enum Type { BOARD, TRAY_POCKET }

    private final Type type;
    private final PlacementsHolderLocation<?> boardLocation;
    private final JEDEC_TrayFeeder feeder;
    private final int feedIndexBase0;

    private CameraTarget(Type type, PlacementsHolderLocation<?> boardLocation,
            JEDEC_TrayFeeder feeder, int feedIndexBase0) {
        this.type = type;
        this.boardLocation = boardLocation;
        this.feeder = feeder;
        this.feedIndexBase0 = feedIndexBase0;
    }

    public static CameraTarget board(PlacementsHolderLocation<?> boardLocation) {
        if (boardLocation == null) throw new IllegalArgumentException("boardLocation");
        return new CameraTarget(Type.BOARD, boardLocation, null, -1);
    }

    public static CameraTarget trayPocket(JEDEC_TrayFeeder feeder, int feedIndexBase0) {
        if (feeder == null || feedIndexBase0 < 0) throw new IllegalArgumentException("tray pocket");
        return new CameraTarget(Type.TRAY_POCKET, null, feeder, feedIndexBase0);
    }

    public Type getType() { return type; }
    public PlacementsHolderLocation<?> getBoardLocation() { return boardLocation; }
    public JEDEC_TrayFeeder getFeeder() { return feeder; }
    public int getFeedIndexBase0() { return feedIndexBase0; }
}
