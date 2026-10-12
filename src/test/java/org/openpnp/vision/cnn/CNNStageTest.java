package org.openpnp.vision.cnn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.openpnp.model.Configuration;
import org.openpnp.vision.FluentCv.ColorSpace;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.TerminalException;
import org.openpnp.vision.pipeline.stages.CNN;
import org.openpnp.vision.pipeline.stages.ScriptRun;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;

class CNNStageTest {
    @TempDir
    Path directory;

    private Configuration configuration;

    @BeforeEach
    void installFixture() throws Exception {
        nu.pattern.OpenCV.loadLocally();
        Configuration.initialize(directory.toFile());
        configuration = Configuration.get();
        Path models = Files.createDirectories(directory.resolve("models"));
        try (InputStream source = getClass().getResourceAsStream("mean_channels_nchw_to_n1hw.onnx")) {
            assertNotNull(source);
            Files.copy(source, models.resolve("fixture.onnx"));
        }
        Files.writeString(models.resolve("fixture.json"), "{\"schemaVersion\":1,\"modelId\":\"fixture\","
                + "\"modelPath\":\"fixture.onnx\",\"task\":\"die\","
                + "\"input\":{\"name\":\"image\",\"layout\":\"NCHW\",\"dataType\":\"FLOAT32\","
                + "\"colorOrder\":\"RGB\",\"scale\":0.00392156862745098,\"mean\":[0.5,0.5,0.5],"
                + "\"std\":[1,1,1],\"resizeInterpolation\":\"AREA\",\"padding\":\"RIGHT_BOTTOM_REPLICATE\"},"
                + "\"output\":{\"name\":\"logits\",\"layout\":\"NCHW\",\"dataType\":\"FLOAT32\","
                + "\"interpretation\":\"LOGITS\",\"foregroundChannel\":0,\"resizeInterpolation\":\"LINEAR\"},"
                + "\"downsampleFactor\":1,\"inputAlignment\":8,\"defaultThreshold\":0.5}");
    }

    @AfterEach
    void disposeManager() throws Exception {
        CnnModelManager.close(configuration);
    }

