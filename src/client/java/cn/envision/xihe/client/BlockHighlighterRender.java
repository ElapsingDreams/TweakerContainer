package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.features.PlacementContainerAccess;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import javax.naming.Context;
import java.util.*;
import java.util.stream.Collectors;

import static cn.envision.xihe.client.config.HighlightConfig.getSW;
import static cn.envision.xihe.client.config.HighlightConfig.setSW;

public class BlockHighlighterRender {
    private static Map<Item, Integer> remainingNeeded = new HashMap<>();
    // 基础常量
    private static final Set<BlockPos> TEMP_HIGHLIGHTED_BLOCKS = new HashSet<>();
    private static final Set<BlockPos> HIGHLIGHTED_BLOCKS = new HashSet<>();
    private static final Set<BlockPos> STORAGE_CONTAINERS = new HashSet<>();

    private static final Map<BlockPos, Inventory> STORAGE_CONTAINER_CACHE = new HashMap<>();
    private static BlockPos currentProjectionContainer = null;
    private static final List<ItemStack> currentMissingItems = new ArrayList<>();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_TAG = "[XiheContainerHighlight]";
    private static final Set<BlockPos> MATCHING_STORAGE_CONTAINERS = new HashSet<>();

    // 颜色常量（ARGB格式）
    private static final int COLOR_PROJECTION = 0xFF00FF00; // 绿色
    private static final int COLOR_STORAGE = 0xFF0000FF;    // 蓝色
    private static final int COLOR_MATCHING = 0xFFFFFF00;   // 黄色
    private static BlockPos tempProcessingPos = null;
    public static void setup() {
        // 注册世界渲染事件
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BlockHighlighterRender::onRender);
        ClientPlayConnectionEvents.DISCONNECT.register(BlockHighlighterRender::clearAll);
        //.register(BlockHighlighterRender::clearAll);
        //WorldRenderEvents.END.register(BlockHighlighterRender::clearAll);
        // LOGGER.info("{} 容器高亮系统初始化完成", MOD_TAG);

    }


    public static Map<Item, Integer> getRemainingNeeded() {
        return remainingNeeded;
    }

    public static void addSTORAGE_CONTAINER_CACHE(BlockPos pos, Inventory inv){
            STORAGE_CONTAINER_CACHE.put(pos, inv);
    }
    // 新增：设置临时处理坐标
    public static void setTempProcessingPos(BlockPos pos) {
        tempProcessingPos = pos != null ? pos.toImmutable() : null;
    }
    // BlockHighlighterRender.java

    public static void updateMatchingStorageContainers() {
        MATCHING_STORAGE_CONTAINERS.clear();
        remainingNeeded.clear();
        if (currentProjectionContainer == null || currentMissingItems.isEmpty()) return;

        // 1. 先统计玩家背包里已有的物品数量
        PlayerEntity player = MinecraftClient.getInstance().player;
        Map<Item, Integer> playerInventoryCount = new HashMap<>();
        if (player != null) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (!stack.isEmpty()) {
                    playerInventoryCount.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }

        // 2. 统计投影容器里已有的物品数量
        Map<Item, Integer> projectionContainerCount = new HashMap<>();
        Inventory projectionInv = STORAGE_CONTAINER_CACHE.get(currentProjectionContainer);
        if (projectionInv != null) {
            for (int i = 0; i < projectionInv.size(); i++) {
                ItemStack stack = projectionInv.getStack(i);
                if (!stack.isEmpty()) {
                    projectionContainerCount.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }

        // 3. 计算扣除背包和投影容器后，每种物品还缺多少
        //Map<Item, Integer> remainingNeeded = new HashMap<>();
        for (ItemStack missing : currentMissingItems) {
            int needed = missing.getCount();
            int haveInPlayer = playerInventoryCount.getOrDefault(missing.getItem(), 0);
            int haveInProjection = projectionContainerCount.getOrDefault(missing.getItem(), 0);

            // 核心修改：从总需求中减去玩家背包和投影容器里已有的数量
            int remaining = needed - haveInPlayer - haveInProjection;

            if (remaining > 0) {
                remainingNeeded.put(missing.getItem(), remaining);
            }
        }

        // 4. 如果背包和投影容器加起来已经全部满足，直接返回，不高亮任何仓储箱
        if (remainingNeeded.isEmpty()) {
            return;
        }

        // 5. 检查仓储箱，只高亮包含"仍然缺少"物品的仓储箱
        for (BlockPos storagePos : STORAGE_CONTAINERS) {
            Inventory storageInv = STORAGE_CONTAINER_CACHE.get(storagePos);
            if (storageInv == null) continue;

            boolean hasMatchingItem = false;
            for (int i = 0; i < storageInv.size(); i++) {
                ItemStack storedStack = storageInv.getStack(i);
                if (!storedStack.isEmpty() && remainingNeeded.containsKey(storedStack.getItem())) {
                    hasMatchingItem = true;
                    break;
                }
            }

            if (hasMatchingItem) {
                MATCHING_STORAGE_CONTAINERS.add(storagePos);
            }
        }
    }

    // 新增：获取并清空临时处理坐标（读取后自动清除）
    public static BlockPos getAndClearTempProcessingPos() {
        BlockPos pos = tempProcessingPos;
        tempProcessingPos = null; // 读取后立即清空，防止被其他界面处理
        return pos;
    }
    // 处理容器点击事件

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
    public static boolean setCurrentProjectionContainer(BlockPos newPos) {
        if (newPos == null) {
            //LOGGER.warn("{} 尝试设置空的投影容器，操作忽略", MOD_TAG);
        }

        BlockPos immutableNewPos = newPos;
        World world = MinecraftClient.getInstance().world;
        if (world == null) return true;
        // 原则上弃用，非servux等协议获取，此处纯作为虚拟方块，缺的方块默认成全部了，能用就行)
        BlockState state = world.getBlockState(immutableNewPos);
        Optional<SimpleInventory> schematicInv = PlacementContainerAccess.getSchematicInventory(immutableNewPos, state);

        if (schematicInv.isEmpty() || isInventoryEmpty(schematicInv.get())) {
            //LOGGER.warn("{} 投影容器[{}]为空或无法读取", MOD_TAG, immutableNewPos.toShortString());
            return false;
        }

        if (currentProjectionContainer != null) {
            HIGHLIGHTED_BLOCKS.remove(currentProjectionContainer);
        }
        currentProjectionContainer = immutableNewPos;
        HIGHLIGHTED_BLOCKS.add(immutableNewPos.toImmutable());
        //LOGGER.info("{} 已设置投影容器：{}", MOD_TAG, immutableNewPos.toShortString());
        //logInventoryContent("投影容器", immutableNewPos, schematicInv.get());

        // 重新计算缺失物品
        currentMissingItems.clear();
        //currentMissingItems.addAll(findMissingItems(immutableNewPos, schematicInv.get()));
        /*Inventory inventory = schematicInv.get();
        List<ItemStack> missing = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!currentMissingItems.contains(stack))
                missing.add(stack);
        }
        currentMissingItems.addAll(missing);*/

        // 安全方法
        Inventory inventory = schematicInv.get();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                currentMissingItems.add(stack.copy()); // 使用 copy() 是个好习惯
            }
        }

        updateMatchingStorageContainers();
        return true;
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
        if (!STORAGE_CONTAINERS.contains(pos))
            STORAGE_CONTAINERS.add(immutablePos);
        //STORAGE_CONTAINERS.add(immutablePos);
        updateMatchingStorageContainers();
    }

    // 移除仓储容器
    public static void removeStorageContainer(BlockPos pos) {
        BlockPos immutablePos = pos.toImmutable();
        STORAGE_CONTAINERS.remove(immutablePos);
        STORAGE_CONTAINER_CACHE.remove(immutablePos);
        //LOGGER.debug("{} 移除仓储容器及缓存：{}", MOD_TAG, immutablePos.toShortString());
        updateMatchingStorageContainers();
    }
    public static void removeProjectContainer() {
        currentProjectionContainer=null;
        currentMissingItems.clear();
        updateMatchingStorageContainers();
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
        MATCHING_STORAGE_CONTAINERS.clear();
        //LOGGER.info("{} 清除所有仓储容器及缓存", MOD_TAG);
    }
    private static void clearAll(ClientPlayNetworkHandler clientPlayNetworkHandler, MinecraftClient minecraftClient) {
        setSW(false);
        clearAll();
    }

    // 世界渲染回调
    private static void onRender(WorldRenderContext context) {
        if (!getSW()) return;
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
            for (BlockPos storagePos : STORAGE_CONTAINERS) {
                renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_STORAGE, 2.0F);
            }
            if (currentProjectionContainer != null) {
                renderBlockWireframe(matrices, immediate, cameraPos, currentProjectionContainer, COLOR_PROJECTION, 2.5F);
            }

        } else {
            /*if (currentProjectionContainer != null) {
                renderBlockWireframe(matrices, immediate, cameraPos, currentProjectionContainer, COLOR_PROJECTION, 2.5F);
            }*/
            for (BlockPos matchingPos : MATCHING_STORAGE_CONTAINERS) {
                renderBlockWireframe(matrices, immediate, cameraPos, matchingPos, COLOR_MATCHING, 2.5F);
            }
            // 渲染匹配的仓储容器（黄色）
            /*if (currentProjectionContainer != null && !STORAGE_CONTAINERS.isEmpty()) {
                Set<BlockPos> matchedStorage = findMatchingStorageContainers(currentProjectionContainer);
                for (BlockPos storagePos : matchedStorage) {
                    renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_MATCHING, 2.5F);
                }
            }
            // 重新实现：渲染包含缺失物品的仓储容器（黄色）
            if (currentProjectionContainer != null && !STORAGE_CONTAINERS.isEmpty() && !currentMissingItems.isEmpty()) {
                for (BlockPos storagePos : STORAGE_CONTAINERS) {
                    Inventory storageInv = STORAGE_CONTAINER_CACHE.get(storagePos);
                    if (storageInv == null) continue;

                    // 检查仓储容器中是否包含任意缺失物品
                    boolean hasMissingItem = false;
                    for (ItemStack missingItem : currentMissingItems) {
                        for (int i = 0; i < storageInv.size(); i++) {
                            ItemStack storedItem = storageInv.getStack(i);
                            if (!storedItem.isEmpty() && ItemStack.areItemsEqual(storedItem, missingItem)) {
                                hasMissingItem = true;
                                break;
                            }
                        }
                        if (hasMissingItem) break;
                    }

                    if (hasMissingItem) {
                        renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_MATCHING, 2.5F);
                    }
                }
            }*/
            /*for (BlockPos storagePos : getTempHighlightedBlocks()) {
                if(storagePos != currentProjectionContainer)
                    renderBlockWireframe(matrices, immediate, cameraPos, storagePos, COLOR_MATCHING, 2.5F);
            }*/
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
            //LOGGER.info("{} {}[{}] 内容为空 (类型: {})",
             //       MOD_TAG, containerType, pos.toShortString(), inventory.getClass().getSimpleName());
        } else {
            //LOGGER.info("{} {}[{}] 内容: {} (类型: {})",
             //       MOD_TAG, containerType, pos.toShortString(),
             //       String.join(", ", itemList), inventory.getClass().getSimpleName());
        }
    }
    private static RenderPipeline TRANSPARENT_PIPELINE;
    private static RenderLayer BLOCK_HIGHLIGHT_LAYER;
    // 渲染方块线框
    private static void renderBlockWireframe(MatrixStack matrices, VertexConsumerProvider.Immediate immediate,
                                             Vec3d cameraPos, BlockPos pos, int color, float lineWidth) {
        if (TRANSPARENT_PIPELINE == null) {
            RenderPipeline baseLinePipeline = RenderPipelines.LINES;
            TRANSPARENT_PIPELINE = RenderPipeline.builder()
                    .withVertexShader(baseLinePipeline.getVertexShader())
                    .withFragmentShader(baseLinePipeline.getFragmentShader())
                    .withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL, VertexFormat.DrawMode.LINES)
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    // .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST) 会瞎的
                    .withDepthWrite(false)
                    .withCull(false)
                    .withoutBlend()
                    .withLocation(Identifier.of("xihe", "block_highlighter"))
                    .build();
        }



        if (BLOCK_HIGHLIGHT_LAYER == null) {
            RenderPhase.LineWidth lineWidthPhase = RenderPhaseCache.getLineWidthPhase(lineWidth);
            RenderLayer.MultiPhaseParameters phaseParams = RenderLayer.MultiPhaseParameters.builder()
                    .lineWidth(lineWidthPhase)
                    .texture(RenderPhase.NO_TEXTURE)
                    .lightmap(RenderPhase.DISABLE_LIGHTMAP)
                    .overlay(RenderPhase.DISABLE_OVERLAY_COLOR)
                    .layering(RenderPhase.NO_LAYERING)
                    .target(RenderPhase.MAIN_TARGET)
                    .texturing(RenderPhase.DEFAULT_TEXTURING)
                    .build(RenderLayer.OutlineMode.NONE);

            BLOCK_HIGHLIGHT_LAYER = RenderLayer.of(
                    "xihe_block_highlighter",
                    256,
                    false,
                    false,
                    TRANSPARENT_PIPELINE,
                    phaseParams
            );
        }

        VertexConsumer consumer = immediate.getBuffer(BLOCK_HIGHLIGHT_LAYER);

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

    // 查找缺失物品
    public static List<ItemStack> findMissingItems(BlockPos projectionPos, SimpleInventory schematicInv) {
        List<ItemStack> missing = new ArrayList<>();
        if (isInventoryEmpty(schematicInv)) {
            //LOGGER.info("{} 投影容器[{}]内容为空", MOD_TAG, projectionPos.toShortString());
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
            //.info("{} 投影容器[{}]缺失物品: {}",
            //        MOD_TAG, projectionPos.toShortString(),
            //        String.join(", ", missingItems));
        } else {
            //LOGGER.info("{} 投影容器[{}]物品齐全", MOD_TAG, projectionPos.toShortString());
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
    public static boolean isHoldingTriggerItem() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;
        return client.player.getMainHandStack().getItem() == HighlightConfig.getTriggerItem() ||
                client.player.getOffHandStack().getItem() == HighlightConfig.getTriggerItem();
    }

    // 获取当前缺失物品列表
    public static List<ItemStack> getCurrentMissingItems() {
        return currentMissingItems;
    }


    public static void checkAndRemoveSatisfiedContainer(BlockPos storagePos) {
        if (storagePos == null || currentProjectionContainer == null || currentMissingItems.isEmpty()) {
            return;
        }

        BlockPos immutableStoragePos = storagePos.toImmutable();
        Inventory storageInv = STORAGE_CONTAINER_CACHE.get(immutableStoragePos);
        if (storageInv == null) {
            return;
        }

        // 遍历所有缺失的物品，检查这个仓储容器是否已经满足了所有需求
        boolean isFullySatisfied = true;
        for (ItemStack missingStack : currentMissingItems) {
            int neededCount = missingStack.getCount();
            int foundCount = 0;

            // 计算这个仓储容器里有多少我们需要的物品
            for (int i = 0; i < storageInv.size(); i++) {
                ItemStack storedStack = storageInv.getStack(i);
                if (ItemStack.areItemsEqual(storedStack, missingStack)) {
                    foundCount += storedStack.getCount();
                }
            }

            // 如果这个仓储容器里的数量不够，说明还没满足
            if (foundCount < neededCount) {
                isFullySatisfied = false;
                break;
            }
        }

        // 只有当这个仓储箱里的物品完全满足了投影需求时，才从仓储区名单中移除它
        if (isFullySatisfied) {
            STORAGE_CONTAINERS.remove(immutableStoragePos);
            STORAGE_CONTAINER_CACHE.remove(immutableStoragePos);
            updateMatchingStorageContainers(); // 重新计算匹配，刷新高亮
            // LOGGER.info("{} 仓储容器[{}]物品已齐全，已移除高亮", MOD_TAG, immutableStoragePos.toShortString());
        }
    }
}
