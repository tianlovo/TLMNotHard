package com.tianluoqaq.tlmnh.common.life;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.life.armor.spi.SpecialArmorCompatProvider.ArmorSupportAccess;
import com.tianluoqaq.tlmnh.common.life.flight.FlightTuning;
import com.tianluoqaq.tlmnh.common.life.hunger.MaidHungerTuning;

/** 服务端生存结算的实体适配，状态仍由各领域控制器独占 */
public final class MaidSurvivalController implements ArmorSupportAccess {

    private final EntityMaid maid;
    private long lastWorldTick = Long.MIN_VALUE;

    public MaidSurvivalController(EntityMaid maid) {
        this.maid = maid;
    }

    public void tick() {
        if (!maid.isEntityAlive() || maid.worldObj.getTotalWorldTime() == lastWorldTick) return;
        lastWorldTick = maid.worldObj.getTotalWorldTime();
        CompatServices.healingAxe()
            .tickOffhandPassiveHunger(maid.worldObj, maid.getOffhandItem(), maid.getHungerState()::addStats);
        maid.getHungerState()
            .tick(maid, CompatServices.hungerPolicy());
        maid.syncHunger();
        maid.getHotItemHazard()
            .tick(maid, CompatServices.hotItemHazard());
        if (!maid.isEntityAlive()) return;
        if (CompatServices.specialArmor()
            .tickSpecialArmor(
                maid,
                maid.getEquipmentInSlot(MaidSurvivalTuning.BOOTS),
                maid.getEquipmentInSlot(MaidSurvivalTuning.LEGS),
                maid.getEquipmentInSlot(MaidSurvivalTuning.CHEST),
                maid.getEquipmentInSlot(MaidSurvivalTuning.HELMET),
                this)) {
            maid.getMaidInventory()
                .markDirty();
            maid.getEquipmentEpoch()
                .invalidate();
            maid.syncHunger();
        }
        maid.getSpaceSurvival()
            .tick(maid, CompatServices.spaceSurvival());
        maid.getFallRescue()
            .tick(
                maid,
                maid.getFlightController()
                    .isActivelyHoveringOrFlying(),
                CompatServices.specialArmor(),
                CompatServices.spaceSurvival(),
                CompatServices.fallRescueBoat());
    }

    public void recordTravel(double x, double y, double z, boolean autonomous) {
        if (!autonomous || maid.worldObj.isRemote
            || maid.isRiding()
            || maid.getFlightController()
                .isActivelyHoveringOrFlying())
            return;
        double dx = maid.posX - x, dy = maid.posY - y, dz = maid.posZ - z;
        double distance = maid.isInWater() ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Math.sqrt(dx * dx + dz * dz);
        float rate = maid.isInWater() ? MaidHungerTuning.SWIM_EXHAUSTION_PER_BLOCK
            : maid.isSprinting() ? MaidHungerTuning.SPRINT_EXHAUSTION_PER_BLOCK
                : MaidHungerTuning.WALK_EXHAUSTION_PER_BLOCK;
        if (Double.isFinite(distance)) maid.getHungerState()
            .addExhaustion((float) distance * rate);
    }

    public void recordJump() {
        if (!maid.isRiding() && !maid.getFlightController()
            .isActivelyHoveringOrFlying()) maid.getHungerState()
                .addExhaustion(
                    maid.isSprinting() ? MaidHungerTuning.SPRINT_JUMP_EXHAUSTION : MaidHungerTuning.JUMP_EXHAUSTION);
    }

    @Override
    public ItemStack extractMatching(java.util.function.Predicate<ItemStack> filter, int count, boolean simulate) {
        return MaidSurvivalInventory.extract(maid, filter, count, simulate);
    }

    @Override
    public int currentFood() {
        return maid.getHungerState()
            .getFood();
    }

    @Override
    public void addFoodStats(int food, float saturation) {
        maid.getHungerState()
            .addStats(food, saturation);
    }

    @Override
    public int maidRawSlotCount() {
        return MaidInventoryLayout.OFF_HAND + 1;
    }

    @Override
    public ItemStack getMaidRawSlot(int slot) {
        return maid.getMaidInventory()
            .getStackInSlot(slot);
    }

    @Override
    public void setMaidRawSlot(int slot, ItemStack stack) {
        maid.getMaidInventory()
            .setInventorySlotContents(slot, stack);
    }

    @Override
    public ItemStack insertIntoMaidInventory(ItemStack stack, boolean simulate) {
        return maid.getMaidInventory()
            .insertAvailable(stack, simulate);
    }

    @Override
    public boolean isGroundFastMoving() {
        return maid.onGround && maid.isSprinting()
            && !maid.isRiding()
            && (maid.moveForward != 0 || maid.moveStrafing != 0);
    }

    @Override
    public void applyQuantumLegSpeedBoost() {
        maid.motionX *= FlightTuning.QUANTUM_GROUND_SPEED_MULTIPLIER;
        maid.motionZ *= FlightTuning.QUANTUM_GROUND_SPEED_MULTIPLIER;
    }
}
