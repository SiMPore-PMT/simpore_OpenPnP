// Pass an OpenPnP RotatedRect through as java.util.List<RotatedRect>.
// This changes ONLY the result container. No changes to center, width,
// height or rotation; no implicit square correction or 10-pixel expansion.
//
// ScriptRun stage name: results (when feeding JEDEC), or rectAsList
// ScriptRun Args: preResults
//   where preResults is your CNN_BestSquare ScriptRun stage's name.
// If used for JEDEC, the feeder expects the FINAL stage name "results".

var ArrayList = Java.type("java.util.ArrayList");
var JavaList = Java.type("java.util.List");
var RotatedRect = Java.type("org.opencv.core.RotatedRect");
var Result = Packages.org.openpnp.vision.pipeline.CvStage.Result;

var stageName = String(stage.getArgs() || "").trim().split(/\s+/)[0];
if (!stageName) {
    throw new Error("rectAsList: set ScriptRun Args to source stage name, e.g. preResults");
}

var previous = pipeline.getExpectedResult(stageName);
var model = previous.getModel();
var rectangles = new ArrayList();

if (model instanceof RotatedRect) {
    // Keep the SAME object; do not reconstruct a RotatedRect.
    rectangles.add(model);
} else if (model instanceof JavaList) {
    for (var i = 0; i < model.size(); ++i) {
        var rect = model.get(i);
        if (!(rect instanceof RotatedRect)) {
            throw new Error("rectAsList: invalid element " + i + " in stage " + stageName);
        }
        rectangles.add(rect);
    }
} else {
    throw new Error("rectAsList: source stage " + stageName +
        " must return RotatedRect or List<RotatedRect>, got " + model);
}

// Null image: CvPipeline retains its existing working image.
// Last expression is the returned stage result.
new Result(null, rectangles);
