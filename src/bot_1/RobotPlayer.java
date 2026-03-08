package bot_1;

import battlecode.common.*;
import java.util.Random;

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

    // Message Declare 
    // Tipe msg di bit 30-31 
    // (00=ada musuh, 01=ketemu ruin, 10=cat dikit)
    // Koor x ada di bit 29-15, Koor y ada di bit 14-0 
    static final int MSG_ENEMY    = 0;
    static final int MSG_RUIN     = 1 << 30;
    static final int MSG_PAINTLOW = 2 << 30; 

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            turnCount++;
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case MOPPER:   runMopper(rc);   break;
                    case SPLASHER: runSplasher(rc); break;
                    default:       runTower(rc);    break;
                }
            } catch (GameActionException e) {
                System.out.println("GameActionException: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Exception: " + e.getMessage());
            } finally {
                Clock.yield();
            }
        }
    }

    public static void runTower(RobotController rc) throws GameActionException {
        // atk musuh yang dekat (protect tower)
        attackNearestEnemy(rc);

        // untuk msg
        boolean paintLow = false;
        boolean enemyNear = false; 
        MapLocation ruinLoc = null;

        // Baca msg
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int data = m.getBytes();
            int msgType = data & (3 << 30);
            if (msgType == MSG_PAINTLOW) {
                paintLow = true;
            } else if (msgType == MSG_ENEMY){
                enemyNear = true;
            } else if (msgType == MSG_RUIN) {
                int x = (data >> 15) & 0x7FFF;
                int y = data & 0x7FFF;
                ruinLoc = new MapLocation(x, y);
            }
        }

        int solCount = 0;
        int mopCount = 0;
        int splashCount = 0;
        int allySolLowPaint = 0;
        int enemyPaintTiles = 0;
        int emptyTiles = 0;

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.type == UnitType.SOLDIER) {
                solCount++;
                int paintPct = (int)(100.0 * ally.paintAmount / ally.type.paintCapacity);
                if (paintPct < 40) allySolLowPaint++;
            }
            if (ally.type == UnitType.MOPPER) {
                mopCount++;
            }
            if (ally.type == UnitType.SPLASHER) {
                splashCount++;
            }
        }

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : nearbyTiles) {
            PaintType p = tile.getPaint();
            if (p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY) {
                enemyPaintTiles++;
            }
            if (p == PaintType.EMPTY && tile.isPassable()) {
                emptyTiles++;
            }
        }

        UnitType spawnUnit;
        if (paintLow) { 
            spawnUnit = UnitType.MOPPER;
        } else if (enemyNear) { 
            // musuh dekat & banyak cat musuh -> mopper, cat musuh dikit -> soldier
            if(enemyPaintTiles >= 5){
                spawnUnit = UnitType.MOPPER;
            } else {
                spawnUnit = UnitType.SOLDIER;
            }
        } else if (ruinLoc != null) { 
            spawnUnit = UnitType.SOLDIER;
        } else if (allySolLowPaint >= 2){
            spawnUnit = UnitType.MOPPER;
        } else if (mopCount * 4 < solCount){ // jumlah sol kebanyakan
            spawnUnit = UnitType.MOPPER;
        } else if (emptyTiles > 15 && splashCount < 2){
            spawnUnit = UnitType.SPLASHER;
        } else { // default
            spawnUnit = UnitType.SOLDIER;
        }

        // best loc spawn
        Direction bestDir = null;
        int bestScore = -100000;

        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (!rc.canBuildRobot(spawnUnit, spawnLoc)) {
                continue;
            }

            int score = evalLoc(rc, spawnLoc, spawnUnit, ruinLoc);
            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir != null) {
            MapLocation spawnLoc = rc.getLocation().add(bestDir);
            rc.buildRobot(spawnUnit, spawnLoc);
        }
    }

    // eval loc spawn
    static int evalLoc(RobotController rc, MapLocation loc, UnitType unit, MapLocation ruinLoc) throws GameActionException {
        int score = 0;

        // prefer loc own paint
        PaintType paint = rc.senseMapInfo(loc).getPaint();
        if (paint.isAlly()) {
            score += 100;
        } else if (paint == PaintType.EMPTY){
            if(unit == UnitType.SOLDIER){
                score += 30;
            } else {
                score -= 100;
            }
        } else { // cat musuh
            score -= 300;
        }

        // jika soldier, prefer close to ruin
        if (unit == UnitType.SOLDIER && ruinLoc != null) {
            int distToRuin = loc.distanceSquaredTo(ruinLoc);
            score -= distToRuin; 
        }

        // area banyak empty tiles
        if (unit == UnitType.SPLASHER) {
            MapInfo[] nearby = rc.senseNearbyMapInfos(loc, 4);
            for (MapInfo m : nearby) {
                if (m.getPaint() == PaintType.EMPTY && m.isPassable()) {
                    score += 5;
                }
            }
        }

        // avoid spawn dekat musuh
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(loc, 10, rc.getTeam().opponent());
        if (nearbyEnemies.length > 0) {
            score -= 80 * nearbyEnemies.length;
        }

        return score;
    }

    // atk musuh terdekat
    static void attackNearestEnemy(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0 && rc.isActionReady()) {
            // atk musuh HP terendah
            RobotInfo weakest = enemies[0];
            for (RobotInfo e : enemies) {
                if (e.health < weakest.health) {
                    weakest = e;
                }
            }
            if (rc.canAttack(weakest.location)) {
                rc.attack(weakest.location);
            }
        }
    }

    public static void runSoldier(RobotController rc) throws GameActionException {

    }

    public static void runMopper(RobotController rc) throws GameActionException {

    }

    public static void runSplasher(RobotController rc) throws GameActionException {

    }

    
}