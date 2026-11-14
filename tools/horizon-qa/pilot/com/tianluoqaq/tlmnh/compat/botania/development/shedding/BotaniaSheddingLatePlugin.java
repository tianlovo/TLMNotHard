package com.tianluoqaq.tlmnh.compat.botania.development.shedding;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Config creation precedes adding every mod URL, so resource observation belongs in preApply. */
public final class BotaniaSheddingLatePlugin implements IMixinConfigPlugin {

    public void onLoad(String name) {
        BotaniaSheddingBootstrap.requested();
    }

    public String getRefMapperConfig() {
        return null;
    }

    public List<String> getMixins() {
        return Collections.emptyList();
    }

    public boolean shouldApplyMixin(String target, String mixin) {
        return BotaniaSheddingBootstrap.appliesProvider(target, mixin);
    }

    public void acceptTargets(Set<String> mine, Set<String> other) {}

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        BotaniaSheddingBootstrap.providerPreApply(target, node, mixin);
    }

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        BotaniaSheddingBootstrap.providerPostApply(target, mixin);
    }
}
