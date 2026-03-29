package alternative_bots_1;

import battlecode.common.*;

final class Tower {

    static final class SpawnStats {
        boolean paintLow, enemyNear;
        MapLocation ruinLoc;
        int soldierCount, mopperCount, splasherCount, lowPaintSoldiers;
        int enemyTiles, emptyTiles, allyTiles, nearbyAllies;
    }

    private Tower() {}

    static void run(RobotController rc) throws GameActionException {
        attackWeakestEnemy(rc);

        SpawnStats stats = collectStats(rc);

        // Prioritaskan upgrade tower saat resource cukup dan kondisi mendukung
        MapLocation myLoc = rc.getLocation();
        if (rc.canUpgradeTower(myLoc) && shouldUpgradeNow(rc, stats)) {
            rc.upgradeTower(myLoc);
            return;
        }

        UnitType myType = rc.getType();
        // Recycle money tower saat aman untuk all in push
        if ((myType == UnitType.LEVEL_ONE_MONEY_TOWER || myType == UnitType.LEVEL_TWO_MONEY_TOWER
                || myType == UnitType.LEVEL_THREE_MONEY_TOWER) && rc.getChips() > 5000
                && rc.getPaint() < 50 && !stats.enemyNear && RobotPlayer.turnCount > 300 && rc.getNumberTowers() >= 5) {
            for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
                if (ally.type == UnitType.SOLDIER && ally.paintAmount > 50) {
                    rc.disintegrate();
                    return;
                }
            }
        }

        // spawn unit
        UnitType spawnUnit = greedySelectUnit(stats);

        if (RobotPlayer.turnCount > 150 && stats.nearbyAllies >= 6 && RobotPlayer.turnCount % 3 != 0) return;

