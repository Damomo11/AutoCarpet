package com.autocarpet.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * 物品栏操作工具 (移植自 Lotus ar_0 + meteor InvUtils/SlotUtils 中制图师用到的部分)。
 * 全部通过 mc.gameMode.handleContainerInput 完成, 不手搓包。
 */
public class InvUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    /** 背包主体大小 (主物品栏, 不含装备) */
    public static final int MAIN_SIZE = 36;

    // ------------------------------------------------------------------
    // 槽位换算 (meteor SlotUtils.indexToId)
    // ------------------------------------------------------------------

    /**
     * 背包索引 (0-8 快捷栏, 9-35 主物品栏, 36-44 盔甲/副手) -> 当前容器窗口槽位 id。
     * 与原版 (meteor SlotUtils.indexToId) 一致: 优先在当前菜单里动态查找
     * "容器为玩家背包且容器内下标匹配"的窗口槽位。
     * 找不到匹配时按容器协议的标准布局兜底 (玩家背包区域槽位号是标准布局):
     * 背包界面 (InventoryMenu): 主栏(9-35)原样、快捷栏(0-8)+36;
     * 普通容器: 玩家背包 36 格固定排在窗口末尾 (主栏在前, 快捷栏在后)。
     * 任何情况下都不会把 -1/越界 id 返回给调用方。
     */
    public static int indexToId(int index) {
        if (mc.player == null) return Math.max(0, index);
        var menu = mc.player.containerMenu;
        int size = menu.slots.size();
        for (int i = 0; i < size; i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == index) return i;
        }
        if (index >= 0) {
            if (menu instanceof InventoryMenu && index < MAIN_SIZE) {
                return Math.min(index >= 9 ? index : index + 36, size - 1);
            }
            if (size > MAIN_SIZE) {
                return Math.min(size - MAIN_SIZE + (index >= 9 ? index - 9 : 27 + index), size - 1);
            }
            return Math.min(index, size - 1);
        }
        return 0;
    }

    public static int selectedIndex() {
        return mc.player == null ? 0 : mc.player.getInventory().getSelectedSlot();
    }

    // ------------------------------------------------------------------
    // 基础点击
    // ------------------------------------------------------------------

    /** 向当前打开的容器/背包发送一次点击 */
    public static void click(int slot, int button, ContainerInput input) {
        if (mc.player == null || mc.gameMode == null) return;
        int syncId = mc.player.containerMenu.containerId;
        mc.gameMode.handleContainerInput(syncId, slot, button, input, mc.player);
    }

    public static void shiftClick(int windowSlot) {
        if (windowSlot < 0) return;
        click(windowSlot, 0, ContainerInput.QUICK_MOVE);
    }

    /** shift 点击背包索引对应的槽位 (meteor InvUtils.shiftClick().slot(index)) */
    public static void shiftClickInv(int invIndex) {
        if (invIndex < 0 || invIndex >= MAIN_SIZE) return;
        shiftClick(indexToId(invIndex));
    }

    public static void pickup(int slot) {
        if (slot < 0) return;
        click(slot, 0, ContainerInput.PICKUP);
    }

    /** 两段式移动: 从 from 拿起, 放到 to (meteor InvUtils.move().from(a).to(b)) */
    public static void move(int windowFrom, int windowTo) {
        if (windowFrom < 0 || windowTo < 0) return;
        pickup(windowFrom);
        pickup(windowTo);
    }

    /** 三段式交换 (干净交换, 光标不残留) */
    public static void swapSlots(int windowA, int windowB) {
        if (windowA < 0 || windowB < 0) return;
        pickup(windowA);
        pickup(windowB);
        pickup(windowA);
    }

    /** 丢弃指定槽位的物品（背包自身槽位号, 与 meteor InvUtils.drop().slot(index) 一致） */
    public static void dropSlot(int slot) {
        if (slot < 0 || slot >= MAIN_SIZE) return;
        pickup(indexToId(slot));
        click(-999, 0, ContainerInput.PICKUP);
    }

    /** 对打开的容器使用窗口槽位号丢物品 */
    public static void dropWindowSlot(int windowSlot) {
        pickup(windowSlot);
        click(-999, 0, ContainerInput.PICKUP);
    }

    /**
     * 从容器槽位取 1 个物品放进背包槽位 (Lotus ar_0.d(int windowSlot, int invWindowId)):
     * 左键拿起 -> 右键放下 1 个 -> 左键放回剩余。
     */
    public static void takeOneFromContainer(int windowSlot, int invWindowId) {
        pickup(windowSlot);
        click(invWindowId, 1, ContainerInput.PICKUP);
        pickup(windowSlot);
    }

    // ------------------------------------------------------------------
    // 快捷栏选择 / 交换
    // ------------------------------------------------------------------

    /** 选中快捷栏槽位 (0-8) 并同步服务器 */
    public static void selectSlot(int hotbarIndex) {
        if (mc.player == null) return;
        if (mc.player.getInventory().getSelectedSlot() != hotbarIndex) {
            mc.player.getInventory().setSelectedSlot(hotbarIndex);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(hotbarIndex));
        }
    }

    /** SWAP: 把背包索引槽位的物品与当前手持交换 (Lotus ar_0.b(int)) */
    public static void swapWithSelected(int invIndex) {
        int selected = selectedIndex();
        if (invIndex == selected) return;
        click(indexToId(invIndex), selected, ContainerInput.SWAP);
    }

    /** 把背包索引槽位的物品换到手上 (Lotus ar_0.d(int)) */
    public static void moveToSelected(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            selectSlot(invIndex);
            return;
        }
        boolean handFull = !testInMainHand(Items.AIR);
        move(indexToId(invIndex), indexToId(selectedIndex()));
        if (handFull) {
            pickup(indexToId(invIndex));
        }
    }

    public static boolean testInMainHand(Item... items) {
        if (mc.player == null) return false;
        ItemStack hand = mc.player.getMainHandItem();
        for (Item item : items) {
            if (hand.getItem() == item) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 查找
    // ------------------------------------------------------------------

    public static FindItemResult find(Predicate<ItemStack> filter, int from, int to) {
        if (mc.player == null) return new FindItemResult(-1, ItemStack.EMPTY);
        return FindItemResult.search(mc.player.getInventory(), filter, from, to);
    }

    /** 在主物品栏 + 快捷栏 (0-35) 中查找 */
    public static FindItemResult find(Predicate<ItemStack> filter) {
        return find(filter, 0, MAIN_SIZE - 1);
    }

    /** 在快捷栏 (0-8) 中查找 */
    public static FindItemResult findInHotbar(Predicate<ItemStack> filter) {
        return find(filter, 0, 8);
    }

    public static FindItemResult find(Item... items) {
        return find(stack -> {
            Item item = stack.getItem();
            for (Item i : items) {
                if (item == i) return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------
    // 容器关闭
    // ------------------------------------------------------------------

    /** 关闭当前打开的容器 (若不是自身背包); Lotus ar_0.a() */
    public static void closeContainer() {
        if (mc.player == null) return;
        if (!(mc.player.containerMenu instanceof InventoryMenu)) {
            mc.player.connection.send(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
            mc.player.closeContainer();
        }
    }

    /** 仅发送关闭包 (Lotus ar_0.e()) */
    public static void sendClosePacket() {
        if (mc.player == null) return;
        mc.player.connection.send(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
    }

    public static ItemStack mainHand() {
        return mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
    }
}
