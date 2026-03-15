package TerraFirma;

import battlecode.common.*;

final class ExploreDirector {

    static final int HUGE_REFRESH_TURNS = 6;
    static final int NORMAL_REFRESH_TURNS = 4;
    static final int HUGE_RETARGET_RADIUS = 6;
    static final int NORMAL_RETARGET_RADIUS = 10;
    static final int SECTOR_AGE_WEIGHT = 6;
    static final int FRONTIER_COUNT_WEIGHT = 3;
    static final int HUGE_FRONTIER_MARGIN = 22;
    static final int NORMAL_FRONTIER_MARGIN = 16;
    static final int EDGE_X_WEIGHT = 4;
    static final int EDGE_Y_WEIGHT = 2;
    static final int STYLE_WEIGHT_EARLY = 9;
    static final int STYLE_WEIGHT_MID = 5;
    static final int STYLE_WEIGHT_LATE = 3;

    private ExploreDirector() {}

    static void bootstrapStyle(RobotController rc) {
        if (RobotPlayer.exploreStyle >= 0) return;
        RobotPlayer.openingDirectionIndex = Math.floorMod(rc.getID(), 8);
        int mod = Math.floorMod(rc.getID(), 12);
        if (mod <= 1) {
            RobotPlayer.exploreStyle = RobotPlayer.EXPLORE_REAR_FIRST;
        } else if (mod <= 6) {
            RobotPlayer.exploreStyle = RobotPlayer.EXPLORE_LEFT_FIRST;
        } else {
            RobotPlayer.exploreStyle = RobotPlayer.EXPLORE_RIGHT_FIRST;
        }
    }

    static void markSectorVisit(MapLocation loc, int round) {
        if (RobotPlayer.mapWidth <= 0 || loc == null) return;
        int sx = Math.max(0, Math.min(RobotPlayer.MAX_SECTORS - 1, loc.x / RobotPlayer.SECTOR_SIZE));
        int sy = Math.max(0, Math.min(RobotPlayer.MAX_SECTORS - 1, loc.y / RobotPlayer.SECTOR_SIZE));
        RobotPlayer.sectorTouched[sx][sy] = round;
    }

    static void maybeRefreshTarget(RobotController rc, MapLocation myLoc) throws GameActionException {
        RobotPlayer.targetTurn++;
        int mapArea = RobotPlayer.mapArea();
        int refreshTurns = mapArea >= 2600 ? HUGE_REFRESH_TURNS : NORMAL_REFRESH_TURNS;
        int retargetRadius = mapArea >= 2600 ? HUGE_RETARGET_RADIUS : NORMAL_RETARGET_RADIUS;
        if (RobotPlayer.targetTurn <= refreshTurns
                && RobotPlayer.exploreTarget != null
                && myLoc.distanceSquaredTo(RobotPlayer.exploreTarget) > retargetRadius) {
            return;
        }
        RobotPlayer.exploreTarget = chooseExploreObjective(rc, myLoc);
        RobotPlayer.targetTurn = 0;
    }

    static MapLocation chooseExploreObjective(RobotController rc, MapLocation myLoc) throws GameActionException {
        FrontierCandidate frontier = bestFrontierCandidate(rc, myLoc);

        RobotPlayer.assignSector(rc);
        MapLocation sectorTarget = bestSectorAnchor(myLoc, rc.getRoundNum());
        if (sectorTarget == null) {
            sectorTarget = RobotPlayer.sectorCenter(RobotPlayer.mySecX, RobotPlayer.mySecY);
        }
        if (sectorTarget != null && myLoc.distanceSquaredTo(sectorTarget) <= 25) {
            RobotPlayer.rotateSector();
            sectorTarget = bestSectorAnchor(myLoc, rc.getRoundNum());
        }
        if (RobotPlayer.spawnTower != null && sectorTarget != null
                && sectorTarget.distanceSquaredTo(RobotPlayer.spawnTower) < 36
                && RobotPlayer.mirrorTower != null) {
            sectorTarget = RobotPlayer.mirrorTower;
        }

        int sectorScore = scoreSectorAnchor(myLoc, sectorTarget, rc.getRoundNum());
        int frontierMargin = RobotPlayer.isHugeMap() ? HUGE_FRONTIER_MARGIN : NORMAL_FRONTIER_MARGIN;
        if (frontier.bestLoc != null && frontier.bestScore >= sectorScore + frontierMargin) {
            return frontier.bestLoc;
        }
        return sectorTarget;
    }

