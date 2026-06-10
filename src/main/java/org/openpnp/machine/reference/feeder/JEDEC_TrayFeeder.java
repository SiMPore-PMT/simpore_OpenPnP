package org.openpnp.machine.reference.feeder;

import javax.swing.Action;

import org.apache.commons.io.IOUtils;
import org.opencv.core.RotatedRect;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.feeder.wizards.AdvancedLoosePartFeederConfigurationWizard;
import org.openpnp.machine.reference.feeder.wizards.JEDEC_TrayFeederConfigurationWizard;
import org.openpnp.model.Configuration;
import org.openpnp.model.FiducialVisionSettings;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.Transient;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.core.Commit;

import java.util.List;

/**
 * Based off ReferenceRotatedTrayFeeder
 *
 * Start of JEDEC tray implementation for parts. (FUTURE can use actual JEDEC nomenclature,
 * for now just x, y grid motion).
 *
 * Combines ReferenceRotatedTrayFeeder structure and imaging pipeline of AdvancedLoosePartFeeder.
 * Allows for part to be posed, and tip to be adjusted before initial pick from tray
 */
@Root(strict = false)
public class JEDEC_TrayFeeder extends ReferenceFeeder {
    public static final double DEFAULT_RECENTER_TOLERANCE_MM = 0.02;
    public static final int DEFAULT_RECENTER_MAX_PASSES = 3;

    /**
     * New -> From advancedLoosePartFeeder
     */
    @Element(required = false)
    private CvPipeline pipeline = createDefaultPipeline();

    @Transient
    private String fiducialVisionSettingsId;

    @Attribute(required = false)
    private boolean useAdvancedCameraCalibration = false;

    @Attribute(required = false)
    private boolean useAsyncGcodeMotion = false;

    @Attribute(required = false)
    private double recenterToleranceMm = DEFAULT_RECENTER_TOLERANCE_MM;

    @Attribute(required = false)
    private int recenterMaxPasses = DEFAULT_RECENTER_MAX_PASSES;

    @Attribute(required = false)
    private boolean useDetectedAngleForPickRotation = false;

    @Attribute(required = false)
    private boolean rotateNozzleAtPick = false;

    @Attribute(required = false)
    private StartCorner startCorner = StartCorner.BOTTOM_LEFT;

    @Attribute(required = false)
    private FirstRasterDirection firstRasterDirection = FirstRasterDirection.ROW;

    @Attribute(required = false)
    private RasterPattern rasterPattern = RasterPattern.ZIG_ZAG;

    /**
     * Canonical physical grid origin: row 0, column 0 is the top-left tray pocket.
     * columnVector and rowVector are already in machine coordinates and must not be
     * rotated again by location.R.
     */
    @Element(required = false)
    private Location gridOrigin = new Location(LengthUnit.Millimeters);

    @Element(required = false)
    private Location columnVector = new Location(LengthUnit.Millimeters);

    @Element(required = false)
    private Location rowVector = new Location(LengthUnit.Millimeters);

    @Transient
    private double lastDetectedAngle = Double.NaN;

    public enum StartCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public enum FirstRasterDirection {
        ROW,
        COLUMN
    }

    public enum RasterPattern {
        ZIG_ZAG,
        SNAKE
    }

    public static class GridIndex {
        public final int row;
        public final int col;

        GridIndex(int row, int col) {
            this.row = row;
            this.col = col;
        }
    }

    private Location pickLocation;
    private Location lastLocation;
    @Override
    public Location getPickLocation() throws Exception {
        return pickLocation == null ? location : pickLocation;
    }

    @Attribute
    private int trayCountCols = 1;
    @Attribute
    private int trayCountRows = 1;
    @Element(required = false)
    private Location offsets = new Location(LengthUnit.Millimeters);
    @Attribute
    private int feedCount = 0;  // UI is base 1, 0 is ok because a pick operation always preceded by a feed, which increments feedCount to 1

    @Attribute(required=false)
    @Deprecated
    private Double trayRotation = null;

    @Attribute(required=false)
    private double componentRotationInTray = 0;

    @Attribute(required=false)
    private boolean legacyPickingInProgress = false;

    @Element(required = false)
    protected Location lastComponentLocation = new Location(LengthUnit.Millimeters);
    @Element(required = false)
    protected Location firstRowLastComponentLocation = new Location(LengthUnit.Millimeters);

