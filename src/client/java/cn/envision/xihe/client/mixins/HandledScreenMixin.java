package cn.envision.xihe.client.mixins;

import cn.envision.xihe.client.HighlightState;
import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.features.InventoryOverlay;
import fi.dy.masa.malilib.util.ItemType;
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
    // 每帧记录已标注过的需求键，用于“同种物品只标第一个匹配格子”
    @Unique
    private Set<ItemType> xiheShownPutKeys;
    @Unique
    private Set<ItemType> xiheShownTakeKeys;
    // 当前槽位是否要写数量，由 HEAD 阶段的高亮判定顺带给出
    @Unique
    private boolean xiheShowCount;

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
        if (!isEnabled() || !xiheShowCount) return;

        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) return;

        // 需求总量：一个物品需要多少就写多少，不按格子数量分摊或封顶
        HighlightState.SlotNeed need = getSlotNeed(HighlightState.get(), slot);
        if (need == null) return;

        // 以槽位左上角为锚点，右/下偏移与字号都由配置决定；避开原版画在右下角的堆叠数量
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(slot.x + HighlightConfig.getHintTextOffsetX(),
                slot.y + HighlightConfig.getHintTextOffsetY());
        float scale = HighlightConfig.getHintTextScale();
        matrices.scale(scale, scale);
        context.drawText(MinecraftClient.getInstance().textRenderer,
                Integer.toString(need.amount()), 0, 0, HighlightConfig.getSlotCountTextColor(), true);
        matrices.popMatrix();
    }

    /**
     * 背景与数量共用同一套判定：
     * 同一种物品在开启“只标第一个匹配格子”时只标第一个，其余格子背景和数量都不画。
     *
     * @return 需要高亮时的背景色，0 表示不需要
     */
    @Unique
    private int getHighlightColor(HighlightState state, Slot slot) {
        xiheShowCount = false;

        ItemStack stack = slot.getStack();
        if (stack.isEmpty() || !isHintSide(state, slot)) {
            return 0;
        }

        boolean playerSlot = slot.inventory instanceof PlayerInventory;

        // 自身或内部容器内容命中需求时标色，数量用该需求的总量
        HighlightState.SlotNeed need = state.matchSlot(stack, playerSlot);
        if (need != null) {
            if (!isFirstMatchSlot(need.key(), playerSlot)) {
                return 0;
            }
            xiheShowCount = true;
            return playerSlot ? HighlightConfig.getSlotPutColor() : HighlightConfig.getSlotTakeColor();
        }

        // 严格校验时，物品相同但 NBT 不同的槽位换一种颜色提示，同样只标第一个匹配格子
        if (HighlightConfig.isNbtMismatchColor()
                && state.hasComponentMismatch(stack, playerSlot)
                && isFirstMatchSlot(HighlightState.requirementKey(stack), playerSlot)) {
            return HighlightConfig.getSlotNbtMismatchColor();
        }

        return 0;
    }

    /**
     * 该槽位匹配到的需求（含容器内部内容），没有则返回 null。
     */
    @Unique
    private HighlightState.SlotNeed getSlotNeed(HighlightState state, Slot slot) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty() || !isHintSide(state, slot)) {
            return null;
        }

        // 背包侧看投影容器还缺多少，容器侧看还要去仓储取多少
        return state.matchSlot(stack, slot.inventory instanceof PlayerInventory);
    }

    /**
     * 该槽位是否是同一需求键第一个被绘制的格子；关闭“只标第一个”时恒为 true。
     */
    @Unique
    private boolean isFirstMatchSlot(ItemType key, boolean playerSlot) {
        if (!HighlightConfig.isCountOnlyFirstMatch() || key == null) {
            return true;
        }

        Set<ItemType> shown = playerSlot ? shownPutKeys() : shownTakeKeys();
        return shown.add(key);
    }

    @Unique
    private Set<ItemType> shownPutKeys() {
        if (xiheShownPutKeys == null) {
            xiheShownPutKeys = new HashSet<>();
        }
        return xiheShownPutKeys;
    }

    @Unique
    private Set<ItemType> shownTakeKeys() {
        if (xiheShownTakeKeys == null) {
            xiheShownTakeKeys = new HashSet<>();
        }
        return xiheShownTakeKeys;
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
