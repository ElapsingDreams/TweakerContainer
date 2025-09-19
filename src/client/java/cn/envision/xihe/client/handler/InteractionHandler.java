package cn.envision.xihe.client.handler;

import cn.envision.xihe.client.BlockHighlighterRender;
import cn.envision.xihe.client.features.InventoryOverlay;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Queue;

public abstract class InteractionHandler {
    private static final Queue<InteractionHandler> queue = new ArrayDeque<>();
    private final long tick;
    private final BlockPos pos;

    public InteractionHandler(BlockPos pos, long tick) {
        this.pos = pos;
        this.tick = tick;
    }

    public static void setup() {
        UseBlockCallback.EVENT.register(InteractionHandler::onBlockUse);
    }

    private static ActionResult onBlockUse(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        // 处理潜行右键清除标记
        if (player.isSneaking() && hitResult.getType() == BlockHitResult.Type.BLOCK) {
            BlockPos pos = hitResult.getBlockPos();

            // 清除单个标记
            if (BlockHighlighterRender.isStorageContainer(pos)) {
                BlockHighlighterRender.removeStorageContainer(pos);
                player.sendMessage(Text.translatable("xihe.message.removed_storage",
                        pos.getX(), pos.getY(), pos.getZ()), true);
                return ActionResult.SUCCESS;
            }
        } else if (player.isSneaking() && hitResult.getType() == BlockHitResult.Type.MISS) {
            // 潜行右键空气清除所有标记
            BlockHighlighterRender.clearAll();
            player.sendMessage(Text.translatable("xihe.message.cleared_all"), true);
            return ActionResult.SUCCESS;
        }

        // 处理容器点击
        if (!player.isSneaking()) {
            InventoryOverlay.onContainerClick(hitResult);
        }

        return ActionResult.PASS;
    }

    public static boolean contains(BlockPos pos) {
        for (InteractionHandler handler : queue) {
            if (handler.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public abstract void process();

    public BlockPos getPos() {
        return pos;
    }

    public long getTick() {
        return tick;
    }

    public static Queue<InteractionHandler> getQueue() {
        return queue;
    }
}
