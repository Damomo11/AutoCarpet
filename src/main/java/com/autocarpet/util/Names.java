package com.autocarpet.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class Names {
    public static String get(Item item) {
        return item.getName(new ItemStack(item)).getString();
    }

    public static String get(ItemStack stack) {
        return stack.getHoverName().getString();
    }
}
