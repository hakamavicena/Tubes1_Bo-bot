package bot_1;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {

    static int turnCount = 0;
    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    // Message Declare 
    // Tipe msg di bit 30-31 
    // (01=ada musuh, 10=ketemu ruin, 11=cat dikit)
    // Koor x ada di bit 29-15, Koor y ada di bit 14-0 
    static final int MSG_ENEMY = 1 << 30;
    static final int MSG_RUIN = 2 << 30;
    static final int MSG_PAINTLOW = 3 << 30; 

    // state bot awal
    static MapLocation[] recentLocs = new MapLocation[10];
    static int recentIdx = 0, stuckCount = 0, targetTurn = 0, mapWidth = -1, mapHeight = -1, mySecX = -1, mySecY = -1;
    static MapLocation lastLoc = null, exploreTarget = null, spawnTower = null, mirrorTower = null;
    static boolean symChecked = false, refillMode = false;
    static final int SECTOR_SIZE = 10;

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

    // prediksi enemy dengan teknik simetri peta
    static void initState(RobotController rc) throws GameActionException {
        if(symChecked){
            return;
        }

        symChecked = true;
        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();
        MapLocation myLoc = rc.getLocation();

        // cari own tower terdekat
        int bestD = Integer.MAX_VALUE;
        for(RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())){
            if(!a.type.isTowerType()){
                continue;
            }

            int d = myLoc.distanceSquaredTo(a.location);
            if(d < bestD){ 
                bestD = d; 
                spawnTower = a.location; 
            }
        }

        if(spawnTower == null){
            return;
        }

        // rotasi, refleksi thd sumbu X dan Y
        MapLocation rot180 = new MapLocation(mapWidth - 1 - spawnTower.x, mapHeight - 1 - spawnTower.y);
        MapLocation refX = new MapLocation(mapWidth - 1 - spawnTower.x, spawnTower.y);
        MapLocation refY = new MapLocation(spawnTower.x, mapHeight - 1 - spawnTower.y);
        int dRot = spawnTower.distanceSquaredTo(rot180);
        int dH = spawnTower.distanceSquaredTo(refX);
        int dV = spawnTower.distanceSquaredTo(refY);

        if(dRot >= dH && dRot >= dV){
            mirrorTower = rot180;
        } else if (dH >= dV){
            mirrorTower = refX;
        } else {
            mirrorTower = refY;
        }
    }

    public static void runTower(RobotController rc) throws GameActionException {
        // atk musuh yang dekat (protect tower)
        attackWeakestEnemy(rc);

        // untuk msg
        boolean paintLow = false, enemyNear = false; 
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

        // jumlah unit & tile sekitar
        int solCount = 0, mopCount = 0, splashCount = 0, allySolLowPaint = 0;
        int enemyTiles = 0, emptyTiles = 0, allyTiles = 0;

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for(RobotInfo ally : allies) {
            if(ally.type == UnitType.SOLDIER){
                solCount++;
                int paintPct = (int)(100.0 * ally.paintAmount / ally.type.paintCapacity);
                if(paintPct < 40){
                    allySolLowPaint++;
                }
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
                enemyTiles++;
            } else if (p == PaintType.EMPTY && tile.isPassable()){
                emptyTiles++;
            } else if (p.isAlly()){
                allyTiles++;
            }
        }

        // tower tidak sering hilang, trus rebuild
        UnitType myType = rc.getType();
        if ((myType == UnitType.LEVEL_ONE_MONEY_TOWER || myType == UnitType.LEVEL_TWO_MONEY_TOWER
             || myType == UnitType.LEVEL_THREE_MONEY_TOWER) && rc.getChips() > 5000 && rc.getPaint() < 50 && !enemyNear
            && turnCount > 300 && rc.getNumberTowers() >= 5){
            for(RobotInfo a : rc.senseNearbyRobots(2, rc.getTeam()))
                if (a.type == UnitType.SOLDIER && a.paintAmount > 50){ 
                    rc.disintegrate(); 
                    return; 
                }
        }

        // milih spawn unit
        UnitType spawnUnit = greedySelectUnit(paintLow, enemyNear, enemyTiles, ruinLoc,
                allySolLowPaint, solCount, mopCount, splashCount, emptyTiles, allyTiles);

        // jangan terlalu banyak ngumpul
        int nearbyAllies = rc.senseNearbyRobots(9, rc.getTeam()).length;
        if(turnCount > 150 && nearbyAllies >= 6 && turnCount % 3 != 0){
            return;
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

    // greedy pilih robot based on value
    static UnitType greedySelectUnit(boolean paintLow, boolean enemyNear, int enemyTiles,
           MapLocation ruinLoc, int allySolLowPaint, int solCount, int mopCount, int splashCount,
           int emptyTiles, int allyTiles) {

        int solVal = 10, mopVal = 5, splVal = 5;

        if(ruinLoc != null){
            solVal += 50;
        } 
        if(enemyNear){
            solVal += 60;
        }
        solVal += Math.max(0, 30 - solCount * 2);
        if(solCount < 4){
            solVal += 40;
        }

        if(paintLow){
            mopVal += 80;
        }
        if(enemyNear){
            mopVal += 50;
        }
        if(enemyNear && enemyTiles >= 5){
            mopVal += 40;
        }
        if(allySolLowPaint >= 2){
            mopVal += 30;
        }

        mopVal += enemyTiles * 3;
        if(mopCount * 4 < solCount){
            mopVal += 20;
        }
        mopVal -= mopCount * 5;

        if(emptyTiles > allyTiles){
            splVal += 45;
        }
        splVal += Math.min(40, emptyTiles / 2);

        if(splashCount >= 4){
            splVal -= 40;
        }
        if(turnCount > 200 && splashCount < 2){
            splVal += 30;
        }

        if(mopVal >= solVal && mopVal >= splVal){
            return UnitType.MOPPER;
        }
        if(splVal >= solVal){
            return UnitType.SPLASHER;
        }

        return UnitType.SOLDIER;
    }

    // eval loc spawn
    static int evalLoc(RobotController rc, MapLocation loc, UnitType unit, MapLocation ruinLoc) throws GameActionException {
        int score = 0;

        PaintType paint = rc.senseMapInfo(loc).getPaint();
        if(paint == PaintType.EMPTY) {
            score -= 1;
        } else if (!paint.isAlly()) { // cat musuh
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
            score -= loc.distanceSquaredTo(ruinLoc) / 2; 
        }

        // area banyak empty tiles
        if(unit == UnitType.SPLASHER) {
            MapInfo[] nearby = rc.senseNearbyMapInfos(loc, 9);
            for(MapInfo m : nearby) {
                if(!m.isPassable()){
                    continue;
                }

                if(m.getPaint() == PaintType.EMPTY){
                    score += 2;
                } else if((m.getPaint() == PaintType.ENEMY_PRIMARY || m.getPaint() == PaintType.ENEMY_SECONDARY)){
                    score += 3;
                }
            }
        }

        if(unit == UnitType.MOPPER){
            for(MapInfo m : rc.senseNearbyMapInfos(loc, 4)){
                if (m.getPaint() == PaintType.ENEMY_PRIMARY || m.getPaint() == PaintType.ENEMY_SECONDARY){
                    score += 5;
                }
            }
        }

        // avoid spawn deket musuh
        int enCount = rc.senseNearbyRobots(loc, 20, rc.getTeam().opponent()).length;
        if(unit == UnitType.MOPPER){
            score -= 6 * enCount;
        } else {
            score -= 3 * enCount;
        }
    
        score += rc.senseNearbyRobots(loc, 16, rc.getTeam()).length;
        return score;
    }

    // atk musuh terlemah
    static void attackWeakestEnemy(RobotController rc) throws GameActionException {
        if(!rc.isActionReady()){
            return;
        }
        RobotInfo[] en = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if(en.length == 0){
            return;
        }
        RobotInfo w = en[0];
        for(RobotInfo e : en)
            if(e.health < w.health || (e.health == w.health && e.type == UnitType.SOLDIER)){
                w = e;
            }
        if(rc.canAttack(w.location)){
            rc.attack(w.location);
        }
    }

    // greedy (atk - move - atk)
    public static void runSoldier(RobotController rc) throws GameActionException {
        initState(rc);
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if(enemies.length > 0){
            repEnemySeen(rc);
        }

        // butuh refill
        if (needsRefill(rc)) {
            if(enemies.length > 0 && rc.getPaint() > 15){
                if (rc.isActionReady()){
                    attackBestTarget(rc, enemies);
                }
            } else {
                repLowPaint(rc);
                doRefill(rc);
                return;
            }
        }

         // Atk sebelum jalan
        if (rc.isActionReady() && enemies.length > 0){
            attackBestTarget(rc, enemies);
        }

        // bikin tower (prior : no enemy)
        MapInfo bestRuin = findBestRuin(rc);
        if(bestRuin != null) {
            handleRuinBuilding(rc, bestRuin);
            return;
        }

        if(rc.isActionReady() && enemies.length == 0){
            taintEnemyRuin(rc);
        }

        if(rc.getNumberTowers() >= 6 && rc.isActionReady() && enemies.length == 0){
            if(tryBuildSRP(rc)){ // build special res pattern
                return;
            }
        }

        // cat dulu
        if(rc.isActionReady()) {
            if(greedyAttackEnemyPaint(rc) == 0 && !isNearRuinBeingBuilt(rc)){
                paintBestNearby(rc);
            }
        }

        greedyMove(rc);
        
        // atk setelah move
        if(rc.isActionReady()) {
            RobotInfo[] postEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if(postEnemies.length > 0){
                attackBestTarget(rc, postEnemies);
            } else if (!isNearRuinBeingBuilt(rc)){
                paintAfterMove(rc);
            }
        }
    }

    static void attackBestTarget(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if(!rc.isActionReady()){
            return;
        }

        RobotInfo best = null; int bestScore = Integer.MIN_VALUE;
        for(RobotInfo e : enemies) {
            if(!rc.canAttack(e.location)){
                continue;
            }
            int score = 1000 - e.health;
            if(e.health <= rc.getType().attackStrength){
                score += 500;
            }
            if(e.type.isTowerType()){
                score += 300;
            } else if (e.type == UnitType.SOLDIER){
                score += 100;
            } else if (e.type == UnitType.SPLASHER){
                score += 80;
            } else {
                score += 50;
            }

            if(score > bestScore){ 
                bestScore = score; 
                best = e; 
            }
        }

        if(best != null){
            rc.attack(best.location);
        }
    }

    // refill based on jarak
    static boolean needsRefill(RobotController rc) throws GameActionException {
        int paint = rc.getPaint();
        if(paint >= 60){ 
            refillMode = false; 
            return false; 
        }

        boolean towerVisible = false;
        for(RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())){
            if(a.type.isTowerType()){ 
                towerVisible = true; 
                break; 
            }
        }

        if(paint < 10){ 
            refillMode = true; 
            return true; 
        }

        if(towerVisible && !refillMode){
            MapLocation tower = nearestAllyTowerLoc(rc);
            if(tower != null){
                int estSteps = (int) Math.sqrt(rc.getLocation().distanceSquaredTo(tower)) + 1;
                refillMode = paint < Math.min(50, estSteps * 2 + 15);
            }
        }
        if(refillMode && paint >= 60){
            refillMode = false;
        }

        return refillMode;
    }

    static MapLocation nearestAllyTowerLoc(RobotController rc) throws GameActionException {
        MapLocation best = null; int bestD = Integer.MAX_VALUE;
        for(RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())){
            if(!a.type.isTowerType()){
                continue;
            }
            int d = rc.getLocation().distanceSquaredTo(a.location);
            if(d < bestD){ 
                bestD = d; 
                best = a.location; 
            }
        }
        if(best != null){ 
            spawnTower = best; 
            return best; 
        }

        return spawnTower;
    }

    static void doRefill(RobotController rc) throws GameActionException {
        RobotInfo nearestTower = null; 
        int bestDist = Integer.MAX_VALUE;
        for(RobotInfo a : rc.senseNearbyRobots(-1, rc.getTeam())){
            if(!a.type.isTowerType()){
                continue;
            }
            int d = rc.getLocation().distanceSquaredTo(a.location);
            if(d < bestDist){ 
                bestDist = d; 
                nearestTower = a; 
            }
        }
        if(nearestTower != null){
            if(rc.isActionReady() && rc.getLocation().distanceSquaredTo(nearestTower.location) <= 2){
                int take = Math.min(rc.getType().paintCapacity - rc.getPaint(), nearestTower.paintAmount);
                if(take > 0 && rc.canTransferPaint(nearestTower.location, -take)){
                    rc.transferPaint(nearestTower.location, -take);
                    refillMode = false;
                    return;
                }
            }
            moveToward(rc, nearestTower.location);
        } else {
            // tower tidak keliatan, move ke arah ally
            Direction bestDir = null; 
            int bestScore = Integer.MIN_VALUE;
            for(Direction dir : directions){ 
                if(!rc.canMove(dir)){
                    continue;
                }
                int score = 0;
                for(MapInfo m : rc.senseNearbyMapInfos(rc.getLocation().add(dir), 4)){
                    if (m.getPaint().isAlly()) score++;
                }
                if(score > bestScore){ 
                    bestScore = score; 
                    bestDir = dir; 
                }
            }
            if(bestDir != null && rc.isMovementReady()){
                rc.move(bestDir);
            }
            refillMode = false;
        }
    }

    static void greedyMove(RobotController rc) throws GameActionException {
        if(!rc.isMovementReady()){
            return;
        }

        MapLocation myLoc = rc.getLocation();

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        if(lastLoc != null && myLoc.equals(lastLoc)){
            stuckCount++;
            if(stuckCount >= 3){
                Direction d = directions[rng.nextInt(8)];
                if(rc.canMove(d)){ 
                    rc.move(d); 
                    stuckCount = 0; 
                    lastLoc = myLoc; 
                    return; 
                }
            }
        } else {
            stuckCount = 0;
        }

        recentLocs[recentIdx % 10] = myLoc;
        recentIdx++;
        targetTurn++;
        if(targetTurn > 3 || exploreTarget == null || (exploreTarget != null && myLoc.distanceSquaredTo(exploreTarget) <= 8)){
            updateExploreTarget(rc, myLoc);
            targetTurn = 0;
        }

        // cari prior target musuh
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation enemyTarget = null;
        int bestES = Integer.MIN_VALUE;
        for(RobotInfo e : enemies){
            int es;
            if(e.type.isTowerType()){
                es = 200;
            } else if (e.type == UnitType.SOLDIER){
                es = 100;
            } else if (e.type == UnitType.SPLASHER){
                es = 80;
            } else {
                es = 50;
            }
            es += 1000 - e.health;
            if(es > bestES){ 
                bestES = es; 
                enemyTarget = e.location; 
            }
        }

        MapLocation target = exploreTarget;
        if(target == null && mirrorTower != null){
            target = mirrorTower;
        }

        for(Direction dir : directions){
            if(!rc.canMove(dir)){
                continue;
            }
            MapLocation next = myLoc.add(dir);
            int score = 0;

            // Kejar musuh
            if(enemyTarget != null){
                int dNow = myLoc.distanceSquaredTo(enemyTarget), dNext = next.distanceSquaredTo(enemyTarget);
                if(dNext < dNow){
                    score += 30;
                } else if (dNext > dNow){
                    score -= 10;
                }
                if(dNext <= 9 && dNext < dNow){
                    score += 20;
                }
            }

            // eval position
            score += evalTileGain(rc, next);
            score -= recentVisitPenalty(next);

            // Hindari tembok
            int blocked = 0;
            for(Direction d : directions){
                MapLocation t = next.add(d);
                if(!rc.onTheMap(t) || !rc.sensePassability(t)){
                    blocked++;
                }
            }
            if(blocked >= 5){
                score -= 15;
            }

            // eksplorasi (no enemy)
            if(enemyTarget == null && target != null){
                int dNow = myLoc.distanceSquaredTo(target), dNext = next.distanceSquaredTo(target);
                if(dNext < dNow){
                    score += 20;
                } else if (dNext > dNow){
                    score -= 8;
                }
            }

            int allies = 0;
            for(RobotInfo a : rc.senseNearbyRobots(next, 8, rc.getTeam())){
                if(a.type == UnitType.SOLDIER){
                    allies++;
                }
            }
            score -= allies * 4;

            // push
            if(spawnTower != null && enemyTarget == null){
                int sNow = myLoc.distanceSquaredTo(spawnTower), sNext = next.distanceSquaredTo(spawnTower);
                if(sNext > sNow){
                    score += 5;
                } else if (sNext < sNow){
                    score -= 3;
                }
            }

            score += rng.nextInt(2);
            if(score > bestScore){ 
                bestScore = score; 
                bestDir = dir; 
            }
        }

        if(bestDir != null){
            rc.move(bestDir);
        }

        lastLoc = myLoc;
    }

    static int evalTileGain(RobotController rc, MapLocation loc) throws GameActionException {
        int score = 0;
        for(MapInfo tile : rc.senseNearbyMapInfos(loc, 4)){
            if(!tile.isPassable()){
                continue;
            }
            if(tile.hasRuin()){ 
                score += 6; 
                continue; 
            }
            PaintType p = tile.getPaint();
            if(p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY){
                score += 5;
            } else if (p == PaintType.EMPTY){
                score += 3;
            }
        }
        return score;
    }

    static void updateExploreTarget(RobotController rc, MapLocation myLoc) throws GameActionException {
        int frontierCount = 0, bestDist = 0;
        MapLocation bestFrontier = null;
        for(MapInfo tile : rc.senseNearbyMapInfos()){
            if(!tile.isPassable()){
                continue;
            }
            PaintType p = tile.getPaint();
            if(p == PaintType.ALLY_PRIMARY || p == PaintType.ALLY_SECONDARY){
                continue;
            }
            frontierCount++;
            int dist;
            if(spawnTower != null){
                dist = tile.getMapLocation().distanceSquaredTo(spawnTower);
            } else {
                dist = myLoc.distanceSquaredTo(tile.getMapLocation());
            }
            if(dist > bestDist){ 
                bestDist = dist; 
                bestFrontier = tile.getMapLocation(); 
            }
        }
        if(bestFrontier != null && frontierCount > 3){
            exploreTarget = bestFrontier;
        } else {
            assignSector(rc);
            MapLocation st = sectorCenter();
            if(myLoc.distanceSquaredTo(st) <= 16){ 
                rotateSector(); 
                st = sectorCenter(); 
            }
            exploreTarget = st;
            if (spawnTower != null && st.distanceSquaredTo(spawnTower) < 50 && mirrorTower != null)
                exploreTarget = mirrorTower;
        }
    }

    static MapLocation sectorCenter() {
        return new MapLocation(Math.min(mapWidth - 1, mySecX * SECTOR_SIZE + SECTOR_SIZE / 2),
            Math.min(mapHeight - 1, mySecY * SECTOR_SIZE + SECTOR_SIZE / 2));
    }

    static void assignSector(RobotController rc) {
        if(mapWidth <= 0 || mySecX >= 0){
            return;
        }
        int id = rc.getID();
        int sx = (mapWidth + SECTOR_SIZE - 1) / SECTOR_SIZE;
        int sy = (mapHeight + SECTOR_SIZE - 1) / SECTOR_SIZE;
        mySecX = (id * 7) % sx;
        mySecY = (id * 13) % sy;
    }

    static void rotateSector() {
        if(mapWidth <= 0){
            return;
        }
        int sx = (mapWidth + SECTOR_SIZE - 1) / SECTOR_SIZE;
        int sy = (mapHeight + SECTOR_SIZE - 1) / SECTOR_SIZE;
        mySecX++;
        if(mySecX >= sx){ 
            mySecX = 0; 
            mySecY = (mySecY + 1) % sy; 
        }
    }

    static int recentVisitPenalty(MapLocation loc) {
        int c = 0;
        for(MapLocation r : recentLocs){
            if(r != null && r.equals(loc)){
                c++;
            }
        }
        return c == 0 ? 0 : c == 1 ? 12 : c == 2 ? 28 : c == 3 ? 48 : 60;
    }

    // ngecat
    static int greedyAttackEnemyPaint(RobotController rc) throws GameActionException {
        if(!rc.isActionReady()){
            return 0;
        }

        MapLocation best = null; 
        int bestGain = 0;
        MapLocation myLoc = rc.getLocation();
        for(MapInfo tile : rc.senseNearbyMapInfos(myLoc, 9)){
            PaintType p = tile.getPaint();
            if(p != PaintType.ENEMY_PRIMARY && p != PaintType.ENEMY_SECONDARY){
                continue;
            }
            if(!rc.canAttack(tile.getMapLocation())){
                continue;
            }
            // nyari yang deket
            int gain = 10 - myLoc.distanceSquaredTo(tile.getMapLocation());
            for(MapInfo a : rc.senseNearbyMapInfos(tile.getMapLocation(), 2)){
                if(a.hasRuin()){
                    gain += 5;
                }
            }
            if(gain > bestGain){ 
                bestGain = gain; 
                best = tile.getMapLocation(); 
            }
        }
        if(best != null){ 
            rc.attack(best); 
            return bestGain; 
        }
        return 0;
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

    // move satu langkah ke arah target 
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if(!rc.isMovementReady() || target == null){
            return;
        }
        Direction dir = rc.getLocation().directionTo(target);
        if(rc.canMove(dir)){
            rc.move(dir);
            return;
        }
        Direction[] alts = {
            dir.rotateLeft(), dir.rotateRight(),
            dir.rotateLeft().rotateLeft(), dir.rotateRight().rotateRight()
        };
        for(Direction alt : alts){
            if(rc.canMove(alt)){
                rc.move(alt);
                return;
            }
        }
    }

    // gangguin ruin musuh (gbisa bikin tower)
    static void taintEnemyRuin(RobotController rc) throws GameActionException {
        if(!rc.isActionReady()){
            return;
        }
        MapLocation myLoc = rc.getLocation();
        for(MapInfo tile : rc.senseNearbyMapInfos()){
            if(!tile.hasRuin()){
                continue;
            }
            MapLocation rl = tile.getMapLocation();
            if(rc.senseRobotAtLocation(rl) != null){
                continue;
            }
            if(mirrorTower != null && myLoc.distanceSquaredTo(rl) < mirrorTower.distanceSquaredTo(rl)){
                continue;
            }
            for(MapInfo pat : rc.senseNearbyMapInfos(rl, 8)){
                if(!pat.isPassable() || pat.getPaint().isAlly()){
                    continue;
                }
                if(rc.canAttack(pat.getMapLocation())){
                    rc.attack(pat.getMapLocation());
                    return;
                }
            }
        }
    }

    // build SRP (special res pattern)
    static boolean tryBuildSRP(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation bestCenter = null;
        int bestDist = Integer.MAX_VALUE;
        for(MapInfo tile : rc.senseNearbyMapInfos()){
            MapLocation loc = tile.getMapLocation();
            if(loc.x % 4 != 2 || loc.y % 4 != 2){
                continue;
            }
            int dist = myLoc.distanceSquaredTo(loc);
            if(dist >= bestDist){
                continue;
            }
            boolean valid = true;
            for(MapInfo t : rc.senseNearbyMapInfos(loc, 8)){
                if(!t.isPassable() && !t.getMapLocation().equals(loc) && t.hasRuin()){
                    valid = false;
                    break;
                }
            }
            if(!valid){
                continue;
            }
            bestDist = dist;
            bestCenter = loc;
        }
        if(bestCenter == null){
            return false;
        }
        if(myLoc.distanceSquaredTo(bestCenter) > 8){
            moveToward(rc, bestCenter);
            return true;
        }
        if(rc.isActionReady()){
            for(MapInfo pat : rc.senseNearbyMapInfos(bestCenter, 8)){
                if(!pat.isPassable() || pat.getPaint().isAlly()){
                    continue;
                }
                if(rc.canAttack(pat.getMapLocation())){
                    int dx = pat.getMapLocation().x - bestCenter.x;
                    int dy = pat.getMapLocation().y - bestCenter.y;
                    boolean useSec = (dx + dy) % 2 != 0;
                    rc.attack(pat.getMapLocation(), useSec);
                    return true;
                }
            }
        }
        return false;
    }

    // cek apakah dekat ruin yg sedang dibangun (ada mark)
    static boolean isNearRuinBeingBuilt(RobotController rc) throws GameActionException {
        for(MapInfo tile : rc.senseNearbyMapInfos()){
            if(!tile.hasRuin()){
                continue;
            }
            MapLocation rl = tile.getMapLocation();
            if(rc.senseRobotAtLocation(rl) != null){
                continue;
            }
            for(MapInfo m : rc.senseNearbyMapInfos(rl, 8)){
                if(m.getMark() != PaintType.EMPTY){
                    return true;
                }
            }
        }
        return false;
    }

    // cat tile terbaik di area sekitar
    static void paintBestNearby(RobotController rc) throws GameActionException {
        if(!rc.isActionReady()){
            return;
        }
        greedyPaint(rc);
    }

    // cat setelah move 
    static void paintAfterMove(RobotController rc) throws GameActionException {
        if(!rc.isActionReady()){
            return;
        }
        MapLocation myLoc = rc.getLocation();
        MapInfo curr = rc.senseMapInfo(myLoc);
        if(!curr.getPaint().isAlly() && rc.canAttack(myLoc)){
            rc.attack(myLoc);
            return;
        }
        greedyPaint(rc);
    }
}