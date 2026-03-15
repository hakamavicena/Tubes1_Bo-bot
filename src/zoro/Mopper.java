package zoro;

import battlecode.common.*;


public class Mopper {

    public static void run(RobotController rc) throws GameActionException {
        if (!RobotPlayer.symInit) {
            RobotPlayer.initSymmetry(rc);
            RobotPlayer.symInit = true;
        }

        RobotPlayer.updateCoverageAndPhase(rc);
        RobotPlayer.detectBleed(rc);

        int paintPct = (int)(100.0 * rc.getPaint() / rc.getType().paintCapacity);
        if (paintPct < 20) {
            RobotInfo tower = RobotPlayer.findNearestTower(rc);
            if (tower != null) {
                int dist = RobotPlayer.myLoc.distanceSquaredTo(tower.location);
                if (dist <= 2 && rc.isActionReady()) {
                    int needed = rc.getType().paintCapacity - rc.getPaint();
                    if (needed > 0 && rc.canTransferPaint(tower.location, -needed))
                        rc.transferPaint(tower.location, -needed);
                } else if (rc.isMovementReady()) {
                    RobotPlayer.moveToward(rc, tower.location);
                }
                return;
            }
            if (rc.isMovementReady()) RobotPlayer.moveTowardAllyPaint(rc);
            return;
        }

        MapInfo[] near2 = rc.senseNearbyMapInfos(RobotPlayer.myLoc, 2);

        if (rc.isActionReady()) {
            // --- Transfer ke soldier ---
            RobotInfo bestSol = null; int tfGain = 0;
            for (RobotInfo ally : RobotPlayer.nearbyAllies) {
                if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
                if (RobotPlayer.myLoc.distanceSquaredTo(ally.location) > 2) continue;
                int pct = (int)(100.0 * ally.paintAmount / ally.type.paintCapacity);
                if (pct >= 50) continue;
                int give = Math.min(rc.getPaint() - 10, ally.type.paintCapacity - ally.paintAmount);
                if (give <= 0) continue;
                int gain = (pct < 20 ? give * 2 : give)
                         + (ally.type == UnitType.SOLDIER ? 20 : 0);
                if (gain > tfGain) { tfGain = gain; bestSol = ally; }
            }

            // --- Swing ---
            Direction bestSwing = null; int swGain = 0;
            for (Direction dir : RobotPlayer.CARDINAL) {
                if (!rc.canMopSwing(dir)) continue;
                int gain = RobotPlayer.countSwingHits(rc, dir) * 30;
                // bonus jika ada ruin di dekat — bantu soldier build
                for (MapInfo t : near2) if (t.hasRuin()) gain += 15;
                if (gain > swGain) { swGain = gain; bestSwing = dir; }
            }

            // --- Mop tile ---
            MapLocation bestMop = null; int mopGain = 0;
            for (MapInfo tile : near2) {
                if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
                if (!RobotPlayer.isEnemyPaint(tile.getPaint())
                    || !rc.canAttack(tile.getMapLocation())) continue;
                int gain = 15;
                try {
                    RobotInfo on = rc.senseRobotAtLocation(tile.getMapLocation());
                    if (on != null && on.team != rc.getTeam()) gain += 20;
                } catch (GameActionException e) {}
                // bonus jika dekat ruin
                for (MapInfo near : RobotPlayer.nearbyTiles)
                    if (near.hasRuin()
                        && tile.getMapLocation().distanceSquaredTo(near.getMapLocation()) <= 8)
                        gain += 15;
                if (gain > mopGain) { mopGain = gain; bestMop = tile.getMapLocation(); }
            }

            // Boost mop gain di fase agresif
            if (RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER
                    || RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG)
                mopGain = (int)(mopGain * 1.5);

            // Pilih aksi dengan gain tertinggi
            if (swGain >= mopGain && swGain >= tfGain && bestSwing != null) {
                rc.mopSwing(bestSwing);
            } else if (mopGain >= tfGain && bestMop != null) {
                rc.attack(bestMop);
            } else if (tfGain > 0 && bestSol != null) {
                int give = Math.min(rc.getPaint() - 10,
                    bestSol.type.paintCapacity - bestSol.paintAmount);
                if (give > 0 && rc.canTransferPaint(bestSol.location, give))
                    rc.transferPaint(bestSol.location, give);
            }
        }

        
        greedyMoveMopper(rc);
    }

