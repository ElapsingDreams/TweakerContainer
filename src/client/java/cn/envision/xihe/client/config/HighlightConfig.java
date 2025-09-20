package cn.envision.xihe.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class HighlightConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/xihe_highlight.json");

    private static Item triggerItem = Items.SHULKER_BOX;
    private static boolean SW = false;
    public static void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null && data.triggerItem != null) {
                try {
                    Identifier identifier = Identifier.tryParse(data.triggerItem);
                    if (identifier != null && Registries.ITEM.containsId(identifier)) {
                        Item item = Registries.ITEM.get(identifier);
                        if (item != null) {
                            triggerItem = item;
                        } else {
                            triggerItem = Items.SHULKER_BOX;
                        }
                    } else {
                        triggerItem = Items.SHULKER_BOX;
                    }
                } catch (Exception e) {
                    triggerItem = Items.SHULKER_BOX;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void setSW(boolean newsw){
        SW = newsw;
    }
    public static boolean getSW(){
       return SW;
    }
    public static void save() {
        ConfigData data = new ConfigData();
        data.triggerItem = Registries.ITEM.getId(triggerItem).toString();

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Item getTriggerItem() {
        return triggerItem;
    }

    public static void setTriggerItem(Item item) {
        triggerItem = item;
        save();
    }

    private static class ConfigData {
        String triggerItem = "minecraft:shulker_box";
    }
}