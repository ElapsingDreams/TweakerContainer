package cn.yireve.tweakercontainer.client.handler;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import cn.yireve.tweakercontainer.client.data.ContainerDataManager;
import cn.yireve.tweakercontainer.client.data.ContainerSource;
import cn.yireve.tweakercontainer.client.data.ProjectionSelectionMode;
import cn.yireve.tweakercontainer.client.features.InventoryOverlay;
import cn.yireve.tweakercontainer.client.features.PlacementContainerAccess;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import static cn.yireve.tweakercontainer.client.BlockHighlighterRender.isHoldingTriggerItem;
import static cn.yireve.tweakercontainer.client.config.HighlightConfig.isEnabled;

/**
 * 处理高亮相关的方块交互：潜行右键清除标记，右键容器登记容器。
 */
public final class InteractionHandler {
    private InteractionHandler() {
    }

    public static void setup() {
        UseBlockCallback.EVENT.register(InteractionHandler::onBlockUse);
        AttackBlockCallback.EVENT.register(InteractionHandler::onBlockAttack);
    }

    /**
     * 角点模式左键：把点的位置记为选框起点，并吃掉这次攻击。
     * <p>
     * FAIL 既取消客户端处理也不发包，所以起手挖方块也不会发生；
     * 但按住左键不放时客户端走的是另一条 {@code updateBlockBreakingProgress}，
     * 那条由 {@code ClientPlayerInteractionManagerMixin} 按 {@link #shouldSuppressBlockBreaking()} 拦。
     */
    private static ActionResult onBlockAttack(PlayerEntity player, World world, Hand hand, BlockPos pos, Direction direction) {
        if (!world.isClient || !isCornerSelecting()) {
            return ActionResult.PASS;
        }

        HighlightState.get().setProjectionCorner(pos, true);
        return SUPPRESSED_RESULT;
    }

    private static ActionResult onBlockUse(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        if (!world.isClient) {
            return ActionResult.PASS;
        }

        HighlightState state = HighlightState.get();

        // 潜行右键清除标记：只清当前位置真的画着框的东西，没框的地方不响应也不提示
        if (player.isSneaking() && hitResult.getType() == BlockHitResult.Type.BLOCK && isHoldingTriggerItem() && isEnabled()) {
            BlockPos pos = hitResult.getBlockPos();

            // 投影来源（大箱子的另一半也算），以及投影里同样有容器的位置：取消整个投影。
            // 没有登记着投影时这一支不成立，也就不会有任何提示
            boolean projectionSide = state.isProjectionContainer(pos)
                    || (!state.getProjectionContainers().isEmpty()
                        && PlacementContainerAccess.isSchematicContainer(pos, world.getBlockState(pos)));
            if (projectionSide && state.removeProjectionContainer(pos)) {
                player.sendMessage(Text.translatable("tweakercontainer.message.removed_schem",
                        pos.getX(), pos.getY(), pos.getZ()), true);
                return SUPPRESSED_RESULT;
            }

            // 材料容器：必须登记着、而且此刻确实画得出蓝框（方块还是原来那个容器）才清。
            // 大箱子按整箱清，点哪一半都清整箱
            if (state.isStorageContainer(pos) && state.isStorageContainerPresent(pos, world)
                    && state.removeWholeStorageContainer(pos)) {
                player.sendMessage(Text.translatable("tweakercontainer.message.removed_storage",
                        pos.getX(), pos.getY(), pos.getZ()), true);
                return SUPPRESSED_RESULT;
            }
        }

        // 角点模式右键：记下选框终点，然后把框内每一个容器按和逐个右键一样的口径分流。
        // 潜行右键留给"清除标记"，不参与框选
        if (!player.isSneaking() && hitResult.getType() == BlockHitResult.Type.BLOCK && isCornerSelecting()) {
            state.setProjectionCorner(hitResult.getBlockPos(), false);
            HighlightState.BoxSelection selected = state.selectContainersInBox();

            // 框完就把选框收掉：留着会和刚框出来的那些框叠在一起，分不清哪个是选框
            state.clearProjectionCorners();

            player.sendMessage(Text.translatable("tweakercontainer.message.corner_selected",
                    selected.projections(), selected.storages()), true);
            return SUPPRESSED_RESULT;
        }

        // 右键容器：登记为投影/仓储容器并记录本次处理坐标（空手也算，界面提示要用）
        if (handleContainerClick(player, hitResult) && isHoldingTriggerItem()
                && shouldKeepContainerClosed(hitResult.getBlockPos())) {
            // 内容已经能从服务端拿到（内置服务端 / Servux / 原版查询），就不打开界面了。
            // 注意把待抓取坐标清掉：界面不会开，留着会被下一个界面（比如背包）误当成容器去抓
            state.setTempProcessingPos(null);
            return SUPPRESSED_RESULT;
        }

        return ActionResult.PASS;
    }

