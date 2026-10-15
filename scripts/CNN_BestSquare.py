# CNN_BestSquare.py - OpenPnP ScriptRun, compatible with Jython 2.7.
#
# The DeepLabs CNN outputs native-resolution CV_32FC1 foreground probabilities.
# Select the appropriate connected component just like the DeepLabs debugger,
# then optionally use four-edge probability fitting and/or enforce a PHYSICAL square.
# No grayscale edge detection, arbitrary shrinking, or X/Y shift.
#
# ScriptRun args:
#   cnnStage=cnn;mode=die;threshold=0.5;fit=minarea;debug=1
#   cnnStage=cnn;mode=pocket;threshold=0.5;fit=minarea;debug=1
#   fit=square           Four-edge probability fit, then enforce a physical square.
#   fit=probsquare       Existing four-edge probability RECTANGLE (legacy mode, not square).
#   fit=minarea         Original DeepLabs minAreaRect rectangle (default; fit=debugger alias).
#   fit=debugger_square Original minAreaRect followed by a balanced physical square.
#   fit=minarea_square  Alias for debugger_square; minarea aliases debugger.
#   sidePx=450          Optional desired horizontal size in pixels for four-edge methods.
#   edgeProb=0.5        Iso-probability for four-edge fitting (independent of threshold).
#
# Debug images saved to /tmp on every run (debug=1, default):
#   lumen_cnn_input.png, lumen_cnn_mask.png, lumen_cnn_probability.png,
#   lumen_cnn_fit.png (predicted square drawn in green).
#
# Returns CvStage.Result(None, RotatedRect), with signed IMAGE angle [-45,45).
# The OpenPnP caller still handles mapping camera image angle to machine rotation.

import math
from java.util import ArrayList
from jarray import zeros, array
from org.opencv.core import Core, CvType, Mat, MatOfPoint2f, RotatedRect, Size, Scalar, Point
from org.opencv.imgproc import Imgproc
from org.opencv.imgcodecs import Imgcodecs
from org.openpnp.vision.pipeline import CvStage

cnn_stage = 'cnn'
threshold = 0.5
mode = 'die'
fit = 'debugger'
debug = True
side_px = None
edge_prob = 0.5
min_area_fraction = 0.00025
edge_margin = 2
max_aspect_ratio = 1.40
min_fill_ratio = 0.50

if args is not None and str(args).strip():
    for field in str(args).split(';'):
        field = field.strip()
        if not field:
            continue
        if '=' not in field:
            raise ValueError('ScriptRun args must be key=value: ' + field)
        key, value = field.split('=', 1)
        key, value = key.strip().lower(), value.strip()
        if key == 'cnnstage':
            cnn_stage = value
        elif key == 'threshold':
            threshold = float(value)
        elif key == 'mode':
            mode = value.lower()
        elif key == 'debug':
            debug = value.lower() in ('1', 'true', 'yes', 'on')
        elif key == 'fit':
            fit = value.lower()
        elif key == 'sidepx':
            side_px = float(value)
        elif key == 'edgeprob':
            edge_prob = float(value)
        elif key == 'minarea':
            min_area_fraction = float(value)
        elif key == 'edgemargin':
            edge_margin = int(value)
        elif key == 'maxaspect':
            max_aspect_ratio = float(value)
        elif key == 'minfill':
            min_fill_ratio = float(value)
        else:
            raise ValueError('Unknown CNN_BestSquare option: ' + key)

if mode in ('dry', 'wet', 'dry_pocket', 'wet_pocket'):
    mode = 'pocket'
if mode not in ('die', 'pocket'):
    raise ValueError('mode must be die or pocket')
if fit == 'robust':
    fit = 'square'  # Backward-compatible arguments.
if fit in ('minarea', 'min_area'):
    fit = 'debugger'
if fit in ('minarea_square', 'min_area_square'):
    fit = 'debugger_square'
if fit == 'probrect':
    fit = 'probsquare'  # Legacy name used for the four-edge rectangle.
if fit == 'bestfit':
    fit = 'debugger'  # Original debugger contour/minAreaRect fit.
if fit not in ('square', 'probsquare', 'debugger', 'debugger_square'):
    raise ValueError('fit must be square, probsquare, debugger, or debugger_square')
if not cnn_stage:
    raise ValueError('cnnStage is required')
