# Native CNN models in OpenPnP

This note describes the native CPU ONNX integration and how to deploy a model with OpenPnP. The integration follows the existing vision pipeline: an ordinary `CvStage` runs inference, and a later stage or `ScriptRun` interprets the probability map for a specific task. It does not assign mechanical coordinates. OpenPnP's existing feeder, fiducial and part-alignment consumers remain responsible for coordinate transforms and motion decisions.

## Deployment

Place each metadata file directly in the active OpenPnP configuration's `models/` directory. Its ONNX file may be in that directory or a nested subdirectory:

```text
<configuration directory>/models/<model-id>.json
<configuration directory>/models/<model-id>.onnx
```

For example, `modelPath` may be `die-segmentation.onnx` or `weights/die-segmentation.onnx`.

For example, the default user configuration is `~/.openpnp2`; a custom directory can be selected with `-DconfigDir=/path/to/config`. Reference the ONNX filename from the metadata using a path relative to that metadata file. Keep the model ID stable and unique. The model files are user-managed deployment assets and do not belong in the source repository.

The model catalog scans JSON metadata files directly inside `models/`; each referenced ONNX file must resolve inside `models/`. Metadata and model assets must be regular non-symlink files, although model files may be nested below `models/`. Symlink-based model installation or replacement is unsupported; copy the assets into `models/` and replace them atomically. The configuration directory itself may resolve through a symlink. The Java runtime uses the official ONNX Runtime Java API, CPU execution provider, version 1.19.2. Its Maven artifact packages the native runtime for supported host platforms, including x86-64 Linux (Intel NUCs without NVIDIA hardware) and Windows development systems. Inference does not require Python, CUDA, or a separate process.

After copying the files, add a `CNN` stage to a vision pipeline, select the model ID from the discovered IDs, and save the pipeline. The ONNX session is shared for stages that select the same model. Pipeline execution loads a model on demand if it was not found during configuration preload. Use `Capture → CNN → ScriptRun` for task-specific interpretation. See [samples/cnn/README.md](../samples/cnn/README.md) and [samples/cnn/mask-preview.java](../samples/cnn/mask-preview.java) for the result contract and an optional preview.

## Metadata contract (schema version 1)

