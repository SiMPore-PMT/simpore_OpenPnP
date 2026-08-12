package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.FirstRasterDirection;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.GridIndex;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.RasterPattern;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.StartCorner;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.VisionUtils;

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
    public void pickRotationOptionsFollowPrecedenceAndSignPolicy() {
        assertEquals(-1.5, JEDEC_TrayFeeder.calculatePickRotation(true, true,
                10, 90, 1.5), 0.0001);
        assertEquals(100, JEDEC_TrayFeeder.calculatePickRotation(true, false,
                10, 90, 1.5), 0.0001);
        assertEquals(10, JEDEC_TrayFeeder.calculatePickRotation(false, false,
                10, 90, 1.5), 0.0001);
        assertEquals(-1.5, JEDEC_TrayFeeder.calculatePickRotation(false, true,
                47, 270, 1.5), 0.0001);
    }

    @Test
    public void visionTargetsAlwaysResetInheritedRotation() {
        Location previousCameraLocation =
                new Location(LengthUnit.Millimeters, 10, 20, 5, 12.103);
        Location detected = new Location(LengthUnit.Millimeters, 11, 21, 5, 12.103);
        assertEquals(0, JEDEC_TrayFeeder.createVisionTarget(previousCameraLocation, 0)
                .getRotation(), 0.0);
        assertEquals(0, JEDEC_TrayFeeder.createRecenterTarget(
                previousCameraLocation, detected, 0).getRotation(), 0.0);
        Location anotherPreviousLocation = previousCameraLocation.derive(null, null, null, -33.7);
        assertEquals(0, JEDEC_TrayFeeder.createVisionTarget(anotherPreviousLocation, 0)
                .getRotation(), 0.0);
    }

    @Test
    public void cameraRecenterAndNozzlePickUseSeparateCoordinateSpaces() {
        Location cameraLocation = new Location(LengthUnit.Millimeters, 100, 200, 10, 0);
        Location unitsPerPixel = new Location(LengthUnit.Millimeters, 1, 1, 0, 0);
        Nozzle nozzle = proxy(Nozzle.class, null, null, null);
        Camera camera = proxy(Camera.class, cameraLocation, cameraLocation, unitsPerPixel);
        JEDEC_TrayFeeder feeder = new JEDEC_TrayFeeder();

        Location cameraPartLocation = VisionUtils.getPixelLocation(camera, 330, 220);
        Location nozzlePickLocation = VisionUtils.getPixelLocation(camera, nozzle, 330, 220);
        Location recenterOffset = JEDEC_TrayFeeder.getCameraRecenterOffset(camera, cameraPartLocation);

        assertEquals(110, cameraPartLocation.getX(), 0.0001);
        assertEquals(220, cameraPartLocation.getY(), 0.0001);
        assertEquals(10, recenterOffset.getX(), 0.0001);
        assertEquals(20, recenterOffset.getY(), 0.0001);
        assertEquals(cameraPartLocation, nozzlePickLocation);
    }

    @Test
    public void pickRotationDoesNotChangeNozzleAwareDetectedXy() {
        Location cameraLocation = new Location(LengthUnit.Millimeters, 100, 200, 10, 0);
        Location unitsPerPixel = new Location(LengthUnit.Millimeters, 1, 1, 0, 0);
        Nozzle nozzle = proxy(Nozzle.class, null, null, null);
        Camera camera = proxy(Camera.class, cameraLocation, cameraLocation, unitsPerPixel);
        JEDEC_TrayFeeder feeder = new JEDEC_TrayFeeder();

        Location detected = VisionUtils.getPixelLocation(camera, nozzle, 330, 220);
        Location withoutDetectedAngle = detected.derive(null, null, 1.5, 0.0);
        Location withDetectedAngle = detected.derive(null, null, 1.5,
                JEDEC_TrayFeeder.calculatePickRotation(true, true, 10, 90, 1.5));

        assertEquals(withoutDetectedAngle.getX(), withDetectedAngle.getX(), 0.0);
        assertEquals(withoutDetectedAngle.getY(), withDetectedAngle.getY(), 0.0);
        assertEquals(110, withDetectedAngle.getX(), 0.0001);
        assertEquals(220, withDetectedAngle.getY(), 0.0001);
    }

    @Test
    public void finalPickUsesTrayZAndStandardNozzleSemanticsAddPartHeightOnce() {
        JEDEC_TrayFeeder feeder = new JEDEC_TrayFeeder();
        Location trayLocation = new Location(LengthUnit.Millimeters, 0, 0, 1.25, 0);
        Location detected = new Location(LengthUnit.Millimeters, 10, 20, 99, 0);
        Part part = new Part("test-part");
        part.setHeight(new Length(0.6, LengthUnit.Millimeters));
        feeder.setLocation(trayLocation);
        feeder.setPart(part);

        Location pickLocation = JEDEC_TrayFeeder.deriveFinalNozzlePickLocation(
                detected, trayLocation, 17);
        ReferenceNozzle nozzle = new ReferenceNozzle();
        Location standardNozzleTarget = pickLocation.add(new Location(
                nozzle.getSafePartHeight(part).getUnits(), 0, 0,
                nozzle.getSafePartHeight(part).getValue(), 0));

        assertEquals(1.25, pickLocation.getZ(), 0.0001);
        assertEquals(17, pickLocation.getRotation(), 0.0001);
        assertEquals(true, feeder.isPartHeightAbovePickLocation());
        assertEquals(1.85, standardNozzleTarget.getZ(), 0.0001);
    }

    @Test
    public void visionPlaneIncludesKnownPartHeightButHandlesMissingPart() {
        JEDEC_TrayFeeder feeder = new JEDEC_TrayFeeder();
        feeder.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 1.25, 0));

        assertEquals(1.25, feeder.getVisionViewingPlaneZ()
                .convertToUnits(LengthUnit.Millimeters).getValue(), 0.0001);

        Part part = new Part("test-part");
        part.setHeight(new Length(0.6, LengthUnit.Millimeters));
        feeder.setPart(part);
        assertEquals(1.85, feeder.getVisionViewingPlaneZ()
                .convertToUnits(LengthUnit.Millimeters).getValue(), 0.0001);

        part.setHeight(new Length(0, LengthUnit.Millimeters));
        assertEquals(1.25, feeder.getVisionViewingPlaneZ()
                .convertToUnits(LengthUnit.Millimeters).getValue(), 0.0001);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Location cameraLocation,
            Location calibratedCameraLocation, Location unitsPerPixel) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation":
                            return args != null && args.length == 1
                                    ? calibratedCameraLocation : cameraLocation;
                        case "getUnitsPerPixelAtZ":
                        case "getUnitsPerPixel":
                            return unitsPerPixel;
                        case "getWidth":
                            return 640;
                        case "getHeight":
                            return 480;
                        default:
                            Class<?> returnType = method.getReturnType();
                            if (returnType == boolean.class) return false;
                            if (returnType == int.class) return 0;
                            if (returnType == double.class) return 0.0;
                            return null;
                    }
                });
    }

    private static void assertGrid(int feedIndex, int row, int col, StartCorner startCorner,
            FirstRasterDirection firstRasterDirection, RasterPattern rasterPattern) {
        GridIndex index = JEDEC_TrayFeeder.getGridIndexForFeed(feedIndex, 3, 4,
                startCorner, firstRasterDirection, rasterPattern);
        assertEquals(row, index.row);
        assertEquals(col, index.col);
    }
}
