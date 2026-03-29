package alternative_bots_2;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {

    static int turnCount = 0;
    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    static final int MSG_ENEMY = 1 << 30;
    static final int MSG_RUIN = 2 << 30;
    static final int MSG_PAINTLOW = 3 << 30;
    static final int SECTOR_SIZE = 14;
    static final int MAX_SECTORS = 32;
    static final int EXPLORE_REAR_FIRST = 0;
    static final int EXPLORE_LEFT_FIRST = 1;
    static final int EXPLORE_RIGHT_FIRST = 2;
    static final int OPENING_SWEEP_END_ROUND = 500;

    static MapLocation[] recentLocs = new MapLocation[18];
    static int recentIdx = 0, stuckCount = 0, targetTurn = 0;
    static int mapWidth = -1, mapHeight = -1, mySecX = -1, mySecY = -1;
    static int exploreStyle = -1, openingDirectionIndex = -1;
    static MapLocation lastLoc = null, exploreTarget = null, spawnTower = null, mirrorTower = null, lockedEnemyLoc = null;
    static boolean symChecked = false, refillMode = false, isObsFollowing = false, obsFollowLeft = true;
    static int obsFollowTurns = 0, obsFollowStartDist = Integer.MAX_VALUE, combatLockTurns = 0;
    static Direction obsWallDirection = Direction.NORTH;
    static MapLocation obsNavTarget = null;
    static MapLocation hintedRuin = null, hintedEnemy = null, frontTarget = null;
    static int hintedRuinRound = -1000, hintedEnemyRound = -1000, frontTargetRound = -1000;
    static final int[][] sectorTouched = new int[MAX_SECTORS][MAX_SECTORS];

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            turnCount++;
            try {
                switch (rc.getType()) {
                    case SOLDIER:
                        Soldier.run(rc);
                        break;
                    case MOPPER:
                        Mopper.run(rc);
                        break;
                    case SPLASHER:
                        Splasher.run(rc);
                        break;
                    default:
                        Tower.run(rc);
                        break;
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

    static boolean isEnemyPaint(PaintType paint) {
        return paint == PaintType.ENEMY_PRIMARY || paint == PaintType.ENEMY_SECONDARY;
    }

    static int blockedAround(RobotController rc, MapLocation center) throws GameActionException {
        int blocked = 0;
        for (Direction direction : directions) {
            MapLocation target = center.add(direction);
            if (!rc.onTheMap(target) || !rc.sensePassability(target)) {
                blocked++;
            }
        }
        return blocked;
    }

    static boolean sendMsgToTower(RobotController rc, int msg) throws GameActionException {
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.type.isTowerType()) continue;
            if (rc.canSendMessage(ally.location, msg)) {
                rc.sendMessage(ally.location, msg);
                return true;
            }
        }
        return false;
    }

    static void initState(RobotController rc) throws GameActionException {
        if (!symChecked) {
            symChecked = true;
            mapWidth = rc.getMapWidth();
            mapHeight = rc.getMapHeight();
        }

        MapLocation myLoc = rc.getLocation();
        int bestDistance = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.type.isTowerType()) continue;
            int distance = myLoc.distanceSquaredTo(ally.location);
            if (distance < bestDistance) {
                bestDistance = distance;
                spawnTower = ally.location;
            }
        }

        if (spawnTower != null) {
            MapLocation rotate180 = new MapLocation(mapWidth - 1 - spawnTower.x, mapHeight - 1 - spawnTower.y);
            MapLocation reflectX = new MapLocation(mapWidth - 1 - spawnTower.x, spawnTower.y);
            MapLocation reflectY = new MapLocation(spawnTower.x, mapHeight - 1 - spawnTower.y);
            int rotateDistance = spawnTower.distanceSquaredTo(rotate180);
            int reflectXDistance = spawnTower.distanceSquaredTo(reflectX);
            int reflectYDistance = spawnTower.distanceSquaredTo(reflectY);

            if (rotateDistance >= reflectXDistance && rotateDistance >= reflectYDistance) {
                mirrorTower = rotate180;
            } else if (reflectXDistance >= reflectYDistance) {
                mirrorTower = reflectX;
            } else {
                mirrorTower = reflectY;
            }
        }

        ExploreDirector.bootstrapStyle(rc);
        markVisitedSector(myLoc, rc.getRoundNum());
        ingestMessages(rc);
    }

    static void ingestMessages(RobotController rc) throws GameActionException {
        for (Message message : rc.readMessages(-1)) {
            int data = message.getBytes();
            int type = data & (3 << 30);
            MapLocation loc = new MapLocation((data >> 15) & 0x7FFF, data & 0x7FFF);
            if (type == MSG_RUIN) {
                hintedRuin = loc;
                hintedRuinRound = rc.getRoundNum();
            } else if (type == MSG_ENEMY) {
                hintedEnemy = loc;
                hintedEnemyRound = rc.getRoundNum();
                frontTarget = loc;
                frontTargetRound = rc.getRoundNum();
            }
        }
    }

    static void markVisitedSector(MapLocation loc, int round) {
        ExploreDirector.markSectorVisit(loc, round);
    }

    static int estimateTravelSteps(MapLocation from, MapLocation to) {
        if (from == null || to == null) return 999;
        int dx = Math.abs(from.x - to.x);
        int dy = Math.abs(from.y - to.y);
        return Math.max(dx, dy);
    }

    static boolean tryMoveTowardObs(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady() || target == null) {
            return false;
        }

        MapLocation myLoc = rc.getLocation();
        if (myLoc.equals(target)) {
            return false;
        }

        if (obsNavTarget == null || !obsNavTarget.equals(target)) {
            isObsFollowing = false;
            obsFollowTurns = 0;
            obsNavTarget = target;
            obsFollowStartDist = myLoc.distanceSquaredTo(target);
        }

        Direction toTarget = myLoc.directionTo(target);
        if (rc.canMove(toTarget)) {
            if (!isObsFollowing || myLoc.distanceSquaredTo(target) <= obsFollowStartDist || obsFollowTurns > 10) {
                rc.move(toTarget);
                isObsFollowing = false;
                obsFollowTurns = 0;
                obsFollowStartDist = rc.getLocation().distanceSquaredTo(target);
                obsWallDirection = toTarget;
                return true;
            }
        }

        if (!isObsFollowing) {
            isObsFollowing = true;
            obsFollowTurns = 0;
            obsFollowStartDist = myLoc.distanceSquaredTo(target);
            obsWallDirection = toTarget;
            obsFollowLeft = ((rc.getID() + turnCount) & 1) == 0;
        }

        obsFollowTurns++;
        Direction obs = obsWallDirection;
        for (int index = 0; index < 8; index++) {
            obs = obsFollowLeft ? obs.rotateLeft() : obs.rotateRight();
            if (rc.canMove(obs)) {
                rc.move(obs);
                obsWallDirection = obs;
                if (rc.canMove(rc.getLocation().directionTo(target))
                        && rc.getLocation().distanceSquaredTo(target) < obsFollowStartDist) {
                    isObsFollowing = false;
                    obsFollowTurns = 0;
                }
                return true;
            }
        }

        obsFollowLeft = !obsFollowLeft;
        obs = obsWallDirection;
        for (int index = 0; index < 8; index++) {
            obs = obsFollowLeft ? obs.rotateLeft() : obs.rotateRight();
            if (rc.canMove(obs)) {
                rc.move(obs);
                obsWallDirection = obs;
                return true;
            }
        }
        return false;
    }

    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        tryMoveTowardObs(rc, target);
    }

    static MapLocation nearestAllyTowerLoc(RobotController rc) throws GameActionException {
        MapLocation best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.type.isTowerType()) continue;
            int distance = rc.getLocation().distanceSquaredTo(ally.location);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = ally.location;
            }
        }
        if (best != null) {
            spawnTower = best;
            return best;
        }
        return spawnTower;
    }

    static int evalTileGain(RobotController rc, MapLocation loc) throws GameActionException {
        int score = 0;
        for (MapInfo tile : rc.senseNearbyMapInfos(loc, 4)) {
            if (!tile.isPassable()) continue;
            if (tile.hasRuin()) {
                score += 8;
                continue;
            }
            PaintType paint = tile.getPaint();
            if (isEnemyPaint(paint)) {
                score += 8;
            } else if (paint == PaintType.EMPTY) {
                score += 6;
            }
        }
        return score;
    }

    static int localClaimValue(RobotController rc, MapLocation loc, int radiusSq) throws GameActionException {
        int score = 0;
        for (MapInfo tile : rc.senseNearbyMapInfos(loc, radiusSq)) {
            if (!tile.isPassable()) continue;
            PaintType paint = tile.getPaint();
            if (isEnemyPaint(paint)) {
                score += 3;
            } else if (paint == PaintType.EMPTY) {
                score += 2;
            }
        }
        return score;
    }

    static int edgeSectorBias(MapLocation loc) {
        return ExploreDirector.edgeBias(loc);
    }

    static int edgeProgressBias(MapLocation current, MapLocation next, MapLocation target) {
        return ExploreDirector.progressBias(current, next, target);
    }

    static void updateExploreTarget(RobotController rc, MapLocation myLoc) throws GameActionException {
        exploreTarget = ExploreDirector.chooseExploreObjective(rc, myLoc);
    }

    static MapLocation bestSectorAnchor(MapLocation myLoc, int roundNum) {
        return ExploreDirector.bestSectorAnchor(myLoc, roundNum);
    }

    static void refreshExploreTarget(RobotController rc, MapLocation myLoc) throws GameActionException {
        ExploreDirector.maybeRefreshTarget(rc, myLoc);
    }

    static MapLocation sectorCenter(int sx, int sy) {
        return new MapLocation(
                Math.min(mapWidth - 1, sx * SECTOR_SIZE + SECTOR_SIZE / 2),
                Math.min(mapHeight - 1, sy * SECTOR_SIZE + SECTOR_SIZE / 2));
    }

    static void assignSector(RobotController rc) {
        if (mapWidth <= 0 || mySecX >= 0) return;
        int sectorWidth = Math.max(1, Math.min(MAX_SECTORS, (mapWidth + SECTOR_SIZE - 1) / SECTOR_SIZE));
        int sectorHeight = Math.max(1, Math.min(MAX_SECTORS, (mapHeight + SECTOR_SIZE - 1) / SECTOR_SIZE));
        mySecX = (rc.getID() * 7) % sectorWidth;
        mySecY = (rc.getID() * 13) % sectorHeight;
    }

    static void rotateSector() {
        if (mapWidth <= 0) return;
        int sectorWidth = Math.max(1, Math.min(MAX_SECTORS, (mapWidth + SECTOR_SIZE - 1) / SECTOR_SIZE));
        int sectorHeight = Math.max(1, Math.min(MAX_SECTORS, (mapHeight + SECTOR_SIZE - 1) / SECTOR_SIZE));
        mySecX++;
        if (mySecX >= sectorWidth) {
            mySecX = 0;
            mySecY = (mySecY + 1) % sectorHeight;
        }
    }

    static int mapArea() {
        return Math.max(1, mapWidth * mapHeight);
    }

    static boolean isHugeMap() {
        return mapArea() >= 2600;
    }

    static int macroPhase() {
        if (turnCount < 120) return 0;
        if (turnCount < 500) return 1;
        return 2;
    }

    static void refreshFrontTarget(RobotController rc, RobotInfo[] enemies) {
        int round = rc.getRoundNum();
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;
        if (enemies != null) {
            for (RobotInfo enemy : enemies) {
                int score = enemy.type.isTowerType() ? 140
                        : (enemy.type == UnitType.SOLDIER ? 95
                        : (enemy.type == UnitType.SPLASHER ? 72 : 54));
                score += Math.max(0, 110 - enemy.health);
                for (RobotInfo other : enemies) {
                    int dist = enemy.location.distanceSquaredTo(other.location);
                    if (dist <= 8) score += 16;
                    else if (dist <= 20) score += 8;
                }
                if (mirrorTower != null) {
                    score += Math.max(0, 120 - enemy.location.distanceSquaredTo(mirrorTower)) / 6;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = enemy.location;
                }
            }
        }
        if (best != null) {
            frontTarget = best;
            frontTargetRound = round;
            return;
        }
        if (hintedEnemy != null && round - hintedEnemyRound <= 18) {
            if (frontTarget == null || round - frontTargetRound > 6 || frontTarget.distanceSquaredTo(hintedEnemy) > 36) {
                frontTarget = hintedEnemy;
            }
            frontTargetRound = round;
            return;
        }
        if (!warFrontActive(round)) {
            frontTarget = null;
        }
    }

    static boolean warFrontActive(int roundNum) {
        int ttl = macroPhase() == 2 ? 26 : 18;
        return frontTarget != null && roundNum - frontTargetRound <= ttl;
    }

    static MapLocation currentFrontTarget(int roundNum) {
        return warFrontActive(roundNum) ? frontTarget : null;
    }

    static int frontPushBias(MapLocation current, MapLocation next, MapLocation target) {
        if (current == null || next == null || target == null) return 0;
        int nowDistance = current.distanceSquaredTo(target);
        int nextDistance = next.distanceSquaredTo(target);
        int score = 0;
        if (nextDistance < nowDistance) score += 24;
        else if (nextDistance > nowDistance) score -= 12;
        if (nowDistance > 9 && nextDistance <= 9) score += 14;
        if (nextDistance <= 16) score += 6;
        score += edgeProgressBias(current, next, target) / 2;
        return score;
    }

    static boolean freshHint(int hintRound, int ttl) {
        return turnCount - hintRound <= ttl;
    }

    static int recentVisitPenalty(MapLocation loc) {
        int count = 0;
        for (MapLocation recent : recentLocs) {
            if (recent != null && recent.equals(loc)) {
                count++;
            }
        }
        if (count == 0) return 0;
        if (count == 1) return 12;
        if (count == 2) return 28;
        if (count == 3) return 48;
        return 60;
    }
}
