package com.tianluoqaq.tlmnh.client.render.attachment;

import java.nio.FloatBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.util.Constants;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatServices;
import com.tianluoqaq.tlmnh.client.render.model.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemFilterScope;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原生头颅及方块渲染边界，不改装备、物品NBT或模型姿态 */
@SideOnly(Side.CLIENT)
public final class MaidHeadItemRenderer {

    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM);
    private static final Map<EntityMaid, Integer> LAST_DRAWN = new WeakHashMap<>();
    private static final Map<EntityMaid, String> LAST_SKULL_RENDERER = new WeakHashMap<>();

    public static void beginFrame(EntityMaid maid) {
        if (DevelopmentFlags.isDevCommandsEnabled()) {
            LAST_DRAWN.put(maid, AttachmentRenderLayout.PUT_INITIAL_VALUE);
            LAST_SKULL_RENDERER.put(maid, "");
        }
    }

    /** 仅记录实际注册并正常绘制的提供方，不代表皮肤或外层像素验收 */
    public static String skullRendererClass(EntityMaid maid) {
        return LAST_SKULL_RENDERER.getOrDefault(maid, "");
    }

    /** bit 1头颅、bit 2方块，只表示原生绘制正常返回，不代替画面验收 */
    public static int drawnMask(EntityMaid maid) {
        return LAST_DRAWN.getOrDefault(maid, AttachmentRenderLayout.PUT_INITIAL_VALUE);
    }

    private static void drawn(EntityMaid maid, int bit) {
        if (DevelopmentFlags.isDevCommandsEnabled()) LAST_DRAWN.put(maid, drawnMask(maid) | bit);
    }

    private MaidHeadItemRenderer() {}

    public static void render(EntityMaid maid, BuiltinMaidModel model) {
        var attachment = MaidHeadAttachment.capture(model);
        if (attachment == null) return;
        ItemStack helmet = maid.getEquipmentInSlot(AttachmentRenderLayout.HEAD_ITEM_GET_EQUIPMENT_IN_SLOT_FIRST_PARAM);
        if (helmet != null && helmet.stackSize > 0 && helmet.getItem() == Items.skull) {
            var matrix = new Matrix4f(attachment);
            matrix.scale(
                model.getGecko() == null ? AttachmentRenderLayout.HEAD_ITEM_RENDER_FACTOR : AttachmentRenderLayout.HEAD_ITEM_RENDER_FACTOR_2,
                model.getGecko() == null ? AttachmentRenderLayout.HEAD_ITEM_RENDER_FACTOR_2 : AttachmentRenderLayout.HEAD_ITEM_RENDER_FACTOR,
                AttachmentRenderLayout.HEAD_ITEM_RENDER_FACTOR_2);
            try (var scope = new MaidItemRenderScope()) {
                apply(matrix);
                // EFR给同一Tile类型注册了原生外层模型，不能固定调用已被替换的旧singleton
                var renderer = TileEntityRendererDispatcher.instance.getSpecialRenderer(new TileEntitySkull());
                if (renderer != null && renderer.getClass() == TileEntitySkullRenderer.class) {
                    ((TileEntitySkullRenderer) renderer)
                        .func_152674_a(AttachmentRenderLayout.BACK_ITEM_RENDER_OFFSET_X, AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.BACK_ITEM_RENDER_OFFSET_X, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.HEAD_ITEM_FUNC_152674_A_FIFTH_PARAM, helmet.getItemDamage(), profile(helmet));
                } else ClientCompatServices.itemRender()
                    .renderCustomSkull(renderer, helmet.getItemDamage(), profile(helmet));
                if (DevelopmentFlags.isDevCommandsEnabled()) LAST_SKULL_RENDERER.put(
                    maid,
                    renderer.getClass()
                        .getName());
                drawn(maid, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
            }
        }
        ItemStack stack = maid.getBackpackShowItem();
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) return;
        if (ClientCompatServices.itemRender()
            .isBanner(stack)) return;
        Block block = Block.getBlockFromItem(stack.getItem());
        // 1.20默认方块状态；旧平台用该物品公开的元数据入口，不借用玩家朝向
        int metadata = stack.getItem()
            .getMetadata(stack.getItemDamage());
        if (block instanceof IPlantable plant && !(block instanceof BlockDoublePlant)) {
            int x = MathHelper.floor_double(maid.posX), y = MathHelper.floor_double(maid.posY),
                z = MathHelper.floor_double(maid.posZ);
            block = plant.getPlant(maid.worldObj, x, y, z);
            metadata = plant.getPlantMetadata(maid.worldObj, x, y, z);
        }
        if (block == null) throw new IllegalStateException(AttachmentRenderDiagnostics.MSG_HEAD_DISPLAY_PLANT_HAS_NO);
        var matrix = new Matrix4f(attachment);
        matrix.scale(model.getGecko() == null ? AttachmentRenderLayout.HEAD_ITEM_RENDER_SCALE_X : AttachmentRenderLayout.HEAD_ITEM_RENDER_SCALE_X_2, model.getGecko() == null ? AttachmentRenderLayout.HEAD_ITEM_RENDER_SCALE_X_2 : AttachmentRenderLayout.HEAD_ITEM_RENDER_SCALE_X, AttachmentRenderLayout.HEAD_ITEM_RENDER_SCALE_X_2);
        // 原block坐标[0,1]先translate(-.5,.625,-.5)，旧renderBlockAsItem坐标以中心为原点
        matrix.translate(AttachmentRenderLayout.PUT_INITIAL_VALUE, AttachmentRenderLayout.HEAD_ITEM_RENDER_OFFSET_Y, AttachmentRenderLayout.PUT_INITIAL_VALUE);
        int type = block.getRenderType();
        // 原生这些分支额外旋转Y90；只抵消已知入口，不干预其它模组的库存渲染器
        if (type == 0 || type == AttachmentRenderLayout.TARGET_TYPE_THRESHOLD || type == AttachmentRenderLayout.TARGET_TYPE_THRESHOLD_2 || type == AttachmentRenderLayout.BACK_ITEM_CREATE_FLOAT_BUFFER_FIRST_PARAM || type == AttachmentRenderLayout.TARGET_TYPE_THRESHOLD_3 || type == AttachmentRenderLayout.TARGET_TYPE_THRESHOLD_4)
            matrix.rotateY((float) -Math.PI / AttachmentRenderLayout.MIDPOINT_DIVISOR);
        try (var scope = new MaidItemRenderScope(); var filters = new MaidItemFilterScope(stack)) {
            apply(matrix);
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            // 独立实例避免原生或第三方渲染器把bounds留给后续女仆
            new RenderBlocks().renderBlockAsItem(block, metadata, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
            drawn(maid, AttachmentRenderLayout.MIDPOINT_DIVISOR);
        }
    }

    private static GameProfile profile(ItemStack stack) {
        if (!stack.hasTagCompound()) return null;
        var nbt = stack.getTagCompound();
        if (nbt.hasKey(AttachmentRenderIds.KEY_SKULL_OWNER, Constants.NBT.TAG_COMPOUND))
            return NBTUtil.func_152459_a(nbt.getCompoundTag(AttachmentRenderIds.KEY_SKULL_OWNER));
        if (nbt.hasKey(AttachmentRenderIds.KEY_SKULL_OWNER, Constants.NBT.TAG_STRING) && !nbt.getString(AttachmentRenderIds.KEY_SKULL_OWNER)
            .isEmpty()) return new GameProfile((UUID) null, nbt.getString(AttachmentRenderIds.KEY_SKULL_OWNER));
        return null;
    }

    private static void apply(Matrix4f matrix) {
        MATRIX_BUFFER.clear();
        matrix.get(MATRIX_BUFFER);
        MATRIX_BUFFER.position(AttachmentRenderLayout.PUT_INITIAL_VALUE);
        GL11.glMultMatrix(MATRIX_BUFFER);
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glColor4f(AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y, AttachmentRenderLayout.BACK_ITEM_ATTACHMENT_OFFSET_Y);
    }
}
