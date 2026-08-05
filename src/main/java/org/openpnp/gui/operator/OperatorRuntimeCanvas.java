package org.openpnp.gui.operator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
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
    private Job job;
    private HeadMountable selectedTool;

    public OperatorRuntimeCanvas() {
        setPreferredSize(new Dimension(500, 360));
        setBackground(Color.WHITE);
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
        g2.setColor(new Color(0, 110, 0));
        g2.drawString(text, 12, 18);
    }

    private void drawFeeders(Graphics2D g2) {
        int x = 12;
        int y = 32;
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
                    g2.setColor(index < feedCount ? new Color(180, 180, 180) : new Color(120, 190, 255));
                    g2.fillRect(x + col * cell, y + 18 + row * cell, cell - 1, cell - 1);
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
            g2.setColor(Color.GRAY);
            g2.drawString("No job loaded", getWidth() / 2 - 40, getHeight() / 2);
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
        Rectangle area = new Rectangle(getWidth() / 3, 35, getWidth() * 2 / 3 - 16, getHeight() - 50);
        g2.setColor(new Color(0, 140, 0));
        g2.drawRect(area.x, area.y, area.width, area.height);
        for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
            Location bl = boardLocation.getGlobalLocation();
            int bx = bounds.x(bl, area);
            int by = bounds.y(bl, area);
            g2.setColor(new Color(230, 230, 250));
            g2.fillRoundRect(bx - 18, by - 12, 36, 24, 10, 10);
            g2.setColor(Color.BLUE.darker());
            g2.drawRoundRect(bx - 18, by - 12, 36, 24, 10, 10);
            for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                Location p = bl.add(placement.getLocation());
                int x = bounds.x(p, area);
                int y = bounds.y(p, area);
                if (placement.getType() == Placement.Type.Fiducial) {
                    g2.setColor(new Color(0, 160, 0));
                    g2.fillOval(x - 3, y - 3, 6, 6);
                }
                else if (placement.getType() == Placement.Type.Dispense) {
                    g2.setColor(Color.ORANGE.darker());
                    g2.drawOval(x - 4, y - 4, 8, 8);
                }
                else if (placement.getType() == Placement.Type.Placement) {
                    g2.setColor(Color.RED.darker());
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
        int x(Location l, Rectangle r) { return r.x + 20 + (int) ((l.getX() - minX) / Math.max(1, maxX - minX) * (r.width - 40)); }
        int y(Location l, Rectangle r) { return r.y + 20 + (int) ((l.getY() - minY) / Math.max(1, maxY - minY) * (r.height - 40)); }
    }
}
