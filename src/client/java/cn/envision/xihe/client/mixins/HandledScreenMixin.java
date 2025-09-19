package cn.envision.xihe.client.mixins;

import cn.envision.xihe.client.BlockHighlighterRender;
import cn.envision.xihe.client.features.InventoryOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {

    @Shadow protected int x;
    @Shadow protected int y;

    public HandledScreenMixin(T handler, Text title) {
        super();
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
        List<ItemStack> missingItems = BlockHighlighterRender.getCurrentMissingItems();
        if (missingItems.isEmpty()) {
            return;
        }

        ItemStack currentStack = slot.getStack();
        if (currentStack.isEmpty()) {
            return;
        }

        boolean found = false;
        for (ItemStack needed : missingItems) {
            if (ItemStack.areItemsEqual(currentStack, needed)) {
                drawFoundItemHighlight(context, slot);
                found = true;
                break;
            }
        }

        // 关键修改：使用专门的临时处理坐标，读取后自动清空
        BlockPos clickedPos = BlockHighlighterRender.getAndClearTempProcessingPos();

        // 如果获取到了有效的临时处理坐标，则进行处理
        if (clickedPos != null && !clickedPos.equals(BlockPos.ORIGIN)) {
            if (found) {
                if (!BlockHighlighterRender.getTempHighlightedBlocks().contains(clickedPos)) {
                    BlockHighlighterRender.addTempHighlightedBlock(clickedPos);
                }
            } else {
                BlockHighlighterRender.getTempHighlightedBlocks().remove(clickedPos);
            }
        }
    }

    @Unique
    private void drawFoundItemHighlight(DrawContext context, Slot slot) {
        int slotSize = 16;
        int x = slot.x;
        int y = slot.y;
        context.fill(x - 1, y - 1, x + slotSize + 1, y + slotSize + 1, 0x8000FF00);
    }
}