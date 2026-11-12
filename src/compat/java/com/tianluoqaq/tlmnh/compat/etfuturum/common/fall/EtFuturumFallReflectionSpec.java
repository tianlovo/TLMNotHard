package com.tianluoqaq.tlmnh.compat.etfuturum.common.fall;

/** Et Futurum ItemNewBoat 与 EntityNewBoat 反射定义 */
public final class EtFuturumFallReflectionSpec {

    public static final String ITEM_NEW_BOAT_CLASS = "ganymedes01.etfuturum.items.ItemNewBoat";
    public static final String ENTITY_NEW_BOAT_CLASS = "ganymedes01.etfuturum.entities.EntityNewBoat";
    public static final String ENTITY_BOAT_SEAT_CLASS = "ganymedes01.etfuturum.entities.EntityNewBoatSeat";
    public static final String ENTITY_CHEST_BOAT_CLASS = "ganymedes01.etfuturum.entities.EntityNewBoatWithChest";

    public static final String FLD_IS_CHEST = "isChest";
    public static final String FLD_DOMAIN = "domain";
    public static final String FLD_NAME = "name";

    public static final String MTD_SET_BOAT_TYPE = "setBoatType";
    public static final String MTD_SET_BOAT_NAME = "setBoatName";
    public static final String MTD_ADD_TO_BOAT = "addToBoat";
    public static final String MTD_REMOVE_PASSENGERS = "removePassengers";
    public static final String MTD_GET_SEAT = "getSeat";

    private EtFuturumFallReflectionSpec() {}
}
