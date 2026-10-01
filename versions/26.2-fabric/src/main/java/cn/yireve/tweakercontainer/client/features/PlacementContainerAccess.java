package cn.yireve.tweakercontainer.client.features;

import cn.yireve.tweakercontainer.client.utils.ContainerUtils;

import cn.yireve.tweakercontainer.client.utils.LocalPlacementPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * Used to access container data inside a placement.
 * <p>
 * Is this overcomplicated? Definitely.
 */
public final class PlacementContainerAccess {
    private static final SimpleContainer EMPTY_CHEST_INVENTORY = new SimpleContainer(27);

    public static Optional<SimpleContainer> getSchematicInventory(BlockPos worldPos, BlockState worldState) {
        ChestType type = getChestType(worldState);
        if (type == ChestType.SINGLE)
            return getSchematicInventoryInternal(worldPos, worldState);

        // Double chest handling
        Level world = Minecraft.getInstance().level;
        if (world == null)
            return Optional.empty();
        BlockPos adjacentChest = worldPos.relative(ChestBlock.getConnectedDirection(worldState));
        BlockState adjacentState = world.getBlockState(adjacentChest);

        Optional<SimpleContainer> opt1 = getSchematicInventoryInternal(worldPos, worldState);
        Optional<SimpleContainer> opt2 = getSchematicInventoryInternal(adjacentChest, adjacentState);
        if (opt1.isEmpty() && opt2.isEmpty())
            return Optional.empty();
        SimpleContainer chest1 = opt1.orElse(EMPTY_CHEST_INVENTORY);
        SimpleContainer chest2 = opt2.orElse(EMPTY_CHEST_INVENTORY);

        return type == ChestType.RIGHT ? Optional.of(merge(chest1, chest2)) : Optional.of(merge(chest2, chest1));
    }

    private static Optional<SimpleContainer> getSchematicInventoryInternal(BlockPos worldPos, BlockState worldState) {
        LocalPlacementPos placementPos = matchSchematicContainer(worldPos, worldState);
        return placementPos == null ? Optional.empty() : Optional.ofNullable(getItems(placementPos));
    }

    /**
     * 蓝图这一格（大箱子时算上另一半）是不是和世界方块配套的容器。
     * <p>
     * 与 {@link #getSchematicInventory} 的区别：这里不看容器里有没有东西，
     * 只看“蓝图这格本来就是个同样的容器”，用来判定投影区里的箱子。
     */
    public static boolean isSchematicContainer(BlockPos worldPos, BlockState worldState) {
        if (matchSchematicContainer(worldPos, worldState) != null) {
            return true;
        }
        if (getChestType(worldState) == ChestType.SINGLE) {
            return false;
        }

        Level world = Minecraft.getInstance().level;
        if (world == null) {
            return false;
        }
        BlockPos adjacentChest = worldPos.relative(ChestBlock.getConnectedDirection(worldState));
        return matchSchematicContainer(adjacentChest, world.getBlockState(adjacentChest)) != null;
    }

    /**
     * 世界方块与蓝图同一格上的容器配套时返回蓝图坐标，否则返回 null。
     */
    @Nullable
    private static LocalPlacementPos matchSchematicContainer(BlockPos worldPos, BlockState worldState) {
        Optional<Container> dummyInv = ContainerUtils.validateContainer(worldPos, worldState);
        // Level block is not a container
        if (dummyInv.isEmpty()) {
            return null;
        }

        Optional<LocalPlacementPos> optionalPos = LocalPlacementPos.get(worldPos);
        // Block is not in the schematic
        if (optionalPos.isEmpty()) {
            return null;
        }

        LocalPlacementPos placementPos = optionalPos.get();
        Optional<Container> schemInv = ContainerUtils.validateContainer(worldPos, placementPos.blockState());
        // Schematic and world blocks don't match
        if (schemInv.isEmpty()
                || dummyInv.get().getContainerSize() != schemInv.get().getContainerSize()
                || !(schemInv.get() instanceof BlockEntity schemBE)
                || !(dummyInv.get() instanceof BlockEntity dummyBE)
                || schemBE.getType() != dummyBE.getType()
        ) {
            return null;
        }

        return placementPos;
    }

    private static SimpleContainer merge(Container first, Container second) {
        CompoundContainer combined = new CompoundContainer(first, second);
        SimpleContainer inventory = new SimpleContainer(combined.getContainerSize());
        for (int i = 0; i < combined.getContainerSize(); i++) {
            inventory.setItem(i, combined.getItem(i));
        }
        return inventory;
    }

    /**
     * @return {@link ChestType} of a block state. Returns {@link ChestType#SINGLE} for any other state, as all that matters is if it's a single-block storage.
     */
    private static ChestType getChestType(BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock))
            return ChestType.SINGLE;
        return state.getValue(ChestBlock.TYPE);
    }

    @Nullable
    private static SimpleContainer getItems(LocalPlacementPos placementPos) {
        // 26.2 起 litematica 的方块实体表给的是 malilib 新 data 层的 CompoundData，不再是 NBT
        Map<BlockPos, CompoundData> blockEntities = placementPos.placement().getSchematic()
                .getBlockEntityMapForRegion(placementPos.region());
        // No block entity map for the region. Shouldn't be possible unless it was manually modified
        if (blockEntities == null)
            return null;

        CompoundData data = blockEntities.get(placementPos.pos());
        // No such entry in the map
        if (data == null)
            return null;

        Level world = Minecraft.getInstance().level;
        if (world == null)
            return null;

        // 26.2：malilib 直接把这份数据变成容器，不用再自己拼一个方块实体出来
        Container schematicInventory = InventoryUtils.getDataInventory(data);
        if (schematicInventory == null) {
            return null;
        }

        SimpleContainer inventory = new SimpleContainer(schematicInventory.getContainerSize());

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            inventory.setItem(i, schematicInventory.getItem(i));
        }

        return inventory;
    }
}
