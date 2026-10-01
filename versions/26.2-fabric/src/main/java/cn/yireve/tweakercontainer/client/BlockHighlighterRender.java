package cn.yireve.tweakercontainer.client;

import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import cn.yireve.tweakercontainer.client.data.ProjectionSelectionMode;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Set;

/**
 * 高亮方块的线框渲染与事件注册，状态与仓储匹配逻辑见 {@link HighlightState}。
 * <p>
 * 绘制交给 malilib（Litematica 的底层库）：{@link RenderUtils#renderBlockOutline} 用的是
 * Litematica 描边同一套 pipeline（{@code DEBUG_LINES_MASA_SIMPLE_*}），透视与线宽都由它处理，
 * 本类只负责颜色、距离裁剪与调用。
 */
public final class BlockHighlighterRender {
    private static final Logger LOGGER = LogUtils.getLogger();

    // 线框相对方块表面的外扩量（格），避免与方块表面 z-fighting，同 Litematica 的 expand 参数
    private static final float EXPAND = 0.005F;
    // 线宽（像素），同 Litematica 的 renderBlockOutline 参数
    private static final float LINE_WIDTH = 1.0F;

    // 超出该距离（方块）的线框不再绘制
    private static final double MAX_RENDER_DISTANCE_SQ = 64.0D * 64.0D;

    private BlockHighlighterRender() {
    }