    @Commit
    public void commit() {
        if (trayRotation != null) {
            Logger.trace("Updating legacy Rotated Tray Feeder to latest version.");
            //In previous versions, the location held the pick rotation and trayRotation held
            //the actual rotation of the tray. In this version, the location holds the actual
            //rotation of the tray and componentRotationInTray holds the rotation of the component
            //relative to the tray. Note, in almost all cases, componentRotationInTray will be one
            //of 0, or +/-90, or +/-180. So, with the new version, pick rotation =
            //location.getRotation() + componentRotationInTray

            //Convert the values from the old version to the new version
            componentRotationInTray = location.getRotation() - trayRotation;
            location = location.derive(null, null, null, trayRotation);

            //The previous version of the feeder also had a bug which caused it to skip the first
            //component and thereafter it was picking one component ahead of where it should.
            //This bumps the feedCount up by one to account for that bug.
            if ((feedCount > 0) && (feedCount < trayCountCols*trayCountRows)) {
                feedCount++;
                legacyPickingInProgress = true;
            }

            //Remove the deprecated attribute
            trayRotation = null;
        }
        componentRotationInTray = normalizeComponentRotationInTray(componentRotationInTray);
        migrateGridVectorsIfNeeded();
    }



    public void feed(Nozzle nozzle) throws Exception {
        Logger.debug("{}.feed({})", getName(), nozzle);

        lastLocation = pickLocation;
        pickLocation = null;

        int trayCapacity = getEffectiveTrayCountCols() * getEffectiveTrayCountRows();
        if (feedCount >= trayCapacity) {
            throw new FeederEmptyException(this.getName() + " (" + this.partId + ") is empty.");
        }

        // Advance to exactly one next pocket per feed() call. If no part is found in that pocket,
        // throw a feed fault so the Job Processor fault window / limit logic can act on it.
        setFeedCount(getFeedCount() + 1);
        Location nextPocket = getNominalPocketLocation(getFeedCount() - 1);
        pickLocation = locateFeederPart(nozzle, nextPocket);
        if (pickLocation == null) {
            throw new Exception(
                    String.format("Feeder %s: Pick %d at location [%s] not found.",
                            getName(), getFeedCount(), nextPocket));
        }
    }

    private Location getNextPocketLocation() {
        int feedCountBase0 = feedCount - 1;
        if (feedCount == 0) {
            feedCountBase0 = 0;
        }
        else if (feedCount > (trayCountCols * trayCountRows)) {
            feedCountBase0 = trayCountCols * trayCountRows - 1;
            Logger.warn("{}.getPickLocation: feedCount larger then tray, limiting to maximum.", getName());
        }
        return getNominalPocketLocation(feedCountBase0);
    }

    public Location getNominalPocketLocation(int feedCountBase0) {
        int rows = getEffectiveTrayCountRows();
        int cols = getEffectiveTrayCountCols();
        int clampedFeedIndex = Math.max(0, Math.min(feedCountBase0, rows * cols - 1));
        GridIndex gridIndex = getGridIndexForFeed(clampedFeedIndex, rows, cols,
                getStartCorner(), getFirstRasterDirection(), getRasterPattern());

        Location origin = getGridOrigin().convertToUnits(Configuration.get().getSystemUnits());
        Location colStep = getColumnVector().convertToUnits(origin.getUnits()).multiply(gridIndex.col);
        Location rowStep = getRowVector().convertToUnits(origin.getUnits()).multiply(gridIndex.row);
        Location pocketCenter = origin.add(colStep).add(rowStep);
        double z = location.convertToUnits(pocketCenter.getUnits()).getZ();
        return pocketCenter.derive(null, null, z, getSafeNominalPickRotation(pocketCenter));
    }

    double getSafeNominalPickRotation(Location pocketCenter) {
        return calculatePickRotation(isRotateNozzleAtPick(), false,
                getLocation().getRotation(), getLocation().getRotation(),
                getComponentRotationInTray(), Double.NaN);
    }

