package cn.envision.xihe.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 全部配置项都注册到 malilib，界面由 {@link cn.envision.xihe.client.gui.GuiConfigs} 提供。
 * <p>
 * 存档读写交给 malilib 的 {@link ConfigUtils}，开关变更会立即落盘。
 */
public class HighlightConfig implements IConfigHandler {
    public static final String MOD_ID = "tweakerxihe";
    private static final String CONFIG_FILE_NAME = MOD_ID + ".json";
    private static final String GENERIC_KEY = "generic";
    private static final String COLORS_KEY = "colors";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Item DEFAULT_TRIGGER_ITEM = Items.SHULKER_BOX;

    private static final HighlightConfig INSTANCE = new HighlightConfig();

    // 触发物品要按帧查询，这里缓存解析结果，配置改了自动失效
    private static volatile Item cachedTriggerItem = DEFAULT_TRIGGER_ITEM;
    private static volatile String cachedTriggerItemId = "";

    public static class Generic {
        public static final ConfigBoolean ENABLED = new ConfigBoolean("enabled", false,
                "自动高亮总开关");
        public static final ConfigString TRIGGER_ITEM = new ConfigString("triggerItem", "minecraft:shulker_box",
                "手持该物品时显示线框，填写物品 ID");
        public static final ConfigBoolean SEE_THROUGH = new ConfigBoolean("seeThrough", false,
                "线框穿透方块显示");
        public static final ConfigBoolean STRICT_NBT = new ConfigBoolean("strictNbt", false,
                "严格校验 NBT：物品组件不一致时不计入缺失，也不互相顶替数量");
        public static final ConfigBoolean NBT_MISMATCH_COLOR = new ConfigBoolean("nbtMismatchColor", true,
                "严格校验时，物品相同但 NBT 不同的槽位换一种背景色提示");
        public static final ConfigBoolean READ_NESTED_CONTAINERS = new ConfigBoolean("readNestedContainers", true,
                "嵌套读取容器内容：箱子或背包里的潜影盒、收纳袋也算作可用物品（最多两层）");
        public static final ConfigBoolean COUNT_ONLY_FIRST_MATCH = new ConfigBoolean("countOnlyFirstMatch", true,
                "同一种物品只标注第一个匹配到的格子（背景与数量都不画），其余格子保持原样；关闭则每个格子都标");
        public static final ConfigBoolean SPREAD_COUNT_BY_STACK_SIZE = new ConfigBoolean("spreadCountByStackSize", false,
                "按物品最大堆叠数逐个格子标注到够为止；关闭则只在首个匹配格子标出全部需要数量");
        public static final ConfigBoolean SPREAD_COUNT_FOR_NON_STACKABLE = new ConfigBoolean("spreadCountForNonStackable", false,
                "不可堆叠物品是否也逐个格子标注；默认关闭，由一个格子标出全部需要数量");
        public static final ConfigBoolean HINT_IN_PLAYER_INVENTORY = new ConfigBoolean("hintInPlayerInventory", false,
                "在玩家背包界面也显示投影容器的缺货提示");
        public static final ConfigInteger HINT_TEXT_OFFSET_X = new ConfigInteger("hintTextOffsetX", 2, 0, 16,
                "缺货数量文字相对槽位左上角向右的像素");
        public static final ConfigInteger HINT_TEXT_OFFSET_Y = new ConfigInteger("hintTextOffsetY", 2, 0, 16,
                "缺货数量文字相对槽位左上角向下的像素");
        public static final ConfigDouble HINT_TEXT_SCALE = new ConfigDouble("hintTextScale", 1.0D, 0.25D, 2.0D,
                "缺货数量文字的字号倍数");
        public static final ConfigHotkey OPEN_CONFIG_GUI = new ConfigHotkey("openConfigGui", "X,C",
                "打开配置界面");

        public static final List<IConfigBase> OPTIONS = List.of(
                ENABLED,
                TRIGGER_ITEM,
                SEE_THROUGH,
                STRICT_NBT,
                NBT_MISMATCH_COLOR,
                READ_NESTED_CONTAINERS,
                COUNT_ONLY_FIRST_MATCH,
                SPREAD_COUNT_BY_STACK_SIZE,
                SPREAD_COUNT_FOR_NON_STACKABLE,
                HINT_IN_PLAYER_INVENTORY,
                HINT_TEXT_OFFSET_X,
                HINT_TEXT_OFFSET_Y,
                HINT_TEXT_SCALE,
                OPEN_CONFIG_GUI
        );
    }

    /** 颜色统一用 #AARRGGBB，alpha 也由界面上的取色器调整。 */
    public static class Colors {
        public static final ConfigColor PROJECTION_CONTAINER = new ConfigColor("projectionContainerColor", "#FF00FF00",
                "投影容器线框");
        public static final ConfigColor STORAGE_CONTAINER = new ConfigColor("storageContainerColor", "#FF0000FF",
                "仓储容器线框");
        public static final ConfigColor MATCHING_CONTAINER = new ConfigColor("matchingContainerColor", "#FFFFFF00",
                "有可用物品的仓储箱线框");
        public static final ConfigColor SLOT_PUT = new ConfigColor("slotPutColor", "#8040FF40",
                "背包里该放进投影容器的槽位背景");
        public static final ConfigColor SLOT_TAKE = new ConfigColor("slotTakeColor", "#6000FF00",
                "仓储容器里该取出的槽位背景");
        public static final ConfigColor SLOT_NBT_MISMATCH = new ConfigColor("slotNbtMismatchColor", "#60FF8000",
                "严格校验时物品相同但 NBT 不同的槽位背景");
        public static final ConfigColor SLOT_COUNT_TEXT = new ConfigColor("slotCountTextColor", "#FFFFFFFF",
                "缺货数量文字");