    public static void setup() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BlockHighlighterRender::onRender);
        ClientPlayConnectionEvents.DISCONNECT.register(BlockHighlighterRender::onDisconnect);
    }

    private static void onDisconnect(ClientPacketListener clientPlayNetworkHandler, Minecraft minecraftClient) {
        HighlightConfig.setEnabled(false);
        HighlightState.get().clearAll();
    }

    // 检查是否手持触发物品
    public static boolean isHoldingTriggerItem() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        return client.player.getMainHandItem().getItem() == HighlightConfig.getTriggerItem()
                || client.player.getOffhandItem().getItem() == HighlightConfig.getTriggerItem();
    }

    // 世界渲染回调
    private static void onRender(WorldRenderContext context) {
        if (!HighlightConfig.isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        HighlightState state = HighlightState.get();
        state.ensureUpToDate();

        boolean isHoldingTrigger = isHoldingTriggerItem();
        Vec3 cameraPos = context.camera().getPosition();
        boolean throughWalls = HighlightConfig.isSeeThrough();

        if (isHoldingTrigger) {
            Color4f storageColor = HighlightConfig.getStorageContainerColor();
            for (BlockPos storagePos : state.getStorageContainers()) {
                renderContainer(state, client.level, cameraPos, storagePos, storageColor, throughWalls,
                        state.getStorageContainers());
            }
        } else {
            Set<BlockPos> matching = state.getMatchingStorageContainers();
            Color4f matchingColor = HighlightConfig.getMatchingContainerColor();
            for (BlockPos matchingPos : matching) {
                renderContainer(state, client.level, cameraPos, matchingPos, matchingColor, throughWalls, matching);
            }
        }

        // 投影容器的绿框：手持触发物品时总是画，另外可以由配置要求一直显示
        if (isHoldingTrigger || HighlightConfig.isAlwaysShowProjection()) {
            renderProjectionContainer(state, cameraPos, throughWalls);

            // 选框只在角点模式下画：换了模式还留着旧框会很莫名
            if (HighlightConfig.getProjectionSelectionMode() == ProjectionSelectionMode.CORNER) {
                renderProjectionCorners(state, cameraPos, throughWalls);
            }
        }
    }

    /**
     * 角点框选的选框：只有一个角时画那一格，两个角都有时画整框。
     * <p>
     * 用的是投影容器同一个颜色——框选出来的东西也是投影容器，颜色一致才看得出对应关系。
     */
    private static void renderProjectionCorners(HighlightState state, Vec3 cameraPos, boolean throughWalls) {
        BlockPos start = state.getProjectionCornerStart();
        if (start == null) {
            return;
        }

        Color4f color = HighlightConfig.getProjectionContainerColor();
        BlockPos end = state.getProjectionCornerEnd();
        if (end == null) {
            renderOutline(cameraPos, start, color, throughWalls);
        } else {
            renderChestOutline(cameraPos, start, end, color, throughWalls);
        }
    }

    /** 画投影容器（多选，逐个画；大箱子合成整箱框）。 */
    private static void renderProjectionContainer(HighlightState state, Vec3 cameraPos, boolean throughWalls) {
        Color4f projectionColor = HighlightConfig.getProjectionContainerColor();

        for (BlockPos projection : state.getProjectionContainers()) {
            BlockPos projectionPartner = state.getProjectionContainerPartner(projection);

            if (projectionPartner != null) {
                // 两半之间共用的那条棱不画
                renderChestOutline(cameraPos, projection, projectionPartner, projectionColor, throughWalls);
            } else {
                renderOutline(cameraPos, projection, projectionColor, throughWalls);
            }
        }
    }

    /**
     * 大箱子由两半合并绘制时，另一半必须在同一次遍历的集合里，
     * 否则（例如黄框只遍历匹配到的那一半）会互相跳过导致整箱都不画。
     */
    private static void renderContainer(HighlightState state, ClientLevel world, Vec3 cameraPos,
                                        BlockPos pos, Color4f color, boolean throughWalls,
                                        Set<BlockPos> considered) {
        // 方块被撬掉或换掉后不再绘制，避免原地留下幽灵框
        if (!state.isStorageContainerPresent(pos, world)) {
            return;
        }

        BlockPos partner = state.getChestPartner(pos);
        if (partner == null || !considered.contains(partner)) {
            renderOutline(cameraPos, pos, color, throughWalls);
        } else if (partner.asLong() > pos.asLong()) {
            renderChestOutline(cameraPos, pos, partner, color, throughWalls);
        }
    }

    private static void renderOutline(Vec3 cameraPos, BlockPos pos, Color4f color, boolean throughWalls) {
        if (!isWithinRenderDistance(cameraPos, pos)) {
            return;
        }
        RenderUtils.renderBlockOutline(pos, EXPAND, LINE_WIDTH, color, throughWalls);
    }

    /**
     * 画大箱子两半合成的整体方框，两半之间共用的那条棱不会出现。
     * <p>
     * 走 malilib 自己的绘制路径（{@link RenderContext} + {@link RenderUtils#drawBoxAllEdgesBatchedLines}），
     * 与 {@link RenderUtils#renderBlockOutline} 同一套 pipeline，只是把范围换成两个坐标，因此透视开关照旧生效。
     */
    private static void renderChestOutline(Vec3 cameraPos, BlockPos pos1, BlockPos pos2,
                                           Color4f color, boolean throughWalls) {
        Vec3 center = Vec3.atCenterOf(pos1).add(Vec3.atCenterOf(pos2)).multiply(0.5D);
        if (cameraPos.distanceToSqr(center) > MAX_RENDER_DISTANCE_SQ) {
            return;
        }

        // malilib 的批处理顶点写入器输出的是相机相对坐标（renderBlockOutline 内部同样先减去相机位置），
        // 这里必须自己减，否则框会被画到别处、看上去像完全没画
        float x1 = (float) (Math.min(pos1.getX(), pos2.getX()) - EXPAND - cameraPos.x);
        float y1 = (float) (Math.min(pos1.getY(), pos2.getY()) - EXPAND - cameraPos.y);
        float z1 = (float) (Math.min(pos1.getZ(), pos2.getZ()) - EXPAND - cameraPos.z);
        float x2 = (float) (Math.max(pos1.getX(), pos2.getX()) + 1.0D + EXPAND - cameraPos.x);
        float y2 = (float) (Math.max(pos1.getY(), pos2.getY()) + 1.0D + EXPAND - cameraPos.y);
        float z2 = (float) (Math.max(pos1.getZ(), pos2.getZ()) + 1.0D + EXPAND - cameraPos.z);

        RenderPipeline pipeline = throughWalls
                ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL
                : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH;

        try (RenderContext renderContext = new RenderContext(() -> "tc_storage_box", pipeline)) {
            var builder = renderContext.getBuilder();
            RenderUtils.drawBoxAllEdgesBatchedLines(x1, y1, z1, x2, y2, z2, color, builder);

            renderContext.lineWidth(LINE_WIDTH);
            // draw() 内部会 build 再绘制，避免直接调用私有的 BufferBuilder.build()
            renderContext.draw();
        } catch (Exception e) {
            LOGGER.warn("绘制大箱子线框失败", e);
        }
    }

    private static boolean isWithinRenderDistance(Vec3 cameraPos, BlockPos pos) {
        return cameraPos.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_RENDER_DISTANCE_SQ;
    }
}
