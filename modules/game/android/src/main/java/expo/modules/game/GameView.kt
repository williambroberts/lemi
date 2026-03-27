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
import kotlin.math.*

// ═════════════════════════════════════════════════════════════════════════════
//  ARCHITECTURE
//
//  Layer 1 — Grid data (LevelData):      pure data, no logic, no coords
//  Layer 2 — Game logic (GameState):     pure grid integers, no screen coords
//  Layer 3 — Projection:                 two interchangeable implementations
//              · Iso  — isoX/isoY + inverse screenToCol/screenToRow
//              · TopDown — simple col*TILE / row*TILE + inverse
//            Toggle via the "ISO/TOP" button in the HUD.
//            The game logic layer (Layer 2) is completely unaffected.
//  Layer 4 — Rendering (IsoRenderer):    draws using Layer 3, reads Layer 1/2
//  Layer 5 — View (GameInternalsView):   SurfaceView lifecycle + touch input
//
//  VISUAL OFFSET
//  ─────────────
//  Iso tiles are drawn with isoX/Y(col, row) at their NORTH CORNER, not
//  their visual centre.  The centre of tile (col, row) in screen space is
//  isoX/Y(col+0.5, row+0.5).  The player and objects therefore apply a
//  +0.5 offset BEFORE the iso transform so they sit on the tile centre
//  visually.  This is a projection-layer change only — no logic is affected.
//
//  THREADING
//  ─────────
//  UI thread writes @Volatile pendingDc/pendingDr/hasPending.
//  Game loop thread drains them at the top of update() and calls state.move().
//  All GameState mutations happen on the game loop thread only — no races.
//
//  PHYSICS
//  ───────
//  dyn4j holds wall bodies, stepped every frame, ready for future entities.
//  The player is NOT a physics body — movement is a lerp.
// ═════════════════════════════════════════════════════════════════════════════

// ── Projection constants ──────────────────────────────────────────────────────
private const val HALF_W = 36f
private const val HALF_H = 18f
private const val SIDE_H = 18f
private const val TILE   = 40f
private const val HEIGHT_OFFSET = 20f  // Pixels per height level

// ── ISO projection — forward ──────────────────────────────────────────────────
private fun isoX(ox: Float, col: Float, row: Float) = ox + (col - row) * HALF_W
private fun isoY(oy: Float, col: Float, row: Float, height: Float = 0f) = 
    oy + (col + row) * HALF_H - height * HEIGHT_OFFSET

// ── ISO projection — inverse ──────────────────────────────────────────────────
private fun isoScreenToCol(sx: Float, sy: Float, ox: Float, oy: Float) =
    ((sx - ox) / HALF_W + (sy - oy) / HALF_H) * 0.5f
private fun isoScreenToRow(sx: Float, sy: Float, ox: Float, oy: Float) =
    ((sy - oy) / HALF_H - (sx - ox) / HALF_W) * 0.5f

// ── TOP-DOWN projection — forward ─────────────────────────────────────────────
private fun tdX(ox: Float, col: Float) = ox + col * TILE
private fun tdY(oy: Float, row: Float) = oy + row * TILE

// ── TOP-DOWN projection — inverse ─────────────────────────────────────────────
private fun tdScreenToCol(sx: Float, ox: Float) = (sx - ox) / TILE
private fun tdScreenToRow(sy: Float, oy: Float) = (sy - oy) / TILE

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 1 — GRID DATA
// ═════════════════════════════════════════════════════════════════════════════

enum class TileType   { GRASS, SAND, STONE, WATER, TELEPORT, BARRIER }
enum class ObjectType { BLOCKER, GOAL, NONE }

data class Tile(
    val type:       TileType   = TileType.GRASS,
    val obj:        ObjectType = ObjectType.NONE,
    val isWalkable: Boolean    = true,
    val isGoal:     Boolean    = false,
    val height:     Int        = 0  // Elevation: 0 = ground, 1+ = platforms
)

private fun G(h: Int = 0) = Tile(TileType.GRASS, height = h)
private fun S(h: Int = 0) = Tile(TileType.SAND, height = h)
private fun P(h: Int = 0) = Tile(TileType.STONE, height = h)
private fun T(h: Int = 0) = Tile(TileType.TELEPORT, height = h)
private fun K(h: Int = 0) = Tile(TileType.BARRIER, height = h) // K for Barrier
private fun W(h: Int = 0) = Tile(TileType.WATER,  ObjectType.NONE,    isWalkable = false, height = h)
private fun B(h: Int = 0) = Tile(TileType.GRASS,  ObjectType.BLOCKER, isWalkable = false, height = h)
private fun X(h: Int = 0) = Tile(TileType.STONE,  ObjectType.GOAL,    isGoal     = true,  height = h)

