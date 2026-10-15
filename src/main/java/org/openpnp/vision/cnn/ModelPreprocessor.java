package org.openpnp.vision.cnn;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.openpnp.vision.FluentCv.ColorSpace;
import org.opencv.imgproc.Imgproc;

/** Applies the explicitly declared schema-v1 image transform for an ONNX segmentation model. */
public final class ModelPreprocessor {
    private ModelPreprocessor() {
    }

    /**
     * Resize, right/bottom replicate-pad, reorder channels and normalize an 8-bit three-channel
     * image into an owned NCHW float array. No matrix is retained by the result.
     */
    public static PreparedInput prepare(Mat image, ColorSpace colorSpace, ModelMetadata metadata)
            throws Exception {
        if (image == null || image.empty()) {
            throw new IllegalArgumentException("CNN input image must not be empty.");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Model metadata must be specified.");
        }
        if (image.depth() != CvType.CV_8U || image.channels() != 3) {
            throw new IllegalArgumentException("CNN input must be an 8-bit, three-channel image.");
        }
        if (colorSpace != ColorSpace.Bgr && colorSpace != ColorSpace.Rgb) {
            throw new IllegalArgumentException("CNN input color space must be RGB or BGR.");
        }

        int nativeWidth = image.cols();
        int nativeHeight = image.rows();
        int resizeWidth = roundedDimension(nativeWidth, metadata.getDownsampleFactor());
        int resizeHeight = roundedDimension(nativeHeight, metadata.getDownsampleFactor());
        int paddedWidth = alignedDimension(resizeWidth, metadata.getInputAlignment());
        int paddedHeight = alignedDimension(resizeHeight, metadata.getInputAlignment());

        Mat resized = new Mat();
        Mat padded = new Mat();
        try {
            Imgproc.resize(image, resized, new Size(resizeWidth, resizeHeight), 0, 0, Imgproc.INTER_AREA);
            Core.copyMakeBorder(resized, padded, 0, paddedHeight - resizeHeight, 0,
                    paddedWidth - resizeWidth, Core.BORDER_REPLICATE);

            int valueCount = arrayLength(paddedWidth, paddedHeight, 3);
            byte[] pixels = new byte[valueCount];
            padded.get(0, 0, pixels);
            float[] nchw = new float[valueCount];
            double[] mean = metadata.getMean();
            double[] std = metadata.getStd();
            boolean sourceRgb = colorSpace == ColorSpace.Rgb;
            boolean wantedRgb = "RGB".equals(metadata.getColorOrder());
            int planeSize = paddedWidth * paddedHeight;
            for (int y = 0; y < paddedHeight; y++) {
                for (int x = 0; x < paddedWidth; x++) {
                    int pixelOffset = (y * paddedWidth + x) * 3;
                    for (int channel = 0; channel < 3; channel++) {
                        int sourceChannel = (sourceRgb == wantedRgb) ? channel : 2 - channel;
                        int unsigned = pixels[pixelOffset + sourceChannel] & 0xff;
                        int tensorIndex = channel * planeSize + y * paddedWidth + x;
                        double normalized = (unsigned * metadata.getInputScale() - mean[channel])
                                / std[channel];
                        float tensorValue = (float) normalized;
                        if (!Float.isFinite(tensorValue)) {
                            throw new IllegalArgumentException("Normalized CNN input is outside the finite "
                                    + "FLOAT32 range; check input scale, mean and std metadata.");
                        }
                        nchw[tensorIndex] = tensorValue;
                    }
                }
            }
            return new PreparedInput(nchw, nativeWidth, nativeHeight, resizeWidth, resizeHeight,
                    paddedWidth, paddedHeight);
        }
        finally {
            resized.release();
            padded.release();
        }
    }

