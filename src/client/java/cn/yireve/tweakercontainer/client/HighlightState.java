package cn.yireve.tweakercontainer.client;

import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import cn.yireve.tweakercontainer.client.features.PlacementContainerAccess;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.ItemType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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
 * 状态会被客户端线程（右击容器、界面绘制）与渲染线程同时访问，因此统一使用并发容器。
 */
public final class HighlightState {
    private static final HighlightState INSTANCE = new HighlightState();

    /**
     * 需求与缺口的聚合键直接用 malilib 的 {@link ItemType}：
     * checkNBT 跟随严格校验开关，为 true 时连物品组件（NBT）一起比较。
     */
    private static final int MAX_NESTING_DEPTH = 2;

    /** 该物品对应的需求键，供界面判断“同一种物品”用。 */
    public static ItemType requirementKey(ItemStack stack) {
        return stack.isEmpty() ? null : keyOf(stack);
    }

    private final Map<ItemType, Integer> remainingNeeded = new ConcurrentHashMap<>();
    private final Map<ItemType, Integer> projectionMissing = new ConcurrentHashMap<>();
    private final Map<BlockPos, Inventory> storageContainerCache = new ConcurrentHashMap<>();
    // 登记时的方块状态，用于复核这个位置是否还是原来那个容器
    private final Map<BlockPos, BlockState> storageContainerStates = new ConcurrentHashMap<>();
    // 大箱子的另一半坐标，用于合成一个整体框与半箱失效时只丢一半
    private final Map<BlockPos, BlockPos> storageContainerPartners = new ConcurrentHashMap<>();
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
    private static ItemType keyOf(ItemStack stack) {
        return new ItemType(stack, HighlightConfig.isStrictNbt());
    }

