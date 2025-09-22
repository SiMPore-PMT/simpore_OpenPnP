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
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner;
import org.openpnp.spi.MotionPlanner.CompletionType;
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
public class JEDEC_TrayFeeder extends ReferenceFeeder {

    /**
     * New -> From advancedLoosePartFeeder
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
    private int feedCount = 0;  // UI is base 1, 0 is ok because a pick operation always preceded by a feed, which increments feedCount to 1

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

        while(pickLocation == null || (feedCount <= (trayCountCols * trayCountRows))){
            //Inc feed count
            setFeedCount(getFeedCount() + 1);

            //Determine rough position of next part (pocket)
            Location nextPocket = getNextPocketLocation();

            //Pass pocket to function to get exact pick location
            pickLocation = locateFeederPart(nozzle, nextPocket);
            if (pickLocation == null) {
                //TODO: Add more handling for failed prealign
                Logger.warn("Pick {} at location [{}] not found!", getFeedCount(), nextPocket);
                //If we failed, we are incramenting by one and going to the next location.
            }
        }
        if (feedCount >= (trayCountCols * trayCountRows)) {
            throw new Exception(this.getName() + " (" + this.partId + ") is empty.");
        }
    }

    private Location getNextPocketLocation() {
        int feedCountBase0 = feedCount -1; // UI uses feedCount base 1, the following calculations are base 0

        // if feedCound is currently zero, assume its one
        // this can happen if the pickLocation is requested before any feed operation
        // return first location in that case
        if (feedCount == 0) {
            feedCountBase0 = 0;
        }
        // limit feed count to tray size
        else if (feedCount > (trayCountCols * trayCountRows)) {
            feedCountBase0 = trayCountCols * trayCountRows -1;
            Logger.warn("{}.getPickLocation: feedCount larger then tray, limiting to maximum.", getName());
        }

        //The original version of this feeder fed along either the rows or columns depending on
        //which was shorter. This version now feeds along a row until it is empty and then it moves
        //to the next row. However, if an old version of the feeder was just loaded and it was
        //partially completed (not completely full or completely empty), the picking order will
        //revert to the legacy method until the feed count is reset to 0.
        int colNum, rowNum;
        if (legacyPickingInProgress && (trayCountCols >= trayCountRows)) {
            //Pick parts along a column (stepping through all the rows) until it is empty and then
            //move to the next column
            rowNum = feedCountBase0 % trayCountRows;
            colNum = feedCountBase0 / trayCountRows;
        } else {
            //Pick parts along a row (stepping through all the columns) until it is empty and then
            //move to the next row (the new default)
            rowNum = feedCountBase0 / trayCountCols;
            colNum = feedCountBase0 % trayCountCols;
        }

        //The definition of the tray has row numbers increasing in the negative y direction so that
        //is why we negate rowNum here:
        Location delta = offsets.multiply(colNum, -rowNum, 0, 0).rotateXy(location.getRotation()).
                derive(null, null, null, componentRotationInTray);

        return location.addWithRotation(delta);
    }

    /**
     * Executes the vision pipeline to locate a part.
     * @param nozzle used nozzle
     * @return location or null
     * @throws Exception something went wrong
     */
    private Location locateFeederPart(Nozzle nozzle, Location startPoint) throws Exception {
        Camera camera = nozzle.getHead().getDefaultCamera();
        MovableUtils.moveToLocationAtSafeZ(camera, startPoint);
        camera.waitForCompletion(MotionPlanner.CompletionType.WaitForStillstand);
        try (CvPipeline pipeline = getPipeline()) {
            // Process the pipeline to extract RotatedRect results
            pipeline.setProperty("camera", camera);
            pipeline.setProperty("nozzle", nozzle);
            pipeline.setProperty("feeder", this);
            pipeline.process();
            // Grab the results
            List<RotatedRect> results = (List<RotatedRect>) pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME).model;
            if ((results == null) || results.isEmpty()) {
                //nothing found
                return null;
            }
            // Find the closest result
            results.sort((a, b) -> {
                Double da = VisionUtils.getPixelLocation(camera, a.center.x, a.center.y)
                        .getLinearDistanceTo(camera.getLocation());
                Double db = VisionUtils.getPixelLocation(camera, b.center.x, b.center.y)
                        .getLinearDistanceTo(camera.getLocation());
                return da.compareTo(db);
            });
            RotatedRect result = results.get(0);
            Location partLocation = VisionUtils.getPixelLocation(camera, result.center.x, result.center.y);
            // Get the result's Location
            // Update the location with the result's rotation
            partLocation = partLocation.derive(null, null, null, -(result.angle + getLocation().getRotation()));
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
     * Checks if the testLocation is inside the camera view starting on the feeder location.
     * Avoids to run outside the initial area if a bad pipeline repeated detects the parts
     * on one edge of the field of view, even after moving the camera to the location.
     * @param camera the used camera
     * @param testLocation the location to test
     * @return the testLocation, or null if outside the initial field of view
     */
    private Location checkIfInInitialView(Camera camera, Location testLocation) {
        // just make sure, the vision did not "run away" => outside of the initial camera range
        // should never happen, but with badly dialed in pipelines ...
        double distanceX = Math.abs(this.location.convertToUnits(LengthUnit.Millimeters).getX() - testLocation.convertToUnits(LengthUnit.Millimeters).getX());
        double distanceY = Math.abs(this.location.convertToUnits(LengthUnit.Millimeters).getY() - testLocation.convertToUnits(LengthUnit.Millimeters).getY());

        // if moved more than the half of the camera picture size => something went wrong => return no result
        if (distanceX > camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters).getX() * camera.getWidth() / 2
                || distanceY > camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters).getY() * camera.getHeight() / 2) {
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
     * @return
     */
    @Override
    public boolean isPartHeightAbovePickLocation() {
        return true;
    }

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
        try {
            String xml = IOUtils.toString(AdvancedLoosePartFeeder.class
                    .getResource("AdvancedLoosePartFeeder-DefaultPipeline.xml"));
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }

    public static CvPipeline createDefaultTrainingPipeline() {
        try {
            String xml = IOUtils.toString(AdvancedLoosePartFeeder.class
                    .getResource("AdvancedLoosePartFeeder-DefaultTrainingPipeline.xml"));
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }

}
