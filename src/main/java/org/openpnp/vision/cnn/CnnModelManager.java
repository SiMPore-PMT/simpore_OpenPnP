package org.openpnp.vision.cnn;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.swing.SwingUtilities;

import org.opencv.core.Mat;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;
import org.openpnp.vision.FluentCv.ColorSpace;
import org.openpnp.vision.cnn.ModelPreprocessor.PreparedInput;
import org.pmw.tinylog.Logger;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Owns the ONNX Runtime sessions used by CNN pipeline stages. A manager is scoped to one OpenPnP
 * configuration and shares one session for every unchanged model.
 */
public final class CnnModelManager implements AutoCloseable {
    private static final String CPU_THREADS_PROPERTY = "openpnp.cnn.intraOpThreads";
    private static final int MAX_CONFIGURED_CPU_THREADS = 64;

    private static final Map<Configuration, CnnModelManager> instances = new WeakHashMap<>();

    private final Path modelDirectory;
    private final ExecutorService preloadExecutor;
    private final Semaphore inferencePermit = new Semaphore(1, true);
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final Object sessionLock = new Object();
    private final Object refreshLock = new Object();

    private volatile Map<String, ModelEntry> models = Collections.emptyMap();
    private volatile Map<Path, Exception> catalogErrors = Collections.emptyMap();
    private volatile boolean closing;
    private volatile boolean closed;

