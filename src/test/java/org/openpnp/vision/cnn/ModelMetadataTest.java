package org.openpnp.vision.cnn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class ModelMetadataTest {
    @Test
    public void readsValidatedMetadataAndResolvesModelBesideJson() throws Exception {
        Path dir = Files.createTempDirectory("cnn-model-metadata");
        try {
            Path model = Files.write(dir.resolve("segmentation.onnx"), new byte[] { 1, 2, 3 });
            Path json = writeMetadata(dir, "segmentation.onnx", null);

            ModelMetadata metadata = ModelMetadata.read(json);

            assertEquals(1, metadata.getSchemaVersion());
            assertEquals("die-segmentation", metadata.getModelId());
            assertEquals("die", metadata.getTask());
            assertEquals(model.toRealPath(), metadata.getModelPath());
            assertEquals("input", metadata.getInputName());
            assertEquals("output", metadata.getOutputName());
            assertEquals(2, metadata.getDownsampleFactor());
            assertEquals(4, metadata.getInputAlignment());
            assertEquals(0.5f, metadata.getDefaultThreshold(), 0.00001f);
            double[] mean = metadata.getMean();
            mean[0] = 99;
            assertArrayEquals(new double[] { 0.1, 0.2, 0.3 }, metadata.getMean(), 0.000001);
        }
        finally {
            deleteTree(dir);
        }
    }

    @Test
    public void rejectsUnsupportedVersionAndMissingPreprocessingFields() throws Exception {
        Path dir = Files.createTempDirectory("cnn-model-metadata");
        try {
            Files.write(dir.resolve("segmentation.onnx"), new byte[] { 1 });
            Path version = writeMetadata(dir, "segmentation.onnx", "\"schemaVersion\":2,");
            assertThrows(IOException.class, () -> ModelMetadata.read(version));

            String missing = validJson("segmentation.onnx").replace("\"std\":[1,1,1],", "");
            Path json = Files.write(dir.resolve("missing.json"), missing.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> ModelMetadata.read(json));
        }
        finally {
            deleteTree(dir);
        }
    }

    @Test
    public void rejectsInvalidNormalizationAndEscapingModelPath() throws Exception {
        Path dir = Files.createTempDirectory("cnn-model-metadata");
        try {
            Files.write(dir.resolve("segmentation.onnx"), new byte[] { 1 });
            Path invalidStd = Files.write(dir.resolve("std.json"),
                    validJson("segmentation.onnx").replace("\"std\":[1,1,1]", "\"std\":[1,0,1]")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> ModelMetadata.read(invalidStd));

            Path traversal = writeMetadata(dir, "../segmentation.onnx", null);
            assertThrows(IOException.class, () -> ModelMetadata.read(traversal));

            Path absolute = writeMetadata(dir, dir.resolve("segmentation.onnx").toString(), null);
            assertThrows(IOException.class, () -> ModelMetadata.read(absolute));
        }
        finally {
            deleteTree(dir);
        }
    }

    @Test
    public void rejectsUnsupportedTensorInterpretationAndMultichannelOutput() throws Exception {
        Path dir = Files.createTempDirectory("cnn-model-metadata");
        try {
            Files.write(dir.resolve("segmentation.onnx"), new byte[] { 1 });
            String invalid = validJson("segmentation.onnx").replace("\"interpretation\":\"LOGITS\"",
                    "\"interpretation\":\"SOFTMAX\"");
            Path json = Files.write(dir.resolve("invalid.json"),
                    invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> ModelMetadata.read(json));

            String multichannel = validJson("segmentation.onnx").replace("\"foregroundChannel\":0",
                    "\"foregroundChannel\":1");
            Path multichannelJson = Files.write(dir.resolve("multichannel.json"),
                    multichannel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> ModelMetadata.read(multichannelJson));
        }
        finally {
            deleteTree(dir);
        }
    }

    @Test
    public void acceptsDeclaredProbabilityOutputSemantics() throws Exception {
        Path dir = Files.createTempDirectory("cnn-model-metadata");
        try {
            Files.write(dir.resolve("segmentation.onnx"), new byte[] { 1 });
            String json = validJson("segmentation.onnx").replace("\"interpretation\":\"LOGITS\"",
                    "\"interpretation\":\"PROBABILITIES\"");
            Path metadataPath = Files.write(dir.resolve("probabilities.json"),
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ModelMetadata metadata = ModelMetadata.read(metadataPath);
            assertEquals(ModelMetadata.OUTPUT_PROBABILITIES, metadata.getOutputInterpretation());
            org.junit.jupiter.api.Assertions.assertFalse(metadata.isOutputLogits());
        }
        finally {
            deleteTree(dir);
        }
    }

    @Test
    public void rejectsNonIntegerAndStringEncodedDimensionFields() throws Exception {
        Path dir = Files.createTempDirectory("cnn-model-metadata");
        try {
            Files.write(dir.resolve("segmentation.onnx"), new byte[] { 1 });
            String decimal = validJson("segmentation.onnx").replace("\"downsampleFactor\":2",
                    "\"downsampleFactor\":2.0");
            Path decimalJson = Files.write(dir.resolve("decimal.json"),
                    decimal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> ModelMetadata.read(decimalJson));

            String string = validJson("segmentation.onnx").replace("\"inputAlignment\":4",
                    "\"inputAlignment\":\"4\"");
            Path stringJson = Files.write(dir.resolve("string.json"),
                    string.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> ModelMetadata.read(stringJson));
        }
        finally {
            deleteTree(dir);
        }
    }

    @Test
    public void rejectsMetadataSymlinkToAnotherDirectory() throws Exception {
        Path models = Files.createTempDirectory("cnn-model-metadata");
        Path outside = Files.createTempDirectory("cnn-model-metadata-outside");
        try {
            Files.write(outside.resolve("segmentation.onnx"), new byte[] { 1 });
            Path externalMetadata = writeMetadata(outside, "segmentation.onnx", null);
            Path link;
            try {
                link = Files.createSymbolicLink(models.resolve("linked.json"), externalMetadata);
            }
            catch (UnsupportedOperationException | IOException e) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable.");
                return;
            }
            assertThrows(IOException.class, () -> ModelMetadata.read(link));
        }
        finally {
            deleteTree(models);
            deleteTree(outside);
        }
    }

    @Test
    public void rejectsModelAssetSymlinkEvenWhenTargetStaysInsideDirectory() throws Exception {
        Path dir = Files.createTempDirectory("cnn-model-metadata");
        try {
            Files.write(dir.resolve("real.onnx"), new byte[] { 1 });
            try {
                Files.createSymbolicLink(dir.resolve("alias.onnx"), dir.resolve("real.onnx"));
            }
            catch (UnsupportedOperationException | IOException e) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable.");
                return;
            }
            Path metadata = writeMetadata(dir, "alias.onnx", null);
            assertThrows(IOException.class, () -> ModelMetadata.read(metadata));
        }
        finally {
            deleteTree(dir);
        }
    }

    private static Path writeMetadata(Path dir, String modelPath, String versionOverride) throws IOException {
        String json = validJson(modelPath);
        if (versionOverride != null) {
            json = json.replace("\"schemaVersion\":1,", versionOverride);
        }
        return Files.write(dir.resolve("model.json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String validJson(String modelPath) {
        return "{\"schemaVersion\":1,\"modelId\":\"die-segmentation\","
                + "\"modelPath\":\"" + modelPath + "\",\"task\":\"die\","
                + "\"input\":{\"name\":\"input\",\"layout\":\"NCHW\",\"dataType\":\"FLOAT32\","
                + "\"colorOrder\":\"RGB\",\"scale\":0.00392156862745098,"
                + "\"mean\":[0.1,0.2,0.3],\"std\":[1,1,1],\"resizeInterpolation\":\"AREA\","
                + "\"padding\":\"RIGHT_BOTTOM_REPLICATE\"},"
                + "\"output\":{\"name\":\"output\",\"layout\":\"NCHW\",\"dataType\":\"FLOAT32\","
                + "\"interpretation\":\"LOGITS\",\"foregroundChannel\":0,"
                + "\"resizeInterpolation\":\"LINEAR\"},"
                + "\"downsampleFactor\":2,\"inputAlignment\":4,\"defaultThreshold\":0.5}";
    }

    private static void deleteTree(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
}
