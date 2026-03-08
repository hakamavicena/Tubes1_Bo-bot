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
        MapLocation myLoc = rc.getLocation();

        if(rc.getPaint() < 40){ // cari tower / mopper
            repLowPaint(rc);
            paintRefill(rc);
            return;
        }

        MapInfo bestRuin = findBestRuin(rc);
        if (bestRuin != null) {
            handleRuinBuilding(rc, bestRuin);
            return;
        }

        attackEnemyPaint(rc); // jika ada, hapus cat musuh
        greedyMove(rc);
        paintCurrTile(rc);

    }

    static void greedyMove(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()){
            return;
        }

        MapLocation myLoc = rc.getLocation();

        Direction bestDir = null;
        int bestScore = -100000;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)){
                continue;
            }

            MapLocation nextLoc = myLoc.add(dir);
            int score = evalScore(rc, nextLoc);
            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir != null){
            rc.move(bestDir);
        }

        paintCurrTile(rc);
    }

    static int evalScore(RobotController rc, MapLocation loc) throws GameActionException {
        int score = 0;
        MapInfo[] nearby = rc.senseNearbyMapInfos(loc, 9);
        for (MapInfo tile : nearby) {
            if (!tile.isPassable()){
                continue;
            }

            if (tile.hasRuin()) {
                score += 8; // priority utama
                continue;
            }

            PaintType paint = tile.getPaint();
            if (paint == PaintType.ENEMY_PRIMARY || paint == PaintType.ENEMY_SECONDARY){
                 score += 5; // hapus cat musuh
            } else if (paint == PaintType.EMPTY){
                score += 3; 
            } else if (paint.isAlly()){
                score -= 1; 
            }
        }

        return score;
    }

    static MapInfo findBestRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo bestRuin  = null;
        int bestScore = -100000;

        for (MapInfo tile : nearbyTiles) {
            if (!tile.hasRuin()){
                 continue;
            }

            MapLocation ruinLoc = tile.getMapLocation();

            // Skip jika sudah ada tower
            RobotInfo atRuin = rc.senseRobotAtLocation(ruinLoc);
            if (atRuin != null && atRuin.type.isTowerType()){
                 continue;
            }

            int dist = rc.getLocation().distanceSquaredTo(ruinLoc);
            int unpainted = countUnpainted(rc, ruinLoc);

            // dekat & hampir selesai lebih baik
            int score = -dist - (unpainted * 3);
            if (score > bestScore) {
                bestScore = score;
                bestRuin = tile;
            }
        }

        return bestRuin;
    }

    static int countUnpainted(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        int count = 0;
        MapInfo[] tiles = rc.senseNearbyMapInfos(ruinLoc, 8);
        for (MapInfo m : tiles) {
            if (m.getMark() != PaintType.EMPTY && m.getMark() != m.getPaint()) {
                count++;
            }
        }

        return count;
    }

    static void handleRuinBuilding(RobotController rc, MapInfo ruinInfo) throws GameActionException {
        MapLocation ruinLoc = ruinInfo.getMapLocation();
        MapLocation myLoc = rc.getLocation();

        if (rc.isMovementReady()) {
            Direction dir = myLoc.directionTo(ruinLoc);
            if (rc.canMove(dir)) {
                rc.move(dir);
                myLoc = rc.getLocation();
            } else { // alternatif
                Direction[] alts = {dir.rotateLeft(), dir.rotateRight(), dir.rotateLeft().rotateLeft(), dir.rotateRight().rotateRight()};
                for (Direction alt : alts) {
                    if (rc.canMove(alt)) { 
                        rc.move(alt); 
                        myLoc = rc.getLocation(); 
                        break; 
                    }
                }
            }
        }

        UnitType towerType = chooseTower(rc);

        if (rc.canMarkTowerPattern(towerType, ruinLoc)) { // mark tower
            boolean alrMarked = false;
            for (MapInfo m : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (m.getMark() != PaintType.EMPTY) { 
                    alrMarked = true; 
                    break; 
                }
            }
            if (!alrMarked){
                rc.markTowerPattern(towerType, ruinLoc);
            }
        }

        if (rc.isActionReady()) { // cat yang nearest
            MapLocation closestUnpainted = null;
            boolean useSecondary = false;
            int closestDist = 10000000;

            for (MapInfo pat : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (pat.getMark() == PaintType.EMPTY){
                    continue;
                }

                if (pat.getMark() == pat.getPaint()){
                    continue;
                } 

                if (!rc.canAttack(pat.getMapLocation())){
                    continue;
                }

                int d = myLoc.distanceSquaredTo(pat.getMapLocation());
                if (d < closestDist) {
                    closestDist = d;
                    closestUnpainted = pat.getMapLocation();
                    useSecondary = pat.getMark() == PaintType.ALLY_SECONDARY;
                }
            }
            if (closestUnpainted != null){
                rc.attack(closestUnpainted, useSecondary);
            }
        }

        // finish tower
        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            rc.setTimelineMarker("Tower Built!", 0, 255, 0);
        }

        repRuinFound(rc, ruinLoc);
    }

    static UnitType chooseTower(RobotController rc) throws GameActionException {
        int paintTowers = 0;
        int moneyTowers = 0;
        int defenseTowers = 0;
        int enemyRobots = 0;

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.type == UnitType.LEVEL_ONE_PAINT_TOWER || ally.type == UnitType.LEVEL_TWO_PAINT_TOWER || ally.type == UnitType.LEVEL_THREE_PAINT_TOWER){
                paintTowers++;
            } else if (ally.type == UnitType.LEVEL_ONE_MONEY_TOWER || ally.type == UnitType.LEVEL_TWO_MONEY_TOWER || ally.type == UnitType.LEVEL_THREE_MONEY_TOWER){
                moneyTowers++;
            } else if (ally.type == UnitType.LEVEL_ONE_DEFENSE_TOWER || ally.type == UnitType.LEVEL_TWO_DEFENSE_TOWER || ally.type == UnitType.LEVEL_THREE_DEFENSE_TOWER){
                defenseTowers++;
            }
        }

        enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length;

        // pilih tower sesuai kebutuhan
        if (paintTowers == 0){
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        }
        if (moneyTowers == 0){
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        }
        if (enemyRobots >= 3 && defenseTowers == 0){
            return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        }
        if (paintTowers <= moneyTowers){
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        return UnitType.LEVEL_ONE_MONEY_TOWER;
    }

    static void paintCurrTile(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()){
            return;
        }

        MapInfo tile = rc.senseMapInfo(rc.getLocation());
        if (!tile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation());
        }
    }

    static void attackEnemyPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()){
            return;
        }

        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getLocation(), 9);
        MapLocation bestTarget = null;
        int bestDist = 10000000;
        for (MapInfo tile : nearby) {
            PaintType p = tile.getPaint();
            if (p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY) {
                int d = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (d < bestDist && rc.canAttack(tile.getMapLocation())) {
                    bestDist = d;
                    bestTarget = tile.getMapLocation();
                }
            }
        }
        if (bestTarget != null){
            rc.attack(bestTarget);
        }
    }

    // refill cat
    static void paintRefill(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo nearestTower = null;
        int bestDist = 10000000;

        for (RobotInfo ally : allies) {
            if (ally.type.isTowerType()) {
                int d = rc.getLocation().distanceSquaredTo(ally.location);
                if (d < bestDist){
                    bestDist = d; 
                    nearestTower = ally; 
                }
            }
        }

        // move to tower
        if (nearestTower != null) {
            Direction dir = rc.getLocation().directionTo(nearestTower.location);
            if (rc.isMovementReady() && rc.canMove(dir)){
                rc.move(dir);
            }

            // ambil cat
            int distTower = rc.getLocation().distanceSquaredTo(nearestTower.location);
            if (rc.isActionReady() && distTower <= Math.sqrt(2) ) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                if (needed > 0) {
                    rc.transferPaint(nearestTower.location, -needed); 
                }
            }
        } else {
            Direction bestDir = null;
            int bestScore = -100000;

            for (Direction dir : directions) {
                if (!rc.canMove(dir)){
                    continue;
                }

                MapLocation next = rc.getLocation().add(dir);
                MapInfo[] tiles = rc.senseNearbyMapInfos(next, 4);
                int score = 0;
                for (MapInfo m : tiles){
                    if (m.getPaint().isAlly()){
                        score++;
                    }
                }
                if (score > bestScore){ 
                    bestScore = score; 
                    bestDir = dir; 
                }
            }
            if (bestDir != null && rc.isMovementReady()){
                rc.move(bestDir);
            }
        }
    }

    static void repLowPaint(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.type.isTowerType()) {
                int msg = MSG_PAINTLOW | (rc.getLocation().x << 15) | rc.getLocation().y;
                if (rc.canSendMessage(ally.location, msg)){
                    rc.sendMessage(ally.location, msg);
                    break;
                }
            }
        }
    }

    static void repRuinFound(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.type.isTowerType()) {
                int msg = MSG_RUIN | (ruinLoc.x << 15) | ruinLoc.y;
                if (rc.canSendMessage(ally.location, msg)) {
                    rc.sendMessage(ally.location, msg);
                    break;
                }
            }
        }
    }

    public static void runMopper(RobotController rc) throws GameActionException {

    }

    public static void runSplasher(RobotController rc) throws GameActionException {

    }

    
}