    public static GridIndex getGridIndexForFeed(int feedIndexBase0, int rows, int cols,
            StartCorner startCorner, FirstRasterDirection firstRasterDirection, RasterPattern rasterPattern) {
        rows = Math.max(rows, 1);
        cols = Math.max(cols, 1);
        int capacity = rows * cols;
        int index = Math.max(0, Math.min(feedIndexBase0, capacity - 1));
        startCorner = startCorner == null ? StartCorner.BOTTOM_LEFT : startCorner;
        firstRasterDirection = firstRasterDirection == null ? FirstRasterDirection.ROW : firstRasterDirection;
        rasterPattern = rasterPattern == null ? RasterPattern.ZIG_ZAG : rasterPattern;

        boolean startTop = startCorner == StartCorner.TOP_LEFT || startCorner == StartCorner.TOP_RIGHT;
        boolean startLeft = startCorner == StartCorner.TOP_LEFT || startCorner == StartCorner.BOTTOM_LEFT;

        int row;
        int col;
        if (firstRasterDirection == FirstRasterDirection.ROW) {
            int pass = index / cols;
            int inPass = index % cols;
            boolean reverse = rasterPattern == RasterPattern.SNAKE && (pass % 2) == 1;
            row = startTop ? pass : rows - 1 - pass;
            if (reverse) {
                col = startLeft ? cols - 1 - inPass : inPass;
            }
            else {
                col = startLeft ? inPass : cols - 1 - inPass;
            }
        }
        else {
            int pass = index / rows;
            int inPass = index % rows;
            boolean reverse = rasterPattern == RasterPattern.SNAKE && (pass % 2) == 1;
            col = startLeft ? pass : cols - 1 - pass;
            if (reverse) {
                row = startTop ? rows - 1 - inPass : inPass;
            }
            else {
                row = startTop ? inPass : rows - 1 - inPass;
            }
        }

        row = Math.max(0, Math.min(row, rows - 1));
        col = Math.max(0, Math.min(col, cols - 1));
        return new GridIndex(row, col);
    }

    public String getSecondRasterDirectionDescription() {
        return getSecondRasterDirectionDescription(getStartCorner(), getFirstRasterDirection());
    }

    public static String getSecondRasterDirectionDescription(StartCorner startCorner,
            FirstRasterDirection firstRasterDirection) {
        startCorner = startCorner == null ? StartCorner.BOTTOM_LEFT : startCorner;
        firstRasterDirection = firstRasterDirection == null ? FirstRasterDirection.ROW : firstRasterDirection;
        if (firstRasterDirection == FirstRasterDirection.ROW) {
            return (startCorner == StartCorner.TOP_LEFT || startCorner == StartCorner.TOP_RIGHT) ? "DOWN" : "UP";
        }
        return (startCorner == StartCorner.TOP_LEFT || startCorner == StartCorner.BOTTOM_LEFT) ? "RIGHT" : "LEFT";
    }