        public static final List<IConfigBase> OPTIONS = List.of(
                PROJECTION_CONTAINER,
                STORAGE_CONTAINER,
                MATCHING_CONTAINER,
                SLOT_PUT,
                SLOT_TAKE,
                SLOT_NBT_MISMATCH,
                SLOT_COUNT_TEXT
        );
    }

    public static HighlightConfig getInstance() {
        return INSTANCE;
    }

    // ---------- 供其它模块使用的读取入口 ----------

    public static boolean isEnabled() {
        return Generic.ENABLED.getBooleanValue();
    }

    public static void setEnabled(boolean value) {
        Generic.ENABLED.setBooleanValue(value);
        saveToFile();
    }

    public static boolean isSeeThrough() {
        return Generic.SEE_THROUGH.getBooleanValue();
    }

    public static boolean isStrictNbt() {
        return Generic.STRICT_NBT.getBooleanValue();
    }

    public static boolean isNbtMismatchColor() {
        return Generic.NBT_MISMATCH_COLOR.getBooleanValue();
    }

    public static boolean isCountOnlyFirstMatch() {
        return Generic.COUNT_ONLY_FIRST_MATCH.getBooleanValue();
    }

    public static boolean isReadNestedContainers() {
        return Generic.READ_NESTED_CONTAINERS.getBooleanValue();
    }

    public static boolean isSpreadCountByStackSize() {
        return Generic.SPREAD_COUNT_BY_STACK_SIZE.getBooleanValue();
    }

    public static boolean isSpreadCountForNonStackable() {
        return Generic.SPREAD_COUNT_FOR_NON_STACKABLE.getBooleanValue();
    }

    public static boolean isHintInPlayerInventory() {
        return Generic.HINT_IN_PLAYER_INVENTORY.getBooleanValue();
    }

    public static int getHintTextOffsetX() {
        return Generic.HINT_TEXT_OFFSET_X.getIntegerValue();
    }

    public static int getHintTextOffsetY() {
        return Generic.HINT_TEXT_OFFSET_Y.getIntegerValue();
    }

    public static float getHintTextScale() {
        return (float) Generic.HINT_TEXT_SCALE.getDoubleValue();
    }

    public static Item getTriggerItem() {
        String itemId = Generic.TRIGGER_ITEM.getStringValue();
        if (!itemId.equals(cachedTriggerItemId)) {
            cachedTriggerItem = parseTriggerItem(itemId);
            cachedTriggerItemId = itemId;
        }
        return cachedTriggerItem;
    }

    public static Color4f getProjectionContainerColor() {
        return Colors.PROJECTION_CONTAINER.getColor();
    }

    public static Color4f getStorageContainerColor() {
        return Colors.STORAGE_CONTAINER.getColor();
    }

    public static Color4f getMatchingContainerColor() {
        return Colors.MATCHING_CONTAINER.getColor();
    }

    public static int getSlotPutColor() {
        return Colors.SLOT_PUT.getIntegerValue();
    }

    public static int getSlotTakeColor() {
        return Colors.SLOT_TAKE.getIntegerValue();
    }

    public static int getSlotNbtMismatchColor() {
        return Colors.SLOT_NBT_MISMATCH.getIntegerValue();
    }

    public static int getSlotCountTextColor() {
        return Colors.SLOT_COUNT_TEXT.getIntegerValue();
    }

    // ---------- 存档 ----------

    @Override
    public void load() {
        loadFromFile();
    }

    @Override
    public void save() {
        saveToFile();
    }

    public static void loadFromFile() {
        Path file = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);

        try {
            if (!Files.exists(file)) {
                return;
            }

            JsonElement element = JsonUtils.parseJsonFileAsPath(file);
            if (element != null && element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                ConfigUtils.readConfigBase(obj, GENERIC_KEY, Generic.OPTIONS);
                ConfigUtils.readConfigBase(obj, COLORS_KEY, Colors.OPTIONS);
            } else {
                LOGGER.warn("配置读取失败，使用默认值: {}", file.toAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.warn("配置读取异常，使用默认值", e);
        }
    }

    public static void saveToFile() {
        Path configDir = FileUtils.getConfigDirectoryAsPath();
        if (!Files.exists(configDir)) {
            LOGGER.warn("配置目录不存在，跳过写入: {}", configDir.toAbsolutePath());
            return;
        }

        try {
            JsonObject obj = new JsonObject();
            ConfigUtils.writeConfigBase(obj, GENERIC_KEY, Generic.OPTIONS);
            ConfigUtils.writeConfigBase(obj, COLORS_KEY, Colors.OPTIONS);
            JsonUtils.writeJsonToFileAsPath(obj, configDir.resolve(CONFIG_FILE_NAME));
        } catch (Exception e) {
            LOGGER.warn("配置写入失败", e);
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

        return Registries.ITEM.get(identifier);
    }
}