object LevelData {
    val grid: Array<Array<Tile>> = arrayOf(
        //        0      1      2      3      4      5      6      7      8      9      10     11
        arrayOf( W(),   W(),   W(),   W(),   W(),   W(),   W(),   W(),   W(),   W(),   W(),   W()   ), // 0
        arrayOf( W(),   G(0),  G(0),  G(0),  W(),   W(),   W(),   W(),   W(),   W(),   W(),   W()   ), // 1
        arrayOf( W(),   G(0),  B(0),  G(0),  G(1),  G(1),  G(1),  W(),   W(),   W(),   W(),   W()   ), // 2
        arrayOf( W(),   G(0),  G(0),  G(1),  G(1),  P(2),  G(1),  G(1),  W(),   W(),   W(),   W()   ), // 3
        arrayOf( W(),   W(),   G(1),  G(1),  P(2),  P(2),  P(2),  G(1),  G(1),  W(),   W(),   W()   ), // 4
        arrayOf( W(),   W(),   G(1),  P(2),  P(2),  T(3),  P(2),  P(2),  G(1),  G(1),  W(),   W()   ), // 5 - Teleport A
        arrayOf( W(),   W(),   G(1),  P(2),  K(3),  P(3),  K(3),  P(2),  G(1),  G(1),  W(),   W()   ), // 6 - Barriers
        arrayOf( W(),   W(),   G(1),  P(2),  P(3),  X(4),  P(3),  P(2),  G(1),  G(0),  G(0),  W()   ), // 7 - Goal at H4
        arrayOf( W(),   W(),   G(1),  P(2),  K(3),  P(3),  K(3),  P(2),  G(1),  G(0),  B(0),  W()   ), // 8 - Barriers
        arrayOf( W(),   W(),   G(1),  G(1),  P(2),  P(2),  P(2),  G(1),  G(1),  G(0),  G(0),  W()   ), // 9
        arrayOf( W(),   W(),   W(),   G(1),  G(1),  T(1),  G(1),  G(1),  G(0),  G(0),  G(0),  W()   ), // 10 - Teleport B
        arrayOf( W(),   W(),   W(),   W(),   W(),   W(),   W(),   W(),   W(),   W(),   W(),   W()   ), // 11
    )
    val cols   = grid[0].size
    val rows   = grid.size
    val startX = 1
    val startY = 1
    val hint   = "Tap to move, Swipe to jump"

    fun tile(col: Int, row: Int): Tile? = grid.getOrNull(row)?.getOrNull(col)
    fun isWalkable(col: Int, row: Int) = tile(col, row)?.isWalkable == true

    val goalX: Int; val goalY: Int
    init {
        var gx = startX; var gy = startY
        outer@ for (r in grid.indices) for (c in grid[r].indices) {
            if (grid[r][c].isGoal) { gx = c; gy = r; break@outer }
        }
        goalX = gx; goalY = gy
    }

