package com.autocarpet.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class BlockActions {
    private static final Minecraft mc = Minecraft.getInstance();
    private static BlockPos breaking;

    public static void breakBlock(BlockPos pos) {
        breaking = pos;
    }

    public static void stopBreaking() {
        breaking = null;
    }

    /** 每 tick 驱动 (对齐原版 BlockUtils.breakBlock(pos, true) 的节奏: 不带额外冷却, 每 tick 重试) */
    public static void tickBreaking() {
        if (mc.player == null || mc.gameMode == null || mc.level == null) return;
        if (breaking != null) {
            Direction dir = Direction.UP;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(breaking), dir, breaking, false);
            if (mc.gameMode.continueDestroyBlock(breaking, dir)) {
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            } else {
                mc.gameMode.startDestroyBlock(breaking, dir);
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            if (mc.level.getBlockState(breaking).isAir()) {
                breaking = null;
            }
        }
    }
}
