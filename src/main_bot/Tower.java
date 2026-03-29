package main_bot;

import battlecode.common.*;

public class Tower {

    static int lastEnemySeenRound = -1000;

    public static void run(RobotController rc) throws GameActionException {
        if (!RobotPlayer.symInit) {
            RobotPlayer.initSymmetry(rc);
            RobotPlayer.symInit = true;
        }

        boolean     paintLow  = false;
        boolean     enemyNear = false;
        MapLocation ruinMsg   = null;
        MapLocation bleedInfo = null;
        boolean     srpNeeded = false;

        if (RobotPlayer.turnCount % 10 == 0) {
            if (RobotPlayer.coverageReportCount > 0)
                RobotPlayer.globalCoverage = RobotPlayer.coverageReportSum / RobotPlayer.coverageReportCount;
            RobotPlayer.coverageReportCount = 0;
            RobotPlayer.coverageReportSum   = 0f;
        }

        for (Message m : rc.readMessages(-1)) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            int data = m.getBytes();
            int type = data & RobotPlayer.MSG_TYPE_MASK;
            if (type == RobotPlayer.MSG_PAINTLOW) {
                paintLow = true;
            } else if (type == RobotPlayer.MSG_ENEMY) {
                enemyNear = true;
                lastEnemySeenRound = rc.getRoundNum();
                RobotPlayer.refineSymmetry(new MapLocation((data>>15)&0x7FFF, data&0x7FFF));
            } else if (type == RobotPlayer.MSG_RUIN) {
                ruinMsg = new MapLocation((data>>15)&0x7FFF, data&0x7FFF);
            } else {
                if ((data & RobotPlayer.MSG_SECTOR_FLAG) != 0) {
                    int sid = (data >> 20) & 0x7F;
                    if (sid >= 0 && sid < RobotPlayer.maxSectors)
                        RobotPlayer.setSectorBit(RobotPlayer.towerExhSectors, sid);
                } else if ((data & RobotPlayer.MSG_BLEED_FLAG) != 0) {
                    bleedInfo = new MapLocation((data>>15)&0x3FFF, data&0x7FFF);
                } else if ((data & RobotPlayer.MSG_RUIN_CLAIMED) != 0) {
                    RobotPlayer.addClaimedRuin(new MapLocation((data>>14)&0x3FFF, data&0x3FFF));
                } else if ((data & RobotPlayer.MSG_COVERAGE) != 0) {
                    int covInt = data & 0x3FFF;
                    RobotPlayer.coverageReportSum += covInt / 1000f;
                    RobotPlayer.coverageReportCount++;
                }
            }
            if (type == RobotPlayer.MSG_ENEMY || type == RobotPlayer.MSG_RUIN
                    || type == RobotPlayer.MSG_PAINTLOW) {
                broadcastTower(rc, data);
            }
        }

        if (rc.getRoundNum() % 10 == 0 && RobotPlayer.globalCoverage > 0) {
            broadcastTower(rc, RobotPlayer.MSG_COVERAGE | (int)(RobotPlayer.globalCoverage * 1000));
        }

        int round = rc.getRoundNum();
        srpNeeded = round > 60 && round % 50 == 0
                 && round - RobotPlayer.srpSquadLastRound >= 50;

        towerSpawn(rc, paintLow, enemyNear, ruinMsg, bleedInfo, srpNeeded);

        towerSingleAttack(rc);
        towerAoeAttack(rc);

        tryUpgradeSelf(rc);

