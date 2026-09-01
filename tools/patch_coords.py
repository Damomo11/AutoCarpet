p = r"src\main\java\com\autocarpet\img\ImageToSchematicScreen.java"
s = open(p, encoding="utf-8").read()
count = 0

def rep(old, new):
    global s, count
    assert old in s, "NOT FOUND: " + old[:80]
    s = s.replace(old, new, 1)
    count += 1

# 1. 字段: imgScale 改为 dispScale (屏幕像素/图片像素), 增加绘制矩形尺寸
rep("""    private int previewSize;
    private int imgDrawX;
    private int imgDrawY;
    private double imgScale;""",
    """    private int previewSize;
    private int imgDrawX;
    private int imgDrawY;
    private int imgDrawWidth;
    private int imgDrawHeight;
    private double dispScale;   // 屏幕像素 / 图片像素""")

# 2. updateImageRect: 按图片尺寸等比缩放
rep("""    /** 图片在面板内的绘制矩形 (等比缩放居中) */
    private void updateImageRect() {
        if (this.thumbWidth <= 0) return;
        this.imgScale = Math.min(this.previewSize / (double) this.thumbWidth, this.previewSize / (double) this.thumbHeight);
        int w = (int) Math.round(this.thumbWidth * this.imgScale);
        int h = (int) Math.round(this.thumbHeight * this.imgScale);
        this.imgDrawX = this.previewX + (this.previewSize - w) / 2;
        this.imgDrawY = this.previewY + (this.previewSize - h) / 2;
    }""",
    """    /** 图片在面板内的绘制矩形 (按图片尺寸等比缩放居中) */
    private void updateImageRect() {
        if (this.imgWidth <= 0 || this.imgHeight <= 0) return;
        this.dispScale = Math.min(this.previewSize / (double) this.imgWidth, this.previewSize / (double) this.imgHeight);
        this.imgDrawWidth = Math.max(1, (int) Math.round(this.imgWidth * this.dispScale));
        this.imgDrawHeight = Math.max(1, (int) Math.round(this.imgHeight * this.dispScale));
        this.imgDrawX = this.previewX + (this.previewSize - this.imgDrawWidth) / 2;
        this.imgDrawY = this.previewY + (this.previewSize - this.imgDrawHeight) / 2;
    }""")

# 3. drawImage: 用像素 UV 的 10 参重载 (原版 WinScreen 同款, 语义已从字节码验证)
rep("""    /** 整张预览一次 blit (贴图已含底色) */
    private void drawImage(GuiGraphicsExtractor graphics) {
        if (this.previewTextureId == null) return;
        int drawWidth = Math.max(1, (int) Math.round(this.thumbWidth * this.imgScale));
        int drawHeight = Math.max(1, (int) Math.round(this.thumbHeight * this.imgScale));
        graphics.blit(this.previewTextureId, this.imgDrawX, this.imgDrawY, drawWidth, drawHeight,
                0.0f, 0.0f, 1.0f, 1.0f);
    }""",
    """    /** 整张预览一次 blit (贴图 0,0-160,160 像素区域缩放到绘制矩形) */
    private void drawImage(GuiGraphicsExtractor graphics) {
        if (this.previewTextureId == null || this.previewTexture == null) return;
        graphics.blit(com.mojang.blaze3d.pipeline.RenderPipelines.GUI_TEXTURED,
                this.previewTextureId,
                this.imgDrawX, this.imgDrawY,
                0.0f, 0.0f,
                this.imgDrawWidth, this.imgDrawHeight,
                this.thumbWidth, this.thumbHeight);
    }""")

# 4. drawSelection: 用 dispScale
rep("""    /** 选区高亮框 */
    private void drawSelection(GuiGraphicsExtractor graphics) {
        if (this.selSize < 1) return;
        int x = this.imgDrawX + (int) Math.round(this.selX * this.imgScale);
        int y = this.imgDrawY + (int) Math.round(this.selY * this.imgScale);
        int w = Math.max(2, (int) Math.round(this.selSize * this.imgScale));
        graphics.outline(x, y, w, w, 0xFFFFFFFF);
    }""",
    """    /** 选区高亮框 */
    private void drawSelection(GuiGraphicsExtractor graphics) {
        if (this.selSize < 1) return;
        int x = this.imgDrawX + (int) Math.round(this.selX * this.dispScale);
        int y = this.imgDrawY + (int) Math.round(this.selY * this.dispScale);
        int w = Math.max(2, (int) Math.round(this.selSize * this.dispScale));
        graphics.outline(x, y, w, w, 0xFFFFFFFF);
    }""")

# 5. toImagePixel: 用 dispScale
rep("""    /** GUI 坐标 -> 图片像素坐标 (取不到时返回 null) */
    private int[] toImagePixel(double mouseX, double mouseY) {
        if (this.pixels == null) return null;
        int fx = (int) Math.floor((mouseX - this.imgDrawX) / this.imgScale);
        int fy = (int) Math.floor((mouseY - this.imgDrawY) / this.imgScale);
        fx = Math.max(0, Math.min(this.imgWidth - 1, fx));
        fy = Math.max(0, Math.min(this.imgHeight - 1, fy));
        return new int[]{fx, fy};
    }""",
    """    /** GUI 坐标 -> 图片像素坐标 (取不到时返回 null) */
    private int[] toImagePixel(double mouseX, double mouseY) {
        if (this.pixels == null) return null;
        int fx = (int) Math.floor((mouseX - this.imgDrawX) / this.dispScale);
        int fy = (int) Math.floor((mouseY - this.imgDrawY) / this.dispScale);
        fx = Math.max(0, Math.min(this.imgWidth - 1, fx));
        fy = Math.max(0, Math.min(this.imgHeight - 1, fy));
        return new int[]{fx, fy};
    }""")

# 6. inSelectionBox: 用 dispScale
rep("""    /** 鼠标点是否落在当前选区框内 (屏幕坐标) */
    private boolean inSelectionBox(double mouseX, double mouseY) {
        if (this.pixels == null || this.selSize < 1) return false;
        int x1 = this.imgDrawX + (int) Math.round(this.selX * this.imgScale);
        int y1 = this.imgDrawY + (int) Math.round(this.selY * this.imgScale);
        int side = Math.max(2, (int) Math.round(this.selSize * this.imgScale));
        return mouseX >= x1 && mouseX <= x1 + side && mouseY >= y1 && mouseY <= y1 + side;
    }""",
    """    /** 鼠标点是否落在当前选区框内 (屏幕坐标) */
    private boolean inSelectionBox(double mouseX, double mouseY) {
        if (this.pixels == null || this.selSize < 1) return false;
        int x1 = this.imgDrawX + (int) Math.round(this.selX * this.dispScale);
        int y1 = this.imgDrawY + (int) Math.round(this.selY * this.dispScale);
        int side = Math.max(2, (int) Math.round(this.selSize * this.dispScale));
        return mouseX >= x1 && mouseX <= x1 + side && mouseY >= y1 && mouseY <= y1 + side;
    }""")

open(p, "w", encoding="utf-8", newline="").write(s)
print("applied", count, "edits")
