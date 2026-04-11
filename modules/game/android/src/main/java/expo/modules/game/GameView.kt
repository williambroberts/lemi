package expo.modules.gameinternals

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
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
//  Layer 1 — Grid data (LevelLayout/LevelManager): pure data, no logic, no coords
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
//
//  BUILD OVERLAY
//  ─────────────
//  Long-pressing a direction places a "built" tile adjacent to the player.
//  Built tiles are stored in GameState.builtTiles — a Map<Pair<Int,Int>, Int>
//  of (col, row) → height. They never mutate LevelLayout. The renderer reads
//  both LevelLayout and builtTiles when drawing, with builtTiles drawn on top
//  of null cells or rendered as raised versions of existing cells.
//
//  IMPOSSIBLE LINKS
//  ────────────────
//  An ImpossibleLink is an explicit directional connection between two world-
//  space cells that becomes traversable only when the camera is at a specific
//  rotation.  Links are declared in LevelLayout alongside the grid — they are
//  pure data and carry no rendering or projection logic.
//
//  Each link has:
//    · fromCol/fromRow  — origin cell (world space)
//    · toCol/toRow      — destination cell (world space)
//    · requiredRotation — camera rotation (0–3) at which the link is active
//    · heightOverride   — if non-null, the player lands at this height instead
//                         of the destination tile's natural height.  Allows
//                         crossing between platforms of different heights.
//
//  The 'I' tile char is a purely visual marker meaning "an impossible link
//  starts or ends here."  Its tile.height is the REAL height of the tile, not
//  the required rotation — that hack is gone.  The renderer queries
//  LevelLayout.isActiveLinkEndpoint() to decide the glow colour.
//
//  To add a new impossible connection in a level, just add ImpossibleLink
//  entries to the level's impossibleLinks list — no code changes needed.
//  Links are one-directional; add the reverse entry for two-way traversal.
// ═════════════════════════════════════════════════════════════════════════════

// ── Projection base constants (unscaled) ─────────────────────────────────────
private const val BASE_HALF_W        = 36f
private const val BASE_HALF_H        = 18f
private const val BASE_SIDE_H        = 18f
private const val BASE_TILE          = 40f
private const val BASE_HEIGHT_OFFSET = 20f

// ── Scaled projection values — updated by GameInternalsView.applyZoom() ──────
private var HALF_W        = BASE_HALF_W
private var HALF_H        = BASE_HALF_H
private var SIDE_H        = BASE_SIDE_H
private var TILE          = BASE_TILE
private var HEIGHT_OFFSET = BASE_HEIGHT_OFFSET

// ── Zoom limits ───────────────────────────────────────────────────────────────
private const val ZOOM_MIN  = 0.4f
private const val ZOOM_MAX  = 2.0f
private const val ZOOM_STEP = 0.15f

// ── ISO projection — forward ──────────────────────────────────────────────────
private fun isoX(ox: Float, col: Float, row: Float) =
    ox + (col - row) * HALF_W

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
//  LONG-PRESS ACTIONS
// ═════════════════════════════════════════════════════════════════════════════

enum class BuildAction(val label: String) {
    BUILD_TILE("+ Tile"),
    REMOVE_TILE("- Tile"),
}

// ═════════════════════════════════════════════════════════════════════════════
//  IMPOSSIBLE LINKS
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Declares a perspective-dependent connection between two cells.
 *
 * When the camera is at [requiredRotation] and the player is standing on
 * ([fromCol], [fromRow]) and moves in the direction of ([toCol], [toRow]),
 * the move succeeds — even if those cells are not adjacent in world space.
 *
 * [heightOverride]: if set, the player arrives at this height regardless of
 * the destination tile's natural height.  Useful for linking platforms at
 * different elevations that should feel flush from one camera angle.
 *
 * Links are one-directional.  For two-way traversal declare both directions.
 *
 * Example — two platforms separated by a void, bridged at rotation 1:
 *   ImpossibleLink(requiredRotation=1, fromCol=2, fromRow=2, toCol=4, toRow=4)
 *   ImpossibleLink(requiredRotation=1, fromCol=4, fromRow=4, toCol=2, toRow=2)
 */
data class ImpossibleLink(
    val requiredRotation: Int,
    val fromCol: Int,
    val fromRow: Int,
    val toCol: Int,
    val toRow: Int,
    val heightOverride: Int? = null
)

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 1 — GRID DATA
// ═════════════════════════════════════════════════════════════════════════════

interface TileType {
    val charCode: Char
    val isWalkable: Boolean get() = true
    fun getTopColor(state: GameState, col: Int, row: Int): Int
    fun getLeftColor(state: GameState, col: Int, row: Int): Int
    fun getRightColor(state: GameState, col: Int, row: Int): Int
    fun getHeightOffset(state: GameState, col: Int, row: Int): Float = 0f
    fun getPositionOffset(state: GameState, col: Int, row: Int): Pair<Float, Float> = Pair(0f, 0f)
}

open class BasicTile(
    override val charCode: Char,
    private val topColor: Int,
    private val leftColor: Int,
    private val rightColor: Int,
    override val isWalkable: Boolean = true
) : TileType {
    override fun getTopColor(state: GameState, col: Int, row: Int)  = topColor
    override fun getLeftColor(state: GameState, col: Int, row: Int)  = leftColor
    override fun getRightColor(state: GameState, col: Int, row: Int) = rightColor
}

enum class ObjectType { BLOCKER, GOAL, NONE }

data class Tile(
    val type:       TileType,
    val obj:        ObjectType = ObjectType.NONE,
    val isWalkable: Boolean    = true,
    val isGoal:     Boolean    = false,
    val height:     Int        = 0
)

class LevelLayout(
    val layout: Array<Array<String>>,
    val startX: Int    = 1,
    val startY: Int    = 1,
    val hint:   String = "Tap to move · Long-press to build",
    // Impossible links declared alongside the grid — pure data, no logic.
    val impossibleLinks: List<ImpossibleLink> = emptyList()
) {
    val grid: Array<Array<Tile?>> = Array(layout.size) { r ->
        Array(layout[r].size) { c ->
            val code = layout[r][c]
            if (code == "..") null
            else {
                val typeChar = code[0]
                val height   = code[1].digitToInt()
                val tileType = TileRegistry.get(typeChar) ?: TileRegistry.get('G')!!

                var obj        = ObjectType.NONE
                var isWalkable = tileType.isWalkable
                var isGoal     = false

                if (typeChar == 'B') { obj = ObjectType.BLOCKER; isWalkable = false }
                if (typeChar == 'X') { obj = ObjectType.GOAL;    isGoal     = true  }

                Tile(tileType, obj, isWalkable, isGoal, height)
            }
        }
    }

    val cols = grid[0].size
    val rows = grid.size

    val goalX: Int
    val goalY: Int

    // Pre-built lookup: (fromCol, fromRow) → list of links originating there.
    // GameState.move() uses this for O(1) link lookup per move attempt.
    private val linksByOrigin: Map<Pair<Int, Int>, List<ImpossibleLink>> =
        impossibleLinks.groupBy { Pair(it.fromCol, it.fromRow) }

    // Set of all cells that are an endpoint (from OR to) of any link, for the
    // renderer's glow query.
    private val linkEndpoints: Set<Pair<Int, Int>> =
        impossibleLinks.flatMap {
            listOf(Pair(it.fromCol, it.fromRow), Pair(it.toCol, it.toRow))
        }.toSet()

    init {
        var gx = startX; var gy = startY
        outer@ for (r in grid.indices) for (c in grid[r].indices) {
            if (grid[r][c]?.isGoal == true) { gx = c; gy = r; break@outer }
        }
        goalX = gx; goalY = gy
    }

    fun tile(col: Int, row: Int): Tile? = grid.getOrNull(row)?.getOrNull(col)
    fun isInBounds(col: Int, row: Int): Boolean = col in 0 until cols && row in 0 until rows

    /** Returns all impossible links that originate at (col, row). */
    fun linksFrom(col: Int, row: Int): List<ImpossibleLink> =
        linksByOrigin[Pair(col, row)] ?: emptyList()

    /**
     * Returns true if (col, row) is the from- or to-end of any link that is
     * active at [rotation].  Used by the renderer to decide whether to glow
     * an 'I' tile.
     */
    fun isActiveLinkEndpoint(col: Int, row: Int, rotation: Int): Boolean {
        if (Pair(col, row) !in linkEndpoints) return false
        return impossibleLinks.any { link ->
            link.requiredRotation == rotation &&
            (link.fromCol == col && link.fromRow == row ||
             link.toCol   == col && link.toRow   == row)
        }
    }

    fun findOtherTeleport(currentC: Int, currentR: Int): Pair<Int, Int>? {
        for (r in grid.indices) for (c in grid[r].indices) {
            val t = grid[r][c]
            if (t != null && t.type.charCode == 'T' && (c != currentC || r != currentR))
                return Pair(c, r)
        }
        return null
    }
}

