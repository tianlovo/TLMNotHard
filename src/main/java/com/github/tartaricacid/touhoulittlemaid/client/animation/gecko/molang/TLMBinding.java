/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/gecko/molang/TLMBinding.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: built-in animation frame data only; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang;

import com.tianluoqaq.tlmnh.client.render.molang.MolangFrameBinding;

public class TLMBinding extends MolangFrameBinding {

    public static final TLMBinding INSTANCE = new TLMBinding();

    private TLMBinding() {
        frameVar("is_begging", frame -> frame.begging);
        frameVar("is_sitting", frame -> frame.sitting);
        frameVar("has_backpack", frame -> frame.hasBackpack);
    }
}
