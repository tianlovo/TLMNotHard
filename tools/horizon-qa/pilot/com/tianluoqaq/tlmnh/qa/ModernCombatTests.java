package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState;
import com.tianluoqaq.tlmnh.common.ai.faithful.combat.MaidCombatActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernCombatTests {

    private ModernCombatTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 160, batch = "modern_combat_f_live_target")
    public static void completeFaithfulRootSkipsAlliesAndRechecksPreCallbackWorldMembership(GameTestHelper helper) {
        var f = fixture(helper);
        String type = "tlmnhqapilot:live_team_pig";
        helper.assertTrue(
            !EntityList.stringToClassMapping.containsKey(type)
                && !EntityList.classToStringMapping.containsKey(TeamPig.class),
            "Controlled team target name already owned");
        helper.afterTest(() -> {
            EntityList.stringToClassMapping.remove(type, TeamPig.class);
            EntityList.classToStringMapping.remove(TeamPig.class, type);
        });
        EntityList.stringToClassMapping.put(type, TeamPig.class);
        EntityList.classToStringMapping.put(TeamPig.class, type);
        var scoreboard = helper.getWorld()
            .getScoreboard();
        String name = "tlmnhF" + f.maid.getEntityId();
        var oldTeam = f.owner.getTeam();
        var team = scoreboard.createTeam(name);
        helper.afterTest(() -> {
            scoreboard.removeTeam(team);
            if (oldTeam != null) scoreboard.func_151392_a(f.owner.getCommandSenderName(), oldTeam.getRegisteredName());
        });
        var ally = new TeamPig(helper.getWorld());
        var target = new TeamPig(helper.getWorld());
        var hook = new LiveTargetHook(f, target, ally, team);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(hook);
            for (var entity : new TeamPig[] { ally, target }) {
                entity.setDead();
                helper.getWorld()
                    .removePlayerEntityDangerously(entity);
            }
        });
        helper.assertTrue(
            scoreboard.func_151392_a(f.owner.getCommandSenderName(), name),
            "Native owner team input rejected");
        ally.team = team;
        for (var entity : new TeamPig[] { ally, target }) {
            entity.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(200);
            entity.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            entity.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            entity.setHealth(200);
        }
        helper.spawnEntity(ally, 2.5, 1, 3.5);
        helper.spawnEntity(target, 3.3, 1, 4.5);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Complete F factory rejected live-target regression");
        var core = f.maid.getBrainCoordinator()
            .peekFaithfulBrain();
        MinecraftForge.EVENT_BUS.register(hook);
        int[] phase = { 0 }, changedAt = { -1 }, wear = { 0 };
        float[] health = { 200 };
        boolean[] done = { false };
        helper.onEachTick("complete F selection and native melee admission after Pre", () -> {
            int age = f.maid.ticksExisted;
            helper.assertTrue(
                f.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .isReady()
                    && f.maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.FAITHFUL_TLM
                    && f.maid.getBrainCoordinator()
                        .peekFaithfulBrain() == core
                    && f.maid.getBrainCoordinator()
                        .getCombatController()
                        .getTicks() == 0,
                "Live-target regression changed its complete natural F root");
            helper.assertTrue(
                hook.allyAttempts == 0 && ally.getHealth() == 200,
                "Nearest allied target was selected or hurt instead of the farther legal enemy");
            var weapon = f.maid.getHeldItem();
            if (phase[0] == 0 && hook.mutations == 1) {
                helper.assertTrue(
                    target.getHealth() == 200 && weapon.getItemDamage() == 0 && hook.posts == 0,
                    "Joining the owner's team in Pre still committed damage, Post or weapon wear");
                changedAt[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age >= changedAt[0] + 2) {
                helper.assertTrue(
                    target.getHealth() == 200 && weapon.getItemDamage() == 0,
                    "Live allied target retained stale cached attack permission");
                target.team = null;
                hook.mode = 2;
                phase[0] = 2;
            } else if (phase[0] == 2 && target.getHealth() < 200) {
                helper.assertTrue(
                    weapon.getItemDamage() == 1 && hook.posts == 1,
                    "Leaving the team did not resume one actual finite melee attack");
                health[0] = target.getHealth();
                wear[0] = weapon.getItemDamage();
                hook.mode = 3;
                phase[0] = 3;
            } else if (phase[0] == 3 && hook.mutations == 2) {
                helper.assertTrue(
                    target.isEntityAlive() && helper.getWorld()
                        .getEntityByID(target.getEntityId()) != target
                        && target.getHealth() == health[0]
                        && weapon.getItemDamage() == wear[0]
                        && hook.posts == 1,
                    "Alive but natively unregistered Pre target retained attack admission");
                hook.mode = 4;
                helper.spawnEntity(target, target.posX - f.x, target.posY - f.y, target.posZ - f.z);
                helper.assertTrue(
                    helper.getWorld()
                        .getEntityByID(target.getEntityId()) == target,
                    "Original target identity was not actually registered again");
                phase[0] = 4;
            } else if (phase[0] == 4 && target.getHealth() < health[0]) {
                helper.assertTrue(
                    weapon.getItemDamage() == wear[0] + 1 && hook.posts == 2,
                    "Re-registered original target did not resume exactly one attack and wear");
                System.out.println(
                    "TLMNH_F_LIVE_TARGET_PASS age=" + age
                        + " targetClass="
                        + target.getClass()
                            .getName()
                        + " targetId="
                        + MaidCombatEntities.id(target)
                        + " scope=controlled_native_team_getter_and_remove_then_alive_input not_real_mod_team_or_multiplayer=true");
                done[0] = true;
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    /** 旧平台普通生物没有可写队伍；只用原生Team类型提供明确受控的getTeam输入 */
    public static final class TeamPig extends EntityPig {

        Team team;

        public TeamPig(World world) {
            super(world);
        }

        @Override
        public Team getTeam() {
            return team;
        }
    }

    public static final class LiveTargetHook {

        final ModernCoreTests.Fixture f;
        final TeamPig target, ally;
        final ScorePlayerTeam team;
        int mode = 1, mutations, allyAttempts, posts;

        LiveTargetHook(ModernCoreTests.Fixture f, TeamPig target, TeamPig ally, ScorePlayerTeam team) {
            this.f = f;
            this.target = target;
            this.ally = ally;
            this.team = team;
        }

        @SubscribeEvent
        public void pre(MaidHurtTarget.Pre event) {
            if (event.getMaid() != f.maid) return;
            if (event.getTarget() == ally) allyAttempts++;
            if (event.getTarget() != target) return;
            if (mode == 1) {
                target.team = team;
                mutations++;
                mode = 0;
            } else if (mode == 3) {
                f.helper.getWorld()
                    .removePlayerEntityDangerously(target);
                // 将世界登记与alive状态分离的受控回调，不冒称正常维度转移机制
                target.isDead = false;
                mutations++;
                mode = 0;
            }
        }

        @SubscribeEvent
        public void post(MaidHurtTarget.Post event) {
            if (event.getMaid() == f.maid && event.getTarget() == target) posts++;
        }
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 240, batch = "modern_combat_approach")
    public static void naturalApproachUsesSourceReachAndExpirableAttributeCooldown(GameTestHelper helper) {
        var f = fixture(helper);
        var pig = pig(helper, f, 9.5, 4.5);
        // 这个场景隔离连续近战冷却，原生受击击退另由混合运动benchmark评估
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        var hook = hook(helper, f);
        f.maid.setOffhandItem(new ItemStack(Items.bow));
        boolean[] slowed = { false }, done = { false };
        helper.onEachTick("natural selection, live approach, native damage and source cooldown", () -> {
            f.check();
            var actor = f.core.getActor();
            if (f.maid.ticksExisted % 20 == 0) System.out.println(
                "TLMNH_COMBAT110_APPROACH_TRACE age=" + f.maid.ticksExisted
                    + " attempts="
                    + hook.attempts
                    + " target="
                    + pig.posX
                    + ","
                    + pig.posY
                    + ","
                    + pig.posZ
                    + " hp="
                    + pig.getHealth()
                    + " distanceSq="
                    + f.maid.getDistanceSqToEntity(pig));
            if (hook.attempts.size() >= 5 && !slowed[0]) {
                for (int i = 1; i < 5; i++) helper.assertTrue(
                    hook.attempts.get(i) - hook.attempts.get(i - 1) == 6,
                    "TTL5 did not expire on sixth Brain frame");
                helper.assertTrue(
                    f.maid.posX > f.x + 3 && f.navigation.searchCount() > 0,
                    "Melee did not approach using real navigation");
                helper.assertTrue(
                    hook.firstDistanceSq <= 4 && hook.firstDistanceSq > 1,
                    "First melee did not use source reach2: " + hook.firstDistanceSq);
                helper.assertTrue(
                    pig.getHealth() < 200 && f.maid.getHeldItem()
                        .getItemDamage() > 0 && actor.meleeNativeAccepted() > 0,
                    "Native health or finite weapon wear did not change");
                f.maid.getEntityAttribute(MaidCombatAttributes.ATTACK_SPEED)
                    .setBaseValue(2);
                slowed[0] = true;
            }
            if (slowed[0] && hook.attempts.size() >= 7) {
                helper.assertTrue(
                    hook.attempts.get(6) - hook.attempts.get(5) == 11,
                    "Attribute speed2 did not produce source TTL10 interval11");
                done[0] = true;
                System.out.println(
                    "TLMNH_COMBAT110_APPROACH_PASS attempts=" + hook.attempts
                        + " nativeAccepted="
                        + actor.meleeNativeAccepted()
                        + " hp="
                        + pig.getHealth());
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "modern_combat_callbacks")
    public static void cancelledPrePolicyRevocationStaleViewAndLethalWeaponWear(GameTestHelper helper) {
        var f = fixture(helper);
        var pig = pig(helper, f, 2.8, 4.5);
        var hook = hook(helper, f);
        hook.mode = 1;
        int[] phase = { 0 }, changed = { -1 }, wearBeforeDeath = { -1 };
        Long[] pausedTtl = { null };
        MaidCombatActor.Target[] old = { null };
        boolean[] done = { false };
        helper.onEachTick("source Pre, real policy cancellation, stale capability and killing blow", () -> {
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            if (phase[0] != 1 && phase[0] != 4) f.check();
            if (phase[0] == 0 && hook.attempts.size() == 2) {
                helper.assertTrue(
                    hook.posts == 0 && pig.getHealth() == 200
                        && f.maid.getHeldItem()
                            .getItemDamage() == 0
                        && actor.meleePreCancelled() == 2,
                    "Cancelled Pre emitted Post, damaged target or consumed weapon");
                helper.assertTrue(
                    hook.attempts.get(1) - hook.attempts.get(0) == 6,
                    "Cancelled Pre did not keep source melee cooldown");
                old[0] = actor.attackTarget();
                pausedTtl[0] = f.maid.getModernCombatMemory()
                    .remaining();
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age >= changed[0] + 4) {
                helper.assertTrue(
                    pausedTtl[0].equals(
                        f.maid.getModernCombatMemory()
                            .remaining()),
                    "Suspended Brain decremented combat TTL using world time");
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                changed[0] = age;
                phase[0] = 2;
            } else if (phase[0] == 2 && age > changed[0]) {
                boolean rejected = false;
                try {
                    actor.hurtTarget(old[0]);
                } catch (IllegalArgumentException | IllegalStateException expected) {
                    rejected = true;
                }
                helper.assertTrue(rejected && hook.attempts.size() == 2, "Old view gained new frame authority");
                hook.mode = 2;
                phase[0] = 3;
            } else if (phase[0] == 3 && hook.attempts.size() == 3) {
                helper.assertTrue(
                    pig.getHealth() == 200 && hook.posts == 0
                        && f.maid.getHeldItem()
                            .getItemDamage() == 0,
                    "Revoked Pre continued native effects");
                changed[0] = age;
                phase[0] = 4;
            } else if (phase[0] == 4 && age > changed[0]) {
                hook.mode = 0;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 5;
            } else if (phase[0] == 5 && pig.getHealth() < 200) {
                pig.setHealth(1);
                wearBeforeDeath[0] = f.maid.getHeldItem()
                    .getItemDamage();
                phase[0] = 6;
            } else if (phase[0] == 6 && !pig.isEntityAlive()) {
                helper.assertTrue(
                    f.maid.getHeldItem()
                        .getItemDamage() > wearBeforeDeath[0],
                    "Accepted killing blow skipped finite weapon wear");
                done[0] = true;
                System.out.println(
                    "TLMNH_COMBAT110_CALLBACK_PASS age=" + age
                        + " pre="
                        + hook.attempts.size()
                        + " posts="
                        + hook.posts
                        + " weaponWear="
                        + f.maid.getHeldItem()
                            .getItemDamage());
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "modern_combat_mounted")
    public static void sourceClassificationNbtMountedAttackAndStrictReachTimeout(GameTestHelper helper) {
        var f = fixture(helper);
        var pig = pig(helper, f, 2.8, 4.5);
        classificationAndNbt(helper, f, pig);
        var cart = new EntityMinecartEmpty(helper.getWorld());
        helper.spawnEntity(cart, 1.5, 1.05, 4.5);
        f.maid.mountEntity(cart);
        helper.afterTest(() -> {
            f.maid.mountEntity(null);
            cart.setDead();
        });
        int[] phase = { 0 }, changed = { -1 };
        boolean[] done = { false };
        helper.onEachTick("real minecart melee, target invalidation and strict CANT_REACH boundary", () -> {
            var actor = f.core.getActor();
            int age = f.maid.ticksExisted;
            helper.assertTrue(
                f.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .isReady(),
                "Mounted policy faulted");
            if (phase[0] == 0 && actor.meleeNativeAccepted() > 0) {
                helper.assertTrue(
                    f.maid.ridingEntity == cart && f.core.getActivities()
                        .active() == MaidActivityState.Activity.RIDE_WORK
                        && actor.peekWalkTarget() == null
                        && pig.getHealth() < 200,
                    "Mounted source melee required walking or prototype dismount");
                actor.setCantReachSince(f.maid.worldObj.getTotalWorldTime() - 199);
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age == changed[0] + 1) {
                helper.assertTrue(f.maid.getAttackTarget() == pig, "CANT_REACH erased target at exactly200");
                phase[0] = 2;
            } else if (phase[0] == 2 && age == changed[0] + 2) {
                helper.assertTrue(f.maid.getAttackTarget() == null, "CANT_REACH retained target after200");
                phase[0] = 3;
            } else if (phase[0] == 3 && f.maid.getAttackTarget() == pig) {
                helper.assertTrue(actor.getCantReachSince() == null, "Reacquisition retained old reach timeout");
                pig.setCustomNameTag("MaidNoAttack-qa");
                phase[0] = 4;
            } else if (phase[0] == 4 && f.maid.getAttackTarget() == null) {
                done[0] = true;
                System.out.println("TLMNH_COMBAT110_MOUNTED_CLASSIFY_PASS age=" + age);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 120, batch = "modern_combat_shield")
    public static void realFlyingArrowAndActiveShieldStartStopPhases(GameTestHelper helper) {
        var f = fixture(helper);
        var pig = pig(helper, f, 1.5, 6.5);
        f.maid.setSitting(true);
        Item item = (Item) Item.itemRegistry.getObject("battlegear2:shield.wood");
        helper.assertTrue(item != null, "Formal Battlegear wood shield missing");
        var shield = new ItemStack(item);
        f.maid.setOffhandItem(shield);
        long[] firstDeadline = { -1 };
        boolean[] fired = { false }, blocked = { false }, stopped = { false }, restarted = { false }, done = { false };
        helper.onEachTick("actual projectile flight and source shield Behavior phases", () -> {
            f.check();
            f.maid.rotationYaw = 0;
            var state = f.maid.getShieldController()
                .getSnapshot();
            long now = f.maid.worldObj.getTotalWorldTime();
            if (state.activeTaskRunning && firstDeadline[0] < 0) firstDeadline[0] = state.activeEndTime;
            if (!fired[0] && f.maid.ticksExisted >= 10 && state.using) {
                var arrow = new EntityArrow(helper.getWorld(), f.maid.posX, f.maid.posY + .9, f.maid.posZ + 2.7);
                arrow.shootingEntity = pig;
                arrow.motionZ = -.7;
                arrow.setDamage(4);
                helper.getWorld()
                    .spawnEntityInWorld(arrow);
                helper.afterTest(arrow::setDead);
                fired[0] = true;
            }
            var block = f.maid.getShieldController()
                .getLastBlock();
            if (block != null && block.outcome == MaidShieldController.Outcome.BLOCKED) {
                helper.assertTrue(
                    fired[0] && shield.getItemDamage() > 0 && f.maid.getHealth() == 20,
                    "Actual arrow block did not preserve health and consume shield durability");
                blocked[0] = true;
            }
            if (firstDeadline[0] >= 0 && now == firstDeadline[0])
                helper.assertTrue(state.activeTaskRunning, "Shield stopped at inclusive deadline");
            if (firstDeadline[0] >= 0 && now == firstDeadline[0] + 1) {
                helper.assertTrue(!state.activeTaskRunning && !state.using, "Shield did not stop in running phase");
                stopped[0] = true;
            }
            if (stopped[0] && now == firstDeadline[0] + 2) {
                helper.assertTrue(
                    state.activeTaskRunning && state.activeEndTime == firstDeadline[0] + 62
                        && f.maid.hasPose(MaidPose.USING_OFFHAND),
                    "Shield did not restart in following start phase");
                pig.setPosition(f.maid.posX + 8, f.maid.posY, f.maid.posZ);
                restarted[0] = true;
            } else if (restarted[0] && now > firstDeadline[0] + 2) {
                helper.assertTrue(!state.activeTaskRunning, "Shield allowed distance exactly8");
                done[0] = blocked[0];
                if (done[0]) System.out.println(
                    "TLMNH_COMBAT110_ARROW_PHASE_PASS age=" + f.maid.ticksExisted
                        + " shieldWear="
                        + shield.getItemDamage());
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    private static void classificationAndNbt(GameTestHelper helper, ModernCoreTests.Fixture f, EntityPig pig) {
        var task = new TaskAttack();
        var id = MaidCombatEntities.id(pig);
        helper.assertTrue(id != null, "Native pig has no registered target id");
        f.maid.getAttackListData()
            .attackGroups()
            .clear();
        helper.assertFalse(task.canAttack(f.maid, pig), "Unprovoked neutral target became hostile");
        f.owner.setLastAttacker(pig);
        f.owner.ticksExisted += 150; // 原生历史输入，不冒称自然150tick护主benchmark
        helper.assertTrue(task.canAttack(f.maid, pig), "Owner forward hurt history gained invented100tick limit");
        helper.assertFalse(task.canAttack(f.maid, f.owner), "Source classification targeted player");
        var pet = new EntityWolf(helper.getWorld());
        pet.func_152115_b(
            f.owner.getUniqueID()
                .toString());
        helper.assertFalse(task.canAttack(f.maid, pet), "Owned pet was attackable");
        try {
            var stand = (EntityLivingBase) Class.forName("ganymedes01.etfuturum.entities.EntityArmourStand")
                .getConstructor(World.class)
                .newInstance(helper.getWorld());
            helper.assertFalse(task.canAttack(f.maid, stand), "Actual formal EFR armor stand was attackable");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        f.maid.getAttackListData()
            .attackGroups()
            .put(id, MonsterType.FRIENDLY);
        helper.assertFalse(task.canAttack(f.maid, pig), "Per-maid friendly override was ignored");
        f.maid.getAttackListData()
            .attackGroups()
            .put(id, MonsterType.HOSTILE);
        helper.assertTrue(task.canAttack(f.maid, pig), "Per-maid hostile override was ignored");
        var saved = new NBTTagCompound();
        f.maid.writeToNBT(saved);
        var root = saved.getCompoundTag("MaidTaskDataMaps");
        root.setString("unknown_task", "keep");
        root.getCompoundTag("touhou_little_maid:maid_attack_list")
            .getCompoundTag("attack_groups")
            .setString("qa:unknown", "future_type");
        var copy = new EntityMaid(helper.getWorld());
        copy.readFromNBT(saved);
        helper.assertTrue(
            copy.getAttackListData()
                .attackGroups()
                .get(id) == MonsterType.HOSTILE,
            "Entity NBT lost original attack_groups layout");
        copy.getAttackListData()
            .attackGroups()
            .remove(id);
        var again = new NBTTagCompound();
        copy.writeToNBT(again);
        var groups = again.getCompoundTag("MaidTaskDataMaps")
            .getCompoundTag("touhou_little_maid:maid_attack_list")
            .getCompoundTag("attack_groups");
        helper.assertTrue(
            !groups.hasKey(id.toString()) && "future_type".equals(groups.getString("qa:unknown"))
                && "keep".equals(
                    again.getCompoundTag("MaidTaskDataMaps")
                        .getString("unknown_task")),
            "Attack settings lost unknown data or retained removed known entry");
        copy.setDead();
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16);
        f.maid.setTask(MaidTask.ATTACK);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static EntityPig pig(GameTestHelper helper, ModernCoreTests.Fixture f, double x, double z) {
        var pig = new EntityPig(helper.getWorld());
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.setHealth(200);
        helper.spawnEntity(pig, x, 1, z);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
        helper.afterTest(pig::setDead);
        return pig;
    }

    private static HurtHook hook(GameTestHelper helper, ModernCoreTests.Fixture f) {
        var hook = new HurtHook(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(hook));
        return hook;
    }

    public static final class HurtHook {

        private final ModernCoreTests.Fixture f;
        final List<Integer> attempts = new ArrayList<>();
        int mode, posts;
        double firstDistanceSq = -1;

        HurtHook(ModernCoreTests.Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void pre(MaidHurtTarget.Pre event) {
            if (event.getMaid() != f.maid) return;
            attempts.add(f.maid.ticksExisted);
            if (firstDistanceSq < 0) firstDistanceSq = f.maid.getDistanceSqToEntity(event.getTarget());
            if (mode == 1) event.setCanceled(true);
            else if (mode == 2) f.maid.getBrainCoordinator()
                .setSuspended(true);
        }

        @SubscribeEvent
        public void post(MaidHurtTarget.Post event) {
            if (event.getMaid() == f.maid) posts++;
        }
    }
}
