package cn.envision.xihe.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import com.mojang.brigadier.arguments.StringArgumentType;
import cn.envision.xihe.client.config.HighlightConfig;
import cn.envision.xihe.client.handler.InteractionHandler;

import java.util.HashSet;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Environment(EnvType.CLIENT)
public class XiheClient implements ClientModInitializer {
    public static final Set<BlockPos> HIGHLIGHTED_BLOCKS = new HashSet<>();

    @Override
    public void onInitializeClient() {
        HighlightConfig.load();
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
                                                    context.getSource().sendError(Text.literal("该坐标超出世界范围！"));
                                                    return 0;
                                                }

                                                HIGHLIGHTED_BLOCKS.add(pos.toImmutable());
                                                context.getSource().sendFeedback(Text.literal("已在位置 " + pos.toShortString() + " 添加高亮"));
                                                return 1;
                                            }))))
            );

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
}