package com.tianluoqaq.tlmnh.common.combat.shield;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Lease;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** TLM举盾生命周期与RC2物品格挡分开，冷却按Item维护，不占主手近战动作 */
public final class MaidShieldController {

    public enum Cause {
        NONE,
        ACTIVE,
        PASSIVE
    }

    public enum Outcome {
        BLOCKED,
        NOT_USING,
        INVALID_AMOUNT,
        UNSUPPORTED_ITEM,
        SOURCE_REJECTED,
        DIRECTION_REJECTED,
        INVALID_ITEM_RULE,
        STALE_USE,
        REENTRANT_EVENT,
        ERROR
    }

    /** 只包含scalar和enum，开发报告不得递归序列化世界、物品或实体 */
    public static final class Snapshot {

        public final boolean using, activeTaskRunning, arrowCatchSupported;
        public final Cause cause;
        public final int passiveRemaining, currentItemCooldown;
        public final long maintenanceTicks, activeEndTime, reentrantRejections;
        public final String itemId, lastStop;

        private Snapshot(MaidShieldController controller) {
            using = controller.isBlocking();
            cause = using ? controller.cause : Cause.NONE;
            activeTaskRunning = controller.activeTaskRunning;
            passiveRemaining = controller.passiveRemaining;
            maintenanceTicks = controller.maintenanceTicks;
            activeEndTime = controller.activeEndTime;
            reentrantRejections = controller.reentrantRejections;
            var stack = controller.maid.getOffhandItem();
            itemId = MaidShieldItems.itemId(stack);
            currentItemCooldown = stack == null ? 0 : controller.getCooldownRemaining(stack.getItem());
            lastStop = controller.lastStop;
            arrowCatchSupported = false;
        }
    }

    public static final class BlockResult {

        public final long sequence;
        public final int depth, requestedWear, stackSizeAfter, cooldownApplied;
        public final Outcome outcome;
        public final boolean blocked, wearCallbackInvoked, brokenAfter, equipmentChangedDuringCallback;
        public final boolean arrowCatchSupported = false;
        public final String itemId, sourceType, sourceEntity, directEntity;
        public final Float amount, relativeYaw, blockAngle, durabilityReduction;

        private BlockResult(long sequence, int depth, Outcome outcome, String itemId, DamageSource source, float amount,
            Float relativeYaw, Float blockAngle, Float reduction, int wear, boolean wearInvoked, ItemStack shield,
            boolean equipmentChanged, int cooldown) {
            this.sequence = sequence;
            this.depth = depth;
            this.outcome = outcome;
            blocked = outcome == Outcome.BLOCKED;
            this.itemId = itemId;
            sourceType = source == null ? "" : source.getDamageType();
            sourceEntity = source == null ? "" : id(source.getEntity());
            directEntity = source == null ? "" : id(source.getSourceOfDamage());
            this.amount = finite(amount);
            this.relativeYaw = relativeYaw;
            this.blockAngle = blockAngle;
            durabilityReduction = reduction;
            requestedWear = wear;
            wearCallbackInvoked = wearInvoked;
            stackSizeAfter = shield == null ? 0 : shield.stackSize;
            var data = MaidShieldItems.toolData(shield);
            brokenAfter = data != null && data.getBoolean("Broken");
            equipmentChangedDuringCallback = equipmentChanged;
            cooldownApplied = cooldown;
        }
    }

    private static final class DamageFrame {

        private final int depth = 1;
    }

    private final EntityMaid maid;
    private final MaidItemUseState uses;
    private final Map<Item, Integer> cooldowns = new HashMap<>();
    private Lease lease, activeLease, passiveLease;
    private Cause cause = Cause.NONE;
    private boolean activeTaskRunning;
    private int passiveRemaining;
    private long maintenanceTicks, activeEndTime, sequence, reentrantRejections;
    private World lastTickWorld;
    private World lastActiveWorld;
    private long lastTickTime = Long.MIN_VALUE;
    private long lastActiveTime = Long.MIN_VALUE;
    private int lastEntityTick = Integer.MIN_VALUE;
    private String lastStop = "initial";
    private DamageFrame damageFrame;
    private BlockResult lastBlock;

