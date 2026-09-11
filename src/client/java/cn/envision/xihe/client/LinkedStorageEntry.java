package cn.envision.xihe.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Represents contents of an existing container and one according to a placement at the same position.
 */
public class LinkedStorageEntry {
    // 差异优先级：错物品 > 数量不符 > 多出 > 缺失
    private static final int DIFF_MISSING = 1;
    private static final int DIFF_EXTRA = 2;
    private static final int DIFF_COUNT = 3;
    private static final int DIFF_WRONG_ITEM = 4;

    // 用 lambda 而非方法引用：Configs 的取值推迟到实际比对时，避免该类初始化时提前触碰 Litematica 配置
    private static final Supplier<Color4f> MISSING_COLOR = () -> Configs.Colors.SCHEMATIC_OVERLAY_COLOR_MISSING.getColor();
    private static final Supplier<Color4f> WRONG_COLOR = () -> Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_BLOCK.getColor();
    private static final Supplier<Color4f> MISMATCHED_COLOR = () -> Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_STATE.getColor();
    private static final Supplier<Color4f> EXTRA_COLOR = () -> Configs.Colors.SCHEMATIC_OVERLAY_COLOR_EXTRA.getColor();
    public final BlockPos pos;
    @Nullable
    private Inventory worldInventory;
    @Nullable
    private Inventory placementInventory;

    public LinkedStorageEntry(BlockPos pos, @Nullable Inventory worldInventory, @Nullable Inventory placementInventory) {
        this.pos = pos;
        this.worldInventory = worldInventory;
        this.placementInventory = placementInventory;
    }

    public Optional<Inventory> getWorldInventory() {
        return Optional.ofNullable(this.worldInventory);
    }

    public void setWorldInventory(@Nullable Inventory inventory) {
        this.worldInventory = inventory;
    }

    public Optional<Inventory> getPlacementInventory() {
        return Optional.ofNullable(this.placementInventory);
    }

    public void setPlacementInventory(@Nullable Inventory inventory) {
        this.placementInventory = inventory;
    }

    public Optional<Color4f> validate() {
        if (this.worldInventory == null || this.placementInventory == null)
            return Optional.empty();

        int type = 0;
        // 两侧容量可能不一致（例如单箱对双箱），只比较重叠部分
        int size = Math.min(this.worldInventory.size(), this.placementInventory.size());
        for (int i = 0; i < size; i++) {
            ItemStack world = this.worldInventory.getStack(i);
            ItemStack schem = this.placementInventory.getStack(i);

            if (world.isEmpty() && !schem.isEmpty())
                type = Math.max(type, DIFF_MISSING);
            else if (!world.isEmpty() && schem.isEmpty())
                type = Math.max(type, DIFF_EXTRA);
            else if (world.getItem() != schem.getItem())
                type = Math.max(type, DIFF_WRONG_ITEM);
            else if (world.getCount() != schem.getCount())
                type = Math.max(type, DIFF_COUNT);
        }

        return switch (type) {
            case DIFF_MISSING -> Optional.of(MISSING_COLOR.get());
            case DIFF_EXTRA -> Optional.of(EXTRA_COLOR.get());
            case DIFF_COUNT -> Optional.of(MISMATCHED_COLOR.get());
            case DIFF_WRONG_ITEM -> Optional.of(WRONG_COLOR.get());
            default -> Optional.empty();
        };
    }
}
