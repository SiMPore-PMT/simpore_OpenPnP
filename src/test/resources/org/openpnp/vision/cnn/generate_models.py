"""Regenerate the deterministic ONNX fixtures in this directory."""

from pathlib import Path

import onnx
from onnx import TensorProto, helper


FIXTURE_DIR = Path(__file__).resolve().parent


def make_model(
    filename: str,
    input_type: int = TensorProto.FLOAT,
    output_mode: str = "mean",
) -> None:
    input_info = helper.make_tensor_value_info(
        "image", input_type, ["batch", 3, "height", "width"]
    )
    output_type = input_type
    output_shape = ["batch", 1, "height", "width"]
    nodes = [
        helper.make_node(
            "ReduceMean",
            inputs=["image"],
            outputs=["mean"],
            axes=[1],
            keepdims=1,
        )
    ]
    graph_inputs = [input_info]
    initializers = []

    if output_mode == "identity":
        nodes = [helper.make_node("Identity", inputs=["image"], outputs=["logits"])]
        output_shape = ["batch", 3, "height", "width"]
    elif output_mode == "rank3":
        nodes[0] = helper.make_node(
            "ReduceMean",
            inputs=["image"],
            outputs=["logits"],
            axes=[1],
            keepdims=0,
        )
        output_shape = ["batch", "height", "width"]
    elif output_mode == "transpose":
        nodes.append(
            helper.make_node(
                "Transpose", inputs=["mean"], outputs=["logits"], perm=[0, 1, 3, 2]
            )
        )
        output_shape = ["batch", 1, "width", "height"]
    elif output_mode == "offset":
        offset = helper.make_tensor("offset", input_type, [], [1.0])
        initializers.append(offset)
        nodes.append(
            helper.make_node("Add", inputs=["mean", "offset"], outputs=["logits"])
        )
    elif output_mode == "extra_input":
        extra_info = helper.make_tensor_value_info("offset", input_type, [])
        graph_inputs.append(extra_info)
        nodes.append(
            helper.make_node("Add", inputs=["mean", "offset"], outputs=["logits"])
        )
    elif output_mode == "mean":
        nodes[0] = helper.make_node(
            "ReduceMean",
            inputs=["image"],
            outputs=["logits"],
            axes=[1],
            keepdims=1,
        )
    else:
        raise ValueError(f"Unsupported fixture mode: {output_mode}")

    output_info = helper.make_tensor_value_info(
        "logits", output_type, output_shape
    )
    graph = helper.make_graph(
        nodes,
        "cnn_test_fixture",
        graph_inputs,
        [output_info],
        initializer=initializers,
    )
    model = helper.make_model(
        graph,
        opset_imports=[helper.make_opsetid("", 13)],
        producer_name="OpenPnP deterministic CNN test fixture",
    )
    onnx.checker.check_model(model)
    onnx.save(model, FIXTURE_DIR / filename)


def main() -> None:
    make_model("mean_channels_nchw_to_n1hw.onnx")
    make_model("invalid_rank_nchw_to_nhw.onnx", output_mode="rank3")
    make_model("transposed_nchw_to_n1wh.onnx", output_mode="transpose")
    make_model("extra_input_nchw_to_n1hw.onnx", output_mode="extra_input")
    make_model(
        "float16_nchw_to_n3hw.onnx",
        input_type=TensorProto.FLOAT16,
        output_mode="identity",
    )
    make_model("mean_plus_one_nchw_to_n1hw.onnx", output_mode="offset")


if __name__ == "__main__":
    main()
