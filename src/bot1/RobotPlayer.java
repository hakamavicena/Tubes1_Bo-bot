package bot1;

import java.util.Random;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.PaintType;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;

/**
 * RobotPlayer dengan strategi Greedy Berlapis.
 *
 * Tower  : spawn unit berdasarkan prioritas (mopper jika paint rendah, soldier jika kurang defense, splasher untuk ekspansi)
 * Soldier: greedy berlapis — defend > ruin assist > territorial > default patrol
 * Mopper : greedy berlapis — finish enemy > mop swing > chase > escort
 * Splasher: greedy berlapis — retreat jika paint kritis > ruin assist > AOE eksplorasi > default patrol
 */
public class RobotPlayer {

    static int turnCount = 0;

    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    // -------------------------------------------------------------------------
    // Threshold konstanta untuk greedy decision
    // -------------------------------------------------------------------------
    static final int PAINT_REFILL_THRESHOLD  = 50;  // paint <= ini → retreat ke tower
    static final int PAINT_SAFE_THRESHOLD    = 150; // paint >= ini → aman untuk aksi berat
    static final int MIN_SOLDIER_COUNT       = 2;   // minimum soldier sebelum spawn splasher/mopper
    static final int CHIPS_SPAWN_THRESHOLD   = 150; // minimum chips sebelum spawn unit baru

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        System.out.println("I'm alive");
        rc.setIndicatorString("Greedy Berlapis Active");

