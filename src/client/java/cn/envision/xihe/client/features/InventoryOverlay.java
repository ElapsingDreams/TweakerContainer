package cn.envision.xihe.client.features;

import cn.envision.xihe.client.HighlightState;
import cn.envision.xihe.client.utils.ContainerUtils;
import cn.envision.xihe.client.utils.LocalPlacementPos;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

import static cn.envision.xihe.client.config.HighlightConfig.isEnabled;

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

    public static void onContainerClick(BlockHitResult hitResult) {
        if (!isEnabled()) {
            return;
        }

        BlockPos pos = hitResult.getBlockPos();
        World world = WorldUtils.getBestWorld(MinecraftClient.getInstance());
        if (world == null) {
            return;
        }

        BlockState state = world.getBlockState(pos);
        Optional<Inventory> inventory = ContainerUtils.validateContainer(world, pos, state);

        // 检查是否是有效的容器
        if (inventory.isEmpty() || !(inventory.get() instanceof ScreenHandlerFactory)) {
            return;
        }

        HighlightState highlightState = HighlightState.get();

        // 检查是否是投影中的容器
        if (LocalPlacementPos.get(pos).isPresent()) {
            // 是投影容器则登记为投影来源，读取不到内容时退化为仓储容器
            if (!highlightState.setCurrentProjectionContainer(pos)) {
                highlightState.addStorageContainer(pos);
            }
        } else {
            // 不是投影容器，作为仓储容器处理；重复打开只会刷新
            highlightState.addStorageContainer(pos);
        }
        getInstance().currentContainerPos = pos;
    }

    public static void onContainerClose(BlockPos pos) {
        if (pos == null) {
            return;
        }

        World world = WorldUtils.getBestWorld(MinecraftClient.getInstance());
        if (world == null) {
            return;
        }

        BlockState state = world.getBlockState(pos);
        // 重新获取容器内容
        if (ContainerUtils.validateContainer(world, pos, state).isEmpty()) {
            return;
        }

        // 投影容器：提示还缺哪些物品；仓储容器自动刷新，无需额外操作
        if (LocalPlacementPos.get(pos).isPresent()) {
            Optional<SimpleInventory> schematicInv = PlacementContainerAccess.getSchematicInventory(pos, state);
            if (schematicInv.isPresent()) {
                showMissingItemsHint(HighlightState.get().findMissingItems(pos, schematicInv.get()));
            }
        }
    }

    private static void showMissingItemsHint(List<ItemStack> missingItems) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (missingItems.isEmpty()) {
            client.player.sendMessage(Text.translatable("xihe.message.all_items_present"), true);
            return;
        }

        client.player.sendMessage(Text.translatable("xihe.message.missing_items"), true);
        for (ItemStack stack : missingItems) {
            Text itemName = stack.getItem().getName();
            client.player.sendMessage(Text.translatable("xihe.message.item_format",
                    stack.getCount(), itemName), true);
        }
    }

    public static BlockPos getCurrentContainerPos() {
        return getInstance().currentContainerPos;
    }
}
