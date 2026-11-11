package com.tianluoqaq.tlmnh.compat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.tianluoqaq.tlmnh.compat.adventurebackpack.AdventureBackpackCompat;
import com.tianluoqaq.tlmnh.compat.angelica.AngelicaCompat;
import com.tianluoqaq.tlmnh.compat.applecore.AppleCoreCompat;
import com.tianluoqaq.tlmnh.compat.backhand.BackhandCompat;
import com.tianluoqaq.tlmnh.compat.backpack.BackpackCompat;
import com.tianluoqaq.tlmnh.compat.battlegear2.Battlegear2Compat;
import com.tianluoqaq.tlmnh.compat.endlessids.EndlessIdsCompat;
import com.tianluoqaq.tlmnh.compat.etfuturum.EtFuturumCompat;
import com.tianluoqaq.tlmnh.compat.extrautilities.ExtraUtilitiesCompat;
import com.tianluoqaq.tlmnh.compat.forestry.ForestryCompat;
import com.tianluoqaq.tlmnh.compat.galacticraftcore.GalacticraftCoreCompat;
import com.tianluoqaq.tlmnh.compat.gregtech.GregTechCompat;
import com.tianluoqaq.tlmnh.compat.hodgepodge.HodgepodgeCompat;
import com.tianluoqaq.tlmnh.compat.lwjgl3ify.Lwjgl3ifyCompat;
import com.tianluoqaq.tlmnh.compat.malisisdoors.MalisisDoorsCompat;
import com.tianluoqaq.tlmnh.compat.projredexploration.ProjRedExplorationCompat;
import com.tianluoqaq.tlmnh.compat.tconstruct.TConstructCompat;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.TinkersDefenseCompat;
import com.tianluoqaq.tlmnh.compat.twilightforest.TwilightForestCompat;
import com.tianluoqaq.tlmnh.compat.witchery.WitcheryCompat;
import com.tianluoqaq.tlmnh.compat.ysmu.YsmuCompat;

/** 全部已接入外部模组描述类的有序清单；新增或移除模组适配时在此处登记 */
public final class CompatModules {

    private static final List<CompatDescriptor> ALL = Collections.unmodifiableList(
        Arrays.asList(
            AdventureBackpackCompat.INSTANCE,
            AngelicaCompat.INSTANCE,
            AppleCoreCompat.INSTANCE,
            BackhandCompat.INSTANCE,
            BackpackCompat.INSTANCE,
            Battlegear2Compat.INSTANCE,
            EndlessIdsCompat.INSTANCE,
            EtFuturumCompat.INSTANCE,
            ExtraUtilitiesCompat.INSTANCE,
            ForestryCompat.INSTANCE,
            GalacticraftCoreCompat.INSTANCE,
            GregTechCompat.INSTANCE,
            HodgepodgeCompat.INSTANCE,
            Lwjgl3ifyCompat.INSTANCE,
            MalisisDoorsCompat.INSTANCE,
            ProjRedExplorationCompat.INSTANCE,
            TConstructCompat.INSTANCE,
            TinkersDefenseCompat.INSTANCE,
            TwilightForestCompat.INSTANCE,
            WitcheryCompat.INSTANCE,
            YsmuCompat.INSTANCE));

    private CompatModules() {}

    public static List<CompatDescriptor> all() {
        return ALL;
    }
}
