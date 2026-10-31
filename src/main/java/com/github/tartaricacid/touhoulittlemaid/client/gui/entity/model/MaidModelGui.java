/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/model/MaidModelGui.java, MIT
 * Adaptation: native RC2 preview, bounded detached entities, authoritative builtin selection
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.render.MaidGuiPreview;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidModelGui extends AbstractModelGui {

    private static int pageIndex, packIndex, rowIndex;
    private final Map<Integer, EntityMaid> previews = new LinkedHashMap<Integer, EntityMaid>(64, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, EntityMaid> eldest) {
            return size() > 64;
        }
    };

    public MaidModelGui(ContainerMaidModels container) {
        super(container);
    }

    @Override
    protected int getPackIndex() {
        return packIndex;
    }

    @Override
    protected void setPackIndex(int index) {
        packIndex = index;
    }

    @Override
    protected int getPageIndex() {
        return pageIndex;
    }

    @Override
    protected void setPageIndex(int index) {
        pageIndex = index;
    }

    @Override
    protected int getRowIndex() {
        return rowIndex;
    }

    @Override
    protected void setRowIndex(int index) {
        rowIndex = index;
    }

    @Override
    protected void drawLeftEntity(int x, int y, int mouseX, int mouseY) {
        var choice = BuiltinModelCatalog.get(entity.getModelId());
        float scale = choice == null ? 1 : choice.getItemScale();
        int center = (x - 128) / 2;
        MaidGuiPreview
            .draw(entity, center, y + 90, (int) (45 * scale), center - mouseX, y + 40 - mouseY, 0, 0, x - 128, height);
    }

    @Override
    protected void drawRightEntity(int x, int y, int modelIndex) {
        if (com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache.CacheIconManager.INSTANCE
            .draw(modelIndex, x, y)) return;
        var maid = previews.computeIfAbsent(modelIndex, index -> {
            var detached = new EntityMaid(mc.theWorld);
            detached.onGround = true;
            detached.setSitting(false);
            detached.setModelId(
                BuiltinModelCatalog.choices()
                    .get(index)
                    .getId());
            return detached;
        });
        var choice = BuiltinModelCatalog.choices()
            .get(modelIndex);
        MaidGuiPreview.draw(maid, x, y, (int) (12 * choice.getItemScale()), -25, -20, 0, 0, width, height);
    }

    @Override
    public void onGuiClosed() {
        previews.clear();
        super.onGuiClosed();
    }
}
