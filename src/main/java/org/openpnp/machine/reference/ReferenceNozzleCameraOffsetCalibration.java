package org.openpnp.machine.reference;

import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.vision.pipeline.CvPipeline;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

/**
 * Nozzle-scoped settings for precision, pipeline-driven camera offset calibration.
 */
public class ReferenceNozzleCameraOffsetCalibration extends AbstractModelObject {
    public enum RecalibrationTrigger {
        Manual, MachineHome
    }

    @Attribute(required = false)
    private boolean enabled;

    @Attribute(required = false)
    private RecalibrationTrigger recalibrationTrigger = RecalibrationTrigger.Manual;

    @Attribute(required = false)
    private boolean failHoming = true;

    @Element(required = false)
    private Location calibrationLocation = new Location(LengthUnit.Millimeters);

    @Attribute(required = false)
    private String calibrationPartId;

    @Element(required = false)
    private CvPipeline pipeline;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        Object oldValue = this.enabled;
        this.enabled = enabled;
        firePropertyChange("enabled", oldValue, enabled);
    }

    public RecalibrationTrigger getRecalibrationTrigger() {
        return recalibrationTrigger;
    }

    public void setRecalibrationTrigger(RecalibrationTrigger recalibrationTrigger) {
        Object oldValue = this.recalibrationTrigger;
        this.recalibrationTrigger = recalibrationTrigger;
        firePropertyChange("recalibrationTrigger", oldValue, recalibrationTrigger);
    }

    public boolean isFailHoming() {
        return failHoming;
    }

    public void setFailHoming(boolean failHoming) {
        Object oldValue = this.failHoming;
        this.failHoming = failHoming;
        firePropertyChange("failHoming", oldValue, failHoming);
    }


    public Location getCalibrationLocation() {
        return calibrationLocation;
    }

    public void setCalibrationLocation(Location calibrationLocation) {
        Object oldValue = this.calibrationLocation;
        this.calibrationLocation = calibrationLocation;
        firePropertyChange("calibrationLocation", oldValue, calibrationLocation);
    }

    public String getCalibrationPartId() {
        return calibrationPartId;
    }

    public void setCalibrationPartId(String calibrationPartId) {
        Object oldValue = this.calibrationPartId;
        this.calibrationPartId = calibrationPartId;
        firePropertyChange("calibrationPartId", oldValue, calibrationPartId);
    }

    public Part getCalibrationPart() {
        return org.openpnp.model.Configuration.get().getPart(calibrationPartId);
    }

    public void setCalibrationPart(Part calibrationPart) {
        setCalibrationPartId(calibrationPart == null ? null : calibrationPart.getId());
        firePropertyChange("calibrationPart", null, calibrationPart);
    }

    public CvPipeline getPipeline() {
        if (pipeline == null) {
            pipeline = ReferenceNozzleTipCalibration.createDefaultPipeline();
        }
        return pipeline;
    }

    public void setPipeline(CvPipeline pipeline) {
        Object oldValue = this.pipeline;
        this.pipeline = pipeline;
        firePropertyChange("pipeline", oldValue, pipeline);
    }

}
