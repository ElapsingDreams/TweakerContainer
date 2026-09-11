package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;

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

    private static void onDisconnect(ClientPlayNetworkHandler clientPlayNetworkHandler, MinecraftClient minecraftClient) {
        HighlightConfig.setEnabled(false);
        HighlightState.get().clearAll();
    }

    // 检查是否手持触发物品
    public static boolean isHoldingTriggerItem() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }
        return client.player.getMainHandStack().getItem() == HighlightConfig.getTriggerItem()
                || client.player.getOffHandStack().getItem() == HighlightConfig.getTriggerItem();
    }

    // 世界渲染回调
    private static void onRender(WorldRenderContext context) {
        if (!HighlightConfig.isEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        HighlightState state = HighlightState.get();
        state.ensureUpToDate();

        boolean isHoldingTrigger = isHoldingTriggerItem();
        Vec3d cameraPos = context.camera().getPos();
        boolean throughWalls = HighlightConfig.isSeeThrough();

        if (isHoldingTrigger) {
            Color4f storageColor = HighlightConfig.getStorageContainerColor();
            for (BlockPos storagePos : state.getStorageContainers()) {
                renderContainer(state, client.world, cameraPos, storagePos, storageColor, throughWalls);
            }

            BlockPos projectionContainer = state.getCurrentProjectionContainer();
            if (projectionContainer != null) {
                renderOutline(cameraPos, projectionContainer, HighlightConfig.getProjectionContainerColor(), throughWalls);
            }
        } else {
            Color4f matchingColor = HighlightConfig.getMatchingContainerColor();
            for (BlockPos matchingPos : state.getMatchingStorageContainers()) {
                renderContainer(state, client.world, cameraPos, matchingPos, matchingColor, throughWalls);
            }
        }
    }

    /**
     * 画一个已登记的仓储容器：大箱子与另一半合并成一个整体框，其余按单格画。
     * <p>
     * 合并时只由坐标较小的那一半画一次，避免两半各画一遍。
     */
    private static void renderContainer(HighlightState state, ClientWorld world, Vec3d cameraPos,
                                        BlockPos pos, Color4f color, boolean throughWalls) {
        // 方块被撬掉或换掉后不再绘制，避免原地留下幽灵框
        if (!state.isStorageContainerPresent(pos, world)) {
            return;
        }

        BlockPos partner = state.getChestPartner(pos);
        if (partner == null) {
            renderOutline(cameraPos, pos, color, throughWalls);
        } else if (partner.asLong() > pos.asLong()) {
            renderChestOutline(cameraPos, pos, partner, color, throughWalls);
        }
    }

    private static void renderOutline(Vec3d cameraPos, BlockPos pos, Color4f color, boolean throughWalls) {
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
    private static void renderChestOutline(Vec3d cameraPos, BlockPos pos1, BlockPos pos2,
                                           Color4f color, boolean throughWalls) {
        Vec3d center = Vec3d.ofCenter(pos1).add(Vec3d.ofCenter(pos2)).multiply(0.5D);
        if (cameraPos.squaredDistanceTo(center) > MAX_RENDER_DISTANCE_SQ) {
            return;
        }

        float x1 = Math.min(pos1.getX(), pos2.getX()) - EXPAND;
        float y1 = Math.min(pos1.getY(), pos2.getY()) - EXPAND;
        float z1 = Math.min(pos1.getZ(), pos2.getZ()) - EXPAND;
        float x2 = Math.max(pos1.getX(), pos2.getX()) + 1.0F + EXPAND;
        float y2 = Math.max(pos1.getY(), pos2.getY()) + 1.0F + EXPAND;
        float z2 = Math.max(pos1.getZ(), pos2.getZ()) + 1.0F + EXPAND;

        RenderPipeline pipeline = throughWalls
                ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL
                : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH;

        try (RenderContext renderContext = new RenderContext(() -> "xihe_storage_box", pipeline)) {
            var builder = renderContext.getBuilder();
            RenderUtils.drawBoxAllEdgesBatchedLines(x1, y1, z1, x2, y2, z2, color, builder);

            renderContext.lineWidth(LINE_WIDTH);
            // draw() 内部会 build 再绘制，避免直接调用私有的 BufferBuilder.build()
            renderContext.draw();
        } catch (Exception e) {
            LOGGER.warn("绘制大箱子线框失败", e);
        }
    }

    private static boolean isWithinRenderDistance(Vec3d cameraPos, BlockPos pos) {
        return cameraPos.squaredDistanceTo(Vec3d.ofCenter(pos)) <= MAX_RENDER_DISTANCE_SQ;
    }
}
