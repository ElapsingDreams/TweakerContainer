package cn.yireve.tweakercontainer.client.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
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

    /** 通道协议版本：tweakeroo 的 {@code ServuxTweaksPacket.getVersion()} 就是写死的 1。 */
    public static final int PROTOCOL_VERSION = 1;

    /** tweakeroo 发请求时用的占位值，响应里不带这个字段（响应按坐标匹配）。 */
    private static final int TRANSACTION_ID = -1;

    private static final NbtSizeTracker NBT_SIZE = NbtSizeTracker.ofUnlimitedBytes();

    private final int type;
    @Nullable private final BlockPos pos;
    @Nullable private final NbtCompound nbt;

    private ServuxTweaksPacket(int type, @Nullable BlockPos pos, @Nullable NbtCompound nbt) {
        this.type = type;
        this.pos = pos;
        this.nbt = nbt;
    }

    /** 握手请求：告诉服务端我们是谁（服务端据此把玩家标记为 registered）。 */
    public static ServuxTweaksPacket metadataRequest(String modVersion) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("version", modVersion);
        return new ServuxTweaksPacket(TYPE_C2S_METADATA_REQUEST, null, nbt);
    }

    public static ServuxTweaksPacket blockEntityRequest(BlockPos pos) {
        return new ServuxTweaksPacket(TYPE_C2S_BLOCK_ENTITY_REQUEST, pos.toImmutable(), null);
    }

    public int type() {
        return this.type;
    }

    @Nullable
    public BlockPos pos() {
        return this.pos;
    }

    @Nullable
    public NbtCompound nbt() {
        return this.nbt;
    }

    public void write(PacketByteBuf buf) {
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
    public static ServuxTweaksPacket read(PacketByteBuf buf) {
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
    private static NbtCompound readCompound(PacketByteBuf buf) {
        return buf.readNbt(NBT_SIZE) instanceof NbtCompound compound ? compound : null;
    }
}