object LevelManager {
    val levels = mutableListOf<LevelLayout>()
    var currentLevelIndex = 0

    val currentLevel: LevelLayout get() = levels[currentLevelIndex]

    fun loadNextLevel(): Boolean {
        if (currentLevelIndex < levels.size - 1) { currentLevelIndex++; return true }
        return false
    }

    init {
        // ── Level 0: Tutorial ────────────────────────────────────────────────
        levels.add(LevelLayout(
            arrayOf(
                arrayOf("..", "..", "..", "..", "..", "..", "..", "..", "..", "..", "..", ".."),
                arrayOf("..", "G0", "W0", "D0", "G0", "10", "G0", "..", "..", "..", "..", ".."),
                arrayOf("..", "G0", "B0", "G0", "G1", "G1", "G1", "..", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "G1", "G1", "P2", "G1", "G1", "..", "..", "..", ".."),
                arrayOf("..", "..", "G1", "G1", "P2", "P2", "P2", "G1", "G1", "..", "..", ".."),
                arrayOf("..", "..", "G1", "P2", "P2", "T3", "P2", "P2", "G1", "G1", "..", ".."),
                arrayOf("..", "..", "G1", "P2", "K3", "P3", "K3", "P2", "G1", "G1", "..", ".."),
                arrayOf("..", "..", "G1", "P2", "P3", "X4", "P3", "P2", "G1", "G0", "G0", ".."),
                arrayOf("..", "..", "G1", "P2", "K3", "P3", "K3", "P2", "G1", "G0", "B0", ".."),
                arrayOf("..", "..", "G1", "G1", "P2", "P2", "P2", "G1", "G1", "G0", "G0", ".."),
                arrayOf("..", "..", "..", "G1", "G1", "T1", "G1", "G1", "G0", "G0", "G0", ".."),
                arrayOf("..", "..", "..", "..", "..", "..", "..", "..", "..", "..", "..", "..")
            )
        ))

        // ── Level 1: Impossible Geometry Demo ────────────────────────────────
        levels.add(LevelLayout(
            layout = arrayOf(
                arrayOf("X9", "..", "..", "..", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "G0", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "G0", "..", "..", "..", ".."),  // link origin,      h=0
                arrayOf("..", "G0", "G0", "G0", "..", "..", "..", ".."),
                arrayOf("..", "..", "..", "..", "G0", "P2", "P2", ".."),  // link destination, h=2
                arrayOf("..", "..", "..", "..", "P2", "P2", "P2", ".."),
                arrayOf("..", "..", "..", "..", "P2", "P2", "P2", ".."),
                arrayOf("..", "..", "..", "..", "..", "..", "..", "..")
            ),
            startX = 1, startY = 1,
            hint = "Rotate camera (↺/↻) to connect the impossible path!",
        ))
        levels.add(LevelLayout(
            layout = arrayOf(
                arrayOf("..", "..", "..", "..", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "G0", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "G0", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "I0", "..", "..", "..", ".."),  // bottom-right corner of G0 island
                arrayOf("..", "..", "..", "..", "G0", "I2", "P2", ".."),  // top-left corner of P2 island
                arrayOf("..", "..", "..", "..", "I2", "X9", "P2", ".."),
                arrayOf("..", "..", "..", "..", "P2", "P2", "P2", ".."),
                arrayOf("..", "..", "..", "..", "..", "..", "..", "..")
            ),
            startX = 1, startY = 1,
            hint = "Rotate to break the connection!",
            impossibleLinks = listOf(
                ImpossibleLink(requiredRotation = 0, fromCol = 3, fromRow = 3, toCol = 5, toRow = 4),
                ImpossibleLink(requiredRotation = 0, fromCol = 3, fromRow = 3, toCol = 4, toRow = 5),
                ImpossibleLink(requiredRotation = 0, fromCol = 5, fromRow = 4, toCol = 3, toRow = 3),
                ImpossibleLink(requiredRotation = 0, fromCol = 4, fromRow = 5, toCol = 3, toRow = 3),
            )
        ))
        levels.add(LevelLayout(
            layout = arrayOf(
                arrayOf("..", "..", "..", "..", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "G0", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "G0", "..", "..", "..", ".."),
                arrayOf("..", "G0", "G0", "I0", "..", "..", "..", ".."),
                arrayOf("..", "..", "..", "..", "I0", "P0", "P0", ".."),
                arrayOf("..", "..", "..", "..", "P0", "X0", "P0", ".."),
                arrayOf("..", "..", "..", "..", "P0", "P0", "P0", ".."),
                arrayOf("..", "..", "..", "..", "..", "..", "..", "..")
            ),
            startX = 1, startY = 1,
            hint = "Rotate to break the connection!",
            impossibleLinks = listOf(
                ImpossibleLink(requiredRotation = 0, fromCol = 3, fromRow = 3, toCol = 4, toRow = 4),
                ImpossibleLink(requiredRotation = 0, fromCol = 4, fromRow = 4, toCol = 3, toRow = 3)
            )
        ))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  THEME
// ═════════════════════════════════════════════════════════════════════════════

object TileRegistry {
    private val types = mutableMapOf<Char, TileType>()
    fun register(type: TileType) { types[type.charCode] = type }
    fun get(char: Char): TileType? = types[char]

    init {
        register(BasicTile('G', Theme.grassTop,  Theme.grassL,  Theme.grassR))
        register(BasicTile('S', Theme.sandTop,   Theme.sandL,   Theme.sandR))
        register(BasicTile('P', Theme.stoneTop,  Theme.stoneL,  Theme.stoneR))
        register(BasicTile('B', Theme.grassTop,  Theme.grassL,  Theme.grassR, isWalkable = false))
        register(BasicTile('X', Theme.stoneTop,  Theme.stoneL,  Theme.stoneR))
        register(BasicTile('~', Theme.builtTop,  Theme.builtL,  Theme.builtR))

        // ── Impossible tile 'I' ───────────────────────────────────────────────
        // Purely visual.  Glows when the current rotation has an active link
        // at this cell.  tile.height is the REAL height — required rotation
        // is stored in ImpossibleLink, not here.
        register(object : TileType {
            override val charCode   = 'I'
            override val isWalkable = true  // walkability enforced by link presence

            override fun getTopColor(state: GameState, col: Int, row: Int): Int =
                if (LevelManager.currentLevel.isActiveLinkEndpoint(col, row, state.currentRotation))
                    Theme.impossibleActive
                else
                    Theme.impossibleDim

            override fun getLeftColor(state: GameState, col: Int, row: Int)  = Theme.stoneL
            override fun getRightColor(state: GameState, col: Int, row: Int) = Theme.stoneR
        })

        // ── Leaf tile 'F' ─────────────────────────────────────────────────────
        val LEAF_MAX_HITS = 3
        register(object : TileType {
            override val charCode   = 'F'
            override val isWalkable = true

            private fun alpha(state: GameState, col: Int, row: Int): Int {
                val hits = state.leafHits[Pair(col, row)] ?: 0
                return (255f * (1f - hits.toFloat() / LEAF_MAX_HITS)).toInt().coerceIn(60, 255)
            }
            private fun tinted(base: Int, state: GameState, col: Int, row: Int): Int {
                val a = alpha(state, col, row)
                return Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
            }
            override fun getTopColor  (state: GameState, col: Int, row: Int) = tinted(Theme.leafTop, state, col, row)
            override fun getLeftColor (state: GameState, col: Int, row: Int) = tinted(Theme.leafL,   state, col, row)
            override fun getRightColor(state: GameState, col: Int, row: Int) = tinted(Theme.leafR,   state, col, row)
        })

        register(object : BasicTile('T', Theme.teleportTop, Theme.teleportL, Theme.teleportR) {})

        register(object : BasicTile('K', Theme.barrierTop, Theme.barrierL, Theme.barrierR) {
            override fun getHeightOffset(state: GameState, col: Int, row: Int): Float =
                (sin(state.stateTime * 2f + (col + row)) + 1f) * 0.75f
        })

        class MovingTile(
            charCode: Char, top: Int, left: Int, right: Int,
            val axis: String, val speed: Float, val range: Float
        ) : BasicTile(charCode, top, left, right) {
            override fun getPositionOffset(state: GameState, col: Int, row: Int): Pair<Float, Float> {
                val offset = (sin(state.stateTime * speed) + 1f) * range
                return if (axis == "EW") Pair(offset, 0f) else Pair(0f, offset)
            }
        }
        register(MovingTile('1', Theme.movingEW1Top, Theme.movingEW1L, Theme.movingEW1R, "EW", 1.5f, 0.5f))
        register(MovingTile('2', Theme.movingEW2Top, Theme.movingEW2L, Theme.movingEW2R, "EW", 1.5f, 1.0f))
        register(MovingTile('3', Theme.movingNS1Top, Theme.movingNS1L, Theme.movingNS1R, "NS", 1.5f, 0.5f))
        register(MovingTile('4', Theme.movingNS2Top, Theme.movingNS2L, Theme.movingNS2R, "NS", 1.5f, 1.0f))

        register(object : BasicTile('W', Theme.switchOff, Theme.stoneL, Theme.stoneR) {
            override fun getTopColor(state: GameState, col: Int, row: Int) =
                if (state.isSwitchOn) Theme.switchOn else Theme.switchOff
        })

        register(object : BasicTile('D', Theme.doorTop, Theme.doorL, Theme.doorR) {})
    }
}

private object Theme {
    val skyTop       = Color.rgb(100, 155, 210); val skyBot       = Color.rgb(165, 200, 235)
    val grassTop     = Color.rgb( 95, 145,  68); val grassL       = Color.rgb( 68, 108,  46); val grassR       = Color.rgb( 55,  92,  36)
    val sandTop      = Color.rgb(200, 175, 120); val sandL        = Color.rgb(162, 138,  85); val sandR        = Color.rgb(142, 120,  68)
    val stoneTop     = Color.rgb(142, 136, 126); val stoneL       = Color.rgb(102,  98,  90); val stoneR       = Color.rgb( 86,  84,  78)
    val teleportTop  = Color.rgb(180, 100, 255); val teleportL    = Color.rgb(140,  70, 210); val teleportR    = Color.rgb(110,  50, 180)
    val barrierTop   = Color.rgb(220,  60,  60); val barrierL     = Color.rgb(170,  40,  40); val barrierR     = Color.rgb(130,  30,  30)
    val builtTop     = Color.rgb( 90, 160, 160); val builtL       = Color.rgb( 60, 120, 120); val builtR       = Color.rgb( 45,  95,  95)
    val leafTop      = Color.rgb(140, 180,  60); val leafL        = Color.rgb(100, 140,  40); val leafR        = Color.rgb( 80, 115,  30)
    val movingEW1Top = Color.rgb(100, 180, 255); val movingEW1L   = Color.rgb( 70, 140, 210); val movingEW1R   = Color.rgb( 50, 110, 180)
    val movingEW2Top = Color.rgb( 60, 120, 255); val movingEW2L   = Color.rgb( 40,  80, 210); val movingEW2R   = Color.rgb( 30,  60, 180)
    val movingNS1Top = Color.rgb(100, 255, 180); val movingNS1L   = Color.rgb( 70, 210, 140); val movingNS1R   = Color.rgb( 50, 180, 110)
    val movingNS2Top = Color.rgb( 60, 255, 120); val movingNS2L   = Color.rgb( 40, 210,  80); val movingNS2R   = Color.rgb( 30, 180,  60)
    val switchOff    = Color.rgb(100, 100, 100); val switchOn     = Color.rgb(100, 255, 100)
    val doorTop      = Color.rgb(150, 100,  50); val doorL        = Color.rgb(120,  80,  40); val doorR        = Color.rgb( 90,  60,  30)
    val blocker      = Color.rgb(185,  80,  65); val blockerL     = Color.rgb(140,  55,  42); val blockerR     = Color.rgb(118,  45,  35)
    val goal         = Color.rgb(255, 210,  50); val goalGlow     = Color.argb( 60, 255, 210,  50)
    val player       = Color.rgb( 50, 200, 205); val playerL      = Color.rgb( 35, 137, 150)
    val shadow       = Color.argb( 45,   0,   0,   0)
    val edge         = Color.argb( 48,   0,   0,   0)
    val impossibleActive = Color.rgb(255, 100, 255)
    val impossibleDim    = Color.rgb(100,  50, 100)
}

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 2 — GAME LOGIC
// ═════════════════════════════════════════════════════════════════════════════

class PhysicsWorld {
    val world = World<Body>()
    init { world.setGravity(Vector2(0.0, 0.0)) }

    fun build() {
        world.bodies.toList().forEach { world.removeBody(it) }
        val level = LevelManager.currentLevel
        for (r in level.grid.indices) for (c in level.grid[r].indices) {
            val t = level.grid[r][c]
            if (t != null && !t.isWalkable) addWallBody(c, r)
        }
    }

    fun addWallBody(col: Int, row: Int) {
        val b = Body()
        b.addFixture(Geometry.createRectangle(0.95, 0.95))
        b.setMass(MassType.INFINITE)
        b.setAtRestDetectionEnabled(false)
        b.translate(col.toDouble(), row.toDouble())
        world.addBody(b)
    }

    fun step(dt: Double) { world.update(dt) }
}

data class BuiltTile(val height: Int)

class GameState {
    val physics = PhysicsWorld()

    var won          = false; private set
    var winTimer     = 0f;    private set
    var stateTime    = 0f;    private set

    var playerCol    = LevelManager.currentLevel.startX.toFloat(); private set
    var playerRow    = LevelManager.currentLevel.startY.toFloat(); private set
    var playerHeight = 0f;                                          private set

    var targetCol    = LevelManager.currentLevel.startX;           private set
    var targetRow    = LevelManager.currentLevel.startY;           private set
    var targetHeight = 0;                                           private set

    var prevTargetCol = LevelManager.currentLevel.startX;          private set
    var prevTargetRow = LevelManager.currentLevel.startY;          private set

    var isJumping    = false; private set
    var jumpProgress = 0f;    private set
    var isSwitchOn       = false; private set
    var doorOpenProgress = 0f;    private set

    val builtTiles = mutableMapOf<Pair<Int, Int>, BuiltTile>()
    var currentBuildAction: BuildAction = BuildAction.BUILD_TILE; private set
    val leafHits     = mutableMapOf<Pair<Int, Int>, Int>()
    val brokenLeaves = mutableSetOf<Pair<Int, Int>>()
    private var teleportLocked = false

    // Synced from the View layer each frame before move() is called.
    var currentRotation: Int = 0

    private val LERP_SPEED    = 8f
    private val JUMP_DURATION = 0.35f
    private val movingChars   = setOf('1', '2', '3', '4')

    init {
        physics.build()
        val startTile = LevelManager.currentLevel.tile(
            LevelManager.currentLevel.startX,
            LevelManager.currentLevel.startY
        )
        targetHeight = startTile?.height ?: 0
        playerHeight = targetHeight.toFloat()
    }

    data class CellInfo(val height: Int, val isWalkable: Boolean, val charCode: Char)

    fun resolveCell(col: Int, row: Int): CellInfo? {
        val key   = Pair(col, row)
        val built = builtTiles[key]
        if (built != null) return CellInfo(built.height, isWalkable = true, charCode = '~')
        val tile = LevelManager.currentLevel.tile(col, row) ?: return null
        val h    = tile.height + tile.type.getHeightOffset(this, col, row).toInt()
        return CellInfo(h, tile.isWalkable, tile.type.charCode)
    }

    fun executeBuildAction(dc: Int, dr: Int) {
        when (currentBuildAction) {
            BuildAction.BUILD_TILE  -> buildTile(dc, dr)
            BuildAction.REMOVE_TILE -> removeTile(dc, dr)
        }
    }

    fun cycleAction() {
        val values = BuildAction.entries
        currentBuildAction = values[(currentBuildAction.ordinal + 1) % values.size]
    }

    private fun buildTile(dc: Int, dr: Int) {
        val nc    = targetCol + dc
        val nr    = targetRow + dr
        val level = LevelManager.currentLevel
        if (!level.isInBounds(nc, nr)) return
        val playerH       = targetHeight
        val key           = Pair(nc, nr)
        val existingBuilt = builtTiles[key]
        val levelTile     = level.tile(nc, nr)
        val currentH: Int? = when {
            existingBuilt != null -> existingBuilt.height
            levelTile     != null -> levelTile.height
            else                  -> null
        }
        val newH: Int = when {
            currentH == null    -> playerH
            currentH < playerH  -> playerH
            currentH == playerH -> playerH + 1
            else                -> return
        }
        builtTiles[key] = BuiltTile(newH)
    }

    private fun removeTile(dc: Int, dr: Int) {
        val nc  = targetCol + dc
        val nr  = targetRow + dr
        val key = Pair(nc, nr)
        val built = builtTiles[key] ?: return
        if (abs(built.height - targetHeight) > 1) return
        builtTiles.remove(key)
    }

    fun move(dc: Int, dr: Int, shouldJump: Boolean = false) {
    if (won || isJumping) return

    var nc        = targetCol + dc
    var nr        = targetRow + dr
    var destBuilt = builtTiles[Pair(nc, nr)]
    var destLevel = LevelManager.currentLevel.tile(nc, nr)

    // ── IMPOSSIBLE LINK CHECK ─────────────────────────────────────────────
    // If the normal step lands in the void, check if the current cell has an
    // active link at this rotation.  If so, snap destination to the partner.
    // The visual projection already placed the partner tile visually adjacent,
    // so the player is simply stepping onto what they can see.
    if (destBuilt == null && destLevel == null) {
        val link = LevelManager.currentLevel.linksFrom(targetCol, targetRow)
            .firstOrNull { it.requiredRotation == currentRotation }
        if (link != null) {
            nc        = link.toCol
            nr        = link.toRow
            destBuilt = builtTiles[Pair(nc, nr)]
            destLevel = LevelManager.currentLevel.tile(nc, nr)
            // Impossible link — bypass all height checks, just move there
            prevTargetCol = targetCol; prevTargetRow = targetRow
            targetCol    = nc
            targetRow    = nr
            targetHeight = destLevel?.height ?: targetHeight
            return
        }
    }

    // ── STANDARD MOVEMENT GUARDS ──────────────────────────────────────────
    if (destBuilt == null && destLevel == null) return
    if (brokenLeaves.contains(Pair(nc, nr))) return
    if (destBuilt == null && destLevel?.type?.charCode == 'D' && doorOpenProgress < 0.9f) return
    if (destBuilt == null && destLevel?.isWalkable == false) return
    if (destBuilt == null && destLevel?.type?.charCode == 'F') hitLeaf(nc, nr)

    val currentH = targetHeight.toFloat()
    val destH: Float = when {
        destBuilt != null -> destBuilt.height.toFloat()
        else -> destLevel!!.height.toFloat() + destLevel.type.getHeightOffset(this, nc, nr)
    }

    val heightDiff = destH - currentH

    // ── BARRIER HEIGHT LIMITS ─────────────────────────────────────────────
    if (destBuilt == null && destLevel?.type?.charCode == 'K') {
        if (heightDiff > 1.2f && !shouldJump) return
        if (heightDiff > 2.0f &&  shouldJump) return
    }

    // ── COMMIT MOVE ───────────────────────────────────────────────────────
    val newTargetH = destH.toInt()

    when {
        abs(heightDiff) < 0.2f -> {
            prevTargetCol = targetCol; prevTargetRow = targetRow
            targetCol = nc; targetRow = nr; targetHeight = newTargetH
            if (shouldJump) { isJumping = true; jumpProgress = 0f }
        }
        heightDiff > 0f && heightDiff <= 1.2f && shouldJump -> {
            prevTargetCol = targetCol; prevTargetRow = targetRow
            targetCol = nc; targetRow = nr; targetHeight = newTargetH
            isJumping = true; jumpProgress = 0f
        }
        heightDiff < 0f -> {
            prevTargetCol = targetCol; prevTargetRow = targetRow
            targetCol = nc; targetRow = nr; targetHeight = newTargetH
            if (shouldJump || heightDiff < -1.2f) { isJumping = true; jumpProgress = 0f }
        }
    }
}

    fun update(dt: Float) {
        stateTime += dt
        if (won) { winTimer += dt; return }
        physics.step(dt.toDouble())
        doorOpenProgress = if (isSwitchOn)
            (doorOpenProgress + dt * 2f).coerceAtMost(1f)
        else
            (doorOpenProgress - dt * 2f).coerceAtLeast(0f)
        if (isJumping) updateJump(dt) else updateLerp(dt)
    }

    private fun resolveHeightAt(col: Int, row: Int): Float {
        val built = builtTiles[Pair(col, row)]
        if (built != null) return built.height.toFloat()
        val tile = LevelManager.currentLevel.tile(col, row) ?: return 0f
        return if (tile.type.charCode == 'K')
            tile.height.toFloat() + tile.type.getHeightOffset(this, col, row)
        else
            tile.height.toFloat()
    }

    private fun updateJump(dt: Float) {
        jumpProgress += dt / JUMP_DURATION
        if (jumpProgress >= 1f) {
            jumpProgress = 1f; isJumping = false
            playerHeight = resolveHeightAt(targetCol, targetRow)
            onArrived()
        } else {
            playerCol    = prevTargetCol + (targetCol - prevTargetCol) * jumpProgress
            playerRow    = prevTargetRow + (targetRow - prevTargetRow) * jumpProgress
            val startH   = resolveHeightAt(prevTargetCol, prevTargetRow)
            val endH     = resolveHeightAt(targetCol, targetRow)
            val arc      = sin(jumpProgress * PI.toFloat()) * 0.8f
            playerHeight = startH + (endH - startH) * jumpProgress + arc
        }
    }

    private fun updateLerp(dt: Float) {
        val level       = LevelManager.currentLevel
        val currentTile = level.tile(targetCol, targetRow)
        val isMoving    = currentTile?.type?.charCode in movingChars

        val finalTc: Float
        val finalTr: Float
        if (isMoving && currentTile != null) {
            val off = currentTile.type.getPositionOffset(this, targetCol, targetRow)
            finalTc = targetCol + off.first
            finalTr = targetRow + off.second
        } else {
            finalTc = targetCol.toFloat()
            finalTr = targetRow.toFloat()
        }

        val th     = resolveHeightAt(targetCol, targetRow)
        val ddx    = finalTc - playerCol
        val ddy    = finalTr - playerRow
        val ddh    = th      - playerHeight
        val distSq = ddx * ddx + ddy * ddy + ddh * ddh

        if (distSq > 0.0001f) {
            val step  = (LERP_SPEED * dt).coerceAtMost(1f)
            playerCol    += ddx * step
            playerRow    += ddy * step
            playerHeight += ddh * step
        } else {
            playerCol     = finalTc
            playerRow     = finalTr
            playerHeight  = th
            prevTargetCol = targetCol
            prevTargetRow = targetRow
            onArrived()
        }
    }

    private fun onArrived() {
        checkTeleport()
        checkSwitch()
        checkWin()
    }

    private val LEAF_MAX_HITS = 3

    fun hitLeaf(col: Int, row: Int) {
        val key  = Pair(col, row)
        val hits = (leafHits[key] ?: 0) + 1
        if (hits >= LEAF_MAX_HITS) { leafHits.remove(key); brokenLeaves.add(key) }
        else leafHits[key] = hits
    }

    private fun checkWin() {
        if (targetCol == LevelManager.currentLevel.goalX &&
            targetRow == LevelManager.currentLevel.goalY) {
            val goalTile = LevelManager.currentLevel.tile(targetCol, targetRow)
            if (abs(playerHeight - (goalTile?.height ?: 0)) < 0.5f) won = true
        }
    }

    private fun checkTeleport(): Boolean {
        val currentTile = LevelManager.currentLevel.tile(targetCol, targetRow)
        if (currentTile?.type?.charCode != 'T') { teleportLocked = false; return false }
        if (teleportLocked) return false
        val other = LevelManager.currentLevel.findOtherTeleport(targetCol, targetRow) ?: return false
        targetCol     = other.first;  targetRow     = other.second
        prevTargetCol = other.first;  prevTargetRow = other.second
        targetHeight  = LevelManager.currentLevel.tile(targetCol, targetRow)?.height ?: 0
        playerCol    = targetCol.toFloat(); playerRow    = targetRow.toFloat()
        playerHeight = targetHeight.toFloat()
        isJumping    = false; jumpProgress = 0f; teleportLocked = true
        return true
    }

    private fun checkSwitch() {
        val currentTile = LevelManager.currentLevel.tile(targetCol, targetRow)
        if (currentTile?.type?.charCode == 'W') isSwitchOn = true
    }

    fun reset() {
        won = false; winTimer = 0f; stateTime = 0f
        isSwitchOn = false; doorOpenProgress = 0f
        teleportLocked = false
        isJumping = false; jumpProgress = 0f
        targetCol     = LevelManager.currentLevel.startX
        targetRow     = LevelManager.currentLevel.startY
        prevTargetCol = LevelManager.currentLevel.startX
        prevTargetRow = LevelManager.currentLevel.startY
        playerCol     = LevelManager.currentLevel.startX.toFloat()
        playerRow     = LevelManager.currentLevel.startY.toFloat()
        val startTile = LevelManager.currentLevel.tile(
            LevelManager.currentLevel.startX,
            LevelManager.currentLevel.startY
        )
        targetHeight = startTile?.height ?: 0
        playerHeight = targetHeight.toFloat()
        builtTiles.clear()
        leafHits.clear()
        brokenLeaves.clear()
        physics.build()
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  DRAW CALL QUEUE
// ═════════════════════════════════════════════════════════════════════════════

fun interface DrawAction { fun draw() }

class DrawCallQueue(capacity: Int = 512) {
    private val keys = FloatArray(capacity)
    private val fns  = arrayOfNulls<DrawAction>(capacity)
    private var size = 0

    fun add(key: Float, fn: DrawAction) {
        if (size >= keys.size) return
        keys[size] = key; fns[size] = fn; size++
    }

    fun flush() {
        for (i in 1 until size) {
            val k = keys[i]; val f = fns[i]!!; var j = i - 1
            while (j >= 0 && keys[j] > k) { keys[j + 1] = keys[j]; fns[j + 1] = fns[j]; j-- }
            keys[j + 1] = k; fns[j + 1] = f
        }
        for (i in 0 until size) { fns[i]!!.draw(); fns[i] = null }
        size = 0
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 4 — RENDERER
// ═════════════════════════════════════════════════════════════════════════════

class IsoRenderer {
    private val bgP      = Paint().apply { style = Paint.Style.FILL }
    private val fillP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val topP     = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val leftP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val rightP   = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val edgeP    = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f;   isAntiAlias = true; color = Theme.edge }
    private val objTP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val objLP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val objRP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val shadowP  = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true; color = Theme.shadow }
    private val glowP    = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true; color = Theme.goalGlow }
    private val playerTP = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val playerLP = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true; color = Theme.playerL }
    private val playerRP = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true; color = Theme.playerL }
    private val playerEP = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f;   isAntiAlias = true; color = Theme.playerL }
    private val tdEdgeP  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f;   isAntiAlias = true; color = Color.argb(80, 0, 0, 0) }
    private val hudP     = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val hudSP    = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val overlayP = Paint().apply { style = Paint.Style.FILL }
    private val bFillP   = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val bEdgeP   = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
    private val bTextP   = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val rippleP  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f;   isAntiAlias = true }

    private val topPath = Path()
    private val sideL   = Path()
    private val sideR   = Path()
    private val queue   = DrawCallQueue(512)

    private var cachedSky: LinearGradient? = null
    private var cachedSkyHeight = 0f
    private var cachedPlayerGrad: RadialGradient? = null
    private var cachedPx = Float.MAX_VALUE
    private var cachedPy = Float.MAX_VALUE

    private inner class TileSlot {
        var canvas: Canvas? = null; var cx = 0f; var cy = 0f
        var cTop = 0; var cL = 0; var cR = 0
        var isDoor = false; var doorProg = 0f
        val fn = DrawAction {
            if (isDoor) drawIsoDoor(canvas!!, cx, cy, doorProg)
            else        drawIsoTile(canvas!!, cx, cy, cTop, cL, cR)
        }
    }
    private inner class ObjSlot {
        var canvas: Canvas? = null; var cx = 0f; var cy = 0f
        var obj = ObjectType.NONE; var t = 0f
        val fn = DrawAction { drawIsoObj(canvas!!, cx, cy, obj, t) }
    }
    private inner class PlayerSlot {
        var canvas: Canvas? = null; var cx = 0f; var cy = 0f; var iso = true
        val fn = DrawAction {
            if (iso) drawIsoPlayer(canvas!!, cx, cy) else drawTdPlayer(canvas!!, cx, cy)
        }
    }

    private val tilePool   = Array(600) { TileSlot()  }
    private val objPool    = Array(64)  { ObjSlot()   }
    private val playerSlot = PlayerSlot()
    private var ti = 0; private var oi = 0

    fun renderWorld(
        canvas: Canvas, state: GameState,
        ox: Float, oy: Float, sw: Float, sh: Float,
        t: Float, isIso: Boolean, rotation: Int
    ) {
        ti = 0; oi = 0
        if (isIso) {
            drawSky(canvas, sw, sh)
            renderLevelTiles(canvas, state, ox, oy, t, rotation)
            renderBuiltTiles(canvas, state, ox, oy, rotation)
            renderPlayer(canvas, state, ox, oy, rotation)
            queue.flush()
        } else {
            renderTopDown(canvas, state, ox, oy)
        }
    }

    fun renderHUD(
        canvas: Canvas, sw: Float, sh: Float,
        won: Boolean, winTimer: Float, hint: String,
        btnView: RectF, btnRotL: RectF, btnRotR: RectF,
        isIso: Boolean, rippleX: Float, rippleY: Float, rippleT: Float,
        btnAction: RectF, currentActionLabel: String,
        btnZoomIn: RectF, btnZoomOut: RectF,
        currentLevelIndex: Int, btnNextLevel: RectF
    ) {
        if (rippleT in 0f..0.4f) {
            val prog = rippleT / 0.4f
            rippleP.color = Color.argb(((1f - prog) * 80).toInt(), 255, 255, 255)
            canvas.drawCircle(rippleX, rippleY, prog * 44f, rippleP)
        }
        hudSP.textSize = 13f
        hudSP.color    = Color.argb(150, 255, 255, 255)
        hudSP.setShadowLayer(3f, 0f, 1f, Color.argb(70, 0, 0, 0))
        canvas.drawText(hint, sw / 2f, sh - 18f, hudSP)

        fun btn(r: RectF, lbl: String, highlight: Boolean = false) {
            bFillP.color = if (highlight) Color.argb(160, 255, 200, 0) else Color.argb(100, 0, 0, 0)
            canvas.drawRoundRect(r, 10f, 10f, bFillP)
            bEdgeP.color = Color.argb(80, 255, 255, 255)
            canvas.drawRoundRect(r, 10f, 10f, bEdgeP)
            bTextP.textSize = 14f
            bTextP.color    = if (highlight) Color.argb(220, 0, 0, 0) else Color.argb(200, 255, 255, 255)
            canvas.drawText(lbl, r.centerX(), r.centerY() + 7f, bTextP)
        }

        btn(btnView,      if (isIso) "TOP" else "ISO", !isIso)
        btn(btnRotL,      "↺")
        btn(btnRotR,      "↻")
        btn(btnAction,    currentActionLabel, highlight = true)
        btn(btnZoomIn,    "+")
        btn(btnZoomOut,   "-")
        btn(btnNextLevel, "Next Lvl")

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
                    hudSP.textSize = sw * 0.028f
                    hudSP.color    = Color.argb(175, 255, 255, 255)
                    canvas.drawText("Tap anywhere to restart", sw / 2f, sh / 2f + 94f, hudSP)
                }
            }
        }
    }

    private fun drawSky(canvas: Canvas, sw: Float, sh: Float) {
        if (sh != cachedSkyHeight) {
            cachedSky       = LinearGradient(0f, 0f, 0f, sh, intArrayOf(Theme.skyTop, Theme.skyBot), null, Shader.TileMode.CLAMP)
            cachedSkyHeight = sh
        }
        bgP.shader = cachedSky
        canvas.drawRect(0f, 0f, sw, sh, bgP)
    }

    private fun rotatedCoords(c: Float, r: Float, rotation: Int): Pair<Float, Float> {
        val level = LevelManager.currentLevel
        return when (rotation % 4) {
            1    -> Pair(r, (level.cols - 1).toFloat() - c)
            2    -> Pair((level.cols - 1).toFloat() - c, (level.rows - 1).toFloat() - r)
            3    -> Pair((level.rows - 1).toFloat() - r, c)
            else -> Pair(c, r)
        }
    }

    private fun enqueueTile(
        canvas: Canvas, rc: Float, rr: Float, h: Float,
        cTop: Int, cL: Int, cR: Int,
        isDoor: Boolean, doorProg: Float,
        ox: Float, oy: Float
    ) {
        if (ti >= tilePool.size) return
        val s = tilePool[ti++]
        s.canvas = canvas; s.cx = isoX(ox, rc, rr); s.cy = isoY(oy, rc, rr, h)
        s.cTop = cTop; s.cL = cL; s.cR = cR
        s.isDoor = isDoor; s.doorProg = doorProg
        queue.add(rc + rr + h * 0.01f, s.fn)
    }

    private fun renderLevelTiles(canvas: Canvas, state: GameState, ox: Float, oy: Float, t: Float, rotation: Int) {
        val level = LevelManager.currentLevel
        for (r in 0 until level.rows) for (c in 0 until level.cols) {
            val tile = level.tile(c, r) ?: continue
            if (state.brokenLeaves.contains(Pair(c, r))) continue
            val h = tile.height.toFloat() + tile.type.getHeightOffset(state, c, r)
            var (rc, rr) = rotatedCoords(c.toFloat(), r.toFloat(), rotation)
            if (tile.type.charCode in setOf('1', '2', '3', '4')) {
                val off        = tile.type.getPositionOffset(state, c, r)
                val (mrc, mrr) = rotatedCoords(c + off.first, r + off.second, rotation)
                rc = mrc; rr = mrr
            }
            enqueueTile(
                canvas, rc, rr, h,
                tile.type.getTopColor(state, c, r),
                tile.type.getLeftColor(state, c, r),
                tile.type.getRightColor(state, c, r),
                isDoor   = tile.type.charCode == 'D',
                doorProg = state.doorOpenProgress,
                ox, oy
            )
            if (tile.obj != ObjectType.NONE && oi < objPool.size) {
                val cx = isoX(ox, rc + 0.5f, rr + 0.5f)
                val cy = isoY(oy, rc + 0.5f, rr + 0.5f, h)
                val s  = objPool[oi++]
                s.canvas = canvas; s.cx = cx; s.cy = cy; s.obj = tile.obj; s.t = t
                queue.add(rc + rr + h * 0.01f + 0.005f, s.fn)
            }
        }
    }

    private fun renderBuiltTiles(canvas: Canvas, state: GameState, ox: Float, oy: Float, rotation: Int) {
        val builtType = TileRegistry.get('~')!!
        for ((pos, built) in state.builtTiles) {
            val (c, r)   = pos
            val h        = built.height.toFloat()
            val (rc, rr) = rotatedCoords(c.toFloat(), r.toFloat(), rotation)
            enqueueTile(
                canvas, rc, rr, h,
                builtType.getTopColor(state, c, r),
                builtType.getLeftColor(state, c, r),
                builtType.getRightColor(state, c, r),
                isDoor = false, doorProg = 0f, ox, oy
            )
        }
    }

    private fun renderPlayer(canvas: Canvas, state: GameState, ox: Float, oy: Float, rotation: Int) {
        val (prc, prr)    = rotatedCoords(state.playerCol, state.playerRow, rotation)
        playerSlot.canvas = canvas; playerSlot.iso = true
        playerSlot.cx     = isoX(ox, prc + 0.5f, prr + 0.5f)
        playerSlot.cy     = isoY(oy, prc + 0.5f, prr + 0.5f, state.playerHeight)
        queue.add(prc + prr + 0.5f + state.playerHeight * 0.01f + 0.005f, playerSlot.fn)
    }

    private fun renderTopDown(canvas: Canvas, state: GameState, ox: Float, oy: Float) {
        val level     = LevelManager.currentLevel
        val builtType = TileRegistry.get('~')!!
        canvas.drawColor(Color.rgb(40, 45, 50))
        for (r in 0 until level.rows) for (c in 0 until level.cols) {
            val x     = tdX(ox, c.toFloat()); val y = tdY(oy, r.toFloat())
            val color = state.builtTiles[Pair(c, r)]?.let { builtType.getTopColor(state, c, r) }
                ?: level.tile(c, r)?.type?.getTopColor(state, c, r)
                ?: continue
            fillP.color = color
            canvas.drawRect(x, y, x + TILE, y + TILE, fillP)
            canvas.drawRect(x, y, x + TILE, y + TILE, tdEdgeP)
        }
        val px = tdX(ox, state.playerCol) + TILE * 0.5f
        val py = tdY(oy, state.playerRow) + TILE * 0.5f
        fillP.color = Theme.player
        canvas.drawCircle(px, py, TILE * 0.35f, fillP)
    }

    private fun drawIsoTile(canvas: Canvas, cx: Float, cy: Float, cTop: Int, cL: Int, cR: Int) {
        val tx = cx;           val ty = cy - HALF_H
        val rx = cx + HALF_W;  val ry = cy
        val bx = cx;           val by = cy + HALF_H
        val lx = cx - HALF_W;  val ly = cy
        topPath.reset(); topPath.moveTo(tx, ty); topPath.lineTo(rx, ry); topPath.lineTo(bx, by); topPath.lineTo(lx, ly); topPath.close()
        topP.color = cTop; canvas.drawPath(topPath, topP); canvas.drawPath(topPath, edgeP)
        sideL.reset(); sideL.moveTo(lx, ly); sideL.lineTo(bx, by); sideL.lineTo(bx, by + SIDE_H); sideL.lineTo(lx, ly + SIDE_H); sideL.close()
        leftP.color = cL; canvas.drawPath(sideL, leftP); canvas.drawPath(sideL, edgeP)
        sideR.reset(); sideR.moveTo(bx, by); sideR.lineTo(rx, ry); sideR.lineTo(rx, ry + SIDE_H); sideR.lineTo(bx, by + SIDE_H); sideR.close()
        rightP.color = cR; canvas.drawPath(sideR, rightP); canvas.drawPath(sideR, edgeP)
    }

    private fun drawIsoDoor(canvas: Canvas, cx: Float, cy: Float, prog: Float) {
        val h = 40f * (1f - prog)
        if (h <= 0f) return
        val tx = cx;           val ty = cy - HALF_H - h
        val rx = cx + HALF_W;  val ry = cy - h
        val bx = cx;           val by = cy + HALF_H - h
        val lx = cx - HALF_W;  val ly = cy - h
        topPath.reset(); topPath.moveTo(tx, ty); topPath.lineTo(rx, ry); topPath.lineTo(bx, by); topPath.lineTo(lx, ly); topPath.close()
        topP.color = Theme.doorTop; canvas.drawPath(topPath, topP); canvas.drawPath(topPath, edgeP)
        sideL.reset(); sideL.moveTo(lx, ly); sideL.lineTo(bx, by); sideL.lineTo(bx, by + h + SIDE_H); sideL.lineTo(lx, ly + h + SIDE_H); sideL.close()
        leftP.color = Theme.doorL; canvas.drawPath(sideL, leftP); canvas.drawPath(sideL, edgeP)
        sideR.reset(); sideR.moveTo(bx, by); sideR.lineTo(rx, ry); sideR.lineTo(rx, ry + h + SIDE_H); sideR.lineTo(bx, by + h + SIDE_H); sideR.close()
        rightP.color = Theme.doorR; canvas.drawPath(sideR, rightP); canvas.drawPath(sideR, edgeP)
    }

    private fun drawIsoObj(canvas: Canvas, cx: Float, cy: Float, obj: ObjectType, t: Float) {
        val base = cy - HALF_H
        when (obj) {
            ObjectType.BLOCKER -> {
                val h = 32f
                objTP.color = Theme.blocker
                topPath.reset(); topPath.moveTo(cx, base - h - HALF_H); topPath.lineTo(cx + HALF_W, base - h); topPath.lineTo(cx, base - h + HALF_H); topPath.lineTo(cx - HALF_W, base - h); topPath.close()
                canvas.drawPath(topPath, objTP); canvas.drawPath(topPath, edgeP)
                sideL.reset(); sideL.moveTo(cx - HALF_W, base - h); sideL.lineTo(cx, base - h + HALF_H); sideL.lineTo(cx, base + HALF_H); sideL.lineTo(cx - HALF_W, base); sideL.close()
                objLP.color = Theme.blockerL; canvas.drawPath(sideL, objLP); canvas.drawPath(sideL, edgeP)
                sideR.reset(); sideR.moveTo(cx, base - h + HALF_H); sideR.lineTo(cx + HALF_W, base - h); sideR.lineTo(cx + HALF_W, base); sideR.lineTo(cx, base + HALF_H); sideR.close()
                objRP.color = Theme.blockerR; canvas.drawPath(sideR, objRP); canvas.drawPath(sideR, edgeP)
            }
            ObjectType.GOAL -> {
                val pulse = 1f + 0.15f * sin(t * 3f)
                canvas.drawCircle(cx, base - 20f, 30f * pulse, glowP)
                val h = 28f * pulse
                topPath.reset(); topPath.moveTo(cx, base - h - 16f); topPath.lineTo(cx + 14f, base - h * 0.5f); topPath.lineTo(cx, base - 4f); topPath.lineTo(cx - 14f, base - h * 0.5f); topPath.close()
                objTP.color = Theme.goal; canvas.drawPath(topPath, objTP)
            }
            ObjectType.NONE -> Unit
        }
    }

    private fun drawIsoPlayer(canvas: Canvas, cx: Float, cy: Float) {
        val base   = cy - HALF_H
        val pw     = HALF_W * 0.55f
        val ph     = HALF_H * 0.55f
        val height = 28f
        canvas.drawOval(cx - pw * 1.1f, base + ph * 0.4f, cx + pw * 1.1f, base + ph * 0.9f, shadowP)
        if (abs(cx - cachedPx) > 3f || abs(cy - cachedPy) > 3f) {
            cachedPlayerGrad = RadialGradient(
                cx - pw * 0.25f, base - height * 0.7f, pw * 1.4f,
                intArrayOf(Color.rgb(90, 170, 255), Theme.player, Theme.playerL),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            cachedPx = cx; cachedPy = cy
        }
        playerTP.shader = cachedPlayerGrad
        topPath.reset(); topPath.moveTo(cx, base - height - ph); topPath.lineTo(cx + pw, base - height); topPath.lineTo(cx, base - height + ph); topPath.lineTo(cx - pw, base - height); topPath.close()
        canvas.drawPath(topPath, playerTP); canvas.drawPath(topPath, playerEP)
        sideL.reset(); sideL.moveTo(cx - pw, base - height); sideL.lineTo(cx, base - height + ph); sideL.lineTo(cx, base + ph); sideL.lineTo(cx - pw, base); sideL.close()
        canvas.drawPath(sideL, playerLP); canvas.drawPath(sideL, playerEP)
        sideR.reset(); sideR.moveTo(cx, base - height + ph); sideR.lineTo(cx + pw, base - height); sideR.lineTo(cx + pw, base); sideR.lineTo(cx, base + ph); sideR.close()
        canvas.drawPath(sideR, playerRP); canvas.drawPath(sideR, playerEP)
    }

    private fun drawTdPlayer(canvas: Canvas, cx: Float, cy: Float) {
        fillP.color = Theme.player
        canvas.drawCircle(cx, cy, TILE * 0.35f, fillP)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 5 — VIEW
// ═════════════════════════════════════════════════════════════════════════════

class GameInternalsView(context: Context, appContext: AppContext) :
    ExpoView(context, appContext), SurfaceHolder.Callback {

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
    private var surfaceW = 0f; private var surfaceH = 0f
    private val renderer = IsoRenderer()
    private val state    = GameState()
    private var isIso    = true
    private var rotation = 0

    @Volatile private var pendingDc:             Int     = 0
    @Volatile private var pendingDr:             Int     = 0
    @Volatile private var hasPending:            Boolean = false
    @Volatile private var pendingReset:          Boolean = false
    @Volatile private var pendingJump:           Boolean = false
    @Volatile private var pendingBuildDc:        Int     = 0
    @Volatile private var pendingBuildDr:        Int     = 0
    @Volatile private var hasPendingBuild:       Boolean = false
    @Volatile private var hasPendingCycleAction: Boolean = false
    @Volatile private var hasPendingNextLevel:   Boolean = false

    private var isoOx = 0f; private var isoOy = 0f
    private var tdOx  = 0f; private var tdOy  = 0f
    private fun ox() = if (isIso) isoOx else tdOx
    private fun oy() = if (isIso) isoOy else tdOy

    private var rippleX = 0f; private var rippleY = 0f; private var rippleT = -1f
    private val btnView      = RectF()
    private val btnRotL      = RectF()
    private val btnRotR      = RectF()
    private val btnAction    = RectF()
    private val btnZoomIn    = RectF()
    private val btnZoomOut   = RectF()
    private val btnNextLevel = RectF()
    private var touchWasButton = false  
    private var zoom = 1.0f

    private fun applyZoom(newZoom: Float) {
        zoom          = newZoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
        HALF_W        = BASE_HALF_W        * zoom
        HALF_H        = BASE_HALF_H        * zoom
        SIDE_H        = BASE_SIDE_H        * zoom
        TILE          = BASE_TILE          * zoom
        HEIGHT_OFFSET = BASE_HEIGHT_OFFSET * zoom
        recomputeOffsets()
    }

    private fun recomputeOffsets() {
        if (surfaceW == 0f) return
        val midC = (LevelManager.currentLevel.cols - 1) / 2f
        val midR = (LevelManager.currentLevel.rows - 1) / 2f
        isoOx = surfaceW * 0.50f - (midC - midR) * HALF_W
        isoOy = surfaceH * 0.42f - (midC + midR) * HALF_H
        tdOx  = (surfaceW - LevelManager.currentLevel.cols * TILE) * 0.5f
        tdOy  = (surfaceH - LevelManager.currentLevel.rows * TILE) * 0.45f
    }

    private var touchStartX     = 0f
    private var touchStartY     = 0f
    private val SWIPE_THRESHOLD = 40f
    private val LONG_PRESS_MS   = 500L
    private val uiHandler       = Handler(Looper.getMainLooper())
    private var longPressPosted = false
    private var longPressFired  = false
    private var longPressDc     = 0
    private var longPressDr     = 0

    private val longPressRunnable = Runnable {
        longPressPosted = false
        longPressFired  = true
        pendingBuildDc  = longPressDc
        pendingBuildDr  = longPressDr
        hasPendingBuild = true
    }

    init {
        setWillNotDraw(true); clipChildren = false
        addView(surfaceView)
        surfaceView.setOnTouchListener { _, ev -> handleTouch(ev); true }
    }

    override fun surfaceCreated(h: SurfaceHolder)   { surfaceReady = true;  startLoop() }
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stopLoop()  }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
       surfaceW = w.toFloat(); surfaceH = ht.toFloat()
        val bm  = 48f; val ubw = 156f; val ubh = 132f
        val abw = 240f; val zbw = 132f; val zbh = 132f; val gap = 24f
        btnView.set(surfaceW / 2 - 78f, bm, surfaceW / 2 + 78f, bm + ubh)
        btnRotL.set(bm, bm, bm + ubw, bm + ubh)
        btnRotR.set(bm + ubw + 24f, bm, bm + ubw * 2 + 24f, bm + ubh)
        btnAction.set(surfaceW - bm - abw, surfaceH - bm - ubh, surfaceW - bm, surfaceH - bm)
        btnZoomIn.set(bm,  surfaceH - bm - zbh * 2 - gap, bm + zbw, surfaceH - bm - zbh - gap)
        btnZoomOut.set(bm, surfaceH - bm - zbh,            bm + zbw, surfaceH - bm)
        btnNextLevel.set(surfaceW - bm - abw, bm, surfaceW - bm, bm + ubh)
        applyZoom(zoom)
    }

    private fun handleTouch(ev: MotionEvent) {
        val sx = ev.x; val sy = ev.y
        when (ev.actionMasked) {
           MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchStartX = sx; touchStartY = sy
                rippleX = sx; rippleY = sy; rippleT = 0f
                touchWasButton = false  // reset each touch
                if (btnView.contains(sx, sy))      { isIso = !isIso; touchWasButton = true; return }
                if (btnRotL.contains(sx, sy))      { rotation = (rotation + 3) % 4; touchWasButton = true; return }
                if (btnRotR.contains(sx, sy))      { rotation = (rotation + 1) % 4; touchWasButton = true; return }
                if (btnAction.contains(sx, sy))    { hasPendingCycleAction = true; touchWasButton = true; return }
                if (btnZoomIn.contains(sx, sy))    { applyZoom(zoom + ZOOM_STEP); touchWasButton = true; return }
                if (btnZoomOut.contains(sx, sy))   { applyZoom(zoom - ZOOM_STEP); touchWasButton = true; return }
                if (btnNextLevel.contains(sx, sy)) { hasPendingNextLevel = true; touchWasButton = true; return }
                if (state.won)                     { pendingReset = true; return }
                val (dc, dr) = tapDirection(sx, sy)
                val (wdc, wdr) = applyRotationToMove(dc, dr)
                longPressDc    = wdc; longPressDr = wdr
                longPressFired = false
                uiHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                longPressPosted = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = sx - touchStartX; val dy = sy - touchStartY
                if (longPressPosted && dx * dx + dy * dy > SWIPE_THRESHOLD * SWIPE_THRESHOLD)
                    cancelLongPressGesture()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressGesture()
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) return
                if (longPressFired) return
                if (touchWasButton) return  // don't move if finger started on a button
                val dx = sx - touchStartX; val dy = sy - touchStartY
                resolveMove(dx, dy, sqrt(dx * dx + dy * dy))
            }
        }
    }

    private fun cancelLongPressGesture() {
        if (longPressPosted) { uiHandler.removeCallbacks(longPressRunnable); longPressPosted = false }
    }

    // Returns a screen-space direction unit step (±1,0) or (0,±1) toward the
    // tapped cell relative to the player's rotated visual position.
    private fun tapDirection(sx: Float, sy: Float): Pair<Int, Int> {
        val tapCol: Float; val tapRow: Float
        if (isIso) {
            tapCol = isoScreenToCol(sx, sy, ox(), oy())
            tapRow = isoScreenToRow(sx, sy, ox(), oy())
        } else {
            tapCol = tdScreenToCol(sx, ox())
            tapRow = tdScreenToRow(sy, oy())
        }
        val (playerRotCol, playerRotRow) = rotatedCoords(state.playerCol, state.playerRow, rotation)
        val dCol = tapCol - playerRotCol
        val dRow = tapRow - playerRotRow
        return if (abs(dCol) >= abs(dRow))
            Pair(if (dCol >= 0f) 1 else -1, 0)
        else
            Pair(0, if (dRow >= 0f) 1 else -1)
    }

    private fun resolveMove(dx: Float, dy: Float, dist: Float) {
        val moveDc: Int; val moveDr: Int
        if (dist > SWIPE_THRESHOLD) {
            val swipeCol = (dx / HALF_W + dy / HALF_H) * 0.5f
            val swipeRow = (dy / HALF_H - dx / HALF_W) * 0.5f
            if (abs(swipeCol) > abs(swipeRow)) {
                moveDc = if (swipeCol > 0) 1 else -1; moveDr = 0
            } else {
                moveDc = 0; moveDr = if (swipeRow > 0) 1 else -1
            }
            pendingJump = true
        } else {
            val (dc, dr) = tapDirection(touchStartX, touchStartY)
            if (dc == 0 && dr == 0) return
            moveDc = dc; moveDr = dr
            pendingJump = false
        }
        // moveDc/moveDr are in rotated (screen) space — un-rotate to world space.
        val (finalDc, finalDr) = applyRotationToMove(moveDc, moveDr)
        pendingDc = finalDc; pendingDr = finalDr; hasPending = true
    }

    // ── Rotation helpers ──────────────────────────────────────────────────────

    private fun rotatedCoords(c: Float, r: Float, rotation: Int): Pair<Float, Float> {
        val level = LevelManager.currentLevel
        return when (rotation % 4) {
            1    -> Pair(r, (level.cols - 1).toFloat() - c)
            2    -> Pair((level.cols - 1).toFloat() - c, (level.rows - 1).toFloat() - r)
            3    -> Pair((level.rows - 1).toFloat() - r, c)
            else -> Pair(c, r)
        }
    }

    // Converts a screen-space directional step back to world space.
    private fun applyRotationToMove(dc: Int, dr: Int): Pair<Int, Int> =
        when (rotation % 4) {
            1    -> Pair(-dr,  dc)
            2    -> Pair(-dc, -dr)
            3    -> Pair( dr, -dc)
            else -> Pair( dc,  dr)
        }

    // ── Game loop ─────────────────────────────────────────────────────────────

    private fun startLoop() {
        if (isRunning) return
        isRunning   = true
        lastFrameMs = System.currentTimeMillis()
        gameThread  = Thread({
            while (isRunning) {
                if (!surfaceReady) { Thread.sleep(16); continue }
                val frameStart = System.currentTimeMillis()
                val dt = (frameStart - lastFrameMs).coerceAtMost(80).toDouble() / 1000.0
                lastFrameMs = frameStart
                update(dt.toFloat())
                draw()
                val sleep = targetFrameMs - (System.currentTimeMillis() - frameStart)
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }, "IsoLoop").also {
            it.priority = Thread.MAX_PRIORITY
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopLoop() {
        isRunning = false
        gameThread?.interrupt(); gameThread?.join(500); gameThread = null
    }

    private fun update(dt: Float) {
        if (pendingReset) {
            pendingReset = false; hasPending = false; hasPendingBuild = false
            state.reset(); return
        }
        if (hasPendingNextLevel) {
            hasPendingNextLevel = false
            if (LevelManager.loadNextLevel()) { state.reset(); recomputeOffsets() }
        }
        if (hasPendingCycleAction) { hasPendingCycleAction = false; state.cycleAction() }
        if (hasPendingBuild) {
            hasPendingBuild = false
            state.executeBuildAction(pendingBuildDc, pendingBuildDr)
        }
        // Sync rotation into state so move() and TileType.getTopColor() can read it.
        state.currentRotation = rotation
        if (hasPending) { hasPending = false; state.move(pendingDc, pendingDr, pendingJump) }
        if (rippleT >= 0f) { rippleT += dt; if (rippleT > 0.4f) rippleT = -1f }
        state.update(dt)
    }

    private fun draw() {
        val holder = surfaceView.holder; var canvas: Canvas? = null
        try {
            canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                holder.lockHardwareCanvas()
            else
                holder.lockCanvas()
            canvas ?: return
            renderer.renderWorld(canvas, state, ox(), oy(), surfaceW, surfaceH, state.stateTime, isIso, rotation)
            renderer.renderHUD(
                canvas, surfaceW, surfaceH,
                state.won, state.winTimer, LevelManager.currentLevel.hint,
                btnView, btnRotL, btnRotR, isIso,
                rippleX, rippleY, rippleT,
                btnAction, state.currentBuildAction.label,
                btnZoomIn, btnZoomOut,
                LevelManager.currentLevelIndex, btnNextLevel
            )
        } finally {
            canvas?.let { surfaceView.holder.unlockCanvasAndPost(it) }
        }
    }

    override fun onDetachedFromWindow() { stopLoop(); super.onDetachedFromWindow() }
    fun pause()  { isRunning = false }
    fun resume() { if (surfaceReady) startLoop() }
    fun reset()  { pendingReset = true }
}