    static int edgeBias(MapLocation loc) {
        if (RobotPlayer.mapWidth <= 0 || RobotPlayer.mapHeight <= 0 || loc == null) return 0;
        int edgeX = Math.min(loc.x, RobotPlayer.mapWidth - 1 - loc.x);
        int edgeY = Math.min(loc.y, RobotPlayer.mapHeight - 1 - loc.y);
        int score = 0;
        score += Math.max(0, RobotPlayer.SECTOR_SIZE * 2 - edgeX) * EDGE_X_WEIGHT;
        score += Math.max(0, RobotPlayer.SECTOR_SIZE * 2 - edgeY) * EDGE_Y_WEIGHT;
        return score;
    }

    static int progressBias(MapLocation current, MapLocation next, MapLocation target) {
        if (target == null || current == null || next == null) return 0;
        int score = 0;
        int targetEdgeX = Math.min(target.x, RobotPlayer.mapWidth - 1 - target.x);
        int targetEdgeY = Math.min(target.y, RobotPlayer.mapHeight - 1 - target.y);
        if (targetEdgeX <= RobotPlayer.SECTOR_SIZE * 2) {
            int nowDx = Math.abs(current.x - target.x);
            int nextDx = Math.abs(next.x - target.x);
            if (nextDx < nowDx) score += 8;
            else if (nextDx > nowDx) score -= 4;
        }
        if (targetEdgeY <= RobotPlayer.SECTOR_SIZE * 2) {
            int nowDy = Math.abs(current.y - target.y);
            int nextDy = Math.abs(next.y - target.y);
            if (nextDy < nowDy) score += 6;
            else if (nextDy > nowDy) score -= 3;
        }
        return score;
    }

    static int scoreSectorAnchor(MapLocation myLoc, MapLocation center, int roundNum) {
        if (center == null) return Integer.MIN_VALUE;
        int sx = Math.max(0, Math.min(RobotPlayer.MAX_SECTORS - 1, center.x / RobotPlayer.SECTOR_SIZE));
        int sy = Math.max(0, Math.min(RobotPlayer.MAX_SECTORS - 1, center.y / RobotPlayer.SECTOR_SIZE));
        int age = roundNum - RobotPlayer.sectorTouched[sx][sy];
        int score = age * SECTOR_AGE_WEIGHT;
        score -= myLoc.distanceSquaredTo(center) / 10;
        if (RobotPlayer.spawnTower != null) {
            score += center.distanceSquaredTo(RobotPlayer.spawnTower) / 18;
        }
        if (RobotPlayer.mirrorTower != null) {
            score += Math.max(0, 160 - center.distanceSquaredTo(RobotPlayer.mirrorTower)) / 8;
        }
        score += styleBias(center, roundNum);
        score += openingSweepBias(center, roundNum);
        score += edgeBias(center);
        if (age >= 30) score += edgeBias(center) / 2;
        if (myLoc.distanceSquaredTo(center) <= 18) score -= 24;
        return score;
    }

