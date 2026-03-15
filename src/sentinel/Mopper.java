package sentinel;

import battlecode.common.*;

final class Mopper {

    private Mopper() {}

    static void run(RobotController rc) throws GameActionException {
        RobotPlayer.initState(rc);
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        if (rc.isActionReady()) {
            Direction bestSwing = null;
            int swingGain = 0;
            for (Direction direction : new Direction[] {
                    Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
            }) {
                if (!rc.canMopSwing(direction)) continue;
                int count = countSwingEnemy(rc, direction, enemies);
                int gain = count * 30;
                if (gain > swingGain) {
                    swingGain = gain;
                    bestSwing = direction;
                }
            }

            RobotInfo bestSoldier = null;
            int transferGain = 0;
            for (RobotInfo ally : allies) {
                if (ally.type != UnitType.SOLDIER || myLoc.distanceSquaredTo(ally.location) > 2) continue;
                int percent = (int) (100.0 * ally.paintAmount / ally.type.paintCapacity);
                if (percent >= 50) continue;
                int give = Math.min(rc.getPaint() - 10, ally.type.paintCapacity - ally.paintAmount);
                if (give <= 0) continue;
                int gain = percent < 20 ? give * 2 : give;
                if (gain > transferGain) {
                    transferGain = gain;
                    bestSoldier = ally;
                }
            }

            MapLocation bestMopTile = null;
            int mopGain = 0;
            for (MapInfo tile : rc.senseNearbyMapInfos(myLoc, 2)) {
                if (!RobotPlayer.isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;

                int gain = 15;
                RobotInfo onTile = rc.senseRobotAtLocation(tile.getMapLocation());
                if (onTile != null && onTile.team != rc.getTeam()) {
                    gain += 20;
                }
                for (MapInfo nearby : rc.senseNearbyMapInfos(tile.getMapLocation(), 2)) {
                    if (nearby.hasRuin()) {
                        gain += 15;
                    }
                }
                if (gain > mopGain) {
                    mopGain = gain;
                    bestMopTile = tile.getMapLocation();
                }
            }

            if (swingGain > 0 && swingGain >= transferGain && swingGain >= mopGain) {
                rc.mopSwing(bestSwing);
            } else if (mopGain > 0 && bestMopTile != null && mopGain >= transferGain) {
                rc.attack(bestMopTile);
            } else if (transferGain > 0 && bestSoldier != null) {
                int give = Math.min(rc.getPaint() - 10, bestSoldier.type.paintCapacity - bestSoldier.paintAmount);
                if (give > 0 && rc.canTransferPaint(bestSoldier.location, give)) {
                    rc.transferPaint(bestSoldier.location, give);
                }
            }
        }

        greedyMove(rc, allies, enemies);
    }

    static int countSwingEnemy(RobotController rc, Direction direction, RobotInfo[] enemies) {
        MapLocation myLoc = rc.getLocation();
        MapLocation step1 = myLoc.add(direction);
        MapLocation step2 = step1.add(direction);
        int count = 0;
        for (RobotInfo enemy : enemies) {
            if (enemy.location.isAdjacentTo(step1) || enemy.location.equals(step1) || enemy.location.isAdjacentTo(step2) || enemy.location.equals(step2)) {
                count++;
            }
        }
        return count;
    }

    static void greedyMove(RobotController rc, RobotInfo[] allies, RobotInfo[] enemies) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation myLoc = rc.getLocation();
        if (enemies.length > 0) {
            RobotInfo closestEnemy = null;
            int closestDistance = Integer.MAX_VALUE;
            for (RobotInfo enemy : enemies) {
                int distance = myLoc.distanceSquaredTo(enemy.location);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestEnemy = enemy;
                }
            }
            if (closestEnemy != null && closestDistance > 2) {
                RobotPlayer.moveToward(rc, closestEnemy.location);
                return;
            }
        }

        MapLocation soldierTarget = null;
        int lowestPercent = 40;
        for (RobotInfo ally : allies) {
            if (ally.type != UnitType.SOLDIER) continue;
            int percent = (int) (100.0 * ally.paintAmount / ally.type.paintCapacity);
            if (percent < lowestPercent) {
                lowestPercent = percent;
                soldierTarget = ally.location;
            }
        }
        if (soldierTarget != null && rc.getPaint() > 30) {
            RobotPlayer.moveToward(rc, soldierTarget);
            return;
        }

        Direction bestDirection = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction direction : RobotPlayer.directions) {
            if (!rc.canMove(direction)) continue;

            MapLocation next = myLoc.add(direction);
            int score = 0;
            try {
                PaintType paint = rc.senseMapInfo(next).getPaint();
                if (RobotPlayer.isEnemyPaint(paint)) {
                    score += 15;
                } else if (paint == PaintType.EMPTY) {
                    score += 4;
                }
            } catch (GameActionException ignored) {}

            int enemyPaintNearby = 0;
            for (MapInfo tile : rc.senseNearbyMapInfos(next, 2)) {
                if (RobotPlayer.isEnemyPaint(tile.getPaint())) {
                    enemyPaintNearby++;
                }
            }
            score += enemyPaintNearby * 20;

            for (RobotInfo enemy : enemies) {
                int nowDistance = myLoc.distanceSquaredTo(enemy.location);
                int nextDistance = next.distanceSquaredTo(enemy.location);
                if (nextDistance < nowDistance) {
                    score += 15;
                }
            }

            for (RobotInfo ally : allies) {
                if (ally.type != UnitType.SOLDIER) continue;
                int percent = (int) (100.0 * ally.paintAmount / ally.type.paintCapacity);
                int give = Math.min(rc.getPaint() - 10, ally.type.paintCapacity - ally.paintAmount);
                if (percent < 50 && next.distanceSquaredTo(ally.location) <= 2 && give > 0) {
                    score += percent < 20 ? give * 3 : give;
                }
            }

            if (RobotPlayer.spawnTower != null && enemies.length == 0 && next.distanceSquaredTo(RobotPlayer.spawnTower) > myLoc.distanceSquaredTo(RobotPlayer.spawnTower)) {
                score += 9;
            }

            score -= RobotPlayer.recentVisitPenalty(next) / 2;
            if (RobotPlayer.blockedAround(rc, next) >= 5) {
                score -= 10;
            }

            if (score > bestScore) {
                bestScore = score;
                bestDirection = direction;
            }
        }

        if (bestDirection != null) {
            rc.move(bestDirection);
        }
    }
}