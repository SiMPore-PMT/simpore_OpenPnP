package org.openpnp.vision.cnn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;

class PipelineSerializationTest {
    @Test
    @Timeout(15)
    void savingCannotResetAnExecutingPipeline() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        PausingStage stage = new PausingStage();
        AtomicReference<Thread> savingThread = new AtomicReference<>();
        CountDownLatch saving = new CountDownLatch(1);
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("paused", stage);
            Future<?> processing = workers.submit(() -> {
                pipeline.process();
                return null;
            });
            assertTrue(stage.entered.await(5, TimeUnit.SECONDS));
            Future<String> serialized = workers.submit(() -> {
                savingThread.set(Thread.currentThread());
                saving.countDown();
                return pipeline.toXmlString();
            });
            assertTrue(saving.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (savingThread.get().getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield();
            }
            try {
                assertEquals(Thread.State.BLOCKED, savingThread.get().getState());
            }
            finally {
                stage.finish.countDown();
            }
            processing.get(5, TimeUnit.SECONDS);
            assertTrue(serialized.get(5, TimeUnit.SECONDS).contains("paused"));
        }
        finally {
            stage.finish.countDown();
            workers.shutdownNow();
        }
    }

    public static class PausingStage extends CvStage {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);

        @Override
        public Result process(CvPipeline pipeline) throws Exception {
            entered.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release processing.");
            }
            return null;
        }
    }
}
