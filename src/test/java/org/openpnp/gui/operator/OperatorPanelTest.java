package org.openpnp.gui.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;

import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.Test;

public class OperatorPanelTest {
    @Test
    public void editingToolbarClicksPreserveCanvasHighlight() {
        JPanel root = new JPanel();
        JPanel canvas = new JPanel();
        JButton moveCamera = new JButton();
        JLabel moveCameraChild = new JLabel();
        moveCamera.add(moveCameraChild);
        JToolBar editingToolbar = new JToolBar();
        JButton resetBoards = new JButton("Reset Boards");
        JButton dispense = new JButton("Dispense");
        JButton edit = new JButton("Edit");
        JButton boards = new JButton("Boards");
        JButton placements = new JButton("Placements");
        JButton pickAndPlace = new JButton("Pick-and-place");
        JButton dispenseFilter = new JButton("Dispense filter");
        JButton fiducials = new JButton("Fiducials");
        JPanel nestedToolbarChild = new JPanel();
        JLabel nestedLabel = new JLabel();
        nestedToolbarChild.add(nestedLabel);
        for (Component control : new Component[] { resetBoards, dispense, moveCamera, edit,
                boards, placements, pickAndPlace, dispenseFilter, fiducials, nestedToolbarChild }) {
            editingToolbar.add(control);
        }
        JButton unrelatedControl = new JButton();
        root.add(canvas);
        root.add(editingToolbar);
        root.add(unrelatedControl);

        assertFalse(OperatorPanel.shouldDismissHighlight(canvas, canvas, moveCamera, editingToolbar));
        assertFalse(OperatorPanel.shouldDismissHighlight(moveCamera, canvas, moveCamera, editingToolbar));
        assertFalse(OperatorPanel.shouldDismissHighlight(moveCameraChild, canvas, moveCamera, editingToolbar));
        assertFalse(OperatorPanel.shouldDismissHighlight(editingToolbar, canvas, moveCamera, editingToolbar));
        for (Component control : editingToolbar.getComponents()) {
            assertFalse(OperatorPanel.shouldDismissHighlight(control, canvas, moveCamera, editingToolbar));
        }
        assertFalse(OperatorPanel.shouldDismissHighlight(nestedLabel, canvas, moveCamera, editingToolbar));
        assertTrue(OperatorPanel.shouldDismissHighlight(unrelatedControl, canvas, moveCamera, editingToolbar));
    }

    @Test
    public void inspectorRowsScrollWithoutMovingInitializedRuntimeSplit() {
        DefaultTableModel model = new DefaultTableModel(new Object[] { "Id" }, 0);
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        JScrollPane scrollPane = new JScrollPane(table);
        JLabel selectionLabel = new JLabel("Select a board to inspect placements.");
        JPanel inspector = new JPanel(new BorderLayout(4, 4));
        inspector.add(selectionLabel, BorderLayout.NORTH);
        inspector.add(scrollPane, BorderLayout.CENTER);
        OperatorPanel.setDetailsPanelPreferredSize(inspector, table, scrollPane, selectionLabel);

        JPanel runtimeCanvas = new JPanel();
        runtimeCanvas.setMinimumSize(new Dimension(360, 240));
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, runtimeCanvas, inspector);
        splitPane.setSize(900, 700);
        splitPane.setDividerLocation(420);
        splitPane.doLayout();
        inspector.doLayout();
        scrollPane.doLayout();
        int dividerBefore = splitPane.getDividerLocation();
        Rectangle canvasBefore = runtimeCanvas.getBounds();
        Dimension inspectorPreferredBefore = inspector.getPreferredSize();

        selectionLabel.setText("Board: board-with-many-placements");
        for (int i = 0; i < 24; i++) {
            model.addRow(new Object[] { "P" + i });
        }
        splitPane.doLayout();
        inspector.doLayout();
        scrollPane.doLayout();

        assertEquals("Board: board-with-many-placements", selectionLabel.getText());
        assertEquals(24, model.getRowCount());
        assertEquals(inspectorPreferredBefore, inspector.getPreferredSize());
        assertEquals(dividerBefore, splitPane.getDividerLocation());
        assertEquals(canvasBefore, runtimeCanvas.getBounds());
        assertTrue(scrollPane.getVerticalScrollBar().isVisible());
        assertTrue(table.getPreferredSize().height > scrollPane.getViewport().getExtentSize().height);
    }
}
