package expo.modules.gameinternals

class Terrain {
    data class Boulder(val x: Float, val height: Float, val width: Float)

    private val boulders = mutableListOf<Boulder>()
    // a terrain point is just an x and y in world space
    data class Point(val x: Float, val y: Float)

    // the terrain is a list of points that define the ground line
    // x goes from 0 to however wide the world is
    // y is the height of the ground at that x position
    private val points = mutableListOf<Point>()


    private val path = android.graphics.Path()

    // how far apart each terrain point is in world space
    private val segmentWidth = 800f
    private val boulderPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#8B7355")
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    fun generate(screenHeight: Float) {
        
        points.clear()
         
        val baseY = screenHeight * 0.5f

        var currentY = baseY
        for (i in 0..200) {
            val x = i * segmentWidth
            if (i >= 3) {//todow why
                currentY += (Math.random() * (screenHeight * 0.3) - (screenHeight * 0.01)).toFloat()
            }
            points.add(Point(x, currentY))
        }

        boulders.clear()
        for (i in 5..195) {
            // roughly 1 in 10 segments gets a boulder
            if (Math.random() < 0.1) {
                val boulderX = points[i].x + (Math.random() * segmentWidth * 0.5).toFloat()
                val boulderHeight = (screenHeight * 0.04 + Math.random() * screenHeight * 0.04).toFloat()
                val boulderWidth = (boulderHeight * 1.5f + Math.random() * boulderHeight).toFloat()
                boulders.add(Boulder(boulderX, boulderHeight, boulderWidth))
            }
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
    

    private fun drawBoulder(canvas: android.graphics.Canvas, boulder: Boulder, cameraX: Float, cameraY: Float, paint: android.graphics.Paint) {
        val groundY = getGroundY(boulder.x) - cameraY
        val screenX = boulder.x - cameraX

        // get slope angle at this boulder's position
        val sampleDist = 10f
        val yLeft = getGroundY(boulder.x - sampleDist)
        val yRight = getGroundY(boulder.x + sampleDist)
        val angle = Math.toDegrees(Math.atan2((yRight - yLeft).toDouble(), (sampleDist * 2).toDouble())).toFloat()

        canvas.save()

        // move origin to boulder base center, rotate to match slope
        canvas.translate(screenX, groundY)
        canvas.rotate(angle)

        // draw rectangle sitting on the ground (y goes upward so height is negative)
        canvas.drawRect(
            -boulder.width / 2f,    // left
            -boulder.height,         // top (above ground)
            boulder.width / 2f,     // right
            0f,                      // bottom (at ground level)
            paint
        )

        canvas.restore()
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

        path.reset()

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
        
        for (boulder in boulders) {
            val screenX = boulder.x - cameraX
            if (screenX > -boulder.width && screenX < screenWidth + boulder.width) {
                drawBoulder(canvas, boulder, cameraX, cameraY, boulderPaint)
            }
        }
    }
}