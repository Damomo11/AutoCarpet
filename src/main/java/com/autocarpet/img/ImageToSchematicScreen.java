package com.autocarpet.img;

import com.autocarpet.util.Chat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 图片 -> 地毯投影生成器界面: Ctrl+V 粘贴或选择文件载入图片,
 * 拖选正方形选区 (边长 16-256 可设, 默认 128), 最近颜色映射为 16 色地毯,
 * 生成单层纯地毯 .litematic 直接写入 schematics/momomap (进入制图队列)。
 */
public class ImageToSchematicScreen extends Screen {
    private static final int MIN_SIZE = 16;
    private static final int MAX_SIZE = 256;
    private static final int DEFAULT_SIZE = 128;
    /** 预览缩略图最大边长 (避免每帧逐像素 fill 过多) */
    private static final int THUMB_MAX = 160;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    private final Screen parent;

    // ---- 图片状态 ----
    private int[] pixels;        // ARGB 行优先
    private int imgWidth = -1;
    private int imgHeight = -1;
    private int[] thumb;         // 预览缩略图 ARGB
    private int thumbWidth = -1;
    private int thumbHeight = -1;

    // ---- 选区 (图片像素坐标, 正方形, 大小固定=边长设置) ----
    private int selX;
    private int selY;
    private int selSize;

    // ---- 控件 ----
    private EditBox sizeBox;
    private EditBox nameBox;
    private String sizeText = String.valueOf(DEFAULT_SIZE);
    private String nameText = "";
    private int targetSize = DEFAULT_SIZE;

    // ---- 布局 ----
    private int previewX;
    private int previewY;
    private int previewSize;
    private int imgDrawX;
    private int imgDrawY;
    private double imgScale;

    // ---- 拖选 ----
    private boolean dragging;
    private int grabOffsetX;
    private int grabOffsetY;

    // ---- 预览 GPU 贴图 (1 次 blit/帧, 替代逐像素 fill) ----
    private DynamicTexture previewTexture;
    private Identifier previewTextureId;

    // ---- 生成结果 ----
    private PixelArtGenerator.Result lastResult;
    private String status = "Ctrl+V 粘贴图片, 或点击[选择文件]";
    private int statusColor = 0xFFAAAAAA;

