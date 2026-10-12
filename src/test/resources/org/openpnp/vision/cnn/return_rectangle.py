from org.opencv.core import CvType, Point, RotatedRect, Size
from org.openpnp.vision.pipeline import CvStage


def parse_threshold(arguments):
    threshold = 0.5
    for argument in arguments.split(';'):
        if argument.strip().startswith('threshold='):
            threshold = float(argument.split('=', 1)[1])
    return threshold


threshold = parse_threshold(args)
cnn_result = pipeline.getExpectedResult('cnn')
probability = cnn_result.getModel()
if probability.type() != CvType.CV_32FC1:
    raise TypeError('CNN model must be a single-channel float Mat')
if probability.cols() != 6 or probability.rows() != 4:
    raise ValueError('CNN model must retain the expected 6x4 native dimensions')

sample = probability.get(1, 2)[0]
if not (0.0 <= sample <= 1.0):
    raise ValueError('CNN probability sample is outside [0, 1]')
if sample < threshold:
    raise ValueError('CNN probability sample is below the requested threshold')

# This fixture checks Java/Jython object interoperability only. It deliberately does
# not implement segmentation or square fitting; the rectangle is deterministic.
rectangle = RotatedRect(Point(3.0, 2.0), Size(2.0, 1.0), 17.0)
CvStage.Result(None, rectangle)