The DeepLabs exporter is maintained separately. The implementation was compared with the preprocessing and inference sources at [DeepLabs_Lumen commit `c518e10b6ac29fba733cd9474909ae2fbfbe2ae4`](https://github.com/SiMPore-PMT/DeepLabs_Lumen/tree/c518e10b6ac29fba733cd9474909ae2fbfbe2ae4), including the training/checkpoint definitions used by those paths. That source confirms the image transforms represented below. The trained ONNX files and companion metadata are not included in this repository, so verify the exported artifact's actual tensor names and shapes before deployment. The v1 schema is an explicit adapter contract; unsupported or absent values are rejected rather than inferred.

```json
{
  "schemaVersion": 1,
  "modelId": "die-segmentation",
  "modelPath": "die-segmentation.onnx",
  "task": "die-segmentation",
  "input": {
    "name": "input",
    "layout": "NCHW",
    "dataType": "FLOAT32",
    "colorOrder": "RGB",
    "scale": 0.00392156862745098,
    "mean": [0.485, 0.456, 0.406],
    "std": [0.229, 0.224, 0.225],
    "resizeInterpolation": "AREA",
    "padding": "RIGHT_BOTTOM_REPLICATE"
  },
  "output": {
    "name": "logits",
    "layout": "NCHW",
    "dataType": "FLOAT32",
    "interpretation": "LOGITS",
    "foregroundChannel": 0,
    "resizeInterpolation": "LINEAR"
  },
  "downsampleFactor": 1,
  "inputAlignment": 1,
  "defaultThreshold": 0.5
}
```

`mean` and `std` contain one value per channel. `downsampleFactor` and `inputAlignment` must be positive integers. The input color order, scale, normalization, resize and padding values must match the exported model. A one-channel foreground output may be declared as `LOGITS` or `PROBABILITIES`: the runtime applies sigmoid only to logits and validates probability outputs are finite and in `[0,1]`. It removes input padding and restores a native image-resolution probability map. The default threshold is a downstream mask setting; the floating-point probabilities are preserved.

The Java boundary in `src/main/java/org/openpnp/vision/cnn/` is `ModelMetadata` for validated metadata, `ModelPreprocessor` for converting an OpenCV image to NCHW input and restoring output, and an internal immutable probability payload. `CNN.process()` converts the restored payload to the pipeline-facing OpenCV matrix described below.

## Pipeline and script integration

`CvPipeline` stores a `CvStage.Result` for each stage. The `CNN` stage exposes a native-resolution, single-channel `CV_32FC1` OpenCV `Mat` of finite probabilities in `[0,1]` as `Result.model`. It applies sigmoid once when metadata declares logits, removes right/bottom padding, and restores the original input dimensions. It leaves the camera image as the working image; it does not threshold the result or calculate geometry.

`ScriptRun` is registered with `CvPipelineEditor` and exposes `pipeline`, `stage`, and `args` to the configured Jython script. The script reads the probability Mat with:

```python
cnn_result = pipeline.getExpectedResult("cnn")
probability = cnn_result.getModel()  # CV_32FC1 Mat; borrowed for this pipeline run
```

Treat that Mat as borrowed while downstream stages run: do not release it. `CvPipeline` owns and releases CNN result Mats when the pipeline is reprocessed, released, or closed. Clone the Mat before retaining it beyond the pipeline run, and release the clone when finished. Thresholding and contour/geometry decisions belong in the script or subsequent vision stages. A `.py` ScriptRun uses the service-loaded Jython engine. Because Jython file-mode `eval` returns `None` even when a file ends with an expression, OpenPnP's existing ScriptRun now captures the final Jython expression or assignment value once in a unique internal binding, then returns it if it is a `CvStage.Result`. For example, the script can end with:

```python
from org.openpnp.vision.pipeline import CvStage
CvStage.Result(None, geometry)  # geometry is an OpenCV RotatedRect
```

The final expression or assignment must evaluate to an actual Java `CvStage.Result`; arbitrary Python objects such as dictionaries are not interpreted as geometry results. Script contents are executed once, in the configured Jython engine, without rewriting the selected file or adding guessed result-variable names. Other script engines retain their existing behavior. A returned placement result must match its consumer's contract; existing feeder pipelines commonly expect `List<RotatedRect>`, while `ReferenceBottomVision` expects one `RotatedRect`. The requested `CNN_BestSquare.py` was not included with the supplied files, so this guide does not implement or claim its square-fitting behavior or angle convention. Its stated arguments are `cnnStage=cnn;threshold=0.5;mode=die`, with `mode=dry` and `mode=wet` for pockets. The probability map itself is never a part center, orientation, or machine location. Pipeline processing and XML serialization are synchronized because serialization resets stage defaults and cannot race an in-progress inference.

The current source of truth for model references is each serialized stage's selected model ID. Configuration preload should inspect persistent pipeline owners where available, including `Configuration.getVisionSettings()` (bottom-vision and fiducial settings), machine feeder pipelines, and nozzle-tip/camera calibration pipelines. Not every pipeline is enumerable: feeders and calibrations can clone pipelines, editor and training paths can construct temporary pipelines, and plugins can supply their own. Consequently, stage execution must also resolve its model ID through the shared manager on demand. Model discovery is a preload optimization, not a correctness requirement.

Relevant existing owners and execution paths include:

- `src/main/java/org/openpnp/model/AbstractVisionSettings.java` persists the shared bottom/fiducial `CvPipeline`; `src/main/java/org/openpnp/machine/reference/vision/ReferenceBottomVision.java` and `ReferenceFiducialLocator.java` prepare and execute those pipelines.
- `src/main/java/org/openpnp/machine/reference/feeder/JEDEC_TrayFeeder.java` uses configured fiducial vision settings and clones the active pipeline for execution.
- Feeder-owned pipelines exist in `ReferencePushPullFeeder`, `ReferenceLoosePartFeeder`, `ReferenceStripFeeder`, `ReferenceHeapFeeder` (including per-drop-box pipelines), `AdvancedLoosePartFeeder`, `BlindsFeeder`, and `machine/pandaplacer/AbstractPandaplacerVisionFeeder`.
- `src/main/java/org/openpnp/machine/reference/ReferenceNozzleTipCalibration.java` and `src/main/java/org/openpnp/machine/reference/camera/calibration/AdvancedCalibration.java` own additional pipelines.
- The standard pipeline editor and script stage live in `src/main/java/org/openpnp/vision/pipeline/ui/CvPipelineEditor.java` and `src/main/java/org/openpnp/vision/pipeline/stages/ScriptRun.java`.

## Lifecycle, concurrency, and failures

`CnnModelManager` in `src/main/java/org/openpnp/vision/cnn/CnnModelManager.java` is held in a weak-keyed map by `Configuration`; the manager does not retain the configuration. `get(Configuration)` creates only the configuration-specific model directory path and a loader executor. `listModelIds()` parses the small metadata files without hashing model weights or initializing ONNX Runtime, so the stage editor can list models on the Swing event-dispatch thread. `CnnPipelineDiscovery.findReferencedModelIds(Machine)` scans persistent references; `preloadReferencedModels(Machine)` uses a separate single-thread loader. `ReferenceMachine.setEnabled(true)` awaits preload before firing `machineEnabled`. If configured model references exist and enabling is invoked on the Swing event-dispatch thread, enabling throws an instruction to retry from a worker thread rather than blocking Swing. Per-model preload failures are logged and stored as failed model state; they do not roll back drivers or disable unrelated machine functionality.

Normal GUI enable requests run through the machine task executor, which is a single-thread queue in `AbstractMachine`. Loading uses the manager's separate single-thread executor, avoiding a wait on the machine executor from its own task. The manager coalesces simultaneous requests for one model and retains one reusable session per model. The CPU provider uses one intra-op thread and one inter-op thread. One global inference permit bounds preprocessing, inference, and output restoration to a single active inference across models on low-power machines.

Machine disable retains sessions to avoid reload churn. On re-enable, the manager hashes installed metadata/model files, retains sessions with unchanged contents, and replaces changed sessions. Before each inference, inexpensive file stamps (size, modified time, and file key) detect replacements; a detected change triggers a locked catalog refresh and SHA-256 comparison before using the session. A file change during inference causes that result to be discarded. Atomic replacement is therefore detected on the next inference. If model contents are changed in place while preserving all three stamp values, call `refreshCatalog()` or `invalidate(modelId)` explicitly before the next inference; ordinary installs should use atomic file replacement. Shutdown through `ReferenceMachine.close()` rejects new calls, waits for active inference, and closes sessions and the loader once. Closing is terminal for that `Configuration` instance; the manager keeps a weak-keyed closed-manager tombstone and rejects new manager access for it. Restarting the manager requires a new `Configuration` object. ONNX Runtime 1.19.2 exposes cooperative cancellation through `RunOptions.setTerminate()`, but this integration does not implement inference cancellation or a deadline. A long or hung native call can therefore delay manager/editor/application shutdown while resources remain protected from disposal. Qualify inference latency and shutdown behavior on the target Linux NUC.

An absent model, invalid metadata, session creation failure, wrong tensor shape, or inference error is a pipeline error. `CvPipeline.process()` catches ordinary stage exceptions and continues executing later stages, so `src/main/java/org/openpnp/vision/pipeline/stages/CNN.java` raises `TerminalException` on failure. This makes `process()` report the error instead of allowing a downstream script to use stale results. When a successfully produced CNN result is present, ScriptRun converts script evaluation errors, missing script files, and missing or incompatible script results into terminal failures. Dependent feeder/alignment/fiducial operations then fail before using an old coordinate or empty mask. Unrelated OpenPnP functions remain available when one CNN model fails. Keep model and inference diagnostics clear and avoid per-frame production log noise.

## Repository and verification notes

The DeepLabs preprocessing source is verified at the cited commit, but no exported model and companion metadata are included here. Fixture tests do not establish compatibility with the actual exported artifact. Verify its input/output names and dimensions, channel ordering, normalization, padding, logits/probabilities, and restored mask on the target Linux NUC.

Validation in the cloud environment used Linux x86-64, Java 21, Maven 3.9.9, Xvfb, ONNX Runtime CPU 1.19.2, and deterministic ONNX fixtures. The final full `clean package` run passed 309 tests with zero failures, errors, or skips, including 40 focused CNN/interface tests. A final identical-content atomic model-reinstallation fix was then verified by all 12 manager tests and a successful package build. Checkstyle passed without bypasses. All introduced and modified production classes also compiled against Java 11 APIs with `javac --release 11`; this is an API compatibility check, not a Java 11 runtime test. The packaged GUI started under Xvfb with its main window and welcome log verified, and repeating the saved startup script retained the existing instance. The updated installation script also completed successfully on repeat execution. These checks exercise tensor validation, preprocessing restoration, concurrent session reuse, lifecycle preload/close, Mat ownership, preserved camera images, terminal failures, actual Jython expression/assignment returns, and unchanged non-CNN script error behavior. The Jython test rectangle is only an interoperability fixture; it does not implement the user script's square fitting.

The requested GPT-5.6 Luna model was unavailable, so routine investigation/tests used GPT-6 Luna High. GPT-5.6 Sol Medium handled interoperability and reviewed the introduced changes. Astra was skipped because its strict input-context limit could not be guaranteed; Sol performed the final review instead. Delegated agents were instructed not to spawn further agents.

Real trained ONNX/export metadata, `CNN_BestSquare.py`, physical cameras/machine hardware, Windows execution, and Java 11 runtime execution were not available for qualification. Square selection and normalized rotation behavior remain unverified until the supplied script and representative model/images are provided.

## Modified file list

- `docs/cnn-onnx.md`
- `pom.xml`
- `samples/cnn/README.md`
- `samples/cnn/mask-preview.java`
- `samples/cnn/pipeline.xml`
- `samples/cnn/rectangle-list.java`
- `src/main/java/org/openpnp/machine/reference/ReferenceMachine.java`
- `src/main/java/org/openpnp/vision/cnn/CnnModelManager.java`
- `src/main/java/org/openpnp/vision/cnn/CnnPipelineDiscovery.java`
- `src/main/java/org/openpnp/vision/cnn/ModelMetadata.java`
- `src/main/java/org/openpnp/vision/cnn/ModelPreprocessor.java`
- `src/main/java/org/openpnp/vision/cnn/ProbabilityMap.java`
- `src/main/java/org/openpnp/vision/pipeline/CvPipeline.java`
- `src/main/java/org/openpnp/vision/pipeline/stages/CNN.java`
- `src/main/java/org/openpnp/vision/pipeline/stages/ScriptRun.java`
- `src/main/java/org/openpnp/vision/pipeline/ui/CvPipelineEditor.java`
- `src/test/java/org/openpnp/machine/reference/feeder/JEDEC_TrayFeederTest.java`
- `src/test/java/org/openpnp/vision/cnn/CNNStageTest.java`
- `src/test/java/org/openpnp/vision/cnn/CnnModelManagerTest.java`
- `src/test/java/org/openpnp/vision/cnn/JythonInteropTest.java`
- `src/test/java/org/openpnp/vision/cnn/ModelMetadataTest.java`
- `src/test/java/org/openpnp/vision/cnn/ModelPreprocessorTest.java`
- `src/test/java/org/openpnp/vision/cnn/PipelineSerializationTest.java`
- `src/test/java/org/openpnp/vision/pipeline/PipelineMatOwnershipTest.java`
- `src/test/resources/org/openpnp/vision/cnn/README.md`
- `src/test/resources/org/openpnp/vision/cnn/extra_input_nchw_to_n1hw.onnx`
- `src/test/resources/org/openpnp/vision/cnn/float16_nchw_to_n3hw.onnx`
- `src/test/resources/org/openpnp/vision/cnn/generate_models.py`
- `src/test/resources/org/openpnp/vision/cnn/invalid_rank_nchw_to_nhw.json`
- `src/test/resources/org/openpnp/vision/cnn/invalid_rank_nchw_to_nhw.onnx`
- `src/test/resources/org/openpnp/vision/cnn/mean_channels_nchw_to_n1hw.json`
- `src/test/resources/org/openpnp/vision/cnn/mean_channels_nchw_to_n1hw.onnx`
- `src/test/resources/org/openpnp/vision/cnn/mean_plus_one_nchw_to_n1hw.onnx`
- `src/test/resources/org/openpnp/vision/cnn/return_rectangle.py`
- `src/test/resources/org/openpnp/vision/cnn/transposed_nchw_to_n1wh.onnx`
