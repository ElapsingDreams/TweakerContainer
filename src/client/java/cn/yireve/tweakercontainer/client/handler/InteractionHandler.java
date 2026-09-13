package cn.yireve.tweakercontainer.client.handler;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.features.InventoryOverlay;
import cn.yireve.tweakercontainer.client.utils.LocalPlacementPos;
import fi.dy.masa.malilib.util.WorldUtils;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
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
            World bestWorld = WorldUtils.getBestWorld(MinecraftClient.getInstance());
            boolean container = InventoryOverlay.isContainer(bestWorld != null ? bestWorld : world, pos);

            // 投影里的容器：登记的那一格（大箱子两半都算），以及蓝图里同样有容器的位置，
            // 潜行右键都是取消整个投影
            boolean inProjection = container
                    && state.getCurrentProjectionContainer() != null
                    && LocalPlacementPos.get(pos).isPresent();
            if (state.isProjectionContainer(pos) || inProjection) {
                state.removeProjectContainer();
                player.sendMessage(Text.translatable("tweakercontainer.message.removed_schem",
                        pos.getX(), pos.getY(), pos.getZ()), true);
                return ActionResult.SUCCESS;
            }

            // 其余容器：不要求它还挂在名单上——登记可能已经被“材料够了”或方块复核撤掉，
            // 玩家手动清除时照样要清一次并给反馈。只认容器，免得对着普通方块乱提示
            if (container) {
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
