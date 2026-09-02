package com.autocarpet;

import com.autocarpet.carto.CartographerModule;
import com.autocarpet.ConfigManager;
import com.autocarpet.gui.ConfigScreen;
import com.autocarpet.module.Module;
import com.autocarpet.util.BlockActions;
import com.autocarpet.util.Rotations;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class AutoCarpet implements ClientModInitializer {
    private static AutoCarpet instance;
    public final List<Module> modules = new ArrayList<>();
    public ConfigManager config;
    private KeyMapping toggleCartographer;
    private KeyMapping openConfig;

    public static AutoCarpet get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        // Fabric 环境下 AWT 默认 headless, 会导致剪贴板图片/文件对话框抛 HeadlessException
        System.setProperty("java.awt.headless", "false");
        CartographerModule cartographer = new CartographerModule();
        this.modules.add(cartographer);
        this.config = new ConfigManager();
        this.toggleCartographer = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autocarpet.toggle", GLFW.GLFW_KEY_RIGHT_CONTROL, KeyMapping.Category.MISC));
        this.openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autocarpet.config", InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
        cartographer.toggleKey.onChanged = () -> this.toggleCartographer.setKey(InputConstants.Type.KEYSYM.getOrCreate(cartographer.toggleKey.get()));
        cartographer.configKey.onChanged = () -> this.openConfig.setKey(keyBinding(cartographer.configKey.get()));
        this.config.load(this.modules);
        this.toggleCartographer.setKey(InputConstants.Type.KEYSYM.getOrCreate(cartographer.toggleKey.get()));
        this.openConfig.setKey(keyBinding(cartographer.configKey.get()));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    /** 0 (无) 映射为未绑定按键, 其余按 KEYSYM 解析 */
    private static InputConstants.Key keyBinding(int code) {
        return code == 0 ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(code);
    }

    private void onTick(Minecraft mc) {
        while (this.openConfig.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new ConfigScreen(null));
        }
        while (this.toggleCartographer.consumeClick()) {
            for (Module module : this.modules) {
                if (module instanceof CartographerModule) {
                    module.toggle();
                    break;
                }
            }
        }
        for (Module module : this.modules) {
            if (module instanceof CartographerModule cartographer && module.enabled) {
                // 真实视角只在 baritone 寻路行走 (WALKING) 时跟随行走方向;
                // 打印相关状态 (READY/NEXT/OPEN_PRINTER/BREAK/放置/破坏) 完全不改真实视角 (静默包旋转)
                if (cartographer.isWalkingState()) Rotations.tick();
                break;
            }
        }
        BlockActions.tickBreaking();
        if (mc.player == null || mc.level == null) {
            for (Module module : this.modules) {
                if (module.enabled) module.toggle();
            }
            return;
        }
        for (Module module : this.modules) {
            if (module.enabled) module.onTick();
        }
    }
}
