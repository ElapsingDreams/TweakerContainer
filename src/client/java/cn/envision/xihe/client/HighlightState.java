package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.features.PlacementContainerAccess;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.ComponentChanges;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 高亮功能的状态与仓储匹配逻辑，线框绘制见 {@link BlockHighlighterRender}。
 * <p>
 * 状态会被客户端线程（右击/命令）与渲染线程同时访问，因此统一使用并发容器。
 */
public final class HighlightState {
    private static final HighlightState INSTANCE = new HighlightState();

    /**
     * 需求与缺口的聚合键：默认只按物品，开启严格校验后再带上物品组件（NBT）。
     * <p>
     * {@link ComponentChanges} 自带 equals/hashCode，可以直接作为 map 键。
     */
    public record StackKey(Item item, ComponentChanges components) {
    }

    // 嵌套读取的最大层数：箱子 → 潜影盒 → 收纳袋
    private static final int MAX_NESTING_DEPTH = 2;

    /** 该物品对应的需求键，供界面判断“同一种物品”用。 */
    public static StackKey requirementKey(ItemStack stack) {
        return stack.isEmpty() ? null : keyOf(stack);
    }

    private final Map<StackKey, Integer> remainingNeeded = new ConcurrentHashMap<>();
    private final Map<StackKey, Integer> projectionMissing = new ConcurrentHashMap<>();
    private final Map<BlockPos, Inventory> storageContainerCache = new ConcurrentHashMap<>();
    private final Set<BlockPos> storageContainers = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> matchingStorageContainers = ConcurrentHashMap.newKeySet();
    private final List<ItemStack> currentMissingItems = new CopyOnWriteArrayList<>();

    private volatile BlockPos currentProjectionContainer;
    private volatile BlockPos tempProcessingPos;
    // 匹配结果脏标记：标脏后每帧最多重算一次
    private volatile boolean dirty = true;
    // 兜底刷新间隔（毫秒）
    private static final long REFRESH_INTERVAL_MS = 250L;
    private long lastUpdateMs;

    private HighlightState() {
    }

    public static HighlightState get() {
        return INSTANCE;
    }

    /** 按当前配置决定是否区分 NBT/组件。 */
    private static StackKey keyOf(ItemStack stack) {
        return new StackKey(stack.getItem(),
                HighlightConfig.isStrictNbt() ? stack.getComponentChanges() : null);
    }

    private static void merge(Map<StackKey, Integer> counts, ItemStack stack, int depth) {
        if (stack.isEmpty()) {
            return;
        }

        counts.merge(keyOf(stack), stack.getCount(), Integer::sum);

        if (depth <= 0 || !HighlightConfig.isReadNestedContainers()) {
            return;
        }

        for (ItemStack nested : storedItemsOf(stack)) {
            merge(counts, nested, depth - 1);
        }
    }

    /**
     * 该物品里装着的物品。
     * <p>
     * 判定与读取都走 malilib 的组件接口（{@code CONTAINER} 与 {@code BUNDLE_CONTENTS}），
     * 所以潜影盒、收纳袋以及任何带容器组件的模组物品都能覆盖，不需要物品类型白名单。
     */
    private static List<ItemStack> storedItemsOf(ItemStack stack) {
        if (InventoryUtils.shulkerBoxHasItems(stack)) {
            return InventoryUtils.getStoredItems(stack);
        }
        if (InventoryUtils.bundleHasItems(stack)) {
            return InventoryUtils.getBundleItems(stack);
        }
        return List.of();
    }

