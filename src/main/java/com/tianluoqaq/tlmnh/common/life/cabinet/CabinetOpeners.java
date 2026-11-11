package com.tianluoqaq.tlmnh.common.life.cabinet;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.util.AxisAlignedBB;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;

/** 原ContainerOpenersCounter的首开/末关声音；旧IInventory回调无玩家，下一tick核实际窗口 */
public final class CabinetOpeners {

    private final TileEntitySnackCabinet cabinet;
    private int count;
    private long nextCheck;
    private boolean requested;

    public CabinetOpeners(TileEntitySnackCabinet cabinet) {
        this.cabinet = cabinet;
    }

    public void requestCheck() {
        requested = true;
    }

    public void reset() {
        count = SnackCabinetLayout.BLOCK_SNACK_GET_COMPARATOR_INPUT_OVERRIDE_ZERO_BASE;
        nextCheck = SnackCabinetLayout.BLOCK_SNACK_GET_COMPARATOR_INPUT_OVERRIDE_ZERO_BASE;
        requested = false;
    }

    public void tick() {
        var world = cabinet.getWorldObj();
        if (world.isRemote || cabinet.isInvalid()
            || world.getTileEntity(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord) != cabinet
            || !(world.getBlock(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord) instanceof BlockSnackCabinet)) return;
        long now = world.getTotalWorldTime();
        if (!requested && (count == 0 || now < nextCheck)) return;
        requested = false;
        nextCheck = now + SnackCabinetLayout.RECHECK_TICKS;
        int actual = 0;
        var box = AxisAlignedBB.getBoundingBox(
            cabinet.xCoord - SnackCabinetLayout.CABINET_OPENERS_TICK_PARAM,
            cabinet.yCoord - SnackCabinetLayout.CABINET_OPENERS_TICK_PARAM,
            cabinet.zCoord - SnackCabinetLayout.CABINET_OPENERS_TICK_PARAM,
            cabinet.xCoord + SnackCabinetLayout.CABINET_OPENERS_TICK_PARAM_2,
            cabinet.yCoord + SnackCabinetLayout.CABINET_OPENERS_TICK_PARAM_2,
            cabinet.zCoord + SnackCabinetLayout.CABINET_OPENERS_TICK_PARAM_2);
        for (Object candidate : world.getEntitiesWithinAABB(EntityPlayer.class, box)) {
            var player = (EntityPlayer) candidate;
            if (player instanceof EntityPlayerMP mp && mp.theItemInWorldManager.getGameType()
                .getID() == SnackCabinetLayout.LOW_TWO_BITS_MASK) continue;
            if (player.isEntityAlive() && player.openContainer instanceof ContainerChest chest
                && chest.getLowerChestInventory() == cabinet
                && cabinet.isUseableByPlayer(player)) actual++;
        }
        boolean opened = count == 0 && actual > 0, closed = count > 0 && actual == 0;
        count = actual;
        if (!opened && !closed) return;
        int direction = BlockSnackCabinet
            .facingDirection(world.getBlockMetadata(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord));
        double x = cabinet.xCoord + SnackCabinetLayout.BLOCK_SNACK_FACING_2
            + (direction == SnackCabinetLayout.TARGET_SIDE_THRESHOLD ? SnackCabinetLayout.CABINET_OPENERS_TICK_FACTOR
                : direction == SnackCabinetLayout.LOW_TWO_BITS_MASK ? SnackCabinetLayout.BLOCK_SNACK_FACING_2
                    : SnackCabinetLayout.BLOCK_SNACK_GET_COMPARATOR_INPUT_OVERRIDE_ZERO_BASE);
        double z = cabinet.zCoord + SnackCabinetLayout.BLOCK_SNACK_FACING_2
            + (direction == 0 ? SnackCabinetLayout.BLOCK_SNACK_FACING_2
                : direction == SnackCabinetLayout.BLOCK_SNACK_FACING_DIRECTION_PARAM
                    ? SnackCabinetLayout.CABINET_OPENERS_TICK_FACTOR
                    : SnackCabinetLayout.BLOCK_SNACK_GET_COMPARATOR_INPUT_OVERRIDE_ZERO_BASE);
        world.playSoundEffect(
            x,
            cabinet.yCoord + SnackCabinetLayout.BLOCK_SNACK_FACING_2,
            z,
            CompatServices.worldContent()
                .barrelSound(opened),
            SnackCabinetLayout.CABINET_OPENERS_TICK_FACTOR_2,
            world.rand.nextFloat() * SnackCabinetLayout.CABINET_OPENERS_TICK_FACTOR_3
                + SnackCabinetLayout.CABINET_OPENERS_TICK_FACTOR_4);
    }
}
