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

    // given a world x position, return the ground y at that x by interpolating between the two nearest points
    fun getGroundY(worldX: Float): Float {
        if (points.isEmpty()) return 0f

        // find which segment worldX falls in
        val index = (worldX / segmentWidth).toInt().coerceIn(0, points.size - 2)
        val left = points[index]
        val right = points[index + 1]

        // how far between left and right are we? 0.0 = at left, 1.0 = at right
        val t = ((worldX - left.x) / segmentWidth).coerceIn(0f, 1f)

        // linear interpolation between the two y values
        return left.y + (right.y - left.y) * t
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
        path.moveTo(firstPoint.x - cameraX, firstPoint.y - cameraY)

        // draw lines to each subsequent visible point
        for (i in startIndex + 1..endIndex) {
            path.lineTo(points[i].x - cameraX, points[i].y - cameraY)
        }

        // close the polygon down to the bottom of the screen so it looks filled
        path.lineTo(points[endIndex].x - cameraX, screenHeight + 1000f)//todow
        path.lineTo(firstPoint.x - cameraX, screenHeight + 1000f)//todow
        path.close()

        canvas.drawPath(path, paint)
    }
}