package com.tianluoqaq.tlmnh.compat.gregtech.common.maintenance;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.common.work.maintenance.spi.MaintenanceHatchCompatProvider;
import com.tianluoqaq.tlmnh.compat.gregtech.GregTechCompat;

import cpw.mods.fml.relauncher.Side;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.SoundResource;
import gregtech.api.items.MetaGeneratedTool;
import gregtech.api.util.GTUtility;

/**
 * GT5U 普通维护仓（MetaTileID == 90）检修适配实现：
 * 严格排除自动/创造/无菌/无人机等派生维护仓，零旋转、零耗材、零扳手耐久与电量损耗
 */
public final class GregTechMaintenanceHatchCompat implements MaintenanceHatchCompatProvider {

    public static final GregTechMaintenanceHatchCompat INSTANCE = new GregTechMaintenanceHatchCompat();

    private static volatile Api cachedApi;
    private static volatile boolean resolved;

    private GregTechMaintenanceHatchCompat() {}

    @Override
    public boolean isAvailable() {
        return OptionalApi.isModLoaded(GregTechCompat.MOD_ID)
            && IntegrationGuard.available(Side.SERVER, GregTechMaintenanceIds.GUARD)
            && api() != null;
    }

    @Override
    public ItemStack taskIcon() {
        if (!OptionalApi.isModLoaded(GregTechCompat.MOD_ID)
            || !IntegrationGuard.available(Side.CLIENT, GregTechMaintenanceIds.GUARD)) {
            return null;
        }
        try {
            return ItemList.Hatch_Maintenance.get(GregTechMaintenanceTuning.ICON_STACK_AMOUNT);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.CLIENT, GregTechMaintenanceIds.GUARD, failure);
            return null;
        }
    }

    @Override
    public boolean isValidMaintenanceWrench(ItemStack offhandStack) {
        if (!isAvailable() || offhandStack == null
            || offhandStack.stackSize <= GregTechMaintenanceTuning.ZERO_DEFICIT) {
            return false;
        }
        try {
            if (!(offhandStack.getItem() instanceof MetaGeneratedTool)) {
                return false;
            }
            return GTUtility.isStackInList(offhandStack, GregTechAPI.sWrenchList);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechMaintenanceIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean isOrdinaryMaintenanceHatch(World world, int x, int y, int z) {
        if (!isAvailable() || world == null) return false;
        try {
            IChunkProvider provider = world.getChunkProvider();
            int cx = x >> GregTechMaintenanceTuning.BLOCK_TO_CHUNK_SHIFT;
            int cz = z >> GregTechMaintenanceTuning.BLOCK_TO_CHUNK_SHIFT;
            if (provider == null || !provider.chunkExists(cx, cz)) return false;
            return asOrdinaryHatch(api(), world.getTileEntity(x, y, z)) != null;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechMaintenanceIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public List<MaintenanceTarget> findNeedingMaintenanceHatches(World world, List<int[]> boundChunks) {
        if (!isAvailable() || world == null || world.isRemote || boundChunks == null || boundChunks.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Api a = api();
            IChunkProvider provider = world.getChunkProvider();
            if (provider == null) return Collections.emptyList();
            List<MaintenanceTarget> result = new ArrayList<>();
            for (int[] coords : boundChunks) {
                if (coords == null || coords.length < GregTechMaintenanceTuning.CHUNK_COORD_PAIR_MIN_LEN) continue;
                int cx = coords[GregTechMaintenanceTuning.CHUNK_X_INDEX];
                int cz = coords[GregTechMaintenanceTuning.CHUNK_Z_INDEX];
                if (!provider.chunkExists(cx, cz)) continue;
                Chunk chunk = provider.provideChunk(cx, cz);
                if (chunk == null || chunk.chunkTileEntityMap == null) continue;
                for (Object rawTe : new ArrayList<Object>(chunk.chunkTileEntityMap.values())) {
                    if (!(rawTe instanceof TileEntity te)) continue;
                    Object hatch = asOrdinaryHatch(a, te);
                    if (hatch == null) continue;
                    List<Object> controllers = findNeedingControllers(a, provider, te.xCoord, te.zCoord, hatch);
                    if (controllers.isEmpty()) continue;
                    int deficit = GregTechMaintenanceTuning.ZERO_DEFICIT;
                    for (Object ctrl : controllers) {
                        int ideal = (Integer) a.getIdealStatus.invoke(ctrl);
                        int repair = (Integer) a.getRepairStatus.invoke(ctrl);
                        deficit += Math.max(GregTechMaintenanceTuning.ZERO_DEFICIT, ideal - repair);
                    }
                    if (deficit > GregTechMaintenanceTuning.ZERO_DEFICIT) {
                        result.add(new MaintenanceTarget(te.xCoord, te.yCoord, te.zCoord, deficit));
                    }
                }
            }
            return result;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechMaintenanceIds.GUARD, failure);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean performZeroSideEffectMaintenance(World world, int x, int y, int z) {
        if (!isAvailable() || world == null || world.isRemote) return false;
        try {
            Api a = api();
            IChunkProvider provider = world.getChunkProvider();
            int cx = x >> GregTechMaintenanceTuning.BLOCK_TO_CHUNK_SHIFT;
            int cz = z >> GregTechMaintenanceTuning.BLOCK_TO_CHUNK_SHIFT;
            if (provider == null || !provider.chunkExists(cx, cz)) return false;
            TileEntity te = world.getTileEntity(x, y, z);
            Object hatch = asOrdinaryHatch(a, te);
            if (hatch == null) return false;
            List<Object> controllers = findNeedingControllers(a, provider, x, z, hatch);
            if (controllers.isEmpty()) return false;
            a.setMaintenanceSound.invoke(
                hatch,
                SoundResource.GTCEU_OP_WRENCH,
                GregTechMaintenanceTuning.WRENCH_SOUND_STRENGTH,
                GregTechMaintenanceTuning.WRENCH_SOUND_MODULATION);
            for (Object ctrl : controllers) {
                a.setAllHatchToolFlags(hatch, true);
                a.checkMaintenance.invoke(ctrl);
            }
            a.setAllHatchToolFlags(hatch, false);
            return true;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechMaintenanceIds.GUARD, failure);
            return false;
        }
    }

    private static Object asOrdinaryHatch(Api a, TileEntity te) throws ReflectiveOperationException {
        if (a == null || te == null || te.isInvalid() || !a.gregTechTileCls.isInstance(te)) return null;
        if ((Boolean) a.isDead.invoke(te)) return null;
        int metaId = (Integer) a.getMetaTileId.invoke(te);
        if (metaId != GregTechMaintenanceTuning.ORDINARY_MAINTENANCE_HATCH_META_ID) return null;
        Object mte = a.getMetaTileEntity.invoke(te);
        if (mte == null || mte.getClass() != a.hatchCls) return null;
        return a.autoField.getBoolean(mte) ? null : mte;
    }

    private static List<Object> findNeedingControllers(Api a, IChunkProvider provider, int hx, int hz, Object hatch)
        throws ReflectiveOperationException {
        int centerCx = hx >> GregTechMaintenanceTuning.BLOCK_TO_CHUNK_SHIFT;
        int centerCz = hz >> GregTechMaintenanceTuning.BLOCK_TO_CHUNK_SHIFT;
        int radius = GregTechMaintenanceTuning.CONTROLLER_SCAN_CHUNK_RADIUS;
        LinkedHashSet<Object> found = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerCx + dx;
                int cz = centerCz + dz;
                if (!provider.chunkExists(cx, cz)) continue;
                Chunk chunk = provider.provideChunk(cx, cz);
                if (chunk == null || chunk.chunkTileEntityMap == null) continue;
                for (Object rawTe : new ArrayList<Object>(chunk.chunkTileEntityMap.values())) {
                    if (!(rawTe instanceof TileEntity te) || te.isInvalid() || !a.gregTechTileCls.isInstance(te)) {
                        continue;
                    }
                    if ((Boolean) a.isDead.invoke(te)) continue;
                    Object mte = a.getMetaTileEntity.invoke(te);
                    if (mte == null || !a.multiBlockCls.isInstance(mte)) continue;
                    Object base = a.getBaseMetaTileEntity.invoke(mte);
                    if (base == null || (Boolean) a.isDead.invoke(base)) continue;
                    if (!a.machineField.getBoolean(mte) || !(Boolean) a.shouldCheckMaintenance.invoke(mte)) continue;
                    int repair = (Integer) a.getRepairStatus.invoke(mte);
                    int ideal = (Integer) a.getIdealStatus.invoke(mte);
                    if (repair >= ideal) continue;
                    Object hatchesObj = a.maintenanceHatchesField.get(mte);
                    if (hatchesObj instanceof Collection<?>hatches && hatches.contains(hatch)) {
                        found.add(mte);
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static Api api() {
        if (resolved) return cachedApi;
        synchronized (GregTechMaintenanceHatchCompat.class) {
            if (resolved) return cachedApi;
            try {
                cachedApi = Api.bind();
            } catch (Exception | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, GregTechMaintenanceIds.GUARD, failure);
                cachedApi = null;
            }
            resolved = true;
            return cachedApi;
        }
    }

    private static final class Api {

        final Class<?> gregTechTileCls;
        final Class<?> hatchCls;
        final Class<?> multiBlockCls;
        final Method isDead;
        final Method getMetaTileId;
        final Method getMetaTileEntity;
        final Method getBaseMetaTileEntity;
        final Field autoField;
        final Field wrenchField;
        final Field screwdriverField;
        final Field softMalletField;
        final Field hardHammerField;
        final Field solderingField;
        final Field crowbarField;
        final Field machineField;
        final Field maintenanceHatchesField;
        final Method setMaintenanceSound;
        final Method shouldCheckMaintenance;
        final Method getIdealStatus;
        final Method getRepairStatus;
        final Method checkMaintenance;

        private Api(Class<?> gregTechTileCls, Class<?> hatchCls, Class<?> multiBlockCls, Method isDead,
            Method getMetaTileId, Method getMetaTileEntity, Method getBaseMetaTileEntity, Field autoField,
            Field wrenchField, Field screwdriverField, Field softMalletField, Field hardHammerField,
            Field solderingField, Field crowbarField, Field machineField, Field maintenanceHatchesField,
            Method setMaintenanceSound, Method shouldCheckMaintenance, Method getIdealStatus, Method getRepairStatus,
            Method checkMaintenance) {
            this.gregTechTileCls = gregTechTileCls;
            this.hatchCls = hatchCls;
            this.multiBlockCls = multiBlockCls;
            this.isDead = isDead;
            this.getMetaTileId = getMetaTileId;
            this.getMetaTileEntity = getMetaTileEntity;
            this.getBaseMetaTileEntity = getBaseMetaTileEntity;
            this.autoField = autoField;
            this.wrenchField = wrenchField;
            this.screwdriverField = screwdriverField;
            this.softMalletField = softMalletField;
            this.hardHammerField = hardHammerField;
            this.solderingField = solderingField;
            this.crowbarField = crowbarField;
            this.machineField = machineField;
            this.maintenanceHatchesField = maintenanceHatchesField;
            this.setMaintenanceSound = setMaintenanceSound;
            this.shouldCheckMaintenance = shouldCheckMaintenance;
            this.getIdealStatus = getIdealStatus;
            this.getRepairStatus = getRepairStatus;
            this.checkMaintenance = checkMaintenance;
        }

        void setAllHatchToolFlags(Object hatch, boolean value) throws ReflectiveOperationException {
            wrenchField.setBoolean(hatch, value);
            screwdriverField.setBoolean(hatch, value);
            softMalletField.setBoolean(hatch, value);
            hardHammerField.setBoolean(hatch, value);
            solderingField.setBoolean(hatch, value);
            crowbarField.setBoolean(hatch, value);
        }

        static Api bind() throws ReflectiveOperationException {
            Class<?> gregTechTileCls = Class.forName(GregTechMaintenanceReflectionSpec.CLS_GREGTECH_TILE);
            Class<?> metaTileCls = Class.forName(GregTechMaintenanceReflectionSpec.CLS_META_TILE);
            Class<?> hatchCls = Class.forName(GregTechMaintenanceReflectionSpec.CLS_HATCH_MAINTENANCE);
            Class<?> multiBlockCls = Class.forName(GregTechMaintenanceReflectionSpec.CLS_MULTI_BLOCK_BASE);
            return new Api(
                gregTechTileCls,
                hatchCls,
                multiBlockCls,
                gregTechTileCls.getMethod(GregTechMaintenanceReflectionSpec.MTH_IS_DEAD),
                gregTechTileCls.getMethod(GregTechMaintenanceReflectionSpec.MTH_GET_META_TILE_ID),
                gregTechTileCls.getMethod(GregTechMaintenanceReflectionSpec.MTH_GET_META_TILE_ENTITY),
                metaTileCls.getMethod(GregTechMaintenanceReflectionSpec.MTH_GET_BASE_META_TILE_ENTITY),
                pubField(hatchCls, GregTechMaintenanceReflectionSpec.FLD_AUTO),
                pubField(hatchCls, GregTechMaintenanceReflectionSpec.FLD_WRENCH),
                pubField(hatchCls, GregTechMaintenanceReflectionSpec.FLD_SCREWDRIVER),
                pubField(hatchCls, GregTechMaintenanceReflectionSpec.FLD_SOFT_MALLET),
                pubField(hatchCls, GregTechMaintenanceReflectionSpec.FLD_HARD_HAMMER),
                pubField(hatchCls, GregTechMaintenanceReflectionSpec.FLD_SOLDERING_TOOL),
                pubField(hatchCls, GregTechMaintenanceReflectionSpec.FLD_CROWBAR),
                pubField(multiBlockCls, GregTechMaintenanceReflectionSpec.FLD_MACHINE),
                pubField(multiBlockCls, GregTechMaintenanceReflectionSpec.FLD_MAINTENANCE_HATCHES),
                hatchCls.getMethod(
                    GregTechMaintenanceReflectionSpec.MTH_SET_MAINTENANCE_SOUND,
                    SoundResource.class,
                    float.class,
                    float.class),
                multiBlockCls.getMethod(GregTechMaintenanceReflectionSpec.MTH_SHOULD_CHECK_MAINTENANCE),
                multiBlockCls.getMethod(GregTechMaintenanceReflectionSpec.MTH_GET_IDEAL_STATUS),
                multiBlockCls.getMethod(GregTechMaintenanceReflectionSpec.MTH_GET_REPAIR_STATUS),
                multiBlockCls.getMethod(GregTechMaintenanceReflectionSpec.MTH_CHECK_MAINTENANCE));
        }

        private static Field pubField(Class<?> cls, String name) throws ReflectiveOperationException {
            Field f = cls.getField(name);
            f.setAccessible(true);
            return f;
        }
    }
}
