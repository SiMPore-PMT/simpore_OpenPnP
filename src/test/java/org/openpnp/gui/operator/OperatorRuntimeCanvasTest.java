package org.openpnp.gui.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Panel;
import org.openpnp.model.PanelLocation;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;

public class OperatorRuntimeCanvasTest {
    @BeforeAll
    public static void initializeConfiguration() throws Exception {
        File workingDirectory = new File(com.google.common.io.Files.createTempDir(), ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
    }

    @Test
    public void everyPocketIndexIsStoredWithoutChangingTrayProgress() {
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        JEDEC_TrayFeeder tray = new JEDEC_TrayFeeder();
        tray.setTrayCountRows(2);
        tray.setTrayCountCols(4);
        tray.setFeedCount(3);
        tray.setEnabled(false);

        for (int index : new int[] { 0, 3, 4, 7 }) {
            canvas.selectTrayPocket(tray, index);
            assertSame(tray, canvas.getSelectedPocketTarget().getFeeder());
            assertEquals(index, canvas.getSelectedPocketTarget().getFeedIndexBase0());
            assertEquals(3, tray.getFeedCount());
        }
    }

    @Test
    public void boardPanelAndPocketSelectionsAreMutuallyExclusive() {
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        JEDEC_TrayFeeder tray = new JEDEC_TrayFeeder();
        BoardLocation board = new BoardLocation(new Board());
        PanelLocation panel = new PanelLocation(new Panel());

        canvas.selectTrayPocket(tray, 2);
        assertTrue(canvas.getSelectedBoards().isEmpty());
        canvas.selectBoardTarget(board);
        assertNull(canvas.getSelectedPocketTarget());
        assertTrue(canvas.getSelectedBoards().contains(board));
        canvas.selectTrayPocket(tray, 1);
        assertTrue(canvas.getSelectedBoards().isEmpty());
        canvas.selectPanelTarget(panel);
        assertNull(canvas.getSelectedPocketTarget());
        assertTrue(canvas.getSelectedBoards().contains(panel));
        canvas.selectTrayPocket(tray, 0);
        canvas.clearPocketSelection();
        assertNull(canvas.getSelectedPocketTarget());
    }

    @Test
    public void popupDoesNotAlterPocketSelection() {
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        JEDEC_TrayFeeder tray = new JEDEC_TrayFeeder();
        canvas.selectTrayPocket(tray, 4);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0,
                MouseEvent.BUTTON3_DOWN_MASK, 1, 1, 1, true, MouseEvent.BUTTON3));
        assertEquals(4, canvas.getSelectedPocketTarget().getFeedIndexBase0());
    }

    @Test
    public void clearingHighlightsDoesNotReportAnInspectorSelectionChange() {
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        JEDEC_TrayFeeder tray = new JEDEC_TrayFeeder();
        BoardLocation board = new BoardLocation(new Board());
        final int[] selectionChanges = { 0 };
        canvas.setListener(new NoOpListener() {
            @Override
            public void boardSelectionChanged(Set<PlacementsHolderLocation<?>> selection) {
                selectionChanges[0]++;
            }
        });

        canvas.selectBoardTarget(board);
        assertEquals(1, selectionChanges[0]);
        canvas.clearHighlightSelection();
        assertTrue(canvas.getSelectedBoards().isEmpty());
        assertNull(canvas.getSelectedPocketTarget());
        assertEquals(1, selectionChanges[0]);

        canvas.selectTrayPocket(tray, 2);
        assertEquals(1, selectionChanges[0]);
        canvas.clearHighlightSelection();
        assertNull(canvas.getSelectedPocketTarget());
        assertEquals(1, selectionChanges[0]);
    }

    @Test
    public void trayPocketSelectionSurvivesTheMatchingMouseRelease() throws Exception {
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        JEDEC_TrayFeeder tray = new JEDEC_TrayFeeder();
        tray.setTrayCountRows(2);
        tray.setTrayCountCols(4);
        Configuration.get().getMachine().addFeeder(tray);
        try {
            canvas.setListener(new NoOpListener());
            canvas.setBounds(0, 0, 900, 600);
            paint(canvas);
            Rectangle pocket = canvas.getTrayPocketHitBounds(tray, 3);
            int x = pocket.x + pocket.width / 2;
            int y = pocket.y + pocket.height / 2;

            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, 0,
                    x, y, 1, false, MouseEvent.BUTTON1));
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 1, 0,
                    x, y, 1, false, MouseEvent.BUTTON1));

            assertSame(tray, canvas.getSelectedPocketTarget().getFeeder());
            assertEquals(3, canvas.getSelectedPocketTarget().getFeedIndexBase0());
        }
        finally {
            Configuration.get().getMachine().removeFeeder(tray);
        }
    }

    @Test
    public void calculatedCardsShareWidthAndTrackContent() {
        OperatorRuntimeCanvas.LayoutGeometry geometry =
                OperatorRuntimeCanvas.calculateLayoutGeometry(1200, 800, 2, 2, true);
        assertEquals(geometry.toolCard.width, geometry.legendCard.width);
        assertEquals(18, 1200 - geometry.jobCard.x - geometry.jobCard.width);
        assertTrue(geometry.jobCard.contains(geometry.jobTitle));
        assertTrue(geometry.panelLabel.y >= geometry.panelSelectorCard.y
                + geometry.panelSelectorCard.height + 6);
        assertTrue(!geometry.panelSelectorCard.intersects(geometry.panelLabel));
        assertTrue(geometry.panelSelectorCard.y + geometry.panelSelectorCard.height
                < geometry.jobCard.y);
        assertTrue(geometry.panelLabel.y + geometry.panelLabel.height < geometry.jobCard.y);
        assertEquals(108 + 28 + 76 + 24,
                geometry.jobCard.y + geometry.jobCard.height);
        assertTrue(geometry.jobCard.y + geometry.jobCard.height < 800);
    }

    @Test
    public void runningSelectionKeepsCanvasAndBoardHitGeometryStable() {
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        Board board = new Board();
        for (int i = 0; i < 24; i++) {
            board.addPlacement(new Placement("P" + i));
        }
        BoardLocation boardLocation = new BoardLocation(board);
        Job job = new Job();
        job.getRootPanelLocation().getPanel().addChild(boardLocation);
        final PlacementsHolderLocation<?>[] inspected = new PlacementsHolderLocation<?>[1];
        canvas.setListener(new NoOpListener() {
            @Override
            public void boardClicked(PlacementsHolderLocation<?> selected) {
                inspected[0] = selected;
            }
        });
        canvas.setJob(job);
        canvas.setEditingAllowed(false);
        canvas.setBounds(11, 13, 900, 600);

        paint(canvas);
        Rectangle canvasBounds = canvas.getBounds();
        Rectangle hitBefore = canvas.getBoardHitBounds(boardLocation);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0,
                hitBefore.x + hitBefore.width / 2, hitBefore.y + hitBefore.height / 2,
                1, false, MouseEvent.BUTTON1));
        paint(canvas);

        assertSame(boardLocation, inspected[0]);
        assertTrue(canvas.getSelectedBoards().contains(boardLocation));
        assertEquals(canvasBounds, canvas.getBounds());
        assertEquals(hitBefore, canvas.getBoardHitBounds(boardLocation));
    }

    @Test
    public void runtimeBoardCalibrationDoesNotScatterLayoutCards() {
        Job job = new Job();
        BoardLocation first = boardAt("First", 0, 0);
        BoardLocation second = boardAt("Second", 40, 0);
        BoardLocation third = boardAt("Third", 0, 30);
        BoardLocation fourth = boardAt("Fourth", 40, 30);
        job.getRootPanelLocation().getPanel().addChild(first);
        job.getRootPanelLocation().getPanel().addChild(second);
        job.getRootPanelLocation().getPanel().addChild(third);
        job.getRootPanelLocation().getPanel().addChild(fourth);

        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        canvas.setBounds(0, 0, 900, 600);
        canvas.setJob(job);
        canvas.setEditingAllowed(false);
        paint(canvas);
        Rectangle firstBefore = canvas.getBoardHitBounds(first);
        Rectangle secondBefore = canvas.getBoardHitBounds(second);
        Rectangle thirdBefore = canvas.getBoardHitBounds(third);
        Rectangle fourthBefore = canvas.getBoardHitBounds(fourth);

        // Fiducial alignment can update individual global board locations while a
        // job is running. These machine coordinates must not become UI grid axes.
        second.setGlobalLocation(new Location(LengthUnit.Millimeters, 43.7, -2.4, 0, 0));
        third.setGlobalLocation(new Location(LengthUnit.Millimeters, -3.1, 34.2, 0, 0));
        fourth.setGlobalLocation(new Location(LengthUnit.Millimeters, 45.5, 35.8, 0, 0));
        paint(canvas);

        assertEquals(firstBefore, canvas.getBoardHitBounds(first));
        assertEquals(secondBefore, canvas.getBoardHitBounds(second));
        assertEquals(thirdBefore, canvas.getBoardHitBounds(third));
        assertEquals(fourthBefore, canvas.getBoardHitBounds(fourth));

        // Stopping enables editing again. Starting a second run must not replace
        // the original visual grid with the calibrated machine coordinates.
        canvas.selectBoardTarget(fourth);
        canvas.setEditingAllowed(true);
        canvas.setJob(job);
        paint(canvas);
        assertTrue(canvas.getSelectedBoards().contains(fourth));
        assertEquals(firstBefore, canvas.getBoardHitBounds(first));
        assertEquals(secondBefore, canvas.getBoardHitBounds(second));
        assertEquals(thirdBefore, canvas.getBoardHitBounds(third));
        assertEquals(fourthBefore, canvas.getBoardHitBounds(fourth));

        canvas.setEditingAllowed(false);
        paint(canvas);
        assertEquals(firstBefore, canvas.getBoardHitBounds(first));
        assertEquals(secondBefore, canvas.getBoardHitBounds(second));
        assertEquals(thirdBefore, canvas.getBoardHitBounds(third));
        assertEquals(fourthBefore, canvas.getBoardHitBounds(fourth));

        // A job refresh/reset may rebuild location objects while retaining their
        // stable job IDs. The cached layout must survive that identity change.
        BoardLocation replacement = boardAt("Replacement", 43.7, -2.4);
        replacement.setId(second.getId());
        job.getRootPanelLocation().removeChild(second);
        job.getRootPanelLocation().addChild(replacement);
        canvas.setJob(job);
        paint(canvas);
        assertEquals(secondBefore, canvas.getBoardHitBounds(replacement));
    }

    @Test
    public void differentJobReplacesLayoutSnapshot() {
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        canvas.setBounds(0, 0, 900, 600);
        Job firstJob = jobWith(boardAt("Shared", 0, 0), boardAt("Right", 40, 0));
        canvas.setJob(firstJob);
        paint(canvas);
        Rectangle sharedInFirstJob = canvas.getBoardHitBounds(firstJob.getBoardLocations().get(0));

        Job secondJob = jobWith(boardAt("Shared", 0, 0), boardAt("Above", 0, 30));
        canvas.setJob(secondJob);
        paint(canvas);

        Rectangle sharedInSecondJob = canvas.getBoardHitBounds(secondJob.getBoardLocations().get(0));
        Rectangle above = canvas.getBoardHitBounds(secondJob.getBoardLocations().get(1));
        assertEquals(sharedInSecondJob.x, above.x);
        assertTrue(sharedInSecondJob.y > above.y);
        assertTrue(!sharedInFirstJob.equals(sharedInSecondJob));
    }

    @Test
    public void sameJobRefreshMergesAddedAndRemovedBoardIds() {
        BoardLocation retained = boardAt("Retained", 0, 0);
        BoardLocation removed = boardAt("Removed", 40, 0);
        Job job = jobWith(retained, removed);
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        canvas.setBounds(0, 0, 900, 600);
        canvas.setJob(job);
        paint(canvas);
        Rectangle retainedBefore = canvas.getBoardHitBounds(retained);

        retained.setGlobalLocation(new Location(LengthUnit.Millimeters, 7, 9, 0, 0));
        BoardLocation replacement = boardAt("Replacement", 7, 9);
        replacement.setId(retained.getId());
        BoardLocation added = boardAt("Added", 0, 30);
        job.getRootPanelLocation().removeChild(retained);
        job.getRootPanelLocation().removeChild(removed);
        job.getRootPanelLocation().addChild(replacement);
        job.getRootPanelLocation().addChild(added);
        canvas.setJob(job);
        paint(canvas);

        assertEquals(retainedBefore.x, canvas.getBoardHitBounds(replacement).x);
        assertTrue(canvas.getBoardHitBounds(added).y < canvas.getBoardHitBounds(replacement).y);
        assertNull(canvas.getBoardHitBounds(removed));
    }

    @Test
    public void horizontalPanelCandidatesUseBottomSlots() {
        BoardLocation left = boardAt("Left", 0, 0);
        BoardLocation right = boardAt("Right", 40, 5);

        List<OperatorRuntimeCanvas.PanelSlot> slots = new OperatorRuntimeCanvas()
                .getPanelSlots(jobWith(left, right));

        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.BOTTOM_LEFT, left);
        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.BOTTOM_RIGHT, right);
    }

    @Test
    public void verticalPanelCandidatesUseLeftSlots() {
        BoardLocation bottom = boardAt("Bottom", 0, 0);
        BoardLocation top = boardAt("Top", 5, 40);

        List<OperatorRuntimeCanvas.PanelSlot> slots = new OperatorRuntimeCanvas()
                .getPanelSlots(jobWith(bottom, top));

        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.BOTTOM_LEFT, bottom);
        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.TOP_LEFT, top);
    }

    @Test
    public void rectangularPanelCandidatesUseAllFourSlots() {
        BoardLocation bottomLeft = boardAt("Bottom Left", 0, 0);
        BoardLocation bottomRight = boardAt("Bottom Right", 40, 3);
        BoardLocation topLeft = boardAt("Top Left", 2, 40);
        BoardLocation topRight = boardAt("Top Right", 42, 43);

        List<OperatorRuntimeCanvas.PanelSlot> slots = new OperatorRuntimeCanvas()
                .getPanelSlots(jobWith(bottomLeft, bottomRight, topLeft, topRight));

        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.BOTTOM_LEFT, bottomLeft);
        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.BOTTOM_RIGHT, bottomRight);
        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.TOP_LEFT, topLeft);
        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.TOP_RIGHT, topRight);
    }

    @Test
    public void runtimeGlobalLocationsDoNotChangePanelSlots() {
        BoardLocation left = boardAt("Left", 0, 0);
        BoardLocation right = boardAt("Right", 40, 5);
        Job job = jobWith(left, right);
        OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
        canvas.setJob(job);

        left.setGlobalLocation(new Location(LengthUnit.Millimeters, 100, 100, 0, 0));
        right.setGlobalLocation(new Location(LengthUnit.Millimeters, -100, -100, 0, 0));
        List<OperatorRuntimeCanvas.PanelSlot> slots = canvas.getPanelSlots(job);

        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.BOTTOM_LEFT, left);
        assertSlot(slots, OperatorRuntimeCanvas.PanelQuadrant.BOTTOM_RIGHT, right);
    }

    private static void assertSlot(List<OperatorRuntimeCanvas.PanelSlot> slots,
            OperatorRuntimeCanvas.PanelQuadrant quadrant, PlacementsHolderLocation<?> expected) {
        OperatorRuntimeCanvas.PanelSlot slot = slots.stream()
                .filter(candidate -> candidate.quadrant == quadrant)
                .findFirst().orElseThrow(AssertionError::new);
        assertSame(expected, slot.location);
    }

    private static Job jobWith(BoardLocation... boards) {
        Job job = new Job();
        for (BoardLocation board : boards) {
            job.getRootPanelLocation().getPanel().addChild(board);
        }
        return job;
    }

    private static BoardLocation boardAt(String id, double x, double y) {
        Board board = new Board();
        board.setName(id);
        BoardLocation location = new BoardLocation(board);
        location.setLocation(new Location(LengthUnit.Millimeters, x, y, 0, 0));
        return location;
    }

    private static void paint(OperatorRuntimeCanvas canvas) {
        BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        canvas.paint(graphics);
        graphics.dispose();
    }

    private static class NoOpListener implements OperatorRuntimeCanvas.Listener {
        @Override public void boardClicked(PlacementsHolderLocation<?> boardLocation) { }
        @Override public void boardSelectionChanged(Set<PlacementsHolderLocation<?>> selection) { }
        @Override public void showBoardContextMenu(Component invoker, int x, int y,
                Set<PlacementsHolderLocation<?>> selection) { }
        @Override public void showPlacementContextMenu(Component invoker, int x, int y,
                Set<PlacementsHolderLocation<?>> selection) { }
        @Override public void showTrayPocketContextMenu(Component invoker, int x, int y,
                JEDEC_TrayFeeder feeder, int feedIndexBase0, int displayPosition) { }
        @Override public void resetTray(JEDEC_TrayFeeder feeder) { }
        @Override public void enableTray(JEDEC_TrayFeeder feeder) { }
        @Override public void panelSelectionChanged(PlacementsHolderLocation<?> panelLocation) { }
        @Override public void trayPocketSelectionChanged(JEDEC_TrayFeeder feeder, int feedIndexBase0) { }
    }
}
