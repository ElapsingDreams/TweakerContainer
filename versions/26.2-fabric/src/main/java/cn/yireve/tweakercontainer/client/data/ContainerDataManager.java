package cn.yireve.tweakercontainer.client.data;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import cn.yireve.tweakercontainer.client.utils.ContainerUtils;
import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.RegistryAccess;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器内容的获取，按配置走三条路：
 * <ol>
 *   <li><b>内置服务端直读</b>：本地世界（单人、局域网主机）直接读内置服务端的方块实体。
 *       不需要任何其它模组——客户端世界只有开界面时才会被同步，服务端手里才有真数据。
 *       读不到（区块没加载、方块实体还没同步等）会自动退到服务端查询，单人主机有权限，一定问得到。</li>
 *   <li><b>服务端查询</b>：走 Servux 自定义通道（{@link ServuxTweaksChannel}）。</li>
 *   <li><b>开界面抓取</b>：{@link HighlightState#cacheStorageInventory} 那条老链路，任何服务器都能用，作为回退。</li>
 * </ol>
 * <p>
 * <b>这里刻意不用原版 NBT 查询（{@code QueryBlockNbtC2SPacket}）</b>，虽然那条路在"服务端放行查询"时能用：
 * litematica 的 {@code MixinClientPlayNetworkHandler} 会把客户端收到的<b>每一个</b>
 * {@code NbtQueryResponseS2CPacket} 都交给 {@code EntitiesDataStorage.handleVanillaQueryNbt}，
 * 而那个方法第一件事就是"只要探针还立着，就把权限状态记成有"——它分不清这条回包是谁发的（字节码核对过）。
 * 我们自己的查询一回包就可能让它误以为有查询权限，接着它拿非 OP 的身份去发自己的查询，
 * 那些请求被服务端静默忽略，它自己的待办表/缓存就对不上，随后 {@code tickCache} 里 NPE 崩游戏
 * （0.23.4 与 0.23.7 那行都没有判空）。litematica 是本模组的硬依赖，这条路等于永远在别人的地盘上乱踩，
 * 而 Servux 通道 + 开界面抓取已经够用，所以整条去掉。
 * <p>
 * 另外按配置的间隔（{@code containerRefreshInterval}）定时重取玩家附近已登记的容器，
 * 应对"内容被别的玩家改动"。
 */
public final class ContainerDataManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ContainerDataManager INSTANCE = new ContainerDataManager();

    /** 一轮定时重取最多处理几个容器（可配），实际还受 Servux 通道在飞上限约束 */
    private int refreshCursor;
    /** 只重取玩家这么多格以内的容器 */
    private static final double REFRESH_RANGE_SQ = 64.0D * 64.0D;

    /** 已经排队或正在查的坐标 */
    private final Set<BlockPos> pending = ConcurrentHashMap.newKeySet();

    private int refreshTicks;

    private ContainerDataManager() {
    }

    public static ContainerDataManager get() {
        return INSTANCE;
    }

    public static void setup() {
        // 定时重取靠 tick 推进，没有别的定时器
        ClientTickEvents.END_CLIENT_TICK.register(client -> INSTANCE.tickRefresh());
    }

    // ---------- 对外入口 ----------

    /**
     * 登记了一个容器之后调这里：按当前生效的数据源把这格（大箱子时含另一半）的内容抓进缓存。
     * 已有内容、或已经在查的位置会直接跳过，所以重复右键不会反复发查询。
     */
    public void ensureContents(BlockPos pos, BlockState state) {
        if (pos == null || state == null || HighlightState.get().hasContents(pos.immutable())) {
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

        BlockPos immutablePos = pos.immutable();
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

        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        Level world = client.world;
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
    private static List<BlockPos> nearbyContainers(Player player) {
        Vec3 playerPos = player.getPos();
        List<BlockPos> result = new ArrayList<>();

        for (BlockPos pos : HighlightState.get().getStorageContainers()) {
            if (playerPos.squaredDistanceTo(Vec3.atCenterOf(pos)) <= REFRESH_RANGE_SQ) {
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

    /** 联机时是否有办法向服务端要数据：只有 Servux 通道这一条（原版查询已整条去掉，原因见类注释）。 */
    private boolean canReachServer() {
        return ServuxTweaksChannel.get().isAvailable();
    }

    /** 掉线、换世界、清空高亮时重置在途请求。 */
    public void reset() {
        this.pending.clear();
    }

    // ---------- ① 内置服务端 ----------

    /** 本地世界（单人 / 局域网主机）才有内置服务端世界可读。 */
    private static boolean isLocalWorld() {
        return serverWorld() != null;
    }

    /** 内置服务端里与客户端同维度的那个世界；不是本地世界时返回 null。 */
    private static Level serverWorld() {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        ClientLevel clientWorld = client.world;
        return server == null || clientWorld == null ? null : server.getWorld(clientWorld.getRegistryKey());
    }

    /**
     * 内置服务端读某一格的容器内容。
     * <p>
     * 服务端世界只能在服务端线程上碰，所以扔到服务端线程去读，读完再把结果送回客户端线程收尾。
     */
    private void readIntegrated(BlockPos pos, BlockState state) {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        Level world = serverWorld();

        if (server == null || world == null) {
            LOGGER.warn("内置服务端世界取不到，位置 {} 改走服务端查询", pos);
            this.finishIntegrated(pos, state, null);
            return;
        }

        server.execute(() -> {
            Container inventory = readBlockEntityInventory(world, pos);
            client.execute(() -> this.finishIntegrated(pos, state, inventory));
        });
    }

    /** 内置直读的结果回到客户端线程后收尾：成功写缓存，失败退服务端查询。 */
    private void finishIntegrated(BlockPos pos, BlockState state, Container inventory) {
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
    private static Container readBlockEntityInventory(Level world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);

        if (!(blockEntity instanceof Container) && world.isPosLoaded(pos)) {
            LevelChunk chunk = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            blockEntity = chunk.getBlockEntity(pos, LevelChunk.CreationType.IMMEDIATE);
        }

        if (blockEntity instanceof Container inventory) {
            return copyOf(inventory);
        }

        LOGGER.warn("内置服务端 {} 取不到容器内容：服务端方块={}，方块实体={}，区块已加载={}",
                pos, world.getBlockState(pos), blockEntity, world.isPosLoaded(pos));
        return null;
    }

    // ---------- ② 服务端查询 ----------

    /**
     * 联机取数据：走 Servux 自定义通道（非 OP 也能用，前提是服务端装了 Servux 且放行 tweaks_data）。
     *
     * @return 是否已经接下这次请求
     */
    private boolean requestFromServer(BlockPos pos) {
        return ServuxTweaksChannel.get().requestBlockEntity(pos);
    }

    /** Servux 通道拿到了方块实体 NBT。 */
    void onServuxBlockEntityData(BlockPos pos, CompoundTag nbt) {
        this.pending.remove(pos);
        this.acceptServerContents(pos, nbt);
    }

    /** 服务端给的方块实体 NBT：转成背包写进缓存，大箱子顺手把另一半也带上。 */
    private void acceptServerContents(BlockPos pos, CompoundTag nbt) {
        Level world = Minecraft.getInstance().world;
        BlockState state = world != null ? world.getBlockState(pos) : null;

        Container inventory = inventoryFromNbt(pos, state, nbt);
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

    /** Servux 通道判定不可用：把还在排队的位置从在途表里摘掉，等开界面时再抓。 */
    void onServuxUnavailable(Iterable<BlockPos> queued) {
        for (BlockPos pos : queued) {
            this.pending.remove(pos);
        }
    }

    // ---------- 公共部分 ----------

    /** 大箱子的另一半是另一个方块实体，各读各的；拿到两半后 {@link HighlightState} 会把它们配上对。 */
    private void ensureChestPartner(BlockPos pos, BlockState state) {
        if (state == null || !(state.getBlock() instanceof ChestBlock)
                || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return;
        }

        Level world = Minecraft.getInstance().world;
        if (world == null) {
            return;
        }

        BlockPos partner = pos.add(ChestBlock.getConnectedDirection(state).getVector());
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
    private static Container inventoryFromNbt(BlockPos pos, BlockState state, CompoundTag nbt) {
        Level world = Minecraft.getInstance().world;
        if (world == null) {
            return null;
        }

        int expectedSize = expectedContainerSize(world, pos, state);
        RegistryAccess registryManager = world.getRegistryManager();

        if (nbt != null) {
            Container inventory = InventoryUtils.getNbtInventory(nbt, expectedSize, registryManager);
            if (inventory != null && inventory.size() > 0) {
                return copyOf(inventory);
            }
        }

        return expectedSize > 0 ? new SimpleContainer(expectedSize) : null;
    }

    /** 这一格容器有多少个格子（靠客户端这边的方块实体推，空箱子也能推出 27）。 */
    private static int expectedContainerSize(Level world, BlockPos pos, BlockState state) {
        if (state == null) {
            return -1;
        }

        return ContainerUtils.validateContainer(world, pos, state)
                .map(Container::size)
                .orElse(-1);
    }

    /** 服务端那边的背包是活的，跨 tick 持有不安全，统一拷一份。 */
    private static Container copyOf(Container source) {
        SimpleContainer copy = new SimpleContainer(source.size());
        for (int i = 0; i < source.size(); i++) {
            copy.setStack(i, source.getStack(i).copy());
        }
        return copy;
    }
}
