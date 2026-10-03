package cn.yireve.tweakercontainer.client.data;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * servux:tweaks 通道：直接向装了 Servux 的服务端要某一格的方块实体 NBT（内含容器物品）。
 * <p>
 * 为什么需要它：Servux 那条"放行原版 NBT 查询"的开关（{@code nbt_query_override}）默认是关的，
 * 非 OP 玩家走原版查询拿不到数据，只有这条自定义通道能用。
 * <p>
 * 流程：加入服务器 → 发 metadata 请求握手（服务端据此把玩家标记为 registered，之后才受理请求）
 * → 收到 metadata 响应后开始发方块实体请求 → 响应按坐标匹配。握手或请求连续失败就整条通道作废，
 * 由 {@link ContainerDataManager} 退回原版查询 / 开界面抓取。
 * <p>
 * 同一通道 tweakeroo 也在用。它的载荷类和我们的不是同一个，而 Fabric 的注册表按通道只留一份编码器，
 * 谁先注册谁生效：先注册的那个模组的编码器会去解另一个模组的载荷类，发包时 ClassCastException，
 * 在 netty 线程上炸掉连接（单机没事，一进服务器就被踢）。
 * <p>
 * 所以这里分两种跑法：
 * <ul>
 *     <li>tweakeroo 在场：完全不注册，借它的载荷类收发（见 {@link TweakerooBridge}），
 *     回包从 {@code CustomPayloadS2CPacketMixin} 那里拿——Fabric 一个通道只挂一个 receiver，
 *     那个位置是它的，抢过来会让它收不到自己的回包</li>
 *     <li>tweakeroo 不在场：按正常方式自己注册；万一还是被别人占了（未知模组），
 *     不跟它抢，这条通道直接判死</li>
 * </ul>
 */
public final class ServuxTweaksChannel {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Identifier CHANNEL_ID = Identifier.of("servux", "tweaks");

    /** 握手与单次请求的等待上限（毫秒）。 */
    private static final long TIMEOUT_MS = 2000L;
    /** 连续多少次请求没回包就判定这条通道不可用。 */
    private static final int MAX_FAILURES = 3;
    /** 同时在飞的请求上限，避免对着服务端刷包；其它模块也按这个值给自己的轮询定量。 */
    public static final int MAX_PENDING_REQUESTS = 32;
    /** 这条通道失败后的冷却时间，过后重新握手再试（不然一次抖动就废掉整个会话）。 */
    private static final long RETRY_COOLDOWN_MS = 30_000L;

    private static final ServuxTweaksChannel INSTANCE = new ServuxTweaksChannel();

    /** 已发出、等响应的坐标 → 发出时间 */
    private final Map<BlockPos, Long> pending = new ConcurrentHashMap<>();
    /** 握手还没回来时先攒着，确认 Servux 后统一发 */
    private final Set<BlockPos> waiting = ConcurrentHashMap.newKeySet();

    private boolean installed;
    /** 注册不是自己做的，而是借 tweakeroo 的（收发都要走它那套）。 */
    private boolean borrowed;
    /** 借用模式下确认 tweakeroo 真的把通道注册上了（没注册就发包会炸连接）。 */
    private boolean borrowedUsable;
    private boolean handshakeSent;
    private long handshakeSentAt;
    private boolean servuxConfirmed;
    private long failedUntil;
    private int failures;

    private ServuxTweaksChannel() {
    }

    public static ServuxTweaksChannel get() {
        return INSTANCE;
    }

