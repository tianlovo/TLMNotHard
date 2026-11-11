package com.tianluoqaq.tlmnh.compat.malisisdoors.common.block;

import java.lang.reflect.Method;

import net.minecraft.block.BlockDoor;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;

/** native 原版木门的 Malisis 动画状态；不替换其开门入口、动画或权限 */
public final class NativeDoorState {

    private static final Method ANIMATED_STATE = IntegrationGuard
        .call(ExternalFeature.ANIMATED_DOORS, NativeDoorState::resolve, () -> null);

    private NativeDoorState() {}

    public static boolean isOpenOrOpening(BlockDoor door, World world, int x, int y, int z) {
        return IntegrationGuard.call(ExternalFeature.ANIMATED_DOORS, () -> read(door, world, x, y, z), () -> false);
    }

    private static boolean read(BlockDoor door, World world, int x, int y, int z) {
        var tile = world.getTileEntity(x, y, z);
        if (ANIMATED_STATE != null && ANIMATED_STATE.getDeclaringClass()
            .isInstance(tile)) {
            try {
                Object state = ANIMATED_STATE.invoke(tile);
                if (state instanceof Enum<?>value) return value.name()
                    .equals(MalisisDoorsBlockIds.KEY_OPENED)
                    || value.name()
                        .equals(MalisisDoorsBlockIds.KEY_OPENING);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                    MalisisDoorsBlockDiagnostics.MSG_NATIVE_ANIMATED_DOOR_STATE_IS,
                    failure);
            }
        }
        return door.func_150015_f(world, x, y, z);
    }

    private static Method resolve() {
        try {
            return Class
                .forName(
                    MalisisDoorsBlockIds.TARGET_CLASS_DOOR_TILE_ENTITY,
                    false,
                    NativeDoorState.class.getClassLoader())
                .getMethod(MalisisDoorsBlockIds.ID_GETSTATE);
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            if (Loader.isModLoaded(MalisisDoorsBlockIds.ID_MALISISDOORS)) throw new IllegalStateException(
                MalisisDoorsBlockDiagnostics.MSG_INSTALLED_ANIMATED_DOOR_STATE_API,
                absent);
            return null;
        }
    }
}
