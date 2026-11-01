package com.tianluoqaq.tlmnh.common.development;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 一个真实联网实体的水中姿态与睡眠同步，临时方块严格限制在开发虚空 */
public final class MaidPoseRuntimeProbe {

    private static EntityMaid maid;
    private static WorldServer world;

    private MaidPoseRuntimeProbe() {}

    public static boolean isRegistered(EntityMaid value) {
        return value == maid;
    }

    public static void stage(EntityPlayerMP observer, String action) {
        AnimationLab.verifyObserver(observer);
        if (action.equals("cleanup")) {
            world = (WorldServer) observer.worldObj;
            if (maid != null) {
                require(maid.worldObj == world, "Same-world fixture cleanup required");
                maid.setDead();
                world.removeEntity(maid);
                maid = null;
            }
            clearPool();
            world = null;
        } else if (action.equals("swim")) {
            require(maid == null, "Previous pose fixture must be cleaned");
            world = (WorldServer) observer.worldObj;
            for (int x = 3; x <= 7; x++) for (int z = -2; z <= 2; z++)
                for (int y = 65; y <= 69; y++) require(world.isAirBlock(x, y, z), "Pure air temporary pool required");
            for (int x = 3; x <= 7; x++) for (int z = -2; z <= 2; z++) for (int y = 65; y <= 69; y++) world.setBlock(
                x,
                y,
                z,
                x == 3 || x == 7 || z == -2 || z == 2 || y == 65 || y == 69 ? Blocks.glass : Blocks.water,
                0,
                2);
            maid = new EntityMaid(world);
            require(maid.bindTo(observer.getUniqueID()), "Fresh maid owner binding required");
            maid.getEntityData()
                .setBoolean("TLMNHAquaticProbe", true);
            maid.setPosition(5.5, 66.2, .5);
            require(world.spawnEntityInWorld(maid), "Registered aquatic maid required");
        } else {
            require(
                maid != null && maid.isEntityAlive() && maid.worldObj == observer.worldObj,
                "Live same-world fixture required");
            switch (action) {
                case "ceiling":
                    require(
                        maid.height < .61F && maid.getAquaticController()
                            .getManager()
                            .wantToSwim(),
                        "Natural swimming must precede low ceiling");
                    for (int x = 4; x <= 6; x++)
                        for (int z = -1; z <= 1; z++) world.setBlock(x, 67, z, Blocks.glass, 0, 2);
                    break;
                case "stand":
                    maid.getBrainCoordinator()
                        .setSuspended(true);
                    clearPool();
                    maid.setPosition(5.5, 65, .5);
                    maid.motionX = maid.motionY = maid.motionZ = 0;
                    break;
                case "sleep":
                    maid.setPose(MaidPose.SLEEPING, true);
                    break;
                case "restriction":
                    maid.setPosition(5.5, 65, .5);
                    require(
                        maid.getHomeArea()
                            .setEnabled(true),
                        "Restriction metadata setup required");
                    var point = maid.getHomeArea()
                        .restrictionCenter();
                    TLMNotHard.LOG.info(
                        "TLMNH_RESTRICTION105_SERVER uuid={} xyz={},{},{} radius={}",
                        maid.getUniqueID(),
                        point.x,
                        point.y,
                        point.z,
                        maid.getHomeArea()
                            .restrictionRadius());
                    world.setEntityState(maid, (byte) 46);
                    break;
                case "wake":
                    maid.setPose(MaidPose.SLEEPING, false);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown aquatic stage");
            }
        }
        TLMNotHard.LOG.info(
            "TLMNH_AQUATIC_STAGE_PASS action={} natural_swim={} pose={} height={} eye={}",
            action,
            maid != null && maid.getAquaticController()
                .getManager()
                .wantToSwim(),
            maid == null ? "removed" : maid.getEntityPose(),
            maid == null ? 0 : maid.height,
            maid == null ? 0 : maid.getEyeHeight());
    }

    private static void clearPool() {
        for (int x = 3; x <= 7; x++) for (int z = -2; z <= 2; z++) for (int y = 65; y <= 69; y++) {
            var block = world.getBlock(x, y, z);
            require(
                block == Blocks.air || block == Blocks.water || block == Blocks.flowing_water || block == Blocks.glass,
                "Unexpected block in this fixture's owned temporary pool");
        }
        for (int x = 3; x <= 7; x++)
            for (int z = -2; z <= 2; z++) for (int y = 65; y <= 69; y++) world.setBlock(x, y, z, Blocks.air, 0, 2);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