    @Test
    void registrationSerializationAndCloneUseNativeStageConventions() throws Exception {
        assertTrue(CvPipelineEditor.getStageClasses().contains(CNN.class));
        CNN cnn = new CNN();
        cnn.setModelId("fixture");
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("cnn", cnn);
            try (CvPipeline clone = pipeline.clone()) {
                CNN restored = (CNN) clone.getStage("cnn");
                assertEquals("fixture", restored.getModelId());
            }
        }
        try (CvPipeline example = new CvPipeline(Files.readString(Path.of("samples/cnn/pipeline.xml")))) {
            assertTrue(example.getStage("cnn") instanceof CNN);
            assertTrue(example.getStage("results") instanceof ScriptRun);
        }
    }

    @Test
    void realCpuInferenceExposesOwnedNativeProbabilityMatAndPreservesCameraImage() throws Exception {
        Mat image = imageWithDie();
        Mat lastFrame = null;
        try (CvPipeline pipeline = pipeline(image)) {
            pipeline.process();
            Object model = pipeline.getExpectedResult("cnn").getModel();
            assertTrue(model instanceof Mat);
            Mat probabilities = (Mat) model;
            assertEquals(CvType.CV_32FC1, probabilities.type());
            assertEquals(36, probabilities.cols());
            assertEquals(24, probabilities.rows());
            assertEquals(1.0 / (1.0 + Math.exp(-0.5)), probabilities.get(11, 14)[0], 0.000001);
            float[] values = new float[36 * 24];
            probabilities.get(0, 0, values);
            for (float value : values) {
                assertTrue(Float.isFinite(value) && value >= 0 && value <= 1);
            }
            assertEquals(CvType.CV_8UC3, pipeline.getWorkingImage().type());
            assertArrayEquals(image.get(8, 11), pipeline.getWorkingImage().get(8, 11));
            ScriptRun preview = new ScriptRun();
            preview.setFile(new File("samples/cnn/mask-preview.java"));
            pipeline.add("preview", preview);
            pipeline.process();
            assertTrue(probabilities.empty(), "The previous frame model must be released on reprocess.");
            assertEquals(CvType.CV_8UC1, pipeline.getWorkingImage().type());
            assertEquals(255, pipeline.getWorkingImage().get(11, 14)[0]);
            lastFrame = (Mat) pipeline.getExpectedResult("cnn").getModel();
            assertEquals(CvType.CV_32FC1, lastFrame.type());
            assertFalse(lastFrame.empty(), "A downstream preview must not dispose its borrowed CNN model.");
        }
        finally {
            image.release();
        }
        assertNotNull(lastFrame);
        assertTrue(lastFrame.empty(), "Closing the pipeline must release its probability model.");
    }

    @Test
    void failedInferenceClearsOldResultsAndStopsDownstreamStages() throws Exception {
        Mat image = imageWithDie();
        AtomicInteger calls = new AtomicInteger();
        try (CvPipeline pipeline = pipeline(image)) {
            pipeline.add("results", new CvStage() {
                @Override
                public Result process(CvPipeline current) {
                    return new Result(null, new RotatedRect());
                }
            });
            pipeline.add("side-effect", new CvStage() {
                @Override
                public Result process(CvPipeline current) {
                    calls.incrementAndGet();
                    return null;
                }
            });
            pipeline.process();
            assertEquals(1, calls.get());
            ((CNN) pipeline.getStage("cnn")).setModelId("missing-model");
            assertThrows(Exception.class, pipeline::process);
            assertEquals(1, calls.get());
            assertNull(pipeline.getResult("results"));
            assertNull(pipeline.getResult("side-effect"));
            assertTrue(pipeline.getResult("cnn").model instanceof Exception);
        }
        finally {
            image.release();
        }
    }

    @Test
    void stageRejectsInferenceOnEdt() throws Exception {
        CNN cnn = new CNN();
        cnn.setModelId("fixture");
        try (CvPipeline pipeline = new CvPipeline()) {
            SwingUtilities.invokeAndWait(() -> assertThrows(TerminalException.class, () -> cnn.process(pipeline)));
        }
    }

    @Test
    void pipelineEditorRunsCnnAwayFromEdt() throws Exception {
        Mat image = imageWithDie();
        CountDownLatch interpreted = new CountDownLatch(1);
        try (CvPipeline pipeline = pipeline(image)) {
            pipeline.add("observe", new CvStage() {
                @Override
                public Result process(CvPipeline current) throws Exception {
                    if (SwingUtilities.isEventDispatchThread()) {
                        throw new TerminalException(new Exception("Inference ran on EDT."));
                    }
                    interpreted.countDown();
                    return null;
                }
            });
            SwingUtilities.invokeAndWait(() -> new CvPipelineEditor(pipeline));
            assertTrue(interpreted.await(10, TimeUnit.SECONDS));
            // Acquire the process monitor before inspecting or disposing worker-owned results.
            synchronized (pipeline) {
                assertTrue(pipeline.getExpectedResult("cnn").getModel() instanceof Mat);
            }
        }
        finally {
            image.release();
        }
    }

    private CvPipeline pipeline(Mat image) {
        CvPipeline pipeline = new CvPipeline();
        pipeline.add("capture", new CvStage() {
            @Override
            public Result process(CvPipeline current) {
                return new Result(image.clone(), ColorSpace.Bgr);
            }
        });
        CNN cnn = new CNN();
        cnn.setModelId("fixture");
        pipeline.add("cnn", cnn);
        return pipeline;
    }

    private Mat imageWithDie() {
        Mat image = new Mat(24, 36, CvType.CV_8UC3, Scalar.all(0));
        Mat die = image.submat(new Rect(10, 7, 10, 10));
        try {
            die.setTo(Scalar.all(255));
        }
        finally {
            die.release();
        }
        return image;
    }
}
