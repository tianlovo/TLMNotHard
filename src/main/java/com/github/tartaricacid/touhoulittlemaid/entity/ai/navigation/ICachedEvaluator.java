/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/navigation/ICachedEvaluator.java, MIT
 * Adaptation: target-independent integer positions and modern evaluator boundary
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

public interface ICachedEvaluator {

    void init(int x, int y, int z, int cx, int cy, int cz);

    void markVis(Point pos);
}