    public ImageToSchematicScreen(Screen parent) {
        super(Component.literal("图片生成投影"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int contentTop = 44;
        this.addRenderableWidget(Button.builder(Component.literal("粘贴图片"), b -> this.pasteFromClipboard())
                .bounds(this.width / 2 - 102, 20, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("选择文件"), b -> this.chooseFile())
                .bounds(this.width / 2 + 2, 20, 100, 20).build());

        // 预览面板 (左侧)
        this.previewX = 8;
        this.previewY = contentTop;
        this.previewSize = Math.max(80, Math.min(this.width - 244, Math.min(this.height - 116, 256)));

        // 右侧控制列
        int rx = this.previewX + this.previewSize + 8;
        int rw = Math.max(120, Math.min(this.width - rx - 8, 220));

        this.sizeBox = new EditBox(this.font, rx + 32, 44, 60, 18, Component.literal("边长"));
        this.sizeBox.setMaxLength(3);
        this.sizeBox.setValue(this.sizeText);
        this.sizeBox.setResponder(value -> {
            this.sizeText = value;
            try {
                this.targetSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, Integer.parseInt(value.trim())));
            } catch (NumberFormatException ignored) {
            }
        });
        this.addRenderableWidget(this.sizeBox);
        this.addRenderableWidget(Button.builder(Component.literal("−"), b -> this.adjustSize(-8))
                .bounds(rx + 96, 44, 20, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.adjustSize(8))
                .bounds(rx + 118, 44, 20, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("适应边长"), b -> this.fitSelection())
                .bounds(rx, 66, 110, 18).build());

        this.nameBox = new EditBox(this.font, rx, 100, rw, 18, Component.literal("名称"));
        this.nameBox.setMaxLength(64);
        this.nameBox.setValue(this.nameText);
        this.nameBox.setResponder(value -> this.nameText = value);
        this.nameBox.setHint(Component.literal("投影名称"));
        this.addRenderableWidget(this.nameBox);

        this.addRenderableWidget(Button.builder(Component.literal("生成投影"), b -> this.generate())
                .bounds(rx, 124, rw, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(this.width / 2 - 45, this.height - 27, 90, 20).build());
        this.updateImageRect();
    }

    private void adjustSize(int delta) {
        int size = this.targetSize + delta;
        try {
            size = Integer.parseInt(this.sizeText.trim()) + delta;
        } catch (NumberFormatException ignored) {
        }
        size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        this.targetSize = size;
        this.sizeText = String.valueOf(size);
        this.sizeBox.setValue(this.sizeText);
        this.applySelectionSize();
    }

    /** 选区大小固定 = 边长设置 (夹到图片内), 位置保持并夹回 */
    private void applySelectionSize() {
        if (this.pixels == null) return;
        this.selSize = Math.max(1, Math.min(this.targetSize, Math.min(this.imgWidth, this.imgHeight)));
        this.selX = Math.max(0, Math.min(this.imgWidth - this.selSize, this.selX));
        this.selY = Math.max(0, Math.min(this.imgHeight - this.selSize, this.selY));
    }

    // ==================================================================
    // 图片载入
    // ==================================================================

    private void pasteFromClipboard() {
        Thread worker = new Thread(() -> {
            try {
                java.awt.Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
                Clipboard clipboard = toolkit.getSystemClipboard();
                if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    this.minecraft.execute(() -> this.setStatus("剪贴板中没有图片", 0xFFFF5555));
                    return;
                }
                Object data = clipboard.getData(DataFlavor.imageFlavor);
                if (!(data instanceof Image image) || image.getWidth(null) <= 0 || image.getHeight(null) <= 0) {
                    this.minecraft.execute(() -> this.setStatus("剪贴板图片无效", 0xFFFF5555));
                    return;
                }
                BufferedImage buffered = toBufferedImage(image);
                String name = "momomap_" + TIME_FORMAT.format(LocalDateTime.now());
                this.minecraft.execute(() -> this.loadImage(buffered, name));
            } catch (Throwable throwable) {
                String message = throwable.getMessage();
                if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
                String finalMessage = message;
                this.minecraft.execute(() -> this.setStatus("粘贴失败: " + finalMessage, 0xFFFF5555));
            }
        }, "autocarpet-clipboard");
        worker.setDaemon(true);
        worker.start();
    }

    private void chooseFile() {
        Thread worker = new Thread(() -> {
        try {
            java.awt.FileDialog dialog = new java.awt.FileDialog((java.awt.Frame) null, "选择图片", java.awt.FileDialog.LOAD);
            try {
                dialog.setFilenameFilter((dir, name) -> name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|bmp|gif)$"));
            } catch (Throwable ignored) {
            }
            dialog.setVisible(true);   // 模态, 选完返回 (后台线程, 不卡渲染)
            String fileName = dialog.getFile();
            if (fileName == null) return;
            File file = new File(dialog.getDirectory(), fileName);
            BufferedImage image = ImageIO.read(file);
            String base = fileName;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            String finalBase = base;
            if (image == null) {
                this.minecraft.execute(() -> this.setStatus("无法解码图片 (支持 png/jpg/bmp/gif)", 0xFFFF5555));
                return;
            }
            this.minecraft.execute(() -> this.loadImage(image, finalBase));
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
            String finalMessage = message;
            this.minecraft.execute(() -> this.setStatus("读取文件失败: " + finalMessage, 0xFFFF5555));
        }
        }, "autocarpet-filedialog");
        worker.setDaemon(true);
        worker.start();
    }

    /** 统一转成 TYPE_INT_ARGB, 保证 getRGB 拿到统一格式 */
    private static BufferedImage toBufferedImage(Image image) {
        int width = image.getWidth(null);
        int height = image.getHeight(null);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return out;
    }

