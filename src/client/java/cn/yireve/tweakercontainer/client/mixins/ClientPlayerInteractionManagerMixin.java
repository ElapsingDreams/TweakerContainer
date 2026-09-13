package cn.yireve.tweakercontainer.client.mixins;
import cn.yireve.tweakercontainer.client.features.InventoryOverlay;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
    @Inject(method = "interactBlock", at = @At(value = "RETURN", ordinal = 1))
    private void onContainerClick(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
        // 潜行右键是"清除标记"，绝不能顺手登记：一次右键会走主手、副手两遍 interactBlock，
        // 这里登记下去，副手那遍就会把同一个箱子当成已登记的，弹一条清材料的提示
        if (player.isSneaking()) {
            return;
        }

        InventoryOverlay.onContainerClick(hitResult);
    }
}