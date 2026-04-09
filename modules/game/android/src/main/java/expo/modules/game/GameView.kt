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
//  Layer 1 — Grid data (LevelLayout/LevelManager): seed-only after init
//  Layer 2 — Game logic (GameState):     pure grid integers, no screen coords
//  Layer 3 — Projection:                 Iso and TopDown, toggled via HUD
//  Layer 4 — Rendering (IsoRenderer):    draws using Layer 3, reads Layer 1/2
//  Layer 5 — View (GameInternalsView):   SurfaceView lifecycle + touch input
//
//  VOXEL WORLD
//  ───────────
//  GameState owns a VoxelWorld: Map<Pair<Int,Int>, MutableMap<Int,Char>>
//    (col, row) → (height → tileChar)
//  Any height can have any tile; gaps are legal (floating tiles).
//  LevelLayout seeds the world at init; after that it is never read again.
//
//  PLAYER
//  ──────
//  Player is a 1-unit-tall cube. targetHeight is the bottom of that cube.
//  Movement: player can walk freely to same-height or lower tiles.
//  To move UP by exactly 1 tile the player MUST swipe (jump).
//  Steps > 1 tile up are always blocked regardless of jump.
//  Falls freely to highest reachable tile when stepping into lower terrain.
//
//  PLACE (long-press)
//  ──────────────────
//  Places a block at floor level in the adjacent cell:
//    • If the dest column is EMPTY → place at (targetHeight - 1)  [extend ground]
//    • If dest column has tiles    → place at (bottomHeight - 1)   [extend downward]
//  Costs 1 from inventory of selected type.
//
//  REMOVE
//  ──────
//  Removes the tile at targetHeight in the adjacent cell.
//  Refunds 1 to inventory.
//  If that was the bottom tile of the column, auto-spawns same char at
//  height-1 down to MIN_WORLD_DEPTH.
//
//  CUBE TILES
//  ──────────
//  BASE_SIDE_H == BASE_HALF_W * 2  so every tile face is square.
//
//  PLAYER CENTERING
//  ────────────────
//  Player is rendered at (col+0.5, row+0.5) so it sits in the tile centre.
//
//  ANIMATED TILES
//  ──────────────
//  TileType retains getHeightOffset() and getPositionOffset() exactly as
//  the original. These are applied in renderVoxels() and move() via
//  TileRegistry lookups on the stored char.
//
//  LEAF TILES (F)
//  ──────────────
//  leafHits / brokenLeaves live in GameState. When a leaf tile is broken it
//  is removed from the VoxelWorld entirely.
//
//  SPECIAL CELLS
//  ─────────────
//  Goals, blockers seeded from LevelLayout into GameState.specialCells.
//  Never modified by place/remove.
//
//  INVENTORY
//  ─────────
//  inventory: Map<Char,Int> seeded per level. Place costs 1, remove refunds 1.
//  currentPlaceTileChar selects which type to place.
//
//  THREADING
//  ─────────
//  UI thread writes @Volatile pending* fields.
//  Game loop thread drains them at top of update().
//  All VoxelWorld/GameState mutations happen on the game loop thread only.
//
//  MIN_WORLD_DEPTH = -3
//  ─────────────────────
//  Auto-generated floor tiles never go below this height.
// ═════════════════════════════════════════════════════════════════════════════

// ── Projection base constants (unscaled) ─────────────────────────────────────
private const val BASE_HALF_W        = 36f
private const val BASE_HALF_H        = 18f
// CUBE: side height = HALF_W * 2 so the vertical face is a perfect square
private const val BASE_SIDE_H        = BASE_HALF_W * 2f   // = 72f
private const val BASE_TILE          = 40f
private const val BASE_HEIGHT_OFFSET = BASE_SIDE_H         // match cube face height

private var HALF_W        = BASE_HALF_W
private var HALF_H        = BASE_HALF_H
private var SIDE_H        = BASE_SIDE_H
private var TILE          = BASE_TILE
private var HEIGHT_OFFSET = BASE_HEIGHT_OFFSET

private const val ZOOM_MIN  = 0.4f
private const val ZOOM_MAX  = 2.0f
private const val ZOOM_STEP = 0.15f

private const val MIN_WORLD_DEPTH = -3

// ── ISO projection ────────────────────────────────────────────────────────────
private fun isoX(ox: Float, col: Float, row: Float) =
    ox + (col - row) * HALF_W

private fun isoY(oy: Float, col: Float, row: Float, height: Float = 0f) =
    oy + (col + row) * HALF_H - height * HEIGHT_OFFSET

private fun isoScreenToCol(sx: Float, sy: Float, ox: Float, oy: Float) =
    ((sx - ox) / HALF_W + (sy - oy) / HALF_H) * 0.5f

private fun isoScreenToRow(sx: Float, sy: Float, ox: Float, oy: Float) =
    ((sy - oy) / HALF_H - (sx - ox) / HALF_W) * 0.5f

// ── TOP-DOWN projection ───────────────────────────────────────────────────────
private fun tdX(ox: Float, col: Float) = ox + col * TILE
private fun tdY(oy: Float, row: Float) = oy + row * TILE
private fun tdScreenToCol(sx: Float, ox: Float) = (sx - ox) / TILE
private fun tdScreenToRow(sy: Float, oy: Float) = (sy - oy) / TILE

// ═════════════════════════════════════════════════════════════════════════════
//  BUILD ACTIONS
// ═════════════════════════════════════════════════════════════════════════════

enum class BuildAction(val label: String) {
    BUILD_TILE("Place"),
    REMOVE_TILE("Remove"),
}

// Tile chars placeable by the player. Add a char here + register in TileRegistry.
val PLACEABLE_TILE_CHARS = listOf('G', 'S', 'P')

fun tileDisplayName(char: Char): String = when (char) {
    'G'  -> "Grass"
    'S'  -> "Sand"
    'P'  -> "Stone"
    else -> char.toString()
}

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 1 — TILE TYPES
// ═════════════════════════════════════════════════════════════════════════════

interface TileType {
    val charCode: Char
    val isWalkable: Boolean get() = true
    fun getTopColor(state: GameState, col: Int, row: Int): Int
    fun getLeftColor(state: GameState, col: Int, row: Int): Int
    fun getRightColor(state: GameState, col: Int, row: Int): Int
    // Vertical visual offset in tile units (e.g. bobbing barrier tile)
    fun getHeightOffset(state: GameState, col: Int, row: Int): Float = 0f
    // Horizontal position offset in grid units (e.g. moving platforms)
    fun getPositionOffset(state: GameState, col: Int, row: Int): Pair<Float, Float> = Pair(0f, 0f)
}

open class BasicTile(
    override val charCode: Char,
    private val topColor: Int,
    private val leftColor: Int,
    private val rightColor: Int,
    override val isWalkable: Boolean = true
) : TileType {
    override fun getTopColor(state: GameState, col: Int, row: Int)   = topColor
    override fun getLeftColor(state: GameState, col: Int, row: Int)  = leftColor
    override fun getRightColor(state: GameState, col: Int, row: Int) = rightColor
}

enum class ObjectType { BLOCKER, GOAL, NONE }

