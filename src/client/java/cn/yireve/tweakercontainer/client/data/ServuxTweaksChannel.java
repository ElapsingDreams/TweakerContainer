package cn.yireve.tweakercontainer.client.data;

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
 * 同一通道可能已被别的模组（tweakeroo）注册，注册撞车时不抢，直接让这条通道失效。
 */
public final class ServuxTweaksChannel {
    public static final Identifier CHANNEL_ID = Identifier.of("servux", "tweaks");

    /** 握手与单次请求的等待上限（毫秒）。 */
    private static final long TIMEOUT_MS = 2000L;
    /** 连续多少次请求没回包就判定这条通道不可用。 */
    private static final int MAX_FAILURES = 3;
    /** 同时在飞的请求上限，避免对着服务端刷包。 */
    private static final int MAX_PENDING = 32;
    /** 这条通道失败后的冷却时间，过后重新握手再试（不然一次抖动就废掉整个会话）。 */
    private static final long RETRY_COOLDOWN_MS = 30_000L;

    private static final ServuxTweaksChannel INSTANCE = new ServuxTweaksChannel();

    /** 已发出、等响应的坐标 → 发出时间 */
    private final Map<BlockPos, Long> pending = new ConcurrentHashMap<>();
    /** 握手还没回来时先攒着，确认 Servux 后统一发 */
    private final Set<BlockPos> waiting = ConcurrentHashMap.newKeySet();

    private boolean installed;
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
        try {
            PayloadTypeRegistry.playC2S().register(ServuxTweaksPayload.ID, ServuxTweaksPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(ServuxTweaksPayload.ID, ServuxTweaksPayload.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(ServuxTweaksPayload.ID,
                    (payload, context) -> context.client().execute(() -> this.onPacket(payload.data())));
            this.installed = true;
        } catch (Throwable t) {
            // 通道已被别的模组注册（例如 tweakeroo 在跑），不跟它抢：这条通道直接判死
            this.installed = false;
        }
    }

    // ---------- 连接生命周期 ----------

    private void onJoin() {
        this.reset();

        // 本地内置服务端直接读方块实体，用不着这条通道
        if (!this.installed || MinecraftClient.getInstance().getServer() != null) {
            return;
        }

        this.handshakeSent = true;
        this.handshakeSentAt = System.currentTimeMillis();
        this.send(ServuxTweaksPacket.metadataRequest(modVersion()));
    }

    private void reset() {
        this.pending.clear();
        this.waiting.clear();
        this.handshakeSent = false;
        this.servuxConfirmed = false;
        this.failedUntil = 0L;
        this.failures = 0;
    }

    /** 这条通道现在还能不能用（握手还没结果时也算"能用"，请求会先排队；失败则进入冷却）。 */
    public boolean isAvailable() {
        return this.installed && System.currentTimeMillis() >= this.failedUntil;
    }

    /**
     * 请求某一格的方块实体 NBT。
     *
     * @return 请求是否已经接下（false 表示这条通道用不了，调用方该退回别的数据源）
     */
    public boolean requestBlockEntity(BlockPos pos) {
        if (!this.isAvailable() || this.pending.size() + this.waiting.size() >= MAX_PENDING) {
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
        this.failedUntil = System.currentTimeMillis() + RETRY_COOLDOWN_MS;
        this.handshakeSent = false;
        this.servuxConfirmed = false;
        this.failures = 0;
        this.pending.clear();

        Set<BlockPos> queued = Set.copyOf(this.waiting);
        this.waiting.clear();
        ContainerDataManager.get().onServuxUnavailable(queued);
    }

    // ---------- 收发 ----------

    private void send(ServuxTweaksPacket packet) {
        if (MinecraftClient.getInstance().getNetworkHandler() == null) {
            return;
        }
        ClientPlayNetworking.send(new ServuxTweaksPayload(packet));
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

