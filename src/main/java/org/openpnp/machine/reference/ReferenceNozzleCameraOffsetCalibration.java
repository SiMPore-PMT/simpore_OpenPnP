package org.openpnp.machine.reference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.opencv.core.KeyPoint;
import org.opencv.core.RotatedRect;
import org.openpnp.gui.MainFrame;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

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

    public CameraOffsetCalibrationResult calibrate(ReferenceNozzle nozzle, boolean apply) throws Exception {
        if (!enabled) {
            return finish(new CameraOffsetCalibrationResult(false, "Camera offset calibration is disabled.", 0, 0, 0, Instant.now()));
        }
        Camera camera = VisionUtils.getBottomVisionCamera();
        if (camera == null) {
            return finish(new CameraOffsetCalibrationResult(false, "No bottom vision camera configured.", 0, 0, 0, Instant.now()));
        }
        if (calibrationLocation == null || !calibrationLocation.isInitialized()) {
            return finish(new CameraOffsetCalibrationResult(false, "Calibration location is not configured.", 0, 0, 0, Instant.now()));
        }

        MovableUtils.moveToLocationAtSafeZ(nozzle, calibrationLocation);
        nozzle.moveTo(calibrationLocation.derive(null, null, null, null));

        List<Location> offsets = new ArrayList<>();
        try (CvPipeline p = getPipeline()) {
            p.setProperty("camera", camera);
            p.setProperty("nozzle", nozzle);
            p.setProperty("part", getCalibrationPart());
            p.process();

            List<?> results = p.getExpectedResult(VisionUtils.PIPELINE_RESULTS_NAME).getExpectedModel(List.class);
            for (Object result : results) {
                if (result instanceof Result.Circle) {
                    Result.Circle circle = (Result.Circle) result;
                    offsets.add(VisionUtils.getPixelCenterOffsets(camera, circle.x, circle.y));
                }
                else if (result instanceof KeyPoint) {
                    KeyPoint keyPoint = (KeyPoint) result;
                    offsets.add(VisionUtils.getPixelCenterOffsets(camera, keyPoint.pt.x, keyPoint.pt.y));
                }
                else if (result instanceof RotatedRect) {
                    RotatedRect rect = (RotatedRect) result;
                    offsets.add(VisionUtils.getPixelCenterOffsets(camera, rect.center.x, rect.center.y));
                }
            }
            MainFrame mainFrame = MainFrame.get();
            if (mainFrame != null) {
                mainFrame.getCameraViews().getCameraView(camera).showFilteredImage(OpenCvUtils.toBufferedImage(p.getWorkingImage()), 1000);
            }
        }

        boolean expectedDetections = !offsets.isEmpty();
        boolean orientationValid = true;
        if (!expectedDetections) {
            return finish(new CameraOffsetCalibrationResult(false, "No expected calibration detections from pipeline.", 0, 0, 0, Instant.now()));
        }

        Location avg = offsets.get(0);
        for (int i = 1; i < offsets.size(); i++) {
            avg = avg.add(offsets.get(i));
        }
        avg = avg.multiply(1.0 / offsets.size());
        double sumError2 = 0;
        double peakError = 0;
        for (Location l : offsets) {
            double err = l.getLinearDistanceTo(avg);
            sumError2 += err * err;
            peakError = Math.max(peakError, err);
        }
        double rmsError = Math.sqrt(sumError2 / offsets.size());
        double correctionMagnitude = avg.getLinearDistanceTo(0, 0);

        if (!orientationValid || rmsError > MAX_RMS_ERROR || peakError > MAX_PEAK_ERROR || correctionMagnitude > MAX_CORRECTION) {
            return finish(new CameraOffsetCalibrationResult(false, "Calibration validation failed; offsets were not applied.",
                    rmsError, peakError, correctionMagnitude, Instant.now()));
        }

        if (apply) {
            Location corrected = nozzle.getHeadOffsets().add(avg);
            nozzle.setHeadOffsets(corrected.derive(null, null, null, null));
            Logger.info("Applied nozzle {} precision camera offset correction {} -> new headOffsets {}",
                    nozzle.getName(), avg, nozzle.getHeadOffsets());
        }
        String message = apply ? "Calibration passed validation and offsets were applied." : "Calibration passed validation in dry-run mode.";
        return finish(new CameraOffsetCalibrationResult(true, message, rmsError, peakError, correctionMagnitude, Instant.now()));
    }

    private CameraOffsetCalibrationResult finish(CameraOffsetCalibrationResult result) {
        setLastResultSummary(String.format("%s | success=%s | msg=%s | rms=%.4f | peak=%.4f | corr=%.4f",
                result.getTimestamp(), result.isSuccessful(), result.getMessage(), result.getRmsError(), result.getPeakError(), result.getCorrectionMagnitude()));
        return result;
    }

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
}
