package cn.envision.xihe.client;

import cn.envision.xihe.client.config.HighlightConfig;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 高亮方块的线框渲染与事件注册，状态与仓储匹配逻辑见 {@link HighlightState}。
 * <p>
 * 绘制交给 malilib（Litematica 的底层库）：{@link RenderUtils#renderBlockOutline} 用的是
 * Litematica 描边同一套 pipeline（{@code DEBUG_LINES_MASA_SIMPLE_*}），透视与线宽都由它处理，
 * 本类只负责颜色与距离裁剪。
 */
public final class BlockHighlighterRender {
    // 颜色常量（ARGB格式）
    private static final int COLOR_PROJECTION = 0xFF00FF00; // 绿色
    private static final int COLOR_STORAGE = 0xFF0000FF;    // 蓝色
    private static final int COLOR_MATCHING = 0xFFFFFF00;   // 黄色
    private static final int COLOR_MANUAL = 0xFFFF00FF;     // 品红（/highlightblock 手动标记）

    // 线框相对方块表面的外扩量（格），避免与方块表面 z-fighting，同 Litematica 的 expand 参数
    private static final float EXPAND = 0.005F;  //0.002F
    // 线宽（像素），同 Litematica 的 renderBlockOutline 参数
    private static final float LINE_WIDTH = 1.0F;

    // 超出该距离（方块）的线框不再绘制
    private static final double MAX_RENDER_DISTANCE_SQ = 64.0D * 64.0D;

    private BlockHighlighterRender() {
    }

    public static void setup() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BlockHighlighterRender::onRender);
        ClientPlayConnectionEvents.DISCONNECT.register(BlockHighlighterRender::onDisconnect);
    }

    private static void onDisconnect(ClientPlayNetworkHandler clientPlayNetworkHandler, MinecraftClient minecraftClient) {
        HighlightConfig.setEnabled(false);
        HighlightState.get().clearAll();
    }

    // 检查是否手持触发物品
    public static boolean isHoldingTriggerItem() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }
        return client.player.getMainHandStack().getItem() == HighlightConfig.getTriggerItem()
                || client.player.getOffHandStack().getItem() == HighlightConfig.getTriggerItem();
    }

    // 世界渲染回调
    private static void onRender(WorldRenderContext context) {
        if (!HighlightConfig.isEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        HighlightState state = HighlightState.get();
        state.ensureUpToDate();

        boolean isHoldingTrigger = isHoldingTriggerItem();
        Vec3d cameraPos = context.camera().getPos();
        boolean throughWalls = HighlightConfig.isSeeThrough();

        for (BlockPos highlightPos : state.getHighlightedBlocks()) {
            renderOutline(cameraPos, highlightPos, COLOR_MANUAL, throughWalls);
        }

        if (isHoldingTrigger) {
            for (BlockPos storagePos : state.getStorageContainers()) {
                renderOutline(cameraPos, storagePos, COLOR_STORAGE, throughWalls);
            }
            BlockPos projectionContainer = state.getCurrentProjectionContainer();
            if (projectionContainer != null) {
                renderOutline(cameraPos, projectionContainer, COLOR_PROJECTION, throughWalls);
            }
        } else {
            for (BlockPos matchingPos : state.getMatchingStorageContainers()) {
                renderOutline(cameraPos, matchingPos, COLOR_MATCHING, throughWalls);
            }
        }
    }

    private static void renderOutline(Vec3d cameraPos, BlockPos pos, int color, boolean throughWalls) {
        if (!isWithinRenderDistance(cameraPos, pos)) {
            return;
        }
        RenderUtils.renderBlockOutline(pos, EXPAND, LINE_WIDTH, toColor4f(color), throughWalls);
    }

    private static boolean isWithinRenderDistance(Vec3d cameraPos, BlockPos pos) {
        return cameraPos.squaredDistanceTo(Vec3d.ofCenter(pos)) <= MAX_RENDER_DISTANCE_SQ;
    }

    private static Color4f toColor4f(int color) {
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = ((color >> 24) & 0xFF) / 255.0F;
        return new Color4f(r, g, b, a);
    }
}
