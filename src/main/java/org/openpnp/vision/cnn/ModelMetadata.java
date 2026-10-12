package org.openpnp.vision.cnn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Validated metadata for an ONNX segmentation model.
 *
 * <p>Schema version 1 is an explicit adapter contract for the DeepLabs exporter. The exporter
 * contract was not available when this adapter was added; unsupported or absent preprocessing
 * fields are rejected rather than inferred. Output interpretation may be logits or probabilities.</p>
 */
public final class ModelMetadata {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final String INPUT_LAYOUT = "NCHW";
    public static final String DATA_TYPE = "FLOAT32";
    public static final String RESIZE_AREA = "AREA";
    public static final String RESIZE_LINEAR = "LINEAR";
    public static final String PADDING_RIGHT_BOTTOM_REPLICATE = "RIGHT_BOTTOM_REPLICATE";
    public static final String OUTPUT_LOGITS = "LOGITS";
    public static final String OUTPUT_PROBABILITIES = "PROBABILITIES";

    private final int schemaVersion;
    private final String modelId;
    private final String task;
    private final Path metadataPath;
    private final Path modelPath;
    private final String inputName;
    private final String outputName;
    private final String outputInterpretation;
    private final String colorOrder;
    private final double inputScale;
    private final double[] mean;
    private final double[] std;
    private final int downsampleFactor;
    private final int inputAlignment;
    private final float defaultThreshold;

    private ModelMetadata(int schemaVersion, String modelId, String task, Path metadataPath,
            Path modelPath, String inputName, String outputName, String outputInterpretation,
            String colorOrder, double inputScale,
            double[] mean, double[] std, int downsampleFactor, int inputAlignment,
            float defaultThreshold) {
        this.schemaVersion = schemaVersion;
        this.modelId = modelId;
        this.task = task;
        this.metadataPath = metadataPath;
        this.modelPath = modelPath;
        this.inputName = inputName;
        this.outputName = outputName;
        this.outputInterpretation = outputInterpretation;
        this.colorOrder = colorOrder;
        this.inputScale = inputScale;
        this.mean = mean.clone();
        this.std = std.clone();
        this.downsampleFactor = downsampleFactor;
        this.inputAlignment = inputAlignment;
        this.defaultThreshold = defaultThreshold;
    }

