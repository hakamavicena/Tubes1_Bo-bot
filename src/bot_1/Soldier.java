package bot_1;

import battlecode.common.*;

final class Soldier {

    static final int LOW_COMBAT_PAINT = 18;

    private Soldier() {}

    static void run(RobotController rc) throws GameActionException {
        RobotPlayer.initState(rc);
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            repEnemySeen(rc);
        }

        if (needsRefill(rc)) {
            // atk saat masih aman (diatas batas kritis)
            if (enemies.length > 0 && rc.getPaint() > LOW_COMBAT_PAINT) {
                if (rc.isActionReady()) {
                    attackBestTarget(rc, enemies);
                }
            } else {
                repLowPaint(rc);
                doRefill(rc);
                return;
            }
        }

        if (rc.isActionReady() && enemies.length > 0) {
            attackBestTarget(rc, enemies);
        }

        MapInfo bestRuin = findBestRuin(rc);
        if (bestRuin != null) {
            handleRuinBuilding(rc, bestRuin);
            return;
        }

        if (rc.isActionReady() && enemies.length == 0) {
            paintEnemyRuin(rc);
        }

        if (rc.getNumberTowers() >= 6 && rc.isActionReady() && enemies.length == 0 && tryBuildSRP(rc)) {
            return;
        }

        if (rc.isActionReady() && greedyAttackEnemyPaint(rc) == 0 && !isNearRuinBeingBuilt(rc)) {
            paintBestNearby(rc);
        }

        // attack - move - attack.
        greedyMove(rc);

