package cn.envision.xihe.client.gui;

import cn.envision.xihe.client.HighlightState;
import cn.envision.xihe.client.config.HighlightConfig;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;

import java.util.ArrayList;
import java.util.List;

/**
 * malilib 提供的配置界面，配置项与顺序取自 {@link HighlightConfig}。
 */
public class GuiConfigs extends GuiConfigsBase {

    public GuiConfigs() {
        super(10, 50, HighlightConfig.MOD_ID, null, "TweakerXihe Config");
    }

    @Override
    public void initGui() {
        super.initGui();

        // 清除按钮：清空全部高亮状态
        ButtonGeneric clearButton = new ButtonGeneric(10, 24, 100, 20, "清除全部高亮");
        this.addButton(clearButton, (button, mouseButton) -> HighlightState.get().clearAll());
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> wrappers = new ArrayList<>();
        wrappers.addAll(ConfigOptionWrapper.createFor(HighlightConfig.Generic.OPTIONS));
        wrappers.add(new ConfigOptionWrapper("颜色（格式 #AARRGGBB，含透明度）"));
        wrappers.addAll(ConfigOptionWrapper.createFor(HighlightConfig.Colors.OPTIONS));
        return wrappers;
    }

    @Override
    protected void onSettingsChanged() {
        ConfigManager.getInstance().onConfigsChanged(HighlightConfig.MOD_ID);
    }
}
