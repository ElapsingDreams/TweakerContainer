package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Set;

public class BlockHighlighterRender {
    private static final Set<BlockPos> HIGHLIGHTED_BLOCKS = XiheClient.HIGHLIGHTED_BLOCKS;

    public static void setup() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            var player = MinecraftClient.getInstance().player;
            if (player == null || !(player.getMainHandStack().getItem() == HighlightConfig.getTriggerItem())) {
                return;
            }

            if (HIGHLIGHTED_BLOCKS.isEmpty()) return;

            renderWireframeBoxes(context);
        });
    }

    private static void renderWireframeBoxes(WorldRenderContext context) {
        var camera = context.camera();
        var matrices = context.matrixStack();

        // 计算颜色
        int color = 0xFF0000; // 红色
        final int r = (color >> 16) & 0xFF;
        final int g = (color >> 8) & 0xFF;
        final int b = color & 0xFF;
        final int a = 255;

        // 获取缓冲区
        var bufferSource = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        var consumer = bufferSource.getBuffer(RenderLayer.getLines());

        matrices.push();

        // 循环渲染每个方块
        for (BlockPos pos : HIGHLIGHTED_BLOCKS) {
            renderWireframeBox(
                    camera.getPos(),
                    pos,
                    consumer,
                    matrices,
                    r, g, b, a
            );
        }

        bufferSource.draw();
        matrices.pop();
    }

    private static void renderWireframeBox(Vec3d cameraPos, BlockPos pos, VertexConsumer consumer, MatrixStack pose, int r, int g, int b, int a) {
        pose.push();

        // 计算方块中心坐标相对于相机的位置
        final double xOffset = pos.getX() + 0.5 - cameraPos.x;
        final double yOffset = pos.getY() + 0.5 - cameraPos.y;
        final double zOffset = pos.getZ() + 0.5 - cameraPos.z;
        pose.translate(xOffset, yOffset, zOffset);

        // 应用缩放
        pose.scale(0.5f, 0.5f, 0.5f);

        // 获取最终矩阵
        var matrix = pose.peek().getPositionMatrix();

        // 定义法线向量
        Vector3f normal = new Vector3f(0, 1, 0);

        // 绘制立方体的12条边
        // 底部
        addLine(consumer, matrix, -1, -1, -1, 1, -1, -1, r, g, b, a, normal);
        addLine(consumer, matrix, -1, -1, -1, -1, -1, 1, r, g, b, a, normal);
        addLine(consumer, matrix, 1, -1, -1, 1, -1, 1, r, g, b, a, normal);
        addLine(consumer, matrix, -1, -1, 1, 1, -1, 1, r, g, b, a, normal);

        // 顶部
        addLine(consumer, matrix, -1, 1, -1, 1, 1, -1, r, g, b, a, normal);
        addLine(consumer, matrix, -1, 1, -1, -1, 1, 1, r, g, b, a, normal);
        addLine(consumer, matrix, 1, 1, -1, 1, 1, 1, r, g, b, a, normal);
        addLine(consumer, matrix, -1, 1, 1, 1, 1, 1, r, g, b, a, normal);

        // 垂直边
        addLine(consumer, matrix, -1, -1, -1, -1, 1, -1, r, g, b, a, normal);
        addLine(consumer, matrix, 1, -1, -1, 1, 1, -1, r, g, b, a, normal);
        addLine(consumer, matrix, -1, -1, 1, -1, 1, 1, r, g, b, a, normal);
        addLine(consumer, matrix, 1, -1, 1, 1, 1, 1, r, g, b, a, normal);

        pose.pop();
    }

    private static void addLine(VertexConsumer consumer, Matrix4f matrix, float x1, float y1, float z1, float x2, float y2, float z2, int r, int g, int b, int a, Vector3f normal) {
        consumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z());
        consumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(normal.x(), normal.y(), normal.z());
    }
}