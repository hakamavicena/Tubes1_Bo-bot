package zoro;

import battlecode.common.*;

public class Soldier {

    static boolean roleDetected = false;

    static int allyPaintTowerCount   = 0;
    static int allyMoneyTowerCount   = 0;
    static int allyDefenseTowerCount = 0;

    public static void run(RobotController rc) throws GameActionException {
        if (!RobotPlayer.symInit) {
            RobotPlayer.initSymmetry(rc);
            RobotPlayer.symInit = true;
        }

        if (!roleDetected && RobotPlayer.spawnTowerLoc != null) {
            detectMyRole(rc);
            roleDetected = true;
        }

        RobotPlayer.updateStuck();
        RobotPlayer.detectWallStuck(rc);
        RobotPlayer.updateSectorState(rc);
        RobotPlayer.updateCoverageAndPhase(rc);
        RobotPlayer.detectBleed(rc);
        RobotPlayer.updateAsymmetricCoverage();

        RobotPlayer.exploreTargetAge++;
        if (RobotPlayer.exploreTargetAge >= 4 || RobotPlayer.exploreTarget == null
                || RobotPlayer.myLoc.distanceSquaredTo(RobotPlayer.exploreTarget) <= 8) {
            RobotPlayer.updateExploreTarget(rc);
        }

        if (RobotPlayer.nearbyEnemies.length > 0)
            RobotPlayer.sendMsg(rc, RobotPlayer.MSG_ENEMY
                | (RobotPlayer.myLoc.x << 15) | RobotPlayer.myLoc.y);
        for (RobotInfo e : RobotPlayer.nearbyEnemies)
            if (e.type.isTowerType()) RobotPlayer.refineSymmetry(e.location);

        MapLocation enemyFrontier = RobotPlayer.findEnemyFrontier();
        if (enemyFrontier != null)
            RobotPlayer.sendMsg(rc, (enemyFrontier.x << 15) | enemyFrontier.y);

        if (RobotPlayer.isBleedingNow && RobotPlayer.bleedLocation != null)
            RobotPlayer.sendMsg(rc, RobotPlayer.MSG_BLEED_FLAG
                | (RobotPlayer.bleedLocation.x << 14) | RobotPlayer.bleedLocation.y);

        updateTowerCounts();

        RobotPlayer.tryUpgradeNearTower(rc);

        if (RobotPlayer.isSrpDuty) {
            int paintPct = (int)(100.0 * rc.getPaint() / rc.getType().paintCapacity);
            rc.setIndicatorString("SRP_DUTY p=" + paintPct + "%");
            runSrpDuty(rc, paintPct);
            RobotPlayer.paintUnderSelf(rc);
            return;
        }

        int paintPct = (int)(100.0 * rc.getPaint() / rc.getType().paintCapacity);

        int retreatScore    = calcRetreatScore(paintPct);
        int combatScore     = calcCombatScore(rc, paintPct);
        MapInfo bestRuin    = findBestRuin(rc);
        int buildTowerScore = calcBuildTowerScore(rc, bestRuin, paintPct);
        int messingUpScore  = calcMessingUpScore(rc, bestRuin);
        int expandScore     = calcExpandScore(rc);
        int srpScore        = calcSrpScore(rc);
        int blitzScore      = calcBlitzScore();
        int bleedScore      = RobotPlayer.isBleedingNow ? 60 : 0;

        switch (RobotPlayer.myRole) {
            case RobotPlayer.ROLE_BUILDER:
                buildTowerScore = (int)(buildTowerScore * 2.5);
                messingUpScore  = (int)(messingUpScore  * 1.5);
                blitzScore      = (int)(blitzScore      * 0.2);
                expandScore     = (int)(expandScore     * 0.4);
                break;
            case RobotPlayer.ROLE_EXPLORER:
                expandScore     = (int)(expandScore     * 1.5);
                buildTowerScore = (int)(buildTowerScore * 1.3);
                break;
            case RobotPlayer.ROLE_PAINTER:
                expandScore     = (int)(expandScore     * 1.8);
                combatScore     = (int)(combatScore     * 0.7);
                break;
            case RobotPlayer.ROLE_ATTACKER:
                combatScore     = (int)(combatScore     * 2.0);
                blitzScore      = (int)(blitzScore      * 1.5);
                expandScore     = (int)(expandScore     * 0.6);
                break;
        }

        if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BORDER) {
            combatScore    = (int)(combatScore    * 1.5);
            messingUpScore = (int)(messingUpScore * 1.8);
            int fc = 0;
            for (RobotInfo a : RobotPlayer.nearbyAllies)
                if (a.type == UnitType.SOLDIER || a.type == UnitType.MOPPER) fc++;
            if (fc >= 3 && RobotPlayer.myRole == RobotPlayer.ROLE_PAINTER) expandScore += 25;
        }
        if (RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER) {
            combatScore    = (int)(combatScore    * 2.0);
            messingUpScore = (int)(messingUpScore * 2.5);
            expandScore    = Math.max(0, expandScore - 30);
        }
        if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG) {
            if (RobotPlayer.myRole != RobotPlayer.ROLE_BUILDER) {
                blitzScore  = 200;
                expandScore = 0;
                srpScore    = 0;
            }
        }

        int maxScore = retreatScore; int bestAct = RobotPlayer.ACT_RETREAT;
        if (combatScore     > maxScore) { maxScore = combatScore;     bestAct = RobotPlayer.ACT_COMBAT; }
        if (buildTowerScore > maxScore) { maxScore = buildTowerScore; bestAct = RobotPlayer.ACT_BUILD_TOWER; }
        if (messingUpScore  > maxScore) { maxScore = messingUpScore;  bestAct = RobotPlayer.ACT_MESSING_UP; }
        if (bleedScore      > maxScore) { maxScore = bleedScore;      bestAct = RobotPlayer.ACT_BLEED_RESPOND; }
        if (expandScore     > maxScore) { maxScore = expandScore;     bestAct = RobotPlayer.ACT_FRONTIER_EXPAND; }
        if (srpScore        > maxScore) { maxScore = srpScore;        bestAct = RobotPlayer.ACT_BUILD_SRP; }
        if (blitzScore      > maxScore) { maxScore = blitzScore;      bestAct = RobotPlayer.ACT_BLITZKRIEG; }

        if (bestAct != RobotPlayer.curAct) { RobotPlayer.curAct = bestAct; RobotPlayer.actTimer = 0; }
        else RobotPlayer.actTimer++;

        rc.setIndicatorString(RobotPlayer.actName(RobotPlayer.curAct)
            + "|" + RobotPlayer.roleName(RobotPlayer.myRole)
            + "|" + RobotPlayer.phaseName(RobotPlayer.gamePhase)
            + "|p" + paintPct);

        switch (RobotPlayer.curAct) {
            case RobotPlayer.ACT_RETREAT:
                RobotPlayer.sendMsg(rc, RobotPlayer.MSG_PAINTLOW
                    | (RobotPlayer.myLoc.x << 15) | RobotPlayer.myLoc.y);
                doRetreat(rc);
                break;
            case RobotPlayer.ACT_COMBAT:
                doCombat(rc);
                break;
            case RobotPlayer.ACT_BUILD_TOWER:

                if (RobotPlayer.persistTarget != null) {
                    try {
                        RobotInfo atTarget = rc.senseRobotAtLocation(RobotPlayer.persistTarget);
                        if (atTarget != null && atTarget.type.isTowerType()) {
                            RobotPlayer.releaseRuinClaim(RobotPlayer.persistTarget);
                            RobotPlayer.persistTarget = null;
                            RobotPlayer.ruinProgressTimer = 0;
                            RobotPlayer.ruinLastUnpainted = 999;
                        }
                    } catch (GameActionException e) {}
                }
                if (bestRuin != null) {
                    RobotPlayer.persistTarget = bestRuin.getMapLocation();
                    RobotPlayer.claimRuin(rc, RobotPlayer.persistTarget);
                    doBuildTower(rc, RobotPlayer.persistTarget);
                } else if (RobotPlayer.persistTarget != null) {
                    RobotPlayer.paintWhileMoving(rc, RobotPlayer.persistTarget, RobotPlayer.nearbyTiles);
                    if (rc.isMovementReady()) RobotPlayer.moveToward(rc, RobotPlayer.persistTarget);
                }
                break;
            case RobotPlayer.ACT_MESSING_UP:
                if (bestRuin != null) {
                    RobotPlayer.persistTarget = bestRuin.getMapLocation();
                    doMessingUp(rc, RobotPlayer.persistTarget);
                }
                break;
            case RobotPlayer.ACT_BLEED_RESPOND:
                doBleedRespond(rc);
                break;
            case RobotPlayer.ACT_FRONTIER_EXPAND:
                doFrontierExpand(rc);
                break;
            case RobotPlayer.ACT_BUILD_SRP:
                MapLocation srpT = RobotPlayer.nextSrpTarget(rc);
                if (srpT != null) { RobotPlayer.persistTarget = srpT; doBuildSrp(rc, srpT); }
                else doFrontierExpand(rc);
                break;
            case RobotPlayer.ACT_BLITZKRIEG:
                doBlitzkrieg(rc);
                break;
        }

        RobotPlayer.paintUnderSelf(rc);
    }

    static void updateTowerCounts() {
        allyPaintTowerCount = allyMoneyTowerCount = allyDefenseTowerCount = 0;
        for (RobotInfo ally : RobotPlayer.nearbyAllies) {
            if (RobotPlayer.isPaintTower(ally.type))   allyPaintTowerCount++;
            else if (RobotPlayer.isMoneyTower(ally.type))   allyMoneyTowerCount++;
            else if (RobotPlayer.isDefenseTower(ally.type)) allyDefenseTowerCount++;
        }
    }

    static void detectMyRole(RobotController rc) {
        if (RobotPlayer.spawnTowerLoc == null) return;
        Direction fromTower = RobotPlayer.spawnTowerLoc.directionTo(rc.getLocation());
        if (fromTower==Direction.NORTH||fromTower==Direction.NORTHWEST||fromTower==Direction.NORTHEAST)
            RobotPlayer.myRole = RobotPlayer.ROLE_BUILDER;
        else if (fromTower==Direction.EAST||fromTower==Direction.SOUTHEAST)
            RobotPlayer.myRole = RobotPlayer.ROLE_EXPLORER;
        else if (fromTower==Direction.SOUTH||fromTower==Direction.SOUTHWEST)
            RobotPlayer.myRole = RobotPlayer.ROLE_PAINTER;
        else
            RobotPlayer.myRole = RobotPlayer.ROLE_ATTACKER;
    }

    static int calcRetreatScore(int paintPct) {
        if (RobotPlayer.wallStuck) return 180;

        MapLocation nearestTower = RobotPlayer.getNearestKnownTower(RobotPlayer.myLoc);
        int dynamicThreshold;
        if (nearestTower != null) {
            int distToTower = RobotPlayer.myLoc.distanceSquaredTo(nearestTower);
            int estimatedSteps = (int) Math.sqrt(distToTower) + 1;
            int paintNeeded = Math.min(50, estimatedSteps * 2 + 15);
            dynamicThreshold = (int)(100.0 * paintNeeded / 200);
        } else {
            dynamicThreshold = 40;
        }

        if (paintPct >= dynamicThreshold) return 0;
        if (RobotPlayer.curAct == RobotPlayer.ACT_BUILD_TOWER
                && paintPct >= dynamicThreshold / 2) return 0;
        return (dynamicThreshold - paintPct) * 5;
    }

    static int calcCombatScore(RobotController rc, int paintPct) {
        if (RobotPlayer.nearbyEnemies.length == 0 || paintPct < 20) return 0;
        int score = RobotPlayer.nearbyEnemies.length * 20;
        int ac = 0;
        for (RobotInfo a : RobotPlayer.nearbyAllies)
            if (a.type == UnitType.SOLDIER || a.type == UnitType.MOPPER) ac++;
        if (ac >= RobotPlayer.nearbyEnemies.length) score += 25; else score -= 10;
        if (paintPct < 40) score -= 20;
        for (RobotInfo e : RobotPlayer.nearbyEnemies)
            if (e.type.isTowerType()) { score += 50; break; }
        return Math.max(0, score);
    }

    static MapInfo findBestRuin(RobotController rc) throws GameActionException {
        MapInfo best = null; int bs = Integer.MIN_VALUE;
        for (MapInfo tile : RobotPlayer.nearbyTiles) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            try {
                RobotInfo at = rc.senseRobotAtLocation(ruinLoc);
                if (at != null && at.type.isTowerType()) continue;
            } catch (GameActionException e) { continue; }
            if (RobotPlayer.isRuinRecentlyVisited(ruinLoc, rc)) continue;

            int dist = RobotPlayer.myLoc.distanceSquaredTo(ruinLoc);
            int up   = RobotPlayer.countUnpainted(rc, ruinLoc);
            int score = 100 - dist / 2 - up * 2;

            boolean inT = RobotPlayer.isRuinInAllyTerritory(ruinLoc);
            if (inT) score += 60;
            if (RobotPlayer.mirrorLoc != null
                && ruinLoc.distanceSquaredTo(RobotPlayer.mirrorLoc) < 50) score += 15;
            if (RobotPlayer.mapW > 0)
                score += Math.max(0, 20 - ruinLoc.distanceSquaredTo(
                    new MapLocation(RobotPlayer.mapW/2, RobotPlayer.mapH/2)) / 8);
            if (score > bs) { bs = score; best = tile; }
        }
        return best;
    }

    static int calcBuildTowerScore(RobotController rc, MapInfo bestRuin, int paintPct)
            throws GameActionException {
        if (bestRuin == null) {
            if (RobotPlayer.curAct == RobotPlayer.ACT_BUILD_TOWER
                    && RobotPlayer.persistTarget != null) return 120;
            return 0;
        }
        MapLocation ruinLoc = bestRuin.getMapLocation();
        if (RobotPlayer.countRuinClaims(ruinLoc) >= 2) return 0;

        int score = 200;
        score -= RobotPlayer.myLoc.distanceSquaredTo(ruinLoc) / 3;
        score += rc.getRoundNum() < 150 ? 80 : 30;
        if (paintPct < 40 && RobotPlayer.curAct != RobotPlayer.ACT_BUILD_TOWER) score -= 30;
        if (RobotPlayer.isRuinInAllyTerritory(ruinLoc)) score += 40;
        if (RobotPlayer.mapArea < 900) score += 30;

        int ep = RobotPlayer.countEnemyPaintNear(ruinLoc, RobotPlayer.nearbyTiles);
        if (ep >= 4) score += 60;
        else if (ep > 0) score += ep * 8;

        try {
            for (MapInfo m : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                PaintType mk = m.getMark();
                if (mk != PaintType.EMPTY
                        && mk != PaintType.ALLY_PRIMARY
                        && mk != PaintType.ALLY_SECONDARY) {
                    score += 120;
                    break;
                }
            }
        } catch (GameActionException e) {}

        return Math.max(0, score);
    }

    static int calcMessingUpScore(RobotController rc, MapInfo bestRuin)
            throws GameActionException {
        if (bestRuin == null) return 0;
        int ep = RobotPlayer.countEnemyPaintNear(bestRuin.getMapLocation(), RobotPlayer.nearbyTiles);
        if (ep < 4) return 0;
        int score = ep * 10;
        for (RobotInfo a : RobotPlayer.nearbyAllies)
            if (a.type == UnitType.MOPPER
                && a.location.distanceSquaredTo(bestRuin.getMapLocation()) <= 16)
                { score -= 40; break; }
        return Math.max(0, score);
    }

    static int calcExpandScore(RobotController rc) throws GameActionException {
        int emptyCount = 0, frontierCount = 0, borderEnemyCount = 0;
        for (MapInfo tile : RobotPlayer.nearbyTiles) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            if (!tile.isPassable()) continue;
            if (tile.getPaint() == PaintType.EMPTY) emptyCount++;
            if (tile.getPaint().isAlly()) {
                for (Direction dir : RobotPlayer.DIRS) {
                    try {
                        MapInfo adj = rc.senseMapInfo(tile.getMapLocation().add(dir));
                        if (adj.isPassable() && !adj.getPaint().isAlly()) { frontierCount++; break; }
                    } catch (GameActionException e) {}
                }
            }
            if (RobotPlayer.isEnemyPaint(tile.getPaint())) {
                for (MapInfo adj : RobotPlayer.nearbyTiles)
                    if (tile.getMapLocation().distanceSquaredTo(adj.getMapLocation()) <= 2
                        && adj.getPaint().isAlly()) { borderEnemyCount++; break; }
            }
        }
        int score = emptyCount * 4 + frontierCount * 2 + borderEnemyCount * 8;
        score += RobotPlayer.mapArea > 1600 ? 40 : RobotPlayer.mapArea > 900 ? 20 : 8;
        score += RobotPlayer.turnCount / 6;
        int sec = RobotPlayer.getSectorId(RobotPlayer.myLoc);
        if (sec >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.mirrorPrioritySec, sec)) score += 25;
        if (sec >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.exhaustedSectors, sec)) score -= 40;
        if (emptyCount < 2 && frontierCount < 2 && borderEnemyCount < 2) score -= 20;
        return Math.max(0, score);
    }

    static int calcSrpScore(RobotController rc) throws GameActionException {
        if (rc.getRoundNum() < 150 || rc.getChips() < 800) return 0;
        if (rc.getNumberTowers() < 4) return 0;
        int score = 15;
        if (rc.getChips() > 1200)   score += 25;
        if (rc.getRoundNum() > 250) score += 20;
        int ec = 0;
        for (MapInfo t : RobotPlayer.nearbyTiles)
            if (t.isPassable() && t.getPaint() == PaintType.EMPTY) ec++;
        if (ec > 8) score -= 35;
        for (MapInfo t : RobotPlayer.nearbyTiles) if (t.hasRuin()) return 0;
        return Math.max(0, score + 25);
    }

    static int calcBlitzScore() {
        if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG) return 150;
        if (RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER)
            return Math.min(120, RobotPlayer.nearbyEnemies.length * 25 + 50);
        return 0;
    }

    static void doRetreat(RobotController rc) throws GameActionException {
        RobotInfo towerNearby = RobotPlayer.findNearestTower(rc);
        if (towerNearby != null) {
            int dist = RobotPlayer.myLoc.distanceSquaredTo(towerNearby.location);
            if (dist <= 2 && rc.isActionReady()) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                if (needed > 0 && rc.canTransferPaint(towerNearby.location, -needed)) {
                    rc.transferPaint(towerNearby.location, -needed);
                    if (RobotPlayer.wallStuck) {
                        RobotPlayer.resetWallStuck();
                        RobotPlayer.rotateSector();
                        RobotPlayer.rotateSector();
                    }
                }
            }
            if (rc.isMovementReady() && dist > 2) RobotPlayer.moveToward(rc, towerNearby.location);
            return;
        }
        MapLocation knownTower = RobotPlayer.getNearestKnownTower(RobotPlayer.myLoc);
        if (knownTower != null) {
            if (rc.isMovementReady()) RobotPlayer.moveToward(rc, knownTower);
            return;
        }
        RobotPlayer.moveTowardAllyPaint(rc);
    }

    static void doCombat(RobotController rc) throws GameActionException {
        if (rc.isActionReady()) attackBestTarget(rc);
        if (rc.isMovementReady()) {
            RobotInfo wk = null;
            for (RobotInfo e : RobotPlayer.nearbyEnemies)
                if (wk == null || e.health < wk.health) wk = e;
            if (wk != null && !rc.canAttack(wk.location))
                RobotPlayer.moveToward(rc, wk.location);
        }
        if (rc.isActionReady()) {
            RobotInfo[] post = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (post.length > 0) attackBestTarget(rc);
            else RobotPlayer.paintWhileMoving(rc, null, rc.senseNearbyMapInfos());
        }
    }

    static void attackBestTarget(RobotController rc) throws GameActionException {
        if (!rc.isActionReady() || RobotPlayer.nearbyEnemies.length == 0) return;
        RobotInfo best = null; int bs = Integer.MIN_VALUE;
        for (RobotInfo e : RobotPlayer.nearbyEnemies) {
            if (!rc.canAttack(e.location)) continue;
            int s = 1000 - e.health;
            if (e.health <= rc.getType().attackStrength) s += 500;
            if (e.type.isTowerType())            s += 300;
            else if (e.type == UnitType.SOLDIER) s += 100;
            if (s > bs) { bs = s; best = e; }
        }
        if (best != null) rc.attack(best.location);
    }

    static void doBuildTower(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        UnitType towerType = chooseTower(rc);

        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            boolean allyMarked = false;
            boolean enemyMarked = false;
            for (MapInfo m : RobotPlayer.nearbyTiles) {
                PaintType mk = m.getMark();
                if (mk == PaintType.EMPTY) continue;
                if (mk == PaintType.ALLY_PRIMARY || mk == PaintType.ALLY_SECONDARY)
                    allyMarked = true;
                else
                    enemyMarked = true;
            }
            if (!allyMarked || enemyMarked) {
                rc.markTowerPattern(towerType, ruinLoc);
            }
        }

        int distToRuin = RobotPlayer.myLoc.distanceSquaredTo(ruinLoc);
        boolean closeEnough = distToRuin <= 8;

        if (rc.isActionReady()) {
            MapLocation unpainted = RobotPlayer.findClosestUnpainted(rc, ruinLoc);
            if (unpainted != null) {
                boolean sec = rc.senseMapInfo(unpainted).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(unpainted, sec);
            } else if (!closeEnough) {
                paintAroundRuin(rc, ruinLoc);
            }
        }

        if (rc.isMovementReady()) {
            if (closeEnough) {
                MapLocation orbit = getBestOrbitPosition(rc, ruinLoc);
                if (orbit != null && !RobotPlayer.myLoc.equals(orbit)) {
                    RobotPlayer.moveToward(rc, orbit);
                }
            } else {
                RobotPlayer.moveToward(rc, ruinLoc);
            }
        }

        if (rc.isActionReady()) {
            MapLocation unpainted = RobotPlayer.findClosestUnpainted(rc, ruinLoc);
            if (unpainted != null) {
                boolean sec = rc.senseMapInfo(unpainted).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(unpainted, sec);
            }
        }

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            completeTowerNow(rc, towerType, ruinLoc); return;
        }

        int unpaintedCount = RobotPlayer.countUnpainted(rc, ruinLoc);
        if (unpaintedCount >= RobotPlayer.ruinLastUnpainted && unpaintedCount > 0)
            RobotPlayer.ruinProgressTimer++;
        else
            RobotPlayer.ruinProgressTimer = 0;
        RobotPlayer.ruinLastUnpainted = unpaintedCount;

        if (RobotPlayer.ruinProgressTimer > 25 && unpaintedCount > 0) {
            RobotPlayer.ruinProgressTimer = 0;
            RobotPlayer.ruinLastUnpainted = 999;
            RobotPlayer.persistTarget = null;
            RobotPlayer.curAct = RobotPlayer.ACT_FRONTIER_EXPAND;
        }

        RobotPlayer.sendMsg(rc, RobotPlayer.MSG_RUIN | (ruinLoc.x << 15) | ruinLoc.y);
    }

    static void paintAroundRuin(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        if (!rc.isActionReady()) return;
        for (MapInfo pat : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (!pat.isPassable() || pat.getPaint().isAlly()) continue;
            if (!rc.canAttack(pat.getMapLocation())) continue;
            rc.attack(pat.getMapLocation(), false);
            return;
        }
    }

    static UnitType chooseTower(RobotController rc) throws GameActionException {
        int pt = allyPaintTowerCount;
        int mt = allyMoneyTowerCount;
        int dt = allyDefenseTowerCount;
        int totalTowers = rc.getNumberTowers();
        int enemies = RobotPlayer.nearbyEnemies.length;

        if (pt == 0) return UnitType.LEVEL_ONE_PAINT_TOWER;
        if (mt == 0) return UnitType.LEVEL_ONE_MONEY_TOWER;

        int mv = Math.max(0, 60 - mt * 15);
        if (totalTowers < 4) mv += 30;

        int pv = Math.max(0, 50 - pt * 15);
        if (pt <= mt) pv += 20;

        int targetDefense = Math.max(1, totalTowers / 3);
        int dv;
        if (dt < targetDefense) {
            dv = 100 + (targetDefense - dt) * 30;
        } else {
            dv = enemies >= 3 ? 60 + enemies * 10 : Math.max(0, 20 - dt * 5);
        }
        if (enemies > 0) dv += enemies * 8;
        if (RobotPlayer.mapW > 0
            && RobotPlayer.myLoc.distanceSquaredTo(
                new MapLocation(RobotPlayer.mapW/2, RobotPlayer.mapH/2)) < 50)
            dv += 15;

        if (dv >= mv && dv >= pv) return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        if (mv >= pv) return UnitType.LEVEL_ONE_MONEY_TOWER;
        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    static MapLocation getBestOrbitPosition(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        MapLocation best = null; int bd = Integer.MAX_VALUE;
        for (Direction dir : RobotPlayer.ORBIT_DIRS) {
            MapLocation candidate = ruinLoc.add(dir);
            if (!rc.onTheMap(candidate)) continue;
            try { if (!rc.senseMapInfo(candidate).isPassable()) continue; }
            catch (GameActionException e) { continue; }
            int d = RobotPlayer.myLoc.distanceSquaredTo(candidate);
            if (d < bd) { bd = d; best = candidate; }
        }
        return best;
    }

    static void completeTowerNow(RobotController rc, UnitType towerType,
                                  MapLocation ruinLoc) throws GameActionException {
        rc.completeTowerPattern(towerType, ruinLoc);
        rc.setTimelineMarker("Tower!", 0, 255, 0);
        RobotPlayer.queueSrpAround(ruinLoc);
        RobotPlayer.releaseRuinClaim(ruinLoc);
        RobotPlayer.registerRuin(ruinLoc, rc.getRoundNum());
        RobotPlayer.persistTarget = null;
        RobotPlayer.ruinProgressTimer = 0;
        RobotPlayer.ruinLastUnpainted = 999;
        RobotPlayer.curAct = RobotPlayer.ACT_FRONTIER_EXPAND;
    }

    static void paintEnemyRuinNearby(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        for (MapInfo tile : RobotPlayer.nearbyTiles) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            try { if (rc.senseRobotAtLocation(ruinLoc) != null) continue; }
            catch (GameActionException e) { continue; }
            if (RobotPlayer.persistTarget != null && RobotPlayer.persistTarget.equals(ruinLoc)) continue;
            if (RobotPlayer.mirrorLoc != null
                && RobotPlayer.myLoc.distanceSquaredTo(ruinLoc)
                   < RobotPlayer.mirrorLoc.distanceSquaredTo(ruinLoc)) continue;
            for (MapInfo pat : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (!pat.isPassable() || pat.getPaint().isAlly()) continue;
                if (rc.canAttack(pat.getMapLocation())) { rc.attack(pat.getMapLocation()); return; }
            }
        }
    }

    static void doMessingUp(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        if (rc.isActionReady()) {
            MapLocation best = null; int bd = Integer.MAX_VALUE;
            for (MapInfo tile : RobotPlayer.nearbyTiles) {
                if (!RobotPlayer.isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;
                int d = ruinLoc.distanceSquaredTo(tile.getMapLocation());
                if (d < bd) { bd = d; best = tile.getMapLocation(); }
            }
            if (best != null) rc.attack(best);
        }
        if (rc.isMovementReady()) RobotPlayer.moveToward(rc, ruinLoc);
    }

    static void doBleedRespond(RobotController rc) throws GameActionException {
        if (RobotPlayer.bleedLocation == null) { RobotPlayer.curAct = RobotPlayer.ACT_FRONTIER_EXPAND; return; }
        if (rc.isActionReady()) {
            MapLocation best = null; int bd = Integer.MAX_VALUE;
            for (MapInfo tile : RobotPlayer.nearbyTiles) {
                if (!RobotPlayer.isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;
                int d = RobotPlayer.bleedLocation.distanceSquaredTo(tile.getMapLocation());
                if (d < bd) { bd = d; best = tile.getMapLocation(); }
            }
            if (best != null) rc.attack(best);
        }
        if (rc.isMovementReady()) RobotPlayer.moveToward(rc, RobotPlayer.bleedLocation);
        if (rc.isActionReady()) {
            for (MapInfo tile : rc.senseNearbyMapInfos()) {
                if (!RobotPlayer.isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;
                rc.attack(tile.getMapLocation()); break;
            }
        }
    }

    static void doFrontierExpand(RobotController rc) throws GameActionException {
        boolean nearBuild = isNearRuinBeingBuilt(rc);

        if (rc.isActionReady() && !nearBuild) {
            RobotPlayer.greedyPaintFrontier(rc, RobotPlayer.nearbyTiles);
        }

        MapLocation ft = findBestFrontierTarget(rc);
        if (rc.isMovementReady()) {
            if (ft != null && RobotPlayer.myLoc.distanceSquaredTo(ft) > 2) {
                if (!nearBuild) RobotPlayer.paintWhileMoving(rc, ft, RobotPlayer.nearbyTiles);
                RobotPlayer.moveToward(rc, ft);
            } else if (RobotPlayer.exploreTarget != null) {
                if (!nearBuild) RobotPlayer.paintWhileMoving(rc, RobotPlayer.exploreTarget, RobotPlayer.nearbyTiles);
                RobotPlayer.moveToward(rc, RobotPlayer.exploreTarget);
            } else {
                RobotPlayer.moveExplore(rc);
            }
        }

        if (rc.isActionReady() && !nearBuild) {
            RobotPlayer.greedyPaintFrontier(rc, rc.senseNearbyMapInfos());
        }

        if ((RobotPlayer.gamePhase == RobotPlayer.PHASE_BORDER
                || RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER)
                && rc.isActionReady()) aggressivePushEnemy(rc);
    }

    static boolean isNearRuinBeingBuilt(RobotController rc) throws GameActionException {
        for (MapInfo tile : RobotPlayer.nearbyTiles) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            try { if (rc.senseRobotAtLocation(ruinLoc) != null) continue; }
            catch (GameActionException e) { continue; }
            for (MapInfo nearby : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                PaintType mk = nearby.getMark();
                if (mk == PaintType.ALLY_PRIMARY || mk == PaintType.ALLY_SECONDARY)
                    return true;
            }
        }
        return false;
    }

    static MapLocation findBestFrontierTarget(RobotController rc) throws GameActionException {
        MapLocation best = null; int bs = Integer.MIN_VALUE;
        for (MapInfo tile : RobotPlayer.nearbyTiles) {
            if (Clock.getBytecodesLeft() < RobotPlayer.BC_CUTOFF) break;
            if (!tile.isPassable()) continue;
            PaintType p = tile.getPaint();
            if (p.isAlly()) continue;
            MapLocation loc = tile.getMapLocation();
            boolean isFrontier = false;
            for (Direction dir : RobotPlayer.DIRS) {
                try { if (rc.senseMapInfo(loc.add(dir)).getPaint().isAlly()) { isFrontier = true; break; } }
                catch (GameActionException e) {}
            }
            int score = isFrontier ? 30 : 0;
            if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BLITZKRIEG)    score += RobotPlayer.isEnemyPaint(p) ? 80 : 15;
            else if (RobotPlayer.gamePhase == RobotPlayer.PHASE_CONQUER)  score += RobotPlayer.isEnemyPaint(p) ? 60 : 10;
            else if (RobotPlayer.gamePhase == RobotPlayer.PHASE_BORDER)   score += RobotPlayer.isEnemyPaint(p) ? 40 : 20;
            else {
                if (RobotPlayer.isEnemyPaint(p) && isFrontier) score += 45;
                else if (RobotPlayer.isEnemyPaint(p))           score += 30;
                else if (p == PaintType.EMPTY)                  score += 20;
                else                                            score += 10;
            }
            int secId = RobotPlayer.getSectorId(loc);
            if (secId >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.mirrorPrioritySec, secId)) score += 20;
            if (RobotPlayer.spawnTowerLoc != null) score += loc.distanceSquaredTo(RobotPlayer.spawnTowerLoc) / 15;
            MapLocation et = RobotPlayer.confirmedEnemy != null ? RobotPlayer.confirmedEnemy : RobotPlayer.mirrorLoc;
            if (et != null) score -= loc.distanceSquaredTo(et) / 20;
            score -= RobotPlayer.recentVisitPenalty(loc);
            try { score -= RobotPlayer.calcAdjacencyPenalty(rc, loc); }
            catch (GameActionException e) {}
            int ce = 0;
            for (MapInfo m : RobotPlayer.nearbyTiles)
                if (loc.distanceSquaredTo(m.getMapLocation()) <= 4 && m.isPassable() && m.getPaint() == PaintType.EMPTY) ce++;
            score += ce * 3;
            if (secId >= 0 && !RobotPlayer.isSectorBitSet(RobotPlayer.visitedSectors, secId)) score += 20;
            if (secId >= 0 && RobotPlayer.isSectorBitSet(RobotPlayer.exhaustedSectors, secId)) score -= 30;
            if (score > bs) { bs = score; best = loc; }
        }
        return best;
    }

    static void aggressivePushEnemy(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        MapLocation best = null; int bs = Integer.MIN_VALUE;
        for (MapInfo m : RobotPlayer.nearbyTiles) {
            if (!rc.canAttack(m.getMapLocation()) || !RobotPlayer.isEnemyPaint(m.getPaint())) continue;
            int score = 100;
            int aa = 0;
            for (MapInfo adj : RobotPlayer.nearbyTiles)
                if (m.getMapLocation().distanceSquaredTo(adj.getMapLocation()) <= 2 && adj.getPaint().isAlly()) aa++;
            score += aa * 15;
            if (score > bs) { bs = score; best = m.getMapLocation(); }
        }
        if (best != null) rc.attack(best);
    }

    static void doBlitzkrieg(RobotController rc) throws GameActionException {
        MapLocation blitzTarget = RobotPlayer.confirmedEnemy != null ? RobotPlayer.confirmedEnemy : RobotPlayer.mirrorLoc;
        if (rc.isActionReady()) {
            MapLocation best = null; int bs = Integer.MIN_VALUE;
            for (MapInfo m : RobotPlayer.nearbyTiles) {
                if (!rc.canAttack(m.getMapLocation())) continue;
                PaintType p = m.getPaint();
                int score = RobotPlayer.isEnemyPaint(p) ? 150 : p == PaintType.EMPTY ? 30 : -10;
                if (score > bs) { bs = score; best = m.getMapLocation(); }
            }
            if (best != null && bs > 0) rc.attack(best);
        }
        if (rc.isMovementReady()) {
            if (blitzTarget != null) RobotPlayer.moveToward(rc, blitzTarget);
            else RobotPlayer.moveExplore(rc);
        }
        if (rc.isActionReady()) {
            if (RobotPlayer.nearbyEnemies.length > 0) attackBestTarget(rc);
            else for (MapInfo m : rc.senseNearbyMapInfos()) {
                if (!rc.canAttack(m.getMapLocation())) continue;
                if (RobotPlayer.isEnemyPaint(m.getPaint())) { rc.attack(m.getMapLocation()); break; }
            }
        }
    }

    static void runSrpDuty(RobotController rc, int paintPct) throws GameActionException {
        if (paintPct < 25) { doRetreat(rc); return; }
        MapLocation st = RobotPlayer.nextSrpTarget(rc);
        if (st != null) { RobotPlayer.persistTarget = st; doBuildSrp(rc, st); }
        else { RobotPlayer.moveExplore(rc); RobotPlayer.isSrpDuty = false; RobotPlayer.curAct = RobotPlayer.ACT_FRONTIER_EXPAND; }
    }

    static void doBuildSrp(RobotController rc, MapLocation target) throws GameActionException {
        if (!RobotPlayer.isSrpValid(rc, target)) { RobotPlayer.disqualifySrp(target); RobotPlayer.persistTarget = null; return; }
        if (rc.canMarkResourcePattern(target)) rc.markResourcePattern(target);
        if (rc.isActionReady()) {
            MapLocation t = RobotPlayer.findClosestUnpainted(rc, target);
            if (t != null) {
                boolean sec = rc.senseMapInfo(t).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(t, sec);
            } else {
                for (MapInfo pat : rc.senseNearbyMapInfos(target, 8)) {
                    if (!pat.isPassable() || pat.getPaint().isAlly()) continue;
                    if (!rc.canAttack(pat.getMapLocation())) continue;
                    int dx = pat.getMapLocation().x - target.x;
                    int dy = pat.getMapLocation().y - target.y;
                    rc.attack(pat.getMapLocation(), (dx + dy) % 2 != 0);
                    break;
                }
            }
        }
        if (rc.isMovementReady()) RobotPlayer.moveToward(rc, target);
        if (rc.isActionReady()) {
            MapLocation t = RobotPlayer.findClosestUnpainted(rc, target);
            if (t != null) {
                boolean sec = rc.senseMapInfo(t).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(t, sec);
            }
        }
        if (rc.canCompleteResourcePattern(target)) {
            rc.completeResourcePattern(target);
            rc.setTimelineMarker("SRP!", 0, 200, 255);
            RobotPlayer.queueSrpExpansion(target);
            RobotPlayer.persistTarget = null;
            if (RobotPlayer.isSrpDuty) RobotPlayer.isSrpDuty = false;
        }
    }
}