        tryDisintegrateDefense(rc);
    }

    static void tryUpgradeSelf(RobotController rc) throws GameActionException {
        if (!rc.canUpgradeTower(rc.getLocation())) return;
        int chips = rc.getChips();
        UnitType t = rc.getType();
        int reserve = 500;
        if      (RobotPlayer.isMoneyTower(t)   && chips >= 3500 + reserve) rc.upgradeTower(rc.getLocation());
        else if (RobotPlayer.isPaintTower(t)   && chips >= 4000 + reserve) rc.upgradeTower(rc.getLocation());
        else if (RobotPlayer.isDefenseTower(t) && chips >= 3500 + reserve) rc.upgradeTower(rc.getLocation());
    }

    static void towerSingleAttack(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (RobotPlayer.nearbyEnemies.length == 0) return;
        int atk = rc.getType().attackStrength;
        RobotInfo best = null; int bs = Integer.MIN_VALUE;
        for (RobotInfo e : RobotPlayer.nearbyEnemies) {
            if (!rc.canAttack(e.location)) continue;
            int score = e.health <= atk ? (10000 - e.health) : (1000 - e.health);
            if (e.type == UnitType.SOLDIER) score += 50;
            if (score > bs) { bs = score; best = e; }
        }
        if (best != null && rc.canAttack(best.location)) rc.attack(best.location);
    }

    static void towerAoeAttack(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (RobotPlayer.nearbyEnemies.length == 0) return;
        if (rc.canAttack(null)) { rc.attack(null); return; }
        MapLocation best = null; int bestCnt = 0;
        for (RobotInfo e : RobotPlayer.nearbyEnemies) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            if (!rc.canAttack(e.location)) continue;
            int cnt = 0;
            for (RobotInfo e2 : RobotPlayer.nearbyEnemies)
                if (e.location.distanceSquaredTo(e2.location) <= 9) cnt++;
            if (cnt > bestCnt) { bestCnt = cnt; best = e.location; }
        }
        if (best != null) rc.attack(best);
    }

    static void broadcastTower(RobotController rc, int data) throws GameActionException {
        for (RobotInfo ally : RobotPlayer.nearbyAllies) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            if (!ally.type.isTowerType()) continue;
            if (rc.getLocation().distanceSquaredTo(ally.location) > 80) continue;
            if (rc.canSendMessage(ally.location, data)) rc.sendMessage(ally.location, data);
        }
    }

    static void tryDisintegrateDefense(RobotController rc) throws GameActionException {
        if (!RobotPlayer.isDefenseTower(rc.getType())) return;
        if (RobotPlayer.nearbyEnemies.length > 0) { lastEnemySeenRound = rc.getRoundNum(); return; }
        for (MapInfo tile : RobotPlayer.nearbyTiles)
            if (RobotPlayer.isEnemyPaint(tile.getPaint())) { lastEnemySeenRound = rc.getRoundNum(); return; }
        if (rc.getRoundNum() - lastEnemySeenRound > 30) rc.disintegrate();
    }

    static void towerSpawn(RobotController rc, boolean paintLow, boolean enemyNear,
                            MapLocation ruinMsg, MapLocation bleedInfo,
                            boolean srpNeeded) throws GameActionException {
        if (!rc.isActionReady()) return;
        int round = rc.getRoundNum();

        int solCount = 0, mopCount = 0, splashCount = 0, lowPaintSol = 0;
        int enemyTiles = 0, emptyTiles = 0;
        boolean hasDefenseTower = false;

        for (RobotInfo ally : RobotPlayer.nearbyAllies) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            if (ally.type == UnitType.SOLDIER) {
                solCount++;
                if ((int)(100.0*ally.paintAmount/ally.type.paintCapacity) < 40) lowPaintSol++;
            } else if (ally.type == UnitType.MOPPER)   mopCount++;
            else if (ally.type == UnitType.SPLASHER)   splashCount++;
            if (RobotPlayer.isDefenseTower(ally.type)) hasDefenseTower = true;
        }
        for (MapInfo tile : RobotPlayer.nearbyTiles) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            PaintType p = tile.getPaint();
            if (RobotPlayer.isEnemyPaint(p))                    enemyTiles++;
            else if (p == PaintType.EMPTY && tile.isPassable()) emptyTiles++;
        }

        int nearbyRobots = solCount + mopCount + splashCount;

        boolean tooManyRobots = round > 150 && nearbyRobots >= 6;
        boolean chipsLow = rc.getChips() < 500;
        if (tooManyRobots && chipsLow && round % 3 != 0) return;

        boolean needBuilder = ruinMsg != null;
        MapLocation nearestUnbuiltRuin = null;
        int nearestRuinDist = Integer.MAX_VALUE;
        for (MapInfo tile : RobotPlayer.nearbyTiles) {
            if (!tile.hasRuin()) continue;
            try {
                RobotInfo at = rc.senseRobotAtLocation(tile.getMapLocation());
                if (at != null && at.type.isTowerType()) continue;
            } catch (GameActionException e) { continue; }
            int d = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
            if (d < nearestRuinDist) { nearestRuinDist = d; nearestUnbuiltRuin = tile.getMapLocation(); }
        }
        if (nearestUnbuiltRuin != null) {
            needBuilder = true;
            if (ruinMsg == null) ruinMsg = nearestUnbuiltRuin;
        }

        int buildersNearRuin = 0;
        if (ruinMsg != null) {
            for (RobotInfo ally : RobotPlayer.nearbyAllies)
                if (ally.type == UnitType.SOLDIER && ally.location.distanceSquaredTo(ruinMsg) <= 8)
                    buildersNearRuin++;
        }
        if (buildersNearRuin >= 2) needBuilder = false;

        if (RobotPlayer.towerLocalSpawned < 3) {
            if (spawnWithRole(rc, UnitType.SOLDIER, RobotPlayer.ROLE_BUILDER, ruinMsg)) {
                RobotPlayer.towerLocalSpawned++;
                RobotPlayer.towerSpawnCount++;
            }
            return;
        }
        if (round < 50) {
            if (spawnWithRole(rc, UnitType.SOLDIER, RobotPlayer.ROLE_BUILDER, ruinMsg)) {
                RobotPlayer.towerLocalSpawned++;
                RobotPlayer.towerSpawnCount++;
            }
            return;
        }

        if (srpNeeded) {
            if (spawnWithRole(rc, UnitType.SOLDIER, RobotPlayer.ROLE_PAINTER, null)) {
                RobotPlayer.towerLocalSpawned++;
                RobotPlayer.towerSpawnCount++;
                RobotPlayer.srpSquadLastRound = round;
                spawnWithRole(rc, UnitType.SOLDIER, RobotPlayer.ROLE_PAINTER, null);
                RobotPlayer.towerSpawnCount++;
            }
            return;
        }

        if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG) {
            if (needBuilder && RobotPlayer.towerBuilderCount < 2) {
                if (spawnWithRole(rc, UnitType.SOLDIER, RobotPlayer.ROLE_BUILDER, ruinMsg)) {
                    RobotPlayer.towerSpawnCount++; RobotPlayer.towerBuilderCount++;
                    RobotPlayer.towerLocalSpawned++;
                }
                return;
            }
            UnitType t = solCount * 2 < splashCount + mopCount ? UnitType.SOLDIER
                       : splashCount <= mopCount ? UnitType.SPLASHER : UnitType.MOPPER;
            if (spawnWithRole(rc, t, RobotPlayer.ROLE_ATTACKER, null)) {
                RobotPlayer.towerSpawnCount++; RobotPlayer.towerLocalSpawned++;
            }
            return;
        }

        double targetSol, targetSplash, targetMop;
        if (RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER) {
            targetSol = 0.30; targetSplash = 0.45; targetMop = 0.25;
        } else if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BORDER) {
            targetSol = 0.35; targetSplash = 0.40; targetMop = 0.25;
        } else {
            if (round < 100) {
                targetSol = 0.60; targetSplash = 0.25; targetMop = 0.15;
            } else if (round < 400) {
                double t = (round - 100.0) / 300.0;
                targetSol = 0.60-t*0.30; targetSplash = 0.25+t*0.25; targetMop = 0.15+t*0.05;
            } else {
                targetSol = 0.30; targetSplash = 0.50; targetMop = 0.20;
            }
        }

        int total = Math.max(1, nearbyRobots);
        double sSoldier  = RobotPlayer.dblSoldiers  + targetSol    - (double)solCount/total;
        double sSplasher = RobotPlayer.dblSplashers + targetSplash - (double)splashCount/total;
        double sMopper   = RobotPlayer.dblMoppers   + targetMop    - (double)mopCount/total;

        if (needBuilder)       sSoldier  += 0.7;
        if (enemyNear)       { sSoldier  += 0.2; sMopper += 0.15; }
        if (paintLow)          sMopper   += 0.5;
        if (lowPaintSol >= 2)  sMopper   += 0.35;
        if (bleedInfo != null) sMopper   += 0.45;
        if (emptyTiles > 15)   sSplasher += 0.25;
        if (RobotPlayer.mapArea < 900) sSoldier += 0.2;
        if (splashCount >= 5)  sSplasher -= 0.40;
        if (mopCount >= 4)     sMopper   -= 0.30;
        if (solCount >= 8)     sSoldier  -= 0.25;
        if (hasDefenseTower) { sSoldier += 0.15; sMopper += 0.10; }

        int soldierRole;
        if (needBuilder)                                     soldierRole = RobotPlayer.ROLE_BUILDER;
        else if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BORDER
              || RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER)
                                                             soldierRole = RobotPlayer.ROLE_ATTACKER;
        else if (emptyTiles > 10)                            soldierRole = RobotPlayer.ROLE_PAINTER;
        else                                                 soldierRole = RobotPlayer.ROLE_EXPLORER;

        UnitType toSpawn;
        if (sSoldier >= sSplasher && sSoldier >= sMopper) toSpawn = UnitType.SOLDIER;
        else if (sSplasher >= sMopper)                     toSpawn = UnitType.SPLASHER;
        else                                               toSpawn = UnitType.MOPPER;

        boolean spawned = false;
        if (toSpawn == UnitType.SOLDIER)       spawned = spawnWithRole(rc, UnitType.SOLDIER, soldierRole, ruinMsg);
        else if (toSpawn == UnitType.SPLASHER) spawned = spawnWithRole(rc, UnitType.SPLASHER, RobotPlayer.ROLE_PAINTER, null);
        else                                   spawned = spawnWithRole(rc, UnitType.MOPPER, RobotPlayer.ROLE_ATTACKER, null);

        if (spawned) {
            RobotPlayer.towerSpawnCount++;
            RobotPlayer.towerLocalSpawned++;
            RobotPlayer.dblSoldiers  += targetSol;
            RobotPlayer.dblSplashers += targetSplash;
            RobotPlayer.dblMoppers   += targetMop;
        }
    }

    static boolean spawnWithRole(RobotController rc, UnitType type, int role,
                                  MapLocation ruinLoc) throws GameActionException {
        Direction roleDir = RobotPlayer.ROLE_DIRS[role];
        MapLocation roleLoc = rc.getLocation().add(roleDir);
        if (rc.canBuildRobot(type, roleLoc)) {
            rc.buildRobot(type, roleLoc); return true;
        }

        Direction bestDir = null; int bestScore = Integer.MIN_VALUE;
        for (Direction dir : RobotPlayer.DIRS) {
            MapLocation loc = rc.getLocation().add(dir);
            if (!rc.canBuildRobot(type, loc)) continue;
            int score = evalSpawnLoc(rc, loc, type, ruinLoc);
            if (dir == roleDir) score += 30;
            else if (dir == roleDir.rotateLeft() || dir == roleDir.rotateRight()) score += 10;
            if (score > bestScore) { bestScore = score; bestDir = dir; }
        }
        if (bestDir != null) { rc.buildRobot(type, rc.getLocation().add(bestDir)); return true; }
        return false;
    }

    static int evalSpawnLoc(RobotController rc, MapLocation loc, UnitType unit,
                             MapLocation ruinLoc) throws GameActionException {
        int score = 0;
        PaintType p = rc.senseMapInfo(loc).getPaint();
        if (p.isAlly())                score += 2;
        else if (p == PaintType.EMPTY) score -= 1;
        else score -= unit == UnitType.MOPPER ? 4 : unit == UnitType.SPLASHER ? 3 : 2;

        if (unit == UnitType.SOLDIER && ruinLoc != null)
            score -= loc.distanceSquaredTo(ruinLoc) / 8;

        for (RobotInfo ally : RobotPlayer.nearbyAllies) {
            if (!ally.type.isTowerType()) continue;
            if (ally.location.equals(rc.getLocation())) continue;

            Direction awayFromTower = ally.location.directionTo(rc.getLocation());
            Direction toSpawn = rc.getLocation().directionTo(loc);
            if (toSpawn == awayFromTower || toSpawn == awayFromTower.rotateLeft()
                    || toSpawn == awayFromTower.rotateRight()) score += 8;
        }

        if (RobotPlayer.spawnTowerLoc != null)
            score += loc.distanceSquaredTo(RobotPlayer.spawnTowerLoc) / 8;
        int secId = RobotPlayer.getSectorId(loc);
        if (secId >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.towerExhSectors, secId))
            score -= 20;
        score -= (unit == UnitType.MOPPER ? 6 : 3) *
                  rc.senseNearbyRobots(loc, 20, rc.getTeam().opponent()).length;
        return score;
    }
}