if math.isnan(threshold) or math.isinf(threshold) or not (0.0 < threshold < 1.0):
    raise ValueError('threshold must be in (0,1)')
if math.isnan(edge_prob) or math.isinf(edge_prob) or edge_prob < 0.05 or edge_prob > 0.7:
    raise ValueError('edgeProb must be in [0.05,0.7]')
if side_px is not None and (math.isnan(side_px) or math.isinf(side_px) or side_px <= 0):
    raise ValueError('sidePx must be positive and finite')
if not (0.0 < min_area_fraction < 1.0):
    raise ValueError('minArea must be in (0,1)')
if max_aspect_ratio < 1.0 or not (0.0 < min_fill_ratio <= 1.0) or edge_margin < 0:
    raise ValueError('Invalid filter settings')

print('CNN_BestSquare v2026-10-15-fit-modes-github: script=%s args=%s fit=%s' %
      (str(stage.getFile()), str(args), fit))

cnn_result = pipeline.getExpectedResult(cnn_stage)
probability = cnn_result.getModel()
if not isinstance(probability, Mat) or probability.empty() or probability.type() != CvType.CV_32FC1:
    raise ValueError('CNN result.model must be a nonempty CV_32FC1 probability Mat')

height, width = probability.rows(), probability.cols()
min_area_px = max(25, int(round(width * height * min_area_fraction)))
image_cx, image_cy = width / 2.0, height / 2.0


def canonical_rectangle(rect):
    """Same geometry, angle in [-45,45) for OpenPnP's image coordinates."""
    angle = float(rect.angle)
    turns = int(math.floor((angle + 45.0) / 90.0))
    angle -= turns * 90.0
    w, h = float(rect.size.width), float(rect.size.height)
    if turns % 2:
        w, h = h, w
    return RotatedRect(rect.center, Size(w, h), angle)


def bilinear(pixels, ix, iy):
    """Fast bilinear probability lookup from a single native float[] copy."""
    if ix < 0.0 or iy < 0.0 or ix >= width - 1 or iy >= height - 1:
        return 0.0
    x0, y0 = int(ix), int(iy)
    fx, fy = ix - x0, iy - y0
    base = y0 * width + x0
    top = pixels[base] * (1.0 - fx) + pixels[base + 1] * fx
    bottom = pixels[base + width] * (1.0 - fx) + pixels[base + width + 1] * fx
    return top * (1.0 - fy) + bottom * fy