    private void loadImage(BufferedImage image, String defaultName) {
        this.imgWidth = image.getWidth();
        this.imgHeight = image.getHeight();
        this.pixels = new int[this.imgWidth * this.imgHeight];
        image.getRGB(0, 0, this.imgWidth, this.imgHeight, this.pixels, 0, this.imgWidth);
        // 最近邻生成预览缩略图
        double scale = Math.min(1.0, (double) THUMB_MAX / Math.max(this.imgWidth, this.imgHeight));
        this.thumbWidth = Math.max(1, (int) Math.round(this.imgWidth * scale));
        this.thumbHeight = Math.max(1, (int) Math.round(this.imgHeight * scale));
        this.thumb = new int[this.thumbWidth * this.thumbHeight];
        for (int ty = 0; ty < this.thumbHeight; ty++) {
            int sy = Math.min(this.imgHeight - 1, (int) ((ty + 0.5) * this.imgHeight / this.thumbHeight));
            for (int tx = 0; tx < this.thumbWidth; tx++) {
                int sx = Math.min(this.imgWidth - 1, (int) ((tx + 0.5) * this.imgWidth / this.thumbWidth));
                this.thumb[ty * this.thumbWidth + tx] = this.pixels[sy * this.imgWidth + sx];
            }
        }
        this.nameText = defaultName;
        if (this.nameBox != null) this.nameBox.setValue(defaultName);
        this.lastResult = null;
        this.fitSelection();
        this.updateImageRect();
        this.updatePreviewTexture();
        this.setStatus("已载入 " + this.imgWidth + "x" + this.imgHeight + " (拖动鼠标框选正方形)", 0xFF55FF55);
    }

    /** 把缩略图写入 GPU 贴图: 透明像素预合成到深色底, 整张不透明避免混合问题 */
    private void updatePreviewTexture() {
        if (this.thumb == null) return;
        if (this.previewTexture == null) {
            this.previewTexture = new DynamicTexture("autocarpet_preview", this.thumbWidth, this.thumbHeight, true);
            this.previewTextureId = Identifier.fromNamespaceAndPath("autocarpet", "preview");
            this.minecraft.getTextureManager().register(this.previewTextureId, this.previewTexture);
        }
        com.mojang.blaze3d.platform.NativeImage image = this.previewTexture.getPixels();
        for (int ty = 0; ty < this.thumbHeight; ty++) {
            for (int tx = 0; tx < this.thumbWidth; tx++) {
                int argb = this.thumb[ty * this.thumbWidth + tx];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (((argb >>> 24) & 0xFF) < PixelArtGenerator.ALPHA_THRESHOLD) { r = 0x20; g = 0x20; b = 0x20; }
                image.setPixelABGR(tx, ty, (0xFF << 24) | (b << 16) | (g << 8) | r);
            }
        }
        this.previewTexture.upload();
    }

    private void releasePreviewTexture() {
        if (this.previewTextureId != null) {
            this.minecraft.getTextureManager().release(this.previewTextureId);
            this.previewTexture = null;
            this.previewTextureId = null;
        }
    }

    /** 图片在面板内的绘制矩形 (等比缩放居中) */
    private void updateImageRect() {
        if (this.thumbWidth <= 0) return;
        this.imgScale = Math.min(this.previewSize / (double) this.thumbWidth, this.previewSize / (double) this.thumbHeight);
        int w = (int) Math.round(this.thumbWidth * this.imgScale);
        int h = (int) Math.round(this.thumbHeight * this.imgScale);
        this.imgDrawX = this.previewX + (this.previewSize - w) / 2;
        this.imgDrawY = this.previewY + (this.previewSize - h) / 2;
    }

    /** 自动取图中心的正方形选区, 边长 = 边长框的值 (不超过图片) */
    private void fitSelection() {
        if (this.pixels == null) return;
        this.applySelectionSize();
        this.selX = (this.imgWidth - this.selSize) / 2;
        this.selY = (this.imgHeight - this.selSize) / 2;
    }

    private void setStatus(String message, int color) {
        this.status = message;
        this.statusColor = color;
    }

    // ==================================================================
    // 生成
    // ==================================================================

