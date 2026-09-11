package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高亮方块的线框渲染与事件注册，状态与仓储匹配逻辑见 {@link HighlightState}。
 */
public final class BlockHighlighterRender {
    // 颜色常量（ARGB格式）
    private static final int COLOR_PROJECTION = 0xFF00FF00; // 绿色
    private static final int COLOR_STORAGE = 0xFF0000FF;    // 蓝色
    private static final int COLOR_MATCHING = 0xFFFFFF00;   // 黄色
    private static final int COLOR_MANUAL = 0xFFFF00FF;     // 品红（/highlightblock 手动标记）

    private static final float LINE_WIDTH_NORMAL = 3.0F;
    private static final float LINE_WIDTH_EMPHASIS = 3.5F;

    // 超出该距离（方块）的线框不再绘制
    private static final double MAX_RENDER_DISTANCE_SQ = 64.0D * 64.0D;

    private static volatile RenderPipeline blockHighlightPipeline;
    // 每种线宽一个 RenderLayer，线宽参数才能真正生效
    private static final Map<Float, RenderLayer> HIGHLIGHT_LAYERS = new ConcurrentHashMap<>();

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
        HIGHLIGHT_LAYERS.clear();
        blockHighlightPipeline = null;
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
                renderBlockWireframe(matrices, immediate, cameraPos, highlightPos, COLOR_MANUAL, LINE_WIDTH_NORMAL);
            }
        }

        if (isHoldingTrigger) {
            for (BlockPos storagePos : state.getStorageContainers()) {
                if (isWithinRenderDistance(cameraPos, storagePos)) {
                    renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_STORAGE, LINE_WIDTH_NORMAL);
                }
            }
            BlockPos projectionContainer = state.getCurrentProjectionContainer();
            if (projectionContainer != null) {
                renderBlockWireframe(matrices, immediate, cameraPos, projectionContainer, COLOR_PROJECTION, LINE_WIDTH_EMPHASIS);
            }
        } else {
            for (BlockPos matchingPos : state.getMatchingStorageContainers()) {
                if (isWithinRenderDistance(cameraPos, matchingPos)) {
                    renderBlockWireframe(matrices, immediate, cameraPos, matchingPos, COLOR_MATCHING, LINE_WIDTH_EMPHASIS);
                }
            }
        }

        immediate.draw(RenderLayer.LINES);
    }

    private static boolean isWithinRenderDistance(Vec3d cameraPos, BlockPos pos) {
        return cameraPos.squaredDistanceTo(Vec3d.ofCenter(pos)) <= MAX_RENDER_DISTANCE_SQ;
    }

    private static RenderLayer getHighlightLayer(float lineWidth) {
        return HIGHLIGHT_LAYERS.computeIfAbsent(lineWidth, BlockHighlighterRender::createHighlightLayer);
    }

    private static RenderLayer createHighlightLayer(float lineWidth) {
        if (blockHighlightPipeline == null) {
            RenderPipeline baseLinePipeline = RenderPipelines.LINES;
            blockHighlightPipeline = RenderPipeline.builder()
                    .withVertexShader(baseLinePipeline.getVertexShader())
                    .withFragmentShader(baseLinePipeline.getFragmentShader())
                    .withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL, VertexFormat.DrawMode.LINES)
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
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

        RenderPhase.LineWidth lineWidthPhase = new RenderPhase.LineWidth(OptionalDouble.of(lineWidth));
        RenderLayer.MultiPhaseParameters phaseParams = RenderLayer.MultiPhaseParameters.builder()
                .lineWidth(lineWidthPhase)
                .texture(RenderPhase.NO_TEXTURE)
                .lightmap(RenderPhase.DISABLE_LIGHTMAP)
                .overlay(RenderPhase.DISABLE_OVERLAY_COLOR)
                .layering(RenderPhase.NO_LAYERING)
                .target(RenderPhase.MAIN_TARGET)
                .texturing(RenderPhase.DEFAULT_TEXTURING)
                .build(RenderLayer.OutlineMode.NONE);

        return RenderLayer.of(
                "xihe_block_highlighter_" + lineWidth,
                256,
                false,
                false,
                blockHighlightPipeline,
                phaseParams
        );
    }

    // 渲染方块线框
    private static void renderBlockWireframe(MatrixStack matrices, VertexConsumerProvider.Immediate immediate,
                                             Vec3d cameraPos, BlockPos pos, int color, float lineWidth) {
        VertexConsumer consumer = immediate.getBuffer(getHighlightLayer(lineWidth));

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = ((color >> 24) & 0xFF) / 255.0F;

        drawBlockEdges(matrices.peek().getPositionMatrix(), consumer, pos, r, g, b, a);
        drawBlockSurface(matrices.peek().getPositionMatrix(), consumer, pos, r, g, b, a);
        matrices.pop();
    }

    // 绘制方块边缘
    private static void drawBlockEdges(Matrix4f matrix, VertexConsumer consumer, BlockPos pos,
                                       float r, float g, float b, float a) {
        float w = 0.005F;
        float x = pos.getX() - w;
        float y = pos.getY() - w;
        float z = pos.getZ() - w;
        float x2 = x + 1 + 2 * w;
        float y2 = y + 1 + 2 * w;
        float z2 = z + 1 + 2 * w;

        // 前面
        drawLine(matrix, consumer, x, y, z, x2, y, z, r, g, b, a);
        drawLine(matrix, consumer, x2, y, z, x2, y2, z, r, g, b, a);
        drawLine(matrix, consumer, x2, y2, z, x, y2, z, r, g, b, a);
        drawLine(matrix, consumer, x, y2, z, x, y, z, r, g, b, a);

        // 后面
        drawLine(matrix, consumer, x, y, z2, x2, y, z2, r, g, b, a);
        drawLine(matrix, consumer, x2, y, z2, x2, y2, z2, r, g, b, a);
        drawLine(matrix, consumer, x2, y2, z2, x, y2, z2, r, g, b, a);
        drawLine(matrix, consumer, x, y2, z2, x, y, z2, r, g, b, a);

        // 连接前后
        drawLine(matrix, consumer, x, y, z, x, y, z2, r, g, b, a);
        drawLine(matrix, consumer, x2, y, z, x2, y, z2, r, g, b, a);
        drawLine(matrix, consumer, x2, y2, z, x2, y2, z2, r, g, b, a);
        drawLine(matrix, consumer, x, y2, z, x, y2, z2, r, g, b, a);
    }

    private static void drawBlockSurface(Matrix4f matrix, VertexConsumer consumer, BlockPos pos,
                                       float r, float g, float b, float a) {
        float w = 0.005F;
        float x = pos.getX() - w;
        float y = pos.getY() - w;
        float z = pos.getZ() - w;
        float x2 = x + 1 + 2 * w;
        float y2 = y + 1 + 2 * w;
        float z2 = z + 1 + 2 * w;

        float cx = x + 0.5f + w;
        float cy = y + 0.5f + w;
        float cz = z + 0.5f + w;

        // 前
        drawLine(matrix, consumer, x, cy, z, x2, cy, z, r, g, b, a);
        drawLine(matrix, consumer, cx, y, z, cx, y2, z, r, g, b, a);

        // 后
        drawLine(matrix, consumer, x, cy, z2, x2, cy, z2, r, g, b, a);
        drawLine(matrix, consumer, cx, y, z2, cx, y2, z2, r, g, b, a);

        // 左
        drawLine(matrix, consumer, x, cy, z, x, cy, z2, r, g, b, a);
        drawLine(matrix, consumer, x, y, cz, x, y2, cz, r, g, b, a);

        // 右
        drawLine(matrix, consumer, x2, cy, z, x2, cy, z2, r, g, b, a);
        drawLine(matrix, consumer, x2, y, cz, x2, y2, cz, r, g, b, a);

        // 下
        drawLine(matrix, consumer, x, y, cz, x2, y, cz, r, g, b, a);
        drawLine(matrix, consumer, cx, y, z, cx, y, z2, r, g, b, a);

        // 上
        drawLine(matrix, consumer, x, y2, cz, x2, y2, cz, r, g, b, a);
        drawLine(matrix, consumer, cx, y2, z, cx, y2, z2, r, g, b, a);
    }

    // 绘制单条线
    private static void drawLine(Matrix4f matrix, VertexConsumer consumer,
                                 float x1, float y1, float z1, float x2, float y2, float z2,
                                 float r, float g, float b, float a) {
        consumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(0, 1, 0);
        consumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(0, 1, 0);
    }
}