    public MaidShieldController(EntityMaid maid, MaidItemUseState uses) {
        this.maid = Objects.requireNonNull(maid, "maid");
        this.uses = Objects.requireNonNull(uses, "uses");
    }

    public boolean canUseShield() {
        var stack = maid.getOffhandItem();
        return canOperate() && MaidShieldItems.inspect(stack)
            .isReady() && getCooldownRemaining(stack.getItem()) == 0;
    }

    public boolean isBlocking() {
        return lease != null && uses.isCurrent(lease);
    }

    public int getCooldownRemaining(Item item) {
        return item == null ? 0 : cooldowns.getOrDefault(item, 0);
    }

    public Snapshot getSnapshot() {
        return new Snapshot(this);
    }

    public BlockResult getLastBlock() {
        return lastBlock;
    }

    /** 原TLM在super.hurt之前激活；原生取消仍可能举盾，但不能因此磨盾 */
    public void beforeIncoming(DamageSource source, float amount) {
        if (source == null || !source.isProjectile() || !canUseShield()) return;
        Entity attacker = source.getEntity();
        if (attacker instanceof EntityPlayer player && (maid.isOwner(player) || maid.isOnSameTeam(player))) return;
        uses.reconcile();
        var current = uses.peek();
        if (current != null && current.getHand() == Hand.OFF && uses.isCurrent(current)) return;
        passiveLease = beginUse(Cause.PASSIVE);
        // 原startUsingItem会拒绝抢占MAIN，但TLM仍设置counter
        passiveRemaining = MaidShieldRules.PASSIVE_TICKS;
    }

    /** 此入口只维护原近战举盾Behavior，不能替代一次近战攻击或占用路径 */
    public void updateActiveTarget(EntityLivingBase target) {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        long now = maid.worldObj.getTotalWorldTime();
        if (lastActiveWorld == maid.worldObj && lastActiveTime == now) return;
        lastActiveWorld = maid.worldObj;
        lastActiveTime = now;
        boolean permitted = canUseShield() && !maid.getBrainCoordinator()
            .isSuspended()
            && maid.getTask() == MaidTask.ATTACK
            && target != null
            && target == maid.getAttackTarget()
            && target.worldObj == maid.worldObj
            && target.isEntityAlive()
            && maid.getDistanceSqToEntity(target) < 64;
        if (activeTaskRunning) {
            if (!permitted || MaidShieldRules.activeTimedOut(now, activeEndTime)) {
                activeTaskRunning = false;
                release(activeLease, permitted ? "active_timed_out" : "active_condition_lost");
                activeLease = null;
            }
            return;
        }
        if (!permitted) return;
        // Behavior默认min=max=60，bound=1仍消耗一次世界RNG
        activeEndTime = now + MaidShieldRules.ACTIVE_DURATION + maid.worldObj.rand.nextInt(1);
        activeTaskRunning = true;
        activeLease = beginUse(Cause.ACTIVE);
    }

