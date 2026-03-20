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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

// ═════════════════════════════════════════════════════════════════════════════
//  ARCHITECTURE
//
//  Layer 1 — Grid data (LevelData):
//    Pure data. Tiles, objects, walkability. No logic.
//
//  Layer 2 — Game logic (GameState):
//    Top-down 2D. Swipe → target tile. dyn4j drives player movement and
//    handles collision. Physics coords = grid coords (1 unit = 1 tile).
//    No gravity — this is a top-down view, not a platformer.
//
//  Layer 3 — Rendering (IsoRenderer):
//    Iso transform applied ONLY at draw time.
//    screenX = ox + (gridX - gridY) * HALF_W
//    screenY = oy + (gridX + gridY) * HALF_H
//    Painter depth = gridX + gridY.
//    Camera is a smooth float in grid space, baked into the origin — no
//    canvas.translate(), so HUD and world are always in sync (no flicker).
// ═════════════════════════════════════════════════════════════════════════════

// ── Iso projection constants & helpers ────────────────────────────────────────
private const val HALF_W = 64f   // half tile width  (full = 128px, 2:1 ratio)
private const val HALF_H = 32f   // half tile height (full = 64px)
private const val SIDE_H = 20f   // side face drop in pixels

private fun isoX(ox: Float, gx: Float, gy: Float): Float = ox + (gx - gy) * HALF_W
private fun isoY(oy: Float, gx: Float, gy: Float): Float = oy + (gx + gy) * HALF_H

// ═════════════════════════════════════════════════════════════════════════════
//  DATA TYPES
// ═════════════════════════════════════════════════════════════════════════════

enum class TileType   { GRASS, SAND, STONE, WATER }
enum class ObjectType { BLOCKER, GOAL, NONE }

data class Tile(
    val type:       TileType   = TileType.GRASS,
    val obj:        ObjectType = ObjectType.NONE,
    val isWalkable: Boolean    = true,
    val isGoal:     Boolean    = false
)

// ═════════════════════════════════════════════════════════════════════════════
//  LEVEL DATA  —  a named object so its members are accessible everywhere
//
//  To extend: add more tile helpers, change the grid, or swap THE_LEVEL_GRID
//  for a different array to load a different level.
// ═════════════════════════════════════════════════════════════════════════════

// Tile shorthand builders — private to this file
private fun G() = Tile(TileType.GRASS)
private fun S() = Tile(TileType.SAND)
private fun P() = Tile(TileType.STONE)
private fun W() = Tile(TileType.WATER,  ObjectType.NONE,    isWalkable = false)
private fun B() = Tile(TileType.GRASS,  ObjectType.BLOCKER, isWalkable = false)
private fun X() = Tile(TileType.STONE,  ObjectType.GOAL,    isGoal     = true)

// Named object — members (grid, cols, rows, startX, startY, hint, tile(), isWalkable())
// are fully accessible from all classes in this file.
object LevelData {
    val grid: Array<Array<Tile>> = arrayOf(
        //        col 0   1     2     3     4     5     6     7
        arrayOf( W(),  W(),  W(),  W(),  W(),  W(),  W(),  W() ),  // row 0
        arrayOf( W(),  G(),  G(),  G(),  B(),  G(),  G(),  W() ),  // row 1
        arrayOf( W(),  G(),  B(),  G(),  G(),  G(),  B(),  W() ),  // row 2
        arrayOf( W(),  G(),  G(),  P(),  P(),  G(),  G(),  W() ),  // row 3
        arrayOf( W(),  B(),  G(),  P(),  P(),  G(),  G(),  W() ),  // row 4
        arrayOf( W(),  G(),  G(),  G(),  G(),  G(),  S(),  W() ),  // row 5
        arrayOf( W(),  G(),  S(),  S(),  S(),  S(),  S(),  W() ),  // row 6
        arrayOf( W(),  G(),  G(),  G(),  G(),  G(),  X(),  W() ),  // row 7
        arrayOf( W(),  W(),  W(),  W(),  W(),  W(),  W(),  W() ),  // row 8
    )
    val cols:   Int    = grid[0].size           // 8
    val rows:   Int    = grid.size              // 9
    val startX: Int    = 1
    val startY: Int    = 1
    val hint:   String = "Swipe to move"

    fun tile(x: Int, y: Int): Tile? = grid.getOrNull(y)?.getOrNull(x)
    fun isWalkable(x: Int, y: Int): Boolean = tile(x, y)?.isWalkable == true

