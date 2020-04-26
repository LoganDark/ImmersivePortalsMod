package com.qouteall.immersive_portals.teleportation;

import com.qouteall.immersive_portals.CHelper;
import com.qouteall.immersive_portals.Helper;
import com.qouteall.immersive_portals.McHelper;
import com.qouteall.immersive_portals.ducks.IEEntity;
import com.qouteall.immersive_portals.portal.Portal;
import com.qouteall.immersive_portals.portal.global_portals.GlobalTrackedPortal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

public class CollisionHelper {
    
    //cut a box with a plane
    //the facing that normal points to will be remained
    //return null for empty box
    private static Box clipBox(Box box, Vec3d planePos, Vec3d planeNormal) {
        
        boolean xForward = planeNormal.x > 0;
        boolean yForward = planeNormal.y > 0;
        boolean zForward = planeNormal.z > 0;
        
        Vec3d pushedPos = new Vec3d(
            xForward ? box.x1 : box.x2,
            yForward ? box.y1 : box.y2,
            zForward ? box.z1 : box.z2
        );
        Vec3d staticPos = new Vec3d(
            xForward ? box.x2 : box.x1,
            yForward ? box.y2 : box.y1,
            zForward ? box.z2 : box.z1
        );
        
        double tOfPushedPos = Helper.getCollidingT(planePos, planeNormal, pushedPos, planeNormal);
        boolean isPushedPosInFrontOfPlane = tOfPushedPos < 0;
        if (isPushedPosInFrontOfPlane) {
            //the box is not cut by plane
            return box;
        }
        boolean isStaticPosInFrontOfPlane = Helper.isInFrontOfPlane(
            staticPos, planePos, planeNormal
        );
        if (!isStaticPosInFrontOfPlane) {
            //the box is fully cut by plane
            return null;
        }
        
        //the plane cut the box halfly
        Vec3d afterBeingPushed = pushedPos.add(planeNormal.multiply(tOfPushedPos));
        return new Box(afterBeingPushed, staticPos);
    }
    
    private static boolean shouldCollideWithPortal(Entity entity, Portal portal) {
        return portal.isTeleportable() &&
            portal.isInFrontOfPortal(entity.getCameraPosVec(1));
    }
    
    public static Vec3d handleCollisionHalfwayInPortal(
        Entity entity,
        Vec3d attemptedMove,
        Portal collidingPortal,
        Function<Vec3d, Vec3d> handleCollisionFunc
    ) {
        Box originalBoundingBox = entity.getBoundingBox();
        
        Vec3d thisSideMove = getThisSideMove(
            entity, attemptedMove, collidingPortal,
            handleCollisionFunc, originalBoundingBox
        );
        
        Vec3d otherSideMove = getOtherSideMove(
            entity, attemptedMove, collidingPortal,
            handleCollisionFunc, originalBoundingBox
        );
        
        //handle stepping onto slab or stair through portal
        if (attemptedMove.y < 0) {
            if (otherSideMove.y > 0) {
                //stepping on the other side
                return new Vec3d(
                    absMin(thisSideMove.x, otherSideMove.x),
                    otherSideMove.y,
                    absMin(thisSideMove.z, otherSideMove.z)
                );
            }
            else if (thisSideMove.y > 0) {
                //stepping on this side
                //re-calc collision with intact collision box
                //the stepping is shorter using the clipped collision box
                Vec3d newThisSideMove = handleCollisionFunc.apply(attemptedMove);
                
                //apply the stepping move for the other side
                Vec3d newOtherSideMove = getOtherSideMove(
                    entity, newThisSideMove, collidingPortal,
                    handleCollisionFunc, originalBoundingBox
                );
    
                return newOtherSideMove;
            }
        }
        
        return new Vec3d(
            absMin(thisSideMove.x, otherSideMove.x),
            absMin(thisSideMove.y, otherSideMove.y),
            absMin(thisSideMove.z, otherSideMove.z)
        );
    }
    
    private static double absMin(double a, double b) {
        return Math.abs(a) < Math.abs(b) ? a : b;
    }
    