    fun findOtherTeleport(currentC: Int, currentR: Int): Pair<Int, Int>? {
        for (r in grid.indices) for (c in grid[r].indices) {
            if (grid[r][c].type == TileType.TELEPORT && (c != currentC || r != currentR)) {
                return Pair(c, r)
            }
        }
        return null
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  THEME
// ═════════════════════════════════════════════════════════════════════════════

private object Theme {
    val skyTop   = Color.rgb(100, 155, 210);  val skyBot   = Color.rgb(165, 200, 235)
    val grassTop = Color.rgb(95,  145,  68);  val grassL   = Color.rgb(68,  108,  46);  val grassR = Color.rgb(55,  92, 36)
    val sandTop  = Color.rgb(200, 175, 120);  val sandL    = Color.rgb(162, 138,  85);  val sandR  = Color.rgb(142,120, 68)
    val stoneTop = Color.rgb(142, 136, 126);  val stoneL   = Color.rgb(102,  98,  90);  val stoneR = Color.rgb( 86, 84, 78)
    val teleportTop = Color.rgb(180, 100, 255); val teleportL = Color.rgb(140, 70, 210); val teleportR = Color.rgb(110, 50, 180)
    val barrierTop = Color.rgb(220, 60, 60); val barrierL = Color.rgb(170, 40, 40); val barrierR = Color.rgb(130, 30, 30)
    val water    = Color.rgb( 55, 120, 178)
    val blocker  = Color.rgb(185,  80,  65);  val blockerL = Color.rgb(140,  55,  42);  val blockerR = Color.rgb(118, 45, 35)
    val goal     = Color.rgb(255, 210,  50);  val goalGlow = Color.argb(60, 255, 210, 50)
    val player   = Color.rgb( 50, 200, 205);  val playerL  = Color.rgb( 35, 137, 150)
    val shadow   = Color.argb(45, 0, 0, 0);   val edge     = Color.argb(48, 0, 0, 0)
}

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 2 — GAME LOGIC
// ═════════════════════════════════════════════════════════════════════════════

class PhysicsWorld {
    val world = World<Body>()
    init { world.setGravity(Vector2(0.0, 0.0)) }
    fun build() {
        world.bodies.toList().forEach { world.removeBody(it) }
        for (r in LevelData.grid.indices) for (c in LevelData.grid[r].indices) {
            if (!LevelData.grid[r][c].isWalkable) {
                val b = Body()
                b.addFixture(Geometry.createRectangle(0.95, 0.95))
                b.setMass(MassType.INFINITE)
                b.setAtRestDetectionEnabled(false)
                b.translate(c.toDouble(), r.toDouble())
                world.addBody(b)
            }
        }
    }
    fun step(dt: Double) { world.update(dt) }
}

class GameState {
    val physics = PhysicsWorld()
    var won = false;       private set
    var winTimer = 0f;     private set
    var playerCol = LevelData.startX.toFloat(); private set
    var playerRow = LevelData.startY.toFloat(); private set
    var playerHeight = 0f; private set
    var targetCol = LevelData.startX; private set
    var targetRow = LevelData.startY; private set
    var targetHeight = 0; private set
    var prevTargetCol = LevelData.startX; private set
    var prevTargetRow = LevelData.startY; private set
    
    var isJumping = false; private set
    var jumpProgress = 0f; private set
    
    private val LERP_SPEED = 8f
    private val JUMP_DURATION = 0.35f
    
    var stateTime = 0f; private set
    private var teleportLocked = false // Prevents re-triggering until moving off the tile

    init { 
        physics.build()
        val startTile = LevelData.tile(LevelData.startX, LevelData.startY)
        targetHeight = startTile?.height ?: 0
        playerHeight = targetHeight.toFloat()
    }

    fun getBarrierHeight(col: Int, row: Int): Float {
        val tile = LevelData.tile(col, row)
        if (tile?.type != TileType.BARRIER) return 0f
        // Moves between 0 and 1.5 height units over a 3s period
        val offset = (sin(stateTime * 2f + (col + row)) + 1f) * 0.75f
        return tile.height.toFloat() + offset
    }

    fun move(dc: Int, dr: Int, shouldJump: Boolean = false) {
        if (won || isJumping) return
        
        val nc = targetCol + dc
        val nr = targetRow + dr
        val destTile = LevelData.tile(nc, nr) ?: return
        
        if (!destTile.isWalkable) return
        
        val currentH = targetHeight.toFloat()
        val destH = if (destTile.type == TileType.BARRIER) getBarrierHeight(nc, nr) else destTile.height.toFloat()
        val heightDiff = destH - currentH
        
        // If it's a barrier and it's currently too high, we can't move onto it
        if (destTile.type == TileType.BARRIER && heightDiff > 1.2f && !shouldJump) return
        if (destTile.type == TileType.BARRIER && heightDiff > 2.0f && shouldJump) return

        when {
            abs(heightDiff) < 0.2f -> {
                targetCol = nc
                targetRow = nr
                targetHeight = destH.toInt()
                if (shouldJump) {
                    isJumping = true
                    jumpProgress = 0f
                }
            }
            heightDiff > 0 && heightDiff <= 1.2f && shouldJump -> {
                targetCol = nc
                targetRow = nr
                targetHeight = destH.toInt()
                isJumping = true
                jumpProgress = 0f
            }
            heightDiff < 0 -> {
                targetCol = nc
                targetRow = nr
                targetHeight = destH.toInt()
                if (shouldJump || heightDiff < -1.2f) {
                    isJumping = true
                    jumpProgress = 0f
                }
            }
        }
    }

    fun update(dt: Float) {
        stateTime += dt
        if (won) { winTimer += dt; return }
        physics.step(dt.toDouble())
        
        if (isJumping) {
            jumpProgress += dt / JUMP_DURATION
            if (jumpProgress >= 1f) {
                jumpProgress = 1f
                isJumping = false
                val finalTile = LevelData.tile(targetCol, targetRow)
                playerHeight = if (finalTile?.type == TileType.BARRIER) getBarrierHeight(targetCol, targetRow) else targetHeight.toFloat()
                checkTeleport()
            } else {
                val horizT = jumpProgress
                playerCol = prevTargetCol + (targetCol - prevTargetCol) * horizT
                playerRow = prevTargetRow + (targetRow - prevTargetRow) * horizT
                
                val arcHeight = sin(jumpProgress * PI.toFloat()) * 0.8f
                val startH = LevelData.tile(prevTargetCol, prevTargetRow)?.let { 
                    if (it.type == TileType.BARRIER) getBarrierHeight(prevTargetCol, prevTargetRow) else it.height.toFloat()
                } ?: 0f
                val endH = LevelData.tile(targetCol, targetRow)?.let {
                    if (it.type == TileType.BARRIER) getBarrierHeight(targetCol, targetRow) else it.height.toFloat()
                } ?: 0f
                playerHeight = startH + (endH - startH) * jumpProgress + arcHeight
            }
        } else {
            val tc = targetCol.toFloat()
            val tr = targetRow.toFloat()
            val currentTile = LevelData.tile(targetCol, targetRow)
            val th = if (currentTile?.type == TileType.BARRIER) getBarrierHeight(targetCol, targetRow) else targetHeight.toFloat()
            
            val ddx = tc - playerCol
            val ddy = tr - playerRow
            val ddh = th - playerHeight
            val distSq = ddx * ddx + ddy * ddy + ddh * ddh
            
            if (distSq > 0.0001f) {
                val step = (LERP_SPEED * dt).coerceAtMost(1f)
                playerCol += ddx * step
                playerRow += ddy * step
                playerHeight += ddh * step
            } else {
                playerCol = tc
                playerRow = tr
                playerHeight = th
                prevTargetCol = targetCol
                prevTargetRow = targetRow
                
                if (checkTeleport()) return

                if (targetCol == LevelData.goalX && targetRow == LevelData.goalY) {
                    val goalTile = LevelData.tile(targetCol, targetRow)
                    if (abs(playerHeight - (goalTile?.height ?: 0)) < 0.5f) {
                        won = true
                    }
                }
            }
        }
    }

    private fun checkTeleport(): Boolean {
        val currentTile = LevelData.tile(targetCol, targetRow)
        
        // If we're not on a teleport tile, unlock it for the next time
        if (currentTile?.type != TileType.TELEPORT) {
            teleportLocked = false
            return false
        }
        
        // If we're on a teleport tile but it's locked, don't trigger again
        if (teleportLocked) return false

        val other = LevelData.findOtherTeleport(targetCol, targetRow)
        if (other != null) {
            // Instantly snap all state variables to the destination
            targetCol = other.first
            targetRow = other.second
            prevTargetCol = other.first
            prevTargetRow = other.second
            
            val destTile = LevelData.tile(targetCol, targetRow)
            targetHeight = destTile?.height ?: 0
            
            playerCol = targetCol.toFloat()
            playerRow = targetRow.toFloat()
            playerHeight = targetHeight.toFloat()
            
            // Reset movement states to ensure no "ghost" transitions
            isJumping = false
            jumpProgress = 0f
            
            // Lock teleport until we move to a non-teleport tile
            teleportLocked = true
            
            return true
        }
        return false
    }

    fun reset() {
        won = false;  winTimer = 0f; stateTime = 0f
        targetCol = LevelData.startX;      targetRow = LevelData.startY
        prevTargetCol = LevelData.startX;  prevTargetRow = LevelData.startY
        playerCol = LevelData.startX.toFloat()
        playerRow = LevelData.startY.toFloat()
        val startTile = LevelData.tile(LevelData.startX, LevelData.startY)
        targetHeight = startTile?.height ?: 0
        playerHeight = targetHeight.toFloat()
        isJumping = false
        jumpProgress = 0f
        teleportLocked = false
        physics.build()
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  DRAW CALL QUEUE
// ═════════════════════════════════════════════════════════════════════════════

fun interface DrawAction { fun draw() }

class DrawCallQueue(capacity: Int = 512) {
    private class Entry { var key = 0f; var fn: DrawAction = NOOP }
    private val pool = Array(capacity) { Entry() }
    private var size = 0
    fun add(key: Float, fn: DrawAction) {
        if (size >= pool.size) return
        pool[size].key = key;  pool[size].fn = fn;  size++
    }
    fun flush() {
        for (i in 1 until size) {
            val k = pool[i].key;  val f = pool[i].fn;  var j = i - 1
            while (j >= 0 && pool[j].key > k) { pool[j+1].key=pool[j].key; pool[j+1].fn=pool[j].fn; j-- }
            pool[j+1].key = k;  pool[j+1].fn = f
        }
        for (i in 0 until size) { pool[i].fn.draw();  pool[i].fn = NOOP }
        size = 0
    }
    companion object { private val NOOP = DrawAction {} }
}

// ═════════════════════════════════════════════════════════════════════════════
//  LAYERS 3 & 4 — RENDERER
// ═════════════════════════════════════════════════════════════════════════════

class IsoRenderer {

    private val bgP      = Paint().apply { style = Paint.Style.FILL }
    private val fillP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
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
    private val playerEP = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true; color = Theme.playerL }
    private val tdEdgeP  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true; color = Color.argb(80,0,0,0) }
    private val tdPlayerP= Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val tdLabelP = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; textSize = 9f; color = Color.argb(120,0,0,0) }
    private val hudP     = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val hudSP    = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val overlayP = Paint().apply { style = Paint.Style.FILL }
    private val bFillP   = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val bEdgeP   = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
    private val bTextP   = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val rippleP  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }

