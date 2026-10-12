package org.openpnp.vision.cnn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.openpnp.vision.FluentCv.ColorSpace;

public class ModelPreprocessorTest {
    @BeforeAll
    public static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    public void preparesNchwRgbWithTiesToEvenResizeAndRightBottomPadding() throws Exception {
        Path dir = Files.createTempDirectory("cnn-preprocessor");
        Mat image = new Mat(3, 5, CvType.CV_8UC3);
        try {
            Path model = Files.write(dir.resolve("test.onnx"), new byte[] { 1 });
            ModelMetadata metadata = ModelMetadata.read(writeMetadata(dir, model.getFileName().toString()));
            byte[] bgr = new byte[3 * 5 * 3];
            for (int i = 0; i < 15; i++) {
                bgr[i * 3] = 10;
                bgr[i * 3 + 1] = 20;
                bgr[i * 3 + 2] = 30;
            }
            image.put(0, 0, bgr);

            ModelPreprocessor.PreparedInput prepared = ModelPreprocessor.prepare(image, ColorSpace.Bgr, metadata);

            // 5/2 = 2.5 and 3/2 = 1.5 round to even: 2x2; align to 4x4.
            assertArrayEquals(new int[] { 1, 3, 4, 4 }, prepared.getShape());
            assertEquals(5, prepared.getNativeWidth());
            assertEquals(3, prepared.getNativeHeight());
            assertEquals(2, prepared.getResizedWidth());
            assertEquals(2, prepared.getResizedHeight());
            float[] data = prepared.getData();
            assertEquals((float) ((30 * metadata.getInputScale() - 0.1) / 1), data[0], 0.00001f);
            assertEquals((float) ((20 * metadata.getInputScale() - 0.2) / 1), data[16], 0.00001f);
            assertEquals((float) ((10 * metadata.getInputScale() - 0.3) / 1), data[32], 0.00001f);
        }
        finally {
            image.release();
            deleteTree(dir);
        }
    }

    @Test
    public void restoresSigmoidProbabilityToNativeResolutionAndExposesOwnedMatrices() throws Exception {
        Path dir = Files.createTempDirectory("cnn-preprocessor");
        Mat image = new Mat(3, 5, CvType.CV_8UC3);
        try {
            Path model = Files.write(dir.resolve("test.onnx"), new byte[] { 1 });
            ModelMetadata metadata = ModelMetadata.read(writeMetadata(dir, model.getFileName().toString()));
            ModelPreprocessor.PreparedInput prepared = ModelPreprocessor.prepare(image, ColorSpace.Bgr, metadata);
            ProbabilityMap map = ModelPreprocessor.restore(new float[16], prepared, metadata);

            assertEquals(5, map.getWidth());
            assertEquals(3, map.getHeight());
            assertEquals(0.5f, map.get(2, 1), 0.00001f);
            float[] copy = map.copyProbabilities();
            copy[0] = 0;
            assertEquals(0.5f, map.get(0, 0), 0.00001f);
            Mat probabilities = map.toMat();
            Mat mask = map.toMask(0.5f);
            try {
                assertEquals(CvType.CV_32FC1, probabilities.type());
                assertEquals(CvType.CV_8UC1, mask.type());
                assertEquals(0.5f, (float) probabilities.get(0, 0)[0], 0.00001f);
                assertEquals(255, (int) mask.get(0, 0)[0]);
            }
            finally {
                probabilities.release();
                mask.release();
            }
        }
        finally {
            image.release();
            deleteTree(dir);
        }
    }

    @Test
    public void preservesDeclaredProbabilityOutputWithoutApplyingSigmoid() throws Exception {
        Path dir = Files.createTempDirectory("cnn-preprocessor");
        Mat image = new Mat(3, 5, CvType.CV_8UC3);
        try {
            Path model = Files.write(dir.resolve("test.onnx"), new byte[] { 1 });
            ModelMetadata logitsMetadata = ModelMetadata.read(writeMetadata(dir, model.getFileName().toString()));
            String probabilityJson = new String(Files.readAllBytes(logitsMetadata.getMetadataPath()),
                    java.nio.charset.StandardCharsets.UTF_8).replace("\"interpretation\":\"LOGITS\"",
                            "\"interpretation\":\"PROBABILITIES\"");
            Path probabilityMetadataPath = Files.write(dir.resolve("probabilities.json"),
                    probabilityJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ModelMetadata probabilityMetadata = ModelMetadata.read(probabilityMetadataPath);
            ModelPreprocessor.PreparedInput prepared = ModelPreprocessor.prepare(image, ColorSpace.Bgr,
                    probabilityMetadata);

            float[] output = new float[16];
            java.util.Arrays.fill(output, 0.25f);
            ProbabilityMap map = ModelPreprocessor.restore(output, prepared, probabilityMetadata);
            assertEquals(0.25f, map.get(2, 1), 0.00001f);
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPreprocessor.restore(new float[] { 1.5f, 1.5f, 1.5f, 1.5f,
                            1.5f, 1.5f, 1.5f, 1.5f, 1.5f, 1.5f, 1.5f, 1.5f, 1.5f, 1.5f,
                            1.5f, 1.5f }, prepared, probabilityMetadata));
        }
        finally {
            image.release();
            deleteTree(dir);
        }
    }