    /** 只由EntityMaid.onLivingUpdate的super之后调用，伤害与AI入口不推进时钟 */
    public void tickAfterLivingUpdate() {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        var world = maid.worldObj;
        long time = world.getTotalWorldTime();
        if (lastTickWorld == world && lastTickTime == time && lastEntityTick == maid.ticksExisted) return;
        lastTickWorld = world;
        lastTickTime = time;
        lastEntityTick = maid.ticksExisted;
        maintenanceTicks++;
        var iterator = cooldowns.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) iterator.remove();
            else entry.setValue(remaining);
        }
        uses.reconcile();
        if (passiveRemaining > 0) {
            passiveRemaining = MaidShieldRules.maintainPassive(
                passiveRemaining,
                MaidShieldItems.inspect(maid.getOffhandItem())
                    .isReady());
            if (passiveRemaining == 1) release(passiveLease, "passive_counter_one");
            if (passiveRemaining == 0) passiveLease = null;
        }
    }

    /** 普通取消只释放使用权，按Item的冷却不会随任务或新ItemStack重置 */
    public void cancelUse(String reason) {
        activeTaskRunning = false;
        activeLease = null;
        release(lease, reason);
    }

    public void onEntityBoundary(String reason) {
        cancelUse(reason);
        passiveRemaining = 0;
        passiveLease = null;
        lastActiveWorld = null;
        lastActiveTime = Long.MIN_VALUE;
        uses.invalidate();
    }

    public void equipmentChanged() {
        uses.reconcile();
    }

    private Lease beginUse(Cause requestedCause) {
        if (!canUseShield()) return null;
        if (isBlocking()) return lease;
        var stack = maid.getOffhandItem();
        var world = maid.worldObj;
        UUID identity = maid.getUniqueID();
        var acquired = uses.tryAcquire(
            this,
            stack,
            Hand.OFF,
            Kind.SHIELD,
            () -> canOperate() && maid.worldObj == world
                && identity.equals(maid.getUniqueID())
                && maid.getOffhandItem() == stack
                && MaidShieldItems.inspect(stack)
                    .isReady()
                && getCooldownRemaining(stack.getItem()) == 0);
        if (acquired != null) {
            lease = acquired;
            cause = requestedCause;
        }
        return acquired;
    }

    private void release(Lease expected, String reason) {
        if (expected == null) return;
        uses.release(expected);
        if (lease == expected) {
            lease = null;
            cause = Cause.NONE;
            lastStop = reason;
        }
    }

    private boolean canOperate() {
        return maid.worldObj != null && !maid.worldObj.isRemote
            && maid.isEntityAlive()
            && maid.getBrainCoordinator()
                .allowsReactiveItemUse();
    }

    /** 由真实Forge LivingHurt处理器调用，不提前短路原生LivingAttack或无敌帧 */
    public void onHurt(LivingHurtEvent event) {
        if (event == null || event.entityLiving != maid || event.isCanceled()) return;
        var previous = damageFrame;
        if (previous != null) {
            // 物品能力/耗损回调中的未知重入不再次格挡，不取消原生内层伤害
            reentrantRejections++;
            record(
                previous.depth + 1,
                Outcome.REENTRANT_EVENT,
                MaidShieldItems.itemId(maid.getOffhandItem()),
                event.source,
                event.ammount,
                null,
                null,
                null,
                -1,
                false,
                maid.getOffhandItem(),
                false,
                0);
            return;
        }
        var frame = new DamageFrame();
        damageFrame = frame;
        try {
            block(event, frame.depth);
        } finally {
            damageFrame = previous;
        }
    }

    private void block(LivingHurtEvent event, int depth) {
        float amount = event.ammount;
        var source = event.source;
        var shield = maid.getOffhandItem();
        var profile = MaidShieldItems.inspect(shield);
        String itemId = profile.itemId;
        Float yaw = null, angle = null, reduction = null;
        int wear = -1, cooldown = 0;
        boolean wore = false;
        if (!Float.isFinite(amount) || amount <= 0) {
            record(
                depth,
                Outcome.INVALID_AMOUNT,
                itemId,
                source,
                amount,
                yaw,
                angle,
                reduction,
                wear,
                wore,
                shield,
                false,
                0);
            return;
        }
        uses.reconcile();
        var use = lease;
        if (!isBlocking()) {
            record(
                depth,
                Outcome.NOT_USING,
                itemId,
                source,
                amount,
                yaw,
                angle,
                reduction,
                wear,
                wore,
                shield,
                false,
                0);
            return;
        }
        if (!profile.isReady()) {
            record(
                depth,
                Outcome.UNSUPPORTED_ITEM,
                itemId,
                source,
                amount,
                yaw,
                angle,
                reduction,
                wear,
                wore,
                shield,
                false,
                0);
            return;
        }
        var world = maid.worldObj;
        var identity = maid.getUniqueID();
        Item originalItem = shield.getItem();
        try {
            if (!MaidShieldItems.canBlock(shield, source)) {
                record(
                    depth,
                    Outcome.SOURCE_REJECTED,
                    itemId,
                    source,
                    amount,
                    yaw,
                    angle,
                    reduction,
                    wear,
                    wore,
                    shield,
                    false,
                    0);
                return;
            }
            Entity opponent = source.getEntity();
            if (opponent != null) {
                float rawAngle = MaidShieldItems.blockAngle(shield);
                float rawYaw = MaidShieldRules
                    .relativeYaw(opponent.posX, opponent.posZ, maid.posX, maid.posZ, maid.rotationYaw, Math::random);
                angle = finite(rawAngle);
                yaw = finite(rawYaw);
                if (!MaidShieldRules.withinAngle(rawYaw, rawAngle)) {
                    record(
                        depth,
                        Outcome.DIRECTION_REJECTED,
                        itemId,
                        source,
                        amount,
                        yaw,
                        angle,
                        reduction,
                        wear,
                        wore,
                        shield,
                        false,
                        0);
                    return;
                }
            }
            float rawReduction = MaidShieldItems.durabilityReduction(shield, source);
            reduction = finite(rawReduction);
            if (reduction == null) {
                record(
                    depth,
                    Outcome.INVALID_ITEM_RULE,
                    itemId,
                    source,
                    amount,
                    yaw,
                    angle,
                    reduction,
                    wear,
                    wore,
                    shield,
                    false,
                    0);
                return;
            }
            wear = MaidShieldRules.requestedWear(amount, rawReduction);
            if (!uses.isCurrent(use) || maid.getOffhandItem() != shield) {
                record(
                    depth,
                    Outcome.STALE_USE,
                    itemId,
                    source,
                    amount,
                    yaw,
                    angle,
                    reduction,
                    wear,
                    wore,
                    shield,
                    true,
                    0);
                return;
            }
            event.setCanceled(true);
            maid.playSound("battlegear2:shield", 1, 1);
            // NPC箭捕获另做原生碰撞时序验收，此切片不调用EntityPlayer专用catchArrow
            if (wear >= 0) {
                wore = true;
                shield.damageItem(wear, maid);
            }
            boolean sameUse = sameUseContext(use, shield, world, identity);
            boolean disable = sameUse && !source.isProjectile()
                && source.getSourceOfDamage() instanceof EntityLivingBase attacker
                && MaidShieldItems.canDisableShield(attacker.getHeldItem());
            // getToolClasses也是外部物品回调，不能沿用它之前的授权快照
            sameUse = sameUseContext(use, shield, world, identity);
            if (disable && sameUse) {
                cooldowns.put(originalItem, MaidShieldRules.DISABLE_TICKS);
                cooldown = MaidShieldRules.DISABLE_TICKS;
                release(use, "shield_disabled");
            }
            // 物品回调可换槽；只清本次磨损的实际那份装备
            if (maid.getOffhandItem() == shield && shield.stackSize <= 0) maid.setOffhandItem(null);
            uses.reconcile();
            maid.syncOffhand();
            record(
                depth,
                Outcome.BLOCKED,
                itemId,
                source,
                amount,
                yaw,
                angle,
                reduction,
                wear,
                wore,
                shield,
                !sameUse,
                cooldown);
        } catch (RuntimeException | LinkageError failure) {
            release(use, "shield_callback_failed");
            record(
                depth,
                Outcome.ERROR,
                itemId,
                source,
                amount,
                yaw,
                angle,
                reduction,
                wear,
                wore,
                shield,
                maid.getOffhandItem() != shield,
                cooldown);
            throw failure;
        }
    }

    private boolean sameUseContext(Lease expected, ItemStack shield, World world, UUID identity) {
        return uses.peek() == expected && maid.getOffhandItem() == shield
            && maid.worldObj == world
            && identity.equals(maid.getUniqueID())
            && canOperate();
    }

    private void record(int depth, Outcome outcome, String itemId, DamageSource source, float amount, Float yaw,
        Float angle, Float reduction, int wear, boolean wore, ItemStack shield, boolean changed, int cooldown) {
        lastBlock = new BlockResult(
            ++sequence,
            depth,
            outcome,
            itemId,
            source,
            amount,
            yaw,
            angle,
            reduction,
            wear,
            wore,
            shield,
            changed,
            cooldown);
    }

    private static Float finite(float value) {
        return Float.isFinite(value) ? value : null;
    }

    private static String id(Entity entity) {
        return entity == null ? ""
            : entity.getUniqueID()
                .toString();
    }
}
