package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.features.PlacementContainerAccess;
import cn.envision.xihe.client.utils.ContainerUtils;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.util.WorldUtils;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ScreenHandlerProvider;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class BlockHighlighterRender {
    // 基础常量
    private static final Set<BlockPos> TEMP_HIGHLIGHTED_BLOCKS = new HashSet<>();
    private static final Set<BlockPos> HIGHLIGHTED_BLOCKS = new HashSet<>();
    private static final Set<BlockPos> STORAGE_CONTAINERS = new HashSet<>();
    private static final Queue<ContainerInteractionHandler> interactionQueue = new LinkedList<>();
    private static final Map<BlockPos, Inventory> STORAGE_CONTAINER_CACHE = new HashMap<>();
    private static BlockPos currentProjectionContainer = null;
    private static final List<ItemStack> currentMissingItems = new ArrayList<>();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_TAG = "[XiheContainerHighlight]";

    // 颜色常量（ARGB格式）
    private static final int COLOR_PROJECTION = 0xFF00FF00; // 绿色
    private static final int COLOR_STORAGE = 0xFF0000FF;    // 蓝色
    private static final int COLOR_MATCHING = 0xFFFFFF00;   // 黄色
    private static BlockPos tempProcessingPos = null;
    public static void setup() {
        // 注册世界渲染事件
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BlockHighlighterRender::onRender);
        // LOGGER.info("{} 容器高亮系统初始化完成", MOD_TAG);
    }
    // 新增：设置临时处理坐标
    public static void setTempProcessingPos(BlockPos pos) {
        tempProcessingPos = pos != null ? pos.toImmutable() : null;
    }

    // 新增：获取并清空临时处理坐标（读取后自动清除）
    public static BlockPos getAndClearTempProcessingPos() {
        BlockPos pos = tempProcessingPos;
        tempProcessingPos = null; // 读取后立即清空，防止被其他界面处理
        return pos;
    }
    // 处理容器点击事件

    public static void addTempHighlightedBlock(BlockPos pos) {
        if (pos != null) {
            TEMP_HIGHLIGHTED_BLOCKS.add(pos.toImmutable());
        }
    }

    public static void clearTempHighlightedBlocks() {
        TEMP_HIGHLIGHTED_BLOCKS.clear();
    }

    public static Set<BlockPos> getTempHighlightedBlocks() {
        return TEMP_HIGHLIGHTED_BLOCKS;
    }
    // 检查是否为仓储容器
    public static boolean isStorageContainer(BlockPos pos) {
        return STORAGE_CONTAINERS.contains(pos.toImmutable());
    }

    // 设置投影容器
    public static void setCurrentProjectionContainer(BlockPos newPos) {
        if (newPos == null) {
            LOGGER.warn("{} 尝试设置空的投影容器，操作忽略", MOD_TAG);
            return;
        }

        BlockPos immutableNewPos = newPos.toImmutable();
        World world = MinecraftClient.getInstance().world;
        if (world == null) return;

        BlockState state = world.getBlockState(immutableNewPos);
        Optional<SimpleInventory> schematicInv = PlacementContainerAccess.getSchematicInventory(immutableNewPos, state);

        if (schematicInv.isEmpty() || isInventoryEmpty(schematicInv.get())) {
            LOGGER.warn("{} 投影容器[{}]为空或无法读取", MOD_TAG, immutableNewPos.toShortString());
            return;
        }

        if (currentProjectionContainer != null) {
            HIGHLIGHTED_BLOCKS.remove(currentProjectionContainer);
        }
        currentProjectionContainer = immutableNewPos;
        HIGHLIGHTED_BLOCKS.add(immutableNewPos);
        LOGGER.info("{} 已设置投影容器：{}", MOD_TAG, immutableNewPos.toShortString());
        logInventoryContent("投影容器", immutableNewPos, schematicInv.get());

        // 重新计算缺失物品
        currentMissingItems.clear();
        currentMissingItems.addAll(findMissingItems(immutableNewPos, schematicInv.get()));
    }

    // 检查库存是否为空
    private static boolean isInventoryEmpty(Inventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            if (!inventory.getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // 获取缓存的仓储容器
    public static Inventory getCachedStorageInventory(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        return STORAGE_CONTAINER_CACHE.getOrDefault(immutablePos, null);
    }

    // 添加仓储容器
    public static void addStorageContainer(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        STORAGE_CONTAINERS.add(immutablePos);

        World world = MinecraftClient.getInstance().world;
        if (world != null) {
            BlockState state = world.getBlockState(immutablePos);

        }
    }

    // 移除仓储容器
    public static void removeStorageContainer(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        STORAGE_CONTAINERS.remove(immutablePos);
        STORAGE_CONTAINER_CACHE.remove(immutablePos);
        LOGGER.debug("{} 移除仓储容器及缓存：{}", MOD_TAG, immutablePos.toShortString());
    }
    public static void removeTempHighlightedBlock(BlockPos pos) {
        if (pos != null) {
            TEMP_HIGHLIGHTED_BLOCKS.remove(pos.toImmutable());
        }
    }
    // 清除所有数据
    public static void clearAll() {
        STORAGE_CONTAINERS.clear();
        STORAGE_CONTAINER_CACHE.clear();
        HIGHLIGHTED_BLOCKS.clear();
        currentProjectionContainer = null;
        currentMissingItems.clear();
        interactionQueue.clear();
        LOGGER.info("{} 清除所有仓储容器及缓存", MOD_TAG);
    }

    // 世界渲染回调
    private static void onRender(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        boolean isHoldingTrigger = isHoldingTriggerItem();
        Vec3d cameraPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        BufferBuilderStorage bufferBuilders = MinecraftClient.getInstance().getBufferBuilders();
        VertexConsumerProvider.Immediate immediate = bufferBuilders.getEntityVertexConsumers();

        if (isHoldingTrigger) {
            // 渲染投影容器（绿色）
            if (currentProjectionContainer != null) {
                renderBlockWireframe(matrices, immediate, cameraPos, currentProjectionContainer, COLOR_PROJECTION, 2.5F);
            }
            // 渲染仓储容器（蓝色）
            for (BlockPos storagePos : STORAGE_CONTAINERS) {
                renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_STORAGE, 2.0F);
            }
        } else {
            // 渲染匹配的仓储容器（黄色）
            /*if (currentProjectionContainer != null && !STORAGE_CONTAINERS.isEmpty()) {
                Set<BlockPos> matchedStorage = findMatchingStorageContainers(currentProjectionContainer);
                for (BlockPos storagePos : matchedStorage) {
                    renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_MATCHING, 2.5F);
                }
            }*/
            for (BlockPos storagePos : getTempHighlightedBlocks()) {
                if(storagePos != currentProjectionContainer)
                    renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_MATCHING, 2.5F);
            }
        }

        immediate.draw(RenderLayer.LINES);
    }
    public static BlockPos getCurrentProjectionContainer() {
        return currentProjectionContainer;
    }
    // 日志输出库存内容
    private static void logInventoryContent(String containerType, BlockPos pos, Inventory inventory) {
        List<String> itemList = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getItem().getName(stack).getString();
                itemList.add(String.format("%s x%d", itemName, stack.getCount()));
            }
        }
        if (itemList.isEmpty()) {
            LOGGER.info("{} {}[{}] 内容为空 (类型: {})",
                    MOD_TAG, containerType, pos.toShortString(), inventory.getClass().getSimpleName());
        } else {
            LOGGER.info("{} {}[{}] 内容: {} (类型: {})",
                    MOD_TAG, containerType, pos.toShortString(),
                    String.join(", ", itemList), inventory.getClass().getSimpleName());
        }
    }

    // 渲染方块线框
    private static void renderBlockWireframe(MatrixStack matrices, VertexConsumerProvider.Immediate immediate,
                                             Vec3d cameraPos, BlockPos pos, int color, float lineWidth) {
        RenderPipeline baseLinePipeline = RenderPipelines.LINES;

        RenderPipeline transparentPipeline = RenderPipeline.builder()
                .withVertexShader(baseLinePipeline.getVertexShader())
                .withFragmentShader(baseLinePipeline.getFragmentShader())
                .withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL, VertexFormat.DrawMode.LINES)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .withoutBlend()
                .withLocation(Identifier.of("xihe", "block_highlighter"))
                .build();

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

        RenderLayer renderLayer = RenderLayer.of(
                "xihe_block_highlighter",
                256,
                false,
                false,
                transparentPipeline,
                phaseParams
        );

        VertexConsumer consumer = immediate.getBuffer(renderLayer);

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = ((color >> 24) & 0xFF) / 255.0F;

        drawBlockEdges(matrices.peek().getPositionMatrix(), consumer, pos, r, g, b, a);
        matrices.pop();
    }

    // 绘制方块边缘
    private static void drawBlockEdges(Matrix4f matrix, VertexConsumer consumer, BlockPos pos,
                                       float r, float g, float b, float a) {
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

    // 绘制单条线
    private static void drawLine(Matrix4f matrix, VertexConsumer consumer,
                                 float x1, float y1, float z1, float x2, float y2, float z2,
                                 float r, float g, float b, float a) {
        consumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(0, 0, 1);
        consumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(0, 0, 1);
    }

    // 查找缺失物品
    public static List<ItemStack> findMissingItems(BlockPos projectionPos, SimpleInventory schematicInv) {
        List<ItemStack> missing = new ArrayList<>();
        if (isInventoryEmpty(schematicInv)) {
            LOGGER.info("{} 投影容器[{}]内容为空", MOD_TAG, projectionPos.toShortString());
            return missing;
        }

        for (int i = 0; i < schematicInv.size(); i++) {
            ItemStack required = schematicInv.getStack(i);
            if (required.isEmpty()) continue;

            boolean found = false;
            int neededCount = required.getCount();
            int foundCount = 0;

            for (Inventory storageInv : STORAGE_CONTAINER_CACHE.values()) {
                if (storageInv == null || storageInv.isEmpty()) continue;

                for (int j = 0; j < storageInv.size(); j++) {
                    ItemStack stored = storageInv.getStack(j);
                    if (ItemStack.areItemsEqual(stored, required)) {
                        foundCount += stored.getCount();
                        if (foundCount >= neededCount) {
                            found = true;
                            break;
                        }
                    }
                }
                if (found) break;
            }

            if (!found) {
                missing.add(required.copy());
            }
        }

        if (!missing.isEmpty()) {
            List<String> missingItems = missing.stream()
                    .map(stack -> String.format("%s x%d",
                            stack.getItem().getName(stack).getString(),
                            stack.getCount()))
                    .collect(Collectors.toList());
            LOGGER.info("{} 投影容器[{}]缺失物品: {}",
                    MOD_TAG, projectionPos.toShortString(),
                    String.join(", ", missingItems));
        } else {
            LOGGER.info("{} 投影容器[{}]物品齐全", MOD_TAG, projectionPos.toShortString());
        }
        return missing;
    }

    // 查找匹配的仓储容器
    public static Set<BlockPos> findMatchingStorageContainers(BlockPos projectionPos) {
        Set<BlockPos> matched = new HashSet<>();
        if (currentMissingItems.isEmpty()) return matched;

        World world = MinecraftClient.getInstance().world;
        if (world == null) return matched;

        BlockState state = world.getBlockState(projectionPos);
        Optional<SimpleInventory> schematicInv = PlacementContainerAccess.getSchematicInventory(projectionPos, state);
        if (schematicInv.isEmpty()) return matched;

        for (Map.Entry<BlockPos, Inventory> entry : STORAGE_CONTAINER_CACHE.entrySet()) {
            BlockPos storagePos = entry.getKey();
            Inventory storageInv = entry.getValue();
            if (storageInv == null) continue;

            for (ItemStack missing : currentMissingItems) {
                for (int i = 0; i < storageInv.size(); i++) {
                    ItemStack stored = storageInv.getStack(i);
                    if (ItemStack.areItemsEqual(stored, missing) &&
                            stored.getCount() >= missing.getCount()) {
                        matched.add(storagePos);
                        break;
                    }
                }
            }
        }
        return matched;
    }

    // 检查是否手持触发物品
    private static boolean isHoldingTriggerItem() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;
        return client.player.getMainHandStack().getItem() == HighlightConfig.getTriggerItem() ||
                client.player.getOffHandStack().getItem() == HighlightConfig.getTriggerItem();
    }

    // 获取当前缺失物品列表
    public static List<ItemStack> getCurrentMissingItems() {
        return new ArrayList<>(currentMissingItems);
    }

    // 容器交互处理器（静态内部类）
    private static class ContainerInteractionHandler {
        private final BlockPos pos;
        private final long tick;

        public ContainerInteractionHandler(BlockPos pos, long tick) {
            this.pos = pos;
            this.tick = tick;
        }

        // 处理屏幕打开事件
        public static void onScreenOpen(Screen screen) {
            if (interactionQueue.isEmpty()) return;

            ContainerInteractionHandler handler = interactionQueue.peek();
            if (handler != null && handler.processScreen(screen)) {
                interactionQueue.poll();
            }
        }

        // 处理容器界面
        private boolean processScreen(Screen screen) {
            if (!(screen instanceof ScreenHandlerProvider<?> provider)) {
                LOGGER.warn("{} 容器界面不是ScreenHandlerProvider [{}]", MOD_TAG, pos);
                return false;
            }

            // 提取容器库存（排除玩家背包）
            Inventory worldInventory = null;
            for (Slot slot : provider.getScreenHandler().slots) {
                if (!(slot.inventory instanceof PlayerInventory)) {
                    worldInventory = slot.inventory;
                    break;
                }
            }

            if (worldInventory == null) {
                LOGGER.warn("{} 未找到容器库存 [{}]", MOD_TAG, pos);
                return false;
            }

            // 更新缓存并记录日志
            STORAGE_CONTAINER_CACHE.put(pos, worldInventory);
            LOGGER.info("{} 交互后更新容器库存 [{}]，类型：{}，槽位：{}",
                    MOD_TAG, pos, worldInventory.getClass().getSimpleName(), worldInventory.size());
            logInventoryContent("交互后仓储容器", pos, worldInventory);
            return true;
        }
    }
}
