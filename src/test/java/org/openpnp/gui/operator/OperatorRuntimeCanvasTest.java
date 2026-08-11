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
