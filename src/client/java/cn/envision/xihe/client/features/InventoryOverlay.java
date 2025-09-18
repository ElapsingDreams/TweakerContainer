package cn.envision.xihe.client.features;

import cn.envision.xihe.client.LinkedStorageEntry;
import cn.envision.xihe.client.handler.InteractionHandler;
import cn.envision.xihe.client.utils.ContainerUtils;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandlerFactory;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Objects;
import java.util.Optional;

public class InventoryOverlay {
    private static InventoryOverlay instance = null;
    public static void onContainerClick(BlockHitResult hitResult) {
        BlockPos pos = hitResult.getBlockPos();
        World world = WorldUtils.getBestWorld(MinecraftClient.getInstance());
        if (!(ContainerUtils.validateContainer(world, pos, world.getBlockState(pos)).orElse(null) instanceof ScreenHandlerFactory))
            return;
        if (InteractionHandler.contains(pos))
            return;
        //System.out.println(pos);;
        instance = get(pos, true).orElse(null);
    }
    public static Optional<InventoryOverlay> get(BlockPos pos, boolean queueInteraction) {
        World world = Objects.requireNonNull(WorldUtils.getBestWorld(MinecraftClient.getInstance()));

        long tick = world.getTime();
        BlockState state = world.getBlockState(pos);
        Optional<Inventory> inventory = ContainerUtils.validateContainer(world, pos, state);
        if (inventory.isEmpty())
            return Optional.empty();

        //LinkedStorageEntry entry = PlacementContainerAccess.getEntry(pos, state, null);
        Optional<SimpleInventory> Schem = PlacementContainerAccess.getSchematicInventory(pos, state);
        //instance = get(pos, true).orElse(null);
        if(Schem.isPresent()){
            SimpleInventory inventory1 = Schem.get();
            for (int i = 0; i < inventory1.size(); i++){
                ItemStack stack = inventory1.getStack(i);
                if (!stack.isEmpty()) {
                    String itemID = stack.getItem().toString();
                    int count = stack.getCount();
                    System.out.println("槽位 " + i + ": " + itemID + " x " + count);
                }else {
                    System.out.println("槽位 " + i + ": 空");
                }
            }
        }
        /*
        entry.setWorldInventory(inventory.get());
        if (entry.getPlacementInventory().isEmpty())
            return Optional.empty();
        if (queueInteraction) InteractionHandler.add(new InteractionHandler(pos, tick) {
            @Override
            public boolean accept(Screen screen) {
                Slot validSlot = null;
                for (Slot slot : ((ScreenHandlerProvider<?>) screen).getScreenHandler().slots) {
                    if (!(slot.inventory instanceof PlayerInventory)) {
                        validSlot = slot;
                        break;
                    }
                }
                if (validSlot != null)
                    entry.setWorldInventory(validSlot.inventory);
                return true;
            }
        });
        return Optional.of(new InventoryOverlay(entry));*/

        return Optional.empty();
    }
}
