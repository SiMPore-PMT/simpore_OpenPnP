//package org.openpnp.machine.reference.feeder;
//
//import javax.swing.Action;
//
//import org.apache.commons.io.IOUtils;
//import org.opencv.core.RotatedRect;
//import org.openpnp.gui.MainFrame;
//import org.openpnp.gui.support.PropertySheetWizardAdapter;
//import org.openpnp.gui.support.Wizard;
//import org.openpnp.machine.reference.ReferenceFeeder;
//import org.openpnp.machine.reference.feeder.wizards.AdvancedLoosePartFeederConfigurationWizard;
//import org.openpnp.machine.reference.feeder.wizards.JEDEC_TrayFeederConfigurationWizard;
//import org.openpnp.model.LengthUnit;
//import org.openpnp.model.Location;
//import org.openpnp.spi.Camera;
//import org.openpnp.spi.MotionPlanner;
//import org.openpnp.spi.MotionPlanner.CompletionType;
//import org.openpnp.spi.Nozzle;
//import org.openpnp.spi.PropertySheetHolder;
//import org.openpnp.util.MovableUtils;
//import org.openpnp.util.OpenCvUtils;
//import org.openpnp.util.VisionUtils;
//import org.openpnp.vision.pipeline.CvPipeline;
//import org.simpleframework.xml.Element;
//import org.pmw.tinylog.Logger;
//import org.simpleframework.xml.Attribute;
//import org.simpleframework.xml.core.Commit;
//
//import java.util.List;
//
///**
// * Based off ReferenceRotatedTrayFeeder
// *
// * Start of JEDEC tray implementation for parts. (FUTURE can use actual JEDEC nomenclature,
// * for now just x, y grid motion).
// *
// * Combines ReferenceRotatedTrayFeeder structure and imaging pipeline of AdvancedLoosePartFeeder.
// * Allows for part to be posed, and tip to be adjusted before initial pick from tray
// */
//public class JEDEC_TrayFeeder extends ReferenceFeeder {
//
//    /**
//     * New -> From advancedLoosePartFeeder
//     */
//    @Element(required = false)
//    private CvPipeline pipeline = createDefaultPipeline();
//
//    @Element(required = false)
//    private CvPipeline trainingPipeline = createDefaultTrainingPipeline();
//
//    private Location pickLocation;
//    private Location lastLocation;
//    @Override
//    public Location getPickLocation() throws Exception {
//        return pickLocation == null ? location : pickLocation;
//    }
//
//    @Attribute
//    private int trayCountCols = 1;
//    @Attribute
//    private int trayCountRows = 1;
//    @Element
//    private Location offsets = new Location(LengthUnit.Millimeters);
//    @Attribute
//    private int feedCount = 0;  // 0-based index: number of pockets already consumed/tried
//
//    @Attribute(required=false)
//    @Deprecated
//    private Double trayRotation = null;
//
//    @Attribute(required=false)
//    private double componentRotationInTray = 0;
//
//    @Attribute(required=false)
//    private boolean legacyPickingInProgress = false;
//
//    @Element
//    protected Location lastComponentLocation = new Location(LengthUnit.Millimeters);
//    @Element
//    protected Location firstRowLastComponentLocation = new Location(LengthUnit.Millimeters);
//
//    @Commit
//    public void commit() {
//        if (trayRotation != null) {
//            Logger.trace("Updating legacy Rotated Tray Feeder to latest version.");
//            //In previous versions, the location held the pick rotation and trayRotation held
//            //the actual rotation of the tray. In this version, the location holds the actual
//            //rotation of the tray and componentRotationInTray holds the rotation of the component
//            //relative to the tray. Note, in almost all cases, componentRotationInTray will be one
//            //of 0, or +/-90, or +/-180. So, with the new version, pick rotation =
//            //location.getRotation() + componentRotationInTray
//
//            //Convert the values from the old version to the new version
//            componentRotationInTray = location.getRotation() - trayRotation;
//            location = location.derive(null, null, null, trayRotation);
//
//            //The previous version of the feeder also had a bug which caused it to skip the first
//            //component and thereafter it was picking one component ahead of where it should.
//            //This bumps the feedCount up by one to account for that bug.
//            if ((feedCount > 0) && (feedCount < trayCountCols*trayCountRows)) {
//                feedCount++;
//                legacyPickingInProgress = true;
//            }
//
//            //Remove the deprecated attribute
//            trayRotation = null;
//        }
//    }
//
//
//
//    public void feed(Nozzle nozzle) throws Exception {
//        Logger.debug("{}.feed({})", getName(), nozzle);
//
//        lastLocation = pickLocation;
//        pickLocation = null;
//
//        while (true) {
//            // out-of-bounds check
//            if (getFeedCount() >= (trayCountCols * trayCountRows)) {
//                throw new Exception(getName() + " (" + partId + ") is empty.");
//            }
//
//            // Determine rough position of next pocket (based on current feedCount/index)
//            Location nextPocket = getNextPocketLocation();
//
//            // Try to pre-align & find exact pick location for this pocket
//            Location candidate = locateFeederPart(nozzle, nextPocket);
//
//            if (candidate != null) {
//                // Found a part in this pocket: set pick location and advance pocket index for next time
//                pickLocation = candidate;
//                setFeedCount(getFeedCount() + 1);
//                break; // exit loop; we’re ready to pick/place
//            } else {
//                // No part here: log and advance to the *next pocket* and try again
//                Logger.warn("Pocket {} at {} empty/not found. Skipping.", getFeedCount() + 1, nextPocket);
//                setFeedCount(getFeedCount() + 1);
//                // loop continues to next pocket
//            }
//        }
//    }
//
//
//    private Location getNextPocketLocation() {
//        // Treat feedCount as 0-based index of the next pocket to try
//        int feedCountBase0 = feedCount;
//
//        // Limit feedCount to tray size (defensive)
//        int maxIndex = trayCountCols * trayCountRows - 1;
//        if (feedCountBase0 > maxIndex) {
//            feedCountBase0 = maxIndex;
//            Logger.warn("{}.getNextPocketLocation: feedCount larger than tray, limiting to maximum.", getName());
//        }
//
//        int colNum, rowNum;
//        if (legacyPickingInProgress && (trayCountCols >= trayCountRows)) {
//            // legacy: column-major
//            rowNum = feedCountBase0 % trayCountRows;
//            colNum = feedCountBase0 / trayCountRows;
//        } else {
//            // default: row-major
//            rowNum = feedCountBase0 / trayCountCols;
//            colNum = feedCountBase0 % trayCountCols;
//        }
//
//        // row increases in negative Y
//        Location delta = offsets
//                .multiply(colNum, -rowNum, 0, 0)
//                .rotateXy(location.getRotation())
//                .derive(null, null, null, componentRotationInTray);
//
//        return location.addWithRotation(delta);
//    }
//
//
//    /**
//     * Executes the vision pipeline to locate a part.
//     * @param nozzle used nozzle
//     * @return location or null
//     * @throws Exception something went wrong
//     */
//    private Location locateFeederPart(Nozzle nozzle, Location startPoint) throws Exception {
//        Camera camera = nozzle.getHead().getDefaultCamera();
//        MovableUtils.moveToLocationAtSafeZ(camera, startPoint);
//        camera.waitForCompletion(MotionPlanner.CompletionType.WaitForStillstand);
//        try (CvPipeline pipeline = getPipeline()) {
//            // Process the pipeline to extract RotatedRect results
//            pipeline.setProperty("camera", camera);
//            pipeline.setProperty("nozzle", nozzle);
//            pipeline.setProperty("feeder", this);
//            pipeline.process();
//            // Grab the results
//            List<RotatedRect> results = (List<RotatedRect>) pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME).model;
//            if ((results == null) || results.isEmpty()) {
//                //nothing found
//                return null;
//            }
//            // Find the closest result
//            results.sort((a, b) -> {
//                Double da = VisionUtils.getPixelLocation(camera, a.center.x, a.center.y)
//                        .getLinearDistanceTo(camera.getLocation());
//                Double db = VisionUtils.getPixelLocation(camera, b.center.x, b.center.y)
//                        .getLinearDistanceTo(camera.getLocation());
//                return da.compareTo(db);
//            });
//            RotatedRect result = results.get(0);
//            Location partLocation = VisionUtils.getPixelLocation(camera, result.center.x, result.center.y);
//            // Get the result's Location
//            // Update the location with the result's rotation
//
//            //DAK - Modified to account for componentRotation
//            double baseRotation = startPoint.getRotation();         // includes tray + componentRotationInTray
//            double corrected = baseRotation - result.angle;         // same sign convention you were using (negating the result)
//            partLocation = partLocation.derive(null, null, null, corrected);
//            //partLocation = partLocation.derive(null, null, null, -(result.angle + getLocation().getRotation()));
//
//
//            // Update the location with the correct Z, which is the configured Location's Z.
//            partLocation =
//                    partLocation.derive(null, null,
//                            this.location.convertToUnits(partLocation.getUnits()).getZ(),
//                            null);
//            MainFrame.get().getCameraViews().getCameraView(camera)
//                    .showFilteredImage(OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()), 250);
//
//            return checkIfInInitialView(camera, partLocation);
//        }
//    }
//
//    /**
//     * Checks if the testLocation is inside the camera view centered at the current camera position.
//     * Prevents "runaway" when a bad pipeline finds edges and drags the view.
//     */
//    private Location checkIfInInitialView(Camera camera, Location testLocation) {
//        // Use the actual camera center after we've moved to the pocket, not the feeder origin.
//        Location camCenter = camera.getLocation();
//
//        double camCenterXmm = camCenter.convertToUnits(LengthUnit.Millimeters).getX();
//        double camCenterYmm = camCenter.convertToUnits(LengthUnit.Millimeters).getY();
//        double testXmm      = testLocation.convertToUnits(LengthUnit.Millimeters).getX();
//        double testYmm      = testLocation.convertToUnits(LengthUnit.Millimeters).getY();
//
//        double dx = Math.abs(camCenterXmm - testXmm);
//        double dy = Math.abs(camCenterYmm - testYmm);
//
//        double mmPerPixX = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters).getX();
//        double mmPerPixY = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters).getY();
//
//        double halfFovXmm = mmPerPixX * camera.getWidth()  / 2.0;
//        double halfFovYmm = mmPerPixY * camera.getHeight() / 2.0;
//
//        boolean outside = (dx > halfFovXmm) || (dy > halfFovYmm);
//        if (outside) {
//            Logger.warn("Vision outside of initial camera area: dx={}mm (>{}), dy={}mm (>{})",
//                    String.format("%.3f", dx), String.format("%.3f", halfFovXmm),
//                    String.format("%.3f", dy), String.format("%.3f", halfFovYmm));
//            return null;
//        }
//        return testLocation;
//    }
//    /**
//     * Returns if the feeder can take back a part.
//     * Makes the assumption, that after each feed a pick followed,
//     * so the pockets are now empty.
//     */
//    @Override
//    public boolean canTakeBackPart() {
//        if (feedCount > 0 ) {
//            return true;
//        } else {
//            return false;
//        }
//    }
//
//    @Override
//    //TODO:: Handle takeBack, we want to dispose for our purposes, not return to feeder (also that means don't decrement feed count)
//    public void takeBackPart(Nozzle nozzle) throws Exception {
//        // first check if we can and want to take back this part (should be always be checked before calling, but to be sure)
//        if (nozzle.getPart() == null) {
//            throw new UnsupportedOperationException("No part loaded that could be taken back.");
//        }
//        if (!nozzle.getPart().equals(getPart())) {
//            throw new UnsupportedOperationException("Feeder: " + getName() + " - Can not take back " + nozzle.getPart().getId() + " this feeder only supports " + getPart().getId());
//        }
//        if (!canTakeBackPart()) {
//            throw new UnsupportedOperationException("Feeder: " + getName() + " - Currently no free slot. Can not take back the part.");
//        }
//
//        // ok, now put the part back on the location of the last pick
//        nozzle.moveToPickLocation(this);
//        nozzle.place();
//        nozzle.moveToSafeZ();
//        if (nozzle.isPartOffEnabled(Nozzle.PartOffStep.AfterPlace) && !nozzle.isPartOff()) {
//            throw new Exception("Feeder: " + getName() + " - Putting part back failed, check nozzle tip");
//        }
//        // change FeedCount
//        setFeedCount(getFeedCount() - 1);
//    }
//
//    public int getTrayCountCols() {
//        return trayCountCols;
//    }
//
//    public void setTrayCountCols(int trayCountCols) {
//        int oldValue = this.trayCountCols;
//        this.trayCountCols = trayCountCols;
//        firePropertyChange("trayCountCols", oldValue, trayCountCols);
//        firePropertyChange("remainingCount", trayCountRows*oldValue - feedCount,
//                trayCountRows*trayCountCols - feedCount);
//    }
//
//    public int getTrayCountRows() {
//        return trayCountRows;
//    }
//
//    public void setTrayCountRows(int trayCountRows) {
//        int oldValue = this.trayCountRows;
//        this.trayCountRows = trayCountRows;
//        firePropertyChange("trayCountRows", oldValue, trayCountRows);
//        firePropertyChange("remainingCount", oldValue*trayCountCols - feedCount,
//                trayCountRows*trayCountCols - feedCount);
//    }
//
//    public Location getLastComponentLocation() {
//        return lastComponentLocation;
//    }
//
//    public void setLastComponentLocation(Location LastComponentLocation) {
//        this.lastComponentLocation = LastComponentLocation;
//    }
//
//    public Location getFirstRowLastComponentLocation() {
//        return this.firstRowLastComponentLocation;
//    }
//
//    public void setFirstRowLastComponentLocation(Location FirstRowLastComponentLocation) {
//        this.firstRowLastComponentLocation = FirstRowLastComponentLocation;
//    }
//
//    public Location getOffsets() {
//        return offsets;
//    }
//
//    public void setOffsets(Location offsets) {
//        this.offsets = offsets;
//    }
//
//    public int getFeedCount() {
//        return feedCount;
//    }
//
//    /**
//     * Changed .debug to show the machine location. This was calling getPickLocation again, though not really what needs
//     * to happen. I know this isn't gonna work but for now good enough
//     * @param feedCount
//     */
//    public void setFeedCount(int feedCount) {
//        int oldValue = this.feedCount;
//        this.feedCount = feedCount;
//        if (feedCount == 0) {
//            legacyPickingInProgress = false;
//        }
//        firePropertyChange("feedCount", oldValue, feedCount);
//        firePropertyChange("remainingCount", trayCountRows*trayCountCols - oldValue,
//                trayCountRows*trayCountCols - feedCount);
//        Logger.debug("{}.setFeedCount(): feedCount {}, pickLocation {}", getName(), feedCount, location);
//    }
//
//    public int getRemainingCount() {
//        return trayCountRows*trayCountCols - feedCount;
//    }
//
//    public double getComponentRotationInTray() {
//        return componentRotationInTray;
//    }
//
//    public void setComponentRotationInTray(double componentRotationInTray) {
//        double oldValue = this.componentRotationInTray;
//        this.componentRotationInTray = componentRotationInTray;
//        firePropertyChange("componentRotationInTray", oldValue, componentRotationInTray);
//    }
//
//    @Override
//    public String toString() {
//        return getName();
//    }
//
//    @Override
//    public Wizard getConfigurationWizard() {
//        return new JEDEC_TrayFeederConfigurationWizard(this);
//    }
//
//    @Override
//    public String getPropertySheetHolderTitle() {
//        return getClass().getSimpleName() + " " + getName();
//    }
//
//    @Override
//    public PropertySheetHolder[] getChildPropertySheetHolders() {
//        return null;
//    }
//
//    @Override
//    public Action[] getPropertySheetHolderActions() {
//        return null;
//    }
//
//    /**
//     * From AdvancedLoosePartFeeder
//     * @return
//     */
//    @Override
//    public boolean isPartHeightAbovePickLocation() {
//        return true;
//    }
//
//    public CvPipeline getPipeline() {
//        return pipeline;
//    }
//
//    public void resetPipeline() {
//        pipeline = createDefaultPipeline();
//    }
//
//    public CvPipeline getTrainingPipeline() {
//        return trainingPipeline;
//    }
//
//    public void resetTrainingPipeline() {
//        trainingPipeline = createDefaultTrainingPipeline();
//    }
//
//    public static CvPipeline createDefaultPipeline() {
//        try {
//            String xml = IOUtils.toString(AdvancedLoosePartFeeder.class
//                    .getResource("AdvancedLoosePartFeeder-DefaultPipeline.xml"));
//            return new CvPipeline(xml);
//        }
//        catch (Exception e) {
//            throw new Error(e);
//        }
//    }
//
//    public static CvPipeline createDefaultTrainingPipeline() {
//        try {
//            String xml = IOUtils.toString(AdvancedLoosePartFeeder.class
//                    .getResource("AdvancedLoosePartFeeder-DefaultTrainingPipeline.xml"));
//            return new CvPipeline(xml);
//        }
//        catch (Exception e) {
//            throw new Error(e);
//        }
//    }
//
//}
//////////////////////////////////////////////////// Above is prev working code ///////////////////////////
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
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.simpleframework.xml.Element;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.core.Commit;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Based off ReferenceRotatedTrayFeeder
 *
 * Start of JEDEC tray implementation for parts. (FUTURE can use actual JEDEC nomenclature,
 * for now just x, y grid motion).
 *
 * Combines ReferenceRotatedTrayFeeder structure and imaging pipeline of AdvancedLoosePartFeeder.
 * Allows for part to be posed, and tip to be adjusted before initial pick from tray.
 *
 * This version has been extended to:
 *  - Prefer the Part's FiducialVisionSettings pipeline (if present) for pre-align vision.
 *  - Skip vision completely (simple pick from pocket center) if no Part fiducial vision is configured.
 *  - Still keep local feeder pipelines as a fallback and for training.
 */
