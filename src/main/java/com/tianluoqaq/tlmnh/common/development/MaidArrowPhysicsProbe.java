package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.combat.ArrowInterceptSolver;
import com.tianluoqaq.tlmnh.common.combat.ArrowPhysics;
import com.tianluoqaq.tlmnh.common.combat.BallisticVector;

/** 实际RC2箭实体的同步自由飞行检查；不等于自然tick武器、碰撞或命中率benchmark */
public final class MaidArrowPhysicsProbe {

    private MaidArrowPhysicsProbe() {}

    public static void run(WorldServer world) {
        require(world.provider.dimensionId == 0, "Arrow profile fixture requires the overworld");
        var spawn = world.getSpawnPoint();
        int x = spawn.posX, z = spawn.posZ;
        for (int dx = -3; dx <= 32; dx++) for (int dz = -3; dz <= 12; dz++) {
            require(world.blockExists(x + dx, 230, z + dz), "Arrow fixture touches an unloaded chunk");
            for (int y = 226; y <= 236; y++)
                require(world.isAirBlock(x + dx, y, z + dz), "Arrow fixture is obstructed");
        }
        var arrows = new ArrayList<EntityArrow>();
        var water = new ArrayList<int[]>();
        var report = new JsonObject();
        report.addProperty(
            "scope",
            "actual spawned EntityArrow.onUpdate; uniform media and zero-spread point interception only");
        report.addProperty("dimension", world.provider.dimensionId);
        report.addProperty("benchmarkVerified", false);
        var traces = new JsonArray();
        var intercepts = new JsonArray();
        try {
            var origin = new BallisticVector(x + .5, 230, z + .5);
            traces.add(
                trace(
                    world,
                    arrows,
                    origin,
                    new BallisticVector(.25, .1, -.125),
                    ArrowPhysics.OVERWORLD_AIR,
                    12,
                    false));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 227; y <= 233; y++) {
                require(world.setBlock(x + dx, y, z + dz, Blocks.water, 0, 2), "Temporary water fixture rejected");
                water.add(new int[] { x + dx, y, z + dz });
            }
            traces.add(
                trace(world, arrows, origin, new BallisticVector(.1, 0, .05), ArrowPhysics.OVERWORLD_WATER, 6, true));
            for (var block : water)
                require(world.setBlockToAir(block[0], block[1], block[2]), "Water fixture restoration rejected");
            water.clear();
            for (var motion : new BallisticVector[] { BallisticVector.ZERO, new BallisticVector(0, 0, .25),
                new BallisticVector(-.15, 0, 0), new BallisticVector(.15, 0, 0), new BallisticVector(0, .1, .1) }) {
                intercepts.add(intercept(world, arrows, origin, origin.add(new BallisticVector(20, 0, 0)), motion));
            }
            report.add("physicsTraces", traces);
            report.add("intercepts", intercepts);
            report.addProperty("passed", true);
            TLMNotHard.LOG.info("TLMNH_ARROW_PHYSICS_TEST_PASS {}", report);
        } finally {
            for (var arrow : arrows) arrow.setDead();
            for (var block : water) world.setBlockToAir(block[0], block[1], block[2]);
        }
    }

    private static JsonObject trace(WorldServer world, List<EntityArrow> arrows, BallisticVector origin,
        BallisticVector velocity, ArrowPhysics physics, int ticks, boolean water) {
        var arrow = create(world, arrows, origin);
        arrow.motionX = velocity.x;
        arrow.motionY = velocity.y;
        arrow.motionZ = velocity.z;
        var expected = new ArrowPhysics.State(origin, velocity);
        double maximum = 0;
        for (int tick = 0; tick < ticks; tick++) {
            expected = physics.step(expected);
            arrow.onUpdate();
            require(!arrow.isDead && arrow.isInWater() == water, "Native trace changed medium or collided");
            double error = Math.max(
                position(arrow).subtract(expected.position)
                    .length(),
                velocity(arrow).subtract(expected.velocity)
                    .length());
            maximum = Math.max(maximum, error);
            require(error == 0, "Effective RC2 arrow physics differs at tick " + tick + ": " + error);
        }
        var value = new JsonObject();
        value.addProperty("medium", water ? "water" : "air");
        value.addProperty("ticks", ticks);
        value.addProperty("drag", physics.drag);
        value.addProperty("gravity", physics.gravity);
        value.addProperty("maximumRawDelta", maximum);
        return value;
    }

    private static JsonObject intercept(WorldServer world, List<EntityArrow> arrows, BallisticVector origin,
        BallisticVector target, BallisticVector motion) {
        var solution = ArrowInterceptSolver.solve(origin, target, motion, 3, 80, ArrowPhysics.OVERWORLD_AIR);
        require(solution.isSolved(), "Reviewed intercept fixture was not solved");
        double residual = nativeResidual(world, arrows, origin, target, motion, solution);
        // 原生heading用float平方根重新归一化，允许小于1毫米的中心点舍入差；自由飞行逐tick仍要求精确
        require(residual < .001, "Native point-intercept residual exceeded 1 mm: " + residual);
        var staticAim = ArrowInterceptSolver
            .solve(origin, target, BallisticVector.ZERO, 3, 80, ArrowPhysics.OVERWORLD_AIR);
        require(staticAim.isSolved(), "Gravity-compensated static control failed");
        double staticResidual = nativeResidual(world, arrows, origin, target, motion, staticAim);
        if (motion.length() > .01)
            require(staticResidual > .5, "Moving fixture did not distinguish lead from static gravity compensation");
        var value = new JsonObject();
        value.addProperty("targetVx", motion.x);
        value.addProperty("targetVy", motion.y);
        value.addProperty("targetVz", motion.z);
        value.addProperty("flightTicks", solution.flightTicks);
        value.addProperty("evaluatedIntervals", solution.evaluatedIntervals);
        value.addProperty("nativeLeadResidual", residual);
        value.addProperty("nativeStaticGravityResidual", staticResidual);
        return value;
    }

    private static double nativeResidual(WorldServer world, List<EntityArrow> arrows, BallisticVector origin,
        BallisticVector target, BallisticVector motion, ArrowInterceptSolver.Solution solution) {
        var arrow = create(world, arrows, origin);
        arrow.setThrowableHeading(
            solution.initialVelocity.x,
            solution.initialVelocity.y,
            solution.initialVelocity.z,
            3,
            0);
        double time = solution.flightTicks;
        int whole = (int) time;
        for (int tick = 0; tick < whole; tick++) {
            arrow.onUpdate();
            require(!arrow.isDead && !arrow.isInWater(), "Intercept arrow collided or changed medium");
        }
        var before = position(arrow);
        arrow.onUpdate();
        require(!arrow.isDead && !arrow.isInWater(), "Intercept final segment invalid");
        var actual = before.add(
            position(arrow).subtract(before)
                .scale(time - whole));
        return actual.subtract(target.add(motion.scale(time)))
            .length();
    }

    private static EntityArrow create(WorldServer world, List<EntityArrow> arrows, BallisticVector origin) {
        var arrow = new EntityArrow(world, origin.x, origin.y, origin.z);
        arrow.canBePickedUp = 0;
        require(world.spawnEntityInWorld(arrow), "Actual arrow spawn rejected");
        arrows.add(arrow);
        return arrow;
    }

    private static BallisticVector position(EntityArrow arrow) {
        return new BallisticVector(arrow.posX, arrow.posY, arrow.posZ);
    }

    private static BallisticVector velocity(EntityArrow arrow) {
        return new BallisticVector(arrow.motionX, arrow.motionY, arrow.motionZ);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("TLMNH arrow physics probe failed: " + name);
    }
}
