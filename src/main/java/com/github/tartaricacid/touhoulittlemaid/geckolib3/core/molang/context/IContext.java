/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/context/IContext.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context;

import java.util.Random;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationControllerContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.IScopedVariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.ITempVariableStorage;
import com.tianluoqaq.tlmnh.client.render.molang.MolangModelResources;

public interface IContext<TEntity> {

    TEntity entity();

    Random random();

    AnimationControllerContext animationControllerContext();

    MolangModelResources modelResources();

    <TChild> IContext<TChild> createChild(TChild child);

    ITempVariableStorage tempStorage();

    IScopedVariableStorage scopedStorage();

    IForeignVariableStorage foreignStorage();
}
