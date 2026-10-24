package com.autocarpet.carto;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.item.MapItem;

import java.util.List;

/**
 * 制图师材料白名单 (移植自 Lotus as_0)。
 */
public final class CartoItems {
    private CartoItems() {
    }

    private static final Minecraft mc = Minecraft.getInstance();

    /** 16 色地毯 (ColorCollection 顺序: white…black, 与 CarpetPalette.RGB 一致) */
    public static final List<Item> CARPETS = Items.CARPET.asList();

    /** 潜影盒容器 (含 16 色) */
    public static boolean isShulkerBox(Item item) {
        return item == Items.SHULKER_BOX || Items.DYED_SHULKER_BOX.asList().contains(item);
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