    private void generate() {
        if (this.pixels == null) {
            this.setStatus("请先载入图片 (Ctrl+V 或 选择文件)", 0xFFFF5555);
            return;
        }
        if (this.selSize < 1) {
            this.setStatus("请先拖选一个正方形选区", 0xFFFF5555);
            return;
        }
        String name = sanitizeName(this.nameText);
        if (name.isEmpty()) {
            this.setStatus("请输入投影名称", 0xFFFF5555);
            return;
        }
        try {
            PixelArtGenerator.Result result = PixelArtGenerator.generate(
                    this.pixels, this.imgWidth, this.imgHeight, this.selX, this.selY, this.selSize, this.targetSize);
            if (result.totalBlocks == 0) {
                this.setStatus("选区全是透明像素, 没有可打印内容", 0xFFFF5555);
                return;
            }
            java.nio.file.Path path = PixelArtGenerator.writeSchematic(name, result);
            this.lastResult = result;
            this.setStatus("已生成 " + path.getFileName() + " (已加入队列)", 0xFF55FF55);
            Chat.info("投影已生成: %s.litematic（已加入队列）", name);
            PixelArtGenerator.sendStatsToChat(result);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            String message = throwable.getMessage();
            if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
            this.setStatus("生成失败: " + message, 0xFFFF5555);
        }
    }

