package com.autocarpet;

import com.autocarpet.carto.CartographerModule;
import com.autocarpet.ConfigManager;
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

    public static AutoCarpet get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        CartographerModule cartographer = new CartographerModule();
        this.modules.add(cartographer);
        this.config = new ConfigManager();
        this.toggleCartographer = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autocarpet.toggle", GLFW.GLFW_KEY_RIGHT_CONTROL, KeyMapping.Category.MISC));
        cartographer.toggleKey.onChanged = () -> this.toggleCartographer.setKey(InputConstants.Type.KEYSYM.getOrCreate(cartographer.toggleKey.get()));
        this.config.load(this.modules);
        this.toggleCartographer.setKey(InputConstants.Type.KEYSYM.getOrCreate(cartographer.toggleKey.get()));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
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
