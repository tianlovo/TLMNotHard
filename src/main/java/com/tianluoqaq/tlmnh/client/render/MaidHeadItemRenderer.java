package com.tianluoqaq.tlmnh.client.render;

import java.nio.FloatBuffer;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.IPlantable;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原生头颅及方块渲染边界，不改装备、物品NBT或模型姿态 */
@SideOnly(Side.CLIENT)
public final class MaidHeadItemRenderer {

    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final java.util.Map<EntityMaid, Integer> LAST_DRAWN = new java.util.WeakHashMap<>();

    public static void beginFrame(EntityMaid maid) {
        if (Boolean.getBoolean("tlmnh.devCommands")) LAST_DRAWN.put(maid, 0);
    }

    /** bit 1头颅、bit 2方块，只表示原生绘制正常返回，不代替画面验收 */
    public static int drawnMask(EntityMaid maid) {
        return LAST_DRAWN.getOrDefault(maid, 0);
    }

    private static void drawn(EntityMaid maid, int bit) {
        if (Boolean.getBoolean("tlmnh.devCommands")) LAST_DRAWN.put(maid, drawnMask(maid) | bit);
    }

    private MaidHeadItemRenderer() {}

    public static void render(EntityMaid maid, BuiltinMaidModel model) {
        var attachment = MaidHeadAttachment.capture(model);
        if (attachment == null) return;
        ItemStack helmet = maid.getEquipmentInSlot(4);
        if (helmet != null && helmet.stackSize > 0 && helmet.getItem() == Items.skull) {
            var matrix = new Matrix4f(attachment);
            matrix.scale(
                model.getGecko() == null ? 1.1875F : -1.1875F,
                model.getGecko() == null ? -1.1875F : 1.1875F,
                -1.1875F);
            try (var scope = new MaidItemRenderScope()) {
                apply(matrix);
                TileEntitySkullRenderer.field_147536_b
                    .func_152674_a(-.5F, 0, -.5F, 1, 180, helmet.getItemDamage(), profile(helmet));
                drawn(maid, 1);
            }
        }
        ItemStack stack = maid.getBackpackShowItem();
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) return;
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
        if (block == null) throw new IllegalStateException("Head display plant has no block");
        var matrix = new Matrix4f(attachment);
        matrix.scale(model.getGecko() == null ? .8F : -.8F, model.getGecko() == null ? -.8F : .8F, -.8F);
        // 原block坐标[0,1]先translate(-.5,.625,-.5)，旧renderBlockAsItem坐标以中心为原点
        matrix.translate(0, 1.125F, 0);
        int type = block.getRenderType();
        // 原生这些分支额外旋转Y90；只抵消已知入口，不干预其它模组的库存渲染器
        if (type == 0 || type == 31 || type == 39 || type == 16 || type == 26 || type == 22)
            matrix.rotateY((float) -Math.PI / 2);
        try (var scope = new MaidItemRenderScope(); var filters = new MaidItemFilterScope(stack)) {
            apply(matrix);
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            // 独立实例避免原生或第三方渲染器把bounds留给后续女仆
            new RenderBlocks().renderBlockAsItem(block, metadata, 1);
            drawn(maid, 2);
        }
    }

    private static GameProfile profile(ItemStack stack) {
        if (!stack.hasTagCompound()) return null;
        var nbt = stack.getTagCompound();
        if (nbt.hasKey("SkullOwner", 10)) return NBTUtil.func_152459_a(nbt.getCompoundTag("SkullOwner"));
        if (nbt.hasKey("SkullOwner", 8) && !nbt.getString("SkullOwner")
            .isEmpty()) return new GameProfile((UUID) null, nbt.getString("SkullOwner"));
        return null;
    }

    private static void apply(Matrix4f matrix) {
        MATRIX_BUFFER.clear();
        matrix.get(MATRIX_BUFFER);
        MATRIX_BUFFER.position(0);
        GL11.glMultMatrix(MATRIX_BUFFER);
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glColor4f(1, 1, 1, 1);
    }
}
