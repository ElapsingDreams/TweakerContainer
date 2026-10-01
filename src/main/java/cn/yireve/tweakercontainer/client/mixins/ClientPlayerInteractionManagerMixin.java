package cn.yireve.tweakercontainer.client.mixins;
import cn.yireve.tweakercontainer.client.handler.InteractionHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
    @Inject(method = "interactBlock", at = @At(value = "RETURN", ordinal = 1))
    private void onContainerClick(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
        // 登记统一走 InteractionHandler 的入口（潜行判断也在里面），这里不再自己判断
        InteractionHandler.handleContainerClick(player, hitResult);
    }

    /**
     * 角点框选时按住左键不放的连续破坏走的是这个方法，不是 attackBlock，
     * 所以 AttackBlockCallback 拦不住，得在这里一并挡掉。
     */
    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void onUpdateBlockBreakingProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (InteractionHandler.shouldSuppressBlockBreaking()) {
            cir.setReturnValue(false);
        }
    }
}