    /**
     * ONNX intra-op CPU threads. Use up to four available processors by default so a CNN
     * does not monopolize a machine-control host; allow an explicit JVM override for tuning.
     * Inter-op remains one, because these segmentation models execute a single graph.
     */
    static int cpuIntraOpThreads(int processors, String override) {
        int conservativeDefault = Math.min(4, Math.max(1, processors));
        if (override == null || override.trim().isEmpty()) {
            return conservativeDefault;
        }
        final int requested;
        try {
            requested = Integer.parseInt(override.trim());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid -D" + CPU_THREADS_PROPERTY + "="
                    + override + "; expected an integer from 1 to "
                    + MAX_CONFIGURED_CPU_THREADS + ".", e);
        }
        if (requested < 1 || requested > MAX_CONFIGURED_CPU_THREADS) {
            throw new IllegalArgumentException("Invalid -D" + CPU_THREADS_PROPERTY + "="
                    + override + "; expected an integer from 1 to "
                    + MAX_CONFIGURED_CPU_THREADS + ".");
        }
        return requested;
    }

    private CnnModelManager(Configuration configuration) {
        if (configuration.getConfigurationDirectory() == null) {
            throw new IllegalStateException("CNN models require an initialized configuration directory.");
        }
        modelDirectory = configuration.getConfigurationDirectory().toPath().resolve("models")
                .toAbsolutePath().normalize();
        preloadExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "OpenPnP-CNN-model-loader");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public static synchronized CnnModelManager get(Configuration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration is required.");
        }
        CnnModelManager manager = instances.get(configuration);
        if (manager == null) {
            manager = new CnnModelManager(configuration);
            instances.put(configuration, manager);
        }
        else if (manager.closing || manager.closed) {
            throw new IllegalStateException(
                    "CNN model manager is closing or closed for this configuration.");
        }
        return manager;
    }

    /** Close the manager for a configuration if it was used, without creating one. */
    public static synchronized void close(Configuration configuration) {
        CnnModelManager manager = instances.get(configuration);
        if (manager != null) {
            manager.close();
        }
    }

    public Set<String> listModelIds() {
        checkOpen();
        // Re-read only small metadata files so newly installed models appear in the editor without
        // hashing model weights or initializing ONNX Runtime on the event-dispatch thread.
        List<String> identifiers = new ArrayList<>(readCatalog(false).models.keySet());
        Collections.sort(identifiers);
        return Collections.unmodifiableSet(new LinkedHashSet<>(identifiers));
    }

    /**
     * Refreshes model metadata and content hashes. Sessions for unchanged content are retained;
     * changed and removed sessions are closed after active inference has finished.
     */
    public void refreshCatalog() {
        lifecycleLock.writeLock().lock();
        try {
            checkOpen();
            Catalog catalog = readCatalog(true);
            synchronized (sessionLock) {
                for (Map.Entry<String, ModelEntry> old : models.entrySet()) {
                    ModelEntry replacement = catalog.models.get(old.getKey());
                    if (replacement != null && replacement.fingerprint.equals(old.getValue().fingerprint)) {
                        old.getValue().metadataStamp = replacement.metadataStamp;
                        old.getValue().modelStamp = replacement.modelStamp;
                        catalog.models.put(old.getKey(), old.getValue());
                    }
                    else {
                        old.getValue().close();
                    }
                }
                models = Collections.unmodifiableMap(catalog.models);
                catalogErrors = Collections.unmodifiableMap(catalog.errors);
            }
        }
        finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    public CompletableFuture<PreloadReport> preloadReferencedModels(Machine machine) {
        checkOpen();
        final Set<String> modelIds = CnnPipelineDiscovery.findReferencedModelIds(machine);
        if (modelIds.isEmpty()) {
            return CompletableFuture.completedFuture(new PreloadReport(modelIds,
                    Collections.<String, Exception>emptyMap(), catalogErrors));
        }
        return CompletableFuture.supplyAsync(() -> {
            refreshCatalog();
            Map<String, Exception> failures = new LinkedHashMap<>();
            for (String modelId : modelIds) {
                lifecycleLock.readLock().lock();
                try {
                    resolve(modelId);
                }
                catch (Exception e) {
                    failures.put(modelId, e);
                    Logger.warn(e, "Unable to preload CNN model \"{}\".", modelId);
                }
                finally {
                    lifecycleLock.readLock().unlock();
                }
            }
            return new PreloadReport(modelIds, failures, catalogErrors);
        }, preloadExecutor);
    }

    public ProbabilityMap infer(String modelId, Mat image, ColorSpace colorSpace,
            Double threshold) throws Exception {
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new Exception("CNN model identifier is required.");
        }
        if (image == null || image.empty()) {
            throw new Exception("CNN input image is empty.");
        }
        if (threshold != null && (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)) {
            throw new Exception("CNN segmentation threshold must be between 0 and 1.");
        }
        if (SwingUtilities.isEventDispatchThread()) {
            throw new Exception("CNN inference must run off the Swing event-dispatch thread.");
        }

        refreshIfChanged(modelId);

        lifecycleLock.readLock().lock();
        boolean permit = false;
        try {
            checkOpen();
            ModelEntry entry = resolve(modelId);
            inferencePermit.acquire();
            permit = true;
            long preprocessStarted = System.nanoTime();
            try {
                PreparedInput prepared = ModelPreprocessor.prepare(image, colorSpace, entry.metadata);
                long preprocessNs = System.nanoTime() - preprocessStarted;
                long started = System.nanoTime();
                float[] output = entry.run(prepared);
                long restoreStarted = System.nanoTime();
                ProbabilityMap probabilityMap = ModelPreprocessor.restore(output, prepared,
                        entry.metadata);
                long restoreNs = System.nanoTime() - restoreStarted;
                if (threshold != null) {
                    probabilityMap = new ProbabilityMap(probabilityMap.getModelId(),
                            probabilityMap.getTask(), probabilityMap.getWidth(),
                            probabilityMap.getHeight(), probabilityMap.copyProbabilities(),
                            threshold.floatValue());
                }
                if (entry.filesChanged()) {
                    throw new Exception("CNN model files changed during inference; result discarded.");
                }
                Logger.debug("CNN model \"{}\" completed in {} ms (preprocess {} ms, restore {} ms, "
                        + "native {}x{}, model {}x{}, provider CPU).", modelId,
                        (System.nanoTime() - started + preprocessNs) / 1_000_000.0,
                        preprocessNs / 1_000_000.0, restoreNs / 1_000_000.0,
                        prepared.getNativeWidth(), prepared.getNativeHeight(),
                        prepared.getPaddedWidth(), prepared.getPaddedHeight());
                return probabilityMap;
            }
            catch (LinkageError e) {
                throw new Exception("CNN native runtime failed for model \"" + modelId + "\": "
                        + e.getMessage(), e);
            }
            catch (Exception e) {
                throw new Exception("CNN inference failed for model \"" + modelId + "\": "
                        + e.getMessage(), e);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Interrupted while waiting to run CNN model \"" + modelId + "\".", e);
        }
        finally {
            if (permit) {
                inferencePermit.release();
            }
            lifecycleLock.readLock().unlock();
        }
    }

    public void invalidate(String modelId) {
        if (modelId == null) {
            throw new IllegalArgumentException("Model identifier is required.");
        }
        lifecycleLock.writeLock().lock();
        try {
            checkOpen();
            synchronized (sessionLock) {
                ModelEntry entry = models.get(modelId);
                if (entry != null) {
                    entry.close();
                    Map<String, ModelEntry> changed = new LinkedHashMap<>(models);
                    changed.remove(modelId);
                    models = Collections.unmodifiableMap(changed);
                }
            }
        }
        finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void refreshIfChanged(String modelId) {
        synchronized (refreshLock) {
            boolean changed = false;
            lifecycleLock.readLock().lock();
            try {
                checkOpen();
                synchronized (sessionLock) {
                    ModelEntry entry = models.get(modelId);
                    changed = entry != null && entry.filesChanged();
                }
            }
            finally {
                lifecycleLock.readLock().unlock();
            }
            if (changed) {
                refreshCatalog();
            }
        }
    }

    private ModelEntry resolve(String modelId) throws Exception {
        checkOpen();
        synchronized (sessionLock) {
            ModelEntry entry = models.get(modelId);
            if (entry == null) {
                // A dynamically constructed pipeline may be the first reference to a newly added
                // model. Refresh once before reporting it missing.
                refreshCatalogForDynamicResolution();
                entry = models.get(modelId);
            }
            if (entry == null) {
                StringBuilder message = new StringBuilder("Unknown CNN model \"")
                        .append(modelId).append("\" in ").append(modelDirectory).append('.');
                if (!catalogErrors.isEmpty()) {
                    message.append(' ').append(catalogErrors.size())
                            .append(" metadata file(s) were invalid; first error: ")
                            .append(catalogErrors.values().iterator().next().getMessage());
                }
                throw new Exception(message.toString());
            }
            entry.open();
            return entry;
        }
    }

    private void refreshCatalogForDynamicResolution() throws Exception {
        // resolve() is called under the lifecycle read lock, so a full refresh (which needs the
        // write lock) would deadlock. Read and merge only a previously unknown identifier here.
        Catalog catalog = readCatalog(true);
        Map<String, ModelEntry> changed = new LinkedHashMap<>(models);
        for (Map.Entry<String, ModelEntry> candidate : catalog.models.entrySet()) {
            if (!changed.containsKey(candidate.getKey())) {
                changed.put(candidate.getKey(), candidate.getValue());
            }
        }
        models = Collections.unmodifiableMap(changed);
        catalogErrors = Collections.unmodifiableMap(catalog.errors);
    }

    private Catalog readCatalog(boolean includeModelFingerprint) {
        Map<String, ModelEntry> discovered = new LinkedHashMap<>();
        Map<Path, Exception> errors = new LinkedHashMap<>();
        Map<String, Path> metadataById = new LinkedHashMap<>();
        Set<String> duplicateIds = new LinkedHashSet<>();
        if (!Files.isDirectory(modelDirectory)) {
            return new Catalog(discovered, errors);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modelDirectory, "*.json")) {
            List<Path> metadataPaths = new ArrayList<>();
            for (Path metadataPath : stream) {
                metadataPaths.add(metadataPath);
            }
            metadataPaths.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path metadataPath : metadataPaths) {
                try {
                    FileStamp metadataStamp = FileStamp.read(metadataPath);
                    ModelMetadata metadata = ModelMetadata.read(metadataPath);
                    FileStamp modelStamp = FileStamp.read(metadata.getModelPath());
                    Path realRoot = modelDirectory.toRealPath();
                    if (!metadata.getMetadataPath().getParent().equals(realRoot)
                            || !metadata.getModelPath().startsWith(realRoot)) {
                        throw new IOException("CNN metadata and model must remain directly under "
                                + modelDirectory + ".");
                    }
                    if (duplicateIds.contains(metadata.getModelId())) {
                        throw new IOException("Duplicate CNN model identifier \""
                                + metadata.getModelId() + "\".");
                    }
                    if (discovered.containsKey(metadata.getModelId())) {
                        discovered.remove(metadata.getModelId());
                        duplicateIds.add(metadata.getModelId());
                        Path previousPath = metadataById.get(metadata.getModelId());
                        errors.put(previousPath, new IOException("Duplicate CNN model identifier \""
                                + metadata.getModelId() + "\"."));
                        throw new IOException("Duplicate CNN model identifier \""
                                + metadata.getModelId() + "\".");
                    }
                    String fingerprint = includeModelFingerprint
                            ? fingerprint(metadataPath, metadata.getModelPath()) : null;
                    if (!metadataStamp.equals(FileStamp.read(metadataPath))
                            || !modelStamp.equals(FileStamp.read(metadata.getModelPath()))) {
                        throw new IOException("CNN metadata or model changed while the catalog "
                                + "entry was validated; retry the operation.");
                    }
                    discovered.put(metadata.getModelId(), new ModelEntry(metadata, fingerprint,
                            metadataStamp, modelStamp));
                    metadataById.put(metadata.getModelId(), metadataPath);
                }
                catch (Exception e) {
                    errors.put(metadataPath, e);
                    Logger.warn(e, "Ignoring invalid CNN metadata {}.", metadataPath);
                }
            }
        }
        catch (IOException e) {
            errors.put(modelDirectory, e);
            Logger.warn(e, "Unable to scan CNN model directory {}.", modelDirectory);
        }
        return new Catalog(discovered, errors);
    }

    private static String fingerprint(Path metadataPath, Path modelPath) throws Exception {
        FileStamp metadataBefore = FileStamp.read(metadataPath);
        FileStamp modelBefore = FileStamp.read(modelPath);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
        updateDigest(digest, metadataPath);
        updateDigest(digest, modelPath);
        if (!metadataBefore.equals(FileStamp.read(metadataPath))
                || !modelBefore.equals(FileStamp.read(modelPath))) {
            throw new IOException("CNN model files changed while their fingerprint was computed.");
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void updateDigest(MessageDigest digest, Path path) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            while (input.read(buffer) != -1) {
                // Reading through DigestInputStream updates the digest.
            }
        }
    }

    private void checkOpen() {
        if (closing || closed) {
            throw new IllegalStateException("CNN model manager is closing or closed.");
        }
    }

    @Override
    public void close() {
        synchronized (CnnModelManager.class) {
            closing = true;
        }
        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            // Let queued preload futures start and observe the closed state, so callers waiting on
            // them are not left with an incomplete future.
            preloadExecutor.shutdown();
            synchronized (sessionLock) {
                for (ModelEntry entry : models.values()) {
                    entry.close();
                }
                models = Collections.emptyMap();
                catalogErrors = Collections.emptyMap();
            }
        }
        finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private static final class Catalog {
        private final Map<String, ModelEntry> models;
        private final Map<Path, Exception> errors;

        private Catalog(Map<String, ModelEntry> models, Map<Path, Exception> errors) {
            this.models = models;
            this.errors = errors;
        }
    }

    private static final class FileStamp {
        private final long size;
        private final FileTime modifiedTime;
        private final Object fileKey;

        private FileStamp(long size, FileTime modifiedTime, Object fileKey) {
            this.size = size;
            this.modifiedTime = modifiedTime;
            this.fileKey = fileKey;
        }

        private static FileStamp read(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileStamp(attributes.size(), attributes.lastModifiedTime(),
                    attributes.fileKey());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FileStamp)) {
                return false;
            }
            FileStamp that = (FileStamp) other;
            return size == that.size && modifiedTime.equals(that.modifiedTime)
                    && java.util.Objects.equals(fileKey, that.fileKey);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(size, modifiedTime, fileKey);
        }
    }

    private static final class ModelEntry implements AutoCloseable {
        private final ModelMetadata metadata;
        private String fingerprint;
        private FileStamp metadataStamp;
        private FileStamp modelStamp;
        private OrtSession session;
        private Exception failure;

        private ModelEntry(ModelMetadata metadata, String fingerprint, FileStamp metadataStamp,
                FileStamp modelStamp) {
            this.metadata = metadata;
            this.fingerprint = fingerprint;
            this.metadataStamp = metadataStamp;
            this.modelStamp = modelStamp;
        }

        private void open() throws Exception {
            if (session != null) {
                return;
            }
            if (failure != null) {
                throw failure;
            }
            if (filesChanged()) {
                throw new Exception("CNN metadata or model changed after validation; retry the operation.");
            }
            if (fingerprint == null) {
                fingerprint = fingerprint(metadata.getMetadataPath(), metadata.getModelPath());
                if (filesChanged()) {
                    throw new Exception("CNN metadata or model changed while its fingerprint was "
                            + "computed; retry the operation.");
                }
            }
            long started = System.nanoTime();
            OrtSession candidate = null;
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                int intraOpThreads = cpuIntraOpThreads(Runtime.getRuntime().availableProcessors(),
                        System.getProperty(CPU_THREADS_PROPERTY));
                options.setIntraOpNumThreads(intraOpThreads);
                options.setInterOpNumThreads(1);
                options.addCPU(true);
                Logger.info("CNN model \"{}\" CPU ONNX Runtime threading: intra-op {}, inter-op 1 "
                        + "(override using -D{}=N; restart required).",
                        metadata.getModelId(), intraOpThreads, CPU_THREADS_PROPERTY);
                candidate = OrtEnvironment.getEnvironment().createSession(
                        metadata.getModelPath().toString(), options);
                validateSession(candidate);
                session = candidate;
                candidate = null;
                Logger.info("Loaded CNN model \"{}\" with CPU provider in {} ms.",
                        metadata.getModelId(),
                        (System.nanoTime() - started) / 1_000_000.0);
            }
            catch (Exception e) {
                failure = new Exception("Unable to load CNN model \"" + metadata.getModelId()
                        + "\": " + e.getMessage(), e);
                throw failure;
            }
            catch (LinkageError e) {
                failure = new Exception("Unable to load CNN native runtime for model \""
                        + metadata.getModelId() + "\": " + e.getMessage(), e);
                throw failure;
            }
            finally {
                if (candidate != null) {
                    candidate.close();
                }
            }
        }

        private void validateSession(OrtSession candidate) throws Exception {
            if (candidate.getInputInfo().size() != 1) {
                throw new Exception("CNN ONNX model must have exactly one input tensor.");
            }
            NodeInfo input = candidate.getInputInfo().get(metadata.getInputName());
            NodeInfo output = candidate.getOutputInfo().get(metadata.getOutputName());
            validateTensor(input, metadata.getInputName(), 3);
            validateTensor(output, metadata.getOutputName(), 1);
        }

        private void validateTensor(NodeInfo node, String name, int channels) throws Exception {
            if (node == null || !(node.getInfo() instanceof TensorInfo)) {
                throw new Exception("ONNX tensor \"" + name + "\" is missing or is not a tensor.");
            }
            TensorInfo tensor = (TensorInfo) node.getInfo();
            long[] shape = tensor.getShape();
            if (tensor.type != OnnxJavaType.FLOAT) {
                throw new Exception("ONNX tensor \"" + name + "\" must use FLOAT32 values.");
            }
            if (shape.length != 4 || (shape[0] != -1 && shape[0] != 1)
                    || (shape[1] != -1 && shape[1] != channels)) {
                throw new Exception("ONNX tensor \"" + name + "\" must have NCHW shape [1, "
                        + channels + ", H, W].");
            }
        }

        private float[] run(PreparedInput prepared) throws OrtException {
            TensorInfo declaredInput = (TensorInfo) session.getInputInfo()
                    .get(metadata.getInputName()).getInfo();
            long[] declaredShape = declaredInput.getShape();
            if ((declaredShape[2] != -1 && declaredShape[2] != prepared.getPaddedHeight())
                    || (declaredShape[3] != -1
                            && declaredShape[3] != prepared.getPaddedWidth())) {
                throw new IllegalStateException("Prepared CNN input dimensions do not match the "
                        + "model's declared input shape.");
            }
            long[] shape = { 1, 3, prepared.getPaddedHeight(), prepared.getPaddedWidth() };
            long started = System.nanoTime();
            try (OnnxTensor input = OnnxTensor.createTensor(environment(),
                    FloatBuffer.wrap(prepared.getData()), shape);
                    OrtSession.Result result = session.run(Collections.singletonMap(
                            metadata.getInputName(), input))) {
                OnnxValue value = result.get(metadata.getOutputName()).orElseThrow(() ->
                        new IllegalStateException("ONNX output \"" + metadata.getOutputName()
                                + "\" was not returned."));
                if (!(value instanceof OnnxTensor)) {
                    throw new IllegalStateException("ONNX output \"" + metadata.getOutputName()
                            + "\" is not a tensor.");
                }
                OnnxTensor outputTensor = (OnnxTensor) value;
                TensorInfo outputInfo = (TensorInfo) outputTensor.getInfo();
                long[] outputShape = outputInfo.getShape();
                if (outputShape.length != 4 || outputShape[0] != 1 || outputShape[1] != 1
                        || outputShape[2] != prepared.getPaddedHeight()
                        || outputShape[3] != prepared.getPaddedWidth()) {
                    throw new IllegalStateException("CNN output must have runtime shape [1, 1, "
                            + prepared.getPaddedHeight() + ", " + prepared.getPaddedWidth() + "].");
                }
                FloatBuffer values = outputTensor.getFloatBuffer();
                float[] output = new float[values.remaining()];
                values.get(output);
                Logger.debug("CNN model \"{}\" CPU inference took {} ms.", metadata.getModelId(),
                        (System.nanoTime() - started) / 1_000_000.0);
                return output;
            }
        }

        private boolean filesChanged() {
            try {
                return !metadataStamp.equals(FileStamp.read(metadata.getMetadataPath()))
                        || !modelStamp.equals(FileStamp.read(metadata.getModelPath()));
            }
            catch (IOException e) {
                return true;
            }
        }

        private OrtEnvironment environment() {
            return OrtEnvironment.getEnvironment();
        }

        @Override
        public void close() {
            if (session != null) {
                try {
                    session.close();
                }
                catch (Exception e) {
                    Logger.warn(e, "Unable to close CNN model \"{}\".", metadata.getModelId());
                }
                session = null;
            }
        }
    }

    public static final class PreloadReport {
        private final Set<String> referencedModelIds;
        private final Map<String, Exception> failures;
        private final Map<Path, Exception> catalogErrors;

        private PreloadReport(Set<String> referencedModelIds, Map<String, Exception> failures,
                Map<Path, Exception> catalogErrors) {
            this.referencedModelIds = Collections.unmodifiableSet(new LinkedHashSet<>(referencedModelIds));
            this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
            this.catalogErrors = Collections.unmodifiableMap(new LinkedHashMap<>(catalogErrors));
        }

        public Set<String> getReferencedModelIds() {
            return referencedModelIds;
        }

        public Map<String, Exception> getFailures() {
            return failures;
        }

        public Map<Path, Exception> getCatalogErrors() {
            return catalogErrors;
        }

        public boolean isSuccessful() {
            return failures.isEmpty();
        }
    }
}
