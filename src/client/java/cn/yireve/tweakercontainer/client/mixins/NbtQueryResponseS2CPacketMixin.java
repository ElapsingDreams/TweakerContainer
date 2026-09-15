package cn.yireve.tweakercontainer.client.mixins;

import cn.yireve.tweakercontainer.client.data.VanillaQueryChannel;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.NbtQueryResponseS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把原版 NBT 查询的响应接给 {@link VanillaQueryChannel}。
 * <p>
 * 挂在"包对象刚建好"这一步，和自定义载荷那边同一个思路：这里跑在 Fabric 的分发之前，
 * 也不在被谁取消的方法里，注入顺序影响不到它。id 不是我们自己分配的那些就直接不管，
 * 原版（那个单回调槽）和别的模组的查询照旧。
 * <p>
 * 注入失败（换版本、构造器签名变了）不影响游戏：{@code require = 0} 会跳过，
 * 我们的查询收不到回包，按超时处理，退回开界面抓取。
 */
@Mixin(NbtQueryResponseS2CPacket.class)
public class NbtQueryResponseS2CPacketMixin {
    @Inject(method = "<init>(ILnet/minecraft/nbt/NbtCompound;)V", at = @At("TAIL"), require = 0)
    private void tweakercontainer_onNbtQueryResponse(int transactionId, NbtCompound nbt, CallbackInfo ci) {
        VanillaQueryChannel.get().onIncoming(transactionId, nbt);
    }
}
