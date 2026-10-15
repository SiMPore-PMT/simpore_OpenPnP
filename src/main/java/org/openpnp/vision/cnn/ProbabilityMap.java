package org.openpnp.vision.cnn;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

/** Immutable, native-resolution single-channel foreground probabilities. */
public final class ProbabilityMap {
    private final String modelId;
    private final String task;
    private final int width;
    private final int height;
    private final float[] probabilities;
    private final float defaultThreshold;

    public ProbabilityMap(String modelId, String task, int width, int height, float[] probabilities,
            float defaultThreshold) {
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("modelId must not be empty.");
        }
        if (task == null || task.trim().isEmpty()) {
            throw new IllegalArgumentException("task must not be empty.");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Probability map dimensions must be positive.");
        }
        long expectedLength = (long) width * height;
        if (probabilities == null || expectedLength != probabilities.length) {
            throw new IllegalArgumentException("Probability count must equal width * height.");
        }
        if (Float.isNaN(defaultThreshold) || Float.isInfinite(defaultThreshold)
                || defaultThreshold < 0 || defaultThreshold > 1) {
            throw new IllegalArgumentException("defaultThreshold must be between 0 and 1 inclusive.");
        }
        for (float probability : probabilities) {
            if (Float.isNaN(probability) || Float.isInfinite(probability)
                    || probability < 0 || probability > 1) {
                throw new IllegalArgumentException("Probabilities must be finite values from 0 to 1.");
            }
        }
        this.modelId = modelId;
        this.task = task;
        this.width = width;
        this.height = height;
        this.probabilities = probabilities.clone();
        this.defaultThreshold = defaultThreshold;
    }

    public String getModelId() { return modelId; }
    public String getTask() { return task; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public float getDefaultThreshold() { return defaultThreshold; }

    public float get(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("Probability coordinate outside map: " + x + "," + y);
        }
        return probabilities[y * width + x];
    }

    /** Return a defensive copy in row-major order. */
    public float[] copyProbabilities() {
        return probabilities.clone();
    }

    /**
     * Create a caller-owned CV_32FC1 matrix. The caller must release it when finished.
     */
    public Mat toMat() {
        Mat mat = new Mat(height, width, CvType.CV_32FC1);
        mat.put(0, 0, probabilities);
        return mat;
    }

    /**
     * Create a caller-owned CV_8UC1 binary mask using {@code probability >= threshold}. The caller
     * must release it when finished.
     */
    public Mat toMask(float threshold) {
        if (Float.isNaN(threshold) || Float.isInfinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be between 0 and 1 inclusive.");
        }
        byte[] mask = new byte[probabilities.length];
        for (int i = 0; i < probabilities.length; i++) {
            mask[i] = probabilities[i] >= threshold ? (byte) 255 : 0;
        }
        Mat mat = new Mat(height, width, CvType.CV_8UC1);
        mat.put(0, 0, mask);
        return mat;
    }

    @Override
    public String toString() {
        return "ProbabilityMap{" + modelId + ", task=" + task + ", size=" + width + "x" + height
                + ", values=" + probabilities.length + "}";
    }
}
