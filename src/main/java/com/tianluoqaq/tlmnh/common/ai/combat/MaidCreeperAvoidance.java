package com.tianluoqaq.tlmnh.common.ai.combat;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.init.Items;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * Source: fixed TLM 8a3ac5e event/EntityJoinWorldEvent.java, onCreeperJoinWorld
 * Retains its priority, nearest visible target and nonempty-path admission, using native random positions/navigation
 */
public final class MaidCreeperAvoidance {

    private static boolean registered;

    private MaidCreeperAvoidance() {}

    public static void register() {
        if (registered)
            throw new IllegalStateException(AiCombatDiagnostics.MSG_MAID_CREEPER_AVOIDANCE_ALREADY_REGISTERED);
        registered = true;
        MinecraftForge.EVENT_BUS.register(new MaidCreeperAvoidance());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onJoin(EntityJoinWorldEvent event) {
        if (event.world.isRemote || !(event.entity instanceof EntityCreeper creeper)
            || creeper.isDead
            || creeper.worldObj != event.world) return;
        guarded(() -> {
            // The goal belongs to this entity object; a native reload constructs a fresh task list
            for (var entry : creeper.tasks.taskEntries) if (entry.action instanceof AvoidMaidGoal) return false;
            creeper.tasks.addTask(AiCombatTuning.ADD_TASK_SINGLE_UNIT, new AvoidMaidGoal(creeper));
            return true;
        });
    }

    private static boolean guarded(IntegrationGuard.Call<Boolean> operation) {
        if (!IntegrationGuard.available(Side.SERVER, AiCombatIds.GUARD_KEY)) return false;
        try {
            return operation.get();
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, AiCombatIds.GUARD_KEY, failure);
            return false;
        }
    }

    /** Source AvoidEntityGoal control flow; native random-position and path algorithms retain platform behavior */
    public static final class AvoidMaidGoal extends EntityAIBase {

        private final EntityCreeper creeper;
        private final PathNavigate navigator;
        private EntityMaid toAvoid;
        private PathEntity path;

        private AvoidMaidGoal(EntityCreeper creeper) {
            this.creeper = creeper;
            navigator = creeper.getNavigator();
            setMutexBits(AiCombatTuning.ADD_TASK_SINGLE_UNIT);
        }

        @Override
        public boolean shouldExecute() {
            return guarded(() -> {
                toAvoid = null;
                double nearest = Double.MAX_VALUE;
                // Keep the native query order untouched; ties retain the first eligible object as in getNearestEntity
                for (Object value : creeper.worldObj.getEntitiesWithinAABB(
                    EntityMaid.class,
                    creeper.boundingBox.expand(
                        AiCombatTuning.CREEPER_AVOIDANCE_EXPAND_FIRST_FACTOR,
                        AiCombatTuning.CREEPER_AVOIDANCE_EXPAND_SECOND_FACTOR,
                        AiCombatTuning.CREEPER_AVOIDANCE_EXPAND_FIRST_FACTOR))) {
                    if (!(value instanceof EntityMaid maid) || !eligible(maid)) continue;
                    double distance = creeper.getDistanceSqToEntity(maid);
                    if (distance < nearest) {
                        nearest = distance;
                        toAvoid = maid;
                    }
                }
                if (toAvoid == null) return false;
                var destination = RandomPositionGenerator.findRandomTargetBlockAwayFrom(
                    creeper,
                    AiCombatTuning.CREEPER_AVOIDANCE_SHOULD_EXECUTE_PARAM,
                    AiCombatTuning.CREEPER_AVOIDANCE_SHOULD_EXECUTE_PARAM_2,
                    Vec3.createVectorHelper(toAvoid.posX, toAvoid.posY, toAvoid.posZ));
                if (destination == null
                    || toAvoid.getDistanceSq(destination.xCoord, destination.yCoord, destination.zCoord)
                        < toAvoid.getDistanceSqToEntity(creeper))
                    return false;
                path = navigator.getPathToXYZ(destination.xCoord, destination.yCoord, destination.zCoord);
                return path != null;
            });
        }

        private boolean eligible(EntityMaid maid) {
            if (maid.isDead || !maid.isEntityAlive()
                || maid.worldObj != creeper.worldObj
                || maid.isEntityInvulnerable()
                || !creeper.canAttackClass(maid.getClass())
                || creeper.isOnSameTeam(maid)) return false;
            double visibility = maid.isSneaking() ? AiCombatTuning.CREEPER_AVOIDANCE_ELIGIBLE_FACTOR
                : AiCombatTuning.CREEPER_AVOIDANCE_ELIGIBLE_FACTOR_2;
            if (maid.isInvisible()) {
                int equipped = 0;
                for (int slot = 1; slot <= AiCombatTuning.MIN_SLOT_THRESHOLD; slot++) {
                    var stack = maid.getEquipmentInSlot(slot);
                    if (stack != null && stack.stackSize > 0) equipped++;
                }
                visibility *= AiCombatTuning.CREEPER_AVOIDANCE_ELIGIBLE_FACTOR_3 * Math.max(
                    equipped / AiCombatTuning.CREEPER_AVOIDANCE_MAX_FIRST_FACTOR,
                    AiCombatTuning.CREEPER_AVOIDANCE_MAX_SECOND_FACTOR);
            }
            var head = maid.getEquipmentInSlot(AiCombatTuning.MIN_SLOT_THRESHOLD);
            if (AiCombatIds.KEY_CREEPER.equals(EntityList.getEntityString(creeper)) && head != null
                && head.stackSize > 0
                && head.getItem() == Items.skull
                && head.getItemDamage() == AiCombatTuning.MIN_SLOT_THRESHOLD)
                visibility *= AiCombatTuning.HALF_FACTOR_D;
            // Modern Forge's visibility-multiplier event has no equivalent in Forge1614; no substitute API is guessed
            double range = Math.max(
                AiCombatTuning.CREEPER_AVOIDANCE_EXPAND_FIRST_FACTOR * visibility,
                AiCombatTuning.COMBAT_TARGETING_WIDTH);
            return creeper.getDistanceSqToEntity(maid) <= range * range && creeper.getEntitySenses()
                .canSee(maid);
        }

        @Override
        public boolean continueExecuting() {
            return guarded(() -> !navigator.noPath());
        }

        @Override
        public void startExecuting() {
            guarded(() -> {
                navigator.setPath(path, AiCombatTuning.CREEPER_AVOIDANCE_ELIGIBLE_FACTOR_2);
                return true;
            });
        }

        @Override
        public void resetTask() {
            toAvoid = null;
            path = null;
        }

        @Override
        public void updateTask() {
            guarded(() -> {
                navigator.setSpeed(
                    creeper.getDistanceSqToEntity(toAvoid) < AiCombatTuning.CREEPER_AVOIDANCE_SET_SPEED_FIRST_FACTOR
                        ? AiCombatTuning.CREEPER_AVOIDANCE_SET_SPEED_FIRST_FACTOR_2
                        : AiCombatTuning.CREEPER_AVOIDANCE_ELIGIBLE_FACTOR_2);
                return true;
            });
        }
    }
}
