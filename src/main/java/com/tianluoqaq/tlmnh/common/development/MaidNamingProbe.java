package com.tianluoqaq.tlmnh.common.development;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.network.MaidNameRequest;
import com.tianluoqaq.tlmnh.common.network.MaidNamingNetwork;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 真实服务端开窗、名称请求编解码、权限和原生同步数据夹具，不代替客户端输入测试 */
public final class MaidNamingProbe {

    private MaidNamingProbe() {}

    public static void run(WorldServer world) {
        try (var fixture = new Fixture(world)) {
            validRequests(fixture);
            staleRequests(fixture);
            changedConditions(fixture);
            namedTags(fixture);
            TLMNotHard.LOG.info(
                "TLMNH_NAMING_TEST_PASS routing=actual-openGui codec=roundtrip permissions=checked"
                    + " cost=one creative=free watcher=serialized nbt=roundtrip client=unverified");
        }
    }

    private static void validRequests(Fixture f) {
        var container = f.open(2);
        require(f.held().stackSize == 2, "opening costs no name tag");
        require(
            MaidNamingNetwork.apply(f.owner, roundtrip(MaidNameRequest.from(container, "酒狐 小满", true))),
            "Chinese name request accepted");
        require(
            f.maid.getCustomNameTag()
                .equals("酒狐 小满") && f.maid.getAlwaysRenderNameTag(),
            "name and visibility");
        require(
            f.held().stackSize == 1 && f.owner.openContainer == f.owner.inventoryContainer,
            "one tag and closed window");
        verifyMetadata(f.maid);
        var saved = new NBTTagCompound();
        f.maid.writeEntityToNBT(saved);
        var restored = new EntityMaid(f.world);
        restored.readEntityFromNBT(saved);
        require(
            restored.getCustomNameTag()
                .equals("酒狐 小满") && restored.getAlwaysRenderNameTag(),
            "name NBT roundtrip");
        f.owner.capabilities.isCreativeMode = true;
        container = f.open(1);
        require(MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "创造女仆", false)), "creative rename");
        require(f.held().stackSize == 1 && !f.maid.getAlwaysRenderNameTag(), "creative free and visibility reset");
        f.owner.capabilities.isCreativeMode = false;
        container = f.open(2);
        f.owner.closeScreen();
        require(
            f.held().stackSize == 2 && f.maid.getCustomNameTag()
                .equals("创造女仆"),
            "cancel preserves name and tags");
    }

    private static void staleRequests(Fixture f) {
        var first = f.open(3);
        var request = MaidNameRequest.from(first, "第一次", false);
        require(MaidNamingNetwork.apply(f.owner, request), "first submit");
        require(!MaidNamingNetwork.apply(f.owner, request) && f.held().stackSize == 2, "duplicate rejected");
        var next = f.open(2);
        next.windowId = first.windowId;
        require(
            !MaidNamingNetwork.apply(f.owner, request) && f.owner.openContainer == next && f.held().stackSize == 2,
            "old session rejected after window id reuse");
        var wrongEntity = new MaidNameRequest(
            next.windowId,
            next.getSession(),
            f.maid.getEntityId(),
            UUID.randomUUID(),
            "错对象",
            false);
        require(!MaidNamingNetwork.apply(f.owner, wrongEntity) && f.held().stackSize == 2, "entity UUID checked");
        var wrongWindow = new MaidNameRequest(
            next.windowId + 1,
            next.getSession(),
            f.maid.getEntityId(),
            f.maid.getUniqueID(),
            "错窗口",
            false);
        require(!MaidNamingNetwork.apply(f.owner, wrongWindow), "window checked");
        f.owner.closeScreen();
    }

    private static void changedConditions(Fixture f) {
        var container = f.open(2);
        var request = MaidNameRequest.from(container, "陌生人", false);
        f.stranger.openContainer = container;
        require(!MaidNamingNetwork.apply(f.stranger, request) && f.held().stackSize == 2, "owner rechecked");
        f.stranger.openContainer = f.stranger.inventoryContainer;
        f.owner.closeScreen();
        container = f.open(2);
        f.owner.setPosition(f.maid.posX + 20, f.maid.posY, f.maid.posZ);
        require(!MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "太远", false)), "distance rechecked");
        require(f.held().stackSize == 2, "distance denial preserves tags");
        f.owner.setPosition(f.maid.posX, f.maid.posY, f.maid.posZ);
        container = f.open(2);
        f.owner.inventory.setInventorySlotContents(0, new ItemStack(Items.name_tag, 2));
        require(
            !MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "换牌", false)),
            "same item identity rechecked");
        require(f.held().stackSize == 2, "replaced stack preserved");
        container = f.open(2);
        f.held()
            .setStackDisplayName("已经写名");
        require(
            !MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "改过NBT", false)),
            "held tag NBT rechecked");
        require(f.held().stackSize == 2, "modified tag preserved");
        container = f.open(2);
        f.owner.inventory.currentItem = 1;
        require(
            !MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "换手", false)),
            "selected slot rechecked");
        require(f.owner.inventory.getStackInSlot(0).stackSize == 2, "changed hand preserves original tag");
        container = f.open(2);
        f.owner.inventory.setItemStack(new ItemStack(Items.stick));
        require(
            !MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "鼠标持物", false)),
            "cursor item denied");
        require(f.held().stackSize == 2, "cursor denial preserves tags");
        f.owner.inventory.setItemStack(null);
        container = f.open(2);
        require(!MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "  ", false)), "blank name denied");
        require(f.held().stackSize == 2, "invalid name preserves tags");
        container = f.open(2);
        var spectator = GameType.getByID(3);
        require(spectator.getID() == 3, "native spectator available");
        f.owner.theItemInWorldManager.setGameType(spectator);
        require(!MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "旁观", false)), "spectator denied");
        require(f.owner.inventory.getStackInSlot(0).stackSize == 2, "spectator raw slot conserved");
        f.owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        container = f.open(2);
        // 暂时撤销世界实体注册但保持存活，单独检出身份校验，避免死亡条件掩盖它
        f.world.onEntityRemoved(f.maid);
        try {
            require(
                f.maid.isEntityAlive() && f.world.getEntityByID(f.maid.getEntityId()) != f.maid,
                "alive entity temporarily unregistered");
            require(
                !MaidNamingNetwork.apply(f.owner, MaidNameRequest.from(container, "已移除", false)),
                "world entity identity rechecked");
            require(f.held().stackSize == 2, "unregistered entity preserves tags");
        } finally {
            f.world.onEntityAdded(f.maid);
        }
    }

    private static void namedTags(Fixture f) {
        f.owner.inventory.currentItem = 0;
        var tag = new ItemStack(Items.name_tag, 2);
        tag.setStackDisplayName("原牌名称");
        f.owner.inventory.setInventorySlotContents(0, tag);
        require(
            f.owner.interactWith(f.maid) && f.maid.getCustomNameTag()
                .equals("原牌名称"),
            "written tag vanilla branch");
        require(
            f.held().stackSize == 1 && f.owner.openContainer == f.owner.inventoryContainer,
            "written tag consumes one without GUI");
        f.owner.capabilities.isCreativeMode = true;
        require(f.owner.interactWith(f.maid) && f.held().stackSize == 1, "creative written tag preserved");
        f.owner.capabilities.isCreativeMode = false;
        var denied = new ItemStack(Items.name_tag, 2);
        denied.setStackDisplayName("不应改名");
        f.stranger.inventory.setInventorySlotContents(0, denied);
        require(
            f.stranger.interactWith(f.maid) && f.maid.getCustomNameTag()
                .equals("原牌名称") && denied.stackSize == 2,
            "stranger written tag cannot fall through to vanilla naming");
        f.owner.setPosition(f.maid.posX + 20, f.maid.posY, f.maid.posZ);
        f.owner.inventory.setInventorySlotContents(0, denied.copy());
        require(
            f.owner.interactWith(f.maid) && f.maid.getCustomNameTag()
                .equals("原牌名称") && f.held().stackSize == 2,
            "distant written tag cannot fall through to vanilla naming");
        f.owner.setPosition(f.maid.posX, f.maid.posY, f.maid.posZ);
        try {
            int slot = (Integer) Class.forName("xonin.backhand.api.core.BackhandUtils")
                .getMethod("getOffhandSlot", net.minecraft.entity.player.EntityPlayer.class)
                .invoke(null, f.owner);
            f.owner.inventory.setInventorySlotContents(slot, denied.copy());
            f.owner.inventory.currentItem = slot;
            require(
                f.owner.interactWith(f.maid) && f.maid.getCustomNameTag()
                    .equals("原牌名称") && f.owner.inventory.getStackInSlot(slot).stackSize == 2,
                "offhand written tag cannot fall through to vanilla naming");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("native Backhand naming probe unavailable", error);
        } finally {
            f.owner.inventory.currentItem = 0;
        }
    }

    private static MaidNameRequest roundtrip(MaidNameRequest request) {
        var bytes = Unpooled.buffer();
        try {
            request.toBytes(bytes);
            var decoded = new MaidNameRequest();
            decoded.fromBytes(bytes);
            return decoded;
        } finally {
            bytes.release();
        }
    }

    private static void verifyMetadata(EntityMaid maid) {
        var bytes = Unpooled.buffer();
        try {
            var buffer = new PacketBuffer(bytes);
            new S1CPacketEntityMetadata(maid.getEntityId(), maid.getDataWatcher(), true).writePacketData(buffer);
            require(buffer.readInt() == maid.getEntityId(), "metadata entity id");
            boolean name = false, visible = false;
            for (DataWatcher.WatchableObject value : DataWatcher.readWatchedListFromPacketBuffer(buffer)) {
                if (value.getDataValueId() == 10) name = maid.getCustomNameTag()
                    .equals(value.getObject());
                if (value.getDataValueId() == 11) visible = Byte.valueOf((byte) 1)
                    .equals(value.getObject());
            }
            require(name && visible, "actual metadata codec carries name and visibility");
        } catch (IOException error) {
            throw new IllegalStateException("Maid naming metadata codec failed", error);
        } finally {
            bytes.release();
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("TLMNH naming probe failed: " + label);
    }

    private static final class Fixture implements AutoCloseable {

        private final WorldServer world;
        private final EntityPlayerMP owner, stranger;
        private final EntityMaid maid;
        private final Set<Entity> originalEntities = new HashSet<>();
        private final List<EmbeddedChannel> connections = new ArrayList<>();

        private Fixture(WorldServer world) {
            this.world = world;
            originalEntities.addAll(world.loadedEntityList);
            owner = player(world, "[TLMNH-Name]");
            stranger = player(world, "[TLMNH-Other]");
            var spawn = world.getSpawnPoint();
            int x = spawn.posX + 16, z = spawn.posZ + 16;
            world.getChunkFromBlockCoords(x, z);
            owner.setPosition(x + 0.5D, Math.max(64, world.getTopSolidOrLiquidBlock(x, z)), z + 0.5D);
            stranger.setPosition(owner.posX, owner.posY, owner.posZ);
            maid = new EntityMaid(world);
            maid.setPosition(owner.posX, owner.posY, owner.posZ);
            maid.getBrainCoordinator()
                .setSuspended(true);
            require(maid.bindTo(owner.getUniqueID()) && world.spawnEntityInWorld(maid), "fixture binding and spawn");
        }

        private ContainerMaidNaming open(int count) {
            owner.inventory.currentItem = 0;
            owner.inventory.setInventorySlotContents(0, new ItemStack(Items.name_tag, count));
            require(
                owner.interactWith(maid) && owner.openContainer instanceof ContainerMaidNaming,
                "actual name tag GUI routing");
            var container = (ContainerMaidNaming) owner.openContainer;
            require(container.canInteractWith(owner), "naming session available");
            return container;
        }

        private ItemStack held() {
            return owner.getCurrentEquippedItem();
        }

        @Override
        public void close() {
            Throwable failure = null;
            try {
                try {
                    owner.inventory.setItemStack(null);
                    if (owner.openContainer != owner.inventoryContainer) owner.closeScreen();
                } catch (RuntimeException | Error error) {
                    failure = error;
                }
                try {
                    world.removeEntity(maid);
                    for (Object entity : new ArrayList<>(world.loadedEntityList)) {
                        if (entity instanceof EntityItem item && !originalEntities.contains(item))
                            world.removeEntity(item);
                    }
                    for (int slot = 0; slot < owner.inventory.mainInventory.length; slot++)
                        owner.inventory.setInventorySlotContents(slot, null);
                } catch (RuntimeException | Error error) {
                    failure = remember(failure, error);
                }
            } finally {
                // 窗口或实体清理失败也不能跳过任一内存channel，保留首个异常
                for (var connection : connections) {
                    try {
                        IsolatedPlayerChannel.requireUnbound(connection);
                    } catch (RuntimeException | Error error) {
                        failure = remember(failure, error);
                    }
                    try {
                        connection.finish();
                    } catch (RuntimeException | Error error) {
                        failure = remember(failure, error);
                    }
                    try {
                        Object message;
                        while ((message = connection.readOutbound()) != null) ReferenceCountUtil.release(message);
                    } catch (RuntimeException | Error error) {
                        failure = remember(failure, error);
                    }
                    try {
                        Object message;
                        while ((message = connection.readInbound()) != null) ReferenceCountUtil.release(message);
                    } catch (RuntimeException | Error error) {
                        failure = remember(failure, error);
                    }
                }
                connections.clear();
            }
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        }

        private static Throwable remember(Throwable first, Throwable next) {
            if (first == null) return next;
            if (first != next) first.addSuppressed(next);
            return first;
        }

        private EntityPlayerMP player(WorldServer world, String name) {
            var player = new EntityPlayerMP(
                MinecraftServer.getServer(),
                world,
                new GameProfile(UUID.randomUUID(), name),
                new ItemInWorldManager(world));
            var manager = new NetworkManager(false);
            // openGui的FML PLAYER路由需要实际channel；没有dispatcher时明确不投递给客户端
            var connection = new EmbeddedChannel(manager);
            connections.add(connection);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, connection);
            new NetHandlerPlayServer(MinecraftServer.getServer(), manager, player);
            player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            return player;
        }
    }
}
