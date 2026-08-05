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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JPanel;
import javax.swing.UIManager;

import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Location;
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
    }

    private static final Color BACKGROUND = new Color(250, 250, 250);
    private static final Color SECTION = new Color(245, 247, 250);
    private static final Color BORDER = new Color(190, 198, 208);
    private static final Color TRAY_AVAILABLE = new Color(104, 171, 224);
    private static final Color TRAY_USED = new Color(178, 182, 188);
    private static final Color BOARD_FILL = new Color(232, 238, 248);
    private static final Color BOARD_DISABLED = new Color(220, 220, 220);
    private static final Color BOARD_BORDER = new Color(67, 92, 135);
    private static final Color SELECTED = new Color(38, 120, 210);
    private static final Color PLACEMENT = new Color(174, 54, 54);
    private static final Color PLACED = new Color(210, 158, 24);
    private static final Color FIDUCIAL = new Color(44, 150, 82);
    private static final Color DISPENSE = new Color(210, 132, 34);

    private Job job;
    private HeadMountable selectedTool;
    private Listener listener;
    private EditMode editMode = EditMode.NONE;
    private boolean editingAllowed;
    private final Set<PlacementsHolderLocation<?>> selectedBoards = new LinkedHashSet<>();
    private final List<BoardHit> boardHits = new ArrayList<>();
    private final List<TrayPocketHit> trayPocketHits = new ArrayList<>();
    private final List<TrayActionHit> trayActionHits = new ArrayList<>();
    private Rectangle dragRectangle;
    private int dragStartX;
    private int dragStartY;

    public OperatorRuntimeCanvas() {
        setPreferredSize(new Dimension(760, 520));
        Color lafBackground = UIManager.getColor("Panel.background");
        setBackground(lafBackground == null ? BACKGROUND : lafBackground);
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
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
                if (editMode == EditMode.NONE || !editingAllowed) {
                    return;
                }
                if (dragRectangle != null && dragRectangle.width > 4 && dragRectangle.height > 4) {
                    selectBoardsIn(dragRectangle, isMenuShortcutDown(e));
                    dragRectangle = null;
                    repaint();
                    return;
                }
                BoardHit hit = findBoard(e.getX(), e.getY());
                if (hit != null) {
                    selectBoard(hit.boardLocation, isMenuShortcutDown(e));
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
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.5f));
            drawTool(g2);
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
        g2.fillRoundRect(10, 8, Math.min(getWidth() - 20, 320), 26, 10, 10);
        g2.setColor(BORDER);
        g2.drawRoundRect(10, 8, Math.min(getWidth() - 20, 320), 26, 10, 10);
        g2.setColor(FIDUCIAL.darker());
        g2.drawString(text, 22, 26);
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
            if (y < 42) {
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
        return tray.getEffectiveTrayCountRows() * cell + 58;
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
        int cardWidth = cols * cell + 170;
        int cardHeight = rows * cell + 58;
        boolean enabled = tray.isEnabled();
        g2.setColor(enabled ? SECTION : new Color(90, 72, 56, 80));
        g2.fillRoundRect(x - 6, y - 4, cardWidth, cardHeight, 12, 12);
        g2.setColor(enabled ? BORDER : new Color(190, 110, 60));
        g2.drawRoundRect(x - 6, y - 4, cardWidth, cardHeight, 12, 12);
        g2.setColor(enabled ? Color.DARK_GRAY : new Color(190, 80, 40));
        String status = enabled ? "Position " + (nextIndex + 1) + " • Remaining " + Math.max(0, capacity - feedCount) : "Disabled";
        g2.drawString(tray.getName(), x, y + 12);
        g2.setColor(enabled ? new Color(80, 110, 140) : new Color(190, 80, 40));
        g2.drawString(status, x, y + 28);
        for (int index = 0; index < capacity; index++) {
            JEDEC_TrayFeeder.GridIndex grid = JEDEC_TrayFeeder.getGridIndexForFeed(index, rows, cols,
                    tray.getStartCorner(), tray.getFirstRasterDirection(), tray.getRasterPattern());
            int px = x + grid.col * cell;
            int py = y + 36 + (rows - 1 - grid.row) * cell;
            g2.setColor(!enabled ? BOARD_DISABLED : index < feedCount ? TRAY_USED : TRAY_AVAILABLE);
            g2.fillRoundRect(px, py, cell - 2, cell - 2, 3, 3);
            g2.setColor(new Color(255, 255, 255, 150));
            g2.drawRoundRect(px, py, cell - 2, cell - 2, 3, 3);
            if (enabled && index == nextIndex) {
                g2.setStroke(new BasicStroke(2.4f));
                g2.setColor(PLACED);
                g2.drawRoundRect(px - 2, py - 2, cell + 2, cell + 2, 6, 6);
                g2.setStroke(new BasicStroke(1.5f));
            }
            trayPocketHits.add(new TrayPocketHit(tray, index, index + 1,
                    new Rectangle(px, py, cell - 2, cell - 2)));
        }
        if (enabled) {
            g2.setColor(PLACED.darker());
            g2.drawString("Next Pick", x + cols * cell + 18, y + 28);
        }
        int buttonX = x + cols * cell + 14;
        Rectangle reset = new Rectangle(buttonX, y + 42, 120, 24);
        Rectangle enable = new Rectangle(buttonX, y + 72, 120, 24);
        drawButton(g2, reset, "Reset Tray", enabled);
        drawButton(g2, enable, enabled ? "Enabled" : "Enable Tray", enabled);
        trayActionHits.add(new TrayActionHit(tray, reset, true));
        trayActionHits.add(new TrayActionHit(tray, enable, false));
        return y + cardHeight + 4;
    }

    private int drawReferenceTray(Graphics2D g2, ReferenceTrayFeeder tray, int x, int y) {
        int rows = tray.getEffectiveTrayCountY();
        int cols = tray.getEffectiveTrayCountX();
        int cell = Math.max(12, Math.min(20, 220 / Math.max(rows, cols)));
        g2.setColor(Color.GRAY);
        g2.drawString(tray.getName() + "  (view only)", x, y + 10);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = row * cols + col;
                g2.setColor(index < tray.getFeedCount() ? TRAY_USED : new Color(175, 205, 225));
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
            g2.setColor(Color.GRAY);
            g2.drawString("No job loaded", getWidth() / 2 - 42, getHeight() / 3 + 38);
            return;
        }
        List<Location> locations = new ArrayList<>();
        for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
            locations.add(boardLocation.getGlobalLocation());
            for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                locations.add(boardLocation.getGlobalLocation().add(placement.getLocation()));
            }
        }
        if (locations.isEmpty()) {
            return;
        }
        Bounds bounds = new Bounds(locations);
        int trayColumnWidth = Math.min(300, Math.max(230, getWidth() / 3));
        Rectangle area = new Rectangle(trayColumnWidth + 16, 46, getWidth() - trayColumnWidth - 34, getHeight() - 60);
        g2.setColor(Color.DARK_GRAY);
        g2.drawString("Job Layout", area.x, area.y - 10);
        g2.setColor(SECTION);
        g2.fillRoundRect(area.x, area.y, area.width, area.height, 14, 14);
        g2.setColor(BORDER);
        g2.drawRoundRect(area.x, area.y, area.width, area.height, 14, 14);
        for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
            drawBoard(g2, bounds, area, boardLocation);
        }
    }

    private void drawBoard(Graphics2D g2, Bounds bounds, Rectangle area, PlacementsHolderLocation<?> boardLocation) {
        Location bl = boardLocation.getGlobalLocation();
        int bx = bounds.x(bl, area);
        int by = bounds.y(bl, area);
        Rectangle boardBounds = new Rectangle(bx - 40, by - 28, 80, 56);
        boolean enabled = boardLocation.isEnabled();
        boolean selected = selectedBoards.contains(boardLocation);
        g2.setColor(enabled ? BOARD_FILL : BOARD_DISABLED);
        g2.fillRoundRect(boardBounds.x, boardBounds.y, boardBounds.width, boardBounds.height, 10, 10);
        g2.setColor(enabled ? BOARD_BORDER : Color.GRAY);
        g2.drawRoundRect(boardBounds.x, boardBounds.y, boardBounds.width, boardBounds.height, 10, 10);
        if (selected) {
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(SELECTED);
            g2.drawRoundRect(boardBounds.x - 4, boardBounds.y - 4, boardBounds.width + 8, boardBounds.height + 8, 12, 12);
            g2.setStroke(new BasicStroke(1.5f));
        }
        boardHits.add(new BoardHit(boardLocation, boardBounds));
        for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
            drawPlacement(g2, bounds, area, boardLocation, placement, enabled);
        }
    }

    private void drawPlacement(Graphics2D g2, Bounds bounds, Rectangle area, PlacementsHolderLocation<?> boardLocation,
            Placement placement, boolean boardEnabled) {
        Location p = boardLocation.getGlobalLocation().add(placement.getLocation());
        int x = bounds.x(p, area);
        int y = bounds.y(p, area);
        boolean placed = job.retrievePlacedStatus(boardLocation, placement.getId());
        boolean placementEnabled = placement.isEnabled();
        Color color = placed ? PLACED : (placement.getType() == Placement.Type.Fiducial ? FIDUCIAL
                : placement.getType() == Placement.Type.Dispense ? DISPENSE : PLACEMENT);
        if (!boardEnabled || !placementEnabled) {
            color = !placementEnabled ? new Color(210, 95, 30) : Color.GRAY;
        }
        g2.setColor(color);
        if (placement.getType() == Placement.Type.Fiducial) {
            g2.fillOval(x - 6, y - 6, 12, 12);
        }
        else if (placement.getType() == Placement.Type.Dispense) {
            g2.drawOval(x - 7, y - 7, 14, 14);
        }
        else if (placement.getType() == Placement.Type.Placement) {
            g2.drawLine(x - 7, y - 7, x + 7, y + 7);
            g2.drawLine(x + 7, y - 7, x - 7, y + 7);
        }
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

    private static class BoardHit {
        final PlacementsHolderLocation<?> boardLocation;
        final Rectangle bounds;
        BoardHit(PlacementsHolderLocation<?> boardLocation, Rectangle bounds) {
            this.boardLocation = boardLocation;
            this.bounds = bounds;
        }
    }

    private void drawButton(Graphics2D g2, Rectangle bounds, String text, boolean enabled) {
        g2.setColor(enabled ? new Color(235, 239, 244) : new Color(250, 232, 220));
        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8);
        g2.setColor(enabled ? BORDER : new Color(190, 110, 60));
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8);
        g2.setColor(enabled ? Color.DARK_GRAY : new Color(150, 60, 30));
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
            return r.x + 24 + (int) ((l.getX() - minX) / Math.max(1, maxX - minX) * (r.width - 48));
        }
        int y(Location l, Rectangle r) {
            return r.y + r.height - 24 - (int) ((l.getY() - minY) / Math.max(1, maxY - minY) * (r.height - 48));
        }
    }
}
