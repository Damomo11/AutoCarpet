package com.autocarpet.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class FindItemResult {
    public final int slot;
    public final ItemStack stack;

    public FindItemResult(int slot, ItemStack stack) {
        this.slot = slot;
        this.stack = stack;
    }

    public boolean found() {
        return this.slot != -1 && !this.stack.isEmpty();
    }

    public boolean isEmpty() {
        return !this.found() || this.stack.isEmpty();
    }

    public int count() {
        return this.found() ? this.stack.getCount() : 0;
    }

    public static FindItemResult search(Inventory inv, java.util.function.Predicate<ItemStack> filter, int from, int to) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return new FindItemResult(-1, ItemStack.EMPTY);
        for (int i = from; i <= to; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && filter.test(stack)) return new FindItemResult(i, stack);
        }
        return new FindItemResult(-1, ItemStack.EMPTY);
    }

    public static FindItemResult inHotbar(Inventory inv, java.util.function.Predicate<ItemStack> filter) {
        return search(inv, filter, Inventory.getSelectionSize(), Inventory.getSelectionSize() + 8);
    }

    public static FindItemResult inMain(Inventory inv, java.util.function.Predicate<ItemStack> filter) {
        return search(inv, filter, Inventory.getSelectionSize(), Inventory.getSelectionSize() + 35);
    }
}
