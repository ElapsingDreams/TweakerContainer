package cn.yireve.tweakercontainer.client.mixins;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import cn.yireve.tweakercontainer.client.features.InventoryOverlay;
import fi.dy.masa.malilib.util.ItemType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.world.entity.player.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.core.BlockPos;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static cn.yireve.tweakercontainer.client.config.HighlightConfig.isEnabled;

/**
 * 容器界面的缺货提示：在槽位上标出还缺的物品，并在槽位左上角写上数量。
 * <p>
 * 打开投影容器时，背包侧显示该放进去多少；打开其它容器时，容器侧显示该取出多少，
 * 背包侧是否也提示由配置决定。数量一律用需求总量表示。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin<T extends AbstractContainerMenu> {
    // 每帧记录已标注过的需求键，用于“同种物品只标第一个匹配格子”
    @Unique
    private Set<ItemType> tcShownPutKeys;
    // 已抓取内容的界面 syncId，避免 init 因窗口尺寸变化而重复抓取
    @Unique
    private Integer tcCapturedSyncId;
    // 本槽位要标注的数量（HEAD 阶段定下，RETURN 阶段写出来）
    @Unique
    private int tcLabelAmount;
    // 按堆叠数逐个标注时，每个需求键还剩多少没标完
    @Unique
    private Map<ItemType, Integer> tcPutLabelRemaining;
    @Unique
    private Map<ItemType, Integer> tcTakeLabelRemaining;
    // 当前正在看的容器坐标与“内容已变化、需重新拆分”的标记
    @Unique
    private BlockPos tcCapturePos;
    @Unique
    private boolean tcRecapturePending;
    @Unique
    private Set<ItemType> tcShownTakeKeys;
    // 当前槽位是否要写数量，由 HEAD 阶段的高亮判定顺带给出
    @Unique
    private boolean tcShowCount;

    @Inject(method = "close",
        at = @At("RETURN"),
        cancellable = false
    )
    private void onClose(CallbackInfo ci){
        // 关屏前抓最后一次，保证缓存停留在最终内容
        if (isEnabled()) {
            captureContainer();
        }

        // 关闭界面时清掉当前容器记录，否则再打开背包会被当成还停留在投影容器里
        InventoryOverlay.clearCurrentContainer();
        if (!isEnabled()) return;
        HighlightState.get().updateMatching();
    }

    /**
     * 界面打开时一次性抓取容器内容，数据获取不再挂在渲染循环上。
     */
    @Inject(method = "init",
        at = @At("RETURN"),
        cancellable = false
    )
    @SuppressWarnings("unchecked")
    private void onInit(CallbackInfo ci) {
        if (!isEnabled()) return;

        // 只处理刚右击过的容器；玩家背包、创造模式物品栏等界面没有待处理坐标
        BlockPos clickedPos = HighlightState.get().getAndClearTempProcessingPos();
        if (clickedPos == null || isNonContainerScreen()) return;

        AbstractContainerMenu handler = ((MenuAccess<AbstractContainerMenu>) (Object) this).getScreenHandler();
        if (tcCapturedSyncId != null && tcCapturedSyncId == handler.syncId) {
            return; // init 在窗口尺寸变化时会再次调用
        }
        tcCapturedSyncId = handler.syncId;
        tcCapturePos = clickedPos;

        // 首次抓取：此刻槽位内容可能还没同步过来，收到槽位更新后会再抓一次
        captureContainer();

        // 槽位一变就标脏：重算仍由 tick / 渲染的节流兜底，实际每帧最多算一次，
        // 这样搬东西时槽位数字与匹配结果基本立刻跟上
        handler.addListener(new ContainerListener() {
            @Override
            public void onSlotUpdate(AbstractContainerMenu screenHandler, int slotId, ItemStack stack) {
                tcRecapturePending = true;
                HighlightState.get().markDirty();
            }

            @Override
            public void onPropertyUpdate(AbstractContainerMenu screenHandler, int property, int value) {
                // 属性变化（例如熔炉进度）与容器物品无关，忽略
            }
        });
    }

    /**
     * 用当前界面里的容器内容刷新缓存。
     * <p>
     * 大箱子的整箱数据是按半拷贝的，所以必须在内容同步到达之后（以及每次内容变化之后）重新拆一次，
     * 否则拷到的会是空箱子——这也是"有料的大箱子不高亮"的原因。
     */
    @Unique
    @SuppressWarnings("unchecked")
    private void captureContainer() {
        if (tcCapturePos == null) {
            return;
        }

        // 同一屏的非玩家槽位共用一个 Container，取第一个就够
        AbstractContainerMenu handler = ((MenuAccess<AbstractContainerMenu>) (Object) this).getScreenHandler();
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof Inventory)) {
                HighlightState.get().cacheStorageInventory(tcCapturePos, slot.inventory);
                return;
            }
        }
    }

    /**
     * 每 tick 一次的重算（内部按时间节流），不再由画槽位驱动。
     */
    @Inject(method = "handledScreenTick",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onHandledScreenTick(CallbackInfo ci) {
        if (!isEnabled()) return;

        // 槽位内容有变化：重新拆一次大箱子的两半数据，再重算
        if (tcRecapturePending) {
            tcRecapturePending = false;
            captureContainer();
        }

        HighlightState.get().ensureUpToDate();
    }

    @Inject(
            method = "drawSlots",
            at = @At("HEAD"),
            cancellable = false
    )
    private void onDrawSlots(GuiGraphics context, CallbackInfo ci) {
        // 每帧重置，保证“第一个匹配到的格子”按本次绘制顺序判定
        if (tcShownPutKeys != null) {
            tcShownPutKeys.clear();
        }
        if (tcShownTakeKeys != null) {
            tcShownTakeKeys.clear();
        }
        if (tcPutLabelRemaining != null) {
            tcPutLabelRemaining.clear();
        }
        if (tcTakeLabelRemaining != null) {
            tcTakeLabelRemaining.clear();
        }
    }


    @Inject(
            method = "drawSlot",
            at = @At("HEAD"),
            cancellable = false
    )
    private void onDrawSlot(GuiGraphics context, Slot slot, CallbackInfo ci) {
        if (!isEnabled()) return;

        HighlightState state = HighlightState.get();

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
    private void onDrawSlotCount(GuiGraphics context, Slot slot, CallbackInfo ci) {
        if (!isEnabled() || !tcShowCount) return;

        ItemStack stack = slot.getStack();
        if (stack.isEmpty() || tcLabelAmount <= 0) return;

        // 以槽位左上角为锚点，右/下偏移与字号都由配置决定；避开原版画在右下角的堆叠数量
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(slot.x + HighlightConfig.getHintTextOffsetX(),
                slot.y + HighlightConfig.getHintTextOffsetY());
        float scale = HighlightConfig.getHintTextScale();
        matrices.scale(scale, scale);
        context.drawText(Minecraft.getInstance().textRenderer,
                Integer.toString(tcLabelAmount), 0, 0, HighlightConfig.getSlotCountTextColor(), true);
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
        tcShowCount = false;
        tcLabelAmount = 0;

        ItemStack stack = slot.getStack();
        if (stack.isEmpty() || !isHintSide(state, slot)) {
            return 0;
        }

        boolean playerSlot = slot.inventory instanceof Inventory;
        int color = playerSlot ? HighlightConfig.getSlotPutColor() : HighlightConfig.getSlotTakeColor();

        // 自身或内部容器内容命中需求时标色
        HighlightState.SlotNeed need = state.matchSlot(stack, playerSlot);
        if (need != null) {
            // 按最大堆叠数逐个格子标注到够为止（容器格子不参与，只在盒子上标色）
            if (!need.nested() && useSpreadCount(stack)) {
                int label = nextLabelAmount(need, playerSlot, stack);
                if (label <= 0) {
                    return 0; // 这种物品已经标够
                }
                tcShowCount = true;
                tcLabelAmount = label;
                return color;
            }

            if (!isFirstMatchSlot(need.key(), playerSlot)) {
                return 0;
            }
            // 容器格子（潜影盒、收纳袋）只标色，不在盒子上写它内部物品的数量
            tcShowCount = !need.nested();
            tcLabelAmount = need.amount();
            return color;
        }

        // 严格校验时，物品相同但 NBT 不同的槽位换一种颜色提示，同样只标第一个匹配格子
        if (HighlightConfig.isNbtMismatchColor()
                && state.hasComponentMismatch(stack, playerSlot)
                && isFirstMatchSlot(HighlightState.requirementKey(stack), playerSlot)) {
            return HighlightConfig.getSlotNbtMismatchColor();
        }

        return 0;
    }

    /** 按最大堆叠数逐个标注时，不可堆叠物品默认仍由一个格子标出全部需要数量。 */
    @Unique
    private static boolean useSpreadCount(ItemStack stack) {
        if (!HighlightConfig.isSpreadCountByStackSize()) {
            return false;
        }
        return stack.getMaxStackSize() > 1 || HighlightConfig.isSpreadCountForNonStackable();
    }

    /**
     * 取出本格要标注的数量：最多一个堆叠，剩下的由后面的匹配格子接着标。
     *
     * @return 0 表示这种物品已经标够，本格不再标注
     */
    @Unique
    private int nextLabelAmount(HighlightState.SlotNeed need, boolean playerSlot, ItemStack stack) {
        Map<ItemType, Integer> remaining = playerSlot ? putLabelRemaining() : takeLabelRemaining();
        int left = remaining.computeIfAbsent(need.key(), key -> need.amount());
        if (left <= 0) {
            return 0;
        }

        int label = Math.min(left, stack.getMaxStackSize());
        remaining.put(need.key(), left - label);
        return label;
    }

    @Unique
    private Map<ItemType, Integer> putLabelRemaining() {
        if (tcPutLabelRemaining == null) {
            tcPutLabelRemaining = new HashMap<>();
        }
        return tcPutLabelRemaining;
    }

    @Unique
    private Map<ItemType, Integer> takeLabelRemaining() {
        if (tcTakeLabelRemaining == null) {
            tcTakeLabelRemaining = new HashMap<>();
        }
        return tcTakeLabelRemaining;
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
        if (tcShownPutKeys == null) {
            tcShownPutKeys = new HashSet<>();
        }
        return tcShownPutKeys;
    }

    @Unique
    private Set<ItemType> shownTakeKeys() {
        if (tcShownTakeKeys == null) {
            tcShownTakeKeys = new HashSet<>();
        }
        return tcShownTakeKeys;
    }

    /**
     * 该槽位是否属于当前要提示的一侧。
     * <p>
     * 背包侧：投影容器打开时总是提示；其它界面（含玩家背包）看配置。
     * 容器侧：打开了非投影的容器时才提示，避免没开容器时把合成格当成容器槽位。
     */
    @Unique
    private boolean isHintSide(HighlightState state, Slot slot) {
        boolean playerSlot = slot.inventory instanceof Inventory;

        // 创造模式物品栏里只画正常背包格子；它那个"直接拿物品/销毁"的栏不画
        if (isNonContainerScreen() && !playerSlot) {
            return false;
        }

        if (playerSlot) {
            return isProjectionContainerOpen(state) || HighlightConfig.isHintInPlayerInventory();
        }

        return !isProjectionContainerOpen(state) && InventoryOverlay.getCurrentContainerPos() != null;
    }

    /** 创造模式物品栏这类"没有真正容器"的界面：不按容器抓内容，非背包格子也不染色。 */
    @Unique
    private boolean isNonContainerScreen() {
        return (Object) this instanceof CreativeModeInventoryScreen;
    }

    @Unique
    private boolean isProjectionContainerOpen(HighlightState state) {
        // 大箱子的任意一半都算投影容器
        return state.isProjectionContainer(InventoryOverlay.getCurrentContainerPos());
    }
}
