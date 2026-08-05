package org.openpnp.gui.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Job;
import org.openpnp.model.Placement;

public class OperatorJobEditingServiceTest {
    @BeforeAll
    public static void initializeConfiguration() throws Exception {
        File workingDirectory = com.google.common.io.Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
    }

    @Test
    public void resetBoardsOnlyDoesNotResetJedecFeedCount() throws Exception {
        OperatorJobEditingService service = new OperatorJobEditingService();
        Job job = jobWithBoard();
        BoardLocation board = job.getBoardLocations().get(0);
        Placement placement = board.getPlacementsHolder().getPlacements().get(0);
        JEDEC_TrayFeeder feeder = new JEDEC_TrayFeeder();
        feeder.setFeedCount(7);
        board.setLocallyEnabled(false);
        placement.setEnabled(false);
        job.storePlacedStatus(board, placement.getId(), true);

        service.resetBoardsOnly(job);

        assertEquals(7, feeder.getFeedCount());
        assertTrue(board.isLocallyEnabled());
        assertTrue(placement.isEnabled());
        assertFalse(job.retrievePlacedStatus(board, placement.getId()));
    }

    @Test
    public void placementTogglesUpdateJobState() throws Exception {
        OperatorJobEditingService service = new OperatorJobEditingService();
        Job job = jobWithBoard();
        BoardLocation board = job.getBoardLocations().get(0);
        Placement placement = board.getPlacementsHolder().getPlacements().get(0);

        service.setPlacementEnabled(job, board, placement, false);
        service.setPlacementPlaced(job, board, placement, true);

        assertFalse(placement.isEnabled());
        assertFalse(job.retrieveEnabledState(board, placement));
        assertTrue(job.retrievePlacedStatus(board, placement.getId()));
    }

    @Test
    public void globalDispenseRestorePreservesManuallyDisabledDispense() throws Exception {
        OperatorJobEditingService service = new OperatorJobEditingService();
        Job job = jobWithBoard();
        BoardLocation board = job.getBoardLocations().get(0);
        Placement manuallyDisabled = new Placement("D1");
        manuallyDisabled.setType(Placement.Type.Dispense);
        manuallyDisabled.setEnabled(false);
        Placement globallyDisabled = new Placement("D2");
        globallyDisabled.setType(Placement.Type.Dispense);
        board.getPlacementsHolder().addPlacement(manuallyDisabled);
        board.getPlacementsHolder().addPlacement(globallyDisabled);

        assertEquals(1, service.setDispenseGloballyEnabled(job, false));
        assertFalse(manuallyDisabled.isEnabled());
        assertFalse(globallyDisabled.isEnabled());

        assertEquals(1, service.setDispenseGloballyEnabled(job, true));
        assertFalse(manuallyDisabled.isEnabled());
        assertTrue(globallyDisabled.isEnabled());
    }

    @Test
    public void jedecDisplayPositionConvertsToStoredFeedCountAndRefreshResets() throws Exception {
        OperatorJobEditingService service = new OperatorJobEditingService();
        JEDEC_TrayFeeder feeder = new JEDEC_TrayFeeder();
        feeder.setTrayCountRows(2);
        feeder.setTrayCountCols(4);

        assertEquals(5, service.setJedecTrayStartingPosition(null, feeder, 5));
        assertEquals(5, feeder.getFeedCount());

        assertEquals(0, service.resetJedecTray(null, feeder));
        assertEquals(0, feeder.getFeedCount());
    }

    private Job jobWithBoard() {
        Job job = new Job();
        Board board = new Board();
        Placement placement = new Placement("P1");
        placement.setType(Placement.Type.Placement);
        board.addPlacement(placement);
        BoardLocation boardLocation = new BoardLocation(board);
        job.getRootPanelLocation().getPanel().addChild(boardLocation);
        return job;
    }
}
