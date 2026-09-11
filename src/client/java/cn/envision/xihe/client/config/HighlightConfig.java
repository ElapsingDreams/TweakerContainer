package cn.envision.xihe.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 高亮功能的开关与触发物品配置。
 */
public final class HighlightConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/xihe_highlight.json");
    private static final Item DEFAULT_TRIGGER_ITEM = Items.SHULKER_BOX;

    private static volatile boolean enabled = false;
    private static Item triggerItem = DEFAULT_TRIGGER_ITEM;

    private HighlightConfig() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static Item getTriggerItem() {
        return triggerItem;
    }

    public static void setTriggerItem(Item item) {
        if (item == null) {
            return;
        }
        triggerItem = item;
        save();
    }

    public static void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            triggerItem = parseTriggerItem(data != null ? data.triggerItem : null);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("读取高亮配置失败，回退到默认触发物品", e);
            triggerItem = DEFAULT_TRIGGER_ITEM;
        }
    }

    public static void save() {
        ConfigData data = new ConfigData();
        data.triggerItem = Registries.ITEM.getId(triggerItem).toString();

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.warn("写入高亮配置失败", e);
        }
    }

    private static Item parseTriggerItem(String itemId) {
        if (itemId == null) {
            return DEFAULT_TRIGGER_ITEM;
        }

        Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null || !Registries.ITEM.containsId(identifier)) {
            return DEFAULT_TRIGGER_ITEM;
        }

        Item item = Registries.ITEM.get(identifier);
        return item != null ? item : DEFAULT_TRIGGER_ITEM;
    }

    private static class ConfigData {
        // 缺省为 null，读取时按默认触发物品处理
        private String triggerItem;
    }
}
