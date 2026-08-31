package com.autocarpet.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * 旋转控制: 面向行走方向 + 随机视角偏移。
 */
public class Rotations {
    private static final Random RANDOM = new Random();
    private static double jitterDegrees = 1.5;
    private static Vec3 target;
    private static boolean faceMovement = true;
    private static boolean enabled;
    private static boolean randomView;

    public static void setJitter(double degrees) {
        jitterDegrees = degrees;
    }

    public static void lookAt(Vec3 pos) {
        target = pos;
    }

    public static void clearTarget() {
        target = null;
    }

    public static void setFaceMovement(boolean value) {
        faceMovement = value;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) clearTarget();
    }

    public static void setRandomView(boolean value) {
        randomView = value;
    }

    public static void configure(boolean turning, boolean random) {
        setEnabled(turning);
        setFaceMovement(turning);
        setRandomView(random);
    }

    public static void tick() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (target != null) {
            double dx = target.x - player.getX();
            double dy = target.y - (player.getEyeY());
            double dz = target.z - player.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
            apply(player, yaw, pitch);
            if (player.distanceToSqr(target) < 0.5) target = null;
        } else if (faceMovement) {
            Vec3 vel = player.getDeltaMovement();
            double horiz = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            if (horiz > 0.08) {
                float yaw = (float) (Math.toDegrees(Math.atan2(vel.z, vel.x)) - 90.0);
                apply(player, yaw, player.getXRot());
            }
        }
    }

    private static void apply(LocalPlayer player, float yaw, float pitch) {
        double jy = randomView ? (RANDOM.nextDouble() - 0.5) * 2.0 * jitterDegrees : 0.0;
        double jp = randomView ? (RANDOM.nextDouble() - 0.5) * 2.0 * jitterDegrees * 0.5 : 0.0;
        player.setYRot(smooth(player.getYRot(), yaw + (float) jy));
        player.setYHeadRot(player.getYRot());
        player.setXRot(java.lang.Math.clamp(pitch + (float) jp, -90.0f, 90.0f));
    }

    private static float smooth(float current, float target) {
        float delta = net.minecraft.util.Mth.wrapDegrees(target - current);
        if (delta > 30.0f) delta = 30.0f;
        if (delta < -30.0f) delta = -30.0f;
        return current + delta;
    }
}
