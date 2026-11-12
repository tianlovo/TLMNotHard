package com.tianluoqaq.tlmnh.compat.gregtech.common.maintenance;

/** GT5U 普通维护仓与多方块控制器反射绑定表（避开 GT5U 非传递超接口编译期解析） */
public final class GregTechMaintenanceReflectionSpec {

    public static final String CLS_GREGTECH_TILE = "gregtech.api.interfaces.tileentity.IGregTechTileEntity";
    public static final String CLS_META_TILE = "gregtech.api.interfaces.metatileentity.IMetaTileEntity";
    public static final String CLS_HATCH_MAINTENANCE = "gregtech.api.metatileentity.implementations.MTEHatchMaintenance";
    public static final String CLS_MULTI_BLOCK_BASE = "gregtech.api.metatileentity.implementations.MTEMultiBlockBase";

    public static final String FLD_AUTO = "mAuto";
    public static final String FLD_WRENCH = "mWrench";
    public static final String FLD_SCREWDRIVER = "mScrewdriver";
    public static final String FLD_SOFT_MALLET = "mSoftMallet";
    public static final String FLD_HARD_HAMMER = "mHardHammer";
    public static final String FLD_SOLDERING_TOOL = "mSolderingTool";
    public static final String FLD_CROWBAR = "mCrowbar";
    public static final String FLD_MACHINE = "mMachine";
    public static final String FLD_MAINTENANCE_HATCHES = "mMaintenanceHatches";

    public static final String MTH_IS_DEAD = "isDead";
    public static final String MTH_GET_META_TILE_ID = "getMetaTileID";
    public static final String MTH_GET_META_TILE_ENTITY = "getMetaTileEntity";
    public static final String MTH_GET_BASE_META_TILE_ENTITY = "getBaseMetaTileEntity";
    public static final String MTH_SET_MAINTENANCE_SOUND = "setMaintenanceSound";
    public static final String MTH_SHOULD_CHECK_MAINTENANCE = "shouldCheckMaintenance";
    public static final String MTH_GET_IDEAL_STATUS = "getIdealStatus";
    public static final String MTH_GET_REPAIR_STATUS = "getRepairStatus";
    public static final String MTH_CHECK_MAINTENANCE = "checkMaintenance";

    private GregTechMaintenanceReflectionSpec() {}
}
