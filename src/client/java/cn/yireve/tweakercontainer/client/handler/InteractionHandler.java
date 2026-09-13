package cn.yireve.tweakercontainer.client.handler;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.features.InventoryOverlay;
import cn.yireve.tweakercontainer.client.features.PlacementContainerAccess;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
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
    }

    private static ActionResult onBlockUse(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        if (!world.isClient) {
            return ActionResult.PASS;
        }

        HighlightState state = HighlightState.get();

        // 潜行右键清除标记
        if (player.isSneaking() && hitResult.getType() == BlockHitResult.Type.BLOCK && isHoldingTriggerItem() && isEnabled()) {
            BlockPos pos = hitResult.getBlockPos();

            // 投影里的容器——登记的那一格（大箱子两半都算）、以及蓝图里同样是容器的格子——
            // 一律不算材料容器：有投影时按取消整个投影处理，没有投影时什么都不做。
            // 没登记过的普通容器同样不响应也不提示，只认有蓝框的仓储容器
            boolean projectionSide = state.isProjectionContainer(pos)
                    || PlacementContainerAccess.isSchematicContainer(pos, world.getBlockState(pos));
            if (projectionSide) {
                if (state.getCurrentProjectionContainer() != null) {
                    state.removeProjectContainer();
                    player.sendMessage(Text.translatable("tweakercontainer.message.removed_schem",
                            pos.getX(), pos.getY(), pos.getZ()), true);
                    return ActionResult.SUCCESS;
                }
                return ActionResult.PASS;
            }

            if (state.isStorageContainer(pos)) {
                state.removeStorageContainer(pos);
                player.sendMessage(Text.translatable("tweakercontainer.message.removed_storage",
                        pos.getX(), pos.getY(), pos.getZ()), true);
                return ActionResult.SUCCESS;
            }
        }

        // 右键容器：登记为投影/仓储容器并记录本次处理坐标
        if (!player.isSneaking() && isEnabled() && InventoryOverlay.onContainerClick(hitResult)) {
            state.setTempProcessingPos(hitResult.getBlockPos());
            state.checkAndRemoveSatisfiedContainer(hitResult.getBlockPos());
        }

        return ActionResult.PASS;
    }
}
