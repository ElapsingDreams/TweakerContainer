package cn.yireve.tweakercontainer.client.handler;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.features.InventoryOverlay;
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

        // 潜行右键清除标记：只清当前位置真的画着框的东西，没框的地方不响应也不提示
        if (player.isSneaking() && hitResult.getType() == BlockHitResult.Type.BLOCK && isHoldingTriggerItem() && isEnabled()) {
            BlockPos pos = hitResult.getBlockPos();

            // 投影来源：大箱子的另一半也算，取消即整个投影
            if (state.isProjectionContainer(pos) && state.removeProjectContainer()) {
                player.sendMessage(Text.translatable("tweakercontainer.message.removed_schem",
                        pos.getX(), pos.getY(), pos.getZ()), true);
                return ActionResult.SUCCESS;
            }

            // 材料容器：必须登记着、而且此刻确实画得出蓝框（方块还是原来那个容器）才清，
            // 这样提示和框永远对得上——没蓝框的箱子不会响应也不会提示
            if (state.isStorageContainer(pos) && state.isStorageContainerPresent(pos, world)
                    && state.removeStorageContainer(pos)) {
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
