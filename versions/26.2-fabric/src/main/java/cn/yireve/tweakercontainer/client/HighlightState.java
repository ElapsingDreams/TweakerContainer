package cn.yireve.tweakercontainer.client;

import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import cn.yireve.tweakercontainer.client.data.ContainerDataManager;
import cn.yireve.tweakercontainer.client.features.InventoryOverlay;
import cn.yireve.tweakercontainer.client.features.PlacementContainerAccess;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.data.ItemType;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

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
    // 当前打开的那个投影容器自己的缺口：打开某个投影容器时只看它，不然会把别的箱子要放的东西也标出来
    private final Map<ItemType, Integer> openProjectionMissing = new ConcurrentHashMap<>();
    private final Map<BlockPos, Container> storageContainerCache = new ConcurrentHashMap<>();
    // 登记时的方块状态，用于复核这个位置是否还是原来那个容器
    private final Map<BlockPos, BlockState> storageContainerStates = new ConcurrentHashMap<>();
    // 大箱子的另一半坐标，用于合成一个整体框与半箱失效时只丢一半
    private final Map<BlockPos, BlockPos> storageContainerPartners = new ConcurrentHashMap<>();
    private final Set<BlockPos> storageContainers = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> matchingStorageContainers = ConcurrentHashMap.newKeySet();
    private final List<ItemStack> currentMissingItems = new CopyOnWriteArrayList<>();

    private volatile BlockPos tempProcessingPos;
    // 投影容器：多选。大箱子只记"规范那一半"，另一半放配表里——两半各算一遍需求就重复了
    private final Set<BlockPos> projectionContainers = ConcurrentHashMap.newKeySet();
    private final Map<BlockPos, BlockPos> projectionPartners = new ConcurrentHashMap<>();
    // 角点框选的两个角（角点模式用；只在客户端线程读写，渲染要读所以是 volatile）
    private volatile BlockPos projectionCornerStart;
    private volatile BlockPos projectionCornerEnd;
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

    /** 所有选中的投影容器（大箱子只有规范那一半）。 */
    public Set<BlockPos> getProjectionContainers() {
        return Collections.unmodifiableSet(projectionContainers);
    }

    /** 该投影容器大箱子的另一半；不是大箱子、或对面不是箱子时返回 null。 */
    public BlockPos getProjectionContainerPartner(BlockPos pos) {
        if (pos == null) {
            return null;
        }

        BlockPos partner = projectionPartners.get(pos);
        if (partner != null) {
            return partner;
        }

        Level world = Minecraft.getInstance().level;
        return world == null ? null : findChestPartner(world, pos, world.getBlockState(pos));
    }

    /**
     * 该坐标归到哪个投影容器上（大箱子的任意一半都归到规范那一半）。
     *
     * @return 不是投影容器时返回 null
     */
    public BlockPos canonicalProjectionContainer(BlockPos pos) {
        if (pos == null || projectionContainers.isEmpty()) {
            return null;
        }

        BlockPos immutablePos = pos.immutable();
        if (projectionContainers.contains(immutablePos)) {
            return immutablePos;
        }

        for (Map.Entry<BlockPos, BlockPos> entry : projectionPartners.entrySet()) {
            if (immutablePos.equals(entry.getValue())) {
                return entry.getKey();
            }
        }

        return null;
    }

    /** 该坐标是不是投影容器：大箱子的任意一半都算。 */
    public boolean isProjectionContainer(BlockPos pos) {
        return canonicalProjectionContainer(pos) != null;
    }

    // ---------- 角点框选 ----------

    /** 选框起点（角点模式左键点的位置）；没设时返回 null。 */
    public BlockPos getProjectionCornerStart() {
        return projectionCornerStart;
    }

    /** 选框终点（角点模式右键点的位置）；没设时返回 null。 */
    public BlockPos getProjectionCornerEnd() {
        return projectionCornerEnd;
    }

    /**
     * 记录选框的一角。
     * <p>
     * 重新点起点就是重新起一个框，会把旧的终点清掉（否则会拿旧终点直接框选）。
     *
     * @param start true = 起点（左键），false = 终点（右键）
     * @return 两个角是否都齐了
     */
    public boolean setProjectionCorner(BlockPos pos, boolean start) {
        if (pos == null) {
            return false;
        }

        if (start) {
            projectionCornerStart = pos.immutable();
            projectionCornerEnd = null;
        } else {
            projectionCornerEnd = pos.immutable();
        }

        return projectionCornerStart != null && projectionCornerEnd != null;
    }

    /**
     * 投影侧要看哪份需求。
     * <p>
     * 打开着某个投影容器时只看那个容器自己的缺口——多选时所有选中箱子的缺口是合在一起的，
     * 直接拿合计去标会把"别的箱子要放的东西"也标在这个界面上，看着就像这箱子缺一堆它其实不缺的。
     */
    private Map<ItemType, Integer> projectionNeedSource() {
        return canonicalProjectionContainer(InventoryOverlay.getCurrentContainerPos()) != null
                ? openProjectionMissing
                : projectionMissing;
    }

    /**
     * 重算"当前打开的投影容器"自己的缺口：它的蓝图内容减去它自己现在已经放进去的。
     * <p>
     * 口径与单容器时期一致：不扣背包——背包里有多少不影响"这个箱子还该放进去多少"。
     */
    private void updateOpenProjectionMissing() {
        openProjectionMissing.clear();

        Level world = Minecraft.getInstance().level;
        BlockPos openProjection = canonicalProjectionContainer(InventoryOverlay.getCurrentContainerPos());
        if (world == null || openProjection == null) {
            return;
        }

        Optional<SimpleContainer> schematicInv =
                PlacementContainerAccess.getSchematicInventory(openProjection, world.getBlockState(openProjection));
        if (schematicInv.isEmpty()) {
            return;
        }

        Map<ItemType, Integer> required = new HashMap<>();
        Container inventory = schematicInv.get();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            merge(required, inventory.getItem(i), 0);
        }

        Map<ItemType, Integer> present = new HashMap<>();
        Container cached = storageContainerCache.get(openProjection);
        if (cached != null) {
            for (int i = 0; i < cached.getContainerSize(); i++) {
                merge(present, cached.getItem(i), MAX_NESTING_DEPTH);
            }
        }

        for (Map.Entry<ItemType, Integer> entry : required.entrySet()) {
            int missing = entry.getValue() - present.getOrDefault(entry.getKey(), 0);
            if (missing > 0) {
                openProjectionMissing.put(entry.getKey(), missing);
            }
        }
    }

    /**
     * 按当前两个角点框选：框内每一个容器，按和"逐个右键"完全一样的口径分流——
     * 蓝图里也是容器的进投影集合，其余登记为材料容器。
     *
     * @return 本次新增的数量（投影 / 材料各多少）
     */
    public BoxSelection selectContainersInBox() {
        BlockPos start = projectionCornerStart;
        BlockPos end = projectionCornerEnd;
        Level world = Minecraft.getInstance().level;
        if (start == null || end == null || world == null) {
            return new BoxSelection(0, 0);
        }

        int minX = Math.min(start.getX(), end.getX());
        int minY = Math.min(start.getY(), end.getY());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxX = Math.max(start.getX(), end.getX());
        int maxY = Math.max(start.getY(), end.getY());
        int maxZ = Math.max(start.getZ(), end.getZ());

        int projections = 0;
        int storages = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    cursor.set(x, y, z);

                    // 先按方块状态便宜地筛一遍：不是方块实体就直接跳过，省掉后面取容器内容那套
                    BlockState state = world.getBlockState(cursor);
                    if (!(state.getBlock() instanceof EntityBlock)
                            || !InventoryOverlay.isContainer(world, cursor)) {
                        continue;
                    }

                    BlockPos pos = cursor.immutable();
                    if (PlacementContainerAccess.isSchematicContainer(pos, state)) {
                        if (canonicalProjectionContainer(pos) == null && addProjectionContainer(pos, false)) {
                            projections++;
                        }
                    } else if (!isStorageContainer(pos)) {
                        addStorageContainer(pos, state);
                        storages++;
                    }
                }
            }
        }

        return new BoxSelection(projections, storages);
    }

    /** 框选结果：新增了多少个投影容器、多少个材料容器。 */
    public record BoxSelection(int projections, int storages) {
    }

    /** 清掉选框（框选完成后、切换模式、断线时用）。 */
    public void clearProjectionCorners() {
        projectionCornerStart = null;
        projectionCornerEnd = null;
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

        Map<ItemType, Integer> source = projectionView ? projectionNeedSource() : remainingNeeded;

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

        Map<ItemType, Integer> source = projectionView ? projectionNeedSource() : remainingNeeded;
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
        pairRegisteredChests();
        matchingStorageContainers.clear();
        remainingNeeded.clear();
        projectionMissing.clear();
        updateOpenProjectionMissing();
        if (projectionContainers.isEmpty() || currentMissingItems.isEmpty()) {
            return;
        }

        // 1. 背包里已有的数量
        Map<ItemType, Integer> playerCount = new HashMap<>();
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                merge(playerCount, player.getInventory().getItem(i), MAX_NESTING_DEPTH);
            }
        }

        // 2. 投影容器里已有的数量：所有选中的投影容器一起算，大箱子的内容记在规范那一半
        Map<ItemType, Integer> containerCount = new HashMap<>();
        for (BlockPos projection : projectionContainers) {
            Container projectionInv = storageContainerCache.get(projection);
            if (projectionInv == null) {
                continue;
            }

            for (int i = 0; i < projectionInv.getContainerSize(); i++) {
                merge(containerCount, projectionInv.getItem(i), MAX_NESTING_DEPTH);
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
            Container storageInv = storageContainerCache.get(storagePos);
            if (storageInv == null) {
                continue;
            }

            for (int i = 0; i < storageInv.getContainerSize(); i++) {
                ItemStack storedStack = storageInv.getItem(i);
                if (containsNeeded(remainingNeeded, storedStack, MAX_NESTING_DEPTH)) {
                    matchingStorageContainers.add(storagePos);

                    // 大箱子：一半有料就把另一半也标上，渲染那边才能合成整箱框，
                    // 否则（空的那半不在名单里）会退化成只框一半
                    BlockPos partner = getChestPartner(storagePos);
                    if (partner != null) {
                        matchingStorageContainers.add(partner);
                    }
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
        tempProcessingPos = pos != null ? pos.immutable() : null;
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
    public void cacheStorageInventory(BlockPos pos, Container inv) {
        if (pos == null || inv == null) {
            return;
        }

        BlockPos immutablePos = pos.immutable();
        Level world = Minecraft.getInstance().level;
        BlockState state = world != null ? world.getBlockState(immutablePos) : null;

        // 投影容器（大箱子两半都算）只缓存内容用于扣减需求，不登记为材料容器，
        // 否则手持触发物品时会在投影容器位置多画一个蓝框
        BlockPos projection = canonicalProjectionContainer(immutablePos);
        if (projection != null) {
            acceptProjectionContents(projection, inv);
            return;
        }

        // 投影区里其它的箱子（蓝图里同样是容器，但不是当前投影来源）：既不算材料容器，
        // 也不能把它的内容记到投影来源那一格上，直接不管
        if (state != null && PlacementContainerAccess.isSchematicContainer(immutablePos, state)) {
            return;
        }

        if (world != null && state != null && state.getBlock() instanceof ChestBlock) {
            ChestType chestType = state.getValue(ChestBlock.TYPE);
            if (chestType != ChestType.SINGLE && inv.getContainerSize() % 2 == 0) {
                int halfSize = inv.getContainerSize() / 2;
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

    /** 大箱子的另一半；不是大箱子、或对面不是箱子时返回 null。 */
    private static BlockPos findChestPartner(Level world, BlockPos pos, BlockState state) {
        if (world == null || state == null || !(state.getBlock() instanceof ChestBlock)) {
            return null;
        }
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }

        BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
        return world.getBlockState(partner).getBlock() instanceof ChestBlock ? partner : null;
    }

    private void putStorageContainer(BlockPos pos, Container inv, BlockState state) {
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
    private static SimpleContainer copyRange(Container source, int from, int size) {
        SimpleContainer result = new SimpleContainer(size);
        for (int i = 0; i < size; i++) {
            result.setItem(i, source.getItem(from + i).copy());
        }
        return result;
    }

    /**
     * 把"两格都登记了、却没配上对"的大箱子补上配对。
     * <p>
     * 正常路径（{@link #acceptStorageContents}）拿到两半内容时就会配对，但两半可能来自不同来源或先后顺序
     * 不凑巧（比如另一半那次请求失败后靠别的路径登记上来），漏配就会画成两个小框。这里每次重算前扫一遍兜底。
     */
    public void pairRegisteredChests() {
        Level world = Minecraft.getInstance().level;
        if (world == null || storageContainers.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (BlockPos pos : storageContainers) {
            if (storageContainerPartners.containsKey(pos)) {
                continue;
            }

            BlockPos partner = findChestPartner(world, pos, world.getBlockState(pos));
            if (partner != null && storageContainers.contains(partner)) {
                storageContainerPartners.put(pos, partner);
                storageContainerPartners.put(partner, pos);
                changed = true;
            }
        }

        if (changed) {
            markDirty();
        }
    }

    public boolean isStorageContainer(BlockPos pos) {
        return pos != null && storageContainers.contains(pos.immutable());
    }

    public void addStorageContainer(BlockPos pos, BlockState state) {
        if (pos == null) {
            return;
        }

        BlockPos immutablePos = pos.immutable();
        if (state != null) {
            storageContainerStates.put(immutablePos, state);
        }
        if (storageContainers.add(immutablePos)) {
            markDirty();
        }

        // 登记后立刻按配置的数据源取一次内容：本地世界直读内置服务端，联机走服务端查询，
        // 两条都拿不到就退回开界面抓取那条老链路
        ContainerDataManager.get().ensureContents(immutablePos, state);
    }

    /** 这一格的容器内容是否已经有缓存（不管来自哪条数据源；投影容器按规范那半找）。 */
    public boolean hasContents(BlockPos pos) {
        if (pos == null) {
            return false;
        }

        BlockPos projection = canonicalProjectionContainer(pos);
        return storageContainerCache.containsKey(projection != null ? projection : pos.immutable());
    }

    /** 投影容器（世界那一格）的内容：只记"已经放进去多少"，不登记为材料容器。 */
    public void acceptProjectionContents(BlockPos projection, Container inv) {
        if (projection == null || inv == null) {
            return;
        }

        if (storageContainerCache.put(projection, inv) != inv) {
            markDirty();
        }
    }

    /**
     * 数据源直接读到的某一格容器内容：写进缓存并登记。
     * <p>
     * 大箱子两半是各自的方块实体、各读各的，两半都拿到以后在这里配上对，让它们合成一个整箱框。
     * 投影容器走 {@link #acceptProjectionContents}：只缓存内容，不登记成材料容器。
     */
    public void acceptStorageContents(BlockPos pos, BlockState state, Container inv) {
        if (pos == null || inv == null) {
            return;
        }

        BlockPos immutablePos = pos.immutable();

        BlockPos projection = canonicalProjectionContainer(immutablePos);
        if (projection != null) {
            acceptProjectionContents(projection, inv);
            return;
        }

        putStorageContainer(immutablePos, inv, state);

        BlockPos partner = findChestPartner(Minecraft.getInstance().level, immutablePos, state);
        if (partner != null && storageContainerCache.containsKey(partner)) {
            storageContainerPartners.put(immutablePos, partner);
            storageContainerPartners.put(partner, immutablePos);
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
    public boolean isStorageContainerPresent(BlockPos pos, Level world) {
        BlockState expected = storageContainerStates.get(pos);
        if (expected == null || !world.isLoaded(pos)) {
            return true;
        }

        BlockState current = world.getBlockState(pos);
        // 只比方块类型：大箱子被撬掉一半后，剩下那一半会从 LEFT/RIGHT 变成 SINGLE，
        // 但那一格自己的数据仍然有效，应该保留并按单格继续绘制（配对由另一半失效时解除）
        return current.getBlock() == expected.getBlock();
    }

    /** 复核登记的仓储容器：被撬掉或换成别的方块就取消登记，顺带清掉过期缓存。 */
    private void validateStorageContainers() {
        Level world = Minecraft.getInstance().level;
        if (world == null) {
            return;
        }

        for (BlockPos pos : storageContainers) {
            if (!isStorageContainerPresent(pos, world)) {
                removeStorageContainer(pos);
            }
        }
    }

    /**
     * 取消仓储登记。
     *
     * @return 是否真的清掉了东西（没登记过时返回 false）
     */
    public boolean removeStorageContainer(BlockPos pos) {
        if (pos == null) {
            return false;
        }

        BlockPos immutablePos = pos.immutable();
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
        return changed;
    }

    /**
     * 手动清除材料标记：大箱子时连同另一半一起清掉，点哪一半都清整箱。
     * <p>
     * 与 {@link #removeStorageContainer} 的区别：那个只清一格，留给"被撬掉一半后只丢那一半"用。
     *
     * @return 是否真的清掉了东西（没登记过时返回 false）
     */
    public boolean removeWholeStorageContainer(BlockPos pos) {
        if (pos == null) {
            return false;
        }

        BlockPos immutablePos = pos.immutable();
        BlockPos partner = getChestPartner(immutablePos);
        if (partner == null) {
            // 还没建立配对信息时，按方块朝向再找一次大箱子的另一半
            Level world = Minecraft.getInstance().level;
            partner = findChestPartner(world, immutablePos, world != null ? world.getBlockState(immutablePos) : null);
        }

        boolean changed = removeStorageContainer(immutablePos);
        if (partner != null) {
            changed |= removeStorageContainer(partner);
        }
        return changed;
    }

    // ---------- 投影容器（多选） ----------

    /**
     * 加入一个投影容器。
     * <p>
     * 大箱子只记规范那一半：点另一半、或点已在集合里的箱子，都不会重复加入，
     * 否则同一箱内容会被算两遍需求。
     *
     * @param replace true = 单选模式，先把已有选择清掉
     * @return 是否成功（蓝图里那格不是容器、或读不出内容时返回 false）
     */
    public boolean addProjectionContainer(BlockPos pos, boolean replace) {
        if (pos == null) {
            return false;
        }

        BlockPos immutablePos = pos.immutable();
        Level world = Minecraft.getInstance().level;
        if (world == null) {
            return false;
        }

        // 已经在选中的箱子里（含大箱子另一半）就当作已选
        if (canonicalProjectionContainer(immutablePos) != null) {
            return true;
        }

        // 投影内容取自蓝图，世界方块只用于确定容器类型
        BlockState state = world.getBlockState(immutablePos);
        Optional<SimpleContainer> schematicInv = PlacementContainerAccess.getSchematicInventory(immutablePos, state);
        if (schematicInv.isEmpty() || isInventoryEmpty(schematicInv.get())) {
            return false;
        }

        if (replace) {
            clearProjectionContainers();
        }

        projectionContainers.add(immutablePos);

        // 记下大箱子另一半，渲染要合成整箱框、"任意一半都算投影容器"也要靠它
        BlockPos partner = findChestPartner(world, immutablePos, state);
        if (partner != null) {
            projectionPartners.put(immutablePos, partner);
        }

        rebuildProjectionRequirements();
        markDirty();

        // 世界那格的内容也顺手取一次（数据源可用时），用于算"已经放进去多少"
        ContainerDataManager.get().ensureContents(immutablePos, state);
        return true;
    }

    /** 切换一个投影容器：已选就取消、没选就加入。多选模式用。 */
    public boolean toggleProjectionContainer(BlockPos pos) {
        BlockPos canonical = canonicalProjectionContainer(pos);
        if (canonical != null) {
            return removeProjectionContainer(canonical);
        }

        return addProjectionContainer(pos, false);
    }

    /**
     * 取消一个投影容器（大箱子的任意一半都指向同一个）。
     *
     * @return 是否真的取消了（本来就没选中时返回 false）
     */
    public boolean removeProjectionContainer(BlockPos pos) {
        BlockPos canonical = canonicalProjectionContainer(pos);
        if (canonical == null) {
            return false;
        }

        projectionContainers.remove(canonical);
        BlockPos partner = projectionPartners.remove(canonical);

        // 投影区的箱子不能同时是材料容器：取消投影时顺手清掉同一格（大箱子两半都算）
        // 可能残留的材料标记，免得原地留下蓝框
        removeStorageContainer(canonical);
        if (partner != null) {
            removeStorageContainer(partner);
        }

        rebuildProjectionRequirements();
        projectionMissing.clear();
        markDirty();
        return true;
    }

    /** 清空全部投影选择。 */
    public void clearProjectionContainers() {
        if (projectionContainers.isEmpty()) {
            return;
        }

        for (BlockPos projection : projectionContainers) {
            removeStorageContainer(projection);

            BlockPos partner = projectionPartners.get(projection);
            if (partner != null) {
                removeStorageContainer(partner);
            }
        }

        projectionContainers.clear();
        projectionPartners.clear();
        rebuildProjectionRequirements();
        projectionMissing.clear();
        markDirty();
    }

    /** 按当前选中的投影容器重建需求清单（各投影容器的蓝图内容拼在一起）。 */
    private void rebuildProjectionRequirements() {
        currentMissingItems.clear();

        Level world = Minecraft.getInstance().level;
        if (world == null) {
            return;
        }

        for (BlockPos projection : projectionContainers) {
            Optional<SimpleContainer> schematicInv =
                    PlacementContainerAccess.getSchematicInventory(projection, world.getBlockState(projection));
            if (schematicInv.isEmpty()) {
                continue;
            }

            Container inventory = schematicInv.get();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    currentMissingItems.add(stack.copy());
                }
            }
        }
    }

    private boolean isInventoryEmpty(Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 该仓储箱已能满足投影需求时，从仓储名单中移除。
     */
    public void checkAndRemoveSatisfiedContainer(BlockPos storagePos) {
        if (storagePos == null || projectionContainers.isEmpty() || currentMissingItems.isEmpty()) {
            return;
        }

        BlockPos immutableStoragePos = storagePos.immutable();
        Container storageInv = storageContainerCache.get(immutableStoragePos);
        if (storageInv == null) {
            return;
        }

        // 需求与可提供数量都按总数汇总后再逐个比较
        Map<ItemType, Integer> required = new HashMap<>();
        for (ItemStack missingStack : currentMissingItems) {
            merge(required, missingStack, 0);
        }

        Map<ItemType, Integer> available = new HashMap<>();
        for (int i = 0; i < storageInv.getContainerSize(); i++) {
            merge(available, storageInv.getItem(i), MAX_NESTING_DEPTH);
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
        projectionContainers.clear();
        projectionPartners.clear();
        clearProjectionCorners();
        tempProcessingPos = null;
        dirty = false;
        ContainerDataManager.get().reset();
    }
}
