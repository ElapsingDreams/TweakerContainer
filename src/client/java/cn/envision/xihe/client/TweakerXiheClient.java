package cn.envision.xihe.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.data.ResourceLocation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import com.mojang.brigadier.arguments.StringArgumentType;
import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.gui.GuiConfigs;
import cn.envision.xihe.client.handler.InteractionHandler;

import static cn.envision.xihe.client.config.HighlightConfig.isEnabled;
import static cn.envision.xihe.client.config.HighlightConfig.setEnabled;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Environment(EnvType.CLIENT)
public class TweakerXiheClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 配置交给 malilib 统一读写与显示，界面见 GuiConfigs
        ConfigManager.getInstance().registerConfigHandler(HighlightConfig.MOD_ID, HighlightConfig.getInstance());
        HighlightConfig.loadFromFile();
        setupOpenConfigHotkey();

        BlockHighlighterRender.setup();
        InteractionHandler.setup();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("highlightblock")
                    .then(argument("x", IntegerArgumentType.integer())
                            .then(argument("y", IntegerArgumentType.integer())
                                    .then(argument("z", IntegerArgumentType.integer())
                                            .executes(context -> {
                                                int x = IntegerArgumentType.getInteger(context, "x");
                                                int y = IntegerArgumentType.getInteger(context, "y");
                                                int z = IntegerArgumentType.getInteger(context, "z");
                                                BlockPos pos = new BlockPos(x, y, z);
                                                ClientWorld world = MinecraftClient.getInstance().world;

                                                if (world != null && !world.isInBuildLimit(pos)) {
                                                    context.getSource().sendError(Text.literal("Out World Range！"));
                                                    return 0;
                                                }

                                                HighlightState.get().addHighlightedBlock(pos);
                                                context.getSource().sendFeedback(Text.literal("Set " + pos.toShortString() + "  Highlight"));
                                                return 1;
                                            }))))
                    .then(literal("clear")
                            .executes(context -> {
                                // 清除所有相关高亮数据
                                HighlightState.get().clearAll();

                                context.getSource().sendFeedback(Text.literal("Clear All Highlight Block!"));
                                return 1;
                            }))
                    .then(literal("config")
                            .executes(context -> {
                                GuiBase.openGui(new GuiConfigs());
                                return 1;
                            }))
                    .then(literal("start")
                            .executes(context -> {
                                if (isEnabled()) {
                                    context.getSource().sendError(Text.literal("Auto Highlight Block Started!"));
                                    return 0;
                                }
                                setEnabled(true);
                                context.getSource().sendFeedback(Text.literal("Starting Auto Highlight Block."));
                                return 1;
                            }))
                    .then(literal("stop")
                            .executes(context -> {
                                if (!isEnabled()) {
                                    context.getSource().sendError(Text.literal("Auto Highlight Block Stopped!"));
                                    return 0;
                                }
                                setEnabled(false);
                                context.getSource().sendFeedback(Text.literal("Stopping Auto Highlight Block."));
                                return 1;
                            }))
                    .then(literal("depth")
                            .executes(context -> {
                                boolean seeThrough = !HighlightConfig.isSeeThrough();
                                HighlightConfig.setSeeThrough(seeThrough);

                                context.getSource().sendFeedback(Text.literal(seeThrough
                                        ? "Highlight Through Walls: ON"
                                        : "Highlight Through Walls: OFF"));
                                return 1;
                            })));

            dispatcher.register(literal("highlightblockbyitem")
                    .then(argument("item", StringArgumentType.greedyString())
                            .executes(context -> {
                                String itemId = StringArgumentType.getString(context, "item");

                                try {
                                    Item item = Registries.ITEM.get(new ResourceLocation(itemId).getId());
                                    HighlightConfig.setTriggerItem(item);
                                    context.getSource().sendFeedback(Text.literal("Set highlight trigger item to " + itemId));
                                    return 1;
                                } catch (Exception e) {
                                    context.getSource().sendError(Text.literal("Invalid item format: " + itemId + ". Use format like 'minecraft:stone'"));
                                    return 0;
                                }
                            }))
            );
        });
    }

    // 用 malilib 的热键机制打开配置界面
    private static void setupOpenConfigHotkey() {
        HighlightConfig.Generic.OPEN_CONFIG_GUI.getKeybind().setCallback((action, keybind) -> {
            if (action == KeyAction.PRESS) {
                GuiBase.openGui(new GuiConfigs());
                return true;
            }
            return false;
        });

        InputEventHandler.getKeybindManager().addKeybindToMap(HighlightConfig.Generic.OPEN_CONFIG_GUI.getKeybind());
        InputEventHandler.getKeybindManager().updateUsedKeys();
    }
}