    @Test
    public void downsampleAndRightBottomPaddingPreserveCenterInNonSquareImage() throws Exception {
        Path dir = Files.createTempDirectory("cnn-preprocessor");
        Mat image = new Mat(13, 19, CvType.CV_8UC3);
        try {
            Path model = Files.write(dir.resolve("test.onnx"), new byte[] { 1 });
            String json = new String(Files.readAllBytes(writeMetadata(dir, model.getFileName().toString())),
                    java.nio.charset.StandardCharsets.UTF_8).replace("\"downsampleFactor\":2",
                            "\"downsampleFactor\":4");
            Path metadataPath = Files.write(dir.resolve("downsampled.json"),
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ModelMetadata metadata = ModelMetadata.read(metadataPath);
            ModelPreprocessor.PreparedInput prepared = ModelPreprocessor.prepare(image, ColorSpace.Bgr, metadata);

            // The 19x13 image downsamples to 5x3 then pads to 8x4. A feature at model pixel
            // (2, 1) maps to the native image center at (9, 6); high padded logits must be cropped.
            float[] logits = new float[prepared.getPaddedWidth() * prepared.getPaddedHeight()];
            java.util.Arrays.fill(logits, 10f);
            for (int y = 0; y < prepared.getResizedHeight(); y++) {
                for (int x = 0; x < prepared.getResizedWidth(); x++) {
                    logits[y * prepared.getPaddedWidth() + x] = -10f;
                }
            }
            logits[prepared.getPaddedWidth() + 2] = 10f;
            ProbabilityMap map = ModelPreprocessor.restore(logits, prepared, metadata);
            int bestX = 0;
            int bestY = 0;
            float best = -1;
            for (int y = 0; y < map.getHeight(); y++) {
                for (int x = 0; x < map.getWidth(); x++) {
                    if (map.get(x, y) > best) {
                        best = map.get(x, y);
                        bestX = x;
                        bestY = y;
                    }
                }
            }
            assertEquals(9, bestX);
            assertEquals(6, bestY);
        }
        finally {
            image.release();
            deleteTree(dir);
        }
    }

    @Test
    public void rejectsUnexpectedOutputDimensionsAndUnsupportedImageType() throws Exception {
        Path dir = Files.createTempDirectory("cnn-preprocessor");
        Mat image = new Mat(3, 5, CvType.CV_8UC3);
        Mat gray = new Mat(3, 5, CvType.CV_8UC1);
        try {
            Path model = Files.write(dir.resolve("test.onnx"), new byte[] { 1 });
            ModelMetadata metadata = ModelMetadata.read(writeMetadata(dir, model.getFileName().toString()));
            ModelPreprocessor.PreparedInput prepared = ModelPreprocessor.prepare(image, ColorSpace.Bgr, metadata);
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPreprocessor.restore(new float[15], prepared, metadata));
            assertThrows(IllegalArgumentException.class,
                    () -> ModelPreprocessor.prepare(gray, ColorSpace.Gray, metadata));
        }
        finally {
            image.release();
            gray.release();
            deleteTree(dir);
        }
    }

    @Test
    public void rejectsNormalizationOutsideFloatTensorRange() throws Exception {
        Path dir = Files.createTempDirectory("cnn-preprocessor");
        Mat image = new Mat(3, 5, CvType.CV_8UC3);
        try {
            Path model = Files.write(dir.resolve("test.onnx"), new byte[] { 1 });
            String json = new String(Files.readAllBytes(writeMetadata(dir, model.getFileName().toString())),
                    java.nio.charset.StandardCharsets.UTF_8).replace("\"std\":[1,1,1]",
                            "\"std\":[1e-300,1,1]");
            Path metadataPath = Files.write(dir.resolve("tiny-std.json"),
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ModelMetadata metadata = ModelMetadata.read(metadataPath);
            IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelPreprocessor.prepare(image, ColorSpace.Bgr, metadata));
            org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("finite FLOAT32 range"));
        }
        finally {
            image.release();
            deleteTree(dir);
        }
    }

    private static Path writeMetadata(Path dir, String modelName) throws Exception {
        String json = "{\"schemaVersion\":1,\"modelId\":\"test\",\"modelPath\":\"" + modelName
                + "\",\"task\":\"die\",\"input\":{\"name\":\"x\",\"layout\":\"NCHW\","
                + "\"dataType\":\"FLOAT32\",\"colorOrder\":\"RGB\",\"scale\":0.00392156862745098,"
                + "\"mean\":[0.1,0.2,0.3],\"std\":[1,1,1],\"resizeInterpolation\":\"AREA\","
                + "\"padding\":\"RIGHT_BOTTOM_REPLICATE\"},\"output\":{\"name\":\"y\","
                + "\"layout\":\"NCHW\",\"dataType\":\"FLOAT32\",\"interpretation\":\"LOGITS\","
                + "\"foregroundChannel\":0,\"resizeInterpolation\":\"LINEAR\"},"
                + "\"downsampleFactor\":2,\"inputAlignment\":4,\"defaultThreshold\":0.5}";
        return Files.write(dir.resolve("model.json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void deleteTree(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
