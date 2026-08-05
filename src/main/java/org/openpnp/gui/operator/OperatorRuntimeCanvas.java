package org.openpnp.gui.operator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JPanel;
import javax.swing.UIManager;

import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Job;
import org.openpnp.model.Location;
import org.openpnp.model.PanelLocation;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Nozzle;

@SuppressWarnings("serial")
public class OperatorRuntimeCanvas extends JPanel {
    public enum EditMode { NONE, BOARD, PLACEMENT }

    public interface Listener {
        void boardClicked(PlacementsHolderLocation<?> boardLocation);
        void boardSelectionChanged(Set<PlacementsHolderLocation<?>> selection);
        void showBoardContextMenu(Component invoker, int x, int y, Set<PlacementsHolderLocation<?>> selection);
        void showPlacementContextMenu(Component invoker, int x, int y, Set<PlacementsHolderLocation<?>> selection);
        void showTrayPocketContextMenu(Component invoker, int x, int y, JEDEC_TrayFeeder feeder,
                int feedIndexBase0, int displayPosition);
        void resetTray(JEDEC_TrayFeeder feeder);
        void enableTray(JEDEC_TrayFeeder feeder);
        void panelSelectionChanged();
    }

    private static final Color BACKGROUND = new Color(35, 39, 46);
    private static final Color SECTION = new Color(47, 53, 62);
    private static final Color BORDER = new Color(92, 105, 124);
    private static final Color TEXT = new Color(218, 224, 232);
    private static final Color MUTED_TEXT = new Color(165, 175, 188);
    private static final Color TRAY_AVAILABLE = new Color(78, 143, 190);
    private static final Color TRAY_USED = new Color(82, 88, 98);
    private static final Color BOARD_FILL = new Color(58, 72, 92);
    private static final Color BOARD_DISABLED = new Color(74, 72, 70);
    private static final Color BOARD_BORDER = new Color(86, 135, 190);
    private static final Color SELECTED = new Color(38, 120, 210);
    private static final Color PLACEMENT = new Color(174, 54, 54);
    private static final Color PLACED = new Color(210, 158, 24);
    private static final Color FIDUCIAL = new Color(44, 150, 82);
    private static final Color DISPENSE = new Color(210, 132, 34);
    private static final int TOOL_HEIGHT = 96;
    private static final int BOARD_WIDTH = 108;
    private static final int BOARD_HEIGHT = 76;

    private Job job;
    private HeadMountable selectedTool;
    private Listener listener;
    private EditMode editMode = EditMode.NONE;
    private boolean editingAllowed;
    private final Set<PlacementsHolderLocation<?>> selectedBoards = new LinkedHashSet<>();
    private final List<BoardHit> boardHits = new ArrayList<>();
    private final List<TrayPocketHit> trayPocketHits = new ArrayList<>();
    private final List<TrayActionHit> trayActionHits = new ArrayList<>();
    private final List<PanelQuadrantHit> panelQuadrantHits = new ArrayList<>();
    private PanelQuadrant selectedPanelQuadrant;
    private Rectangle dragRectangle;
    private int dragStartX;
    private int dragStartY;

