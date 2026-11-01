package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 一次自然世界序列验证走位、实际箭结算和切换后的输入清理 */
public final class MaidRangedMovementProbe {

    private static MaidRangedMovementProbe pending;
    private final WorldServer world;
    private EntityMaid maid;
    private EntityZombie target;
    private EntityPlayerMP owner;
    private int x, z, ticks;
    private boolean floorReady, finished;
    private double startX, startZ, stoppedX, stoppedZ;
    private long stoppedShots;
    private float targetHealthBeforeShots;

    private MaidRangedMovementProbe(WorldServer world) {
        this.world = world;
    }

    public static void start(WorldServer world) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && world.provider.dimensionId == 0 && pending == null,
            "Dedicated test overworld and one episode required");
        var probe = new MaidRangedMovementProbe(world);
        pending = probe;
        try {
            probe.begin();
            FMLCommonHandler.instance()
                .bus()
                .register(probe);
        } catch (RuntimeException | Error failure) {
            probe.finish(false, failure.toString());
            throw failure;
        }
    }

    private void begin() {
        var spawn = world.getSpawnPoint();
        x = spawn.posX + 12;
        z = spawn.posZ + 56;
        for (int px = x - 14; px <= x + 14; px++) for (int pz = z - 10; pz <= z + 10; pz++) {
            world.getChunkFromBlockCoords(px, pz);
            for (int py = 99; py <= 104; py++) require(world.isAirBlock(px, py, pz), "Empty test volume required");
        }
        floorReady = true;
        for (int px = x - 14; px <= x + 14; px++)
            for (int pz = z - 10; pz <= z + 10; pz++) world.setBlock(px, 99, pz, Blocks.stone, 0, 2);
        owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("b1edd70e-64b8-4303-9707-b18b933165f0"), "[TLMNH-Strafe]"),
            new ItemInWorldManager(world));
        owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        owner.setPosition(x - 2, 100, z + .5);
        world.playerEntities.add(owner);
        maid = new EntityMaid(world);
        require(maid.bindTo(owner.getUniqueID()), "Bound owner required");
        maid.setTask(MaidTask.RANGED_ATTACK);
        maid.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
        maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 16));
        maid.setPosition(x + .5, 100, z + .5);
        maid.rotationYaw = maid.rotationYawHead = -90;
        startX = maid.posX;
        startZ = maid.posZ;
        target = new EntityZombie(world) {

            @Override
            protected void updateAITasks() {}
        };
        target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        target.setHealth(200);
        target.setCurrentItemOrArmor(4, new ItemStack(Items.diamond_helmet));
        target.setPosition(x + 2.5, 100, z + .5);
        require(world.spawnEntityInWorld(target) && world.spawnEntityInWorld(maid), "Registered actors required");
        maid.setAttackTarget(target);
        TLMNotHard.LOG.info("TLMNH_RANGED_MOVEMENT_STARTED natural_world_ticks=true");
    }

    @SubscribeEvent
    public void tick(TickEvent.WorldTickEvent event) {
        if (finished || event.world != world || event.phase != TickEvent.Phase.END) return;
        try {
            ticks++;
            var bow = maid.getBrainCoordinator()
                .getBowAttack();
            if (ticks == 16) {
                require(bow.getShots() == 0, "Damage baseline must precede the first arrow");
                targetHealthBeforeShots = target.getHealth();
                require(
                    maid.posX < startX - .15 && Math.abs(maid.posZ - startZ) > .15,
                    "Natural travel must retreat and strafe near a target: " + maid.posX + "," + maid.posZ);
                require(maid.moveStrafing == 0, "One-shot input must not remain in the native strafe field");
                TLMNotHard.LOG.info(
                    "TLMNH_RANGED_MOVEMENT_PHASE_PASS phase=retreat dx={} dz={} draw={} target_health_before={}",
                    maid.posX - startX,
                    maid.posZ - startZ,
                    bow.getDrawTicks(),
                    targetHealthBeforeShots);
                target.setPosition(x + 8.5, 100, z + .5);
            } else if (ticks == 55) {
                require(
                    bow.getShots() >= 1 && target.getHealth() < targetHealthBeforeShots,
                    "Native arrow must release and collide: shots=" + bow.getShots()
                        + " status="
                        + bow.getStatus()
                        + " draw="
                        + bow.getDrawTicks()
                        + " hp="
                        + target.getHealth()
                        + " combat="
                        + maid.getBrainCoordinator()
                            .getCombatController()
                            .getStatus()
                        + " maid="
                        + maid.posX
                        + ","
                        + maid.posY
                        + ","
                        + maid.posZ
                        + " target="
                        + target.posX
                        + ","
                        + target.posY
                        + ","
                        + target.posZ);
                require(
                    maid.getHeldItem()
                        .getItemDamage() == bow.getShots()
                        && maid.getMaidInventory()
                            .getStackInSlot(0).stackSize == 16 - bow.getShots(),
                    "Arrow and bow resource accounting");
                TLMNotHard.LOG.info(
                    "TLMNH_RANGED_MOVEMENT_PHASE_PASS phase=arrows shots={} before={} after={}",
                    bow.getShots(),
                    targetHealthBeforeShots,
                    target.getHealth());
                maid.setSitting(true);
                stoppedShots = bow.getShots();
            } else if (ticks == 56) {
                require(
                    bow.getDrawTicks() == 0 && !maid.getBrainCoordinator()
                        .applyCombatMovement(),
                    "The next natural decision cancels bow use and pending movement after sitting");
            } else if (ticks == 62) {
                stoppedX = maid.posX;
                stoppedZ = maid.posZ;
            } else if (ticks == 72) {
                require(
                    Math.abs(maid.posX - stoppedX) < .01 && Math.abs(maid.posZ - stoppedZ) < .01
                        && maid.moveStrafing == 0
                        && bow.getShots() == stoppedShots,
                    "Cancelled movement must settle without residual input or another shot");
                finish(true, "retreat;strafe;native-arrow-hit;resources;sitting-cancel;settled");
            }
        } catch (RuntimeException | Error failure) {
            finish(false, failure.toString());
        }
    }

    private void finish(boolean passed, String detail) {
        if (finished) return;
        finished = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        if (maid != null) {
            maid.getBrainCoordinator()
                .setSuspended(true);
            for (Object value : new ArrayList<>(world.loadedEntityList))
                if (value instanceof EntityArrow arrow && arrow.shootingEntity == maid) arrow.setDead();
            world.removeEntity(maid);
            maid.setDead();
        }
        if (target != null) {
            world.removeEntity(target);
            target.setDead();
        }
        if (owner != null) {
            world.playerEntities.remove(owner);
            owner.setDead();
        }
        if (floorReady) for (int px = x - 14; px <= x + 14; px++)
            for (int pz = z - 10; pz <= z + 10; pz++) world.setBlockToAir(px, 99, pz);
        pending = null;
        TLMNotHard.LOG.info(
            "TLMNH_RANGED_MOVEMENT_TEST_{} ticks={} detail={} benchmark=unverified",
            passed ? "PASS" : "FAIL",
            ticks,
            detail);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
