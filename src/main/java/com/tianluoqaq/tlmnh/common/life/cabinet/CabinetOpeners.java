package com.tianluoqaq.tlmnh.common.life.cabinet;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.util.AxisAlignedBB;

import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.life.BlockSnackCabinet;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;

/** 原ContainerOpenersCounter的首开/末关声音；旧IInventory回调无玩家，下一tick核实际窗口 */
public final class CabinetOpeners {

    private static final int RECHECK_TICKS = 5;
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
        count = 0;
        nextCheck = 0;
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
        nextCheck = now + RECHECK_TICKS;
        int actual = 0;
        var box = AxisAlignedBB.getBoundingBox(
            cabinet.xCoord - 5,
            cabinet.yCoord - 5,
            cabinet.zCoord - 5,
            cabinet.xCoord + 6,
            cabinet.yCoord + 6,
            cabinet.zCoord + 6);
        for (Object candidate : world.getEntitiesWithinAABB(EntityPlayer.class, box)) {
            var player = (EntityPlayer) candidate;
            if (player instanceof EntityPlayerMP mp && mp.theItemInWorldManager.getGameType()
                .getID() == 3) continue;
            if (player.isEntityAlive() && player.openContainer instanceof ContainerChest chest
                && chest.getLowerChestInventory() == cabinet
                && cabinet.isUseableByPlayer(player)) actual++;
        }
        boolean opened = count == 0 && actual > 0, closed = count > 0 && actual == 0;
        count = actual;
        if (!opened && !closed) return;
        int direction = BlockSnackCabinet
            .facingDirection(world.getBlockMetadata(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord));
        double x = cabinet.xCoord + .5 + (direction == 1 ? -.5 : direction == 3 ? .5 : 0);
        double z = cabinet.zCoord + .5 + (direction == 0 ? .5 : direction == 2 ? -.5 : 0);
        world.playSoundEffect(
            x,
            cabinet.yCoord + .5,
            z,
            CompatServices.worldContent()
                .barrelSound(opened),
            .5F,
            world.rand.nextFloat() * .1F + .8F);
    }
}
