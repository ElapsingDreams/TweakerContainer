package cn.envision.xihe.client;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.DefaultPosArgument;
import net.minecraft.command.argument.PosArgument;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

public class BlockPosArgumentType implements ArgumentType<PosArgument> {
    private static final Collection<String> EXAMPLES = Arrays.asList("0 0 0", "~ ~ ~", "^ ^ ^", "^1 ^ ^-5", "~0.5 ~1 ~-5");

    public static BlockPosArgumentType blockPos() {
        return new BlockPosArgumentType();
    }

    public static BlockPos getBlockPos(CommandContext<ServerCommandSource> context, String name) {
        return context.getArgument(name, PosArgument.class).toAbsoluteBlockPos(context.getSource());
    }

    @Override
    public PosArgument parse(StringReader reader) throws CommandSyntaxException {
        return DefaultPosArgument.parse(reader);
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        if (!(context.getSource() instanceof ServerCommandSource)) {
            return Suggestions.empty();
        }

        // 使用新的建议方法
        return CommandSource.suggestPositions(
                builder.getInput(),
                Collections.emptyList(),
                builder,
                s -> true
        );
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}