    private static void merge(Map<ItemType, Integer> counts, ItemStack stack, int depth) {
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
    private static boolean containsNeeded(Map<ItemType, Integer> needed, ItemStack stack, int depth) {
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

    /** 投影容器大箱子的另一半；不是大箱子、或对面不是箱子时返回 null。 */
    public BlockPos getProjectionContainerPartner() {
        BlockPos projection = currentProjectionContainer;
        if (projection == null) {
            return null;
        }

        World world = MinecraftClient.getInstance().world;
        return world == null ? null : findChestPartner(world, projection, world.getBlockState(projection));
    }

    /** 该坐标是不是投影容器：大箱子的任意一半都算。 */
    public boolean isProjectionContainer(BlockPos pos) {
        if (pos == null || currentProjectionContainer == null) {
            return false;
        }

        BlockPos immutablePos = pos.toImmutable();
        return immutablePos.equals(currentProjectionContainer) || immutablePos.equals(getProjectionContainerPartner());
    }

    // ---------- 槽位提示查询 ----------

    /**
     * 槽位匹配到的需求。
     *
     * @param key    需求物品的聚合键
     * @param amount 该需求的总量
     * @param nested 是否由容器内部内容命中（为 true 时只标色，不在盒子上写数量）
     */
    public record SlotNeed(ItemType key, int amount, boolean nested) {
    }

    /**
     * 查询该槽位物品满足的需求。
     * <p>
     * 先看物品自身，自身不匹配时再看它内部装着的容器内容（潜影盒、收纳袋，最多两层），
     * 这样装着所需物品的容器格子也能被标出来。
     *
     * @param projectionView true 查投影容器缺口，false 查仓储需求
     * @return 没有匹配时返回 null
     */
    public SlotNeed matchSlot(ItemStack stack, boolean projectionView) {
        if (stack.isEmpty()) {
            return null;
        }

        Map<ItemType, Integer> source = projectionView ? projectionMissing : remainingNeeded;

        ItemType self = keyOf(stack);
        Integer direct = source.get(self);
        if (direct != null && direct > 0) {
            return new SlotNeed(self, direct, false);
        }

        return nestedNeed(source, stack, MAX_NESTING_DEPTH);
    }

    /** 在容器物品内部递归查找需求物品，返回第一个命中的需求。 */
    private static SlotNeed nestedNeed(Map<ItemType, Integer> needed, ItemStack stack, int depth) {
        if (stack.isEmpty() || depth <= 0 || !HighlightConfig.isReadNestedContainers()) {
            return null;
        }

        for (ItemStack nested : storedItemsOf(stack)) {
            if (nested.isEmpty()) {
                continue;
            }

            ItemType key = keyOf(nested);
            Integer amount = needed.get(key);
            if (amount != null && amount > 0) {
                return new SlotNeed(key, amount, true);
            }

            SlotNeed deeper = nestedNeed(needed, nested, depth - 1);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
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

        Map<ItemType, Integer> source = projectionView ? projectionMissing : remainingNeeded;
        ItemType self = keyOf(stack);
        for (ItemType key : source.keySet()) {
            if (key.getStack().getItem() == stack.getItem() && !key.equals(self)) {
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
        validateStorageContainers();
        matchingStorageContainers.clear();
        remainingNeeded.clear();
        projectionMissing.clear();
        if (currentProjectionContainer == null || currentMissingItems.isEmpty()) {
            return;
        }

        // 1. 背包里已有的数量
        Map<ItemType, Integer> playerCount = new HashMap<>();
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                merge(playerCount, player.getInventory().getStack(i), MAX_NESTING_DEPTH);
            }
        }

        // 2. 投影容器里已有的数量（大箱子的内容统一记在登记坐标下，登记坐标落在哪一半都能取到）
        Map<ItemType, Integer> containerCount = new HashMap<>();
        Inventory projectionInv = storageContainerCache.get(currentProjectionContainer);
        if (projectionInv == null) {
            BlockPos partner = getProjectionContainerPartner();
            if (partner != null) {
                projectionInv = storageContainerCache.get(partner);
            }
        }
        if (projectionInv != null) {
            for (int i = 0; i < projectionInv.size(); i++) {
                merge(containerCount, projectionInv.getStack(i), MAX_NESTING_DEPTH);
            }
        }

        // 3. 需求按总数合并后再扣减，几组相同物品会算在一起；需求侧不展开容器内容
        Map<ItemType, Integer> required = new HashMap<>();
        for (ItemStack missing : currentMissingItems) {
            merge(required, missing, 0);
        }

        for (Map.Entry<ItemType, Integer> entry : required.entrySet()) {
            ItemType key = entry.getKey();
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

    /**
     * 缓存打开的容器内容。
     * <p>
     * 大箱子打开任意一半时界面给的都是整箱数据，这里按箱子朝向拆成两半、各自记到所在的那一格上：
     * 同一份数据不会存两次，统计时也不会把整箱算两遍，被撬掉一半时只丢那一半。
     *
     * @param pos 玩家点击的那一格
     * @param inv 界面提供的容器内容（大箱子时是整箱）
     */
    public void cacheStorageInventory(BlockPos pos, Inventory inv) {
        if (pos == null || inv == null) {
            return;
        }

        BlockPos immutablePos = pos.toImmutable();
        World world = MinecraftClient.getInstance().world;
        BlockState state = world != null ? world.getBlockState(immutablePos) : null;

        // 投影来源（大箱子两半都算）只缓存内容用于扣减需求，不登记为仓储容器，
        // 否则手持触发物品时会在投影容器位置多画一个蓝框
        if (isProjectionContainer(immutablePos)) {
            cacheProjectionContents(inv);
            return;
        }

        // 投影区里其它的箱子（蓝图里同样是容器，但不是当前投影来源）：既不算材料容器，
        // 也不能把它的内容记到投影来源那一格上，直接不管
        if (state != null && PlacementContainerAccess.isSchematicContainer(immutablePos, state)) {
            return;
        }

        if (world != null && state != null && state.getBlock() instanceof ChestBlock) {
            ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
            if (chestType != ChestType.SINGLE && inv.size() % 2 == 0) {
                int halfSize = inv.size() / 2;
                // 与 PlacementContainerAccess 的顺序保持一致：RIGHT 时本格那半在前
                boolean selfFirst = chestType == ChestType.RIGHT;

                putStorageContainer(immutablePos, copyRange(inv, selfFirst ? 0 : halfSize, halfSize), state);

                BlockPos partner = findChestPartner(world, immutablePos, state);
                if (partner != null) {
                    putStorageContainer(partner, copyRange(inv, selfFirst ? halfSize : 0, halfSize), world.getBlockState(partner));
                    storageContainerPartners.put(immutablePos, partner);
                    storageContainerPartners.put(partner, immutablePos);
                }
                return;
            }
        }

        putStorageContainer(immutablePos, inv, state);
    }

    /**
     * 投影容器的内容统一记在登记坐标下：大箱子开哪一半都只存一份，统计时也不会算两遍。
     */
    private void cacheProjectionContents(Inventory inv) {
        BlockPos canonical = currentProjectionContainer;
        if (canonical != null && storageContainerCache.put(canonical, inv) != inv) {
            markDirty();
        }
    }

    /** 大箱子的另一半；不是大箱子、或对面不是箱子时返回 null。 */
    private static BlockPos findChestPartner(World world, BlockPos pos, BlockState state) {
        if (world == null || state == null || !(state.getBlock() instanceof ChestBlock)) {
            return null;
        }
        if (state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return null;
        }

        BlockPos partner = pos.add(ChestBlock.getFacing(state).getVector());
        return world.getBlockState(partner).getBlock() instanceof ChestBlock ? partner : null;
    }

    private void putStorageContainer(BlockPos pos, Inventory inv, BlockState state) {
        if (storageContainerCache.put(pos, inv) != inv) {
            markDirty();
        }
        if (state != null) {
            storageContainerStates.put(pos, state);
        }

        // 注意不能写成 `put(...) != inv || storageContainers.add(pos)`：
        // 左边为真时 || 会短路，导致登记这一句根本不执行
        if (storageContainers.add(pos)) {
            markDirty();
        }
    }

    /** 复制容器的一段槽位，用于把整箱数据拆成两半。 */
    private static SimpleInventory copyRange(Inventory source, int from, int size) {
        SimpleInventory result = new SimpleInventory(size);
        for (int i = 0; i < size; i++) {
            result.setStack(i, source.getStack(from + i).copy());
        }
        return result;
    }

    public boolean isStorageContainer(BlockPos pos) {
        return pos != null && storageContainers.contains(pos.toImmutable());
    }

    public void addStorageContainer(BlockPos pos, BlockState state) {
        if (pos == null) {
            return;
        }

        BlockPos immutablePos = pos.toImmutable();
        if (state != null) {
            storageContainerStates.put(immutablePos, state);
        }
        if (storageContainers.add(immutablePos)) {
            markDirty();
        }
    }

    /** 大箱子的另一半；不是大箱子或另一半已失效时返回 null。 */
    public BlockPos getChestPartner(BlockPos pos) {
        return pos == null ? null : storageContainerPartners.get(pos);
    }

    /**
     * 该登记坐标当前是否还是原来那个容器。
     * <p>
     * 纯客户端做法：只比对登记的方块状态。容器内容不能用来判断——服务端不会把未打开容器的内容下发给客户端；
     * 区块未加载时不做判定，避免走远一趟回来登记被误删。
     */
    public boolean isStorageContainerPresent(BlockPos pos, World world) {
        BlockState expected = storageContainerStates.get(pos);
        if (expected == null || !world.isPosLoaded(pos)) {
            return true;
        }

        BlockState current = world.getBlockState(pos);
        // 只比方块类型：大箱子被撬掉一半后，剩下那一半会从 LEFT/RIGHT 变成 SINGLE，
        // 但那一格自己的数据仍然有效，应该保留并按单格继续绘制（配对由另一半失效时解除）
        return current.getBlock() == expected.getBlock();
    }

    /** 复核登记的仓储容器：被撬掉或换成别的方块就取消登记，顺带清掉过期缓存。 */
    private void validateStorageContainers() {
        World world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }

        for (BlockPos pos : storageContainers) {
            if (!isStorageContainerPresent(pos, world)) {
                removeStorageContainer(pos);
            }
        }
    }

    public void removeStorageContainer(BlockPos pos) {
        if (pos == null) {
            return;
        }

        BlockPos immutablePos = pos.toImmutable();
        boolean changed = storageContainers.remove(immutablePos);
        changed |= storageContainerCache.remove(immutablePos) != null;
        storageContainerStates.remove(immutablePos);

        // 大箱子：解除配对，让剩下的那一半按单箱绘制、并保留自己那半数据
        BlockPos partner = storageContainerPartners.remove(immutablePos);
        if (partner != null) {
            storageContainerPartners.remove(partner);
        }

        if (changed) {
            markDirty();
        }
    }

    public void removeProjectContainer() {
        // 投影区的箱子不能同时是材料容器：取消投影时顺手清掉同一格（大箱子两半都算）
        // 可能残留的材料标记，免得原地留下蓝框
        if (currentProjectionContainer != null) {
            removeStorageContainer(currentProjectionContainer);

            BlockPos partner = getProjectionContainerPartner();
            if (partner != null) {
                removeStorageContainer(partner);
            }
        }

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
        Map<ItemType, Integer> required = new HashMap<>();
        for (ItemStack missingStack : currentMissingItems) {
            merge(required, missingStack, 0);
        }

        Map<ItemType, Integer> available = new HashMap<>();
        for (int i = 0; i < storageInv.size(); i++) {
            merge(available, storageInv.getStack(i), MAX_NESTING_DEPTH);
        }

        for (Map.Entry<ItemType, Integer> entry : required.entrySet()) {
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
        storageContainerStates.clear();
        storageContainerPartners.clear();
        matchingStorageContainers.clear();
        remainingNeeded.clear();
        projectionMissing.clear();
        currentMissingItems.clear();
        currentProjectionContainer = null;
        tempProcessingPos = null;
        dirty = false;
    }
}