    private val topPath = Path();  private val sideL = Path();  private val sideR = Path()
    private val queue   = DrawCallQueue(512)

    private var cachedSky: LinearGradient? = null;  private var cSkySh = 0f
    private var cachedPlayerGrad: RadialGradient? = null
    private var cPx = Float.MAX_VALUE;  private var cPy = Float.MAX_VALUE

    private inner class TileSlot {
        var canvas: Canvas? = null;  var cx = 0f;  var cy = 0f
        var cTop = 0;  var cL = 0;  var cR = 0;  var water = false
        val fn = DrawAction { drawIsoTile(canvas!!, cx, cy, cTop, cL, cR, water) }
    }
    private inner class ObjSlot {
        var canvas: Canvas? = null;  var cx = 0f;  var cy = 0f
        var obj = ObjectType.NONE;  var t = 0f
        val fn = DrawAction { drawIsoObj(canvas!!, cx, cy, obj, t) }
    }
    private inner class PlayerSlot {
        var canvas: Canvas? = null;  var cx = 0f;  var cy = 0f;  var iso = true
        val fn = DrawAction { if (iso) drawIsoPlayer(canvas!!, cx, cy) else drawTdPlayer(canvas!!, cx, cy) }
    }

    private val tilePool   = Array(512) { TileSlot() }
    private val objPool    = Array(64)  { ObjSlot() }
    private val playerSlot = PlayerSlot()
    private var ti = 0;  private var oi = 0

