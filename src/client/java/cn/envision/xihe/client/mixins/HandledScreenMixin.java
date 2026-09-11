package cn.envision.xihe.client.mixins;

import cn.envision.xihe.client.HighlightState;
import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.features.InventoryOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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

import java.util.HashSet;
import java.util.Set;

import static cn.envision.xihe.client.config.HighlightConfig.isEnabled;

/**
 * 容器界面的缺货提示：在槽位上标出还缺的物品，并在槽位左上角写上数量。
 * <p>
 * 打开投影容器时，背包侧显示该放进去多少；打开其它容器时，容器侧显示该取出多少，
 * 背包侧是否也提示由配置决定。数量一律用需求总量表示。
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {
    // 每帧记录已显示过数量的需求键，用于“同种物品只在第一个匹配格子显示”
    @Unique
    private Set<HighlightState.StackKey> xiheShownPutKeys;
    @Unique
    private Set<HighlightState.StackKey> xiheShownTakeKeys;

    @Inject(method = "close",
        at = @At("RETURN"),
        cancellable = false
    )
    private void onClose(CallbackInfo ci){
        // 关闭界面时清掉当前容器记录，否则再打开背包会被当成还停留在投影容器里
        InventoryOverlay.clearCurrentContainer();
        if (!isEnabled()) return;
        HighlightState.get().updateMatching();
    }

    @Inject(
            method = "drawSlots",
            at = @At("HEAD"),
            cancellable = false
    )
    private void onDrawSlots(DrawContext context, CallbackInfo ci) {
        // 每帧重置，保证“第一个匹配到的格子”按本次绘制顺序判定
        if (xiheShownPutKeys != null) {
            xiheShownPutKeys.clear();
        }
        if (xiheShownTakeKeys != null) {
            xiheShownTakeKeys.clear();
        }
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

        // 需求总量：一个物品需要多少就写多少，不按格子数量分摊或封顶
        int needed = getNeededAmount(HighlightState.get(), slot);
        if (needed <= 0) return;

        // 同种物品是否只在第一个匹配到的格子显示
        if (HighlightConfig.isCountOnlyFirstMatch() && !markCountShown(slot)) {
            return;
        }

        // 以槽位左上角为锚点，右/下偏移与字号都由配置决定；避开原版画在右下角的堆叠数量
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(slot.x + HighlightConfig.getHintTextOffsetX(),
                slot.y + HighlightConfig.getHintTextOffsetY());
        float scale = HighlightConfig.getHintTextScale();
        matrices.scale(scale, scale);
        context.drawText(MinecraftClient.getInstance().textRenderer,
                Integer.toString(needed), 0, 0, HighlightConfig.getSlotCountTextColor(), true);
        matrices.popMatrix();
    }

    /**
     * 记录该需求键已经显示过数量。
     *
     * @return 首次出现返回 true，之后返回 false
     */
    @Unique
    private boolean markCountShown(Slot slot) {
        HighlightState.StackKey key = HighlightState.requirementKey(slot.getStack());
        if (key == null) {
            return true;
        }

        Set<HighlightState.StackKey> shown = slot.inventory instanceof PlayerInventory
                ? shownPutKeys()
                : shownTakeKeys();
        return shown.add(key);
    }

    @Unique
    private Set<HighlightState.StackKey> shownPutKeys() {
        if (xiheShownPutKeys == null) {
            xiheShownPutKeys = new HashSet<>();
        }
        return xiheShownPutKeys;
    }

    @Unique
    private Set<HighlightState.StackKey> shownTakeKeys() {
        if (xiheShownTakeKeys == null) {
            xiheShownTakeKeys = new HashSet<>();
        }
        return xiheShownTakeKeys;
    }

    /**
     * @return 需要高亮时的背景色，0 表示不需要
     */
    @Unique
    private int getHighlightColor(HighlightState state, Slot slot) {
        boolean playerSlot = slot.inventory instanceof PlayerInventory;

        if (getNeededAmount(state, slot) > 0) {
            return playerSlot ? HighlightConfig.getSlotPutColor() : HighlightConfig.getSlotTakeColor();
        }

        // 严格校验时，物品相同但 NBT 不同的槽位换一种颜色提示
        if (!HighlightConfig.isNbtMismatchColor()) {
            return 0;
        }

        ItemStack stack = slot.getStack();
        if (stack.isEmpty() || !isHintSide(state, slot)) {
            return 0;
        }

        return state.hasComponentMismatch(stack, playerSlot)
                ? HighlightConfig.getSlotNbtMismatchColor()
                : 0;
    }

    /**
     * 该槽位还要处理多少个物品，0 表示不用管。
     */
    @Unique
    private int getNeededAmount(HighlightState state, Slot slot) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty() || !isHintSide(state, slot)) {
            return 0;
        }

        // 背包侧看投影容器还缺多少，容器侧看还要去仓储取多少
        return slot.inventory instanceof PlayerInventory
                ? state.getProjectionMissing(stack)
                : state.getRemainingNeeded(stack);
    }

    /**
     * 该槽位是否属于当前要提示的一侧。
     * <p>
     * 背包侧：投影容器打开时总是提示；其它界面（含玩家背包）看配置。
     * 容器侧：打开了非投影的容器时才提示，避免没开容器时把合成格当成容器槽位。
     */
    @Unique
    private boolean isHintSide(HighlightState state, Slot slot) {
        if (slot.inventory instanceof PlayerInventory) {
            return isProjectionContainerOpen(state) || HighlightConfig.isHintInPlayerInventory();
        }

        return !isProjectionContainerOpen(state) && InventoryOverlay.getCurrentContainerPos() != null;
    }

    @Unique
    private boolean isProjectionContainerOpen(HighlightState state) {
        BlockPos projectionContainer = state.getCurrentProjectionContainer();
        BlockPos currentContainer = InventoryOverlay.getCurrentContainerPos();
        return projectionContainer != null && projectionContainer.equals(currentContainer);
    }
}
