package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.FirstRasterDirection;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.GridIndex;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.RasterPattern;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.StartCorner;
import org.openpnp.util.Utils2D;

public class JEDEC_TrayFeederTest {
    @Test
    public void bottomLeftRowZigZagRaster() {
        assertGrid(0, 2, 0, StartCorner.BOTTOM_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        assertGrid(1, 2, 1, StartCorner.BOTTOM_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        assertGrid(3, 2, 3, StartCorner.BOTTOM_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        assertGrid(4, 1, 0, StartCorner.BOTTOM_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
    }

    @Test
    public void bottomLeftRowSnakeRaster() {
        assertGrid(4, 1, 3, StartCorner.BOTTOM_LEFT, FirstRasterDirection.ROW, RasterPattern.SNAKE);
        assertGrid(5, 1, 2, StartCorner.BOTTOM_LEFT, FirstRasterDirection.ROW, RasterPattern.SNAKE);
    }

    @Test
    public void bottomLeftColumnRaster() {
        assertGrid(0, 2, 0, StartCorner.BOTTOM_LEFT, FirstRasterDirection.COLUMN, RasterPattern.ZIG_ZAG);
        assertGrid(1, 1, 0, StartCorner.BOTTOM_LEFT, FirstRasterDirection.COLUMN, RasterPattern.ZIG_ZAG);
        assertGrid(3, 2, 1, StartCorner.BOTTOM_LEFT, FirstRasterDirection.COLUMN, RasterPattern.ZIG_ZAG);
        assertGrid(3, 0, 1, StartCorner.BOTTOM_LEFT, FirstRasterDirection.COLUMN, RasterPattern.SNAKE);
    }

    @Test
    public void allCornersRasterSigns() {
        assertGrid(0, 0, 0, StartCorner.TOP_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        assertGrid(1, 0, 1, StartCorner.TOP_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        assertGrid(4, 1, 0, StartCorner.TOP_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);

        assertGrid(0, 0, 3, StartCorner.TOP_RIGHT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        assertGrid(1, 0, 2, StartCorner.TOP_RIGHT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        assertGrid(4, 1, 3, StartCorner.TOP_RIGHT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);

        assertGrid(0, 2, 3, StartCorner.BOTTOM_RIGHT, FirstRasterDirection.COLUMN, RasterPattern.ZIG_ZAG);
        assertGrid(1, 1, 3, StartCorner.BOTTOM_RIGHT, FirstRasterDirection.COLUMN, RasterPattern.ZIG_ZAG);
        assertGrid(3, 2, 2, StartCorner.BOTTOM_RIGHT, FirstRasterDirection.COLUMN, RasterPattern.ZIG_ZAG);
    }

    @Test
    public void rasterBoundsClamp() {
        assertGrid(-1, 2, 0, StartCorner.BOTTOM_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        assertGrid(99, 0, 3, StartCorner.BOTTOM_LEFT, FirstRasterDirection.ROW, RasterPattern.ZIG_ZAG);
        GridIndex index = JEDEC_TrayFeeder.getGridIndexForFeed(0, 0, 0, null, null, null);
        assertEquals(0, index.row);
        assertEquals(0, index.col);
    }

    @Test
    public void componentRotationIsNormalizedToQuadrants() {
        assertEquals(0, JEDEC_TrayFeeder.normalizeComponentRotationInTray(2), 0.0);
        assertEquals(90, JEDEC_TrayFeeder.normalizeComponentRotationInTray(88), 0.0);
        assertEquals(180, JEDEC_TrayFeeder.normalizeComponentRotationInTray(181), 0.0);
        assertEquals(270, JEDEC_TrayFeeder.normalizeComponentRotationInTray(269), 0.0);
    }

    @Test
    public void trayVisionPickRotationAlwaysUsesDetectedAngle() {
        assertEquals(Utils2D.angleNorm(-(1.5 + 0), 180),
                JEDEC_TrayFeeder.calculateTrayVisionPickRotation(0, 1.5), 0.0001);
        assertEquals(Utils2D.angleNorm(-(-1.1915 + 0.409557), 180),
                JEDEC_TrayFeeder.calculateTrayVisionPickRotation(0.409557, -1.1915), 0.0001);
        assertEquals(Utils2D.angleNorm(-(0 + 10), 180),
                JEDEC_TrayFeeder.calculateTrayVisionPickRotation(10, Double.NaN), 0.0001);
    }

    @Test
    public void fullPickRotationIncludesComponentRotation() {
        double trayRotation = 0.409557;
        double detectedAngle = -1.1915;
        double base = Utils2D.angleNorm(-(detectedAngle + trayRotation), 180);

        assertEquals(base,
                JEDEC_TrayFeeder.calculatePickRotation(trayRotation, detectedAngle, 0), 0.0001);
        assertEquals(Utils2D.angleNorm(base + 90, 180),
                JEDEC_TrayFeeder.calculatePickRotation(trayRotation, detectedAngle, 90), 0.0001);
        assertEquals(Utils2D.angleNorm(base + 180, 180),
                JEDEC_TrayFeeder.calculatePickRotation(trayRotation, detectedAngle, 180), 0.0001);
        assertEquals(Utils2D.angleNorm(base + 270, 180),
                JEDEC_TrayFeeder.calculatePickRotation(trayRotation, detectedAngle, 270), 0.0001);
    }

    @Test
    public void feederDoesNotOverridePostPick() {
        long postPickOverrides = Arrays.stream(JEDEC_TrayFeeder.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("postPick"))
                .count();
        assertEquals(0, postPickOverrides);
    }

    private static void assertGrid(int feedIndex, int row, int col, StartCorner startCorner,
            FirstRasterDirection firstRasterDirection, RasterPattern rasterPattern) {
        GridIndex index = JEDEC_TrayFeeder.getGridIndexForFeed(feedIndex, 3, 4,
                startCorner, firstRasterDirection, rasterPattern);
        assertEquals(row, index.row);
        assertEquals(col, index.col);
    }
}