        if (rc.isActionReady()) {
            RobotInfo[] postMoveEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (postMoveEnemies.length > 0) {
                attackBestTarget(rc, postMoveEnemies);
            } else if (!isNearRuinBeingBuilt(rc)) {
                paintAfterMove(rc);
            }
        }
    }

    static void attackBestTarget(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (!rc.isActionReady()) return;

        RobotInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (RobotInfo enemy : enemies) {
            if (!rc.canAttack(enemy.location)) continue;

            int score = 1000 - enemy.health;
            if (enemy.health <= rc.getType().attackStrength) {
                score += 500;
            }
            if (enemy.type.isTowerType()) {
                score += 300;
            } else if (enemy.type == UnitType.SOLDIER) {
                score += 100;
            } else if (enemy.type == UnitType.SPLASHER) {
                score += 80;
            } else {
                score += 50;
            }

            if (score > bestScore) {
                bestScore = score;
                best = enemy;
            }
        }

        if (best != null) {
            rc.attack(best.location);
        }
    }

    // refill based on dist
    static boolean needsRefill(RobotController rc) throws GameActionException {
        int paint = rc.getPaint();
        if (paint >= 60) {
            RobotPlayer.refillMode = false;
            return false;
        }

        boolean towerVisible = false;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.type.isTowerType()) {
                towerVisible = true;
                break;
            }
        }

        if (paint < 10) {
            RobotPlayer.refillMode = true;
            return true;
        }

        if (towerVisible && !RobotPlayer.refillMode) {
            MapLocation tower = RobotPlayer.nearestAllyTowerLoc(rc);
            if (tower != null) {
                int estimatedSteps = (int) Math.sqrt(rc.getLocation().distanceSquaredTo(tower)) + 1;
                RobotPlayer.refillMode = paint < Math.min(50, estimatedSteps * 2 + 15);
            }
        }
        if (RobotPlayer.refillMode && paint >= 60) {
            RobotPlayer.refillMode = false;
        }
        return RobotPlayer.refillMode;
    }

    static void doRefill(RobotController rc) throws GameActionException {
        RobotInfo nearestTower = null;
        int bestDistance = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.type.isTowerType()) continue;
            int distance = rc.getLocation().distanceSquaredTo(ally.location);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearestTower = ally;
            }
        }

        if (nearestTower != null) {
            if (rc.isActionReady() && rc.getLocation().distanceSquaredTo(nearestTower.location) <= 2) {
                int take = Math.min(rc.getType().paintCapacity - rc.getPaint(), nearestTower.paintAmount);
                if (take > 0 && rc.canTransferPaint(nearestTower.location, -take)) {
                    rc.transferPaint(nearestTower.location, -take);
                    RobotPlayer.refillMode = false;
                    return;
                }
            }
            RobotPlayer.moveToward(rc, nearestTower.location);
            return;
        }

        Direction bestDirection = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction direction : RobotPlayer.directions) {
            if (!rc.canMove(direction)) continue;
            int score = 0;
            for (MapInfo tile : rc.senseNearbyMapInfos(rc.getLocation().add(direction), 4)) {
                if (tile.getPaint().isAlly()) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestDirection = direction;
            }
        }

        if (bestDirection != null && rc.isMovementReady()) {
            rc.move(bestDirection);
        }
        RobotPlayer.refillMode = false;
    }

    // kejar musuh + push
    static void greedyMove(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation myLoc = rc.getLocation();
        Direction bestDirection = null;
        int bestScore = Integer.MIN_VALUE;

        if (RobotPlayer.lastLoc != null && myLoc.equals(RobotPlayer.lastLoc)) {
            RobotPlayer.stuckCount++;
            if (RobotPlayer.stuckCount >= 3) {
                Direction randomDirection = RobotPlayer.directions[RobotPlayer.rng.nextInt(8)];
                if (rc.canMove(randomDirection)) {
                    rc.move(randomDirection);
                    RobotPlayer.stuckCount = 0;
                    RobotPlayer.lastLoc = myLoc;
                    return;
                }
            }
        } else {
            RobotPlayer.stuckCount = 0;
        }

        RobotPlayer.recentLocs[RobotPlayer.recentIdx % 10] = myLoc;
        RobotPlayer.recentIdx++;
        RobotPlayer.targetTurn++;
        if (RobotPlayer.targetTurn > 3 || RobotPlayer.exploreTarget == null || myLoc.distanceSquaredTo(RobotPlayer.exploreTarget) <= 8) {
            RobotPlayer.updateExploreTarget(rc, myLoc);
            RobotPlayer.targetTurn = 0;
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation enemyTarget = null;
        int bestEnemyScore = Integer.MIN_VALUE;
        int allyCombat = 0, enemyCombat = 0;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.type == UnitType.SOLDIER || ally.type == UnitType.MOPPER || ally.type == UnitType.SPLASHER){
                allyCombat++;
            }
        }
        for (RobotInfo enemy : enemies) {
            if (enemy.type == UnitType.SOLDIER || enemy.type == UnitType.MOPPER || enemy.type == UnitType.SPLASHER){
                enemyCombat++;
            }
        }
        boolean allIn = enemyCombat > 0 && allyCombat >= enemyCombat + 1;

        if (enemies.length == 0) {
            RobotPlayer.combatLockTurns = 0;
            RobotPlayer.lockedEnemyLoc = null;
        }
        if (RobotPlayer.combatLockTurns > 0 && RobotPlayer.lockedEnemyLoc != null && enemies.length > 0) {
            RobotInfo nearestLocked = null;
            int bestLockDist = Integer.MAX_VALUE;
            for (RobotInfo enemy : enemies) {
                int dist = enemy.location.distanceSquaredTo(RobotPlayer.lockedEnemyLoc);
                if (dist < bestLockDist) {
                    bestLockDist = dist;
                    nearestLocked = enemy;
                }
            }
            if (nearestLocked != null && bestLockDist <= 25) {
                enemyTarget = nearestLocked.location;
                RobotPlayer.combatLockTurns--;
            } else {
                RobotPlayer.combatLockTurns = 0;
                RobotPlayer.lockedEnemyLoc = null;
            }
        }

        for (RobotInfo enemy : enemies) {
            int score;
            if (enemy.type.isTowerType()) {
                score = 200;
            } else if (enemy.type == UnitType.SOLDIER) {
                score = 100;
            } else if (enemy.type == UnitType.SPLASHER) {
                score = 80;
            } else {
                score = 50;
            }
            score += 1000 - enemy.health;
            if (enemy.type == UnitType.SOLDIER) score += 90;
            if (score > bestEnemyScore) {
                bestEnemyScore = score;
                enemyTarget = enemy.location;
            }
        }
        if (enemyTarget != null) {
            RobotPlayer.lockedEnemyLoc = enemyTarget;
            if (RobotPlayer.combatLockTurns <= 0){
                RobotPlayer.combatLockTurns = 2;
            }
        }

        MapLocation target = RobotPlayer.exploreTarget;
        if (target == null && RobotPlayer.mirrorTower != null) {
            target = RobotPlayer.mirrorTower;
        }

        MapLocation pursuit = enemyTarget != null ? enemyTarget : target;
        if (pursuit != null && RobotPlayer.blockedAround(rc, myLoc) >= 5 && RobotPlayer.tryMoveTowardBug0(rc, pursuit)) {
            RobotPlayer.lastLoc = myLoc;
            return;
        }

        for (Direction direction : RobotPlayer.directions) {
            if (!rc.canMove(direction)) continue;

            MapLocation next = myLoc.add(direction);
            int score = 0;
            if (enemyTarget != null) {
                int nowDistance = myLoc.distanceSquaredTo(enemyTarget);
                int nextDistance = next.distanceSquaredTo(enemyTarget);
                if (nextDistance < nowDistance) {
                    score += rc.getPaint() > 40 ? 34 : 22;
                } else if (nextDistance > nowDistance) {
                    score -= rc.getPaint() > 40 ? 10 : 16;
                }
                if (nextDistance <= 9 && nextDistance < nowDistance) {
                    score += rc.getPaint() > 40 ? 22 : 12;
                }
                if (nowDistance > 9 && nextDistance <= 9) score += 30;
                if (nextDistance <= 9) score += 14;
                if (nowDistance <= 9 && nextDistance > 9) score -= allIn ? 55 : 42;
                if (allIn && nextDistance < nowDistance) score += 16;
            }

            score += RobotPlayer.evalTileGain(rc, next);
            score -= RobotPlayer.recentVisitPenalty(next);

            if (RobotPlayer.blockedAround(rc, next) >= 5) {
                score -= 15;
            }

            if (enemyTarget == null && target != null) {
                int nowDistance = myLoc.distanceSquaredTo(target);
                int nextDistance = next.distanceSquaredTo(target);
                if (nextDistance < nowDistance) {
                    score += 26;
                } else if (nextDistance > nowDistance) {
                    score -= 10;
                }
            }

            int allySoldiers = 0;
            for (RobotInfo ally : rc.senseNearbyRobots(next, 8, rc.getTeam())) {
                if (ally.type == UnitType.SOLDIER) {
                    allySoldiers++;
                }
            }
            score -= allySoldiers * 4;

            if (RobotPlayer.spawnTower != null && enemyTarget == null) {
                int currentDistance = myLoc.distanceSquaredTo(RobotPlayer.spawnTower);
                int nextDistance = next.distanceSquaredTo(RobotPlayer.spawnTower);
                if (nextDistance > currentDistance) {
                    score += 8;
                } else if (nextDistance < currentDistance) {
                    score -= 5;
                }
            }

            score += RobotPlayer.rng.nextInt(2);
            if (score > bestScore) {
                bestScore = score;
                bestDirection = direction;
            }
        }

        if (bestDirection != null) {
            rc.move(bestDirection);
        }
        RobotPlayer.lastLoc = myLoc;
    }

    static int greedyAttackEnemyPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) {
            return 0;
        }

        MapLocation best = null;
        int bestGain = 0;
        MapLocation myLoc = rc.getLocation();
        for (MapInfo tile : rc.senseNearbyMapInfos(myLoc, 9)) {
            if (!RobotPlayer.isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;

            int gain = 10 - myLoc.distanceSquaredTo(tile.getMapLocation());
            for (MapInfo nearby : rc.senseNearbyMapInfos(tile.getMapLocation(), 2)) {
                if (nearby.hasRuin()) {
                    gain += 5;
                }
            }
            if (gain > bestGain) {
                bestGain = gain;
                best = tile.getMapLocation();
            }
        }

        if (best != null) {
            rc.attack(best);
            return bestGain;
        }
        return 0;
    }

    // cari ruin yang terbaik 
    static MapInfo findBestRuin(RobotController rc) throws GameActionException {
        MapInfo bestRuin = null;
        int bestScore = Integer.MIN_VALUE;
        MapLocation myLoc = rc.getLocation();

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;

            MapLocation ruinLoc = tile.getMapLocation();
            RobotInfo atRuin = rc.senseRobotAtLocation(ruinLoc);
            if (atRuin != null && atRuin.type.isTowerType()) continue;

            int distance = myLoc.distanceSquaredTo(ruinLoc);
            int unpainted = countUnpainted(rc, ruinLoc);
            int score = -distance - unpainted * 3;
            if (RobotPlayer.mapWidth > 0) {
                int centerX = RobotPlayer.mapWidth / 2;
                int centerY = RobotPlayer.mapHeight / 2;
                int centerDistance = (ruinLoc.x - centerX) * (ruinLoc.x - centerX + (ruinLoc.y - centerY) * (ruinLoc.y - centerY));
                score += Math.max(0, 50 - centerDistance / 4);
            }
            if (RobotPlayer.mirrorTower != null && ruinLoc.distanceSquaredTo(RobotPlayer.mirrorTower) < 50) {
                score += 20;
            }
            score -= rc.senseNearbyRobots(ruinLoc, 25, rc.getTeam().opponent()).length * 15;

            if (score > bestScore) {
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

        for (MapInfo tile : tiles) {
            if (tile.getMark() != PaintType.EMPTY) {
                hasMark = true;
                break;
            }
        }

        if (hasMark) {
            for (MapInfo tile : tiles) {
                if (tile.getMark() != PaintType.EMPTY && tile.getMark() != tile.getPaint()) {
                    count++;
                }
            }
        } else {
            for (MapInfo tile : tiles) {
                if (tile.isPassable() && !tile.getPaint().isAlly()) {
                    count++;
                }
            }
        }
        return count;
    }

    static void handleRuinBuilding(RobotController rc, MapInfo ruinInfo) throws GameActionException {
        MapLocation ruinLoc = ruinInfo.getMapLocation();
        MapLocation myLoc = rc.getLocation();
        UnitType towerType = chooseTower(rc, ruinLoc);

        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            boolean alreadyMarked = false;
            for (MapInfo tile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (tile.getMark() != PaintType.EMPTY) {
                    alreadyMarked = true;
                    break;
                }
            }
            if (!alreadyMarked) {
                rc.markTowerPattern(towerType, ruinLoc);
            }
        }

        if (rc.isMovementReady()) {
            RobotPlayer.moveToward(rc, ruinLoc);
            myLoc = rc.getLocation();
        }

        if (rc.isActionReady()) {
            MapLocation closestUnpainted = null;
            boolean useSecondary = false;
            int closestDistance = Integer.MAX_VALUE;
            for (MapInfo tile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (tile.getMark() == PaintType.EMPTY || tile.getMark() == tile.getPaint()) continue;
                if (!rc.canAttack(tile.getMapLocation())) continue;

                int distance = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestUnpainted = tile.getMapLocation();
                    useSecondary = tile.getMark() == PaintType.ALLY_SECONDARY;
                }
            }
            if (closestUnpainted != null) {
                rc.attack(closestUnpainted, useSecondary);
            }
        }

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            rc.setTimelineMarker("Tower Built!", 0, 255, 0);
        }

        repRuinFound(rc, ruinLoc);
    }

    static UnitType chooseTower(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        int paintTowers = 0;
        int moneyTowers = 0;
        int defenseTowers = 0;

        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.type == UnitType.LEVEL_ONE_PAINT_TOWER || ally.type == UnitType.LEVEL_TWO_PAINT_TOWER || ally.type == UnitType.LEVEL_THREE_PAINT_TOWER) {
                paintTowers++;
            } else if (ally.type == UnitType.LEVEL_ONE_MONEY_TOWER || ally.type == UnitType.LEVEL_TWO_MONEY_TOWER || ally.type == UnitType.LEVEL_THREE_MONEY_TOWER) {
                moneyTowers++;
            } else if (ally.type == UnitType.LEVEL_ONE_DEFENSE_TOWER || ally.type == UnitType.LEVEL_TWO_DEFENSE_TOWER || ally.type == UnitType.LEVEL_THREE_DEFENSE_TOWER) {
                defenseTowers++;
            }
        }

        int enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length;
        int totalTowers = paintTowers + moneyTowers + defenseTowers;

        int moneyValue = moneyTowers == 0 ? 90 : Math.max(0, 50 - moneyTowers * 10);
        if (totalTowers < 3) {
            moneyValue += 20;
        }

        int paintValue = paintTowers == 0 ? 80 : Math.max(0, 45 - paintTowers * 10);
        if (totalTowers >= 3 && paintTowers <= moneyTowers) {
            paintValue += 20;
        }

        int defenseValue;
        if (enemyRobots >= 3 && defenseTowers == 0) {
            defenseValue = 90;
        } else if (enemyRobots >= 2) {
            defenseValue = 40 + enemyRobots * 5;
        } else {
            defenseValue = Math.max(0, 15 - defenseTowers * 10);
        }

        if (RobotPlayer.mapWidth > 0) {
            int centerX = RobotPlayer.mapWidth / 2;
            int centerY = RobotPlayer.mapHeight / 2;
            int centerDistance = ruinLoc.distanceSquaredTo(new MapLocation(centerX, centerY));
            if (centerDistance < 35 && enemyRobots > 0) {
                defenseValue += 25;
            }
        }

        if (moneyValue >= paintValue && moneyValue >= defenseValue) {
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        }
        if (defenseValue >= paintValue) {
            return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        }
        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    static void repLowPaint(RobotController rc) throws GameActionException {
        MapLocation loc = rc.getLocation();
        RobotPlayer.sendMsgToTower(rc, RobotPlayer.MSG_PAINTLOW | (loc.x << 15) | loc.y);
    }

    static void repRuinFound(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        RobotPlayer.sendMsgToTower(rc, RobotPlayer.MSG_RUIN | (ruinLoc.x << 15) | ruinLoc.y);
    }

    static void repEnemySeen(RobotController rc) throws GameActionException {
        MapLocation loc = rc.getLocation();
        RobotPlayer.sendMsgToTower(rc, RobotPlayer.MSG_ENEMY | (loc.x << 15) | loc.y);
    }

    // ganggu musuh (gbisa bikin tower)
    static void paintEnemyRuin(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        MapLocation myLoc = rc.getLocation();
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            if (rc.senseRobotAtLocation(ruinLoc) != null) continue;
            if (RobotPlayer.mirrorTower != null && myLoc.distanceSquaredTo(ruinLoc) < RobotPlayer.mirrorTower.distanceSquaredTo(ruinLoc)) continue;

            for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (!patternTile.isPassable() || patternTile.getPaint().isAlly()) continue;
                if (rc.canAttack(patternTile.getMapLocation())) {
                    rc.attack(patternTile.getMapLocation());
                    return;
                }
            }
        }
    }

    // special resource pattern
    static boolean tryBuildSRP(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation bestCenter = null;
        int bestDistance = Integer.MAX_VALUE;

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            MapLocation loc = tile.getMapLocation();
            if (loc.x % 4 != 2 || loc.y % 4 != 2) continue;

            int distance = myLoc.distanceSquaredTo(loc);
            if (distance >= bestDistance) continue;

            boolean valid = true;
            for (MapInfo nearby : rc.senseNearbyMapInfos(loc, 8)) {
                if (!nearby.isPassable() && !nearby.getMapLocation().equals(loc) && nearby.hasRuin()) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;

            bestDistance = distance;
            bestCenter = loc;
        }

        if (bestCenter == null) {
            return false;
        }
        if (myLoc.distanceSquaredTo(bestCenter) > 8) {
            RobotPlayer.moveToward(rc, bestCenter);
            return true;
        }
        if (rc.isActionReady()) {
            for (MapInfo patternTile : rc.senseNearbyMapInfos(bestCenter, 8)) {
                if (!patternTile.isPassable() || patternTile.getPaint().isAlly()) continue;
                if (rc.canAttack(patternTile.getMapLocation())) {
                    int dx = patternTile.getMapLocation().x - bestCenter.x;
                    int dy = patternTile.getMapLocation().y - bestCenter.y;
                    rc.attack(patternTile.getMapLocation(), (dx + dy) % 2 != 0);
                    return true;
                }
            }
        }
        return false;
    }

    // apakah ruin dekat sedang bangun tower
    static boolean isNearRuinBeingBuilt(RobotController rc) throws GameActionException {
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;

            MapLocation ruinLoc = tile.getMapLocation();
            if (rc.senseRobotAtLocation(ruinLoc) != null) continue;
            for (MapInfo nearby : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (nearby.getMark() != PaintType.EMPTY) {
                    return true;
                }
            }
        }
        return false;
    }

    static void paintBestNearby(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MapInfo tile : rc.senseNearbyMapInfos(rc.getLocation(), 4)) {
            if (!rc.canAttack(tile.getMapLocation())) continue;

            PaintType paint = tile.getPaint();
            if (paint.isAlly()) continue;

            int score;
            if (RobotPlayer.isEnemyPaint(paint)) {
                score = 7;
            } else if (paint == PaintType.EMPTY) {
                score = 3;
            } else {
                score = 0;
            }

            if (RobotPlayer.spawnTower != null) {
                score += tile.getMapLocation().distanceSquaredTo(RobotPlayer.spawnTower) / 20;
            }
            if (score > bestScore) {
                bestScore = score;
                best = tile.getMapLocation();
            }
        }

        if (best != null && bestScore > 0) {
            rc.attack(best);
        }
    }

    // cat abis gerak
    static void paintAfterMove(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        MapLocation myLoc = rc.getLocation();
        MapInfo current = rc.senseMapInfo(myLoc);
        if (!current.getPaint().isAlly() && rc.canAttack(myLoc)) {
            rc.attack(myLoc);
            return;
        }
        paintBestNearby(rc);
    }
}