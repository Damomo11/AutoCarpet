p = r"src\main\java\com\autocarpet\img\ImageToSchematicScreen.java"
s = open(p, encoding="utf-8").read()
count = 0

def rep(old, new):
    global s, count
    assert old in s, "NOT FOUND: " + old[:80]
    s = s.replace(old, new, 1)
    count += 1

# 1. 字段: imgScale -> dispScale + 绘制矩形尺寸
rep("""    private int imgDrawX;
    private int imgDrawY;
    private double imgScale;""",
    """    private int imgDrawX;
    private int imgDrawY;
    private int imgDrawWidth;
    private int imgDrawHeight;
    private double dispScale;   // 屏幕像素 / 图片像素""")

# 2. updateImageRect: 按图片尺寸等比缩放 (1930 的图选区坐标才能和显示一一对应)
rep("""    private void updateImageRect() {
        if (this.thumbWidth <= 0) return;
        this.imgScale = Math.min(this.previewSize / (double) this.thumbWidth, this.previewSize / (double) this.thumbHeight);
        int w = (int) Math.round(this.thumbWidth * this.imgScale);
        int h = (int) Math.round(this.thumbHeight * this.imgScale);
        this.imgDrawX = this.previewX + (this.previewSize - w) / 2;
        this.imgDrawY = this.previewY + (this.previewSize - h) / 2;
    }""",
    """    private void updateImageRect() {
        if (this.imgWidth <= 0 || this.imgHeight <= 0) return;
        this.dispScale = Math.min(this.previewSize / (double) this.imgWidth, this.previewSize / (double) this.imgHeight);
        this.imgDrawWidth = Math.max(1, (int) Math.round(this.imgWidth * this.dispScale));
        this.imgDrawHeight = Math.max(1, (int) Math.round(this.imgHeight * this.dispScale));
        this.imgDrawX = this.previewX + (this.previewSize - this.imgDrawWidth) / 2;
        this.imgDrawY = this.previewY + (this.previewSize - this.imgDrawHeight) / 2;
    }""")

# 3. toImagePixel: dispScale
rep("""        int fx = (int) Math.floor((mouseX - this.imgDrawX) / this.imgScale);
        int fy = (int) Math.floor((mouseY - this.imgDrawY) / this.imgScale);""",
    """        int fx = (int) Math.floor((mouseX - this.imgDrawX) / this.dispScale);
        int fy = (int) Math.floor((mouseY - this.imgDrawY) / this.dispScale);""")

# 4. drawImage: 像素 UV 10 参重载 (原版 WinScreen 同款, 语义从字节码验证)
rep("""        int drawWidth = Math.max(1, (int) Math.round(this.thumbWidth * this.imgScale));
        int drawHeight = Math.max(1, (int) Math.round(this.thumbHeight * this.imgScale));
        graphics.blit(this.previewTextureId, this.imgDrawX, this.imgDrawY, drawWidth, drawHeight,
                0.0f, 0.0f, 1.0f, 1.0f);""",
    """        graphics.blit(com.mojang.blaze3d.pipeline.RenderPipelines.GUI_TEXTURED,
                this.previewTextureId,
                this.imgDrawX, this.imgDrawY,
                0.0f, 0.0f,
                this.imgDrawWidth, this.imgDrawHeight,
                this.thumbWidth, this.thumbHeight);""")

# 5. drawSelection: dispScale
rep("""        int x = this.imgDrawX + (int) Math.round(this.selX * this.imgScale);
        int y = this.imgDrawY + (int) Math.round(this.selY * this.imgScale);
        int w = Math.max(2, (int) Math.round(this.selSize * this.imgScale));""",
    """        int x = this.imgDrawX + (int) Math.round(this.selX * this.dispScale);
        int y = this.imgDrawY + (int) Math.round(this.selY * this.dispScale);
        int w = Math.max(2, (int) Math.round(this.selSize * this.dispScale));""")

open(p, "w", encoding="utf-8", newline="").write(s)
print("applied", count, "edits")
