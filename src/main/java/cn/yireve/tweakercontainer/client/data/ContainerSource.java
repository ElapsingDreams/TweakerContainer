package cn.yireve.tweakercontainer.client.data;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.List;

/**
 * 容器内容从哪来。
 * <p>
 * AUTO：本地世界（单人 / 局域网主机）直接读内置服务端；联机先试服务端查询，拿不到再退回开界面抓取。
 * INTEGRATED：只读内置服务端，联机时自动退回开界面。
 * SERVUX：只走服务端 NBT 查询（服务端装了 Servux 才放行非 OP 的查询）。
 * SCREEN：只用开界面抓到的内容，最保守。
 */
public enum ContainerSource implements IConfigOptionListEntry {
    AUTO("auto", "tweakercontainer.config.containerSource.auto"),
    INTEGRATED("integrated", "tweakercontainer.config.containerSource.integrated"),
    SERVUX("servux", "tweakercontainer.config.containerSource.servux"),
    SCREEN("screen", "tweakercontainer.config.containerSource.screen");

    public static final List<ContainerSource> VALUES = List.of(values());

    private final String value;
    private final String translationKey;

    ContainerSource(String value, String translationKey) {
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

    public static ContainerSource byValue(String value) {
        for (ContainerSource source : VALUES) {
            if (source.value.equalsIgnoreCase(value)) {
                return source;
            }
        }
        return AUTO;
    }
}
