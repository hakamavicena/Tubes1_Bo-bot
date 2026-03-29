package main_bot;

import battlecode.common.*;

public class Splasher {

    public static void run(RobotController rc) throws GameActionException {
        if (!RobotPlayer.symInit) {
            RobotPlayer.initSymmetry(rc);
            RobotPlayer.symInit = true;
        }

        RobotPlayer.updateCoverageAndPhase(rc);

        int paintPct = (int)(100.0 * rc.getPaint() / rc.getType().paintCapacity);

        if (paintPct < 30) {
            splasherRetreat(rc); return;
        }

        int minGain = RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG ? 1
                    : (paintPct < 60 || RobotPlayer.nearbyEnemies.length > 0) ? 2 : 1;

        boolean attacked = false;
        if (rc.isActionReady()) {
            MapLocation best = findBestSplashCenter(rc, RobotPlayer.myLoc, minGain);
            if (best != null) { rc.attack(best); attacked = true; }
        }

        if (rc.isMovementReady()) greedyMoveSplasher(rc);

        if (!attacked && rc.isActionReady()) {
            MapLocation best = findBestSplashCenter(rc, rc.getLocation(), minGain);
            if (best != null) rc.attack(best);
        }

        RobotPlayer.paintUnderSelf(rc);
    }

    static MapLocation findBestSplashCenter(RobotController rc, MapLocation myLoc,
                                             int minGain) throws GameActionException {
        MapLocation best = null; int bg = minGain - 1;
        for (MapInfo c : rc.senseNearbyMapInfos(myLoc, 4)) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            if (!rc.canAttack(c.getMapLocation())) continue;
            int gain = calcSplashGain(rc, c.getMapLocation());
            for (RobotInfo e : RobotPlayer.nearbyEnemies)
                if (c.getMapLocation().distanceSquaredTo(e.location) <= 8) gain += 5;
            if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG
                    || RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER)
                gain = (int)(gain * 1.5);
            if (gain > bg) { bg = gain; best = c.getMapLocation(); }
        }
        return best;
    }

    static void greedyMoveSplasher(RobotController rc) throws GameActionException {
        Direction bd = null; int bsc = Integer.MIN_VALUE;

        MapLocation et = RobotPlayer.confirmedEnemy != null
            ? RobotPlayer.confirmedEnemy : RobotPlayer.mirrorLoc;

        for (Direction dir : RobotPlayer.DIRS) {
            if (!rc.canMove(dir)) continue;
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;

            MapLocation next = RobotPlayer.myLoc.add(dir);
            int score = 0;

            boolean nearEdge = next.x <= 2 || next.x >= RobotPlayer.mapW - 3
                             || next.y <= 2 || next.y >= RobotPlayer.mapH - 3;
            boolean atCorner  = (next.x <= 2 || next.x >= RobotPlayer.mapW - 3)
                              && (next.y <= 2 || next.y >= RobotPlayer.mapH - 3);
            if (atCorner) score -= 60;
            else if (nearEdge) score -= 30;

            try {
                PaintType np = rc.senseMapInfo(next).getPaint();
                if (RobotPlayer.isEnemyPaint(np))
                    score += (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG ? 25 : 12);
                else if (np == PaintType.EMPTY) score += 8;
                else score -= 3;
            } catch (GameActionException e) {}

            int mg = 0;
            for (MapInfo c : rc.senseNearbyMapInfos(next, 4)) {
                if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
                if (next.distanceSquaredTo(c.getMapLocation()) > 4) continue;
                int g = calcSplashGain(rc, c.getMapLocation());
                if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG) g = (int)(g * 1.5);
                if (g > mg) mg = g;
            }
            score += mg * 3;

            int en = 0;
            for (MapInfo tile : RobotPlayer.nearbyTiles)
                if (tile.isPassable() && tile.getPaint() == PaintType.EMPTY
                    && next.distanceSquaredTo(tile.getMapLocation()) <= 9) en++;
            score += en / (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG ? 3 : 2);

            for (RobotInfo e : RobotPlayer.nearbyEnemies)
                if (next.distanceSquaredTo(e.location)
                    < RobotPlayer.myLoc.distanceSquaredTo(e.location))
                    score += rc.getPaint() > 100
                        ? (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG ? 20 : 10)
                        : 5;

            if (et != null) {
                int nowDist = RobotPlayer.myLoc.distanceSquaredTo(et);
                int nextDist = next.distanceSquaredTo(et);
                if (nextDist < nowDist)
                    score += (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG ? 25
                            : RobotPlayer.gamePhase == RobotPlayer.PHASE_BORDER ? 15 : 10);
                else if (nextDist > nowDist)
                    score -= 5;
            } else if (RobotPlayer.spawnTowerLoc != null) {

                int nowDist = RobotPlayer.myLoc.distanceSquaredTo(RobotPlayer.spawnTowerLoc);
                int nextDist = next.distanceSquaredTo(RobotPlayer.spawnTowerLoc);
                int maxDist = (RobotPlayer.mapW * RobotPlayer.mapW + RobotPlayer.mapH * RobotPlayer.mapH) / 4;
                if (nextDist > nowDist && nowDist < maxDist)
                    score += 6;
            }

            int secId = RobotPlayer.getSectorId(next);
            if (secId >= 0 && !RobotPlayer.isSectorBitSet(RobotPlayer.visitedSectors, secId)) score += 20;
            if (secId >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.exhaustedSectors, secId)) score -= 30;
            if (secId >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.mirrorPrioritySec, secId)) score += 25;

            score -= RobotPlayer.recentVisitPenalty(next) / 3;
            score -= rc.senseNearbyRobots(next, 2, rc.getTeam()).length * 4;
            try { score -= RobotPlayer.calcAdjacencyPenalty(rc, next); }
            catch (GameActionException e) {}
            score += RobotPlayer.rng.nextInt(3);

            if (score > bsc) { bsc = score; bd = dir; }
        }
        if (bd != null) { rc.move(bd); RobotPlayer.myLoc = rc.getLocation(); }
    }

    static int calcSplashGain(RobotController rc, MapLocation center)
            throws GameActionException {
        int gain = 0;
        for (MapInfo tile : rc.senseNearbyMapInfos(center, 4)) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            if (!tile.isPassable()) continue;
            PaintType p = tile.getPaint();
            int d = center.distanceSquaredTo(tile.getMapLocation());
            if (RobotPlayer.isEnemyPaint(p))   gain += d <= 2 ? 4 : 2;
            else if (p == PaintType.EMPTY)      gain += d <= 1 ? 3 : 1;
        }
        return gain;
    }

    static void splasherRetreat(RobotController rc) throws GameActionException {
        RobotInfo towerNearby = RobotPlayer.findNearestTower(rc);
        if (towerNearby != null) {
            int dist = RobotPlayer.myLoc.distanceSquaredTo(towerNearby.location);
            if (dist <= 2 && rc.isActionReady()) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                if (needed > 0 && rc.canTransferPaint(towerNearby.location, -needed))
                    rc.transferPaint(towerNearby.location, -needed);
            }
            if (rc.isMovementReady() && dist > 2)
                RobotPlayer.moveToward(rc, towerNearby.location);
        } else {
            MapLocation knownTower = RobotPlayer.getNearestKnownTower(RobotPlayer.myLoc);
            if (knownTower != null && rc.isMovementReady()) {
                RobotPlayer.moveToward(rc, knownTower);
            } else {
                RobotPlayer.moveTowardAllyPaint(rc);
            }
        }
        RobotPlayer.paintUnderSelf(rc);
    }
}