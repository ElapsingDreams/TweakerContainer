package cn.yireve.tweakercontainer.client.data;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自己发原版方块 NBT 查询（{@code QueryBlockNbtC2SPacket}），不走 {@code DataQueryHandler}。
 * <p>
 * 为什么不能走原版那个入口：{@code DataQueryHandler} 里只有<b>一个</b>回调槽
 * （{@code expectedTransactionId} + {@code callback} 两个字段，全客户端共用），
 * {@code handleQueryResponse} 还要求 id 完全相等。谁后发谁就把前一个的回调顶掉，
 * 前一个的响应因为 id 对不上被直接丢掉。
 * <p>
 * 这条链路会把别的模组弄崩：litematica 的实体数据同步用原版查询当后备，
 * 发完包之后把 {@code malilib_currentTransactionId()} 记进自己的待办表，靠回调结算；
 * 回调被我们顶掉后它那边永远等不到回包，下一次 tick 清理缓存时
 * {@code EntitiesDataStorage.tickCache} 里那个 {@code pair} 就是 null → NPE 崩游戏。
 * tweakeroo 的原版查询后备同样用这个槽，谁顶谁都不行。
 * <p>
 * 所以这里自己发包、自己按 transactionId 收：id 从 {@link #ID_BASE} 起分配，
 * 和原版那条从 -1 开始递增的序列彻底隔开，回包由 {@code NbtQueryResponseS2CPacketMixin}
 * 在包对象刚建好时交回来。两边各用各的 id，互不干扰。
 */
public final class VanillaQueryChannel {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 自用 id 起点：原版的序列是 -1、0、1…… 从这儿开始不可能撞上。 */
    private static final int ID_BASE = 1 << 30;

    private static final VanillaQueryChannel INSTANCE = new VanillaQueryChannel();

    /** 发出去、等回包的 transactionId → 坐标 */
    private final Map<Integer, BlockPos> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(ID_BASE);

    private VanillaQueryChannel() {
    }

    public static VanillaQueryChannel get() {
        return INSTANCE;
    }

    public static void setup() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.reset());
    }

    /**
     * 发一次方块 NBT 查询。
     * <p>
     * 服务端对权限不足的查询是静默忽略（{@code hasPermissionLevel(2)} 不满足直接 return），
     * 所以调用方得自己按超时处理，这一点和走原版入口时一样。
     *
     * @return 是否发出去了（没连服务器时返回 false）
     */
    public boolean send(BlockPos pos) {
        if (pos == null) {
            return false;
        }

        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) {
            return false;
        }

        int transactionId = this.nextId.getAndIncrement();
        BlockPos immutablePos = pos.toImmutable();
        this.pending.put(transactionId, immutablePos);
        handler.sendPacket(new QueryBlockNbtC2SPacket(transactionId, immutablePos));
        return true;
    }

    /**
     * 收到 NBT 查询响应，由 {@code NbtQueryResponseS2CPacketMixin} 在网络线程调用。
     * <p>
     * id 不在表里就当没看见——这个包是所有查询共用的，原版和别的模组的响应不能乱碰。
     * （服务端在方块实体取不到时会回一个 nbt 为 null 的响应，照原样传下去。）
     */
    public void onIncoming(int transactionId, @Nullable NbtCompound nbt) {
        BlockPos pos = this.pending.remove(transactionId);
        if (pos == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> ContainerDataManager.get().onQueryResponse(pos, nbt));
    }

    public void reset() {
        this.pending.clear();
    }
}
