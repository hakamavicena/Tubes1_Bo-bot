package bot_8;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {

    static final Random rng = new Random(6147);
    static int turnCount = 0;

    static final Direction[] DIRS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };
    static final Direction[] CARDINAL = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
    };

    static final int BC_CUTOFF = 2500;

    // bit 30-31: 00=INTERNAL 01=ENEMY 10=RUIN 11=PAINTLOW
    // bit 29(00): 1=SECTOR_DONE | bit 28: 1=BLEED | bit 27: RUIN_CLAIMED
    static final int MSG_ENEMY        = 1 << 30;
    static final int MSG_RUIN         = 2 << 30;
    static final int MSG_PAINTLOW     = 3 << 30;
    static final int MSG_TYPE_MASK    = 3 << 30;
    static final int MSG_SECTOR_FLAG  = 1 << 29;
    static final int MSG_BLEED_FLAG   = 1 << 28;
    static final int MSG_RUIN_CLAIMED = 1 << 27;

    static int         mapW = -1, mapH = -1, mapArea = -1;
    static boolean     symInit        = false;
    static MapLocation spawnTowerLoc  = null;
    static MapLocation mirrorLoc      = null;
    static MapLocation confirmedEnemy = null;
    static int         symType        = 0; // 0=rot180 1=reflectX 2=reflectY

    // fase: EXPAND → BORDER → CONQUER → BLITZKRIEG
    static final int PHASE_EXPAND     = 0;
    static final int PHASE_BORDER     = 1;
    static final int PHASE_CONQUER    = 2;
    static final int PHASE_BLITZKRIEG = 3;

    static int   gamePhase       = PHASE_EXPAND;
    static float localCoverage   = 0f;
    static float localEnemyRatio = 0f;

    static final float BLITZ_TRIGGER_COVERAGE = 0.78f;
    static final float BLITZ_EXIT_COVERAGE    = 0.50f;
    static final int   BLITZ_MIN_ROUND        = 250;

    // role di-encode dalam spawn direction: NORTH=BUILDER EAST=EXPLORER SOUTH=PAINTER WEST=ATTACKER
    static final int ROLE_BUILDER  = 0;
    static final int ROLE_EXPLORER = 1;
    static final int ROLE_PAINTER  = 2;
    static final int ROLE_ATTACKER = 3;

    static int myRole = ROLE_PAINTER;

    static final Direction[] ROLE_DIRS = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    static int         prevAllyCount  = -1;
    static final int   BLEED_THRESH   = 3;
    static boolean     isBleedingNow  = false;
    static MapLocation bleedLocation  = null;

    static long[] enemySectorsSeen  = new long[4];
    static long[] mirrorPrioritySec = new long[4];

    static final int SECTOR_SIZE        = 5;
    static final int EXHAUSTED_TURNS    = 10;
    static final int MIN_PAINT_PROGRESS = 2;

    static int secGridW = -1, secGridH = -1, maxSectors = -1;
    static long[] visitedSectors   = new long[4];
    static long[] exhaustedSectors = new long[4];
    static long[] towerExhSectors  = new long[4];

    static int         lastSectorId    = -1;
    static int         turnsInSector   = 0;
    static int         paintedInSector = 0;
    static int         targetSectorId  = -1;
    static MapLocation sectorTarget    = null;
    static int         sectorTargetAge = 0;

    static final int ACT_RETREAT         = 0;
    static final int ACT_COMBAT          = 1;
    static final int ACT_BUILD_TOWER     = 2;
    static final int ACT_MESSING_UP      = 3;
    static final int ACT_FRONTIER_EXPAND = 4;
    static final int ACT_BUILD_SRP       = 5;
    static final int ACT_SRP_DUTY        = 6;
    static final int ACT_BLITZKRIEG      = 7;
    static final int ACT_BLEED_RESPOND   = 8;

    static int         curAct            = ACT_FRONTIER_EXPAND;
    static MapLocation persistTarget     = null;
    static int         actTimer          = 0;
    static boolean     isSrpDuty         = false;

    static int         ruinProgressTimer = 0;
    static int         ruinLastUnpainted = 999;

    static MapLocation[] recentLocs = new MapLocation[10];
    static int           recentIdx  = 0;
    static MapLocation   lastLoc    = null;
    static int           stuckCount = 0;

    static MapLocation[] claimedRuins = new MapLocation[10];
    static int           claimedCount = 0;

    static boolean isBlitzkrieg  = false;
    static int     builderCount  = 0;

    static MapLocation[] srpQueue          = new MapLocation[20];
    static boolean[]     srpDone           = new boolean[20];
    static int           srpQueueSize      = 0;
    static int           srpQueueHead      = 0;
    static int           srpSquadLastRound = 0;

    static int towerSpawnCount   = 0;
    static int towerBuilderCount = 0;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        if (mapW < 0) {
            mapW    = rc.getMapWidth();
            mapH    = rc.getMapHeight();
            mapArea = mapW * mapH;
            secGridW   = (mapW + SECTOR_SIZE - 1) / SECTOR_SIZE;
            secGridH   = (mapH + SECTOR_SIZE - 1) / SECTOR_SIZE;
            maxSectors = secGridW * secGridH;
        }
        while (true) {
            turnCount++;
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case MOPPER:   runMopper(rc);   break;
                    case SPLASHER: runSplasher(rc); break;
                    default:       runTower(rc);    break;
                }
            } catch (GameActionException e) {
                System.out.println("GAE:" + e.getMessage());
            } catch (Exception e) {
                System.out.println("EX:" + e.getMessage());
            } finally {
                Clock.yield();
            }
        }
    }

    // =========================================================================
    // TOWER
    // =========================================================================
    public static void runTower(RobotController rc) throws GameActionException {
        if (!symInit) { initSymmetry(rc); symInit = true; }

        tryUpgradeSelf(rc);
        towerSingleAttack(rc);
        towerAoeAttack(rc);

        boolean     paintLow     = false;
        boolean     enemyNear    = false;
        MapLocation ruinMsg      = null;
        MapLocation frontierInfo = null;
        MapLocation bleedInfo    = null;
        int         sectorDoneId = -1;

        for (Message m : rc.readMessages(-1)) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            int data = m.getBytes();
            int type = data & MSG_TYPE_MASK;

            if      (type == MSG_PAINTLOW) paintLow = true;
            else if (type == MSG_ENEMY) {
                enemyNear = true;
                refineSymmetry(new MapLocation((data>>15)&0x7FFF, data&0x7FFF));
            } else if (type == MSG_RUIN) {
                ruinMsg = new MapLocation((data>>15)&0x7FFF, data&0x7FFF);
            } else {
                if ((data & MSG_SECTOR_FLAG) != 0) {
                    sectorDoneId = (data >> 20) & 0x7F;
                    if (sectorDoneId >= 0 && sectorDoneId < maxSectors)
                        setSectorBit(towerExhSectors, sectorDoneId);
                } else if ((data & MSG_BLEED_FLAG) != 0) {
                    bleedInfo = new MapLocation((data>>15)&0x3FFF, data&0x7FFF);
                } else if ((data & MSG_RUIN_CLAIMED) != 0) {
                    addClaimedRuin(new MapLocation((data>>14)&0x3FFF, data&0x3FFF));
                } else {
                    frontierInfo = new MapLocation((data>>15)&0x7FFF, data&0x7FFF);
                }
            }
            broadcastTower(rc, data);
        }

        tryDisintegrateDefense(rc);

        int round = rc.getRoundNum();
        boolean srpNeeded = round > 60 && round % 50 == 0
                         && round - srpSquadLastRound >= 50;

        towerSpawn(rc, paintLow, enemyNear, ruinMsg, frontierInfo, bleedInfo, srpNeeded);
    }

    static void tryUpgradeSelf(RobotController rc) throws GameActionException {
        if (!rc.canUpgradeTower(rc.getLocation())) return;
        int chips = rc.getChips();
        UnitType t = rc.getType();
        if      (isMoneyTower(t)   && chips >= 3500) rc.upgradeTower(rc.getLocation());
        else if (isPaintTower(t)   && chips >= 4000) rc.upgradeTower(rc.getLocation());
        else if (isDefenseTower(t) && chips >= 3500) rc.upgradeTower(rc.getLocation());
    }

    static void towerSingleAttack(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0) return;
        RobotInfo weakest = enemies[0];
        for (RobotInfo e : enemies)
            if (e.health < weakest.health ||
               (e.health == weakest.health && e.type == UnitType.SOLDIER)) weakest = e;
        if (rc.canAttack(weakest.location)) rc.attack(weakest.location);
    }

    static void towerAoeAttack(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0) return;
        MapLocation best = null; int bestCount = 0;
        for (RobotInfo e : enemies) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!rc.canAttack(e.location)) continue;
            int cnt = 0;
            for (RobotInfo e2 : enemies)
                if (e.location.distanceSquaredTo(e2.location) <= 9) cnt++;
            if (cnt > bestCount) { bestCount = cnt; best = e.location; }
        }
        if (best != null) rc.attack(best);
    }

    static void broadcastTower(RobotController rc, int data) throws GameActionException {
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!ally.type.isTowerType()) continue;
            if (rc.getLocation().distanceSquaredTo(ally.location) > 80) continue;
            if (rc.canSendMessage(ally.location, data)) rc.sendMessage(ally.location, data);
        }
    }

    static void tryDisintegrateDefense(RobotController rc) throws GameActionException {
        if (!isDefenseTower(rc.getType())) return;
        if (turnCount % 30 != 0) return;
        if (rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length > 0) return;
        for (MapInfo tile : rc.senseNearbyMapInfos())
            if (isEnemyPaint(tile.getPaint())) return;
        rc.disintegrate();
    }

    static void towerSpawn(RobotController rc, boolean paintLow, boolean enemyNear,
                            MapLocation ruinMsg, MapLocation frontierInfo,
                            MapLocation bleedInfo, boolean srpNeeded)
            throws GameActionException {
        if (!rc.isActionReady()) return;
        int round = rc.getRoundNum();

        if (round > 150 && rc.senseNearbyRobots(9, rc.getTeam()).length >= 6
                && round % 3 != 0) return;

        if (towerSpawnCount < 3) {
            if (spawnWithRole(rc, UnitType.SOLDIER, ROLE_BUILDER, ruinMsg)) towerSpawnCount++;
            return;
        }
        if (round < 50) {
            if (spawnWithRole(rc, UnitType.SOLDIER, ROLE_BUILDER, ruinMsg)) towerSpawnCount++;
            return;
        }

        if (srpNeeded) {
            if (spawnWithRole(rc, UnitType.SOLDIER, ROLE_PAINTER, null)) {
                towerSpawnCount++;
                srpSquadLastRound = round;
                spawnWithRole(rc, UnitType.SOLDIER, ROLE_PAINTER, null);
                towerSpawnCount++;
                return;
            }
        }

        int solCount = 0, mopCount = 0, splashCount = 0, lowPaintSol = 0;
        int enemyTiles = 0, emptyTiles = 0, allyTiles = 0;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (ally.type == UnitType.SOLDIER) {
                solCount++;
                if ((int)(100.0*ally.paintAmount/ally.type.paintCapacity) < 40) lowPaintSol++;
            } else if (ally.type == UnitType.MOPPER)   mopCount++;
            else if (ally.type == UnitType.SPLASHER) splashCount++;
        }
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            PaintType p = tile.getPaint();
            if (isEnemyPaint(p))                            enemyTiles++;
            else if (p == PaintType.EMPTY && tile.isPassable()) emptyTiles++;
            else if (p.isAlly())                            allyTiles++;
        }

        boolean needBuilder = (ruinMsg != null);

        if (gamePhase == PHASE_BLITZKRIEG) {
            // sisakan maks 2 BUILDER, sisanya serbu
            if (needBuilder && towerBuilderCount < 2) {
                if (spawnWithRole(rc, UnitType.SOLDIER, ROLE_BUILDER, ruinMsg)) {
                    towerSpawnCount++; towerBuilderCount++;
                }
                return;
            }
            if (solCount * 2 < splashCount + mopCount)
                spawnWithRole(rc, UnitType.SOLDIER, ROLE_ATTACKER, null);
            else if (splashCount <= mopCount)
                spawnWithRole(rc, UnitType.SPLASHER, ROLE_ATTACKER, null);
            else
                spawnWithRole(rc, UnitType.MOPPER, ROLE_ATTACKER, null);
            towerSpawnCount++;
            return;
        }

        double targetSol, targetSplash, targetMop;
        if (gamePhase == PHASE_CONQUER) {
            targetSol = 0.30; targetSplash = 0.45; targetMop = 0.25;
        } else if (gamePhase == PHASE_BORDER) {
            targetSol = 0.35; targetSplash = 0.40; targetMop = 0.25;
        } else {
            if (round < 100) {
                targetSol = 0.60; targetSplash = 0.25; targetMop = 0.15;
            } else if (round < 400) {
                double t = (round-100.0)/300.0;
                targetSol = 0.60-t*0.30; targetSplash = 0.25+t*0.25; targetMop = 0.15+t*0.05;
            } else {
                targetSol = 0.30; targetSplash = 0.50; targetMop = 0.20;
            }
        }

        int total = Math.max(1, solCount + mopCount + splashCount);
        int sSoldier  = (int)((targetSol    - (double)solCount/total)   * 100) + 10;
        int sSplasher = (int)((targetSplash - (double)splashCount/total)* 100) + 10;
        int sMopper   = (int)((targetMop    - (double)mopCount/total)   * 100) + 10;

        if (needBuilder)         sSoldier  += 70; // ruin teritori → prioritas utama
        if (enemyNear)         { sSoldier  += 20; sMopper += 15; }
        if (paintLow)            sMopper   += 50;
        if (lowPaintSol >= 2)    sMopper   += 35;
        if (bleedInfo != null)   sMopper   += 45;
        if (frontierInfo != null){ sSplasher += 35; sMopper += 20; }
        if (emptyTiles > 15)     sSplasher += 25;
        if (mapArea < 900)       sSoldier  += 20;
        if (splashCount >= 5)    sSplasher -= 40;
        if (mopCount >= 4)       sMopper   -= 30;
        if (solCount >= 8)       sSoldier  -= 25;

        int soldierRole;
        if (needBuilder)                                    soldierRole = ROLE_BUILDER;
        else if (gamePhase==PHASE_BORDER||gamePhase==PHASE_CONQUER) soldierRole = ROLE_ATTACKER;
        else if (emptyTiles > 10)                           soldierRole = ROLE_PAINTER;
        else                                                soldierRole = ROLE_EXPLORER;

        UnitType toSpawn;
        if (sSoldier >= sSplasher && sSoldier >= sMopper)  toSpawn = UnitType.SOLDIER;
        else if (sSplasher >= sMopper)                      toSpawn = UnitType.SPLASHER;
        else                                                toSpawn = UnitType.MOPPER;

        if (toSpawn == UnitType.SOLDIER) {
            if (spawnWithRole(rc, UnitType.SOLDIER, soldierRole, ruinMsg)) towerSpawnCount++;
        } else if (toSpawn == UnitType.SPLASHER) {
            if (spawnWithRole(rc, UnitType.SPLASHER, ROLE_PAINTER, null)) towerSpawnCount++;
        } else {
            if (spawnWithRole(rc, UnitType.MOPPER, ROLE_ATTACKER, null)) towerSpawnCount++;
        }
    }

    // role di-encode ke arah spawn; robot deteksi rolenya dari posisi relatif ke tower
    static boolean spawnWithRole(RobotController rc, UnitType type,
                                  int role, MapLocation ruinLoc)
            throws GameActionException {
        Direction roleDir = ROLE_DIRS[role];
        MapLocation roleLoc = rc.getLocation().add(roleDir);
        if (rc.canBuildRobot(type, roleLoc)) {
            rc.buildRobot(type, roleLoc);
            return true;
        }
        Direction bestDir = null; int bestScore = Integer.MIN_VALUE;
        for (Direction dir : DIRS) {
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
        else score -= unit==UnitType.MOPPER ? 4 : unit==UnitType.SPLASHER ? 3 : 2;
        if (unit == UnitType.SOLDIER && ruinLoc != null)
            score -= loc.distanceSquaredTo(ruinLoc) / 8;
        if (spawnTowerLoc != null)
            score += loc.distanceSquaredTo(spawnTowerLoc) / 8;
        int secId = getSectorId(loc);
        if (secId >= 0 && isSectorBitSet(towerExhSectors, secId)) score -= 20;
        score -= (unit==UnitType.MOPPER ? 6 : 3) *
                  rc.senseNearbyRobots(loc, 20, rc.getTeam().opponent()).length;
        return score;
    }

    // =========================================================================
    // SOLDIER
    // =========================================================================
    public static void runSoldier(RobotController rc) throws GameActionException {
        if (!symInit) { initSymmetry(rc); symInit = true; }
        if (myRole == ROLE_PAINTER && spawnTowerLoc != null) detectMyRole(rc);

        MapLocation myLoc   = rc.getLocation();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        MapInfo[]   nearby  = rc.senseNearbyMapInfos();
        int paintPct = (int)(100.0 * rc.getPaint() / rc.getType().paintCapacity);

        updateStuck(myLoc);
        recentLocs[recentIdx % recentLocs.length] = myLoc;
        recentIdx++;

        updateSectorState(rc, myLoc, nearby);
        updateCoverageAndPhase(nearby);
        detectBleed(rc, nearby, myLoc);
        updateAsymmetricCoverage(nearby);

        if (enemies.length > 0)
            sendMsg(rc, MSG_ENEMY | (myLoc.x<<15) | myLoc.y);
        for (RobotInfo e : enemies)
            if (e.type.isTowerType()) refineSymmetry(e.location);

        MapLocation enemyFrontier = findEnemyFrontier(nearby);
        if (enemyFrontier != null)
            sendMsg(rc, (enemyFrontier.x<<15) | enemyFrontier.y);

        if (isBleedingNow && bleedLocation != null)
            sendMsg(rc, MSG_BLEED_FLAG | (bleedLocation.x<<14) | bleedLocation.y);

        tryUpgradeNearTower(rc);

        if (isSrpDuty) {
            rc.setIndicatorString("SRP_DUTY p="+paintPct+"%");
            runSrpDuty(rc, nearby, paintPct, myLoc);
            paintUnderSelf(rc);
            return;
        }

        int retreatScore    = calcRetreatScore(paintPct);
        int combatScore     = calcCombatScore(rc, enemies, allies, paintPct);
        MapInfo bestRuin    = findBestAllyTerritoryRuin(rc, nearby);
        int buildTowerScore = calcBuildTowerScore(rc, bestRuin, nearby, paintPct);
        int messingUpScore  = calcMessingUpScore(rc, bestRuin, nearby, allies);
        int expandScore     = calcExpandScore(rc, nearby, myLoc);
        int srpScore        = calcSrpScore(rc, nearby);
        int blitzScore      = calcBlitzScore(enemies, nearby);
        int bleedScore      = isBleedingNow ? 60 : 0;

        // role modifier
        switch (myRole) {
            case ROLE_BUILDER:
                buildTowerScore = (int)(buildTowerScore * 2.0);
                messingUpScore  = (int)(messingUpScore  * 1.5);
                blitzScore      = (int)(blitzScore      * 0.3);
                break;
            case ROLE_EXPLORER:
                expandScore     = (int)(expandScore     * 1.5);
                buildTowerScore = (int)(buildTowerScore * 1.3);
                break;
            case ROLE_PAINTER:
                expandScore     = (int)(expandScore     * 1.8);
                combatScore     = (int)(combatScore     * 0.7);
                break;
            case ROLE_ATTACKER:
                combatScore     = (int)(combatScore     * 2.0);
                blitzScore      = (int)(blitzScore      * 1.5);
                expandScore     = (int)(expandScore     * 0.6);
                break;
        }

        // phase modifier
        if (gamePhase == PHASE_BORDER) {
            combatScore    = (int)(combatScore    * 1.5);
            messingUpScore = (int)(messingUpScore * 1.8);
            int fc = 0;
            for (RobotInfo a : allies)
                if (a.type==UnitType.SOLDIER||a.type==UnitType.MOPPER) fc++;
            if (fc >= 3 && myRole == ROLE_PAINTER) expandScore += 25;
        }
        if (gamePhase == PHASE_CONQUER) {
            combatScore    = (int)(combatScore    * 2.0);
            messingUpScore = (int)(messingUpScore * 2.5);
            expandScore    = Math.max(0, expandScore - 30);
        }
        if (gamePhase == PHASE_BLITZKRIEG) {
            if (myRole != ROLE_BUILDER) {
                blitzScore  = 200;
                expandScore = 0;
                srpScore    = 0;
            }
        }

        int maxScore = retreatScore; int bestAct = ACT_RETREAT;
        if (combatScore     > maxScore) { maxScore = combatScore;     bestAct = ACT_COMBAT; }
        if (buildTowerScore > maxScore) { maxScore = buildTowerScore; bestAct = ACT_BUILD_TOWER; }
        if (messingUpScore  > maxScore) { maxScore = messingUpScore;  bestAct = ACT_MESSING_UP; }
        if (bleedScore      > maxScore) { maxScore = bleedScore;      bestAct = ACT_BLEED_RESPOND; }
        if (expandScore     > maxScore) { maxScore = expandScore;     bestAct = ACT_FRONTIER_EXPAND; }
        if (srpScore        > maxScore) { maxScore = srpScore;        bestAct = ACT_BUILD_SRP; }
        if (blitzScore      > maxScore) { maxScore = blitzScore;      bestAct = ACT_BLITZKRIEG; }

        if (bestAct != curAct) { curAct = bestAct; actTimer = 0; }
        else actTimer++;

        rc.setIndicatorString(actName(curAct)+" "+roleName(myRole)
                              +" ph="+phaseName(gamePhase)+" p="+paintPct+"%");

        switch (curAct) {
            case ACT_RETREAT:
                sendMsg(rc, MSG_PAINTLOW | (myLoc.x<<15) | myLoc.y);
                doRetreat(rc);
                break;
            case ACT_COMBAT:
                doCombat(rc, enemies, nearby);
                break;
            case ACT_BUILD_TOWER:
                if (bestRuin != null) {
                    persistTarget = bestRuin.getMapLocation();
                    claimRuin(rc, persistTarget);
                    doBuildTower(rc, persistTarget, nearby);
                } else if (persistTarget != null) {
                    paintWhileMoving(rc, persistTarget, nearby);
                    if (rc.isMovementReady()) moveToward(rc, persistTarget);
                }
                break;
            case ACT_MESSING_UP:
                if (bestRuin != null) {
                    persistTarget = bestRuin.getMapLocation();
                    doMessingUp(rc, persistTarget, nearby);
                }
                break;
            case ACT_BLEED_RESPOND:
                doBleedRespond(rc, nearby);
                break;
            case ACT_FRONTIER_EXPAND:
                doFrontierExpand(rc, nearby, myLoc);
                break;
            case ACT_BUILD_SRP:
                MapLocation srpT = nextSrpTarget(rc, nearby);
                if (srpT != null) { persistTarget = srpT; doBuildSrp(rc, srpT, nearby); }
                else doFrontierExpand(rc, nearby, myLoc);
                break;
            case ACT_BLITZKRIEG:
                doBlitzkrieg(rc, nearby, myLoc);
                break;
        }

        paintUnderSelf(rc);
    }

    static void detectMyRole(RobotController rc) throws GameActionException {
        if (spawnTowerLoc == null) return;
        Direction fromTower = spawnTowerLoc.directionTo(rc.getLocation());
        if (fromTower==Direction.NORTH||fromTower==Direction.NORTHWEST||fromTower==Direction.NORTHEAST)
            myRole = ROLE_BUILDER;
        else if (fromTower==Direction.EAST||fromTower==Direction.SOUTHEAST)
            myRole = ROLE_EXPLORER;
        else if (fromTower==Direction.SOUTH||fromTower==Direction.SOUTHWEST)
            myRole = ROLE_PAINTER;
        else myRole = ROLE_ATTACKER;
    }

    static void updateCoverageAndPhase(MapInfo[] nearby) {
        int ally=0, empty=0, enemy=0, total=0;
        for (MapInfo tile : nearby) {
            if (!tile.isPassable()) continue;
            total++;
            PaintType p = tile.getPaint();
            if (p.isAlly())            ally++;
            else if (p==PaintType.EMPTY) empty++;
            else if (isEnemyPaint(p))  enemy++;
        }
        total = Math.max(1, total);
        localCoverage    = (float)ally  / total;
        localEnemyRatio  = (float)enemy / total;
        float emptyRatio = (float)empty / total;

        if (gamePhase == PHASE_BLITZKRIEG) {
            if (localCoverage < BLITZ_EXIT_COVERAGE) {
                gamePhase = PHASE_EXPAND; isBlitzkrieg = false;
            }
        } else {
            if (turnCount > BLITZ_MIN_ROUND
                && localCoverage >= BLITZ_TRIGGER_COVERAGE
                && emptyRatio < 0.05f && enemy > 0) {
                gamePhase = PHASE_BLITZKRIEG; isBlitzkrieg = true;
            } else if (emptyRatio < 0.05f && enemy > 0) {
                gamePhase = PHASE_CONQUER;
            } else if (localEnemyRatio > 0.10f) {
                gamePhase = PHASE_BORDER;
            } else {
                gamePhase = PHASE_EXPAND;
            }
        }
    }

    static void detectBleed(RobotController rc, MapInfo[] nearby,
                             MapLocation myLoc) throws GameActionException {
        int currentAlly = 0;
        for (MapInfo tile : nearby)
            if (tile.isPassable() && tile.getPaint().isAlly()) currentAlly++;

        if (prevAllyCount >= 0 && (prevAllyCount - currentAlly) >= BLEED_THRESH) {
            isBleedingNow = true;
            MapLocation worst = null; int worstAdj = 0;
            for (MapInfo tile : nearby) {
                if (!isEnemyPaint(tile.getPaint())) continue;
                int adjAlly = 0;
                for (MapInfo adj : nearby)
                    if (tile.getMapLocation().distanceSquaredTo(adj.getMapLocation()) <= 2
                        && adj.getPaint().isAlly()) adjAlly++;
                if (adjAlly > worstAdj) { worstAdj = adjAlly; worst = tile.getMapLocation(); }
            }
            bleedLocation = worst != null ? worst : myLoc;
        } else {
            isBleedingNow = false; bleedLocation = null;
        }
        prevAllyCount = currentAlly;
    }

    static void updateAsymmetricCoverage(MapInfo[] nearby) {
        if (mirrorLoc == null) return;
        for (MapInfo tile : nearby) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!isEnemyPaint(tile.getPaint())) continue;
            // tile musuh terlihat → mirror-nya di sisi kita harus diprioritaskan
            int ms = getSectorId(getMirrorLoc(tile.getMapLocation()));
            if (ms >= 0) setSectorBit(mirrorPrioritySec, ms);
        }
    }

    static MapLocation getMirrorLoc(MapLocation loc) {
        switch (symType) {
            case 0: return new MapLocation(mapW-1-loc.x, mapH-1-loc.y);
            case 1: return new MapLocation(mapW-1-loc.x, loc.y);
            case 2: return new MapLocation(loc.x, mapH-1-loc.y);
            default: return new MapLocation(mapW-1-loc.x, mapH-1-loc.y);
        }
    }

    static int calcRetreatScore(int paintPct) {
        if (paintPct >= 35) return 0;
        if (curAct == ACT_BUILD_TOWER && paintPct >= 20) return 0;
        return (35 - paintPct) * 5;
    }

    static int calcCombatScore(RobotController rc, RobotInfo[] enemies,
                                RobotInfo[] allies, int paintPct)
            throws GameActionException {
        if (enemies.length == 0 || paintPct < 20) return 0;
        int score = enemies.length * 20;
        int ac = 0;
        for (RobotInfo a : allies)
            if (a.type==UnitType.SOLDIER||a.type==UnitType.MOPPER) ac++;
        if (ac >= enemies.length) score += 25; else score -= 10;
        if (paintPct < 40) score -= 20;
        for (RobotInfo e : enemies)
            if (e.type.isTowerType()) { score += 50; break; }
        return Math.max(0, score);
    }

    // hanya ruin di teritori ally yang diprioritaskan
    static int calcBuildTowerScore(RobotController rc, MapInfo bestRuin,
                                    MapInfo[] nearby, int paintPct)
            throws GameActionException {
        if (bestRuin == null) {
            if (curAct == ACT_BUILD_TOWER && persistTarget != null) return 55;
            return 0;
        }
        MapLocation ruinLoc = bestRuin.getMapLocation();
        if (countRuinClaims(ruinLoc, rc.senseNearbyRobots(-1, rc.getTeam())) >= 2) return 0;
        int score = 110;
        score -= rc.getLocation().distanceSquaredTo(ruinLoc) * 2;
        score -= countEnemyPaintNear(ruinLoc, nearby) * 8;
        score += rc.getRoundNum() < 100 ? 50 : 20;
        if (paintPct < 40 && curAct != ACT_BUILD_TOWER) score -= 40;
        if (mapArea < 900) score += 35;
        if (isRuinInAllyTerritory(ruinLoc, nearby)) score += 60;
        return Math.max(0, score);
    }

    static boolean isRuinInAllyTerritory(MapLocation ruinLoc, MapInfo[] nearby) {
        for (MapInfo tile : nearby)
            if (ruinLoc.distanceSquaredTo(tile.getMapLocation()) <= 9
                && tile.getPaint().isAlly()) return true;
        return false;
    }

    static int calcMessingUpScore(RobotController rc, MapInfo bestRuin,
                                   MapInfo[] nearby, RobotInfo[] allies)
            throws GameActionException {
        if (bestRuin == null) return 0;
        int ep = countEnemyPaintNear(bestRuin.getMapLocation(), nearby);
        if (ep < 4) return 0;
        int score = ep * 10;
        for (RobotInfo a : allies)
            if (a.type == UnitType.MOPPER
                && a.location.distanceSquaredTo(bestRuin.getMapLocation()) <= 16)
                { score -= 40; break; }
        return Math.max(0, score);
    }

    static int calcExpandScore(RobotController rc, MapInfo[] nearby,
                                MapLocation myLoc) throws GameActionException {
        int emptyCount = 0, frontierCount = 0;
        for (MapInfo tile : nearby) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!tile.isPassable()) continue;
            if (tile.getPaint() == PaintType.EMPTY) emptyCount++;
            if (tile.getPaint().isAlly()) {
                for (Direction dir : DIRS) {
                    try {
                        MapInfo adj = rc.senseMapInfo(tile.getMapLocation().add(dir));
                        if (adj.isPassable() && !adj.getPaint().isAlly()) { frontierCount++; break; }
                    } catch (GameActionException e) {}
                }
            }
        }
        int score = emptyCount * 4 + frontierCount * 2;
        score += mapArea > 1600 ? 40 : mapArea > 900 ? 20 : 8;
        score += turnCount / 6;
        int sec = getSectorId(myLoc);
        if (sec >= 0 && isSectorBitSet(mirrorPrioritySec, sec)) score += 25;
        if (sec >= 0 && isSectorBitSet(exhaustedSectors, sec)) score += 35;
        if (emptyCount < 2 && frontierCount < 2) score -= 20;
        return Math.max(0, score);
    }

    static int calcSrpScore(RobotController rc, MapInfo[] nearby)
            throws GameActionException {
        if (rc.getRoundNum() < 60 || rc.getChips() < 200) return 0;
        int score = 15;
        if (rc.getChips() > 500)    score += 25;
        if (rc.getRoundNum() > 120) score += 20;
        int ec = 0;
        for (MapInfo t : nearby)
            if (t.isPassable() && t.getPaint() == PaintType.EMPTY) ec++;
        if (ec > 12) score -= 35;
        boolean hr = false;
        for (MapInfo t : nearby) if (t.hasRuin()) { hr = true; break; }
        if (!hr) score += 25;
        return Math.max(0, score);
    }

    static int calcBlitzScore(RobotInfo[] enemies, MapInfo[] nearby) {
        if (gamePhase != PHASE_BLITZKRIEG && gamePhase != PHASE_CONQUER) return 0;
        if (gamePhase == PHASE_BLITZKRIEG) return 150;
        return Math.min(120, enemies.length * 25 + 50);
    }

    static void doRetreat(RobotController rc) throws GameActionException {
        RobotInfo tower = findNearestTower(rc);
        if (tower != null) {
            int dist = rc.getLocation().distanceSquaredTo(tower.location);
            if (dist <= 2 && rc.isActionReady()) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                if (needed > 0 && rc.canTransferPaint(tower.location, -needed))
                    rc.transferPaint(tower.location, -needed);
            }
            if (rc.isMovementReady() && dist > 2) moveToward(rc, tower.location);
        } else moveTowardAllyPaint(rc);
    }

    static void doCombat(RobotController rc, RobotInfo[] enemies,
                          MapInfo[] nearby) throws GameActionException {
        if (rc.isActionReady()) attackBestTarget(rc, enemies);
        if (rc.isMovementReady()) {
            RobotInfo wk = null;
            for (RobotInfo e : enemies) if (wk==null||e.health<wk.health) wk = e;
            if (wk != null && !rc.canAttack(wk.location)) moveToward(rc, wk.location);
            else paintUnderSelf(rc);
        }
        if (rc.isActionReady()) {
            RobotInfo[] post = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (post.length > 0) attackBestTarget(rc, post);
            else paintWhileMoving(rc, null, rc.senseNearbyMapInfos());
        }
    }

    static void attackBestTarget(RobotController rc, RobotInfo[] enemies)
            throws GameActionException {
        if (!rc.isActionReady() || enemies.length == 0) return;
        RobotInfo best = null; int bs = Integer.MIN_VALUE;
        for (RobotInfo e : enemies) {
            if (!rc.canAttack(e.location)) continue;
            int s = 1000 - e.health;
            if (e.health <= rc.getType().attackStrength) s += 500;
            if (e.type.isTowerType())           s += 300;
            else if (e.type == UnitType.SOLDIER)  s += 100;
            if (s > bs) { bs = s; best = e; }
        }
        if (best != null) rc.attack(best.location);
    }

    // attack→move→attack untuk cat pola + complete; abandon jika macet >15 turn
    static void doBuildTower(RobotController rc, MapLocation ruinLoc,
                              MapInfo[] nearby) throws GameActionException {
        UnitType towerType = chooseTower(rc);

        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            boolean marked = false;
            for (MapInfo m : nearby) if (m.getMark()!=PaintType.EMPTY) { marked=true; break; }
            if (!marked) rc.markTowerPattern(towerType, ruinLoc);
        }

        int unpainted = countUnpainted(rc, ruinLoc);
        if (unpainted >= ruinLastUnpainted && unpainted > 0) ruinProgressTimer++;
        else ruinProgressTimer = 0;
        ruinLastUnpainted = unpainted;

        if (ruinProgressTimer > 15 && unpainted > 0) {
            for (RobotInfo a : rc.senseNearbyRobots(ruinLoc, 16, rc.getTeam())) {
                if (a.type==UnitType.SOLDIER
                    && a.location.distanceSquaredTo(ruinLoc)
                       < rc.getLocation().distanceSquaredTo(ruinLoc)) {
                    persistTarget=null; ruinProgressTimer=0; ruinLastUnpainted=999;
                    curAct = ACT_FRONTIER_EXPAND; return;
                }
            }
        }

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            rc.setTimelineMarker("Tower!", 0, 255, 0);
            queueSrpAround(ruinLoc); releaseRuinClaim(ruinLoc);
            persistTarget=null; ruinProgressTimer=0; ruinLastUnpainted=999;
            curAct = ACT_FRONTIER_EXPAND; return;
        }

        if (rc.isActionReady()) {
            MapLocation t = findClosestUnpainted(rc, ruinLoc);
            if (t != null) {
                boolean sec = rc.senseMapInfo(t).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(t, sec); paintedInSector++;
            } else paintWhileMoving(rc, ruinLoc, nearby);
        }
        if (rc.isMovementReady()) moveToward(rc, ruinLoc);
        if (rc.isActionReady()) {
            MapLocation t = findClosestUnpainted(rc, ruinLoc);
            if (t != null) {
                boolean sec = rc.senseMapInfo(t).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(t, sec); paintedInSector++;
            }
            if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
                rc.completeTowerPattern(towerType, ruinLoc);
                rc.setTimelineMarker("Tower!", 0, 255, 0);
                queueSrpAround(ruinLoc); releaseRuinClaim(ruinLoc);
                persistTarget=null; ruinProgressTimer=0; ruinLastUnpainted=999;
                curAct = ACT_FRONTIER_EXPAND;
            }
        }
        sendMsg(rc, MSG_RUIN | (ruinLoc.x<<15) | ruinLoc.y);
    }

    static void doMessingUp(RobotController rc, MapLocation ruinLoc,
                             MapInfo[] nearby) throws GameActionException {
        if (rc.isActionReady()) {
            MapLocation best = null; int bd = Integer.MAX_VALUE;
            for (MapInfo tile : nearby) {
                if (!isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;
                int d = ruinLoc.distanceSquaredTo(tile.getMapLocation());
                if (d < bd) { bd = d; best = tile.getMapLocation(); }
            }
            if (best != null) rc.attack(best);
        }
        if (rc.isMovementReady()) moveToward(rc, ruinLoc);
    }

    static void doBleedRespond(RobotController rc, MapInfo[] nearby)
            throws GameActionException {
        if (bleedLocation == null) { curAct = ACT_FRONTIER_EXPAND; return; }
        if (rc.isActionReady()) {
            MapLocation best = null; int bd = Integer.MAX_VALUE;
            for (MapInfo tile : nearby) {
                if (!isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;
                int d = bleedLocation.distanceSquaredTo(tile.getMapLocation());
                if (d < bd) { bd = d; best = tile.getMapLocation(); }
            }
            if (best != null) { rc.attack(best); paintedInSector++; }
        }
        if (rc.isMovementReady()) moveToward(rc, bleedLocation);
        if (rc.isActionReady()) {
            for (MapInfo tile : rc.senseNearbyMapInfos()) {
                if (!isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;
                rc.attack(tile.getMapLocation()); break;
            }
        }
    }

    static void doFrontierExpand(RobotController rc, MapInfo[] nearby,
                                  MapLocation myLoc) throws GameActionException {
        int cs = getSectorId(myLoc);
        boolean needNew = (sectorTarget == null)
            || (cs >= 0 && isSectorBitSet(exhaustedSectors, cs))
            || sectorTargetAge > 25;
        if (needNew) { sectorTarget = findBestUnvisitedSector(rc, myLoc); sectorTargetAge = 0; }
        sectorTargetAge++;

        if (rc.isActionReady()) { int p = greedyPaintFrontier(rc, nearby); if (p>0) paintedInSector++; }

        MapLocation ft = findBestFrontierTarget(rc, nearby, myLoc);
        if (rc.isMovementReady()) {
            if (ft != null && myLoc.distanceSquaredTo(ft) > 2) moveToward(rc, ft);
            else if (sectorTarget != null) moveToward(rc, sectorTarget);
            else moveExplore(rc, myLoc);
        }

        if (rc.isActionReady()) {
            int p = greedyPaintFrontier(rc, rc.senseNearbyMapInfos()); if (p>0) paintedInSector++;
        }
        if ((gamePhase==PHASE_BORDER||gamePhase==PHASE_CONQUER) && rc.isActionReady())
            aggressivePushEnemy(rc);
    }

    static void doBlitzkrieg(RobotController rc, MapInfo[] nearby,
                              MapLocation myLoc) throws GameActionException {
        MapLocation blitzTarget = confirmedEnemy != null ? confirmedEnemy : mirrorLoc;

        if (rc.isActionReady()) {
            MapLocation best = null; int bs = Integer.MIN_VALUE;
            for (MapInfo m : nearby) {
                if (!rc.canAttack(m.getMapLocation())) continue;
                PaintType p = m.getPaint();
                int score = isEnemyPaint(p) ? 150 : p==PaintType.EMPTY ? 30 : -10;
                if (score > bs) { bs = score; best = m.getMapLocation(); }
            }
            if (best != null && bs > 0) rc.attack(best);
        }
        if (rc.isMovementReady()) {
            if (blitzTarget != null) moveToward(rc, blitzTarget);
            else moveExplore(rc, myLoc);
        }
        if (rc.isActionReady()) {
            RobotInfo[] en = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (en.length > 0) attackBestTarget(rc, en);
            else for (MapInfo m : rc.senseNearbyMapInfos()) {
                if (!rc.canAttack(m.getMapLocation())) continue;
                if (isEnemyPaint(m.getPaint())) { rc.attack(m.getMapLocation()); break; }
            }
        }
    }

    static void runSrpDuty(RobotController rc, MapInfo[] nearby, int paintPct,
                            MapLocation myLoc) throws GameActionException {
        if (paintPct < 25) { doRetreat(rc); return; }
        MapLocation st = nextSrpTarget(rc, nearby);
        if (st != null) { persistTarget = st; doBuildSrp(rc, st, nearby); }
        else { moveExplore(rc, myLoc); isSrpDuty = false; curAct = ACT_FRONTIER_EXPAND; }
    }

    static void aggressivePushEnemy(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        MapInfo[] nb = rc.senseNearbyMapInfos();
        MapLocation best = null; int bs = Integer.MIN_VALUE;
        for (MapInfo m : nb) {
            if (!rc.canAttack(m.getMapLocation()) || !isEnemyPaint(m.getPaint())) continue;
            int score = 100;
            int aa = 0;
            for (MapInfo adj : nb)
                if (m.getMapLocation().distanceSquaredTo(adj.getMapLocation()) <= 2
                    && adj.getPaint().isAlly()) aa++;
            score += aa * 15;
            if (score > bs) { bs = score; best = m.getMapLocation(); }
        }
        if (best != null) rc.attack(best);
    }

    static MapLocation findBestUnvisitedSector(RobotController rc, MapLocation myLoc) {
        if (secGridW < 0) return null;
        MapLocation best = null; int bs = Integer.MIN_VALUE;
        for (int sy = 0; sy < secGridH; sy++) {
            for (int sx = 0; sx < secGridW; sx++) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                int secId = sy*secGridW+sx;
                if (isSectorBitSet(exhaustedSectors, secId)) continue;
                if (isSectorBitSet(towerExhSectors, secId)) continue;

                int cx = Math.min(mapW-1, sx*SECTOR_SIZE+SECTOR_SIZE/2);
                int cy = Math.min(mapH-1, sy*SECTOR_SIZE+SECTOR_SIZE/2);
                MapLocation center = new MapLocation(cx, cy);

                int score = 50;
                if (!isSectorBitSet(visitedSectors, secId)) score += 40;
                score -= myLoc.distanceSquaredTo(center) / 8;
                if (isSectorBitSet(mirrorPrioritySec, secId)) score += 35;

                MapLocation et = confirmedEnemy != null ? confirmedEnemy : mirrorLoc;
                if (et != null) score -= center.distanceSquaredTo(et) / 15;
                if (spawnTowerLoc != null) score += center.distanceSquaredTo(spawnTowerLoc) / 20;

                int[][] adj = {{-1,0},{1,0},{0,-1},{0,1}};
                for (int[] off : adj) {
                    int asx = sx+off[0], asy = sy+off[1];
                    if (asx<0||asx>=secGridW||asy<0||asy>=secGridH) continue;
                    if (isSectorBitSet(visitedSectors, asy*secGridW+asx)) { score += 25; break; }
                }
                if (gamePhase == PHASE_BLITZKRIEG && et != null)
                    score += 30 - Math.min(30, center.distanceSquaredTo(et) / 4);

                if (score > bs) { bs = score; best = center; targetSectorId = secId; }
            }
        }
        return best;
    }

    static MapLocation findBestFrontierTarget(RobotController rc, MapInfo[] nearby,
                                               MapLocation myLoc) throws GameActionException {
        MapLocation best = null; int bs = Integer.MIN_VALUE;
        for (MapInfo tile : nearby) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!tile.isPassable()) continue;
            PaintType p = tile.getPaint();
            if (p.isAlly()) continue;

            MapLocation loc = tile.getMapLocation();
            boolean isFrontier = false;
            for (Direction dir : DIRS) {
                try {
                    if (rc.senseMapInfo(loc.add(dir)).getPaint().isAlly()) { isFrontier = true; break; }
                } catch (GameActionException e) {}
            }

            int score = isFrontier ? 30 : 0;

            if (gamePhase == PHASE_BLITZKRIEG)    score += isEnemyPaint(p) ? 80 : 15;
            else if (gamePhase == PHASE_CONQUER)  score += isEnemyPaint(p) ? 60 : 10;
            else if (gamePhase == PHASE_BORDER)   score += isEnemyPaint(p) ? 40 : 20;
            else                                  score += p==PaintType.EMPTY ? 20 : 10;

            int secId = getSectorId(loc);
            if (secId >= 0 && isSectorBitSet(mirrorPrioritySec, secId)) score += 20;
            if (spawnTowerLoc != null) score += loc.distanceSquaredTo(spawnTowerLoc) / 15;
            MapLocation et = confirmedEnemy != null ? confirmedEnemy : mirrorLoc;
            if (et != null) score -= loc.distanceSquaredTo(et) / 20;
            score -= recentVisitPenalty(loc);

            int ce = 0;
            for (MapInfo m : nearby)
                if (loc.distanceSquaredTo(m.getMapLocation()) <= 4
                    && m.isPassable() && m.getPaint() == PaintType.EMPTY) ce++;
            score += ce * 3;
            if (secId >= 0 && !isSectorBitSet(visitedSectors, secId)) score += 20;
            if (secId >= 0 && isSectorBitSet(exhaustedSectors, secId)) score -= 30;

            if (score > bs) { bs = score; best = loc; }
        }
        return best;
    }

    static int greedyPaintFrontier(RobotController rc, MapInfo[] nearby)
            throws GameActionException {
        if (!rc.isActionReady()) return 0;
        MapLocation best = null; int bs = Integer.MIN_VALUE;
        for (MapInfo m : nearby) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!rc.canAttack(m.getMapLocation())) continue;
            PaintType p = m.getPaint();
            if (p.isAlly()) continue;

            int score = isEnemyPaint(p)
                ? (gamePhase==PHASE_BLITZKRIEG?160:gamePhase==PHASE_CONQUER?120:gamePhase==PHASE_BORDER?100:80)
                : 50;

            if (spawnTowerLoc != null) score += m.getMapLocation().distanceSquaredTo(spawnTowerLoc) / 20;
            int secId = getSectorId(m.getMapLocation());
            if (secId >= 0 && isSectorBitSet(mirrorPrioritySec, secId)) score += 25;
            int ne = 0;
            for (MapInfo adj : nearby)
                if (m.getMapLocation().distanceSquaredTo(adj.getMapLocation()) <= 2
                    && adj.isPassable() && adj.getPaint() == PaintType.EMPTY) ne++;
            score += ne * 5;
            if (score > bs) { bs = score; best = m.getMapLocation(); }
        }
        if (best != null) { rc.attack(best); return 1; }
        return 0;
    }

    static void paintWhileMoving(RobotController rc, MapLocation target,
                                  MapInfo[] nearby) throws GameActionException {
        if (!rc.isActionReady()) return;
        greedyPaintFrontier(rc, nearby);
    }

    static void doBuildSrp(RobotController rc, MapLocation target,
                            MapInfo[] nearby) throws GameActionException {
        if (!isSrpValid(rc, target)) { disqualifySrp(target); persistTarget = null; return; }
        if (rc.canMarkResourcePattern(target)) rc.markResourcePattern(target);
        if (rc.isActionReady()) {
            MapLocation t = findClosestUnpainted(rc, target);
            if (t != null) {
                boolean sec = rc.senseMapInfo(t).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(t, sec); paintedInSector++;
            } else paintWhileMoving(rc, target, nearby);
        }
        if (rc.isMovementReady()) moveToward(rc, target);
        if (rc.isActionReady()) {
            MapLocation t = findClosestUnpainted(rc, target);
            if (t != null) {
                boolean sec = rc.senseMapInfo(t).getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(t, sec); paintedInSector++;
            }
        }
        if (rc.canCompleteResourcePattern(target)) {
            rc.completeResourcePattern(target);
            rc.setTimelineMarker("SRP!", 0, 200, 255);
            queueSrpExpansion(target); persistTarget = null;
            if (isSrpDuty) isSrpDuty = false;
        }
    }

    // =========================================================================
    // MOPPER
    // =========================================================================
    public static void runMopper(RobotController rc) throws GameActionException {
        if (!symInit) { initSymmetry(rc); symInit = true; }

        MapLocation myLoc   = rc.getLocation();
        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapInfo[]   near2   = rc.senseNearbyMapInfos(myLoc, 2);
        MapInfo[]   nearby  = rc.senseNearbyMapInfos();

        updateCoverageAndPhase(nearby);
        detectBleed(rc, nearby, myLoc);

        if (rc.isActionReady()) {
            RobotInfo bt = null; int tfGain = 0;
            for (RobotInfo ally : allies) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                if (myLoc.distanceSquaredTo(ally.location) > 2) continue;
                int pct = (int)(100.0*ally.paintAmount/ally.type.paintCapacity);
                if (pct >= 50) continue;
                int give = Math.min(rc.getPaint()-10, ally.type.paintCapacity-ally.paintAmount);
                if (give <= 0) continue;
                int gain = (pct<20?give*2:give) + (ally.type==UnitType.SOLDIER?20:0);
                if (gain > tfGain) { tfGain = gain; bt = ally; }
            }
            Direction bs = null; int swGain = 0;
            for (Direction dir : CARDINAL) {
                if (!rc.canMopSwing(dir)) continue;
                int gain = countSwingHits(rc, dir, enemies) * 30;
                for (MapInfo t : near2) if (t.hasRuin()) gain += 15;
                if (gain > swGain) { swGain = gain; bs = dir; }
            }
            MapLocation bm = null; int mopGain = 0;
            for (MapInfo tile : near2) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                if (!isEnemyPaint(tile.getPaint()) || !rc.canAttack(tile.getMapLocation())) continue;
                int gain = 15;
                try {
                    RobotInfo on = rc.senseRobotAtLocation(tile.getMapLocation());
                    if (on != null && on.team != rc.getTeam()) gain += 20;
                } catch (GameActionException e) {}
                for (MapInfo near : nearby)
                    if (near.hasRuin() && tile.getMapLocation()
                        .distanceSquaredTo(near.getMapLocation()) <= 8) gain += 15;
                if (gain > mopGain) { mopGain = gain; bm = tile.getMapLocation(); }
            }
            if (gamePhase==PHASE_CONQUER||gamePhase==PHASE_BLITZKRIEG) mopGain = (int)(mopGain*1.5);

            if (swGain >= mopGain && swGain >= tfGain && bs != null) rc.mopSwing(bs);
            else if (mopGain >= tfGain && bm != null) rc.attack(bm);
            else if (tfGain > 0 && bt != null) {
                int give = Math.min(rc.getPaint()-10, bt.type.paintCapacity-bt.paintAmount);
                if (give > 0 && rc.canTransferPaint(bt.location, give))
                    rc.transferPaint(bt.location, give);
            }
        }

        greedyMoveMopper(rc, allies, enemies, nearby);
    }

    static void greedyMoveMopper(RobotController rc, RobotInfo[] allies,
                                  RobotInfo[] enemies, MapInfo[] nearby)
            throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation myLoc = rc.getLocation();

        if (gamePhase==PHASE_BLITZKRIEG || gamePhase==PHASE_CONQUER) {
            if (enemies.length > 0) {
                RobotInfo cl = null; int cd = Integer.MAX_VALUE;
                for (RobotInfo e : enemies) { int d = myLoc.distanceSquaredTo(e.location); if (d<cd){cd=d;cl=e;} }
                if (cl != null && cd > 2) { moveToward(rc, cl.location); return; }
            }
            MapLocation et = confirmedEnemy != null ? confirmedEnemy : mirrorLoc;
            if (et != null) { moveToward(rc, et); return; }
        }

        if (isBleedingNow && bleedLocation != null && rc.getPaint() > 20) {
            moveToward(rc, bleedLocation); return;
        }

        RobotInfo ls = null; int lp = 40;
        for (RobotInfo ally : allies) {
            if (ally.type != UnitType.SOLDIER) continue;
            int pct = (int)(100.0*ally.paintAmount/ally.type.paintCapacity);
            if (pct < lp && rc.getPaint() > 20) { lp = pct; ls = ally; }
        }
        if (ls != null) { moveToward(rc, ls.location); return; }

        Direction bd = null; int bsc = Integer.MIN_VALUE;
        for (Direction dir : DIRS) {
            if (!rc.canMove(dir)) continue;
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            MapLocation next = myLoc.add(dir);
            int score = 0;
            for (MapInfo tile : nearby) {
                if (!isEnemyPaint(tile.getPaint())) continue;
                int d = next.distanceSquaredTo(tile.getMapLocation());
                if (d<=2) score += (gamePhase==PHASE_BLITZKRIEG?50:25);
                else if (d<=9) score += 8;
            }
            for (MapInfo tile : nearby) {
                if (!tile.hasRuin()) continue;
                try {
                    RobotInfo at = rc.senseRobotAtLocation(tile.getMapLocation());
                    if (at==null||!at.type.isTowerType())
                        if (next.distanceSquaredTo(tile.getMapLocation()) <= 16) score += 20;
                } catch (GameActionException e) {}
            }
            try {
                PaintType np = rc.senseMapInfo(next).getPaint();
                if (isEnemyPaint(np)) score += 15;
                else if (np==PaintType.EMPTY) score += 4;
            } catch (GameActionException e) {}
            for (RobotInfo ally : allies) {
                if (ally.type != UnitType.SOLDIER) continue;
                int pct  = (int)(100.0*ally.paintAmount/ally.type.paintCapacity);
                int give = Math.min(rc.getPaint()-10, ally.type.paintCapacity-ally.paintAmount);
                if (pct < 50 && next.distanceSquaredTo(ally.location) <= 2 && give > 0)
                    score += pct < 20 ? give*3 : give;
            }
            if (spawnTowerLoc != null && enemies.length == 0)
                if (next.distanceSquaredTo(spawnTowerLoc) > myLoc.distanceSquaredTo(spawnTowerLoc)) score += 9;
            int secId = getSectorId(next);
            if (secId>=0 && !isSectorBitSet(visitedSectors, secId)) score += 15;
            if (secId>=0 && isSectorBitSet(exhaustedSectors, secId)) score -= 20;
            if (secId>=0 && isSectorBitSet(mirrorPrioritySec, secId)) score += 15;
            score -= recentVisitPenalty(next) / 2;
            score -= rc.senseNearbyRobots(next, 2, rc.getTeam()).length * 3;
            if (score > bsc) { bsc = score; bd = dir; }
        }
        if (bd != null) rc.move(bd);
    }

    // =========================================================================
    // SPLASHER
    // =========================================================================
    public static void runSplasher(RobotController rc) throws GameActionException {
        if (!symInit) { initSymmetry(rc); symInit = true; }

        MapLocation myLoc   = rc.getLocation();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapInfo[]   nearby  = rc.senseNearbyMapInfos();
        int paintPct = (int)(100.0*rc.getPaint()/rc.getType().paintCapacity);

        updateCoverageAndPhase(nearby);

        if (paintPct < 30) { splasherRetreat(rc); return; }

        int minGain = gamePhase==PHASE_BLITZKRIEG ? 1 : (paintPct<60||enemies.length>0) ? 2 : 1;

        boolean attacked = false;
        if (rc.isActionReady()) {
            MapLocation best = findBestSplashCenter(rc, myLoc, enemies, minGain);
            if (best != null) { rc.attack(best); attacked = true; }
        }
        if (rc.isMovementReady()) greedyMoveSplasher(rc, myLoc, enemies, nearby);
        if (!attacked && rc.isActionReady()) {
            MapLocation best = findBestSplashCenter(rc, rc.getLocation(), enemies, minGain);
            if (best != null) rc.attack(best);
        }
        paintUnderSelf(rc);
    }

    static MapLocation findBestSplashCenter(RobotController rc, MapLocation myLoc,
                                             RobotInfo[] enemies, int minGain)
            throws GameActionException {
        MapLocation best = null; int bg = minGain - 1;
        for (MapInfo c : rc.senseNearbyMapInfos(myLoc, 4)) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!rc.canAttack(c.getMapLocation())) continue;
            int gain = calcSplashGain(rc, c.getMapLocation());
            for (RobotInfo e : enemies)
                if (c.getMapLocation().distanceSquaredTo(e.location) <= 8) gain += 5;
            if (gamePhase==PHASE_BLITZKRIEG||gamePhase==PHASE_CONQUER) gain = (int)(gain*1.5);
            if (gain > bg) { bg = gain; best = c.getMapLocation(); }
        }
        return best;
    }

    static void greedyMoveSplasher(RobotController rc, MapLocation myLoc,
                                    RobotInfo[] enemies, MapInfo[] nearby)
            throws GameActionException {
        Direction bd = null; int bsc = Integer.MIN_VALUE;
        for (Direction dir : DIRS) {
            if (!rc.canMove(dir)) continue;
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            MapLocation next = myLoc.add(dir);
            int score = 0;
            try {
                PaintType np = rc.senseMapInfo(next).getPaint();
                if (isEnemyPaint(np)) score += (gamePhase==PHASE_BLITZKRIEG?25:12);
                else if (np==PaintType.EMPTY) score += 8;
                else score -= 3;
            } catch (GameActionException e) {}
            int mg = 0;
            for (MapInfo c : rc.senseNearbyMapInfos(next, 4)) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                if (next.distanceSquaredTo(c.getMapLocation()) > 4) continue;
                int g = calcSplashGain(rc, c.getMapLocation());
                if (gamePhase==PHASE_BLITZKRIEG) g = (int)(g*1.5);
                if (g > mg) mg = g;
            }
            score += mg * 3;
            int en = 0;
            for (MapInfo tile : nearby)
                if (tile.isPassable() && tile.getPaint()==PaintType.EMPTY
                    && next.distanceSquaredTo(tile.getMapLocation()) <= 9) en++;
            score += en / (gamePhase==PHASE_BLITZKRIEG?3:2);
            for (RobotInfo e : enemies)
                if (next.distanceSquaredTo(e.location) < myLoc.distanceSquaredTo(e.location))
                    score += rc.getPaint()>100 ? (gamePhase==PHASE_BLITZKRIEG?20:10) : 5;
            if (spawnTowerLoc != null)
                if (next.distanceSquaredTo(spawnTowerLoc) > myLoc.distanceSquaredTo(spawnTowerLoc))
                    score += enemies.length==0 ? 12 : 6;
            MapLocation et = confirmedEnemy != null ? confirmedEnemy : mirrorLoc;
            if (et != null && next.distanceSquaredTo(et) < myLoc.distanceSquaredTo(et))
                score += (gamePhase==PHASE_BLITZKRIEG?25:gamePhase==PHASE_BORDER?15:8);
            int secId = getSectorId(next);
            if (secId>=0 && !isSectorBitSet(visitedSectors, secId)) score += 20;
            if (secId>=0 && isSectorBitSet(exhaustedSectors, secId)) score -= 25;
            if (secId>=0 && isSectorBitSet(mirrorPrioritySec, secId)) score += 20;
            score -= recentVisitPenalty(next) / 3;
            score -= rc.senseNearbyRobots(next, 2, rc.getTeam()).length * 4;
            score += rng.nextInt(3);
            if (score > bsc) { bsc = score; bd = dir; }
        }
        if (bd != null) rc.move(bd);
    }

    static int calcSplashGain(RobotController rc, MapLocation center)
            throws GameActionException {
        int gain = 0;
        for (MapInfo tile : rc.senseNearbyMapInfos(center, 4)) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!tile.isPassable()) continue;
            PaintType p = tile.getPaint();
            int d = center.distanceSquaredTo(tile.getMapLocation());
            if (isEnemyPaint(p)) gain += d<=2 ? 4 : 2;
            else if (p==PaintType.EMPTY) gain += d<=1 ? 3 : 1;
        }
        return gain;
    }

    static void splasherRetreat(RobotController rc) throws GameActionException {
        RobotInfo tower = findNearestTower(rc);
        if (tower != null) {
            int dist = rc.getLocation().distanceSquaredTo(tower.location);
            if (dist <= 2 && rc.isActionReady()) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                if (needed > 0 && rc.canTransferPaint(tower.location, -needed))
                    rc.transferPaint(tower.location, -needed);
            }
            if (rc.isMovementReady() && dist > 2) moveToward(rc, tower.location);
        } else moveTowardAllyPaint(rc);
        paintUnderSelf(rc);
    }

    // =========================================================================
    // SECTOR HELPERS
    // =========================================================================
    static void updateSectorState(RobotController rc, MapLocation myLoc,
                                   MapInfo[] nearby) throws GameActionException {
        int sid = getSectorId(myLoc);
        if (sid < 0) return;
        setSectorBit(visitedSectors, sid);
        if (sid != lastSectorId) { lastSectorId = sid; turnsInSector = 0; paintedInSector = 0; }
        else turnsInSector++;

        int emptyIn = 0, wallIn = 0;
        for (MapInfo tile : nearby) {
            if (!tile.isPassable()) wallIn++;
            else if (tile.getPaint() == PaintType.EMPTY) emptyIn++;
        }
        // wall >22 atau empty habis → exhausted segera
        boolean full    = emptyIn==0 && gamePhase!=PHASE_CONQUER && gamePhase!=PHASE_BLITZKRIEG;
        boolean walled  = wallIn > 22;
        boolean timeout = turnsInSector >= EXHAUSTED_TURNS && paintedInSector < MIN_PAINT_PROGRESS;

        if ((full||walled||timeout) && !isSectorBitSet(exhaustedSectors, sid)) {
            setSectorBit(exhaustedSectors, sid);
            sendMsg(rc, MSG_SECTOR_FLAG | (sid << 20));
            sectorTarget = null; targetSectorId = -1; sectorTargetAge = 0;
        }
        try { if (rc.senseMapInfo(myLoc).getPaint().isAlly()) paintedInSector++; }
        catch (GameActionException e) {}
    }

    static int getSectorId(MapLocation loc) {
        if (secGridW < 0 || loc == null) return -1;
        int sx = loc.x/SECTOR_SIZE, sy = loc.y/SECTOR_SIZE;
        if (sx >= secGridW || sy >= secGridH) return -1;
        return sy*secGridW+sx;
    }

    static void setSectorBit(long[] b, int id) {
        if (id<0||id>=maxSectors||id>=b.length*64) return;
        b[id/64] |= (1L<<(id%64));
    }

    static boolean isSectorBitSet(long[] b, int id) {
        if (id<0||id>=maxSectors||id>=b.length*64) return false;
        return (b[id/64]&(1L<<(id%64))) != 0;
    }

    // =========================================================================
    // RUIN HELPERS
    // =========================================================================
    // skip ruin di luar teritori saat EXPAND; bonus +60 jika dalam teritori
    static MapInfo findBestAllyTerritoryRuin(RobotController rc, MapInfo[] nearby)
            throws GameActionException {
        MapInfo best = null; int bs = Integer.MIN_VALUE;
        for (MapInfo tile : nearby) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            try {
                RobotInfo at = rc.senseRobotAtLocation(ruinLoc);
                if (at != null && at.type.isTowerType()) continue;
            } catch (GameActionException e) {}
            if (countRuinClaims(ruinLoc, rc.senseNearbyRobots(-1, rc.getTeam())) >= 2) continue;
            boolean inT = isRuinInAllyTerritory(ruinLoc, nearby);
            if (!inT && gamePhase == PHASE_EXPAND) continue;
            int dist = rc.getLocation().distanceSquaredTo(ruinLoc);
            int up   = countUnpainted(rc, ruinLoc);
            int score = -dist - up*3;
            if (inT) score += 60;
            if (mirrorLoc != null && ruinLoc.distanceSquaredTo(mirrorLoc) < 50) score += 20;
            if (mapW > 0) score += Math.max(0, 30-ruinLoc.distanceSquaredTo(
                new MapLocation(mapW/2, mapH/2)) / 4);
            if (score > bs) { bs = score; best = tile; }
        }
        return best;
    }

    static int countUnpainted(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        boolean hm = false; int count = 0;
        MapInfo[] tiles = rc.senseNearbyMapInfos(ruinLoc, 8);
        for (MapInfo m : tiles) if (m.getMark()!=PaintType.EMPTY) { hm = true; break; }
        if (hm) {
            for (MapInfo m : tiles) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                if (m.getMark()!=PaintType.EMPTY && m.getMark()!=m.getPaint()) count++;
            }
        } else {
            for (MapInfo m : tiles) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                if (m.isPassable() && !m.getPaint().isAlly()) count++;
            }
        }
        return count;
    }

    static MapLocation findClosestUnpainted(RobotController rc, MapLocation center)
            throws GameActionException {
        MapLocation myLoc = rc.getLocation(), best = null; int bd = Integer.MAX_VALUE;
        for (MapInfo pat : rc.senseNearbyMapInfos(center, 8)) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (pat.getMark()==PaintType.EMPTY || pat.getMark()==pat.getPaint()) continue;
            if (!rc.canAttack(pat.getMapLocation())) continue;
            int d = myLoc.distanceSquaredTo(pat.getMapLocation());
            if (d < bd) { bd = d; best = pat.getMapLocation(); }
        }
        return best;
    }

    static int countEnemyPaintNear(MapLocation ruinLoc, MapInfo[] nearby) {
        int count = 0;
        for (MapInfo tile : nearby)
            if (ruinLoc.distanceSquaredTo(tile.getMapLocation()) <= 12
                && isEnemyPaint(tile.getPaint())) count++;
        return count;
    }

    static void claimRuin(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        sendMsg(rc, MSG_RUIN_CLAIMED | (ruinLoc.x<<14) | ruinLoc.y);
        addClaimedRuin(ruinLoc);
    }

    static void addClaimedRuin(MapLocation loc) {
        for (int i=0;i<claimedCount;i++) if (claimedRuins[i]!=null&&claimedRuins[i].equals(loc)) return;
        if (claimedCount < claimedRuins.length) claimedRuins[claimedCount++] = loc;
    }

    static void releaseRuinClaim(MapLocation loc) {
        for (int i=0;i<claimedCount;i++) if (claimedRuins[i]!=null&&claimedRuins[i].equals(loc)) {
            claimedRuins[i] = claimedRuins[--claimedCount]; claimedRuins[claimedCount] = null; return;
        }
    }

    static boolean isRuinClaimed(MapLocation loc) {
        for (int i=0;i<claimedCount;i++) if (claimedRuins[i]!=null&&claimedRuins[i].equals(loc)) return true;
        return false;
    }

    static int countRuinClaims(MapLocation ruinLoc, RobotInfo[] allies) {
        int count = 0;
        for (RobotInfo ally : allies)
            if (ally.type==UnitType.SOLDIER && ally.location.distanceSquaredTo(ruinLoc) <= 25) count++;
        return count;
    }

    static UnitType chooseTower(RobotController rc) throws GameActionException {
        int pt=0, mt=0, dt=0;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (isPaintTower(ally.type)) pt++;
            else if (isMoneyTower(ally.type)) mt++;
            else if (isDefenseTower(ally.type)) dt++;
        }
        int enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length;
        int mv = mt==0 ? 90 : Math.max(0, 50-mt*10);
        if (rc.getNumberTowers() < 3) mv += 20;
        int pv = pt==0 ? 80 : Math.max(0, 45-pt*10);
        if (rc.getNumberTowers() >= 3 && pt <= mt) pv += 20;
        int dv = enemies>=3&&dt==0 ? 90 : enemies>=2 ? 40+enemies*5 : Math.max(0, 15-dt*10);
        if (mapW>0 && rc.getLocation().distanceSquaredTo(
            new MapLocation(mapW/2, mapH/2)) < 35 && enemies > 0) dv += 25;
        if (mv >= pv && mv >= dv) return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (dv >= pv) return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    // =========================================================================
    // SRP HELPERS
    // =========================================================================
    static MapLocation nextSrpTarget(RobotController rc, MapInfo[] nearby)
            throws GameActionException {
        for (int i=0; i<srpQueueSize; i++) {
            int idx = (srpQueueHead+i) % srpQueue.length;
            if (srpDone[idx] || srpQueue[idx]==null) continue;
            if (isSrpValid(rc, srpQueue[idx])) return srpQueue[idx];
            srpDone[idx] = true;
        }
        for (MapInfo tile : nearby) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!tile.isPassable() || tile.hasRuin()) continue;
            MapLocation loc = tile.getMapLocation();
            if (rc.canMarkResourcePattern(loc) && isSrpValid(rc, loc)) return loc;
        }
        return null;
    }

    static boolean isSrpValid(RobotController rc, MapLocation loc)
            throws GameActionException {
        if (loc == null) return false;
        if (loc.x<2||loc.x>=mapW-2||loc.y<2||loc.y>=mapH-2) return false;
        try {
            for (MapInfo tile : rc.senseNearbyMapInfos(loc, 12)) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                if (tile.hasRuin() && loc.distanceSquaredTo(tile.getMapLocation()) <= 12) return false;
                if (!tile.isPassable() && loc.distanceSquaredTo(tile.getMapLocation()) <= 8) return false;
            }
        } catch (GameActionException e) { return false; }
        return true;
    }

    static void queueSrpExpansion(MapLocation c) {
        int[][] off = {{0,9},{9,0},{0,-9},{-9,0},{9,9},{9,-9},{-9,9},{-9,-9}};
        for (int[] o : off) addSrpQueue(new MapLocation(c.x+o[0], c.y+o[1]));
    }

    static void queueSrpAround(MapLocation t) {
        int[][] off = {{5,0},{-5,0},{0,5},{0,-5}};
        for (int[] o : off) addSrpQueue(new MapLocation(t.x+o[0], t.y+o[1]));
    }

    static void addSrpQueue(MapLocation loc) {
        if (srpQueueSize >= srpQueue.length) return;
        int idx = (srpQueueHead+srpQueueSize) % srpQueue.length;
        srpQueue[idx] = loc; srpDone[idx] = false; srpQueueSize++;
    }

    static void disqualifySrp(MapLocation loc) {
        for (int i=0;i<srpQueue.length;i++)
            if (srpQueue[i]!=null && srpQueue[i].equals(loc)) { srpDone[i] = true; return; }
    }

    // =========================================================================
    // SYMMETRY
    // =========================================================================
    static void initSymmetry(RobotController rc) throws GameActionException {
        int bd = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.type.isTowerType()) continue;
            int d = rc.getLocation().distanceSquaredTo(ally.location);
            if (d < bd) { bd = d; spawnTowerLoc = ally.location; }
        }
        if (spawnTowerLoc == null) return;
        MapLocation rot  = new MapLocation(mapW-1-spawnTowerLoc.x, mapH-1-spawnTowerLoc.y);
        MapLocation refX = new MapLocation(mapW-1-spawnTowerLoc.x, spawnTowerLoc.y);
        MapLocation refY = new MapLocation(spawnTowerLoc.x, mapH-1-spawnTowerLoc.y);
        int dR = spawnTowerLoc.distanceSquaredTo(rot);
        int dX = spawnTowerLoc.distanceSquaredTo(refX);
        int dY = spawnTowerLoc.distanceSquaredTo(refY);
        if (dR>=dX&&dR>=dY) { mirrorLoc=rot;  symType=0; }
        else if (dX>=dY)     { mirrorLoc=refX; symType=1; }
        else                 { mirrorLoc=refY; symType=2; }
    }

    static void refineSymmetry(MapLocation obs) {
        if (mirrorLoc==null || confirmedEnemy!=null) return;
        if (mirrorLoc.distanceSquaredTo(obs) <= 25) confirmedEnemy = mirrorLoc;
    }

    // =========================================================================
    // NAVIGATION
    // =========================================================================
    static MapLocation navTarget     = null;
    static boolean     wallFollowing = false;
    static boolean     followLeft    = true;
    static Direction   wallDir       = Direction.NORTH;
    static int         navFollowTurn = 0;
    static int         navStartDist  = Integer.MAX_VALUE;

    static void moveToward(RobotController rc, MapLocation target)
            throws GameActionException {
        if (!rc.isMovementReady() || target==null) return;
        MapLocation myLoc = rc.getLocation();
        if (myLoc.equals(target)) return;

        if (navTarget==null || !navTarget.equals(target)) {
            navTarget=target; wallFollowing=false;
            navFollowTurn=0; navStartDist=myLoc.distanceSquaredTo(target);
        }
        Direction toTarget = myLoc.directionTo(target);
        if (rc.canMove(toTarget)) {
            if (!wallFollowing || myLoc.distanceSquaredTo(target)<=navStartDist || navFollowTurn>14) {
                rc.move(toTarget); wallFollowing=false; navFollowTurn=0;
                navStartDist=rc.getLocation().distanceSquaredTo(target); wallDir=toTarget; return;
            }
        }
        if (!wallFollowing) {
            wallFollowing=true; navFollowTurn=0; navStartDist=myLoc.distanceSquaredTo(target);
            wallDir=toTarget; followLeft=((rc.getID()+turnCount)&1)==0;
        }
        navFollowTurn++;
        Direction obs = wallDir;
        for (int i=0; i<8; i++) {
            obs = followLeft ? obs.rotateLeft() : obs.rotateRight();
            if (rc.canMove(obs)) {
                rc.move(obs); wallDir=obs;
                if (rc.canMove(rc.getLocation().directionTo(target))
                    && rc.getLocation().distanceSquaredTo(target) < navStartDist) {
                    wallFollowing=false; navFollowTurn=0;
                }
                return;
            }
        }
        // stuck total → random escape
        if (stuckCount >= 3)
            for (Direction d : DIRS) { if (rc.canMove(d)) { rc.move(d); stuckCount=0; return; } }
    }

    static void moveTowardAllyPaint(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction bd = null; int bs = Integer.MIN_VALUE;
        for (Direction dir : DIRS) {
            if (!rc.canMove(dir)) continue;
            int score = 0;
            for (MapInfo m : rc.senseNearbyMapInfos(rc.getLocation().add(dir), 4))
                if (m.getPaint().isAlly()) score++;
            if (score > bs) { bs = score; bd = dir; }
        }
        if (bd != null) rc.move(bd);
    }

    static void moveExplore(RobotController rc, MapLocation myLoc)
            throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation target = findBestUnvisitedSector(rc, myLoc);
        if (target == null) target = confirmedEnemy != null ? confirmedEnemy : mirrorLoc;
        if (target == null) {
            int bias = rc.getID() % 8; Direction d = DIRS[bias];
            int tx = d.dx>0?mapW-1:d.dx<0?0:mapW/2;
            int ty = d.dy>0?mapH-1:d.dy<0?0:mapH/2;
            target = new MapLocation(tx, ty);
        }
        moveToward(rc, target);
    }

    static void paintUnderSelf(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        try {
            MapInfo curr = rc.senseMapInfo(rc.getLocation());
            if (!curr.getPaint().isAlly() && rc.canAttack(rc.getLocation()))
                rc.attack(rc.getLocation());
        } catch (GameActionException e) {}
    }

    static void updateStuck(MapLocation myLoc) {
        if (lastLoc!=null && myLoc.equals(lastLoc)) stuckCount++;
        else stuckCount = 0;
        lastLoc = myLoc;
    }

    static int recentVisitPenalty(MapLocation loc) {
        int count = 0;
        for (MapLocation r : recentLocs) if (r!=null && r.equals(loc)) count++;
        if (count==0) return 0; if (count==1) return 12;
        if (count==2) return 28; if (count==3) return 48;
        return 60;
    }

    static RobotInfo findNearestTower(RobotController rc) throws GameActionException {
        RobotInfo best = null; int bd = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.type.isTowerType()) continue;
            int d = rc.getLocation().distanceSquaredTo(ally.location);
            if (d < bd) { bd = d; best = ally; }
        }
        return best;
    }

    static int countSwingHits(RobotController rc, Direction dir, RobotInfo[] enemies) {
        MapLocation my = rc.getLocation(), s1 = my.add(dir), s2 = s1.add(dir);
        int count = 0;
        for (RobotInfo e : enemies)
            if (e.location.isAdjacentTo(s1)||e.location.equals(s1)||
                e.location.isAdjacentTo(s2)||e.location.equals(s2)) count++;
        return count;
    }

    static MapLocation findEnemyFrontier(MapInfo[] nearby) {
        MapLocation best = null; int bc = 0;
        for (MapInfo tile : nearby) {
            if (!isEnemyPaint(tile.getPaint())) continue;
            int aa = 0;
            for (MapInfo adj : nearby)
                if (tile.getMapLocation().distanceSquaredTo(adj.getMapLocation()) <= 2
                    && adj.getPaint().isAlly()) aa++;
            if (aa > bc) { bc = aa; best = tile.getMapLocation(); }
        }
        return best;
    }

    static void tryUpgradeNearTower(RobotController rc) throws GameActionException {
        for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
            if (!ally.type.isTowerType()) continue;
            if (rc.canUpgradeTower(ally.location) && rc.getChips() >= 3000) {
                rc.upgradeTower(ally.location); return;
            }
        }
    }

    static boolean isEnemyPaint(PaintType p) { return p==PaintType.ENEMY_PRIMARY||p==PaintType.ENEMY_SECONDARY; }
    static boolean isMoneyTower(UnitType t)  { return t==UnitType.LEVEL_ONE_MONEY_TOWER||t==UnitType.LEVEL_TWO_MONEY_TOWER||t==UnitType.LEVEL_THREE_MONEY_TOWER; }
    static boolean isPaintTower(UnitType t)  { return t==UnitType.LEVEL_ONE_PAINT_TOWER||t==UnitType.LEVEL_TWO_PAINT_TOWER||t==UnitType.LEVEL_THREE_PAINT_TOWER; }
    static boolean isDefenseTower(UnitType t){ return t==UnitType.LEVEL_ONE_DEFENSE_TOWER||t==UnitType.LEVEL_TWO_DEFENSE_TOWER||t==UnitType.LEVEL_THREE_DEFENSE_TOWER; }

    static void sendMsg(RobotController rc, int msg) throws GameActionException {
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!ally.type.isTowerType()) continue;
            if (rc.canSendMessage(ally.location, msg)) { rc.sendMessage(ally.location, msg); return; }
        }
    }

    static String actName(int a) {
        switch(a) { case ACT_RETREAT:return"RET"; case ACT_COMBAT:return"CMB";
            case ACT_BUILD_TOWER:return"TWR"; case ACT_MESSING_UP:return"MSS";
            case ACT_BLEED_RESPOND:return"BLD"; case ACT_FRONTIER_EXPAND:return"FRN";
            case ACT_BUILD_SRP:return"SRP"; case ACT_BLITZKRIEG:return"BLITZ";
            default:return"?"; }
    }
    static String roleName(int r) {
        switch(r) { case ROLE_BUILDER:return"BLD"; case ROLE_EXPLORER:return"EXP";
            case ROLE_PAINTER:return"PNT"; case ROLE_ATTACKER:return"ATK"; default:return"?"; }
    }
    static String phaseName(int p) {
        switch(p) { case PHASE_EXPAND:return"EXP"; case PHASE_BORDER:return"BDR";
            case PHASE_CONQUER:return"CON"; case PHASE_BLITZKRIEG:return"BLITZ"; default:return"?"; }
    }
}