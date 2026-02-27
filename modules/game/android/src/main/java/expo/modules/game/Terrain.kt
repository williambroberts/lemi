package expo.modules.gameinternals

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2

class Terrain {

    private val resolution = 10
    private val worldWidth = 200 * 800
    private val pointCount = worldWidth / resolution
    private val terrainY = FloatArray(pointCount)

    private val path = Path()

    fun generate(screenHeight: Float) {
        val baseY = screenHeight * 0.5f
        var currentY = baseY

        for (i in 0 until pointCount) {
            if (i >= 3 * (800 / resolution)) {
                currentY += (Math.random() * (screenHeight * 0.01) - (screenHeight * 0.005)).toFloat()
            }
            terrainY[i] = currentY
        }

        // smooth pass
        for (i in 1 until pointCount - 1) {
            terrainY[i] = (terrainY[i - 1] + terrainY[i] + terrainY[i + 1]) / 3f
        }

        // sharp peaks
        var lastSpikeIndex = -pointCount // ensure no minimum gap issue at start
        for (i in 100 until pointCount - 100) {
            val gapInPoints = (screenHeight * 0.5f / resolution).toInt()
            if (Math.random() < 0.01 && i - lastSpikeIndex > gapInPoints) {
                terrainY[i] -= screenHeight * 0.08f
                lastSpikeIndex = i
            }
        }
    }

    fun getGroundY(worldX: Float): Float {
        val index = (worldX / resolution).toInt().coerceIn(0, pointCount - 1)
        val next = (index + 1).coerceAtMost(pointCount - 1)
        val t = (worldX / resolution) - index
        return terrainY[index] * (1f - t) + terrainY[next] * t
    }

    fun draw(canvas: Canvas, cameraX: Float, cameraY: Float, screenWidth: Float, screenHeight: Float, paint: Paint) {
        if (terrainY.isEmpty()) return

        path.reset()

        val startWorldX = (cameraX - resolution).coerceAtLeast(0f)
        val endWorldX = (cameraX + screenWidth + resolution).coerceAtMost(worldWidth.toFloat())

        val startIndex = (startWorldX / resolution).toInt().coerceIn(0, pointCount - 1)
        val endIndex = (endWorldX / resolution).toInt().coerceIn(0, pointCount - 1)

        path.moveTo(startIndex * resolution - cameraX, terrainY[startIndex] - cameraY)

        for (i in startIndex..endIndex) {
            val screenX = i * resolution - cameraX
            val screenY = terrainY[i] - cameraY
            path.lineTo(screenX, screenY)
        }

        path.lineTo(endIndex * resolution - cameraX, screenHeight + 1000f)
        path.lineTo(startIndex * resolution - cameraX, screenHeight + 1000f)
        path.close()

        canvas.drawPath(path, paint)
    }
}