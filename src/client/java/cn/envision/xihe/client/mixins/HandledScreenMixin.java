package cn.envision.xihe.client.mixins;

import cn.envision.xihe.client.HighlightState;
import cn.envision.xihe.client.config.HighlightConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static cn.envision.xihe.client.config.HighlightConfig.isEnabled;
import static cn.envision.xihe.client.features.InventoryOverlay.getCurrentContainerPos;

/**
 * 容器界面的缺货提示：在槽位上标出还缺的物品，并在槽位左上角写上数量。
 * <p>
 * 打开投影容器时提示背包里该放进去多少，打开仓储容器时提示该取出来多少。
 */
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
        if (!isEnabled()) return;

        HighlightState state = HighlightState.get();

        // 只在右击容器后缓存容器内容；背包侧（含合成格）不写入缓存
        if (!(slot.inventory instanceof PlayerInventory)) {
            BlockPos clickedPos = state.getAndClearTempProcessingPos();
            if (clickedPos != null) {
                state.cacheStorageInventory(clickedPos, slot.inventory);
            }
        }

        // 标脏或超过刷新间隔时才重算，不再逐槽全量重建
        state.ensureUpToDate();

        // 背景必须画在物品之前，否则会盖住图标
        int color = getHighlightColor(state, slot);
        if (color != 0) {
            context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
        }
    }

    @Inject(
            method = "drawSlot",
            at = @At("RETURN"),
            cancellable = false
    )
    private void onDrawSlotCount(DrawContext context, Slot slot, CallbackInfo ci) {
        if (!isEnabled()) return;

        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) return;

        int needed = getNeededAmount(HighlightState.get(), slot);
        if (needed <= 0) return;

        // 以槽位左上角为锚点，右/下偏移与字号都由配置决定；避开原版画在右下角的堆叠数量
        int count = Math.min(stack.getCount(), needed);
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(slot.x + HighlightConfig.getHintTextOffsetX(),
                slot.y + HighlightConfig.getHintTextOffsetY());
        float scale = HighlightConfig.getHintTextScale();
        matrices.scale(scale, scale);
        context.drawText(MinecraftClient.getInstance().textRenderer,
                Integer.toString(count), 0, 0, HighlightConfig.getSlotCountTextColor(), true);
        matrices.popMatrix();
    }

    /**
     * @return 需要高亮时的背景色，0 表示不需要
     */
    @Unique
    private int getHighlightColor(HighlightState state, Slot slot) {
        int needed = getNeededAmount(state, slot);
        if (needed > 0) {
            return isProjectionContainerOpen(state)
                    ? HighlightConfig.getSlotPutColor()
                    : HighlightConfig.getSlotTakeColor();
        }

        // 严格校验时，物品相同但 NBT 不同的槽位换一种颜色提示
        if (!HighlightConfig.isNbtMismatchColor()) {
            return 0;
        }

        ItemStack stack = slot.getStack();
        if (stack.isEmpty() || !isHintSlot(state, slot)) {
            return 0;
        }

        boolean projectionView = isProjectionContainerOpen(state);
        return state.hasComponentMismatch(stack, projectionView)
                ? HighlightConfig.getSlotNbtMismatchColor()
                : 0;
    }

    /**
     * 该槽位还要处理多少个物品，0 表示不用管。
     */
    @Unique
    private int getNeededAmount(HighlightState state, Slot slot) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty() || getCurrentContainerPos() == null) {
            return 0;
        }
        if (!isHintSlot(state, slot)) {
            return 0;
        }

        // 投影容器自身还缺多少，就是背包里该放进去多少
        return isProjectionContainerOpen(state)
                ? state.getProjectionMissing(stack)
                : state.getRemainingNeeded(stack);
    }

    /**
     * 该槽位是否属于当前要提示的一侧：投影容器看背包侧，仓储容器看容器侧。
     */
    @Unique
    private boolean isHintSlot(HighlightState state, Slot slot) {
        boolean playerSlot = slot.inventory instanceof PlayerInventory;
        return isProjectionContainerOpen(state) == playerSlot;
    }

    @Unique
    private boolean isProjectionContainerOpen(HighlightState state) {
        BlockPos projectionContainer = state.getCurrentProjectionContainer();
        BlockPos currentContainer = getCurrentContainerPos();
        if (projectionContainer == null || currentContainer == null) {
            return false;
        }

        if (!projectionContainer.equals(currentContainer)) {
            return false;
        }

        // 玩家背包界面是否提示由配置决定，免得关掉容器后按 E 也被标色
        if ((Object) this instanceof InventoryScreen) {
            return HighlightConfig.isHintInPlayerInventory();
        }
        return true;
    }
}
