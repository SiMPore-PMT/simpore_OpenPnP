package org.openpnp.vision.cnn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.openpnp.vision.FluentCv.ColorSpace;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.CNN;
import org.openpnp.vision.pipeline.stages.ScriptRun;

class JythonInteropTest {
    @TempDir
    Path directory;

    @Test
    void jythonScriptReadsProbabilityMatAndReturnsOpenCvGeometry() throws Exception {
        nu.pattern.OpenCV.loadLocally();
        Mat cameraImage = new Mat(4, 6, CvType.CV_8UC3, new Scalar(11, 22, 33));
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("capture", new CvStage() {
                @Override
                public Result process(CvPipeline current) {
                    return new Result(cameraImage.clone(), ColorSpace.Bgr);
                }
            });
            pipeline.add("cnn", new CNN() {
                @Override
                public Result process(CvPipeline current) {
                    Mat probability = new Mat(4, 6, CvType.CV_32FC1, Scalar.all(0.8));
                    return new Result(null, current.ownResultModel(probability));
                }
            });
            ScriptRun script = new ScriptRun();
            script.setFile(fixtureFile("return_rectangle.py"));
            script.setArgs("threshold=0.7");
            pipeline.add("results", script);

            pipeline.process();

            Mat probability = pipeline.getExpectedResult("cnn").getExpectedModel(Mat.class);
            assertEquals(CvType.CV_32FC1, probability.type());
            assertEquals(6, probability.cols());
            assertEquals(4, probability.rows());
            assertEquals(0.8, probability.get(1, 2)[0], 0.0001);
            assertEquals(CvType.CV_8UC3, pipeline.getWorkingImage().type());
            assertEquals(6, pipeline.getWorkingImage().cols());
            assertEquals(4, pipeline.getWorkingImage().rows());
            assertArrayEquals(cameraImage.get(2, 3), pipeline.getWorkingImage().get(2, 3));

            RotatedRect rectangle = pipeline.getExpectedResult("results")
                    .getExpectedModel(RotatedRect.class);
            assertEquals(3.0, rectangle.center.x, 0.0001);
            assertEquals(2.0, rectangle.center.y, 0.0001);
            assertEquals(2.0, rectangle.size.width, 0.0001);
            assertEquals(1.0, rectangle.size.height, 0.0001);
            assertEquals(17.0, rectangle.angle, 0.0001);

            script.setName("square");
            ScriptRun rectangleList = new ScriptRun();
            rectangleList.setFile(new File("samples/cnn/rectangle-list.java"));
            pipeline.add("results", rectangleList);
            pipeline.process();
            List<?> rectangles = pipeline.getExpectedResult("results").getExpectedModel(List.class);
            assertEquals(1, rectangles.size());
            RotatedRect adapted = (RotatedRect) rectangles.get(0);
            assertEquals(rectangle.center.x, adapted.center.x, 0.0001);
            assertEquals(rectangle.center.y, adapted.center.y, 0.0001);
            assertEquals(rectangle.size.width, adapted.size.width, 0.0001);
            assertEquals(rectangle.size.height, adapted.size.height, 0.0001);
            assertEquals(rectangle.angle, adapted.angle, 0.0001);

            // The fixture's documented default threshold is 0.5.
            script.setArgs("");
            pipeline.process();
            assertTrue(pipeline.getExpectedResult("square").getModel() instanceof RotatedRect);
            assertEquals(1, pipeline.getExpectedResult("results").getExpectedModel(List.class).size());
        }
        finally {
            cameraImage.release();
        }
    }

    @Test
    void cnnDependentScriptFailuresStopDownstreamAndCannotReuseOldGeometry() throws Exception {
        nu.pattern.OpenCV.loadLocally();
        Mat cameraImage = new Mat(4, 6, CvType.CV_8UC3, new Scalar(11, 22, 33));
        AtomicInteger downstreamCalls = new AtomicInteger();
        try (CvPipeline pipeline = new CvPipeline()) {
            addDeterministicInput(pipeline, cameraImage);
            ScriptRun script = new ScriptRun();
            script.setFile(fixtureFile("return_rectangle.py"));
            script.setArgs("threshold=0.7");
            pipeline.add("results", script);
            pipeline.add("downstream", new CvStage() {
                @Override
                public Result process(CvPipeline current) {
                    downstreamCalls.incrementAndGet();
                    return null;
                }
            });

            pipeline.process();
            assertEquals(1, downstreamCalls.get());
            script.setArgs("threshold=0.9");
            assertThrows(Exception.class, pipeline::process);
            assertEquals(1, downstreamCalls.get());
            assertTrue(pipeline.getResult("results").getModel() instanceof Exception);
            assertTrue(pipeline.getResult("cnn").getModel() instanceof Mat);

            script.setFile(Path.of("target", "missing-cnn-interpreter.py").toFile());
            assertThrows(Exception.class, pipeline::process);
            assertEquals(1, downstreamCalls.get());
            assertTrue(pipeline.getResult("results").getModel() instanceof Exception);
        }
        finally {
            cameraImage.release();
        }
    }

    @Test
    void scriptFailureWithoutCnnKeepsOrdinaryPipelineBehavior() throws Exception {
        nu.pattern.OpenCV.loadLocally();
        Mat cameraImage = new Mat(4, 6, CvType.CV_8UC3, new Scalar(11, 22, 33));
        AtomicInteger downstreamCalls = new AtomicInteger();
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("capture", new CvStage() {
                @Override
                public Result process(CvPipeline current) {
                    return new Result(cameraImage.clone(), ColorSpace.Bgr);
                }
            });
            ScriptRun script = new ScriptRun();
            script.setFile(fixtureFile("return_rectangle.py"));
            pipeline.add("results", script);
            pipeline.add("downstream", new CvStage() {
                @Override
                public Result process(CvPipeline current) {
                    downstreamCalls.incrementAndGet();
                    return null;
                }
            });

            pipeline.process();

            assertEquals(1, downstreamCalls.get());
            assertTrue(pipeline.getResult("results").getModel() instanceof Exception);
        }
        finally {
            cameraImage.release();
        }
    }

    @Test
    void jythonCapturesFinalAssignmentWithoutReexecutingScript() throws Exception {
        nu.pattern.OpenCV.loadLocally();
        Mat cameraImage = new Mat(4, 6, CvType.CV_8UC3, new Scalar(11, 22, 33));
        Path scriptPath = directory.resolve("arbitrary-final-assignment.py");
        String scriptPrefix = "from org.opencv.core import Point, RotatedRect, Size\n"
                + "from org.openpnp.vision.pipeline import CvStage\n"
                + "def make_result():\n"
                + "    pipeline.setProperty('jython-evaluations', "
                + "pipeline.getProperty('jython-evaluations') + 1)\n"
                + "    return CvStage.Result(None, "
                + "RotatedRect(Point(3.0, 2.0), Size(2.0, 1.0), 17.0))\n";
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.setProperty("jython-evaluations", 0);
            addDeterministicInput(pipeline, cameraImage);
            ScriptRun script = new ScriptRun();
            script.setFile(scriptPath.toFile());
            pipeline.add("results", script);

            for (String finalStatement : new String[] {
                    "make_result()\n", "opaque_value = make_result()\n" }) {
                Files.writeString(scriptPath, scriptPrefix + finalStatement);
                pipeline.process();
                assertTrue(pipeline.getExpectedResult("results").getModel() instanceof RotatedRect);
            }

            assertEquals(2, pipeline.getProperty("jython-evaluations"));
        }
        finally {
            cameraImage.release();
        }
    }

    private static void addDeterministicInput(CvPipeline pipeline, Mat cameraImage) {
        pipeline.add("capture", new CvStage() {
            @Override
            public Result process(CvPipeline current) {
                return new Result(cameraImage.clone(), ColorSpace.Bgr);
            }
        });
        pipeline.add("cnn", new CNN() {
            @Override
            public Result process(CvPipeline current) {
                Mat probability = new Mat(4, 6, CvType.CV_32FC1, Scalar.all(0.8));
                return new Result(null, current.ownResultModel(probability));
            }
        });
    }

    private static File fixtureFile(String name) throws URISyntaxException {
        java.net.URL resource = JythonInteropTest.class.getResource(name);
        assertNotNull(resource, "Missing Jython test fixture " + name);
        return Path.of(resource.toURI()).toFile();
    }
}
