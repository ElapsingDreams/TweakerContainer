package cn.yireve.tweakercontainer.client.data;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.WorldUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.DataQueryHandler;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器内容的获取，按配置走三条路：
 * <ol>
 *   <li><b>内置服务端直读</b>：本地世界（单人、局域网主机）里 {@link WorldUtils#getBestWorld} 给的就是服务端世界，
 *       直接读方块实体。不需要任何其它模组——客户端世界只有开界面时才会被同步，服务端手里才有真数据。</li>
 *   <li><b>服务端查询</b>：发原版 {@code QueryBlockNbtC2SPacket}（{@link DataQueryHandler#queryBlockNbt}），
 *       服务端装了 Servux 时它的 {@code MixinServerPlayNetworkHandler_QueryNbt} 会把要求的权限等级从 2 放到 0，
 *       非 OP 也能拿到方块实体 NBT（含容器物品）。原版服务端对权限不足的查询是静默忽略、不会踢人，
 *       所以可以放心试探：连续几次没回包就把这个服务器标记为不支持，退回开界面抓取。</li>
 *   <li><b>开界面抓取</b>：{@link HighlightState#cacheStorageInventory} 那条老链路，任何服务器都能用，作为回退。</li>
 * </ol>
 * 原版 {@link DataQueryHandler} 一次只挂一个回调，所以这里的查询严格串行：同一时刻只有一个在飞，其余排队。
 */
public final class ContainerDataManager {
    private static final ContainerDataManager INSTANCE = new ContainerDataManager();

    /** 单次查询的等待上限（毫秒） */
    private static final long QUERY_TIMEOUT_MS = 2000L;
    /** 连续多少次查询没回包就认定这个服务器不支持查询 */
    private static final int QUERY_FAILURE_LIMIT = 3;

    /** 已经排队或正在查的坐标 */
    private final Set<BlockPos> pending = ConcurrentHashMap.newKeySet();
    private final Deque<BlockPos> queryQueue = new ArrayDeque<>();

    private BlockPos inFlight;
    private long inFlightSince;
    private int consecutiveFailures;
    private boolean queryUnsupported;

    private ContainerDataManager() {
    }

    public static ContainerDataManager get() {
        return INSTANCE;
    }

    public static void setup() {
        // 查询超时要靠 tick 推进，没有别的定时器
        ClientTickEvents.END_CLIENT_TICK.register(client -> INSTANCE.tick());
    }

    // ---------- 对外入口 ----------

    /**
     * 登记了一个容器之后调这里：按当前生效的数据源把这格（大箱子时含另一半）的内容抓进缓存。
     * 已有内容、或已经在查的位置会直接跳过，所以重复右键不会反复发查询。
     */
    public void ensureContents(BlockPos pos, BlockState state) {
        if (pos == null || state == null) {
            return;
        }

        BlockPos immutablePos = pos.toImmutable();
        if (HighlightState.get().hasContents(immutablePos) || !pending.add(immutablePos)) {
            return;
        }

        switch (this.effectiveSource()) {
            case INTEGRATED -> {
                if (!this.readIntegrated(immutablePos, state)) {
                    pending.remove(immutablePos);
                }
            }
            case SERVUX -> {
                if (!this.requestFromServer(immutablePos)) {
                    pending.remove(immutablePos);
                }
            }
            // SCREEN：什么都不做，等开界面；AUTO 已经在 effectiveSource() 里解析掉了
            case AUTO, SCREEN -> pending.remove(immutablePos);
        }
    }

    /** 当前真正生效的数据源（配置 + 实际环境解析后的结果）。 */
    public ContainerSource effectiveSource() {
        ContainerSource configured = HighlightConfig.getContainerSource();
        boolean local = isLocalWorld();
        boolean remote = this.canReachServer();

        return switch (configured) {
            case AUTO -> local ? ContainerSource.INTEGRATED : (remote ? ContainerSource.SERVUX : ContainerSource.SCREEN);
            case INTEGRATED -> local ? ContainerSource.INTEGRATED : ContainerSource.SCREEN;
            case SERVUX -> remote ? ContainerSource.SERVUX : ContainerSource.SCREEN;
            case SCREEN -> ContainerSource.SCREEN;
        };
    }

    /** 联机时是否有办法向服务端要数据：Servux 通道，或者原版 NBT 查询。 */
    private boolean canReachServer() {
        return ServuxTweaksChannel.get().isAvailable() || this.canQuery();
    }

    /** 掉线、换世界、清空高亮时重置查询状态（"这个服务器不支持查询"的标记也跟着清）。 */
    public void reset() {
        this.pending.clear();
        this.queryQueue.clear();
        this.inFlight = null;
        this.consecutiveFailures = 0;
        this.queryUnsupported = false;
    }

    // ---------- ① 内置服务端 ----------

    /** 本地世界（单人 / 局域网主机）才有内置服务端世界可读。 */
    private static boolean isLocalWorld() {
        return WorldUtils.getBestWorld(MinecraftClient.getInstance()) instanceof ServerWorld;
    }

    private boolean readIntegrated(BlockPos pos, BlockState state) {
        World world = WorldUtils.getBestWorld(MinecraftClient.getInstance());
        if (!(world instanceof ServerWorld) || !world.isPosLoaded(pos)) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof Inventory inventory)) {
            return false;
        }

        HighlightState.get().acceptStorageContents(pos, state, copyOf(inventory));
        this.ensureChestPartner(pos, state);
        return true;
    }

    // ---------- ② 服务端查询 ----------

    /**
     * 联机取数据：优先 Servux 自定义通道（非 OP 也能用），通道不可用再退回原版 NBT 查询（要权限≥2 或服务端放行）。
     *
     * @return 是否已经接下这次请求
     */
    private boolean requestFromServer(BlockPos pos) {
        if (ServuxTweaksChannel.get().requestBlockEntity(pos)) {
            return true;
        }

        if (!this.canQuery()) {
            return false;
        }

        this.queryQueue.add(pos);
        this.pumpQueryQueue();
        return true;
    }

    /** Servux 通道拿到了方块实体 NBT。 */
    void onServuxBlockEntityData(BlockPos pos, NbtCompound nbt) {
        this.pending.remove(pos);
        this.acceptServerContents(pos, nbt);
    }

    /** 服务端给的方块实体 NBT：转成背包写进缓存，大箱子顺手把另一半也带上。 */
    private void acceptServerContents(BlockPos pos, NbtCompound nbt) {
        Inventory inventory = inventoryFromNbt(nbt);
        if (inventory == null) {
            return;
        }

        World world = MinecraftClient.getInstance().world;
        BlockState state = world != null ? world.getBlockState(pos) : null;
        HighlightState.get().acceptStorageContents(pos, state, inventory);
        this.ensureChestPartner(pos, state);
    }

    /** Servux 通道这次请求超时了。 */
    void onServuxTimeout(BlockPos pos) {
        this.pending.remove(pos);
    }

    /** Servux 通道判定不可用：把还在排队的位置交给原版查询，别让它们卡在 pending 里。 */
    void onServuxUnavailable(Iterable<BlockPos> queued) {
        boolean query = this.canQuery();

        for (BlockPos pos : queued) {
            if (query && !HighlightState.get().hasContents(pos)) {
                this.queryQueue.add(pos.toImmutable());
            } else {
                this.pending.remove(pos);
            }
        }

        this.pumpQueryQueue();
    }

    private boolean canQuery() {
        return !this.queryUnsupported && MinecraftClient.getInstance().getNetworkHandler() != null;
    }

    private void pumpQueryQueue() {
        if (this.inFlight != null || this.queryQueue.isEmpty() || this.queryUnsupported) {
            return;
        }

        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) {
            this.failAllQueries();
            return;
        }

        BlockPos pos = this.queryQueue.poll();
        if (!this.pending.contains(pos)) {
            this.pumpQueryQueue();
            return;
        }

        this.inFlight = pos;
        this.inFlightSince = System.currentTimeMillis();
        handler.getDataQueryHandler().queryBlockNbt(pos, nbt -> this.onQueryResponse(pos, nbt));
    }

    private void onQueryResponse(BlockPos pos, NbtCompound nbt) {
        // 不是我们等的那次（回调可能被别的模组顶掉），忽略
        if (!pos.equals(this.inFlight)) {
            return;
        }

        this.inFlight = null;
        this.pending.remove(pos);
        this.consecutiveFailures = 0;
        this.acceptServerContents(pos, nbt);
        this.pumpQueryQueue();
    }

    /** 每个 tick 检查在飞的查询有没有超时；连续失败到上限就不再查，退回开界面。 */
    private void tick() {
        if (this.inFlight == null || System.currentTimeMillis() - this.inFlightSince <= QUERY_TIMEOUT_MS) {
            return;
        }

        BlockPos lost = this.inFlight;
        this.inFlight = null;
        this.pending.remove(lost);

        if (++this.consecutiveFailures >= QUERY_FAILURE_LIMIT) {
            this.failAllQueries();
        }

        this.pumpQueryQueue();
    }

    private void failAllQueries() {
        this.queryUnsupported = true;
        this.pending.clear();
        this.queryQueue.clear();
        this.inFlight = null;
    }

    // ---------- 公共部分 ----------

    /** 大箱子的另一半是另一个方块实体，各读各的；拿到两半后 {@link HighlightState} 会把它们配上对。 */
    private void ensureChestPartner(BlockPos pos, BlockState state) {
        if (state == null || !(state.getBlock() instanceof ChestBlock)
                || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return;
        }

        World world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }

        BlockPos partner = pos.add(ChestBlock.getFacing(state).getVector());
        BlockState partnerState = world.getBlockState(partner);
        if (partnerState.getBlock() instanceof ChestBlock) {
            this.ensureContents(partner, partnerState);
        }
    }

    /** 方块实体的 NBT（带 Items）转成背包；不是容器或没有内容时返回 null。 */
    private static Inventory inventoryFromNbt(NbtCompound nbt) {
        World world = MinecraftClient.getInstance().world;
        if (nbt == null || world == null) {
            return null;
        }

        DynamicRegistryManager registryManager = world.getRegistryManager();
        Inventory inventory = InventoryUtils.getNbtInventory(nbt, -1, registryManager);
        return inventory == null || inventory.size() <= 0 ? null : copyOf(inventory);
    }

    /** 服务端那边的背包是活的，跨 tick 持有不安全，统一拷一份。 */
    private static Inventory copyOf(Inventory source) {
        SimpleInventory copy = new SimpleInventory(source.size());
        for (int i = 0; i < source.size(); i++) {
            copy.setStack(i, source.getStack(i).copy());
        }
        return copy;
    }
}
