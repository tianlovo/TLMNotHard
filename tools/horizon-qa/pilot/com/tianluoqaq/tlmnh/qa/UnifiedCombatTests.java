package com.tianluoqaq.tlmnh.qa;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.combat.MaidMeleeAttack;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatObservation;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.CombatHitReceipt;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatNbtKeys;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidEquipmentTransfer;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.inventory.container.ContainerMaid;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.common.items.IDMetaTool01;

/** 受控专服的真实执行边界；不冒充自主战术收益、真实客户端输入或完整原版benchmark */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "gregtech" })
public final class UnifiedCombatTests {

    private static final float EPSILON = .001F;
    private static final String OPAQUE = "QAUnifiedCombatOpaque";

    private UnifiedCombatTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 60, batch = "unified_combat_1_migration")
    public static void migrationRoundTripPreservesRawFAndCanonicalProduction(GameTestHelper h) {
        var f = fixture(h);
        ItemStack[] items = new ItemStack[MaidInventoryLayout.TOTAL_SIZE];
        for (int slot = 0; slot < items.length; slot++) {
            items[slot] = tagged(
                new ItemStack(Items.stick, slot < MaidInventoryLayout.MAIN_HAND ? slot + 1 : 1),
                "slot-" + slot);
            f.maid.getMaidInventory()
                .setInventorySlotContents(slot, items[slot]);
        }
        var source = snapshot(f.maid);
        source.removeTag(MaidCombatNbtKeys.TAG);
        source.removeTag(MaidCombatNbtKeys.TAG_2);
        source.getCompoundTag("TLMNH")
            .removeTag("CombatTask");
        source.getCompoundTag("TLMNH")
            .setIntArray(OPAQUE, new int[] { 209, 34, 36 });
        var taskMaps = source.getCompoundTag("MaidTaskDataMaps");
        var futureMap = new NBTTagCompound();
        futureMap.setString("Type", "future-task-data");
        futureMap.setIntArray("Payload", new int[] { 7, 9, 209 });
        taskMaps.setTag("qa:future-task", futureMap);
        source.setTag("MaidTaskDataMaps", taskMaps);
        for (MaidBrainProfile profile : new MaidBrainProfile[] { MaidBrainProfile.ENHANCED,
            MaidBrainProfile.LEGACY_PROTOTYPE, MaidBrainProfile.FAITHFUL_TLM }) {
            for (String id : new String[] { MaidTask.ATTACK.getId(), MaidTask.RANGED_ATTACK.getId(),
                "qa:future_weapon_task" }) {
                var input = (NBTTagCompound) source.copy();
                input.setString("MaidTask", id);
                var unchangedInput = input.copy();
                var copy = new EntityMaid(h.getWorld());
                select(h, copy, profile);
                copy.readFromNBT(input);
                MaidTask expected = id.equals("qa:future_weapon_task") ? MaidTask.IDLE
                    : profile == MaidBrainProfile.FAITHFUL_TLM ? MaidTask.fromId(id) : MaidTask.ATTACK;
                h.assertTrue(copy.getTask() == expected, "Task projection differs for " + profile + "/" + id);
                var saved = snapshot(copy);
                String savedId = profile != MaidBrainProfile.FAITHFUL_TLM && !id.startsWith("qa:")
                    ? MaidTask.ATTACK.getId()
                    : id;
                h.assertTrue(
                    savedId.equals(saved.getString("MaidTask")),
                    "Task save canonicalized raw F or lost unknown id");
                if (profile == MaidBrainProfile.FAITHFUL_TLM) h.assertFalse(
                    saved.getCompoundTag("TLMNH")
                        .hasKey("CombatTask"),
                    "Frozen F gained a production task extension");
                else h.assertTrue(
                    id.equals(
                        saved.getCompoundTag("TLMNH")
                            .getCompoundTag("CombatTask")
                            .getString("OriginalId")),
                    "Production migration lost original task identity");
                for (int slot = 0; slot < items.length; slot++) h.assertTrue(
                    ItemStack.areItemStacksEqual(
                        items[slot],
                        copy.getMaidInventory()
                            .getStackInSlot(slot)),
                    "Migration changed complete native inventory slot " + slot);
                h.assertTrue(
                    input.equals(unchangedInput) && taskMaps.equals(saved.getCompoundTag("MaidTaskDataMaps"))
                        && saved.getCompoundTag("TLMNH")
                            .getTag(OPAQUE)
                            .equals(
                                source.getCompoundTag("TLMNH")
                                    .getTag(OPAQUE)),
                    "Migration modified input or opaque task/extension payload");
                copy.setDead();
            }
        }
        var malformed = (NBTTagCompound) source.copy();
        malformed.setTag("MaidTask", futureMap.copy());
        var held = new EntityMaid(h.getWorld());
        select(h, held, MaidBrainProfile.ENHANCED);
        held.readFromNBT(malformed);
        h.assertTrue(
            held.getTask() == MaidTask.IDLE && snapshot(held).getTag("MaidTask")
                .equals(futureMap),
            "Unknown typed task was activated or rewritten");
        held.setDead();
        System.out
            .println("TLMNH_UNIFIED209_MIGRATION_PASS production=canonical F=raw inventory=12full opaque=preserved");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 420, batch = "unified_combat_2_mechanics")
    public static void nativeSwordAndGregTechAcceptedHitsUseOneMechanicalChain(GameTestHelper h) {
        var f = fixture(h);
        f.owner.capabilities.allowFlying = f.owner.capabilities.isFlying = false;
        f.owner.getEntityAttribute(SharedMonsterAttributes.attackDamage)
            .setBaseValue(2);
        var events = new HurtEvents(f.maid);
        MinecraftForge.EVENT_BUS.register(events);
        h.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(events));
        var run = new Mechanics(h, f, events);
        h.onEachTick("native gear phase, actual falling and one GT/vanilla accepted-hit chain", run::tick);
        h.succeedWhen(() -> run.done);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 240, batch = "unified_combat_3_receipt")
    public static void acceptedHitCancellationDeathAndStaleReadKeepSettlementOwnership(GameTestHelper h) {
        var f = fixture(h);
        var cancelled = controlledPig(h, f, 2.7, 4.5);
        var heldMaid = extraMaid(h, f, 7.5, 4.5, "held");
        var heldTarget = controlledPig(h, f, 8.7, 4.5);
        var dying = extraMaid(h, f, 13.5, 4.5, "death");
        var thorns = pig(h, f, 14.7, 4.5);
        var armor = new ItemStack(Items.iron_chestplate);
        armor.addEnchantment(Enchantment.thorns, 100); // 原生概率已达1且伤害90，避免随机抽样决定本场景是否致死
        thorns.setCurrentItemOrArmor(3, armor);
        var death = new DeathDuringHit(h, dying);
        MinecraftForge.EVENT_BUS.register(death);
        h.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(death));
        cancelled.afterAccepted = () -> {
            h.assertTrue(
                f.maid.getCombatHitReceipt()
                    .activeCall(),
                "Accepted callback lacked in-flight ownership");
            f.maid.setTask(MaidTask.IDLE);
            f.maid.getBrainCoordinator()
                .setSuspended(true);
        };
        var old = snapshot(heldMaid);
        boolean[] entityRejected = { false }, lowerRejected = { false };
        heldTarget.afterAccepted = () -> {
            try {
                heldMaid.readFromNBT(old);
            } catch (IllegalStateException expected) {
                entityRejected[0] = true;
            }
            try {
                heldMaid.getCombatHitReceipt()
                    .load(old);
            } catch (IllegalStateException expected) {
                lowerRejected[0] = true;
            }
        };
        int[] phase = { 0 };
        h.onEachTick("accepted cancellation, stale read HELD and native guaranteed-thorns deferred death", () -> {
            if (phase[0] == 0) {
                var result = hit(f.maid, cancelled);
                if (!result.nativeAccepted) return;
                h.assertTrue(
                    f.maid.getTask() == MaidTask.IDLE && f.maid.getHeldItem()
                        .getItemDamage() == 1
                        && !result.settlementHeld
                        && !f.maid.getCombatHitReceipt()
                            .blocksTransfer(),
                    "Accepted task cancellation skipped or duplicated original weapon wear");
                phase[0] = 1;
            } else if (phase[0] == 1) {
                var result = hit(heldMaid, heldTarget);
                if (!result.nativeAccepted) return;
                h.assertTrue(
                    entityRejected[0] && lowerRejected[0]
                        && result.settlementHeld
                        && heldMaid.getCombatHitReceipt()
                            .stage() == CombatHitReceipt.Stage.HELD
                        && heldMaid.getHeldItem()
                            .getItemDamage() == 0,
                    "Stale read replaced ownership, lost accepted fact or blindly settled unknown successor");
                var preserved = snapshot(heldMaid);
                var fact = preserved.getCompoundTag(MaidCombatNbtKeys.TAG_2);
                h.assertTrue(
                    fact.getBoolean("Accepted") && "HELD".equals(fact.getString("Stage")),
                    "HELD save lost accepted native outcome");
                float health = heldTarget.getHealth();
                h.assertFalse(hit(heldMaid, heldTarget).nativeAccepted, "HELD issued another hit");
                var reload = new EntityMaid(h.getWorld());
                reload.readFromNBT(preserved);
                h.assertTrue(
                    reload.getCombatHitReceipt()
                        .blocksTransfer()
                        && snapshot(reload).getTag(MaidCombatNbtKeys.TAG_2)
                            .equals(fact)
                        && heldTarget.getHealth() == health,
                    "Known unclosed receipt replayed or changed during native reload");
                reload.setDead();
                var unknown = (NBTTagCompound) preserved.copy();
                unknown.getCompoundTag(MaidCombatNbtKeys.TAG_2)
                    .setInteger("Version", Integer.MAX_VALUE);
                unknown.getCompoundTag(MaidCombatNbtKeys.TAG_2)
                    .setTag(OPAQUE, old.copy());
                var future = new EntityMaid(h.getWorld());
                future.readFromNBT(unknown);
                h.assertTrue(
                    future.getCombatHitReceipt()
                        .blocksTransfer()
                        && snapshot(future).getTag(MaidCombatNbtKeys.TAG_2)
                            .equals(unknown.getTag(MaidCombatNbtKeys.TAG_2)),
                    "Unknown receipt schema was activated or rewritten");
                future.setDead();
                phase[0] = 2;
            } else if (phase[0] == 2) {
                var result = hit(dying, thorns);
                if (!result.nativeAccepted) return;
                h.assertTrue(
                    death.seen && death.active && death.unworn && death.unpublished,
                    "Native thorns death did not defer its original equipment snapshot while hit remained active");
                UUID token = MaidLifecycleData.get(h.getWorld())
                    .deathToken(dying.getUniqueID());
                var entry = deathEntry(h, token);
                int weapons = 0;
                var graveItems = entry.getTagList("TombstoneItems", 10);
                for (int i = 0; i < graveItems.tagCount(); i++) {
                    var stack = ProtectedItemCodec.decode(graveItems.getCompoundTagAt(i));
                    if (stack != null && stack.getItem() == Items.iron_sword
                        && stack.hasTagCompound()
                        && "death".equals(
                            stack.getTagCompound()
                                .getString(OPAQUE))) {
                        weapons++;
                        h.assertTrue(stack.getItemDamage() == 1, "Grave encoded weapon before final accepted-hit wear");
                    }
                }
                h.assertTrue(
                    !dying.isEntityAlive() && !result.settlementHeld
                        && weapons == 1
                        && dying.getHeldItem() == null
                        && graves(h, token) == 1
                        && "COMMITTED".equals(
                            entry.getCompoundTag("DeathIngress")
                                .getString("Stage")),
                    "Deferred native death lost, duplicated or prematurely published the worn original weapon");
                System.out.println(
                    "TLMNH_UNIFIED209_RECEIPT_PASS cancelledWear=1 staleRead=HELD thornsDeath=deferred graveWear=1");
                phase[0] = 3;
            }
        });
        h.succeedWhen(() -> phase[0] == 3);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 160, batch = "unified_combat_4_transfer_cadence")
    public static void equipmentNormalizationAndWorldCadenceSurviveTaskGuiBoundaries(GameTestHelper h) {
        var f = fixture(h);
        var unknown = tagged(new ItemStack(Items.stick), "unknown-original");
        var expectedUnknown = unknown.copy();
        var tool = gregtech(IDMetaTool01.KNIFE.ID);
        tool.getTagCompound()
            .removeTag("ench");
        f.maid.setCurrentItemOrArmor(0, unknown);
        f.maid.getMaidInventory()
            .setInventorySlotContents(2, tool);
        var target = pig(h, f, 2.7, 4.5);
        f.maid.getEntityAttribute(MaidCombatAttributes.ATTACK_SPEED)
            .setBaseValue(1);
        h.assertFalse(
            MaidWeaponCatalog.inspect(unknown)
                .supported(),
            "Unknown current item gained attack capability");
        var lease = f.maid.getItemUseState()
            .tryAcquire(new Object(), unknown, MaidItemUseState.Hand.MAIN, MaidItemUseState.Kind.FOOD, () -> true);
        h.assertTrue(lease != null, "Foreign hand owner fixture failed");
        var blocked = MaidEquipmentTransfer.capture(f.maid);
        h.assertTrue(
            blocked != null
                && MaidEquipmentTransfer.exchange(f.maid, blocked, 2, () -> true)
                    == MaidEquipmentTransfer.Result.REJECTED
                && f.maid.getHeldItem() == unknown,
            "Weapon exchange stole foreign use ownership");
        f.maid.getItemUseState()
            .release(lease);
        var before = MaidEquipmentTransfer.capture(f.maid);
        h.assertTrue(
            MaidEquipmentTransfer.exchange(f.maid, before, 2, () -> true)
                .changed() && f.maid.getHeldItem() == tool
                && f.maid.getMaidInventory()
                    .getStackInSlot(2) == unknown
                && ItemStack.areItemStacksEqual(expectedUnknown, unknown)
                && !f.maid.getEquipmentEpoch()
                    .acknowledged(tool),
            "Whole-stack exchange lost original NBT, identity or native phase invalidation");
        h.assertTrue(
            !hit(f.maid, target).nativeAccepted && target.getHealth() == 200
                && GregTechPublicApi.get()
                    .damage(tool) == 0
                && tool.getTagCompound()
                    .hasKey("ench", 9),
            "Same-tick normalized exchange attacked using stale native equipment or paid wear");
        h.assertTrue(
            !hit(f.maid, target).nativeAccepted && target.getHealth() == 200
                && GregTechPublicApi.get()
                    .damage(tool) == 0
                && f.maid.getCombatCadence()
                    .ready(),
            "Already-normalized second attempt reused pre-exchange native acknowledgement");
        int[] phase = { 0 };
        long[] committedAt = { -1 }, cooldown = { -1 };
        ContainerMaid[] gui = { null };
        h.afterTest(() -> {
            if (gui[0] != null) {
                gui[0].onContainerClosed(f.owner);
                f.owner.openContainer = f.owner.inventoryContainer;
            }
        });
        h.onEachTick("real native equipment acknowledgement and nonresetting world-clock cadence", () -> {
            if (phase[0] == 0) {
                var result = hit(f.maid, target);
                if (!result.nativeAccepted) return;
                h.assertTrue(
                    GregTechPublicApi.get()
                        .damage(tool) == 600 && ItemStack.areItemStacksEqual(expectedUnknown, unknown),
                    "First admitted GT strike did not pay once");
                committedAt[0] = h.getWorld()
                    .getTotalWorldTime();
                cooldown[0] = f.maid.getCombatCadence()
                    .remainingTicks();
                h.assertTrue(cooldown[0] == 20, "Actual speed1 strike did not commit a20tick entity cadence");
                f.maid.setTask(MaidTask.IDLE);
                f.maid.setTask(MaidTask.RANGED_ATTACK);
                h.assertTrue(
                    f.maid.getTask() == MaidTask.ATTACK && f.maid.getCombatCadence()
                        .remainingTicks() == cooldown[0],
                    "Task changes reset production cadence");
                var saved = snapshot(f.maid);
                var restored = new EntityMaid(h.getWorld());
                restored.readFromNBT(saved);
                h.assertTrue(
                    restored.getCombatCadence()
                        .remainingTicks() == cooldown[0],
                    "Native save/reload changed remaining entity cadence");
                restored.setDead();
                f.owner.setPosition(f.maid.posX, f.maid.posY, f.maid.posZ + 2);
                gui[0] = new ContainerMaid(f.owner.inventory, f.maid);
                f.owner.openContainer = gui[0];
                h.assertTrue(gui[0].canModifyWith(f.owner) && f.maid.guiOpening, "Actual owner GUI did not open");
                phase[0] = 1;
            } else if (phase[0] == 1) {
                long elapsed = h.getWorld()
                    .getTotalWorldTime() - committedAt[0];
                long expected = Math.max(0, cooldown[0] - elapsed);
                h.assertTrue(
                    f.maid.getCombatCadence()
                        .remainingTicks() == expected && !hit(f.maid, target).nativeAccepted
                        && GregTechPublicApi.get()
                            .damage(tool) == 600,
                    "GUI pause froze/reset cadence or issued another accepted hit");
                if (elapsed < cooldown[0] + 2) return;
                gui[0].onContainerClosed(f.owner);
                f.owner.openContainer = f.owner.inventoryContainer;
                gui[0] = null;
                h.assertTrue(
                    !f.maid.guiOpening && f.maid.getCombatCadence()
                        .ready(),
                    "GUI close reset elapsed cadence");
                phase[0] = 2;
            } else if (phase[0] == 2) {
                // GT刀自身受伤抗性为60tick，拒收攻击仍可耗节拍；换全新目标隔离该已知机制
                var fresh = pig(h, f, 2.7, 6.5);
                fresh.setPosition(f.maid.posX + 1.2, f.maid.posY, f.maid.posZ);
                var result = hit(f.maid, fresh);
                if (!result.nativeAccepted) {
                    fresh.setDead();
                    return;
                }
                h.assertTrue(
                    GregTechPublicApi.get()
                        .damage(tool) == 1200 && ItemStack.areItemStacksEqual(expectedUnknown, unknown),
                    "Resumed cadence duplicated or skipped GT wear");
                System.out.println(
                    "TLMNH_UNIFIED209_TRANSFER_CADENCE_PASS unknown=retained nativeAck=required GTwear=600x2 GUI=worldclock");
                phase[0] = 3;
            }
        });
        h.succeedWhen(() -> phase[0] == 3);
    }

    private static final class Mechanics {

        final GameTestHelper h;
        final ModernCoreTests.Fixture f;
        final HurtEvents events;
        final CombatObservation observation = new CombatObservation();
        final CombatPlanner planner = new CombatPlanner();
        EntityPig target, control;
        ItemStack weapon, playerWeapon;
        int index, pre, post, terrainPhase, controlUpdates, sampleAttempts, totalAttempts;
        long preparedAt, droppedAt = -1, lastControlWorldTick = Long.MIN_VALUE;
        String waitingFor = "native_equipment", lastSubmission = "none";
        boolean done;

        Mechanics(GameTestHelper h, ModernCoreTests.Fixture f, HurtEvents events) {
            this.h = h;
            this.f = f;
            this.events = events;
            prepare();
        }

        void prepare() {
            waitingFor = "native_equipment";
            lastSubmission = "none";
            sampleAttempts = 0;
            if (index >= 6) {
                f.maid.setCurrentItemOrArmor(0, tagged(new ItemStack(Items.iron_sword), "terrain"));
                f.maid.setPosition(f.x + 10.5, f.y + 1, f.z + 4.5);
                f.owner.setPosition(f.x + 17.5, f.y + 1, f.z + 4.5);
                target = pig(h, f, 11.7, 4.5);
                for (int x = 7; x <= 14; x++) for (int z = 2; z <= 6; z++) h.setBlock(x, 3, z, Blocks.stone);
                return;
            }
            weapon = index / 2 == 0 ? new ItemStack(Items.iron_sword)
                : gregtech(index / 2 == 1 ? IDMetaTool01.KNIFE.ID : IDMetaTool01.BUTCHERYKNIFE.ID);
            if (index / 2 == 0) weapon.addEnchantment(Enchantment.sharpness, 2);
            tagged(weapon, "mechanical-" + index);
            playerWeapon = weapon.copy();
            f.maid.setCurrentItemOrArmor(0, weapon);
            f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = playerWeapon;
            f.maid.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
            f.owner.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
            f.maid.motionX = f.maid.motionZ = 0;
            f.owner.motionX = f.owner.motionZ = 0;
            f.owner.setSprinting(false);
            target = pig(h, f, 2.7, 4.5);
            control = pig(h, f, 9.7, 4.5);
            h.assertTrue(
                MaidWeaponCatalog.inspect(weapon)
                    .melee(),
                "Actual representative weapon failed explicit admission " + index);
            preparedAt = h.getWorld()
                .getTotalWorldTime();
            droppedAt = -1;
            pre = events.pre;
            post = events.post;
        }

        void tick() {
            if (done) return;
            long now = h.getWorld()
                .getTotalWorldTime();
            if (lastControlWorldTick == now) return;
            lastControlWorldTick = now;
            // 隔离MP没有C03输入；该公开入口经Player/LivingBase完成原生装备、重力和碰撞更新
            // 不再额外调用moveEntity或moveEntityWithHeading，也不写onGround/fallDistance/motionY
            double previousOwnerY = f.owner.posY;
            f.owner.onUpdateEntity();
            // MP把掉落累计留给网络入口；使用同一公开入口结算刚才原生移动的真实高度差
            f.owner.handleFalling(f.owner.posY - previousOwnerY, f.owner.onGround);
            controlUpdates++;
            try {
                advanceSample(now);
            } finally {
                if (controlUpdates == 1 || controlUpdates % 40 == 0) trace(now);
            }
        }

        void advanceSample(long now) {
            if (index >= 6) {
                waitingFor = "terrain_observation";
                terrain();
                return;
            }
            if (now - preparedAt < 3) {
                waitingFor = "native_equipment";
                return;
            }
            if (!f.maid.getCombatCadence()
                .ready()) {
                waitingFor = "cadence";
                return;
            }
            boolean falling = index % 2 == 1;
            if (falling) {
                if (droppedAt < 0 || f.maid.onGround && now - droppedAt > 2) {
                    f.maid.setPosition(f.x + 1.5, f.y + 2.2, f.z + 4.5);
                    f.owner.setPosition(f.x + 8.5, f.y + 2.2, f.z + 4.5);
                    f.maid.motionX = f.maid.motionZ = 0;
                    f.owner.motionX = f.owner.motionZ = 0;
                    droppedAt = now;
                    waitingFor = "natural_drop_started";
                    return;
                }
                if (!falling(f.maid) || !falling(f.owner)) {
                    waitingFor = "natural_descent";
                    return;
                }
            } else if (!f.maid.onGround || !f.owner.onGround) {
                waitingFor = "native_ground";
                return;
            }
            double physical = f.maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                .getAttributeValue();
            double playerPhysical = f.owner.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                .getAttributeValue();
            h.assertTrue(
                Math.abs(physical - playerPhysical) < EPSILON,
                "Native player and maid equipment attributes did not converge");
            if (gregtechItem(weapon)) physical += GregTechPublicApi.get()
                .combatDamage(weapon);
            float magic = EnchantmentHelper.getEnchantmentModifierLiving(f.maid, target);
            h.assertTrue(
                magic > 0,
                "Representative native material/sword lacks the required nonzero enchantment control");
            float expected = (float) (physical * (falling ? 1.5 : 1) + magic);
            long beforeWear = wear(weapon), controlWear = wear(playerWeapon);
            var result = submit();
            if (!result.nativeAccepted) return;
            f.owner.attackTargetEntityWithCurrentItem(control);
            float actual = 200 - target.getHealth(), nativePlayer = 200 - control.getHealth();
            long cost = index / 2 == 0 ? 1 : index / 2 == 1 ? 600 : 200;
            int resistance = index / 2 == 0 ? 1 : index / 2 == 1 ? 3 : 2;
            h.assertTrue(
                Math.abs(actual - expected) < EPSILON && Math.abs(nativePlayer - expected) < EPSILON,
                "Physical critical or one-time enchantment differs: sample=" + index
                    + " expected="
                    + expected
                    + " maid="
                    + actual
                    + " nativePlayer="
                    + nativePlayer);
            h.assertTrue(
                !result.settlementHeld && wear(weapon) - beforeWear == cost
                    && wear(playerWeapon) - controlWear == cost
                    && target.hurtResistantTime == 20 * resistance
                    && control.hurtResistantTime == target.hurtResistantTime
                    && events.pre == pre + 1
                    && events.post == post + 1,
                "Native accepted-hit source, resistance, event count or finite wear differs for sample " + index);
            System.out.println(
                "TLMNH_UNIFIED209_MECHANICAL_SAMPLE index=" + index
                    + " naturalFalling="
                    + falling
                    + " rawPhysical="
                    + physical
                    + " magic="
                    + magic
                    + " actual="
                    + actual
                    + " cost="
                    + cost
                    + " resistance="
                    + target.hurtResistantTime
                    + " nativePlayer="
                    + nativePlayer);
            target.setDead();
            control.setDead();
            index++;
            prepare();
        }

        MaidMeleeAttack.Result submit() {
            waitingFor = "native_melee_submission";
            sampleAttempts++;
            totalAttempts++;
            var result = hit(f.maid, target);
            lastSubmission = "api=" + result.apiResult
                + ",accepted="
                + result.nativeAccepted
                + ",preCancelled="
                + result.preCancelled
                + ",held="
                + result.settlementHeld;
            waitingFor = result.nativeAccepted ? "accepted" : "production_refused";
            return result;
        }

        void trace(long now) {
            System.out.println(
                "TLMNH_UNIFIED209_MECHANICAL_TRACE worldTick=" + now
                    + " sample="
                    + index
                    + " terrain="
                    + terrainPhase
                    + " waitingFor="
                    + waitingFor
                    + " nativeControlUpdates="
                    + controlUpdates
                    + " sampleAttempts="
                    + sampleAttempts
                    + " totalAttempts="
                    + totalAttempts
                    + " lastSubmission="
                    + lastSubmission
                    + " maidXYZ="
                    + f.maid.posX
                    + ","
                    + f.maid.posY
                    + ","
                    + f.maid.posZ
                    + " maidGround="
                    + f.maid.onGround
                    + " maidMotionY="
                    + f.maid.motionY
                    + " maidFallDistance="
                    + f.maid.fallDistance
                    + " ownerXYZ="
                    + f.owner.posX
                    + ","
                    + f.owner.posY
                    + ","
                    + f.owner.posZ
                    + " ownerGround="
                    + f.owner.onGround
                    + " ownerMotionY="
                    + f.owner.motionY
                    + " ownerFallDistance="
                    + f.owner.fallDistance
                    + " ownerAge="
                    + f.owner.ticksExisted
                    + " ownerFlying="
                    + f.owner.capabilities.isFlying
                    + " maidPhysical="
                    + f.maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                        .getAttributeValue()
                    + " ownerPhysical="
                    + f.owner.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                        .getAttributeValue()
                    + " cadence="
                    + f.maid.getCombatCadence()
                        .remainingTicks()
                    + " receipt="
                    + f.maid.getCombatHitReceipt()
                        .stage());
        }

        void terrain() {
            long now = h.getWorld()
                .getTotalWorldTime();
            var quota = CombatWorldBudget.heartbeat(h.getWorld(), f.maid.getUniqueID(), now, true);
            var snapshot = observation.capture(
                f.maid,
                target,
                quota,
                1,
                f.maid.getEquipmentEpoch()
                    .generation(),
                false,
                f.maid.getCombatCadence()
                    .ready(),
                false,
                false,
                0,
                false,
                false,
                false,
                null);
            // 低顶反例须有完整预演输入；水下原生暴击条件不依赖全邻域感知完成
            if (terrainPhase == 0 && !snapshot.complete) {
                waitingFor = "terrain_snapshot_incomplete";
                return;
            }
            var plan = planner.decide(snapshot, quota);
            CombatWorldBudget.completed(h.getWorld(), f.maid.getUniqueID(), now);
            h.assertTrue(
                plan.macro != CombatPlan.Macro.JUMP_STRIKE,
                "Known low ceiling or water retained jump-strike choice");
            if (terrainPhase == 0) {
                h.assertTrue(
                    f.maid.onGround && snapshot.geometry != null && snapshot.geometry.complete,
                    "Low-ceiling control lacks native ground or complete known geometry");
                for (int x = 7; x <= 14; x++) for (int z = 2; z <= 6; z++) h.setBlock(x, 3, z, Blocks.air);
                for (int x = 9; x <= 12; x++) for (int z = 3; z <= 5; z++) h.setBlock(x, 1, z, Blocks.water);
                terrainPhase = 1;
                waitingFor = "native_water";
                return;
            }
            if (!f.maid.isInWater()) {
                waitingFor = "native_water";
                return;
            }
            h.assertFalse(
                snapshot.self.jumpAllowed,
                "Real native water input advertised falling-critical jump permission");
            if (!f.maid.getCombatCadence()
                .ready()) {
                waitingFor = "cadence_in_water";
                return;
            }
            float beforeHealth = target.getHealth();
            double physical = f.maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                .getAttributeValue();
            var result = submit();
            if (!result.nativeAccepted) return;
            h.assertTrue(
                Math.abs(beforeHealth - target.getHealth() - physical) < EPSILON,
                "Real native water melee gained forbidden critical damage");
            System.out.println(
                "TLMNH_UNIFIED209_MECHANICS_PASS representatives=vanillaSword+GTknife+GTbutchery normal+naturalFalling nativePlayerControl=true lowCeiling=noJump water=noCritical");
            done = true;
        }
    }

    public static final class HurtEvents {

        final EntityMaid maid;
        int pre, post;

        HurtEvents(EntityMaid maid) {
            this.maid = maid;
        }

        @SubscribeEvent
        public void pre(MaidHurtTarget.Pre event) {
            if (event.getMaid() == maid) pre++;
        }

        @SubscribeEvent
        public void post(MaidHurtTarget.Post event) {
            if (event.getMaid() == maid) post++;
        }
    }

    public static final class DeathDuringHit {

        final GameTestHelper h;
        final EntityMaid maid;
        boolean seen, active, unworn, unpublished;

        DeathDuringHit(GameTestHelper h, EntityMaid maid) {
            this.h = h;
            this.maid = maid;
        }

        @SubscribeEvent
        public void death(LivingDeathEvent event) {
            if (event.entityLiving != maid) return;
            seen = true;
            active = "thorns".equals(event.source.getDamageType()) && maid.getCombatHitReceipt()
                .activeCall()
                && maid.getCombatHitReceipt()
                    .stage() == CombatHitReceipt.Stage.ACCEPTED;
            unworn = maid.getHeldItem() != null && maid.getHeldItem()
                .getItemDamage() == 0;
            unpublished = MaidLifecycleData.get(h.getWorld())
                .deathToken(maid.getUniqueID()) == null;
        }
    }

    /** super的真实伤害返回ACCEPTED后再注入同步取消/旧读，不直接修改生命或资源 */
    public static final class AcceptedPig extends EntityPig {

        Runnable afterAccepted;

        AcceptedPig(World world) {
            super(world);
        }

        @Override
        public boolean attackEntityFrom(DamageSource source, float amount) {
            boolean accepted = super.attackEntityFrom(source, amount);
            if (accepted && source.getEntity() instanceof EntityMaid && afterAccepted != null) {
                var callback = afterAccepted;
                afterAccepted = null;
                callback.run();
            }
            return accepted;
        }
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper h) {
        var f = new ModernCoreTests.Fixture(h, false, 16, 20, MaidBrainProfile.ENHANCED);
        f.maid.setTask(MaidTask.ATTACK);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setCurrentItemOrArmor(0, tagged(new ItemStack(Items.iron_sword), "cancelled"));
        f.maid.getBrainCoordinator()
            .setSuspended(true);
        f.owner.setPosition(f.x + 18.5, f.y + 1, f.z + 4.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) h.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static EntityMaid extraMaid(GameTestHelper h, ModernCoreTests.Fixture f, double x, double z, String label) {
        var maid = new EntityMaid(h.getWorld());
        h.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Additional owned maid binding failed");
        select(h, maid, MaidBrainProfile.ENHANCED);
        maid.setTask(MaidTask.ATTACK);
        maid.setSchedule(MaidSchedule.ALL);
        maid.setPickup(false);
        maid.setCurrentItemOrArmor(0, tagged(new ItemStack(Items.iron_sword), label));
        maid.getBrainCoordinator()
            .setSuspended(true);
        h.spawnEntity(maid, x, 1, z);
        h.afterTest(() -> {
            maid.getBrainCoordinator()
                .setSuspended(true);
            maid.setDead();
            h.getWorld()
                .removePlayerEntityDangerously(maid);
            UUID token = MaidLifecycleData.get(h.getWorld())
                .deathToken(maid.getUniqueID());
            for (Object raw : h.getWorld().loadedEntityList.toArray())
                if (raw instanceof MaidTombstoneEntity grave && token != null && token.equals(grave.getToken()))
                    grave.setDead();
        });
        return maid;
    }

    private static EntityPig pig(GameTestHelper h, ModernCoreTests.Fixture f, double x, double z) {
        var pig = new EntityPig(h.getWorld());
        preparePig(pig);
        h.spawnEntity(pig, x, 1, z);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
        h.afterTest(pig::setDead);
        return pig;
    }

    private static AcceptedPig controlledPig(GameTestHelper h, ModernCoreTests.Fixture f, double x, double z) {
        var pig = new AcceptedPig(h.getWorld());
        preparePig(pig);
        h.spawnEntity(pig, x, 1, z);
        h.afterTest(pig::setDead);
        return pig;
    }

    private static void preparePig(EntityPig pig) {
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        pig.setHealth(200);
    }

    private static void select(GameTestHelper h, EntityMaid maid, MaidBrainProfile profile) {
        h.assertTrue(
            maid.selectBrainProfileForDevelopment(profile) == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Requested actual policy root failed to select " + profile);
    }

    /** 仅替换actor的授权输入，生产真实prepare/伤害事件/附魔/耐久/凭证均保持 */
    private static MaidMeleeAttack.Result hit(EntityMaid maid, EntityLivingBase target) {
        return MaidMeleeAttack.hurtForUnified(
            maid,
            target,
            () -> maid.isEntityAlive() && maid.getTask() == MaidTask.ATTACK
                && !maid.guiOpening
                && target.isEntityAlive()
                && maid.worldObj == target.worldObj
                && maid.dimension == target.dimension
                && maid.worldObj.getEntityByID(target.getEntityId()) == target
                && maid.getDistanceSqToEntity(target) <= 4.0D
                && maid.canEntityBeSeen(target));
    }

    private static ItemStack gregtech(int metadata) {
        return GregTechPublicApi.get()
            .create(metadata);
    }

    private static long wear(ItemStack stack) {
        return gregtechItem(stack) ? GregTechPublicApi.get()
            .damage(stack) : stack.getItemDamage();
    }

    private static boolean gregtechItem(ItemStack stack) {
        return stack != null && stack.getItem() != null
            && "gregtech.common.items.MetaGeneratedTool01".equals(
                stack.getItem()
                    .getClass()
                    .getName());
    }

    /** 只为夹具造物品、读取公开机械数据，原生玩家对照仍调用真实攻击入口 */
    private static final class GregTechPublicApi {

        private final Class<?> toolType;
        private final Field instance, steel, wood;
        private final Method create, damage, combatDamage;

        private GregTechPublicApi() throws ReflectiveOperationException {
            var loader = UnifiedCombatTests.class.getClassLoader();
            toolType = Class.forName("gregtech.common.items.MetaGeneratedTool01", false, loader);
            var generated = Class.forName("gregtech.api.items.MetaGeneratedTool", false, loader);
            var materials = Class.forName("gregtech.api.enums.Materials", false, loader);
            steel = material(materials, "Steel");
            wood = material(materials, "Wood");
            instance = toolType.getField("INSTANCE");
            if (!Modifier.isPublic(instance.getModifiers()) || !Modifier.isStatic(instance.getModifiers())
                || instance.getType() != toolType)
                throw new NoSuchFieldException("Unexpected public GregTech tool INSTANCE");
            // GT265官方源码的最后一个形参是long[]，不是getElectricStats返回的Long[]
            create = method(
                generated,
                "getToolWithStats",
                ItemStack.class,
                false,
                int.class,
                int.class,
                materials,
                materials,
                long[].class);
            damage = method(generated, "getToolDamage", long.class, true, ItemStack.class);
            combatDamage = method(generated, "getToolCombatDamage", float.class, false, ItemStack.class);
        }

        static GregTechPublicApi get() {
            return Holder.API;
        }

        ItemStack create(int metadata) {
            try {
                return (ItemStack) invoke(create, tool(), metadata, 1, steel.get(null), wood.get(null), (long[]) null);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("GregTech public fixture materials inaccessible", failure);
            }
        }

        long damage(ItemStack stack) {
            exact(stack);
            return (Long) invoke(damage, null, stack);
        }

        float combatDamage(ItemStack original) {
            exact(original);
            // 该公开方法内部会getToolStats重建ench，观测只在复制栈上调用
            return (Float) invoke(combatDamage, original.getItem(), original.copy());
        }

        private Object tool() {
            try {
                Object actual = instance.get(null);
                if (actual == null || actual.getClass() != toolType)
                    throw new IllegalStateException("Actual GregTech tool singleton differs");
                return actual;
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("GregTech public tool singleton inaccessible", failure);
            }
        }

        private void exact(ItemStack stack) {
            if (stack == null || stack.getItem() != tool())
                throw new IllegalStateException("Representative stack is not the actual GregTech tool singleton");
        }

        private static Field material(Class<?> type, String name) throws NoSuchFieldException {
            Field field = type.getField(name);
            if (!Modifier.isPublic(field.getModifiers()) || !Modifier.isStatic(field.getModifiers())
                || field.getType() != type)
                throw new NoSuchFieldException("Unexpected GregTech public fixture material " + name);
            return field;
        }

        private static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic,
            Class<?>... parameters) throws NoSuchMethodException {
            Method method = owner.getMethod(name, parameters);
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers()) != isStatic
                || method.getReturnType() != result)
                throw new NoSuchMethodException("Unexpected GregTech public fixture method " + name);
            return method;
        }

        private static Object invoke(Method method, Object receiver, Object... arguments) {
            try {
                return method.invoke(receiver, arguments);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                IntegrationGuard.throwIfFatal(cause);
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("GregTech public fixture API failed", cause);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("GregTech public fixture method inaccessible", failure);
            }
        }

        private static final class Holder {

            static final GregTechPublicApi API = load();

            private static GregTechPublicApi load() {
                try {
                    return new GregTechPublicApi();
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Pinned GregTech public fixture API is unavailable", failure);
                }
            }
        }
    }

    private static boolean falling(EntityLivingBase entity) {
        return !entity.onGround && entity.motionY < 0 && entity.fallDistance > 0 && !entity.isInWater();
    }

    private static ItemStack tagged(ItemStack stack, String label) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setString(OPAQUE, label);
        stack.getTagCompound()
            .setIntArray("FullPayload", new int[] { 209, 208, label.length() });
        return stack;
    }

    private static NBTTagCompound snapshot(EntityMaid maid) {
        var result = new NBTTagCompound();
        maid.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    private static NBTTagCompound deathEntry(GameTestHelper h, UUID token) {
        var saved = new NBTTagCompound();
        MaidLifecycleData.get(h.getWorld())
            .writeToNBT(saved);
        var entries = saved.getTagList("Entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            var entry = entries.getCompoundTagAt(i);
            if (token != null && token.toString()
                .equals(entry.getString("Token"))) return entry;
        }
        return new NBTTagCompound();
    }

    private static int graves(GameTestHelper h, UUID token) {
        int result = 0;
        for (Object raw : h.getWorld().loadedEntityList.toArray())
            if (raw instanceof MaidTombstoneEntity grave && token != null && token.equals(grave.getToken())) result++;
        return result;
    }
}
