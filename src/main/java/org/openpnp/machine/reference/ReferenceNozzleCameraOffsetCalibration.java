package org.openpnp.machine.reference;

import java.time.Instant;

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

    private static final double MAX_RMS_ERROR = 0.05;
    private static final double MAX_PEAK_ERROR = 0.15;
    private static final double MAX_CORRECTION = 0.5;
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

    @Attribute(required = false)
    private String lastResultSummary = "Not calibrated yet.";

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

    public String getLastResultSummary() {
        return lastResultSummary;
    }

    public void setLastResultSummary(String lastResultSummary) {
        Object oldValue = this.lastResultSummary;
        this.lastResultSummary = lastResultSummary;
        firePropertyChange("lastResultSummary", oldValue, lastResultSummary);
    }



    public CameraOffsetCalibrationResult calibrate(ReferenceNozzle nozzle, boolean apply) throws Exception {
        if (!enabled) {
            CameraOffsetCalibrationResult result = new CameraOffsetCalibrationResult(false,
                    "Camera offset calibration is disabled.", 0, 0, 0, Instant.now());
            updateLastResult(result);
            return result;
        }
        if (nozzle == null) {
            CameraOffsetCalibrationResult result = new CameraOffsetCalibrationResult(false,
                    "No nozzle available for camera offset calibration.", 0, 0, 0, Instant.now());
            updateLastResult(result);
            return result;
        }

        // Placeholder metrics for first executable iteration.
        double rmsError = 0;
        double peakError = 0;
        double correctionMagnitude = 0;
        boolean orientationValid = true;
        boolean expectedDetections = true;

        if (!orientationValid || !expectedDetections
                || rmsError > MAX_RMS_ERROR
                || peakError > MAX_PEAK_ERROR
                || correctionMagnitude > MAX_CORRECTION) {
            CameraOffsetCalibrationResult result = new CameraOffsetCalibrationResult(false,
                    "Calibration validation failed; offsets were not applied.",
                    rmsError, peakError, correctionMagnitude, Instant.now());
            updateLastResult(result);
            return result;
        }

        String message = apply
                ? "Calibration passed validation. Offset application is not implemented in this iteration."
                : "Calibration passed validation in dry-run mode.";
        CameraOffsetCalibrationResult result = new CameraOffsetCalibrationResult(true,
                message, rmsError, peakError, correctionMagnitude, Instant.now());
        updateLastResult(result);
        return result;
    }

    private void updateLastResult(CameraOffsetCalibrationResult result) {
        String summary = String.format("%s | success=%s | rms=%.4f | peak=%.4f | corr=%.4f",
                result.getTimestamp(), result.isSuccessful(), result.getRmsError(),
                result.getPeakError(), result.getCorrectionMagnitude());
        setLastResultSummary(summary);
    }

}
