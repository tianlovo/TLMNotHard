package com.tianluoqaq.tlmnh.compat.botania.development.shedding;

import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

/** Discovery supplies the provider mixin before the framework's late prepareConfigs checkpoint. */
@LateMixin
public final class BotaniaSheddingLateLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.tlmnhqa.botania-shedding-provider.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        return BotaniaSheddingBootstrap.lateMixins(loadedMods);
    }
}
