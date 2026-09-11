package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * 高亮方块的线框渲染与事件注册，状态与仓储匹配逻辑见 {@link HighlightState}。
 * <p>
 * 线框粗细用世界空间几何表示（每条棱一个细长方体），不用 {@code RenderPhase.LineWidth}：
 * GL 线宽的单位是屏幕像素、不随距离衰减，远距离会把方块糊成一坨。
 */
public final class BlockHighlighterRender {
    // 颜色常量（ARGB格式）
    private static final int COLOR_PROJECTION = 0xFF00FF00; // 绿色
    private static final int COLOR_STORAGE = 0xFF0000FF;    // 蓝色
    private static final int COLOR_MATCHING = 0xFFFFFF00;   // 黄色
    private static final int COLOR_MANUAL = 0xFFFF00FF;     // 品红（/highlightblock 手动标记）

    // 线框粗细（世界单位，1.0 = 一个方块边长）。4 格外约相当于 2px / 3px
    private static final float THICKNESS_NORMAL = 0.01F;
    private static final float THICKNESS_EMPHASIS = 0.016F;
    // 线框相对方块表面的外扩量，避免与方块表面 z-fighting
    private static final float EXPAND = 0.004F;

    // 超出该距离（方块）的线框不再绘制
    private static final double MAX_RENDER_DISTANCE_SQ = 64.0D * 64.0D;

    private static volatile RenderPipeline blockHighlightPipeline;
    private static volatile RenderLayer blockHighlightLayer;

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

    /**
     * 深度测试等渲染配置变更后，丢弃已缓存的 pipeline 与 RenderLayer。
     */
    public static void reloadLayers() {
        synchronized (BlockHighlighterRender.class) {
            blockHighlightLayer = null;
            blockHighlightPipeline = null;
        }
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
        MatrixStack matrices = context.matrixStack();
        BufferBuilderStorage bufferBuilders = client.getBufferBuilders();
        VertexConsumerProvider.Immediate immediate = bufferBuilders.getEntityVertexConsumers();

        for (BlockPos highlightPos : state.getHighlightedBlocks()) {
            if (isWithinRenderDistance(cameraPos, highlightPos)) {
                renderBlockWireframe(matrices, immediate, cameraPos, highlightPos, COLOR_MANUAL, THICKNESS_NORMAL);
            }
        }

        if (isHoldingTrigger) {
            for (BlockPos storagePos : state.getStorageContainers()) {
                if (isWithinRenderDistance(cameraPos, storagePos)) {
                    renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_STORAGE, THICKNESS_NORMAL);
                }
            }
            BlockPos projectionContainer = state.getCurrentProjectionContainer();
            if (projectionContainer != null) {
                renderBlockWireframe(matrices, immediate, cameraPos, projectionContainer, COLOR_PROJECTION, THICKNESS_EMPHASIS);
            }
        } else {
            for (BlockPos matchingPos : state.getMatchingStorageContainers()) {
                if (isWithinRenderDistance(cameraPos, matchingPos)) {
                    renderBlockWireframe(matrices, immediate, cameraPos, matchingPos, COLOR_MATCHING, THICKNESS_EMPHASIS);
                }
            }
        }

