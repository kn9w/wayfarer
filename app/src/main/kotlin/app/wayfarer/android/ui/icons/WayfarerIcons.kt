package app.wayfarer.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's icons, drawn here rather than depended upon.
 *
 * `material-icons-extended` is a very large artifact for a handful of glyphs, and
 * the two this app leans on hardest — a tree and a globe — carry meaning specific
 * to it: here, and everywhere. They are declared as [ImageVector]s on the same
 * 24dp/24-unit grid Material's own icons use, so `Icon()` tints them with
 * `LocalContentColor` and scales them exactly as it would any other icon.
 *
 * Each is built once and cached: building an ImageVector is not free, and doing
 * it per recomposition would allocate on every frame of a tab transition.
 */
object WayfarerIcons {
    /** The global feed: everything on every relay the user allows. */
    val Globe: ImageVector
        get() =
            _globe ?: icon("Globe") {
                stroked {
                    circle(cx = 12f, cy = 12f, r = 9f)
                    // Equator.
                    moveTo(3f, 12f)
                    lineTo(21f, 12f)
                    // Two meridians, bowing opposite ways.
                    moveTo(12f, 3f)
                    curveTo(15.5f, 7f, 15.5f, 17f, 12f, 21f)
                    moveTo(12f, 3f)
                    curveTo(8.5f, 7f, 8.5f, 17f, 12f, 21f)
                }
            }.also { _globe = it }

    /** The local view: this account, and the ground it stands on. */
    val Tree: ImageVector
        get() =
            _tree ?: icon("Tree") {
                filled {
                    // A three-tier canopy, each tier wider than the last.
                    triangle(12f, 2f, 16.6f, 9.2f, 7.4f, 9.2f)
                    triangle(12f, 6.4f, 18.2f, 15f, 5.8f, 15f)
                    triangle(12f, 11f, 20f, 20f, 4f, 20f)
                    // Trunk.
                    rect(10.9f, 18f, 13.1f, 22f)
                }
            }.also { _tree = it }

    /** Relays: a mast, broadcasting. */
    val Relay: ImageVector
        get() =
            _relay ?: icon("Relay") {
                filled {
                    circle(cx = 12f, cy = 10f, r = 1.9f)
                    // The mast, splaying into a base.
                    moveTo(11.2f, 12.2f)
                    lineTo(12.8f, 12.2f)
                    lineTo(14.6f, 21.5f)
                    lineTo(12.9f, 21.5f)
                    lineTo(12f, 16f)
                    lineTo(11.1f, 21.5f)
                    lineTo(9.4f, 21.5f)
                    close()
                }
                stroked(width = 1.7f) {
                    // Two pairs of waves, near and far.
                    moveTo(9.3f, 6.9f)
                    curveTo(7.9f, 8.7f, 7.9f, 11.3f, 9.3f, 13.1f)
                    moveTo(14.7f, 6.9f)
                    curveTo(16.1f, 8.7f, 16.1f, 11.3f, 14.7f, 13.1f)
                    moveTo(6.7f, 4.4f)
                    curveTo(4.3f, 7.4f, 4.3f, 12.6f, 6.7f, 15.6f)
                    moveTo(17.3f, 4.4f)
                    curveTo(19.7f, 7.4f, 19.7f, 12.6f, 17.3f, 15.6f)
                }
            }.also { _relay = it }

    /** Write something. */
    val Add: ImageVector
        get() =
            _add ?: icon("Add") {
                filled {
                    rect(11f, 5f, 13f, 19f)
                    rect(5f, 11f, 19f, 13f)
                }
            }.also { _add = it }

    /** Filtering: the funnel, narrowing what is shown. */
    val Funnel: ImageVector
        get() =
            _funnel ?: icon("Funnel") {
                filled {
                    moveTo(3.5f, 4f)
                    lineTo(20.5f, 4f)
                    lineTo(14f, 12.2f)
                    lineTo(14f, 19.4f)
                    lineTo(10f, 21.4f)
                    lineTo(10f, 12.2f)
                    close()
                }
            }.also { _funnel = it }

    val ChevronLeft: ImageVector
        get() =
            _chevronLeft ?: icon("ChevronLeft") {
                stroked {
                    moveTo(15f, 5f)
                    lineTo(8f, 12f)
                    lineTo(15f, 19f)
                }
            }.also { _chevronLeft = it }

    val ChevronRight: ImageVector
        get() =
            _chevronRight ?: icon("ChevronRight") {
                stroked {
                    moveTo(9f, 5f)
                    lineTo(16f, 12f)
                    lineTo(9f, 19f)
                }
            }.also { _chevronRight = it }

    /** Back, in the direction the system's own back arrow points. */
    val ArrowBack: ImageVector
        get() =
            _arrowBack ?: icon("ArrowBack") {
                stroked {
                    moveTo(20f, 12f)
                    lineTo(4f, 12f)
                    moveTo(11f, 5f)
                    lineTo(4f, 12f)
                    lineTo(11f, 19f)
                }
            }.also { _arrowBack = it }

    /** The affordance on a title that opens a menu. */
    val DropDown: ImageVector
        get() =
            _dropDown ?: icon("DropDown") {
                filled {
                    moveTo(6f, 9f)
                    lineTo(18f, 9f)
                    lineTo(12f, 16f)
                    close()
                }
            }.also { _dropDown = it }