    // Goal position — cached at init time, not searched every frame
    val goalX: Int
    val goalY: Int
    init {
        var gx = startX; var gy = startY
        outer@ for (r in grid.indices) {
            for (c in grid[r].indices) {
                if (grid[r][c].isGoal) { gx = c; gy = r; break@outer }
            }
        }
        goalX = gx; goalY = gy
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  THEME  —  all colours in one place, easy to swap
// ═════════════════════════════════════════════════════════════════════════════

private object Theme {
    // Sky
    val skyTop   = Color.rgb(100, 155, 210)
    val skyBot   = Color.rgb(165, 200, 235)
    // Tiles
    val grassTop = Color.rgb(95,  145,  68);  val grassL = Color.rgb(68, 108, 46);  val grassR = Color.rgb(55,  92, 36)
    val sandTop  = Color.rgb(200, 175, 120);  val sandL  = Color.rgb(162,138, 85);  val sandR  = Color.rgb(142,120, 68)
    val stoneTop = Color.rgb(142, 136, 126);  val stoneL = Color.rgb(102, 98, 90);  val stoneR = Color.rgb( 86, 84, 78)
    val water    = Color.rgb( 55, 120, 178)
    // Objects
    val blocker  = Color.rgb(185,  80,  65);  val blockerL = Color.rgb(140, 55, 42);  val blockerR = Color.rgb(118, 45, 35)
    val goal     = Color.rgb(255, 210,  50);  val goalGlow = Color.argb(60, 255, 210, 50)
    // Player
    val player   = Color.rgb( 50, 125, 205);  val playerL  = Color.rgb( 35,  90,150)
    // Misc
    val shadow   = Color.argb(45,  0,   0,   0)
    val edge     = Color.argb(48,  0,   0,   0)
}

// ═════════════════════════════════════════════════════════════════════════════
//  PHYSICS WORLD  —  dyn4j, top-down, no gravity
//
//  Physics coordinates = grid coordinates (1 unit = 1 tile cell).
//  Every impassable tile gets a static rectangle body.
//  Player is a small dynamic circle — velocity-driven, high damping.
//  Goal is a sensor circle — contact triggers goalReached flag.
// ═════════════════════════════════════════════════════════════════════════════

class PhysicsWorld {

    val  world        = World<Body>()
    private val goalReached = AtomicBoolean(false)
    lateinit var playerBody: Body
        private set

    init {
        world.setGravity(Vector2(0.0, 0.0))   // top-down — no gravity
    }

    /** (Re)build all bodies from LevelData. Safe to call on reset. */
    fun build() {
        // Remove all existing bodies
        val existing = world.bodies.toList()
        for (b in existing) world.removeBody(b)
        goalReached.set(false)

        // Static bodies for every impassable tile
        for (r in LevelData.grid.indices) {
            for (c in LevelData.grid[r].indices) {
                if (!LevelData.grid[r][c].isWalkable) {
                    val b = Body()
                    b.addFixture(Geometry.createRectangle(1.02, 1.02))  // 1.02 closes seams
                    b.setMass(MassType.INFINITE)
                    b.setAtRestDetectionEnabled(false)
                    b.translate(c.toDouble(), r.toDouble())
                    world.addBody(b)
                }
            }
        }

        // Player body
        val p = Body()
        p.addFixture(Geometry.createCircle(0.35), 1.0, 0.0, 0.0)
        p.setMass(MassType.NORMAL)
        p.setLinearDamping(12.0)        // stops quickly, no sliding
        p.setAngularDamping(1000.0)
        p.setAtRestDetectionEnabled(false)
        p.translate(LevelData.startX.toDouble(), LevelData.startY.toDouble())
        world.addBody(p)
        playerBody = p

        // Goal sensor body
        val g = Body()
        val gf = g.addFixture(Geometry.createCircle(0.45))
        gf.isSensor = true
        g.setMass(MassType.INFINITE)
        g.setAtRestDetectionEnabled(false)
        g.translate(LevelData.goalX.toDouble(), LevelData.goalY.toDouble())
        world.addBody(g)

        // Contact listener — detect player reaching goal
        val pp = playerBody
        world.addContactListener(object : ContactListenerAdapter<Body>() {
            override fun begin(
                col: org.dyn4j.world.ContactCollisionData<Body>,
                contact: org.dyn4j.dynamics.contact.Contact
            ) {
                if ((col.body1 === pp && col.body2 === g) ||
                    (col.body2 === pp && col.body1 === g))
                    goalReached.set(true)
            }
        })
    }

    /** Read and clear the goal flag — call once per frame from game loop. */
    fun pollGoalReached(): Boolean = goalReached.getAndSet(false)

    /** Player grid position (smooth float, read from physics body). */
    val playerGridX: Float get() = playerBody.transform.translationX.toFloat()
    val playerGridY: Float get() = playerBody.transform.translationY.toFloat()

    fun step(dt: Double) { world.update(dt) }
}

// ═════════════════════════════════════════════════════════════════════════════
//  GAME STATE  —  grid logic + physics
//
//  Swipe sets a target tile one step in the swiped direction.
//  Each frame, the player body is driven toward the target by velocity.
//  dyn4j resolves collisions — player can't enter impassable tiles.
// ═════════════════════════════════════════════════════════════════════════════

class GameState {

    val  physics  = PhysicsWorld()
    var  won      = false;  private set
    var  winTimer = 0f;     private set

    val  playerX: Float get() = physics.playerGridX
    val  playerY: Float get() = physics.playerGridY

    // Exposed so renderer can use target-based depth (fixes tile-over-player bug)
    var targetX = LevelData.startX; private set
    var targetY = LevelData.startY; private set
    private val MOVE_SPEED = 8.0   // grid cells per second

    init { physics.build() }

    /**
     * Request one step in direction (dx, dy).
     * Only moves if the target tile is walkable.
     * Called from the swipe handler on the UI thread — writes targetX/Y which
     * are read on the game loop thread. Safe because they are simple Ints and
     * the worst case is one frame of stale read, which is imperceptible.
     */
    fun move(dx: Int, dy: Int) {
        if (won) return
        val nx = targetX + dx
        val ny = targetY + dy
        if (LevelData.isWalkable(nx, ny)) { targetX = nx; targetY = ny }
    }

    /** Called once per frame on the game loop thread. */
    fun update(dt: Float) {
        if (won) { winTimer += dt; return }

        val p   = physics.playerBody
        val px  = p.transform.translationX
        val py  = p.transform.translationY
        val tx  = targetX.toDouble()
        val ty  = targetY.toDouble()
        val ddx = tx - px
        val ddy = ty - py
        val dist = sqrt(ddx * ddx + ddy * ddy)

        if (dist > 0.04) {
            val speed = MOVE_SPEED * dist.coerceAtMost(1.0)
            p.linearVelocity = Vector2(ddx / dist * speed, ddy / dist * speed)
        } else {
            // Arrived — snap precisely and stop
            p.linearVelocity = Vector2(0.0, 0.0)
            if (dist > 0.0) p.translate(ddx, ddy)
        }

        physics.step(dt.toDouble())

        if (physics.pollGoalReached()) won = true
    }

    fun reset() {
        won = false; winTimer = 0f
        targetX = LevelData.startX; targetY = LevelData.startY
        physics.build()
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  SWIPE DETECTOR
//  Any swipe anywhere on screen → one of four grid directions.
//  Consumes the swipe on first threshold crossing so fast swipes don't repeat.
// ═════════════════════════════════════════════════════════════════════════════

class SwipeDetector(private val minDist: Float = 25f) {
    private var downX = 0f; private var downY = 0f; private var active = false

    enum class Dir { EAST, WEST, SOUTH, NORTH }

    fun onDown(x: Float, y: Float) { downX = x; downY = y; active = true }

    fun onMove(x: Float, y: Float): Dir? {
        if (!active) return null
        val dx = x - downX; val dy = y - downY
        if (dx * dx + dy * dy < minDist * minDist) return null
        active = false  // consume — one direction per gesture
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) Dir.EAST else Dir.WEST
        } else {
            if (dy > 0) Dir.SOUTH else Dir.NORTH
        }
    }

    fun onUp() { active = false }
}

// ═════════════════════════════════════════════════════════════════════════════
//  DRAW CALL QUEUE  —  zero per-frame allocation
//  Fixed-size pool of entries. Insertion sort (fast for small N ≤ ~100).
//  NOOP singleton prevents lambda allocation on flush clear.
// ═════════════════════════════════════════════════════════════════════════════

fun interface DrawAction { fun draw() }

class DrawCallQueue(capacity: Int = 256) {
    private class Entry { var key: Float = 0f; var fn: DrawAction = NOOP }
    private val pool = Array(capacity) { Entry() }
    private var size = 0

    fun add(key: Float, fn: DrawAction) {
        if (size >= pool.size) return
        pool[size].key = key; pool[size].fn = fn; size++
    }

    fun flush() {
        for (i in 1 until size) {
            val k = pool[i].key; val f = pool[i].fn; var j = i - 1
            while (j >= 0 && pool[j].key > k) {
                pool[j+1].key = pool[j].key; pool[j+1].fn = pool[j].fn; j--
            }
            pool[j+1].key = k; pool[j+1].fn = f
        }
        for (i in 0 until size) { pool[i].fn.draw(); pool[i].fn = NOOP }
        size = 0
    }

    companion object { private val NOOP = DrawAction {} }
}

// ═════════════════════════════════════════════════════════════════════════════
//  ISO RENDERER
//
//  All Paint and Path objects are pre-allocated as class fields — zero
//  heap allocation in the hot draw path.
//
//  Pre-allocated TileSlot / ObjSlot / PlayerSlot inner classes hold draw
//  parameters. Their `fn` field is a pre-bound DrawAction — no lambda
//  allocation per frame.
//
//  renderWorld() and renderHUD() both draw on the raw canvas.
//  Camera is baked into ox/oy — no canvas.save/translate/restore needed,
//  so HUD is never accidentally shifted by world transform (no flicker).
// ═════════════════════════════════════════════════════════════════════════════

class IsoRenderer {

    // ── Pre-allocated paints ──────────────────────────────────────────────────
    private val bgP      = Paint().apply { style = Paint.Style.FILL }
    private val topP     = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val leftP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val rightP   = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val edgeP    = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true; color = Theme.edge }
    private val waterP   = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val objTP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val objLP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val objRP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val shadowP  = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true; color = Theme.shadow }
    private val glowP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true; color = Theme.goalGlow }
    private val playerTP = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val playerLP = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true; color = Theme.playerL }
    private val playerRP = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true; color = Theme.playerL }
    private val playerEP = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f;  isAntiAlias = true; color = Theme.playerL }
    private val hudP     = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val hudSP    = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val overlayP = Paint().apply { style = Paint.Style.FILL }
    private val bFillP   = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val bEdgeP   = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
    private val bTextP   = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val rippleP  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f;  isAntiAlias = true }

    // ── Pre-allocated paths ───────────────────────────────────────────────────
    private val topPath = Path()
    private val sideL   = Path()
    private val sideR   = Path()

    // ── Draw call queue ───────────────────────────────────────────────────────
    private val queue = DrawCallQueue(256)

    // ── Gradient caches ───────────────────────────────────────────────────────
    private var cachedSky: LinearGradient? = null
    private var cSkySh = 0f
    private var cachedPlayerGrad: RadialGradient? = null
    private var cPx = Float.MAX_VALUE
    private var cPy = Float.MAX_VALUE

    // ── Pre-allocated draw parameter structs ──────────────────────────────────
    // Writing into these before enqueue = zero lambda capture allocation.

    private inner class TileSlot {
        var canvas: Canvas? = null
        var cx = 0f; var cy = 0f
        var cTop = 0; var cL = 0; var cR = 0; var water = false
        val fn = DrawAction { drawTile(canvas!!, cx, cy, cTop, cL, cR, water) }
    }

    private inner class ObjSlot {
        var canvas: Canvas? = null
        var cx = 0f; var cy = 0f
        var obj = ObjectType.NONE; var t = 0f
        val fn = DrawAction { drawObj(canvas!!, cx, cy, obj, t) }
    }

    private inner class PlayerSlot {
        var canvas: Canvas? = null
        var cx = 0f; var cy = 0f
        val fn = DrawAction { drawPlayer(canvas!!, cx, cy) }
    }

    private val tilePool   = Array(256) { TileSlot() }
    private val objPool    = Array(64)  { ObjSlot() }
    private val playerSlot = PlayerSlot()
    private var ti = 0; private var oi = 0

    // ── renderWorld ───────────────────────────────────────────────────────────

    fun renderWorld(
        canvas: Canvas, state: GameState,
        targetX: Int, targetY: Int,
        ox: Float, oy: Float,
        sw: Float, sh: Float, t: Float
    ) {
        ti = 0; oi = 0

        // Sky gradient — rebuilt only when screen height changes
        if (sh != cSkySh) {
            cachedSky = LinearGradient(0f, 0f, 0f, sh,
                intArrayOf(Theme.skyTop, Theme.skyBot), null, Shader.TileMode.CLAMP)
            cSkySh = sh
        }
        bgP.shader = cachedSky
        canvas.drawRect(0f, 0f, sw, sh, bgP)

        // Enqueue tiles and objects — painter order (depth = col + row)
        val grid = LevelData.grid
        for (row in grid.indices) {
            for (col in grid[row].indices) {
                val tile  = grid[row][col]
                val depth = (col + row).toFloat()
                val cx    = isoX(ox, col.toFloat(), row.toFloat())
                val cy    = isoY(oy, col.toFloat(), row.toFloat())

                if (ti < tilePool.size) {
                    val s = tilePool[ti++]
                    s.canvas = canvas; s.cx = cx; s.cy = cy
                    s.water = tile.type == TileType.WATER
                    when (tile.type) {
                        TileType.GRASS -> { s.cTop=Theme.grassTop; s.cL=Theme.grassL; s.cR=Theme.grassR }
                        TileType.SAND  -> { s.cTop=Theme.sandTop;  s.cL=Theme.sandL;  s.cR=Theme.sandR }
                        TileType.STONE -> { s.cTop=Theme.stoneTop; s.cL=Theme.stoneL; s.cR=Theme.stoneR }
                        TileType.WATER -> { s.cTop=Theme.water;    s.cL=Theme.water;  s.cR=Theme.water }
                    }
                    queue.add(depth, s.fn)
                }

                if (tile.obj != ObjectType.NONE && oi < objPool.size) {
                    val s = objPool[oi++]
                    s.canvas = canvas; s.cx = cx; s.cy = cy
                    s.obj = tile.obj; s.t = t
                    queue.add(depth + 0.5f, s.fn)
                }
            }
        }

        // Player — enqueued at smooth float depth
        val pcx = isoX(ox, state.playerX, state.playerY)
        val pcy = isoY(oy, state.playerX, state.playerY)
        playerSlot.canvas = canvas; playerSlot.cx = pcx; playerSlot.cy = pcy
        // Depth based on TARGET tile (not current float pos) — ensures player
        // always renders in front of the tile they're moving toward, not behind it.
        queue.add(targetX.toFloat() + targetY.toFloat() + 0.8f, playerSlot.fn)

        queue.flush()
    }

    // ── renderHUD ─────────────────────────────────────────────────────────────
    // Drawn directly on the raw canvas — no transform applied, never flickering.

    fun renderHUD(
        canvas: Canvas, sw: Float, sh: Float,
        won: Boolean, winTimer: Float, hint: String,
        btnPrev: RectF, btnNext: RectF,
        rippleX: Float, rippleY: Float, rippleT: Float
    ) {
        // Tap ripple
        if (rippleT in 0f..0.4f) {
            val prog = rippleT / 0.4f
            rippleP.color = Color.argb(((1f - prog) * 80).toInt(), 255, 255, 255)
            canvas.drawCircle(rippleX, rippleY, prog * 44f, rippleP)
        }

        // Hint text
        hudSP.textSize = 13f; hudSP.color = Color.argb(150, 255, 255, 255)
        hudSP.setShadowLayer(3f, 0f, 1f, Color.argb(70, 0, 0, 0))
        canvas.drawText(hint, sw / 2f, sh - 18f, hudSP)

        // Nav buttons
        fun btn(r: RectF, lbl: String) {
            bFillP.color = Color.argb(100, 0, 0, 0)
            canvas.drawRoundRect(r, 10f, 10f, bFillP)
            bEdgeP.color = Color.argb(80, 255, 255, 255)
            canvas.drawRoundRect(r, 10f, 10f, bEdgeP)
            bTextP.textSize = 13f; bTextP.color = Color.argb(200, 255, 255, 255)
            canvas.drawText(lbl, r.centerX(), r.centerY() + 5f, bTextP)
        }
        btn(btnPrev, "◀"); btn(btnNext, "▶")

        // Win overlay
        if (won) {
            val alpha = min(190, (winTimer * 280).toInt())
            overlayP.color = Color.argb(alpha, 0, 0, 0)
            canvas.drawRect(0f, 0f, sw, sh, overlayP)
            if (winTimer > 0.4f) {
                hudP.textSize = sw * 0.11f; hudP.color = Theme.goal
                hudP.setShadowLayer(10f, 0f, 4f, Color.argb(70, 0, 0, 0))
                canvas.drawText("✦", sw / 2f, sh / 2f - 16f, hudP)
                hudP.textSize = sw * 0.044f; hudP.color = Color.WHITE
                canvas.drawText("You made it!", sw / 2f, sh / 2f + 48f, hudP)
                if (winTimer > 1.0f) {
                    hudSP.textSize = sw * 0.028f; hudSP.color = Color.argb(175, 255, 255, 255)
                    canvas.drawText("Tap anywhere to restart", sw / 2f, sh / 2f + 94f, hudSP)
                }
            }
        }
    }

    // ── Tile face ─────────────────────────────────────────────────────────────
    // Diamond top + left parallelogram + right parallelogram.
    // cx/cy = centre of the diamond in screen space.

    private fun drawTile(
        canvas: Canvas, cx: Float, cy: Float,
        cTop: Int, cL: Int, cR: Int, water: Boolean
    ) {
        val tx = cx;           val ty = cy - HALF_H   // top vertex
        val rx = cx + HALF_W;  val ry = cy             // right vertex
        val bx = cx;           val by = cy + HALF_H   // bottom vertex
        val lx = cx - HALF_W;  val ly = cy             // left vertex

        topPath.reset()
        topPath.moveTo(tx, ty); topPath.lineTo(rx, ry)
        topPath.lineTo(bx, by); topPath.lineTo(lx, ly); topPath.close()

        if (water) {
            waterP.color = cTop; waterP.alpha = 210
            canvas.drawPath(topPath, waterP)
            canvas.drawPath(topPath, edgeP)
            return
        }

        topP.color = cTop
        canvas.drawPath(topPath, topP); canvas.drawPath(topPath, edgeP)

        sideL.reset()
        sideL.moveTo(lx, ly); sideL.lineTo(bx, by)
        sideL.lineTo(bx, by + SIDE_H); sideL.lineTo(lx, ly + SIDE_H); sideL.close()
        leftP.color = cL; canvas.drawPath(sideL, leftP); canvas.drawPath(sideL, edgeP)

        sideR.reset()
        sideR.moveTo(bx, by); sideR.lineTo(rx, ry)
        sideR.lineTo(rx, ry + SIDE_H); sideR.lineTo(bx, by + SIDE_H); sideR.close()
        rightP.color = cR; canvas.drawPath(sideR, rightP); canvas.drawPath(sideR, edgeP)
    }

    // ── Object drawing ────────────────────────────────────────────────────────
    // base = top of tile diamond = cy - HALF_H. Objects grow upward from base.
    // To add sprites: replace drawBlocker / drawGoal internals — interface unchanged.

    private fun drawObj(canvas: Canvas, cx: Float, cy: Float, obj: ObjectType, t: Float) {
        val base = cy - HALF_H
        when (obj) {
            ObjectType.BLOCKER -> drawBlocker(canvas, cx, base)
            ObjectType.GOAL    -> drawGoal(canvas, cx, base, t)
            ObjectType.NONE    -> Unit
        }
    }

    // Placeholder iso box — swap internals for a bitmap/sprite draw later
    private fun drawBlocker(canvas: Canvas, cx: Float, base: Float) {
        val h = 32f
        // Top face
        objTP.color = Theme.blocker
        topPath.reset()
        topPath.moveTo(cx,          base - h - HALF_H)
        topPath.lineTo(cx + HALF_W, base - h)
        topPath.lineTo(cx,          base - h + HALF_H)
        topPath.lineTo(cx - HALF_W, base - h); topPath.close()
        canvas.drawPath(topPath, objTP); canvas.drawPath(topPath, edgeP)
        // Left face
        sideL.reset()
        sideL.moveTo(cx - HALF_W, base - h)
        sideL.lineTo(cx,          base - h + HALF_H)
        sideL.lineTo(cx,          base + HALF_H)
        sideL.lineTo(cx - HALF_W, base); sideL.close()
        objLP.color = Theme.blockerL; canvas.drawPath(sideL, objLP); canvas.drawPath(sideL, edgeP)
        // Right face
        sideR.reset()
        sideR.moveTo(cx,          base - h + HALF_H)
        sideR.lineTo(cx + HALF_W, base - h)
        sideR.lineTo(cx + HALF_W, base)
        sideR.lineTo(cx,          base + HALF_H); sideR.close()
        objRP.color = Theme.blockerR; canvas.drawPath(sideR, objRP); canvas.drawPath(sideR, edgeP)
    }

    // Placeholder glowing diamond — swap internals for a sprite draw later
    private fun drawGoal(canvas: Canvas, cx: Float, base: Float, t: Float) {
        val pulse = 1f + 0.15f * sin(t * 3f)
        canvas.drawCircle(cx, base - 20f, 30f * pulse, glowP)
        val h = 28f * pulse
        topPath.reset()
        topPath.moveTo(cx,       base - h - 16f)
        topPath.lineTo(cx + 14f, base - h * 0.5f)
        topPath.lineTo(cx,       base - 4f)
        topPath.lineTo(cx - 14f, base - h * 0.5f); topPath.close()
        objTP.color = Theme.goal; canvas.drawPath(topPath, objTP)
    }

    // ── Player ────────────────────────────────────────────────────────────────
    // Iso box with gradient top — placeholder for character sprite.
    // cx/cy = iso screen position of the player's grid coords (smooth float).

    private fun drawPlayer(canvas: Canvas, cx: Float, cy: Float) {
        val base   = cy - HALF_H
        val pw     = HALF_W * 0.55f
        val ph     = HALF_H * 0.55f
        val height = 28f

        canvas.drawOval(cx - pw * 1.1f, base + ph * 0.4f,
                        cx + pw * 1.1f, base + ph * 0.9f, shadowP)

        if (abs(cx - cPx) > 3f || abs(cy - cPy) > 3f) {
            cachedPlayerGrad = RadialGradient(
                cx - pw * 0.25f, base - height * 0.7f, pw * 1.4f,
                intArrayOf(Color.rgb(90, 170, 255), Theme.player, Theme.playerL),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            cPx = cx; cPy = cy
        }

        // Top face
        playerTP.shader = cachedPlayerGrad
        topPath.reset()
        topPath.moveTo(cx,      base - height - ph)
        topPath.lineTo(cx + pw, base - height)
        topPath.lineTo(cx,      base - height + ph)
        topPath.lineTo(cx - pw, base - height); topPath.close()
        canvas.drawPath(topPath, playerTP); canvas.drawPath(topPath, playerEP)

        // Left face
        sideL.reset()
        sideL.moveTo(cx - pw, base - height)
        sideL.lineTo(cx,      base - height + ph)
        sideL.lineTo(cx,      base + ph)
        sideL.lineTo(cx - pw, base); sideL.close()
        canvas.drawPath(sideL, playerLP); canvas.drawPath(sideL, playerEP)

        // Right face
        sideR.reset()
        sideR.moveTo(cx,      base - height + ph)
        sideR.lineTo(cx + pw, base - height)
        sideR.lineTo(cx + pw, base)
        sideR.lineTo(cx,      base + ph); sideR.close()
        canvas.drawPath(sideR, playerRP); canvas.drawPath(sideR, playerEP)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  GAME INTERNALS VIEW  —  thin orchestrator
//
//  Owns: SurfaceView lifecycle, 60fps game loop, touch routing.
//  Does NOT own: game logic, physics, rendering, level data.
// ═════════════════════════════════════════════════════════════════════════════

class GameInternalsView(context: Context, appContext: AppContext) : ExpoView(context, appContext),
    SurfaceHolder.Callback {

    private val surfaceView = SurfaceView(context).also {
        it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        it.holder.addCallback(this)
        it.holder.setFormat(PixelFormat.RGBA_8888)
        it.setZOrderMediaOverlay(true)
    }

    @Volatile private var isRunning    = false
    @Volatile private var surfaceReady = false
    private var gameThread: Thread?    = null
    private val targetFrameMs          = 1_000L / 60
    private var lastFrameMs            = System.currentTimeMillis()
    private var surfaceW = 0f
    private var surfaceH = 0f

    private val renderer = IsoRenderer()
    private val state    = GameState()

    // Camera in grid space — smooth float, follows player
    private var camX = state.playerX
    private var camY = state.playerY

    // Base origin: screen position of grid (0,0) with camera at (0,0)
    // Camera baked in: ox = baseOx + (camY - camX)*HALF_W
    //                  oy = baseOy - (camX + camY)*HALF_H
    private var baseOx = 0f
    private var baseOy = 0f

    private fun ox(): Float = baseOx + (camY - camX) * HALF_W
    private fun oy(): Float = baseOy - (camX + camY) * HALF_H

    private var stateTime = 0f
    private var rippleX = 0f; private var rippleY = 0f; private var rippleT = -1f
    private val btnPrev = RectF()
    private val btnNext = RectF()

    init {
        setWillNotDraw(true)
        clipChildren = false
        addView(surfaceView)
        surfaceView.setOnTouchListener { _, ev -> handleTouch(ev); true }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        startLoop()
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
        surfaceW = w.toFloat(); surfaceH = ht.toFloat()
        val bm = 16f; val bw = 52f; val bh = 40f
        btnPrev.set(bm,            surfaceH - bh - bm, bm + bw,            surfaceH - bm)
        btnNext.set(surfaceW-bm-bw, surfaceH - bh - bm, surfaceW - bm, surfaceH - bm)
        // Centre the full grid on screen
        val midC = LevelData.cols / 2f
        val midR = LevelData.rows / 2f
        baseOx = surfaceW / 2f - isoX(0f, midC, midR)
        baseOy = surfaceH * 0.40f - isoY(0f, midC, midR)
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    private fun handleTouch(ev: MotionEvent) {
        val tx = ev.x; val ty = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rippleX = tx; rippleY = ty; rippleT = 0f

                if (btnPrev.contains(tx, ty) || btnNext.contains(tx, ty)) return

                if (state.won) {
                    state.reset(); camX = state.playerX; camY = state.playerY; return
                }

                // Direction = screen quadrant of tap relative to player's screen position.
                //
                // In isometric view, the four tile directions map to screen quadrants:
                //   tap bottom-right of player  →  E  (gridX + 1)
                //   tap bottom-left  of player  →  S  (gridY + 1)
                //   tap top-right    of player  →  N  (gridY - 1)
                //   tap top-left     of player  →  W  (gridX - 1)
                //
                // This matches user intuition: tapping visually below-left moves
                // the player toward the bottom-left of the iso view.

                val playerSx = isoX(ox(), state.playerX, state.playerY)
                val playerSy = isoY(oy(), state.playerX, state.playerY)
                val dx = tx - playerSx
                val dy = ty - playerSy

                if (abs(dx) < 8f && abs(dy) < 8f) return  // dead zone — ignore taps on player

                val move = when {
                    dx >= 0 && dy >= 0 -> Pair( 1,  0)   // bottom-right → E
                    dx <  0 && dy >= 0 -> Pair( 0,  1)   // bottom-left  → S
                    dx >= 0 && dy <  0 -> Pair( 0, -1)   // top-right    → N
                    else               -> Pair(-1,  0)   // top-left     → W
                }
                state.move(move.first, move.second)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> { /* no-op */ }
        }
    }

    private fun startLoop() {
        if (isRunning) return
        isRunning = true
        lastFrameMs = System.currentTimeMillis()
        gameThread = Thread({
            while (isRunning) {
                if (!surfaceReady) { Thread.sleep(16); continue }
                val fs  = System.currentTimeMillis()
                val dt  = (fs - lastFrameMs).coerceAtMost(80).toDouble() / 1000.0
                lastFrameMs = fs
                update(dt.toFloat())
                draw()
                val sleep = targetFrameMs - (System.currentTimeMillis() - fs)
                if (sleep > 0) try { Thread.sleep(sleep) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }, "IsoLoop").also {
            it.priority = Thread.MAX_PRIORITY
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopLoop() {
        isRunning = false
        gameThread?.interrupt()
        gameThread?.join(500)
        gameThread = null
    }

    private fun update(dt: Float) {
        stateTime += dt
        if (rippleT >= 0f) { rippleT += dt; if (rippleT > 0.4f) rippleT = -1f }
        state.update(dt)
        // Smooth camera follows player in grid space
        camX += (state.playerX - camX) * (dt * 6f).coerceAtMost(1f)
        camY += (state.playerY - camY) * (dt * 6f).coerceAtMost(1f)
    }

    private fun draw() {
        val holder = surfaceView.holder
        var canvas: Canvas? = null
        try {
            canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                holder.lockHardwareCanvas() else holder.lockCanvas()
            canvas ?: return
            renderer.renderWorld(canvas, state, state.targetX, state.targetY, ox(), oy(), surfaceW, surfaceH, stateTime)
            renderer.renderHUD(canvas, surfaceW, surfaceH,
                state.won, state.winTimer, LevelData.hint,
                btnPrev, btnNext, rippleX, rippleY, rippleT)
        } finally {
            canvas?.let { holder.unlockCanvasAndPost(it) }
        }
    }

    override fun onDetachedFromWindow() { stopLoop(); super.onDetachedFromWindow() }
    fun pause()  { isRunning = false }
    fun resume() { if (surfaceReady) startLoop() }
    fun reset()  { state.reset(); camX = state.playerX; camY = state.playerY }
}