package cn.yireve.tweakercontainer.client.data;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 借用 tweakeroo 的 servux:tweaks 载荷类。
 * <p>
 * 为什么必须借：Fabric 的载荷注册表按通道 id 存一份编码器，谁先注册谁生效，后来者直接抛异常
 * （tweakeroo 那边是 malilib 接住并打一行 error 就算了，但它之后仍然照常发包）。
 * 结果是"先注册的那个模组的编码器去解另一个模组的载荷类"，编码时 ClassCastException，
 * 发包在 netty 线程上炸掉连接——这就是"单机好好的，一进服务器就被踢"的原因。
 * <p>
 * 所以 tweakeroo 在场时我们完全不碰注册，改成借它的报文类：协议字节完全一样（这个项目的
 * {@link ServuxTweaksPacket} 本来就是照它的线格式实现的），差的只是外面那层壳。
 * 全程只用它的公开 API，任何一步借不到都当作借不到，调用方退回别的数据源。
 */
final class TweakerooBridge {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PACKET_CLASS = "fi.dy.masa.tweakeroo.network.ServuxTweaksPacket";
    private static final String PAYLOAD_CLASS = PACKET_CLASS + "$Payload";

    private static boolean attempted;
    private static boolean ready;

    private static Class<?> payloadClass;
    private static Constructor<?> payloadConstructor;
    private static Method metadataRequestFactory;
    private static Method blockEntityRequestFactory;
    private static Method dataAccessor;
    private static Method packetTypeGetter;
    private static Method posGetter;
    private static Method compoundGetter;

    private TweakerooBridge() {
    }

    private static synchronized void init() {
        if (attempted) {
            return;
        }
        attempted = true;

        try {
            Class<?> packetClass = Class.forName(PACKET_CLASS);
            payloadClass = Class.forName(PAYLOAD_CLASS);
            payloadConstructor = payloadClass.getConstructor(packetClass);
            metadataRequestFactory = packetClass.getMethod("MetadataRequest", CompoundTag.class);
            blockEntityRequestFactory = packetClass.getMethod("BlockEntityRequest", BlockPos.class);
            dataAccessor = payloadClass.getMethod("data");
            packetTypeGetter = packetClass.getMethod("getPacketType");
            posGetter = packetClass.getMethod("getPos");
            compoundGetter = packetClass.getMethod("getCompound");
            ready = true;
        } catch (Throwable t) {
            ready = false;
            LOGGER.warn("没能借到 tweakeroo 的 servux:tweaks 载荷类（版本对不上？），联机数据改用原版查询或开界面抓取", t);
        }
    }

    /** tweakeroo 的载荷类能不能借到。 */
    static boolean isPresent() {
        init();
        return ready;
    }

    /**
     * 把要发的报文包成 tweakeroo 的载荷对象。
     *
     * @return 借不到、或这个类型用不上时返回 null（调用方这会儿就该放弃这次请求）
     */
    @Nullable
    static CustomPacketPayload create(ServuxTweaksPacket packet) {
        if (!isPresent()) {
            return null;
        }

        try {
            Object tweakerooPacket = switch (packet.type()) {
                case ServuxTweaksPacket.TYPE_C2S_METADATA_REQUEST -> metadataRequestFactory.invoke(null, packet.nbt());
                case ServuxTweaksPacket.TYPE_C2S_BLOCK_ENTITY_REQUEST -> blockEntityRequestFactory.invoke(null, packet.pos());
                default -> null;
            };

            return tweakerooPacket == null ? null : (CustomPacketPayload) payloadConstructor.newInstance(tweakerooPacket);
        } catch (Throwable t) {
            LOGGER.warn("构造 tweakeroo 的 servux:tweaks 载荷失败，放弃这次请求", t);
            return null;
        }
    }

    /**
     * 把 tweakeroo 的载荷对象还原成我们自己的报文结构。
     *
     * @return 不是它的载荷、或类型不是我们要的时返回 null
     */
    @Nullable
    static ServuxTweaksPacket read(CustomPacketPayload payload) {
        if (!isPresent() || !payloadClass.isInstance(payload)) {
            return null;
        }

        try {
            Object tweakerooPacket = dataAccessor.invoke(payload);
            if (tweakerooPacket == null) {
                return null;
            }

            int type = (Integer) packetTypeGetter.invoke(tweakerooPacket);
            return switch (type) {
                case ServuxTweaksPacket.TYPE_S2C_METADATA -> ServuxTweaksPacket.metadataResponse();
                case ServuxTweaksPacket.TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> ServuxTweaksPacket.blockNbtResponse(
                        (BlockPos) posGetter.invoke(tweakerooPacket),
                        compoundGetter.invoke(tweakerooPacket) instanceof CompoundTag nbt ? nbt : null);
                default -> null;
            };
        } catch (Throwable t) {
            LOGGER.warn("解析 tweakeroo 的 servux:tweaks 载荷失败，忽略这个回包", t);
            return null;
        }
    }
}
