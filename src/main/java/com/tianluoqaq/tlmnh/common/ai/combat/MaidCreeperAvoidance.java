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
    private static final String GUARD_KEY = "behavior/creeper_avoidance";

    private MaidCreeperAvoidance() {}

    public static void register() {
        if (registered) throw new IllegalStateException("Maid Creeper avoidance already registered");
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
            creeper.tasks.addTask(1, new AvoidMaidGoal(creeper));
            return true;
        });
    }

    private static boolean guarded(IntegrationGuard.Call<Boolean> operation) {
        if (!IntegrationGuard.available(Side.SERVER, GUARD_KEY)) return false;
        try {
            return operation.get();
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GUARD_KEY, failure);
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
            setMutexBits(1);
        }

        @Override
        public boolean shouldExecute() {
            return guarded(() -> {
                toAvoid = null;
                double nearest = Double.MAX_VALUE;
                // Keep the native query order untouched; ties retain the first eligible object as in getNearestEntity
                for (Object value : creeper.worldObj
                    .getEntitiesWithinAABB(EntityMaid.class, creeper.boundingBox.expand(6.0D, 3.0D, 6.0D))) {
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
                    16,
                    7,
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
            double visibility = maid.isSneaking() ? 0.8D : 1.0D;
            if (maid.isInvisible()) {
                int equipped = 0;
                for (int slot = 1; slot <= 4; slot++) {
                    var stack = maid.getEquipmentInSlot(slot);
                    if (stack != null && stack.stackSize > 0) equipped++;
                }
                visibility *= 0.7D * Math.max(equipped / 4.0F, 0.1F);
            }
            var head = maid.getEquipmentInSlot(4);
            if ("Creeper".equals(EntityList.getEntityString(creeper)) && head != null
                && head.stackSize > 0
                && head.getItem() == Items.skull
                && head.getItemDamage() == 4) visibility *= 0.5D;
            // Modern Forge's visibility-multiplier event has no equivalent in Forge1614; no substitute API is guessed
            double range = Math.max(6.0D * visibility, 2.0D);
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
                navigator.setPath(path, 1.0D);
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
                navigator.setSpeed(creeper.getDistanceSqToEntity(toAvoid) < 49.0D ? 1.2D : 1.0D);
                return true;
            });
        }
    }
}