        Direction bestDirection = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction direction : RobotPlayer.directions) {
            MapLocation spawnLoc = rc.getLocation().add(direction);
            if (!rc.canBuildRobot(spawnUnit, spawnLoc)) continue;
            int score = evalLoc(rc, spawnLoc, spawnUnit, stats.ruinLoc);
            if (score > bestScore) {
                bestScore = score;
                bestDirection = direction;
            }
        }

        if (bestDirection != null) {
            rc.buildRobot(spawnUnit, rc.getLocation().add(bestDirection));
        }
    }

    static boolean shouldUpgradeNow(RobotController rc, SpawnStats stats) {
        UnitType myType = rc.getType();
        if (!isUpgradeableTower(myType)) return false;

        int chips = rc.getChips();
        int paint = rc.getPaint();
        int towerCount = rc.getNumberTowers();

        if (chips < 1800 || paint < 80) {
            return false;
        }

        if (towerCount >= 8 && chips >= 2200 && paint >= 120) {
            return true;
        }

        // Saat diserang, prioritaskan upgrade tower defense/paint
        if (stats.enemyNear) {
            if (isDefenseTower(myType) && chips >= 1800) {
                return true;
            }
            if (isPaintTower(myType) && chips >= 2200 && paint >= 140) {
                return true;
            }
            return false;
        }

        // Saat aman, fokus money tower
        if (isMoneyTower(myType) && chips >= 2000 && paint >= 100) {
            return true;
        }

        // fokus paint tower saat banyak low paint
        if (isPaintTower(myType) && stats.lowPaintSoldiers >= 2 && chips >= 2100) {
            return true;
        }

        // def tower upgrade saat banyak musuh
        if (isDefenseTower(myType) && (stats.enemyTiles >= 6 || towerCount < 5) && chips >= 2000) {
            return true;
        }

        return false;
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

        for (Message message : rc.readMessages(-1)) {
            int data = message.getBytes(), type = data & (3 << 30);
            if (type == RobotPlayer.MSG_PAINTLOW){
                stats.paintLow = true;
            } else if (type == RobotPlayer.MSG_ENEMY){
                stats.enemyNear = true;
            } else if (type == RobotPlayer.MSG_RUIN){
                stats.ruinLoc = new MapLocation((data >> 15) & 0x7FFF, data & 0x7FFF);
            }
        }

        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.type == UnitType.SOLDIER) {
                stats.soldierCount++;
                int paintPercent = (int) (100.0 * ally.paintAmount / ally.type.paintCapacity);
                if (paintPercent < 40){
                    stats.lowPaintSoldiers++;
                }
            }
            if (ally.type == UnitType.MOPPER){
                stats.mopperCount++;
            }

            if (ally.type == UnitType.SPLASHER){
                stats.splasherCount++;
            }
        }

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            PaintType paint = tile.getPaint();
            if (RobotPlayer.isEnemyPaint(paint)){
                stats.enemyTiles++;
            } else if (paint == PaintType.EMPTY && tile.isPassable()){
                stats.emptyTiles++;
            } else if (paint.isAlly()){
                stats.allyTiles++;
            }
        }

        // stats ally sekitar tower.
        stats.nearbyAllies = rc.senseNearbyRobots(9, rc.getTeam()).length;
        return stats;
    }

    static UnitType greedySelectUnit(SpawnStats s) {
        int soldierValue = 12, mopperValue = 6, splasherValue = 6;

        if (s.ruinLoc != null) soldierValue += 70;
        if (s.enemyNear) soldierValue += 35;
        if (s.enemyTiles >= 6) soldierValue += 20;
        soldierValue += s.soldierCount < 3 ? 45 : Math.max(0, 24 - s.soldierCount * 2);

        if (s.paintLow) mopperValue += 60;
        if (s.enemyNear) mopperValue += 35;
        if (s.lowPaintSoldiers >= 2) mopperValue += 40;
        mopperValue += s.enemyTiles * 4;
        if (s.mopperCount * 2 < s.soldierCount) mopperValue += 20;
        mopperValue -= s.mopperCount * 6;

        if (s.emptyTiles > s.allyTiles) splasherValue += 35;
        splasherValue += Math.min(36, s.emptyTiles / 2);
        splasherValue += Math.min(30, s.enemyTiles * 2);
        if (RobotPlayer.turnCount < 140) splasherValue += 20;
        if (s.splasherCount >= 3) splasherValue -= 35;
        if (RobotPlayer.turnCount > 180 && s.splasherCount < 2) splasherValue += 25;
        if (s.enemyTiles == 0 && s.emptyTiles < 6) splasherValue -= 20;

        // Saat diserang, prior soldier dan mopper
        if (s.enemyNear) {
            soldierValue += 35;
            mopperValue += 20;
            splasherValue -= 25;
        }

        if (mopperValue >= soldierValue && mopperValue >= splasherValue) {
            return UnitType.MOPPER;
        }
        if (splasherValue >= soldierValue) {
            return UnitType.SPLASHER;
        }
        return UnitType.SOLDIER;
    }

    static int evalLoc(RobotController rc, MapLocation loc, UnitType unit, MapLocation ruinLoc) throws GameActionException {
        // prioritaskan area yang cepat menambah wilayah
        int score = 0;
        PaintType paint = rc.senseMapInfo(loc).getPaint();
        if (paint == PaintType.EMPTY) {
            score -= 1;
        } else if (!paint.isAlly()) {
            if (unit == UnitType.MOPPER) {
                score -= 4;
            } else if (unit == UnitType.SPLASHER) {
                score -= 3;
            } else {
                score -= 2;
            }
        }

        if (unit == UnitType.SOLDIER && ruinLoc != null) {
            score -= loc.distanceSquaredTo(ruinLoc) / 2;
        }

        if (unit == UnitType.SPLASHER) {
            for (MapInfo nearby : rc.senseNearbyMapInfos(loc, 9)) {
                if (!nearby.isPassable()) continue;
                if (nearby.getPaint() == PaintType.EMPTY) {
                    score += 3;
                } else if (RobotPlayer.isEnemyPaint(nearby.getPaint())) {
                    score += 4;
                }
            }
        }

        if (unit == UnitType.MOPPER) {
            for (MapInfo nearby : rc.senseNearbyMapInfos(loc, 4)) {
                if (RobotPlayer.isEnemyPaint(nearby.getPaint())) {
                    score += 5;
                }
            }
        }

        int enemyCount = rc.senseNearbyRobots(loc, 20, rc.getTeam().opponent()).length;
        score -= (unit == UnitType.MOPPER ? 6 : 3) * enemyCount;
        score += rc.senseNearbyRobots(loc, 16, rc.getTeam()).length;
        if (unit == UnitType.SPLASHER && RobotPlayer.spawnTower != null){
            score += loc.distanceSquaredTo(RobotPlayer.spawnTower) / 8;
        }

        if (unit == UnitType.SOLDIER && enemyCount > 0){
            score += 6;
        }

        return score;
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