    /**
     * Executes the vision pipeline to locate a part.
     * @param nozzle used nozzle
     * @return location or null
     * @throws Exception something went wrong
     */
    private Location locateFeederPart(Nozzle nozzle, Location startPoint) throws Exception {
        Camera camera = nozzle.getHead().getDefaultCamera();

        // startPoint contains the nominal pick rotation, e.g. componentRotationInTray.
        // Do not use that rotation for the head-mounted top-camera vision move.
        // The camera should image the tray pocket using its own normal viewing rotation;
        // the final pick location will still receive the configured part pick rotation.
        Location cameraLocation = camera.getLocation();
        double cameraRotation = cameraLocation.getRotation();
        if (Double.isNaN(cameraRotation)) {
            cameraRotation = 0;
        }

        Location visionStartPoint = startPoint.derive(null, null, null, cameraRotation);

        MovableUtils.moveToLocationAtSafeZ(camera, visionStartPoint);
        camera.waitForCompletion(CompletionType.WaitForStillstand);

        int maxPasses = getEffectiveRecenterMaxPasses();
        double toleranceMm = getEffectiveRecenterToleranceMm();
        for (int pass = 0; pass < maxPasses; pass++) {
            if (isUseAsyncGcodeMotion()) {
                camera.waitForCompletion(CompletionType.WaitForStillstand);
            }
            try (CvPipeline pipeline = getPipelineForProcessing()) {
                // Process the pipeline to extract RotatedRect results
                pipeline.setProperty("camera", camera);
                pipeline.setProperty("nozzle", nozzle);
                pipeline.setProperty("feeder", this);
                pipeline.process();
                // Grab the results
                @SuppressWarnings("unchecked")
                List<RotatedRect> results = (List<RotatedRect>) pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME).model;
                if ((results == null) || results.isEmpty()) {
                    //nothing found
                    return null;
                }
                // Find the closest result
                results.sort((a, b) -> {
                    Double da = getPixelLocation(camera, a.center.x, a.center.y)
                            .getLinearDistanceTo(camera.getLocation());
                    Double db = getPixelLocation(camera, b.center.x, b.center.y)
                            .getLinearDistanceTo(camera.getLocation());
                    return da.compareTo(db);
                });
                RotatedRect result = results.get(0);
                Location partLocation = getPixelLocation(camera, result.center.x, result.center.y);
                // Detected RotatedRect angle is retained for diagnostics only by default.
                // It can affect pick R only when the legacy/debug option is explicitly enabled.
                lastDetectedAngle = result.angle;
                double pickRotation = getPickRotation(startPoint, result.angle);
                Logger.debug("{}.locateFeederPart(): nominal rotation {}, detected offset {}, pick rotation {}",
                        getName(), startPoint.getRotation(), result.angle, pickRotation);
                // Update the location with the configured pick Z and the safe pick rotation.
                // The default safe rotation deliberately excludes component rotation and
                // detected angle to avoid runout-compensation XY shifts before pickup.
                partLocation =
                        partLocation.derive(null, null,
                                this.location.convertToUnits(partLocation.getUnits()).getZ(),
                                pickRotation);
                MainFrame.get().getCameraViews().getCameraView(camera)
                        .showFilteredImage(OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()), 250);

                Location guardedPartLocation = checkIfInInitialView(camera, visionStartPoint, partLocation);
                if (guardedPartLocation == null) {
                    return null;
                }

                Location cameraLocationMm = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
                Location partLocationMm = guardedPartLocation.convertToUnits(LengthUnit.Millimeters);
                double dx = partLocationMm.getX() - cameraLocationMm.getX();
                double dy = partLocationMm.getY() - cameraLocationMm.getY();

                if (Math.abs(dx) <= toleranceMm && Math.abs(dy) <= toleranceMm) {
                    return guardedPartLocation;
                }

                if (pass + 1 < maxPasses) {
                    moveCameraToDieCenter(camera, guardedPartLocation);
                    camera.waitForCompletion(CompletionType.WaitForStillstand);
                }
                else {
                    return guardedPartLocation;
                }
            }
        }
        return null;
    }

    double getPickRotation(Location startPoint, double detectedAngle) {
        return calculatePickRotation(isRotateNozzleAtPick(), isUseDetectedAngleForPickRotation(),
                getLocation().getRotation(), startPoint.getRotation(), getComponentRotationInTray(), detectedAngle);
    }

    public static double calculatePickRotation(boolean rotateNozzleAtPick, boolean useDetectedAngleForPickRotation,
            double trayRotation, double nominalPocketRotation, double componentRotationInTray, double detectedAngle) {
        if (useDetectedAngleForPickRotation) {
            // Legacy/debug absolute-angle behavior. Disabled by default because nozzle
            // runout compensation can shift XY when pick R changes.
            return -(detectedAngle + trayRotation);
        }
        if (rotateNozzleAtPick) {
            return trayRotation + normalizeComponentRotationInTray(componentRotationInTray);
        }
        // Safe default: stable tray/display rotation only, no component or detected angle.
        return trayRotation;
    }

    public static double normalizeComponentRotationInTray(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        double normalized = Utils2D.angleNorm(value, 180);
        double[] allowed = new double[] { 0, 90, 180, -90 };
        double best = allowed[0];
        double bestDistance = Double.MAX_VALUE;
        for (double candidate : allowed) {
            double distance = Math.abs(Utils2D.angleNorm(normalized - candidate, 180));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best < 0 ? 270 : best;
    }

    private double getEffectiveRecenterToleranceMm() {
        if (Double.isNaN(recenterToleranceMm)) {
            return DEFAULT_RECENTER_TOLERANCE_MM;
        }
        return Math.max(0, recenterToleranceMm);
    }

    private int getEffectiveRecenterMaxPasses() {
        return Math.max(recenterMaxPasses, 1);
    }

    private Location getPixelLocation(Camera camera, double x, double y) {
        if (!isUseAdvancedCameraCalibration()) {
            return VisionUtils.getPixelLocation(camera, x, y);
        }

        Location unitsPerPixel = getUnitsPerPixelForVision(camera);
        LengthUnit units = unitsPerPixel.getUnits();
        double offsetX = x - camera.getWidth() / 2.0;
        double offsetY = y - camera.getHeight() / 2.0;
        double machineOffsetX = offsetX * unitsPerPixel.getX();
        double machineOffsetY = -offsetY * unitsPerPixel.getY();
        return camera.getLocation().add(new Location(units, machineOffsetX, machineOffsetY, 0, 0));
    }

    private Location getUnitsPerPixelForVision(Camera camera) {
        if (!isUseAdvancedCameraCalibration()) {
            return camera.getUnitsPerPixelAtZ();
        }
        return camera.getUnitsPerPixel(getVisionViewingPlaneZ());
    }

    private Length getVisionViewingPlaneZ() {
        Length feederZ = this.location.getLengthZ();
        Length partHeight = getPart() == null ? null : getPart().getHeight();
        if (partHeight == null || partHeight.getUnits() == null) {
            return feederZ;
        }
        return feederZ.add(partHeight);
    }

    private void moveCameraToDieCenter(Camera camera, Location detectedLocation) throws Exception {
        Location cameraLocation = camera.getLocation();
        Location detectedCameraUnits = detectedLocation.convertToUnits(cameraLocation.getUnits());
        double rotation = cameraLocation.getRotation();
        if (Double.isNaN(rotation)) {
            rotation = 0;
        }
        Location targetLocation = new Location(cameraLocation.getUnits(),
                detectedCameraUnits.getX(), detectedCameraUnits.getY(), cameraLocation.getZ(), rotation);
        MovableUtils.moveToLocationAtSafeZ(camera, targetLocation);
    }

    /**
     * Checks if the testLocation is inside the camera view starting on the initialViewLocation.
     * Avoids to run outside the initial area if a bad pipeline repeated detects the parts
     * on one edge of the field of view, even after moving the camera to the location.
     * @param camera the used camera
     * @param initialViewLocation the location where the camera view started
     * @param testLocation the location to test
     * @return the testLocation, or null if outside the initial field of view
     */
    private Location checkIfInInitialView(Camera camera, Location initialViewLocation, Location testLocation) {
        // just make sure, the vision did not "run away" => outside of the initial camera range
        // should never happen, but with badly dialed in pipelines ...
        double distanceX = Math.abs(initialViewLocation.convertToUnits(LengthUnit.Millimeters).getX() - testLocation.convertToUnits(LengthUnit.Millimeters).getX());
        double distanceY = Math.abs(initialViewLocation.convertToUnits(LengthUnit.Millimeters).getY() - testLocation.convertToUnits(LengthUnit.Millimeters).getY());

        // if moved more than the half of the camera picture size => something went wrong => return no result
        Location unitsPerPixel = getUnitsPerPixelForVision(camera).convertToUnits(LengthUnit.Millimeters);
        if (distanceX > unitsPerPixel.getX() * camera.getWidth() / 2
                || distanceY > unitsPerPixel.getY() * camera.getHeight() / 2) {
            System.err.println("Vision outside of the initial area");
            return null;
        }
        return testLocation;
    }
    /**
     * Returns if the feeder can take back a part.
     * Makes the assumption, that after each feed a pick followed,
     * so the pockets are now empty.
     */
    @Override
    public boolean canTakeBackPart() {
        if (feedCount > 0 ) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    //TODO:: Handle takeBack, we want to dispose for our purposes, not return to feeder (also that means don't decrement feed count)
    public void takeBackPart(Nozzle nozzle) throws Exception {
        // first check if we can and want to take back this part (should be always be checked before calling, but to be sure)
        if (nozzle.getPart() == null) {
            throw new UnsupportedOperationException("No part loaded that could be taken back.");
        }
        if (!nozzle.getPart().equals(getPart())) {
            throw new UnsupportedOperationException("Feeder: " + getName() + " - Can not take back " + nozzle.getPart().getId() + " this feeder only supports " + getPart().getId());
        }
        if (!canTakeBackPart()) {
            throw new UnsupportedOperationException("Feeder: " + getName() + " - Currently no free slot. Can not take back the part.");
        }

        // ok, now put the part back on the location of the last pick
        nozzle.moveToPickLocation(this);
        nozzle.place();
        nozzle.moveToSafeZ();
        if (nozzle.isPartOffEnabled(Nozzle.PartOffStep.AfterPlace) && !nozzle.isPartOff()) {
            throw new Exception("Feeder: " + getName() + " - Putting part back failed, check nozzle tip");
        }
        // change FeedCount
        setFeedCount(getFeedCount() - 1);
    }

    private void migrateGridVectorsIfNeeded() {
        if (!isZeroVector(columnVector) || !isZeroVector(rowVector) || !isZeroVector(gridOrigin)) {
            return;
        }
        LengthUnit units = location.getUnits() == null ? LengthUnit.Millimeters : location.getUnits();
        gridOrigin = location.derive(null, null, 0.0, 0.0).convertToUnits(units);
        Location oldOffsets = offsets == null ? new Location(units) : offsets.convertToUnits(units);
        Location oldColumnVector = new Location(units, oldOffsets.getX(), 0, 0, 0).rotateXy(location.getRotation());
        Location oldRowVector = new Location(units, 0, -oldOffsets.getY(), 0, 0).rotateXy(location.getRotation());
        columnVector = oldColumnVector;
        rowVector = oldRowVector;
        // Legacy tray definitions used the first-row/first-column pocket as the
        // physical grid origin, so migrate them to a top-left start corner even
        // though brand-new JEDEC feeders default to bottom-left rastering.
        startCorner = StartCorner.TOP_LEFT;
        if (legacyPickingInProgress && trayCountCols >= trayCountRows) {
            firstRasterDirection = FirstRasterDirection.COLUMN;
        }
    }

    private static boolean isZeroVector(Location vector) {
        if (vector == null) {
            return true;
        }
        Location mm = vector.convertToUnits(LengthUnit.Millimeters);
        return Math.abs(mm.getX()) < 0.000001 && Math.abs(mm.getY()) < 0.000001;
    }

    public int getTrayCountCols() {
        return trayCountCols;
    }

    public int getEffectiveTrayCountCols() {
        return Math.max(trayCountCols, 1);
    }

    public void setTrayCountCols(int trayCountCols) {
        int oldValue = this.trayCountCols;
        this.trayCountCols = trayCountCols;
        firePropertyChange("trayCountCols", oldValue, trayCountCols);
        firePropertyChange("remainingCount", trayCountRows*oldValue - feedCount,
                trayCountRows*trayCountCols - feedCount);
    }

    public int getTrayCountRows() {
        return trayCountRows;
    }

    public int getEffectiveTrayCountRows() {
        return Math.max(trayCountRows, 1);
    }

    public void setTrayCountRows(int trayCountRows) {
        int oldValue = this.trayCountRows;
        this.trayCountRows = trayCountRows;
        firePropertyChange("trayCountRows", oldValue, trayCountRows);
        firePropertyChange("remainingCount", oldValue*trayCountCols - feedCount,
                trayCountRows*trayCountCols - feedCount);
    }

    public Location getLastComponentLocation() {
        return lastComponentLocation;
    }

    public void setLastComponentLocation(Location LastComponentLocation) {
        this.lastComponentLocation = LastComponentLocation;
    }

    public Location getFirstRowLastComponentLocation() {
        return this.firstRowLastComponentLocation;
    }

    public void setFirstRowLastComponentLocation(Location FirstRowLastComponentLocation) {
        this.firstRowLastComponentLocation = FirstRowLastComponentLocation;
    }

    public Location getOffsets() {
        return offsets;
    }

    public void setOffsets(Location offsets) {
        this.offsets = offsets;
    }

    public int getFeedCount() {
        return feedCount;
    }

    /**
     * Changed .debug to show the machine location. This was calling getPickLocation again, though not really what needs
     * to happen. I know this isn't gonna work but for now good enough
     * @param feedCount
     */
    public void setFeedCount(int feedCount) {
        int oldValue = this.feedCount;
        this.feedCount = feedCount;
        if (feedCount == 0) {
            legacyPickingInProgress = false;
        }
        firePropertyChange("feedCount", oldValue, feedCount);
        firePropertyChange("remainingCount", trayCountRows*trayCountCols - oldValue,
                trayCountRows*trayCountCols - feedCount);
        Logger.debug("{}.setFeedCount(): feedCount {}, pickLocation {}", getName(), feedCount, location);
    }

    public int getRemainingCount() {
        return trayCountRows*trayCountCols - feedCount;
    }


    public boolean isUseAdvancedCameraCalibration() {
        return useAdvancedCameraCalibration;
    }

    public void setUseAdvancedCameraCalibration(boolean useAdvancedCameraCalibration) {
        boolean oldValue = this.useAdvancedCameraCalibration;
        this.useAdvancedCameraCalibration = useAdvancedCameraCalibration;
        firePropertyChange("useAdvancedCameraCalibration", oldValue, useAdvancedCameraCalibration);
    }

    public boolean isUseAsyncGcodeMotion() {
        return useAsyncGcodeMotion;
    }

    public void setUseAsyncGcodeMotion(boolean useAsyncGcodeMotion) {
        boolean oldValue = this.useAsyncGcodeMotion;
        this.useAsyncGcodeMotion = useAsyncGcodeMotion;
        firePropertyChange("useAsyncGcodeMotion", oldValue, useAsyncGcodeMotion);
    }

    public double getRecenterToleranceMm() {
        return getEffectiveRecenterToleranceMm();
    }

    public void setRecenterToleranceMm(double recenterToleranceMm) {
        double oldValue = this.recenterToleranceMm;
        this.recenterToleranceMm = Double.isNaN(recenterToleranceMm)
                ? DEFAULT_RECENTER_TOLERANCE_MM : Math.max(0, recenterToleranceMm);
        firePropertyChange("recenterToleranceMm", oldValue, this.recenterToleranceMm);
    }

    public int getRecenterMaxPasses() {
        return getEffectiveRecenterMaxPasses();
    }

    public void setRecenterMaxPasses(int recenterMaxPasses) {
        int oldValue = this.recenterMaxPasses;
        this.recenterMaxPasses = Math.max(recenterMaxPasses, 1);
        firePropertyChange("recenterMaxPasses", oldValue, this.recenterMaxPasses);
    }

    public boolean isUseDetectedAngleForPickRotation() {
        return useDetectedAngleForPickRotation;
    }

    public void setUseDetectedAngleForPickRotation(boolean useDetectedAngleForPickRotation) {
        boolean oldValue = this.useDetectedAngleForPickRotation;
        this.useDetectedAngleForPickRotation = useDetectedAngleForPickRotation;
        firePropertyChange("useDetectedAngleForPickRotation", oldValue, useDetectedAngleForPickRotation);
    }

    public double getComponentRotationInTray() {
        return componentRotationInTray;
    }

    public void setComponentRotationInTray(double componentRotationInTray) {
        double oldValue = this.componentRotationInTray;
        this.componentRotationInTray = normalizeComponentRotationInTray(componentRotationInTray);
        firePropertyChange("componentRotationInTray", oldValue, this.componentRotationInTray);
    }

    public boolean isRotateNozzleAtPick() {
        return rotateNozzleAtPick;
    }

    public void setRotateNozzleAtPick(boolean rotateNozzleAtPick) {
        boolean oldValue = this.rotateNozzleAtPick;
        this.rotateNozzleAtPick = rotateNozzleAtPick;
        firePropertyChange("rotateNozzleAtPick", oldValue, rotateNozzleAtPick);
    }

    public StartCorner getStartCorner() {
        return startCorner == null ? StartCorner.BOTTOM_LEFT : startCorner;
    }

    public void setStartCorner(StartCorner startCorner) {
        StartCorner oldValue = this.startCorner;
        this.startCorner = startCorner == null ? StartCorner.BOTTOM_LEFT : startCorner;
        firePropertyChange("startCorner", oldValue, this.startCorner);
        firePropertyChange("secondRasterDirectionDescription", null, getSecondRasterDirectionDescription());
    }

    public FirstRasterDirection getFirstRasterDirection() {
        return firstRasterDirection == null ? FirstRasterDirection.ROW : firstRasterDirection;
    }

    public void setFirstRasterDirection(FirstRasterDirection firstRasterDirection) {
        FirstRasterDirection oldValue = this.firstRasterDirection;
        this.firstRasterDirection = firstRasterDirection == null ? FirstRasterDirection.ROW : firstRasterDirection;
        firePropertyChange("firstRasterDirection", oldValue, this.firstRasterDirection);
        firePropertyChange("secondRasterDirectionDescription", null, getSecondRasterDirectionDescription());
    }

    public RasterPattern getRasterPattern() {
        return rasterPattern == null ? RasterPattern.ZIG_ZAG : rasterPattern;
    }

    public void setRasterPattern(RasterPattern rasterPattern) {
        RasterPattern oldValue = this.rasterPattern;
        this.rasterPattern = rasterPattern == null ? RasterPattern.ZIG_ZAG : rasterPattern;
        firePropertyChange("rasterPattern", oldValue, this.rasterPattern);
    }

    public Location getGridOrigin() {
        return gridOrigin == null ? new Location(LengthUnit.Millimeters) : gridOrigin;
    }

    public void setGridOrigin(Location gridOrigin) {
        Location oldValue = this.gridOrigin;
        this.gridOrigin = gridOrigin == null ? new Location(LengthUnit.Millimeters) : gridOrigin;
        firePropertyChange("gridOrigin", oldValue, this.gridOrigin);
    }

    public Location getColumnVector() {
        return columnVector == null ? new Location(LengthUnit.Millimeters) : columnVector;
    }

    public void setColumnVector(Location columnVector) {
        Location oldValue = this.columnVector;
        this.columnVector = columnVector == null ? new Location(LengthUnit.Millimeters) : columnVector;
        firePropertyChange("columnVector", oldValue, this.columnVector);
    }

    public Location getRowVector() {
        return rowVector == null ? new Location(LengthUnit.Millimeters) : rowVector;
    }

    public void setRowVector(Location rowVector) {
        Location oldValue = this.rowVector;
        this.rowVector = rowVector == null ? new Location(LengthUnit.Millimeters) : rowVector;
        firePropertyChange("rowVector", oldValue, this.rowVector);
    }

    public double getLastDetectedAngle() {
        return lastDetectedAngle;
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new JEDEC_TrayFeederConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return null;
    }

    /**
     * From AdvancedLoosePartFeeder
     * @return
     */
    @Override
    public boolean isPartHeightAbovePickLocation() {
        // JEDEC tray pick Z is expected to be the part top (like tray feeders),
        // so do not add part height again in Nozzle.moveToPickLocation().
        return false;
    }

    public CvPipeline getPipeline() {
        return pipeline;
    }

    public FiducialVisionSettings getFiducialVisionSettings() {
        if (fiducialVisionSettingsId == null || fiducialVisionSettingsId.isEmpty()) {
            return null;
        }
        if (!(Configuration.get().getVisionSettings(fiducialVisionSettingsId) instanceof FiducialVisionSettings)) {
            return null;
        }
        return (FiducialVisionSettings) Configuration.get().getVisionSettings(fiducialVisionSettingsId);
    }

    public void setFiducialVisionSettings(FiducialVisionSettings fiducialVisionSettings) {
        Object oldValue = getFiducialVisionSettings();
        fiducialVisionSettingsId = (fiducialVisionSettings != null) ? fiducialVisionSettings.getId() : null;
        firePropertyChange("fiducialVisionSettings", oldValue, fiducialVisionSettings);
    }

    public String getFiducialVisionSettingsId() {
        return fiducialVisionSettingsId;
    }

    public void setFiducialVisionSettingsId(String fiducialVisionSettingsId) {
        String oldId = this.fiducialVisionSettingsId;
        Object oldValue = getFiducialVisionSettings();
        this.fiducialVisionSettingsId = fiducialVisionSettingsId;
        firePropertyChange("fiducialVisionSettingsId", oldId, fiducialVisionSettingsId);
        firePropertyChange("fiducialVisionSettings", oldValue, getFiducialVisionSettings());
    }

    private CvPipeline getPipelineForProcessing() throws CloneNotSupportedException {
        FiducialVisionSettings fiducialVisionSettings = getFiducialVisionSettings();
        if (fiducialVisionSettings != null && fiducialVisionSettings.getPipeline() != null) {
            return fiducialVisionSettings.getPipeline().clone();
        }
        return getPipeline().clone();
    }

    public void resetPipeline() {
        pipeline = createDefaultPipeline();
    }


    public static CvPipeline createDefaultPipeline() {
        try {
            String xml = IOUtils.toString(AdvancedLoosePartFeeder.class
                    .getResource("AdvancedLoosePartFeeder-DefaultPipeline.xml"));
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }


}
