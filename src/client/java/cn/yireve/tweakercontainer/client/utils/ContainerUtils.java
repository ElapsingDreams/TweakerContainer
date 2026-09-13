package cn.yireve.tweakercontainer.client.utils;

import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.malilib.render.InventoryOverlay;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Optional;

public class ContainerUtils {
    /**
     * 取出该方块的容器内容。
     * <p>
     * world 为 null 时用方块自身创建的空容器占位，只用于判断容器类型与容量。
     */
    public static Optional<Inventory> validateContainer(World world, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof BlockEntityProvider provider) {
            BlockEntity blockEntity;
            if (world != null && InventoryUtils.getTargetInventory(world, pos) instanceof InventoryOverlay.Context ctx && ctx.be() instanceof BlockEntity be) {
                blockEntity = be;
            } else {
                blockEntity = provider.createBlockEntity(pos, state);
            }
            if (blockEntity instanceof Inventory inventory)
                return Optional.of(inventory);
        }
        return Optional.empty();
    }

    public static Optional<Inventory> validateContainer(BlockPos pos, BlockState state) {
        return validateContainer(null, pos, state);
    }
}
