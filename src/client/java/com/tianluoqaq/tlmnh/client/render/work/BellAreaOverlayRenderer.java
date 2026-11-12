package com.tianluoqaq.tlmnh.client.render.work;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatServices;
import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.work.bell.BellPreviewProtocol;
import com.tianluoqaq.tlmnh.common.work.bell.BellPreviewSnapshot;
import com.tianluoqaq.tlmnh.common.work.bell.BellTuning;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellItem;
import com.tianluoqaq.tlmnh.common.work.bell.MaidBellPreviewNetwork;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellItem;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.StickKind;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 持铃或潜行查看女仆时绘制绑定范围，完成后恢复 OpenGL 属性栈与矩阵 */
@SideOnly(Side.CLIENT)
public final class BellAreaOverlayRenderer {

    private static World previewWorld;
    private static int previewTarget = BellPreviewProtocol.UNSET_ENTITY_ID;
    private static BellPreviewSnapshot preview;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new BellAreaOverlayRenderer());
    }

    public static void acceptPreviewSnapshot(BellPreviewSnapshot snapshot) {
        if (previewWorld == null || previewWorld != Minecraft.getMinecraft().theWorld
            || snapshot == null
            || snapshot.worldDimension() != previewWorld.provider.dimensionId
            || snapshot.maidEntityId() != previewTarget) return;
        preview = snapshot;
    }

    public static BellPreviewSnapshot previewSnapshot() {
        return previewWorld == Minecraft.getMinecraft().theWorld ? preview : null;
    }

    private static void clearTarget() {
        previewTarget = BellPreviewProtocol.UNSET_ENTITY_ID;
        preview = null;
    }

    private static void resetWorld(World world) {
        clearTarget();
        previewWorld = world;
        MaidBellPreviewNetwork.resetClientState();
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world == previewWorld) resetWorld(null);
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        var minecraft = Minecraft.getMinecraft();
        if (previewWorld != minecraft.theWorld) resetWorld(minecraft.theWorld);
        if (minecraft.theWorld == null || minecraft.thePlayer == null || minecraft.renderViewEntity == null) return;
        var areas = new ArrayList<Area>();
        var mainhand = minecraft.thePlayer.getHeldItem();
        var offhand = ClientCompatServices.itemRender()
            .playerOffhandStack(minecraft.thePlayer);
        append(mainhand, minecraft.theWorld, areas);
        append(offhand, minecraft.theWorld, areas);
        if (!isBell(mainhand) && !isBell(offhand)
            && minecraft.thePlayer.isSneaking()
            && minecraft.objectMouseOver != null
            && minecraft.objectMouseOver.entityHit instanceof EntityMaid maid
            && maid.isEntityAlive()
            && maid.isOwner(minecraft.thePlayer)
            && maid.getDistanceSqToEntity(minecraft.thePlayer) <= BellPreviewProtocol.MAX_PREVIEW_DIST_SQ) {
            if (previewTarget != maid.getEntityId()) {
                clearTarget();
                previewTarget = maid.getEntityId();
            }
            MaidBellPreviewNetwork.requestPreview(maid.getEntityId());
            appendPreview(preview, minecraft.theWorld, areas);
        } else clearTarget();
        if (areas.isEmpty()) return;
        var viewer = minecraft.renderViewEntity;
        boolean observed = ClientDevelopmentAccess.isActive();
        if (observed) ClientDevelopmentAccess.hooks()
            .bellOverlayBeforeDraw();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(
                -(viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * event.partialTicks),
                -(viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * event.partialTicks),
                -(viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * event.partialTicks));
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(BellAreaOverlayTuning.LINE_WIDTH);
            for (var area : areas) {
                int color = area.color;
                GL11.glColor4ub(
                    (byte) (color >>> BellAreaOverlayTuning.RED_SHIFT),
                    (byte) (color >>> BellAreaOverlayTuning.GREEN_SHIFT),
                    (byte) color,
                    (byte) (color >>> BellAreaOverlayTuning.ALPHA_SHIFT));
                RenderGlobal.drawOutlinedBoundingBox(area.box, BellAreaOverlayTuning.USE_CURRENT_COLOR);
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            if (observed) ClientDevelopmentAccess.hooks()
                .bellOverlayAfterDraw();
        }
    }

    private static boolean isBell(ItemStack stack) {
        return stack != null
            && (stack.getItem() instanceof MaintenanceBellItem || stack.getItem() instanceof BreedingBellItem);
    }

    private static void append(ItemStack stack, World world, List<Area> areas) {
        if (stack == null) return;
        if (stack.getItem() instanceof MaintenanceBellItem) {
            var data = MaintenanceBellData.read(stack);
            if (data.isBound() && data.dimension() == world.provider.dimensionId)
                appendChunks(data.chunks(), world, areas);
        } else if (stack.getItem() instanceof BreedingBellItem) {
            var data = BreedingBellData.read(stack);
            if (data.isBound() && data.dimension() == world.provider.dimensionId)
                appendBreeding(data.centerX(), data.cropY(), data.centerZ(), world, areas);
        }
    }

    private static void appendPreview(BellPreviewSnapshot snapshot, World world, List<Area> areas) {
        if (snapshot == null) return;
        if (snapshot.hasMaintenance() && snapshot.maintenanceDimension() == world.provider.dimensionId)
            appendChunks(snapshot.maintenanceChunks(), world, areas);
        if (snapshot.hasBreeding() && snapshot.breedingDimension() == world.provider.dimensionId) appendBreeding(
            snapshot.breedingCenterX(),
            snapshot.breedingCropY(),
            snapshot.breedingCenterZ(),
            world,
            areas);
    }

    private static void appendChunks(List<int[]> chunks, World world, List<Area> areas) {
        for (var chunk : chunks) {
            double x = (double) chunk[BellTuning.IDX_X] * BellAreaOverlayTuning.CHUNK_SIZE;
            double z = (double) chunk[BellTuning.IDX_Z] * BellAreaOverlayTuning.CHUNK_SIZE;
            areas.add(
                new Area(
                    AxisAlignedBB.getBoundingBox(
                        x,
                        BellAreaOverlayTuning.CHUNK_BOTTOM_Y,
                        z,
                        x + BellAreaOverlayTuning.CHUNK_SIZE,
                        world.getHeight(),
                        z + BellAreaOverlayTuning.CHUNK_SIZE),
                    BellAreaOverlayTuning.MAINTENANCE_COLOR));
        }
    }

    private static void appendBreeding(int centerX, int cropY, int centerZ, World world, List<Area> areas) {
        for (int dx = -BellTuning.PLOT_RADIUS; dx <= BellTuning.PLOT_RADIUS; dx++) {
            for (int dz = -BellTuning.PLOT_RADIUS; dz <= BellTuning.PLOT_RADIUS; dz++) {
                int x = centerX + dx, z = centerZ + dz;
                double y = cropY + BellAreaOverlayTuning.OUTLINE_OFFSET;
                int color = BellAreaOverlayTuning.BREEDING_COLOR;
                if (world.getChunkProvider()
                    .chunkExists(x >> BellTuning.BLOCK_TO_CHUNK_SHIFT, z >> BellTuning.BLOCK_TO_CHUNK_SHIFT)
                    && CompatServices.cropsNhBreeding()
                        .inspectCropStickCell(world, x, cropY, z).kind == StickKind.CROSS_EMPTY)
                    color = BellAreaOverlayTuning.CROSS_COLOR;
                areas.add(
                    new Area(
                        AxisAlignedBB
                            .getBoundingBox(x, y, z, x + 1, y + BellAreaOverlayTuning.CELL_OUTLINE_HEIGHT, z + 1),
                        color));
            }
        }
    }

    private static final class Area {

        final AxisAlignedBB box;
        final int color;

        Area(AxisAlignedBB box, int color) {
            this.box = box;
            this.color = color;
        }
    }
}
