package org.openpnp.machine.reference;

import java.time.Instant;

public class CameraOffsetCalibrationResult {
    private final boolean successful;
    private final String message;
    private final double rmsError;
    private final double peakError;
    private final double correctionMagnitude;
    private final Instant timestamp;

    public CameraOffsetCalibrationResult(boolean successful, String message, double rmsError,
            double peakError, double correctionMagnitude, Instant timestamp) {
        this.successful = successful;
        this.message = message;
        this.rmsError = rmsError;
        this.peakError = peakError;
        this.correctionMagnitude = correctionMagnitude;
        this.timestamp = timestamp;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getMessage() {
        return message;
    }

    public double getRmsError() {
        return rmsError;
    }

    public double getPeakError() {
        return peakError;
    }

    public double getCorrectionMagnitude() {
        return correctionMagnitude;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
