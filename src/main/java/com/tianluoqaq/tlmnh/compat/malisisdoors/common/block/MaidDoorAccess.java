package com.tianluoqaq.tlmnh.compat.malisisdoors.common.block;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

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
            return state.equals("OPENED") || state.equals("OPENING");
        }
        return block instanceof BlockDoor door ? NativeDoorState.isOpenOrOpening(door, world, x, y, z)
            : block instanceof BlockFenceGate && (world.getBlockMetadata(x, y, z) & 4) != 0;
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
                world.setBlockMetadataWithNotify(x, y, z, value ? meta | 4 : meta & ~4, 2);
                world.playAuxSFXAtEntity(null, 1003, x, y, z, 0);
            }
        }
        return open(block, world, x, y, z) == value;
    }

    private static Object invoke(Method method, Object value) {
        try {
            return method.invoke(value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Installed animated door API failed", failure);
        }
    }

    private static Animated animated() {
        try {
            var tile = Class.forName(
                "net.malisis.doors.door.tileentity.DoorTileEntity",
                false,
                MaidDoorAccess.class.getClassLoader());
            return new Animated(tile.getMethod("getState"), tile.getMethod("openOrCloseDoor"));
        } catch (ClassNotFoundException absent) {
            if (cpw.mods.fml.common.Loader.isModLoaded("malisisdoors"))
                throw new IllegalStateException("Installed animated door class missing", absent);
            return null;
        } catch (NoSuchMethodException incompatible) {
            throw new IllegalStateException("Installed animated door API differs", incompatible);
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
