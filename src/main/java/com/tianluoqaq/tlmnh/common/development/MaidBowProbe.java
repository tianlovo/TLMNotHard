package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 原生箭实体命中与资源事务夹具，手动推进，不冒充自然tick战斗或O/F/N测评 */
public final class MaidBowProbe {

    private MaidBowProbe() {}

    public static void run(WorldServer world) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && !AnimationLab.isLab(world),
            "Dedicated test overworld required");
        var spawn = world.getSpawnPoint();
        double x = spawn.posX + .5, z = spawn.posZ + .5;
        double y = Math.max(128, world.getTopSolidOrLiquidBlock(spawn.posX, spawn.posZ) + 12);
        require(y < world.getHeight() - 8, "Fixture height unavailable");
        for (int bx = spawn.posX - 2; bx <= spawn.posX + 12; bx++)
            for (int bz = spawn.posZ - 3; bz <= spawn.posZ + 3; bz++) for (int by = (int) y; by <= y + 5; by++)
                require(world.blockExists(bx, by, bz) && world.isAirBlock(bx, by, bz), "Fixture volume not loaded air");
        var owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("ec057b66-06ed-4e4b-95b1-d0d43ad1ac93"), "[TLMNH-Bow]"),
            new ItemInWorldManager(world));
        owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        owner.setPosition(x - 2, y, z);
        var maid = new EntityMaid(world);
        maid.bindTo(owner.getUniqueID());
        maid.setPosition(x, y, z);
        maid.setTask(MaidTask.RANGED_ATTACK);
        var bow = new ItemStack(Items.bow);
        maid.setCurrentItemOrArmor(0, bow);
        maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
        var arrows = new ArrayList<EntityArrow>();
        var targets = new ArrayList<EntityZombie>();
        var attack = maid.getBrainCoordinator()
            .getBowAttack();
        var reject = new RejectArrow(maid);
        var ally = new EntityMaid(world);
        ally.getBrainCoordinator()
            .setSuspended(true);
        world.playerEntities.add(owner);
        try {
            var stationary = target(world, targets, x + 10, y, z);
            draw(attack, stationary, 1, 0);
            require(maid.hasPose(MaidPose.AIMING_BOW) && attack.getDrawTicks() == 20, "Actual draw pose");
            require(attack.fire(stationary), "Static shot rejected: " + attack.getStatus());
            var first = attack.getLastArrow();
            arrows.add(first);
            require(
                bow.getItemDamage() == 1 && maid.getMaidInventory()
                    .getStackInSlot(0).stackSize == 7,
                "Native durability/ammo conservation");
            double dy = stationary.boundingBox.minY + stationary.height * .5 - first.posY;
            double straightY = 3 * dy / Math.sqrt(100 + dy * dy);
            require(first.motionY > straightY, "Drop compensation must raise aim above the direct ray");
            advance(first, stationary, 0);
            require(stationary.getHealth() < stationary.getMaxHealth(), "Native static target collision");
            stationary.setDead();

            var moving = target(world, targets, x + 10, y, z - 2);
            draw(attack, moving, 30, .1);
            require(attack.fire(moving), "Moving shot rejected: " + attack.getStatus());
            var second = attack.getLastArrow();
            arrows.add(second);
            require(
                second.motionZ > 0 && attack.getLastSolution().predictedTarget.z > moving.posZ,
                "Observed lateral lead");
            advance(second, moving, .1);
            require(moving.getHealth() < moving.getMaxHealth(), "Native moving target collision");
            moving.setDead();

            var blocked = target(world, targets, x + 10, y, z);
            var reentrant = new ReenterArrow(maid, blocked);
            MinecraftForge.EVENT_BUS.register(reentrant);
            try {
                draw(attack, blocked, 60, 0);
                long before = attack.getShots();
                int ammo = maid.getMaidInventory()
                    .getStackInSlot(0).stackSize;
                require(attack.fire(blocked), "Outer transaction should succeed");
                arrows.add(attack.getLastArrow());
                require(
                    reentrant.calls == 1 && !reentrant.innerResult
                        && attack.getShots() == before + 1
                        && maid.getMaidInventory()
                            .getStackInSlot(0).stackSize == ammo - 1,
                    "Reentry must release and consume only once");
                attack.getLastArrow()
                    .setDead();
            } finally {
                MinecraftForge.EVENT_BUS.unregister(reentrant);
            }
            int arrowsBefore = maid.getMaidInventory()
                .getStackInSlot(0).stackSize;
            int wearBefore = bow.getItemDamage();
            MinecraftForge.EVENT_BUS.register(reject);
            try {
                draw(attack, blocked, 90, 0);
                require(
                    !attack.fire(blocked) && attack.getStatus() == MaidBowAttack.Status.SPAWN_REJECTED,
                    "Cancelled spawn must reject shot");
                require(
                    bow.getItemDamage() == wearBefore && maid.getMaidInventory()
                        .getStackInSlot(0).stackSize == arrowsBefore,
                    "Cancelled spawn must not consume resources");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(reject);
            }
            ally.setPosition(x + 4, y, z);
            require(world.spawnEntityInWorld(ally), "Ally spawn rejected");
            draw(attack, blocked, 130, 0);
            require(
                !attack.fire(blocked) && attack.getStatus() == MaidBowAttack.Status.BLOCKED,
                "Friend in actual trajectory must prevent release");
            require(
                bow.getItemDamage() == wearBefore && maid.getMaidInventory()
                    .getStackInSlot(0).stackSize == arrowsBefore,
                "Safety rejection must preserve resources");
            ally.setDead();
            draw(attack, blocked, 170, 0);
            maid.setSitting(true);
            maid.getBrainCoordinator()
                .updateTask();
            require(
                !maid.hasPose(MaidPose.AIMING_BOW) && !maid.hasPose(MaidPose.USING_ITEM),
                "Sit must interrupt bow through actual arbiter");
            require(!attack.fire(blocked), "Cancelled draw must not release");
            maid.setSitting(false);
            for (int mode = 0; mode < 2; mode++) {
                var mutate = new MutateArrow(maid, mode == 0);
                MinecraftForge.EVENT_BUS.register(mutate);
                try {
                    draw(attack, blocked, 220 + mode * 40, 0);
                    require(
                        !attack.fire(blocked) && attack.getStatus() == MaidBowAttack.Status.SPAWN_REJECTED,
                        "Dead or redirected spawn must reject release");
                    require(
                        bow.getItemDamage() == wearBefore && maid.getMaidInventory()
                            .getStackInSlot(0).stackSize == arrowsBefore,
                        "Mutated arrow must not consume resources");
                } finally {
                    MinecraftForge.EVENT_BUS.unregister(mutate);
                }
            }
            bow.addEnchantment(Enchantment.infinity, 1);
            draw(attack, blocked, 310, 0);
            require(attack.fire(blocked), "Infinity shot rejected");
            arrows.add(attack.getLastArrow());
            require(
                attack.getLastArrow().canBePickedUp == 2 && maid.getMaidInventory()
                    .getStackInSlot(0).stackSize == arrowsBefore && bow.getItemDamage() == wearBefore + 1,
                "Infinity preserves ammunition but wears bow and forbids survival pickup");
            attack.cancel();
            maid.setTask(MaidTask.IDLE);
            maid.setPose(MaidPose.USING_ITEM, true);
            maid.setPose(MaidPose.AIMING_BOW, true);
            maid.getBrainCoordinator()
                .updateTask();
            require(
                maid.hasPose(MaidPose.USING_ITEM) && maid.hasPose(MaidPose.AIMING_BOW),
                "Inactive bow must preserve another action's pose");
            maid.setPose(MaidPose.USING_ITEM, false);
            maid.setPose(MaidPose.AIMING_BOW, false);
            TLMNotHard.LOG.info(
                "TLMNH_BOW_CORE_TEST_PASS static_hit=true moving_hit=true drop=true lead=true reentry=single cancelled=conserved altered_arrow=rejected infinity=checked friendly_static=blocked sit=interrupted natural_tick=unverified benchmark=unverified");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(reject);
            maid.getBrainCoordinator()
                .setSuspended(true);
            for (var arrow : arrows) world.removeEntity(arrow);
            for (var target : targets) world.removeEntity(target);
            world.removeEntity(ally);
            world.playerEntities.remove(owner);
            maid.setDead();
            owner.setDead();
        }
    }

    private static EntityZombie target(WorldServer world, ArrayList<EntityZombie> targets, double x, double y,
        double z) {
        var target = new EntityZombie(world);
        target.setPosition(x, y, z);
        require(world.spawnEntityInWorld(target), "Target spawn rejected");
        targets.add(target);
        return target;
    }

    private static void draw(MaidBowAttack attack, EntityZombie target, long start, double dz) {
        attack.cancel();
        for (int i = 0; i < 20; i++) {
            target.setPosition(target.posX, target.posY, target.posZ + dz);
            attack.observe(target, true, start + i);
            boolean ready = attack.prepare();
            require(ready == (i == 19), "Exactly twenty draw ticks");
        }
    }

    private static void advance(EntityArrow arrow, EntityZombie target, double dz) {
        for (int i = 0; i < 12 && !arrow.isDead && target.getHealth() == target.getMaxHealth(); i++) {
            target.setPosition(target.posX, target.posY, target.posZ + dz);
            arrow.onUpdate();
        }
    }

    public static final class RejectArrow {

        private final EntityMaid maid;

        RejectArrow(EntityMaid maid) {
            this.maid = maid;
        }

        @SubscribeEvent
        public void spawn(EntityJoinWorldEvent event) {
            if (event.entity instanceof EntityArrow arrow && arrow.shootingEntity == maid) event.setCanceled(true);
        }
    }

    public static final class ReenterArrow {

        private final EntityMaid maid;
        private final EntityZombie target;
        private int calls;
        private boolean innerResult;

        ReenterArrow(EntityMaid maid, EntityZombie target) {
            this.maid = maid;
            this.target = target;
        }

        @SubscribeEvent
        public void spawn(EntityJoinWorldEvent event) {
            if (event.entity instanceof EntityArrow arrow && arrow.shootingEntity == maid) {
                calls++;
                innerResult = maid.getBrainCoordinator()
                    .getBowAttack()
                    .fire(target);
            }
        }
    }

    public static final class MutateArrow {

        private final EntityMaid maid;
        private final boolean kill;

        MutateArrow(EntityMaid maid, boolean kill) {
            this.maid = maid;
            this.kill = kill;
        }

        @SubscribeEvent
        public void spawn(EntityJoinWorldEvent event) {
            if (event.entity instanceof EntityArrow arrow && arrow.shootingEntity == maid) {
                if (kill) arrow.setDead();
                else arrow.motionX = -arrow.motionX;
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("TLMNH bow core probe failed: " + message);
    }
}
