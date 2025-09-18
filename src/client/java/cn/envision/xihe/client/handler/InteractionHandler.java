package cn.envision.xihe.client.handler;


import cn.envision.xihe.Tweakerxihe;
import cn.envision.xihe.client.XiheClient;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Queue;

public abstract class InteractionHandler {

    private static final Queue<InteractionHandler> queue = new ArrayDeque<>();

    private final long tick;

    private final BlockPos pos;

    public InteractionHandler(BlockPos pos, long tick) {
        this.tick = tick;
        this.pos = pos;
    }


    public static boolean contains(BlockPos pos) {
        for (InteractionHandler handler : queue)
            if (handler.pos.equals(pos))
                return true;
        return false;
    }
    public static void setup() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player.isSneaking() && hitResult.getType() == BlockHitResult.Type.BLOCK) {
                BlockPos pos = hitResult.getBlockPos();
                if (XiheClient.HIGHLIGHTED_BLOCKS.contains(pos)) {
                    XiheClient.HIGHLIGHTED_BLOCKS.remove(pos);
                    player.sendMessage(Text.literal("Removed highlight at " + pos.toShortString()), true);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });
    }
}
