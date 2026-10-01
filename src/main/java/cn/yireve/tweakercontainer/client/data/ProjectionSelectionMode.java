package cn.yireve.tweakercontainer.client.data;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.List;

/**
 * 投影容器的选取方式。
 * <p>
 * SINGLE：右键一个就替换掉之前的（老行为）。
 * MULTI：右键逐个加入，再点一次取消，可以同时选多个投影容器。
 * CORNER：手持触发物品左键点选框一角、右键点另一角，把框内"蓝图里也是容器"的位置整批加进来。
 */
public enum ProjectionSelectionMode implements IConfigOptionListEntry {
    SINGLE("single", "tweakercontainer.config.projectionSelectionMode.single"),
    MULTI("multi", "tweakercontainer.config.projectionSelectionMode.multi"),
    CORNER("corner", "tweakercontainer.config.projectionSelectionMode.corner");

    public static final List<ProjectionSelectionMode> VALUES = List.of(values());

    private final String value;
    private final String translationKey;

    ProjectionSelectionMode(String value, String translationKey) {
        this.value = value;
        this.translationKey = translationKey;
    }

    @Override
    public String getStringValue() {
        return this.value;
    }

    /** 下拉框直接显示这个名字，所以这里自己先把语言键翻好。 */
    @Override
    public String getDisplayName() {
        return StringUtils.translate(this.translationKey);
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        int size = VALUES.size();
        int index = (this.ordinal() + (forward ? 1 : size - 1)) % size;
        return VALUES.get(index);
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        return byValue(value);
    }

    public static ProjectionSelectionMode byValue(String value) {
        for (ProjectionSelectionMode mode : VALUES) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return SINGLE;
    }
}