        while (true) {
            turnCount += 1;
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case MOPPER:   runMopper(rc);   break;
                    case SPLASHER: runSplasher(rc); break;
                    default:       runTower(rc);    break;
                }
            } catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // =========================================================================
    // TOWER — Greedy Berlapis
    // =========================================================================
    public static void runTower(RobotController rc) throws GameActionException {

        // LAYER 1 — Serang musuh dalam range dulu sebelum apapun
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : nearbyEnemies) {
            if (rc.canAttack(enemy.getLocation())) {
                rc.attack(enemy.getLocation());
                rc.setIndicatorString("Tower: ATTACKING enemy at " + enemy.getLocation());
                break;
            }
        }

        // Hitung berapa soldier dan mopper yang ada di sekitar tower
        RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        int soldierCount = 0;
        int mopperCount  = 0;
        for (RobotInfo ally : allyRobots) {
            if (ally.getType() == UnitType.SOLDIER)  soldierCount++;
            if (ally.getType() == UnitType.MOPPER)   mopperCount++;
        }

        // Pilih lokasi spawn acak di sekitar tower
        Direction spawnDir = directions[rng.nextInt(directions.length)];
        MapLocation spawnLoc = rc.getLocation().add(spawnDir);

        // LAYER 2 — Pastikan ada minimum soldier untuk defense
        if (soldierCount < MIN_SOLDIER_COUNT) {
            if (rc.canBuildRobot(UnitType.SOLDIER, spawnLoc)) {
                rc.buildRobot(UnitType.SOLDIER, spawnLoc);
                rc.setIndicatorString("Tower: SPAWN soldier (defense)");
                return;
            }
        }

        // LAYER 3 — Spawn mopper jika mopper kurang (jaga rasio 1 mopper per 2 soldier)
        if (mopperCount * 2 < soldierCount) {
            if (rc.canBuildRobot(UnitType.MOPPER, spawnLoc)) {
                rc.buildRobot(UnitType.MOPPER, spawnLoc);
                rc.setIndicatorString("Tower: SPAWN mopper (paint economy)");
                return;
            }
        }

        // LAYER 4 — Ekspansi: spawn splasher untuk memperluas territory
        if (rc.canBuildRobot(UnitType.SPLASHER, spawnLoc)) {
            rc.buildRobot(UnitType.SPLASHER, spawnLoc);
            rc.setIndicatorString("Tower: SPAWN splasher (expansion)");
            return;
        }

        // Fallback: coba spawn soldier di arah lain
        for (Direction dir : directions) {
            MapLocation loc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(UnitType.SOLDIER, loc)) {
                rc.buildRobot(UnitType.SOLDIER, loc);
                rc.setIndicatorString("Tower: SPAWN soldier (fallback)");
                break;
            }
        }

        // Baca pesan masuk (untuk koordinasi di masa depan)
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message from #" + m.getSenderID() + ": " + m.getBytes());
        }
    }

    // =========================================================================
    // SOLDIER — Greedy Berlapis
    // =========================================================================
    public static void runSoldier(RobotController rc) throws GameActionException {

        int currentPaint = rc.getPaint();
        int maxPaint     = rc.getType().paintCapacity;

        // LAYER 1 — Survival: paint kritis → retreat ke tower terdekat
        if (currentPaint <= PAINT_REFILL_THRESHOLD) {
            rc.setIndicatorString("Soldier: RETREATING (paint low " + currentPaint + ")");
            moveToNearestTower(rc);
            return;
        }

        // LAYER 2 — Immediate threat: ada musuh dalam attack range → serang
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (rc.canAttack(enemy.getLocation())) {
                rc.attack(enemy.getLocation());
                rc.setIndicatorString("Soldier: ATTACKING enemy at " + enemy.getLocation());
                // Tetap coba bergerak menjauh setelah serang
                Direction retreatDir = rc.getLocation().directionTo(enemy.getLocation()).opposite();
                if (rc.canMove(retreatDir)) rc.move(retreatDir);
                return;
            }
        }

        // LAYER 3 — Objective: ada ruin terdekat → bantu bangun tower
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                curRuin = tile;
                break;
            }
        }

        if (curRuin != null) {
            rc.setIndicatorString("Soldier: HEADING to ruin at " + curRuin.getMapLocation());
            buildTowerAtRuin(rc, curRuin);
            return;
        }

        // LAYER 4 — Territorial: cat tile musuh dalam range
        for (MapInfo tile : nearbyTiles) {
            if (tile.getPaint() == PaintType.ENEMY_PRIMARY || tile.getPaint() == PaintType.ENEMY_SECONDARY) {
                if (rc.canAttack(tile.getMapLocation())) {
                    rc.attack(tile.getMapLocation());
                    rc.setIndicatorString("Soldier: REPAINTING enemy tile");
                    break;
                }
            }
        }

        // LAYER 5 — Default: jalan random sambil cat tile di bawah kaki
        Direction dir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(dir)) rc.move(dir);

        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation());
            rc.setIndicatorString("Soldier: PATROLLING and painting");
        }
    }

    // =========================================================================
    // MOPPER — Greedy Berlapis
    // =========================================================================
    public static void runMopper(RobotController rc) throws GameActionException {

        // LAYER 1 — Finish low-HP enemy: cari musuh dengan HP terendah dalam range
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo weakestEnemy = null;
        for (RobotInfo enemy : enemies) {
            if (rc.canAttack(enemy.getLocation())) {
                if (weakestEnemy == null || enemy.getHealth() < weakestEnemy.getHealth()) {
                    weakestEnemy = enemy;
                }
            }
        }
        if (weakestEnemy != null) {
            rc.attack(weakestEnemy.getLocation());
            rc.setIndicatorString("Mopper: FINISHING enemy HP=" + weakestEnemy.getHealth());
        }

        // LAYER 2 — Mop Swing AOE: jika ada musuh dalam jangkauan swing
        Direction bestSwingDir = null;
        for (Direction dir : directions) {
            if (rc.canMopSwing(dir)) {
                bestSwingDir = dir;
                break;
            }
        }
        if (bestSwingDir != null) {
            rc.mopSwing(bestSwingDir);
            rc.setIndicatorString("Mopper: MOP SWING at " + bestSwingDir);
            return;
        }

        // LAYER 3 — Chase enemy paint tiles: dekati tile musuh terdekat
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapLocation closestEnemyPaint = null;
        int minDist = Integer.MAX_VALUE;
        for (MapInfo tile : nearbyTiles) {
            if (tile.getPaint() == PaintType.ENEMY_PRIMARY || tile.getPaint() == PaintType.ENEMY_SECONDARY) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < minDist) {
                    minDist = dist;
                    closestEnemyPaint = tile.getMapLocation();
                }
            }
        }
        if (closestEnemyPaint != null) {
            Direction dir = rc.getLocation().directionTo(closestEnemyPaint);
            if (rc.canMove(dir)) rc.move(dir);
            if (rc.canAttack(closestEnemyPaint)) rc.attack(closestEnemyPaint);
            rc.setIndicatorString("Mopper: CHASING enemy paint");
            return;
        }

        // LAYER 4 — Escort: follow soldier terdekat
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo nearestSoldier = null;
        int minSoldierDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.SOLDIER) {
                int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
                if (dist < minSoldierDist) {
                    minSoldierDist = dist;
                    nearestSoldier = ally;
                }
            }
        }
        if (nearestSoldier != null) {
            Direction dir = rc.getLocation().directionTo(nearestSoldier.getLocation());
            if (rc.canMove(dir)) rc.move(dir);
            rc.setIndicatorString("Mopper: ESCORTING soldier");
            return;
        }

        // LAYER 5 — Default: jalan random
        Direction dir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(dir)) rc.move(dir);
        rc.setIndicatorString("Mopper: PATROLLING");

        updateEnemyRobots(rc);
    }

    // =========================================================================
    // SPLASHER — Greedy Berlapis
    // =========================================================================
    public static void runSplasher(RobotController rc) throws GameActionException {

        int currentPaint = rc.getPaint();
        int maxPaint     = rc.getType().paintCapacity;
        int paintPercent = (currentPaint * 100) / maxPaint;

        // LAYER 1 — Survival: paint <= 25% → retreat ke tower terdekat untuk refill
        if (paintPercent <= 25) {
            rc.setIndicatorString("Splasher: RETREATING paint=" + paintPercent + "%");
            moveToNearestTower(rc);
            return;
        }

        // LAYER 2 — Ruin Assist: ada ruin terdekat DAN paint > 25% → bantu bangun tower
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                curRuin = tile;
                break;
            }
        }

        if (curRuin != null) {
            rc.setIndicatorString("Splasher: ASSISTING ruin at " + curRuin.getMapLocation());
            buildTowerAtRuin(rc, curRuin);
            return;
        }

        // LAYER 3 — AOE Eksplorasi: splash ke tile acak sekitar
        // Splasher memprioritaskan tile yang belum dicat atau tile musuh
        MapLocation bestSplashTarget = null;
        int bestScore = -1;

        for (MapInfo tile : nearbyTiles) {
            if (rc.canAttack(tile.getMapLocation())) {
                int score = 0;
                PaintType paint = tile.getPaint();
                if (paint == PaintType.ENEMY_PRIMARY || paint == PaintType.ENEMY_SECONDARY) score = 3;
                else if (paint == PaintType.EMPTY) score = 2;
                else if (!paint.isAlly()) score = 1;

                if (score > bestScore) {
                    bestScore = score;
                    bestSplashTarget = tile.getMapLocation();
                }
            }
        }

        if (bestSplashTarget != null && bestScore > 0) {
            rc.attack(bestSplashTarget);
            rc.setIndicatorString("Splasher: AOE SPLASH at " + bestSplashTarget + " score=" + bestScore);
            // Bergerak ke arah target splash untuk posisi lebih baik turn berikutnya
            Direction dir = rc.getLocation().directionTo(bestSplashTarget);
            if (rc.canMove(dir)) rc.move(dir);
            return;
        }

        // LAYER 4 — Default: jalan random sambil paint tile di bawah kaki
        Direction dir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(dir)) rc.move(dir);

        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation());
        }
        rc.setIndicatorString("Splasher: DEFAULT patrol paint=" + paintPercent + "%");
    }

    // =========================================================================
    // HELPER — Bangun tower di ruin (dipakai soldier & splasher)
    // =========================================================================
    static void buildTowerAtRuin(RobotController rc, MapInfo ruinInfo) throws GameActionException {
        MapLocation targetLoc = ruinInfo.getMapLocation();
        Direction dir = rc.getLocation().directionTo(targetLoc);

        // Gerak mendekat ke ruin
        if (rc.canMove(dir)) rc.move(dir);

        // Mark pola tower jika belum
        MapLocation shouldBeMarked = targetLoc.subtract(dir);
        if (rc.senseMapInfo(shouldBeMarked).getMark() == PaintType.EMPTY
                && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
            System.out.println("Marked tower pattern at " + targetLoc);
        }

        // Cat tile sesuai pola yang sudah di-mark
        for (MapInfo patternTile : rc.senseNearbyMapInfos(targetLoc, 8)) {
            if (patternTile.getMark() != patternTile.getPaint()
                    && patternTile.getMark() != PaintType.EMPTY) {
                boolean useSecondary = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                if (rc.canAttack(patternTile.getMapLocation())) {
                    rc.attack(patternTile.getMapLocation(), useSecondary);
                }
            }
        }

        // Complete tower jika pola sudah penuh
        if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)) {
            rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
            rc.setTimelineMarker("Tower built", 0, 255, 0);
            System.out.println("Built a tower at " + targetLoc + "!");
        }
    }

    // =========================================================================
    // HELPER — Bergerak menuju tower ally terdekat (untuk retreat/refill)
    // =========================================================================
    static void moveToNearestTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo nearestTower = null;
        int minDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            // Tower ditandai dengan tipe yang bukan SOLDIER/MOPPER/SPLASHER
            UnitType t = ally.getType();
            if (t != UnitType.SOLDIER && t != UnitType.MOPPER && t != UnitType.SPLASHER) {
                int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearestTower = ally;
                }
            }
        }

        if (nearestTower != null) {
            Direction dir = rc.getLocation().directionTo(nearestTower.getLocation());
            if (rc.canMove(dir)) rc.move(dir);
        } else {
            // Tidak ada tower terlihat → jalan random sambil cari
            Direction dir = directions[rng.nextInt(directions.length)];
            if (rc.canMove(dir)) rc.move(dir);
        }
    }

    // =========================================================================
    // HELPER — Update & kirim info musuh ke ally (dipakai mopper)
    // =========================================================================
    public static void updateEnemyRobots(RobotController rc) throws GameActionException {
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemyRobots.length != 0) {
            rc.setIndicatorString("Nearby enemies: " + enemyRobots.length);
            RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
            if (rc.getRoundNum() % 20 == 0) {
                for (RobotInfo ally : allyRobots) {
                    if (rc.canSendMessage(ally.location, enemyRobots.length)) {
                        rc.sendMessage(ally.location, enemyRobots.length);
                    }
                }
            }
        }
    }
}