package TerraFirma;

import battlecode.common.*;

final class Tower {

    static final class SpawnStats {
        boolean paintLow, enemyNear;
        boolean frontActive;
        MapLocation ruinLoc, enemyLoc, frontLoc;
        int soldierCount, mopperCount, splasherCount, lowPaintSoldiers;
        int enemyTiles, emptyTiles, allyTiles, nearbyAllies;
        int mapArea, towerCount;
    }

    private Tower() {}

    static void run(RobotController rc) throws GameActionException {
        RobotPlayer.initState(rc);
        attackWeakestEnemy(rc);
        SpawnStats stats = collectStats(rc);

        MapLocation myLoc = rc.getLocation();
        if (rc.canUpgradeTower(myLoc) && shouldUpgradeNow(rc, stats)) {
            rc.upgradeTower(myLoc);
            return;
        }

        UnitType spawnUnit = greedySelectUnit(stats);
        Direction bestDirection = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction direction : RobotPlayer.directions) {
            MapLocation spawnLoc = myLoc.add(direction);
            if (!rc.canBuildRobot(spawnUnit, spawnLoc)) continue;
            int score = evalLoc(rc, spawnLoc, spawnUnit, stats);
            if (score > bestScore) {
                bestScore = score;
                bestDirection = direction;
            }
        }