    /** Read and strictly validate a schema-version-1 model description. */
    public static ModelMetadata read(Path jsonPath) throws IOException {
        if (jsonPath == null) {
            throw new IOException("Model metadata path must be specified.");
        }
        final Path metadataPath;
        final Path metadataDirectory;
        Path normalizedPath = jsonPath.toAbsolutePath().normalize();
        if (normalizedPath.getParent() == null || normalizedPath.getFileName() == null) {
            throw new IOException("Model metadata path must name a file.");
        }
        if (Files.isSymbolicLink(normalizedPath)) {
            throw new IOException("Model metadata files must not be symbolic links: " + jsonPath);
        }
        try {
            metadataDirectory = normalizedPath.getParent().toRealPath();
            metadataPath = jsonPath.toRealPath();
        }
        catch (IOException e) {
            throw new IOException("Model metadata file does not exist: " + jsonPath, e);
        }
        Path expectedPath = metadataDirectory.resolve(normalizedPath.getFileName()).normalize();
        if (!expectedPath.equals(metadataPath)) {
            throw new IOException("Model metadata files must not be symbolic links: "
                    + jsonPath);
        }
        if (!Files.isRegularFile(metadataPath)) {
            throw new IOException("Model metadata path is not a regular file: " + metadataPath);
        }

        try (java.io.Reader reader = Files.newBufferedReader(metadataPath)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                throw invalid("The metadata root must be a JSON object.");
            }
            JsonObject root = parsed.getAsJsonObject();
            requireKeys(root, "schemaVersion", "modelId", "modelPath", "task", "input", "output",
                    "downsampleFactor", "inputAlignment", "defaultThreshold");

            int version = integer(root, "schemaVersion", 1, Integer.MAX_VALUE);
            if (version != SUPPORTED_SCHEMA_VERSION) {
                throw invalid("Unsupported schemaVersion " + version + "; supported version is "
                        + SUPPORTED_SCHEMA_VERSION + ".");
            }
            String id = string(root, "modelId");
            String modelPathText = string(root, "modelPath");
            String task = string(root, "task");

            JsonObject input = object(root, "input");
            requireKeys(input, "name", "layout", "dataType", "colorOrder", "scale", "mean", "std",
                    "resizeInterpolation", "padding");
            String inputName = string(input, "name");
            requireValue(input, "layout", INPUT_LAYOUT);
            requireValue(input, "dataType", DATA_TYPE);
            String colorOrder = string(input, "colorOrder");
            if (!"RGB".equals(colorOrder) && !"BGR".equals(colorOrder)) {
                throw invalid("input.colorOrder must be RGB or BGR.");
            }
            double inputScale = number(input, "scale");
            if (inputScale <= 0) {
                throw invalid("input.scale must be finite and greater than zero.");
            }
            double[] mean = numberArray(input, "mean", 3, false);
            double[] std = numberArray(input, "std", 3, true);
            requireValue(input, "resizeInterpolation", RESIZE_AREA);
            requireValue(input, "padding", PADDING_RIGHT_BOTTOM_REPLICATE);

            JsonObject output = object(root, "output");
            requireKeys(output, "name", "layout", "dataType", "interpretation", "foregroundChannel",
                    "resizeInterpolation");
            String outputName = string(output, "name");
            requireValue(output, "layout", INPUT_LAYOUT);
            requireValue(output, "dataType", DATA_TYPE);
            String outputInterpretation = string(output, "interpretation");
            if (!OUTPUT_LOGITS.equals(outputInterpretation)
                    && !OUTPUT_PROBABILITIES.equals(outputInterpretation)) {
                throw invalid("output.interpretation must be LOGITS or PROBABILITIES.");
            }
            int foregroundChannel = integer(output, "foregroundChannel", 0, 0);
            if (foregroundChannel != 0) {
                throw invalid("output.foregroundChannel must be 0 for a one-channel foreground output.");
            }
            requireValue(output, "resizeInterpolation", RESIZE_LINEAR);

            int downsampleFactor = integer(root, "downsampleFactor", 1, Integer.MAX_VALUE);
            int inputAlignment = integer(root, "inputAlignment", 1, Integer.MAX_VALUE);
            double threshold = number(root, "defaultThreshold");
            if (threshold < 0 || threshold > 1) {
                throw invalid("defaultThreshold must be between 0 and 1 inclusive.");
            }
            Path modelPath = resolveModelPath(metadataDirectory, modelPathText);
            return new ModelMetadata(version, id, task, metadataPath, modelPath, inputName, outputName,
                    outputInterpretation, colorOrder, inputScale, mean, std, downsampleFactor,
                    inputAlignment, (float) threshold);
        }
        catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Invalid model metadata " + metadataPath + ": " + e.getMessage(), e);
        }
        catch (com.google.gson.JsonParseException e) {
            throw new IOException("Invalid JSON in model metadata " + metadataPath + ": " + e.getMessage(), e);
        }
    }

    private static Path resolveModelPath(Path metadataDirectory, String modelPathText) throws IOException {
        Path relative;
        try {
            relative = java.nio.file.Paths.get(modelPathText);
        }
        catch (RuntimeException e) {
            throw invalid("modelPath is not a valid relative path.");
        }
        if (relative.isAbsolute() || modelPathText.startsWith("\\\\")
                || modelPathText.matches("^[A-Za-z]:[/\\\\].*")) {
            throw invalid("modelPath must be relative to the metadata file.");
        }
        for (String segment : modelPathText.replace('\\', '/').split("/")) {
            if ("..".equals(segment)) {
                throw invalid("modelPath must not traverse outside the metadata directory.");
            }
        }
        Path normalized = metadataDirectory.resolve(relative).normalize();
        if (!normalized.startsWith(metadataDirectory)) {
            throw invalid("modelPath must remain inside the metadata directory.");
        }
        Path component = metadataDirectory;
        for (Path segment : metadataDirectory.relativize(normalized)) {
            component = component.resolve(segment);
            if (Files.isSymbolicLink(component)) {
                throw invalid("modelPath must not use symbolic links; install regular model files beside metadata.");
            }
        }
        final Path resolved;
        try {
            resolved = normalized.toRealPath();
        }
        catch (IOException e) {
            throw new IOException("ONNX model file does not exist: " + normalized, e);
        }
        if (!resolved.startsWith(metadataDirectory) || !Files.isRegularFile(resolved)) {
            throw invalid("modelPath must resolve to a regular file inside the metadata directory.");
        }
        return resolved;
    }

    private static JsonObject object(JsonObject parent, String name) throws IOException {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonObject()) {
            throw invalid(name + " must be a JSON object.");
        }
        return element.getAsJsonObject();
    }

    private static void requireKeys(JsonObject object, String... keys) throws IOException {
        for (String key : keys) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                throw invalid("Missing required field " + key + ".");
            }
        }
        for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            if (!Arrays.asList(keys).contains(key)) {
                throw invalid("Unsupported field " + key + ".");
            }
        }
    }

    private static String string(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid(name + " must be a string.");
        }
        String value = element.getAsString();
        if (value.isEmpty()) {
            throw invalid(name + " must not be empty.");
        }
        if (!value.equals(value.trim())) {
            throw invalid(name + " must not start or end with whitespace.");
        }
        return value;
    }

    private static double number(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(name + " must be a number.");
        }
        double value = element.getAsDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw invalid(name + " must be finite.");
        }
        return value;
    }

    private static int integer(JsonObject object, String name, int minimum, int maximum) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(name + " must be an integer.");
        }
        String number = element.getAsString();
        if (!number.matches("-?(0|[1-9][0-9]*)")) {
            throw invalid(name + " must be an integer.");
        }
        final long value;
        try {
            value = Long.parseLong(number);
        }
        catch (NumberFormatException e) {
            throw invalid(name + " is outside the supported integer range.");
        }
        if (value < minimum || value > maximum) {
            throw invalid(name + " must be an integer from " + minimum + " to " + maximum + ".");
        }
        return (int) value;
    }

    private static double[] numberArray(JsonObject object, String name, int size, boolean positive)
            throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != size) {
            throw invalid(name + " must contain exactly " + size + " numbers.");
        }
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            JsonElement value = element.getAsJsonArray().get(i);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw invalid(name + " values must be numbers.");
            }
            values[i] = value.getAsDouble();
            if (Double.isNaN(values[i]) || Double.isInfinite(values[i])
                    || (positive && values[i] <= 0)) {
                throw invalid(name + " values must be finite" + (positive ? " and positive." : "."));
            }
        }
        return values;
    }

    private static void requireValue(JsonObject object, String name, String expected) throws IOException {
        String actual = string(object, name);
        if (!expected.equals(actual)) {
            throw invalid(name + " has unsupported value " + actual + "; expected " + expected + ".");
        }
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getModelId() { return modelId; }
    public String getTask() { return task; }
    public Path getMetadataPath() { return metadataPath; }
    public Path getModelPath() { return modelPath; }
    public String getInputName() { return inputName; }
    public String getOutputName() { return outputName; }
    public String getOutputInterpretation() { return outputInterpretation; }
    public boolean isOutputLogits() { return OUTPUT_LOGITS.equals(outputInterpretation); }
    public String getColorOrder() { return colorOrder; }
    public double getInputScale() { return inputScale; }
    public double[] getMean() { return mean.clone(); }
    public double[] getStd() { return std.clone(); }
    public int getDownsampleFactor() { return downsampleFactor; }
    public int getInputAlignment() { return inputAlignment; }
    public float getDefaultThreshold() { return defaultThreshold; }
}