    public static void setup() {
        INSTANCE.install();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> INSTANCE.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.reset());
        ClientTickEvents.END_CLIENT_TICK.register(client -> INSTANCE.tick());
    }

    private void install() {
        // tweakeroo 也在用这个通道：注册的事全交给它，我们借它的载荷类收发。
        // 关键是不能"抢"——先注册的一方会顶掉另一方的编码器，另一方再发包就是 ClassCastException
        if (TweakerooBridge.isClassPresent()) {
            this.borrowed = true;
            // 借不到它的壳时这条通道彻底作废：既不注册也不使用 —— 自己注册会把它的载荷抢过来解，
            // 发包时在 netty 线程炸掉连接（这正是 issue #1 里那次抢注的后果）
            this.installed = TweakerooBridge.isPresent();
            if (this.installed) {
                LOGGER.info("检测到 tweakeroo，servux:tweaks 通道让给它，本项目改借它的载荷类收发");
            } else {
                LOGGER.warn("检测到 tweakeroo 但借不到它的载荷类（版本对不上？），"
                        + "这条通道不注册也不使用，联机数据改用原版查询或开界面抓取");
            }
            return;
        }

        try {
            PayloadTypeRegistry.playC2S().register(ServuxTweaksPayload.ID, ServuxTweaksPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(ServuxTweaksPayload.ID, ServuxTweaksPayload.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(ServuxTweaksPayload.ID,
                    (payload, context) -> context.client().execute(() -> this.onPacket(payload.data())));
            this.installed = true;
        } catch (Throwable t) {
            // 通道已被别的模组注册，不跟它抢：这条通道直接判死
            this.installed = false;
            LOGGER.warn("servux:tweaks 通道注册失败（多半是别的模组已占用），联机数据改用原版查询或开界面抓取", t);
        }
    }

    // ---------- 连接生命周期 ----------

    private void onJoin() {
        this.reset();

        // 本地内置服务端直接读方块实体，用不着这条通道
        if (!this.installed || MinecraftClient.getInstance().getServer() != null) {
            return;
        }

        if (this.borrowed) {
            // 借道的前提是 tweakeroo 真的注册了通道。它没注册的话，我们照它的载荷类发包会在编码阶段
            // 炸掉连接，所以这里先核实一次；核实不了就整条通道作废，退回别的数据源
            this.borrowedUsable = isChannelRegistered();
            if (!this.borrowedUsable) {
                LOGGER.warn("tweakeroo 在场但没有注册 servux:tweaks 通道，本项目联机数据改用原版查询或开界面抓取");
                return;
            }
        }

        this.handshakeSent = true;
        this.handshakeSentAt = System.currentTimeMillis();
        this.send(ServuxTweaksPacket.metadataRequest(modVersion()));
    }

    /**
     * 通道的 serverbound 编码器注册上了没有。
     * <p>
     * Fabric 只给了注册接口、没给查询接口，只能问它的实现类要；拿不到结论一律当"没注册"，
     * 宁可退回别的数据源也不要冒着炸掉连接的风险发包。
     */
    private static boolean isChannelRegistered() {
        try {
            Object registry = PayloadTypeRegistry.playC2S();
            Object codec = registry.getClass().getMethod("get", Identifier.class).invoke(registry, CHANNEL_ID);
            return codec != null;
        } catch (Throwable t) {
            LOGGER.warn("没法确认 servux:tweaks 通道的注册状态，按没注册处理", t);
            return false;
        }
    }

    private void reset() {
        this.pending.clear();
        this.waiting.clear();
        this.borrowedUsable = false;
        this.handshakeSent = false;
        this.servuxConfirmed = false;
        this.failedUntil = 0L;
        this.failures = 0;
    }

    /** 这条通道现在还能不能用（握手还没结果时也算"能用"，请求会先排队；失败则进入冷却）。 */
    public boolean isAvailable() {
        return this.installed && (!this.borrowed || this.borrowedUsable)
                && System.currentTimeMillis() >= this.failedUntil;
    }

    /**
     * 请求某一格的方块实体 NBT。
     *
     * @return 请求是否已经接下（false 表示这条通道用不了，调用方该退回别的数据源）
     */
    public boolean requestBlockEntity(BlockPos pos) {
        if (!this.isAvailable() || this.pending.size() + this.waiting.size() >= MAX_PENDING_REQUESTS) {
            return false;
        }

        BlockPos immutablePos = pos.toImmutable();

        if (this.servuxConfirmed) {
            this.pending.put(immutablePos, System.currentTimeMillis());
            this.send(ServuxTweaksPacket.blockEntityRequest(immutablePos));
        } else {
            // 还没握手（或上次失败已过冷却）：先补一次握手，请求排队，收到 metadata 再发
            if (!this.handshakeSent) {
                this.startHandshake();
            }
            this.waiting.add(immutablePos);
        }

        return true;
    }

    private void startHandshake() {
        this.handshakeSent = true;
        this.handshakeSentAt = System.currentTimeMillis();
        this.send(ServuxTweaksPacket.metadataRequest(modVersion()));
    }

    /** 每个 tick 推进握手与请求的超时。 */
    public void tick() {
        if (!this.isAvailable()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (!this.servuxConfirmed) {
            if (this.handshakeSent && now - this.handshakeSentAt > TIMEOUT_MS) {
                this.failChannel();
            }
            return;
        }

        Set<BlockPos> lost = ConcurrentHashMap.newKeySet();
        for (Map.Entry<BlockPos, Long> entry : this.pending.entrySet()) {
            if (now - entry.getValue() > TIMEOUT_MS) {
                lost.add(entry.getKey());
            }
        }

        for (BlockPos pos : lost) {
            this.pending.remove(pos);
            ContainerDataManager.get().onServuxTimeout(pos);

            if (++this.failures >= MAX_FAILURES) {
                this.failChannel();
                return;
            }
        }
    }

    /** 这条通道这次不行了：清空在途请求并进入冷却，冷却过后会重新握手再试。 */
    private void failChannel() {
        boolean handshakeFailed = !this.servuxConfirmed;

        this.failedUntil = System.currentTimeMillis() + RETRY_COOLDOWN_MS;
        this.handshakeSent = false;
        this.servuxConfirmed = false;
        this.failures = 0;
        this.pending.clear();

        Set<BlockPos> queued = Set.copyOf(this.waiting);
        this.waiting.clear();

        // 借用模式下握手失败，说明我们这条旁路根本读不到回包（注入没生效，或服务端没装 Servux）。
        // 再重试只会每隔半分钟刷一行日志，直接判死，这一局都用别的数据源
        if (this.borrowed && handshakeFailed) {
            this.borrowedUsable = false;
        }

        LOGGER.warn(handshakeFailed
                        ? "Servux 握手没回应（服务端没装 Servux，或 tweaks_data 权限不放行），{} 秒后重试；期间联机数据退原版查询/开界面"
                        : "Servux 连续 {} 次请求没回包，{} 秒后重新握手；期间联机数据退原版查询/开界面",
                handshakeFailed ? RETRY_COOLDOWN_MS / 1000 : MAX_FAILURES, RETRY_COOLDOWN_MS / 1000);

        ContainerDataManager.get().onServuxUnavailable(queued);
    }

    // ---------- 收发 ----------

    private void send(ServuxTweaksPacket packet) {
        if (MinecraftClient.getInstance().getNetworkHandler() == null) {
            return;
        }

        if (this.borrowed) {
            CustomPayload payload = TweakerooBridge.create(packet);
            if (payload == null) {
                // 借不到就别发：发一个编码不了的包会把整条连接打断
                return;
            }
            ClientPlayNetworking.send(payload);
            return;
        }

        ClientPlayNetworking.send(new ServuxTweaksPayload(packet));
    }

    /**
     * 借用模式下的收包入口，由 {@code CustomPayloadS2CPacketMixin} 在报文对象建好之后调用。
     * <p>
     * 走这条路而不是 Fabric 的 receiver，是因为一个通道只能挂一个 receiver 且是先到先得：
     * 那个位置是 tweakeroo 的，抢过来会让它收不到自己的回包（Fabric 只是 putIfAbsent，
     * 失败连个警告都没有，坏得不知不觉）。
     * <p>
     * 这里跑在网络线程上，只读字段、不改状态；真正处理回包丢回客户端线程做。
     */
    public void onIncomingPayload(CustomPayload payload) {
        if (!this.borrowed || payload == null || !CHANNEL_ID.equals(payload.getId().id())) {
            return;
        }

        ServuxTweaksPacket packet = TweakerooBridge.read(payload);
        if (packet == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> this.onPacket(packet));
    }

    private void onPacket(ServuxTweaksPacket packet) {
        switch (packet.type()) {
            case ServuxTweaksPacket.TYPE_S2C_METADATA -> this.onMetadata();
            case ServuxTweaksPacket.TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> this.onBlockEntityData(packet);
            default -> {
            }
        }
    }

    private void onMetadata() {
        this.servuxConfirmed = true;
        this.failures = 0;

        for (BlockPos pos : this.waiting) {
            this.pending.put(pos, System.currentTimeMillis());
            this.send(ServuxTweaksPacket.blockEntityRequest(pos));
        }
        this.waiting.clear();
    }

    private void onBlockEntityData(ServuxTweaksPacket packet) {
        BlockPos pos = packet.pos();
        if (pos == null || this.pending.remove(pos) == null) {
            return;
        }

        this.failures = 0;
        ContainerDataManager.get().onServuxBlockEntityData(pos, packet.nbt());
    }

    private static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer("tweakercontainer")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /** 这一条报文对应的 payload 类型（注册与收发都用它）。 */
    public record ServuxTweaksPayload(ServuxTweaksPacket data) implements CustomPayload {
        public static final CustomPayload.Id<ServuxTweaksPayload> ID =
                new CustomPayload.Id<>(ServuxTweaksChannel.CHANNEL_ID);

        public static final PacketCodec<net.minecraft.network.PacketByteBuf, ServuxTweaksPayload> CODEC =
                CustomPayload.codecOf(
                        (payload, buf) -> payload.data().write(buf),
                        buf -> new ServuxTweaksPayload(ServuxTweaksPacket.read(buf)));

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}

