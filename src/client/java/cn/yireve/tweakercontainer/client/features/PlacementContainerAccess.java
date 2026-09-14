package cn.yireve.tweakercontainer.client.features;

import cn.yireve.tweakercontainer.client.utils.ContainerUtils;

import cn.yireve.tweakercontainer.client.utils.LocalPlacementPos;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Used to access container data inside a placement.
 * <p>
 * Is this overcomplicated? Definitely.
 */
public final class PlacementContainerAccess {
    private static final SimpleInventory EMPTY_CHEST_INVENTORY = new SimpleInventory(27);

    public static Optional<SimpleInventory> getSchematicInventory(BlockPos worldPos, BlockState worldState) {
        ChestType type = getChestType(worldState);
        if (type == ChestType.SINGLE)
            return getSchematicInventoryInternal(worldPos, worldState);

        // Double chest handling
        World world = MinecraftClient.getInstance().world;
        if (world == null)
            return Optional.empty();
        BlockPos adjacentChest = worldPos.add(ChestBlock.getFacing(worldState).getVector());
        BlockState adjacentState = world.getBlockState(adjacentChest);

        Optional<SimpleInventory> opt1 = getSchematicInventoryInternal(worldPos, worldState);
        Optional<SimpleInventory> opt2 = getSchematicInventoryInternal(adjacentChest, adjacentState);
        if (opt1.isEmpty() && opt2.isEmpty())
            return Optional.empty();
        SimpleInventory chest1 = opt1.orElse(EMPTY_CHEST_INVENTORY);
        SimpleInventory chest2 = opt2.orElse(EMPTY_CHEST_INVENTORY);

        return type == ChestType.RIGHT ? Optional.of(merge(chest1, chest2)) : Optional.of(merge(chest2, chest1));
    }

    private static Optional<SimpleInventory> getSchematicInventoryInternal(BlockPos worldPos, BlockState worldState) {
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

        World world = MinecraftClient.getInstance().world;
        if (world == null) {
            return false;
        }
        BlockPos adjacentChest = worldPos.add(ChestBlock.getFacing(worldState).getVector());
        return matchSchematicContainer(adjacentChest, world.getBlockState(adjacentChest)) != null;
    }

    /**
     * 找出选框内"蓝图里也是容器"的所有世界坐标，按角点框选加入投影集合用。
     * <p>
     * 不逐格扫描方块：直接遍历所有已放置投影的（已启用）子区域方块实体表，只对容器位置做坐标变换。
     * 取舍与右键登记一致——蓝图里内容为空的箱子本来就没有需求、右键也登记不上，这里同样不列出来；
     * 世界那格还不是配套容器（没建出来、区块没加载、被换成别的方块）的位置也会跳过。
     *
     * @return 世界坐标列表；没有任何命中时返回空表
     */
    public static List<BlockPos> findSchematicContainersInBox(BlockPos cornerA, BlockPos cornerB) {
        World world = MinecraftClient.getInstance().world;
        if (world == null || cornerA == null || cornerB == null) {
            return List.of();
        }

        int minX = Math.min(cornerA.getX(), cornerB.getX());
        int minY = Math.min(cornerA.getY(), cornerB.getY());
        int minZ = Math.min(cornerA.getZ(), cornerB.getZ());
        int maxX = Math.max(cornerA.getX(), cornerB.getX());
        int maxY = Math.max(cornerA.getY(), cornerB.getY());
        int maxZ = Math.max(cornerA.getZ(), cornerB.getZ());

        // 同一个世界坐标可能被多个投影/子区域覆盖，去重后再逐个复核世界方块
        Set<BlockPos> candidates = new LinkedHashSet<>();

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!placement.isEnabled()) {
                continue;
            }

            for (String region : placement.getEnabledRelativeSubRegionPlacements().keySet()) {
                Map<BlockPos, NbtCompound> blockEntities = placement.getSchematic().getBlockEntityMapForRegion(region);
                if (blockEntities == null || blockEntities.isEmpty()) {
                    continue;
                }

                for (BlockPos schematicPos : blockEntities.keySet()) {
                    BlockPos worldPos = LocalPlacementPos.getWorldPos(schematicPos, region, placement);
                    if (worldPos.getX() < minX || worldPos.getX() > maxX
                            || worldPos.getY() < minY || worldPos.getY() > maxY
                            || worldPos.getZ() < minZ || worldPos.getZ() > maxZ) {
                        continue;
                    }
                    candidates.add(worldPos);
                }
            }
        }

        List<BlockPos> result = new ArrayList<>();
        for (BlockPos worldPos : candidates) {
            if (isSchematicContainer(worldPos, world.getBlockState(worldPos))) {
                result.add(worldPos);
            }
        }
        return result;
    }

    /**
     * 世界方块与蓝图同一格上的容器配套时返回蓝图坐标，否则返回 null。
     */
    @Nullable
    private static LocalPlacementPos matchSchematicContainer(BlockPos worldPos, BlockState worldState) {
        Optional<Inventory> dummyInv = ContainerUtils.validateContainer(worldPos, worldState);
        // World block is not a container
        if (dummyInv.isEmpty()) {
            return null;
        }

        Optional<LocalPlacementPos> optionalPos = LocalPlacementPos.get(worldPos);
        // Block is not in the schematic
        if (optionalPos.isEmpty()) {
            return null;
        }

        LocalPlacementPos placementPos = optionalPos.get();
        Optional<Inventory> schemInv = ContainerUtils.validateContainer(worldPos, placementPos.blockState());
        // Schematic and world blocks don't match
        if (schemInv.isEmpty()
                || dummyInv.get().size() != schemInv.get().size()
                || !(schemInv.get() instanceof BlockEntity schemBE)
                || !(dummyInv.get() instanceof BlockEntity dummyBE)
                || schemBE.getType() != dummyBE.getType()
        ) {
            return null;
        }

        return placementPos;
    }

    private static SimpleInventory merge(Inventory first, Inventory second) {
        DoubleInventory combined = new DoubleInventory(first, second);
        SimpleInventory inventory = new SimpleInventory(combined.size());
        for (int i = 0; i < combined.size(); i++) {
            inventory.setStack(i, combined.getStack(i));
        }
        return inventory;
    }

    /**
     * @return {@link ChestType} of a block state. Returns {@link ChestType#SINGLE} for any other state, as all that matters is if it's a single-block storage.
     */
    private static ChestType getChestType(BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock))
            return ChestType.SINGLE;
        return state.get(ChestBlock.CHEST_TYPE);
    }

    @Nullable
    private static SimpleInventory getItems(LocalPlacementPos placementPos) {
        Map<BlockPos, NbtCompound> blockEntities = placementPos.placement().getSchematic()
                .getBlockEntityMapForRegion(placementPos.region());
        // No block entity map for the region. Shouldn't be possible unless it was manually modified
        if (blockEntities == null)
            return null;

        NbtCompound nbt = blockEntities.get(placementPos.pos());
        // No such entry in the map
        if (nbt == null)
            return null;

        World world = MinecraftClient.getInstance().world;
        if (world == null)
            return null;
        var lookup = world.getRegistryManager();
        var blockEntity = BlockEntity.createFromNbt(
                placementPos.pos(),
                placementPos.blockState(),
                nbt,
                lookup
        );

        if (!(blockEntity instanceof Inventory schematicInventory)) {
            return null;
        }

        var inventory = new SimpleInventory(schematicInventory.size());

        for (int i = 0; i < inventory.size(); i++) {
            var stack = schematicInventory.getStack(i);
            inventory.setStack(i, stack);
        }

        return inventory;
    }
}
