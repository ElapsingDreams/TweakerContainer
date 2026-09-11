package cn.envision.xihe.client;

import net.minecraft.client.render.RenderPhase;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

public class RenderPhaseCache {
    // 线宽值到 LineWidth 实例的缓存，避免重复创建
    private static final Map<Double, RenderPhase.LineWidth> LINE_WIDTH_CACHE = new HashMap<>();

    // 获取线宽实例（复用已有实例）
    public static RenderPhase.LineWidth getLineWidthPhase(double lineWidth) {
        // 从缓存获取，不存在则创建并缓存
        return LINE_WIDTH_CACHE.computeIfAbsent(lineWidth,
                key -> new RenderPhase.LineWidth(OptionalDouble.of(key)));
    }

    // 手动清理缓存（可选，如在世界卸载时调用）
    public static void clearCache() {
        LINE_WIDTH_CACHE.clear();
    }
}