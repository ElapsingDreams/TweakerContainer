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
 * 容器界面的缺货提示：在槽位上标出还缺的物品，并在左下角写上数量。
 * <p>
 * 打开投影容器时提示背包里该放进去多少，打开仓储容器时提示该取出来多少。
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {
    // 该放进投影容器的物品
    @Unique
    private static final int XIHE_SLOT_COLOR_PUT = 0x8040FF40;
    // 该从仓储取出的物品
    @Unique
    private static final int XIHE_SLOT_COLOR_TAKE = 0x6000FF00;

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
                Integer.toString(count), 0, 0, 0xFFFFFFFF, true);
        matrices.popMatrix();
    }

    /**
     * @return 需要高亮时的背景色，0 表示不需要
     */
    @Unique
    private int getHighlightColor(HighlightState state, Slot slot) {
        if (getNeededAmount(state, slot) <= 0) {
            return 0;
        }
        return isProjectionContainerOpen(state) ? XIHE_SLOT_COLOR_PUT : XIHE_SLOT_COLOR_TAKE;
    }

    /**
     * 该槽位还要处理多少个物品，0 表示不用管。
     * <p>
     * 投影容器只提示背包侧（要放进去的），仓储容器只提示容器侧（要取出来的）。
     */
    @Unique
    private int getNeededAmount(HighlightState state, Slot slot) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) return 0;
        if (getCurrentContainerPos() == null) return 0;

        boolean playerSlot = slot.inventory instanceof PlayerInventory;

        // 投影容器自身还缺多少，就是背包里该放进去多少
        if (isProjectionContainerOpen(state)) {
            return playerSlot ? state.getProjectionMissing().getOrDefault(stack.getItem(), 0) : 0;
        }

        // 仓储容器里还能补上缺口的，就是该取出来多少
        return playerSlot ? 0 : state.getRemainingNeeded().getOrDefault(stack.getItem(), 0);
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