    fun renderWorld(canvas: Canvas, state: GameState, ox: Float, oy: Float,
                    sw: Float, sh: Float, t: Float, isIso: Boolean, rotation: Int) {
        ti = 0;  oi = 0
        if (isIso) {
            if (sh != cSkySh) {
                cachedSky = LinearGradient(0f,0f,0f,sh, intArrayOf(Theme.skyTop,Theme.skyBot), null, Shader.TileMode.CLAMP)
                cSkySh = sh
            }
            bgP.shader = cachedSky; canvas.drawRect(0f,0f,sw,sh,bgP)
            
            for (r in 0 until LevelData.rows) for (c in 0 until LevelData.cols) {
                val tile = LevelData.tile(c, r) ?: continue
                val h = if (tile.type == TileType.BARRIER) state.getBarrierHeight(c, r) else tile.height.toFloat()
                val rc: Float; val rr: Float
                when (rotation % 4) {
                    1 -> { rc = r.toFloat(); rr = (LevelData.cols - 1 - c).toFloat() }
                    2 -> { rc = (LevelData.cols - 1 - c).toFloat(); rr = (LevelData.rows - 1 - r).toFloat() }
                    3 -> { rc = (LevelData.rows - 1 - r).toFloat(); rr = c.toFloat() }
                    else -> { rc = c.toFloat(); rr = r.toFloat() }
                }
                val tx = isoX(ox, rc, rr)
                val ty = isoY(oy, rc, rr, h)
                val cx = isoX(ox, rc + 0.5f, rr + 0.5f)
                val cy = isoY(oy, rc + 0.5f, rr + 0.5f, h)

                if (ti < tilePool.size) {
                    val s = tilePool[ti++]
                    s.canvas=canvas; s.cx=tx; s.cy=ty; s.water=(tile.type==TileType.WATER)
                    s.cTop = when(tile.type) {
                        TileType.GRASS -> Theme.grassTop; TileType.SAND -> Theme.sandTop
                        TileType.STONE -> Theme.stoneTop; TileType.WATER -> Theme.water
                        TileType.TELEPORT -> Theme.teleportTop; TileType.BARRIER -> Theme.barrierTop
                    }
                    s.cL = when(tile.type) {
                        TileType.GRASS -> Theme.grassL; TileType.SAND -> Theme.sandL
                        TileType.STONE -> Theme.stoneL; TileType.WATER -> Theme.water
                        TileType.TELEPORT -> Theme.teleportL; TileType.BARRIER -> Theme.barrierL
                    }
                    s.cR = when(tile.type) {
                        TileType.GRASS -> Theme.grassR; TileType.SAND -> Theme.sandR
                        TileType.STONE -> Theme.stoneR; TileType.WATER -> Theme.water
                        TileType.TELEPORT -> Theme.teleportR; TileType.BARRIER -> Theme.barrierR
                    }
                    queue.add(rc + rr + h * 0.01f, s.fn)
                }
                if (tile.obj != ObjectType.NONE && oi < objPool.size) {
                    val s = objPool[oi++]
                    s.canvas=canvas; s.cx=cx; s.cy=cy; s.obj=tile.obj; s.t=t
                    queue.add(rc + rr + h * 0.01f + 0.005f, s.fn)
                }
            }
            val prc: Float; val prr: Float
            when (rotation % 4) {
                1 -> { prc = state.playerRow; prr = (LevelData.cols - 1 - state.playerCol) }
                2 -> { prc = (LevelData.cols - 1 - state.playerCol); prr = (LevelData.rows - 1 - state.playerRow) }
                3 -> { prc = (LevelData.rows - 1 - state.playerRow); prr = state.playerCol }
                else -> { prc = state.playerCol; prr = state.playerRow }
            }
            playerSlot.canvas=canvas; playerSlot.iso=true
            playerSlot.cx = isoX(ox, prc+0.5f, prr+0.5f)
            playerSlot.cy = isoY(oy, prc+0.5f, prr+0.5f, state.playerHeight)
            // Restore the +0.5f depth offset to ensure the player is drawn after the tile they are over
            queue.add(prc + prr + 0.5f + state.playerHeight * 0.01f + 0.005f, playerSlot.fn)
            queue.flush()
        } else {
            canvas.drawColor(Color.rgb(40,45,50))
            for (r in 0 until LevelData.rows) for (c in 0 until LevelData.cols) {
                val tile = LevelData.tile(c, r) ?: continue
                val x = tdX(ox, c.toFloat()); val y = tdY(oy, r.toFloat())
                fillP.color = when {
                    tile.isGoal -> Theme.goal; tile.obj == ObjectType.BLOCKER -> Theme.blocker
                    tile.type == TileType.WATER -> Theme.water; tile.type == TileType.GRASS -> Theme.grassTop
                    tile.type == TileType.SAND -> Theme.sandTop; tile.type == TileType.STONE -> Theme.stoneTop
                    tile.type == TileType.TELEPORT -> Theme.teleportTop; tile.type == TileType.BARRIER -> Theme.barrierTop
                    else -> Color.DKGRAY
                }
                canvas.drawRect(x, y, x+TILE, y+TILE, fillP)
                canvas.drawRect(x, y, x+TILE, y+TILE, tdEdgeP)
                canvas.drawText("$c,$r", x+TILE*0.5f, y+TILE*0.62f, tdLabelP)
            }
            val px = tdX(ox, state.playerCol) + TILE*0.5f
            val py = tdY(oy, state.playerRow) + TILE*0.5f
            val r  = TILE * 0.35f
            canvas.drawCircle(px+2f, py+3f, r, shadowP)
            tdPlayerP.color = Theme.player;  canvas.drawCircle(px, py, r, tdPlayerP)
            tdPlayerP.color = Theme.playerL; tdPlayerP.style=Paint.Style.STROKE; tdPlayerP.strokeWidth=2f
            canvas.drawCircle(px, py, r, tdPlayerP);  tdPlayerP.style=Paint.Style.FILL
        }
    }

