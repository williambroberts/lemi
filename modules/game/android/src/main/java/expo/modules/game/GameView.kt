package expo.modules.gameinternals

import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Bitmap
import expo.modules.game.R

class GameInternalsView(context: Context, appContext: AppContext) : ExpoView(context, appContext),
    SurfaceHolder.Callback {

    private val surfaceView = SurfaceView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        holder.addCallback(this@GameInternalsView)
        holder.setFormat(PixelFormat.RGBA_8888)
    }

    // --- GAME STATE ---
    private val terrain = Terrain()

    private val terrainPaint = Paint().apply {
        color = Color.parseColor("#107d81")
        style = Paint.Style.FILL
    }

    private var screenWidth = 0f
    private var screenHeight = 0f

    private var ballX = 500f
    private var ballY = 500f

    private var velocityX = 0f
    private var ballRotation = 0f

    private var cameraX = 0f
    private var cameraY = 0f

    private val ballRadius = 40f

    private val rawBitmap = BitmapFactory.decodeResource(resources, R.drawable.me)
    private val ballDiameter = (ballRadius * 2).toInt()
    private val scaledBitmap = Bitmap.createScaledBitmap(rawBitmap, ballDiameter, ballDiameter, true)

    private val ballPaint = Paint().apply {
        shader = BitmapShader(scaledBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
    }

    private val circumference = 2f * Math.PI.toFloat() * ballRadius

    // --- PHYSICS ---
    private val gravity = 1.2f
    private val friction = 0.995f
    private val maxVelocity = 45f
    private val minVelocity = 5f
    private var velocityY = 0f
    private val gravityY = 1.2f
    private var isOnGround = false

    // --- CAMERA ---
    private var smoothCameraX = 0f
    private var smoothCameraY = 0f
    private val cameraSmoothing = 0.08f

    // --- GAME LOOP ---
    private var isRunning = false
    private var surfaceReady = false

    private val choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return
            update()
            draw()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        addView(surfaceView)

        surfaceView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {}
                MotionEvent.ACTION_UP -> {}
            }
            true
        }
    }

    // --- GAME LOOP FUNCTIONS ---

    private fun update() {
        val sampleDistance = 5f
        val yAhead = terrain.getGroundY(ballX + sampleDistance)
        val yBehind = terrain.getGroundY(ballX - sampleDistance)
        val slope = (yAhead - yBehind) / (sampleDistance * 2f)

        if (isOnGround) {
            velocityX += slope * gravity
            velocityX *= friction
            velocityX = velocityX.coerceIn(-maxVelocity, maxVelocity)
            if (velocityX > 0f && velocityX < minVelocity) velocityX = minVelocity
            if (velocityX < 0f && velocityX > -minVelocity) velocityX = -minVelocity
            if (velocityX == 0f) velocityX = minVelocity
        }

        if (!isOnGround) {
            velocityY += gravityY
        }

        ballX += velocityX
        ballY += velocityY

        val terrainY = terrain.getGroundY(ballX)
        if (ballY + ballRadius >= terrainY) {
            ballY = terrainY - ballRadius
            velocityY = if (velocityY > 8f) -velocityY * 0.3f else 0f
            isOnGround = true
        } else {
            if (velocityY < -3f) {
                isOnGround = false
            } else {
                ballY = terrainY - ballRadius
                velocityY = 0f
                isOnGround = true
            }
        }

        ballRotation += (velocityX / circumference) * 360f

        val targetCameraX = ballX - screenWidth / 2f
        val targetCameraY = ballY - screenHeight / 2f

        smoothCameraX += (targetCameraX - smoothCameraX) * cameraSmoothing
        smoothCameraY += (targetCameraY - smoothCameraY) * cameraSmoothing

        cameraX = smoothCameraX
        cameraY = smoothCameraY
    }

    private fun draw() {
        if (screenWidth == 0f || !surfaceReady) return

        val canvas = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                surfaceView.holder.lockHardwareCanvas()
            } else {
                surfaceView.holder.lockCanvas()
            }
        } catch (e: Exception) {
            null
        } ?: return

        try {
            canvas.drawColor(Color.parseColor("#c5b3ab"))
            terrain.draw(canvas, cameraX, cameraY, screenWidth, screenHeight, terrainPaint)

            val screenX = ballX - cameraX
            val screenY = ballY - cameraY

            canvas.drawText("world x: ${ballX.toInt()}", 40f, 60f, textPaint)

            val matrix = Matrix()
            matrix.setTranslate(screenX - ballRadius, screenY - ballRadius)
            matrix.postRotate(ballRotation, screenX, screenY)
            (ballPaint.shader as BitmapShader).setLocalMatrix(matrix)

            canvas.drawCircle(screenX, screenY, ballRadius, ballPaint)
        } finally {
            surfaceView.holder.unlockCanvasAndPost(canvas)
        }
    }

    // --- SURFACE CALLBACKS ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        holder.setFormat(PixelFormat.RGBA_8888)
        surfaceReady = true
        isRunning = true
        choreographer.postFrameCallback(frameCallback)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        terrain.generate(screenHeight)
        ballX = screenWidth * 0.5f
        ballY = terrain.getGroundY(ballX) - ballRadius
        smoothCameraX = ballX - screenWidth / 2f
        smoothCameraY = ballY - screenHeight / 2f
        cameraX = smoothCameraX
        cameraY = smoothCameraY
        velocityX = minVelocity
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        isRunning = false
        choreographer.removeFrameCallback(frameCallback)
    }
}