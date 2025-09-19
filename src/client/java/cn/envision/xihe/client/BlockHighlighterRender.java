package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.features.PlacementContainerAccess;
import cn.envision.xihe.client.utils.ContainerUtils;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.inventory.Inventory;
import net.minecraft.client.gl.UniformType;  // 新增：引入UniformType
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.*;

import static net.minecraft.client.render.RenderLayer.MultiPhaseParameters.*;


public class BlockHighlighterRender {
    // 基础常量
    private static final Set<BlockPos> HIGHLIGHTED_BLOCKS = new HashSet<>();
    private static final Set<BlockPos> STORAGE_CONTAINERS = new HashSet<>();
    private static BlockPos currentProjectionContainer = null;
    private static final List<ItemStack> currentMissingItems = new ArrayList<>();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_TAG = "[XiheContainerHighlight]";

    // 颜色常量（ARGB格式，1.21+需明确透明度）
    private static final int COLOR_PROJECTION = 0xFF00FF00; // 绿色（不透明）
    private static final int COLOR_STORAGE = 0xFF0000FF;    // 蓝色（不透明）
    private static final int COLOR_MATCHING = 0xFFFFFF00;   // 黄色（不透明）

    public static void setup() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BlockHighlighterRender::onRender);
        LOGGER.info("{} 容器高亮系统初始化完成", MOD_TAG);
    }

    // 补充缺失的isStorageContainer方法
    public static boolean isStorageContainer(BlockPos pos) {
        return STORAGE_CONTAINERS.contains(pos.toImmutable());
    }

    // 投影容器单例逻辑
    public static void setCurrentProjectionContainer(BlockPos newPos) {
        if (newPos == null) {
            LOGGER.warn("{} 尝试设置空的投影容器，操作忽略", MOD_TAG);
            return;
        }
        BlockPos immutableNewPos = newPos.toImmutable();

        if (currentProjectionContainer != null) {
            LOGGER.info("{} 移除旧投影容器：{}", MOD_TAG, currentProjectionContainer.toShortString());
            HIGHLIGHTED_BLOCKS.remove(currentProjectionContainer);
        }

        currentProjectionContainer = immutableNewPos;
        HIGHLIGHTED_BLOCKS.add(immutableNewPos);
        LOGGER.info("{} 刷新投影容器：{}", MOD_TAG, immutableNewPos.toShortString());

        if (!STORAGE_CONTAINERS.isEmpty()) {
            Set<BlockPos> matched = findMatchingStorageContainers(immutableNewPos);
            LOGGER.info("{} 新投影容器匹配到 {} 个含缺失物品的仓储容器", MOD_TAG, matched.size());
        } else {
            LOGGER.info("{} 新投影容器匹配跳过：无仓储容器可匹配", MOD_TAG);
        }
    }

    // 修正onRender方法
    private static void onRender(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            LOGGER.trace("{} 渲染跳过：玩家或世界未加载", MOD_TAG);
            return;
        }

        boolean isHoldingTrigger = isHoldingTriggerItem();
        Vec3d cameraPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        // 明确声明为VertexConsumerProvider.Immediate
        BufferBuilderStorage bufferBuilders = MinecraftClient.getInstance().getBufferBuilders();
        VertexConsumerProvider.Immediate immediate = bufferBuilders.getEntityVertexConsumers();

        // 手持时：绿框（投影）+ 蓝框（仓储）
        if (isHoldingTrigger) {
            if (currentProjectionContainer != null) {
                renderBlockWireframe(matrices, immediate, cameraPos, currentProjectionContainer, COLOR_PROJECTION, 2.5F);
                LOGGER.trace("{} 渲染绿框（投影容器）：{}", MOD_TAG, currentProjectionContainer.toShortString());
            }

            for (BlockPos storagePos : STORAGE_CONTAINERS) {
                renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_STORAGE, 2.0F);
            }
            LOGGER.trace("{} 渲染蓝框（仓储容器）：共 {} 个", MOD_TAG, STORAGE_CONTAINERS.size());
        }
        // 非手持时：仅黄框（含缺失物品的仓储）
        else {
            if (currentProjectionContainer != null && !STORAGE_CONTAINERS.isEmpty()) {
                Set<BlockPos> matchedStorage = findMatchingStorageContainers(currentProjectionContainer);
                for (BlockPos storagePos : matchedStorage) {
                    renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_MATCHING, 2.5F);
                }
                LOGGER.info("{} 渲染黄框（含缺失物品的仓储）：共 {} 个", MOD_TAG, matchedStorage.size());
            } else {
                LOGGER.trace("{} 黄框渲染跳过：投影容器{} 或 仓储容器{}",
                        MOD_TAG,
                        currentProjectionContainer == null ? "未设置" : "已设置",
                        STORAGE_CONTAINERS.isEmpty() ? "为空" : "不为空");
            }
        }

        immediate.draw(RenderLayer.LINES);
    }

    private static void renderBlockWireframe(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, Vec3d cameraPos, BlockPos pos, int color, float lineWidth) {
        RenderPipeline baseLinePipeline = RenderPipelines.LINES;

        RenderPipeline transparentPipeline = RenderPipeline.builder ()
                // 复用(((
                .withVertexShader (baseLinePipeline.getVertexShader ())
                // 复用(((
                .withFragmentShader (baseLinePipeline.getFragmentShader ())
                .withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL, VertexFormat.DrawMode.LINES)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .withoutBlend()
                .withLocation(Identifier.of("xihe", "block_highlighter"))
                .build();

        // 2. RenderPhase.LineWidth
        RenderPhase.LineWidth lineWidthPhase = new RenderPhase.LineWidth(OptionalDouble.of(lineWidth));

        // 3. MultiPhaseParameters
        RenderLayer.MultiPhaseParameters phaseParams = RenderLayer.MultiPhaseParameters.builder()
                .lineWidth(lineWidthPhase)
                .texture(RenderPhase.NO_TEXTURE)  //不需要纹理
                .lightmap(RenderPhase.DISABLE_LIGHTMAP)  // 禁用光照映射
                .overlay(RenderPhase.DISABLE_OVERLAY_COLOR)  // 禁用叠加层
                .layering(RenderPhase.NO_LAYERING)
                .target(RenderPhase.MAIN_TARGET)
                .texturing(RenderPhase.DEFAULT_TEXTURING)
                .build(RenderLayer.OutlineMode.NONE);


        // 构造渲染层RenderLayer.of(((((((((((((((((((
        // fuck mojang
        // MultiPhase
        RenderLayer renderLayer = RenderLayer.of(
                "xihe_block_highlighter",
                256,
                false,  // 线框不破碎
                false,  // 非半透明
                transparentPipeline,
                phaseParams
        );


        VertexConsumer consumer = immediate.getBuffer(renderLayer);

        // 平移矩阵
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // 解析颜色
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = ((color >> 24) & 0xFF) / 255.0F;

        // 绘制线框
        drawBlockEdges(matrices.peek().getPositionMatrix(), consumer, pos, r, g, b, a);

        matrices.pop();
    }

    // 修复VertexConsumer方法调用
    private static void drawBlockEdges(Matrix4f matrix, VertexConsumer consumer, BlockPos pos, float r, float g, float b, float a) {
        float x = pos.getX();
        float y = pos.getY();
        float z = pos.getZ();
        float x2 = x + 1;
        float y2 = y + 1;
        float z2 = z + 1;

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

    // 修复：用next()替代endVertex()
    private static void drawLine(Matrix4f matrix, VertexConsumer consumer, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        // 起点：位置 + 颜色，用next()提交
        consumer.vertex(matrix, x1, y1, z1)
                .color(r, g, b, a)
                .normal(0, 0, 0);

        // 终点：位置 + 颜色，用next()提交
        consumer.vertex(matrix, x2, y2, z2)
                .color(r, g, b, a)
                .normal(0, 0, 0);
    }

    // 其他辅助方法
    private static boolean isHoldingTriggerItem() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;
        return client.player.getMainHandStack().getItem() == HighlightConfig.getTriggerItem() ||
                client.player.getOffHandStack().getItem() == HighlightConfig.getTriggerItem();
    }

    private static void logMissingItems(List<ItemStack> missingItems) {
        if (missingItems.isEmpty()) {
            LOGGER.info("{} 投影容器无缺失物品", MOD_TAG);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : missingItems) {
            sb.append(stack.getCount()).append("x").append(stack.getItem().getName().getString()).append(", ");
        }
        LOGGER.info("{} 投影容器缺失物品：{}", MOD_TAG, sb.substring(0, sb.length() - 2));
    }

    public static Set<BlockPos> findMatchingStorageContainers(BlockPos projectionPos) {
        Set<BlockPos> matches = new HashSet<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            LOGGER.warn("{} 匹配跳过：世界未加载", MOD_TAG);
            return matches;
        }

        Optional<SimpleInventory> projectionInventory = PlacementContainerAccess.getSchematicInventory(
                projectionPos, client.world.getBlockState(projectionPos));
        if (projectionInventory.isEmpty()) {
            LOGGER.warn("{} 无法获取投影容器[{}]的库存配置", MOD_TAG, projectionPos.toShortString());
            return matches;
        }

        List<ItemStack> missingItems = findMissingItems(projectionPos, projectionInventory.get());
        currentMissingItems.clear();
        currentMissingItems.addAll(missingItems);
        logMissingItems(missingItems);

        if (missingItems.isEmpty()) {
            return matches;
        }

        int checkedCount = 0;
        for (BlockPos storagePos : STORAGE_CONTAINERS) {
            checkedCount++;
            Optional<Inventory> storageInv = ContainerUtils.validateContainer(
                    client.world, storagePos, client.world.getBlockState(storagePos));

            if (storageInv.isEmpty()) {
                LOGGER.trace("{} 仓储容器[{}]无效（无法读取库存）", MOD_TAG, storagePos.toShortString());
                continue;
            }

            if (hasAllMissingItems(storageInv.get(), missingItems)) {
                matches.add(storagePos);
                LOGGER.trace("{} 仓储容器[{}]匹配成功", MOD_TAG, storagePos.toShortString());
            }
        }

        LOGGER.info("{} 匹配完成：共检查 {} 个仓储容器，匹配到 {} 个",
                MOD_TAG, checkedCount, matches.size());
        return matches;
    }

    private static boolean hasAllMissingItems(Inventory storageInv, List<ItemStack> missingItems) {
        for (ItemStack needed : missingItems) {
            boolean found = false;
            for (int i = 0; i < storageInv.size(); i++) {
                ItemStack stack = storageInv.getStack(i);
                if (ItemStack.areItemsEqual(stack, needed) && stack.getCount() >= needed.getCount()) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    public static List<ItemStack> findMissingItems(BlockPos projectionPos, SimpleInventory required) {
        List<ItemStack> missing = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return missing;

        Optional<Inventory> actual = ContainerUtils.validateContainer(
                client.world, projectionPos, client.world.getBlockState(projectionPos));
        if (actual.isEmpty()) return missing;

        for (int i = 0; i < required.size(); i++) {
            ItemStack reqStack = required.getStack(i);
            if (reqStack.isEmpty()) continue;

            boolean found = false;
            for (int j = 0; j < actual.get().size(); j++) {
                ItemStack actStack = actual.get().getStack(j);
                if (ItemStack.areItemsEqual(actStack, reqStack) && actStack.getCount() >= reqStack.getCount()) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                missing.add(reqStack.copy());
            }
        }

        return missing;
    }

    // 容器管理方法
    public static void addStorageContainer(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        if (STORAGE_CONTAINERS.add(immutablePos)) {
            LOGGER.info("{} 添加仓储容器：{}", MOD_TAG, immutablePos.toShortString());
        } else {
            LOGGER.trace("{} 仓储容器[{}]已存在", MOD_TAG, immutablePos.toShortString());
        }
    }

    public static void removeStorageContainer(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        if (STORAGE_CONTAINERS.remove(immutablePos)) {
            LOGGER.info("{} 移除仓储容器：{}", MOD_TAG, immutablePos.toShortString());
        } else {
            LOGGER.trace("{} 仓储容器[{}]不存在", MOD_TAG, immutablePos.toShortString());
        }
    }

    public static void clearAll() {
        LOGGER.info("{} 清除所有标记：投影容器={} | 仓储容器数={}",
                MOD_TAG,
                currentProjectionContainer != null ? currentProjectionContainer.toShortString() : "无",
                STORAGE_CONTAINERS.size());

        HIGHLIGHTED_BLOCKS.clear();
        STORAGE_CONTAINERS.clear();
        currentProjectionContainer = null;
        currentMissingItems.clear();
    }

    // Getter方法
    public static List<ItemStack> getCurrentMissingItems() {
        return new ArrayList<>(currentMissingItems);
    }

    public static BlockPos getCurrentProjectionContainer() {
        return currentProjectionContainer;
    }

    public static Set<BlockPos> getStorageContainers() {
        return Collections.unmodifiableSet(STORAGE_CONTAINERS);
    }
}
