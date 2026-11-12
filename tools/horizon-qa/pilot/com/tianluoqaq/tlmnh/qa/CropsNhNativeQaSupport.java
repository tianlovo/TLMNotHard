package com.tianluoqaq.tlmnh.qa;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.gtnewhorizons.horizonqa.structure.TemplateException;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.inventory.spi.BackpackContainerCompatProvider;
import com.tianluoqaq.tlmnh.common.work.breeding.CropsNhBreedingWorkController;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.CropStickCellState;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.SeedInspection;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.StickKind;

import cpw.mods.fml.common.registry.GameRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** CropsNH 世界育种原生 QA 夹具：负责区块强载、3x3 试验田搭建、环境恢复、母本加速与 MethodHandle 安全物品工厂 */
final class CropsNhNativeQaSupport {

    static final int[][] PARENT_OFFSETS = { { 0, 0 }, { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } };
    static final int[][] CROSS_OFFSETS = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };

    private static volatile QaHandles cachedHandles;

    final GameTestHelper h;
    final LifecycleConservationTests.Fixture f;
    final WorldServer world;
    final EmbeddedChannel channel;
    final int cx;
    final int subSoilY;
    final int soilY;
    final int cropY;
    final int cz;
    private final boolean registeredOnline;

    CropsNhNativeQaSupport(GameTestHelper h, boolean registerOwnerOnline) {
        this.h = h;
        this.f = new LifecycleConservationTests.Fixture(h);
        this.world = h.getWorld();
        this.channel = (EmbeddedChannel) f.owner.playerNetServerHandler.netManager.channel();
        this.cx = f.x + 10;
        this.subSoilY = f.y + 1;
        this.soilY = f.y + 2;
        this.cropY = f.y + 3;
        this.cz = f.z + 4;
        this.registeredOnline = registerOwnerOnline;

        GameTestChunkLoader chunks = new GameTestChunkLoader();
        h.afterTest(chunks::releaseAll);
        try {
            chunks.forceChunksStrict(world, f.x - 80, f.y, f.z - 80, f.x + 100, f.y + 10, f.z + 90);
        } catch (TemplateException failure) {
            throw new IllegalStateException("Breeding fixture chunks could not be held", failure);
        }

        long savedTime = world.getWorldTime();
        boolean savedRaining = world.getWorldInfo()
            .isRaining();
        int savedRainTime = world.getWorldInfo()
            .getRainTime();
        boolean savedThundering = world.getWorldInfo()
            .isThundering();
        int savedThunderTime = world.getWorldInfo()
            .getThunderTime();

        List<EntityPlayerMP> onlinePlayers = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        if (!world.playerEntities.contains(f.owner)) {
            world.playerEntities.add(f.owner);
        }
        drainChat();

        world.setWorldTime(2000L);
        world.getWorldInfo()
            .setRaining(false);
        world.getWorldInfo()
            .setThundering(false);

        h.afterTest(() -> {
            onlinePlayers.removeIf(p -> p == f.owner);
            world.playerEntities.removeIf(p -> p == f.owner);
            for (int bx = f.x + 2; bx <= f.x + 16; bx++) {
                for (int bz = f.z + 1; bz <= f.z + 7; bz++) {
                    for (int by = f.y + 6; by >= f.y + 1; by--) {
                        if (world.getTileEntity(bx, by, bz) != null) {
                            world.removeTileEntity(bx, by, bz);
                        }
                        if (!world.isAirBlock(bx, by, bz)) {
                            world.setBlock(bx, by, bz, Blocks.air, 0, 2);
                        }
                    }
                }
            }
            world.setWorldTime(savedTime);
            world.getWorldInfo()
                .setRaining(savedRaining);
            world.getWorldInfo()
                .setRainTime(savedRainTime);
            world.getWorldInfo()
                .setThundering(savedThundering);
            world.getWorldInfo()
                .setThunderTime(savedThunderTime);
        });

        buildRaisedWalkwayAndDirtPlot();
    }

    void registerOwnerOnlineNow() {
        h.assertTrue(registeredOnline, "Only synchronous chat fixtures may register online");
        List<EntityPlayerMP> onlinePlayers = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        h.assertTrue(!onlinePlayers.contains(f.owner), "Fixture owner already registered online");
        onlinePlayers.add(f.owner);
    }

    /** 供 0-tick 同步聊天测试在返回服务端主循环前立即移出在线玩家列表，避免跨 tick 被外部模组轮询 */
    void unregisterOwnerOnlineNow() {
        if (!registeredOnline) return;
        List<EntityPlayerMP> onlinePlayers = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        onlinePlayers.removeIf(p -> p == f.owner);
    }

    void buildRaisedWalkwayAndDirtPlot() {
        // 铺设与 soilY 齐平的石质步道与四角萤石照明（满足 CropFood 最低亮度 >= 9 要求）
        for (int bx = f.x + 3; bx <= cx + 3; bx++) {
            for (int bz = cz - 2; bz <= cz + 2; bz++) {
                world.setBlock(bx, subSoilY, bz, Blocks.stone, 0, 3);
                world.setBlock(bx, soilY, bz, Blocks.stone, 0, 3);
                world.setBlockToAir(bx, cropY, bz);
                world.setBlockToAir(bx, cropY + 1, bz);
                world.setBlockToAir(bx, cropY + 2, bz);
            }
        }
        world.setBlock(cx - 2, soilY, cz - 2, Blocks.glowstone, 0, 3);
        world.setBlock(cx + 2, soilY, cz - 2, Blocks.glowstone, 0, 3);
        world.setBlock(cx - 2, soilY, cz + 2, Blocks.glowstone, 0, 3);
        world.setBlock(cx + 2, soilY, cz + 2, Blocks.glowstone, 0, 3);
        resetThreeByThreeFoundation(Blocks.dirt, Blocks.dirt);
        // 真实水源覆盖九格耕地，避免原版随机 tick 把无水干田退回泥土
        world.setBlock(cx + 2, soilY, cz, Blocks.water, 0, 3);
        f.owner.setPosition(cx - 2.5, cropY, cz + 0.5);
    }

    void resetThreeByThreeFoundation(Block soil, Block subSoil) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (world.getTileEntity(x, cropY, z) != null) {
                    world.removeTileEntity(x, cropY, z);
                }
                world.setBlockToAir(x, cropY, z);
                world.setBlock(x, subSoilY, z, subSoil, 0, 3);
                world.setBlock(x, soilY, z, soil, 0, 3);
            }
        }
    }

    EntityMaid spawnBreedingMaid(String customName, double worldX, double worldY, double worldZ) {
        EntityMaid maid = new EntityMaid(world);
        f.owned.add(maid);
        h.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Failed to bind breeding maid to fixture owner");
        maid.setCustomNameTag(customName);
        maid.setFavorability(384);
        maid.setSchedule(MaidSchedule.ALL);
        maid.setTask(MaidTask.CROPSNH_BREEDING);
        maid.setPickup(false);
        h.spawnEntity(maid, worldX - f.x, worldY - f.y, worldZ - f.z);
        maid.getBrainCoordinator()
            .setSuspended(false);
        h.assertTrue(
            !maid.getBrainCoordinator()
                .isSuspended(),
            "Breeding QA maid must run natural unsuspended AI");
        return maid;
    }

    static void clearMaidRawInventory(EntityMaid maid) {
        MaidInventory inv = maid.getMaidInventory();
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            inv.setInventorySlotContents(i, null);
        }
        inv.markDirty();
    }

    static int countBlockItemsInMaid(EntityMaid maid, Block targetBlock) {
        MaidInventory inv = maid.getMaidInventory();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        int[] count = { 0 };
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            if (s.getItem() instanceof ItemBlock ib && ib.field_150939_a == targetBlock) {
                count[0] += s.stackSize;
            }
            if (backpacks.isSupportedBackpack(s)) {
                backpacks.forEachStoredStack(s, nested -> {
                    if (nested != null && nested.stackSize > 0
                        && nested.getItem() instanceof ItemBlock nib
                        && nib.field_150939_a == targetBlock) {
                        count[0] += nested.stackSize;
                    }
                });
            }
        }
        return count[0];
    }

    int countCropSticksInMaidAndPlot(EntityMaid maid) {
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        int[] count = { 0 };
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            ItemStack stack = maid.getMaidInventory()
                .getStackInSlot(slot);
            if (compat.isCropSticksItem(stack)) count[0] += stack.stackSize;
            else if (backpacks.isSupportedBackpack(stack)) {
                backpacks.forEachStoredStack(
                    stack,
                    nested -> { if (compat.isCropSticksItem(nested)) count[0] += nested.stackSize; });
            }
        }
        return count[0] + countCropSticksInPlot();
    }

    int countCropSticksInPlot() {
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                StickKind kind = compat.inspectCropStickCell(world, cx + dx, cropY, cz + dz).kind;
                if (kind == StickKind.CROSS_EMPTY) count += 2;
                else if (kind == StickKind.SINGLE_EMPTY || kind == StickKind.PLANTED || kind == StickKind.WEED) count++;
            }
        }
        return count;
    }

    static int countCropSeedInRawSlots(EntityMaid maid, String cropId) {
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        MaidInventory inv = maid.getMaidInventory();
        int count = 0;
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0
                || CompatServices.backpacks()
                    .isSupportedBackpack(s)) {
                continue;
            }
            SeedInspection insp = compat.inspectSeedOrAlternate(s);
            if (insp != null && cropId.equals(insp.cropId)) {
                count += s.stackSize;
            }
        }
        return count;
    }

    static int countCropSeedInBackpack(ItemStack bag, String cropId) {
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        int[] count = { 0 };
        CompatServices.backpacks()
            .forEachStoredStack(bag, nested -> {
                SeedInspection insp = compat.inspectSeedOrAlternate(nested);
                if (insp != null && cropId.equals(insp.cropId)) {
                    count[0] += nested.stackSize;
                }
            });
        return count[0];
    }

    static ItemStack findStackInMaidOrBackpacks(EntityMaid maid, Predicate<ItemStack> filter) {
        MaidInventory inv = maid.getMaidInventory();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        ItemStack[] hit = { null };
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            if (filter.test(s)) return s;
            if (backpacks.isSupportedBackpack(s)) {
                backpacks.forEachStoredStack(s, nested -> {
                    if (hit[0] == null && nested != null && nested.stackSize > 0 && filter.test(nested)) {
                        hit[0] = nested;
                    }
                });
                if (hit[0] != null) return hit[0];
            }
        }
        return null;
    }

    List<String> drainChat() {
        channel.runPendingTasks();
        List<String> out = new ArrayList<String>();
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            try {
                if (packet instanceof S02PacketChat chat) {
                    PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
                    try {
                        chat.writePacketData(buf);
                        out.add(buf.readStringFromBuffer(32767));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("Failed to decode outgoing chat packet", e);
                    } finally {
                        buf.release();
                    }
                }
            } finally {
                ReferenceCountUtil.release(packet);
            }
        }
        return out;
    }

    /**
     * 仅在 QA 测试中加速作物生长：
     * 第一阶段先把已种下的母本单架补满水肥并推进至成熟，保证母本先成熟；
     * 第二阶段再对双层十字架推进生长/杂交（空十字架逐 tick 推进并在杂交出新苗时立即停步，已出苗的十字架位推进至成熟等待女仆正常脑 tick 收种）
     */
    void accelerateParentsFirstThenCross(int parentTicks, int crossTicks) {
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        for (int[] off : PARENT_OFFSETS) {
            int x = cx + off[0];
            int z = cz + off[1];
            CropStickCellState st = compat.inspectCropStickCell(world, x, cropY, z);
            if (st.kind == StickKind.PLANTED) {
                feedWaterAndFertilizer(x, cropY, z);
                if (!st.mature) {
                    compat.tickGrowthAcceleration(world, x, cropY, z, parentTicks);
                }
                feedWaterAndFertilizer(x, cropY, z);
            }
        }

        int matureParents = 0;
        for (int[] off : PARENT_OFFSETS) {
            CropStickCellState st = compat.inspectCropStickCell(world, cx + off[0], cropY, cz + off[1]);
            if (st.kind == StickKind.PLANTED && st.mature) {
                matureParents++;
            }
        }
        if (crossTicks <= 0) {
            return;
        }

        for (int[] off : CROSS_OFFSETS) {
            int x = cx + off[0];
            int z = cz + off[1];
            CropStickCellState st = compat.inspectCropStickCell(world, x, cropY, z);
            if (st.kind == StickKind.CROSS_EMPTY && matureParents >= 2) {
                for (int i = 0; i < crossTicks; i++) {
                    compat.tickGrowthAcceleration(world, x, cropY, z, 1);
                    CropStickCellState after = compat.inspectCropStickCell(world, x, cropY, z);
                    if (after.kind != StickKind.CROSS_EMPTY) {
                        if (after.kind == StickKind.PLANTED) {
                            feedWaterAndFertilizer(x, cropY, z);
                            compat.tickGrowthAcceleration(world, x, cropY, z, parentTicks);
                        }
                        break;
                    }
                }
            } else if (st.kind == StickKind.PLANTED && !st.mature) {
                feedWaterAndFertilizer(x, cropY, z);
                compat.tickGrowthAcceleration(world, x, cropY, z, parentTicks);
            }
        }
    }

    Runnable progressProbe(String scenario, EntityMaid maid, IntSupplier phase) {
        long[] lastTick = { Long.MIN_VALUE };
        int[] lastPhase = { Integer.MIN_VALUE };
        String[] lastState = { "not sampled" };
        h.afterTest(() -> System.out.println("TLMNH_BREEDING_FINAL scenario=" + scenario + " " + lastState[0]));
        return () -> {
            long now = world.getTotalWorldTime();
            int currentPhase = phase.getAsInt();
            if (currentPhase == lastPhase[0] && now - lastTick[0] < 200L) return;
            lastTick[0] = now;
            lastPhase[0] = currentPhase;
            lastState[0] = "phase=" + currentPhase + " tick=" + now + " " + describeBreedingState(maid);
            System.out.println("TLMNH_BREEDING_PROGRESS scenario=" + scenario + " " + lastState[0]);
        };
    }

    private String describeBreedingState(EntityMaid maid) {
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        CropsNhBreedingWorkController.EvaluationResult eval = CropsNhBreedingWorkController
            .evaluatePlotAndMissing(maid, maid.getBreedingTaskConfig(), compat);
        StringBuilder text = new StringBuilder();
        text.append("task=")
            .append(maid.getTask())
            .append(" age=")
            .append(maid.ticksExisted)
            .append(" intent=")
            .append(
                maid.getBrainCoordinator()
                    .getOrdinaryIntent())
            .append(" workSchedule=")
            .append(
                maid.getBrainCoordinator()
                    .getWorkModeCoordinator()
                    .isInWorkSchedule(maid))
            .append(" suspended=")
            .append(
                maid.getBrainCoordinator()
                    .isSuspended())
            .append(" alive=")
            .append(!maid.isDead)
            .append(" sitting=")
            .append(maid.isSitting())
            .append(" pos=")
            .append(maid.posX)
            .append(',')
            .append(maid.posY)
            .append(',')
            .append(maid.posZ)
            .append(" worldTime=")
            .append(world.getWorldTime())
            .append(" plan=")
            .append(eval.plan == null ? "none" : eval.plan.status)
            .append(" parents=")
            .append(eval.plan == null ? "none" : eval.plan.parentCropIds)
            .append(" missing=[");
        for (com.tianluoqaq.tlmnh.common.work.notify.MissingConditionEntry entry : eval.missing) {
            text.append(entry.code)
                .append(',');
        }
        text.append("] cells=[");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                CropStickCellState cell = compat.inspectCropStickCell(world, cx + dx, cropY, cz + dz);
                text.append(dx)
                    .append('/')
                    .append(dz)
                    .append(':')
                    .append(cell.kind)
                    .append(':')
                    .append(cell.cropId)
                    .append(':')
                    .append(cell.growth)
                    .append('/')
                    .append(cell.gain)
                    .append('/')
                    .append(cell.resistance)
                    .append(":m=")
                    .append(cell.mature)
                    .append(":harvest=")
                    .append(cell.canHarvest)
                    .append(":req=")
                    .append(cell.growthRequirementsMet)
                    .append(":soil=")
                    .append(Block.getIdFromBlock(world.getBlock(cx + dx, soilY, cz + dz)))
                    .append(',');
            }
        }
        text.append("] raw=[");
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            ItemStack stack = maid.getMaidInventory()
                .getStackInSlot(slot);
            if (stack != null && stack.getItem() != null && stack.stackSize > 0) {
                text.append(slot)
                    .append(':')
                    .append(stack.getUnlocalizedName())
                    .append('x')
                    .append(stack.stackSize)
                    .append(',');
            }
        }
        return text.append(']')
            .toString();
    }

    void feedWaterAndFertilizer(int x, int y, int z) {
        try {
            QaHandles handles = handles();
            TileEntity te = world.getTileEntity(x, y, z);
            if (handles.stickTileCls.isInstance(te)) {
                handles.tileAddWater.invoke(te, 200, 200, 200, false);
                handles.tileAddFertilizer.invoke(te, 200, 200, 200, false);
            }
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to feed CropsNH QA water/fertilizer", e);
        }
    }

    void spawnWeedAt(int x, int y, int z) {
        try {
            QaHandles handles = handles();
            TileEntity te = world.getTileEntity(x, y, z);
            h.assertTrue(handles.stickTileCls.isInstance(te), "Expected ICropStickTile before spawning weed");
            handles.tileSpawnWeed.invoke(te);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to spawn QA weed on ICropStickTile", e);
        }
    }

    SeedInspection findMatchingSeedInPlotOrMaid(EntityMaid maid, String cropId, Predicate<SeedInspection> pred) {
        CropsNhBreedingCompatProvider compat = CompatServices.cropsNhBreeding();
        MaidInventory inv = maid.getMaidInventory();
        BackpackContainerCompatProvider backpacks = CompatServices.backpacks();
        SeedInspection[] nestedHit = { null };
        for (int i = 0; i <= MaidInventoryLayout.OFF_HAND; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            SeedInspection insp = compat.inspectSeedOrAlternate(s);
            if (insp != null && (cropId.isEmpty() || cropId.equals(insp.cropId)) && pred.test(insp)) {
                return insp;
            }
            if (backpacks.isSupportedBackpack(s)) {
                backpacks.forEachStoredStack(s, nested -> {
                    if (nestedHit[0] != null) return;
                    SeedInspection ni = compat.inspectSeedOrAlternate(nested);
                    if (ni != null && (cropId.isEmpty() || cropId.equals(ni.cropId)) && pred.test(ni)) {
                        nestedHit[0] = ni;
                    }
                });
                if (nestedHit[0] != null) {
                    return nestedHit[0];
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                CropStickCellState st = compat.inspectCropStickCell(world, cx + dx, cropY, cz + dz);
                if (st.kind == StickKind.PLANTED && (cropId.isEmpty() || cropId.equals(st.cropId))) {
                    SeedInspection asInsp = new SeedInspection(
                        st.cropId,
                        st.cropId,
                        st.growth,
                        st.gain,
                        st.resistance,
                        true,
                        false);
                    if (pred.test(asInsp)) {
                        return asInsp;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static ItemStack cropsNhItem(String enumName, int count) {
        try {
            QaHandles handles = handles();
            Object entry = Enum.valueOf((Class<Enum>) handles.itemListCls, enumName);
            return (ItemStack) handles.itemContainerGet.invoke(entry, (long) count, new Object[0]);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to create CropsNHItemList." + enumName, e);
        }
    }

    static String registeredCropId(String cropsNhCropsField) {
        try {
            QaHandles handles = handles();
            MethodHandle getter = MethodHandles.publicLookup()
                .findStaticGetter(handles.cropsNhCropsCls, cropsNhCropsField, handles.cropCardCls);
            Object card = getter.invoke();
            return (String) handles.cardGetId.invoke(card);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to resolve CropsNHCrops." + cropsNhCropsField, e);
        }
    }

    static ItemStack createSeed(String cropId, int gr, int ga, int re, boolean analyzed, int stackSize) {
        try {
            QaHandles handles = handles();
            Object reg = handles.cropRegistryInstance.invoke();
            Object card = handles.cropRegistryGetById.invoke(reg, cropId);
            if (card == null) {
                throw new IllegalArgumentException("Unknown CropsNH cropId: " + cropId);
            }
            Object stats = handles.seedStatsCtor.invoke((byte) gr, (byte) ga, (byte) re, analyzed);
            ItemStack seed = (ItemStack) handles.cardGetSeedItem.invoke(card, stats);
            seed.stackSize = stackSize;
            return seed;
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to create CropsNH seed for " + cropId, e);
        }
    }

    static ItemStack createAdventureBackpack() {
        try {
            Item item = GameRegistry.findItem("adventurebackpack", "adventureBackpack");
            if (item == null) {
                Class<?> modItemsCls = Class.forName("com.darkona.adventurebackpack.init.ModItems");
                Class<?> advItemCls = Class.forName("com.darkona.adventurebackpack.item.ItemAdventureBackpack");
                item = (Item) MethodHandles.publicLookup()
                    .findStaticGetter(modItemsCls, "adventureBackpack", advItemCls)
                    .invoke();
            }
            if (item == null) {
                throw new IllegalStateException("AdventureBackpack item is null");
            }
            return new ItemStack(item, 1, 0);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to create real AdventureBackpack ItemStack", e);
        }
    }

    static ItemStack createForesterBackpack(int damage) {
        try {
            Class<?> pluginCls = Class.forName("forestry.plugins.PluginStorage");
            Class<?> regCls = Class.forName("forestry.storage.items.ItemRegistryStorage");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Object storageItems = lookup.findStaticGetter(pluginCls, "items", regCls)
                .invoke();
            Item item = (Item) lookup.findGetter(regCls, "foresterBackpack", Item.class)
                .invoke(storageItems);
            if (item == null) {
                throw new IllegalStateException("Forestry foresterBackpack item is null");
            }
            return new ItemStack(item, 1, damage);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to create real Forestry foresterBackpack", e);
        }
    }

    static void preloadForestryBackpackNbt(ItemStack bag, ItemStack... stacks) {
        int savedDamage = bag.getItemDamage();
        bag.setItemDamage(0);
        try {
            Class<?> container = Class
                .forName("com.tianluoqaq.tlmnh.compat.forestry.common.inventory.ForestryBackpackContainer");
            MethodHandle insert = MethodHandles.publicLookup()
                .findStatic(
                    container,
                    "insertNbt",
                    MethodType.methodType(ItemStack.class, ItemStack.class, int.class, ItemStack.class, boolean.class));
            for (ItemStack st : stacks) {
                if (st != null && st.stackSize > 0) {
                    ItemStack rem = (ItemStack) insert.invoke(bag, 15, st, false);
                    if (rem != null && rem.stackSize > 0) {
                        throw new IllegalStateException("Unexpected leftover preloading Forestry backpack: " + rem);
                    }
                }
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("Failed to preload real Forestry backpack NBT", failure);
        } finally {
            bag.setItemDamage(savedDamage);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static ItemStack createGtTool(String toolEnumName, String materialName) {
        try {
            Class<?> tool01Cls = Class.forName("gregtech.common.items.MetaGeneratedTool01");
            Class<?> idCls = Class.forName("gregtech.common.items.IDMetaTool01");
            Class<?> matCls = Class.forName("gregtech.api.enums.Materials");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Object tool01 = lookup.findStaticGetter(tool01Cls, "INSTANCE", tool01Cls)
                .invoke();
            Object toolEnum = Enum.valueOf((Class<Enum>) idCls, toolEnumName);
            int toolId = (int) lookup.findGetter(idCls, "ID", int.class)
                .invoke(toolEnum);
            Object mat = lookup.findStaticGetter(matCls, materialName, matCls)
                .invoke();
            MethodHandle make = lookup.findVirtual(
                tool01Cls,
                "getToolWithStats",
                MethodType.methodType(ItemStack.class, int.class, int.class, matCls, matCls, long[].class));
            return (ItemStack) make.invoke(tool01, toolId, 1, mat, mat, (long[]) null);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to construct GT tool " + toolEnumName + "/" + materialName, e);
        }
    }

    static ItemStack createLowGtMiningToolRejectedBy(Block block) {
        for (String mat : new String[] { "Flint", "Copper", "Bronze", "Iron" }) {
            ItemStack tool = createGtTool("HARDHAMMER", mat);
            if (tool != null && tool.getItem() != null
                && !tool.getItem()
                    .canHarvestBlock(block, tool)) {
                return tool;
            }
        }
        throw new IllegalStateException("No low-tier GT mining tool found below harvest level of " + block);
    }

    static ItemStack createQualifiedGtMiningToolFor(Block block) {
        for (String mat : new String[] { "Cobalt", "TungstenSteel", "Iridium", "Osmium", "Neutronium" }) {
            ItemStack tool = createGtTool("HARDHAMMER", mat);
            if (tool != null && tool.getItem() != null
                && tool.getItem()
                    .canHarvestBlock(block, tool)
                && getGtToolMaxDamage(tool) > 5000L) {
                return tool;
            }
        }
        throw new IllegalStateException("No qualified GT mining tool found capable of harvesting " + block);
    }

    static long getGtToolDamage(ItemStack stack) {
        try {
            Class<?> mgtCls = Class.forName("gregtech.api.items.MetaGeneratedTool");
            MethodHandle mh = MethodHandles.publicLookup()
                .findStatic(mgtCls, "getToolDamage", MethodType.methodType(long.class, ItemStack.class));
            return (long) mh.invoke(stack);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to inspect MetaGeneratedTool.getToolDamage", e);
        }
    }

    static long getGtToolMaxDamage(ItemStack stack) {
        try {
            Class<?> mgtCls = Class.forName("gregtech.api.items.MetaGeneratedTool");
            MethodHandle mh = MethodHandles.publicLookup()
                .findStatic(mgtCls, "getToolMaxDamage", MethodType.methodType(long.class, ItemStack.class));
            return (long) mh.invoke(stack);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to inspect MetaGeneratedTool.getToolMaxDamage", e);
        }
    }

    /** 供专服无客户端类 ClassLoader 离线自检直接触发，确保 QA 句柄零触发客户端类解析 */
    static void verifyServerSafeHandles() {
        handles();
    }

    private static QaHandles handles() {
        QaHandles h = cachedHandles;
        if (h != null) return h;
        synchronized (CropsNhNativeQaSupport.class) {
            if (cachedHandles == null) {
                cachedHandles = new QaHandles();
            }
            return cachedHandles;
        }
    }

    private static final class QaHandles {

        final Class<?> itemListCls;
        final Class<?> cropsNhCropsCls;
        final Class<?> cropCardCls;
        final Class<?> stickTileCls;
        final MethodHandle itemContainerGet;
        final MethodHandle cardGetId;
        final MethodHandle cardGetSeedItem;
        final MethodHandle cropRegistryInstance;
        final MethodHandle cropRegistryGetById;
        final MethodHandle seedStatsCtor;
        final MethodHandle tileAddWater;
        final MethodHandle tileAddFertilizer;
        final MethodHandle tileSpawnWeed;

        QaHandles() {
            try {
                ClassLoader cl = CropsNhNativeQaSupport.class.getClassLoader();
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                this.itemListCls = Class.forName("com.gtnewhorizon.cropsnh.api.CropsNHItemList", false, cl);
                Class<?> itemContainerIfc = Class.forName("gregtech.api.interfaces.IItemContainer", false, cl);
                this.cropsNhCropsCls = Class.forName("com.gtnewhorizon.cropsnh.api.CropsNHCrops", false, cl);
                this.cropCardCls = Class.forName("com.gtnewhorizon.cropsnh.api.ICropCard", false, cl);
                this.stickTileCls = Class.forName("com.gtnewhorizon.cropsnh.api.ICropStickTile", false, cl);
                Class<?> regIfc = Class.forName("com.gtnewhorizon.cropsnh.api.ICropRegistry", false, cl);
                Class<?> regCls = Class.forName("com.gtnewhorizon.cropsnh.farming.registries.CropRegistry", false, cl);
                Class<?> seedStatsIfc = Class.forName("com.gtnewhorizon.cropsnh.api.ISeedStats", false, cl);
                Class<?> seedStatsCls = Class.forName("com.gtnewhorizon.cropsnh.farming.SeedStats", false, cl);

                this.itemContainerGet = lookup
                    .findVirtual(
                        itemContainerIfc,
                        "get",
                        MethodType.methodType(ItemStack.class, long.class, Object[].class))
                    .asFixedArity();
                this.cardGetId = lookup.findVirtual(cropCardCls, "getId", MethodType.methodType(String.class));
                this.cardGetSeedItem = lookup
                    .findVirtual(cropCardCls, "getSeedItem", MethodType.methodType(ItemStack.class, seedStatsIfc));
                this.cropRegistryInstance = lookup.findStaticGetter(regCls, "instance", regCls);
                this.cropRegistryGetById = lookup
                    .findVirtual(regIfc, "get", MethodType.methodType(cropCardCls, String.class));
                this.seedStatsCtor = lookup.findConstructor(
                    seedStatsCls,
                    MethodType.methodType(void.class, byte.class, byte.class, byte.class, boolean.class));
                this.tileAddWater = lookup.findVirtual(
                    stickTileCls,
                    "addWater",
                    MethodType.methodType(boolean.class, int.class, int.class, int.class, boolean.class));
                this.tileAddFertilizer = lookup.findVirtual(
                    stickTileCls,
                    "addFertilizer",
                    MethodType.methodType(boolean.class, int.class, int.class, int.class, boolean.class));
                this.tileSpawnWeed = lookup.findVirtual(stickTileCls, "spawnWeed", MethodType.methodType(void.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to initialize CropsNH QA MethodHandles", e);
            }
        }
    }
}