    fun renderHUD(canvas: Canvas, sw: Float, sh: Float,
                  won: Boolean, winTimer: Float, hint: String,
                  btnView: RectF, btnRotL: RectF, btnRotR: RectF,
                  isIso: Boolean, rippleX: Float, rippleY: Float, rippleT: Float) {
        if (rippleT in 0f..0.4f) {
            val prog = rippleT/0.4f
            rippleP.color = Color.argb(((1f-prog)*80).toInt(),255,255,255)
            canvas.drawCircle(rippleX, rippleY, prog*44f, rippleP)
        }
        hudSP.textSize=13f; hudSP.color=Color.argb(150,255,255,255)
        hudSP.setShadowLayer(3f,0f,1f,Color.argb(70,0,0,0))
        canvas.drawText(hint, sw/2f, sh-18f, hudSP)
        
        fun btn(r: RectF, lbl: String, highlight: Boolean=false) {
            bFillP.color = if(highlight) Color.argb(160,255,200,0) else Color.argb(100,0,0,0)
            canvas.drawRoundRect(r,10f,10f,bFillP)
            bEdgeP.color=Color.argb(80,255,255,255); canvas.drawRoundRect(r,10f,10f,bEdgeP)
            bTextP.textSize=14f; bTextP.color=if(highlight) Color.argb(220,0,0,0) else Color.argb(200,255,255,255)
            canvas.drawText(lbl, r.centerX(), r.centerY()+7f, bTextP)
        }
        
        btn(btnView, if(isIso)"TOP" else "ISO", !isIso)
        btn(btnRotL, "↺"); btn(btnRotR, "↻")
        
        if (won) {
            val alpha = min(190,(winTimer*280).toInt())
            overlayP.color=Color.argb(alpha,0,0,0); canvas.drawRect(0f,0f,sw,sh,overlayP)
            if (winTimer>0.4f) {
                hudP.textSize=sw*0.11f; hudP.color=Theme.goal
                hudP.setShadowLayer(10f,0f,4f,Color.argb(70,0,0,0))
                canvas.drawText("✦",sw/2f,sh/2f-16f,hudP)
                hudP.textSize=sw*0.044f; hudP.color=Color.WHITE
                canvas.drawText("You made it!",sw/2f,sh/2f+48f,hudP)
                if (winTimer>1.0f) {
                    hudSP.textSize=sw*0.028f; hudSP.color=Color.argb(175,255,255,255)
                    canvas.drawText("Tap anywhere to restart",sw/2f,sh/2f+94f,hudSP)
                }
            }
        }
    }

    private fun drawIsoTile(canvas: Canvas, cx: Float, cy: Float, cTop: Int, cL: Int, cR: Int, water: Boolean) {
        val tx=cx; val ty=cy-HALF_H; val rx=cx+HALF_W; val ry=cy
        val bx=cx; val by=cy+HALF_H; val lx=cx-HALF_W; val ly=cy
        topPath.reset(); topPath.moveTo(tx,ty); topPath.lineTo(rx,ry); topPath.lineTo(bx,by); topPath.lineTo(lx,ly); topPath.close()
        if (water) { waterP.color=cTop; waterP.alpha=210; canvas.drawPath(topPath,waterP); canvas.drawPath(topPath,edgeP); return }
        topP.color=cTop; canvas.drawPath(topPath,topP); canvas.drawPath(topPath,edgeP)
        sideL.reset(); sideL.moveTo(lx,ly); sideL.lineTo(bx,by); sideL.lineTo(bx,by+SIDE_H); sideL.lineTo(lx,ly+SIDE_H); sideL.close()
        leftP.color=cL; canvas.drawPath(sideL,leftP); canvas.drawPath(sideL,edgeP)
        sideR.reset(); sideR.moveTo(bx,by); sideR.lineTo(rx,ry); sideR.lineTo(rx,ry+SIDE_H); sideR.lineTo(bx,by+SIDE_H); sideR.close()
        rightP.color=cR; canvas.drawPath(sideR,rightP); canvas.drawPath(sideR,edgeP)
    }

