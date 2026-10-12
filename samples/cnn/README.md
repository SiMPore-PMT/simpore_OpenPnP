# CNN pipeline interface

Install a model and validated metadata as described in
[the deployment guide](../../docs/cnn-onnx.md). Import `pipeline.xml` into the
existing editor and select the installed model under **CNN → modelId**.

The `cnn` stage keeps the working camera image intact. Its model is a
native-resolution, single-channel `CV_32FC1` OpenCV probability matrix:

```python
probability = pipeline.getExpectedResult("cnn").getModel()
```

The matrix is borrowed from the pipeline. It stays valid for downstream stages
and until the pipeline is released, closed, or processed again. Do not release
it yourself; clone it if retaining it beyond that frame, and release your clone.
CNN does not threshold or interpret geometry.

The sample XML references the user-supplied `CNN_BestSquare.py`. Select its actual
location in ScriptRun's file picker. That script was not present when this
interface was implemented; this repository does not substitute a square-fitting
implementation or claim verification of its mathematics. Its stated arguments
are `cnnStage=cnn;threshold=0.5;mode=die`, with `mode=dry` and `mode=wet` for pockets.

The supplied script's stated output is a single `CvStage.Result` containing a
`RotatedRect`, matching bottom-alignment consumers. Tray-feeder consumers require
`List<RotatedRect>`; `rectangle-list.java` demonstrates a downstream adapter that
wraps the rectangle from a stage named `square` in a list without changing its
angle or geometry. Fiducials require keypoints.

Jython's last evaluated expression can return the actual Java result:

```python
from org.openpnp.vision.pipeline import CvStage
# geometry is the supplied script's RotatedRect, not a Python dictionary.
CvStage.Result(None, geometry)
```

Jython's ordinary file evaluation discards final values. ScriptRun captures a
final expression or simple assignment in an internal binding, executing the
script once through the existing engine. No particular variable name is needed.
The script file and its mathematical operations are unchanged. Script errors,
missing files, and incompatible results after CNN are terminal, so dependent
positioning cannot use earlier frame results.

`mask-preview.java` is an optional existing-engine ScriptRun example. It reads
the probability Mat and produces a binary debugger image using its numeric
`args` threshold (default `0.5`). It intentionally changes the working image for
preview; CNN itself does not. Do not replace a consumer's `results` geometry
stage with a preview.
