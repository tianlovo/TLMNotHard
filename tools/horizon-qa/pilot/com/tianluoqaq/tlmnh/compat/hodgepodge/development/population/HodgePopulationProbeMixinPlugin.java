package com.tianluoqaq.tlmnh.compat.hodgepodge.development.population;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.qa.PopulationQuiescenceContract;
import com.tianluoqaq.tlmnh.qa.DeathProductsBootstrap;
import com.tianluoqaq.tlmnh.qa.PaidWearApplication;

/** Bootstrap reads frozen files/resources only; never defines or reflects on the provider target. */
public final class HodgePopulationProbeMixinPlugin implements IMixinConfigPlugin {

    public void onLoad(String p) {
        if (!PopulationQuiescenceContract.requested()) return;
        try {
            if (getClass().getClassLoader()
                .getResource("com/mitchej123/hodgepodge/mixins/hooks/ChunkGenScheduler.class") == null)
                throw new IllegalStateException("Hodge provider resource absent from actual loader");
            PopulationQuiescenceContract.select();
        } catch (Exception | LinkageError | AssertionError problem) {
            IntegrationGuard.throwIfFatal(problem);
            PopulationQuiescenceContract.selectionFailed(problem);
        }
    }

    public String getRefMapperConfig() {
        return null;
    }

    public boolean shouldApplyMixin(String target, String mixin) {
        if (DeathProductsBootstrap.owns(mixin)) return PopulationQuiescenceContract.selected()
            && DeathProductsBootstrap.applies(target, mixin);
        if (mixin.endsWith(".PaidWearItemStackMixin")) return PaidWearApplication.requested()
            && PopulationQuiescenceContract.selected() && target.equals("net.minecraft.item.ItemStack")
            && mixin.equals("com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin.PaidWearItemStackMixin");
        return PopulationQuiescenceContract.selected()
            && target.equals("com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler")
            && mixin.equals(
                "com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin.HodgePopulationSchedulerMixin");
    }

    public void acceptTargets(Set<String> mine, Set<String> other) {}

    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>(DeathProductsBootstrap.mixins());
        if (PaidWearApplication.requested()) mixins.add("PaidWearItemStackMixin");
        return mixins.isEmpty() ? null : mixins;
    }

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (DeathProductsBootstrap.owns(mixin)) {
            DeathProductsBootstrap.applied(target, node, mixin);
            return;
        }
        if (mixin.endsWith(".PaidWearItemStackMixin")) {
            PaidWearApplication.applied(target, mixin);
            return;
        }
        System.out.println("TLMNH_QA_QUIESCENCE_APPLIED " + target + " " + mixin);
    }
}
