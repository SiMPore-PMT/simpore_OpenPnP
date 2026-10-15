// Put this ScriptRun after the supplied square script named "square", and name it "results".
// Adapt to tray feeders without changing pixel coordinates, dimensions, or angle.
import java.util.ArrayList;
import java.util.List;
import org.opencv.core.RotatedRect;
import org.openpnp.vision.pipeline.CvStage;

RotatedRect geometry = pipeline.getExpectedResult("square").getExpectedModel(RotatedRect.class);
List rectangles = new ArrayList();
rectangles.add(geometry);
new CvStage.Result(null, rectangles);