    /** Starred: a relay the user wants offered first. */
    val Star: ImageVector
        get() = _star ?: icon("Star") { filled { star() } }.also { _star = it }

    /** Not starred. The same outline, so the toggle does not jump. */
    val StarOutline: ImageVector
        get() = _starOutline ?: icon("StarOutline") { stroked(width = 1.8f) { star() } }.also { _starOutline = it }

    /** A reply, pointing back at what it answers. */
    val Reply: ImageVector
        get() =
            _reply ?: icon("Reply") {
                stroked {
                    moveTo(10f, 5f)
                    lineTo(4f, 10.5f)
                    lineTo(10f, 16f)
                    moveTo(4.6f, 10.5f)
                    lineTo(14f, 10.5f)
                    curveTo(17.9f, 10.5f, 20f, 12.9f, 20f, 16.2f)
                    verticalLineTo(19f)
                }
            }.also { _reply = it }

    val Search: ImageVector
        get() =
            _search ?: icon("Search") {
                stroked {
                    circle(cx = 10.5f, cy = 10.5f, r = 6.5f)
                    moveTo(15.4f, 15.4f)
                    lineTo(20.5f, 20.5f)
                }
            }.also { _search = it }

    val Close: ImageVector
        get() =
            _close ?: icon("Close") {
                stroked {
                    moveTo(5.5f, 5.5f)
                    lineTo(18.5f, 18.5f)
                    moveTo(18.5f, 5.5f)
                    lineTo(5.5f, 18.5f)
                }
            }.also { _close = it }

    val Check: ImageVector
        get() =
            _check ?: icon("Check") {
                stroked {
                    moveTo(4.5f, 12.5f)
                    lineTo(9.5f, 17.5f)
                    lineTo(19.5f, 6.5f)
                }
            }.also { _check = it }

    /** Blocked: a circle struck through. */
    val Block: ImageVector
        get() =
            _block ?: icon("Block") {
                stroked {
                    circle(cx = 12f, cy = 12f, r = 8.5f)
                    moveTo(6f, 6f)
                    lineTo(18f, 18f)
                }
            }.also { _block = it }

    private var _globe: ImageVector? = null
    private var _tree: ImageVector? = null
    private var _relay: ImageVector? = null
    private var _add: ImageVector? = null
    private var _star: ImageVector? = null
    private var _starOutline: ImageVector? = null
    private var _reply: ImageVector? = null
    private var _funnel: ImageVector? = null
    private var _chevronLeft: ImageVector? = null
    private var _chevronRight: ImageVector? = null
    private var _arrowBack: ImageVector? = null
    private var _dropDown: ImageVector? = null
    private var _search: ImageVector? = null
    private var _close: ImageVector? = null
    private var _check: ImageVector? = null
    private var _block: ImageVector? = null
}

// ---- the small drawing vocabulary ---------------------------------------

private fun icon(
    name: String,
    body: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(
            name = "Wayfarer.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(body)
        .build()

/** A solid shape, in the form Material's own filled icons take. */
private fun ImageVector.Builder.filled(block: PathBuilder.() -> Unit) {
    path(fill = SolidColor(Color.Black)) { block() }
}

/**
 * A stroked shape.
 *
 * Material's icons are fills, but a globe or a magnifier drawn as a fill at 24dp
 * turns into a blob, so the outline set's weight is used instead.
 */
private fun ImageVector.Builder.stroked(
    width: Float = 2f,
    block: PathBuilder.() -> Unit,
) {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) { block() }
}

/**
 * A full circle, as two half-arcs.
 *
 * One arc cannot close a circle — a sweep of exactly 360° has identical start and
 * end points and is degenerate — so every circle here is drawn as two semicircles.
 */
private fun PathBuilder.circle(
    cx: Float,
    cy: Float,
    r: Float,
) {
    // Positional, in SVG's own order: rx, ry, x-axis-rotation, large-arc,
    // sweep, dx, dy. Spelled this way rather than with argument names because
    // the names of Compose's arc parameters are not part of what this file can
    // check for itself.
    moveTo(cx, cy - r)
    arcToRelative(r, r, 0f, false, true, 0f, r * 2f)
    arcToRelative(r, r, 0f, false, true, 0f, -r * 2f)
    close()
}

/**
 * A five-pointed star on the 24-unit grid.
 *
 * Points computed once and written out rather than derived at runtime: the
 * arithmetic would be the same every call, and the literals are what a reader
 * can check against the drawing.
 */
private fun PathBuilder.star() {
    moveTo(12f, 2.6f)
    lineTo(14.9f, 8.5f)
    lineTo(21.4f, 9.4f)
    lineTo(16.7f, 14f)
    lineTo(17.8f, 20.5f)
    lineTo(12f, 17.4f)
    lineTo(6.2f, 20.5f)
    lineTo(7.3f, 14f)
    lineTo(2.6f, 9.4f)
    lineTo(9.1f, 8.5f)
    close()
}

private fun PathBuilder.triangle(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
) {
    moveTo(x1, y1)
    lineTo(x2, y2)
    lineTo(x3, y3)
    close()
}

private fun PathBuilder.rect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    moveTo(left, top)
    lineTo(right, top)
    lineTo(right, bottom)
    lineTo(left, bottom)
    close()
}
