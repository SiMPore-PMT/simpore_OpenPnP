// Optional ScriptRun after cnn, for the pipeline debugger. Args is a threshold (default 0.5).
// Borrow the probability model; ownership of the new binary preview transfers to CvPipeline.
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.openpnp.vision.FluentCv.ColorSpace;
import org.openpnp.vision.pipeline.CvStage;

Mat probability = (Mat) pipeline.getExpectedResult("cnn").getModel();
double threshold = args.trim().isEmpty() ? 0.5 : Double.parseDouble(args);
if (Double.isNaN(threshold) || Double.isInfinite(threshold) || threshold < 0 || threshold > 1) {
    throw new IllegalArgumentException("Threshold must be finite and in [0,1].");
}
Mat preview = new Mat();
try {
    Imgproc.threshold(probability, preview, threshold, 255, Imgproc.THRESH_BINARY);
    preview.convertTo(preview, CvType.CV_8UC1);
}
catch (Exception failure) {
    preview.release();
    throw failure;
}
new CvStage.Result(preview, ColorSpace.Gray);
