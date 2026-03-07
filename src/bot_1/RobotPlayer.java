package bot_1;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {

    static int turnCount = 0;
    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH, 
        Direction.NORTHEAST, 
        Direction.EAST, 
        Direction.SOUTHEAST,
        Direction.SOUTH, 
        Direction.SOUTHWEST, 
        Direction.WEST, 
        Direction.NORTHWEST,
    };

    // Message Declare 
    // Tipe msg di bit 30-31 
    // (00=ada musuh, 01=ketemu ruin, 10=cat dikit)
    // Koor x ada di bit 29-15, Koor y ada di bit 14-0 
    static final int MSG_ENEMY    = 0;
    static final int MSG_RUIN     = 1 << 30;
    static final int MSG_PAINTLOW = 2 << 30; 

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
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
                System.out.println("GameActionException: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Exception: " + e.getMessage());
            } finally {
                Clock.yield();
            }
        }
    }

    // prioritaskan soldier, baru mopper
    public static void runTower(RobotController rc) throws GameActionException {
        // atk musuh yang dekat (protect tower)
        attackNearestEnemy(rc);

        boolean paintLow = false; // untuk msg
        boolean enemyNear = false; // untuk msg
        boolean spawnSol = ((turnCount % 4 != 0) && !paintLow && !enemyNear); // sol : mop = 3 : 1

        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (spawnSol && rc.canBuildRobot(UnitType.SOLDIER, spawnLoc)) {
                rc.buildRobot(UnitType.SOLDIER, spawnLoc);
                break;
            } else if (!spawnSol && rc.canBuildRobot(UnitType.MOPPER, spawnLoc)) {
                rc.buildRobot(UnitType.MOPPER, spawnLoc);
                break;
            }
        }

        // Baca msg
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int data = m.getBytes();
            int msgType = data & (3 << 30);
            if (msgType == MSG_PAINTLOW) {
                paintLow = true;
            } else if (msgType == MSG_ENEMY){
                enemyNear = true;
            }
        }
    }

    // atk musuh terdekat
    static void attackNearestEnemy(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0 && rc.isActionReady()) {
            // atk musuh HP terendah
            RobotInfo weakest = enemies[0];
            for (RobotInfo e : enemies) {
                if (e.health < weakest.health) {
                    weakest = e;
                }
            }
            if (rc.canAttack(weakest.location)) {
                rc.attack(weakest.location);
            }
        }
    }

    public static void runSoldier(RobotController rc) throws GameActionException {

    }

    public static void runMopper(RobotController rc) throws GameActionException {

    }

    public static void runSplasher(RobotController rc) throws GameActionException {

    }

    
}