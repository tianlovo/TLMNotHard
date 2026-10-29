package com.tianluoqaq.tlmnh.common.development;

import java.util.HashSet;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 在服务端检查全量装饰 ID 与存档白名单，不声称模型已经渲染 */
public final class BuiltinModelProbe {

    private BuiltinModelProbe() {}

    public static void run(World world) {
        var maid = new EntityMaid(world);
        var restored = new EntityMaid(world);
        int winefoxChoices = 0;
        var geckoWinefoxBases = new HashSet<String>();
        var bedrockWinefoxBases = new HashSet<String>();
        for (var choice : BuiltinModelCatalog.choices()) {
            maid.setModelId(choice.getId());
            var tag = new NBTTagCompound();
            maid.writeToNBT(tag);
            restored.readFromNBT(tag);
            require(
                choice.getId()
                    .equals(restored.getModelId()),
                "model ID round-trip");
            if (choice.isWinefox()) {
                winefoxChoices++;
                (choice.isGecko() ? geckoWinefoxBases : bedrockWinefoxBases).add(choice.getBaseId());
            }
        }
        require(
            winefoxChoices == 35 && geckoWinefoxBases.size() == 19 && bedrockWinefoxBases.size() == 2,
            "winefox inventory");
        maid.setModelId("external:not_builtin");
        require(EntityMaid.DEFAULT_MODEL_ID.equals(maid.getModelId()), "unknown model fallback");
        var tag = new NBTTagCompound();
        maid.writeToNBT(tag);
        tag.setString(EntityMaid.MODEL_ID_TAG, "external:not_builtin");
        restored.readFromNBT(tag);
        require(EntityMaid.DEFAULT_MODEL_ID.equals(restored.getModelId()), "unknown saved model fallback");
        TLMNotHard.LOG.info(
            "TLMNH_MODEL_CATALOG_TEST_PASS choices={} winefox={} geckoBases={} bedrockBases={} rendering=unverified",
            BuiltinModelCatalog.choices()
                .size(),
            winefoxChoices,
            geckoWinefoxBases.size(),
            bedrockWinefoxBases.size());
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("TLMNH builtin model probe failed: " + name);
    }
}
