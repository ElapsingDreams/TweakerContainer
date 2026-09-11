package cn.envision.xihe.client.mixins;

import cn.envision.xihe.client.HighlightState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static cn.envision.xihe.client.config.HighlightConfig.isEnabled;
import static cn.envision.xihe.client.features.InventoryOverlay.getCurrentContainerPos;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {

    @Inject(method = "close",
        at = @At("RETURN"),
        cancellable = false
    )
    private void onClose(CallbackInfo ci){
        if (!isEnabled()) return;
        HighlightState.get().updateMatching();
    }


    @Inject(
            method = "drawSlot",
            at = @At("HEAD"),
            cancellable = false
    )
    private void onDrawSlot(DrawContext context, Slot slot, CallbackInfo ci) {
        if (slot.inventory instanceof PlayerInventory) {
            return;
        }
        if (!isEnabled()) return;

        HighlightState state = HighlightState.get();

        // 只在右击容器后缓存容器内容；玩家背包的合成格等非容器槽位不写入缓存
        BlockPos clickedPos = state.getAndClearTempProcessingPos();
        if (clickedPos != null) {
            state.cacheStorageInventory(clickedPos, slot.inventory);
        }

        // 标脏或超过刷新间隔时才重算，不再逐槽全量重建
        state.ensureUpToDate();

        ItemStack currentStack = slot.getStack();
        if (currentStack.isEmpty()) {
            return;
        }
        BlockPos currentProjectionContainer = state.getCurrentProjectionContainer();
        BlockPos currentContainerPos = getCurrentContainerPos();
        if (currentProjectionContainer != null && currentContainerPos != null) {
            if (currentProjectionContainer.equals(currentContainerPos)) return;

            // 剩余需求表本身就只包含仍然缺少的物品
            if (state.getRemainingNeeded().getOrDefault(currentStack.getItem(), 0) > 0) {
                drawFoundItemHighlight(context, slot);
            }
        }
    }

    @Unique
    private void drawFoundItemHighlight(DrawContext context, Slot slot) {

        int slotSize = 16;
        int x = slot.x + 1;
        int y = slot.y + 1;
        context.fill(x - 1, y - 1, x + slotSize - 1, y + slotSize - 1, 0x6000FF00);
    }
}
