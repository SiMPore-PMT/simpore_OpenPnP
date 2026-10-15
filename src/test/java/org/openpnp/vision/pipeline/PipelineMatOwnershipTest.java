package org.openpnp.vision.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

class PipelineMatOwnershipTest {
    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    void registeredModelIsAvailableToDownstreamStagesAndReleasedByPipeline() throws Exception {
        AtomicReference<CountingMat> produced = new AtomicReference<>();
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("producer", new CvStage() {
                @Override
                public Result process(CvPipeline pipeline) {
                    CountingMat model = new CountingMat(2, 3, CvType.CV_32FC1,
                            new Scalar(0.25));
                    produced.set(model);
                    return new Result(null, pipeline.ownResultModel(model));
                }
            });
            pipeline.add("consumer", new CvStage() {
                @Override
                public Result process(CvPipeline pipeline) throws Exception {
                    Mat model = pipeline.getExpectedResult("producer")
                            .getExpectedModel(Mat.class);
                    assertSame(produced.get(), model);
                    assertEquals(CvType.CV_32FC1, model.type());
                    assertEquals(0.25, model.get(1, 2)[0], 0.000001);
                    return null;
                }
            });

            pipeline.process();
            assertFalse(produced.get().empty());
            assertEquals(0, produced.get().getReleaseCount());

            pipeline.release();
            assertEquals(1, produced.get().getReleaseCount());
        }
    }

    @Test
    void ownedModelUsedAsWorkingImageIsHeldUntilPipelineRelease() throws Exception {
        AtomicReference<CountingMat> produced = new AtomicReference<>();
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("producer", new CvStage() {
                @Override
                public Result process(CvPipeline pipeline) {
                    CountingMat model = new CountingMat(2, 3, CvType.CV_32FC1,
                            Scalar.all(0.5));
                    produced.set(model);
                    return new Result(null, pipeline.ownResultModel(model));
                }
            });
            pipeline.add("promote", new CvStage() {
                @Override
                public Result process(CvPipeline pipeline) throws Exception {
                    Mat model = pipeline.getExpectedResult("producer")
                            .getExpectedModel(Mat.class);
                    return new Result(model);
                }
            });
            pipeline.add("replace", new CvStage() {
                @Override
                public Result process(CvPipeline pipeline) {
                    return new Result(new Mat(1, 1, CvType.CV_8UC1, Scalar.all(0)));
                }
            });

            pipeline.process();
            assertFalse(produced.get().empty());
            assertEquals(0, produced.get().getReleaseCount());

            pipeline.release();
            assertEquals(1, produced.get().getReleaseCount());
        }
    }

    @Test
    void unregisteredModelRetainsExistingCallerOwnership() throws Exception {
        CountingMat externalModel = new CountingMat(2, 3, CvType.CV_32FC1, Scalar.all(0.75));
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("producer", new CvStage() {
                @Override
                public Result process(CvPipeline pipeline) {
                    return new Result(null, externalModel);
                }
            });

            pipeline.process();
            pipeline.release();

            assertFalse(externalModel.empty());
            assertEquals(0, externalModel.getReleaseCount());
        }
        finally {
            externalModel.release();
        }
        assertEquals(1, externalModel.getReleaseCount());
    }

    @Test
    void nullCannotBeTransferred() throws Exception {
        try (CvPipeline pipeline = new CvPipeline()) {
            assertThrows(IllegalArgumentException.class, () -> pipeline.ownResultModel(null));
        }
    }

    private static class CountingMat extends Mat {
        private int releaseCount;

        CountingMat(int rows, int columns, int type, Scalar scalar) {
            super(rows, columns, type, scalar);
        }

        @Override
        public void release() {
            releaseCount++;
            super.release();
        }

        int getReleaseCount() {
            return releaseCount;
        }
    }
}
