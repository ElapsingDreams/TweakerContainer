package cn.yireve.tweakercontainer.client.data;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import cn.yireve.tweakercontainer.client.utils.ContainerUtils;
import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.DataQueryHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器内容的获取，按配置走三条路：
 * <ol>
 *   <li><b>内置服务端直读</b>：本地世界（单人、局域网主机）直接读内置服务端的方块实体。
 *       不需要任何其它模组——客户端世界只有开界面时才会被同步，服务端手里才有真数据。
 *       读不到（区块没加载、方块实体还没同步等）会自动退到服务端查询，单人主机有权限，一定问得到。</li>
 *   <li><b>服务端查询</b>：先走 Servux 自定义通道，再退原版 {@code QueryBlockNbtC2SPacket}
 *       （{@link DataQueryHandler#queryBlockNbt}）。原版服务端对权限不足的查询是静默忽略、不会踢人，
 *       所以可以放心试探：连续几次没回包就把这个服务器标记为不支持，退回开界面抓取。</li>
 *   <li><b>开界面抓取</b>：{@link HighlightState#cacheStorageInventory} 那条老链路，任何服务器都能用，作为回退。</li>
 * </ol>
 * 原版 {@link DataQueryHandler} 一次只挂一个回调，所以这里的查询严格串行：同一时刻只有一个在飞，其余排队。
 * <p>
 * 另外按配置的间隔（{@code containerRefreshInterval}）定时重取玩家附近已登记的容器，
 * 应对"内容被别的玩家改动"。
 */
public final class ContainerDataManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ContainerDataManager INSTANCE = new ContainerDataManager();

    /** 单次查询的等待上限（毫秒） */
    private static final long QUERY_TIMEOUT_MS = 2000L;
    /** 连续多少次查询没回包就认定这个服务器不支持查询 */
    private static final int QUERY_FAILURE_LIMIT = 3;
    /** 一轮定时重取最多处理几个容器（可配），实际还受 Servux 通道在飞上限约束 */
    private int refreshCursor;
    /** 只重取玩家这么多格以内的容器 */
    private static final double REFRESH_RANGE_SQ = 64.0D * 64.0D;

    /** 已经排队或正在查的坐标 */
    private final Set<BlockPos> pending = ConcurrentHashMap.newKeySet();
    private final Deque<BlockPos> queryQueue = new ArrayDeque<>();

    private BlockPos inFlight;
    private long inFlightSince;
    private int consecutiveFailures;
    private boolean queryUnsupported;
    private int refreshTicks;

    private ContainerDataManager() {
    }

    public static ContainerDataManager get() {
        return INSTANCE;
    }

    public static void setup() {
        // 查询超时与定时重取都靠 tick 推进，没有别的定时器
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            INSTANCE.tick();
            INSTANCE.tickRefresh();
        });
    }

    // ---------- 对外入口 ----------

    /**
     * 登记了一个容器之后调这里：按当前生效的数据源把这格（大箱子时含另一半）的内容抓进缓存。
     * 已有内容、或已经在查的位置会直接跳过，所以重复右键不会反复发查询。
     */
    public void ensureContents(BlockPos pos, BlockState state) {
        if (pos == null || state == null || HighlightState.get().hasContents(pos.toImmutable())) {
            return;
        }

        this.fetch(pos, state);
    }

    /** 定时重取：不看缓存里有没有，照数据源再取一次（应对内容被别的玩家改动）。 */
    public void refreshContents(BlockPos pos, BlockState state) {
        this.fetch(pos, state);
    }

    private void fetch(BlockPos pos, BlockState state) {
        if (pos == null || state == null) {
            return;
        }

        BlockPos immutablePos = pos.toImmutable();
        if (!this.pending.add(immutablePos)) {
            return; // 这一格已经在取了
        }

        switch (this.effectiveSource()) {
            case INTEGRATED -> this.readIntegrated(immutablePos, state); // 读在服务端线程上跑，回调里收尾
            case SERVUX -> {
                if (!this.requestFromServer(immutablePos)) {
                    this.pending.remove(immutablePos);
                }
            }
            // SCREEN：什么都不做，等开界面；AUTO 已经在 effectiveSource() 里解析掉了
            case AUTO, SCREEN -> this.pending.remove(immutablePos);
        }
    }

    /** 按配置的间隔、小批量轮询玩家附近的容器。 */
    private void tickRefresh() {
        int interval = HighlightConfig.getContainerRefreshInterval();
        if (interval <= 0 || !HighlightConfig.isEnabled() || ++this.refreshTicks < interval) {
            return;
        }

        this.refreshTicks = 0;

        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity player = client.player;
        World world = client.world;
        if (player == null || world == null) {
            return;
        }

        // 在飞请求上限由 Servux 通道决定：只补还能塞进去的那几个，一轮不全刷
        int room = ServuxTweaksChannel.MAX_PENDING_REQUESTS - this.pending.size();
        int batch = Math.min(HighlightConfig.getContainerRefreshBatchSize(), room);
        if (batch <= 0) {
            return;
        }

        List<BlockPos> nearby = nearbyContainers(player);
        if (nearby.isEmpty()) {
            this.refreshCursor = 0;
            return;
        }
        if (this.refreshCursor >= nearby.size()) {
            this.refreshCursor = 0;
        }

        for (int i = 0; i < nearby.size() && batch > 0; i++) {
            int index = (this.refreshCursor + i) % nearby.size();
            BlockPos pos = nearby.get(index);

            // 游标停在下一个待处理的位置上，下一轮从这里接着轮询
            this.refreshCursor = (index + 1) % nearby.size();

            if (this.pending.contains(pos)) {
                continue;
            }

            BlockState state = world.getBlockState(pos);
            this.refreshContents(pos, state);
            // 上一轮没配上对的大箱子（另一半那次请求失败）在这里补上，否则会一直只画一半
            this.ensureChestPartner(pos, state);
            batch--;
        }
    }

    /** 玩家附近已登记的容器，按坐标排序让轮询顺序稳定。 */
    private static List<BlockPos> nearbyContainers(PlayerEntity player) {
        Vec3d playerPos = player.getPos();
        List<BlockPos> result = new ArrayList<>();

        for (BlockPos pos : HighlightState.get().getStorageContainers()) {
            if (playerPos.squaredDistanceTo(Vec3d.ofCenter(pos)) <= REFRESH_RANGE_SQ) {
                result.add(pos);
            }
        }

        result.sort(Comparator.comparingLong(BlockPos::asLong));
        return result;
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
        return serverWorld() != null;
    }

    /** 内置服务端里与客户端同维度的那个世界；不是本地世界时返回 null。 */
    private static World serverWorld() {
        MinecraftClient client = MinecraftClient.getInstance();
        IntegratedServer server = client.getServer();
        ClientWorld clientWorld = client.world;
        return server == null || clientWorld == null ? null : server.getWorld(clientWorld.getRegistryKey());
    }

    /**
     * 内置服务端读某一格的容器内容。
     * <p>
     * 服务端世界只能在服务端线程上碰，所以扔到服务端线程去读，读完再把结果送回客户端线程收尾。
     */
    private void readIntegrated(BlockPos pos, BlockState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        IntegratedServer server = client.getServer();
        World world = serverWorld();

        if (server == null || world == null) {
            LOGGER.warn("内置服务端世界取不到，位置 {} 改走服务端查询", pos);
            this.finishIntegrated(pos, state, null);
            return;
        }

        server.execute(() -> {
            Inventory inventory = readBlockEntityInventory(world, pos);
            client.execute(() -> this.finishIntegrated(pos, state, inventory));
        });
    }

    /** 内置直读的结果回到客户端线程后收尾：成功写缓存，失败退服务端查询。 */
    private void finishIntegrated(BlockPos pos, BlockState state, Inventory inventory) {
        this.pending.remove(pos);

        if (inventory != null) {
            HighlightState.get().acceptStorageContents(pos, state, inventory);
            this.ensureChestPartner(pos, state);
            return;
        }

        if (!this.requestFromServer(pos)) {
            this.pending.remove(pos);
        }
    }

    /**
     * 在服务端线程上取方块实体的背包。
     * <p>
     * 区块在、方块实体却还没建出来时会催一次（`CreationType.IMMEDIATE`）；
     * 单人世界这些数据本来就在内存里，代价很小。
     */
    private static Inventory readBlockEntityInventory(World world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);

        if (!(blockEntity instanceof Inventory) && world.isPosLoaded(pos)) {
            WorldChunk chunk = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            blockEntity = chunk.getBlockEntity(pos, WorldChunk.CreationType.IMMEDIATE);
        }

        if (blockEntity instanceof Inventory inventory) {
            return copyOf(inventory);
        }

        LOGGER.warn("内置服务端 {} 取不到容器内容：服务端方块={}，方块实体={}，区块已加载={}",
                pos, world.getBlockState(pos), blockEntity, world.isPosLoaded(pos));
        return null;
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
        World world = MinecraftClient.getInstance().world;
        BlockState state = world != null ? world.getBlockState(pos) : null;

        Inventory inventory = inventoryFromNbt(pos, state, nbt);
        if (inventory == null) {
            return;
        }

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

    /**
     * 方块实体的 NBT（带 Items）转成背包。
     * <p>
     * 注意空容器：malilib 的 {@code getNbtInventory} 对空内容会给 null，直接当成"没数据"的话，
     * 大箱子里那一半空的就永远登记不上、也不会把另一半带出来（联机下"点有料的那半、空的那半不跟着框"就是这个原因）。
     * 所以转换不出来时，按客户端这边的容器格子数建一个空背包——空是内容，不是没数据。
     */
    private static Inventory inventoryFromNbt(BlockPos pos, BlockState state, NbtCompound nbt) {
        World world = MinecraftClient.getInstance().world;
        if (world == null) {
            return null;
        }

        int expectedSize = expectedContainerSize(world, pos, state);
        DynamicRegistryManager registryManager = world.getRegistryManager();

        if (nbt != null) {
            Inventory inventory = InventoryUtils.getNbtInventory(nbt, expectedSize, registryManager);
            if (inventory != null && inventory.size() > 0) {
                return copyOf(inventory);
            }
        }

        return expectedSize > 0 ? new SimpleInventory(expectedSize) : null;
    }

    /** 这一格容器有多少个格子（靠客户端这边的方块实体推，空箱子也能推出 27）。 */
    private static int expectedContainerSize(World world, BlockPos pos, BlockState state) {
        if (state == null) {
            return -1;
        }

        return ContainerUtils.validateContainer(world, pos, state)
                .map(Inventory::size)
                .orElse(-1);
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
