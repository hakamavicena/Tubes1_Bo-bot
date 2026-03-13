package bot_1;

import battlecode.common.*;

final class Splasher {

    private Splasher() {}

    static void run(RobotController rc) throws GameActionException {
        RobotPlayer.initState(rc);
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        if (rc.isActionReady()) {
            MapLocation bestCenter = null;
            int bestGain = 0;
            int minGain = rc.getPaint() < 80 || enemies.length > 0 ? 2 : 1;
            for (MapInfo candidate : rc.senseNearbyMapInfos(myLoc, 4)) {
                MapLocation center = candidate.getMapLocation();
                if (!rc.canAttack(center)) continue;
                int gain = calcSplashGain(rc, center);
                for (RobotInfo enemy : enemies) {
                    if (center.distanceSquaredTo(enemy.location) <= 8) {
                        gain += 5;
                    }
                }
                if (gain > bestGain) {
                    bestGain = gain;
                    bestCenter = center;
                }
            }
            if (bestCenter != null && bestGain >= minGain) {
                rc.attack(bestCenter);
            }
        }

        if (rc.isMovementReady()) {
            Direction bestDirection = null;
            int bestScore = Integer.MIN_VALUE;
            for (Direction direction : RobotPlayer.directions) {
                if (!rc.canMove(direction)) continue;

                MapLocation next = myLoc.add(direction);
                int score = 0;
                try {
                    PaintType paint = rc.senseMapInfo(next).getPaint();
                    if (RobotPlayer.isEnemyPaint(paint)) {
                        score += 12;
                    } else if (paint == PaintType.EMPTY) {
                        score += 7;
                    } else {
                        score -= 2;
                    }
                } catch (GameActionException ignored) {}

                int maxGain = 0;
                for (MapInfo candidate : rc.senseNearbyMapInfos(next, 4)) {
                    if (next.distanceSquaredTo(candidate.getMapLocation()) > 4) continue;
                    int gain = calcSplashGain(rc, candidate.getMapLocation());
                    if (gain > maxGain) {
                        maxGain = gain;
                    }
                }
                score += maxGain * 2;

                int paintableCount = 0;
                for (MapInfo tile : rc.senseNearbyMapInfos(next, 8)) {
                    PaintType paint = tile.getPaint();
                    if (RobotPlayer.isEnemyPaint(paint) || paint == PaintType.EMPTY) {
                        paintableCount++;
                    }
                }
                score += paintableCount / 2;

                for (RobotInfo enemy : enemies) {
                    int nowDistance = myLoc.distanceSquaredTo(enemy.location);
                    int nextDistance = next.distanceSquaredTo(enemy.location);
                    if (nextDistance < nowDistance) {
                        score += rc.getPaint() > 70 ? 8 : 5;
                    }
                }

                if (RobotPlayer.spawnTower != null && next.distanceSquaredTo(RobotPlayer.spawnTower) > myLoc.distanceSquaredTo(RobotPlayer.spawnTower)) {
                    score += enemies.length == 0 ? 10 : 5;
                }

                if (RobotPlayer.blockedAround(rc, next) >= 5) {
                    score -= 10;
                }

                score -= RobotPlayer.recentVisitPenalty(next) / 3;
                score += RobotPlayer.rng.nextInt(2);
                if (score > bestScore) {
                    bestScore = score;
                    bestDirection = direction;
                }
            }

            if (bestDirection != null) {
                rc.move(bestDirection);
            }
        }

        if (rc.isActionReady()) {
            MapLocation loc = rc.getLocation();
            MapLocation bestCenter = null;
            int bestGain = 0;
            int minGain = rc.getPaint() < 70 || enemies.length > 0 ? 2 : 1;
            for (MapInfo candidate : rc.senseNearbyMapInfos(loc, 4)) {
                if (!rc.canAttack(candidate.getMapLocation())) continue;
                int gain = calcSplashGain(rc, candidate.getMapLocation());
                if (gain > bestGain) {
                    bestGain = gain;
                    bestCenter = candidate.getMapLocation();
                }
            }
            if (bestCenter != null && bestGain >= minGain) {
                rc.attack(bestCenter);
            }
        }
    }

    static int calcSplashGain(RobotController rc, MapLocation center) throws GameActionException {
        int gain = 0;
        for (MapInfo tile : rc.senseNearbyMapInfos(center, 4)) {
            if (!tile.isPassable()) continue;
            PaintType paint = tile.getPaint();
            int distance = center.distanceSquaredTo(tile.getMapLocation());
            if (RobotPlayer.isEnemyPaint(paint)) {
                gain += distance <= 2 ? 4 : 2;
            } else if (paint == PaintType.EMPTY) {
                gain += distance <= 1 ? 3 : 1;
            }
        }
        return gain;
    }
}