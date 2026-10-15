package org.openpnp.vision.cnn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.pandaplacer.BambooFeederAutoVision;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.spi.MachineListener;
import org.openpnp.vision.FluentCv.ColorSpace;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.stages.CNN;

class CnnModelManagerTest {
    private static final String RESOURCE_DIR = "/org/openpnp/vision/cnn/";
    private static final String VALID_MODEL_ID = "test-mean-channels";
    private static final String INVALID_MODEL_ID = "test-invalid-rank";
    private static final String TRANSPOSED_MODEL_ID = "test-transposed";
    private static final String EXTRA_INPUT_MODEL_ID = "test-extra-input";
    private static final String FLOAT16_MODEL_ID = "test-float16";

    private Path configurationDirectory;
    private Path modelDirectory;
    private CnnModelManager manager;

    @BeforeEach
    void setUp() throws Exception {
        nu.pattern.OpenCV.loadLocally();
        configurationDirectory = Files.createTempDirectory("openpnp-cnn-test-");
        modelDirectory = configurationDirectory.resolve("models");
        Files.createDirectories(modelDirectory);
        copyFixture("mean_channels_nchw_to_n1hw.onnx");
        copyFixture("mean_channels_nchw_to_n1hw.json");
        copyFixture("invalid_rank_nchw_to_nhw.onnx");
        copyFixture("invalid_rank_nchw_to_nhw.json");
        copyFixture("transposed_nchw_to_n1wh.onnx");
        writeMetadata(TRANSPOSED_MODEL_ID, "transposed_nchw_to_n1wh.onnx");
        copyFixture("extra_input_nchw_to_n1hw.onnx");
        writeMetadata(EXTRA_INPUT_MODEL_ID, "extra_input_nchw_to_n1hw.onnx");
        copyFixture("float16_nchw_to_n3hw.onnx");
        writeMetadata(FLOAT16_MODEL_ID, "float16_nchw_to_n3hw.onnx");
        Configuration.initialize(configurationDirectory.toFile());
        manager = CnnModelManager.get(Configuration.get());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) {
            manager.close();
        }
        if (configurationDirectory != null) {
            try (java.util.stream.Stream<Path> paths = Files.walk(configurationDirectory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
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

    @Test
    void cpuInferenceThreadDefaultAndJvmOverrideAreValidated() {
        assertEquals(1, CnnModelManager.cpuIntraOpThreads(1, null));
        assertEquals(2, CnnModelManager.cpuIntraOpThreads(2, null));
        assertEquals(4, CnnModelManager.cpuIntraOpThreads(8, null));
        assertEquals(4, CnnModelManager.cpuIntraOpThreads(16, " "));
        assertEquals(1, CnnModelManager.cpuIntraOpThreads(8, "1"));
        assertEquals(6, CnnModelManager.cpuIntraOpThreads(8, " 6 "));
        assertThrows(IllegalArgumentException.class,
                () -> CnnModelManager.cpuIntraOpThreads(8, "0"));
        assertThrows(IllegalArgumentException.class,
                () -> CnnModelManager.cpuIntraOpThreads(8, "65"));
        assertThrows(IllegalArgumentException.class,
                () -> CnnModelManager.cpuIntraOpThreads(8, "auto"));
    }

    @Test
    void concurrentFirstInferenceCoalescesAndInvalidationCreatesFreshSession() throws Exception {
        assertEquals(5, manager.listModelIds().size());
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<ProbabilityMap>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> withImage(image ->
                        manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null)));
            }
            List<Future<ProbabilityMap>> results = executor.invokeAll(calls);
            for (Future<ProbabilityMap> result : results) {
                assertExpectedMap(result.get());
            }
            Object firstSession = sessionFor(VALID_MODEL_ID);
            assertNotNull(firstSession);
            withImage(image -> {
                assertExpectedMap(manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
                return null;
            });
            assertSame(firstSession, sessionFor(VALID_MODEL_ID));

            manager.invalidate(VALID_MODEL_ID);
            withImage(image -> {
                assertExpectedMap(manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
                return null;
            });
            assertNotSame(firstSession, sessionFor(VALID_MODEL_ID));
        }
        finally {
            executor.shutdownNow();
        }

        Files.delete(modelDirectory.resolve("mean_channels_nchw_to_n1hw.onnx"));
        withImage(image -> {
            Exception error = assertThrows(Exception.class,
                    () -> manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
            assertTrue(error.getMessage().contains(VALID_MODEL_ID));
            return null;
        });
    }

    @Test
    void modelWithNonNchwOutputIsRejected() throws Exception {
        withImage(image -> {
            Exception error = assertThrows(Exception.class,
                    () -> manager.infer(INVALID_MODEL_ID, image, ColorSpace.Bgr, null));
            assertTrue(exceptionMessages(error).contains("NCHW"));
            assertNull(sessionFor(INVALID_MODEL_ID));
            return null;
        });

        Path repairedModel = modelDirectory.resolve("repaired_model.onnx");
        Files.copy(modelDirectory.resolve("mean_channels_nchw_to_n1hw.onnx"), repairedModel);
        Path metadata = modelDirectory.resolve("invalid_rank_nchw_to_nhw.json");
        String repaired = Files.readString(metadata).replace(
                "invalid_rank_nchw_to_nhw.onnx", "repaired_model.onnx");
        replaceFile(metadata, repaired);
        withImage(image -> {
            ProbabilityMap result = manager.infer(INVALID_MODEL_ID, image, ColorSpace.Bgr, null);
            assertEquals(INVALID_MODEL_ID, result.getModelId());
            assertEquals(expectedProbability(10, 20, 30), result.get(0, 0), 1.0e-6);
            return null;
        });
    }

    @Test
    void float16AndAdditionalRequiredInputsAreRejectedBeforeSessionRetention() throws Exception {
        withImage(image -> {
            Exception extraInputError = assertThrows(Exception.class,
                    () -> manager.infer(EXTRA_INPUT_MODEL_ID, image, ColorSpace.Bgr, null));
            assertTrue(exceptionMessages(extraInputError).contains("exactly one input"));
            assertNull(sessionFor(EXTRA_INPUT_MODEL_ID));

            Exception float16Error = assertThrows(Exception.class,
                    () -> manager.infer(FLOAT16_MODEL_ID, image, ColorSpace.Bgr, null));
            assertTrue(exceptionMessages(float16Error).contains("FLOAT32"));
            assertNull(sessionFor(FLOAT16_MODEL_ID));
            return null;
        });
    }

    @Test
    void transposedOutputWithSameCellCountIsRejected() throws Exception {
        withImage(image -> {
            Exception error = assertThrows(Exception.class,
                    () -> manager.infer(TRANSPOSED_MODEL_ID, image, ColorSpace.Bgr, null));
            assertTrue(exceptionMessages(error).contains("runtime shape"));
            assertNotNull(sessionFor(TRANSPOSED_MODEL_ID));
            return null;
        });
    }

    @Test
    void modelAndMetadataReplacementArePickedUpAndRemovalFailsClosed() throws Exception {
        withImage(image -> {
            assertExpectedMap(manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
            return null;
        });
        Object originalSession = sessionFor(VALID_MODEL_ID);

        Path originalModel = modelDirectory.resolve("mean_channels_nchw_to_n1hw.onnx");
        Path sameContentReplacement = Files.createTempFile(modelDirectory, "same-content-", ".onnx");
        Files.write(sameContentReplacement, Files.readAllBytes(originalModel));
        Files.move(sameContentReplacement, originalModel,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        withImage(image -> {
            assertExpectedMap(manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
            return null;
        });
        assertSame(originalSession, sessionFor(VALID_MODEL_ID));

        copyFixture("mean_plus_one_nchw_to_n1hw.onnx");
        Path metadata = modelDirectory.resolve("mean_channels_nchw_to_n1hw.json");
        String replacement = Files.readString(metadata).replace(
                "mean_channels_nchw_to_n1hw.onnx", "mean_plus_one_nchw_to_n1hw.onnx");
        replaceFile(metadata, replacement);
        withImage(image -> {
            ProbabilityMap result = manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null);
            assertEquals(expectedProbabilityWithOffset(10, 20, 30, 1.0), result.get(0, 0), 1.0e-6);
            return null;
        });
        Object replacementSession = sessionFor(VALID_MODEL_ID);
        assertNotSame(originalSession, replacementSession);

        String thresholdUpdate = Files.readString(metadata).replace(
                "\"defaultThreshold\": 0.5", "\"defaultThreshold\": 0.25");
        replaceFile(metadata, thresholdUpdate);
        withImage(image -> {
            ProbabilityMap result = manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null);
            assertEquals(0.25f, result.getDefaultThreshold(), 0.0f);
            assertEquals(expectedProbabilityWithOffset(10, 20, 30, 1.0), result.get(0, 0), 1.0e-6);
            return null;
        });
        assertNotSame(replacementSession, sessionFor(VALID_MODEL_ID));

        Files.delete(modelDirectory.resolve("mean_plus_one_nchw_to_n1hw.onnx"));
        withImage(image -> {
            assertThrows(Exception.class,
                    () -> manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
            return null;
        });
    }

    @Test
    void duplicateModelIdentifiersAreExcludedFromTheCatalog() throws Exception {
        writeMetadata("duplicate-content", "mean_channels_nchw_to_n1hw.onnx");
        Path duplicate = modelDirectory.resolve("duplicate-content.json");
        String contents = Files.readString(duplicate).replace(
                "\"modelId\": \"duplicate-content\"",
                "\"modelId\": \"" + VALID_MODEL_ID + "\"");
        replaceFile(duplicate, contents);

        manager.refreshCatalog();
        assertFalse(manager.listModelIds().contains(VALID_MODEL_ID));
        withImage(image -> {
            assertThrows(Exception.class,
                    () -> manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
            return null;
        });
    }

    @Test
    void modelSymlinkEscapingConfigurationIsExcludedFromTheCatalog() throws Exception {
        Path outsideDirectory = Files.createTempDirectory("openpnp-cnn-outside-");
        try {
            Path outsideModel = Files.copy(modelDirectory.resolve("mean_channels_nchw_to_n1hw.onnx"),
                    outsideDirectory.resolve("outside.onnx"));
            Path link = modelDirectory.resolve("external-model.onnx");
            try {
                Files.createSymbolicLink(link, outsideModel);
            }
            catch (UnsupportedOperationException | IOException e) {
                Assumptions.assumeTrue(false, "Symbolic links are unavailable.");
            }
            writeMetadata("test-external-model", "external-model.onnx");

            manager.refreshCatalog();

            assertFalse(manager.listModelIds().contains("test-external-model"));
            withImage(image -> {
                assertThrows(Exception.class, () -> manager.infer(
                        "test-external-model", image, ColorSpace.Bgr, null));
                return null;
            });
        }
        finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(outsideDirectory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
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

    @Test
    void preloadReportsFailuresSkipsDisabledStagesAndRetainsLoadedSessions() throws Exception {
        BottomVisionSettings settings = new BottomVisionSettings("CNN-Test-Settings");
        CvPipeline pipeline = new CvPipeline();
        CNN stage = new CNN();
        stage.setModelId(INVALID_MODEL_ID);
        pipeline.add("CNN", stage);
        settings.setPipeline(pipeline);
        Configuration.get().addVisionSettings(settings);
        ReferenceMachine machine = new ReferenceMachine();

        CnnModelManager.PreloadReport failed = manager.preloadReferencedModels(machine)
                .get(10, TimeUnit.SECONDS);
        assertFalse(failed.isSuccessful());
        assertTrue(failed.getFailures().containsKey(INVALID_MODEL_ID));

        stage.setEnabled(false);
        CnnModelManager.PreloadReport disabled = manager.preloadReferencedModels(machine)
                .get(10, TimeUnit.SECONDS);
        assertTrue(disabled.isSuccessful());
        assertTrue(disabled.getReferencedModelIds().isEmpty());

        stage.setModelId(VALID_MODEL_ID);
        stage.setEnabled(true);
        CnnModelManager.PreloadReport enabled = manager.preloadReferencedModels(machine)
                .get(10, TimeUnit.SECONDS);
        assertTrue(enabled.isSuccessful());
        Object loadedSession = sessionFor(VALID_MODEL_ID);
        assertNotNull(loadedSession);

        stage.setEnabled(false);
        manager.preloadReferencedModels(machine).get(10, TimeUnit.SECONDS);
        assertSame(loadedSession, sessionFor(VALID_MODEL_ID));

        stage.setEnabled(true);
        CnnModelManager.PreloadReport reenabled = manager.preloadReferencedModels(machine)
                .get(10, TimeUnit.SECONDS);
        assertTrue(reenabled.isSuccessful());
        assertSame(loadedSession, sessionFor(VALID_MODEL_ID));
    }

    @Test
    void discoversAndPreloadsBambooAutoVisionFeederPipelineWithoutGlobalSettings() throws Exception {
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        BambooFeederAutoVision feeder = new BambooFeederAutoVision();
        CvPipeline pipeline = new CvPipeline();
        CNN stage = new CNN();
        stage.setModelId(VALID_MODEL_ID);
        pipeline.add("CNN", stage);
        feeder.setPipeline(pipeline);
        machine.addFeeder(feeder);

        assertTrue(Configuration.get().getVisionSettings().isEmpty());
        assertEquals(java.util.Collections.singleton(VALID_MODEL_ID),
                CnnPipelineDiscovery.findReferencedModelIds(machine));

        CnnModelManager.PreloadReport report = manager.preloadReferencedModels(machine)
                .get(10, TimeUnit.SECONDS);
        assertTrue(report.isSuccessful());
        assertEquals(java.util.Collections.singleton(VALID_MODEL_ID), report.getReferencedModelIds());
        assertNotNull(sessionFor(VALID_MODEL_ID));
    }

    @Test
    void machineEnableDisableAndReenableRetainsPreloadedSessionBeforeEnabledEvent() throws Exception {
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        BottomVisionSettings settings = new BottomVisionSettings("CNN-Machine-Lifecycle");
        CvPipeline pipeline = new CvPipeline();
        CNN stage = new CNN();
        stage.setModelId(VALID_MODEL_ID);
        pipeline.add("CNN", stage);
        settings.setPipeline(pipeline);
        Configuration.get().addVisionSettings(settings);

        AtomicReference<Boolean> sessionReadyOnEnabledEvent = new AtomicReference<>(Boolean.FALSE);
        machine.addListener(new MachineListener.Adapter() {
            @Override
            public void machineEnabled(org.openpnp.spi.Machine enabledMachine) {
                try {
                    sessionReadyOnEnabledEvent.set(sessionFor(VALID_MODEL_ID) != null);
                }
                catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        machine.setEnabled(true);
        assertTrue(machine.isEnabled());
        assertEquals(Boolean.TRUE, sessionReadyOnEnabledEvent.get());
        Object loadedSession = sessionFor(VALID_MODEL_ID);
        assertNotNull(loadedSession);

        machine.setEnabled(false);
        assertFalse(machine.isEnabled());
        assertSame(loadedSession, sessionFor(VALID_MODEL_ID));

        sessionReadyOnEnabledEvent.set(Boolean.FALSE);
        machine.setEnabled(true);
        assertTrue(machine.isEnabled());
        assertEquals(Boolean.TRUE, sessionReadyOnEnabledEvent.get());
        assertSame(loadedSession, sessionFor(VALID_MODEL_ID));
    }

    @Test
    void closeWaitsForInferenceHoldingTheLifecycleReadLock() throws Exception {
        Semaphore permit = field(manager, "inferencePermit");
        ReentrantReadWriteLock lifecycleLock = field(manager, "lifecycleLock");
        permit.acquire();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> inferenceThread = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        try {
            Future<ProbabilityMap> inference = executor.submit(() -> {
                inferenceThread.set(Thread.currentThread());
                return withImage(image -> manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Thread thread = inferenceThread.get();
                if (thread != null && thread.getState() == Thread.State.WAITING
                        && lifecycleLock.getReadLockCount() > 0) {
                    break;
                }
                Thread.yield();
            }
            Thread thread = inferenceThread.get();
            assertNotNull(thread);
            assertEquals(Thread.State.WAITING, thread.getState());
            assertTrue(lifecycleLock.getReadLockCount() > 0);

            Future<?> close = executor.submit(() -> {
                closeThread.set(Thread.currentThread());
                manager.close();
            });
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Thread closer = closeThread.get();
                if (closer != null && closer.getState() == Thread.State.WAITING
                        && lifecycleLock.hasQueuedThreads()) {
                    break;
                }
                Thread.yield();
            }
            assertNotNull(closeThread.get());
            assertEquals(Thread.State.WAITING, closeThread.get().getState());
            assertTrue(lifecycleLock.hasQueuedThreads());
            assertFalse(close.isDone());
            assertThrows(IllegalStateException.class,
                    () -> CnnModelManager.get(Configuration.get()));
            permit.release();
            assertExpectedMap(inference.get(10, TimeUnit.SECONDS));
            close.get(10, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, manager::listModelIds);
            assertThrows(IllegalStateException.class,
                    () -> CnnModelManager.get(Configuration.get()));
        }
        finally {
            if (permit.availablePermits() == 0) {
                permit.release();
            }
            executor.shutdownNow();
        }
    }

    @Test
    void closeIsIdempotentAndRejectsLaterInference() throws Exception {
        withImage(image -> {
            assertExpectedMap(manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
            return null;
        });
        manager.close();
        manager.close();
        assertThrows(IllegalStateException.class,
                () -> CnnModelManager.get(Configuration.get()));
        withImage(image -> {
            assertThrows(IllegalStateException.class,
                    () -> manager.infer(VALID_MODEL_ID, image, ColorSpace.Bgr, null));
            return null;
        });
    }

    private void assertExpectedMap(ProbabilityMap result) {
        assertNotNull(result);
        assertEquals(VALID_MODEL_ID, result.getModelId());
        assertEquals("test-segmentation", result.getTask());
        assertEquals(3, result.getWidth());
        assertEquals(2, result.getHeight());
        assertEquals(expectedProbability(10, 20, 30), result.get(0, 0), 1.0e-6);
        assertEquals(expectedProbability(70, 80, 90), result.get(2, 0), 1.0e-6);
        assertEquals(expectedProbability(160, 170, 180), result.get(2, 1), 1.0e-6);
    }

    private static double expectedProbability(int red, int green, int blue) {
        double logit = ((red / 255.0 - 0.1) / 1.0
                + (green / 255.0 - 0.2) / 2.0
                + (blue / 255.0 - 0.3) / 3.0) / 3.0;
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    private static double expectedProbabilityWithOffset(int red, int green, int blue, double offset) {
        double probability = expectedProbability(red, green, blue);
        double logit = Math.log(probability / (1.0 - probability)) + offset;
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    private static String exceptionMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append(' ');
        }
        return messages.toString();
    }

    private static Mat createBgrImage() {
        Mat image = new Mat(2, 3, CvType.CV_8UC3);
        byte[] bgr = {
                30, 20, 10, 60, 50, 40, 90, 80, 70,
                (byte) 120, (byte) 110, (byte) 100, (byte) 150, (byte) 140,
                (byte) 130, (byte) 180, (byte) 170, (byte) 160
        };
        image.put(0, 0, bgr);
        return image;
    }

    private static <T> T withImage(MatOperation<T> operation) throws Exception {
        Mat image = createBgrImage();
        try {
            return operation.apply(image);
        }
        finally {
            image.release();
        }
    }

    @FunctionalInterface
    private interface MatOperation<T> {
        T apply(Mat image) throws Exception;
    }

    private void copyFixture(String name) throws IOException {
        Path destination = modelDirectory.resolve(name);
        try (InputStream input = getClass().getResourceAsStream(RESOURCE_DIR + name)) {
            if (input == null) {
                throw new IOException("Missing CNN test fixture: " + name);
            }
            Files.copy(input, destination);
        }
    }

    private void writeMetadata(String modelId, String modelName) throws IOException {
        String template;
        try (InputStream input = getClass().getResourceAsStream(
                RESOURCE_DIR + "mean_channels_nchw_to_n1hw.json")) {
            if (input == null) {
                throw new IOException("Missing CNN metadata template.");
            }
            template = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String metadata = template.replace("test-mean-channels", modelId)
                .replace("mean_channels_nchw_to_n1hw.onnx", modelName);
        Files.writeString(modelDirectory.resolve(modelId + ".json"), metadata);
    }

    private static void replaceFile(Path path, String contents) throws IOException {
        FileTime modified = FileTime.from(
                Files.getLastModifiedTime(path).toInstant().plusSeconds(2));
        Files.writeString(path, contents);
        Files.setLastModifiedTime(path, modified);
    }

    private Object sessionFor(String modelId) throws Exception {
        Object entry = ((java.util.Map<?, ?>) field(manager, "models")).get(modelId);
        return entry == null ? null : field(entry, "session");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