    static void greedyMoveMopper(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG
                || RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER) {
            if (RobotPlayer.nearbyEnemies.length > 0) {
                RobotInfo cl = null; int cd = Integer.MAX_VALUE;
                for (RobotInfo e : RobotPlayer.nearbyEnemies) {
                    int d = RobotPlayer.myLoc.distanceSquaredTo(e.location);
                    if (d < cd) { cd = d; cl = e; }
                }
                if (cl != null && cd > 2) {
                    RobotPlayer.moveToward(rc, cl.location); return;
                }
            }
            MapLocation et = RobotPlayer.confirmedEnemy != null
                ? RobotPlayer.confirmedEnemy : RobotPlayer.mirrorLoc;
            if (et != null) { RobotPlayer.moveToward(rc, et); return; }
        }

        // Respond bleed
        if (RobotPlayer.isBleedingNow && RobotPlayer.bleedLocation != null
                && rc.getPaint() > 20) {
            RobotPlayer.moveToward(rc, RobotPlayer.bleedLocation); return;
        }

        RobotInfo ls = null; int lp = 40;
        for (RobotInfo ally : RobotPlayer.nearbyAllies) {
            if (ally.type != UnitType.SOLDIER) continue;
            int pct = (int)(100.0 * ally.paintAmount / ally.type.paintCapacity);
            if (pct < lp && rc.getPaint() > 20) { lp = pct; ls = ally; }
        }
        if (ls != null) { RobotPlayer.moveToward(rc, ls.location); return; }

        Direction bd = null; int bsc = Integer.MIN_VALUE;
        for (Direction dir : RobotPlayer.DIRS) {
            if (!rc.canMove(dir)) continue;
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;

            MapLocation next = RobotPlayer.myLoc.add(dir);
            int score = 0;

            for (MapInfo tile : RobotPlayer.nearbyTiles) {
                if (!RobotPlayer.isEnemyPaint(tile.getPaint())) continue;
                int d = next.distanceSquaredTo(tile.getMapLocation());
                if (d <= 2)       score += (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG ? 50 : 25);
                else if (d <= 9)  score += 8;
            }

            for (MapInfo tile : RobotPlayer.nearbyTiles) {
                if (!tile.hasRuin()) continue;
                try {
                    RobotInfo at = rc.senseRobotAtLocation(tile.getMapLocation());
                    if (at == null || !at.type.isTowerType())
                        if (next.distanceSquaredTo(tile.getMapLocation()) <= 16) score += 20;
                } catch (GameActionException e) {}
            }

            // Paint tile tujuan
            try {
                PaintType np = rc.senseMapInfo(next).getPaint();
                if (RobotPlayer.isEnemyPaint(np))  score += 15;
                else if (np == PaintType.EMPTY)     score += 4;
            } catch (GameActionException e) {}

            // Bonus dekat soldier butuh refill
            for (RobotInfo ally : RobotPlayer.nearbyAllies) {
                if (ally.type != UnitType.SOLDIER) continue;
                int pct  = (int)(100.0 * ally.paintAmount / ally.type.paintCapacity);
                int give = Math.min(rc.getPaint() - 10,
                    ally.type.paintCapacity - ally.paintAmount);
                if (pct < 50 && next.distanceSquaredTo(ally.location) <= 2 && give > 0)
                    score += pct < 20 ? give * 3 : give;
            }

            // Bonus menjauhi spawn tower (ekspansi)
            if (RobotPlayer.spawnTowerLoc != null && RobotPlayer.nearbyEnemies.length == 0)
                if (next.distanceSquaredTo(RobotPlayer.spawnTowerLoc)
                    > RobotPlayer.myLoc.distanceSquaredTo(RobotPlayer.spawnTowerLoc)) score += 9;

            // Sektor bonus/penalti
            int secId = RobotPlayer.getSectorId(next);
            if (secId >= 0 && !RobotPlayer.isSectorBitSet(RobotPlayer.visitedSectors, secId)) score += 15;
            if (secId >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.exhaustedSectors, secId)) score -= 20;
            if (secId >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.mirrorPrioritySec, secId)) score += 15;

            // Anti-stuck + anti-bergerombol
            score -= RobotPlayer.recentVisitPenalty(next) / 2;
            score -= rc.senseNearbyRobots(next, 2, rc.getTeam()).length * 3;

            if (score > bsc) { bsc = score; bd = dir; }
        }
        if (bd != null) { rc.move(bd); RobotPlayer.myLoc = rc.getLocation(); }
    }
}
