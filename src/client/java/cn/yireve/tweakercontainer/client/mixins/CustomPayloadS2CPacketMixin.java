package cn.yireve.tweakercontainer.client.mixins;

import cn.yireve.tweakercontainer.client.data.ServuxTweaksChannel;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 借 tweakeroo 的 servux:tweaks 通道时，回包只有它自己的 receiver 能看到，
 * 所以在"载荷对象刚建好"这一步顺手读一份。
 * <p>
 * 挑这个注入点是因为它跑在 Fabric 的分发之前，也不在那个会被 Fabric 取消的方法里
 * （Fabric 是在 {@code ClientCommonNetworkHandler.onCustomPayload} 里取消原版处理的，
 * 挂在被取消的方法里会因为注入顺序不同而时灵时不灵）。
 * <p>
 * 注入失败（比如以后换版本、构造器签名变了）不影响游戏：{@code require = 0} 会跳过，
 * 这边收不到回包，上层按超时处理、退回别的数据源。
 */
@Mixin(CustomPayloadS2CPacket.class)
public class CustomPayloadS2CPacketMixin {
    @Inject(method = "<init>(Lnet/minecraft/network/packet/CustomPayload;)V", at = @At("TAIL"), require = 0)
    private void tweakercontainer_onPayload(CustomPayload payload, CallbackInfo ci) {
        ServuxTweaksChannel.get().onIncomingPayload(payload);
    }
}
