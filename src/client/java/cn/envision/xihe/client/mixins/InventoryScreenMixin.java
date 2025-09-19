package cn.envision.xihe.client.mixins;

import cn.envision.xihe.client.features.InventoryOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends HandledScreen<ScreenHandler> {

    public InventoryScreenMixin(ScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "drawBackground", at = @At("TAIL"))
    private void drawHighlightedSlots(DrawContext context, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        // 获取需要高亮的物品
        List<ItemStack> neededItems = InventoryOverlay.getCurrentMissingItems();
        if (neededItems.isEmpty()) return;

        // 遍历所有格子
        for (Slot slot : this.handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            // 检查是否是需要的物品
            for (ItemStack needed : neededItems) {
                if (ItemStack.areItemsEqual(stack, needed)) {
                    // 绘制向内的矩形高亮
                    drawSlotHighlight(context, slot.x, slot.y);
                    break;
                }
            }
        }
    }

    private void drawSlotHighlight(DrawContext context, int x, int y) {
        int slotSize = 16;
        int borderWidth = 1;
        int innerOffset = 2;

        // 外框
        context.fill(x, y, slotSize + 1, slotSize + 1, 0xFFFF00);

        // 内框（向内的矩形）
        context.fill(
                x + innerOffset,
                y + innerOffset,
                slotSize + 1 - innerOffset * 2,
                slotSize + 1 - innerOffset * 2,
                0xFFFF00
        );
    }
}