// ── LevelLayout — seed only ───────────────────────────────────────────────────
// Parsed once; never read again after GameState.initLevel().
// "G2" = grass top at height 2, stone fill 0..1.  ".." = void column.
class LevelLayout(
    val layout: Array<Array<String>>,
    val startX: Int  = 1,
    val startY: Int  = 1,
    val hint:   String = "Tap · Swipe to jump",
    val startInventory: Map<Char, Int> = mapOf('G' to 8, 'S' to 4, 'P' to 4)
) {
    val cols = layout[0].size
    val rows = layout.size

    data class SeedEntry(val col: Int, val row: Int, val height: Int, val char: Char)

    val seedEntries: List<SeedEntry> by lazy {
        val out = mutableListOf<SeedEntry>()
        for (r in layout.indices) for (c in layout[r].indices) {
            val code = layout[r][c]; if (code == "..") continue
            val char   = code[0]
            val height = code[1].digitToInt()
            for (h in 0..height) out.add(SeedEntry(c, r, h, if (h == height) char else 'P'))
        }
        out
    }

    val goalX: Int
    val goalY: Int
    val specialCells: Map<Pair<Int, Int>, ObjectType>

    init {
        val specials = mutableMapOf<Pair<Int, Int>, ObjectType>()
        var gx = startX; var gy = startY
        for (r in layout.indices) for (c in layout[r].indices) {
            val code = layout[r][c]; if (code == "..") continue
            when (code[0]) {
                'X' -> { gx = c; gy = r; specials[Pair(c, r)] = ObjectType.GOAL    }
                'B' -> {               specials[Pair(c, r)] = ObjectType.BLOCKER }
            }
        }
        goalX = gx; goalY = gy
        specialCells = specials
    }

    fun findOtherTeleport(currentC: Int, currentR: Int, voxel: VoxelWorld): Pair<Int, Int>? {
        for ((pos, col) in voxel.columns) {
            if (col.values.any { it == 'T' } &&
                (pos.first != currentC || pos.second != currentR)) return pos
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
            ),
            startInventory = mapOf('G' to 8, 'S' to 4, 'P' to 6)
        ))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  THEME
// ═════════════════════════════════════════════════════════════════════════════

object TileRegistry {
    private val types = mutableMapOf<Char, TileType>()
    fun register(t: TileType) { types[t.charCode] = t }
    fun get(c: Char): TileType? = types[c]

    init {
        register(BasicTile('G', Theme.grassTop,  Theme.grassL,  Theme.grassR))
        register(BasicTile('S', Theme.sandTop,   Theme.sandL,   Theme.sandR))
        register(BasicTile('P', Theme.stoneTop,  Theme.stoneL,  Theme.stoneR))
        register(BasicTile('B', Theme.grassTop,  Theme.grassL,  Theme.grassR, isWalkable = false))
        register(BasicTile('X', Theme.stoneTop,  Theme.stoneL,  Theme.stoneR))

        // Leaf tile — LEAF_MAX_HITS contacts then removed from VoxelWorld.
        // Alpha fades as hits accumulate.
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

        register(BasicTile('T', Theme.teleportTop, Theme.teleportL, Theme.teleportR))

        // Barrier tile — bobs vertically; requires jump for any upward step
        register(object : BasicTile('K', Theme.barrierTop, Theme.barrierL, Theme.barrierR) {
            override fun getHeightOffset(state: GameState, col: Int, row: Int): Float =
                (sin(state.stateTime * 2f + (col + row)) + 1f) * 0.75f
        })

        // Moving platform tiles — oscillate along an axis
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

        // Switch — top colour driven by state.isSwitchOn
        register(object : BasicTile('W', Theme.switchOff, Theme.stoneL, Theme.stoneR) {
            override fun getTopColor(state: GameState, col: Int, row: Int) =
                if (state.isSwitchOn) Theme.switchOn else Theme.switchOff
        })

        register(BasicTile('D', Theme.doorTop, Theme.doorL, Theme.doorR))
    }
}

private object Theme {
    val skyTop       = Color.rgb(100, 155, 210); val skyBot       = Color.rgb(165, 200, 235)
    val grassTop     = Color.rgb( 95, 145,  68); val grassL       = Color.rgb( 68, 108,  46); val grassR       = Color.rgb( 55,  92,  36)
    val sandTop      = Color.rgb(200, 175, 120); val sandL        = Color.rgb(162, 138,  85); val sandR        = Color.rgb(142, 120,  68)
    val stoneTop     = Color.rgb(142, 136, 126); val stoneL       = Color.rgb(102,  98,  90); val stoneR       = Color.rgb( 86,  84,  78)
    val teleportTop  = Color.rgb(180, 100, 255); val teleportL    = Color.rgb(140,  70, 210); val teleportR    = Color.rgb(110,  50, 180)
    val barrierTop   = Color.rgb(220,  60,  60); val barrierL     = Color.rgb(170,  40,  40); val barrierR     = Color.rgb(130,  30,  30)
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
}

// ═════════════════════════════════════════════════════════════════════════════
//  VOXEL WORLD
// ═════════════════════════════════════════════════════════════════════════════

class VoxelWorld {
    // (col, row) → (height → tileChar). Gaps are legal.
    val columns = mutableMapOf<Pair<Int, Int>, MutableMap<Int, Char>>()

    fun column(col: Int, row: Int): MutableMap<Int, Char> =
        columns.getOrPut(Pair(col, row)) { mutableMapOf() }

    fun tileAt(col: Int, row: Int, height: Int): Char? =
        columns[Pair(col, row)]?.get(height)

    fun setTile(col: Int, row: Int, height: Int, char: Char) {
        column(col, row)[height] = char
    }

    fun removeTile(col: Int, row: Int, height: Int): Char? {
        val c  = columns[Pair(col, row)] ?: return null
        val ch = c.remove(height)
        if (c.isEmpty()) columns.remove(Pair(col, row))
        return ch
    }

    fun topHeight(col: Int, row: Int): Int? =
        columns[Pair(col, row)]?.keys?.maxOrNull()

    fun bottomHeight(col: Int, row: Int): Int? =
        columns[Pair(col, row)]?.keys?.minOrNull()

    // Highest tile h where top surface (h + hOff + 1) <= playerHeight + 2
    fun reachableTopHeight(col: Int, row: Int, playerHeight: Int, state: GameState): Int? {
        val c = columns[Pair(col, row)] ?: return null
        return c.keys.filter { h ->
            val char = c[h] ?: return@filter false
            val hOff = TileRegistry.get(char)?.getHeightOffset(state, col, row) ?: 0f
            (h.toFloat() + hOff + 1f) <= playerHeight.toFloat() + 2f
        }.maxOrNull()
    }