    /** 该物品或它装着的东西里，是否有需要去取的。 */
    private static boolean containsNeeded(Map<StackKey, Integer> needed, ItemStack stack, int depth) {
        if (stack.isEmpty()) {
            return false;
        }
        if (needed.containsKey(keyOf(stack))) {
            return true;
        }
        if (depth <= 0 || !HighlightConfig.isReadNestedContainers()) {
            return false;
        }

        for (ItemStack nested : storedItemsOf(stack)) {
            if (containsNeeded(needed, nested, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 渲染侧只读视图 ----------

    public Set<BlockPos> getStorageContainers() {
        return Collections.unmodifiableSet(storageContainers);
    }

    public Set<BlockPos> getMatchingStorageContainers() {
        return Collections.unmodifiableSet(matchingStorageContainers);
    }

    public BlockPos getCurrentProjectionContainer() {
        return currentProjectionContainer;
    }

    // ---------- 槽位提示查询 ----------

    /** 投影容器自身还缺多少，也就是背包里该放进去多少；0 表示不需要。 */
    public int getProjectionMissing(ItemStack stack) {
        return stack.isEmpty() ? 0 : projectionMissing.getOrDefault(keyOf(stack), 0);
    }

    /** 仓储视角还需要多少（已扣掉背包与投影容器里已有的）；0 表示不需要。 */
    public int getRemainingNeeded(ItemStack stack) {
        return stack.isEmpty() ? 0 : remainingNeeded.getOrDefault(keyOf(stack), 0);
    }

    /**
     * 严格校验下，该物品是否存在“物品相同但组件不同”的需求，用于换色提示。
     *
     * @param projectionView true 查投影容器缺口，false 查仓储需求
     */
    public boolean hasComponentMismatch(ItemStack stack, boolean projectionView) {
        if (stack.isEmpty() || !HighlightConfig.isStrictNbt()) {
            return false;
        }

        Map<StackKey, Integer> source = projectionView ? projectionMissing : remainingNeeded;
        for (StackKey key : source.keySet()) {
            if (key.item() == stack.getItem() && !key.components().equals(stack.getComponentChanges())) {
                return true;
            }
        }
        return false;
    }

    // ---------- 匹配 ----------

    public void markDirty() {
        dirty = true;
    }

    /**
     * 渲染线程每帧调用：标脏或距上次重算超过刷新间隔时才真正重算。
     * <p>
     * 玩家背包内容变化不会标脏，因此加一道时间兜底，避免结果长期停留在旧值。
     */
    public void ensureUpToDate() {
        if (dirty || refreshDue()) {
            updateMatching();
        }
    }

    private boolean refreshDue() {
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < REFRESH_INTERVAL_MS) {
            return false;
        }
        lastUpdateMs = now;
        return true;
    }

    public void updateMatching() {
        dirty = false;
        matchingStorageContainers.clear();
        remainingNeeded.clear();
        projectionMissing.clear();
        if (currentProjectionContainer == null || currentMissingItems.isEmpty()) {
            return;
        }

        // 1. 背包里已有的数量
        Map<StackKey, Integer> playerCount = new HashMap<>();
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                merge(playerCount, player.getInventory().getStack(i), MAX_NESTING_DEPTH);
            }
        }

        // 2. 投影容器里已有的数量
        Map<StackKey, Integer> containerCount = new HashMap<>();
        Inventory projectionInv = storageContainerCache.get(currentProjectionContainer);
        if (projectionInv != null) {
            for (int i = 0; i < projectionInv.size(); i++) {
                merge(containerCount, projectionInv.getStack(i), MAX_NESTING_DEPTH);
            }
        }

        // 3. 需求按总数合并后再扣减，几组相同物品会算在一起；需求侧不展开容器内容
        Map<StackKey, Integer> required = new HashMap<>();
        for (ItemStack missing : currentMissingItems) {
            merge(required, missing, 0);
        }

        for (Map.Entry<StackKey, Integer> entry : required.entrySet()) {
            StackKey key = entry.getKey();
            int need = entry.getValue();
            int inContainer = containerCount.getOrDefault(key, 0);

            // 投影容器自己还缺的，就是背包里该往里放的
            int missing = need - inContainer;
            if (missing > 0) {
                projectionMissing.put(key, missing);
            }

            // 再扣掉背包里已有的，才是还要去仓储取的数量
            int remaining = need - playerCount.getOrDefault(key, 0) - inContainer;
            if (remaining > 0) {
                remainingNeeded.put(key, remaining);
            }
        }

        // 4. 背包和投影容器已全部满足，不高亮任何仓储箱
        if (remainingNeeded.isEmpty()) {
            return;
        }

        // 5. 只高亮包含“仍然缺少”物品的仓储箱
        for (BlockPos storagePos : storageContainers) {
            Inventory storageInv = storageContainerCache.get(storagePos);
            if (storageInv == null) {
                continue;
            }

            for (int i = 0; i < storageInv.size(); i++) {
                ItemStack storedStack = storageInv.getStack(i);
                if (containsNeeded(remainingNeeded, storedStack, MAX_NESTING_DEPTH)) {
                    matchingStorageContainers.add(storagePos);
                    break;
                }
            }
        }
    }

    // ---------- 容器点击 ----------

    /**
     * 记录本次交互的容器坐标，供界面缓存容器内容时取用。
     */
    public void setTempProcessingPos(BlockPos pos) {
        tempProcessingPos = pos != null ? pos.toImmutable() : null;
    }

    /**
     * 读取并清空临时坐标，避免被其他界面复用。
     */
    public BlockPos getAndClearTempProcessingPos() {
        BlockPos pos = tempProcessingPos;
        tempProcessingPos = null;
        return pos;
    }

    public void cacheStorageInventory(BlockPos pos, Inventory inv) {
        if (pos == null || inv == null) {
            return;
        }
        if (storageContainerCache.put(pos.toImmutable(), inv) != inv) {
            markDirty();
        }
    }

    public boolean isStorageContainer(BlockPos pos) {
        return pos != null && storageContainers.contains(pos.toImmutable());
    }

    public void addStorageContainer(BlockPos pos) {
        if (pos != null && storageContainers.add(pos.toImmutable())) {
            markDirty();
        }
    }

    public void removeStorageContainer(BlockPos pos) {
        if (pos == null) {
            return;
        }

        BlockPos immutablePos = pos.toImmutable();
        boolean changed = storageContainers.remove(immutablePos);
        changed |= storageContainerCache.remove(immutablePos) != null;
        if (changed) {
            markDirty();
        }
    }

    public void removeProjectContainer() {
        currentProjectionContainer = null;
        currentMissingItems.clear();
        projectionMissing.clear();
        markDirty();
    }

    // ---------- 投影容器 ----------

    public boolean setCurrentProjectionContainer(BlockPos newPos) {
        if (newPos == null) {
            return false;
        }

        BlockPos immutableNewPos = newPos.toImmutable();
        World world = MinecraftClient.getInstance().world;
        if (world == null) {
            return false;
        }

        // 投影内容取自蓝图，世界方块只用于确定容器类型
        BlockState state = world.getBlockState(immutableNewPos);
        Optional<SimpleInventory> schematicInv = PlacementContainerAccess.getSchematicInventory(immutableNewPos, state);
        if (schematicInv.isEmpty() || isInventoryEmpty(schematicInv.get())) {
            return false;
        }

        currentProjectionContainer = immutableNewPos;

        currentMissingItems.clear();
        Inventory inventory = schematicInv.get();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                currentMissingItems.add(stack.copy());
            }
        }

