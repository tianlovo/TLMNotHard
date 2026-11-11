package com.tianluoqaq.tlmnh.compat.malisisdoors.common.block;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;

/** 目标平台门API适配，保留Malisis动画和锁定规则，不直接改其TileEntity状态 */
public final class MaidDoorAccess {

    private static final Animated ANIMATED = IntegrationGuard
        .call(ExternalFeature.ANIMATED_DOORS, MaidDoorAccess::animated, () -> null);

    private MaidDoorAccess() {}

    public static boolean open(Block block, World world, int x, int y, int z) {
        return IntegrationGuard
            .call(ExternalFeature.ANIMATED_DOORS, () -> openSafe(block, world, x, y, z), () -> false);
    }

    private static boolean openSafe(Block block, World world, int x, int y, int z) {
        var tile = world.getTileEntity(x, y, z);
        if (ANIMATED != null && ANIMATED.state.getDeclaringClass()
            .isInstance(tile)) {
            String state = ((Enum<?>) invoke(ANIMATED.state, tile)).name();
            return state.equals(MalisisDoorsBlockIds.KEY_OPENED) || state.equals(MalisisDoorsBlockIds.KEY_OPENING);
        }
        return block instanceof BlockDoor door ? NativeDoorState.isOpenOrOpening(door, world, x, y, z)
            : block instanceof BlockFenceGate
                && (world.getBlockMetadata(x, y, z) & MalisisDoorsBlockTuning.DOOR_BIT_MASK_4) != 0;
    }

    public static boolean setOpen(Block block, World world, int x, int y, int z, boolean value) {
        return IntegrationGuard
            .call(ExternalFeature.ANIMATED_DOORS, () -> setOpenSafe(block, world, x, y, z, value), () -> false);
    }

    private static boolean setOpenSafe(Block block, World world, int x, int y, int z, boolean value) {
        boolean current = open(block, world, x, y, z);
        if (!IntegrationGuard.available(ExternalFeature.ANIMATED_DOORS)) return false;
        if (current == value) return true;
        if (block instanceof BlockDoor door) door.func_150014_a(world, x, y, z, value);
        else if (block instanceof BlockFenceGate) {
            var tile = world.getTileEntity(x, y, z);
            if (ANIMATED != null && ANIMATED.state.getDeclaringClass()
                .isInstance(tile)) {
                invoke(ANIMATED.toggle, tile);
            } else {
                int meta = world.getBlockMetadata(x, y, z);
                world.setBlockMetadataWithNotify(
                    x,
                    y,
                    z,
                    value ? meta | MalisisDoorsBlockTuning.DOOR_BIT_MASK_4
                        : meta & ~MalisisDoorsBlockTuning.DOOR_BIT_MASK_4,
                    MalisisDoorsBlockTuning.DOOR_SET_BLOCK_METADATA_WITH_NOTIFY_FIFTH_PARAM);
                world.playAuxSFXAtEntity(
                    null,
                    MalisisDoorsBlockTuning.DOOR_PLAY_AUX_SFX_AT_ENTITY_SECOND_PARAM,
                    x,
                    y,
                    z,
                    MalisisDoorsBlockTuning.PLAY_AUX_SFX_AT_ENTITY_INITIAL_VALUE);
            }
        }
        return open(block, world, x, y, z) == value;
    }

    private static Object invoke(Method method, Object value) {
        try {
            return method.invoke(value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                MalisisDoorsBlockDiagnostics.MSG_INSTALLED_ANIMATED_DOOR_API_FAILED,
                failure);
        }
    }

    private static Animated animated() {
        try {
            var tile = Class.forName(
                MalisisDoorsBlockIds.TARGET_CLASS_DOOR_TILE_ENTITY,
                false,
                MaidDoorAccess.class.getClassLoader());
            return new Animated(
                tile.getMethod(MalisisDoorsBlockIds.ID_GETSTATE),
                tile.getMethod(MalisisDoorsBlockIds.ID_OPENORCLOSEDOOR));
        } catch (ClassNotFoundException absent) {
            if (Loader.isModLoaded(MalisisDoorsBlockIds.ID_MALISISDOORS)) throw new IllegalStateException(
                MalisisDoorsBlockDiagnostics.MSG_INSTALLED_ANIMATED_DOOR_CLASS_MISSING,
                absent);
            return null;
        } catch (NoSuchMethodException incompatible) {
            throw new IllegalStateException(
                MalisisDoorsBlockDiagnostics.MSG_INSTALLED_ANIMATED_DOOR_API_DIFFERS,
                incompatible);
        }
    }

    private static final class Animated {

        final Method state, toggle;

        Animated(Method state, Method toggle) {
            this.state = state;
            this.toggle = toggle;
        }
    }
}
