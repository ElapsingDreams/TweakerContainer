package cn.yireve.tweakercontainer.client.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * servux:tweaks 通道上的报文，只实现容器数据用到的那几个类型。
 * <p>
 * 字节布局照抄 tweakeroo 的 {@code ServuxTweaksPacket}（已用字节码核对：
 * 类型号与 transactionId 都是 <b>VarInt</b>，坐标走 {@code writeBlockPos} 即一个 long）：
 * <pre>
 *   1  metadata 响应  : VarInt type + NBT
 *   2  metadata 请求  : VarInt type + NBT
 *   3  方块实体请求   : VarInt type + VarInt transactionId + BlockPos
 *   5  方块 NBT 响应  : VarInt type + BlockPos + NBT
 * </pre>
 * 实体数据和大 NBT 分片（6/10/11/12/13）我们用不到，读的时候把剩余字节吃掉就行。
 */
public final class ServuxTweaksPacket {
    public static final int TYPE_S2C_METADATA = 1;
    public static final int TYPE_C2S_METADATA_REQUEST = 2;
    public static final int TYPE_C2S_BLOCK_ENTITY_REQUEST = 3;
    public static final int TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE = 5;

    /**
     * 通道协议版本。
     * <p>
     * servux 从 0.11.5（26.2 档）起用它卡客户端：握手 NBT 里 {@code version} 是整数，
     * 服务端用 {@code CompoundData#getIntOrDefault("version", -1)} 读，低于它要求的版本直接拒绝
     * （日志：{@code tweaks_data: Denying access ... This Server Requires: Version 2}）。
     * tweakeroo 26.2 的同名常量也是 2。
     */
    public static final int PROTOCOL_VERSION = 2;

    /** tweakeroo 发请求时用的占位值，响应里不带这个字段（响应按坐标匹配）。 */
    private static final int TRANSACTION_ID = -1;

    private static final NbtAccounter NBT_SIZE = NbtAccounter.unlimitedHeap();

    private final int type;
    @Nullable private final BlockPos pos;
    @Nullable private final CompoundTag nbt;

    private ServuxTweaksPacket(int type, @Nullable BlockPos pos, @Nullable CompoundTag nbt) {
        this.type = type;
        this.pos = pos;
        this.nbt = nbt;
    }

    /**
     * 握手请求：告诉服务端我们的协议版本（服务端据此把玩家标记为 registered）。
     * <p>
     * 与 tweakeroo 的 {@code EntityDataManager#requestMetadata} 完全一致：NBT 里只放一个
     * <b>整数</b> {@code version}。以前这里放的是 mod 版本字符串，servux 取整失败会当成 -1
     * 直接拒绝，所以 26.2 上一直连不上（"Your client protocol version is too low"）。
     */
    public static ServuxTweaksPacket metadataRequest() {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("version", PROTOCOL_VERSION);
        return new ServuxTweaksPacket(TYPE_C2S_METADATA_REQUEST, null, nbt);
    }

    public static ServuxTweaksPacket blockEntityRequest(BlockPos pos) {
        return new ServuxTweaksPacket(TYPE_C2S_BLOCK_ENTITY_REQUEST, pos.immutable(), null);
    }

    /**
     * 借道 tweakeroo 时，用它的报文对象还原出我们自己的结构（字段与线格式一致，只是壳子不同）。
     */
    static ServuxTweaksPacket metadataResponse() {
        return new ServuxTweaksPacket(TYPE_S2C_METADATA, null, null);
    }

    /** 同上，方块 NBT 响应。 */
    static ServuxTweaksPacket blockNbtResponse(BlockPos pos, @Nullable CompoundTag nbt) {
        return new ServuxTweaksPacket(TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE, pos, nbt);
    }

    public int messageType() {
        return this.type;
    }

    @Nullable
    public BlockPos pos() {
        return this.pos;
    }

    @Nullable
    public CompoundTag nbt() {
        return this.nbt;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.type);

        switch (this.type) {
            case TYPE_S2C_METADATA, TYPE_C2S_METADATA_REQUEST -> buf.writeNbt(this.nbt);
            case TYPE_C2S_BLOCK_ENTITY_REQUEST -> {
                buf.writeVarInt(TRANSACTION_ID);
                buf.writeBlockPos(this.pos);
            }
            case TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                buf.writeBlockPos(this.pos);
                buf.writeNbt(this.nbt);
            }
            default -> throw new IllegalStateException("不支持的 servux 报文类型: " + this.type);
        }
    }

    /**
     * 读一条报文；类型不认识时把剩余字节吃掉再返回 null，
     * 免得给别的报文类型留下没读完的缓冲。
     */
    @Nullable
    public static ServuxTweaksPacket read(FriendlyByteBuf buf) {
        int type = buf.readVarInt();

        switch (type) {
            case TYPE_S2C_METADATA, TYPE_C2S_METADATA_REQUEST -> {
                return new ServuxTweaksPacket(type, null, readCompound(buf));
            }
            case TYPE_C2S_BLOCK_ENTITY_REQUEST -> {
                buf.readVarInt();
                return new ServuxTweaksPacket(type, buf.readBlockPos(), null);
            }
            case TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                return new ServuxTweaksPacket(type, buf.readBlockPos(), readCompound(buf));
            }
            default -> {
                buf.skipBytes(buf.readableBytes());
                return null;
            }
        }
    }

    @Nullable
    private static CompoundTag readCompound(FriendlyByteBuf buf) {
        return buf.readNbt(NBT_SIZE) instanceof CompoundTag compound ? compound : null;
    }
}