    private fun drawIsoObj(canvas: Canvas, cx: Float, cy: Float, obj: ObjectType, t: Float) {
        val base = cy - HALF_H
        when (obj) {
            ObjectType.BLOCKER -> {
                val h = 32f
                objTP.color=Theme.blocker
                topPath.reset(); topPath.moveTo(cx,base-h-HALF_H); topPath.lineTo(cx+HALF_W,base-h); topPath.lineTo(cx,base-h+HALF_H); topPath.lineTo(cx-HALF_W,base-h); topPath.close()
                canvas.drawPath(topPath,objTP); canvas.drawPath(topPath,edgeP)
                sideL.reset(); sideL.moveTo(cx-HALF_W,base-h); sideL.lineTo(cx,base-h+HALF_H); sideL.lineTo(cx,base+HALF_H); sideL.lineTo(cx-HALF_W,base); sideL.close()
                objLP.color=Theme.blockerL; canvas.drawPath(sideL,objLP); canvas.drawPath(sideL,edgeP)
                sideR.reset(); sideR.moveTo(cx,base-h+HALF_H); sideR.lineTo(cx+HALF_W,base-h); sideR.lineTo(cx+HALF_W,base); sideR.lineTo(cx,base+HALF_H); sideR.close()
                objRP.color=Theme.blockerR; canvas.drawPath(sideR,objRP); canvas.drawPath(sideR,edgeP)
            }
            ObjectType.GOAL -> {
                val pulse = 1f + 0.15f * sin(t*3f)
                canvas.drawCircle(cx, base-20f, 30f*pulse, glowP)
                val h = 28f*pulse
                topPath.reset(); topPath.moveTo(cx,base-h-16f); topPath.lineTo(cx+14f,base-h*0.5f); topPath.lineTo(cx,base-4f); topPath.lineTo(cx-14f,base-h*0.5f); topPath.close()
                objTP.color=Theme.goal; canvas.drawPath(topPath,objTP)
            }
            ObjectType.NONE -> Unit
        }
    }

    private fun drawIsoPlayer(canvas: Canvas, cx: Float, cy: Float) {
        val base=cy-HALF_H; val pw=HALF_W*0.55f; val ph=HALF_H*0.55f; val height=28f
        canvas.drawOval(cx-pw*1.1f,base+ph*0.4f,cx+pw*1.1f,base+ph*0.9f,shadowP)
        if (abs(cx-cPx)>3f||abs(cy-cPy)>3f) {
            cachedPlayerGrad=RadialGradient(cx-pw*0.25f,base-height*0.7f,pw*1.4f,
                intArrayOf(Color.rgb(90,170,255),Theme.player,Theme.playerL),floatArrayOf(0f,0.5f,1f),Shader.TileMode.CLAMP)
            cPx=cx; cPy=cy
        }
        playerTP.shader=cachedPlayerGrad
        topPath.reset(); topPath.moveTo(cx,base-height-ph); topPath.lineTo(cx+pw,base-height); topPath.lineTo(cx,base-height+ph); topPath.lineTo(cx-pw,base-height); topPath.close()
        canvas.drawPath(topPath,playerTP); canvas.drawPath(topPath,playerEP)
        sideL.reset(); sideL.moveTo(cx-pw,base-height); sideL.lineTo(cx,base-height+ph); sideL.lineTo(cx,base+ph); sideL.lineTo(cx-pw,base); sideL.close()
        canvas.drawPath(sideL,playerLP); canvas.drawPath(sideL,playerEP)
        sideR.reset(); sideR.moveTo(cx,base-height+ph); sideR.lineTo(cx+pw,base-height); sideR.lineTo(cx+pw,base); sideR.lineTo(cx,base+ph); sideR.close()
        canvas.drawPath(sideR,playerRP); canvas.drawPath(sideR,playerEP)
    }

