package cn.yireve.tweakercontainer.client.data;

import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
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
            // 26.2 起 tweakeroo 把 NBT 换成了 malilib 的 CompoundData，签名跟着变
            metadataRequestFactory = packetClass.getMethod("MetadataRequest", CompoundData.class);
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
     * tweakeroo 在不在场（只看到类名，不管反射是否全部通）。
     * <p>
     * 注册通道前必须先问这个：只要它在场，这条通道就一律不碰——哪怕我们这次反射没借到它的壳，
     * 也绝不能自己注册，否则它的载荷会被我们的编码器去解，发包时在 netty 线程炸掉连接。
     */
    static boolean isClassPresent() {
        try {
            Class.forName(PACKET_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
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
            Object tweakerooPacket = switch (packet.messageType()) {
                case ServuxTweaksPacket.TYPE_C2S_METADATA_REQUEST -> metadataRequestFactory.invoke(null,
                        DataConverterNbt.fromVanillaCompound(packet.nbt() != null ? packet.nbt() : new CompoundTag()));
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
                        toVanillaNbt(compoundGetter.invoke(tweakerooPacket)));
                default -> null;
            };
        } catch (Throwable t) {
            LOGGER.warn("解析 tweakeroo 的 servux:tweaks 载荷失败，忽略这个回包", t);
            return null;
        }
    }

    /**
     * tweakeroo 那边取出来的方块实体数据转成原版 NBT。
     * <p>
     * 26.2 起它给的是 malilib 的 {@link CompoundData}（那套 data 层替代了 NBT），
     * 老版本仍然是 {@link CompoundTag}，两种都认，省得以后它再改一次又炸。
     */
    @Nullable
    private static CompoundTag toVanillaNbt(@Nullable Object data) {
        if (data instanceof CompoundData compoundData) {
            return DataConverterNbt.toVanillaCompound(compoundData);
        }
        return data instanceof CompoundTag nbt ? nbt : null;
    }
}
