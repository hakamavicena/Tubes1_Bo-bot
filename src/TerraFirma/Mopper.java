package TerraFirma;

import battlecode.common.*;

final class Mopper {

    private Mopper() {}

    static void run(RobotController rc) throws GameActionException {
        RobotPlayer.initState(rc);
        RobotPlayer.markVisitedSector(rc.getLocation(), rc.getRoundNum());
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotPlayer.refreshFrontTarget(rc, enemies);

        if (rc.getPaint() < 20) {
            Soldier.doRefill(rc);
            return;
        }

        if (rc.isActionReady()) {
            Direction bestSwing = null;
            int swingScore = 0;
            for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                if (!rc.canMopSwing(direction)) continue;
                int count = countSwingEnemy(rc, direction, enemies);
                int score = count * 32 + (enemies.length > 0 ? 12 : 0);
                if (score > swingScore) {
                    swingScore = score;
                    bestSwing = direction;
                }
            }

            RobotInfo bestSoldier = null;
            int relayScore = 0;
            for (RobotInfo ally : allies) {
                if (ally.type != UnitType.SOLDIER || myLoc.distanceSquaredTo(ally.location) > 2) continue;
                int percent = (int) (100.0 * ally.paintAmount / ally.type.paintCapacity);
                if (percent >= 60) continue;
                int give = Math.min(rc.getPaint() - 15, ally.type.paintCapacity - ally.paintAmount);
                if (give <= 0) continue;
                int score = percent < 20 ? give * 4 : give * 2;
                if (RobotPlayer.hintedRuin != null && ally.location.distanceSquaredTo(RobotPlayer.hintedRuin) <= 20) score += 18;
                if (score > relayScore) {
                    relayScore = score;
                    bestSoldier = ally;
                }
            }

            MapLocation bestScrubTile = null;
            int scrubScore = 0;
            for (MapInfo tile : rc.senseNearbyMapInfos(myLoc, 2)) {
                if (!RobotPlayer.isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;
                int score = 20;
                RobotInfo onTile = rc.senseRobotAtLocation(tile.getMapLocation());
                if (onTile != null && onTile.team != rc.getTeam()) score += 30;
                for (MapInfo nearby : rc.senseNearbyMapInfos(tile.getMapLocation(), 2)) {
                    if (nearby.hasRuin()) score += 20;
                }
                if (RobotPlayer.hintedRuin != null && tile.getMapLocation().distanceSquaredTo(RobotPlayer.hintedRuin) <= 20) score += 12;
                if (score > scrubScore) {
                    scrubScore = score;
                    bestScrubTile = tile.getMapLocation();
                }
            }

            if (relayScore >= scrubScore && relayScore >= swingScore && bestSoldier != null) {
                int give = Math.min(rc.getPaint() - 15, bestSoldier.type.paintCapacity - bestSoldier.paintAmount);
                if (give > 0 && rc.canTransferPaint(bestSoldier.location, give)) {
                    rc.transferPaint(bestSoldier.location, give);
                }
            } else if (scrubScore >= swingScore && bestScrubTile != null) {
                rc.attack(bestScrubTile);
            } else if (swingScore > 0 && bestSwing != null) {
                rc.mopSwing(bestSwing);
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
        int mapArea = Math.max(1, RobotPlayer.mapWidth * RobotPlayer.mapHeight);
        RobotPlayer.recentLocs[RobotPlayer.recentIdx % RobotPlayer.recentLocs.length] = myLoc;
        RobotPlayer.recentIdx++;
        RobotPlayer.refreshExploreTarget(rc, myLoc);
        MapLocation frontTarget = RobotPlayer.currentFrontTarget(rc.getRoundNum());

        MapLocation supportTarget = null;
        int bestSupport = Integer.MIN_VALUE;
        for (RobotInfo ally : allies) {
            if (ally.type != UnitType.SOLDIER) continue;
            int percent = (int) (100.0 * ally.paintAmount / ally.type.paintCapacity);
            int score = 100 - percent;
            if (ally.location.distanceSquaredTo(myLoc) > 2) score -= ally.location.distanceSquaredTo(myLoc);
            if (score > bestSupport) {
                bestSupport = score;
                supportTarget = ally.location;
            }
        }

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

        MapLocation roamTarget = frontTarget != null ? frontTarget : supportTarget;
        if (roamTarget == null && RobotPlayer.hintedRuin != null && RobotPlayer.freshHint(RobotPlayer.hintedRuinRound, 12)) {
            roamTarget = RobotPlayer.hintedRuin;
        }
        if (roamTarget == null) {
            roamTarget = RobotPlayer.exploreTarget;
        }

        Direction bestDirection = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction direction : RobotPlayer.directions) {
            if (!rc.canMove(direction)) continue;
            MapLocation next = myLoc.add(direction);
            int score = 0;
            PaintType paint = rc.senseMapInfo(next).getPaint();
            if (RobotPlayer.isEnemyPaint(paint)) score += 16;
            else if (paint == PaintType.EMPTY) score += 5;

            int enemyPaintNearby = 0;
            for (MapInfo tile : rc.senseNearbyMapInfos(next, 2)) {
                if (RobotPlayer.isEnemyPaint(tile.getPaint())) enemyPaintNearby++;
                if (tile.hasRuin()) score += 4;
            }
            score += enemyPaintNearby * 18;

            if (roamTarget != null) {
                int nowDist = myLoc.distanceSquaredTo(roamTarget);
                int nextDist = next.distanceSquaredTo(roamTarget);
                if (nextDist < nowDist) score += 15;
                if (mapArea > 2500) score += RobotPlayer.edgeProgressBias(myLoc, next, roamTarget);
            }
            if (frontTarget != null) {
                score += RobotPlayer.frontPushBias(myLoc, next, frontTarget);
            }

            if (RobotPlayer.hintedRuin != null && rc.getRoundNum() - RobotPlayer.hintedRuinRound <= 10) {
                int nowDist = myLoc.distanceSquaredTo(RobotPlayer.hintedRuin);
                int nextDist = next.distanceSquaredTo(RobotPlayer.hintedRuin);
                if (nextDist < nowDist) score += 10;
            }

            if (RobotPlayer.spawnTower != null && enemies.length == 0 && next.distanceSquaredTo(RobotPlayer.spawnTower) > myLoc.distanceSquaredTo(RobotPlayer.spawnTower)) {
                score += 8;
            }

            if (mapArea > 2500) score += RobotPlayer.edgeSectorBias(next) / 5;
            score -= RobotPlayer.recentVisitPenalty(next) / 2;
            if (RobotPlayer.blockedAround(rc, next) >= 5) score -= 10;
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
