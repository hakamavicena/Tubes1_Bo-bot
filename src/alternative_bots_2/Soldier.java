package alternative_bots_2;

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
        RobotPlayer.refreshFrontTarget(rc, enemies);

        MapInfo bestRuin = findHighestYieldRuin(rc);
        boolean lowPaint = needsRefill(rc);

        int refillScore = scoreRefillWindow(rc, lowPaint, enemies, bestRuin);
        int ruinScore = scoreRuinWindow(rc, bestRuin);
        int combatScore = scoreCombatWindow(rc, enemies);
        int claimScore = scoreClaimWindow(rc, enemies, bestRuin);

        if (refillScore >= ruinScore && refillScore >= combatScore && refillScore >= claimScore) {
            runRefillTurn(rc, enemies);
            return;
        }
        if (bestRuin != null && ruinScore >= combatScore && ruinScore >= claimScore) {
            executeRuinPlan(rc, bestRuin);
            return;
        }
        if (combatScore >= claimScore) {
            runCombatTurn(rc, enemies);
            return;
        }
        runClaimTurn(rc, enemies);
    }

    static int scoreRefillWindow(RobotController rc, boolean lowPaint, RobotInfo[] enemies, MapInfo ruin) {
        if (!lowPaint) return Integer.MIN_VALUE;
        int score = 130 - rc.getPaint() * 2;
        if (enemies.length == 0) score += 18;
        if (ruin != null) score -= 24;
        if (RobotPlayer.macroPhase() == 2) score -= 12;
        return score;
    }

    static int scoreRuinWindow(RobotController rc, MapInfo ruin) throws GameActionException {
        if (ruin == null) return Integer.MIN_VALUE;
        MapLocation myLoc = rc.getLocation();
        MapLocation ruinLoc = ruin.getMapLocation();
        int unpainted = countUnpainted(rc, ruinLoc);
        int enemyContest = rc.senseNearbyRobots(ruinLoc, 25, rc.getTeam().opponent()).length;
        int allyCommit = rc.senseNearbyRobots(ruinLoc, 8, rc.getTeam()).length;
        int score = 170;
        score -= myLoc.distanceSquaredTo(ruinLoc) * 2;
        score -= unpainted * 11;
        score -= enemyContest * 22;
        score -= Math.max(0, allyCommit - 2) * 12;
        score += RobotPlayer.localClaimValue(rc, ruinLoc, 8) * 3;
        if (RobotPlayer.freshHint(RobotPlayer.hintedRuinRound, 12) && RobotPlayer.hintedRuin != null
                && RobotPlayer.hintedRuin.distanceSquaredTo(ruinLoc) <= 20) score += 28;
        if (RobotPlayer.macroPhase() == 0) score += 18;
        if (RobotPlayer.isHugeMap()) score += RobotPlayer.edgeSectorBias(ruinLoc) / 4;
        return score;
    }

    static int scoreCombatWindow(RobotController rc, RobotInfo[] enemies) {
        if (enemies.length == 0) return Integer.MIN_VALUE;
        int score = 60 + enemies.length * 14;
        if (rc.isActionReady()) score += 20;
        if (rc.getPaint() > 40) score += 24;
        else if (rc.getPaint() < LOW_COMBAT_PAINT) score -= 18;
        if (RobotPlayer.warFrontActive(rc.getRoundNum())) score += 20;
        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType()) score += 22;
            if (enemy.type == UnitType.SOLDIER) score += 8;
        }
        return score;
    }

    static int scoreClaimWindow(RobotController rc, RobotInfo[] enemies, MapInfo ruin) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int score = 40;
        score += RobotPlayer.localClaimValue(rc, myLoc, RobotPlayer.isHugeMap() ? 12 : 8) * 2;
        if (RobotPlayer.exploreTarget != null) {
            score += Math.max(0, 32 - myLoc.distanceSquaredTo(RobotPlayer.exploreTarget) / 3);
        }
        if (enemies.length == 0) score += 16;
        if (ruin == null) score += 10;
        if (RobotPlayer.warFrontActive(rc.getRoundNum())) score -= 12;
        if (RobotPlayer.isHugeMap()) score += RobotPlayer.edgeSectorBias(myLoc) / 4;
        if (RobotPlayer.macroPhase() == 2) score += 18;
        return score;
    }

    static void runRefillTurn(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (enemies.length > 0 && rc.getPaint() > LOW_COMBAT_PAINT + 6 && rc.isActionReady()) {
            attackBestTarget(rc, enemies);
        }
        repLowPaint(rc);
        doRefill(rc);
    }

    static void runCombatTurn(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (rc.isActionReady() && enemies.length > 0) {
            attackBestTarget(rc, enemies);
        }
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

    static void runClaimTurn(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (rc.isActionReady() && enemies.length == 0) {
            paintOpenRuin(rc);
        }

        if (rc.isActionReady() && rc.getNumberTowers() >= (RobotPlayer.isHugeMap() ? 5 : 6)
                && enemies.length == 0 && tryCarveResourcePattern(rc)) {
            return;
        }

        if (rc.isActionReady() && paintDenyTile(rc) == 0 && !isNearRuinBeingBuilt(rc)) {
            paintBestNearby(rc);
        }

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
            if (enemy.health <= rc.getType().attackStrength) score += 500;
            if (enemy.type.isTowerType()) score += 300;
            else if (enemy.type == UnitType.SOLDIER) score += 100;
            else if (enemy.type == UnitType.SPLASHER) score += 80;
            else score += 50;

            if (score > bestScore) {
                bestScore = score;
                best = enemy;
            }
        }

        if (best != null) {
            rc.attack(best.location);
        }
    }

    static boolean needsRefill(RobotController rc) throws GameActionException {
        int paint = rc.getPaint();
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);
        int exitPaint = mapArea > 2500 ? 55 : 60;
        if (paint >= exitPaint) {
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
                int estimatedSteps = RobotPlayer.estimateTravelSteps(rc.getLocation(), tower) + 1;
                int trigger = mapArea > 2500 ? Math.min(40, estimatedSteps * 2 + 12) : Math.min(50, estimatedSteps * 2 + 15);
                RobotPlayer.refillMode = paint < trigger;
            }
        }
        if (RobotPlayer.refillMode && paint >= exitPaint) {
            RobotPlayer.refillMode = false;
        }
        return RobotPlayer.refillMode;
    }

    static void doRefill(RobotController rc) throws GameActionException {
        RobotInfo nearestTower = null;
        int bestDistance = Integer.MAX_VALUE;
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);
        int exitPaint = mapArea > 2500 ? 55 : 60;
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
                int reserve = mapArea > 2500 ? (rc.getNumberTowers() >= 5 ? 10 : 22) : (rc.getNumberTowers() >= 5 ? 20 : 35);
                int available = Math.max(0, nearestTower.paintAmount - reserve);
                int take = Math.min(rc.getType().paintCapacity - rc.getPaint(), available);
                if (take > 0 && rc.canTransferPaint(nearestTower.location, -take)) {
                    rc.transferPaint(nearestTower.location, -take);
                    if (rc.getPaint() + take >= exitPaint || available <= 10) {
                        RobotPlayer.refillMode = false;
                    }
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
                if (tile.getPaint().isAlly()) score++;
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

    static void greedyMove(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation myLoc = rc.getLocation();
        Direction bestDirection = null;
        int bestScore = Integer.MIN_VALUE;
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);

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

        RobotPlayer.recentLocs[RobotPlayer.recentIdx % RobotPlayer.recentLocs.length] = myLoc;
        RobotPlayer.recentIdx++;
        RobotPlayer.refreshExploreTarget(rc, myLoc);
        MapLocation frontTarget = RobotPlayer.currentFrontTarget(rc.getRoundNum());

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation enemyTarget = null;
        int bestEnemyScore = Integer.MIN_VALUE;
        int allyCombat = 0, enemyCombat = 0;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.type == UnitType.SOLDIER || ally.type == UnitType.MOPPER || ally.type == UnitType.SPLASHER) allyCombat++;
        }
        for (RobotInfo enemy : enemies) {
            if (enemy.type == UnitType.SOLDIER || enemy.type == UnitType.MOPPER || enemy.type == UnitType.SPLASHER) enemyCombat++;
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
            if (enemy.type.isTowerType()) score = 200;
            else if (enemy.type == UnitType.SOLDIER) score = 100;
            else if (enemy.type == UnitType.SPLASHER) score = 80;
            else score = 50;
            score += 1000 - enemy.health;
            if (enemy.type == UnitType.SOLDIER) score += 90;
            if (score > bestEnemyScore) {
                bestEnemyScore = score;
                enemyTarget = enemy.location;
            }
        }
        if (enemyTarget != null) {
            RobotPlayer.lockedEnemyLoc = enemyTarget;
            if (RobotPlayer.combatLockTurns <= 0) RobotPlayer.combatLockTurns = 2;
        }

        MapLocation target = frontTarget != null ? frontTarget : RobotPlayer.exploreTarget;
        if (frontTarget == null && RobotPlayer.hintedRuin != null && rc.getRoundNum() - RobotPlayer.hintedRuinRound <= 15) {
            target = RobotPlayer.hintedRuin;
        }
        if (enemyTarget == null && target == null && RobotPlayer.mirrorTower != null) {
            target = RobotPlayer.mirrorTower;
        }

        MapLocation pursuit = enemyTarget != null ? enemyTarget : target;
        if (pursuit != null && RobotPlayer.blockedAround(rc, myLoc) >= 5 && RobotPlayer.tryMoveTowardObs(rc, pursuit)) {
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
                if (nextDistance < nowDistance) score += rc.getPaint() > 40 ? 34 : 22;
                else if (nextDistance > nowDistance) score -= rc.getPaint() > 40 ? 10 : 16;
                if (nextDistance <= 9 && nextDistance < nowDistance) score += rc.getPaint() > 40 ? 22 : 12;
                if (nowDistance > 9 && nextDistance <= 9) score += 30;
                if (nextDistance <= 9) score += 14;
                if (nowDistance <= 9 && nextDistance > 9) score -= allIn ? 55 : 42;
                if (allIn && nextDistance < nowDistance) score += 16;
            }

            score += RobotPlayer.evalTileGain(rc, next);
            if (mapArea > 2500) score += RobotPlayer.localClaimValue(rc, next, 12);
            score -= RobotPlayer.recentVisitPenalty(next);

            if (RobotPlayer.blockedAround(rc, next) >= 5) score -= 15;

            if (enemyTarget == null && target != null) {
                int nowDistance = myLoc.distanceSquaredTo(target);
                int nextDistance = next.distanceSquaredTo(target);
                if (nextDistance < nowDistance) score += 28;
                else if (nextDistance > nowDistance) score -= 10;
                if (mapArea > 2500) score += RobotPlayer.edgeProgressBias(myLoc, next, target);
            }
            if (enemyTarget == null && frontTarget != null) {
                score += RobotPlayer.frontPushBias(myLoc, next, frontTarget);
            }

            int allySoldiers = 0;
            for (RobotInfo ally : rc.senseNearbyRobots(next, 8, rc.getTeam())) {
                if (ally.type == UnitType.SOLDIER) allySoldiers++;
            }
            score -= allySoldiers * (frontTarget != null ? 2 : 4);

            if (RobotPlayer.spawnTower != null && enemyTarget == null) {
                int currentDistance = myLoc.distanceSquaredTo(RobotPlayer.spawnTower);
                int nextDistance = next.distanceSquaredTo(RobotPlayer.spawnTower);
                if (nextDistance > currentDistance) score += mapArea > 2500 ? 12 : 8;
                else if (nextDistance < currentDistance) score -= 5;
            }

            if (mapArea > 2500) {
                score += RobotPlayer.edgeSectorBias(next) / 5;
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
        RobotPlayer.markVisitedSector(rc.getLocation(), rc.getRoundNum());
    }

    static int paintDenyTile(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return 0;

        MapLocation best = null;
        int bestGain = 0;
        MapLocation myLoc = rc.getLocation();
        for (MapInfo tile : rc.senseNearbyMapInfos(myLoc, 9)) {
            if (!RobotPlayer.isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;

            int gain = 10 - myLoc.distanceSquaredTo(tile.getMapLocation());
            for (MapInfo nearby : rc.senseNearbyMapInfos(tile.getMapLocation(), 2)) {
                if (nearby.hasRuin()) gain += 5;
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

    static MapInfo findHighestYieldRuin(RobotController rc) throws GameActionException {
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
            int enemyContest = rc.senseNearbyRobots(ruinLoc, 25, rc.getTeam().opponent()).length;
            int allyCommit = rc.senseNearbyRobots(ruinLoc, 8, rc.getTeam()).length;
            int score = 170;
            score -= distance * 2;
            score -= unpainted * 11;
            score -= enemyContest * 22;
            score -= Math.max(0, allyCommit - 2) * 12;
            score += RobotPlayer.localClaimValue(rc, ruinLoc, 8) * 3;
            if (RobotPlayer.freshHint(RobotPlayer.hintedRuinRound, 12) && RobotPlayer.hintedRuin != null
                    && RobotPlayer.hintedRuin.distanceSquaredTo(ruinLoc) <= 20) score += 28;
            if (RobotPlayer.macroPhase() == 0) score += 18;
            if (RobotPlayer.isHugeMap()) score += RobotPlayer.edgeSectorBias(ruinLoc) / 4;

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
                if (tile.getMark() != PaintType.EMPTY && tile.getMark() != tile.getPaint()) count++;
            }
        } else {
            for (MapInfo tile : tiles) {
                if (tile.isPassable() && !tile.getPaint().isAlly()) count++;
            }
        }
        return count;
    }

    static void executeRuinPlan(RobotController rc, MapInfo ruinInfo) throws GameActionException {
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
            if (!alreadyMarked) rc.markTowerPattern(towerType, ruinLoc);
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
            if (ally.type == UnitType.LEVEL_ONE_PAINT_TOWER || ally.type == UnitType.LEVEL_TWO_PAINT_TOWER || ally.type == UnitType.LEVEL_THREE_PAINT_TOWER) paintTowers++;
            else if (ally.type == UnitType.LEVEL_ONE_MONEY_TOWER || ally.type == UnitType.LEVEL_TWO_MONEY_TOWER || ally.type == UnitType.LEVEL_THREE_MONEY_TOWER) moneyTowers++;
            else if (ally.type == UnitType.LEVEL_ONE_DEFENSE_TOWER || ally.type == UnitType.LEVEL_TWO_DEFENSE_TOWER || ally.type == UnitType.LEVEL_THREE_DEFENSE_TOWER) defenseTowers++;
        }

        int enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length;
        int totalTowers = paintTowers + moneyTowers + defenseTowers;
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);

        int phase = RobotPlayer.macroPhase();

        int moneyValue = moneyTowers == 0 ? 125 : Math.max(0, 72 - moneyTowers * 7);
        if (RobotPlayer.turnCount < 200) moneyValue += 22;
        if (totalTowers < 5) moneyValue += 22;
        if (mapArea > 2500) {
            if (totalTowers < 9) moneyValue += 28;
            if (moneyTowers < paintTowers) moneyValue += 22;
        }
        if (rc.getChips() < 3200) moneyValue += 14;
        if (phase == 0) moneyValue += 10;
        if (phase == 1) moneyValue += 6;

        int paintValue = paintTowers == 0 ? 78 : Math.max(0, 44 - paintTowers * 8);
        if (mapArea > 2200) paintValue += 18;
        if (paintTowers <= moneyTowers) paintValue += 12;
        if (mapArea > 2500 && totalTowers >= 8 && paintTowers <= moneyTowers) paintValue += 10;
        if (rc.getChips() > 2600) paintValue += 10;
        if (phase == 2) paintValue += 16;

        int defenseValue;
        if (enemyRobots >= 3 && defenseTowers == 0) defenseValue = 90;
        else if (enemyRobots >= 2) defenseValue = 40 + enemyRobots * 5;
        else defenseValue = Math.max(0, 15 - defenseTowers * 10);

        if (RobotPlayer.mapWidth > 0) {
            int centerX = RobotPlayer.mapWidth / 2;
            int centerY = RobotPlayer.mapHeight / 2;
            int dx = ruinLoc.x - centerX;
            int dy = ruinLoc.y - centerY;
            int centerDistance = dx * dx + dy * dy;
            if (centerDistance < 35 && enemyRobots > 0) defenseValue += 25;
        }

        if (moneyValue >= paintValue && moneyValue >= defenseValue) return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (defenseValue >= paintValue) return UnitType.LEVEL_ONE_DEFENSE_TOWER;
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

    static void paintOpenRuin(RobotController rc) throws GameActionException {
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

    static boolean tryCarveResourcePattern(RobotController rc) throws GameActionException {
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

        if (bestCenter == null) return false;
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

    static boolean isNearRuinBeingBuilt(RobotController rc) throws GameActionException {
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            if (rc.senseRobotAtLocation(ruinLoc) != null) continue;
            for (MapInfo nearby : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (nearby.getMark() != PaintType.EMPTY) return true;
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
            if (RobotPlayer.isEnemyPaint(paint)) score = 7;
            else if (paint == PaintType.EMPTY) score = 3;
            else score = 0;

            if (RobotPlayer.spawnTower != null) score += tile.getMapLocation().distanceSquaredTo(RobotPlayer.spawnTower) / 20;
            if (score > bestScore) {
                bestScore = score;
                best = tile.getMapLocation();
            }
        }

        if (best != null && bestScore > 0) {
            rc.attack(best);
        }
    }

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
