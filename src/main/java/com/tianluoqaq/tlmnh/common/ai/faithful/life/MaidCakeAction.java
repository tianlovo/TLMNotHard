package com.tianluoqaq.tlmnh.common.ai.faithful.life;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 原生蛋糕的有限库存提交；同步Forge回调后重新确认当前动作，不扣替换过的物品 */
public final class MaidCakeAction {

    private final EntityMaid maid;
    private final World world;
    private final BooleanSupplier current;
    private final Supplier<Object> lease;
    private boolean committing;
    private long epoch, lastTick = Long.MIN_VALUE;
    private int lastAge = FaithfulLifeTuning.UNSET_OR_NOT_FOUND, bites, placements;
    private Object lastLease;

    public MaidCakeAction(EntityMaid maid, BooleanSupplier current, Supplier<Object> lease) {
        this.maid = maid;
        world = maid.worldObj;
        this.current = current;
        this.lease = lease;
    }

    private int cakeSlot() {
        var inv = maid.getMaidInventory();
        // 原getAvailableInv(true)：先双手，再实际六格背包，不包括护甲
        for (int index = 0; index < MaidInventoryLayout.BACKPACK_SIZE + FaithfulLifeTuning.BED_EXIT_SIDE; index++) {
            int slot = index < FaithfulLifeTuning.BED_EXIT_SIDE ? MaidInventoryLayout.MAIN_HAND + index
                : index - FaithfulLifeTuning.BED_EXIT_SIDE;
            var stack = inv.getStackInSlot(slot);
            if (stack != null && stack.stackSize > 0 && stack.getItem() == Items.cake) return slot;
        }
        return FaithfulLifeTuning.MISSING_CAKE_SLOT;
    }

    public ItemStack firstCakeStack() {
        int slot = cakeSlot();
        return slot < 0 ? null
            : maid.getMaidInventory()
                .getStackInSlot(slot);
    }

    private boolean onCabinet(Point point) {
        return maid.worldObj == world && world.blockExists(point.x, point.y - 1, point.z)
            && world.blockExists(point.x, point.y, point.z)
            && world.getBlock(point.x, point.y - 1, point.z) == MaidLifeContent.snackCabinet;
    }

    public boolean shouldSteal(Point point) {
        return onCabinet(point) && world.getBlock(point.x, point.y, point.z) == Blocks.cake;
    }

    public boolean shouldPlace(Point point, ItemStack stack) {
        return stack != null && stack.stackSize > 0
            && stack.getItem() == Items.cake
            && onCabinet(point)
            && !sameFeet(point)
            && world.getBlock(point.x, point.y, point.z)
                .isReplaceable(world, point.x, point.y, point.z);
    }

    private boolean sameFeet(Point point) {
        return point.x == MathHelper.floor_double(maid.posX) && point.y == MathHelper.floor_double(maid.posY)
            && point.z == MathHelper.floor_double(maid.posZ);
    }

    private boolean admit() {
        if (!current.getAsBoolean() || committing) return false;
        var token = lease.get();
        long tick = world.getTotalWorldTime();
        if (lastLease == token && lastTick == tick && lastAge == maid.ticksExisted) return false;
        lastLease = token;
        lastTick = tick;
        lastAge = maid.ticksExisted;
        committing = true;
        return true;
    }

    public boolean consume(Point point) {
        if (!shouldSteal(point) || !admit()) return false;
        try {
            int next = MaidLifeRules.nextCakeBites(world.getBlockMetadata(point.x, point.y, point.z));
            boolean result = next < 0 ? world.setBlockToAir(point.x, point.y, point.z)
                : world
                    .setBlockMetadataWithNotify(point.x, point.y, point.z, next, FaithfulLifeTuning.LOW_TWO_BITS_MASK);
            if (result) {
                bites++;
                if (current.getAsBoolean()) {
                    if (world instanceof WorldServer server) server.func_147487_a(
                        FaithfulLifeIds.ID_ICONCRACK + Item.getIdFromItem(Items.cake),
                        maid.posX,
                        maid.posY + maid.getEyeHeight(),
                        maid.posZ,
                        FaithfulLifeTuning.CAKE_ACTION_CONSUME_PARAM,
                        FaithfulLifeTuning.CAKE_ACTION_CONSUME_FACTOR,
                        FaithfulLifeTuning.CAKE_ACTION_CONSUME_FACTOR,
                        FaithfulLifeTuning.CAKE_ACTION_CONSUME_FACTOR,
                        FaithfulLifeTuning.CAKE_ACTION_CONSUME_FACTOR_2);
                    maid.playSound(
                        FaithfulLifeIds.ID_RANDOM_EAT,
                        FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP,
                        FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP);
                }
            }
            return result;
        } finally {
            committing = false;
        }
    }

    public boolean place(Point point) {
        int slot = cakeSlot();
        if (slot < 0 || !(world instanceof WorldServer server)) return false;
        var inv = maid.getMaidInventory();
        var original = inv.getStackInSlot(slot);
        if (!shouldPlace(point, original) || !admit()) return false;
        long attempt = epoch;
        Object token = lease.get();
        var expected = original.copy();
        var one = original.copy();
        one.stackSize = FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP;
        var fake = FakePlayerFactory
            .get(server, new GameProfile(maid.getUniqueID(), FaithfulLifeDiagnostics.MSG_TLMNH_MAID));
        int selected = fake.inventory.currentItem;
        var previous = fake.inventory.getStackInSlot(selected);
        double px = fake.posX, py = fake.posY, pz = fake.posZ;
        boolean creative = fake.capabilities.isCreativeMode;
        try {
            fake.setPosition(maid.posX, maid.posY, maid.posZ);
            fake.capabilities.isCreativeMode = false;
            fake.inventory.setInventorySlotContents(selected, one);
            boolean placed = one.tryPlaceItemIntoWorld(
                fake,
                server,
                point.x,
                point.y - 1,
                point.z,
                FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP,
                FaithfulLifeTuning.CAKE_ACTION_TRY_PLACE_ITEM_INTO_WORLD_ARG7_FACTOR,
                FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP,
                FaithfulLifeTuning.CAKE_ACTION_TRY_PLACE_ITEM_INTO_WORLD_ARG7_FACTOR);
            if (!placed || one.stackSize != 0
                || !world.blockExists(point.x, point.y, point.z)
                || world.getBlock(point.x, point.y, point.z) != Blocks.cake) return false;
            if (!current.getAsBoolean() || epoch != attempt
                || lease.get() != token
                || !onCabinet(point)
                || inv.getStackInSlot(slot) != original
                || !ItemStack.areItemStacksEqual(original, expected)) {
                // 仅撤本次原生初始蛋糕，不覆盖回调留下的其他方块或改变过的蛋糕
                if (world.getBlockMetadata(point.x, point.y, point.z) == 0)
                    world.setBlockToAir(point.x, point.y, point.z);
                return false;
            }
            inv.decrStackSize(slot, FaithfulLifeTuning.BED_EXIT_DEFAULT_UNIT_STEP);
            placements++;
            return true;
        } finally {
            fake.inventory.currentItem = selected;
            fake.inventory.setInventorySlotContents(selected, previous);
            fake.capabilities.isCreativeMode = creative;
            fake.setPosition(px, py, pz);
            committing = false;
        }
    }

    public void cancel() {
        epoch++;
    }

    public int bites() {
        return bites;
    }

    public int placements() {
        return placements;
    }
}