    fun seed(level: LevelLayout) {
        columns.clear()
        for (e in level.seedEntries) setTile(e.col, e.row, e.height, e.char)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  LAYER 2 — GAME STATE
// ═════════════════════════════════════════════════════════════════════════════

class PhysicsWorld {
    val world = World<Body>()
    init { world.setGravity(Vector2(0.0, 0.0)) }

    fun build(voxel: VoxelWorld) {
        world.bodies.toList().forEach { world.removeBody(it) }
        for ((pos, colMap) in voxel.columns) {
            for ((h, char) in colMap) {
                val tileType = TileRegistry.get(char)
                if (tileType != null && !tileType.isWalkable) {
                    val b = Body()
                    b.addFixture(Geometry.createRectangle(0.95, 0.95))
                    b.setMass(MassType.INFINITE)
                    b.setAtRestDetectionEnabled(false)
                    b.translate(pos.first.toDouble(), pos.second.toDouble())
                    world.addBody(b)
                }
            }
        }
    }

    fun step(dt: Double) { world.update(dt) }
}

class GameState {
    val physics = PhysicsWorld()
    val voxel   = VoxelWorld()

    // Special cells seeded from level — never mutated by place/remove
    var specialCells: Map<Pair<Int, Int>, ObjectType> = emptyMap()
        private set

    var won       = false; private set
    var winTimer  = 0f;    private set
    var stateTime = 0f;    private set

    var isSwitchOn       = false; private set
    var doorOpenProgress = 0f;    private set

    // Player visual (lerped) — rendered at col+0.5, row+0.5 (tile centre)
    var playerCol    = 0f; private set
    var playerRow    = 0f; private set
    var playerHeight = 0f; private set   // bottom of 1-unit-tall player cube

    // Player logical target
    var targetCol    = 0;  private set
    var targetRow    = 0;  private set
    var targetHeight = 0;  private set   // bottom of cube at rest

    var prevTargetCol = 0; private set
    var prevTargetRow = 0; private set

    var isJumping    = false; private set
    var jumpProgress = 0f;    private set

    // Inventory
    val inventory = mutableMapOf<Char, Int>()
    var currentPlaceTileChar: Char  = PLACEABLE_TILE_CHARS.first(); private set
    var currentBuildAction: BuildAction = BuildAction.BUILD_TILE;   private set

    // Leaf tile state
    val leafHits     = mutableMapOf<Pair<Int, Int>, Int>()
    val brokenLeaves = mutableSetOf<Pair<Int, Int>>()

    private var teleportLocked = false

    private val LERP_SPEED    = 8f
    private val JUMP_DURATION = 0.35f
    private val LEAF_MAX_HITS = 3

    private val movingChars = setOf('1', '2', '3', '4')

    init { initLevel() }

    private fun initLevel() {
        val level = LevelManager.currentLevel
        voxel.seed(level)
        specialCells = level.specialCells

        targetCol     = level.startX;  targetRow     = level.startY
        prevTargetCol = level.startX;  prevTargetRow = level.startY

        val startTop = voxel.topHeight(level.startX, level.startY) ?: 0
        targetHeight = startTop + 1
        playerCol    = targetCol.toFloat()
        playerRow    = targetRow.toFloat()
        playerHeight = targetHeight.toFloat()

        leafHits.clear(); brokenLeaves.clear()
        teleportLocked = false; isJumping = false; jumpProgress = 0f
        won = false; winTimer = 0f; stateTime = 0f
        isSwitchOn = false; doorOpenProgress = 0f

        physics.build(voxel)
        seedInventory()
    }

    private fun seedInventory() {
        inventory.clear()
        for ((char, count) in LevelManager.currentLevel.startInventory) {
            if (char in PLACEABLE_TILE_CHARS) inventory[char] = count
        }
        for (char in PLACEABLE_TILE_CHARS) inventory.putIfAbsent(char, 0)
        currentPlaceTileChar = PLACEABLE_TILE_CHARS.firstOrNull { (inventory[it] ?: 0) > 0 }
            ?: PLACEABLE_TILE_CHARS.first()
    }

    fun selectTileType(char: Char)       { if (char in PLACEABLE_TILE_CHARS) currentPlaceTileChar = char }
    fun setAction(action: BuildAction)   { currentBuildAction = action }

    // ── Place / Remove ────────────────────────────────────────────────────────

    fun executeBuildAction(dc: Int, dr: Int) {
        when (currentBuildAction) {
            BuildAction.BUILD_TILE  -> placeTile(dc, dr)
            BuildAction.REMOVE_TILE -> removeTile(dc, dr)
        }
    }

    // Place at floor level of the adjacent cell:
    //   • Empty column  → place at (targetHeight - 1)  i.e. extend ground outward
    //   • Column exists → place at (bottomHeight - 1)  i.e. extend downward
    private fun placeTile(dc: Int, dr: Int) {
        val count = inventory[currentPlaceTileChar] ?: 0
        if (count <= 0) return
        val nc = targetCol + dc; val nr = targetRow + dr
        val level = LevelManager.currentLevel
        if (nc < 0 || nr < 0 || nc >= level.cols || nr >= level.rows) return
        if (nc == targetCol && nr == targetRow) return  // can't place under self

        val placeHeight = run {
            val bottom = voxel.bottomHeight(nc, nr)
            if (bottom == null) {
                // Empty column — place flush with player's standing surface
                targetHeight - 1
            } else {
                // Column exists — extend one below its current lowest tile
                bottom - 1
            }
        }

        // Don't place below world depth limit
        if (placeHeight < MIN_WORLD_DEPTH) return

        voxel.setTile(nc, nr, placeHeight, currentPlaceTileChar)
        inventory[currentPlaceTileChar] = count - 1
    }

    private fun removeTile(dc: Int, dr: Int) {
        val nc = targetCol + dc; val nr = targetRow + dr
        val level = LevelManager.currentLevel
        if (nc < 0 || nr < 0 || nc >= level.cols || nr >= level.rows) return

        val removed = voxel.removeTile(nc, nr, targetHeight) ?: return

        // Refund — non-placeable types refund as 'G'
        val refundChar = if (removed in PLACEABLE_TILE_CHARS) removed else 'G'
        inventory[refundChar] = (inventory[refundChar] ?: 0) + 1

        // Auto-regenerate floor if this was the bottom tile
        val bottom    = voxel.bottomHeight(nc, nr)
        val wasBottom = (bottom == null) || (targetHeight <= bottom)
        if (wasBottom) {
            val newBottom = targetHeight - 1
            if (newBottom >= MIN_WORLD_DEPTH) voxel.setTile(nc, nr, newBottom, removed)
        }
    }

    // ── Movement ──────────────────────────────────────────────────────────────
    //
    // Jump rules (simplified, cube-world feel):
    //   • Walking (tap, no jump): only allowed if dest is same height or LOWER.
    //     Steps DOWN of any amount are free (player falls).
    //   • Jumping (swipe):        allowed if dest is exactly 1 tile higher, or lower.
    //     Cannot jump MORE than 1 tile up.
    fun move(dc: Int, dr: Int, shouldJump: Boolean = false) {
        if (won || isJumping) return
        val nc = targetCol + dc; val nr = targetRow + dr
        val level = LevelManager.currentLevel
        if (nc < 0 || nr < 0 || nc >= level.cols || nr >= level.rows) return

        // Blocker special cells always block
        if (specialCells[Pair(nc, nr)] == ObjectType.BLOCKER) return

        val destTopH = voxel.reachableTopHeight(nc, nr, targetHeight, this) ?: return

        val destChar     = voxel.tileAt(nc, nr, destTopH)
        val tileType     = destChar?.let { TileRegistry.get(it) }
        val hOff         = tileType?.getHeightOffset(this, nc, nr) ?: 0f
        val destTopFloat = destTopH.toFloat() + hOff
        // heightDiff > 0 means dest surface is HIGHER than current player bottom
        val heightDiff   = destTopFloat - (targetHeight - 1).toFloat()

        // Door check
        if (destChar == 'D' && doorOpenProgress < 0.9f) return

        // ── Height gating ──────────────────────────────────────────────────────
        // Going UP: require a jump; only 1 tile up permitted.
        if (heightDiff > 0f) {
            if (!shouldJump) return          // must swipe to go up at all
            if (heightDiff > 1.0f) return    // can't jump more than 1 tile
        }

        // ── Barrier (K) extra check ────────────────────────────────────────────
        // K tiles bob; treat same as regular height rules above — no special case
        // needed beyond the existing height gating, but keep sanity cap.
        if (destChar == 'K' && heightDiff > 1.0f) return

        // Leaf tile contact — hit on step attempt
        if (destChar == 'F') hitLeaf(nc, nr)
        // Abort if leaf just broke and tile is now gone
        if (brokenLeaves.contains(Pair(nc, nr)) && voxel.tileAt(nc, nr, destTopH) == null) return

        val newBottom = (destTopFloat + 1f).toInt()

        prevTargetCol = targetCol; prevTargetRow = targetRow
        targetCol = nc; targetRow = nr; targetHeight = newBottom

        // Trigger jump animation when actually jumping upward OR for big drops
        val prevSurfaceH = voxel.topHeight(prevTargetCol, prevTargetRow)?.plus(1) ?: 0
        if (shouldJump || abs(newBottom - prevSurfaceH) > 1) {
            isJumping = true; jumpProgress = 0f
        }
    }

    // ── Leaf mechanic ─────────────────────────────────────────────────────────
    fun hitLeaf(col: Int, row: Int) {
        val key  = Pair(col, row)
        val hits = (leafHits[key] ?: 0) + 1
        if (hits >= LEAF_MAX_HITS) {
            leafHits.remove(key)
            brokenLeaves.add(key)
            // Remove all leaf tiles in this column
            voxel.columns[key]?.entries
                ?.filter { it.value == 'F' }
                ?.map { it.key }
                ?.forEach { h -> voxel.removeTile(col, row, h) }
        } else {
            leafHits[key] = hits
        }
    }

    // ── Update ────────────────────────────────────────────────────────────────
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

    // Bottom of player cube = top-of-stack + hOff + 1
    private fun resolvePlayerHeightAt(col: Int, row: Int): Float {
        val topH = voxel.topHeight(col, row) ?: return 0f
        val char = voxel.tileAt(col, row, topH) ?: return 0f
        val hOff = TileRegistry.get(char)?.getHeightOffset(this, col, row) ?: 0f
        return topH.toFloat() + hOff + 1f
    }

    private fun updateJump(dt: Float) {
        jumpProgress += dt / JUMP_DURATION
        if (jumpProgress >= 1f) {
            jumpProgress = 1f; isJumping = false
            playerCol    = targetCol.toFloat()
            playerRow    = targetRow.toFloat()
            playerHeight = resolvePlayerHeightAt(targetCol, targetRow)
            onArrived()
        } else {
            playerCol = prevTargetCol + (targetCol - prevTargetCol) * jumpProgress
            playerRow = prevTargetRow + (targetRow - prevTargetRow) * jumpProgress
            val startH = resolvePlayerHeightAt(prevTargetCol, prevTargetRow)
            val endH   = resolvePlayerHeightAt(targetCol,     targetRow)
            val arc    = sin(jumpProgress * PI.toFloat()) * 0.8f
            playerHeight = startH + (endH - startH) * jumpProgress + arc
        }
    }

    private fun updateLerp(dt: Float) {
        // If standing on a moving platform, ride it visually
        val topH    = voxel.topHeight(targetCol, targetRow)
        val topChar = topH?.let { voxel.tileAt(targetCol, targetRow, it) }
        val isMoving = topChar in movingChars

        val finalTc: Float
        val finalTr: Float
        if (isMoving && topChar != null && topH != null) {
            val off = TileRegistry.get(topChar)?.getPositionOffset(this, targetCol, targetRow)
                ?: Pair(0f, 0f)
            finalTc = targetCol + off.first
            finalTr = targetRow + off.second
        } else {
            finalTc = targetCol.toFloat()
            finalTr = targetRow.toFloat()
        }

        val th     = resolvePlayerHeightAt(targetCol, targetRow)
        val ddx    = finalTc - playerCol
        val ddy    = finalTr - playerRow
        val ddh    = th      - playerHeight
        val distSq = ddx * ddx + ddy * ddy + ddh * ddh

        if (distSq > 0.0001f) {
            val step = (LERP_SPEED * dt).coerceAtMost(1f)
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
        checkLeafUnderfoot()
        checkWin()
    }

    private fun checkWin() {
        val level = LevelManager.currentLevel
        if (targetCol == level.goalX && targetRow == level.goalY) won = true
    }

    private fun checkTeleport() {
        val topH = voxel.topHeight(targetCol, targetRow) ?: return
        val char = voxel.tileAt(targetCol, targetRow, topH) ?: return
        if (char != 'T') { teleportLocked = false; return }
        if (teleportLocked) return
        val other = LevelManager.currentLevel.findOtherTeleport(targetCol, targetRow, voxel)
            ?: return
        targetCol     = other.first;  targetRow     = other.second
        prevTargetCol = other.first;  prevTargetRow = other.second
        val otherTop  = voxel.topHeight(targetCol, targetRow) ?: 0
        targetHeight  = otherTop + 1
        playerCol     = targetCol.toFloat(); playerRow    = targetRow.toFloat()
        playerHeight  = targetHeight.toFloat()
        isJumping = false; jumpProgress = 0f; teleportLocked = true
    }

    private fun checkSwitch() {
        val topH = voxel.topHeight(targetCol, targetRow) ?: return
        val char = voxel.tileAt(targetCol, targetRow, topH) ?: return
        if (char == 'W') isSwitchOn = true
    }

    // Damage the leaf tile the player is standing on after fully arriving
    private fun checkLeafUnderfoot() {
        val standH = targetHeight - 1  // the tile top the player rests on
        val char   = voxel.tileAt(targetCol, targetRow, standH) ?: return
        if (char == 'F') hitLeaf(targetCol, targetRow)
    }

    fun reset() { initLevel() }
}

// ═════════════════════════════════════════════════════════════════════════════
//  DRAW CALL QUEUE  (insertion-sort — stable, allocation-free, fast for ≤2048)
// ═════════════════════════════════════════════════════════════════════════════

fun interface DrawAction { fun draw() }

class DrawCallQueue(capacity: Int = 2048) {
    private val keys = FloatArray(capacity)
    private val fns  = arrayOfNulls<DrawAction>(capacity)
    private var size = 0

    fun add(key: Float, fn: DrawAction) {
        if (size >= keys.size) return
        keys[size] = key; fns[size] = fn; size++
    }

    fun flush() {
        // Insertion sort — stable, O(n²) but n ≤ 2048 and nearly sorted in practice
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
//  LAYERS 3 & 4 — RENDERER
// ═════════════════════════════════════════════════════════════════════════════

class IsoRenderer {

    // ── Paints ────────────────────────────────────────────────────────────────
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
    private val trayBgP  = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val trayTxtP = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val trayCntP = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val traySelP = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f;   isAntiAlias = true; color = Color.rgb(255, 230, 50) }

    // ── Reusable paths ────────────────────────────────────────────────────────
    private val topPath = Path()
    private val sideL   = Path()
    private val sideR   = Path()
    private val queue   = DrawCallQueue(2048)

    // ── Caches ────────────────────────────────────────────────────────────────
    private var cachedSky: LinearGradient?    = null
    private var cachedSkyHeight               = 0f
    private var cachedPlayerGrad: RadialGradient? = null
    private var cachedPx = Float.MAX_VALUE
    private var cachedPy = Float.MAX_VALUE

    // ── Object-pool slots (avoid per-frame lambda allocation) ─────────────────
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
            if (iso) drawIsoPlayer(canvas!!, cx, cy)
            else     drawTdPlayer(canvas!!, cx, cy)
        }
    }

    private val tilePool   = Array(2048) { TileSlot()  }
    private val objPool    = Array(64)   { ObjSlot()   }
    private val playerSlot = PlayerSlot()
    private var ti = 0; private var oi = 0

    // ── Public entry points ───────────────────────────────────────────────────

    fun renderWorld(
        canvas: Canvas, state: GameState,
        ox: Float, oy: Float, sw: Float, sh: Float,
        t: Float, isIso: Boolean, rotation: Int
    ) {
        ti = 0; oi = 0
        if (isIso) {
            drawSky(canvas, sw, sh)
            renderVoxels(canvas, state, ox, oy, t, rotation)
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
        isIso: Boolean,
        rippleX: Float, rippleY: Float, rippleT: Float,
        btnAction: RectF, state: GameState,
        btnZoomIn: RectF, btnZoomOut: RectF,
        trayOpen: Boolean, trayRects: List<RectF>
    ) {
        if (rippleT in 0f..0.4f) {
            val prog = rippleT / 0.4f
            rippleP.color = Color.argb(((1f - prog) * 80).toInt(), 255, 255, 255)
            canvas.drawCircle(rippleX, rippleY, prog * 44f, rippleP)
        }

        hudSP.textSize = 13f; hudSP.color = Color.argb(150, 255, 255, 255)
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

        btn(btnView,    if (isIso) "TOP" else "ISO", !isIso)
        btn(btnRotL,    "↺")
        btn(btnRotR,    "↻")
        btn(btnZoomIn,  "+")
        btn(btnZoomOut, "-")

        val actionLabel = when (state.currentBuildAction) {
            BuildAction.BUILD_TILE  -> "▲ ${tileDisplayName(state.currentPlaceTileChar)}"
            BuildAction.REMOVE_TILE -> "✕ Remove"
        }
        btn(btnAction, actionLabel, highlight = true)

        if (trayOpen) renderTilePicker(canvas, state, trayRects)
        renderInventoryStrip(canvas, state, sw, sh)

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

    // ── Tile picker tray ──────────────────────────────────────────────────────

    fun renderTilePicker(canvas: Canvas, state: GameState, trayRects: List<RectF>) {
        val actionCount = BuildAction.entries.size
        trayRects.forEachIndexed { i, rect ->
            trayBgP.color = Color.argb(200, 20, 20, 30)
            canvas.drawRoundRect(rect, 12f, 12f, trayBgP)

            if (i < actionCount) {
                val action     = BuildAction.entries[i]
                val isSelected = state.currentBuildAction == action
                if (isSelected) canvas.drawRoundRect(rect, 12f, 12f, traySelP)
                trayTxtP.textSize = 15f
                trayTxtP.color    = if (isSelected) Color.rgb(255, 230, 50) else Color.WHITE
                canvas.drawText(action.label, rect.centerX(), rect.centerY() + 6f, trayTxtP)
            } else {
                val tileIndex = i - actionCount
                if (tileIndex >= PLACEABLE_TILE_CHARS.size) return@forEachIndexed
                val char      = PLACEABLE_TILE_CHARS[tileIndex]
                val tileType  = TileRegistry.get(char) ?: return@forEachIndexed
                val count     = state.inventory[char] ?: 0
                val isSelected = state.currentBuildAction == BuildAction.BUILD_TILE &&
                                 state.currentPlaceTileChar == char
                if (isSelected) canvas.drawRoundRect(rect, 12f, 12f, traySelP)

                // Mini iso tile preview — temporarily reduce scale vars
                val savedHW = HALF_W; val savedHH = HALF_H; val savedSH = SIDE_H
                HALF_W = 14f; HALF_H = 7f; SIDE_H = 28f   // keep cube proportions in preview
                drawIsoTile(
                    canvas, rect.left + rect.height() * 0.55f, rect.centerY() + 4f,
                    tileType.getTopColor(state, 0, 0),
                    tileType.getLeftColor(state, 0, 0),
                    tileType.getRightColor(state, 0, 0)
                )
                HALF_W = savedHW; HALF_H = savedHH; SIDE_H = savedSH

                trayTxtP.textSize = 13f
                trayTxtP.color    = if (count > 0) Color.WHITE else Color.argb(100, 255, 255, 255)
                canvas.drawText(tileDisplayName(char), rect.centerX() + 10f, rect.centerY() + 1f, trayTxtP)
                trayCntP.textSize = 11f
                trayCntP.color    = if (count > 0) Color.rgb(100, 255, 160) else Color.rgb(200, 80, 80)
                canvas.drawText("×$count", rect.centerX() + 10f, rect.centerY() + 16f, trayCntP)
            }
        }
    }

    // ── Inventory strip ───────────────────────────────────────────────────────
    private fun renderInventoryStrip(canvas: Canvas, state: GameState, sw: Float, sh: Float) {
        val cellW = 58f; val cellH = 36f
        val totalW = PLACEABLE_TILE_CHARS.size * cellW
        var x = (sw - totalW) / 2f
        val y = sh - 40f - cellH

        for (char in PLACEABLE_TILE_CHARS) {
            val count    = state.inventory[char] ?: 0
            val tileType = TileRegistry.get(char) ?: continue
            val isActive = state.currentBuildAction == BuildAction.BUILD_TILE &&
                           state.currentPlaceTileChar == char

            val rect = RectF(x, y, x + cellW - 4f, y + cellH)
            trayBgP.color = if (isActive) Color.argb(200, 60, 50, 0) else Color.argb(120, 0, 0, 0)
            canvas.drawRoundRect(rect, 8f, 8f, trayBgP)
            if (isActive) canvas.drawRoundRect(rect, 8f, 8f, traySelP)

            // Keep cube proportions in the inventory strip preview
            val savedHW = HALF_W; val savedHH = HALF_H; val savedSH = SIDE_H
            HALF_W = 10f; HALF_H = 5f; SIDE_H = 20f
            drawIsoTile(
                canvas, x + 14f, y + 16f,
                tileType.getTopColor(state, 0, 0),
                tileType.getLeftColor(state, 0, 0),
                tileType.getRightColor(state, 0, 0)
            )
            HALF_W = savedHW; HALF_H = savedHH; SIDE_H = savedSH

            trayCntP.textSize = 12f
            trayCntP.color = if (count > 0) Color.WHITE else Color.argb(80, 255, 255, 255)
            canvas.drawText("×$count", x + cellW * 0.65f, y + cellH * 0.65f, trayCntP)
            x += cellW
        }
    }

    // ── Private render helpers ────────────────────────────────────────────────

    private fun drawSky(canvas: Canvas, sw: Float, sh: Float) {
        if (sh != cachedSkyHeight) {
            cachedSky = LinearGradient(
                0f, 0f, 0f, sh,
                intArrayOf(Theme.skyTop, Theme.skyBot),
                null, Shader.TileMode.CLAMP
            )
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
        val tx = isoX(ox, rc, rr)
        val ty = isoY(oy, rc, rr, h)
        val s  = tilePool[ti++]
        s.canvas = canvas; s.cx = tx; s.cy = ty
        s.cTop = cTop; s.cL = cL; s.cR = cR
        s.isDoor = isDoor; s.doorProg = doorProg
        // Sort key: col+row positions give rough depth; height is fractional to
        // keep stacked tiles within the same cell in correct order
        queue.add(rc + rr + h * 0.001f, s.fn)
    }

    // ── Voxel renderer ────────────────────────────────────────────────────────

    private fun renderVoxels(
        canvas: Canvas, state: GameState,
        ox: Float, oy: Float, t: Float, rotation: Int
    ) {
        val level = LevelManager.currentLevel

        for ((pos, colMap) in state.voxel.columns) {
            val (c, r) = pos
            if (c < 0 || r < 0 || c >= level.cols || r >= level.rows) continue

            val special = state.specialCells[pos]
            val topH    = colMap.keys.maxOrNull()

            for ((h, char) in colMap) {
                val tileType = TileRegistry.get(char) ?: TileRegistry.get('G')!!
                val hOff     = tileType.getHeightOffset(state, c, r)
                val visualH  = h.toFloat() + hOff

                // Base iso coords, then apply moving-platform offset if any
                var (rc, rr) = rotatedCoords(c.toFloat(), r.toFloat(), rotation)
                val posOff   = tileType.getPositionOffset(state, c, r)
                if (posOff.first != 0f || posOff.second != 0f) {
                    val (mrc, mrr) = rotatedCoords(
                        c + posOff.first, r + posOff.second, rotation
                    )
                    rc = mrc; rr = mrr
                }

                enqueueTile(
                    canvas, rc, rr, visualH,
                    tileType.getTopColor(state, c, r),
                    tileType.getLeftColor(state, c, r),
                    tileType.getRightColor(state, c, r),
                    isDoor   = char == 'D',
                    doorProg = state.doorOpenProgress,
                    ox, oy
                )

                // Object overlay on the topmost tile of each column
                if (h == topH && special != null && special != ObjectType.NONE && oi < objPool.size) {
                    val cx = isoX(ox, rc + 0.5f, rr + 0.5f)
                    val cy = isoY(oy, rc + 0.5f, rr + 0.5f, visualH)
                    val s  = objPool[oi++]
                    s.canvas = canvas; s.cx = cx; s.cy = cy; s.obj = special; s.t = t
                    queue.add(rc + rr + visualH * 0.001f + 0.005f, s.fn)
                }
            }
        }
    }

    // Player is rendered at (col+0.5, row+0.5) so it sits in the centre of its tile
    private fun renderPlayer(
        canvas: Canvas, state: GameState,
        ox: Float, oy: Float, rotation: Int
    ) {
        // Rotate the logical (col, row) first, then add the 0.5 centering offset
        val (prc, prr) = rotatedCoords(state.playerCol + 0.5f, state.playerRow + 0.5f, rotation)
        playerSlot.canvas = canvas; playerSlot.iso = true
        playerSlot.cx = isoX(ox, prc, prr)
        playerSlot.cy = isoY(oy, prc, prr, state.playerHeight)
        // Depth key uses the centred coords
        queue.add(prc + prr + state.playerHeight * 0.001f + 0.005f, playerSlot.fn)
    }

    private fun renderTopDown(canvas: Canvas, state: GameState, ox: Float, oy: Float) {
        val level = LevelManager.currentLevel
        canvas.drawColor(Color.rgb(40, 45, 50))
        for ((pos, colMap) in state.voxel.columns) {
            val (c, r) = pos
            if (c < 0 || r < 0 || c >= level.cols || r >= level.rows) continue
            val topH     = colMap.keys.maxOrNull() ?: continue
            val char     = colMap[topH] ?: continue
            val tileType = TileRegistry.get(char) ?: continue
            val x = tdX(ox, c.toFloat()); val y = tdY(oy, r.toFloat())
            fillP.color = tileType.getTopColor(state, c, r)
            canvas.drawRect(x, y, x + TILE, y + TILE, fillP)
            canvas.drawRect(x, y, x + TILE, y + TILE, tdEdgeP)
        }
        // Top-down player also centred in its tile
        val px = tdX(ox, state.playerCol) + TILE * 0.5f
        val py = tdY(oy, state.playerRow) + TILE * 0.5f
        fillP.color = Theme.player
        canvas.drawCircle(px, py, TILE * 0.35f, fillP)
    }

    // ── Tile drawing primitives ───────────────────────────────────────────────
    //
    // CUBE: SIDE_H == HALF_W * 2 (set at top of file), so each visible face is
    // a perfect square (width = HALF_W*2, height = SIDE_H = HALF_W*2).

    private fun drawIsoTile(canvas: Canvas, cx: Float, cy: Float, cTop: Int, cL: Int, cR: Int) {
        val tx = cx;          val ty = cy - HALF_H
        val rx = cx + HALF_W; val ry = cy
        val bx = cx;          val by = cy + HALF_H
        val lx = cx - HALF_W; val ly = cy

        topPath.reset()
        topPath.moveTo(tx, ty); topPath.lineTo(rx, ry)
        topPath.lineTo(bx, by); topPath.lineTo(lx, ly); topPath.close()
        topP.color = cTop; canvas.drawPath(topPath, topP); canvas.drawPath(topPath, edgeP)

        sideL.reset()
        sideL.moveTo(lx, ly); sideL.lineTo(bx, by)
        sideL.lineTo(bx, by + SIDE_H); sideL.lineTo(lx, ly + SIDE_H); sideL.close()
        leftP.color = cL; canvas.drawPath(sideL, leftP); canvas.drawPath(sideL, edgeP)

        sideR.reset()
        sideR.moveTo(bx, by); sideR.lineTo(rx, ry)
        sideR.lineTo(rx, ry + SIDE_H); sideR.lineTo(bx, by + SIDE_H); sideR.close()
        rightP.color = cR; canvas.drawPath(sideR, rightP); canvas.drawPath(sideR, edgeP)
    }

    private fun drawIsoDoor(canvas: Canvas, cx: Float, cy: Float, prog: Float) {
        val h = SIDE_H * (1f - prog)
        if (h <= 0f) return

        val tx = cx;          val ty = cy - HALF_H - h
        val rx = cx + HALF_W; val ry = cy - h
        val bx = cx;          val by = cy + HALF_H - h
        val lx = cx - HALF_W; val ly = cy - h

        topPath.reset()
        topPath.moveTo(tx, ty); topPath.lineTo(rx, ry)
        topPath.lineTo(bx, by); topPath.lineTo(lx, ly); topPath.close()
        topP.color = Theme.doorTop; canvas.drawPath(topPath, topP); canvas.drawPath(topPath, edgeP)

        sideL.reset()
        sideL.moveTo(lx, ly); sideL.lineTo(bx, by)
        sideL.lineTo(bx, by + h + SIDE_H); sideL.lineTo(lx, ly + h + SIDE_H); sideL.close()
        leftP.color = Theme.doorL; canvas.drawPath(sideL, leftP); canvas.drawPath(sideL, edgeP)

        sideR.reset()
        sideR.moveTo(bx, by); sideR.lineTo(rx, ry)
        sideR.lineTo(rx, ry + h + SIDE_H); sideR.lineTo(bx, by + h + SIDE_H); sideR.close()
        rightP.color = Theme.doorR; canvas.drawPath(sideR, rightP); canvas.drawPath(sideR, edgeP)
    }

    private fun drawIsoObj(canvas: Canvas, cx: Float, cy: Float, obj: ObjectType, t: Float) {
        val base = cy - HALF_H
        when (obj) {
            ObjectType.BLOCKER -> {
                val h = SIDE_H
                objTP.color = Theme.blocker
                topPath.reset()
                topPath.moveTo(cx, base - h - HALF_H); topPath.lineTo(cx + HALF_W, base - h)
                topPath.lineTo(cx, base - h + HALF_H); topPath.lineTo(cx - HALF_W, base - h); topPath.close()
                canvas.drawPath(topPath, objTP); canvas.drawPath(topPath, edgeP)

                sideL.reset()
                sideL.moveTo(cx - HALF_W, base - h); sideL.lineTo(cx, base - h + HALF_H)
                sideL.lineTo(cx, base + HALF_H);     sideL.lineTo(cx - HALF_W, base); sideL.close()
                objLP.color = Theme.blockerL; canvas.drawPath(sideL, objLP); canvas.drawPath(sideL, edgeP)

                sideR.reset()
                sideR.moveTo(cx, base - h + HALF_H); sideR.lineTo(cx + HALF_W, base - h)
                sideR.lineTo(cx + HALF_W, base);     sideR.lineTo(cx, base + HALF_H); sideR.close()
                objRP.color = Theme.blockerR; canvas.drawPath(sideR, objRP); canvas.drawPath(sideR, edgeP)
            }
            ObjectType.GOAL -> {
                val pulse = 1f + 0.15f * sin(t * 3f)
                canvas.drawCircle(cx, base - 20f, 30f * pulse, glowP)
                val h = 28f * pulse
                topPath.reset()
                topPath.moveTo(cx, base - h - 16f); topPath.lineTo(cx + 14f, base - h * 0.5f)
                topPath.lineTo(cx, base - 4f);      topPath.lineTo(cx - 14f, base - h * 0.5f); topPath.close()
                objTP.color = Theme.goal; canvas.drawPath(topPath, objTP)
            }
            ObjectType.NONE -> Unit
        }
    }

    // Player is drawn as a 1-unit cube matching the tile dimensions exactly
    private fun drawIsoPlayer(canvas: Canvas, cx: Float, cy: Float) {
        val base   = cy - HALF_H
        // Scale the player cube to the same proportions as a tile but slightly smaller
        val scale  = 0.75f
        val pw     = HALF_W * scale
        val ph     = HALF_H * scale
        val ps     = SIDE_H * scale   // player cube side height = same ratio as tiles

        // Shadow ellipse under the player
        canvas.drawOval(cx - pw * 1.1f, base + ph * 0.4f, cx + pw * 1.1f, base + ph * 0.9f, shadowP)

        // Rebuild gradient only when player has moved meaningfully
        if (abs(cx - cachedPx) > 3f || abs(cy - cachedPy) > 3f) {
            cachedPlayerGrad = RadialGradient(
                cx - pw * 0.25f, base - ps * 0.7f, pw * 1.4f,
                intArrayOf(Color.rgb(90, 170, 255), Theme.player, Theme.playerL),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            cachedPx = cx; cachedPy = cy
        }
        playerTP.shader = cachedPlayerGrad

        // Top face
        topPath.reset()
        topPath.moveTo(cx,      base - ps - ph)
        topPath.lineTo(cx + pw, base - ps)
        topPath.lineTo(cx,      base - ps + ph)
        topPath.lineTo(cx - pw, base - ps)
        topPath.close()
        canvas.drawPath(topPath, playerTP); canvas.drawPath(topPath, playerEP)

        // Left face
        sideL.reset()
        sideL.moveTo(cx - pw, base - ps)
        sideL.lineTo(cx,      base - ps + ph)
        sideL.lineTo(cx,      base + ph)
        sideL.lineTo(cx - pw, base)
        sideL.close()
        canvas.drawPath(sideL, playerLP); canvas.drawPath(sideL, playerEP)

        // Right face
        sideR.reset()
        sideR.moveTo(cx,      base - ps + ph)
        sideR.lineTo(cx + pw, base - ps)
        sideR.lineTo(cx + pw, base)
        sideR.lineTo(cx,      base + ph)
        sideR.close()
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

    // ── Pending inputs (UI thread → game thread via @Volatile) ────────────────
    @Volatile private var pendingDc      = 0;     @Volatile private var pendingDr      = 0
    @Volatile private var hasPending     = false;  @Volatile private var pendingReset   = false
    @Volatile private var pendingJump    = false
    @Volatile private var pendingBuildDc = 0;     @Volatile private var pendingBuildDr = 0
    @Volatile private var hasPendingBuild = false

    @Volatile private var pendingSelectChar:      Char         = ' '
    @Volatile private var hasPendingSelectChar:   Boolean      = false
    @Volatile private var pendingSelectAction:    BuildAction? = null
    @Volatile private var hasPendingSelectAction: Boolean      = false

    // Tile picker tray — read on game thread, written on UI thread (boolean toggle, safe)
    @Volatile private var trayOpen = false
    private val trayRects = mutableListOf<RectF>()

    // ── Offsets ───────────────────────────────────────────────────────────────
    private var isoOx = 0f; private var isoOy = 0f
    private var tdOx  = 0f; private var tdOy  = 0f
    private fun ox() = if (isIso) isoOx else tdOx
    private fun oy() = if (isIso) isoOy else tdOy

    // ── HUD rects ─────────────────────────────────────────────────────────────
    private var rippleX = 0f; private var rippleY = 0f; private var rippleT = -1f
    private val btnView    = RectF(); private val btnRotL    = RectF()
    private val btnRotR    = RectF(); private val btnAction  = RectF()
    private val btnZoomIn  = RectF(); private val btnZoomOut = RectF()
    private var zoom = 1.0f

    private fun applyZoom(newZoom: Float) {
        zoom          = newZoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
        HALF_W        = BASE_HALF_W        * zoom
        HALF_H        = BASE_HALF_H        * zoom
        // Keep cube proportions: SIDE_H = HALF_W * 2
        SIDE_H        = BASE_SIDE_H        * zoom
        TILE          = BASE_TILE          * zoom
        HEIGHT_OFFSET = BASE_HEIGHT_OFFSET * zoom
        recomputeOffsets()
    }

    private fun recomputeOffsets() {
        if (surfaceW == 0f) return
        val level = LevelManager.currentLevel
        val midC  = (level.cols - 1) / 2f
        val midR  = (level.rows - 1) / 2f
        isoOx = surfaceW * 0.50f - (midC - midR) * HALF_W
        isoOy = surfaceH * 0.42f - (midC + midR) * HALF_H
        tdOx  = (surfaceW - level.cols * TILE) * 0.5f
        tdOy  = (surfaceH - level.rows * TILE) * 0.45f
    }

    private fun rebuildTrayRects() {
        trayRects.clear()
        val rowCount = BuildAction.entries.size + PLACEABLE_TILE_CHARS.size
        val rowH = 46f; val gap = 5f
        val rowW = btnAction.width().coerceAtLeast(130f)
        val startY = btnAction.top - gap - rowCount * (rowH + gap)
        val x = btnAction.left
        for (i in 0 until rowCount) {
            val top = startY + i * (rowH + gap)
            trayRects.add(RectF(x, top, x + rowW, top + rowH))
        }
    }

    // ── Gesture state ─────────────────────────────────────────────────────────
    private var touchStartX = 0f; private var touchStartY = 0f
    private val SWIPE_THRESHOLD = 40f

    private val LONG_PRESS_MS   = 500L
    private val uiHandler       = Handler(Looper.getMainLooper())
    private var longPressPosted = false; private var longPressFired = false
    private var longPressDc = 0; private var longPressDr = 0

    private val longPressRunnable = Runnable {
        longPressPosted = false; longPressFired = true
        pendingBuildDc = longPressDc; pendingBuildDr = longPressDr
        hasPendingBuild = true
    }

    init {
        setWillNotDraw(true); clipChildren = false
        addView(surfaceView)
        surfaceView.setOnTouchListener { _, ev -> handleTouch(ev); true }
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────
    override fun surfaceCreated(h: SurfaceHolder)   { surfaceReady = true;  startLoop() }
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stopLoop()  }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
        surfaceW = w.toFloat(); surfaceH = ht.toFloat()
        val bm = 16f; val ubw = 52f; val ubh = 44f
        btnView.set(surfaceW / 2 - 26f, bm, surfaceW / 2 + 26f, bm + ubh)
        btnRotL.set(bm, bm, bm + ubw, bm + ubh)
        btnRotR.set(surfaceW - bm - ubw, bm, surfaceW - bm, bm + ubh)
        val abw = 120f
        btnAction.set(surfaceW - bm - abw, surfaceH - bm - ubh, surfaceW - bm, surfaceH - bm)
        val zbw = 44f; val zbh = 44f; val gap = 8f
        btnZoomIn.set( bm, surfaceH - bm - zbh * 2 - gap, bm + zbw, surfaceH - bm - zbh - gap)
        btnZoomOut.set(bm, surfaceH - bm - zbh,            bm + zbw, surfaceH - bm)
        applyZoom(zoom)
        rebuildTrayRects()
    }

    // ── Touch handling ────────────────────────────────────────────────────────
    private fun handleTouch(ev: MotionEvent) {
        val sx = ev.x; val sy = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchStartX = sx; touchStartY = sy
                rippleX = sx; rippleY = sy; rippleT = 0f

                if (btnView.contains(sx, sy))    { isIso = !isIso; trayOpen = false; return }
                if (btnRotL.contains(sx, sy))    { rotation = (rotation + 3) % 4; trayOpen = false; return }
                if (btnRotR.contains(sx, sy))    { rotation = (rotation + 1) % 4; trayOpen = false; return }
                if (btnZoomIn.contains(sx, sy))  { applyZoom(zoom + ZOOM_STEP); trayOpen = false; return }
                if (btnZoomOut.contains(sx, sy)) { applyZoom(zoom - ZOOM_STEP); trayOpen = false; return }
                if (btnAction.contains(sx, sy))  { trayOpen = !trayOpen; rebuildTrayRects(); return }

                if (trayOpen) {
                    val actionCount = BuildAction.entries.size
                    for ((i, rect) in trayRects.withIndex()) {
                        if (rect.contains(sx, sy)) {
                            if (i < actionCount) {
                                pendingSelectAction    = BuildAction.entries[i]
                                hasPendingSelectAction = true
                            } else {
                                val ti = i - actionCount
                                if (ti < PLACEABLE_TILE_CHARS.size) {
                                    pendingSelectChar      = PLACEABLE_TILE_CHARS[ti]
                                    hasPendingSelectChar   = true
                                    pendingSelectAction    = BuildAction.BUILD_TILE
                                    hasPendingSelectAction = true
                                }
                            }
                            trayOpen = false; return
                        }
                    }
                    trayOpen = false; return
                }

                if (state.won) { pendingReset = true; return }

                val (dc, dr) = tapDirection(sx, sy)
                longPressDc = dc; longPressDr = dr; longPressFired = false
                uiHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                longPressPosted = true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = sx - touchStartX; val dy = sy - touchStartY
                if (longPressPosted && dx * dx + dy * dy > SWIPE_THRESHOLD * SWIPE_THRESHOLD)
                    cancelPendingLongPress()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL || longPressFired) return
                val dx = sx - touchStartX; val dy = sy - touchStartY
                resolveMove(dx, dy, sqrt(dx * dx + dy * dy))
            }
        }
    }

    private fun cancelPendingLongPress() {
        if (longPressPosted) {
            uiHandler.removeCallbacks(longPressRunnable)
            longPressPosted = false
        }
    }

    private fun tapDirection(sx: Float, sy: Float): Pair<Int, Int> {
        val tapCol: Float; val tapRow: Float
        if (isIso) {
            tapCol = isoScreenToCol(sx, sy, ox(), oy())
            tapRow = isoScreenToRow(sx, sy, ox(), oy())
        } else {
            tapCol = tdScreenToCol(sx, ox())
            tapRow = tdScreenToRow(sy, oy())
        }
        val (finalCol, finalRow) = applyRotationInverse(tapCol, tapRow)
        val dCol = finalCol - state.targetCol
        val dRow = finalRow - state.targetRow
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
        val (finalDc, finalDr) = applyRotationToMove(moveDc, moveDr)
        pendingDc = finalDc; pendingDr = finalDr; hasPending = true
    }

    private fun applyRotationInverse(tapCol: Float, tapRow: Float): Pair<Float, Float> {
        val level = LevelManager.currentLevel
        return when (rotation % 4) {
            1    -> Pair((level.cols - 1).toFloat() - tapRow, tapCol)
            2    -> Pair((level.cols - 1).toFloat() - tapCol, (level.rows - 1).toFloat() - tapRow)
            3    -> Pair(tapRow, (level.rows - 1).toFloat() - tapCol)
            else -> Pair(tapCol, tapRow)
        }
    }

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
        isRunning = true; lastFrameMs = System.currentTimeMillis()
        gameThread = Thread({
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
            trayOpen = false; state.reset(); return
        }
        if (hasPendingSelectAction) {
            hasPendingSelectAction = false
            pendingSelectAction?.let { state.setAction(it) }
            pendingSelectAction = null
        }
        if (hasPendingSelectChar) {
            hasPendingSelectChar = false
            state.selectTileType(pendingSelectChar)
        }
        if (hasPendingBuild) {
            hasPendingBuild = false
            state.executeBuildAction(pendingBuildDc, pendingBuildDr)
        }
        if (hasPending) {
            hasPending = false
            state.move(pendingDc, pendingDr, pendingJump)
        }
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
            renderer.renderWorld(
                canvas, state, ox(), oy(), surfaceW, surfaceH,
                state.stateTime, isIso, rotation
            )
            renderer.renderHUD(
                canvas, surfaceW, surfaceH,
                state.won, state.winTimer, LevelManager.currentLevel.hint,
                btnView, btnRotL, btnRotR, isIso,
                rippleX, rippleY, rippleT,
                btnAction, state,
                btnZoomIn, btnZoomOut,
                trayOpen, trayRects
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