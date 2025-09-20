package cn.envision.xihe.client.features;

import cn.envision.xihe.client.BlockHighlighterRender;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static cn.envision.xihe.client.BlockHighlighterRender.clearTempHighlightedBlocks;
import static cn.envision.xihe.client.TweakerXiheClient.HIGHLIGHTED_BLOCKS;
import static cn.envision.xihe.client.config.HighlightConfig.getSW;

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
        if (getSW()) {
            BlockPos pos = hitResult.getBlockPos();
            World world = WorldUtils.getBestWorld(MinecraftClient.getInstance());
            if (world == null) return;

            BlockState state = world.getBlockState(pos);
            Optional<Inventory> inventory = ContainerUtils.validateContainer(world, pos, state);

            // 检查是否是有效的容器
            if (inventory.isEmpty() || !(inventory.get() instanceof ScreenHandlerFactory)) {
                return;
            }

            // 检查是否是投影中的容器
            Optional<LocalPlacementPos> placementPos = LocalPlacementPos.get(pos);
            if (placementPos.isPresent()) {
                // 是投影容器，设置为当前投影容器

                //clearTempHighlightedBlocks();
                if (!BlockHighlighterRender.setCurrentProjectionContainer(pos)) {
                    BlockHighlighterRender.addStorageContainer(pos);
                    getInstance().currentContainerPos = pos;
                }
                //System.out.println(pos);
                getInstance().currentContainerPos = pos;

                // 获取投影容器需要的物品和当前物品，计算缺失的
            /*Optional<SimpleInventory> schematicInv = PlacementContainerAccess.getSchematicInventory(pos, state);
            if (schematicInv.isPresent()) {
                List<ItemStack> missingItems = BlockHighlighterRender.findMissingItems(pos, schematicInv.get());
                showMissingItemsHint(missingItems);
            }*/
            } else {
                // 不是投影容器，作为仓储容器处理
                // 重复打开不会重复添加，只会刷新
                BlockHighlighterRender.addStorageContainer(pos);
                getInstance().currentContainerPos = pos;
            }
        }
    }

    public static void onContainerClose(BlockPos pos) {
        if (pos == null) return;

        World world = WorldUtils.getBestWorld(MinecraftClient.getInstance());
        if (world == null) return;

        BlockState state = world.getBlockState(pos);
        // 重新获取容器内容
        Optional<Inventory> inventory = ContainerUtils.validateContainer(world, pos, state);
        if (inventory.isEmpty()) return;

        // 检查是否是投影中的容器
        Optional<LocalPlacementPos> placementPos = LocalPlacementPos.get(pos);
        if (placementPos.isPresent()) {
            // 更新缺失物品列表
            Optional<SimpleInventory> schematicInv = PlacementContainerAccess.getSchematicInventory(pos, state);
            if (schematicInv.isPresent()) {
                List<ItemStack> missingItems = BlockHighlighterRender.findMissingItems(pos, schematicInv.get());
                showMissingItemsHint(missingItems);
            }
        }
        // 仓储容器自动刷新，无需额外操作
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

    public static List<ItemStack> getCurrentMissingItems() {
        return BlockHighlighterRender.getCurrentMissingItems();
    }
}
