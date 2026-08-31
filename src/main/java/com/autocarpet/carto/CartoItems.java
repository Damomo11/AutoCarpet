package com.autocarpet.carto;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.item.MapItem;

import java.util.Arrays;
import java.util.List;

/**
 * 制图师材料白名单 (移植自 Lotus as_0)。
 */
public final class CartoItems {
    private CartoItems() {
    }

    private static final Minecraft mc = Minecraft.getInstance();

    /** 16 色地毯 */
    public static final List<Item> CARPETS = Arrays.asList(
            Items.WHITE_CARPET, Items.ORANGE_CARPET, Items.MAGENTA_CARPET, Items.LIGHT_BLUE_CARPET,
            Items.YELLOW_CARPET, Items.LIME_CARPET, Items.PINK_CARPET, Items.GRAY_CARPET,
            Items.LIGHT_GRAY_CARPET, Items.CYAN_CARPET, Items.PURPLE_CARPET, Items.BLUE_CARPET,
            Items.BROWN_CARPET, Items.GREEN_CARPET, Items.RED_CARPET, Items.BLACK_CARPET);

    /** 潜影盒容器 (含 16 色) */
    public static boolean isShulkerBox(Item item) {
        return item == Items.SHULKER_BOX || item == Items.WHITE_SHULKER_BOX || item == Items.ORANGE_SHULKER_BOX
                || item == Items.MAGENTA_SHULKER_BOX || item == Items.LIGHT_BLUE_SHULKER_BOX
                || item == Items.YELLOW_SHULKER_BOX || item == Items.LIME_SHULKER_BOX
                || item == Items.PINK_SHULKER_BOX || item == Items.GRAY_SHULKER_BOX
                || item == Items.LIGHT_GRAY_SHULKER_BOX || item == Items.CYAN_SHULKER_BOX
                || item == Items.PURPLE_SHULKER_BOX || item == Items.BLUE_SHULKER_BOX
                || item == Items.BROWN_SHULKER_BOX || item == Items.GREEN_SHULKER_BOX
                || item == Items.RED_SHULKER_BOX || item == Items.BLACK_SHULKER_BOX;
    }

    /** 是否地毯 */
    public static boolean isCarpet(Item item) {
        return CARPETS.contains(item);
    }

    /** 是否已锁定/未锁定的成品地图 */
    public static boolean isFilledMapWithLockState(ItemStack stack, boolean locked) {
        if (stack.getItem() != Items.FILLED_MAP) return false;
        MapItemSavedData data = MapItem.getSavedData(stack, mc.level);
        return data != null && data.locked == locked;
    }
}
