# CNN test models

These tiny ONNX files are deterministic test fixtures, not trained models.
They exercise the ONNX Runtime CPU path without requiring a checkpoint or an
external model download.

`mean_channels_nchw_to_n1hw.onnx` has a dynamic NCHW float input with three
channels and returns the per-pixel mean over those channels, retaining the
channel dimension (`N1HW`). The other files exercise malformed output rank,
transposed runtime dimensions with the same element count, an extra required
input, a non-FLOAT32 tensor, and changed model contents for catalog refresh.

The fixture set is:

- `mean_channels_nchw_to_n1hw.onnx`: valid dynamic input and output.
- `invalid_rank_nchw_to_nhw.onnx`: output drops the channel axis.
- `transposed_nchw_to_n1wh.onnx`: output swaps height and width.
- `extra_input_nchw_to_n1hw.onnx`: output depends on a second required input.
- `float16_nchw_to_n3hw.onnx`: uses FLOAT16 tensors and an Identity node.
- `mean_plus_one_nchw_to_n1hw.onnx`: adds one to the per-pixel mean.

Regenerate them with Python 3 and the development-only `onnx==1.17.0` package:

```sh
python3 -m pip install onnx==1.17.0
python3 src/test/resources/org/openpnp/vision/cnn/generate_models.py
```

The generated models use ONNX opset 13 and contain one `ReduceMean` operation.
No Python package is needed to build or run OpenPnP; production inference uses
the official ONNX Runtime Java dependency.
