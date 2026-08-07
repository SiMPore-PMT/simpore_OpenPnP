package org.openpnp.gui.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Panel;
import org.openpnp.model.PanelLocation;

public class OperatorRuntimeCanvasTest {
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
}
