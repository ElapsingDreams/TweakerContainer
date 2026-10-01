package cn.yireve.tweakercontainer.client.gui;

import cn.yireve.tweakercontainer.client.HighlightState;
import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * malilib 提供的配置界面，配置项与顺序取自 {@link HighlightConfig}。
 */
public class GuiConfigs extends GuiConfigsBase {

    public GuiConfigs() {
        // 标题同样交给语言文件：GuiConfigsBase 会自己对它做 StringUtils.translate
        super(10, 50, HighlightConfig.MOD_ID, null, "tweakercontainer.gui.title");
    }

    @Override
    public void initGui() {
        super.initGui();

        // 清除按钮：清空全部高亮状态。
        // 注意 ButtonBase 只对悬停提示做翻译，按钮文字得自己翻好再传进去
        ButtonGeneric clearButton = new ButtonGeneric(10, 24, 100, 20,
                StringUtils.translate("tweakercontainer.gui.button.clear_all"));
        this.addButton(clearButton, (button, mouseButton) -> HighlightState.get().clearAll());
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> wrappers = new ArrayList<>();
        wrappers.addAll(ConfigOptionWrapper.createFor(HighlightConfig.Generic.OPTIONS));
        wrappers.addAll(ConfigOptionWrapper.createFor(HighlightConfig.Colors.OPTIONS));
        return wrappers;
    }

    @Override
    protected void onSettingsChanged() {
        ConfigManager.getInstance().onConfigsChanged(HighlightConfig.MOD_ID);
    }
}
