package cn.envision.xihe.client.mixins;

import cn.envision.xihe.client.features.InventoryOverlay;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {

    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        BlockPos pos = getCurrentContainerPosition();
        if (pos != null) {
            InventoryOverlay.onContainerClose(pos);
        }
    }

    private BlockPos getCurrentContainerPosition() {
        // 备选方案：从InventoryOverlay获取当前容器位置
        return InventoryOverlay.getInstance().getCurrentContainerPos();
    }
}
