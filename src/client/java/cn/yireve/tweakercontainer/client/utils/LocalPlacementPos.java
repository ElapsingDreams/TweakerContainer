package cn.yireve.tweakercontainer.client.utils;


import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.SchematicUtils;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents world position inside a schematic placement.
 * <p>
 * 只做「世界坐标 → 蓝图坐标」这一个方向：Litematica 的放置分块（{@code PlacementPart}）本来就带世界坐标盒子，
 * 由它反推蓝图坐标是可靠的。
 * <p>
 * 不要在这里加「蓝图坐标 → 世界坐标」的实现：自己拿
 * {@code PositionUtils.getTransformedPlacementPosition} 拼一个出来是不准的——那个方法<strong>只做旋转/镜像，不含任何平移</strong>
 * （字节码核对过），再拿 {@code placement.getOrigin()} 去加，只在"子区域正好落在放置原点"时才对得上，
 * 多子区域/挪过子区域的放置会整体偏掉。角点框选最初就是这么漏掉漏斗的，相关的实现已经删掉，
 * 需要正向映射时请走 Litematica 自己的盒子接口（{@code getSubRegionBox}/{@code PlacementPart}）。
 *
 * @param pos       Block position in subregion's block state array.
 * @param region    Name of the targeted subregion
 * @param placement Targeted schematic placement instance
 */
public record LocalPlacementPos(BlockPos pos, String region, SchematicPlacement placement) {
    public static Optional<LocalPlacementPos> get(BlockPos worldPos) {
        List<SchematicPlacementManager.PlacementPart> parts = DataManager
                .getSchematicPlacementManager()
                .getAllPlacementsTouchingChunk(worldPos);

        for (SchematicPlacementManager.PlacementPart part : parts) {
            if (!part.getBox().containsPos(worldPos))
                continue;

            SchematicPlacement placement = part.placement;
            String region = part.subRegionName;
            LitematicaBlockStateContainer container = placement.getSchematic().getSubRegionContainer(region);
            BlockPos schematicPos = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    worldPos,
                    placement.getSchematic(),
                    region,
                    placement,
                    Objects.requireNonNull(
                            placement.getRelativeSubRegionPlacement(region),
                            "Somehow subregion is null"),
                    container);
            return Optional.of(new LocalPlacementPos(schematicPos, region, placement));
        }
        return Optional.empty();
    }

    public BlockState blockState() {
        return this.placement.getSchematic().getSubRegionContainer(this.region).get(this.pos.getX(), this.pos.getY(), this.pos.getZ());
    }
}