        immediate.draw(getHighlightLayer());
    }

    private static boolean isWithinRenderDistance(Vec3d cameraPos, BlockPos pos) {
        return cameraPos.squaredDistanceTo(Vec3d.ofCenter(pos)) <= MAX_RENDER_DISTANCE_SQ;
    }

    private static RenderLayer getHighlightLayer() {
        RenderLayer layer = blockHighlightLayer;
        if (layer != null) {
            return layer;
        }
        synchronized (BlockHighlighterRender.class) {
            if (blockHighlightLayer == null) {
                blockHighlightLayer = createHighlightLayer();
            }
            return blockHighlightLayer;
        }
    }

    /**
     * 顶点格式、shader 与 uniform 都照搬原版 {@code POSITION_COLOR_SNIPPET}（即 pipeline/debug_quads 的基础），
     * 只把深度测试换成可切换的开关。
     */
    private static RenderLayer createHighlightLayer() {
        if (blockHighlightPipeline == null) {
            blockHighlightPipeline = RenderPipeline.builder()
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    // NO_DEPTH_TEST 的线框会穿透方块，默认关闭，用 /highlightblock depth 切换
                    .withDepthTestFunction(HighlightConfig.isSeeThrough()
                            ? DepthTestFunction.NO_DEPTH_TEST
                            : DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withoutBlend()
                    .withLocation(Identifier.of("xihe", "block_highlighter"))
                    .build();
        }

        RenderLayer.MultiPhaseParameters phaseParams = RenderLayer.MultiPhaseParameters.builder()
                .texture(RenderPhase.NO_TEXTURE)
                .lightmap(RenderPhase.DISABLE_LIGHTMAP)
                .overlay(RenderPhase.DISABLE_OVERLAY_COLOR)
                .layering(RenderPhase.NO_LAYERING)
                .target(RenderPhase.MAIN_TARGET)
                .texturing(RenderPhase.DEFAULT_TEXTURING)
                .build(RenderLayer.OutlineMode.NONE);

        return RenderLayer.of(
                "xihe_block_highlighter",
                1536,
                false,
                false,
                blockHighlightPipeline,
                phaseParams
        );
    }

    // 渲染方块线框
    private static void renderBlockWireframe(MatrixStack matrices, VertexConsumerProvider.Immediate immediate,
                                            Vec3d cameraPos, BlockPos pos, int color, float thickness) {
        VertexConsumer consumer = immediate.getBuffer(getHighlightLayer());

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = ((color >> 24) & 0xFF) / 255.0F;

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        drawBlockEdges(matrix, consumer, pos, thickness, r, g, b, a);
        drawBlockSurface(matrix, consumer, pos, thickness, r, g, b, a);
        matrices.pop();
    }

    /**
     * 12 条棱：每条是一个粗细为 {@code t} 的长方体，沿棱的轴向两端各多出 {@code t}，让 8 个拐角自然补齐。
     */
    private static void drawBlockEdges(Matrix4f matrix, VertexConsumer consumer, BlockPos pos, float t,
                                       float r, float g, float b, float a) {
        float x0 = pos.getX() - EXPAND;
        float y0 = pos.getY() - EXPAND;
        float z0 = pos.getZ() - EXPAND;
        float x1 = x0 + 1.0F + 2 * EXPAND;
        float y1 = y0 + 1.0F + 2 * EXPAND;
        float z1 = z0 + 1.0F + 2 * EXPAND;

        // 沿 X 的 4 条棱，Y / Z 方向朝外
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                float yLo = dy == 0 ? y0 - t : y1;
                float yHi = dy == 0 ? y0 : y1 + t;
                float zLo = dz == 0 ? z0 - t : z1;
                float zHi = dz == 0 ? z0 : z1 + t;
                drawBox(matrix, consumer, x0 - t, yLo, zLo, x1 + t, yHi, zHi, r, g, b, a);
            }
        }

        // 沿 Y 的 4 条棱，X / Z 方向朝外
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                float xLo = dx == 0 ? x0 - t : x1;
                float xHi = dx == 0 ? x0 : x1 + t;
                float zLo = dz == 0 ? z0 - t : z1;
                float zHi = dz == 0 ? z0 : z1 + t;
                drawBox(matrix, consumer, xLo, y0 - t, zLo, xHi, y1 + t, zHi, r, g, b, a);
            }
        }

        // 沿 Z 的 4 条棱，X / Y 方向朝外
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                float xLo = dx == 0 ? x0 - t : x1;
                float xHi = dx == 0 ? x0 : x1 + t;
                float yLo = dy == 0 ? y0 - t : y1;
                float yHi = dy == 0 ? y0 : y1 + t;
                drawBox(matrix, consumer, xLo, yLo, z0 - t, xHi, yHi, z1 + t, r, g, b, a);
            }
        }
    }

    /**
     * 六个面中心的十字标记，同样用世界空间几何（横竖各一条细长方体，位于线框外侧平面上）。
     */
    private static void drawBlockSurface(Matrix4f matrix, VertexConsumer consumer, BlockPos pos, float t,
                                         float r, float g, float b, float a) {
        float h = t * 0.5F;
        float x0 = pos.getX() - EXPAND;
        float y0 = pos.getY() - EXPAND;
        float z0 = pos.getZ() - EXPAND;
        float x1 = x0 + 1.0F + 2 * EXPAND;
        float y1 = y0 + 1.0F + 2 * EXPAND;
        float z1 = z0 + 1.0F + 2 * EXPAND;

        float cx = (x0 + x1) * 0.5F;
        float cy = (y0 + y1) * 0.5F;
        float cz = (z0 + z1) * 0.5F;
        // 外侧面所在的平面，x/y/z 的 o(ut) 与 p(lus) 两侧
        float xo = x0 - t;
        float xp = x1 + t;
        float yo = y0 - t;
        float yp = y1 + t;
        float zo = z0 - t;
        float zp = z1 + t;

        // -Z / +Z 面
        drawBox(matrix, consumer, xo, cy - h, zo, xp, cy + h, z0, r, g, b, a);
        drawBox(matrix, consumer, cx - h, yo, zo, cx + h, yp, z0, r, g, b, a);
        drawBox(matrix, consumer, xo, cy - h, z1, xp, cy + h, zp, r, g, b, a);
        drawBox(matrix, consumer, cx - h, yo, z1, cx + h, yp, zp, r, g, b, a);

        // -X / +X 面
        drawBox(matrix, consumer, xo, cy - h, zo, x0, cy + h, zp, r, g, b, a);
        drawBox(matrix, consumer, xo, yo, cz - h, x0, yp, cz + h, r, g, b, a);
        drawBox(matrix, consumer, x1, cy - h, zo, xp, cy + h, zp, r, g, b, a);
        drawBox(matrix, consumer, x1, yo, cz - h, xp, yp, cz + h, r, g, b, a);

        // -Y / +Y 面
        drawBox(matrix, consumer, xo, yo, cz - h, xp, y0, cz + h, r, g, b, a);
        drawBox(matrix, consumer, cx - h, yo, zo, cx + h, y0, zp, r, g, b, a);
        drawBox(matrix, consumer, xo, y1, cz - h, xp, yp, cz + h, r, g, b, a);
        drawBox(matrix, consumer, cx - h, y1, zo, cx + h, yp, zp, r, g, b, a);
    }

    // 绘制一个轴对齐长方体的 6 个面
    private static void drawBox(Matrix4f matrix, VertexConsumer consumer,
                                float x0, float y0, float z0, float x1, float y1, float z1,
                                float r, float g, float b, float a) {
        // -Z / +Z
        drawQuad(matrix, consumer, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b, a);
        drawQuad(matrix, consumer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
        // -X / +X
        drawQuad(matrix, consumer, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
        drawQuad(matrix, consumer, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        // -Y / +Y
        drawQuad(matrix, consumer, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, r, g, b, a);
        drawQuad(matrix, consumer, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
    }

    // 绘制一个四边形（4 个顶点一组，DrawMode.QUADS）
    private static void drawQuad(Matrix4f matrix, VertexConsumer consumer,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float x4, float y4, float z4,
                                 float r, float g, float b, float a) {
        consumer.vertex(matrix, x1, y1, z1).color(r, g, b, a);
        consumer.vertex(matrix, x2, y2, z2).color(r, g, b, a);
        consumer.vertex(matrix, x3, y3, z3).color(r, g, b, a);
        consumer.vertex(matrix, x4, y4, z4).color(r, g, b, a);
    }
}