    private fun drawTdPlayer(canvas: Canvas, cx: Float, cy: Float) { }
}

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 5 — VIEW
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
    private var surfaceW = 0f;  private var surfaceH = 0f

    private val renderer = IsoRenderer()
    private val state    = GameState()
    private var isIso    = true
    private var rotation = 0

    @Volatile private var pendingDc:    Int     = 0
    @Volatile private var pendingDr:    Int     = 0
    @Volatile private var hasPending:   Boolean = false
    @Volatile private var pendingReset: Boolean = false
    @Volatile private var pendingJump:  Boolean = false

    private var isoOx = 0f;  private var isoOy = 0f
    private var tdOx  = 0f;  private var tdOy  = 0f
    private fun ox() = if (isIso) isoOx else tdOx
    private fun oy() = if (isIso) isoOy else tdOy

    private var rippleX = 0f;  private var rippleY = 0f;  private var rippleT = -1f
    private val btnView = RectF(); private val btnRotL = RectF(); private val btnRotR = RectF()

    // Gesture tracking
    private var startX = 0f; private var startY = 0f
    private val SWIPE_THRESHOLD = 40f

    init {
        setWillNotDraw(true);  clipChildren = false
        addView(surfaceView)
        surfaceView.setOnTouchListener { _, ev -> handleTouch(ev); true }
    }

    override fun surfaceCreated(h: SurfaceHolder)   { surfaceReady = true;  startLoop() }
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stopLoop()  }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
        surfaceW = w.toFloat();  surfaceH = ht.toFloat()
        val bm = 16f; val ubw = 52f; val ubh = 44f
        btnView.set(surfaceW/2 - 26f, bm, surfaceW/2 + 26f, bm + ubh)
        btnRotL.set(bm, bm, bm + ubw, bm + ubh)
        btnRotR.set(surfaceW - bm - ubw, bm, surfaceW - bm, bm + ubh)
        
        val midC = (LevelData.cols-1)/2f;  val midR = (LevelData.rows-1)/2f
        isoOx = surfaceW*0.50f - (midC-midR)*HALF_W
        isoOy = surfaceH*0.42f - (midC+midR)*HALF_H
        tdOx  = (surfaceW - LevelData.cols*TILE)*0.5f
        tdOy  = (surfaceH - LevelData.rows*TILE)*0.45f
    }

    private fun handleTouch(ev: MotionEvent) {
        val sx = ev.x; val sy = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = sx; startY = sy
                rippleX = sx; rippleY = sy; rippleT = 0f
                if (btnView.contains(sx, sy)) { isIso = !isIso; return }
                if (btnRotL.contains(sx, sy)) { rotation = (rotation + 3) % 4; return }
                if (btnRotR.contains(sx, sy)) { rotation = (rotation + 1) % 4; return }
                if (state.won) { pendingReset = true; return }
            }
            MotionEvent.ACTION_UP -> {
                val dx = sx - startX; val dy = sy - startY
                val dist = sqrt(dx*dx + dy*dy)
                
                val moveDc: Int; val moveDr: Int
                if (dist > SWIPE_THRESHOLD) {
                    val swipeCol = (dx / HALF_W + dy / HALF_H) * 0.5f
                    val swipeRow = (dy / HALF_H - dx / HALF_W) * 0.5f
                    if (abs(swipeCol) > abs(swipeRow)) {
                        moveDc = if(swipeCol > 0) 1 else -1; moveDr = 0
                    } else {
                        moveDc = 0; moveDr = if(swipeRow > 0) 1 else -1
                    }
                    pendingJump = true
                } else {
                    val tapCol: Float; val tapRow: Float
                    if (isIso) {
                        tapCol = isoScreenToCol(startX, startY, ox(), oy())
                        tapRow = isoScreenToRow(startX, startY, ox(), oy())
                    } else {
                        tapCol = tdScreenToCol(startX, ox())
                        tapRow = tdScreenToRow(startY, oy())
                    }
                    val finalCol: Float; val finalRow: Float
                    when (rotation % 4) {
                        1 -> { finalCol = (LevelData.cols - 1 - tapRow); finalRow = tapCol }
                        2 -> { finalCol = (LevelData.cols - 1 - tapCol); finalRow = (LevelData.rows - 1 - tapRow) }
                        3 -> { finalCol = tapRow; finalRow = (LevelData.rows - 1 - tapCol) }
                        else -> { finalCol = tapCol; finalRow = tapRow }
                    }
                    val dCol = finalCol - state.targetCol
                    val dRow = finalRow - state.targetRow
                    if (abs(dCol) < 0.25f && abs(dRow) < 0.25f) return
                    if (abs(dCol) > abs(dRow)) { moveDc = if(dCol > 0f) 1 else -1; moveDr = 0 }
                    else                       { moveDc = 0; moveDr = if(dRow > 0f) 1 else -1 }
                    pendingJump = false
                }
                val finalDc: Int; val finalDr: Int
                when (rotation % 4) {
                    1 -> { finalDc = -moveDr; finalDr = moveDc }
                    2 -> { finalDc = -moveDc; finalDr = -moveDr }
                    3 -> { finalDc = moveDr; finalDr = -moveDc }
                    else -> { finalDc = moveDc; finalDr = moveDr }
                }
                pendingDc = finalDc; pendingDr = finalDr; hasPending = true
            }
        }
    }

    private fun startLoop() {
        if (isRunning) return
        isRunning=true;  lastFrameMs=System.currentTimeMillis()
        gameThread = Thread({
            while (isRunning) {
                if (!surfaceReady) { Thread.sleep(16); continue }
                val fs=System.currentTimeMillis()
                val dt=(fs-lastFrameMs).coerceAtMost(80).toDouble()/1000.0
                lastFrameMs=fs
                update(dt.toFloat());  draw()
                val sleep=targetFrameMs-(System.currentTimeMillis()-fs)
                if (sleep>0) try { Thread.sleep(sleep) } catch(_:InterruptedException) { Thread.currentThread().interrupt() }
            }
        }, "IsoLoop").also { it.priority=Thread.MAX_PRIORITY; it.isDaemon=true; it.start() }
    }

    private fun stopLoop() {
        isRunning=false
        gameThread?.interrupt();  gameThread?.join(500);  gameThread=null
    }

    private fun update(dt: Float) {
        if (pendingReset) { pendingReset=false; hasPending=false; state.reset() }
        if (hasPending)   { 
            hasPending=false
            state.move(pendingDc, pendingDr, pendingJump)
        }
        if (rippleT>=0f) { rippleT+=dt; if(rippleT>0.4f) rippleT=-1f }
        state.update(dt)
    }

    private fun draw() {
        val holder=surfaceView.holder;  var canvas: Canvas?=null
        try {
            canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                holder.lockHardwareCanvas() else holder.lockCanvas()
            canvas ?: return
            renderer.renderWorld(canvas, state, ox(), oy(), surfaceW, surfaceH, state.stateTime, isIso, rotation)
            renderer.renderHUD(canvas, surfaceW, surfaceH,
                state.won, state.winTimer, LevelData.hint,
                btnView, btnRotL, btnRotR, isIso, rippleX, rippleY, rippleT)
        } finally { canvas?.let { holder.unlockCanvasAndPost(it) } }
    }

    override fun onDetachedFromWindow() { stopLoop(); super.onDetachedFromWindow() }
    fun pause()  { isRunning=false }
    fun resume() { if (surfaceReady) startLoop() }
    fun reset()  { pendingReset=true }
}