public class JEDEC_TrayFeeder extends ReferenceFeeder {

    /**
     * Default feeder-local pipeline and training pipeline (from AdvancedLoosePartFeeder).
     * These are retained for backward compatibility and training, but the feed-time
     * pre-align now prefers the Part's FiducialVisionSettings pipeline when available.
     */
    @Element(required = false)
    private CvPipeline pipeline = createDefaultPipeline();

    @Element(required = false)
    private CvPipeline trainingPipeline = createDefaultTrainingPipeline();

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
    @Element
    private Location offsets = new Location(LengthUnit.Millimeters);
    @Attribute
    private int feedCount = 0;  // 0-based index: number of pockets already consumed/tried

    @Attribute(required=false)
    @Deprecated
    private Double trayRotation = null;

    @Attribute(required=false)
    private double componentRotationInTray = 0;

    @Attribute(required=false)
    private boolean legacyPickingInProgress = false;

    @Element
    protected Location lastComponentLocation = new Location(LengthUnit.Millimeters);
    @Element
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
    }

    public void feed(Nozzle nozzle) throws Exception {
        Logger.debug("{}.feed({})", getName(), nozzle);

        lastLocation = pickLocation;
        pickLocation = null;

        while (true) {
            // out-of-bounds check
            if (getFeedCount() >= (trayCountCols * trayCountRows)) {
                throw new Exception(getName() + " (" + partId + ") is empty.");
            }

            // Determine rough position of next pocket (based on current feedCount/index)
            Location nextPocket = getNextPocketLocation();

            // Try to pre-align & find exact pick location for this pocket
            Location candidate = locateFeederPart(nozzle, nextPocket);

            if (candidate != null) {
                // Found a part in this pocket: set pick location and advance pocket index for next time
                pickLocation = candidate;
                setFeedCount(getFeedCount() + 1);
                break; // exit loop; we’re ready to pick/place
            } else {
                // No part here: log and advance to the *next pocket* and try again
                Logger.warn("Pocket {} at {} empty/not found. Skipping.", getFeedCount() + 1, nextPocket);
                setFeedCount(getFeedCount() + 1);
                // loop continues to next pocket
            }
        }
    }

    private Location getNextPocketLocation() {
        // Treat feedCount as 0-based index of the next pocket to try
        int feedCountBase0 = feedCount;

        // Limit feedCount to tray size (defensive)
        int maxIndex = trayCountCols * trayCountRows - 1;
        if (feedCountBase0 > maxIndex) {
            feedCountBase0 = maxIndex;
            Logger.warn("{}.getNextPocketLocation: feedCount larger than tray, limiting to maximum.", getName());
        }

        int colNum, rowNum;
        if (legacyPickingInProgress && (trayCountCols >= trayCountRows)) {
            // legacy: column-major
            rowNum = feedCountBase0 % trayCountRows;
            colNum = feedCountBase0 / trayCountRows;
        } else {
            // default: row-major
            rowNum = feedCountBase0 / trayCountCols;
            colNum = feedCountBase0 % trayCountCols;
        }

        // row increases in negative Y
        Location delta = offsets
                .multiply(colNum, -rowNum, 0, 0)
                .rotateXy(location.getRotation())
                .derive(null, null, null, componentRotationInTray);

        return location.addWithRotation(delta);
    }

    /**
     * Try to create a fresh pipeline instance from the Part's FiducialVisionSettings,
     * using reflection so we don't hard-wire against internal APIs.
     *
     * @return New CvPipeline to use for this vision run, or null if:
     *         - no Part is configured,
     *         - no FiducialVisionSettings are configured for the Part, or
     *         - we can't access its pipeline.
     */
    private CvPipeline createFiducialPipelineInstanceFromPart() {
        Part part = getPart();
        if (part == null) {
            return null;
        }
        try {
            // Part#getFiducialVisionSettings()
            java.lang.reflect.Method getFvs = part.getClass().getMethod("getFiducialVisionSettings");
            Object fvs = getFvs.invoke(part);
            if (fvs == null) {
                // Part has no fiducial vision configured
                return null;
            }

            // Prefer a dedicated factory if present: FiducialVisionSettings#createPipelineInstance()
            try {
                java.lang.reflect.Method createInstance = fvs.getClass().getMethod("createPipelineInstance");
                Object instance = createInstance.invoke(fvs);
                if (instance instanceof CvPipeline) {
                    return (CvPipeline) instance;
                }
            }
            catch (NoSuchMethodException ignored) {
                // Fall through to cloning the base pipeline
            }

            // Fallback: clone the configured pipeline via toXml()
            java.lang.reflect.Method getPipelineMethod = fvs.getClass().getMethod("getPipeline");
            Object basePipelineObj = getPipelineMethod.invoke(fvs);
            if (!(basePipelineObj instanceof CvPipeline)) {
                Logger.warn("FiducialVisionSettings pipeline is not a CvPipeline for part {}", part.getId());
                return null;
            }
            CvPipeline basePipeline = (CvPipeline) basePipelineObj;

            try {
                java.lang.reflect.Method toXml = basePipeline.getClass().getMethod("toXml");
                String xml = (String) toXml.invoke(basePipeline);
                return new CvPipeline(xml);
            }
            catch (NoSuchMethodException e) {
                // As a last resort, re-use the pipeline directly (callers must not close it then).
                Logger.warn(e, "CvPipeline.toXml() not found; reusing fiducial pipeline directly.");
                return basePipeline;
            }
        }
        catch (NoSuchMethodException e) {
            // Older / different OpenPnP version: Part has no fiducial vision API at all.
            Logger.debug("Part {} has no getFiducialVisionSettings() method; skipping fiducial vision.", part.getId());
            return null;
        }
        catch (Exception e) {
            Logger.warn(e, "Error creating fiducial pipeline instance for part {}.", part.getId());
            return null;
        }
    }

    /**
     * Executes the vision pipeline to locate a part.
     *
     * Behavior change vs. your original version:
     *  - If the Part has FiducialVisionSettings, we create a pipeline instance from that and use it.
     *  - If the Part has NO fiducial vision settings at all, we SKIP vision and simply
     *    return the nominal pocket center as the pick location (with tray Z and rotation).
     *
     * @param nozzle      used nozzle
     * @param startPoint  nominal pocket center location
     * @return location or null
     * @throws Exception something went wrong
     */
    private Location locateFeederPart(Nozzle nozzle, Location startPoint) throws Exception {
        Camera camera = nozzle.getHead().getDefaultCamera();
        MovableUtils.moveToLocationAtSafeZ(camera, startPoint);
        camera.waitForCompletion(MotionPlanner.CompletionType.WaitForStillstand);

        // Ask the Part for a fiducial vision pipeline; if none, we skip vision entirely.
        CvPipeline fidPipeline = createFiducialPipelineInstanceFromPart();

        if (fidPipeline == null) {
            // No part-level fiducial defined: treat this as a simple tray pick with no vision.
            // Use the tray's configured Z and rotation for the pick location.
            Location partLocation = startPoint.derive(
                    null,
                    null,
                    this.location.convertToUnits(startPoint.getUnits()).getZ(),
                    startPoint.getRotation()
            );
            Logger.debug("{}.locateFeederPart(): no part fiducial vision; using nominal pocket location {}",
                    getName(), partLocation);
            return checkIfInInitialView(camera, partLocation);
        }

        // We DO have a part fiducial vision pipeline: use it to pre-align.
        try (CvPipeline pipeline = fidPipeline) {
            pipeline.setProperty("camera", camera);
            pipeline.setProperty("nozzle", nozzle);
            pipeline.setProperty("feeder", this);
            pipeline.setProperty("part", getPart());
            pipeline.process();

            @SuppressWarnings("unchecked")
            List<RotatedRect> results =
                    (List<RotatedRect>) pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME).model;

            if ((results == null) || results.isEmpty()) {
                // nothing found
                Logger.warn("{}.locateFeederPart(): no vision results from fiducial pipeline.", getName());
                return null;
            }

            // Find the closest result to camera center
            results.sort((a, b) -> {
                Double da = VisionUtils.getPixelLocation(camera, a.center.x, a.center.y)
                        .getLinearDistanceTo(camera.getLocation());
                Double db = VisionUtils.getPixelLocation(camera, b.center.x, b.center.y)
                        .getLinearDistanceTo(camera.getLocation());
                return da.compareTo(db);
            });

            RotatedRect result = results.get(0);
            Location partLocation = VisionUtils.getPixelLocation(camera, result.center.x, result.center.y);

            // Adjust rotation: startPoint rotation includes tray + componentRotationInTray
            double baseRotation = startPoint.getRotation();
            double corrected = baseRotation - result.angle;
            partLocation = partLocation.derive(null, null, null, corrected);

            // Update the location with the correct Z, which is the configured Location's Z.
            partLocation =
                    partLocation.derive(null, null,
                            this.location.convertToUnits(partLocation.getUnits()).getZ(),
                            null);

            MainFrame.get().getCameraViews().getCameraView(camera)
                    .showFilteredImage(OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()), 250);

            return checkIfInInitialView(camera, partLocation);
        }
    }

    /**
     * Checks if the testLocation is inside the camera view centered at the current camera position.
     * Prevents "runaway" when a bad pipeline finds edges and drags the view.
     */
    private Location checkIfInInitialView(Camera camera, Location testLocation) {
        // Use the actual camera center after we've moved to the pocket, not the feeder origin.
        Location camCenter = camera.getLocation();

        double camCenterXmm = camCenter.convertToUnits(LengthUnit.Millimeters).getX();
        double camCenterYmm = camCenter.convertToUnits(LengthUnit.Millimeters).getY();
        double testXmm      = testLocation.convertToUnits(LengthUnit.Millimeters).getX();
        double testYmm      = testLocation.convertToUnits(LengthUnit.Millimeters).getY();

        double dx = Math.abs(camCenterXmm - testXmm);
        double dy = Math.abs(camCenterYmm - testYmm);

        double mmPerPixX = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters).getX();
        double mmPerPixY = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters).getY();

        double halfFovXmm = mmPerPixX * camera.getWidth()  / 2.0;
        double halfFovYmm = mmPerPixY * camera.getHeight() / 2.0;

        boolean outside = (dx > halfFovXmm) || (dy > halfFovYmm);
        if (outside) {
            Logger.warn("Vision outside of initial camera area: dx={}mm (>{}), dy={}mm (>{})",
                    String.format("%.3f", dx), String.format("%.3f", halfFovXmm),
                    String.format("%.3f", dy), String.format("%.3f", halfFovYmm));
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
        return feedCount > 0;
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

    public int getTrayCountCols() {
        return trayCountCols;
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

    public double getComponentRotationInTray() {
        return componentRotationInTray;
    }

    public void setComponentRotationInTray(double componentRotationInTray) {
        double oldValue = this.componentRotationInTray;
        this.componentRotationInTray = componentRotationInTray;
        firePropertyChange("componentRotationInTray", oldValue, componentRotationInTray);
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
     */
    @Override
    public boolean isPartHeightAbovePickLocation() {
        return true;
    }

    /**
     * Feeder-local pipeline accessors retained for compatibility and training.
     * Note that the feed() pre-align logic now prefers the Part's fiducial vision
     * pipeline and only falls back to these when no fiducial vision is configured.
     */
    public CvPipeline getPipeline() {
        return pipeline;
    }

    public void resetPipeline() {
        pipeline = createDefaultPipeline();
    }

    public CvPipeline getTrainingPipeline() {
        return trainingPipeline;
    }

    public void resetTrainingPipeline() {
        trainingPipeline = createDefaultTrainingPipeline();
    }

    public static CvPipeline createDefaultPipeline() {
        try (InputStream is = AdvancedLoosePartFeeder.class
                .getResourceAsStream("AdvancedLoosePartFeeder-DefaultPipeline.xml")) {
            if (is == null) {
                throw new Error("AdvancedLoosePartFeeder-DefaultPipeline.xml not found on classpath.");
            }
            String xml = IOUtils.toString(is, StandardCharsets.UTF_8);
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }

    public static CvPipeline createDefaultTrainingPipeline() {
        try (InputStream is = AdvancedLoosePartFeeder.class
                .getResourceAsStream("AdvancedLoosePartFeeder-DefaultTrainingPipeline.xml")) {
            if (is == null) {
                throw new Error("AdvancedLoosePartFeeder-DefaultTrainingPipeline.xml not found on classpath.");
            }
            String xml = IOUtils.toString(is, StandardCharsets.UTF_8);
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }
}
