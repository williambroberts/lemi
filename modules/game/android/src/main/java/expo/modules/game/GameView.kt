package expo.modules.gameinternals

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import kotlin.math.*

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
    private val targetFrameTimeMs      = 1_000L / 60
    private var lastFrameMs            = System.currentTimeMillis()
    private var surfaceWidth  = 0
    private var surfaceHeight = 0

    // =========================================================================
    // World
    // =========================================================================
    private val WORLD_W = 3200f; private val WORLD_H = 3200f
    private val TILE    = 80f   // floor tile size for checkerboard

    // =========================================================================
    // Player
    // =========================================================================
    private var playerX = WORLD_W / 2f; private var playerY = WORLD_H * 0.85f
    private var playerHP = 100; private val PLAYER_MAX_HP = 100
    private val PLAYER_R = 26f; private val PLAYER_SPD = 300f
    private var playerAngle   = 0f
    private var playerHurtFlash = 0f
    private var playerInvincible = 0f
    // Animation
    private var walkCycle     = 0f   // 0..2π oscillates as player moves
    private var isMoving      = false
    private var lastPlayerX   = WORLD_W / 2f
    private var lastPlayerY   = WORLD_H * 0.85f

    // Brick fragments (hit effect)
    data class BrickFrag(var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rot: Float, var rotSpd: Float, var life: Float, val color: Int, val w: Float, val h: Float)
    private val frags = mutableListOf<BrickFrag>()

    // =========================================================================
    // Weapons & items
    // =========================================================================
    enum class Weapon { SWORD, GUN, HAMMER, BOOMERANG }
    private var activeWeapon  = Weapon.SWORD
    private val PANEL_W       = 120f
    private val BTN_H         = 110f
    private val BTN_MARGIN    = 10f

    // Sword
    private var slashCd        = 0f;   private val SLASH_CD        = 0.35f
    private var chargeHeld     = 0f;   private val CHARGE_THRESH   = 0.5f   // hold time for charged slash
    private var isCharging     = false
    private var chargeReleased = false
    private val SLASH_R        = 85f;  private val SLASH_R_CHARGED = 140f
    private val SLASH_ARC      = Math.PI.toFloat() * 0.72f
    private val SLASH_ARC_CHARGED = Math.PI.toFloat() * 1.1f

    data class Slash(val x: Float, val y: Float, val angle: Float,
        val radius: Float, val arc: Float, var life: Float = 0.22f, val charged: Boolean = false)
    private val slashes = mutableListOf<Slash>()

    // Gun
    private var shootCd = 0f; private val SHOOT_CD = 0.25f
    data class Bullet(var x: Float, var y: Float, val vx: Float, val vy: Float,
        var life: Float = 1.4f, val dmg: Int, val fromEnemy: Boolean = false)
    private val bullets      = mutableListOf<Bullet>()
    private val enemyBullets = mutableListOf<Bullet>()
    private val BULLET_R = 8f; private val BULLET_SPD = 900f
    private val ENEMY_BULLET_R = 7f; private val ENEMY_BULLET_SPD = 400f

    // Hammer
    private var hammerCd = 0f; private val HAMMER_CD = 2.2f
    private val HAMMER_R = 80f; private val HAMMER_ARC = Math.PI.toFloat() * 0.85f
    data class HammerSwing(val x: Float, val y: Float, val angle: Float, var life: Float = 0.30f)
    private val hammerSwings = mutableListOf<HammerSwing>()

    // Boomerang
    private var boomerangCd = 0f; private val BOOMERANG_CD = 1.8f
    private val BOOMERANG_SPD = 600f; private val BOOMERANG_R = 12f
    data class Boomerang(var x: Float, var y: Float, var vx: Float, var vy: Float,
        var returning: Boolean = false, var life: Float = 2.0f, var hitSet: MutableSet<Enemy> = mutableSetOf())
    private var activeBoomerang: Boomerang? = null

    // =========================================================================
    // Enemy types  (swordMult, gunMult, hammerMult)
    // =========================================================================
    enum class EnemyType(
        val radius: Float, val maxHp: Int, val speed: Float, val damage: Int,
        val attackRate: Float, val scoreValue: Int,
        val bodyColor: Int, val strokeColor: Int,
        val swordMult: Float, val gunMult: Float, val hammerMult: Float,
        val canShoot: Boolean, val shootRate: Float, val dropChance: Float,
        val sightRange: Float, val patrolRadius: Float
    ) {
        GRUNT  (22f,  40,  110f, 7,  1.2f, 10, Color.rgb(210,60,60),  Color.rgb(130,20,20),  1.0f,1.0f,99f, false,0f,  0.30f, 280f, 200f),
        SPEEDER(16f,  25,  200f, 5,  0.8f, 15, Color.rgb(255,160,20), Color.rgb(160,90,0),   1.4f,0.6f,99f, false,0f,  0.10f, 240f, 280f),
        TANK   (38f, 140,  55f, 18,  2.0f, 30, Color.rgb(80,60,180),  Color.rgb(40,30,110),  1.5f,0.4f,99f, false,0f,  0.45f, 220f, 100f),
        SNIPER (18f,  30,  75f, 12,  2.5f, 20, Color.rgb(60,180,180), Color.rgb(20,100,100), 0.5f,1.6f,99f, true, 3.5f,0.20f, 400f, 150f),
        BRUTE  (44f, 200,  40f, 25,  2.5f, 50, Color.rgb(160,40,160), Color.rgb(80,10,80),   0.8f,0.8f,99f, true, 5.0f,0.60f, 260f, 120f),
        BOSS   (70f,1200,  50f, 30,  1.5f,200, Color.rgb(180,20,20),  Color.rgb(80,0,0),     0.7f,0.7f,0.3f,true, 1.8f,1.00f, 9999f,0f),
    }

    enum class EnemyState { PATROL, ALERT, CHASE, STUNNED }
    data class Enemy(
        var x: Float, var y: Float, val type: EnemyType,
        var hp: Int = type.maxHp, var angle: Float = 0f,
        var attackCooldown: Float = 0f, var hurtFlash: Float = 0f,
        var shootCooldown: Float = (Math.random() * type.shootRate).toFloat(),
        var state: EnemyState = EnemyState.PATROL,
        var patrolOriginX: Float = 0f, var patrolOriginY: Float = 0f,
        var patrolAngle: Float = (Math.random() * 2 * Math.PI).toFloat(),
        var patrolTimer: Float  = (Math.random() * 3f).toFloat(),
        var alertTimer: Float   = 0f,
        var stunTimer: Float    = 0f,
        val isBoss: Boolean     = type == EnemyType.BOSS,
        // Boss-specific
        var bossPhase: Int      = 1,
        var bossAttackTimer: Float = 0f
    )
    private val enemies = mutableListOf<Enemy>()
    private var bossSpawned   = false
    private var bossDefeated  = false
    private var dungeonCleared = false

    // =========================================================================
    // Environment
    // =========================================================================
    // Boxes: breakable=true means smashable by hammer, contains a pickup
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float,
        var breakable: Boolean = false, var broken: Boolean = false) {
        val left   get() = x - w / 2f; val right  get() = x + w / 2f
        val top    get() = y - h / 2f; val bottom get() = y + h / 2f
        fun rectF() = RectF(left, top, right, bottom)
        fun contains(px: Float, py: Float, r: Float) =
            !broken && px+r > left && px-r < right && py+r > top && py-r < bottom
    }
    private val boxes = mutableListOf<Box>()

    // =========================================================================
    // Pickups
    // =========================================================================
    enum class PickupType { HEART, RUPEE_GREEN, RUPEE_BLUE, RUPEE_RED, KEY }
    data class Pickup(val x: Float, val y: Float, val type: PickupType, var life: Float = 14f)
    private val pickups = mutableListOf<Pickup>()
    private var rupees  = 0
    private var keys    = 0

    // Damage numbers
    data class DmgNum(var x: Float, var y: Float, val text: String, var life: Float = 1f, val color: Int)
    private val dmgNums = mutableListOf<DmgNum>()

    // Camera
    private var camX = 0f; private var camY = 0f

    // Game states
    enum class GameState { PLAYING, BOSS_INTRO, VICTORY, GAME_OVER }
    private var gameState   = GameState.PLAYING
    private var stateTimer  = 0f
    private var score       = 0

    // =========================================================================
    // Touch
    // =========================================================================
    @Volatile private var touchX = -1f; @Volatile private var touchY = -1f
    @Volatile private var isTouching = false
    private var touchDownX = 0f; private var touchDownY = 0f; private var touchDownTime = 0L
    private val HOLD_MOVE_MS   = 160L   // hold longer than this = movement intent
    private val TAP_MAX_DIST   = 40f
    private var swipeTracked   = false  // have we already fired the swipe slash this touch?

    // =========================================================================
    // Paint
    // =========================================================================
    // Floor — isometric brick tiles
    private val brickTopPaint    = Paint().apply { style = Paint.Style.FILL }  // set per tile
    private val brickSidePaint   = Paint().apply { style = Paint.Style.FILL }
    private val brickMortarPaint = Paint().apply { color = Color.rgb(80,60,50); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val studPaint        = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val studRimPaint     = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
    private val wallPaint        = Paint().apply { color = Color.rgb(30,22,18); style = Paint.Style.FILL }
    private val bgPaint          = Paint().apply { color = Color.rgb(20,14,10); style = Paint.Style.FILL }

    // Player — minifig colours
    private val figTorsoTopPaint   = Paint().apply { color = Color.rgb(30,90,200);   style = Paint.Style.FILL; isAntiAlias = true } // blue top face
    private val figTorsoSidePaint  = Paint().apply { color = Color.rgb(18,55,130);   style = Paint.Style.FILL; isAntiAlias = true } // darker side
    private val figHeadPaint       = Paint().apply { color = Color.rgb(255,220,100); style = Paint.Style.FILL; isAntiAlias = true } // yellow head
    private val figHeadSidePaint   = Paint().apply { color = Color.rgb(190,155,55);  style = Paint.Style.FILL; isAntiAlias = true }
    private val figLegTopPaint     = Paint().apply { color = Color.rgb(40,40,40);    style = Paint.Style.FILL; isAntiAlias = true }
    private val figLegSidePaint    = Paint().apply { color = Color.rgb(20,20,20);    style = Paint.Style.FILL; isAntiAlias = true }
    private val figArmPaint        = Paint().apply { color = Color.rgb(30,90,200);   style = Paint.Style.FILL; isAntiAlias = true }
    private val figOutlinePaint    = Paint().apply { color = Color.rgb(10,10,10);    style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true }
    private val figFacePaint       = Paint().apply { color = Color.rgb(10,10,10);    style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
    private val playerChargePaint  = Paint().apply { color = Color.argb(160,255,255,80); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val hurtOverlayPaint  = Paint().apply { style = Paint.Style.FILL }
    private val shadowPaint       = Paint().apply { color = Color.argb(70,0,0,0);  style = Paint.Style.FILL; isAntiAlias = true }
    private val eyePaint          = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    private val pupilPaint        = Paint().apply { color = Color.rgb(10,10,10);   style = Paint.Style.FILL; isAntiAlias = true }
    // Enemy minifig tints (reuse figXxx paints with colour overrides)
    private val figEnemyHead      = Paint().apply { color = Color.rgb(255,220,100); style = Paint.Style.FILL; isAntiAlias = true }
    private val figEnemyTorsoTop  = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val figEnemyTorsoSide = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val figEnemyLegTop    = Paint().apply { color = Color.rgb(40,40,40);   style = Paint.Style.FILL; isAntiAlias = true }
    private val fragPaint         = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    // Cached paints reused inside drawMinifig (colours set per call)
    private val mfLegSidePaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mfLegTopPaint     = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mfArmFillPaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mfArmSidePaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mfHeadFillPaint   = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mfHeadSidePaint   = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mfEyeDotPaint     = Paint().apply { color = Color.rgb(10,10,10); style = Paint.Style.FILL; isAntiAlias = true }
    private val mfShooterRingPaint= Paint().apply { color = Color.argb(100,255,120,120); style = Paint.Style.STROKE; isAntiAlias = true }
    private val borderPaint       = Paint().apply { color=Color.argb(120,80,180,80); style=Paint.Style.STROKE; strokeWidth=8f }
    private val potHighlightPaint = Paint().apply { color=Color.argb(80,200,220,255); style=Paint.Style.FILL }
    private val keyRimPaint       = Paint().apply { color=Color.rgb(200,160,30); style=Paint.Style.STROKE; isAntiAlias=true }
    private val keyShaftPaint     = Paint().apply { color=Color.rgb(255,210,50); style=Paint.Style.STROKE; isAntiAlias=true }

    private val enemyBodyPaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val enemyStrokePaint  = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val alertPaint        = Paint().apply { color = Color.rgb(255,220,0);  style = Paint.Style.FILL; isAntiAlias = true }
    private val stunPaint         = Paint().apply { color = Color.argb(180,150,150,255); style = Paint.Style.FILL; isAntiAlias = true }
    private val sightPaint        = Paint().apply { color = Color.argb(18,255,200,0); style = Paint.Style.FILL }

    private val slashPaint        = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val bulletPaint       = Paint().apply { color = Color.rgb(255,220,60);  style = Paint.Style.FILL; isAntiAlias = true }
    private val bulletGlowPaint   = Paint().apply { color = Color.argb(80,255,200,0); style = Paint.Style.FILL; isAntiAlias = true }
    private val enemyBulletPaint  = Paint().apply { color = Color.rgb(255,80,80);   style = Paint.Style.FILL; isAntiAlias = true }
    private val enemyBulletGlow   = Paint().apply { color = Color.argb(70,255,0,0); style = Paint.Style.FILL; isAntiAlias = true }
    private val boomPaint         = Paint().apply { color = Color.rgb(80,200,255);   style = Paint.Style.FILL; isAntiAlias = true }
    private val boomGlowPaint     = Paint().apply { color = Color.argb(80,80,180,255); style = Paint.Style.FILL; isAntiAlias = true }
    private val hammerSwingPaint  = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    private val boxFillPaint      = Paint().apply { color = Color.rgb(90,70,45);    style = Paint.Style.FILL }
    private val boxBreakPaint     = Paint().apply { color = Color.rgb(120,90,55);   style = Paint.Style.FILL }
    private val boxStrokePaint    = Paint().apply { color = Color.rgb(55,40,20);    style = Paint.Style.STROKE; strokeWidth = 3f }
    private val boxHighlightPaint = Paint().apply { color = Color.argb(60,200,160,80); style = Paint.Style.FILL }
    private val potFillPaint      = Paint().apply { color = Color.rgb(100,130,180); style = Paint.Style.FILL; isAntiAlias = true }
    private val potStrokePaint    = Paint().apply { color = Color.rgb(60,80,130);   style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true }

    private val hpBarBgPaint      = Paint().apply { color = Color.argb(160,20,20,20); style = Paint.Style.FILL }
    private val hpBarFillPaint    = Paint().apply { style = Paint.Style.FILL }
    private val hpBarEnemyPaint   = Paint().apply { style = Paint.Style.FILL }
    private val bossBarBgPaint    = Paint().apply { color = Color.argb(200,10,10,10); style = Paint.Style.FILL }
    private val bossBarFillPaint  = Paint().apply { color = Color.rgb(200,30,30);    style = Paint.Style.FILL }

    private val hudTextPaint      = Paint().apply { color = Color.WHITE; textSize = 38f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(4f,0f,2f,Color.BLACK) }
    private val dmgTextPaint      = Paint().apply { textSize = 36f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; setShadowLayer(3f,0f,1f,Color.BLACK) }
    private val bigTextPaint      = Paint().apply { color = Color.WHITE; textSize = 80f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(8f,0f,3f,Color.BLACK) }
    private val subtitlePaint     = Paint().apply { color = Color.argb(220,200,200,200); textSize = 42f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val overlayPaint      = Paint().apply { color = Color.argb(190,0,0,0); style = Paint.Style.FILL }

    // Panel
    private val panelBgPaint      = Paint().apply { color = Color.argb(210,12,18,12); style = Paint.Style.FILL }
    private val panelBorderPaint  = Paint().apply { color = Color.argb(120,60,140,60); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val btnActivePaint    = Paint().apply { color = Color.argb(230,40,100,60); style = Paint.Style.FILL; isAntiAlias = true }
    private val btnInactivePaint  = Paint().apply { color = Color.argb(130,25,35,25); style = Paint.Style.FILL; isAntiAlias = true }
    private val btnActiveBorder   = Paint().apply { color = Color.argb(255,80,220,120); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val btnInactiveBorder = Paint().apply { color = Color.argb(80,80,120,80);  style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
    private val btnTextPaint      = Paint().apply { textSize = 22f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val cdArcPaint        = Paint().apply { color = Color.argb(180,255,180,40); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val iconPaint         = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3.5f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val iconFillPaint     = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    // Hearts (pickups + HUD)
    private val heartFillPaint   = Paint().apply { color = Color.rgb(230,50,80);   style = Paint.Style.FILL; isAntiAlias = true }
    private val heartStrokePaint = Paint().apply { color = Color.rgb(160,20,50);   style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true }
    private val heartGlowPaint   = Paint().apply { color = Color.argb(60,255,80,120); style = Paint.Style.FILL; isAntiAlias = true }
    private val rupeeGreenPaint  = Paint().apply { color = Color.rgb(50,220,80);   style = Paint.Style.FILL; isAntiAlias = true }
    private val rupeeBluePaint   = Paint().apply { color = Color.rgb(60,130,255);  style = Paint.Style.FILL; isAntiAlias = true }
    private val rupeeRedPaint    = Paint().apply { color = Color.rgb(255,60,60);   style = Paint.Style.FILL; isAntiAlias = true }
    private val keyPaint         = Paint().apply { color = Color.rgb(255,210,50);  style = Paint.Style.FILL; isAntiAlias = true }
    private val pickupTextPaint  = Paint().apply { color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(3f,0f,1f,Color.BLACK) }

    // Reusable paths
    private val slashPath   = Path()
    private val heartPath   = Path()
    private val rupeePath   = Path()

    // =========================================================================
    // Init
    // =========================================================================
    init {
        setWillNotDraw(true); clipChildren = false; addView(surfaceView)
        surfaceView.setOnTouchListener { _, ev -> handleTouch(ev); true }
        buildDungeon()
    }

    private fun buildDungeon() {
        boxes.clear(); enemies.clear(); pickups.clear()
        bossSpawned = false; bossDefeated = false; dungeonCleared = false

        // ---- Obstacle boxes (solid covers) ----
        listOf(
            Triple(600f,  600f,  100f to 80f),  Triple(1200f, 500f,  80f  to 100f),
            Triple(1800f, 600f,  110f to 70f),  Triple(2400f, 550f,  90f  to 90f),
            Triple(2800f, 700f,  80f  to 80f),  Triple(500f,  1200f, 90f  to 70f),
            Triple(1000f, 1100f, 100f to 80f),  Triple(1600f, 1000f, 80f  to 100f),
            Triple(2200f, 1100f, 110f to 70f),  Triple(2700f, 1200f, 85f  to 85f),
            Triple(700f,  1800f, 90f  to 70f),  Triple(1300f, 1700f, 80f  to 90f),
            Triple(1900f, 1800f, 100f to 70f),  Triple(2500f, 1750f, 80f  to 80f),
            Triple(400f,  2400f, 90f  to 80f),  Triple(1100f, 2300f, 80f  to 90f),
            Triple(1700f, 2400f, 100f to 70f),  Triple(2300f, 2350f, 85f  to 85f),
        ).forEach { (x,y,sz) -> boxes.add(Box(x, y, sz.first, sz.second, breakable = false)) }

        // ---- Breakable pots scattered around ----
        listOf(800f to 800f, 1400f to 700f, 2100f to 900f, 900f to 1500f,
               1500f to 1400f, 2300f to 1500f, 600f to 2100f, 1800f to 2100f,
               2600f to 2000f, 1200f to 2600f, 2000f to 2700f).forEach { (x,y) ->
            boxes.add(Box(x, y, 55f, 55f, breakable = true))
        }

        // ---- Regular enemies spread across dungeon ----
        spawnEnemyAt(700f,  700f,  EnemyType.GRUNT)
        spawnEnemyAt(1500f, 600f,  EnemyType.GRUNT)
        spawnEnemyAt(2300f, 700f,  EnemyType.GRUNT)
        spawnEnemyAt(900f,  1300f, EnemyType.SPEEDER)
        spawnEnemyAt(1700f, 1200f, EnemyType.SPEEDER)
        spawnEnemyAt(2500f, 1300f, EnemyType.SPEEDER)
        spawnEnemyAt(600f,  1900f, EnemyType.TANK)
        spawnEnemyAt(1400f, 1800f, EnemyType.SNIPER)
        spawnEnemyAt(2200f, 1900f, EnemyType.SNIPER)
        spawnEnemyAt(800f,  2600f, EnemyType.BRUTE)
        spawnEnemyAt(1600f, 2500f, EnemyType.TANK)
        spawnEnemyAt(2400f, 2600f, EnemyType.BRUTE)

        // Camera starts at player
        camX = (playerX - surfaceWidth /2f).coerceAtLeast(0f)
        camY = (playerY - surfaceHeight/2f).coerceAtLeast(0f)
    }

    private fun spawnEnemyAt(x: Float, y: Float, type: EnemyType) {
        enemies.add(Enemy(x, y, type,
            patrolOriginX = x, patrolOriginY = y,
            patrolAngle   = (Math.random() * 2 * Math.PI).toFloat()))
    }

    private fun spawnBoss() {
        bossSpawned = true
        gameState   = GameState.BOSS_INTRO
        stateTimer  = 3.0f
        enemies.add(Enemy(WORLD_W/2f, 800f, EnemyType.BOSS,
            patrolOriginX = WORLD_W/2f, patrolOriginY = 800f))
    }

    // =========================================================================
    // Touch
    // =========================================================================
    private fun panelLeft(sw: Float) = sw - PANEL_W
    private fun btnRect(sw: Float, sh: Float, idx: Int): RectF {
        val px = panelLeft(sw) + BTN_MARGIN
        val totalH = BTN_H * 4 + BTN_MARGIN * 3
        val startY = (sh - totalH) / 2f
        val y = startY + idx * (BTN_H + BTN_MARGIN)
        return RectF(px, y, sw - BTN_MARGIN, y + BTN_H)
    }
    private val WEAPONS_ORDER = listOf(Weapon.SWORD, Weapon.GUN, Weapon.HAMMER, Weapon.BOOMERANG)

    private fun handleTouch(event: MotionEvent) {
        val sw = surfaceWidth.toFloat(); val sh = surfaceHeight.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Panel buttons
                if (event.x >= panelLeft(sw)) {
                    WEAPONS_ORDER.forEachIndexed { i, w ->
                        if (btnRect(sw,sh,i).contains(event.x,event.y)) { activeWeapon = w; return }
                    }
                }
                touchDownX = event.x; touchDownY = event.y
                touchX = event.x; touchY = event.y
                isTouching = true; touchDownTime = System.currentTimeMillis()
                swipeTracked = false
                // Start charge if sword
                if (activeWeapon == Weapon.SWORD && event.x < panelLeft(sw)) {
                    isCharging = true; chargeHeld = 0f; chargeReleased = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x; touchY = event.y
                // Swipe slash — fires once during drag if moved far enough with sword
                if (!swipeTracked && activeWeapon == Weapon.SWORD && event.x < panelLeft(sw)) {
                    val dist = hypot(event.x - touchDownX, event.y - touchDownY)
                    if (dist >= 55f) {
                        swipeTracked = true; isCharging = false
                        performSwipeSlash(touchDownX, touchDownY, event.x, event.y)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val held = System.currentTimeMillis() - touchDownTime
                val dist = hypot(event.x - touchDownX, event.y - touchDownY)
                if (event.x < panelLeft(sw)) {
                    when {
                        // Charged slash: sword held long enough without swiping
                        activeWeapon == Weapon.SWORD && isCharging && chargeHeld >= CHARGE_THRESH ->
                            performChargedSlash(event.x, event.y)
                        // Quick tap: non-sword or sword quick tap without drag
                        !swipeTracked && held < 220L && dist < TAP_MAX_DIST ->
                            performTapAttack(event.x, event.y)
                    }
                }
                when (gameState) {
                    GameState.GAME_OVER, GameState.VICTORY -> if (stateTimer <= 0f) restartGame()
                    else -> {}
                }
                isCharging = false; chargeHeld = 0f
                isTouching = false; touchX = -1f; touchY = -1f
            }
        }
    }

    // Quick swipe: check if line segment crosses any enemy
    private fun performSwipeSlash(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (slashCd > 0f) return
        slashCd = SLASH_CD
        val wx1 = x1 + camX; val wy1 = y1 + camY
        val wx2 = x2 + camX; val wy2 = y2 + camY
        val angle = atan2(wy2 - wy1, wx2 - wx1)
        playerAngle = angle
        slashes.add(Slash(playerX, playerY, angle, SLASH_R, SLASH_ARC))
        hitEnemiesInArc(playerX, playerY, angle, SLASH_R, SLASH_ARC, 18..30, false)

        // Also check line intersection for any enemies along swipe path
        val toRemove = mutableListOf<Enemy>()
        for (e in enemies) {
            if (e.state == EnemyState.STUNNED) continue
            if (segmentCircleIntersects(wx1, wy1, wx2, wy2, e.x, e.y, e.type.radius + 20f)) {
                if (!toRemove.contains(e)) {
                    val dmg = (25 * e.type.swordMult).toInt()
                    applyDamageToEnemy(e, dmg, "sword", toRemove)
                }
            }
        }
        enemies.removeAll(toRemove)
    }

    // Hold then release: wide charged arc
    private fun performChargedSlash(screenX: Float, screenY: Float) {
        if (slashCd > 0f) return
        slashCd = SLASH_CD * 0.5f
        val wx = screenX + camX; val wy = screenY + camY
        val angle = atan2(wy - playerY, wx - playerX)
        playerAngle = angle
        slashes.add(Slash(playerX, playerY, angle, SLASH_R_CHARGED, SLASH_ARC_CHARGED, charged = true))
        hitEnemiesInArc(playerX, playerY, angle, SLASH_R_CHARGED, SLASH_ARC_CHARGED, 35..55, false)
    }

    // Tap attack for gun, hammer, boomerang
    private fun performTapAttack(screenX: Float, screenY: Float) {
        val wx = screenX + camX; val wy = screenY + camY
        val dx = wx - playerX; val dy = wy - playerY
        val angle = atan2(dy, dx); playerAngle = angle
        val len = hypot(dx, dy).coerceAtLeast(0.001f)
        when (activeWeapon) {
            Weapon.SWORD -> {
                if (slashCd > 0f) return
                slashCd = SLASH_CD
                slashes.add(Slash(playerX, playerY, angle, SLASH_R, SLASH_ARC))
                hitEnemiesInArc(playerX, playerY, angle, SLASH_R, SLASH_ARC, 18..30, false)
            }
            Weapon.GUN -> {
                if (shootCd > 0f) return; shootCd = SHOOT_CD
                bullets.add(Bullet(playerX + cos(angle)*(PLAYER_R+10f),
                    playerY + sin(angle)*(PLAYER_R+10f), (dx/len)*BULLET_SPD, (dy/len)*BULLET_SPD, dmg = 22))
            }
            Weapon.HAMMER -> {
                if (hammerCd > 0f) return; hammerCd = HAMMER_CD
                hammerSwings.add(HammerSwing(playerX, playerY, angle))
                hitEnemiesInArc(playerX, playerY, angle, HAMMER_R, HAMMER_ARC, 9999..9999, true)
                // Smash breakable boxes in range
                for (box in boxes) {
                    if (!box.breakable || box.broken) continue
                    if (hypot(box.x - playerX, box.y - playerY) < HAMMER_R + 40f) smashBox(box)
                }
            }
            Weapon.BOOMERANG -> {
                if (boomerangCd > 0f || activeBoomerang != null) return
                boomerangCd = BOOMERANG_CD
                activeBoomerang = Boomerang(playerX + cos(angle)*(PLAYER_R+12f),
                    playerY + sin(angle)*(PLAYER_R+12f), (dx/len)*BOOMERANG_SPD, (dy/len)*BOOMERANG_SPD)
            }
        }
    }

    private fun hitEnemiesInArc(cx: Float, cy: Float, angle: Float, radius: Float, arc: Float,
                                 dmgRange: IntRange, oneShot: Boolean) {
        val toRemove = mutableListOf<Enemy>()
        for (e in enemies) {
            val ex = e.x - cx; val ey = e.y - cy
            if (hypot(ex, ey) > radius + e.type.radius) continue
            var diff = atan2(ey, ex) - angle
            while (diff > Math.PI)  diff -= (2*Math.PI).toFloat()
            while (diff < -Math.PI) diff += (2*Math.PI).toFloat()
            if (abs(diff) < arc / 2f) {
                val dmg = if (oneShot) e.hp else ((dmgRange.first + (Math.random() * (dmgRange.last - dmgRange.first + 1)).toInt()) * when(activeWeapon) {
                    Weapon.SWORD  -> e.type.swordMult
                    Weapon.HAMMER -> e.type.hammerMult
                    else          -> 1.0f
                }).toInt()
                applyDamageToEnemy(e, dmg, if (oneShot) "hammer" else "sword", toRemove)
            }
        }
        enemies.removeAll(toRemove)
    }

    private fun applyDamageToEnemy(e: Enemy, dmg: Int, source: String, toRemove: MutableList<Enemy>) {
        if (toRemove.contains(e)) return
        e.hp -= dmg; e.hurtFlash = 0.22f
        val mult = if (source == "sword") e.type.swordMult else if (source == "gun") e.type.gunMult else 1f
        val tag = when { mult >= 1.3f -> "WEAK! -$dmg"; mult <= 0.6f -> "RES -$dmg"; else -> "-$dmg" }
        val col = when { mult >= 1.3f -> Color.rgb(255,255,60); mult <= 0.6f -> Color.rgb(160,160,255); else -> Color.rgb(255,230,80) }
        dmgNums.add(DmgNum(e.x, e.y - 32f, tag, color = col))
        if (source == "hammer") dmgNums[dmgNums.lastIndex] = DmgNum(e.x, e.y-32f, "SMASH!", color = Color.rgb(200,100,255))
        if (e.hp <= 0) { toRemove.add(e); onEnemyKilled(e) }
    }

    private fun onEnemyKilled(e: Enemy) {
        score += e.type.scoreValue
        spawnFrags(e.x, e.y, e.type.bodyColor, if (e.isBoss) 10 else 5)
        if (e.isBoss) {
            bossDefeated = true
            dropPickup(e.x, e.y, PickupType.HEART); dropPickup(e.x+60f, e.y, PickupType.RUPEE_RED)
            gameState = GameState.VICTORY; stateTimer = 4f
            return
        }
        if (Math.random() < e.type.dropChance) dropPickup(e.x, e.y, randomDrop())
        // All regular enemies gone → spawn boss
        if (!bossSpawned && enemies.none { !it.isBoss && it != e }) spawnBoss()
    }

    private fun randomDrop(): PickupType = when((Math.random()*100).toInt()) {
        in 0..49  -> PickupType.HEART
        in 50..69 -> PickupType.RUPEE_GREEN
        in 70..84 -> PickupType.RUPEE_BLUE
        in 85..94 -> PickupType.RUPEE_RED
        else      -> PickupType.KEY
    }
    private fun dropPickup(x: Float, y: Float, type: PickupType) { pickups.add(Pickup(x, y, type)) }

    private fun smashBox(box: Box) {
        box.broken = true
        val roll = Math.random()
        val ptype = when { roll < 0.5 -> PickupType.HEART; roll < 0.75 -> PickupType.RUPEE_GREEN; else -> PickupType.RUPEE_BLUE }
        dropPickup(box.x, box.y, ptype)
    }

    // Line segment vs circle
    private fun segmentCircleIntersects(x1: Float, y1: Float, x2: Float, y2: Float,
                                         cx: Float, cy: Float, r: Float): Boolean {
        val dx = x2 - x1; val dy = y2 - y1
        val fx = x1 - cx; val fy = y1 - cy
        val a = dx*dx + dy*dy; val b = 2*(fx*dx + fy*dy); val c = fx*fx + fy*fy - r*r
        var disc = b*b - 4*a*c
        if (disc < 0) return false
        disc = sqrt(disc); val t1 = (-b-disc)/(2*a); val t2 = (-b+disc)/(2*a)
        return (t1 in 0f..1f) || (t2 in 0f..1f)
    }

    // =========================================================================
    // Surface
    // =========================================================================
    override fun surfaceCreated(h: SurfaceHolder)  { surfaceReady = true; startGameLoop() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { surfaceWidth = w; surfaceHeight = ht }
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stopGameLoop() }

    private fun startGameLoop() {
        if (isRunning) return
        isRunning = true; lastFrameMs = System.currentTimeMillis()
        gameThread = Thread({
            while (isRunning) {
                if (!surfaceReady) { Thread.sleep(16); continue }
                val fs = System.currentTimeMillis()
                val dt = (fs - lastFrameMs).coerceAtMost(80).toFloat() / 1000f; lastFrameMs = fs
                update(dt); draw()
                val sleep = targetFrameTimeMs - (System.currentTimeMillis() - fs)
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }, "GameLoop").also { it.priority = Thread.MAX_PRIORITY; it.isDaemon = true; it.start() }
    }

    private fun stopGameLoop() { isRunning = false; gameThread?.interrupt(); gameThread?.join(1000); gameThread = null }

    // =========================================================================
    // Update
    // =========================================================================
    private fun update(dt: Float) {
        if (gameState == GameState.BOSS_INTRO || gameState == GameState.VICTORY || gameState == GameState.GAME_OVER) {
            stateTimer -= dt
            if (gameState == GameState.BOSS_INTRO && stateTimer <= 0f) gameState = GameState.PLAYING
            return
        }

        val sw = surfaceWidth.toFloat()

        // Charge timer
        if (isCharging && activeWeapon == Weapon.SWORD) chargeHeld += dt

        // Player movement — hold in game area
        if (isTouching && touchX >= 0f && touchX < panelLeft(sw)) {
            val held  = System.currentTimeMillis() - touchDownTime
            val moved = hypot(touchX - touchDownX, touchY - touchDownY)
            if (held >= HOLD_MOVE_MS || moved >= TAP_MAX_DIST) {
                val dx = (touchX + camX) - playerX; val dy = (touchY + camY) - playerY
                val dist = hypot(dx, dy)
                if (dist > 8f) {
                    val move = min(PLAYER_SPD * dt, dist)
                    var nx = playerX + (dx/dist)*move; var ny = playerY + (dy/dist)*move
                    nx = nx.coerceIn(PLAYER_R, WORLD_W-PLAYER_R); ny = ny.coerceIn(PLAYER_R, WORLD_H-PLAYER_R)
                    for (box in boxes) pushOutOfBox(nx, ny, PLAYER_R) { ox, oy -> nx = ox; ny = oy }
                    playerX = nx; playerY = ny; playerAngle = atan2(dy, dx)
                }
            }
        }

        if (playerInvincible > 0f) playerInvincible -= dt
        if (playerHurtFlash  > 0f) playerHurtFlash  -= dt

        // Walk cycle
        isMoving = (abs(playerX - lastPlayerX) > 0.5f || abs(playerY - lastPlayerY) > 0.5f)
        if (isMoving) walkCycle = (walkCycle + dt * 8f) % (2f * Math.PI.toFloat())
        lastPlayerX = playerX; lastPlayerY = playerY

        // Brick fragments
        frags.removeAll { f ->
            f.x += f.vx * dt; f.y += f.vy * dt
            f.vy += 180f * dt  // gravity
            f.vx *= 0.92f
            f.rot += f.rotSpd * dt
            f.life -= dt
            f.life <= 0f
        }
        if (slashCd  > 0f) slashCd  -= dt
        if (shootCd  > 0f) shootCd  -= dt
        if (hammerCd > 0f) hammerCd -= dt
        if (boomerangCd > 0f) boomerangCd -= dt

        // Boomerang
        activeBoomerang?.let { boom ->
            boom.x += boom.vx * dt; boom.y += boom.vy * dt; boom.life -= dt
            val dxB = playerX - boom.x; val dyB = playerY - boom.y
            if (boom.life < 1.0f || hypot(dxB, dyB) < 30f && boom.returning) {
                activeBoomerang = null
            } else {
                if (boom.life < 1.2f) boom.returning = true
                if (boom.returning) {
                    val dist = hypot(dxB, dyB).coerceAtLeast(1f)
                    boom.vx += (dxB/dist) * 1400f * dt; boom.vy += (dyB/dist) * 1400f * dt
                    val spd = hypot(boom.vx, boom.vy)
                    if (spd > BOOMERANG_SPD * 1.4f) { boom.vx = boom.vx/spd*BOOMERANG_SPD*1.4f; boom.vy = boom.vy/spd*BOOMERANG_SPD*1.4f }
                }
                val toStun = mutableListOf<Enemy>()
                for (e in enemies) {
                    if (boom.hitSet.contains(e)) continue
                    if (hypot(boom.x - e.x, boom.y - e.y) < BOOMERANG_R + e.type.radius) {
                        boom.hitSet.add(e); toStun.add(e)
                        e.state = EnemyState.STUNNED; e.stunTimer = 2.5f
                        dmgNums.add(DmgNum(e.x, e.y-32f, "STUNNED", color = Color.rgb(150,150,255)))
                    }
                }
            }
        }

        // Player bullets
        val deadB = mutableListOf<Bullet>()
        for (b in bullets) {
            b.x += b.vx*dt; b.y += b.vy*dt; b.life -= dt
            if (b.life<=0||b.x<0||b.x>WORLD_W||b.y<0||b.y>WORLD_H) { deadB.add(b); continue }
            if (boxes.any { it.contains(b.x, b.y, BULLET_R) }) { deadB.add(b); continue }
            val hit = mutableListOf<Enemy>()
            for (e in enemies) {
                if (hypot(b.x-e.x, b.y-e.y) < BULLET_R+e.type.radius) {
                    val dmg = (b.dmg * e.type.gunMult).toInt()
                    applyDamageToEnemy(e, dmg, "gun", hit); deadB.add(b); break
                }
            }
            enemies.removeAll(hit)
        }
        bullets.removeAll(deadB)

        // Enemy bullets
        val deadEB = mutableListOf<Bullet>()
        for (b in enemyBullets) {
            b.x += b.vx*dt; b.y += b.vy*dt; b.life -= dt
            if (b.life<=0||b.x<0||b.x>WORLD_W||b.y<0||b.y>WORLD_H) { deadEB.add(b); continue }
            if (boxes.any { it.contains(b.x, b.y, ENEMY_BULLET_R) }) { deadEB.add(b); continue }
            if (playerInvincible <= 0f && hypot(b.x-playerX, b.y-playerY) < ENEMY_BULLET_R+PLAYER_R) {
                hurtPlayer(b.dmg); deadEB.add(b)
            }
        }
        enemyBullets.removeAll(deadEB)

        // Pickups
        val deadP = mutableListOf<Pickup>()
        for (p in pickups) {
            p.life -= dt; if (p.life <= 0f) { deadP.add(p); continue }
            if (hypot(p.x-playerX, p.y-playerY) < 26f+PLAYER_R) {
                collectPickup(p); deadP.add(p)
            }
        }
        pickups.removeAll(deadP)

        // Enemy AI
        val toKill = mutableListOf<Enemy>()
        for (e in enemies) {
            if (e.state == EnemyState.STUNNED) {
                e.stunTimer -= dt; if (e.stunTimer <= 0f) e.state = EnemyState.PATROL
                if (e.hurtFlash > 0f) e.hurtFlash -= dt; continue
            }
            val dx = playerX - e.x; val dy = playerY - e.y; val dist = hypot(dx, dy)
            e.angle = atan2(dy, dx)

            when (e.state) {
                EnemyState.PATROL -> {
                    // Walk patrol path around origin
                    e.patrolTimer -= dt
                    if (e.patrolTimer <= 0f) { e.patrolAngle += ((Math.random()-0.5)*1.4f).toFloat(); e.patrolTimer = 1.5f + (Math.random()*2f).toFloat() }
                    val tx = e.patrolOriginX + cos(e.patrolAngle)*e.type.patrolRadius
                    val ty = e.patrolOriginY + sin(e.patrolAngle)*e.type.patrolRadius
                    val pdx = tx-e.x; val pdy = ty-e.y; val pdist = hypot(pdx,pdy)
                    if (pdist > 10f) { val m=e.type.speed*0.4f*dt; e.x+=(pdx/pdist)*m; e.y+=(pdy/pdist)*m }
                    if (dist < e.type.sightRange) { e.state = EnemyState.ALERT; e.alertTimer = 0.5f }
                }
                EnemyState.ALERT -> {
                    e.alertTimer -= dt
                    if (e.alertTimer <= 0f) e.state = EnemyState.CHASE
                }
                EnemyState.CHASE -> {
                    val stop = PLAYER_R + e.type.radius
                    if (dist > stop) {
                        val m = (if (e.isBoss && e.bossPhase == 2) e.type.speed * 1.5f else e.type.speed) * dt
                        var nx = e.x+(dx/dist)*m; var ny = e.y+(dy/dist)*m
                        for (box in boxes) pushOutOfBox(nx, ny, e.type.radius) { ox,oy -> nx=ox; ny=oy }
                        e.x=nx; e.y=ny
                    }
                    // Melee
                    e.attackCooldown -= dt
                    if (dist < stop+14f && e.attackCooldown <= 0f) {
                        hurtPlayer(e.type.damage); e.attackCooldown = e.type.attackRate
                    }
                    // Ranged
                    if (e.type.canShoot) {
                        e.shootCooldown -= dt
                        val shootRange = if (e.isBoss) 9999f else 600f
                        if (e.shootCooldown <= 0f && dist < shootRange) {
                            e.shootCooldown = e.type.shootRate + (Math.random()*1.5f).toFloat()
                            val spread = if (e.isBoss) 0.15f else 0.28f
                            val sa = e.angle + ((Math.random()-0.5)*spread).toFloat()
                            enemyBullets.add(Bullet(e.x+cos(e.angle)*(e.type.radius+10f),
                                e.y+sin(e.angle)*(e.type.radius+10f),
                                cos(sa)*ENEMY_BULLET_SPD, sin(sa)*ENEMY_BULLET_SPD,
                                life=2.2f, dmg=e.type.damage/2))
                            // Boss phase 3: triple shot
                            if (e.isBoss && e.bossPhase == 3) {
                                for (offset in listOf(-0.5f, 0.5f)) {
                                    val a2 = e.angle + offset
                                    enemyBullets.add(Bullet(e.x+cos(e.angle)*(e.type.radius+10f),
                                        e.y+sin(e.angle)*(e.type.radius+10f),
                                        cos(a2)*ENEMY_BULLET_SPD, sin(a2)*ENEMY_BULLET_SPD, life=2.2f, dmg=e.type.damage/2))
                                }
                            }
                        }
                    }
                    // Boss phase transitions
                    if (e.isBoss) {
                        val hpFrac = e.hp.toFloat() / e.type.maxHp
                        e.bossPhase = when { hpFrac > 0.66f -> 1; hpFrac > 0.33f -> 2; else -> 3 }
                    }
                    if (dist > e.type.sightRange * 1.8f && !e.isBoss) e.state = EnemyState.PATROL
                }
                EnemyState.STUNNED -> {} // handled above
            }
            if (e.hurtFlash > 0f) e.hurtFlash -= dt
        }
        enemies.removeAll(toKill)

        // Lifetimes
        slashes.removeAll     { s -> s.life.also  { s.life -= dt } <= 0f }
        hammerSwings.removeAll{ h -> h.life.also  { h.life -= dt } <= 0f }
        dmgNums.removeAll     { d -> d.life.also  { d.life -= dt; d.y -= 50f*dt } <= 0f }

        // Camera
        val tcx = (playerX-surfaceWidth /2f).coerceIn(0f,(WORLD_W-surfaceWidth).coerceAtLeast(0f))
        val tcy = (playerY-surfaceHeight/2f).coerceIn(0f,(WORLD_H-surfaceHeight).coerceAtLeast(0f))
        camX += (tcx-camX)*0.12f; camY += (tcy-camY)*0.12f
    }

    private fun hurtPlayer(dmg: Int) {
        if (playerInvincible > 0f) return
        playerHP -= dmg; playerHurtFlash = 0.25f; playerInvincible = 0.8f
        spawnFrags(playerX, playerY, Color.rgb(30,90,200), 3)
        dmgNums.add(DmgNum(playerX, playerY-50f, "-$dmg", color = Color.rgb(255,80,80)))
        if (playerHP <= 0) { playerHP = 0; gameState = GameState.GAME_OVER; stateTimer = 3f }
    }

    private fun spawnFrags(x: Float, y: Float, color: Int, count: Int) {
        repeat(count) {
            val a = (Math.random() * 2 * Math.PI).toFloat()
            val spd = 80f + (Math.random() * 220f).toFloat()
            frags.add(BrickFrag(x, y, cos(a)*spd, sin(a)*spd - 60f,
                rot = (Math.random() * 360f).toFloat(),
                rotSpd = ((Math.random()-0.5)*400f).toFloat(),
                life = 0.5f + (Math.random()*0.5f).toFloat(),
                color = color,
                w = 8f + (Math.random()*10f).toFloat(),
                h = 6f + (Math.random()*8f).toFloat()))
        }
    }

    private fun collectPickup(p: Pickup) {
        when (p.type) {
            PickupType.HEART        -> { playerHP = (playerHP+20).coerceAtMost(PLAYER_MAX_HP); dmgNums.add(DmgNum(playerX,playerY-50f,"+20",color=Color.rgb(80,255,120))) }
            PickupType.RUPEE_GREEN  -> { rupees += 1;  dmgNums.add(DmgNum(p.x,p.y-30f,"+1",  color=Color.rgb(50,220,80))) }
            PickupType.RUPEE_BLUE   -> { rupees += 5;  dmgNums.add(DmgNum(p.x,p.y-30f,"+5",  color=Color.rgb(60,130,255))) }
            PickupType.RUPEE_RED    -> { rupees += 20; dmgNums.add(DmgNum(p.x,p.y-30f,"+20", color=Color.rgb(255,60,60))) }
            PickupType.KEY          -> { keys += 1;    dmgNums.add(DmgNum(p.x,p.y-30f,"KEY!",color=Color.rgb(255,210,50))) }
        }
    }

    private inline fun pushOutOfBox(px: Float, py: Float, r: Float, apply: (Float,Float)->Unit) {
        for (box in boxes) {
            if (!box.contains(px, py, r)) continue
            val ol = px+r-box.left; val or2 = box.right-(px-r)
            val ot = py+r-box.top;  val ob  = box.bottom-(py-r)
            val mn = minOf(ol,or2,ot,ob)
            val nx: Float; val ny: Float
            if      (mn == ol)  { nx = box.left   - r; ny = py }
            else if (mn == or2) { nx = box.right  + r; ny = py }
            else if (mn == ot)  { nx = px;             ny = box.top    - r }
            else                { nx = px;             ny = box.bottom + r }
            apply(nx, ny); return
        }
    }

    private fun restartGame() {
        playerX=WORLD_W/2f; playerY=WORLD_H*0.85f; playerHP=PLAYER_MAX_HP
        playerHurtFlash=0f; playerInvincible=0f; score=0; rupees=0; keys=0
        slashCd=0f; shootCd=0f; hammerCd=0f; boomerangCd=0f; chargeHeld=0f; isCharging=false
        bullets.clear(); enemyBullets.clear(); slashes.clear(); hammerSwings.clear()
        dmgNums.clear(); pickups.clear(); activeBoomerang=null; frags.clear()
        gameState=GameState.PLAYING; stateTimer=0f
        buildDungeon()
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
            val sw = surfaceWidth.toFloat(); val sh = surfaceHeight.toFloat()

            canvas.drawRect(0f,0f,sw,sh,bgPaint)
            canvas.save(); canvas.translate(-camX,-camY)

            // ---- Isometric brick floor tiles ----
            val SIDE_H = TILE * 0.18f  // depth of the brick side face
            val startTX = (camX/TILE).toInt() - 1; val startTY = (camY/TILE).toInt() - 1
            for (ty in startTY..(startTY+(sh/TILE).toInt()+2)) {
                for (tx in startTX..(startTX+(sw/TILE).toInt()+2)) {
                    val wx = tx*TILE; val wy = ty*TILE
                    if (wx < 0||wy < 0||wx >= WORLD_W||wy >= WORLD_H) {
                        canvas.drawRect(wx,wy,wx+TILE,wy+TILE,wallPaint); continue
                    }
                    val even = (tx+ty)%2==0
                    // Top face — alternating warm red/orange brick colours like Lego Classic
                    brickTopPaint.color  = if (even) Color.rgb(190,65,45)  else Color.rgb(175,55,38)
                    brickSidePaint.color = if (even) Color.rgb(120,38,25)  else Color.rgb(108,32,20)
                    // Side face (bottom strip giving depth illusion)
                    canvas.drawRect(wx, wy+TILE-SIDE_H, wx+TILE, wy+TILE, brickSidePaint)
                    // Top face
                    canvas.drawRect(wx, wy, wx+TILE, wy+TILE-SIDE_H, brickTopPaint)
                    // Mortar lines on top
                    canvas.drawRect(wx+0.75f, wy+0.75f, wx+TILE-0.75f, wy+TILE-SIDE_H-0.75f, brickMortarPaint)
                    // Studs — 2×2 grid of raised circles
                    val studR = TILE * 0.09f
                    val sx1 = wx + TILE*0.28f; val sx2 = wx + TILE*0.72f
                    val sy1 = wy + (TILE-SIDE_H)*0.30f; val sy2 = wy + (TILE-SIDE_H)*0.68f
                    studPaint.color  = if (even) Color.rgb(205,80,58) else Color.rgb(188,68,50)
                    studRimPaint.color = brickSidePaint.color
                    for (sx in listOf(sx1,sx2)) for (sy in listOf(sy1,sy2)) {
                        canvas.drawCircle(sx,sy,studR,studPaint)
                        canvas.drawCircle(sx,sy,studR,studRimPaint)
                    }
                }
            }
            // World border
            canvas.drawRect(0f,0f,WORLD_W,WORLD_H,borderPaint)

            // ---- Pickups ----
            for (p in pickups) {
                val pulse = 1f + 0.1f*sin(p.life*6f)
                when (p.type) {
                    PickupType.HEART -> {
                        heartGlowPaint.alpha = 60
                        canvas.drawCircle(p.x,p.y,22f*pulse,heartGlowPaint)
                        drawHeart(canvas,p.x,p.y,16f*pulse)
                    }
                    PickupType.RUPEE_GREEN,PickupType.RUPEE_BLUE,PickupType.RUPEE_RED -> {
                        val rp = when(p.type){PickupType.RUPEE_GREEN->rupeeGreenPaint; PickupType.RUPEE_BLUE->rupeeBluePaint; else->rupeeRedPaint}
                        drawRupee(canvas,p.x,p.y,14f*pulse,rp)
                    }
                    PickupType.KEY -> drawKey(canvas,p.x,p.y,pulse)
                }
                pickupTextPaint.alpha = if(p.life<3f) (p.life/3f*255).toInt() else 255
            }

            // ---- Boxes ----
            for (box in boxes) {
                if (box.broken) continue
                val rf = box.rectF()
                if (box.breakable) {
                    canvas.drawRoundRect(rf,12f,12f,potFillPaint)
                    canvas.drawRoundRect(rf,12f,12f,potStrokePaint)
                    // Pot highlight
                    canvas.drawOval(rf.left+8f,rf.top+6f,rf.right-8f,rf.top+20f,potHighlightPaint)
                } else {
                    canvas.drawRoundRect(rf,8f,8f,boxFillPaint)
                    canvas.drawRoundRect(RectF(rf.left+4f,rf.top+4f,rf.right-4f,rf.top+16f),4f,4f,boxHighlightPaint)
                    canvas.drawRoundRect(rf,8f,8f,boxStrokePaint)
                }
            }

            // ---- Player bullets ----
            for (b in bullets) { canvas.drawCircle(b.x,b.y,BULLET_R*2.2f,bulletGlowPaint); canvas.drawCircle(b.x,b.y,BULLET_R,bulletPaint) }
            // ---- Enemy bullets ----
            for (b in enemyBullets) { canvas.drawCircle(b.x,b.y,ENEMY_BULLET_R*2f,enemyBulletGlow); canvas.drawCircle(b.x,b.y,ENEMY_BULLET_R,enemyBulletPaint) }

            // ---- Boomerang ----
            activeBoomerang?.let { boom ->
                canvas.drawCircle(boom.x,boom.y,BOOMERANG_R*2.2f,boomGlowPaint)
                canvas.drawCircle(boom.x,boom.y,BOOMERANG_R,boomPaint)
            }

            // ---- Slashes ----
            for (slash in slashes) {
                val alpha = (slash.life/0.22f*200).toInt().coerceIn(0,200)
                slashPaint.color = if (slash.charged) Color.argb(alpha,120,200,255) else Color.argb(alpha,255,255,80)
                slashPath.reset(); slashPath.moveTo(slash.x,slash.y)
                val sa=slash.angle-slash.arc/2f; val ea=slash.angle+slash.arc/2f
                slashPath.lineTo(slash.x+cos(sa)*slash.radius,slash.y+sin(sa)*slash.radius)
                var a=sa; while(a<=ea){slashPath.lineTo(slash.x+cos(a)*slash.radius,slash.y+sin(a)*slash.radius);a+=0.08f}
                slashPath.close(); canvas.drawPath(slashPath,slashPaint)
            }

            // ---- Hammer swings ----
            for (h in hammerSwings) {
                val alpha=(h.life/0.30f*180).toInt().coerceIn(0,180)
                hammerSwingPaint.color=Color.argb(alpha,200,100,255)
                slashPath.reset(); slashPath.moveTo(h.x,h.y)
                val sa=h.angle-HAMMER_ARC/2f; val ea=h.angle+HAMMER_ARC/2f
                slashPath.lineTo(h.x+cos(sa)*HAMMER_R,h.y+sin(sa)*HAMMER_R)
                var a=sa; while(a<=ea){slashPath.lineTo(h.x+cos(a)*HAMMER_R,h.y+sin(a)*HAMMER_R);a+=0.08f}
                slashPath.close(); canvas.drawPath(slashPath,hammerSwingPaint)
            }

            // ---- Brick fragments ----
            for (f in frags) {
                fragPaint.color = Color.argb((f.life * 2f * 255).toInt().coerceIn(0,255), Color.red(f.color), Color.green(f.color), Color.blue(f.color))
                canvas.save()
                canvas.translate(f.x, f.y); canvas.rotate(f.rot)
                canvas.drawRoundRect(-f.w/2f,-f.h/2f,f.w/2f,f.h/2f,2f,2f,fragPaint)
                canvas.restore()
            }

            // ---- Enemies ----
            for (e in enemies) {
                val r = e.type.radius
                if (e.state == EnemyState.PATROL || e.state == EnemyState.ALERT) {
                    canvas.drawCircle(e.x,e.y,e.type.sightRange.coerceAtMost(350f),sightPaint)
                }
                val isStunned = e.state == EnemyState.STUNNED
                val hurtCol   = e.hurtFlash > 0f
                // Pick torso colour from enemy type, darken for side
                val tc  = if (hurtCol) Color.rgb(255,200,200) else if (isStunned) Color.rgb(150,150,220) else e.type.bodyColor
                val tcs = darken(tc, 0.55f)
                figEnemyTorsoTop.color  = tc
                figEnemyTorsoSide.color = tcs
                // Draw the minifig at enemy position
                drawMinifig(canvas, e.x, e.y, r, e.angle,
                    torsoTop  = figEnemyTorsoTop,
                    torsoSide = figEnemyTorsoSide,
                    headColor = if (hurtCol) Color.rgb(255,230,230) else Color.rgb(255,220,100),
                    legColor  = Color.rgb(35,35,35),
                    walkPhase = if (e.state == EnemyState.CHASE) (e.attackCooldown * 8f) else 0f,
                    showShooterRing = e.type.canShoot && !isStunned)
                if (e.state == EnemyState.ALERT) {
                    hudTextPaint.textAlign = Paint.Align.CENTER; hudTextPaint.textSize = 36f
                    canvas.drawText("!", e.x, e.y-r-28f, hudTextPaint)
                }
                if (isStunned) {
                    canvas.drawCircle(e.x, e.y-r-22f, 8f, stunPaint)
                    canvas.drawCircle(e.x-14f, e.y-r-18f, 5f, stunPaint)
                    canvas.drawCircle(e.x+14f, e.y-r-18f, 5f, stunPaint)
                }
                if (!e.isBoss) {
                    val bw=r*2.8f; val bh=7f; val bx=e.x-bw/2f; val by=e.y-r-26f
                    canvas.drawRoundRect(bx,by,bx+bw,by+bh,3f,3f,hpBarBgPaint)
                    hpBarEnemyPaint.color=e.type.bodyColor
                    canvas.drawRoundRect(bx,by,bx+bw*(e.hp.toFloat()/e.type.maxHp),by+bh,3f,3f,hpBarEnemyPaint)
                }
            }

            // ---- Player ----
            if (isCharging && chargeHeld > 0.1f) {
                val chargeP = (chargeHeld / CHARGE_THRESH).coerceAtMost(1f)
                playerChargePaint.alpha = (chargeP*200).toInt()
                canvas.drawCircle(playerX, playerY, PLAYER_R+14f+chargeP*20f, playerChargePaint)
            }
            drawMinifig(canvas, playerX, playerY, PLAYER_R, playerAngle,
                torsoTop  = figTorsoTopPaint,
                torsoSide = figTorsoSidePaint,
                headColor = Color.rgb(255,220,100),
                legColor  = Color.rgb(35,35,35),
                walkPhase = if (isMoving) walkCycle else 0f,
                showShooterRing = false)

            for (d in dmgNums) { dmgTextPaint.color=d.color; dmgTextPaint.alpha=(d.life*255).toInt().coerceIn(0,255); canvas.drawText(d.text,d.x,d.y,dmgTextPaint) }

            canvas.restore()

            // ---- Hurt flash overlay ----
            if (playerHurtFlash > 0f) {
                hurtOverlayPaint.color = Color.argb((playerHurtFlash/0.25f*90).toInt().coerceIn(0,90),255,0,0)
                canvas.drawRect(0f,0f,sw,sh,hurtOverlayPaint)
            }

            // ======== HUD ========

            // HP hearts row
            val heartSize = 24f; val heartSpacing = 56f; val heartY = 55f
            val totalHearts = 5; val hpPerHeart = PLAYER_MAX_HP / totalHearts
            for (i in 0 until totalHearts) {
                val hx = 30f + i * heartSpacing + heartSize
                val filled = playerHP >= (i+1)*hpPerHeart
                heartFillPaint.alpha  = if (filled) 255 else 60
                heartStrokePaint.alpha = if (filled) 255 else 60
                drawHeart(canvas, hx, heartY, heartSize)
            }

            // Rupees & keys
            hudTextPaint.textSize = 32f; hudTextPaint.textAlign = Paint.Align.LEFT
            drawRupee(canvas, 30f, 95f, 10f, rupeeGreenPaint)
            canvas.drawText("× $rupees", 52f, 102f, hudTextPaint)
            if (keys > 0) { drawKey(canvas, 130f, 90f, 1f); canvas.drawText("× $keys", 160f, 102f, hudTextPaint) }

            // Score top right
            hudTextPaint.textAlign = Paint.Align.RIGHT; hudTextPaint.textSize = 36f
            canvas.drawText("$score", sw-PANEL_W-16f, 50f, hudTextPaint)

            // Boss HP bar
            val boss = enemies.find { it.isBoss }
            if (boss != null) {
                val bw = (sw-PANEL_W)*0.7f; val bh = 28f; val bx = (sw-PANEL_W-bw)/2f; val by = 80f
                canvas.drawRoundRect(bx,by,bx+bw,by+bh,14f,14f,bossBarBgPaint)
                canvas.drawRoundRect(bx,by,bx+bw*(boss.hp.toFloat()/boss.type.maxHp),by+bh,14f,14f,bossBarFillPaint)
                hudTextPaint.textAlign=Paint.Align.CENTER; hudTextPaint.textSize=26f
                canvas.drawText("BOSS  ${boss.hp} / ${boss.type.maxHp}", (sw-PANEL_W)/2f, by-6f, hudTextPaint)
                // Phase indicator
                val phaseColor = when(boss.bossPhase){1->Color.rgb(200,200,80);2->Color.rgb(255,150,30);else->Color.rgb(255,60,60)}
                hudTextPaint.color=phaseColor
                canvas.drawText("Phase ${boss.bossPhase}", (sw-PANEL_W)/2f, by+bh+22f, hudTextPaint)
                hudTextPaint.color=Color.WHITE
            }

            // Weapon panel
            val panelX = panelLeft(sw)
            canvas.drawRect(panelX,0f,sw,sh,panelBgPaint)
            canvas.drawLine(panelX,0f,panelX,sh,panelBorderPaint)
            hudTextPaint.textSize=17f; hudTextPaint.textAlign=Paint.Align.CENTER; hudTextPaint.color=Color.argb(120,160,210,160)
            canvas.drawText("WEAPON",panelX+PANEL_W/2f,WEAPONS_ORDER.let{btnRect(sw,sh,0).top-12f},hudTextPaint)
            hudTextPaint.color=Color.WHITE
            WEAPONS_ORDER.forEachIndexed { i, w -> drawWeaponBtn(canvas, w, btnRect(sw,sh,i)) }

            // Hint
            if (score == 0) {
                hudTextPaint.textSize=20f; hudTextPaint.textAlign=Paint.Align.CENTER; hudTextPaint.color=Color.argb(160,255,255,255)
                canvas.drawText("Tap=attack  Hold=move  Swipe=slash  Hold+Release=charged",  (sw-PANEL_W)/2f, sh-60f, hudTextPaint)
                hudTextPaint.color=Color.WHITE
            }

            if (gameState == GameState.BOSS_INTRO) {
                overlayPaint.color=Color.argb((stateTimer.coerceAtMost(1f)*160).toInt().coerceIn(0,160),0,0,0)
                canvas.drawRect(0f,0f,sw,sh,overlayPaint)
                bigTextPaint.color=Color.rgb(255,60,60)
                canvas.drawText("BOSS APPEARS!", sw/2f, sh/2f, bigTextPaint)
            }

            // Victory
            if (gameState == GameState.VICTORY) {
                canvas.drawRect(0f,0f,sw,sh,overlayPaint)
                bigTextPaint.color=Color.rgb(255,220,60)
                canvas.drawText("DUNGEON CLEAR!", sw/2f, sh/2f-50f, bigTextPaint)
                canvas.drawText("Score: $score   Rupees: $rupees", sw/2f, sh/2f+30f, subtitlePaint)
                if (stateTimer <= 0f) canvas.drawText("Tap to play again", sw/2f, sh/2f+90f, subtitlePaint)
            }

            // Game over
            if (gameState == GameState.GAME_OVER) {
                canvas.drawRect(0f,0f,sw,sh,overlayPaint)
                bigTextPaint.color=Color.WHITE
                canvas.drawText("GAME OVER", sw/2f, sh/2f-50f, bigTextPaint)
                canvas.drawText("Score: $score", sw/2f, sh/2f+30f, subtitlePaint)
                if (stateTimer <= 0f) canvas.drawText("Tap to retry", sw/2f, sh/2f+90f, subtitlePaint)
            }

        } finally { canvas?.let { holder.unlockCanvasAndPost(it) } }
    }

    // =========================================================================
    // Minifig draw — isometric-feel 3D character
    // cx/cy = feet position, r = scale radius, angle = facing direction
    // =========================================================================
    private fun drawMinifig(canvas: Canvas, cx: Float, cy: Float, r: Float, angle: Float,
                             torsoTop: Paint, torsoSide: Paint,
                             headColor: Int, legColor: Int,
                             walkPhase: Float, showShooterRing: Boolean) {
        val scale  = r / 26f
        val bob    = if (walkPhase != 0f) sin(walkPhase) * 2.5f * scale else 0f
        val armSwing = if (walkPhase != 0f) sin(walkPhase) * 8f * scale else 0f
        val legSwing = if (walkPhase != 0f) sin(walkPhase) * 6f * scale else 0f

        // Dimensions (top-down with perspective squash)
        val tw = 20f * scale   // torso width
        val th = 14f * scale   // torso height (squashed for perspective)
        val td = 5f  * scale   // torso depth (side face)
        val lw = 8f  * scale   // leg width
        val lh = 8f  * scale   // leg height
        val ld = 4f  * scale   // leg depth
        val hr = 11f * scale   // head radius
        val hd = 4f  * scale   // head depth

        // Anchor: cy is the centre, we stack from bottom up
        val legY    = cy + th/2f + lh/2f - bob
        val torsoY  = cy - bob
        val headY   = cy - th/2f - hr - 2f*scale - bob

        // Ground shadow
        canvas.drawOval(cx-tw*0.9f, cy+th/2f+ld, cx+tw*0.9f, cy+th/2f+ld+5f*scale, shadowPaint)

        // ---- Legs ----
        val lx1 = cx - lw * 0.7f; val lx2 = cx + lw * 0.7f
        val leg1Offset = legSwing; val leg2Offset = -legSwing
        // Leg side depth
        mfLegSidePaint.color = darken(legColor, 0.55f)
        mfLegTopPaint.color  = legColor
        // Left leg
        canvas.drawRect(lx1-lw/2f, legY-lh/2f+leg1Offset, lx1+lw/2f, legY+lh/2f+leg1Offset+ld, mfLegSidePaint)
        canvas.drawRect(lx1-lw/2f, legY-lh/2f+leg1Offset, lx1+lw/2f, legY+lh/2f+leg1Offset, mfLegTopPaint)
        // Right leg
        canvas.drawRect(lx2-lw/2f, legY-lh/2f+leg2Offset, lx2+lw/2f, legY+lh/2f+leg2Offset+ld, mfLegSidePaint)
        canvas.drawRect(lx2-lw/2f, legY-lh/2f+leg2Offset, lx2+lw/2f, legY+lh/2f+leg2Offset, mfLegTopPaint)

        // ---- Arms ----
        val ar = 5f*scale
        val armColor = torsoTop.color
        mfArmFillPaint.color = armColor
        mfArmSidePaint.color = darken(armColor, 0.6f)
        val arm1Y = torsoY + armSwing; val arm2Y = torsoY - armSwing
        // Left arm
        canvas.drawOval(cx-tw/2f-ar*1.8f, arm1Y-ar, cx-tw/2f, arm1Y+ar, mfArmFillPaint)
        canvas.drawOval(cx-tw/2f-ar*1.8f, arm1Y-ar+ld, cx-tw/2f, arm1Y+ar+ld, mfArmSidePaint)
        // Right arm
        canvas.drawOval(cx+tw/2f, arm2Y-ar, cx+tw/2f+ar*1.8f, arm2Y+ar, mfArmFillPaint)
        canvas.drawOval(cx+tw/2f, arm2Y-ar+ld, cx+tw/2f+ar*1.8f, arm2Y+ar+ld, mfArmSidePaint)

        // ---- Torso ----
        // Side face (depth)
        canvas.drawRect(cx-tw/2f, torsoY+th/2f, cx+tw/2f, torsoY+th/2f+td, torsoSide)
        // Top face
        canvas.drawRoundRect(cx-tw/2f, torsoY-th/2f, cx+tw/2f, torsoY+th/2f, 3f*scale, 3f*scale, torsoTop)
        // Outline
        figOutlinePaint.strokeWidth = 2f*scale
        canvas.drawRoundRect(cx-tw/2f, torsoY-th/2f, cx+tw/2f, torsoY+th/2f, 3f*scale, 3f*scale, figOutlinePaint)

        // ---- Head ----
        mfHeadFillPaint.color = headColor
        mfHeadSidePaint.color = darken(headColor, 0.6f)
        // Head depth
        canvas.drawOval(cx-hr, headY+hr*0.7f, cx+hr, headY+hr*0.7f+hd*1.5f, mfHeadSidePaint)
        // Head top
        canvas.drawCircle(cx, headY, hr, mfHeadFillPaint)
        canvas.drawCircle(cx, headY, hr, figOutlinePaint)
        // Face — dots for eyes, curved smile, all relative to facing direction
        val ex1 = cx + cos(angle - 0.4f) * hr * 0.55f
        val ey1 = headY + sin(angle - 0.4f) * hr * 0.55f
        val ex2 = cx + cos(angle + 0.4f) * hr * 0.55f
        val ey2 = headY + sin(angle + 0.4f) * hr * 0.55f
        canvas.drawCircle(ex1, ey1, 2.2f*scale, mfEyeDotPaint)
        canvas.drawCircle(ex2, ey2, 2.2f*scale, mfEyeDotPaint)
        // Smile arc — small arc centred between eyes, facing outward
        val smileX = cx + cos(angle)*hr*0.3f
        val smileY = headY + sin(angle)*hr*0.3f
        val smileR = 4f*scale
        figFacePaint.strokeWidth = 1.8f*scale
        canvas.drawArc(smileX-smileR, smileY-smileR, smileX+smileR, smileY+smileR,
            Math.toDegrees(angle.toDouble()).toFloat() + 30f, 120f, false, figFacePaint)

        // Shooter ring
        if (showShooterRing) {
            mfShooterRingPaint.strokeWidth = 2f*scale
            canvas.drawCircle(cx, cy, r+6f*scale, mfShooterRingPaint)
        }
    }

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color)   * factor).toInt().coerceIn(0,255),
        (Color.green(color) * factor).toInt().coerceIn(0,255),
        (Color.blue(color)  * factor).toInt().coerceIn(0,255))

    // =========================================================================
    // Draw helpers
    // =========================================================================
    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s = r*0.9f
        heartPath.reset()
        heartPath.moveTo(cx, cy+s*0.6f)
        heartPath.cubicTo(cx-s*1.4f,cy-s*0.2f,cx-s*1.4f,cy-s*1.0f,cx,cy-s*0.3f)
        heartPath.cubicTo(cx+s*1.4f,cy-s*1.0f,cx+s*1.4f,cy-s*0.2f,cx,cy+s*0.6f)
        heartPath.close()
        canvas.drawPath(heartPath,heartFillPaint); canvas.drawPath(heartPath,heartStrokePaint)
    }

    private fun drawRupee(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        rupeePath.reset()
        rupeePath.moveTo(cx,cy-r*1.4f); rupeePath.lineTo(cx+r,cy-r*0.3f)
        rupeePath.lineTo(cx+r*0.6f,cy+r*1.1f); rupeePath.lineTo(cx-r*0.6f,cy+r*1.1f)
        rupeePath.lineTo(cx-r,cy-r*0.3f); rupeePath.close()
        canvas.drawPath(rupeePath,paint)
    }

    private fun drawKey(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        canvas.drawCircle(cx,cy-10f*scale,9f*scale,keyPaint)
        keyRimPaint.strokeWidth = 5f*scale
        canvas.drawCircle(cx,cy-10f*scale,9f*scale,keyRimPaint)
        keyShaftPaint.strokeWidth = 5f*scale
        canvas.drawLine(cx,cy,cx,cy+22f*scale,keyShaftPaint)
        keyShaftPaint.strokeWidth = 4f*scale
        canvas.drawLine(cx,cy+14f*scale,cx+6f*scale,cy+14f*scale,keyShaftPaint)
        keyShaftPaint.strokeWidth = 5f*scale  // reset
    }

    private fun drawWeaponBtn(canvas: Canvas, weapon: Weapon, rect: RectF) {
        val active = activeWeapon == weapon
        val cd     = when(weapon){Weapon.SWORD->slashCd; Weapon.GUN->shootCd; Weapon.HAMMER->hammerCd; Weapon.BOOMERANG->boomerangCd}
        val maxCd  = when(weapon){Weapon.SWORD->SLASH_CD;Weapon.GUN->SHOOT_CD;Weapon.HAMMER->HAMMER_CD;Weapon.BOOMERANG->BOOMERANG_CD}
        val cx=rect.centerX(); val cy=rect.centerY(); val r=minOf(rect.width(),rect.height())/2f-6f
        canvas.drawRoundRect(rect,14f,14f,if(active) btnActivePaint else btnInactivePaint)
        canvas.drawRoundRect(rect,14f,14f,if(active) btnActiveBorder else btnInactiveBorder)
        if(cd>0f) canvas.drawArc(cx-r,cy-r-12f,cx+r,cy+r-12f,-90f,360f*(cd/maxCd),false,cdArcPaint)
        val baseColor = when(weapon){ Weapon.SWORD->Color.rgb(255,255,100); Weapon.GUN->Color.rgb(255,220,60); Weapon.HAMMER->Color.rgb(200,100,255); Weapon.BOOMERANG->Color.rgb(80,200,255) }
        val dimColor  = Color.argb(130,(Color.red(baseColor)*0.7f).toInt(),(Color.green(baseColor)*0.7f).toInt(),(Color.blue(baseColor)*0.7f).toInt())
        val col = if(active) baseColor else dimColor
        when(weapon) {
            Weapon.SWORD    -> drawSwordIcon   (canvas,cx,cy-12f,col)
            Weapon.GUN      -> drawGunIcon     (canvas,cx,cy-12f,col)
            Weapon.HAMMER   -> drawHammerIcon  (canvas,cx,cy-12f,col)
            Weapon.BOOMERANG-> drawBoomerangIcon(canvas,cx,cy-12f,col)
        }
        btnTextPaint.color = if(active) Color.rgb(120,255,160) else Color.argb(130,160,200,160)
        canvas.drawText(when(weapon){Weapon.SWORD->"SWORD";Weapon.GUN->"GUN";Weapon.HAMMER->"HAMMER";Weapon.BOOMERANG->"BOOM"},cx,rect.bottom-10f,btnTextPaint)
    }

    private fun drawSwordIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        iconPaint.color=color; iconFillPaint.color=color
        canvas.drawLine(cx,cy-26f,cx,cy+12f,iconPaint); canvas.drawLine(cx-13f,cy+4f,cx+13f,cy+4f,iconPaint)
        canvas.drawCircle(cx,cy+18f,5f,iconFillPaint)
        canvas.drawPath(Path().apply{moveTo(cx-5f,cy-18f);lineTo(cx+5f,cy-18f);lineTo(cx,cy-30f);close()},iconFillPaint)
    }
    private fun drawGunIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        iconPaint.color=color; iconFillPaint.color=color
        canvas.drawLine(cx-18f,cy-5f,cx+18f,cy-5f,iconPaint)
        canvas.drawPath(Path().apply{moveTo(cx-18f,cy-9f);lineTo(cx+14f,cy-9f);lineTo(cx+14f,cy+5f);lineTo(cx-3f,cy+5f);lineTo(cx-3f,cy+16f);lineTo(cx-13f,cy+16f);lineTo(cx-13f,cy+5f);lineTo(cx-18f,cy+5f);close()},iconFillPaint)
        iconPaint.strokeWidth=2.5f; canvas.drawLine(cx-1f,cy+5f,cx-7f,cy+13f,iconPaint); iconPaint.strokeWidth=3.5f
    }
    private fun drawHammerIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        iconPaint.color=color; iconFillPaint.color=color
        canvas.drawLine(cx,cy+26f,cx,cy-6f,iconPaint)
        canvas.drawPath(Path().apply{moveTo(cx-17f,cy-6f);lineTo(cx+17f,cy-6f);lineTo(cx+17f,cy-24f);lineTo(cx-17f,cy-24f);close()},iconFillPaint)
        iconPaint.strokeWidth=2f; canvas.drawLine(cx-15f,cy-8f,cx+15f,cy-8f,iconPaint); iconPaint.strokeWidth=3.5f
    }
    private fun drawBoomerangIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        iconPaint.color=color; iconPaint.strokeWidth=4.5f
        canvas.drawArc(cx-18f,cy-18f,cx+18f,cy+18f,200f,250f,false,iconPaint)
        iconFillPaint.color=color; canvas.drawCircle(cx+14f,cy+8f,5f,iconFillPaint)
        iconPaint.strokeWidth=3.5f
    }

    override fun onDetachedFromWindow() { stopGameLoop(); super.onDetachedFromWindow() }
    fun pause()  { isRunning = false }
    fun resume() { if (surfaceReady) startGameLoop() }
    fun reset()  { restartGame() }
}