    private static Vec3d getOtherSideMove(
        Entity entity,
        Vec3d attemptedMove,
        Portal collidingPortal,
        Function<Vec3d, Vec3d> handleCollisionFunc,
        Box originalBoundingBox
    ) {
        Box boxOtherSide = getCollisionBoxOtherSide(
            collidingPortal,
            originalBoundingBox,
            attemptedMove
        );
        if (boxOtherSide == null) {
            return attemptedMove;
        }
        
        //switch world and check collision
        World oldWorld = entity.world;
        Vec3d oldPos = entity.getPos();
        Vec3d oldLastTickPos = McHelper.lastTickPosOf(entity);
        
        entity.world = getWorld(entity.world.isClient, collidingPortal.dimensionTo);
        entity.setBoundingBox(boxOtherSide);
        
        Vec3d move2 = handleCollisionFunc.apply(attemptedMove);
        
        entity.world = oldWorld;
        McHelper.setPosAndLastTickPos(entity, oldPos, oldLastTickPos);
        entity.setBoundingBox(originalBoundingBox);
        
        return collidingPortal.untransformLocalVec(move2);
    }
    
    private static Vec3d getThisSideMove(
        Entity entity,
        Vec3d attemptedMove,
        Portal collidingPortal,
        Function<Vec3d, Vec3d> handleCollisionFunc,
        Box originalBoundingBox
    ) {
        Box boxThisSide = getCollisionBoxThisSide(
            collidingPortal, originalBoundingBox, attemptedMove
        );
        if (boxThisSide == null) {
            return attemptedMove;
        }
        
        entity.setBoundingBox(boxThisSide);
        Vec3d move1 = handleCollisionFunc.apply(attemptedMove);
        
        entity.setBoundingBox(originalBoundingBox);
        
        return move1;
    }
    
    private static Box getCollisionBoxThisSide(
        Portal portal,
        Box originalBox,
        Vec3d attemptedMove
    ) {
        //cut the collision box a little bit more for horizontal portals
        //because the box will be stretched by attemptedMove when calculating collision
        Vec3d cullingPos = portal.getPos().subtract(attemptedMove);
        return clipBox(
            originalBox,
            cullingPos,
            portal.getNormal()
        );
    }
    
    private static Box getCollisionBoxOtherSide(
        Portal portal,
        Box originalBox,
        Vec3d attemptedMove
    ) {
        return clipBox(
            portal.transformBox(originalBox),
            portal.transformLocalVec(attemptedMove.multiply(-1)),
            portal.getContentDirection()
        );
    }
    
    public static World getWorld(boolean isClient, DimensionType dimension) {
        if (isClient) {
            return CHelper.getClientWorld(dimension);
        }
        else {
            return McHelper.getServer().getWorld(dimension);
        }
    }
    
    //world.getEntities is not reliable
    //it has a small chance to ignore collided entities
    //this would cause player to fall through floor when halfway though portal
    //use entity.getCollidingPortal() and do not use this
    public static Portal getCollidingPortalUnreliable(Entity entity) {
        Box box = entity.getBoundingBox().stretch(entity.getVelocity());
        
        return getCollidingPortalRough(entity, box).filter(
            portal -> shouldCollideWithPortal(
                entity, portal
            )
        ).min(
            Comparator.comparingDouble(p -> p.getY())
        ).orElse(null);
    }
    
    public static Stream<Portal> getCollidingPortalRough(Entity entity, Box box) {
        World world = entity.world;
        
        List<GlobalTrackedPortal> globalPortals = McHelper.getGlobalPortals(world);
        
        List<Portal> collidingNormalPortals = McHelper.getEntitiesRegardingLargeEntities(
            world,
            box,
            10,
            Portal.class,
            p -> true
        );
        
        if (globalPortals == null) {
            return collidingNormalPortals.stream();
        }
        
        return Stream.concat(
            collidingNormalPortals.stream(),
            globalPortals.stream()
                .filter(
                    p -> p.getBoundingBox().expand(0.5).intersects(box)
                )
        );
    }
    
    public static boolean isCollidingWithAnyPortal(Entity entity) {
        return ((IEEntity) entity).getCollidingPortal() != null;
    }
    
    public static boolean isNearbyPortal(Entity entity) {
        return getCollidingPortalRough(
            entity,
            entity.getBoundingBox().expand(1)
        ).findAny().isPresent();
    }
    
    public static Box getActiveCollisionBox(Entity entity) {
        Portal collidingPortal = ((IEEntity) entity).getCollidingPortal();
        if (collidingPortal != null) {
            Box thisSideBox = getCollisionBoxThisSide(
                collidingPortal,
                entity.getBoundingBox(),
                Vec3d.ZERO //is it ok?
            );
            if (thisSideBox != null) {
                return thisSideBox;
            }
            else {
                return new Box(0, 0, 0, 0, 0, 0);
            }
        }
        else {
            return entity.getBoundingBox();
        }
    }
}
