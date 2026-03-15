package zoro;

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

    static final Direction[] ORBIT_DIRS = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
    };

    static final int BC_CUTOFF = 2500;

    static final int MSG_ENEMY        = 1 << 30;
    static final int MSG_RUIN         = 2 << 30;
    static final int MSG_PAINTLOW     = 3 << 30;
    static final int MSG_TYPE_MASK    = 3 << 30;
    static final int MSG_SECTOR_FLAG  = 1 << 29;
    static final int MSG_BLEED_FLAG   = 1 << 28;
    static final int MSG_RUIN_CLAIMED = 1 << 27;
    static final int MSG_COVERAGE     = 1 << 26;

    static final int PHASE_EXPAND     = 0;
    static final int PHASE_BORDER     = 1;
    static final int PHASE_CONQUER    = 2;
    static final int PHASE_BLITZKRIEG = 3;

    static int   gamePhase       = PHASE_EXPAND;
    static float localCoverage   = 0f;
    static float localEnemyRatio = 0f;
    static float globalCoverage  = 0f;
    static int   coverageReportCount = 0;
    static float coverageReportSum   = 0f;

    static final float BLITZ_TRIGGER_COVERAGE = 0.72f;
    static final float BLITZ_EXIT_COVERAGE    = 0.50f;
    static final int   BLITZ_MIN_ROUND        = 200;

    static final int ROLE_BUILDER  = 0;
    static final int ROLE_EXPLORER = 1;
    static final int ROLE_PAINTER  = 2;
    static final int ROLE_ATTACKER = 3;

    static int myRole = ROLE_PAINTER;

    static final Direction[] ROLE_DIRS = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    static final int ACT_RETREAT         = 0;
    static final int ACT_COMBAT          = 1;
    static final int ACT_BUILD_TOWER     = 2;
    static final int ACT_MESSING_UP      = 3;
    static final int ACT_FRONTIER_EXPAND = 4;
    static final int ACT_BUILD_SRP       = 5;
    static final int ACT_SRP_DUTY        = 6;
    static final int ACT_BLITZKRIEG      = 7;
    static final int ACT_BLEED_RESPOND   = 8;

    static int         curAct        = ACT_FRONTIER_EXPAND;
    static MapLocation persistTarget = null;
    static int         actTimer      = 0;
    static boolean     isSrpDuty     = false;

    static int         mapW = -1, mapH = -1, mapArea = -1;
    static boolean     symInit       = false;
    static MapLocation spawnTowerLoc = null;
    static MapLocation mirrorLoc     = null;
    static MapLocation confirmedEnemy= null;
    static int         symType       = 0;

    static final int SECTOR_SIZE     = 5;
    static final int EXHAUSTED_TURNS = 10;
    static final int MIN_PAINT_PROG  = 2;

    static int   secGridW = -1, secGridH = -1, maxSectors = -1;
    static long[] visitedSectors   = new long[4];
    static long[] exhaustedSectors = new long[4];
    static long[] towerExhSectors  = new long[4];
    static long[] mirrorPrioritySec= new long[4];

    static int         lastSectorId    = -1;
    static int         turnsInSector   = 0;
    static int         paintedInSector = 0;
    static int         targetSectorId  = -1;
    static MapLocation sectorTarget    = null;
    static int         sectorTargetAge = 0;

    static MapLocation exploreTarget    = null;
    static int         exploreTargetAge = 0;
    static int         mySectorX        = -1;
    static int         mySectorY        = -1;

    static final int   MAX_KNOWN_RUINS      = 25;
    static MapLocation[] knownRuins         = new MapLocation[MAX_KNOWN_RUINS];
    static int[]         ruinVisitRound     = new int[MAX_KNOWN_RUINS];
    static int           knownRuinCount     = 0;
    static final int     RUIN_CLAIM_COOLDOWN = 10;

    static int getRuinIndex(MapLocation loc) {
        for (int i = 0; i < knownRuinCount; i++)
            if (knownRuins[i] != null && knownRuins[i].equals(loc)) return i;
        return -1;
    }

    static void registerRuin(MapLocation loc, int round) {
        int idx = getRuinIndex(loc);
        if (idx >= 0) { ruinVisitRound[idx] = round; return; }
        if (knownRuinCount < MAX_KNOWN_RUINS) {
            knownRuins[knownRuinCount]     = loc;
            ruinVisitRound[knownRuinCount] = round;
            knownRuinCount++;
        }
    }
    static boolean isRuinRecentlyVisited(MapLocation loc, RobotController rc) {
        int idx = getRuinIndex(loc);
        if (idx < 0) return false;
        return rc.getRoundNum() - ruinVisitRound[idx] < RUIN_CLAIM_COOLDOWN;
    }

    static final int   MAX_KNOWN_TOWERS = 10;
    static MapLocation[] knownTowers    = new MapLocation[MAX_KNOWN_TOWERS];
    static int           knownTowerCount= 0;

    static void registerTower(MapLocation loc) {
        for (int i = 0; i < knownTowerCount; i++)
            if (knownTowers[i] != null && knownTowers[i].equals(loc)) return;
        if (knownTowerCount < MAX_KNOWN_TOWERS) {
            knownTowers[knownTowerCount++] = loc;
        }
    }

    static MapLocation getNearestKnownTower(MapLocation myLoc) {
        MapLocation best = null; int bd = Integer.MAX_VALUE;
        for (int i = 0; i < knownTowerCount; i++) {
            if (knownTowers[i] == null) continue;
            int d = myLoc.distanceSquaredTo(knownTowers[i]);
            if (d < bd) { bd = d; best = knownTowers[i]; }
        }
        return best;
    }

    static MapLocation[] claimedRuins = new MapLocation[10];
    static int           claimedCount = 0;

    static MapLocation[] recentLocs = new MapLocation[10];
    static int           recentIdx  = 0;
    static MapLocation   lastLoc    = null;
    static int           stuckCount = 0;

    static int         prevAllyCount = -1;
    static final int   BLEED_THRESH  = 3;
    static boolean     isBleedingNow = false;
    static MapLocation bleedLocation = null;

    static int ruinProgressTimer = 0;
    static int ruinLastUnpainted = 999;

    static MapLocation[] srpQueue     = new MapLocation[20];
    static boolean[]     srpDone      = new boolean[20];
    static int           srpQueueSize = 0;
    static int           srpQueueHead = 0;
    static int           srpSquadLastRound = 0;

    static int    towerSpawnCount    = 0;
    static int    towerBuilderCount  = 0;
    static int    towerLocalSpawned  = 0;
    static double dblSoldiers  = 0;
    static double dblMoppers   = 0;
    static double dblSplashers = 0;

    static RobotInfo[] nearbyAllies  = null;
    static RobotInfo[] nearbyEnemies = null;
    static MapInfo[]   nearbyTiles   = null;
    static MapLocation myLoc         = null;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        if (mapW < 0) {
            mapW     = rc.getMapWidth();
            mapH     = rc.getMapHeight();
            mapArea  = mapW * mapH;
            secGridW = (mapW + SECTOR_SIZE - 1) / SECTOR_SIZE;
            secGridH = (mapH + SECTOR_SIZE - 1) / SECTOR_SIZE;
            maxSectors = secGridW * secGridH;
        }
        while (true) {
            turnCount++;
            try {
                updateInfo(rc);
                switch (rc.getType()) {
                    case SOLDIER:  Soldier.run(rc);  break;
                    case MOPPER:   Mopper.run(rc);   break;
                    case SPLASHER: Splasher.run(rc); break;
                    default:       Tower.run(rc);    break;
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

    static void updateInfo(RobotController rc) throws GameActionException {
        myLoc        = rc.getLocation();
        nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());
        nearbyEnemies= rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        nearbyTiles  = rc.senseNearbyMapInfos();

        for (RobotInfo ally : nearbyAllies) {
            if (ally.type.isTowerType() && ally.team == rc.getTeam()) {
                registerTower(ally.location);
            }
        }
        for (MapLocation ruinLoc : rc.senseNearbyRuins(-1)) {
            int idx = getRuinIndex(ruinLoc);
            if (idx < 0) {
                registerRuin(ruinLoc, -9999);
            }
        }
    }

    static void initSymmetry(RobotController rc) throws GameActionException {
        int bd = Integer.MAX_VALUE;
        for (RobotInfo ally : nearbyAllies) {
            if (!ally.type.isTowerType()) continue;
            int d = myLoc.distanceSquaredTo(ally.location);
            if (d < bd) { bd = d; spawnTowerLoc = ally.location; }
        }
        if (rc.getType().isTowerType()) spawnTowerLoc = myLoc;
        if (spawnTowerLoc == null) return;

        registerTower(spawnTowerLoc);

        MapLocation rot  = new MapLocation(mapW-1-spawnTowerLoc.x, mapH-1-spawnTowerLoc.y);
        MapLocation refX = new MapLocation(mapW-1-spawnTowerLoc.x, spawnTowerLoc.y);
        MapLocation refY = new MapLocation(spawnTowerLoc.x, mapH-1-spawnTowerLoc.y);
        int dR = spawnTowerLoc.distanceSquaredTo(rot);
        int dX = spawnTowerLoc.distanceSquaredTo(refX);
        int dY = spawnTowerLoc.distanceSquaredTo(refY);
        if (dR >= dX && dR >= dY) { mirrorLoc = rot;  symType = 0; }
        else if (dX >= dY)         { mirrorLoc = refX; symType = 1; }
        else                       { mirrorLoc = refY; symType = 2; }
    }

    static void refineSymmetry(MapLocation obs) {
        if (mirrorLoc == null || confirmedEnemy != null) return;
        if (mirrorLoc.distanceSquaredTo(obs) <= 25) confirmedEnemy = mirrorLoc;
    }

    static MapLocation getMirrorLoc(MapLocation loc) {
        switch (symType) {
            case 0: return new MapLocation(mapW-1-loc.x, mapH-1-loc.y);
            case 1: return new MapLocation(mapW-1-loc.x, loc.y);
            case 2: return new MapLocation(loc.x, mapH-1-loc.y);
            default: return new MapLocation(mapW-1-loc.x, mapH-1-loc.y);
        }
    }

    static void updateExploreTarget(RobotController rc) throws GameActionException {
        if (exploreTarget != null
                && exploreTargetAge < 4
                && myLoc.distanceSquaredTo(exploreTarget) > 8) {
            exploreTargetAge++;
            return;
        }
        int frontierCount = 0, bestDist = 0;
        MapLocation bestFrontier = null;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.isPassable()) continue;
            if (tile.getPaint().isAlly()) continue;
            frontierCount++;
            int dist = spawnTowerLoc != null
                ? tile.getMapLocation().distanceSquaredTo(spawnTowerLoc)
                : myLoc.distanceSquaredTo(tile.getMapLocation());
            if (dist > bestDist) { bestDist = dist; bestFrontier = tile.getMapLocation(); }
        }
        if (bestFrontier != null && frontierCount > 3) {
            exploreTarget = bestFrontier; exploreTargetAge = 0; return;
        }
        assignSector(rc);
        MapLocation secCenter = sectorCenter();
        if (myLoc.distanceSquaredTo(secCenter) <= 16) { rotateSector(); secCenter = sectorCenter(); }
        exploreTarget = secCenter; exploreTargetAge = 0;
        if (spawnTowerLoc != null
                && secCenter.distanceSquaredTo(spawnTowerLoc) < 50
                && mirrorLoc != null) {
            exploreTarget = mirrorLoc;
        }
    }

    static void assignSector(RobotController rc) {
        if (mapW <= 0 || mySectorX >= 0) return;
        int sw = (mapW + SECTOR_SIZE-1) / SECTOR_SIZE;
        int sh = (mapH + SECTOR_SIZE-1) / SECTOR_SIZE;
        mySectorX = (rc.getID() * 7) % sw;
        mySectorY = (rc.getID() * 13) % sh;
    }

    static void rotateSector() {
        if (mapW <= 0) return;
        int sw = (mapW + SECTOR_SIZE-1) / SECTOR_SIZE;
        int sh = (mapH + SECTOR_SIZE-1) / SECTOR_SIZE;
        mySectorX++;
        if (mySectorX >= sw) { mySectorX = 0; mySectorY = (mySectorY + 1) % sh; }
    }

    static MapLocation sectorCenter() {
        int cx = Math.min(mapW-1, mySectorX * SECTOR_SIZE + SECTOR_SIZE/2);
        int cy = Math.min(mapH-1, mySectorY * SECTOR_SIZE + SECTOR_SIZE/2);
        return new MapLocation(cx, cy);
    }

    static void updateSectorState(RobotController rc) throws GameActionException {
        int sid = getSectorId(myLoc);
        if (sid < 0) return;
        setSectorBit(visitedSectors, sid);
        if (sid != lastSectorId) { lastSectorId = sid; turnsInSector = 0; paintedInSector = 0; }
        else turnsInSector++;

        int emptyIn = 0, wallIn = 0;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.isPassable()) wallIn++;
            else if (tile.getPaint() == PaintType.EMPTY) emptyIn++;
        }
        boolean full    = emptyIn == 0 && gamePhase != PHASE_CONQUER && gamePhase != PHASE_BLITZKRIEG;
        boolean walled  = wallIn > 22;
        boolean timeout = turnsInSector >= EXHAUSTED_TURNS && paintedInSector < MIN_PAINT_PROG;

        if ((full || walled || timeout) && !isSectorBitSet(exhaustedSectors, sid)) {
            setSectorBit(exhaustedSectors, sid);
            sendMsg(rc, MSG_SECTOR_FLAG | (sid << 20));
            sectorTarget = null; targetSectorId = -1; sectorTargetAge = 0;
            exploreTarget = null; exploreTargetAge = 99;
        }
        try {
            if (rc.senseMapInfo(myLoc).getPaint().isAlly()) paintedInSector++;
        } catch (GameActionException e) {}
    }

    static int getSectorId(MapLocation loc) {
        if (secGridW < 0 || loc == null) return -1;
        int sx = loc.x / SECTOR_SIZE, sy = loc.y / SECTOR_SIZE;
        if (sx >= secGridW || sy >= secGridH) return -1;
        return sy * secGridW + sx;
    }

    static void setSectorBit(long[] b, int id) {
        if (id < 0 || id >= maxSectors || id >= b.length * 64) return;
        b[id/64] |= (1L << (id % 64));
    }

    static boolean isSectorBitSet(long[] b, int id) {
        if (id < 0 || id >= maxSectors || id >= b.length * 64) return false;
        return (b[id/64] & (1L << (id % 64))) != 0;
    }

    static int phaseVoteExpand  = 0;
    static int phaseVoteBorder  = 0;
    static int phaseVoteConquer = 0;
    static int phaseVoteBlitz   = 0;
    static final int PHASE_HYSTERESIS = 4;

    static void updateCoverageAndPhase(RobotController rc) throws GameActionException {
        int ally = 0, empty = 0, enemy = 0, total = 0;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.isPassable()) continue;
            total++;
            PaintType p = tile.getPaint();
            if (p.isAlly())                ally++;
            else if (p == PaintType.EMPTY) empty++;
            else if (isEnemyPaint(p))      enemy++;
        }
        total = Math.max(1, total);
        localCoverage   = (float) ally  / total;
        localEnemyRatio = (float) enemy / total;
        float emptyRatio = (float) empty / total;

        if (turnCount % 10 == 0) {
            int covMsg = MSG_COVERAGE | (int)(localCoverage * 1000);
            sendMsg(rc, covMsg);
        }

        float coverageForPhase = globalCoverage > 0 ? globalCoverage : localCoverage;

        int candidatePhase;
        if (gamePhase == PHASE_BLITZKRIEG && coverageForPhase < BLITZ_EXIT_COVERAGE) {
            candidatePhase = PHASE_EXPAND;
        } else if (turnCount > BLITZ_MIN_ROUND
                && coverageForPhase >= BLITZ_TRIGGER_COVERAGE
                && emptyRatio < 0.08f && enemy > 0) {
            candidatePhase = PHASE_BLITZKRIEG;
        } else if (emptyRatio < 0.05f && enemy > 0) {
            candidatePhase = PHASE_CONQUER;
        } else if (localEnemyRatio > 0.10f) {
            candidatePhase = PHASE_BORDER;
        } else {
            candidatePhase = PHASE_EXPAND;
        }

        if      (candidatePhase == PHASE_EXPAND)  { phaseVoteExpand++;  phaseVoteBorder=0; phaseVoteConquer=0; phaseVoteBlitz=0; }
        else if (candidatePhase == PHASE_BORDER)  { phaseVoteBorder++;  phaseVoteExpand=0; phaseVoteConquer=0; phaseVoteBlitz=0; }
        else if (candidatePhase == PHASE_CONQUER) { phaseVoteConquer++; phaseVoteExpand=0; phaseVoteBorder=0;  phaseVoteBlitz=0; }
        else                                      { phaseVoteBlitz++;   phaseVoteExpand=0; phaseVoteBorder=0;  phaseVoteConquer=0; }

        if (candidatePhase != gamePhase) {
            int threshold = (candidatePhase == PHASE_BLITZKRIEG) ? PHASE_HYSTERESIS * 2 : PHASE_HYSTERESIS;
            int votes = candidatePhase == PHASE_EXPAND  ? phaseVoteExpand
                      : candidatePhase == PHASE_BORDER  ? phaseVoteBorder
                      : candidatePhase == PHASE_CONQUER ? phaseVoteConquer
                      : phaseVoteBlitz;
            if (votes >= threshold) {
                gamePhase = candidatePhase;
                phaseVoteExpand = phaseVoteBorder = phaseVoteConquer = phaseVoteBlitz = 0;
            }
        }
    }

    static void detectBleed(RobotController rc) throws GameActionException {
        int currentAlly = 0;
        for (MapInfo tile : nearbyTiles)
            if (tile.isPassable() && tile.getPaint().isAlly()) currentAlly++;

        if (prevAllyCount >= 0 && (prevAllyCount - currentAlly) >= BLEED_THRESH) {
            isBleedingNow = true;
            MapLocation worst = null; int worstAdj = 0;
            for (MapInfo tile : nearbyTiles) {
                if (!isEnemyPaint(tile.getPaint())) continue;
                int adjAlly = 0;
                for (MapInfo adj : nearbyTiles)
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

    static void updateAsymmetricCoverage() {
        if (mirrorLoc == null) return;
        for (MapInfo tile : nearbyTiles) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!isEnemyPaint(tile.getPaint())) continue;
            int ms = getSectorId(getMirrorLoc(tile.getMapLocation()));
            if (ms >= 0) setSectorBit(mirrorPrioritySec, ms);
        }
    }

    static MapLocation navTarget     = null;
    static boolean     wallFollowing = false;
    static boolean     followLeft    = true;
    static Direction   wallDir       = Direction.NORTH;
    static int         navFollowTurn = 0;
    static int         navStartDist  = Integer.MAX_VALUE;

    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady() || target == null) return;
        if (myLoc.equals(target)) return;

        if (navTarget == null || !navTarget.equals(target)) {
            navTarget = target; wallFollowing = false;
            navFollowTurn = 0; navStartDist = myLoc.distanceSquaredTo(target);
        }
        Direction toTarget = myLoc.directionTo(target);
        if (rc.canMove(toTarget)) {
            if (!wallFollowing || myLoc.distanceSquaredTo(target) <= navStartDist
                    || navFollowTurn > 14) {
                rc.move(toTarget); wallFollowing = false; navFollowTurn = 0;
                navStartDist = rc.getLocation().distanceSquaredTo(target);
                wallDir = toTarget; myLoc = rc.getLocation(); return;
            }
        }
        if (!wallFollowing) {
            wallFollowing = true; navFollowTurn = 0;
            navStartDist = myLoc.distanceSquaredTo(target);
            wallDir = toTarget; followLeft = ((rc.getID() + turnCount) & 1) == 0;
        }
        navFollowTurn++;
        if (navFollowTurn > 14) {
            followLeft = !followLeft; navFollowTurn = 0;
            navStartDist = myLoc.distanceSquaredTo(target);
        }
        Direction obs = wallDir;
        for (int i = 0; i < 8; i++) {
            obs = followLeft ? obs.rotateLeft() : obs.rotateRight();
            if (rc.canMove(obs)) {
                rc.move(obs); wallDir = obs; myLoc = rc.getLocation();
                if (rc.canMove(rc.getLocation().directionTo(target))
                    && rc.getLocation().distanceSquaredTo(target) < navStartDist) {
                    wallFollowing = false; navFollowTurn = 0;
                }
                return;
            }
        }
        if (stuckCount >= 3)
            for (Direction d : DIRS) { if (rc.canMove(d)) { rc.move(d); stuckCount = 0; myLoc = rc.getLocation(); return; } }
    }

    static void moveTowardAllyPaint(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction bd = null; int bs = Integer.MIN_VALUE;
        for (Direction dir : DIRS) {
            if (!rc.canMove(dir)) continue;
            int score = 0;
            for (MapInfo m : rc.senseNearbyMapInfos(myLoc.add(dir), 4))
                if (m.getPaint().isAlly()) score++;
            if (score > bs) { bs = score; bd = dir; }
        }
        if (bd != null) { rc.move(bd); myLoc = rc.getLocation(); }
    }

    static void moveExplore(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation target;

        if (wallStuck) {
            target = findBestUnvisitedSector(rc);
            if (target == null) {
                exhaustedSectors[0] = exhaustedSectors[1] = exhaustedSectors[2] = exhaustedSectors[3] = 0;
                target = findBestUnvisitedSector(rc);
            }
        } else {
            target = exploreTarget;
            if (target == null || myLoc.distanceSquaredTo(target) <= 4) {
                target = findBestUnvisitedSector(rc);
            }
        }

        if (target == null) target = confirmedEnemy != null ? confirmedEnemy : mirrorLoc;
        if (target == null) {
            int bias = rc.getID() % 4;
            int tx, ty;
            if (wallStuck) {
                tx = mapW / 2 + (bias % 2 == 0 ? -3 : 3);
                ty = mapH / 2 + (bias < 2 ? -3 : 3);
            } else {
                tx = (bias & 1) == 0 ? 0 : mapW - 1;
                ty = (bias & 2) == 0 ? 0 : mapH - 1;
            }
            target = new MapLocation(Math.max(0, Math.min(mapW-1, tx)),
                                     Math.max(0, Math.min(mapH-1, ty)));
        }
        moveToward(rc, target);
    }

    static MapLocation findBestUnvisitedSector(RobotController rc) {
        if (secGridW < 0) return null;
        MapLocation best = null; int bs = Integer.MIN_VALUE;
        int idBias = rc.getID() % 4;
        int bx = (idBias & 1) == 0 ? 0 : secGridW - 1;
        int by = (idBias & 2) == 0 ? 0 : secGridH - 1;
        MapLocation biasCenter = new MapLocation(
            Math.min(mapW-1, bx * SECTOR_SIZE + SECTOR_SIZE/2),
            Math.min(mapH-1, by * SECTOR_SIZE + SECTOR_SIZE/2));

        for (int sy = 0; sy < secGridH; sy++) {
            for (int sx = 0; sx < secGridW; sx++) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                int secId = sy * secGridW + sx;
                if (isSectorBitSet(exhaustedSectors, secId)) continue;
                if (isSectorBitSet(towerExhSectors,  secId)) continue;

                int cx = Math.min(mapW-1, sx * SECTOR_SIZE + SECTOR_SIZE/2);
                int cy = Math.min(mapH-1, sy * SECTOR_SIZE + SECTOR_SIZE/2);
                MapLocation center = new MapLocation(cx, cy);

                int score = 50;
                if (!isSectorBitSet(visitedSectors, secId)) score += 40;
                score -= myLoc.distanceSquaredTo(center) / 8;
                if (isSectorBitSet(mirrorPrioritySec, secId)) score += 35;
                score += 20 - Math.min(20, center.distanceSquaredTo(biasCenter) / 8);

                MapLocation et = confirmedEnemy != null ? confirmedEnemy : mirrorLoc;
                if (et != null) score -= center.distanceSquaredTo(et) / 15;
                if (spawnTowerLoc != null) score += center.distanceSquaredTo(spawnTowerLoc) / 20;

                boolean isEdgeSector = (sx == 0 || sx == secGridW-1
                                     || sy == 0 || sy == secGridH-1);
                if (isEdgeSector) {
                    score -= wallStuck ? 80 : 20;
                }
                boolean isCorner = (sx == 0 || sx == secGridW-1)
                                && (sy == 0 || sy == secGridH-1);
                if (isCorner) score -= wallStuck ? 120 : 40;

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

    static void paintWhileMoving(RobotController rc, MapLocation target,
                                  MapInfo[] tiles) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (target != null && rc.canAttack(target)) {
            MapInfo tInfo = rc.senseMapInfo(target);
            if (tInfo.isPassable() && !tInfo.getPaint().isAlly()) {
                rc.attack(target); return;
            }
        }
        greedyPaintFrontier(rc, tiles);
    }

    static int greedyPaintFrontier(RobotController rc, MapInfo[] tiles)
            throws GameActionException {
        if (!rc.isActionReady()) return 0;
        MapLocation best = null; int bs = Integer.MIN_VALUE;
        for (MapInfo m : tiles) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!rc.canAttack(m.getMapLocation())) continue;
            PaintType p = m.getPaint();
            if (p.isAlly()) continue;
            int score = isEnemyPaint(p)
                ? (gamePhase==PHASE_BLITZKRIEG?160:gamePhase==PHASE_CONQUER?120:gamePhase==PHASE_BORDER?100:90)
                : 50;
            if (spawnTowerLoc != null)
                score += m.getMapLocation().distanceSquaredTo(spawnTowerLoc) / 20;
            int secId = getSectorId(m.getMapLocation());
            if (secId >= 0 && isSectorBitSet(mirrorPrioritySec, secId)) score += 25;
            int ne = 0;
            for (MapInfo adj : tiles)
                if (m.getMapLocation().distanceSquaredTo(adj.getMapLocation()) <= 2
                    && adj.isPassable() && adj.getPaint() == PaintType.EMPTY) ne++;
            score += ne * 5;
            if (score > bs) { bs = score; best = m.getMapLocation(); }
        }
        if (best != null) { rc.attack(best); paintedInSector++; return 1; }
        return 0;
    }

    static int calcAdjacencyPenalty(RobotController rc, MapLocation loc)
            throws GameActionException {
        int allyAdj = 0;
        for (RobotInfo ally : nearbyAllies) {
            if (!ally.type.isRobotType()) continue;
            if (loc.distanceSquaredTo(ally.location) <= 2) allyAdj++;
        }
        boolean enemyTerritory = false;
        try {
            PaintType p = rc.senseMapInfo(loc).getPaint();
            enemyTerritory = isEnemyPaint(p);
        } catch (GameActionException e) {}

        return allyAdj * (enemyTerritory ? 10 : 5);
    }

    static void paintUnderSelf(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        try {
            MapInfo curr = rc.senseMapInfo(myLoc);
            if (!curr.getPaint().isAlly() && rc.canAttack(myLoc)) rc.attack(myLoc);
        } catch (GameActionException e) {}
    }

    static void updateStuck() {
        if (lastLoc != null && myLoc.equals(lastLoc)) stuckCount++;
        else stuckCount = 0;
        lastLoc = myLoc;
        recentLocs[recentIdx % recentLocs.length] = myLoc;
        recentIdx++;
    }

    static int recentVisitPenalty(MapLocation loc) {
        int count = 0;
        for (MapLocation r : recentLocs) if (r != null && r.equals(loc)) count++;
        if (count == 0) return 0; if (count == 1) return 12;
        if (count == 2) return 28; if (count == 3) return 48;
        return 60;
    }

    static boolean wallStuck          = false;
    static int     wallStuckTurns     = 0;
    static int     wallStuckReturnTurn= -1;
    static final int WALL_STUCK_THRESHOLD = 5;

    static void detectWallStuck(RobotController rc) throws GameActionException {
        int blocked = 0;
        for (Direction d : DIRS) {
            MapLocation next = myLoc.add(d);
            if (!rc.onTheMap(next) || !rc.sensePassability(next)) blocked++;
        }

        boolean isWallArea = blocked >= 3;
        boolean notMoving  = (lastLoc != null && myLoc.equals(lastLoc));

        if (isWallArea && notMoving) {
            wallStuckTurns++;
        } else if (!notMoving) {

            wallStuckTurns = Math.max(0, wallStuckTurns - 1);
            if (wallStuckTurns == 0) wallStuck = false;
        }

        if (wallStuckTurns >= WALL_STUCK_THRESHOLD && !wallStuck) {
            wallStuck = true;
            wallStuckReturnTurn = turnCount;

            int sid = getSectorId(myLoc);
            if (sid >= 0) {
                setSectorBit(exhaustedSectors, sid);
                int sx = myLoc.x / SECTOR_SIZE, sy = myLoc.y / SECTOR_SIZE;
                int[][] adjOff = {{-1,0},{1,0},{0,-1},{0,1}};
                for (int[] off : adjOff) {
                    int asx = sx + off[0], asy = sy + off[1];
                    if (asx < 0 || asx >= secGridW || asy < 0 || asy >= secGridH) continue;
                    int adjSid = asy * secGridW + asx;
                    MapLocation adjCenter = new MapLocation(
                        Math.min(mapW-1, asx * SECTOR_SIZE + SECTOR_SIZE/2),
                        Math.min(mapH-1, asy * SECTOR_SIZE + SECTOR_SIZE/2));
                    boolean adjEdge = adjCenter.x <= 2 || adjCenter.x >= mapW - 3
                                   || adjCenter.y <= 2 || adjCenter.y >= mapH - 3;
                    if (adjEdge) setSectorBit(exhaustedSectors, adjSid);
                }
            }

            exploreTarget     = null;
            exploreTargetAge  = 99;
            wallFollowing     = false;
            navFollowTurn     = 0;
        }
    }

    static void resetWallStuck() {
        wallStuck      = false;
        wallStuckTurns = 0;
    }

    static int countUnpainted(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        boolean hm = false; int count = 0;
        MapInfo[] tiles = rc.senseNearbyMapInfos(ruinLoc, 8);
        for (MapInfo m : tiles) if (m.getMark() != PaintType.EMPTY) { hm = true; break; }
        if (hm) {
            for (MapInfo m : tiles) {
                if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
                if (m.getMark() == PaintType.EMPTY) continue;
                PaintType mark  = m.getMark();
                PaintType paint = m.getPaint();
                boolean done = (mark == PaintType.ALLY_PRIMARY   && paint == PaintType.ALLY_PRIMARY)
                            || (mark == PaintType.ALLY_SECONDARY && paint == PaintType.ALLY_SECONDARY);
                if (!done) count++;
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
        MapLocation best = null; int bd = Integer.MAX_VALUE;
        for (MapInfo pat : rc.senseNearbyMapInfos(center, 8)) {
            if (Clock.getBytecodesLeft() < BC_CUTOFF) break;
            if (!rc.canAttack(pat.getMapLocation())) continue;

            PaintType mark  = pat.getMark();
            PaintType paint = pat.getPaint();

            if (mark == PaintType.EMPTY) continue;

            boolean doneCorrectly = (mark == PaintType.ALLY_PRIMARY   && paint == PaintType.ALLY_PRIMARY)
                                 || (mark == PaintType.ALLY_SECONDARY && paint == PaintType.ALLY_SECONDARY);
            if (doneCorrectly) continue;

            int d = myLoc.distanceSquaredTo(pat.getMapLocation());
            if (d < bd) { bd = d; best = pat.getMapLocation(); }
        }
        return best;
    }

    static int countEnemyPaintNear(MapLocation ruinLoc, MapInfo[] tiles) {
        int count = 0;
        for (MapInfo tile : tiles)
            if (ruinLoc.distanceSquaredTo(tile.getMapLocation()) <= 12
                && isEnemyPaint(tile.getPaint())) count++;
        return count;
    }

    static boolean isRuinInAllyTerritory(MapLocation ruinLoc) {
        for (MapInfo tile : nearbyTiles)
            if (ruinLoc.distanceSquaredTo(tile.getMapLocation()) <= 9
                && tile.getPaint().isAlly()) return true;
        return false;
    }

    static void claimRuin(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        sendMsg(rc, MSG_RUIN_CLAIMED | (ruinLoc.x << 14) | ruinLoc.y);
        addClaimedRuin(ruinLoc);
    }

    static void addClaimedRuin(MapLocation loc) {
        for (int i = 0; i < claimedCount; i++)
            if (claimedRuins[i] != null && claimedRuins[i].equals(loc)) return;
        if (claimedCount < claimedRuins.length) claimedRuins[claimedCount++] = loc;
    }

    static void releaseRuinClaim(MapLocation loc) {
        for (int i = 0; i < claimedCount; i++)
            if (claimedRuins[i] != null && claimedRuins[i].equals(loc)) {
                claimedRuins[i] = claimedRuins[--claimedCount];
                claimedRuins[claimedCount] = null; return;
            }
    }

    static int countRuinClaims(MapLocation ruinLoc) {
        int count = 0;
        for (RobotInfo ally : nearbyAllies)
            if (ally.type == UnitType.SOLDIER
                && ally.location.distanceSquaredTo(ruinLoc) <= 8) count++;
        return count;
    }

    static UnitType chooseTower(RobotController rc) throws GameActionException {
        int pt = 0, mt = 0, dt = 0;
        for (RobotInfo ally : nearbyAllies) {
            if (isPaintTower(ally.type)) pt++;
            else if (isMoneyTower(ally.type)) mt++;
            else if (isDefenseTower(ally.type)) dt++;
        }
        int enemies = nearbyEnemies.length;
        int mv = mt == 0 ? 90 : Math.max(0, 50 - mt * 10);
        if (rc.getNumberTowers() < 3) mv += 20;
        int pv = pt == 0 ? 80 : Math.max(0, 45 - pt * 10);
        if (rc.getNumberTowers() >= 3 && pt <= mt) pv += 20;
        int dv = enemies >= 3 && dt == 0 ? 90 : enemies >= 2 ? 40 + enemies * 5 : Math.max(0, 15 - dt * 10);
        if (mapW > 0 && myLoc.distanceSquaredTo(new MapLocation(mapW/2, mapH/2)) < 35
            && enemies > 0) dv += 25;
        if (mv >= pv && mv >= dv) return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (dv >= pv) return UnitType.LEVEL_ONE_DEFENSE_TOWER;
        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    static MapLocation nextSrpTarget(RobotController rc) throws GameActionException {
        for (int i = 0; i < srpQueueSize; i++) {
            int idx = (srpQueueHead + i) % srpQueue.length;
            if (srpDone[idx] || srpQueue[idx] == null) continue;
            if (isSrpValid(rc, srpQueue[idx])) return srpQueue[idx];
            srpDone[idx] = true;
        }
        for (MapInfo tile : nearbyTiles) {
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
        if (loc.x < 2 || loc.x >= mapW-2 || loc.y < 2 || loc.y >= mapH-2) return false;
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
        int idx = (srpQueueHead + srpQueueSize) % srpQueue.length;
        srpQueue[idx] = loc; srpDone[idx] = false; srpQueueSize++;
    }

    static void disqualifySrp(MapLocation loc) {
        for (int i = 0; i < srpQueue.length; i++)
            if (srpQueue[i] != null && srpQueue[i].equals(loc)) { srpDone[i] = true; return; }
    }

    static void sendMsg(RobotController rc, int msg) throws GameActionException {
        for (RobotInfo ally : nearbyAllies) {
            if (!ally.type.isTowerType()) continue;
            if (rc.canSendMessage(ally.location, msg)) { rc.sendMessage(ally.location, msg); return; }
        }
    }

    static void tryUpgradeNearTower(RobotController rc) throws GameActionException {
        for (RobotInfo ally : nearbyAllies) {
            if (!ally.type.isTowerType()) continue;
            if (myLoc.distanceSquaredTo(ally.location) > 2) continue;
            if (rc.canUpgradeTower(ally.location) && rc.getChips() >= 3000) {
                rc.upgradeTower(ally.location); return;
            }
        }
    }

    static RobotInfo findNearestTower(RobotController rc) {
        RobotInfo best = null; int bd = Integer.MAX_VALUE;
        for (RobotInfo ally : nearbyAllies) {
            if (!ally.type.isTowerType()) continue;
            int d = myLoc.distanceSquaredTo(ally.location);
            if (d < bd) { bd = d; best = ally; }
        }
        return best;
    }

    static int countSwingHits(RobotController rc, Direction dir) {
        MapLocation s1 = myLoc.add(dir), s2 = s1.add(dir);
        int count = 0;
        for (RobotInfo e : nearbyEnemies)
            if (e.location.isAdjacentTo(s1) || e.location.equals(s1)
                || e.location.isAdjacentTo(s2) || e.location.equals(s2)) count++;
        return count;
    }

    static MapLocation findEnemyFrontier() {
        MapLocation best = null; int bc = 0;
        for (MapInfo tile : nearbyTiles) {
            if (!isEnemyPaint(tile.getPaint())) continue;
            int aa = 0;
            for (MapInfo adj : nearbyTiles)
                if (tile.getMapLocation().distanceSquaredTo(adj.getMapLocation()) <= 2
                    && adj.getPaint().isAlly()) aa++;
            if (aa > bc) { bc = aa; best = tile.getMapLocation(); }
        }
        return best;
    }

    static boolean isEnemyPaint(PaintType p) {
        return p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY;
    }
    static boolean isMoneyTower(UnitType t) {
        return t==UnitType.LEVEL_ONE_MONEY_TOWER||t==UnitType.LEVEL_TWO_MONEY_TOWER||t==UnitType.LEVEL_THREE_MONEY_TOWER;
    }
    static boolean isPaintTower(UnitType t) {
        return t==UnitType.LEVEL_ONE_PAINT_TOWER||t==UnitType.LEVEL_TWO_PAINT_TOWER||t==UnitType.LEVEL_THREE_PAINT_TOWER;
    }
    static boolean isDefenseTower(UnitType t) {
        return t==UnitType.LEVEL_ONE_DEFENSE_TOWER||t==UnitType.LEVEL_TWO_DEFENSE_TOWER||t==UnitType.LEVEL_THREE_DEFENSE_TOWER;
    }

    static String actName(int a) {
        switch(a) {
            case ACT_RETREAT:return"RET"; case ACT_COMBAT:return"CMB";
            case ACT_BUILD_TOWER:return"TWR"; case ACT_MESSING_UP:return"MSS";
            case ACT_BLEED_RESPOND:return"BLD"; case ACT_FRONTIER_EXPAND:return"FRN";
            case ACT_BUILD_SRP:return"SRP"; case ACT_BLITZKRIEG:return"BLITZ";
            default:return"?";
        }
    }
    static String roleName(int r) {
        switch(r) {
            case ROLE_BUILDER:return"BLD"; case ROLE_EXPLORER:return"EXP";
            case ROLE_PAINTER:return"PNT"; case ROLE_ATTACKER:return"ATK";
            default:return"?";
        }
    }
    static String phaseName(int p) {
        switch(p) {
            case PHASE_EXPAND:return"EXP"; case PHASE_BORDER:return"BDR";
            case PHASE_CONQUER:return"CON"; case PHASE_BLITZKRIEG:return"BLITZ";
            default:return"?";
        }
    }
}