package bot_1;

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
	static final int SECTOR_SIZE = 10;

	static MapLocation[] recentLocs = new MapLocation[10];
	static int recentIdx = 0, stuckCount = 0, targetTurn = 0;
	static int mapWidth = -1, mapHeight = -1, mySecX = -1, mySecY = -1;
	static MapLocation lastLoc = null, exploreTarget = null, spawnTower = null, mirrorTower = null, lockedEnemyLoc = null;
	static boolean symChecked = false, refillMode = false, isobsFollowing = false, obsFollowLeft = true;
	static int obsFollowTurns = 0, obsFollowStartDist = Integer.MAX_VALUE, combatLockTurns = 0;
	static Direction obsWallDirection = Direction.NORTH;
	static MapLocation obsNavTarget = null;

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

    // cek bisa send msg atau gk
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

    // prediksi musuh dengan teknik simetri peta
	static void initState(RobotController rc) throws GameActionException {
		if (symChecked) return;

		symChecked = true;
		mapWidth = rc.getMapWidth();
		mapHeight = rc.getMapHeight();
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

		if (spawnTower == null) {
			return;
		}

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

	// Menghindari objek, misal wall agar tidak stuck
	static boolean tryMoveTowardObs(RobotController rc, MapLocation target) throws GameActionException {
		if (!rc.isMovementReady() || target == null) {
			return false;
		}

		MapLocation myLoc = rc.getLocation();
		if (myLoc.equals(target)) {
			return false;
		}

		if (obsNavTarget == null || !obsNavTarget.equals(target)) {
			isobsFollowing = false;
			obsFollowTurns = 0;
			obsNavTarget = target;
			obsFollowStartDist = myLoc.distanceSquaredTo(target);
		}

		Direction toTarget = myLoc.directionTo(target);
		if (rc.canMove(toTarget)) {
			if (!isobsFollowing || myLoc.distanceSquaredTo(target) <= obsFollowStartDist || obsFollowTurns > 12) {
				rc.move(toTarget);
				isobsFollowing = false;
				obsFollowTurns = 0;
				obsFollowStartDist = rc.getLocation().distanceSquaredTo(target);
				obsWallDirection = toTarget;
				return true;
			}
		}

		if (!isobsFollowing) {
			isobsFollowing = true;
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
					isobsFollowing = false;
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
				score += 6;
				continue;
			}
			PaintType paint = tile.getPaint();
			if (isEnemyPaint(paint)) {
				score += 7;
			} else if (paint == PaintType.EMPTY) {
				score += 5;
			}
		}
		return score;
	}

	static void updateExploreTarget(RobotController rc, MapLocation myLoc) throws GameActionException {
		int frontierCount = 0;
		int bestDistance = 0;
		MapLocation bestFrontier = null;
		for (MapInfo tile : rc.senseNearbyMapInfos()) {
			if (!tile.isPassable()) continue;
			PaintType paint = tile.getPaint();
			if (paint == PaintType.ALLY_PRIMARY || paint == PaintType.ALLY_SECONDARY) continue;
			frontierCount++;
			int distance = spawnTower != null ? tile.getMapLocation().distanceSquaredTo(spawnTower) : myLoc.distanceSquaredTo(tile.getMapLocation());
			if (distance > bestDistance) {
				bestDistance = distance;
				bestFrontier = tile.getMapLocation();
			}
		}

		if (bestFrontier != null && frontierCount > 3) {
			exploreTarget = bestFrontier;
		} else {
			assignSector(rc);
			MapLocation sectorTarget = sectorCenter();
			if (myLoc.distanceSquaredTo(sectorTarget) <= 16) {
				rotateSector();
				sectorTarget = sectorCenter();
			}
			exploreTarget = sectorTarget;
			if (spawnTower != null && sectorTarget.distanceSquaredTo(spawnTower) < 50 && mirrorTower != null) {
				exploreTarget = mirrorTower;
			}
		}
	}

	static MapLocation sectorCenter() {
		return new MapLocation(Math.min(mapWidth - 1, mySecX * SECTOR_SIZE + SECTOR_SIZE / 2), Math.min(mapHeight - 1, mySecY * SECTOR_SIZE + SECTOR_SIZE / 2));
	}

	static void assignSector(RobotController rc) {
		if (mapWidth <= 0 || mySecX >= 0) return;
		int sectorWidth = (mapWidth + SECTOR_SIZE - 1) / SECTOR_SIZE;
		int sectorHeight = (mapHeight + SECTOR_SIZE - 1) / SECTOR_SIZE;
		mySecX = (rc.getID() * 7) % sectorWidth;
		mySecY = (rc.getID() * 13) % sectorHeight;
	}

	static void rotateSector() {
		if (mapWidth <= 0) return;
		int sectorWidth = (mapWidth + SECTOR_SIZE - 1) / SECTOR_SIZE;
		int sectorHeight = (mapHeight + SECTOR_SIZE - 1) / SECTOR_SIZE;
		mySecX++;
		if (mySecX >= sectorWidth) {
			mySecX = 0;
			mySecY = (mySecY + 1) % sectorHeight;
		}
	}

	static int recentVisitPenalty(MapLocation loc) {
		int count = 0;
		for (MapLocation recent : recentLocs) {
			if (recent != null && recent.equals(loc)) {
				count++;
			}
		}
		if (count == 0) {
			return 0;
		}
		if (count == 1) {
			return 12;
		}
		if (count == 2) {
			return 28;
		}
		if (count == 3) {
			return 48;
		}
		return 60;
	}
}
