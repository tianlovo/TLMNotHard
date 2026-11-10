package com.tianluoqaq.tlmnh.compat.hodgepodge.development.population;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.qa.PopulationQuiescenceContract;

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
        return PopulationQuiescenceContract.selected()
            && target.equals("com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler")
            && mixin.equals(
                "com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin.HodgePopulationSchedulerMixin");
    }

    public void acceptTargets(Set<String> mine, Set<String> other) {}

    public List<String> getMixins() {
        return null;
    }

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        System.out.println("TLMNH_QA_QUIESCENCE_APPLIED " + target + " " + mixin);
    }
}
