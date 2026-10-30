/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/builtin/query/ItemNameAny.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: pure query snapshots and Java 8; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.query;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExecutionContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFunction;

public class ItemNameAny extends MolangQueryFunction {

    @Override
    protected Object eval(ExecutionContext<IContext<MolangQueryFrame>> context, ArgumentCollection arguments) {
        Slot slot = Slot.fromName(arguments.getAsString(context, 0));
        if (slot == null) return null;
        var equipment = context.entity()
            .entity()
            .equipment(slot);
        if (equipment == null) return false;
        for (int i = 1; i < arguments.size(); i++) {
            String id = MolangQueryFrame.canonicalItemId(arguments.getAsString(context, i));
            if (id == null) return null;
            if (id.equals(equipment.itemId)) return true;
        }
        return false;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size >= 2;
    }
}
