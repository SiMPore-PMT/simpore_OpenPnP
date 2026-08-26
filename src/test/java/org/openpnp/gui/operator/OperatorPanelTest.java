package org.openpnp.gui.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Panel;
import org.openpnp.model.PanelLocation;

public class OperatorPanelTest {
    @Test
    public void panelContextMenuEnablesOnlyStateChangingActionAndDelegatesRequestedState() {
        PanelLocation panel = new PanelLocation(new Panel());
        List<Boolean> requestedStates = new ArrayList<>();

        panel.setEnabled(true);
        JPopupMenu enabledMenu = OperatorPanel.createPanelContextMenu(Set.of(panel), requestedStates::add);
        JMenuItem enable = (JMenuItem) enabledMenu.getComponent(0);
        JMenuItem disable = (JMenuItem) enabledMenu.getComponent(1);
        assertFalse(enable.isEnabled());
        assertTrue(disable.isEnabled());
        disable.doClick();
        assertEquals(List.of(false), requestedStates);

        panel.setEnabled(false);
        JPopupMenu disabledMenu = OperatorPanel.createPanelContextMenu(Set.of(panel), requestedStates::add);
        enable = (JMenuItem) disabledMenu.getComponent(0);
        disable = (JMenuItem) disabledMenu.getComponent(1);
        assertTrue(enable.isEnabled());
        assertFalse(disable.isEnabled());
        enable.doClick();
        assertEquals(List.of(false, true), requestedStates);
    }

    @Test
    public void mixedPanelContextMenuAllowsEitherBulkStateChange() {
        PanelLocation enabledPanel = new PanelLocation(new Panel());
        PanelLocation disabledPanel = new PanelLocation(new Panel());
        enabledPanel.setEnabled(true);
        disabledPanel.setEnabled(false);

        JPopupMenu menu = OperatorPanel.createPanelContextMenu(
                Set.of(enabledPanel, disabledPanel), ignored -> { });

        assertTrue(menu.getComponent(0).isEnabled());
        assertTrue(menu.getComponent(1).isEnabled());
    }

    @Test
    public void selectionDependentEditControlsPreserveCanvasHighlight() {
        JPanel root = new JPanel();
        JPanel canvas = new JPanel();
        JButton moveCamera = new JButton();
        JLabel moveCameraChild = new JLabel();
        moveCamera.add(moveCameraChild);
        JToolBar editingToolBar = new JToolBar();
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
        for (Component control : new Component[] { resetBoards, dispense, edit,
                boards, placements, pickAndPlace, dispenseFilter, fiducials, nestedToolbarChild }) {
            editingToolBar.add(control);
        }
        JPanel detailsPanel = new JPanel(new BorderLayout());
        JTable placementDetailsTable = new JTable();
        JScrollPane placementDetailsScrollPane = new JScrollPane(placementDetailsTable);
        JLabel detailsChild = new JLabel();
        detailsPanel.add(placementDetailsScrollPane, BorderLayout.CENTER);
        detailsPanel.add(detailsChild, BorderLayout.SOUTH);
        JButton unrelatedControl = new JButton();
        root.add(canvas);
        root.add(moveCamera);
        root.add(editingToolBar);
        root.add(detailsPanel);
        root.add(unrelatedControl);

        assertFalse(OperatorPanel.shouldDismissHighlight(canvas, canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(moveCamera, canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(moveCameraChild, canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(editingToolBar, canvas, moveCamera, editingToolBar, detailsPanel));
        for (Component control : editingToolBar.getComponents()) {
            assertFalse(OperatorPanel.shouldDismissHighlight(control, canvas, moveCamera, editingToolBar, detailsPanel));
        }
        assertFalse(OperatorPanel.shouldDismissHighlight(nestedLabel, canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(detailsPanel, canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(placementDetailsScrollPane,
                canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(placementDetailsScrollPane.getViewport(),
                canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(placementDetailsTable,
                canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(detailsChild,
                canvas, moveCamera, editingToolBar, detailsPanel));

        JPopupMenu popup = new JPopupMenu();
        JMenuItem enableItem = new JMenuItem("Enable");
        popup.add(enableItem);
        assertFalse(OperatorPanel.shouldDismissHighlight(popup,
                canvas, moveCamera, editingToolBar, detailsPanel));
        assertFalse(OperatorPanel.shouldDismissHighlight(enableItem,
                canvas, moveCamera, editingToolBar, detailsPanel));

        assertTrue(OperatorPanel.shouldDismissHighlight(unrelatedControl,
                canvas, moveCamera, editingToolBar, detailsPanel));
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
