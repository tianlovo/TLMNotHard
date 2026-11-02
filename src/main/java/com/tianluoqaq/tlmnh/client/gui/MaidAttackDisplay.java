package com.tianluoqaq.tlmnh.client.gui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.tianluoqaq.tlmnh.common.network.MaidAttackSnapshot;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 任务编辑副本在客户端tick交付，换窗口/世界/连接后拒绝旧消息 */
@SideOnly(Side.CLIENT)
public final class MaidAttackDisplay {

    public static final MaidAttackDisplay INSTANCE = new MaidAttackDisplay();
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(16);
    private final AtomicInteger generation = new AtomicInteger();

    private MaidAttackDisplay() {}

    private static final class Pending {

        final MaidAttackSnapshot message;
        final int generation;

        Pending(MaidAttackSnapshot message, int generation) {
            this.message = message;
            this.generation = generation;
        }
    }

    public void enqueue(MaidAttackSnapshot message) {
        var pending = new Pending(message, generation.get());
        if (!queue.offer(pending)) {
            queue.poll();
            queue.offer(pending);
        }
    }

    private void reset() {
        generation.incrementAndGet();
        queue.clear();
    }

    @SubscribeEvent
    public void connect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        reset();
    }

    @SubscribeEvent
    public void disconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        reset();
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world.isRemote) reset();
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getMinecraft();
        for (int index = 0; index < 16; index++) {
            var pending = queue.poll();
            if (pending == null) break;
            if (pending.generation != generation.get() || mc.theWorld == null
                || mc.thePlayer == null
                || !(mc.thePlayer.openContainer instanceof TaskConfigContainer menu)) continue;
            var maid = menu.getMaid();
            if (maid.worldObj != mc.theWorld || mc.theWorld.getEntityByID(maid.getEntityId()) != maid
                || !menu.canInteractWith(mc.thePlayer)) continue;
            menu.acceptSnapshot(pending.message.payload());
        }
    }
}
