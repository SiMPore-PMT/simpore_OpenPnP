package org.openpnp.gui.operator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

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
    private static final Color BACKGROUND = new Color(250, 250, 250);
    private static final Color SECTION = new Color(245, 247, 250);
    private static final Color BORDER = new Color(190, 198, 208);
    private static final Color TRAY_AVAILABLE = new Color(104, 171, 224);
    private static final Color TRAY_USED = new Color(178, 182, 188);
    private static final Color BOARD_FILL = new Color(232, 238, 248);
    private static final Color BOARD_BORDER = new Color(67, 92, 135);
    private static final Color PLACEMENT = new Color(174, 54, 54);
    private static final Color FIDUCIAL = new Color(44, 150, 82);
    private static final Color DISPENSE = new Color(210, 132, 34);

    private Job job;
    private HeadMountable selectedTool;

    public OperatorRuntimeCanvas() {
        setPreferredSize(new Dimension(500, 360));
        setBackground(BACKGROUND);
    }

    public void setJob(Job job) {
        this.job = job;
        repaint();
    }

    public void setSelectedTool(HeadMountable selectedTool) {
        this.selectedTool = selectedTool;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.5f));
            drawTool(g2);
            drawFeeders(g2);
            drawJob(g2);
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
        g2.fillRoundRect(10, 8, Math.min(getWidth() - 20, 300), 26, 10, 10);
        g2.setColor(BORDER);
        g2.drawRoundRect(10, 8, Math.min(getWidth() - 20, 300), 26, 10, 10);
        g2.setColor(FIDUCIAL.darker());
        g2.drawString(text, 22, 26);
    }

    private void drawFeeders(Graphics2D g2) {
        if (Configuration.get().getMachine() == null) {
            return;
        }
        int x = 12;
        int y = 50;
        g2.setColor(Color.DARK_GRAY);
        g2.drawString("Tray Feeders", x, y - 10);
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            int rows = 0;
            int cols = 0;
            int feedCount = 0;
            if (feeder instanceof JEDEC_TrayFeeder) {
                JEDEC_TrayFeeder tray = (JEDEC_TrayFeeder) feeder;
                rows = tray.getEffectiveTrayCountRows();
                cols = tray.getEffectiveTrayCountCols();
                feedCount = tray.getFeedCount();
            }
            else if (feeder instanceof ReferenceTrayFeeder) {
                ReferenceTrayFeeder tray = (ReferenceTrayFeeder) feeder;
                rows = tray.getEffectiveTrayCountY();
                cols = tray.getEffectiveTrayCountX();
                feedCount = tray.getFeedCount();
            }
            if (rows <= 0 || cols <= 0) {
                continue;
            }
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(feeder.getName(), x, y + 10);
            int cell = Math.max(5, Math.min(14, 160 / Math.max(rows, cols)));
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    int index = row * cols + col;
                    g2.setColor(index < feedCount ? TRAY_USED : TRAY_AVAILABLE);
                    g2.fillRoundRect(x + col * cell, y + 18 + row * cell, cell - 2, cell - 2, 3, 3);
                    g2.setColor(new Color(255, 255, 255, 120));
                    g2.drawRoundRect(x + col * cell, y + 18 + row * cell, cell - 2, cell - 2, 3, 3);
                }
            }
            y += 30 + rows * cell;
            if (y > getHeight() / 2) {
                break;
            }
        }
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
        Rectangle area = new Rectangle(getWidth() / 3, 50, getWidth() * 2 / 3 - 20, getHeight() - 74);
        g2.setColor(Color.DARK_GRAY);
        g2.drawString("Job Layout", area.x, area.y - 10);
        g2.setColor(SECTION);
        g2.fillRoundRect(area.x, area.y, area.width, area.height, 14, 14);
        g2.setColor(BORDER);
        g2.drawRoundRect(area.x, area.y, area.width, area.height, 14, 14);
        for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
            Location bl = boardLocation.getGlobalLocation();
            int bx = bounds.x(bl, area);
            int by = bounds.y(bl, area);
            g2.setColor(BOARD_FILL);
            g2.fillRoundRect(bx - 20, by - 14, 40, 28, 10, 10);
            g2.setColor(BOARD_BORDER);
            g2.drawRoundRect(bx - 20, by - 14, 40, 28, 10, 10);
            for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                Location p = bl.add(placement.getLocation());
                int x = bounds.x(p, area);
                int y = bounds.y(p, area);
                if (placement.getType() == Placement.Type.Fiducial) {
                    g2.setColor(FIDUCIAL);
                    g2.fillOval(x - 3, y - 3, 6, 6);
                }
                else if (placement.getType() == Placement.Type.Dispense) {
                    g2.setColor(DISPENSE);
                    g2.drawOval(x - 4, y - 4, 8, 8);
                }
                else if (placement.getType() == Placement.Type.Placement) {
                    g2.setColor(PLACEMENT);
                    g2.drawLine(x - 4, y - 4, x + 4, y + 4);
                    g2.drawLine(x + 4, y - 4, x - 4, y + 4);
                }
            }
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
            return r.y + 24 + (int) ((l.getY() - minY) / Math.max(1, maxY - minY) * (r.height - 48));
        }
    }
}
