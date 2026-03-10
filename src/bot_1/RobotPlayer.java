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
    // (01=ada musuh, 10=ketemu ruin, 11=cat dikit)
    // Koor x ada di bit 29-15, Koor y ada di bit 14-0 
    static final int MSG_ENEMY    = 1 << 30;
    static final int MSG_RUIN     = 2 << 30;
    static final int MSG_PAINTLOW = 3 << 30; 

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
        for(Message m : messages) {
            int data = m.getBytes();
            int msgType = data & (3 << 30);
            if(msgType == MSG_PAINTLOW){
                paintLow = true;
            } else if(msgType == MSG_ENEMY){
                enemyNear = true;
            } else if(msgType == MSG_RUIN){
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
        int allyTiles = 0;

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for(RobotInfo ally : allies) {
            if(ally.type == UnitType.SOLDIER){
                solCount++;
                int paintPct = (int)(100.0 * ally.paintAmount / ally.type.paintCapacity);
                if(paintPct < 40) allySolLowPaint++;
            }
            if(ally.type == UnitType.MOPPER){
                mopCount++;
            }
            if(ally.type == UnitType.SPLASHER){
                splashCount++;
            }
        }

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        for(MapInfo tile : nearbyTiles) {
            PaintType p = tile.getPaint();
            if(p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY){
                enemyPaintTiles++;
            } else if (p == PaintType.EMPTY && tile.isPassable()){
                emptyTiles++;
            } else if (p.isAlly()){
                allyTiles++;
            }
        }

        UnitType spawnUnit;
        if(paintLow) { 
            spawnUnit = UnitType.MOPPER;
        } else if(enemyNear) { 
            // musuh dekat & banyak cat musuh -> mopper, cat musuh dikit -> soldier
            if(enemyPaintTiles >= 5){
                spawnUnit = UnitType.MOPPER;
            } else {
                spawnUnit = UnitType.SOLDIER;
            }
        } else if(ruinLoc != null) { 
            spawnUnit = UnitType.SOLDIER;
        } else if(allySolLowPaint >= 2){
            spawnUnit = UnitType.MOPPER;
        } else if(mopCount * 4 < solCount){ // jumlah sol kebanyakan
            spawnUnit = UnitType.MOPPER;
        } else if(emptyTiles > allyTiles && splashCount < 2){
            spawnUnit = UnitType.SPLASHER;
        } else { // default
            spawnUnit = UnitType.SOLDIER;
        }

        // best loc spawn
        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        for(Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if(!rc.canBuildRobot(spawnUnit, spawnLoc)) {
                continue;
            }

            int score = evalLoc(rc, spawnLoc, spawnUnit, ruinLoc);
            if(score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if(bestDir != null) {
            MapLocation spawnLoc = rc.getLocation().add(bestDir);
            rc.buildRobot(spawnUnit, spawnLoc);
        }
    }

    // eval loc spawn
    static int evalLoc(RobotController rc, MapLocation loc, UnitType unit, MapLocation ruinLoc) throws GameActionException {
        int score = 0;

        // prefer loc own paint
        PaintType paint = rc.senseMapInfo(loc).getPaint();
        if(paint.isAlly()) {
            score += 0;
        } else if(paint == PaintType.EMPTY){
            score -= 1;
        } else { // cat musuh
            if(unit == UnitType.MOPPER){
                score -= 4;
            } else if(unit == UnitType.SPLASHER){
                score -=3;
            } else {
                score -= 2;
            }
        }

        // jika soldier, prefer close to ruin
        if(unit == UnitType.SOLDIER && ruinLoc != null) {
            score -= loc.distanceSquaredTo(ruinLoc); 
        }

        // area banyak empty tiles
        if(unit == UnitType.SPLASHER) {
            MapInfo[] nearby = rc.senseNearbyMapInfos(loc, 4);
            for(MapInfo m : nearby) {
                if(m.getPaint() == PaintType.EMPTY && m.isPassable()){
                    score += 1;
                } else if((m.getPaint() == PaintType.ENEMY_PRIMARY || m.getPaint() == PaintType.ENEMY_SECONDARY) && loc.distanceSquaredTo(m.getMapLocation()) <= 2){
                    score += 2;
                }
            }
        }

        // avoid spawn dekat musuh
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(loc, 20, rc.getTeam().opponent());
        if(nearbyEnemies.length > 0) {
            int penalty;
            if(unit == UnitType.MOPPER){
                penalty = 4;
            } else {
                penalty = 2;
            }
            score -= penalty * nearbyEnemies.length;
        }

        return score;
    }

    // atk musuh terdekat
    static void attackNearestEnemy(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if(enemies.length > 0 && rc.isActionReady()){
            // atk musuh HP terendah
            RobotInfo weakest = enemies[0];
            for(RobotInfo e : enemies) {
                if(e.health < weakest.health){
                    weakest = e;
                }
            }
            if(rc.canAttack(weakest.location)){
                rc.attack(weakest.location);
            }
        }
    }

    public static void runSoldier(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if(enemies.length > 0){
            repEnemySeen(rc);
        }

        if(rc.getPaint() < 40){ // cari tower / mopper
            repLowPaint(rc);
            paintRefill(rc);
            return;
        }

        MapInfo bestRuin = findBestRuin(rc);
        if(bestRuin != null) {
            handleRuinBuilding(rc, bestRuin);
            return;
        }

        greedyMove(rc);
        greedyPaint(rc);
    }

    static void greedyMove(RobotController rc) throws GameActionException {
        if(!rc.isMovementReady()){
            return;
        }

        MapLocation myLoc = rc.getLocation();

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        for(Direction dir : directions){
            if(!rc.canMove(dir)){
                continue;
            }

            MapLocation nextLoc = myLoc.add(dir);
            int score = evalScore(rc, nextLoc);
            if(score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if(bestDir != null){
            rc.move(bestDir);
        }
    }

    static int evalScore(RobotController rc, MapLocation loc) throws GameActionException {
        int score = 0;
        MapInfo[] nearby = rc.senseNearbyMapInfos(loc, 9);
        for(MapInfo tile : nearby){
            if(!tile.isPassable()){
                continue;
            }

            if(tile.hasRuin()){
                score += 30 - loc.distanceSquaredTo(tile.getMapLocation()); // priority utama
                continue;
            }

            PaintType paint = tile.getPaint();
            if(paint == PaintType.ENEMY_PRIMARY || paint == PaintType.ENEMY_SECONDARY){
                 score += 2; // hapus cat musuh
            } else if(paint == PaintType.EMPTY){
                score += 1; 
            } else if(paint.isAlly()){
                score -= 1; 
            }
        }

        return score;
    }

    static MapInfo findBestRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo bestRuin  = null;
        int bestScore = Integer.MIN_VALUE;

        for(MapInfo tile : nearbyTiles){
            if(!tile.hasRuin()){
                 continue;
            }

            MapLocation ruinLoc = tile.getMapLocation();

            // Skip jika sudah ada tower
            RobotInfo atRuin = rc.senseRobotAtLocation(ruinLoc);
            if(atRuin != null && atRuin.type.isTowerType()){
                 continue;
            }

            int dist = rc.getLocation().distanceSquaredTo(ruinLoc);
            int unpainted = countUnpainted(rc, ruinLoc);

            // dekat & hampir selesai lebih baik
            int score = -dist - (unpainted * 3);
            if(score > bestScore) {
                bestScore = score;
                bestRuin = tile;
            }
        }

        return bestRuin;
    }

    static int countUnpainted(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        boolean hasMark = false;
        int count = 0;
        MapInfo[] tiles = rc.senseNearbyMapInfos(ruinLoc, 8);

        for(MapInfo m : tiles) {
            if(m.getMark() != PaintType.EMPTY){
                hasMark = true; 
                break; 
            }
        }

        if(hasMark){
            for(MapInfo m : tiles) {
                if(m.getMark() != PaintType.EMPTY && m.getMark() != m.getPaint()){
                    count++;
                }
            }
        } else {
            for(MapInfo m : tiles){
                if(m.isPassable() && !m.getPaint().isAlly()){
                    count++;
                }
            }
        }

        return count;
    }

    static void handleRuinBuilding(RobotController rc, MapInfo ruinInfo) throws GameActionException {
        MapLocation ruinLoc = ruinInfo.getMapLocation();
        MapLocation myLoc = rc.getLocation();

        UnitType towerType = chooseTower(rc);

        if(rc.canMarkTowerPattern(towerType, ruinLoc)) { // mark tower
            boolean alrMarked = false;
            for(MapInfo m : rc.senseNearbyMapInfos(ruinLoc, 8)){
                if(m.getMark() != PaintType.EMPTY) { 
                    alrMarked = true; 
                    break; 
                }
            }
            if(!alrMarked){
                rc.markTowerPattern(towerType, ruinLoc);
            }
        }

        if(rc.isActionReady()) { // cat yang nearest
            MapLocation closestUnpainted = null;
            boolean useSecondary = false;
            int closestDist = Integer.MAX_VALUE;

            for(MapInfo pat : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if(pat.getMark() == PaintType.EMPTY){
                    continue;
                }

                if(pat.getMark() == pat.getPaint()){
                    continue;
                } 

                if(!rc.canAttack(pat.getMapLocation())){
                    continue;
                }

                int d = myLoc.distanceSquaredTo(pat.getMapLocation());
                if(d < closestDist) {
                    closestDist = d;
                    closestUnpainted = pat.getMapLocation();
                    useSecondary = pat.getMark() == PaintType.ALLY_SECONDARY;
                }
            }
            if(closestUnpainted != null){
                rc.attack(closestUnpainted, useSecondary);
            }
        }

        if(rc.isMovementReady()) {
            Direction dir = myLoc.directionTo(ruinLoc);
            if(rc.canMove(dir)) {
                rc.move(dir);
                myLoc = rc.getLocation();
            } else { // alternatif
                Direction[] alts = {dir.rotateLeft(), dir.rotateRight(), dir.rotateLeft().rotateLeft(), dir.rotateRight().rotateRight()};
                for(Direction alt : alts) {
                    if(rc.canMove(alt)) { 
                        rc.move(alt); 
                        myLoc = rc.getLocation(); 
                        break; 
                    }
                }
            }
        }

        // finish tower
        if(rc.canCompleteTowerPattern(towerType, ruinLoc)){
            rc.completeTowerPattern(towerType, ruinLoc);
            rc.setTimelineMarker("Tower Built!", 0, 255, 0);
        }

        repRuinFound(rc, ruinLoc);
    }

    static UnitType chooseTower(RobotController rc) throws GameActionException {
        int paintTowers = 0;
        int moneyTowers = 0;
        int defenseTowers = 0;

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for(RobotInfo ally : allies) {
            if(ally.type == UnitType.LEVEL_ONE_PAINT_TOWER || ally.type == UnitType.LEVEL_TWO_PAINT_TOWER || ally.type == UnitType.LEVEL_THREE_PAINT_TOWER){
                paintTowers++;
            } else if(ally.type == UnitType.LEVEL_ONE_MONEY_TOWER || ally.type == UnitType.LEVEL_TWO_MONEY_TOWER || ally.type == UnitType.LEVEL_THREE_MONEY_TOWER){
                moneyTowers++;
            } else if(ally.type == UnitType.LEVEL_ONE_DEFENSE_TOWER || ally.type == UnitType.LEVEL_TWO_DEFENSE_TOWER || ally.type == UnitType.LEVEL_THREE_DEFENSE_TOWER){
                defenseTowers++;
            }
        }

        int enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length;

        // pilih tower sesuai kebutuhan
        if(paintTowers == 0){
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        }
        if(moneyTowers == 0){
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        }
        if(enemyRobots >= 3 && defenseTowers == 0){
            return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        }
        if(paintTowers <= moneyTowers){
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        return UnitType.LEVEL_ONE_MONEY_TOWER;
    }

    static void greedyPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()){
            return;
        }

        MapLocation myLoc = rc.getLocation();
        MapInfo[] tiles = rc.senseNearbyMapInfos(myLoc, 9);
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (MapInfo m : tiles) {
            if (!rc.canAttack(m.getMapLocation())){
                continue;
            }

            PaintType p = m.getPaint();
            int score;
            if(p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY){
                score = 7;
            } else if (p == PaintType.EMPTY){
                score = 5;
            } else {
                score = -2;
            }

            if(score > bestScore){ 
                bestScore = score; 
                best = m.getMapLocation(); 
            }
        }

        if(best != null && bestScore > 0){
            rc.attack(best);
        }
    }

    // refill cat
    static void paintRefill(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo nearestTower = null;
        int bestDist = Integer.MAX_VALUE;

        for(RobotInfo ally : allies) {
            if(ally.type.isTowerType()){
                int d = rc.getLocation().distanceSquaredTo(ally.location);
                if(d < bestDist){
                    bestDist = d; 
                    nearestTower = ally; 
                }
            }
        }

        // move to tower
        if(nearestTower != null) {
            Direction dir = rc.getLocation().directionTo(nearestTower.location);
            if(rc.isMovementReady() && rc.canMove(dir)){
                rc.move(dir);
            }

            // ambil cat
            int distTower = rc.getLocation().distanceSquaredTo(nearestTower.location);
            if(rc.isActionReady() && distTower <= 2){
                int needed = rc.getType().paintCapacity - rc.getPaint();
                if(needed > 0) {
                    rc.transferPaint(nearestTower.location, -needed); 
                }
            }
        } else {
            Direction bestDir = null;
            int bestScore = Integer.MIN_VALUE;

            for(Direction dir : directions) {
                if(!rc.canMove(dir)){
                    continue;
                }

                MapLocation next = rc.getLocation().add(dir);
                MapInfo[] tiles = rc.senseNearbyMapInfos(next, 4);
                int score = 0;
                for(MapInfo m : tiles){
                    if(m.getPaint().isAlly()){
                        score++;
                    }
                }
                if(score > bestScore){ 
                    bestScore = score; 
                    bestDir = dir; 
                }
            }
            if(bestDir != null && rc.isMovementReady()){
                rc.move(bestDir);
            }
        }
    }

    static void repLowPaint(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for(RobotInfo ally : allies) {
            if(ally.type.isTowerType()) {
                int msg = MSG_PAINTLOW | (rc.getLocation().x << 15) | rc.getLocation().y;
                if(rc.canSendMessage(ally.location, msg)){
                    rc.sendMessage(ally.location, msg);
                    break;
                }
            }
        }
    }

    static void repRuinFound(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for(RobotInfo ally : allies) {
            if(ally.type.isTowerType()) {
                int msg = MSG_RUIN | (ruinLoc.x << 15) | ruinLoc.y;
                if(rc.canSendMessage(ally.location, msg)) {
                    rc.sendMessage(ally.location, msg);
                    break;
                }
            }
        }
    }

    static void repEnemySeen(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for(RobotInfo ally : allies) {
            if(ally.type.isTowerType()){
                int msg = MSG_ENEMY | (myLoc.x << 15) | myLoc.y;
                if(rc.canSendMessage(ally.location, msg)){
                    rc.sendMessage(ally.location, msg);
                    break;
                }
            }
        }
    }

    public static void runMopper(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapInfo[] nearby = rc.senseNearbyMapInfos(myLoc, 2); // mop radius √2

        if(rc.isActionReady()) { // tf cat
            RobotInfo bestSol = null;
            int tfGain = 0;
            for(RobotInfo ally : allies) {
                if(ally.type != UnitType.SOLDIER){
                    continue;
                }

                if(myLoc.distanceSquaredTo(ally.location) > 2){ // max dist akar 2
                    continue; 
                }

                int pct  = (int)(100.0 * ally.paintAmount / ally.type.paintCapacity);
                if(pct >= 50){ // masih banyak
                    continue; 
                }

                int give = Math.min(rc.getPaint() - 20, ally.type.paintCapacity - ally.paintAmount);
                if(give <= 0){
                    continue;
                }

                int gain; // prioritas soldier low paint
                if(pct < 20){
                    gain = give * 2;
                } else {
                    gain = give; 
                }

                if(gain > tfGain){ 
                    tfGain = gain; 
                    bestSol = ally; 
                }
            }

            // ambil tile musuh
            MapLocation bestMopTile = null;
            int mopGain = 0;
            for(MapInfo tile : nearby) {
                PaintType p = tile.getPaint();
                if(p != PaintType.ENEMY_PRIMARY && p != PaintType.ENEMY_SECONDARY){
                    continue;
                }

                if(!rc.canAttack(tile.getMapLocation())){
                    continue;
                }

                int gain = 15;
                try {
                    RobotInfo onTile = rc.senseRobotAtLocation(tile.getMapLocation());
                    if(onTile != null && onTile.team != rc.getTeam()){
                        gain += 10;
                    }
                } catch (GameActionException e) {}
                if(gain > mopGain){ 
                    mopGain = gain; 
                    bestMopTile = tile.getMapLocation(); 
                }
            }

            // swing enemy
            Direction bestSwing = null;
            int swingGain = 0;
            for(Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH,Direction.EAST,  Direction.WEST}){
                if(!rc.canMopSwing(dir)){
                    continue;
                }

                int count = countSwingEnemy(rc, dir, enemies);
                int gain  = count * 5; 
                if(gain > swingGain){ 
                    swingGain = gain; 
                    bestSwing = dir; 
                }
            }

            // pilih highest gain
            int maxGain = Math.max(tfGain, Math.max(mopGain, swingGain));

            if(maxGain > 0) {
                if(maxGain == tfGain && bestSol != null){ // tf ke soldier low paint
                    int give = Math.min(rc.getPaint() - 20, bestSol.type.paintCapacity - bestSol.paintAmount);
                    rc.transferPaint(bestSol.location, give);

                } else if(maxGain == swingGain && bestSwing != null){
                    rc.mopSwing(bestSwing); // atk musuh

                } else if(bestMopTile != null){
                    rc.attack(bestMopTile); 
                }
            }
        }

        greedyMopper(rc);
    }

    static int countSwingEnemy(RobotController rc, Direction dir, RobotInfo[] enemies){
        MapLocation myLoc = rc.getLocation();
        MapLocation step1 = myLoc.add(dir);
        MapLocation step2 = step1.add(dir);
        int count = 0;
        for(RobotInfo enemy : enemies) {
            if(enemy.location.isAdjacentTo(step1) || enemy.location.equals(step1) || enemy.location.isAdjacentTo(step2) || enemy.location.equals(step2)) {
                count++;
            }
        }
        return count;
    }

    static void greedyMopper(RobotController rc) throws GameActionException {
        if(!rc.isMovementReady()){
            return;
        }

        MapLocation myLoc = rc.getLocation();

        // cari soldier dgn low paint
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation solTarget = null;
        int lowestPaint = 100;
        for(RobotInfo ally : allies) {
            if(ally.type == UnitType.SOLDIER && ally.paintAmount < lowestPaint){
                lowestPaint = ally.paintAmount;
                solTarget = ally.location;
            }
        }

        if(solTarget != null) {
            Direction dir = myLoc.directionTo(solTarget);
            if(rc.canMove(dir)){ 
                rc.move(dir); 
                return; 
            }
        }

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        for(Direction dir : directions) {
            if(!rc.canMove(dir)){
                continue;
            }

            MapLocation next = myLoc.add(dir);
            int score = 0;

            try {
                PaintType nextPaint = rc.senseMapInfo(next).getPaint();
                if(nextPaint.isAlly()){
                    score += 0;
                } else if(nextPaint == PaintType.EMPTY){
                    score -= 1;
                } else {
                    score -= rc.getPaint() * 2;
                }                                    
            } catch (GameActionException e) {}

            // ada cat musuh dalam jangkauan
            for(MapInfo tile : nearby) {
                if(next.distanceSquaredTo(tile.getMapLocation()) > 2){
                    continue;
                }

                PaintType p = tile.getPaint();
                if(p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY){
                    score += 15;
                }
            }

            // soldier butuh refill
            for(RobotInfo ally : allies) {
                if(ally.type != UnitType.SOLDIER){
                    continue;
                }

                int pct = (int)(100.0 * ally.paintAmount / ally.type.paintCapacity);
                int give = Math.min(rc.getPaint() - 20, ally.type.paintCapacity - ally.paintAmount);
                if(pct < 50 && next.distanceSquaredTo(ally.location) <= 2 && give > 0){
                    if(pct < 20){
                        score += give * 2;
                    } else {
                        score += give;
                    }
                }
            }

            // ada musuh
            RobotInfo[] nearEnemies = rc.senseNearbyRobots(next, 4, rc.getTeam().opponent());
            score -= 5 * nearEnemies.length;

            if(score > bestScore){ 
                bestScore = score; 
                bestDir = dir; 
            }
        }

        if(bestDir != null){
            rc.move(bestDir);
        }
    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();

        if(rc.isActionReady()){ // cari lokasi atk dgn max profit
            MapLocation bestCenter = null;
            int bestGain = 0;

            MapInfo[] candidates = rc.senseNearbyMapInfos(myLoc, 4);
            for(MapInfo candidate : candidates) {
                MapLocation center = candidate.getMapLocation();
                if(!rc.canAttack(center)){
                    continue;
                }

                int gain = calcSplashGain(rc, center);
                if(gain > bestGain){
                    bestGain   = gain;
                    bestCenter = center;
                }
            }

            if(bestCenter != null){
                rc.attack(bestCenter);
            }
        }

        if(rc.isMovementReady()){ // cari posisi terbaik
            Direction bestDir = null;
            int bestScore = Integer.MIN_VALUE;

            for(Direction dir : directions){
                if(!rc.canMove(dir)){
                    continue;
                }

                MapLocation next = myLoc.add(dir);
                int score = 0;

                try {
                    PaintType nextPaint = rc.senseMapInfo(next).getPaint();
                    if(nextPaint.isAlly()){
                        score += 0;   
                    } else if(nextPaint == PaintType.EMPTY){
                        score -= 50; 
                    } else {
                        score -= 100; 
                    }
                } catch (GameActionException e) {}

                MapInfo[] nearNext = rc.senseNearbyMapInfos(next, 4);
                for(MapInfo candidate : nearNext){
                    MapLocation center = candidate.getMapLocation();
                    if(next.distanceSquaredTo(center) > 4){
                        continue;
                    }

                    int gain = calcSplashGain(rc, center);
                    if(gain > score + 100){
                        score = gain;
                    }
                }

                RobotInfo[] nearEnemies = rc.senseNearbyRobots(next, 20, rc.getTeam().opponent());
                score -= 2 * nearEnemies.length;

                if(score > bestScore){ 
                    bestScore = score; 
                    bestDir = dir; 
                }
            }

            if(bestDir != null){
                rc.move(bestDir);
            }
        }

        if(rc.isActionReady()) {
            MapInfo curr = rc.senseMapInfo(myLoc);
            if(!curr.getPaint().isAlly() && rc.canAttack(myLoc)) {
                rc.attack(myLoc);
            }
        }
    }

    static int calcSplashGain(RobotController rc, MapLocation center) throws GameActionException {
        int gain = 0;
        MapInfo[] affected = rc.senseNearbyMapInfos(center, 4); 
        for(MapInfo tile : affected) {
            if(!tile.isPassable()){
                continue;
            }

            PaintType p = tile.getPaint();
            int distFromCenter = center.distanceSquaredTo(tile.getMapLocation());

            if(p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY){
                if(distFromCenter <= 2){ // daerah musuh
                    gain += 2; 
                }
            } else if (p == PaintType.EMPTY){ // new area
                gain += 1; 
            }
        }

        return gain;
    }
}