    /** Convert foreground output to a native-resolution probability map. */
    public static ProbabilityMap restore(float[] output, PreparedInput input, ModelMetadata metadata)
            throws Exception {
        if (output == null || input == null || metadata == null) {
            throw new IllegalArgumentException("Model output, prepared input and metadata are required.");
        }
        long expected = (long) input.getPaddedWidth() * input.getPaddedHeight();
        if (expected != output.length) {
            throw new IllegalArgumentException("Expected " + expected + " output logits, got "
                    + output.length + ".");
        }
        float[] probabilities = new float[output.length];
        for (int i = 0; i < output.length; i++) {
            float outputValue = output[i];
            if (Float.isNaN(outputValue) || Float.isInfinite(outputValue)) {
                throw new IllegalArgumentException("Model output contains a non-finite value.");
            }
            if (metadata.isOutputLogits()) {
                if (outputValue >= 0) {
                    probabilities[i] = (float) (1.0 / (1.0 + Math.exp(-outputValue)));
                }
                else {
                    double exp = Math.exp(outputValue);
                    probabilities[i] = (float) (exp / (1.0 + exp));
                }
            }
            else {
                if (outputValue < 0 || outputValue > 1) {
                    throw new IllegalArgumentException("Probability output values must be between 0 and 1.");
                }
                probabilities[i] = outputValue;
            }
        }

        Mat paddedOutput = new Mat(input.getPaddedHeight(), input.getPaddedWidth(), CvType.CV_32FC1);
        Mat cropped = null;
        Mat nativeOutput = new Mat();
        try {
            paddedOutput.put(0, 0, probabilities);
            cropped = paddedOutput.submat(new Rect(0, 0, input.getResizedWidth(), input.getResizedHeight()));
            Imgproc.resize(cropped, nativeOutput,
                    new Size(input.getNativeWidth(), input.getNativeHeight()), 0, 0, Imgproc.INTER_LINEAR);
            float[] restored = new float[arrayLength(input.getNativeWidth(), input.getNativeHeight(), 1)];
            nativeOutput.get(0, 0, restored);
            return new ProbabilityMap(metadata.getModelId(), metadata.getTask(), input.getNativeWidth(),
                    input.getNativeHeight(), restored, metadata.getDefaultThreshold());
        }
        finally {
            paddedOutput.release();
            if (cropped != null) {
                cropped.release();
            }
            nativeOutput.release();
        }
    }

    private static int roundedDimension(int nativeSize, int factor) {
        // Python round() uses ties-to-even, matching Math.rint().
        return Math.max(1, (int) Math.rint(nativeSize / (double) factor));
    }

    private static int alignedDimension(int size, int alignment) {
        long aligned = ((long) size + alignment - 1) / alignment * alignment;
        if (aligned > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Padded image dimension is too large.");
        }
        return (int) aligned;
    }

    private static int arrayLength(int width, int height, int channels) {
        long length = (long) width * height * channels;
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Image tensor is too large.");
        }
        return (int) length;
    }

    /** A per-inference input tensor and the dimensions needed to restore its output. */
    public static final class PreparedInput {
        private final float[] data;
        private final int nativeWidth;
        private final int nativeHeight;
        private final int resizedWidth;
        private final int resizedHeight;
        private final int paddedWidth;
        private final int paddedHeight;

        private PreparedInput(float[] data, int nativeWidth, int nativeHeight, int resizedWidth,
                int resizedHeight, int paddedWidth, int paddedHeight) {
            this.data = data;
            this.nativeWidth = nativeWidth;
            this.nativeHeight = nativeHeight;
            this.resizedWidth = resizedWidth;
            this.resizedHeight = resizedHeight;
            this.paddedWidth = paddedWidth;
            this.paddedHeight = paddedHeight;
        }

        public float[] getData() { return data.clone(); }
        public int[] getShape() { return new int[] { 1, 3, paddedHeight, paddedWidth }; }
        public int getNativeWidth() { return nativeWidth; }
        public int getNativeHeight() { return nativeHeight; }
        public int getResizedWidth() { return resizedWidth; }
        public int getResizedHeight() { return resizedHeight; }
        public int getPaddedWidth() { return paddedWidth; }
        public int getPaddedHeight() { return paddedHeight; }
    }
}
