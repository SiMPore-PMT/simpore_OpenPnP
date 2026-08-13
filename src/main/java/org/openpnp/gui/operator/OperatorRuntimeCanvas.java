package org.openpnp.gui.operator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import org.openpnp.util.Utils2D;

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
        void panelSelectionChanged(PlacementsHolderLocation<?> panelLocation);
        void trayPocketSelectionChanged(JEDEC_TrayFeeder feeder, int feedIndexBase0);
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
    private static final Color FIDUCIAL = new Color(155, 105, 210);
    private static final Color DISPENSE = new Color(44, 150, 82);
    private static final int TOOL_HEIGHT = 116;
    private static final int BOARD_WIDTH = 108;
    private static final int BOARD_HEIGHT = 76;
    private static final int BOARD_GAP = 10;
    private static final int JOB_CONTENT_PADDING = 10;
    private static final int JOB_HEADER_HEIGHT = 28;

    private Job job;
    private HeadMountable selectedTool;
    private Listener listener;
    private EditMode editMode = EditMode.NONE;
    private boolean editingAllowed;
    private final HashMap<String, Location> stableLayoutLocationsById = new HashMap<>();
    private final Set<PlacementsHolderLocation<?>> selectedBoards = new LinkedHashSet<>();
    private final List<BoardHit> boardHits = new ArrayList<>();
    private final List<TrayPocketHit> trayPocketHits = new ArrayList<>();
    private final List<TrayActionHit> trayActionHits = new ArrayList<>();
    private final List<PanelQuadrantHit> panelQuadrantHits = new ArrayList<>();
    private PanelQuadrant selectedPanelQuadrant;
    private CameraTarget selectedPocketTarget;
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
                TrayPocketHit pocketHit = findTrayPocket(e.getX(), e.getY());
                if (pocketHit != null) {
                    selectTrayPocket(pocketHit);
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
        if (this.editingAllowed && !editingAllowed) {
            captureRuntimeLayout();
        }
        this.editingAllowed = editingAllowed;
        repaint();
    }

    public Set<PlacementsHolderLocation<?>> getSelectedBoards() {
        return new LinkedHashSet<>(selectedBoards);
    }

    Rectangle getBoardHitBounds(PlacementsHolderLocation<?> boardLocation) {
        for (BoardHit hit : boardHits) {
            if (hit.boardLocation == boardLocation) {
                return new Rectangle(hit.bounds);
            }
        }
        return null;
    }

    public void setJob(Job job) {
        if (this.job != job) {
            selectedBoards.clear();
            selectedPanelQuadrant = null;
            selectedPocketTarget = null;
            this.job = job;
            captureRuntimeLayout();
        }
        repaint();
    }

    private void captureRuntimeLayout() {
        stableLayoutLocationsById.clear();
        if (job == null) {
            return;
        }
        for (PlacementsHolderLocation<?> board : job.getBoardLocations()) {
            stableLayoutLocationsById.put(board.getUniqueId(), board.getGlobalLocation());
        }
    }

    private Location getLayoutLocation(PlacementsHolderLocation<?> board) {
        Location location = stableLayoutLocationsById.get(board.getUniqueId());
        if (location != null) {
            return location;
        }
        return board.getGlobalLocation();
    }

    public void clearSelection() {
        selectedBoards.clear();
        selectedPocketTarget = null;
        dragRectangle = null;
        repaint();
    }

    public CameraTarget getSelectedPocketTarget() {
        return selectedPocketTarget;
    }

    public void clearPocketSelection() {
        if (selectedPocketTarget != null) {
            selectedPocketTarget = null;
            repaint();
        }
    }

    // Package-private semantic entry points keep selection tests independent of pixels.
    void selectTrayPocket(JEDEC_TrayFeeder feeder, int feedIndexBase0) {
        selectTrayPocket(new TrayPocketHit(feeder, feedIndexBase0, feedIndexBase0 + 1, new Rectangle()));
    }

    void selectBoardTarget(PlacementsHolderLocation<?> boardLocation) {
        selectBoard(boardLocation, false);
    }

    void selectPanelTarget(PanelLocation panelLocation) {
        clearPocketSelection();
        selectedBoards.clear();
        selectedBoards.add(panelLocation);
        if (listener != null) listener.panelSelectionChanged(panelLocation);
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
        int legendWidth = Math.min(getWidth() - 20, 280);
        g2.setColor(SECTION);
        g2.fillRoundRect(legendX, legendY, legendWidth, 68, 10, 10);
        g2.setColor(BORDER);
        g2.drawRoundRect(legendX, legendY, legendWidth, 68, 10, 10);
        int lx = legendX + 12;
        int topY = 58;
        int bottomY = 78;
        lx = drawLegendItem(g2, lx, topY, PLACEMENT, "Placement") + 12;
        lx = drawLegendItem(g2, lx, topY, FIDUCIAL, "Fiducial") + 12;
        drawLegendItem(g2, legendX + 12, bottomY, DISPENSE, "Dispense");
        drawLegendItem(g2, legendX + 102, bottomY, PLACED, "Placed");
        g2.setColor(MUTED_TEXT);
        g2.drawString("Disabled = hidden", legendX + 12, 98);
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
        return tray.getEffectiveTrayCountRows() * cell + 88;
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
        int nextIndex = feedCount >= 0 && feedCount < capacity ? feedCount : -1;
        int cell = Math.max(14, Math.min(24, 240 / Math.max(rows, cols)));
        int gridWidth = cols * cell;
        int gridHeight = rows * cell;
        int cardWidth = Math.max(gridWidth + 20, 244);
        int cardHeight = gridHeight + 88;
        boolean enabled = tray.isEnabled();
        g2.setColor(enabled ? SECTION : new Color(90, 72, 56, 80));
        g2.fillRoundRect(x - 6, y - 4, cardWidth, cardHeight, 12, 12);
        g2.setColor(enabled ? BORDER : new Color(190, 110, 60));
        g2.drawRoundRect(x - 6, y - 4, cardWidth, cardHeight, 12, 12);
        g2.setColor(enabled ? TEXT : new Color(245, 150, 100));
        String status = enabled ? "Next pick: " + (nextIndex < 0 ? "-" : nextIndex + 1)
                + " • Remaining " + Math.max(0, capacity - feedCount) : "Disabled";
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
            Stroke oldStroke = g2.getStroke();
            if (isSelectedPocket(tray, index)) {
                g2.setColor(new Color(35, 78, 128));
                g2.fillRoundRect(px, py, cell - 2, cell - 2, 3, 3);
                g2.setStroke(new BasicStroke(3f));
                g2.setColor(new Color(205, 92, 255));
                g2.drawRoundRect(px - 2, py - 2, cell + 2, cell + 2, 6, 6);
            }
            if (enabled && index == nextIndex) {
                // Draw this after selection so the current-pocket gold marker remains
                // visible inside the purple selection outline.
                g2.setStroke(new BasicStroke(2.4f));
                g2.setColor(PLACED);
                g2.drawRoundRect(px + 1, py + 1, cell - 4, cell - 4, 4, 4);
            }
            g2.setStroke(oldStroke);
            trayPocketHits.add(new TrayPocketHit(tray, index, index + 1,
                    new Rectangle(px, py, cell - 2, cell - 2)));
        }
        int controlsY = y + 36 + gridHeight + 8;
        Rectangle reset = new Rectangle(x, controlsY, 30, 30);
        Rectangle enable = new Rectangle(x + 42, controlsY + 5, 82, 20);
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
        Font originalFont = g2.getFont();
        Font iconFont = originalFont.deriveFont(Font.BOLD, 24f);
        GlyphVector glyph = iconFont.createGlyphVector(g2.getFontRenderContext(), "↻");
        Rectangle2D visualBounds = glyph.getVisualBounds();
        double iconX = bounds.getCenterX() - visualBounds.getCenterX();
        double iconY = bounds.getCenterY() - visualBounds.getCenterY() + 1.5;
        Shape icon = glyph.getOutline((float) iconX, (float) iconY);
        AffineTransform rotate = AffineTransform.getRotateInstance(
                Math.toRadians(18), bounds.getCenterX(), bounds.getCenterY());
        g2.fill(rotate.createTransformedShape(icon));
        g2.setFont(originalFont);
    }

    private void drawEnableToggle(Graphics2D g2, Rectangle bounds, boolean enabled) {
        double centerX = bounds.x + 7.5;
        double centerY = bounds.getCenterY();
        double outerRadius = 7;
        double innerRadius = 3;
        g2.setColor(TEXT);
        g2.draw(new Ellipse2D.Double(centerX - outerRadius, centerY - outerRadius,
                outerRadius * 2, outerRadius * 2));
        if (enabled) {
            g2.fill(new Ellipse2D.Double(centerX - innerRadius, centerY - innerRadius,
                    innerRadius * 2, innerRadius * 2));
        }
        g2.drawString(enabled ? "Enabled" : "Disabled",
                (float) (centerX + outerRadius + 5), bounds.y + 14);
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
            int trayColumnWidth = Math.min(300, Math.max(230, getWidth() / 3));
            int availableWidth = Math.max(1, getWidth() - trayColumnWidth - 34);
            int cardWidth = Math.min(280, availableWidth);
            int cardX = getWidth() - cardWidth - 18;
            g2.setColor(SECTION);
            g2.fillRoundRect(cardX, 46, cardWidth, 70, 16, 16);
            g2.setColor(BORDER);
            g2.drawRoundRect(cardX, 46, cardWidth, 70, 16, 16);
            g2.setColor(MUTED_TEXT);
            int textWidth = g2.getFontMetrics().stringWidth("No job loaded");
            g2.drawString("No job loaded", cardX + (cardWidth - textWidth) / 2, 84);
            return;
        }

        List<PanelSlot> slots = getPanelSlots(job);
        PanelSlot selectedSlot = getSelectedPanelSlot(slots);
        List<PlacementsHolderLocation<?>> visibleBoards = getVisibleBoardLocations(selectedSlot);
        if (visibleBoards.isEmpty()) {
            pruneHiddenSelection(visibleBoards);
            g2.setColor(MUTED_TEXT);
            g2.drawString("No boards in selected panel", Math.min(getWidth() - 190,
                    Math.min(300, Math.max(230, getWidth() / 3)) + 28), 84);
            return;
        }

        boolean showPanelSelector = populatedPanelSlotCount(slots) > 1;
        int[] grid = boardGridSize(visibleBoards);
        LayoutGeometry geometry = calculateLayoutGeometry(getWidth(), getHeight(), grid[0], grid[1], showPanelSelector);
        Rectangle area = geometry.jobCard;
        if (showPanelSelector) {
            drawPanelSelector(g2, geometry.panelSelectorCard, geometry.panelLabel, slots);
        }
        g2.setColor(SECTION);
        g2.fillRoundRect(area.x, area.y, area.width, area.height, 14, 14);
        g2.setColor(BORDER);
        g2.drawRoundRect(area.x, area.y, area.width, area.height, 14, 14);

        g2.setColor(TEXT);
        g2.drawString("Job Layout", area.x + 14, area.y + 19);
        if (selectedSlot != null && showPanelSelector) {
            g2.setColor(MUTED_TEXT);
            g2.drawString(selectedSlot.quadrant.label, area.x + 88, area.y + 19);
        }
        int headerHeight = JOB_HEADER_HEIGHT;
        Rectangle content = new Rectangle(area.x + 12, area.y + headerHeight,
                Math.max(1, area.width - JOB_CONTENT_PADDING * 2),
                Math.max(1, area.height - headerHeight - 12));
        Bounds bounds = new Bounds(visibleBoards, content);
        List<PlacementsHolderLocation<?>> drawnBoards = new ArrayList<>();
        for (PlacementsHolderLocation<?> boardLocation : visibleBoards) {
            drawBoard(g2, bounds, boardLocation);
            drawnBoards.add(boardLocation);
        }
        pruneHiddenSelection(drawnBoards);
    }

    static LayoutGeometry calculateLayoutGeometry(int width, int height, int columns, int rows,
            boolean showPanelSelector) {
        int toolWidth = Math.min(width - 20, 280);
        Rectangle tool = new Rectangle(10, 8, toolWidth, 26);
        Rectangle legend = new Rectangle(10, 40, toolWidth, 68);
        int trayColumnWidth = Math.min(300, Math.max(230, width / 3));
        int availableWidth = Math.max(1, width - trayColumnWidth - 34);
        int jobY = showPanelSelector ? 108 : 46;
        int availableHeight = Math.max(100, height - jobY - 14);
        int desiredWidth = BOARD_WIDTH + (Math.max(1, columns) - 1) * (BOARD_WIDTH + BOARD_GAP);
        int desiredHeight = BOARD_HEIGHT + (Math.max(1, rows) - 1) * (BOARD_HEIGHT + BOARD_GAP);
        int cardWidth = Math.min(availableWidth,
                Math.max(showPanelSelector ? 150 : 180, desiredWidth + 24));
        int cardHeight = Math.min(availableHeight,
                JOB_HEADER_HEIGHT + desiredHeight + 24);
        Rectangle jobCard = new Rectangle(width - cardWidth - 18, jobY, cardWidth, cardHeight);
        Rectangle title = new Rectangle(jobCard.x + 12, jobCard.y, jobCard.width - 24, JOB_HEADER_HEIGHT);
        Rectangle selector = showPanelSelector
                ? new Rectangle(jobCard.x + jobCard.width - 122, 8, 122, 66)
                : null;
        Rectangle panelLabel = selector == null ? null
                : new Rectangle(selector.x, selector.y + selector.height + 6, selector.width, 16);
        return new LayoutGeometry(tool, legend, jobCard, title, selector, panelLabel);
    }

    private int[] boardGridSize(List<PlacementsHolderLocation<?>> boards) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (PlacementsHolderLocation<?> board : boards) {
            xs.add(getLayoutLocation(board).getX());
            ys.add(getLayoutLocation(board).getY());
        }
        xs.sort(Comparator.naturalOrder());
        ys.sort(Comparator.naturalOrder());
        removeDuplicates(xs);
        removeDuplicates(ys);
        return new int[] { Math.max(1, xs.size()), Math.max(1, ys.size()) };
    }

    private void drawBoard(Graphics2D g2, Bounds bounds,
            PlacementsHolderLocation<?> boardLocation) {
        Rectangle boardBounds = bounds.rectangle(boardLocation);
        boolean enabled = boardLocation.isEnabled();
        boolean selected = selectedBoards.contains(boardLocation);
        g2.setColor(enabled ? BOARD_FILL : BOARD_DISABLED);
        g2.fillRoundRect(boardBounds.x, boardBounds.y, boardBounds.width, boardBounds.height, 10, 10);
        g2.setColor(enabled ? BOARD_BORDER : MUTED_TEXT);
        g2.drawRoundRect(boardBounds.x, boardBounds.y, boardBounds.width, boardBounds.height, 10, 10);
        if (selected) {
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(SELECTED);
            g2.drawRoundRect(boardBounds.x - 4, boardBounds.y - 4,
                    boardBounds.width + 8, boardBounds.height + 8, 12, 12);
            g2.setStroke(new BasicStroke(1.5f));
        }
        boardHits.add(new BoardHit(boardLocation, boardBounds));
        if (!enabled) {
            return;
        }
        for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
            drawPlacement(g2, boardBounds, bounds.scale, boardLocation, placement, true);
        }
    }

    private void drawPlacement(Graphics2D g2, Rectangle boardBounds, double displayScale,
            PlacementsHolderLocation<?> boardLocation, Placement placement, boolean boardEnabled) {
        if (!boardEnabled || !placement.isEnabled()) {
            return;
        }
        Location dimensions = boardLocation.getPlacementsHolder().getDimensions();
        Location origin = Utils2D.calculateBoardPlacementLocation(boardLocation);
        Location p = Utils2D.calculateBoardPlacementLocation(boardLocation, placement.getLocation())
                .convertToUnits(origin.getUnits());
        dimensions = dimensions.convertToUnits(origin.getUnits());
        double width = Math.max(1, Math.abs(dimensions.getX()));
        double height = Math.max(1, Math.abs(dimensions.getY()));
        double angle = Math.toRadians(origin.getRotation());
        double globalX = p.getX() - origin.getX();
        double globalY = p.getY() - origin.getY();
        double localX = globalX * Math.cos(angle) + globalY * Math.sin(angle);
        double localY = -globalX * Math.sin(angle) + globalY * Math.cos(angle);
        int x = boardBounds.x + boardBounds.width / 2
                + (int) Math.round(localX / width * boardBounds.width);
        int y = boardBounds.y + boardBounds.height / 2
                - (int) Math.round(localY / height * boardBounds.height);
        int symbolInset = Math.max(2, (int) Math.ceil(13 * displayScale));
        x = Math.max(boardBounds.x + symbolInset, Math.min(boardBounds.x + boardBounds.width - symbolInset, x));
        y = Math.max(boardBounds.y + symbolInset, Math.min(boardBounds.y + boardBounds.height - symbolInset, y));
        int fiducialRadius = Math.max(2, (int) Math.round(10 * displayScale));
        int symbolRadius = Math.max(2, (int) Math.round(13 * displayScale));
        boolean placed = job.retrievePlacedStatus(boardLocation, placement.getId());
        Color color = placed ? PLACED : (placement.getType() == Placement.Type.Fiducial ? FIDUCIAL
                : placement.getType() == Placement.Type.Dispense ? DISPENSE : PLACEMENT);
        g2.setColor(color);
        if (placement.getType() == Placement.Type.Fiducial) {
            g2.fillOval(x - fiducialRadius, y - fiducialRadius,
                    fiducialRadius * 2 + 1, fiducialRadius * 2 + 1);
        }
        else if (placement.getType() == Placement.Type.Dispense) {
            g2.setStroke(new BasicStroke(Math.max(1f, (float) (2.8 * displayScale))));
            g2.drawOval(x - symbolRadius, y - symbolRadius, symbolRadius * 2, symbolRadius * 2);
            g2.setStroke(new BasicStroke(1.5f));
        }
        else if (placement.getType() == Placement.Type.Placement) {
            g2.setStroke(new BasicStroke(Math.max(1f, (float) (3.0 * displayScale))));
            g2.drawLine(x - symbolRadius, y - symbolRadius, x + symbolRadius, y + symbolRadius);
            g2.drawLine(x + symbolRadius, y - symbolRadius, x - symbolRadius, y + symbolRadius);
            g2.setStroke(new BasicStroke(1.5f));
        }
    }


    private void drawPanelSelector(Graphics2D g2, Rectangle card, Rectangle labelBounds,
            List<PanelSlot> slots) {
        int cardWidth = card.width;
        int cardHeight = card.height;
        int x = card.x;
        int y = card.y;
        g2.setColor(new Color(40, 46, 55, 235));
        g2.fillRoundRect(x, y, cardWidth, cardHeight, 14, 14);
        g2.setColor(new Color(120, 135, 155));
        g2.drawRoundRect(x, y, cardWidth, cardHeight, 14, 14);
        int cellW = 48;
        int cellH = 24;
        int gap = 6;
        int gridX = x + 11;
        int gridY = y + 5;
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
        int labelWidth = g2.getFontMetrics().stringWidth("Panels");
        g2.drawString("Panels", labelBounds.x + (labelBounds.width - labelWidth) / 2,
                labelBounds.y + 12);
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
        clearPocketSelection();
        selectedPanelQuadrant = quadrant;
        selectedBoards.clear();
        dragRectangle = null;
        if (listener != null) {
            listener.boardSelectionChanged(getSelectedBoards());
            PanelSlot selectedSlot = getSelectedPanelSlot(getPanelSlots(job));
            if (selectedSlot != null && selectedSlot.location != null) {
                listener.panelSelectionChanged(selectedSlot.location);
            }
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
        clearPocketSelection();
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
        clearPocketSelection();
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

    private boolean isSelectedPocket(JEDEC_TrayFeeder feeder, int index) {
        return selectedPocketTarget != null && selectedPocketTarget.getFeeder() == feeder
                && selectedPocketTarget.getFeedIndexBase0() == index;
    }

    private void selectTrayPocket(TrayPocketHit hit) {
        selectedBoards.clear();
        selectedPocketTarget = CameraTarget.trayPocket(hit.feeder, hit.feedIndexBase0);
        dragRectangle = null;
        if (listener != null) {
            listener.boardSelectionChanged(getSelectedBoards());
            listener.trayPocketSelectionChanged(hit.feeder, hit.feedIndexBase0);
        }
        repaint();
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

    static final class LayoutGeometry {
        final Rectangle toolCard;
        final Rectangle legendCard;
        final Rectangle jobCard;
        final Rectangle jobTitle;
        final Rectangle panelSelectorCard;
        final Rectangle panelLabel;

        LayoutGeometry(Rectangle toolCard, Rectangle legendCard, Rectangle jobCard,
                Rectangle jobTitle, Rectangle panelSelectorCard, Rectangle panelLabel) {
            this.toolCard = toolCard;
            this.legendCard = legendCard;
            this.jobCard = jobCard;
            this.jobTitle = jobTitle;
            this.panelSelectorCard = panelSelectorCard;
            this.panelLabel = panelLabel;
        }
    }

    private static void removeDuplicates(List<Double> values) {
        for (int i = values.size() - 1; i > 0; i--) {
            if (Math.abs(values.get(i) - values.get(i - 1)) < 0.001) {
                values.remove(i);
            }
        }
    }

    private static int nearest(List<Double> values, double value) {
        int nearest = 0;
        for (int i = 1; i < values.size(); i++) {
            if (Math.abs(values.get(i) - value) < Math.abs(values.get(nearest) - value)) {
                nearest = i;
            }
        }
        return nearest;
    }

    private class Bounds {
        private final List<Double> columns = new ArrayList<>();
        private final List<Double> rows = new ArrayList<>();
        private final double left;
        private final double top;
        private final int boardWidth;
        private final int boardHeight;
        private final double horizontalStep;
        private final double verticalStep;
        private final Rectangle content;
        final double scale;

        Bounds(List<? extends PlacementsHolderLocation<?>> boards, Rectangle area) {
            content = new Rectangle(area);
            for (PlacementsHolderLocation<?> board : boards) {
                Location location = getLayoutLocation(board);
                columns.add(location.getX());
                rows.add(location.getY());
            }
            columns.sort(Comparator.naturalOrder());
            rows.sort(Comparator.reverseOrder());
            removeDuplicates(columns);
            removeDuplicates(rows);
            double preferredWidth = BOARD_WIDTH + (columns.size() - 1) * (BOARD_WIDTH + BOARD_GAP);
            double preferredHeight = BOARD_HEIGHT + (rows.size() - 1) * (BOARD_HEIGHT + BOARD_GAP);
            scale = Math.min(1.0, Math.min(area.width / preferredWidth, area.height / preferredHeight));
            boardWidth = Math.max(1, (int) Math.floor(BOARD_WIDTH * scale));
            boardHeight = Math.max(1, (int) Math.floor(BOARD_HEIGHT * scale));
            horizontalStep = (BOARD_WIDTH + BOARD_GAP) * scale;
            verticalStep = (BOARD_HEIGHT + BOARD_GAP) * scale;
            double width = boardWidth + (columns.size() - 1) * horizontalStep;
            double height = boardHeight + (rows.size() - 1) * verticalStep;
            left = area.x + (area.width - width) / 2;
            top = area.y + (area.height - height) / 2;
        }

        Rectangle rectangle(PlacementsHolderLocation<?> board) {
            Location location = getLayoutLocation(board);
            int x = (int) Math.round(left + nearest(columns, location.getX()) * horizontalStep);
            int y = (int) Math.round(top + nearest(rows, location.getY()) * verticalStep);
            x = Math.max(content.x, Math.min(content.x + content.width - boardWidth, x));
            y = Math.max(content.y, Math.min(content.y + content.height - boardHeight, y));
            return new Rectangle(x, y, boardWidth, boardHeight);
        }

    }
}
