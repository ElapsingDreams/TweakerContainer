package cn.yireve.tweakercontainer.client;

import cn.yireve.tweakercontainer.client.config.HighlightConfig;
import cn.yireve.tweakercontainer.client.data.ContainerDataManager;
import cn.yireve.tweakercontainer.client.gui.GuiConfigs;
import cn.yireve.tweakercontainer.client.handler.InteractionHandler;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.List;

/**
 * 入口：配置与开关全部走 malilib 的配置界面与热键。
 */
@Environment(EnvType.CLIENT)
public class TweakerContainerClient implements ClientModInitializer {

    // 与 malilib 自身的 CallbackOpenConfigGui 一致：不判断 KeyAction，触发即开
    private static final IHotkeyCallback OPEN_CONFIG_GUI_CALLBACK = (action, keybind) -> {
        GuiBase.openGui(new GuiConfigs());
        return true;
    };

    @Override
    public void onInitializeClient() {
        // 配置交给 malilib 统一读写与显示，界面见 GuiConfigs
        ConfigManager.getInstance().registerConfigHandler(HighlightConfig.MOD_ID, HighlightConfig.getInstance());
        HighlightConfig.loadFromFile();

        // 主动把界面注册进 malilib：只在 initGui 里惰性自动注册的话，
        // 界面打不开就永远进不了它的模组列表，等于没有入口
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(HighlightConfig.MOD_ID, "TweakerContainer", GuiConfigs::new));

        BlockHighlighterRender.setup();
        InteractionHandler.setup();
        ContainerDataManager.setup();

        setupHotkeys();
    }

    /**
     * 热键通过 {@link IKeybindProvider} 注册：malilib 重建按键映射（初始化、界面里改键）时会回调它，
     * 只在初始化时 addKeybindToMap 会在下一次重建时丢失，热键就失效了。
     */
    private static void setupHotkeys() {
        IKeybindManager keybindManager = InputEventHandler.getKeybindManager();
        keybindManager.registerKeybindProvider(new IKeybindProvider() {
            @Override
            public void addKeysToMap(IKeybindManager manager) {
                IKeybind openConfigGui = HighlightConfig.Generic.OPEN_CONFIG_GUI.getKeybind();
                // 每次重建映射都重新挂回调，避免被改键流程冲掉
                openConfigGui.setCallback(OPEN_CONFIG_GUI_CALLBACK);
                manager.addKeybindToMap(openConfigGui);
            }

            @Override
            public void addHotkeys(IKeybindManager manager) {
                manager.addHotkeysForCategory("TweakerContainer", "tweakercontainer.hotkeys.category.generic",
                        List.of(HighlightConfig.Generic.OPEN_CONFIG_GUI));
            }
        });

        keybindManager.updateUsedKeys();
    }
}
