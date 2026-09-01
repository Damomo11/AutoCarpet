package com.autocarpet.gui;

import com.autocarpet.AutoCarpet;
import com.autocarpet.module.Module;
import com.autocarpet.module.Setting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Compact single-page configuration screen opened by ModMenu. */
public class ConfigScreen extends Screen {
    private final Screen parent;
    private final List<net.minecraft.client.gui.components.AbstractWidget> settingWidgets = new ArrayList<>();
    private Module module;
    private Button moduleToggle;
    private double scroll;
    private boolean capturingKey;
    private Button keyButton;
    private int contentTop;
    private int contentBottom;

    public ConfigScreen(Screen parent) {
        super(Component.literal("自动地毯画配置"));
        this.parent = parent;
        if (!AutoCarpet.get().modules.isEmpty()) this.module = AutoCarpet.get().modules.getFirst();
    }

    @Override
    protected void init() {
        this.contentTop = 72;
        this.contentBottom = this.height - 36;
        this.moduleToggle = Button.builder(Component.literal(status()), button -> {
            this.module.toggle();
            button.setMessage(Component.literal(status()));
            refreshSettingWidgets();
        }).bounds(this.width / 2 - 100, 28, 200, 20).build();
        this.addRenderableWidget(this.moduleToggle);
        this.addRenderableWidget(Button.builder(Component.literal("图片生成投影"), button ->
                        this.minecraft.setScreen(new com.autocarpet.img.ImageToSchematicScreen(this)))
                .bounds(this.width / 2 - 100, 50, 200, 20).build());
        refreshSettingWidgets();
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), button -> this.onClose())
                .bounds(this.width / 2 - 45, this.height - 27, 90, 20).build());
    }

    private String status() {
        return this.module == null ? "无模块" : this.module.name + " · " + (this.module.enabled ? "开启" : "关闭");
    }

    private void refreshSettingWidgets() {
        for (var widget : this.settingWidgets) this.removeWidget(widget);
        this.settingWidgets.clear();
        if (this.module == null) return;
        int y = this.contentTop - (int) this.scroll;
        for (Setting<?> setting : this.module.settings) {
            // Queue is discovered automatically from momomap; do not expose legacy queue fields.
            if (setting == this.moduleSetting("投影路径(相对)") || setting == this.moduleSetting("投影名称")) continue;
            if (setting instanceof Setting.Bool bool) {
                Button button = Button.builder(Component.literal(setting.name + "  " + (bool.get() ? "开" : "关")), b -> {
                    bool.set(!bool.get());
                    b.setMessage(Component.literal(setting.name + "  " + (bool.get() ? "开" : "关")));
                }).bounds(this.width / 2 - 150, y, 300, 20).build();
                addSetting(button, y);
            } else if (setting instanceof Setting.Int integer) {
                if ("快捷键".equals(setting.name)) {
                    this.keyButton = Button.builder(Component.literal(capturingKey ? "按下按键…" : "快捷键  " + integer.get()), b -> {
                        this.capturingKey = true;
                        b.setMessage(Component.literal("按下按键…"));
                    }).bounds(this.width / 2 - 150, y, 300, 20).build();
                    addSetting(this.keyButton, y);
                    y += 24;
                    continue;
                }
                Button minus = Button.builder(Component.literal("−"), b -> { integer.set(Math.max(integer.min, integer.get() - intStep(integer))); refreshSettingWidgets(); })
                        .bounds(this.width / 2 - 150, y, 30, 20).build();
                Button plus = Button.builder(Component.literal("+"), b -> { integer.set(Math.min(integer.max, integer.get() + intStep(integer))); refreshSettingWidgets(); })
                        .bounds(this.width / 2 + 120, y, 30, 20).build();
                StringWidget label = new StringWidget(this.width / 2 - 110, y + 2, 220, 16, Component.literal(setting.name + "  " + integer.get()), this.font);
                addSetting(minus, y); addSetting(label, y); addSetting(plus, y);
            } else if (setting instanceof Setting.Double decimal) {
                double step = (decimal.max - decimal.min) > 10.0 ? 1.0 : 0.01;
                Button minus = Button.builder(Component.literal("−"), b -> { decimal.set(Math.max(decimal.min, decimal.get() - step)); refreshSettingWidgets(); })
                        .bounds(this.width / 2 - 150, y, 30, 20).build();
                Button plus = Button.builder(Component.literal("+"), b -> { decimal.set(Math.min(decimal.max, decimal.get() + step)); refreshSettingWidgets(); })
                        .bounds(this.width / 2 + 120, y, 30, 20).build();
                StringWidget label = new StringWidget(this.width / 2 - 110, y + 2, 220, 16, Component.literal(setting.name + "  " + String.format(java.util.Locale.ROOT, "%.2f", decimal.get())), this.font);
                addSetting(minus, y); addSetting(label, y); addSetting(plus, y);
            } else if (setting instanceof Setting.Str text) {
                EditBox box = new EditBox(this.font, this.width / 2 - 150, y, 300, 20, Component.literal(setting.name));
                box.setMaxLength(256);
                box.setValue(text.get());
                box.setResponder(text::set);
                addSetting(box, y);
            }
            y += 24;
        }
    }

    private Setting<?> moduleSetting(String name) {
        for (Setting<?> setting : this.module.settings) if (setting.name.equals(name)) return setting;
        return null;
    }

    private void addSetting(net.minecraft.client.gui.components.AbstractWidget widget, int y) {
        this.settingWidgets.add(widget);
        this.addRenderableWidget(widget);
        widget.visible = y + 20 >= this.contentTop && y <= this.contentBottom;
    }

    private int intStep(Setting.Int setting) {
        return (setting.max - setting.min) > 100 ? 10 : 1;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.capturingKey && this.module != null) {
            if (event.key() == 256) {
                this.capturingKey = false;
                refreshSettingWidgets();
                return true;
            }
            Setting<?> setting = moduleSetting("快捷键");
            if (setting instanceof Setting.Int key) {
                key.set(Math.max(0, Math.min(512, event.key())));
                this.capturingKey = false;
                refreshSettingWidgets();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseY >= this.contentTop && mouseY <= this.contentBottom) {
            int rows = this.module == null ? 0 : (int) this.module.settings.stream().filter(s -> s != moduleSetting("投影路径(相对)") && s != moduleSetting("投影名称")).count();
            double max = Math.max(0, rows * 24 - (this.contentBottom - this.contentTop));
            this.scroll = Math.max(0, Math.min(max, this.scroll - vertical * 24));
            refreshSettingWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        AutoCarpet.get().config.save();
        this.minecraft.setScreen(this.parent);
    }
}