    static MapLocation bestSectorAnchor(MapLocation myLoc, int roundNum) {
        if (RobotPlayer.mapWidth <= 0 || RobotPlayer.mapHeight <= 0) return RobotPlayer.mirrorTower;
        int sectorWidth = Math.min(RobotPlayer.MAX_SECTORS, (RobotPlayer.mapWidth + RobotPlayer.SECTOR_SIZE - 1) / RobotPlayer.SECTOR_SIZE);
        int sectorHeight = Math.min(RobotPlayer.MAX_SECTORS, (RobotPlayer.mapHeight + RobotPlayer.SECTOR_SIZE - 1) / RobotPlayer.SECTOR_SIZE);
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int sx = 0; sx < sectorWidth; sx++) {
            for (int sy = 0; sy < sectorHeight; sy++) {
                MapLocation center = RobotPlayer.sectorCenter(sx, sy);
                int score = scoreSectorAnchor(myLoc, center, roundNum);
                if (score > bestScore) {
                    bestScore = score;
                    best = center;
                }
            }
        }
        return best;
    }

    private static FrontierCandidate bestFrontierCandidate(RobotController rc, MapLocation myLoc) throws GameActionException {
        FrontierCandidate best = new FrontierCandidate();
        int frontierCount = 0;
        int bestScore = Integer.MIN_VALUE;
        MapLocation bestLoc = null;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.isPassable()) continue;
            PaintType paint = tile.getPaint();
            if (paint == PaintType.ALLY_PRIMARY || paint == PaintType.ALLY_SECONDARY) continue;
            frontierCount++;
            int score = RobotPlayer.evalTileGain(rc, tile.getMapLocation());
            score += RobotPlayer.localClaimValue(rc, tile.getMapLocation(), RobotPlayer.isHugeMap() ? 12 : 8);
            score += openingSweepBias(tile.getMapLocation(), rc.getRoundNum());
            score += edgeBias(tile.getMapLocation()) / 3;
            score -= myLoc.distanceSquaredTo(tile.getMapLocation()) / 3;
            if (score > bestScore) {
                bestScore = score;
                bestLoc = tile.getMapLocation();
            }
        }
        best.bestLoc = bestLoc;
        best.bestScore = bestLoc != null ? bestScore + Math.min(30, frontierCount * FRONTIER_COUNT_WEIGHT) : Integer.MIN_VALUE;
        return best;
    }

    private static int openingSweepBias(MapLocation loc, int roundNum) {
        if (loc == null || RobotPlayer.spawnTower == null || RobotPlayer.openingDirectionIndex < 0) return 0;
        if (roundNum >= RobotPlayer.OPENING_SWEEP_END_ROUND) return 0;

        Direction dir = RobotPlayer.directions[RobotPlayer.openingDirectionIndex];
        int rx = loc.x - RobotPlayer.spawnTower.x;
        int ry = loc.y - RobotPlayer.spawnTower.y;
        int forward = rx * dir.dx + ry * dir.dy;
        int lateral = Math.abs(rx * dir.dy - ry * dir.dx);
        int weight = roundNum < 120 ? 3 : (roundNum < 300 ? 2 : 1);

        int score = 0;
        score += Math.max(0, forward) * weight * 2;
        score -= lateral * weight;
        if (forward < 0) score += forward * weight * 2;
        return score / 2;
    }

    private static int styleBias(MapLocation center, int roundNum) {
        if (RobotPlayer.spawnTower == null || RobotPlayer.mirrorTower == null || RobotPlayer.exploreStyle < 0) return 0;
        int spawnSx = Math.max(0, Math.min(RobotPlayer.MAX_SECTORS - 1, RobotPlayer.spawnTower.x / RobotPlayer.SECTOR_SIZE));
        int spawnSy = Math.max(0, Math.min(RobotPlayer.MAX_SECTORS - 1, RobotPlayer.spawnTower.y / RobotPlayer.SECTOR_SIZE));
        int sx = Math.max(0, Math.min(RobotPlayer.MAX_SECTORS - 1, center.x / RobotPlayer.SECTOR_SIZE));
        int sy = Math.max(0, Math.min(RobotPlayer.MAX_SECTORS - 1, center.y / RobotPlayer.SECTOR_SIZE));
        int dx = sx - spawnSx;
        int dy = sy - spawnSy;

        int mx = RobotPlayer.mirrorTower.x - RobotPlayer.spawnTower.x;
        int my = RobotPlayer.mirrorTower.y - RobotPlayer.spawnTower.y;
        int front;
        int side;
        if (Math.abs(mx) >= Math.abs(my)) {
            int signX = Integer.signum(mx == 0 ? 1 : mx);
            front = dx * signX;
            side = -dy * signX;
        } else {
            int signY = Integer.signum(my == 0 ? 1 : my);
            front = dy * signY;
            side = dx * signY;
        }

        int weight = roundNum < 180 ? STYLE_WEIGHT_EARLY : (roundNum < 700 ? STYLE_WEIGHT_MID : STYLE_WEIGHT_LATE);
        if (RobotPlayer.exploreStyle == RobotPlayer.EXPLORE_REAR_FIRST) {
            return Math.max(0, 3 - front) * weight * 6 + Math.max(0, 2 - Math.abs(side)) * weight;
        }
        if (RobotPlayer.exploreStyle == RobotPlayer.EXPLORE_LEFT_FIRST) {
            return Math.max(0, side + 1) * weight * 5 + Math.max(0, front) * weight * 2;
        }
        return Math.max(0, -side + 1) * weight * 5 + Math.max(0, front) * weight * 2;
    }

    private static final class FrontierCandidate {
        MapLocation bestLoc;
        int bestScore;
    }
}
