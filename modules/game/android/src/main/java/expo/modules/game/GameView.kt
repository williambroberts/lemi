package expo.modules.gameinternals

import android.content.Context
import android.graphics.*
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
// GameInternalsView extends ExpoView so expo knows how to render it in react native
// it also implements SurfaceHolder.Callback so we get notified when the canvas is ready to draw on
class GameInternalsView(context: Context, appContext: AppContext) : ExpoView(context, appContext),
    SurfaceHolder.Callback {

    // SurfaceView is a view that has its own dedicated drawing canvas, good for games
    private val surfaceView = SurfaceView(context).apply {
        // MATCH_PARENT makes it fill the entire ExpoView
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // register ourselves to get surface lifecycle callbacks (created, changed, destroyed)
        holder.addCallback(this@GameInternalsView)
    }

    // --- GAME STATE ---
    // add with other state variables
    private val terrain = Terrain()

    // replace groundPaint with a terrain paint
    private val terrainPaint = Paint().apply {
        color = Color.parseColor("#b3dd65")
        style = Paint.Style.FILL
    }
    private var screenWidth = 0f
    private var screenHeight = 0f
    private var groundY = 0f
    // the balls position in WORLD space, x keeps growing as the ball moves right
    private var ballX = 500f
    private var ballY = 500f

    // how fast the ball moves per frame, positive = right, negative = left
    private var velocityX = 0f
    private var ballRotation = 0f

    // cameraX tracks how far we have scrolled, subtract this when drawing to convert world -> screen
    private var cameraX = 0f
    private var cameraY = 0f

    // radius of the ball in pixels
    private val ballRadius = 40f

    // load the raw bitmap from drawable folder
    private val rawBitmap = BitmapFactory.decodeResource(resources, R.drawable.me)

    // scale the bitmap to match the ball diameter (ballRadius * 2)
    private val ballDiameter = (ballRadius * 2).toInt()
    private val scaledBitmap = Bitmap.createScaledBitmap(rawBitmap, ballDiameter, ballDiameter, true)

    // BitmapShader tiles the bitmap as a paint texture
    // CLAMP means it wont repeat, it just holds the edge color if it goes outside
    private val ballPaint = Paint().apply {
        shader = BitmapShader(scaledBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        isAntiAlias = true
    }
    // paint for a simple ground line so we can see movement
    private val groundPaint = Paint().apply {
        color = Color.parseColor("#9edb8f")
        strokeWidth = 6f
    }

    // paint for a debug text showing world x position
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
    }
    private val circumference = 2f * Math.PI.toFloat() * ballRadius
    // --- GAME LOOP ---

    // the game loop runs on a background thread, this flag tells it when to stop
    private var isRunning = false

    // the thread that runs our update + draw loop
    private var gameThread: Thread? = null

    // --- INPUT ---

    // we track which direction the user is pressing
    // true = pressing right side of screen, false = pressing left, null = not pressing
    private var touchDirection: Float? = null

    init {
        // add the surfaceview into our expo view so it appears on screen
        addView(surfaceView)

        // listen for touch events on the surface so the user can control the ball
        surfaceView.setOnTouchListener { _, event ->
            when (event.action) {

                // when the user puts their finger down, check which side of the screen they touched
                MotionEvent.ACTION_DOWN -> {
                    // if touch x is on the right half of the screen, move right, else move left
                    touchDirection = if (event.x > width / 2f) 1f else -1f
                }

                // when finger is lifted, stop moving
                MotionEvent.ACTION_UP -> {
                    touchDirection = null
                    velocityX = 0f
                }
            }
            true // return true to say we handled the event
        }
    }

    // --- GAME LOOP FUNCTIONS ---

    // update is called every frame to move things
    private fun update() {

        // if the user is pressing a direction, accelerate the ball that way
        touchDirection?.let { dir ->
            // dir is 1f for right, -1f for left
            velocityX = dir * 12f
        }
        ballY = terrain.getGroundY(ballX) - ballRadius

        // move the ball by its velocity
        ballX += velocityX
        
        ballRotation += (velocityX / circumference) * 360f

        // camera follows the ball, keeping it centered horizontally on screen
        cameraX = ballX - width / 2f
        cameraY = ballY - screenHeight / 2f
    }

    // draw is called every frame to render the current state
    private fun draw() {
        if (screenWidth == 0f) return
        // lockCanvas gives us a canvas to draw on, returns null if surface isnt ready
        val canvas = surfaceView.holder.lockCanvas() ?: return
        
        // clear the screen with a dark background each frame
        canvas.drawColor(Color.parseColor("#9be9e9"))
        terrain.draw(canvas, cameraX,cameraY, screenWidth, screenHeight, terrainPaint)

        // convert ball world position to screen position by subtracting cameraX
        val screenX = ballX - cameraX
        val screenY = ballY - cameraY


        // draw the ball as a white circle
        canvas.drawCircle(screenX, screenY, ballRadius, ballPaint)

        // draw a simple ground line across the screen at y=600 for reference
        canvas.drawLine(0f, groundY, width.toFloat(), groundY, groundPaint)

        // draw the world x position for debugging so we can see the ball is actually moving in world space
        canvas.drawText("world x: ${ballX.toInt()}", 40f, 60f, textPaint)



        // the shader draws from 0,0 by default so we need to move it to where the ball is
        // otherwise the texture wont line up with the circle
        val matrix = Matrix()
        matrix.setTranslate(screenX - ballRadius, screenY - ballRadius)
        matrix.postRotate(ballRotation, screenX, screenY)
        (ballPaint.shader as BitmapShader).setLocalMatrix(matrix)

        // drawCircle now fills the circle with the bitmap texture instead of a flat color
        canvas.drawCircle(screenX, screenY, ballRadius, ballPaint)
        // unlock and post the canvas so it gets displayed on screen
        surfaceView.holder.unlockCanvasAndPost(canvas)
    }

    // the game loop, runs update then draw then sleeps to target ~60fps
    private fun gameLoop() {
        while (isRunning) {
            update()
            draw()

            // sleep for ~16ms to target 60 frames per second
            Thread.sleep(16)
        }
    }

    // --- SURFACE CALLBACKS ---

    // called when the surface is created and ready to draw on
    override fun surfaceCreated(holder: SurfaceHolder) {
       
        // start the game loop
        isRunning = true
        gameThread = Thread { gameLoop() }
        gameThread?.start()
    }

    // called if the surface size changes e.g screen rotation
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        terrain.generate(screenHeight)
        ballY = screenHeight * 0.85f
        ballX = screenWidth * 0.5f
        groundY = ballY + ballRadius

    }

    // called when the surface is destroyed e.g app goes to background
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // stop the game loop and wait for the thread to finish cleanly
        isRunning = false
        gameThread?.join()
    }
}