        if (bestDirection != null) {
            rc.buildRobot(spawnUnit, myLoc.add(bestDirection));
        }
    }

    static boolean shouldUpgradeNow(RobotController rc, SpawnStats stats) {
        UnitType myType = rc.getType();
        if (!isUpgradeableTower(myType)) return false;

        int chips = rc.getChips();
        int paint = rc.getPaint();
        int towerCount = rc.getNumberTowers();
        int phase = RobotPlayer.macroPhase();
        if (paint < 45) return false;

        int sharedThreshold = 1080 + towerCount * 48;
        int moneyThreshold = 1020 + towerCount * 42;
        int paintThreshold = 1120 + towerCount * 46;
        int defenseThreshold = stats.enemyNear ? 920 + towerCount * 30 : 1320 + towerCount * 42;
        if (phase == 1) {
            sharedThreshold -= 60;
            moneyThreshold -= 80;
            paintThreshold -= 40;
        } else if (phase == 2) {
            sharedThreshold -= 120;
            moneyThreshold -= 130;
            paintThreshold -= 90;
            defenseThreshold -= 90;
        }
        int surplus = chips - sharedThreshold;

        if (chips >= 2400 && paint >= 55) return true;
        if (stats.enemyNear) {
            if (isDefenseTower(myType) && chips >= defenseThreshold) return true;
            if (isPaintTower(myType) && stats.lowPaintSoldiers >= 1 && chips >= paintThreshold - 120) return true;
            return false;
        }
        if (isMoneyTower(myType) && chips >= moneyThreshold) return true;
        if (isPaintTower(myType) && (stats.lowPaintSoldiers >= 1 || stats.mapArea >= 2600) && chips >= paintThreshold) return true;
        if (isDefenseTower(myType) && stats.enemyTiles >= 6 && chips >= defenseThreshold) return true;
        if (towerCount <= 3 && chips >= moneyThreshold - 80) return true;
        return surplus >= 120 && chips >= sharedThreshold + 60;
    }

    static boolean isUpgradeableTower(UnitType type) {
        return isMoneyTower(type) || isPaintTower(type) || isDefenseTower(type);
    }

    static boolean isMoneyTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_MONEY_TOWER || type == UnitType.LEVEL_TWO_MONEY_TOWER;
    }

    static boolean isPaintTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_PAINT_TOWER || type == UnitType.LEVEL_TWO_PAINT_TOWER;
    }

    static boolean isDefenseTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_DEFENSE_TOWER || type == UnitType.LEVEL_TWO_DEFENSE_TOWER;
    }

    static SpawnStats collectStats(RobotController rc) throws GameActionException {
        SpawnStats stats = new SpawnStats();
        stats.mapArea = rc.getMapWidth() * rc.getMapHeight();
        stats.towerCount = rc.getNumberTowers();
        stats.frontActive = RobotPlayer.warFrontActive(rc.getRoundNum());
        if (stats.frontActive) {
            stats.frontLoc = RobotPlayer.currentFrontTarget(rc.getRoundNum());
        }

        for (Message message : rc.readMessages(-1)) {
            int data = message.getBytes();
            int type = data & (3 << 30);
            MapLocation loc = new MapLocation((data >> 15) & 0x7FFF, data & 0x7FFF);
            if (type == RobotPlayer.MSG_PAINTLOW) {
                stats.paintLow = true;
            } else if (type == RobotPlayer.MSG_ENEMY) {
                stats.enemyNear = true;
                stats.enemyLoc = loc;
            } else if (type == RobotPlayer.MSG_RUIN) {
                stats.ruinLoc = loc;
            }
        }

        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.type == UnitType.SOLDIER) {
                stats.soldierCount++;
                int paintPercent = (int) (100.0 * ally.paintAmount / ally.type.paintCapacity);
                if (paintPercent < 40) stats.lowPaintSoldiers++;
            } else if (ally.type == UnitType.MOPPER) {
                stats.mopperCount++;
            } else if (ally.type == UnitType.SPLASHER) {
                stats.splasherCount++;
            }
        }

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            PaintType paint = tile.getPaint();
            if (RobotPlayer.isEnemyPaint(paint)) stats.enemyTiles++;
            else if (paint == PaintType.EMPTY && tile.isPassable()) stats.emptyTiles++;
            else if (paint.isAlly()) stats.allyTiles++;
        }

        stats.nearbyAllies = rc.senseNearbyRobots(9, rc.getTeam()).length;
        return stats;
    }

    static UnitType greedySelectUnit(SpawnStats s) {
        int phase = RobotPlayer.macroPhase();
        int soldierScore = 20;
        int splasherScore = 12;
        int mopperScore = 6;

        boolean openMap = s.emptyTiles >= s.allyTiles + 4;
        boolean hugeMap = s.mapArea >= 2600;

        if (s.ruinLoc != null) soldierScore += 70;
        if (s.enemyNear) soldierScore += 22;
        if (s.soldierCount < 2) soldierScore += 42;
        else soldierScore += Math.max(0, 24 - s.soldierCount * 3);
        soldierScore += Math.min(20, s.emptyTiles / 2);
        if (s.frontActive) soldierScore += 16;

        splasherScore += Math.min(40, s.emptyTiles * 2);
        splasherScore += Math.min(28, s.enemyTiles * 3);
        if (openMap) splasherScore += 18;
        if (hugeMap) splasherScore += 14;
        if (phase == 2) splasherScore += 28;
        if (phase == 2 && s.splasherCount < Math.max(2, s.soldierCount / 2)) splasherScore += 24;
        if (s.frontActive) splasherScore += phase == 2 ? 32 : 18;
        if (phase == 0 && s.splasherCount == 0 && s.soldierCount >= 1) splasherScore += 26;
        if (hugeMap && phase == 0 && s.splasherCount < 2) splasherScore += 18;
        if (hugeMap && s.towerCount >= 8) splasherScore += 26;
        if (hugeMap && s.towerCount >= 12 && s.splasherCount < 2) splasherScore += 35;
        if (s.ruinLoc != null && RobotPlayer.turnCount < 80) splasherScore -= 18;
        if (s.splasherCount >= 3) splasherScore -= 28;

        mopperScore += s.enemyTiles * 5;
        if (s.paintLow) mopperScore += 35;
        if (s.enemyNear) mopperScore += 18;
        if (s.lowPaintSoldiers >= 2) mopperScore += 20;
        if (s.ruinLoc != null && s.enemyTiles > 0) mopperScore += 18;
        mopperScore -= s.mopperCount * 10;
        if (hugeMap && !s.enemyNear && s.enemyTiles < 4) mopperScore -= 30;
        if (phase == 2) mopperScore -= 18;
        if (phase == 2 && s.mopperCount * 2 >= s.soldierCount + s.splasherCount + 1) mopperScore -= 30;

        if (RobotPlayer.turnCount < 25 && s.soldierCount == 0) return UnitType.SOLDIER;
        if (RobotPlayer.turnCount < 55 && s.soldierCount < 2) return UnitType.SOLDIER;
        if (RobotPlayer.turnCount < 70 && openMap && s.splasherCount == 0 && s.soldierCount >= 2) return UnitType.SPLASHER;
        if (hugeMap && RobotPlayer.turnCount < 95 && openMap && s.splasherCount < 2 && s.soldierCount >= 2) return UnitType.SPLASHER;
        if (phase == 2 && s.frontActive && s.splasherCount < Math.max(3, s.soldierCount / 2)) return UnitType.SPLASHER;
        if (hugeMap && s.towerCount >= 12 && RobotPlayer.turnCount > 500 && !s.enemyNear && s.splasherCount < 6) return UnitType.SPLASHER;

        if (mopperScore >= soldierScore && mopperScore >= splasherScore) return UnitType.MOPPER;
        if (splasherScore >= soldierScore) return UnitType.SPLASHER;
        return UnitType.SOLDIER;
    }

    static int evalLoc(RobotController rc, MapLocation loc, UnitType unit, SpawnStats stats) throws GameActionException {
        int score = 0;
        PaintType paint = rc.senseMapInfo(loc).getPaint();
        if (paint == PaintType.EMPTY) score += unit == UnitType.SPLASHER ? 8 : 2;
        else if (!paint.isAlly()) score += unit == UnitType.MOPPER ? 6 : 3;
        if (stats.mapArea >= 2600 && unit != UnitType.MOPPER) {
            score += RobotPlayer.edgeSectorBias(loc) / 4;
        }

        MapLocation target = spawnVectorTarget(unit, stats, loc);
        if (target != null) {
            score -= loc.distanceSquaredTo(target) / 2;
            if (stats.mapArea >= 2600) {
                score += RobotPlayer.edgeSectorBias(target) / 3;
                score += RobotPlayer.edgeProgressBias(rc.getLocation(), loc, target);
            }
        }

        if (unit == UnitType.SPLASHER) {
            score += RobotPlayer.localClaimValue(rc, loc, 9) * 3;
            if (stats.mapArea >= 2600) score += RobotPlayer.localClaimValue(rc, loc, 12) * 2;
        } else if (unit == UnitType.SOLDIER) {
            score += RobotPlayer.localClaimValue(rc, loc, 4) * 2;
        } else {
            for (MapInfo nearby : rc.senseNearbyMapInfos(loc, 4)) {
                if (RobotPlayer.isEnemyPaint(nearby.getPaint())) score += 5;
            }
        }

        if (RobotPlayer.spawnTower != null && unit != UnitType.MOPPER) {
            score += loc.distanceSquaredTo(RobotPlayer.spawnTower) / 10;
        }

        score -= rc.senseNearbyRobots(loc, 16, rc.getTeam()).length * 2;
        score -= rc.senseNearbyRobots(loc, 20, rc.getTeam().opponent()).length * (unit == UnitType.MOPPER ? 3 : 2);
        return score;
    }

    static MapLocation spawnVectorTarget(UnitType unit, SpawnStats stats, MapLocation loc) {
        int phase = RobotPlayer.macroPhase();
        if (unit == UnitType.SOLDIER) {
            if (stats.frontActive && stats.frontLoc != null && (phase == 2 || stats.enemyNear)) return stats.frontLoc;
            if (stats.ruinLoc != null) return stats.ruinLoc;
            if (stats.frontActive && stats.frontLoc != null) return stats.frontLoc;
            if (phase == 2 && RobotPlayer.mirrorTower != null) return RobotPlayer.mirrorTower;
            return RobotPlayer.bestSectorAnchor(loc, RobotPlayer.turnCount);
        }
        if (unit == UnitType.SPLASHER) {
            if (stats.frontActive && stats.frontLoc != null) return stats.frontLoc;
            if (stats.enemyLoc != null) return stats.enemyLoc;
            if (phase == 2 && RobotPlayer.mirrorTower != null) return RobotPlayer.mirrorTower;
            return RobotPlayer.bestSectorAnchor(loc, RobotPlayer.turnCount);
        }
        if (stats.frontActive && stats.frontLoc != null) return stats.frontLoc;
        if (stats.enemyLoc != null) return stats.enemyLoc;
        if (stats.ruinLoc != null) return stats.ruinLoc;
        return RobotPlayer.bestSectorAnchor(loc, RobotPlayer.turnCount);
    }

    static void attackWeakestEnemy(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0) return;

        RobotInfo weakest = enemies[0];
        for (RobotInfo enemy : enemies) {
            if (enemy.health < weakest.health || (enemy.health == weakest.health && enemy.type == UnitType.SOLDIER)) {
                weakest = enemy;
            }
        }

        if (rc.canAttack(weakest.location)) {
            rc.attack(weakest.location);
        }
    }
}