    public OperatorRuntimeCanvas() {
        setPreferredSize(new Dimension(760, 520));
        setBackground(BACKGROUND);
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                    return;
                }
                PanelQuadrantHit quadrantHit = findPanelQuadrant(e.getX(), e.getY());
                if (quadrantHit != null) {
                    selectPanelQuadrant(quadrantHit.slot.quadrant);
                    return;
                }
                TrayActionHit actionHit = findTrayAction(e.getX(), e.getY());
                if (actionHit != null && listener != null) {
                    if (actionHit.reset) {
                        listener.resetTray(actionHit.feeder);
                    }
                    else {
                        listener.enableTray(actionHit.feeder);
                    }
                    return;
                }
                if (editMode != EditMode.NONE && editingAllowed) {
                    dragStartX = e.getX();
                    dragStartY = e.getY();
                    dragRectangle = null;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (editMode == EditMode.NONE || !editingAllowed) {
                    return;
                }
                dragRectangle = normalizedRectangle(dragStartX, dragStartY, e.getX(), e.getY());
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                    return;
                }
                boolean editSelection = editMode != EditMode.NONE && editingAllowed;
                if (editSelection && dragRectangle != null && dragRectangle.width > 4 && dragRectangle.height > 4) {
                    selectBoardsIn(dragRectangle, isMenuShortcutDown(e));
                    dragRectangle = null;
                    repaint();
                    return;
                }
                BoardHit hit = findBoard(e.getX(), e.getY());
                if (hit != null) {
                    selectBoard(hit.boardLocation, editSelection && isMenuShortcutDown(e));
                    if (listener != null) {
                        listener.boardClicked(hit.boardLocation);
                    }
                }
                dragRectangle = null;
                repaint();
            }
        };
        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setEditMode(EditMode editMode) {
        this.editMode = editMode == null ? EditMode.NONE : editMode;
        repaint();
    }

    public void setEditingAllowed(boolean editingAllowed) {
        this.editingAllowed = editingAllowed;
        repaint();
    }

    public Set<PlacementsHolderLocation<?>> getSelectedBoards() {
        return new LinkedHashSet<>(selectedBoards);
    }

    public void setJob(Job job) {
        this.job = job;
        selectedBoards.clear();
        selectedPanelQuadrant = null;
        repaint();
    }

    public void clearSelection() {
        selectedBoards.clear();
        dragRectangle = null;
        repaint();
    }

    public void setSelectedTool(HeadMountable selectedTool) {
        this.selectedTool = selectedTool;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        boardHits.clear();
        trayPocketHits.clear();
        trayActionHits.clear();
        panelQuadrantHits.clear();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.5f));
            drawTool(g2);
            drawLegend(g2);
            drawJob(g2);
            drawFeeders(g2);
            drawDragSelection(g2);
        }
        finally {
            g2.dispose();
        }
    }

    private void drawTool(Graphics2D g2) {
        String text = "Tool: none";
        if (selectedTool != null) {
            String name = selectedTool.getName() == null ? selectedTool.getClass().getSimpleName() : selectedTool.getName();
            String lower = name.toLowerCase();
            String type = selectedTool instanceof Nozzle ? "Pick head" :
                    (lower.contains("dispense") || lower.contains("needle") || lower.contains("glue") ? "Dispense" : "Tool");
            text = type + ": " + name;
        }
        g2.setColor(SECTION);
        g2.fillRoundRect(10, 8, Math.min(getWidth() - 20, 280), 26, 10, 10);
        g2.setColor(BORDER);
        g2.drawRoundRect(10, 8, Math.min(getWidth() - 20, 280), 26, 10, 10);
        g2.setColor(TEXT);
        g2.drawString(text, 22, 26);
    }

    private void drawLegend(Graphics2D g2) {
        int legendX = 10;
        int legendY = 40;
        int legendWidth = Math.min(getWidth() - 20, 360);
        g2.setColor(SECTION);
        g2.fillRoundRect(legendX, legendY, legendWidth, 48, 10, 10);
        g2.setColor(BORDER);
        g2.drawRoundRect(legendX, legendY, legendWidth, 48, 10, 10);
        int lx = 22;
        int topY = 58;
        int bottomY = 78;
        lx = drawLegendItem(g2, lx, topY, PLACEMENT, "Placement") + 24;
        lx = drawLegendItem(g2, lx, topY, FIDUCIAL, "Fiducial") + 24;
        drawLegendItem(g2, lx, topY, DISPENSE, "Dispense");
        lx = drawLegendItem(g2, 22, bottomY, PLACED, "Placed") + 24;
        g2.setColor(MUTED_TEXT);
        g2.drawString("Disabled = hidden", lx, bottomY);
    }

    private int drawLegendItem(Graphics2D g2, int x, int y, Color color, String label) {
        g2.setColor(color);
        g2.fillOval(x, y - 8, 10, 10);
        g2.setColor(MUTED_TEXT);
        g2.drawString(label, x + 14, y);
        return x + 14 + g2.getFontMetrics().stringWidth(label);
    }

    private void drawFeeders(Graphics2D g2) {
        if (Configuration.get().getMachine() == null) {
            return;
        }
        List<Feeder> trays = new ArrayList<>();
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (feeder instanceof JEDEC_TrayFeeder || feeder instanceof ReferenceTrayFeeder) {
                trays.add(feeder);
            }
        }
        int y = getHeight() - 18;
        int x = 12;
        for (int i = trays.size() - 1; i >= 0; i--) {
            Feeder feeder = trays.get(i);
            int height = feeder instanceof JEDEC_TrayFeeder
                    ? getJedecTrayHeight((JEDEC_TrayFeeder) feeder)
                    : getReferenceTrayHeight((ReferenceTrayFeeder) feeder);
            y -= height;
            if (y < TOOL_HEIGHT + 6) {
                break;
            }
            if (feeder instanceof JEDEC_TrayFeeder) {
                drawJedecTray(g2, (JEDEC_TrayFeeder) feeder, x, y);
            }
            else {
                drawReferenceTray(g2, (ReferenceTrayFeeder) feeder, x, y);
            }
            y -= 8;
        }
    }

    private int getJedecTrayHeight(JEDEC_TrayFeeder tray) {
        int cell = Math.max(14, Math.min(24, 240 / Math.max(tray.getEffectiveTrayCountRows(), tray.getEffectiveTrayCountCols())));
        return tray.getEffectiveTrayCountRows() * cell + 104;
    }

    private int getReferenceTrayHeight(ReferenceTrayFeeder tray) {
        int cell = Math.max(12, Math.min(20, 220 / Math.max(tray.getEffectiveTrayCountY(), tray.getEffectiveTrayCountX())));
        return tray.getEffectiveTrayCountY() * cell + 34;
    }

    private int drawJedecTray(Graphics2D g2, JEDEC_TrayFeeder tray, int x, int y) {
        int rows = tray.getEffectiveTrayCountRows();
        int cols = tray.getEffectiveTrayCountCols();
        int feedCount = tray.getFeedCount();
        int capacity = rows * cols;
        int nextIndex = Math.max(0, Math.min(feedCount, capacity - 1));
        int cell = Math.max(14, Math.min(24, 240 / Math.max(rows, cols)));
        int gridWidth = cols * cell;
        int gridHeight = rows * cell;
        int cardWidth = Math.max(gridWidth + 20, 244);
        int cardHeight = gridHeight + 104;
        boolean enabled = tray.isEnabled();
        g2.setColor(enabled ? SECTION : new Color(90, 72, 56, 80));
        g2.fillRoundRect(x - 6, y - 4, cardWidth, cardHeight, 12, 12);
        g2.setColor(enabled ? BORDER : new Color(190, 110, 60));
        g2.drawRoundRect(x - 6, y - 4, cardWidth, cardHeight, 12, 12);
        g2.setColor(enabled ? TEXT : new Color(245, 150, 100));
        String status = enabled ? "Next pick: " + (nextIndex + 1) + " • Remaining " + Math.max(0, capacity - feedCount) : "Disabled";
        g2.drawString(tray.getName(), x, y + 12);
        g2.setColor(enabled ? MUTED_TEXT : new Color(245, 150, 100));
        g2.drawString(status, x, y + 28);
        for (int index = 0; index < capacity; index++) {
            JEDEC_TrayFeeder.GridIndex grid = JEDEC_TrayFeeder.getGridIndexForFeed(index, rows, cols,
                    tray.getStartCorner(), tray.getFirstRasterDirection(), tray.getRasterPattern());
            int px = x + grid.col * cell;
            int py = y + 36 + grid.row * cell;
            g2.setColor(!enabled ? BOARD_DISABLED : index < feedCount ? TRAY_USED : TRAY_AVAILABLE);
            g2.fillRoundRect(px, py, cell - 2, cell - 2, 3, 3);
            g2.setColor(new Color(255, 255, 255, 85));
            g2.drawRoundRect(px, py, cell - 2, cell - 2, 3, 3);
            if (enabled && index == nextIndex) {
                g2.setStroke(new BasicStroke(2.4f));
                g2.setColor(PLACED);
                g2.drawRoundRect(px - 2, py - 2, cell + 2, cell + 2, 6, 6);
                g2.setStroke(new BasicStroke(1.5f));
            }
            String pocket = Integer.toString(index + 1);
            g2.setColor(TEXT);
            int sw = g2.getFontMetrics().stringWidth(pocket);
            g2.drawString(pocket, px + Math.max(1, (cell - sw) / 2 - 1), py + Math.max(10, cell / 2 + 4));
            trayPocketHits.add(new TrayPocketHit(tray, index, index + 1,
                    new Rectangle(px, py, cell - 2, cell - 2)));
        }
        int controlsY = y + 36 + gridHeight + 16;
        Rectangle reset = new Rectangle(x, controlsY, 30, 30);
        Rectangle enable = new Rectangle(x + 96, controlsY + 5, 120, 20);
        drawResetControl(g2, reset, enabled);
        drawEnableToggle(g2, enable, enabled);
        trayActionHits.add(new TrayActionHit(tray, reset, true));
        trayActionHits.add(new TrayActionHit(tray, enable, false));
        return y + cardHeight + 4;
    }

    private void drawResetControl(Graphics2D g2, Rectangle bounds, boolean enabled) {
        g2.setColor(enabled ? new Color(66, 76, 90) : new Color(92, 64, 48));
        g2.fillOval(bounds.x, bounds.y, bounds.width, bounds.height);
        g2.setColor(enabled ? BORDER : new Color(210, 120, 70));
        g2.drawOval(bounds.x, bounds.y, bounds.width, bounds.height);
        g2.setColor(enabled ? TEXT : new Color(255, 185, 130));
        g2.drawString("↻", bounds.x + 8, bounds.y + 19);
    }

    private void drawEnableToggle(Graphics2D g2, Rectangle bounds, boolean enabled) {
        int cy = bounds.y + bounds.height / 2;
        g2.setColor(TEXT);
        g2.drawOval(bounds.x, cy - 6, 12, 12);
        if (enabled) {
            g2.fillOval(bounds.x + 3, cy - 3, 6, 6);
        }
        g2.drawString(enabled ? "Enabled" : "Disabled", bounds.x + 18, bounds.y + 14);
    }

    private int drawReferenceTray(Graphics2D g2, ReferenceTrayFeeder tray, int x, int y) {
        int rows = tray.getEffectiveTrayCountY();
        int cols = tray.getEffectiveTrayCountX();
        int cell = Math.max(12, Math.min(20, 220 / Math.max(rows, cols)));
        g2.setColor(MUTED_TEXT);
        g2.drawString(tray.getName() + "  (view only)", x, y + 10);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = row * cols + col;
                g2.setColor(index < tray.getFeedCount() ? TRAY_USED : new Color(80, 125, 155));
                g2.fillRoundRect(x + col * cell, y + 18 + (rows - 1 - row) * cell, cell - 2, cell - 2, 3, 3);
            }
        }
        return y + 34 + rows * cell;
    }

    private void drawJob(Graphics2D g2) {
        if (job == null) {
            g2.setColor(SECTION);
            g2.fillRoundRect(getWidth() / 3, getHeight() / 3, getWidth() / 3, 70, 16, 16);
            g2.setColor(BORDER);
            g2.drawRoundRect(getWidth() / 3, getHeight() / 3, getWidth() / 3, 70, 16, 16);
            g2.setColor(MUTED_TEXT);
            g2.drawString("No job loaded", getWidth() / 2 - 42, getHeight() / 3 + 38);
            return;
        }

        List<PanelSlot> slots = getPanelSlots(job);
        PanelSlot selectedSlot = getSelectedPanelSlot(slots);
        List<PlacementsHolderLocation<?>> visibleBoards = getVisibleBoardLocations(selectedSlot);
        if (visibleBoards.isEmpty()) {
            return;
        }

        List<Location> locations = new ArrayList<>();
        for (PlacementsHolderLocation<?> boardLocation : visibleBoards) {
            if (!boardLocation.isEnabled()) {
                continue;
            }
            locations.add(boardLocation.getGlobalLocation());
            for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                if (placement.isEnabled()) {
                    locations.add(boardLocation.getGlobalLocation().add(placement.getLocation()));
                }
            }
        }
        if (locations.isEmpty()) {
            return;
        }
        Bounds bounds = new Bounds(locations);
        int trayColumnWidth = Math.min(300, Math.max(230, getWidth() / 3));
        Rectangle area = new Rectangle(trayColumnWidth + 16, 46, getWidth() - trayColumnWidth - 34, getHeight() - 60);
        g2.setColor(TEXT);
        g2.drawString("Job Layout" + (selectedSlot == null || populatedPanelSlotCount(slots) <= 1 ? "" : " • " + selectedSlot.quadrant.label), area.x, area.y - 10);
        g2.setColor(SECTION);
        g2.fillRoundRect(area.x, area.y, area.width, area.height, 14, 14);
        g2.setColor(BORDER);
        g2.drawRoundRect(area.x, area.y, area.width, area.height, 14, 14);
        boolean showPanelSelector = populatedPanelSlotCount(slots) > 1;
        if (showPanelSelector) {
            drawPanelSelector(g2, area, slots);
        }
        Rectangle contentArea = showPanelSelector
                ? new Rectangle(area.x, area.y + 96, area.width, Math.max(1, area.height - 96))
                : area;
        List<PlacementsHolderLocation<?>> drawnBoards = new ArrayList<>();
        for (PlacementsHolderLocation<?> boardLocation : visibleBoards) {
            if (drawBoard(g2, bounds, contentArea, boardLocation)) {
                drawnBoards.add(boardLocation);
            }
        }
        pruneHiddenSelection(drawnBoards);
    }

    private boolean drawBoard(Graphics2D g2, Bounds bounds, Rectangle area, PlacementsHolderLocation<?> boardLocation) {
        Location bl = boardLocation.getGlobalLocation();
        int bx = bounds.x(bl, area);
        int by = bounds.y(bl, area);
        Rectangle boardBounds = new Rectangle(bx - BOARD_WIDTH / 2, by - BOARD_HEIGHT / 2, BOARD_WIDTH, BOARD_HEIGHT);
        boolean enabled = boardLocation.isEnabled();
        if (!enabled) {
            return false;
        }
        boolean selected = selectedBoards.contains(boardLocation);
        g2.setColor(enabled ? BOARD_FILL : BOARD_DISABLED);
        g2.fillRoundRect(boardBounds.x, boardBounds.y, boardBounds.width, boardBounds.height, 10, 10);
        g2.setColor(enabled ? BOARD_BORDER : MUTED_TEXT);
        g2.drawRoundRect(boardBounds.x, boardBounds.y, boardBounds.width, boardBounds.height, 10, 10);
        if (selected) {
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(SELECTED);
            g2.drawRoundRect(boardBounds.x - 4, boardBounds.y - 4, boardBounds.width + 8, boardBounds.height + 8, 12, 12);
            g2.setStroke(new BasicStroke(1.5f));
        }
        boardHits.add(new BoardHit(boardLocation, boardBounds));
        if (!enabled) {
            return;
        }
        for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
            drawPlacement(g2, bounds, area, boardLocation, placement, enabled);
        }
        return true;
    }

    private void drawPlacement(Graphics2D g2, Bounds bounds, Rectangle area, PlacementsHolderLocation<?> boardLocation,
            Placement placement, boolean boardEnabled) {
        if (!boardEnabled || !placement.isEnabled()) {
            return;
        }
        Location p = boardLocation.getGlobalLocation().add(placement.getLocation());
        int x = bounds.x(p, area);
        int y = bounds.y(p, area);
        boolean placed = job.retrievePlacedStatus(boardLocation, placement.getId());
        Color color = placed ? PLACED : (placement.getType() == Placement.Type.Fiducial ? FIDUCIAL
                : placement.getType() == Placement.Type.Dispense ? DISPENSE : PLACEMENT);
        if (!boardEnabled || !placementEnabled) {
            return;
        }
        g2.setColor(color);
        if (placement.getType() == Placement.Type.Fiducial) {
            g2.fillOval(x - 10, y - 10, 21, 21);
        }
        else if (placement.getType() == Placement.Type.Dispense) {
            g2.setStroke(new BasicStroke(2.8f));
            g2.drawOval(x - 13, y - 13, 26, 26);
            g2.setStroke(new BasicStroke(1.5f));
        }
        else if (placement.getType() == Placement.Type.Placement) {
            g2.setStroke(new BasicStroke(3.0f));
            g2.drawLine(x - 13, y - 13, x + 13, y + 13);
            g2.drawLine(x + 13, y - 13, x - 13, y + 13);
            g2.setStroke(new BasicStroke(1.5f));
        }
    }


    private void drawPanelSelector(Graphics2D g2, Rectangle area, List<PanelSlot> slots) {
        int cardWidth = 122;
        int cardHeight = 76;
        int x = area.x + area.width - cardWidth - 14;
        int y = area.y + 12;
        g2.setColor(new Color(40, 46, 55, 235));
        g2.fillRoundRect(x, y, cardWidth, cardHeight, 14, 14);
        g2.setColor(new Color(120, 135, 155));
        g2.drawRoundRect(x, y, cardWidth, cardHeight, 14, 14);
        int cellW = 48;
        int cellH = 24;
        int gap = 6;
        int gridX = x + 11;
        int gridY = y + 12;
        for (PanelSlot slot : slots) {
            int col = slot.quadrant.right ? 1 : 0;
            int row = slot.quadrant.top ? 0 : 1;
            Rectangle cell = new Rectangle(gridX + col * (cellW + gap), gridY + row * (cellH + gap), cellW, cellH);
            boolean selected = slot.quadrant == selectedPanelQuadrant;
            boolean populated = slot.location != null;
            g2.setColor(selected ? new Color(38, 120, 210, 170)
                    : populated ? new Color(72, 86, 104, 220) : new Color(58, 63, 72, 150));
            g2.fillRoundRect(cell.x, cell.y, cell.width, cell.height, 8, 8);
            g2.setColor(selected ? PLACED : populated ? BOARD_BORDER : new Color(100, 108, 118));
            g2.drawRoundRect(cell.x, cell.y, cell.width, cell.height, 8, 8);
            g2.setColor(populated ? TEXT : MUTED_TEXT);
            g2.drawString(slot.quadrant.shortName, cell.x + 15, cell.y + 16);
            if (populated) {
                panelQuadrantHits.add(new PanelQuadrantHit(slot, cell));
            }
        }
        g2.setColor(MUTED_TEXT);
        g2.drawString("Panels", x + 42, y + cardHeight - 8);
    }

    private List<PanelSlot> getPanelSlots(Job job) {
        List<PlacementsHolderLocation<?>> candidates = getPanelCandidates(job);
        double centerX = 0;
        double centerY = 0;
        for (PlacementsHolderLocation<?> candidate : candidates) {
            Location location = candidate.getGlobalLocation();
            centerX += location.getX();
            centerY += location.getY();
        }
        if (!candidates.isEmpty()) {
            centerX /= candidates.size();
            centerY /= candidates.size();
        }
        EnumMap<PanelQuadrant, PanelSlot> byQuadrant = new EnumMap<>(PanelQuadrant.class);
        for (PanelQuadrant quadrant : PanelQuadrant.values()) {
            byQuadrant.put(quadrant, new PanelSlot(quadrant, null));
        }
        for (PlacementsHolderLocation<?> candidate : candidates) {
            PanelQuadrant quadrant = classifyQuadrant(candidate.getGlobalLocation(), centerX, centerY);
            PanelSlot current = byQuadrant.get(quadrant);
            if (current.location == null || quadrantScore(candidate.getGlobalLocation(), centerX, centerY, quadrant) >
                    quadrantScore(current.location.getGlobalLocation(), centerX, centerY, quadrant)) {
                byQuadrant.put(quadrant, new PanelSlot(quadrant, candidate));
            }
        }
        List<PanelSlot> slots = new ArrayList<>();
        for (PanelQuadrant quadrant : PanelQuadrant.selectionOrder()) {
            slots.add(byQuadrant.get(quadrant));
        }
        return slots;
    }


    private int populatedPanelSlotCount(List<PanelSlot> slots) {
        int count = 0;
        for (PanelSlot slot : slots) {
            if (slot.location != null) {
                count++;
            }
        }
        return count;
    }

    private List<PlacementsHolderLocation<?>> getPanelCandidates(Job job) {
        List<PlacementsHolderLocation<?>> children = job.getRootPanelLocation().getChildren();
        if (!children.isEmpty()) {
            return new ArrayList<>(children);
        }
        LinkedHashSet<PlacementsHolderLocation<?>> roots = new LinkedHashSet<>();
        for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
            roots.add(findPanelRoot(job, boardLocation));
        }
        return new ArrayList<>(roots);
    }

    private PlacementsHolderLocation<?> findPanelRoot(Job job, PlacementsHolderLocation<?> location) {
        PlacementsHolderLocation<?> root = location;
        PanelLocation jobRoot = job.getRootPanelLocation();
        while (root.getParent() != null && root.getParent() != jobRoot) {
            root = root.getParent();
        }
        return root;
    }

    private PanelSlot getSelectedPanelSlot(List<PanelSlot> slots) {
        PanelSlot fallback = null;
        for (PanelSlot slot : slots) {
            if (slot.location == null) {
                continue;
            }
            if (fallback == null) {
                fallback = slot;
            }
            if (slot.quadrant == selectedPanelQuadrant) {
                return slot;
            }
        }
        if (fallback != null && selectedPanelQuadrant != fallback.quadrant) {
            selectedPanelQuadrant = fallback.quadrant;
        }
        return fallback;
    }

    private List<PlacementsHolderLocation<?>> getVisibleBoardLocations(PanelSlot selectedSlot) {
        List<PlacementsHolderLocation<?>> boards = new ArrayList<>();
        if (selectedSlot == null || selectedSlot.location == null) {
            return boards;
        }
        collectBoardLocations(selectedSlot.location, boards);
        return boards;
    }

    private void collectBoardLocations(PlacementsHolderLocation<?> location, List<PlacementsHolderLocation<?>> boards) {
        if (location instanceof BoardLocation) {
            boards.add(location);
        }
        else if (location instanceof PanelLocation) {
            for (PlacementsHolderLocation<?> child : ((PanelLocation) location).getChildren()) {
                collectBoardLocations(child, boards);
            }
        }
    }

    private PanelQuadrant classifyQuadrant(Location location, double centerX, double centerY) {
        boolean right = location.getX() > centerX;
        boolean top = location.getY() > centerY;
        if (top && right) {
            return PanelQuadrant.TOP_RIGHT;
        }
        if (top) {
            return PanelQuadrant.TOP_LEFT;
        }
        if (right) {
            return PanelQuadrant.BOTTOM_RIGHT;
        }
        return PanelQuadrant.BOTTOM_LEFT;
    }

    private double quadrantScore(Location location, double centerX, double centerY, PanelQuadrant quadrant) {
        double dx = quadrant.right ? location.getX() - centerX : centerX - location.getX();
        double dy = quadrant.top ? location.getY() - centerY : centerY - location.getY();
        return dx + dy;
    }

    private void selectPanelQuadrant(PanelQuadrant quadrant) {
        if (quadrant == selectedPanelQuadrant) {
            return;
        }
        selectedPanelQuadrant = quadrant;
        selectedBoards.clear();
        dragRectangle = null;
        if (listener != null) {
            listener.boardSelectionChanged(getSelectedBoards());
            listener.panelSelectionChanged();
        }
        repaint();
    }

    private void pruneHiddenSelection(List<PlacementsHolderLocation<?>> visibleBoards) {
        if (!selectedBoards.retainAll(new LinkedHashSet<>(visibleBoards)) || listener == null) {
            return;
        }
        listener.boardSelectionChanged(getSelectedBoards());
    }

    private void drawDragSelection(Graphics2D g2) {
        if (dragRectangle == null) {
            return;
        }
        g2.setColor(new Color(38, 120, 210, 42));
        g2.fill(dragRectangle);
        g2.setColor(SELECTED);
        g2.draw(dragRectangle);
    }

    private void selectBoard(PlacementsHolderLocation<?> boardLocation, boolean extendSelection) {
        if (!extendSelection) {
            selectedBoards.clear();
        }
        if (extendSelection && selectedBoards.contains(boardLocation)) {
            selectedBoards.remove(boardLocation);
        }
        else {
            selectedBoards.add(boardLocation);
        }
        if (listener != null) {
            listener.boardSelectionChanged(getSelectedBoards());
        }
    }

    private void selectBoardsIn(Rectangle rectangle, boolean extendSelection) {
        if (!extendSelection) {
            selectedBoards.clear();
        }
        for (BoardHit hit : boardHits) {
            if (rectangle.intersects(hit.bounds)) {
                selectedBoards.add(hit.boardLocation);
            }
        }
        if (listener != null) {
            listener.boardSelectionChanged(getSelectedBoards());
        }
    }

    private void showPopup(MouseEvent e) {
        TrayPocketHit pocket = findTrayPocket(e.getX(), e.getY());
        if (pocket != null && listener != null) {
            listener.showTrayPocketContextMenu(this, e.getX(), e.getY(), pocket.feeder,
                    pocket.feedIndexBase0, pocket.displayPosition);
            return;
        }
        if (editMode == EditMode.NONE || !editingAllowed) {
            return;
        }
        BoardHit board = findBoard(e.getX(), e.getY());
        if (board != null && !selectedBoards.contains(board.boardLocation)) {
            selectBoard(board.boardLocation, false);
        }
        if (selectedBoards.isEmpty() || listener == null) {
            return;
        }
        if (editMode == EditMode.BOARD) {
            listener.showBoardContextMenu(this, e.getX(), e.getY(), getSelectedBoards());
        }
        else if (editMode == EditMode.PLACEMENT) {
            listener.showPlacementContextMenu(this, e.getX(), e.getY(), getSelectedBoards());
        }
    }

    private BoardHit findBoard(int x, int y) {
        for (BoardHit hit : boardHits) {
            if (hit.bounds.contains(x, y)) {
                return hit;
            }
        }
        return null;
    }

    private PanelQuadrantHit findPanelQuadrant(int x, int y) {
        for (PanelQuadrantHit hit : panelQuadrantHits) {
            if (hit.bounds.contains(x, y)) {
                return hit;
            }
        }
        return null;
    }

    private TrayActionHit findTrayAction(int x, int y) {
        for (TrayActionHit hit : trayActionHits) {
            if (hit.bounds.contains(x, y)) {
                return hit;
            }
        }
        return null;
    }

    private TrayPocketHit findTrayPocket(int x, int y) {
        for (TrayPocketHit hit : trayPocketHits) {
            if (hit.bounds.contains(x, y)) {
                return hit;
            }
        }
        return null;
    }

    private boolean isMenuShortcutDown(MouseEvent e) {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        return (e.getModifiersEx() & mask) != 0;
    }

    private Rectangle normalizedRectangle(int x1, int y1, int x2, int y2) {
        return new Rectangle(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x1 - x2), Math.abs(y1 - y2));
    }


    private enum PanelQuadrant {
        BOTTOM_LEFT("BL", "Bottom Left", false, false),
        BOTTOM_RIGHT("BR", "Bottom Right", true, false),
        TOP_LEFT("TL", "Top Left", false, true),
        TOP_RIGHT("TR", "Top Right", true, true);

        final String shortName;
        final String label;
        final boolean right;
        final boolean top;

        PanelQuadrant(String shortName, String label, boolean right, boolean top) {
            this.shortName = shortName;
            this.label = label;
            this.right = right;
            this.top = top;
        }

        static PanelQuadrant[] selectionOrder() {
            return new PanelQuadrant[] { BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT };
        }
    }

    private static class PanelSlot {
        final PanelQuadrant quadrant;
        final PlacementsHolderLocation<?> location;
        PanelSlot(PanelQuadrant quadrant, PlacementsHolderLocation<?> location) {
            this.quadrant = quadrant;
            this.location = location;
        }
    }

    private static class PanelQuadrantHit {
        final PanelSlot slot;
        final Rectangle bounds;
        PanelQuadrantHit(PanelSlot slot, Rectangle bounds) {
            this.slot = slot;
            this.bounds = bounds;
        }
    }

    private static class BoardHit {
        final PlacementsHolderLocation<?> boardLocation;
        final Rectangle bounds;
        BoardHit(PlacementsHolderLocation<?> boardLocation, Rectangle bounds) {
            this.boardLocation = boardLocation;
            this.bounds = bounds;
        }
    }

    private void drawButton(Graphics2D g2, Rectangle bounds, String text, boolean enabled) {
        g2.setColor(enabled ? new Color(66, 76, 90) : new Color(92, 64, 48));
        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8);
        g2.setColor(enabled ? BORDER : new Color(210, 120, 70));
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8);
        g2.setColor(enabled ? TEXT : new Color(255, 185, 130));
        g2.drawString(text, bounds.x + 10, bounds.y + 16);
    }

    private static class TrayActionHit {
        final JEDEC_TrayFeeder feeder;
        final Rectangle bounds;
        final boolean reset;
        TrayActionHit(JEDEC_TrayFeeder feeder, Rectangle bounds, boolean reset) {
            this.feeder = feeder;
            this.bounds = bounds;
            this.reset = reset;
        }
    }

    private static class TrayPocketHit {
        final JEDEC_TrayFeeder feeder;
        final int feedIndexBase0;
        final int displayPosition;
        final Rectangle bounds;
        TrayPocketHit(JEDEC_TrayFeeder feeder, int feedIndexBase0, int displayPosition, Rectangle bounds) {
            this.feeder = feeder;
            this.feedIndexBase0 = feedIndexBase0;
            this.displayPosition = displayPosition;
            this.bounds = bounds;
        }
    }

    private static class Bounds {
        double minX, maxX, minY, maxY;
        Bounds(List<Location> locations) {
            minX = maxX = locations.get(0).getX();
            minY = maxY = locations.get(0).getY();
            for (Location l : locations) {
                minX = Math.min(minX, l.getX());
                maxX = Math.max(maxX, l.getX());
                minY = Math.min(minY, l.getY());
                maxY = Math.max(maxY, l.getY());
            }
        }
        int x(Location l, Rectangle r) {
            return r.x + 24 + (int) ((l.getX() - minX) / Math.max(1, maxX - minX) * (r.width - 48) * 0.42
                    + (r.width - 48) * 0.29);
        }
        int y(Location l, Rectangle r) {
            return r.y + r.height - 24 - (int) ((l.getY() - minY) / Math.max(1, maxY - minY) * (r.height - 48) * 0.68
                    + (r.height - 48) * 0.16);
        }
    }
}
