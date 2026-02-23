package expo.modules.gameinternals

class Terrain {

    // a terrain point is just an x and y in world space
    data class Point(val x: Float, val y: Float)

    // the terrain is a list of points that define the ground line
    // x goes from 0 to however wide the world is
    // y is the height of the ground at that x position
    private val points = mutableListOf<Point>()

    // how far apart each terrain point is in world space
    private val segmentWidth = 200f

    fun generate(screenHeight: Float) {
        points.clear()

        val baseY = screenHeight * 0.5f

        var currentY = baseY
        for (i in 0..200) {
            val x = i * segmentWidth
            if (i >= 3) {
                currentY += (Math.random() * (screenHeight * 0.1) - (screenHeight * 0.05)).toFloat()
            }
            points.add(Point(x, currentY))
        }
    }
    // catmull-rom spline interpolation between 4 points, t is 0..1 between p1 and p2
    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        return 0.5f * (
            (2f * p1) +
            (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t
        )
    }
    // given a world x position, return the ground y at that x by interpolating between the two nearest points
    fun getGroundY(worldX: Float): Float {
    if (points.isEmpty()) return 0f

    val index = (worldX / segmentWidth).toInt().coerceIn(0, points.size - 2)
    val t = ((worldX - points[index].x) / segmentWidth).coerceIn(0f, 1f)

    // get 4 surrounding points, clamping at the edges
    val p0 = points[(index - 1).coerceAtLeast(0)].y
    val p1 = points[index].y
    val p2 = points[index + 1].y
    val p3 = points[(index + 2).coerceAtMost(points.size - 1)].y

    return catmullRom(p0, p1, p2, p3, t)
    }

    // draw the terrain as a filled polygon so it looks like ground, not just a line
    fun draw(canvas: android.graphics.Canvas, cameraX: Float,cameraY:Float, screenWidth: Float, screenHeight: Float, paint: android.graphics.Paint) {
        if (points.isEmpty()) return

        val path = android.graphics.Path()

        // find which points are visible on screen
        val startIndex = ((cameraX) / segmentWidth).toInt().minus(1).coerceAtLeast(0)
        // add + 1 so we always include one point off the right edge of the screen
        val endIndex = ((cameraX + screenWidth) / segmentWidth).toInt().plus(1).coerceAtMost(points.size - 1)   
        // move to the first visible point
        val firstPoint = points[startIndex]
        path.moveTo(firstPoint.x - cameraX, getGroundY(firstPoint.x) - cameraY)

        // how many steps per segment, higher = smoother curve
        val steps = 20

        for (i in startIndex until endIndex) {
            for (step in 1..steps) {
                val t = step / steps.toFloat()
                val worldX = points[i].x + t * segmentWidth
                val screenX = worldX - cameraX
                val screenY = getGroundY(worldX) - cameraY
                path.lineTo(screenX, screenY)
            }
        }

        // close the polygon down to the bottom of the screen so it looks filled
        path.lineTo(points[endIndex].x - cameraX, screenHeight + 1000f)//todow
        path.lineTo(firstPoint.x - cameraX, screenHeight + 1000f)//todow
        path.close()

        canvas.drawPath(path, paint)
    }
}