import io

p = r"src\main\java\com\autocarpet\img\ImageToSchematicScreen.java"
s = open(p, encoding="utf-8").read()
count = 0

def rep(old, new):
    global s, count
    assert old in s, "NOT FOUND: " + old[:80]
    s = s.replace(old, new, 1)
    count += 1

# 1. imports
rep("import net.minecraft.client.input.MouseButtonEvent;",
    "import net.minecraft.client.input.MouseButtonEvent;\n"
    "import net.minecraft.client.renderer.texture.DynamicTexture;\n"
    "import net.minecraft.resources.Identifier;")

# 2. fields
rep("""    // ---- 拖选 ----
    private boolean dragging;
    private int dragAnchorX;
    private int dragAnchorY;""",
    """    // ---- 拖选 ----
    private boolean dragging;
    private int dragAnchorX;
    private int dragAnchorY;
    private boolean dragMove;    // true=移动已有选区, false=框选新选区
    private int grabOffsetX;
    private int grabOffsetY;

    // ---- 预览 GPU 贴图 (1 次 blit/帧, 替代逐像素 fill) ----
    private DynamicTexture previewTexture;
    private Identifier previewTextureId;""")

# 3. texture update in loadImage
rep("""        this.fitSelection();
        this.updateImageRect();
        this.setStatus("已载入 " + this.imgWidth + "x" + this.imgHeight + " (拖动鼠标框选正方形)", 0xFF55FF55);
    }""",
    """        this.fitSelection();
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
    }""")

# 4. drawImage -> single blit
rep("""    /** 逐像素画缩略图 (小方块, 无插值) */
    private void drawImage(GuiGraphicsExtractor graphics) {
        for (int ty = 0; ty < this.thumbHeight; ty++) {
            int y1 = this.imgDrawY + (int) Math.round(ty * this.imgScale);
            int y2 = this.imgDrawY + (int) Math.round((ty + 1) * this.imgScale);
            if (y2 <= y1) y2 = y1 + 1;
            for (int tx = 0; tx < this.thumbWidth; tx++) {
                int color = this.thumb[ty * this.thumbWidth + tx];
                if ((color >>> 24) < PixelArtGenerator.ALPHA_THRESHOLD) continue;   // 透明留底色
                int x1 = this.imgDrawX + (int) Math.round(tx * this.imgScale);
                int x2 = this.imgDrawX + (int) Math.round((tx + 1) * this.imgScale);
                if (x2 <= x1) x2 = x1 + 1;
                graphics.fill(x1, y1, x2, y2, 0xFF000000 | color);
            }
        }
    }""",
    """    /** 整张预览一次 blit (贴图已含底色) */
    private void drawImage(GuiGraphicsExtractor graphics) {
        if (this.previewTextureId == null) return;
        int drawWidth = Math.max(1, (int) Math.round(this.thumbWidth * this.imgScale));
        int drawHeight = Math.max(1, (int) Math.round(this.thumbHeight * this.imgScale));
        graphics.blit(this.previewTextureId, this.imgDrawX, this.imgDrawY, drawWidth, drawHeight,
                0.0f, 0.0f, (float) this.thumbWidth, (float) this.thumbHeight);
    }""")

# 5. mouse interactions: move vs create
rep("""    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() == 0 && this.pixels != null && this.inPreview(event.x(), event.y())) {
            int[] anchor = this.toImagePixel(event.x(), event.y());
            this.dragging = true;
            this.dragAnchorX = anchor[0];
            this.dragAnchorY = anchor[1];
            this.applyDrag(anchor[0], anchor[1]);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (this.dragging) {
            int[] current = this.toImagePixel(event.x(), event.y());
            this.applyDrag(current[0], current[1]);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }""",
    """    /** 鼠标点是否落在当前选区框内 (屏幕坐标) */
    private boolean inSelectionBox(double mouseX, double mouseY) {
        if (this.pixels == null || this.selSize < 1) return false;
        int x1 = this.imgDrawX + (int) Math.round(this.selX * this.imgScale);
        int y1 = this.imgDrawY + (int) Math.round(this.selY * this.imgScale);
        int side = Math.max(2, (int) Math.round(this.selSize * this.imgScale));
        return mouseX >= x1 && mouseX <= x1 + side && mouseY >= y1 && mouseY <= y1 + side;
    }

    /** 移动选区 (像素坐标为鼠标位置, 保持抓取偏移) */
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
            if (this.inSelectionBox(event.x(), event.y())) {
                // 选区内按下: 移动整个选区
                this.dragMove = true;
                this.grabOffsetX = anchor[0] - this.selX;
                this.grabOffsetY = anchor[1] - this.selY;
                this.moveSelection(anchor[0], anchor[1]);
            } else {
                // 选区外按下: 重新框选
                this.dragMove = false;
                this.dragAnchorX = anchor[0];
                this.dragAnchorY = anchor[1];
                this.applyDrag(anchor[0], anchor[1]);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (this.dragging) {
            int[] current = this.toImagePixel(event.x(), event.y());
            if (this.dragMove) {
                this.moveSelection(current[0], current[1]);
            } else {
                this.applyDrag(current[0], current[1]);
            }
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }""")

# 6. arrow key nudge + release on removed()
rep("""    @Override
    public boolean keyPressed(KeyEvent event) {
        // Ctrl+V 粘贴 (优先于输入框, 名称框聚焦时同样可用)
        if (event.key() == GLFW.GLFW_KEY_V && event.hasControlDown()) {
            this.pasteFromClipboard();
            return true;
        }
        return super.keyPressed(event);
    }""",
    """    @Override
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
    }""")

# 7. onClose release
rep("""    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }""",
    """    @Override
    public void onClose() {
        this.releasePreviewTexture();
        this.minecraft.setScreen(this.parent);
    }""")

open(p, "w", encoding="utf-8", newline="").write(s)
print("applied", count, "edits")
