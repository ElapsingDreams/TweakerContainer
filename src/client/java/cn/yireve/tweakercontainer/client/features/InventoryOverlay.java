package cn.yireve.tweakercontainer.client.features;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.utils.ContainerUtils;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandlerFactory;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Optional;

import static cn.yireve.tweakercontainer.client.config.HighlightConfig.isEnabled;

/**
 * 记录玩家当前操作的容器，并区分投影容器与仓储容器。
 */
public class InventoryOverlay {
    private static InventoryOverlay instance;
    private BlockPos currentContainerPos;

    private InventoryOverlay(BlockPos pos) {
        this.currentContainerPos = pos;
    }

    public static InventoryOverlay getInstance() {
        if (instance == null) {
            instance = new InventoryOverlay(BlockPos.ORIGIN);
        }
        return instance;
    }

    /**
     * 该位置是不是能登记的容器：有方块实体、能读出背包、并且是个能开界面的容器。
     * <p>
     * 登记与右键清除都用这一个判断，免得两边对“什么算容器”的看法不一致。
     */
    public static boolean isContainer(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        Optional<Inventory> inventory = ContainerUtils.validateContainer(world, pos, state);
        return inventory.isPresent() && inventory.get() instanceof ScreenHandlerFactory;
    }

    /**
     * @return 该方块是否已登记为投影容器或仓储容器
     */
    public static boolean onContainerClick(BlockHitResult hitResult) {
        if (!isEnabled()) {
            return false;
        }

        BlockPos pos = hitResult.getBlockPos();
        World world = WorldUtils.getBestWorld(MinecraftClient.getInstance());

        // 检查是否是有效的容器
        if (!isContainer(world, pos)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        HighlightState highlightState = HighlightState.get();

        // 蓝图里同样是容器的位置（也就是投影区的箱子）只可能是投影来源，绝不能登记成材料容器，
        // 否则投影上会冒出蓝框、取消时也会提示成清了材料。蓝图内容读不出来（比如空箱子）就不登记
        if (PlacementContainerAccess.isSchematicContainer(pos, state)) {
            highlightState.setCurrentProjectionContainer(pos);
        } else {
            // 不是投影容器，作为仓储容器处理；重复打开只会刷新
            highlightState.addStorageContainer(pos, state);
        }
        getInstance().currentContainerPos = pos;
        return true;
    }

    public static BlockPos getCurrentContainerPos() {
        return getInstance().currentContainerPos;
    }

    /**
     * 界面关闭时清掉记录，避免坐标过期导致其它界面被误判成还在该容器里。
     */
    public static void clearCurrentContainer() {
        getInstance().currentContainerPos = null;
    }
}
