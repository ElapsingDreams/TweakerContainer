package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.gui.GuiConfigs;
import cn.envision.xihe.client.handler.InteractionHandler;
import com.mojang.logging.LogUtils;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.slf4j.Logger;

import java.util.List;

/**
 * 入口：配置、开关与操作全部走 malilib 的配置界面与热键，不再注册命令。
 */
@Environment(EnvType.CLIENT)
public class TweakerXiheClient implements ClientModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();

    // 与 malilib 自身的 CallbackOpenConfigGui 一致：不判断 KeyAction，触发即开
    private static final IHotkeyCallback OPEN_CONFIG_GUI_CALLBACK = (action, keybind) -> {
        LOGGER.info("openConfigGui 热键触发, action={}", action);
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
                new ModInfo(HighlightConfig.MOD_ID, "TweakerXihe", GuiConfigs::new));

        BlockHighlighterRender.setup();
        InteractionHandler.setup();

        setupHotkeys();
    }

    /**
     * 热键通过 {@link IKeybindProvider} 注册：malilib 重建按键映射（初始化、界面里改键）时会回调它，
     * 只在初始化时 addKeybindToMap 会在下一次重建时丢失，热键就失效了。
     */
    private static void setupHotkeys() {
        HighlightConfig.Generic.MARK_TARGET_BLOCK.getKeybind().setCallback((action, keybind) -> {
            markTargetBlock();
            return true;
        });

        IKeybindManager keybindManager = InputEventHandler.getKeybindManager();
        keybindManager.registerKeybindProvider(new IKeybindProvider() {
            @Override
            public void addKeysToMap(IKeybindManager manager) {
                IKeybind openConfigGui = HighlightConfig.Generic.OPEN_CONFIG_GUI.getKeybind();
                // 每次重建映射都重新挂回调，避免被改键流程冲掉
                openConfigGui.setCallback(OPEN_CONFIG_GUI_CALLBACK);

                manager.addKeybindToMap(openConfigGui);
                manager.addKeybindToMap(HighlightConfig.Generic.MARK_TARGET_BLOCK.getKeybind());
            }

            @Override
            public void addHotkeys(IKeybindManager manager) {
                manager.addHotkeysForCategory("TweakerXihe", "tweakerxihe.hotkeys.category.generic",
                        List.of(HighlightConfig.Generic.OPEN_CONFIG_GUI, HighlightConfig.Generic.MARK_TARGET_BLOCK));
            }
        });

        keybindManager.updateUsedKeys();

        LOGGER.info("热键注册完成: openConfigGui='{}', markTargetBlock='{}'",
                HighlightConfig.Generic.OPEN_CONFIG_GUI.getKeybind().getStringValue(),
                HighlightConfig.Generic.MARK_TARGET_BLOCK.getKeybind().getStringValue());
    }

    // 标记准星指向的方块
    private static void markTargetBlock() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.crosshairTarget instanceof BlockHitResult hitResult
                && hitResult.getType() == HitResult.Type.BLOCK) {
            HighlightState.get().addHighlightedBlock(hitResult.getBlockPos());
        }
    }
}
