package com.autocarpet.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class Chat {
    private static final String PREFIX = "§b[自动地毯画]§r ";

    public static void info(String message) {
        send(PREFIX + "§7" + message);
    }

    public static void warning(String message) {
        send(PREFIX + "§e" + message);
    }

    public static void error(String message) {
        send(PREFIX + "§c" + message);
    }

    public static void info(String format, Object... args) {
        send(PREFIX + "§7" + String.format(format, args));
    }

    public static void warning(String format, Object... args) {
        send(PREFIX + "§e" + String.format(format, args));
    }

    public static void error(String format, Object... args) {
        send(PREFIX + "§c" + String.format(format, args));
    }

    private static void send(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.sendSystemMessage(Component.literal(text));
    }
}
