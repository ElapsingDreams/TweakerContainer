package cn.envision.xihe.client.handler;

import cn.envision.xihe.client.HighlightState;
import cn.envision.xihe.client.features.InventoryOverlay;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import static cn.envision.xihe.client.BlockHighlighterRender.isHoldingTriggerItem;
import static cn.envision.xihe.client.config.HighlightConfig.isEnabled;

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

            if (state.isStorageContainer(pos)) {
                state.removeStorageContainer(pos);
                player.sendMessage(Text.translatable("xihe.message.removed_storage",
                        pos.getX(), pos.getY(), pos.getZ()), true);
                return ActionResult.SUCCESS;
            }
            if (pos.equals(state.getCurrentProjectionContainer())) {
                state.removeProjectContainer();
                player.sendMessage(Text.translatable("xihe.message.removed_schem",
                        pos.getX(), pos.getY(), pos.getZ()), true);
                return ActionResult.SUCCESS;
            }
        }

        // 右键容器：登记为投影/仓储容器并记录本次处理坐标
        if (!player.isSneaking() && isEnabled()) {
            InventoryOverlay.onContainerClick(hitResult);
            state.setTempProcessingPos(hitResult.getBlockPos());
            state.checkAndRemoveSatisfiedContainer(hitResult.getBlockPos());
        }

        return ActionResult.PASS;
    }
}
