package cn.envision.xihe.client;

import cn.envision.xihe.client.features.PlacementContainerAccess;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
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

    private final Map<Item, Integer> remainingNeeded = new ConcurrentHashMap<>();
    private final Map<BlockPos, Inventory> storageContainerCache = new ConcurrentHashMap<>();
    private final Set<BlockPos> highlightedBlocks = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> storageContainers = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> matchingStorageContainers = ConcurrentHashMap.newKeySet();
    private final List<ItemStack> currentMissingItems = new CopyOnWriteArrayList<>();

    private volatile BlockPos currentProjectionContainer;
    private volatile BlockPos tempProcessingPos;
    // 匹配结果脏标记：标脏后每帧最多重算一次
    private volatile boolean dirty = true;

    private HighlightState() {
    }

    public static HighlightState get() {
        return INSTANCE;
    }

    // ---------- 渲染侧只读视图 ----------

    public Set<BlockPos> getHighlightedBlocks() {
        return Collections.unmodifiableSet(highlightedBlocks);
    }

    public Set<BlockPos> getStorageContainers() {
        return Collections.unmodifiableSet(storageContainers);
    }

    public Set<BlockPos> getMatchingStorageContainers() {
        return Collections.unmodifiableSet(matchingStorageContainers);
    }

    public BlockPos getCurrentProjectionContainer() {
        return currentProjectionContainer;
    }

    public Map<Item, Integer> getRemainingNeeded() {
        return remainingNeeded;
    }

    // ---------- 手动标记 ----------

    public void addHighlightedBlock(BlockPos pos) {
        if (pos != null) {
            highlightedBlocks.add(pos.toImmutable());
        }
    }

    // ---------- 匹配 ----------

    public void markDirty() {
        dirty = true;
    }

    /**
     * 渲染线程每帧调用，只有标脏时才真正重算。
     */
    public void ensureUpToDate() {
        if (dirty) {
            updateMatching();
        }
    }

    public void updateMatching() {
        dirty = false;
        matchingStorageContainers.clear();
        remainingNeeded.clear();
        if (currentProjectionContainer == null || currentMissingItems.isEmpty()) {
            return;
        }

        // 1. 统计玩家背包里已有的物品数量
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
        Inventory projectionInv = storageContainerCache.get(currentProjectionContainer);
        if (projectionInv != null) {
            for (int i = 0; i < projectionInv.size(); i++) {
                ItemStack stack = projectionInv.getStack(i);
                if (!stack.isEmpty()) {
                    projectionContainerCount.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }

        // 3. 先把同一物品在多个槽位的需求合并，再整体扣除背包和投影容器里的数量
        Map<Item, Integer> neededTotal = new HashMap<>();
        for (ItemStack missing : currentMissingItems) {
            if (missing.isEmpty()) {
                continue;
            }
            neededTotal.merge(missing.getItem(), missing.getCount(), Integer::sum);
        }

        for (Map.Entry<Item, Integer> entry : neededTotal.entrySet()) {
            int remaining = entry.getValue()
                    - playerInventoryCount.getOrDefault(entry.getKey(), 0)
                    - projectionContainerCount.getOrDefault(entry.getKey(), 0);

            if (remaining > 0) {
                remainingNeeded.put(entry.getKey(), remaining);
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
                if (!storedStack.isEmpty() && remainingNeeded.containsKey(storedStack.getItem())) {
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
     * 对照所有已缓存的仓储容器，返回蓝图中还没凑齐的槽位物品。
     */
    public List<ItemStack> findMissingItems(BlockPos projectionPos, SimpleInventory schematicInv) {
        List<ItemStack> missing = new ArrayList<>();
        if (isInventoryEmpty(schematicInv)) {
            return missing;
        }

        // 先把所有仓储容器按物品汇总一次，避免逐个需求重复扫描
        Map<Item, Integer> storageTotals = new HashMap<>();
        for (Inventory storageInv : storageContainerCache.values()) {
            if (storageInv == null) {
                continue;
            }
            for (int i = 0; i < storageInv.size(); i++) {
                ItemStack stored = storageInv.getStack(i);
                if (!stored.isEmpty()) {
                    storageTotals.merge(stored.getItem(), stored.getCount(), Integer::sum);
                }
            }
        }

        for (int i = 0; i < schematicInv.size(); i++) {
            ItemStack required = schematicInv.getStack(i);
            if (required.isEmpty()) {
                continue;
            }
            if (storageTotals.getOrDefault(required.getItem(), 0) < required.getCount()) {
                missing.add(required.copy());
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

        boolean fullySatisfied = true;
        for (ItemStack missingStack : currentMissingItems) {
            int foundCount = 0;
            for (int i = 0; i < storageInv.size(); i++) {
                ItemStack storedStack = storageInv.getStack(i);
                if (ItemStack.areItemsEqual(storedStack, missingStack)) {
                    foundCount += storedStack.getCount();
                }
            }
            if (foundCount < missingStack.getCount()) {
                fullySatisfied = false;
                break;
            }
        }

        if (fullySatisfied) {
            storageContainers.remove(immutableStoragePos);
            storageContainerCache.remove(immutableStoragePos);
            markDirty(); // 下一帧重算匹配并刷新高亮
        }
    }

    /**
     * 断线或手动 /highlightblock clear 时重置全部状态。
     */
    public void clearAll() {
        storageContainers.clear();
        storageContainerCache.clear();
        highlightedBlocks.clear();
        matchingStorageContainers.clear();
        remainingNeeded.clear();
        currentMissingItems.clear();
        currentProjectionContainer = null;
        tempProcessingPos = null;
        dirty = false;
    }
}
