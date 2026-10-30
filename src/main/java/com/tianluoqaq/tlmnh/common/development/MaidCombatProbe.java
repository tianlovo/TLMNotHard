package com.tianluoqaq.tlmnh.common.development;

import java.util.UUID;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 真实目标与原生伤害管线夹具，不替代自然 tick 接近、战斗或原版 benchmark */
public final class MaidCombatProbe {

    private MaidCombatProbe() {}

    public static void run(WorldServer world) {
        require(Boolean.getBoolean("tlmnh.devCommands"), "Development commands are disabled");
        require(!AnimationLab.isLab(world), "Combat core fixture uses the existing test overworld");
        var spawn = world.getSpawnPoint();
        require(
            world.getChunkProvider()
                .chunkExists(spawn.posX >> 4, spawn.posZ >> 4),
            "Fixture chunk is unloaded");
        var owner = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString("2974cb4c-6eaf-41d9-a0e4-372ef75bfe9e"), "[TLMNH-Combat]"),
            new ItemInWorldManager(world));
        owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        var maid = new EntityMaid(world);
        var ally = new EntityMaid(world);
        var wild = new EntityMaid(world);
        var target = new EntityZombie(world);
        var sword = new ItemStack(Items.diamond_sword);
        double y = world.getTopSolidOrLiquidBlock(spawn.posX, spawn.posZ);
        double x = spawn.posX + 0.5D, z = spawn.posZ + 0.5D;
        owner.setPosition(x - 2, y, z);
        maid.setPosition(x, y, z);
        ally.setPosition(x - 1, y, z + 1);
        target.setPosition(x + 1, y, z);
        require(maid.bindTo(owner.getUniqueID()) && ally.bindTo(owner.getUniqueID()), "Owner binding");
        world.playerEntities.add(owner);
        var reject = new RejectHit(maid, target);
        try {
            maid.setSitting(true);
            maid.setCurrentItemOrArmor(0, sword);
            // 使用实际原生装备更新应用武器属性，避免手工提高攻击力
            maid.onUpdate();
            maid.setPosition(x, y, z);
            maid.setSitting(false);
            maid.setTask(MaidTask.ATTACK);
            require(world.spawnEntityInWorld(target), "Target spawn rejected");
            require(world.spawnEntityInWorld(ally), "Ally spawn rejected");
            require(MaidCombatTargeting.canAttack(maid, owner, target), "Hostile target should be legal");
            require(!MaidCombatTargeting.canAttack(maid, owner, owner), "Owner must be protected");
            require(!MaidCombatTargeting.canAttack(maid, owner, ally), "Same-owner maid must be protected");
            require(!MaidCombatTargeting.canAttack(wild, owner, target), "Wild maid must not enter combat");
            float before = target.getHealth();
            int wear = sword.getItemDamage();
            maid.swingItem();
            require(maid.attackEntityAsMob(target), "Native melee attack was rejected");
            float baseDamage = before - target.getHealth();
            require(baseDamage > 0 && sword.getItemDamage() == wear + 1, "Damage and native item wear");
            require(maid.isSwingInProgress, "Main-hand swing was not active");
            resetTarget(target, x + 1, y, z);
            sword.addEnchantment(Enchantment.sharpness, 1);
            before = target.getHealth();
            wear = sword.getItemDamage();
            require(maid.attackEntityAsMob(target), "Enchanted melee attack was rejected");
            float enchantedDamage = before - target.getHealth();
            require(enchantedDamage > baseDamage && sword.getItemDamage() == wear + 1, "Native enchantment and wear");
            resetTarget(target, x + 1, y, z);
            before = target.getHealth();
            wear = sword.getItemDamage();
            MinecraftForge.EVENT_BUS.register(reject);
            try {
                require(!maid.attackEntityAsMob(target), "Cancelled damage must fail");
                require(
                    target.getHealth() == before && sword.getItemDamage() == wear,
                    "Cancelled damage must preserve health and durability");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(reject);
            }
            maid.setTask(MaidTask.IDLE);
            require(!maid.attackEntityAsMob(target), "Idle task must not attack");
            maid.setTask(MaidTask.RANGED_ATTACK);
            require(!maid.attackEntityAsMob(target), "Unimplemented ranged task must not use melee as a substitute");
            maid.setTask(MaidTask.ATTACK);
            maid.getBrainCoordinator()
                .setSuspended(true);
            require(!maid.attackEntityAsMob(target), "Suspended action must not attack");
            maid.getBrainCoordinator()
                .setSuspended(false);
            maid.setSitting(true);
            require(!maid.attackEntityAsMob(target), "Sitting must stop attacks");
            maid.setSitting(false);
            maid.setPose(MaidPose.SLEEPING, true);
            require(!maid.attackEntityAsMob(target), "Sleeping must stop attacks");
            maid.setPose(MaidPose.SLEEPING, false);
            maid.mountEntity(owner);
            require(!maid.attackEntityAsMob(target), "Carried maid must not attack");
            maid.mountEntity(null);
            maid.setPosition(x, y, z);
            owner.theItemInWorldManager.setGameType(GameType.getByID(3));
            require(!maid.attackEntityAsMob(target), "Spectator owner must stop combat");
            owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            target.setCustomNameTag("MaidNoAttack-TLMNH-CombatFixture");
            require(!maid.attackEntityAsMob(target), "Protected name must stop combat");
            target.setCustomNameTag("");
            resetTarget(target, x + 1, y, z);
            sword.setItemDamage(sword.getMaxDamage());
            require(maid.attackEntityAsMob(target) && maid.getHeldItem() == null, "Broken sword must leave the hand");
            require(!maid.attackEntityAsMob(target), "Missing weapon must not keep its old damage");
            target.setDead();
            require(!MaidCombatTargeting.canAttack(maid, owner, target), "Dead target must be rejected");
            TLMNotHard.LOG.info(
                "TLMNH_COMBAT_CORE_TEST_PASS base_damage={} enchanted_damage={} wear=native cancelled=conserved allies=protected constraints=checked break=cleared natural_tick=unverified benchmark=unverified",
                baseDamage,
                enchantedDamage);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(reject);
            maid.mountEntity(null);
            maid.getBrainCoordinator()
                .setSuspended(true);
            ally.getBrainCoordinator()
                .setSuspended(true);
            maid.setCurrentItemOrArmor(0, null);
            world.playerEntities.remove(owner);
            world.removeEntity(target);
            world.removeEntity(ally);
            maid.setDead();
            wild.setDead();
            owner.setDead();
        }
    }

    private static void resetTarget(EntityZombie target, double x, double y, double z) {
        target.setHealth(target.getMaxHealth());
        target.hurtResistantTime = 0;
        target.motionX = target.motionY = target.motionZ = 0;
        target.setPosition(x, y, z);
        target.extinguish();
    }

    public static final class RejectHit {

        private final EntityMaid maid;
        private final EntityZombie target;

        private RejectHit(EntityMaid maid, EntityZombie target) {
            this.maid = maid;
            this.target = target;
        }

        @SubscribeEvent
        public void attack(LivingAttackEvent event) {
            if (event.entityLiving == target && event.source.getEntity() == maid) event.setCanceled(true);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("TLMNH combat core probe failed: " + message);
    }
}
