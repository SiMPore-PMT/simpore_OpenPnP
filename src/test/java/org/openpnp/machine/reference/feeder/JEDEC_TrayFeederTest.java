package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.FirstRasterDirection;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.GridIndex;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.RasterPattern;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.StartCorner;

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
        assertEquals(90, JEDEC_TrayFeeder.normalizeComponentRotationInTray(88), 0.0);
        assertEquals(180, JEDEC_TrayFeeder.normalizeComponentRotationInTray(181), 0.0);
        assertEquals(270, JEDEC_TrayFeeder.normalizeComponentRotationInTray(269), 0.0);
    }

    @Test
    public void pickRotationDefaultsAreSafe() {
        assertEquals(17, JEDEC_TrayFeeder.calculatePickRotation(false, false, 17, 107, 90, 33), 0.0);
        assertEquals(107, JEDEC_TrayFeeder.calculatePickRotation(true, false, 17, 17, 90, 33), 0.0);
        assertEquals(-50, JEDEC_TrayFeeder.calculatePickRotation(false, true, 17, 17, 90, 33), 0.0);
    }

    private static void assertGrid(int feedIndex, int row, int col, StartCorner startCorner,
            FirstRasterDirection firstRasterDirection, RasterPattern rasterPattern) {
        GridIndex index = JEDEC_TrayFeeder.getGridIndexForFeed(feedIndex, 3, 4,
                startCorner, firstRasterDirection, rasterPattern);
        assertEquals(row, index.row);
        assertEquals(col, index.col);
    }
}