    /** 只保留安全文件名字符 (字母/数字/下划线/连字符/中文), 其余替换为 _ */
    private static String sanitizeName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length() && i < 64; i++) {
            char c = trimmed.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || (c >= 0x4E00 && c <= 0x9FFF);
            builder.append(safe ? c : '_');
        }
        return builder.toString();
    }

    // ==================================================================
    // 输入: 粘贴 / 拖选
    // ==================================================================

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Ctrl+V 粘贴 (优先于输入框, 名称框聚焦时同样可用)
        if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
            this.pasteFromClipboard();
            return true;
        }
        // 方向键微调选区 (输入框聚焦时交给输入框)
        int dx = 0;
        int dy = 0;
        if (event.key() == GLFW.GLFW_KEY_LEFT) dx = -1;
        else if (event.key() == GLFW.GLFW_KEY_RIGHT) dx = 1;
        else if (event.key() == GLFW.GLFW_KEY_UP) dy = -1;
        else if (event.key() == GLFW.GLFW_KEY_DOWN) dy = 1;
        if ((dx != 0 || dy != 0) && this.pixels != null && !(this.getFocused() instanceof EditBox)) {
            this.selX = Math.max(0, Math.min(this.imgWidth - this.selSize, this.selX + dx));
            this.selY = Math.max(0, Math.min(this.imgHeight - this.selSize, this.selY + dy));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        this.releasePreviewTexture();
        super.removed();
    }

    /** GUI 坐标 -> 图片像素坐标 (取不到时返回 null) */
    private int[] toImagePixel(double mouseX, double mouseY) {
        if (this.pixels == null) return null;
        int fx = (int) Math.floor((mouseX - this.imgDrawX) / this.imgScale);
        int fy = (int) Math.floor((mouseY - this.imgDrawY) / this.imgScale);
        fx = Math.max(0, Math.min(this.imgWidth - 1, fx));
        fy = Math.max(0, Math.min(this.imgHeight - 1, fy));
        return new int[]{fx, fy};
    }

    private boolean inPreview(double mouseX, double mouseY) {
        return mouseX >= this.previewX - 2 && mouseX <= this.previewX + this.previewSize + 2
                && mouseY >= this.previewY - 2 && mouseY <= this.previewY + this.previewSize + 2;
    }

    /** 移动选区 (像素坐标为鼠标位置, 保持抓取偏移, 始终夹回图片内) */
    private void moveSelection(int pixelX, int pixelY) {
        this.selX = Math.max(0, Math.min(this.imgWidth - this.selSize, pixelX - this.grabOffsetX));
        this.selY = Math.max(0, Math.min(this.imgHeight - this.selSize, pixelY - this.grabOffsetY));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() == 0 && this.pixels != null && this.inPreview(event.x(), event.y())) {
            int[] anchor = this.toImagePixel(event.x(), event.y());
            this.dragging = true;
            // 抓取偏移夹在选区内, 保证拖动时选区始终跟随且不出图片
            this.grabOffsetX = Math.max(0, Math.min(this.selSize, anchor[0] - this.selX));
            this.grabOffsetY = Math.max(0, Math.min(this.selSize, anchor[1] - this.selY));
            this.moveSelection(anchor[0], anchor[1]);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (this.dragging) {
            int[] current = this.toImagePixel(event.x(), event.y());
            this.moveSelection(current[0], current[1]);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.dragging) {
            this.dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        // ---- 预览面板 ----
        int px2 = this.previewX + this.previewSize;
        int py2 = this.previewY + this.previewSize;
        graphics.fill(this.previewX - 2, this.previewY - 2, px2 + 2, py2 + 2, 0xFF000000);
        graphics.fill(this.previewX, this.previewY, px2, py2, 0xFF202020);
        if (this.thumb != null) {
            this.drawImage(graphics);
            this.drawSelection(graphics);
            // 选区信息条 (面板底部)
            graphics.fill(this.previewX, py2 - 12, px2, py2, 0xA0000000);
            String info = "选区 " + this.selSize + "x" + this.selSize + " (" + this.selX + "," + this.selY + ") -> " + this.targetSize + "x" + this.targetSize;
            graphics.centeredText(this.font, info, (this.previewX + px2) / 2, py2 - 10, 0xFFFFFF);
        } else {
            String[] hints = {"Ctrl+V 粘贴图片", "或点击[选择文件]"};
            for (int i = 0; i < hints.length; i++) {
                graphics.centeredText(this.font, hints[i], (this.previewX + px2) / 2, this.previewY + this.previewSize / 2 - 14 + i * 12, 0xFF888888);
            }
        }

        // ---- 右侧标签 ----
        int rx = this.previewX + this.previewSize + 8;
        graphics.text(this.font, "边长", rx, 48, 0xFFCCCCCC);
        graphics.text(this.font, MIN_SIZE + "-" + MAX_SIZE, rx + 142, 48, 0xFF888888);
        graphics.text(this.font, "名称", rx, 89, 0xFFCCCCCC);
        // 状态信息
        if (this.status != null && !this.status.isEmpty()) {
            graphics.textWithWordWrap(this.font, FormattedText.of(this.status), rx, 150, Math.max(120, this.width - rx - 8), this.statusColor);
        }

        // ---- 地毯数量统计 (色块 + 数量, 超过一组标红) ----
        if (this.lastResult != null) {
            int y = Math.max(this.previewY + this.previewSize + 16, this.height - 78);
            int x = 8;
            for (int i = 0; i < CarpetPalette.SIZE && y < this.height - 32; i++) {
                int count = this.lastResult.counts[i];
                if (count == 0) continue;
                if (x + 44 > this.width - 8) {
                    x = 8;
                    y += 13;
                    if (y >= this.height - 32) break;
                }
                graphics.fill(x, y + 1, x + 8, y + 9, 0xFF000000 | CarpetPalette.rgb(i));
                graphics.text(this.font, "x" + count, x + 11, y + 1, count > 64 ? 0xFFFF5555 : 0xFFAAAAAA);
                x += 11 + this.font.width("x" + count) + 8;
            }
        }
    }

    /** 整张预览一次 blit (贴图已含底色) */
    private void drawImage(GuiGraphicsExtractor graphics) {
        if (this.previewTextureId == null) return;
        int drawWidth = Math.max(1, (int) Math.round(this.thumbWidth * this.imgScale));
        int drawHeight = Math.max(1, (int) Math.round(this.thumbHeight * this.imgScale));
        graphics.blit(this.previewTextureId, this.imgDrawX, this.imgDrawY, drawWidth, drawHeight,
                0.0f, 0.0f, 1.0f, 1.0f);
    }

    /** 选区高亮框 */
    private void drawSelection(GuiGraphicsExtractor graphics) {
        if (this.selSize < 1) return;
        int x = this.imgDrawX + (int) Math.round(this.selX * this.imgScale);
        int y = this.imgDrawY + (int) Math.round(this.selY * this.imgScale);
        int w = Math.max(2, (int) Math.round(this.selSize * this.imgScale));
        graphics.outline(x, y, w, w, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.releasePreviewTexture();
        this.minecraft.setScreen(this.parent);
    }
}
