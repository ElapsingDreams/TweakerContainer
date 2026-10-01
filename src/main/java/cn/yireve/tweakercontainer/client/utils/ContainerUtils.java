package cn.yireve.tweakercontainer.client.utils;

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
     * 只用于判断"这格是不是容器、是什么容器、有多少格子"，所以拿客户端世界自己的方块实体就够；
     * world 为 null（或那格在客户端取不到方块实体）时用方块自身创建的空容器占位。
     * <p>
     * <b>这里绝不能改成 litematica 的 {@code InventoryUtils#getTargetInventory(world, pos)}</b>：
     * 它在客户端分支里会调 {@code EntitiesDataStorage.requestBlockEntity(world, pos)}
     * ——对没缓存的格子会去服务端要数据，并把它排进 litematica 自己的待办表。
     * 我们会成批调用这个方法（框选要把选框里每个容器都判一遍），一灌就把它的账本灌坏，
     * 之后它 {@code tickCache} 里 {@code blockEntityCache.get(pos).getLeft()} 拿到 null 直接 NPE 崩游戏
     * （0.23.4 的 {@code EntitiesDataStorage.java:359}，0.23.7 那行同样没判空）。
     * 换句话说：那条路会替我们向服务端发请求，而发出来的请求是我们控制不了、也收不到回包的。
     */
    public static Optional<Inventory> validateContainer(World world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BlockEntityProvider provider)) {
            return Optional.empty();
        }

        BlockEntity blockEntity = world != null ? world.getBlockEntity(pos) : null;
        if (blockEntity == null) {
            blockEntity = provider.createBlockEntity(pos, state);
        }

        return blockEntity instanceof Inventory inventory ? Optional.of(inventory) : Optional.empty();
    }

    public static Optional<Inventory> validateContainer(BlockPos pos, BlockState state) {
        return validateContainer(null, pos, state);
    }
}
