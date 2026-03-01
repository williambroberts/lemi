package expo.modules.gameinternals

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import kotlin.math.*

// =============================================================================
// FoxRunView — Alto's Odyssey style endless runner
// Fox silhouette, jungle canopy, extensible mechanic system
// =============================================================================
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
    private val targetFrameMs          = 1_000L / 60
    private var lastFrameMs            = System.currentTimeMillis()
    private var sw = 0f; private var sh = 0f

    // =========================================================================
    // Sky / time of day
    // =========================================================================
    private var skyTime   = 0f      // 0..1 over full day cycle
    private val SKY_SPEED = 0.008f  // how fast day advances per second

    data class SkyColors(val top: Int, val horizon: Int, val ground: Int, val foliage: Int)
    private val SKY_STATES = listOf(
        // Dawn
        SkyColors(Color.rgb(20,10,40),   Color.rgb(255,120,60),  Color.rgb(40,25,15),  Color.rgb(20,45,15)),
        // Morning
        SkyColors(Color.rgb(120,190,255),Color.rgb(255,230,180), Color.rgb(60,40,20),  Color.rgb(30,70,20)),
        // Midday
        SkyColors(Color.rgb(60,150,255), Color.rgb(180,230,255), Color.rgb(50,35,15),  Color.rgb(25,80,15)),
        // Golden hour
        SkyColors(Color.rgb(255,140,40), Color.rgb(255,80,30),   Color.rgb(80,40,10),  Color.rgb(50,55,10)),
        // Dusk
        SkyColors(Color.rgb(60,20,80),   Color.rgb(200,60,100),  Color.rgb(30,15,20),  Color.rgb(20,30,15)),
        // Night
        SkyColors(Color.rgb(5,5,25),     Color.rgb(20,15,50),    Color.rgb(10,8,18),   Color.rgb(8,20,8)),
    )

    private fun currentSky(): SkyColors {
        val n = SKY_STATES.size
        val pos = skyTime * n
        val i = pos.toInt() % n
        val t = pos - pos.toInt()
        val a = SKY_STATES[i]; val b = SKY_STATES[(i+1) % n]
        return SkyColors(
            lerpColor(a.top,     b.top,     t),
            lerpColor(a.horizon, b.horizon, t),
            lerpColor(a.ground,  b.ground,  t),
            lerpColor(a.foliage, b.foliage, t))
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val r = (Color.red(c1)   + (Color.red(c2)   - Color.red(c1))   * t).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt()
        val b = (Color.blue(c1)  + (Color.blue(c2)  - Color.blue(c1))  * t).toInt()
        return Color.rgb(r.coerceIn(0,255), g.coerceIn(0,255), b.coerceIn(0,255))
    }

    // =========================================================================
    // Terrain
    // =========================================================================
    private val CHUNK_W      = 600f
    private val BASE_Y_FRAC  = 0.62f   // terrain sits at ~62% of screen height
    private val SLOPE_AMP    = 0.18f   // amplitude of hills as fraction of sh
    private val MIN_SLOPE    = -0.55f  // max downhill gradient
    private val MAX_SLOPE    = 0.45f   // max uphill gradient
    private val WALL_CHANCE  = 0.12f   // probability a chunk has a climbable wall

    data class TerrainChunk(
        val startX: Float, val startY: Float,
        val cp1X: Float, val cp1Y: Float,
        val cp2X: Float, val cp2Y: Float,
        val endX: Float, val endY: Float,
        val hasWall: Boolean = false,
        val wallX: Float = 0f,
        val wallHeight: Float = 0f
    ) {
        // Evaluate Y on the bezier at parameter t ∈ [0,1]
        fun yAt(t: Float): Float {
            val u = 1f - t
            return u*u*u*startY + 3f*u*u*t*cp1Y + 3f*u*t*t*cp2Y + t*t*t*endY
        }
        // Get terrain Y for world X (approximate — binary search t)
        fun yAtX(wx: Float): Float {
            if (wx <= startX) return startY
            if (wx >= endX) return endY
            val frac = (wx - startX) / (endX - startX)
            // Refine with a couple Newton steps
            var t = frac
            repeat(4) {
                val xEst = (1-t)*(1-t)*(1-t)*startX + 3*(1-t)*(1-t)*t*cp1X + 3*(1-t)*t*t*cp2X + t*t*t*endX
                val dxdt = 3*(1-t)*(1-t)*(cp1X-startX) + 6*(1-t)*t*(cp2X-cp1X) + 3*t*t*(endX-cp2X)
                if (abs(dxdt) > 0.001f) t -= (xEst - wx) / dxdt
                t = t.coerceIn(0f, 1f)
            }
            return yAt(t)
        }
        // Terrain slope angle at approximate X
        fun slopeAt(wx: Float): Float {
            val dx = 1f
            return atan2(yAtX(wx+dx) - yAtX(wx), dx)
        }
    }

    private val chunks = ArrayDeque<TerrainChunk>()
    private var nextChunkX = 0f

    private fun generateChunk(startX: Float, startY: Float): TerrainChunk {
        val targetY = (startY + (Math.random().toFloat() - 0.5f) * sh * SLOPE_AMP * 2f)
            .coerceIn(sh * 0.40f, sh * 0.80f)
        val slope = ((targetY - startY) / CHUNK_W).coerceIn(MIN_SLOPE, MAX_SLOPE)
        val endY = startY + slope * CHUNK_W
        val midY = (startY + endY) / 2f + (Math.random().toFloat() - 0.5f) * sh * SLOPE_AMP
        val hasWall = Math.random() < WALL_CHANCE && abs(slope) < 0.2f
        val wallX = if (hasWall) startX + CHUNK_W * (0.3f + Math.random().toFloat() * 0.4f) else 0f
        val wallH = if (hasWall) sh * (0.12f + Math.random().toFloat() * 0.18f) else 0f
        return TerrainChunk(
            startX, startY,
            startX + CHUNK_W * 0.35f, midY - sh * 0.04f,
            startX + CHUNK_W * 0.65f, midY + sh * 0.04f,
            startX + CHUNK_W, endY,
            hasWall, wallX, wallH
        )
    }

    private fun terrainYAt(worldX: Float): Float {
        for (c in chunks) {
            if (worldX >= c.startX && worldX <= c.endX) return c.yAtX(worldX)
        }
        return sh * BASE_Y_FRAC
    }

    private fun terrainSlopeAt(worldX: Float): Float {
        for (c in chunks) {
            if (worldX >= c.startX && worldX <= c.endX) return c.slopeAt(worldX)
        }
        return 0f
    }

    // =========================================================================
    // Collectibles
    // =========================================================================
    data class Coin(val x: Float, val y: Float, var collected: Boolean = false, var anim: Float = 0f)
    private val coins = mutableListOf<Coin>()
    private var coinScore = 0

    // =========================================================================
    // Particles
    // =========================================================================
    data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val r: Float)
    private val particles = mutableListOf<Particle>()

    // =========================================================================
    // Stars (night sky)
    // =========================================================================
    data class Star(val x: Float, val y: Float, val r: Float, val twinkleOffset: Float)
    private val stars = List(120) {
        Star((Math.random()*4000).toFloat(), (Math.random()*0.45f).toFloat(),
            (0.8f + Math.random()*2.2f).toFloat(), (Math.random()*Math.PI*2).toFloat())
    }

    // =========================================================================
    // Fox physics state
    // =========================================================================
    var foxX     = 0f    // world X
    var foxY     = 0f    // world Y (screen coords)
    var foxVx    = 220f  // horizontal speed (world units/sec)
    var foxVy    = 0f    // vertical velocity
    var foxAngle = 0f    // rotation for flips (radians)
    var foxAngVel = 0f   // angular velocity during flip

    // Tail animation
    var tailAngle    = 0f  // tail rotation offset (lags behind body)
    var tailAngVel   = 0f

    var isOnGround   = false
    var isOnWall     = false
    var wallChunk: TerrainChunk? = null
    var wallSide     = 1f   // +1 = right wall, -1 = left wall

    var runTime      = 0f
    var score        = 0
    var comboCount   = 0
    var comboTimer   = 0f
    var bestScore    = 0

    val GRAVITY      = 980f
    val MAX_VX       = 520f
    val ACCEL_DOWNHILL = 140f
    val DECEL_UPHILL   = 80f
    val FOX_R          = 22f

    // =========================================================================
    // Extensible mechanic system
    // =========================================================================
    interface Mechanic {
        val id: String
        fun onTouchDown(state: FoxState) {}
        fun onTouchUp(state: FoxState) {}
        fun update(dt: Float, state: FoxState) {}
        fun draw(canvas: Canvas, state: FoxState, camX: Float) {}
        fun reset() {}
    }

    data class FoxState(
        var foxX: Float, var foxY: Float, var foxVx: Float, var foxVy: Float,
        var foxAngle: Float, var foxAngVel: Float,
        var isOnGround: Boolean, var isOnWall: Boolean,
        var wallChunk: TerrainChunk?, var wallSide: Float,
        var comboCount: Int, var comboTimer: Float,
        var score: Int,
        // Mechanic flags other mechanics can read
        var isFlipping: Boolean = false,
        var jumpCount: Int = 0,        // 0 = on ground, 1 = single jump, 2 = double jump used
        var isGrinding: Boolean = false
    )

    // --- Jump mechanic ---
    inner class JumpMechanic : Mechanic {
        override val id = "jump"
        private val JUMP_VY = -680f

        override fun onTouchDown(state: FoxState) {
            if (state.isOnGround && state.jumpCount == 0) {
                state.foxVy = JUMP_VY
                state.isOnGround = false
                state.jumpCount = 1
                spawnJumpDust(state.foxX, state.foxY)
            }
        }

        override fun reset() {}
    }

    // --- Backflip mechanic ---
    inner class BackflipMechanic : Mechanic {
        override val id = "backflip"
        private var holding     = false
        private var flipsDone   = 0
        private var lastFlipAng = 0f
        private val FLIP_ANG_SPD = 7.0f  // rad/sec while holding

        override fun onTouchDown(state: FoxState) {
            if (!state.isOnGround && state.jumpCount > 0) {
                holding = true
                lastFlipAng = state.foxAngle
            }
        }

        override fun onTouchUp(state: FoxState) {
            if (holding) {
                holding = false
                // Check clean landing angle (within ±30° of upright)
                val normalised = ((state.foxAngle % (2*PI)).toFloat() + 2*PI.toFloat()) % (2*PI.toFloat())
                val nearUpright = normalised < 0.52f || normalised > (2*PI - 0.52f).toFloat()
                if (flipsDone > 0 && !nearUpright) {
                    // Bad landing will be caught in update via crash check
                }
                flipsDone = 0
            }
        }

        override fun update(dt: Float, state: FoxState) {
            if (holding && !state.isOnGround) {
                state.foxAngVel = -FLIP_ANG_SPD  // spin backward
                state.isFlipping = true
                // Count full flips
                val prev = lastFlipAng
                lastFlipAng = state.foxAngle
                val delta = prev - state.foxAngle
                if (delta > Math.PI) {
                    flipsDone++
                    state.comboCount++
                    state.comboTimer = 3f
                    state.score += flipsDone * 10  // escalating points per flip
                    spawnFlipSparks(state.foxX, state.foxY)
                }
            } else if (!holding) {
                state.foxAngVel *= 0.85f
                state.isFlipping = state.foxAngVel.absoluteValue > 0.5f
            }
        }

        override fun reset() { holding = false; flipsDone = 0; lastFlipAng = 0f }
    }

    // --- Wall ride mechanic ---
    inner class WallRideMechanic : Mechanic {
        override val id = "wallride"
        private var grindTime  = 0f
        private val GRIND_SCORE_RATE = 20f  // points per second while grinding

        override fun update(dt: Float, state: FoxState) {
            if (state.isOnWall && state.isGrinding) {
                grindTime += dt
                state.foxVy = 60f  // slow slide down wall
                state.foxAngVel = 0f
                state.foxAngle = if (state.wallSide > 0) -PI.toFloat()/2f else PI.toFloat()/2f
                state.comboCount = maxOf(state.comboCount, 1)
                state.comboTimer = 2f
                state.score += (GRIND_SCORE_RATE * dt).toInt()
                // Spark trail
                if (Math.random() < 0.3f) spawnGrindSpark(state.foxX, state.foxY)
            } else {
                grindTime = 0f
            }
        }

        override fun reset() { grindTime = 0f }
    }

    private val mechanics: List<Mechanic> = listOf(
        JumpMechanic(),
        BackflipMechanic(),
        WallRideMechanic()
    )

    // Easy extension point — call this to add new mechanics at runtime
    // e.g. addMechanic(DoubleJumpMechanic())

    // =========================================================================
    // Camera
    // =========================================================================
    private var camX   = 0f   // world X of left edge of screen
    private val CAM_LEAD = 0.30f  // fox sits at 30% from left

    // =========================================================================
    // Game state
    // =========================================================================
    enum class GamePhase { TITLE, RUNNING, CRASHED, RESULTS }
    private var phase        = GamePhase.TITLE
    private var phaseTimer   = 0f
    private var crashAnim    = 0f   // 0..1
    private var touchDown    = false
    private var titleAlpha   = 1f

    // =========================================================================
    // Paint cache
    // =========================================================================
    private val skyPaint     = Paint().apply { style = Paint.Style.FILL }
    private val terrainPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val terrainEdge  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val foliagePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val canopyDark   = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val wallPaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val wallEdge     = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
    private val foxPaint     = Paint().apply { color = Color.rgb(15,10,8); style = Paint.Style.FILL; isAntiAlias = true }
    private val foxTailTip   = Paint().apply { color = Color.rgb(200,195,185); style = Paint.Style.FILL; isAntiAlias = true }
    private val coinPaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val coinGlow     = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val particlePaint= Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val starPaint    = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    private val grindLine    = Paint().apply { color = Color.argb(200,255,200,50); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
    private val hudPaint     = Paint().apply { color = Color.WHITE; textSize = 52f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(6f,0f,2f,Color.argb(120,0,0,0)) }
    private val hudSmall     = Paint().apply { color = Color.WHITE; textSize = 32f; isAntiAlias = true; setShadowLayer(4f,0f,1f,Color.argb(100,0,0,0)) }
    private val comboPaint   = Paint().apply { textSize = 48f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; setShadowLayer(6f,0f,2f,Color.BLACK) }
    private val overlayPaint = Paint().apply { style = Paint.Style.FILL }
    private val speedBarBgPaint  = Paint().apply { color = Color.argb(80,255,255,255); style = Paint.Style.FILL }
    private val speedBarFillPaint= Paint().apply { color = Color.argb(200,255,180,50); style = Paint.Style.FILL }
    private val titleTextPaint   = Paint().apply { color = Color.WHITE; textSize = 80f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val resultTextPaint  = Paint().apply { color = Color.WHITE; textSize = 72f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(8f,0f,3f,Color.BLACK) }
    private val vinePaint        = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
    private val mossPaint        = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true }
    private val gradientRect = RectF()

    // Reusable paths
    private val terrainPath  = Path()
    private val foxBodyPath  = Path()
    private val foxTailPath  = Path()
    private val canopyPath   = Path()

    // =========================================================================
    // Init
    // =========================================================================
    init {
        setWillNotDraw(true); clipChildren = false; addView(surfaceView)
        surfaceView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> onTouchDown()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouchUp()
            }
            true
        }
    }

    private fun initRun() {
        chunks.clear(); coins.clear(); particles.clear()
        foxX = sw * 0.3f; foxY = sh * BASE_Y_FRAC - FOX_R
        foxVx = 220f; foxVy = 0f; foxAngle = 0f; foxAngVel = 0f
        tailAngle = 0f; tailAngVel = 0f
        isOnGround = true; isOnWall = false; wallChunk = null
        runTime = 0f; score = 0; coinScore = 0; comboCount = 0; comboTimer = 0f
        skyTime = 0.15f
        nextChunkX = 0f
        // Generate initial terrain
        var cx = -CHUNK_W; var cy = sh * BASE_Y_FRAC
        repeat(12) {
            val chunk = generateChunk(cx, cy)
            chunks.addLast(chunk)
            // Scatter coins on this chunk
            spawnCoinsOnChunk(chunk)
            cx = chunk.endX; cy = chunk.endY
            nextChunkX = cx
        }
        camX = foxX - sw * CAM_LEAD
        mechanics.forEach { it.reset() }
    }

    private fun spawnCoinsOnChunk(chunk: TerrainChunk) {
        val count = (2 + Math.random() * 4).toInt()
        repeat(count) {
            val t = 0.15f + Math.random().toFloat() * 0.7f
            val wx = chunk.startX + t * (chunk.endX - chunk.startX)
            val wy = chunk.yAtX(wx) - 45f - Math.random().toFloat() * 30f
            coins.add(Coin(wx, wy))
        }
    }

    // =========================================================================
    // Touch
    // =========================================================================
    private fun onTouchDown() {
        touchDown = true
        when (phase) {
            GamePhase.TITLE -> { phase = GamePhase.RUNNING; initRun() }
            GamePhase.RESULTS -> { phase = GamePhase.TITLE; titleAlpha = 1f }
            GamePhase.RUNNING -> {
                val state = buildState()
                mechanics.forEach { it.onTouchDown(state) }
                syncFromState(state)
            }
            GamePhase.CRASHED -> {} // wait for results
        }
    }

    private fun onTouchUp() {
        touchDown = false
        if (phase == GamePhase.RUNNING) {
            val state = buildState()
            mechanics.forEach { it.onTouchUp(state) }
            syncFromState(state)
        }
    }

    private fun buildState() = FoxState(foxX, foxY, foxVx, foxVy, foxAngle, foxAngVel,
        isOnGround, isOnWall, wallChunk, wallSide, comboCount, comboTimer, score)

    private fun syncFromState(s: FoxState) {
        foxX=s.foxX; foxY=s.foxY; foxVx=s.foxVx; foxVy=s.foxVy
        foxAngle=s.foxAngle; foxAngVel=s.foxAngVel
        isOnGround=s.isOnGround; isOnWall=s.isOnWall
        wallChunk=s.wallChunk; wallSide=s.wallSide
        comboCount=s.comboCount; comboTimer=s.comboTimer; score=s.score
    }

    // =========================================================================
    // Surface
    // =========================================================================
    override fun surfaceCreated(h: SurfaceHolder)  { surfaceReady = true; startLoop() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
        sw = w.toFloat(); sh = ht.toFloat()
        if (phase == GamePhase.TITLE && chunks.isEmpty()) {
            // Prime terrain for title screen
            var cx = -CHUNK_W * 2f; var cy = sh * BASE_Y_FRAC
            repeat(8) {
                val chunk = generateChunk(cx, cy)
                chunks.addLast(chunk); cx = chunk.endX; cy = chunk.endY
            }
            nextChunkX = cx
            foxX = sw * 0.3f; foxY = sh * BASE_Y_FRAC - FOX_R
            camX = foxX - sw * CAM_LEAD
        }
    }
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stopLoop() }

    private fun startLoop() {
        if (isRunning) return
        isRunning = true; lastFrameMs = System.currentTimeMillis()
        gameThread = Thread({
            while (isRunning) {
                if (!surfaceReady) { Thread.sleep(16); continue }
                val fs = System.currentTimeMillis()
                val dt = ((fs - lastFrameMs).coerceAtMost(80)).toFloat() / 1000f
                lastFrameMs = fs
                update(dt); draw()
                val sleep = targetFrameMs - (System.currentTimeMillis() - fs)
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }, "FoxLoop").also { it.isDaemon = true; it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    private fun stopLoop() { isRunning = false; gameThread?.interrupt(); gameThread?.join(500); gameThread = null }

    // =========================================================================
    // Update
    // =========================================================================
    private fun update(dt: Float) {
        skyTime = (skyTime + SKY_SPEED * dt) % 1f
        updateParticles(dt)

        when (phase) {
            GamePhase.TITLE   -> updateTitle(dt)
            GamePhase.RUNNING -> updateRunning(dt)
            GamePhase.CRASHED -> updateCrashed(dt)
            GamePhase.RESULTS -> { phaseTimer -= dt }
        }
    }

    private fun updateTitle(dt: Float) {
        // Gentle auto-scroll on title
        foxX += 120f * dt; camX += 120f * dt
        ensureChunks(); pruneChunks()
        val ty = terrainYAt(foxX) - FOX_R
        foxY = ty  // sit on ground
        tailAngle += dt * 0.8f
    }

    private fun updateRunning(dt: Float) {
        runTime += dt; score = (runTime * 8f).toInt() + coinScore * 5

        val state = buildState()

        // Mechanics update
        mechanics.forEach { it.update(dt, state) }
        syncFromState(state)

        // Terrain slope affects speed
        val slope = terrainSlopeAt(foxX)
        if (slope > 0.05f)      foxVx = (foxVx - DECEL_UPHILL * slope * dt).coerceAtLeast(80f)
        else if (slope < -0.05f) foxVx = (foxVx + ACCEL_DOWNHILL * (-slope) * dt).coerceAtMost(MAX_VX)
        foxVx = (foxVx + 15f * dt).coerceAtMost(MAX_VX)  // gentle base acceleration

        // Gravity
        if (!isOnGround && !isOnWall) foxVy += GRAVITY * dt

        // Integrate position
        foxX += foxVx * dt
        foxY += foxVy * dt
        foxAngle += foxAngVel * dt

        // Tail physics — lags behind body rotation
        val tailTarget = foxAngle * 0.6f
        tailAngVel += (tailTarget - tailAngle) * 12f * dt
        tailAngVel *= 0.85f
        tailAngle += tailAngVel * dt

        // Ground collision
        val groundY = terrainYAt(foxX) - FOX_R * 0.7f
        if (foxY >= groundY) {
            // Check crash — bad flip landing angle
            val norm = ((foxAngle % (2*PI.toFloat())) + 2*PI.toFloat()) % (2*PI.toFloat())
            val badAngle = norm > 0.6f && norm < (2*PI - 0.6f).toFloat()
            if (badAngle && foxAngVel.absoluteValue > 3f) {
                doCrash(); return
            }
            foxY = groundY; foxVy = 0f
            foxAngle = 0f; foxAngVel = 0f; tailAngle = 0f
            isOnGround = true
            val state2 = buildState(); state2.jumpCount = 0; syncFromState(state2)
            spawnLandDust(foxX, foxY)
        } else {
            isOnGround = false
        }

        // Wall collision detection
        isOnWall = false; wallChunk = null
        val state3 = buildState(); state3.isGrinding = false
        for (chunk in chunks) {
            if (!chunk.hasWall) continue
            val wx = chunk.wallX - camX  // not needed, use world coords
            val distToWall = abs(foxX - chunk.wallX)
            val wallTopY = chunk.yAtX(chunk.wallX) - chunk.wallHeight
            if (distToWall < FOX_R + 8f && foxY > wallTopY && foxY < chunk.yAtX(chunk.wallX)) {
                isOnWall = true
                wallChunk = chunk
                wallSide = if (foxX < chunk.wallX) -1f else 1f
                state3.isOnWall = true; state3.wallChunk = chunk; state3.wallSide = wallSide
                state3.isGrinding = true
                break
            }
        }
        syncFromState(state3)
        mechanics.forEach { it.update(0f, state3) }  // let wallride mechanic run
        syncFromState(state3)

        // Out of bounds crash (fell off bottom)
        if (foxY > sh + 100f) { doCrash(); return }

        // Combo timer
        if (comboTimer > 0f) comboTimer -= dt else comboCount = 0

        // Coin collection
        for (coin in coins) {
            if (!coin.collected && abs(foxX - coin.x) < FOX_R + 12f && abs(foxY - coin.y) < FOX_R + 12f) {
                coin.collected = true; coin.anim = 1f; coinScore++
                comboCount++; comboTimer = 2.5f
                spawnCoinBurst(coin.x, coin.y)
            }
            if (coin.anim > 0f) coin.anim -= dt * 3f
        }
        coins.removeAll { it.collected && it.anim <= 0f }

        // Generate terrain ahead, prune behind
        ensureChunks(); pruneChunks()

        // Camera — smooth follow with lead
        val targetCamX = foxX - sw * CAM_LEAD
        camX += (targetCamX - camX) * 8f * dt
    }

    private fun updateCrashed(dt: Float) {
        crashAnim = (crashAnim + dt * 2f).coerceAtMost(1f)
        phaseTimer -= dt
        if (phaseTimer <= 0f) {
            phase = GamePhase.RESULTS
            phaseTimer = 999f
            if (score > bestScore) bestScore = score
        }
    }

    private fun doCrash() {
        phase = GamePhase.CRASHED; phaseTimer = 1.8f; crashAnim = 0f
        repeat(12) { spawnCrashFrag(foxX, foxY) }
        foxVx = 0f; foxAngVel = 8f
    }

    private fun ensureChunks() {
        while (nextChunkX < camX + sw * 2.5f) {
            val last = chunks.lastOrNull()
            val sx = last?.endX ?: nextChunkX
            val sy = last?.endY ?: sh * BASE_Y_FRAC
            val chunk = generateChunk(sx, sy)
            chunks.addLast(chunk)
            spawnCoinsOnChunk(chunk)
            nextChunkX = chunk.endX
        }
    }

    private fun pruneChunks() {
        while (chunks.isNotEmpty() && chunks.first().endX < camX - CHUNK_W) {
            chunks.removeFirst()
        }
        coins.removeAll { it.x < camX - 50f }
    }

    // =========================================================================
    // Particles
    // =========================================================================
    private fun spawnJumpDust(x: Float, y: Float) {
        repeat(6) {
            val a = PI.toFloat() + (Math.random().toFloat()-0.5f) * PI.toFloat()
            particles.add(Particle(x, y, cos(a)*80f, sin(a)*80f - 40f,
                0.5f, 0.5f, Color.argb(160,180,160,120), 4f + Math.random().toFloat()*4f))
        }
    }

    private fun spawnLandDust(x: Float, y: Float) {
        repeat(8) {
            val a = PI.toFloat() + (Math.random().toFloat()-0.5f) * PI.toFloat() * 0.6f
            particles.add(Particle(x, y, cos(a)*120f, sin(a)*60f,
                0.4f, 0.4f, Color.argb(140,160,140,100), 5f + Math.random().toFloat()*5f))
        }
    }

    private fun spawnFlipSparks(x: Float, y: Float) {
        repeat(5) {
            val a = Math.random().toFloat() * 2 * PI.toFloat()
            particles.add(Particle(x, y, cos(a)*200f, sin(a)*200f - 50f,
                0.6f, 0.6f, Color.argb(220,255,220,80), 3f + Math.random().toFloat()*3f))
        }
    }

    private fun spawnGrindSpark(x: Float, y: Float) {
        val a = PI.toFloat() * 0.25f + (Math.random().toFloat()-0.5f)*0.4f
        particles.add(Particle(x, y, cos(a)*150f, sin(a)*(-150f),
            0.3f, 0.3f, Color.argb(200,255,200,50), 2f + Math.random().toFloat()*2f))
    }

    private fun spawnCoinBurst(x: Float, y: Float) {
        repeat(8) {
            val a = Math.random().toFloat() * 2 * PI.toFloat()
            val spd = 80f + Math.random().toFloat() * 120f
            particles.add(Particle(x, y, cos(a)*spd, sin(a)*spd - 80f,
                0.7f, 0.7f, Color.argb(220,255,210,60), 3f + Math.random().toFloat()*3f))
        }
    }

    private fun spawnCrashFrag(x: Float, y: Float) {
        val a = Math.random().toFloat() * 2 * PI.toFloat()
        val spd = 100f + Math.random().toFloat() * 300f
        particles.add(Particle(x, y, cos(a)*spd, sin(a)*spd - 200f,
            1.2f, 1.2f, Color.rgb(15,10,8), 6f + Math.random().toFloat()*8f))
    }

    private fun updateParticles(dt: Float) {
        particles.removeAll { p ->
            p.x += p.vx * dt; p.y += p.vy * dt
            p.vy += 400f * dt; p.vx *= 0.94f
            p.life -= dt; p.life <= 0f
        }
    }

    // =========================================================================
    // Draw
    // =========================================================================
    private fun draw() {
        val holder = surfaceView.holder; var canvas: Canvas? = null
        try {
            canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                holder.lockHardwareCanvas() else holder.lockCanvas()
            canvas ?: return
            drawScene(canvas)
        } finally { canvas?.let { holder.unlockCanvasAndPost(it) } }
    }

    private fun drawScene(canvas: Canvas) {
        val sky = currentSky()

        // Sky gradient
        gradientRect.set(0f, 0f, sw, sh)
        val skyGrad = LinearGradient(0f, 0f, 0f, sh * 0.7f, sky.top, sky.horizon, Shader.TileMode.CLAMP)
        skyPaint.shader = skyGrad
        canvas.drawRect(gradientRect, skyPaint)

        // Ground fill (below horizon)
        skyPaint.shader = null; skyPaint.color = sky.ground
        canvas.drawRect(0f, sh * 0.7f, sw, sh, skyPaint)

        // Stars (visible at night)
        val nightFactor = getNightFactor()
        if (nightFactor > 0.05f) {
            starPaint.alpha = (nightFactor * 220).toInt()
            for (s in stars) {
                val sx = ((s.x - camX * 0.05f) % (sw * 1.5f) + sw * 1.5f) % (sw * 1.5f)
                val sy = s.y * sh * 0.45f
                val twinkle = 1f + 0.3f * sin((runTime * 2f + s.twinkleOffset).toDouble()).toFloat()
                canvas.drawCircle(sx, sy, s.r * twinkle, starPaint)
            }
        }

        // Far canopy mountains (very slow parallax)
        drawFarCanopy(canvas, sky)

        // Mid canopy (medium parallax)
        drawMidCanopy(canvas, sky)

        // Terrain + walls
        drawTerrain(canvas, sky)

        // Coins
        drawCoins(canvas)

        // Particles (world space)
        canvas.save(); canvas.translate(-camX, 0f)
        for (p in particles) {
            val alpha = (p.life / p.maxLife * 255).toInt().coerceIn(0,255)
            particlePaint.color = Color.argb(alpha, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            canvas.drawCircle(p.x, p.y, p.r * (p.life / p.maxLife + 0.3f), particlePaint)
        }
        canvas.restore()

        // Fox
        if (phase != GamePhase.RESULTS) drawFox(canvas, sky)

        // Near foreground leaves
        drawForeground(canvas, sky)

        // HUD
        drawHUD(canvas, sky)

        // Overlays
        when (phase) {
            GamePhase.TITLE   -> drawTitle(canvas)
            GamePhase.CRASHED -> drawCrashOverlay(canvas)
            GamePhase.RESULTS -> drawResults(canvas)
            else -> {}
        }
    }

    private fun getNightFactor(): Float {
        val n = SKY_STATES.size
        val pos = skyTime * n
        val i = pos.toInt() % n
        return if (i == SKY_STATES.size - 1) 1f else if (i == 0) 0.5f else 0f
    }

    // Far silhouette mountains / distant canopy
    private fun drawFarCanopy(canvas: Canvas, sky: SkyColors) {
        canopyPaint.color = blendColors(sky.foliage, sky.top, 0.6f)
        canopyPaint.alpha = 120
        val offsetX = -(camX * 0.08f) % (sw * 2f)
        drawCanopyLayer(canvas, offsetX, sh * 0.30f, sh * 0.22f, 280f, canopyPaint)
        drawCanopyLayer(canvas, offsetX + sw, sh * 0.30f, sh * 0.22f, 280f, canopyPaint)
    }

    private fun drawMidCanopy(canvas: Canvas, sky: SkyColors) {
        canopyPaint.color = blendColors(sky.foliage, Color.BLACK, 0.25f)
        canopyPaint.alpha = 200
        val offsetX = -(camX * 0.22f) % (sw * 1.5f)
        drawCanopyLayer(canvas, offsetX, sh * 0.42f, sh * 0.28f, 180f, canopyPaint)
        drawCanopyLayer(canvas, offsetX + sw * 1.5f, sh * 0.42f, sh * 0.28f, 180f, canopyPaint)
    }

    private val canopyPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    private fun drawCanopyLayer(canvas: Canvas, offsetX: Float, baseY: Float, treeH: Float, spacing: Float, paint: Paint) {
        canopyPath.reset()
        canopyPath.moveTo(offsetX - spacing, sh)
        var x = offsetX
        while (x < sw + spacing * 2) {
            val h = treeH * (0.7f + 0.3f * sin((x / spacing * 1.3f).toDouble()).toFloat())
            canopyPath.lineTo(x, baseY)
            canopyPath.quadTo(x + spacing*0.25f, baseY - h, x + spacing*0.5f, baseY - h*0.4f)
            canopyPath.quadTo(x + spacing*0.75f, baseY - h*0.9f, x + spacing, baseY)
            x += spacing
        }
        canopyPath.lineTo(x, sh); canopyPath.lineTo(offsetX - spacing, sh); canopyPath.close()
        canvas.drawPath(canopyPath, paint)
    }

    private fun drawTerrain(canvas: Canvas, sky: SkyColors) {
        if (chunks.isEmpty()) return
        val foliageCol = blendColors(sky.foliage, Color.BLACK, 0.15f)
        val groundCol  = sky.ground

        // Build terrain path from visible chunks
        terrainPath.reset()
        terrainPath.moveTo(0f, sh)

        for (chunk in chunks) {
            val sx = chunk.startX - camX; val ex = chunk.endX - camX
            if (ex < -50f || sx > sw + 50f) continue
            terrainPath.lineTo(sx, chunk.startY)
            terrainPath.cubicTo(
                chunk.cp1X - camX, chunk.cp1Y,
                chunk.cp2X - camX, chunk.cp2Y,
                ex, chunk.endY)
        }
        terrainPath.lineTo(sw, sh); terrainPath.lineTo(0f, sh); terrainPath.close()

        // Fill terrain
        terrainPaint.color = foliageCol
        canvas.drawPath(terrainPath, terrainPaint)
        // Edge highlight (lighter top line)
        terrainEdge.color = blendColors(foliageCol, Color.WHITE, 0.18f)
        canvas.drawPath(terrainPath, terrainEdge)

        // Walls
        for (chunk in chunks) {
            if (!chunk.hasWall) continue
            val wx = chunk.wallX - camX
            if (wx < -20f || wx > sw + 20f) continue
            val topY   = chunk.yAtX(chunk.wallX) - chunk.wallHeight
            val botY   = chunk.yAtX(chunk.wallX)
            val ww     = 18f
            wallPaint.color = blendColors(foliageCol, Color.BLACK, 0.35f)
            canvas.drawRect(wx - ww/2f, topY, wx + ww/2f, botY, wallPaint)
            wallEdge.color = blendColors(foliageCol, Color.WHITE, 0.12f)
            canvas.drawRect(wx - ww/2f, topY, wx + ww/2f, botY, wallEdge)
            // Moss / detail lines
            mossPaint.color = wallEdge.color; mossPaint.alpha = 80
            for (y in 0..3) canvas.drawLine(wx-ww/2f, topY+y*chunk.wallHeight/5f, wx+ww/2f, topY+y*chunk.wallHeight/5f, mossPaint)
        }

        // Grind spark line while wall-riding
        if (isOnWall && phase == GamePhase.RUNNING) {
            val wx = wallChunk!!.wallX - camX
            val topY = wallChunk!!.yAtX(wallChunk!!.wallX) - wallChunk!!.wallHeight
            canvas.drawLine(wx, topY, wx, foxY - camX * 0f + 0f, grindLine)
        }
    }

    private fun drawCoins(canvas: Canvas) {
        for (coin in coins) {
            if (coin.collected && coin.anim <= 0f) continue
            val sx = coin.x - camX; val sy = coin.y
            if (sx < -20f || sx > sw + 20f) continue
            val pulse = 1f + 0.12f * sin((runTime * 4f + coin.x * 0.01f).toDouble()).toFloat()
            val alpha = if (coin.collected) (coin.anim * 255).toInt() else 255
            val scale = if (coin.collected) (1f + (1f - coin.anim) * 0.5f) else pulse
            val r = 11f * scale
            coinGlow.color = Color.argb((alpha * 0.4f).toInt(), 255, 230, 80)
            canvas.drawCircle(sx, sy, r * 1.8f, coinGlow)
            coinPaint.color = Color.argb(alpha, 255, 210, 50)
            canvas.drawCircle(sx, sy, r, coinPaint)
            coinPaint.color = Color.argb((alpha * 0.6f).toInt(), 255, 245, 160)
            canvas.drawCircle(sx - r*0.2f, sy - r*0.2f, r * 0.5f, coinPaint)
        }
    }

    private fun drawFox(canvas: Canvas, sky: SkyColors) {
        val sx = (foxX - camX)
        val sy = foxY

        canvas.save()
        canvas.translate(sx, sy)
        canvas.rotate(Math.toDegrees(foxAngle.toDouble()).toFloat())

        // Tail (drawn behind body, with lag rotation)
        canvas.save()
        canvas.rotate(Math.toDegrees((tailAngle - foxAngle).toDouble()).toFloat())
        drawFoxTail(canvas)
        canvas.restore()

        // Body
        drawFoxBody(canvas)

        canvas.restore()
    }

    private fun drawFoxBody(canvas: Canvas) {
        val s = FOX_R / 22f
        foxBodyPath.reset()

        // Body — teardrop-ish oval wider at rump
        foxBodyPath.addOval(RectF(-14f*s, -9f*s, 16f*s, 9f*s), Path.Direction.CW)

        // Neck + Head
        foxBodyPath.moveTo(12f*s, -5f*s)
        foxBodyPath.cubicTo(16f*s, -14f*s, 22f*s, -16f*s, 26f*s, -12f*s)
        foxBodyPath.cubicTo(30f*s, -8f*s, 30f*s, -2f*s, 26f*s, 2f*s)
        foxBodyPath.cubicTo(22f*s, 6f*s, 14f*s, 4f*s, 12f*s, -5f*s)
        foxBodyPath.close()

        // Snout extension
        foxBodyPath.moveTo(24f*s, -6f*s)
        foxBodyPath.cubicTo(30f*s, -5f*s, 36f*s, -3f*s, 38f*s, -1f*s)
        foxBodyPath.cubicTo(37f*s, 2f*s, 34f*s, 3f*s, 28f*s, 2f*s)
        foxBodyPath.cubicTo(26f*s, 1f*s, 24f*s, -2f*s, 24f*s, -6f*s)
        foxBodyPath.close()

        // Left ear (back)
        foxBodyPath.moveTo(18f*s, -12f*s)
        foxBodyPath.lineTo(16f*s, -24f*s)
        foxBodyPath.lineTo(22f*s, -18f*s)
        foxBodyPath.close()

        // Right ear (front)
        foxBodyPath.moveTo(22f*s, -13f*s)
        foxBodyPath.lineTo(23f*s, -26f*s)
        foxBodyPath.lineTo(28f*s, -16f*s)
        foxBodyPath.close()

        // Front leg
        foxBodyPath.moveTo(8f*s, 7f*s)
        foxBodyPath.lineTo(6f*s, 19f*s)
        foxBodyPath.lineTo(10f*s, 19f*s)
        foxBodyPath.lineTo(12f*s, 7f*s)
        foxBodyPath.close()

        // Back leg
        foxBodyPath.moveTo(-8f*s, 7f*s)
        foxBodyPath.lineTo(-10f*s, 19f*s)
        foxBodyPath.lineTo(-6f*s, 19f*s)
        foxBodyPath.lineTo(-4f*s, 7f*s)
        foxBodyPath.close()

        foxPaint.color = Color.rgb(15, 10, 8)
        canvas.drawPath(foxBodyPath, foxPaint)

        // Nose tip (slightly lighter)
        foxPaint.color = Color.rgb(30, 20, 15)
        canvas.drawCircle(38f*s, 0f, 3f*s, foxPaint)
    }

    private fun drawFoxTail(canvas: Canvas) {
        val s = FOX_R / 22f
        foxTailPath.reset()

        // Big bushy tail sweeping behind
        foxTailPath.moveTo(-10f*s, 0f)
        foxTailPath.cubicTo(-20f*s, -8f*s, -42f*s, -14f*s, -52f*s, -4f*s)
        foxTailPath.cubicTo(-60f*s, 4f*s, -55f*s, 18f*s, -44f*s, 20f*s)
        foxTailPath.cubicTo(-32f*s, 22f*s, -16f*s, 14f*s, -10f*s, 8f*s)
        foxTailPath.close()

        foxPaint.color = Color.rgb(15, 10, 8)
        canvas.drawPath(foxTailPath, foxPaint)

        // White tail tip
        foxTailPath.reset()
        foxTailPath.moveTo(-44f*s, 8f*s)
        foxTailPath.cubicTo(-54f*s, 4f*s, -58f*s, -2f*s, -52f*s, -4f*s)
        foxTailPath.cubicTo(-46f*s, -6f*s, -38f*s, 2f*s, -36f*s, 12f*s)
        foxTailPath.cubicTo(-40f*s, 16f*s, -46f*s, 14f*s, -44f*s, 8f*s)
        foxTailPath.close()

        foxTailTip.color = Color.rgb(200, 195, 185)
        canvas.drawPath(foxTailPath, foxTailTip)
    }

    // Near foreground — fast-moving leaves/vines for depth
    private fun drawForeground(canvas: Canvas, sky: SkyColors) {
        foliagePaint.color = blendColors(sky.foliage, Color.BLACK, 0.05f)
        foliagePaint.alpha = 230
        val offsetX = -(camX * 1.4f)
        val leafSpacing = 260f
        var lx = (offsetX % leafSpacing + leafSpacing) % leafSpacing - leafSpacing
        while (lx < sw + leafSpacing) {
            // Drooping vine cluster
            val vinePath = Path()
            vinePath.moveTo(lx, 0f)
            vinePath.cubicTo(lx + 20f, sh * 0.12f, lx - 15f, sh * 0.22f, lx + 5f, sh * 0.28f)
            vinePaint.color = foliagePaint.color; vinePaint.alpha = 180
            canvas.drawPath(vinePath, vinePaint)
            // Leaf blobs
            canvas.drawOval(lx - 20f, sh * 0.05f, lx + 25f, sh * 0.18f, foliagePaint)
            canvas.drawOval(lx + 15f, sh * 0.00f, lx + 55f, sh * 0.12f, foliagePaint)
            lx += leafSpacing
        }
    }

    private fun drawHUD(canvas: Canvas, sky: SkyColors) {
        // Score
        hudPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("$score", 28f, 58f, hudPaint)
        hudSmall.textAlign = Paint.Align.LEFT
        canvas.drawText("BEST $bestScore", 28f, 92f, hudSmall)

        // Coins
        val coinX = sw - 28f; val coinY = 48f
        coinPaint.color = Color.rgb(255, 210, 50); coinPaint.alpha = 255
        canvas.drawCircle(coinX, coinY - 10f, 14f, coinPaint)
        hudSmall.textAlign = Paint.Align.RIGHT
        canvas.drawText("× $coinScore", coinX - 22f, coinY - 2f, hudSmall)

        // Combo
        if (comboCount >= 2 && comboTimer > 0f) {
            val fade = (comboTimer / 3f).coerceAtMost(1f)
            comboPaint.color = Color.argb((fade * 255).toInt(), 255, 220, 80)
            val pulse = 1f + 0.08f * sin((runTime * 12f).toDouble()).toFloat()
            canvas.save()
            canvas.scale(pulse, pulse, sw / 2f, sh * 0.15f)
            canvas.drawText("✕$comboCount COMBO", sw / 2f, sh * 0.15f, comboPaint)
            canvas.restore()
        }

        // Speed bar
        val barW = 100f; val barH = 8f; val barX = sw/2f - barW/2f; val barY = sh - 30f
        val speedFrac = (foxVx / MAX_VX).coerceIn(0f, 1f)
        canvas.drawRoundRect(barX, barY, barX+barW, barY+barH, 4f, 4f, speedBarBgPaint)
        canvas.drawRoundRect(barX, barY, barX+barW*speedFrac, barY+barH, 4f, 4f, speedBarFillPaint)
    }

    private fun drawTitle(canvas: Canvas) {
        overlayPaint.color = Color.argb((titleAlpha * 100).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, sw, sh, overlayPaint)
        titleTextPaint.textSize = 80f
        titleTextPaint.color = Color.argb((titleAlpha * 255).toInt(), 255, 240, 200)
        titleTextPaint.setShadowLayer(10f, 0f, 3f, Color.argb((titleAlpha * 180).toInt(), 0, 0, 0))
        canvas.drawText("FOX RUN", sw / 2f, sh * 0.40f, titleTextPaint)
        val pulse = (0.5f + 0.5f * sin((runTime*3f).toDouble()).toFloat())
        titleTextPaint.textSize = 36f
        titleTextPaint.color = Color.argb((titleAlpha * 200 * pulse).toInt().coerceIn(0,255), 255, 255, 255)
        canvas.drawText("TAP TO START", sw / 2f, sh * 0.54f, titleTextPaint)
        titleTextPaint.textSize = 26f
        titleTextPaint.color = Color.argb((titleAlpha * 150).toInt(), 200, 220, 180)
        canvas.drawText("HOLD TO BACKFLIP  •  GRIND WALLS", sw / 2f, sh * 0.62f, titleTextPaint)
    }

    private fun drawCrashOverlay(canvas: Canvas) {
        overlayPaint.color = Color.argb((crashAnim * 160).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, sw, sh, overlayPaint)
    }

    private fun drawResults(canvas: Canvas) {
        overlayPaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRect(0f, 0f, sw, sh, overlayPaint)
        resultTextPaint.textSize = 72f; resultTextPaint.color = Color.WHITE
        canvas.drawText("RUN OVER", sw/2f, sh*0.32f, resultTextPaint)
        resultTextPaint.textSize = 48f; resultTextPaint.color = Color.rgb(255,220,80)
        canvas.drawText("$score", sw/2f, sh*0.44f, resultTextPaint)
        resultTextPaint.textSize = 28f; resultTextPaint.color = Color.rgb(180,220,180)
        canvas.drawText("BEST: $bestScore", sw/2f, sh*0.52f, resultTextPaint)
        resultTextPaint.color = Color.rgb(255,210,50)
        canvas.drawText("COINS: $coinScore", sw/2f, sh*0.59f, resultTextPaint)
        resultTextPaint.textSize = 30f
        val blink = (180 + 75 * sin((runTime*3).toDouble())).toInt().coerceIn(0,255)
        resultTextPaint.color = Color.argb(blink, 255, 255, 255)
        canvas.drawText("TAP TO CONTINUE", sw/2f, sh*0.72f, resultTextPaint)
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private fun blendColors(c1: Int, c2: Int, t: Float) = lerpColor(c1, c2, t)

    // =========================================================================
    // Lifecycle
    // =========================================================================
    override fun onDetachedFromWindow() { stopLoop(); super.onDetachedFromWindow() }
    fun pause()  { isRunning = false }
    fun resume() { if (surfaceReady) startLoop() }
    fun reset()  { phase = GamePhase.TITLE; titleAlpha = 1f; chunks.clear() }
}