    /**
     * "这次交互算失败"的结果，用来把右键吃掉。
     * <p>
     * 不能用 {@link ActionResult#SUCCESS}：Fabric 的 UseBlockCallback 对"被接受"的结果
     * （SUCCESS / CONSUME）会顺手把 {@code PlayerInteractBlockC2SPacket} 发给服务端，
     * 服务端照样会把容器打开——这就是"服务端明明能拿到数据，界面还是弹出来"的原因。
     * FAIL 不被接受，既取消客户端处理、也不会发包。
     */
    private static final ActionResult SUPPRESSED_RESULT = ActionResult.FAIL;

    /**
     * 这次右键要不要拦下、不打开容器。
     * <p>
     * 只有"确实还能从别处拿到内容"时才拦：数据源退化成只能用开界面抓取时，照旧让它打开。
     * 投影来源默认不拦（那是往里放材料的地方），想连它一起拦就把对应的配置打开。
     */
    private static boolean shouldKeepContainerClosed(BlockPos pos) {
        if (!HighlightConfig.isSuppressContainerOpening()) {
            return false;
        }

        if (ContainerDataManager.get().effectiveSource() == ContainerSource.SCREEN) {
            return false;
        }

        return !HighlightState.get().isProjectionContainer(pos) || HighlightConfig.isSuppressProjectionOpening();
    }

    /**
     * 当前是不是角点框选手势：功能开着、手持触发物品、选了角点模式。
     * <p>
     * 手势期间左右键都不该干别的事（不开箱子、不挖方块、不登记容器）。
     */
    private static boolean isCornerSelecting() {
        return isEnabled() && isHoldingTriggerItem()
                && HighlightConfig.getProjectionSelectionMode() == ProjectionSelectionMode.CORNER;
    }

    /** 角点模式下按住左键不放也要挡住挖方块（起手那下由 AttackBlockCallback 拦）。 */
    public static boolean shouldSuppressBlockBreaking() {
        return isCornerSelecting();
    }

    /**
     * 登记容器的唯一入口。
     * <p>
     * {@link UseBlockCallback} 与 {@code ClientPlayerInteractionManagerMixin} 都会调到这里，
     * 早先两处各写一套，结果潜行右键被其中一处当成登记，弹出了莫名其妙的清除提示。
     * <p>
     * 登记本身不管手不手持触发物品都做：空手开箱子也是正常玩法，界面里的格子提示要靠这次登记
     * 记下"当前容器"。真正会改动玩家东西的两件事——"材料够了自动撤标记"和"吃掉右键不打开界面"——
     * 才只该在手持触发物品时发生。
     *
     * @return 是否登记成功
     */
    public static boolean handleContainerClick(PlayerEntity player, BlockHitResult hitResult) {
        // 角点模式下手持触发物品时的左右键都是选框手势：不登记容器、也不改动投影集合。
        // 必须挡在 InventoryOverlay.onContainerClick 之前，那个方法一进门就会改状态。
        // 空手开箱子照旧登记（界面里的格子提示要用）
        if (isCornerSelecting()) {
            return false;
        }

        if (player == null || player.isSneaking() || !isEnabled() || !InventoryOverlay.onContainerClick(hitResult)) {
            return false;
        }

        HighlightState state = HighlightState.get();
        state.setTempProcessingPos(hitResult.getBlockPos());

        // 空手右键只是开箱子，不该顺手把标记撤掉
        if (isHoldingTriggerItem()) {
            state.checkAndRemoveSatisfiedContainer(hitResult.getBlockPos());
        }

        return true;
    }
}
