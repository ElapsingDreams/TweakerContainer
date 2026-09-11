package cn.envision.xihe.client.gui;

import cn.envision.xihe.client.config.HighlightConfig;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.gui.GuiConfigsBase;

import java.util.List;

/**
 * malilib 提供的配置界面，配置项与顺序取自 {@link HighlightConfig.Generic#OPTIONS}。
 */
public class GuiConfigs extends GuiConfigsBase {

    public GuiConfigs() {
        super(10, 50, HighlightConfig.MOD_ID, null, "TweakerXihe Config");
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(HighlightConfig.Generic.OPTIONS);
    }

    @Override
    protected void onSettingsChanged() {
        ConfigManager.getInstance().onConfigsChanged(HighlightConfig.MOD_ID);
    }
}
