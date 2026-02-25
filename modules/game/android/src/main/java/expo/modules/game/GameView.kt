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
import android.view.GestureDetector

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
        color = Color.parseColor("#7be4e7")
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

    private val bitmapMatrix = Matrix()


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
    private val maxVelocity = 35f
    private val minVelocity = 5f
    private var velocityY = 0f
    private val gravityY = 0.3f
    private var isOnGround = false

    // --- ACTIONS --
    private val timeFor1Rotation = 2f
    private var isHolding = false
    private val jumpVelocity = -5f  // negative = upward, tune this for jump height
    private var jumpRequested = false
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (isOnGround) jumpRequested = true
            if (!isOnGround) isHolding = true
            return true
        }
    })
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
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isHolding = false
            }
            gestureDetector.onTouchEvent(event)
            true//handle the event
        }
    }

    // --- GAME LOOP FUNCTIONS ---

    private fun update() {
    // handle jump input first
    if (jumpRequested) {
        velocityY = jumpVelocity
        isOnGround = false
        jumpRequested = false
    }

    // horizontal slope physics
    val sampleDistance = 5f
    val yAhead = terrain.getGroundY(ballX + sampleDistance)
    val yBehind = terrain.getGroundY(ballX - sampleDistance)
    val slope = (yAhead - yBehind) / (sampleDistance * 2f)

    velocityX += slope * gravity
    velocityX *= friction
    velocityX = velocityX.coerceIn(-maxVelocity, maxVelocity)
    if (velocityX > 0f && velocityX < minVelocity) velocityX = minVelocity
    if (velocityX < 0f && velocityX > -minVelocity) velocityX = -minVelocity
    if (velocityX == 0f) velocityX = minVelocity

    // move horizontally
    ballX += velocityX

    if (isOnGround) {
        // snap directly to terrain surface every frame
        ballY = terrain.getGroundY(ballX) - ballRadius
        velocityY = 0f
    } else {
        // airborne - apply gravity and move vertically
        velocityY += gravityY
        ballY += velocityY

        // check if we've landed
        val terrainY = terrain.getGroundY(ballX)
        if (ballY + ballRadius >= terrainY) {
            ballY = terrainY - ballRadius
            velocityY = 0f
            isOnGround = true
        }
    }

    // ballRotation += (velocityX / circumference) * 360f
    if (isHolding) {
        ballRotation += (360f / (timeFor1Rotation * 60f)) // 1 rotation per 2 seconds at 60fps
    }
    val targetCameraX = ballX - screenWidth * 0.2f
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
            canvas.drawColor(Color.parseColor("#dd835d"))
            terrain.draw(canvas, cameraX, cameraY, screenWidth, screenHeight, terrainPaint)

            val screenX = ballX - cameraX
            val screenY = ballY - cameraY

            canvas.drawText("world x: ${ballX.toInt()}", 40f, 60f, textPaint)

            bitmapMatrix.setTranslate(screenX - ballRadius, screenY - ballRadius)
            bitmapMatrix.postRotate(ballRotation, screenX, screenY)
            (ballPaint.shader as BitmapShader).setLocalMatrix(bitmapMatrix)

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
        smoothCameraX = ballX - screenWidth * 0.2f
        smoothCameraY = ballY - screenHeight / 2f
        cameraX = smoothCameraX
        cameraY = smoothCameraY
        velocityX = minVelocity
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        isRunning = false
        choreographer.removeFrameCallback(frameCallback)
        Thread.sleep(32)
    }
}