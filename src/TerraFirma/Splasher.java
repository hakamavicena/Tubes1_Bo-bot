package TerraFirma;

import battlecode.common.*;

final class Splasher {

    private Splasher() {}

    static void run(RobotController rc) throws GameActionException {
        RobotPlayer.initState(rc);
        RobotPlayer.markVisitedSector(rc.getLocation(), rc.getRoundNum());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            Soldier.repEnemySeen(rc);
        }
        RobotPlayer.refreshFrontTarget(rc, enemies);

        if (needsRefill(rc, enemies)) {
            Soldier.doRefill(rc);
            return;
        }

        boolean pressureMode = enemies.length > 0
                || RobotPlayer.currentFrontTarget(rc.getRoundNum()) != null
                || RobotPlayer.freshHint(RobotPlayer.hintedEnemyRound, 12);
        if (rc.isActionReady()) {
            fireGreedySplash(rc, enemies, false, !pressureMode);
        }
        greedyMove(rc, enemies);
        if (rc.isActionReady()) {
            fireGreedySplash(rc, rc.senseNearbyRobots(-1, rc.getTeam().opponent()), true, !pressureMode);
        }
    }

    static boolean needsRefill(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);
        int exitPaint = mapArea > 2500 ? 45 : 60;
        if (rc.getPaint() >= exitPaint) {
            RobotPlayer.refillMode = false;
            return false;
        }
        MapLocation tower = RobotPlayer.nearestAllyTowerLoc(rc);
        int steps = RobotPlayer.estimateTravelSteps(rc.getLocation(), tower);
        if (rc.getPaint() < 18) {
            RobotPlayer.refillMode = true;
        } else if (rc.getPaint() < Math.min(mapArea > 2500 ? 36 : 52, 18 + steps * 2) && enemies.length == 0) {
            RobotPlayer.refillMode = true;
        }
        if (RobotPlayer.refillMode && rc.getPaint() >= exitPaint) {
            RobotPlayer.refillMode = false;
        }
        return RobotPlayer.refillMode;
    }

    static void fireGreedySplash(RobotController rc, RobotInfo[] enemies, boolean afterMove, boolean coverageMode) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);
        MapLocation frontTarget = RobotPlayer.currentFrontTarget(rc.getRoundNum());
        MapLocation bestCenter = null;
        int bestGain = Integer.MIN_VALUE;
        int threshold = coverageMode ? (afterMove ? 1 : 2) : (afterMove ? 2 : 4);
        if (RobotPlayer.macroPhase() == 2 && mapArea > 2500) threshold--;
        if (RobotPlayer.freshHint(RobotPlayer.hintedEnemyRound, 12) && !coverageMode) threshold++;
        for (MapInfo candidate : rc.senseNearbyMapInfos(myLoc, 4)) {
            MapLocation center = candidate.getMapLocation();
            if (!rc.canAttack(center)) continue;
            int gain = scoreBlastCenter(rc, center, coverageMode);
            for (RobotInfo enemy : enemies) {
                if (center.distanceSquaredTo(enemy.location) <= 8) gain += enemy.type.isTowerType() ? 10 : 5;
            }
            if (!coverageMode && frontTarget != null) {
                gain += Math.max(0, 32 - center.distanceSquaredTo(frontTarget)) / 3;
            }
            if (RobotPlayer.turnCount < 120 || coverageMode) {
                gain += RobotPlayer.localClaimValue(rc, center, 4) * (coverageMode ? 2 : 1);
            }
            if (gain > bestGain) {
                bestGain = gain;
                bestCenter = center;
            }
        }
        if (bestCenter != null && bestGain >= threshold) {
            rc.attack(bestCenter);
        }
    }

    static void greedyMove(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation myLoc = rc.getLocation();
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);
        RobotPlayer.recentLocs[RobotPlayer.recentIdx % RobotPlayer.recentLocs.length] = myLoc;
        RobotPlayer.recentIdx++;
        RobotPlayer.refreshExploreTarget(rc, myLoc);
        MapLocation frontTarget = RobotPlayer.currentFrontTarget(rc.getRoundNum());

        MapLocation target = frontTarget != null ? frontTarget : RobotPlayer.exploreTarget;
        if (frontTarget == null && RobotPlayer.hintedEnemy != null && RobotPlayer.freshHint(RobotPlayer.hintedEnemyRound, 12)) {
            target = RobotPlayer.hintedEnemy;
        } else if (RobotPlayer.macroPhase() == 2 && RobotPlayer.mirrorTower != null) {
            target = RobotPlayer.mirrorTower;
        }

        Direction bestDirection = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction direction : RobotPlayer.directions) {
            if (!rc.canMove(direction)) continue;
            MapLocation next = myLoc.add(direction);
            int score = 0;
            PaintType paint = rc.senseMapInfo(next).getPaint();
            if (RobotPlayer.isEnemyPaint(paint)) score += 14;
            else if (paint == PaintType.EMPTY) score += 10;
            else score -= 2;

            int maxGain = 0;
            for (MapInfo candidate : rc.senseNearbyMapInfos(next, 4)) {
                if (next.distanceSquaredTo(candidate.getMapLocation()) > 4) continue;
                int gain = scoreBlastCenter(rc, candidate.getMapLocation(), enemies.length == 0);
                if (gain > maxGain) maxGain = gain;
            }
            score += maxGain * 3;
            score += RobotPlayer.localClaimValue(rc, next, 8) * 2;
            if (mapArea > 2500) score += RobotPlayer.localClaimValue(rc, next, 12);

            if (target != null) {
                int nowDist = myLoc.distanceSquaredTo(target);
                int nextDist = next.distanceSquaredTo(target);
                if (nextDist < nowDist) score += 18;
                else if (nextDist > nowDist) score -= 8;
                if (mapArea > 2500) score += RobotPlayer.edgeProgressBias(myLoc, next, target);
            }
            if (frontTarget != null) {
                score += RobotPlayer.frontPushBias(myLoc, next, frontTarget);
            }

            if (RobotPlayer.spawnTower != null) {
                int currentDistance = myLoc.distanceSquaredTo(RobotPlayer.spawnTower);
                int nextDistance = next.distanceSquaredTo(RobotPlayer.spawnTower);
                if (nextDistance > currentDistance) score += enemies.length == 0 ? 12 : 6;
                if (mapArea > 2500 && nextDistance > currentDistance) score += 8;
            }

            if (mapArea > 2500) score += RobotPlayer.edgeSectorBias(next) / 4;

            score -= RobotPlayer.recentVisitPenalty(next) / 3;
            if (RobotPlayer.blockedAround(rc, next) >= 5) score -= 12;
            score += RobotPlayer.rng.nextInt(2);
            if (score > bestScore) {
                bestScore = score;
                bestDirection = direction;
            }
        }

        if (bestDirection != null) {
            rc.move(bestDirection);
        } else if (target != null) {
            RobotPlayer.moveToward(rc, target);
        }
    }

    static int scoreBlastCenter(RobotController rc, MapLocation center, boolean coverageMode) throws GameActionException {
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);
        int gain = 0;
        for (MapInfo tile : rc.senseNearbyMapInfos(center, 4)) {
            if (!tile.isPassable()) continue;
            PaintType paint = tile.getPaint();
            int distance = center.distanceSquaredTo(tile.getMapLocation());
            if (RobotPlayer.isEnemyPaint(paint)) {
                gain += distance <= 2 ? (coverageMode ? 4 : 6) : (coverageMode ? 2 : 4);
            } else if (paint == PaintType.EMPTY) {
                if (mapArea > 2500 && coverageMode) {
                    gain += distance <= 1 ? 7 : 5;
                } else if (mapArea > 2500 && RobotPlayer.turnCount < 180) {
                    gain += distance <= 1 ? 5 : 3;
                } else if (mapArea > 2500) {
                    gain += distance <= 1 ? 3 : 2;
                } else {
                    gain += coverageMode ? (distance <= 1 ? 5 : 3)
                            : (RobotPlayer.turnCount < 140 ? (distance <= 1 ? 4 : 2) : (distance <= 1 ? 2 : 1));
                }
            }
            if (tile.hasRuin()) gain += 4;
        }
        return gain;
    }
}
