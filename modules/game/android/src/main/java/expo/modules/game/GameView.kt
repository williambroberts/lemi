package expo.modules.gameinternals

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView

import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2
import org.dyn4j.world.World
import org.dyn4j.world.listener.ContactListenerAdapter

class GameInternalsView(context: Context, appContext: AppContext) : ExpoView(context, appContext),
    SurfaceHolder.Callback {

    private val surfaceView = SurfaceView(context).also { sv ->
        sv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        sv.holder.addCallback(this)
        sv.holder.setFormat(PixelFormat.RGBA_8888)
        sv.setZOrderMediaOverlay(true)
    }

    @Volatile private var isRunning    = false
    @Volatile private var surfaceReady = false
    private var gameThread: Thread?    = null
    private val targetFps              = 60
    private val targetFrameTimeMs      = 1_000L / targetFps
    private var lastFrameMs            = System.currentTimeMillis()

    private var surfaceWidth  = 0
    private var surfaceHeight = 0
    private val SCALE         = 100.0

    // World size in physics units (set in onSizeKnown)
    private var worldWidth  = 0.0
    private var worldHeight = 0.0

    private var world: World<Body>? = null
    private var player: Body?       = null
    private val platforms           = mutableListOf<Body>()

    // Camera position in screen pixels
    private var cameraX = 0f
    private var cameraY = 0f

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    @Volatile private var moveLeft  = false
    @Volatile private var moveRight = false
    @Volatile private var doJump    = false
    @Volatile private var onGround  = false

    private var ptrLeft  = -1
    private var ptrRight = -1
    private var ptrJump  = -1

    // -------------------------------------------------------------------------
    // Paint
    // -------------------------------------------------------------------------

    private val bgPaint = Paint().apply {
        color = Color.rgb(15, 15, 30)
        style = Paint.Style.FILL
    }
    private val playerFillPaint = Paint().apply {
        color = Color.rgb(80, 180, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val playerStrokePaint = Paint().apply {
        color = Color.rgb(40, 120, 200)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val eyeWhitePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val pupilPaint = Paint().apply {
        color = Color.rgb(20, 20, 20)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val platformFillPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val platformStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val btnFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val btnStrokePaint = Paint().apply {
        color = Color.argb(180, 180, 180, 180)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val btnTextPaint = Paint().apply {
        color = Color.WHITE
        textSize  = 52f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    // Mini-map paints
    private val minimapBgPaint = Paint().apply {
        color = Color.argb(140, 10, 10, 20)
        style = Paint.Style.FILL
    }
    private val minimapPlatPaint = Paint().apply {
        color = Color.argb(180, 70, 180, 100)
        style = Paint.Style.FILL
    }
    private val minimapPlayerPaint = Paint().apply {
        color = Color.rgb(80, 180, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val minimapBorderPaint = Paint().apply {
        color = Color.argb(200, 100, 100, 130)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // -------------------------------------------------------------------------
    // Button rects
    // -------------------------------------------------------------------------

    private val btnLeft  = RectF()
    private val btnRight = RectF()
    private val btnJump  = RectF()

    // =========================================================================
    // init
    // =========================================================================

    init {
        setWillNotDraw(true)
        clipChildren = false
        addView(surfaceView)
        surfaceView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
    }

    // =========================================================================
    // Touch
    // =========================================================================

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id  = event.getPointerId(idx)
                val x   = event.getX(idx)
                val y   = event.getY(idx)
                if (btnLeft.contains(x, y))  { moveLeft  = true; ptrLeft  = id }
                if (btnRight.contains(x, y)) { moveRight = true; ptrRight = id }
                if (btnJump.contains(x, y))  { doJump    = true; ptrJump  = id }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id  = event.getPointerId(idx)
                if (id == ptrLeft)  { moveLeft  = false; ptrLeft  = -1 }
                if (id == ptrRight) { moveRight = false; ptrRight = -1 }
                if (id == ptrJump)  {                    ptrJump  = -1 }
            }
            MotionEvent.ACTION_CANCEL -> {
                moveLeft = false; moveRight = false
                ptrLeft = -1; ptrRight = -1; ptrJump = -1
            }
        }
    }

    // =========================================================================
    // Surface callbacks
    // =========================================================================

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startGameLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth  = width
        surfaceHeight = height
        onSizeKnown(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopGameLoop()
    }

    // =========================================================================
    // World setup
    // =========================================================================

    private fun onSizeKnown(width: Int, height: Int) {
        // World is 3x screen width, 2x screen height
        worldWidth  = width  * 3.0 / SCALE
        worldHeight = height * 2.0 / SCALE

        val w  = World<Body>()
        w.setGravity(Vector2(0.0, -50.0))

        val pw = worldWidth
        val ph = worldHeight

        platforms.clear()

        fun makeStatic(cx: Double, cy: Double, bw: Double, bh: Double): Body {
            val b = Body()
            b.addFixture(Geometry.createRectangle(bw, bh), 1.0, 0.6, 0.05)
            b.setMass(MassType.INFINITE)
            b.setAtRestDetectionEnabled(false)
            b.translate(cx, cy)
            w.addBody(b)
            return b
        }

        // Ground — full world width
        platforms.add(makeStatic(pw / 2.0, 0.1, pw, 0.2))

        // Boundary walls — invisible
        makeStatic(0.05,      ph / 2.0, 0.1, ph)   // left
        makeStatic(pw - 0.05, ph / 2.0, 0.1, ph)   // right

        // --- Platforms spread across 3x2 world ---
        // Row 1 (low)
        platforms.add(makeStatic(pw * 0.08,  ph * 0.14, pw * 0.10, 0.15))
        platforms.add(makeStatic(pw * 0.22,  ph * 0.20, pw * 0.12, 0.15))
        platforms.add(makeStatic(pw * 0.38,  ph * 0.13, pw * 0.10, 0.15))
        platforms.add(makeStatic(pw * 0.52,  ph * 0.18, pw * 0.14, 0.15))
        platforms.add(makeStatic(pw * 0.68,  ph * 0.12, pw * 0.10, 0.15))
        platforms.add(makeStatic(pw * 0.82,  ph * 0.20, pw * 0.12, 0.15))
        platforms.add(makeStatic(pw * 0.94,  ph * 0.15, pw * 0.08, 0.15))

        // Row 2 (mid-low)
        platforms.add(makeStatic(pw * 0.14,  ph * 0.32, pw * 0.12, 0.15))
        platforms.add(makeStatic(pw * 0.30,  ph * 0.38, pw * 0.14, 0.15))
        platforms.add(makeStatic(pw * 0.47,  ph * 0.30, pw * 0.10, 0.15))
        platforms.add(makeStatic(pw * 0.60,  ph * 0.36, pw * 0.12, 0.15))
        platforms.add(makeStatic(pw * 0.75,  ph * 0.28, pw * 0.14, 0.15))
        platforms.add(makeStatic(pw * 0.88,  ph * 0.35, pw * 0.10, 0.15))

        // Row 3 (mid-high)
        platforms.add(makeStatic(pw * 0.06,  ph * 0.50, pw * 0.10, 0.15))
        platforms.add(makeStatic(pw * 0.20,  ph * 0.55, pw * 0.14, 0.15))
        platforms.add(makeStatic(pw * 0.36,  ph * 0.48, pw * 0.12, 0.15))
        platforms.add(makeStatic(pw * 0.52,  ph * 0.54, pw * 0.10, 0.15))
        platforms.add(makeStatic(pw * 0.66,  ph * 0.46, pw * 0.14, 0.15))
        platforms.add(makeStatic(pw * 0.80,  ph * 0.52, pw * 0.12, 0.15))
        platforms.add(makeStatic(pw * 0.93,  ph * 0.48, pw * 0.10, 0.15))

        // Row 4 (high)
        platforms.add(makeStatic(pw * 0.12,  ph * 0.68, pw * 0.12, 0.15))
        platforms.add(makeStatic(pw * 0.28,  ph * 0.72, pw * 0.10, 0.15))
        platforms.add(makeStatic(pw * 0.44,  ph * 0.65, pw * 0.14, 0.15))
        platforms.add(makeStatic(pw * 0.58,  ph * 0.70, pw * 0.12, 0.15))
        platforms.add(makeStatic(pw * 0.73,  ph * 0.66, pw * 0.10, 0.15))
        platforms.add(makeStatic(pw * 0.87,  ph * 0.72, pw * 0.12, 0.15))

        // Player — starts bottom centre
        val p = Body()
        p.addFixture(Geometry.createCircle(0.25), 4.0, 0.3, 0.0)  // density 4.0 = heavy, friction 0.3
p.setMass(MassType.NORMAL)
p.setLinearDamping(15.0)   // high drag = stops quickly, precise control
p.setAngularDamping(1000.0)
p.setAtRestDetectionEnabled(false)
        p.translate(pw / 2.0, 1.5)
        w.addBody(p)

        w.addContactListener(object : ContactListenerAdapter<Body>() {
            override fun begin(
                collision: org.dyn4j.world.ContactCollisionData<Body>,
                contact: org.dyn4j.dynamics.contact.Contact
            ) {
                if (collision.body1 === p || collision.body2 === p) onGround = true
            }
            override fun persist(
                collision: org.dyn4j.world.ContactCollisionData<Body>,
                oldContact: org.dyn4j.dynamics.contact.Contact,
                newContact: org.dyn4j.dynamics.contact.Contact
            ) {
                if (collision.body1 === p || collision.body2 === p) onGround = true
            }
        })

        world  = w
        player = p

        // Initialise camera centred on player spawn
        cameraX = ((pw / 2.0) * SCALE - width  / 2.0).toFloat().coerceIn(0f, (worldWidth  * SCALE - width).toFloat())
        cameraY = -(((ph - 1.5) * SCALE) - height / 2.0).toFloat()
    }

    // =========================================================================
    // Game loop
    // =========================================================================

    private fun startGameLoop() {
        if (isRunning) return
        isRunning   = true
        lastFrameMs = System.currentTimeMillis()
        gameThread  = Thread(gameLoop, "GameLoop").also {
            it.priority = Thread.MAX_PRIORITY
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopGameLoop() {
        isRunning = false
        gameThread?.interrupt()
        gameThread?.join(1_000)
        gameThread = null
    }

    private val gameLoop = Runnable {
        while (isRunning) {
            if (!surfaceReady) { Thread.sleep(16); continue }
            val frameStart = System.currentTimeMillis()
            val deltaMs    = (frameStart - lastFrameMs).coerceAtMost(100)
            lastFrameMs    = frameStart
            update(deltaMs / 1_000.0)
            draw()
            val sleepTime = targetFrameTimeMs - (System.currentTimeMillis() - frameStart)
            if (sleepTime > 0) try { Thread.sleep(sleepTime) }
            catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    // =========================================================================
    // Update
    // =========================================================================

    private val MOVE_FORCE   = 40.0   // high force needed to overcome the heavy density + damping
private val JUMP_IMPULSE = 8.0    // needs more impulse to lift the heavier body

    private fun update(deltaSeconds: Double) {
        val w = world  ?: return
        val p = player ?: return

        when {
            moveLeft  -> p.applyForce(Vector2(-MOVE_FORCE, 0.0))
            moveRight -> p.applyForce(Vector2( MOVE_FORCE, 0.0))
        }

        if (doJump && onGround) {
            doJump   = false
            onGround = false
            p.linearVelocity = Vector2(p.linearVelocity.x, 0.0)
            p.applyImpulse(Vector2(0.0, JUMP_IMPULSE))
        }

        onGround = false
        w.update(deltaSeconds)

        // --- Update camera ---
        // Player position in screen-space pixels (world coords, Y flipped)
        val px = (p.transform.translationX * SCALE).toFloat()
        val py = (surfaceHeight - p.transform.translationY * SCALE).toFloat()

        // Target camera so player is centred
        val targetX = px - surfaceWidth  / 2f
        val targetY = py - surfaceHeight / 2f

        // Clamp to world bounds
        val maxCamX = (worldWidth  * SCALE - surfaceWidth).toFloat().coerceAtLeast(0f)
        val maxCamY = (worldHeight * SCALE - surfaceHeight).toFloat().coerceAtLeast(0f)

        val clampedX =  targetX.coerceIn(0f,     maxCamX)
        val clampedY =  targetY.coerceIn(-maxCamY, 0f)

        // Smooth lerp follow
        val lerp = 0.10f
        cameraX += (clampedX - cameraX) * lerp
        cameraY += (clampedY - cameraY) * lerp
    }

    // =========================================================================
    // Draw
    // =========================================================================

    private val PLAYER_R = (0.25 * 100.0).toFloat()

    private fun draw() {
        val p = player ?: return

        val holder = surfaceView.holder
        var canvas: Canvas? = null
        try {
            canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                holder.lockHardwareCanvas() else holder.lockCanvas()
            canvas ?: return

            // Clear
            canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), bgPaint)

            // ---- World drawing (camera-transformed) ----
            canvas.save()
            canvas.translate(-cameraX, -cameraY)

            fun sx(physX: Double) = (physX * SCALE).toFloat()
            fun sy(physY: Double) = (surfaceHeight - physY * SCALE).toFloat()

            // Platforms
            for ((i, plat) in platforms.withIndex()) {
                val aabb = plat.createAABB()
                val hw   = ((aabb.maxX - aabb.minX) / 2.0 * SCALE).toFloat()
                val hh   = ((aabb.maxY - aabb.minY) / 2.0 * SCALE).toFloat()
                val cx   = sx(plat.transform.translationX)
                val cy   = sy(plat.transform.translationY)

                if (i == 0) {
                    // Ground
                    platformFillPaint.color   = Color.rgb(55, 55, 65)
                    platformStrokePaint.color = Color.rgb(35, 35, 45)
                } else {
                    val green = 150 + (i * 12) % 70
                    platformFillPaint.color   = Color.rgb(45, green, 80)
                    platformStrokePaint.color = Color.rgb(25, green - 50, 50)
                }
                canvas.drawRoundRect(cx-hw, cy-hh, cx+hw, cy+hh, 10f, 10f, platformFillPaint)
                canvas.drawRoundRect(cx-hw, cy-hh, cx+hw, cy+hh, 10f, 10f, platformStrokePaint)

                // Grass strip on top of floating platforms
                if (i > 0) {
                    platformFillPaint.color = Color.rgb(80, 220, 100)
                    canvas.drawRoundRect(cx-hw, cy-hh, cx+hw, cy-hh+6f, 3f, 3f, platformFillPaint)
                }
            }

            // Player
            val px = sx(p.transform.translationX)
            val py = sy(p.transform.translationY)

            canvas.drawCircle(px, py, PLAYER_R, playerFillPaint)
            canvas.drawCircle(px, py, PLAYER_R, playerStrokePaint)

            val facing = if (p.linearVelocity.x >= 0) 1f else -1f
            val eyeX   = px + PLAYER_R * 0.30f * facing
            val eyeY   = py - PLAYER_R * 0.15f
            canvas.drawCircle(eyeX,                          eyeY, PLAYER_R * 0.30f, eyeWhitePaint)
            canvas.drawCircle(eyeX + PLAYER_R*0.10f*facing,  eyeY, PLAYER_R * 0.15f, pupilPaint)

            canvas.restore()  // end camera transform

            // ---- HUD (screen-space, not camera-affected) ----

            // Buttons
            val bw     = 150f
            val bh     = 150f
            val margin = 36f
            val btnY   = surfaceHeight - bh - margin

            btnLeft.set (margin,            btnY, margin + bw,            btnY + bh)
            btnRight.set(margin + bw + 16f, btnY, margin + bw * 2 + 16f, btnY + bh)
            btnJump.set (surfaceWidth - margin - bw, btnY, surfaceWidth - margin, btnY + bh)

            fun drawBtn(rect: RectF, label: String, active: Boolean) {
                btnFillPaint.color = if (active)
                    Color.argb(220, 80, 140, 255)
                else
                    Color.argb(160, 40, 40, 60)
                canvas.drawRoundRect(rect, 22f, 22f, btnFillPaint)
                canvas.drawRoundRect(rect, 22f, 22f, btnStrokePaint)
                canvas.drawText(label, rect.centerX(), rect.centerY() + 18f, btnTextPaint)
            }

            drawBtn(btnLeft,  "◀", moveLeft)
            drawBtn(btnRight, "▶", moveRight)
            drawBtn(btnJump,  "▲", doJump)

            // Mini-map (top-right corner)
            val mmW    = 160f
            val mmH    = 100f
            val mmX    = surfaceWidth  - mmW - 20f
            val mmY    = 20f
            val scaleX = mmW / (worldWidth  * SCALE).toFloat()
            val scaleY = mmH / (worldHeight * SCALE).toFloat()

            canvas.drawRoundRect(mmX, mmY, mmX+mmW, mmY+mmH, 8f, 8f, minimapBgPaint)

            // Platforms on minimap
            for ((i, plat) in platforms.withIndex()) {
                if (i == 0) continue  // skip ground on minimap
                val aabb = plat.createAABB()
                val l = mmX + (aabb.minX * SCALE * scaleX).toFloat()
                val r = mmX + (aabb.maxX * SCALE * scaleX).toFloat()
                // flip Y for minimap too
                val t = mmY + mmH - (aabb.maxY * SCALE * scaleY).toFloat()
                val b = mmY + mmH - (aabb.minY * SCALE * scaleY).toFloat()
                canvas.drawRect(l, t, r, b, minimapPlatPaint)
            }

            // Player dot on minimap
            val mpx = mmX + (p.transform.translationX * SCALE * scaleX).toFloat()
            val mpy = mmY + mmH - (p.transform.translationY * SCALE * scaleY).toFloat()
            canvas.drawCircle(mpx, mpy, 5f, minimapPlayerPaint)

            canvas.drawRoundRect(mmX, mmY, mmX+mmW, mmY+mmH, 8f, 8f, minimapBorderPaint)

        } finally {
            canvas?.let { holder.unlockCanvasAndPost(it) }
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onAttachedToWindow() { super.onAttachedToWindow() }

    override fun onDetachedFromWindow() {
        stopGameLoop()
        super.onDetachedFromWindow()
    }

    fun pause()  { isRunning = false }
    fun resume() { if (surfaceReady) startGameLoop() }
    fun reset()  { onSizeKnown(surfaceWidth, surfaceHeight) }
}