        markDirty();
        return true;
    }

    private boolean isInventoryEmpty(Inventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            if (!inventory.getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对照所有已缓存的仓储容器，按总数返回蓝图中还没凑齐的物品。
     * <p>
     * 同一个物品在蓝图里占多个槽位时按合计需求比较，避免“两组各 64、仓储有 64”被判为已凑齐。
     */
    public List<ItemStack> findMissingItems(BlockPos projectionPos, SimpleInventory schematicInv) {
        List<ItemStack> missing = new ArrayList<>();
        if (isInventoryEmpty(schematicInv)) {
            return missing;
        }

        Map<StackKey, Integer> required = new HashMap<>();
        Map<StackKey, ItemStack> samples = new HashMap<>();
        for (int i = 0; i < schematicInv.size(); i++) {
            ItemStack requiredStack = schematicInv.getStack(i);
            if (requiredStack.isEmpty()) {
                continue;
            }
            StackKey key = keyOf(requiredStack);
            required.merge(key, requiredStack.getCount(), Integer::sum);
            samples.putIfAbsent(key, requiredStack);
        }

        Map<StackKey, Integer> stored = new HashMap<>();
        for (Inventory storageInv : storageContainerCache.values()) {
            if (storageInv == null) {
                continue;
            }
            for (int i = 0; i < storageInv.size(); i++) {
                merge(stored, storageInv.getStack(i), MAX_NESTING_DEPTH);
            }
        }

        for (Map.Entry<StackKey, Integer> entry : required.entrySet()) {
            int lack = entry.getValue() - stored.getOrDefault(entry.getKey(), 0);
            if (lack > 0) {
                ItemStack stack = samples.get(entry.getKey()).copy();
                stack.setCount(lack);
                missing.add(stack);
            }
        }
        return missing;
    }

    /**
     * 该仓储箱已能满足投影需求时，从仓储名单中移除。
     */
    public void checkAndRemoveSatisfiedContainer(BlockPos storagePos) {
        if (storagePos == null || currentProjectionContainer == null || currentMissingItems.isEmpty()) {
            return;
        }

        BlockPos immutableStoragePos = storagePos.toImmutable();
        Inventory storageInv = storageContainerCache.get(immutableStoragePos);
        if (storageInv == null) {
            return;
        }

        // 需求与可提供数量都按总数汇总后再逐个比较
        Map<StackKey, Integer> required = new HashMap<>();
        for (ItemStack missingStack : currentMissingItems) {
            merge(required, missingStack, 0);
        }

        Map<StackKey, Integer> available = new HashMap<>();
        for (int i = 0; i < storageInv.size(); i++) {
            merge(available, storageInv.getStack(i), MAX_NESTING_DEPTH);
        }

        for (Map.Entry<StackKey, Integer> entry : required.entrySet()) {
            if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return;
            }
        }

        storageContainers.remove(immutableStoragePos);
        storageContainerCache.remove(immutableStoragePos);
        markDirty(); // 下一帧重算匹配并刷新高亮
    }

    /**
     * 断线或界面上的清除按钮触发时重置全部状态。
     */
    public void clearAll() {
        storageContainers.clear();
        storageContainerCache.clear();
        matchingStorageContainers.clear();
        remainingNeeded.clear();
        projectionMissing.clear();
        currentMissingItems.clear();
        currentProjectionContainer = null;
        tempProcessingPos = null;
        dirty = false;
    }
}