def median(items):
    values = sorted(items)
    if not values:
        raise ValueError('No CNN probability edge crossings')
    n = len(values)
    return values[n // 2] if n % 2 else 0.5 * (values[n // 2 - 1] + values[n // 2])


def edge_samples(pixels, cx, cy, side, nx, ny, tx, ty, level):
    """Return sampled (along, normal distance) at probability level on one side.

    Sample the central 64% of each side and use the median / a robust line slope.
    This is a segmentation-only fit; it does not inspect visible image edges.
    """
    points = []
    search_radius = max(22.0, 0.060 * side)
    start = 0.5 * side - search_radius
    step = 0.75
    steps = int(2.0 * search_radius / step) + 1
    count = 39
    for k in range(count):
        along = side * (-0.32 + 0.64 * k / float(count - 1))
        px0 = cx + tx * along
        py0 = cy + ty * along
        previous_r = start
        previous_p = bilinear(pixels, px0 + nx * start, py0 + ny * start)
        found = None
        for j in range(1, steps):
            r = start + j * step
            p = bilinear(pixels, px0 + nx * r, py0 + ny * r)
            if previous_p >= level and p < level:
                fraction = (previous_p - level) / max(1e-10, previous_p - p)
                found = previous_r + fraction * (r - previous_r)
            previous_r, previous_p = r, p
        if found is not None:
            points.append((along, found))
    if len(points) < 26:
        raise ValueError('CNN probability edge has too few samples (%d/%d)' %
                         (len(points), count))
    return points


def line_slope(points):
    """Theil-Sen robust slope for an edge's sampled normal distances."""
    slopes = []
    for i in range(len(points)):
        for j in range(i + 5, len(points)):
            dt = points[j][0] - points[i][0]
            if dt:
                slopes.append((points[j][1] - points[i][1]) / dt)
    return median(slopes)


def edge_basis(angle):
    rad = math.radians(angle)
    ux, uy = math.cos(rad), math.sin(rad)
    vx, vy = -uy, ux
    return ux, uy, vx, vy, ((-ux, -uy, vx, vy),
                            (ux, uy, vx, vy),
                            (-vx, -vy, ux, uy),
                            (vx, vy, ux, uy))


def fit_probability_square(rect):
    """Four-side fit, respecting that a physical square need not be square in pixels.

    A non-1:1 pixel aspect can come from differing camera units/pixel in X/Y,
    perspective, or systematic mask bias. It cannot be repaired by anchoring one
    edge and forcing equal pixel dimensions. Return the geometry the mask
    actually supports, and report physical calibration ratio when available.
    """
    rect = canonical_rectangle(rect)
    cx0, cy0 = float(rect.center.x), float(rect.center.y)
    raw_side = 0.5 * (float(rect.size.width) + float(rect.size.height))
    theta = float(rect.angle)
    if raw_side < 12.0:
        raise ValueError('Detected component too small for four-side fitting')

    pixels = zeros(width * height, 'f')
    probability.get(0, 0, pixels)

    # minAreaRect can follow a protruding corner. Estimate orientation instead
    # using the slopes of the central portions of all four probability edges.
    # The signs reflect screen coordinates (positive angle rotates clockwise).
    for iteration in range(2):
        ux, uy, vx, vy, basis = edge_basis(theta)
        points = [edge_samples(pixels, cx0, cy0, raw_side, nx, ny, tx, ty,
                               edge_prob) for nx, ny, tx, ty in basis]
        slopes = [line_slope(edge_points) for edge_points in points]
        correction = math.degrees(0.25 * (slopes[0] - slopes[1] -
                                          slopes[2] + slopes[3]))
        if abs(correction) > 1.5:
            raise ValueError('CNN edge orientation inconsistent (%.2f deg)' % correction)
        theta += correction

    # With the refined orientation, independently estimate the four signed
    # distances from the original center to the iso-probability boundaries.
    ux, uy, vx, vy, basis = edge_basis(theta)
    distances = [median([r for t, r in
                         edge_samples(pixels, cx0, cy0, raw_side,
                                      nx, ny, tx, ty, edge_prob)])
                 for nx, ny, tx, ty in basis]
    left, right, top, bottom = distances
    measured_width = left + right
    measured_height = top + bottom
    if measured_width < 10 or measured_height < 10:
        raise ValueError('CNN fitted dimensions are too small')
    ratio = measured_width / measured_height
    if ratio < 0.92 or ratio > 1.08:
        raise ValueError('CNN geometry not sufficiently square-like (ratio %.3f)' % ratio)

    # Optional externally known horizontal side. Preserve observed pixel aspect
    # rather than silently assuming square camera pixels.
    if side_px is not None:
        if abs(side_px - measured_width) > max(8.0, measured_width * 0.12):
            raise ValueError('sidePx %.2f differs too much from measured width %.2f' %
                             (side_px, measured_width))
        factor = side_px / measured_width
        measured_width *= factor
        measured_height *= factor

    # Unlike the old sharpest-edge anchor, use the midpoint of *both* opposite
    # boundaries. This prevents perfect-left / misplaced-right behavior.
    du = 0.5 * (right - left)
    dv = 0.5 * (bottom - top)
    cx = cx0 + ux * du + vx * dv
    cy = cy0 + uy * du + vy * dv
    if abs(du) > max(12.0, 0.04 * raw_side) or abs(dv) > max(12.0, 0.04 * raw_side):
        raise ValueError('CNN center shift too large for a reliable fit')

    theta = ((theta + 45.0) % 90.0) - 45.0
    print('CNN_BestSquare: edges L %.2f R %.2f T %.2f B %.2f; '
          'pixel size %.2f x %.2f (ratio %.4f); '
          'center (%.2f, %.2f), angle %.3f' %
          (left, right, top, bottom, measured_width, measured_height,
           measured_width / measured_height, cx, cy, theta))

    # Check camera-calibrated physical squareness without changing measured
    # image geometry. Calibration is a separate prerequisite for micron picks.
    camera = pipeline.getProperty('camera')
    if camera is not None:
        try:
            upp = camera.getUnitsPerPixelAtZ()
            upp_x, upp_y = float(upp.getX()), float(upp.getY())
            if upp_x > 0 and upp_y > 0:
                physical_ratio = (measured_width * upp_x) / (measured_height * upp_y)
                print('CNN_BestSquare: calibrated W/H = %.5f '
                      '(unitsPerPixel X %.9f, Y %.9f %s)' %
                      (physical_ratio, upp_x, upp_y, str(upp.getUnits())))
                if abs(physical_ratio - 1.0) > 0.01:
                    print('CNN_BestSquare WARNING: apparent die dimensions differ '
                          'by >1%% in calibrated machine units; verify camera X/Y '
                          'units-per-pixel and image distortion before placement.')
        except Exception as ex:
            print('CNN_BestSquare: camera scale unavailable: %s' % ex)

    return RotatedRect(Point(cx, cy), Size(measured_width, measured_height), theta)



def enforce_physical_square(rect):
    """Balance opposing dimensions about the fitted center in CALIBRATED units.

    If camera X/Y pixel scales agree, e.g. 453 x 448 px becomes a 450.5 x
    450.5 px square with unchanged center and orientation. For unequal X/Y
    scales, compute equal physical side lengths along the rotated image axes.
    No edge is arbitrarily anchored or translated.
    """
    rect = canonical_rectangle(rect)
    w, h = float(rect.size.width), float(rect.size.height)
    if w <= 0 or h <= 0:
        raise ValueError('Cannot square an empty fitted rectangle')

    upp_x, upp_y = 1.0, 1.0
    camera = pipeline.getProperty('camera')
    if camera is not None:
        try:
            upp = camera.getUnitsPerPixelAtZ()
            measured_x, measured_y = float(upp.getX()), float(upp.getY())
            if (not math.isnan(measured_x) and not math.isinf(measured_x)
                    and not math.isnan(measured_y) and not math.isinf(measured_y)
                    and measured_x > 0.0 and measured_y > 0.0):
                upp_x, upp_y = measured_x, measured_y
            else:
                print('CNN_BestSquare: invalid camera X/Y pixel calibration; '
                      'using equal pixel scales for square fit')
        except Exception as ex:
            print('CNN_BestSquare: camera pixel calibration unavailable (%s); '
                  'using equal pixel scales' % ex)

    angle_rad = math.radians(float(rect.angle))
    cos_a, sin_a = math.cos(angle_rad), math.sin(angle_rad)
    horizontal_scale = math.sqrt((cos_a * upp_x) ** 2 + (sin_a * upp_y) ** 2)
    vertical_scale = math.sqrt((sin_a * upp_x) ** 2 + (cos_a * upp_y) ** 2)
    original_w_units = w * horizontal_scale
    original_h_units = h * vertical_scale
    # Equal weighting: neither axis receives preferential trust/anchoring.
    balanced_side_units = 0.5 * (original_w_units + original_h_units)
    w_square = balanced_side_units / horizontal_scale
    h_square = balanced_side_units / vertical_scale

    if max(original_w_units, original_h_units) / min(original_w_units, original_h_units) > 1.08:
        raise ValueError('Fitted dimensions differ by >8%% in calibrated units; '
                         'refusing to force an unreliable square')

    print('CNN_BestSquare: balanced physical square %.2f x %.2f pixels '
          '(source %.2f x %.2f; center unchanged at %.2f, %.2f; angle %.3f)' %
          (w_square, h_square, w, h, rect.center.x, rect.center.y, rect.angle))
    return RotatedRect(rect.center, Size(w_square, h_square), rect.angle)


binary = Mat()
labels = Mat()
stats = Mat()
centroids = Mat()
prob_preview = Mat()
selected_rect = None

try:
    # Inclusive >= threshold, matching the Python debugger.
    Core.inRange(probability, Scalar(threshold), Scalar(1.0), binary)

    if debug:
        source = pipeline.getWorkingImage()
        if source.cols() != width or source.rows() != height:
            raise ValueError('CNN probability/image resolution mismatch')
        if not Imgcodecs.imwrite('/tmp/lumen_cnn_input.png', source):
            raise IOError('Could not write /tmp/lumen_cnn_input.png')
        if not Imgcodecs.imwrite('/tmp/lumen_cnn_mask.png', binary):
            raise IOError('Could not write /tmp/lumen_cnn_mask.png')
        probability.convertTo(prob_preview, CvType.CV_8UC1, 255.0)
        if not Imgcodecs.imwrite('/tmp/lumen_cnn_probability.png', prob_preview):
            raise IOError('Could not write /tmp/lumen_cnn_probability.png')

    count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids,
                                                 8, CvType.CV_32S)
    detections = []
    for label_id in range(1, count):
        area = int(stats.get(label_id, Imgproc.CC_STAT_AREA)[0])
        if area < min_area_px:
            continue
        x = int(stats.get(label_id, Imgproc.CC_STAT_LEFT)[0])
        y = int(stats.get(label_id, Imgproc.CC_STAT_TOP)[0])
        w = int(stats.get(label_id, Imgproc.CC_STAT_WIDTH)[0])
        h = int(stats.get(label_id, Imgproc.CC_STAT_HEIGHT)[0])
        component = Mat()
        hierarchy = Mat()
        contours = ArrayList()
        try:
            Core.inRange(labels, Scalar(label_id), Scalar(label_id), component)
            Imgproc.findContours(component, contours, hierarchy,
                                 Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            if contours.isEmpty():
                continue
            largest = None
            largest_area = -1.0
            for contour in contours:
                contour_area = Imgproc.contourArea(contour)
                if contour_area > largest_area:
                    largest_area = contour_area
                    largest = contour
            points_f = MatOfPoint2f()
            try:
                largest.convertTo(points_f, CvType.CV_32FC2)
                rect = Imgproc.minAreaRect(points_f)
            finally:
                points_f.release()
        finally:
            for contour in contours:
                contour.release()
            hierarchy.release()
            component.release()
        rw, rh = float(rect.size.width), float(rect.size.height)
        if rw <= 0.0 or rh <= 0.0:
            continue
        fill_ratio = area / (rw * rh)
        aspect_ratio = max(rw, rh) / min(rw, rh)
        touches_edge = (x <= edge_margin or y <= edge_margin or
                        x + w >= width - edge_margin or
                        y + h >= height - edge_margin)
        complete = (not touches_edge and aspect_ratio <= max_aspect_ratio
                    and fill_ratio >= min_fill_ratio)
        detections.append((area, rect, complete))

    detections.sort(key=lambda candidate: candidate[0], reverse=True)
    if mode == 'pocket':
        if detections:
            selected_rect = detections[0][1]  # Matches the pocket debugger.
    else:
        best_distance = None
        for area, rect, complete in detections:
            if not complete:
                continue
            dx, dy = rect.center.x - image_cx, rect.center.y - image_cy
            distance = dx * dx + dy * dy
            if best_distance is None or distance < best_distance:
                selected_rect, best_distance = rect, distance

    if selected_rect is None:
        raise ValueError('No matching %s segmentation at threshold %.3f' % (mode, threshold))

    if fit in ('square', 'probsquare'):
        base_rect = fit_probability_square(selected_rect)
    else:
        base_rect = canonical_rectangle(selected_rect)

    if fit in ('square', 'debugger_square'):
        final_rect = enforce_physical_square(base_rect)
    else:
        final_rect = base_rect
    print('CNN_BestSquare: RETURN fit=%s center=(%.3f, %.3f) size=%.3f x %.3f angle=%.3f' %
          (fit, final_rect.center.x, final_rect.center.y,
           final_rect.size.width, final_rect.size.height, final_rect.angle))

    if debug:
        frame = pipeline.getWorkingImage().clone()
        try:
            vertices = array([Point() for _ in range(4)], Point)
            final_rect.points(vertices)
            for i in range(4):
                Imgproc.line(frame, vertices[i], vertices[(i + 1) % 4],
                             Scalar(30, 240, 60), 2, Imgproc.LINE_AA)
            Imgproc.circle(frame, final_rect.center, 3,
                           Scalar(30, 240, 60), -1, Imgproc.LINE_AA)
            if not Imgcodecs.imwrite('/tmp/lumen_cnn_fit.png', frame):
                raise IOError('Could not write /tmp/lumen_cnn_fit.png')
        finally:
            frame.release()
finally:
    prob_preview.release()
    centroids.release()
    stats.release()
    labels.release()
    binary.release()

# ScriptRun captures the last expression, not a top-level return statement.
CvStage.Result(None, final_rect)
