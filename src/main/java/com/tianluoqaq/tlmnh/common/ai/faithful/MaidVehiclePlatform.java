package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

/** 正式版EFR新船的第二乘客由Seat实体承载；使用实际addToBoat，不能直接抢占驾驶位 */
public final class MaidVehiclePlatform {

    private MaidVehiclePlatform() {}

    public static Entity vehicle(Entity passenger) {
        return IntegrationGuard.call(ExternalFeature.VEHICLES, () -> vehicleSafe(passenger), () -> null);
    }

    private static Entity vehicleSafe(Entity passenger) {
        Entity riding = passenger.ridingEntity;
        if (riding != null && Efr.SEAT != null && Efr.SEAT.isInstance(riding))
            return (Entity) call(Efr.BOAT_OF_SEAT, riding);
        return riding;
    }

    public static Entity controlledVehicle(EntityPlayer player) {
        return IntegrationGuard.call(ExternalFeature.VEHICLES, () -> controlledVehicleSafe(player), () -> null);
    }

    private static Entity controlledVehicleSafe(EntityPlayer player) {
        Entity vehicle = vehicle(player);
        if (vehicle == null) return null;
        if (Efr.BOAT != null && Efr.BOAT.isInstance(vehicle))
            return call(Efr.CONTROLLER, vehicle) == player ? vehicle : null;
        return vehicle.riddenByEntity == player ? vehicle : null;
    }

    public static boolean canAddPassenger(Entity vehicle) {
        return IntegrationGuard.call(ExternalFeature.VEHICLES, () -> canAddPassengerSafe(vehicle), () -> false);
    }

    private static boolean canAddPassengerSafe(Entity vehicle) {
        if (vehicle == null || vehicle.isDead) return false;
        if (Efr.BOAT != null && Efr.BOAT.isInstance(vehicle)) {
            var passengers = (List<?>) call(Efr.PASSENGERS, vehicle);
            if (passengers.isEmpty()) return true;
            var seat = (Entity) call(Efr.GET_SEAT, vehicle);
            return passengers.size() == 1 && seat != null && !seat.isDead && seat.riddenByEntity == null;
        }
        return vehicle.riddenByEntity == null;
    }

    public static void mount(Entity maid, Entity vehicle) {
        IntegrationGuard.call(ExternalFeature.VEHICLES, () -> {
            mountSafe(maid, vehicle);
            return true;
        }, () -> false);
    }

    private static void mountSafe(Entity maid, Entity vehicle) {
        if (Efr.BOAT != null && Efr.BOAT.isInstance(vehicle)) call(Efr.ADD, vehicle, maid);
        else maid.mountEntity(vehicle);
    }

    private static Object call(Method method, Object object, Object... arguments) {
        try {
            return method.invoke(object, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Verified EFR vehicle API failed: " + method.getName(), failure);
        }
    }

    private static final class Efr {

        static final Class<?> BOAT, SEAT;
        static final Method CONTROLLER, PASSENGERS, GET_SEAT, ADD, BOAT_OF_SEAT;

        static {
            Class<?> boat = null, seat = null;
            Method controller = null, passengers = null, getSeat = null, add = null, boatOfSeat = null;
            try {
                boat = Class.forName("ganymedes01.etfuturum.entities.EntityNewBoat");
                seat = Class.forName("ganymedes01.etfuturum.entities.EntityNewBoatSeat");
                controller = boat.getMethod("getControllingPassenger");
                passengers = boat.getMethod("getPassengers");
                getSeat = boat.getMethod("getSeat");
                add = boat.getMethod("addToBoat", Entity.class);
                boatOfSeat = seat.getMethod("getBoat");
            } catch (ClassNotFoundException absent) {
                if (cpw.mods.fml.common.Loader.isModLoaded("etfuturum"))
                    throw new IllegalStateException("Installed EFR vehicle classes changed", absent);
                boat = seat = null;
            } catch (NoSuchMethodException incompatible) {
                throw new IllegalStateException("Unsupported EFR boat API", incompatible);
            }
            BOAT = boat;
            SEAT = seat;
            CONTROLLER = controller;
            PASSENGERS = passengers;
            GET_SEAT = getSeat;
            ADD = add;
            BOAT_OF_SEAT = boatOfSeat;
        }
    }
}
