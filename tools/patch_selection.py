p = r"src\main\java\com\autocarpet\img\ImageToSchematicScreen.java"
s = open(p, encoding="utf-8").read()
count = 0

def rep(old, new):
    global s, count
    assert old in s, "NOT FOUND: " + old[:80]
    s = s.replace(old, new, 1)
    count += 1

# 1. UV 修复: 9 参 blit 的 uv 是 0..1 归一化坐标 (之前传了像素值 160 导致条纹乱码)
rep("""        graphics.blit(this.previewTextureId, this.imgDrawX, this.imgDrawY, drawWidth, drawHeight,
                0.0f, 0.0f, (float) this.thumbWidth, (float) this.thumbHeight);""",
    """        graphics.blit(this.previewTextureId, this.imgDrawX, this.imgDrawY, drawWidth, drawHeight,
                0.0f, 0.0f, 1.0f, 1.0f);""")

# 2. 选区改为固定大小: 边长=设置值, 拖动只移动整体
rep("""    // ---- 选区 (图片像素坐标, 正方形) ----
    private int selX;
    private int selY;
    private int selSize;""",
    """    // ---- 选区 (图片像素坐标, 正方形, 大小固定=边长设置) ----
    private int selX;
    private int selY;
    private int selSize;""")

rep("""    private void adjustSize(int delta) {
        int size = this.targetSize + delta;
        try {
            size = Integer.parseInt(this.sizeText.trim()) + delta;
        } catch (NumberFormatException ignored) {
        }
        size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        this.targetSize = size;
        this.sizeText = String.valueOf(size);
        this.sizeBox.setValue(this.sizeText);
    }""",
    """    private void adjustSize(int delta) {
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
    }""")

# 3. 载入/边长变化时应用固定大小
rep("""        this.fitSelection();
        this.updateImageRect();
        this.updatePreviewTexture();""",
    """        this.fitSelection();
        this.updateImageRect();
        this.updatePreviewTexture();""")

# 4. 鼠标交互: 任何拖动都只移动选区 (固定大小)
rep("""    /** 鼠标点是否落在当前选区框内 (屏幕坐标) */
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
    }""",
    """    /** 移动选区 (像素坐标为鼠标位置, 保持抓取偏移, 始终夹回图片内) */
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
            this.dragMove = true;
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
    }""")

# 5. 删除已不用的框选/applyDrag
rep("""    /** 锚点到当前点取正方形 (边长 = 两轴偏移绝对值较大者), 再整体夹回图片内 */
    private void applyDrag(int x, int y) {
        int side = Math.max(1, Math.max(Math.abs(x - this.dragAnchorX), Math.abs(y - this.dragAnchorY)));
        // 正方形左上角: 锚点向拖动方向延伸
        int sx = x >= this.dragAnchorX ? this.dragAnchorX : this.dragAnchorX - side;
        int sy = y >= this.dragAnchorY ? this.dragAnchorY : this.dragAnchorY - side;
        // 夹回图片
        side = Math.max(1, Math.min(side, Math.min(this.imgWidth - sx, this.imgHeight - sy)));
        this.selX = sx;
        this.selY = sy;
        this.selSize = side;
    }

""", "")

# 6. dragAnchor 字段删除
rep("""    private boolean dragMove;    // true=移动已有选区, false=框选新选区
    private int grabOffsetX;
    private int grabOffsetY;""",
    """    private int grabOffsetX;
    private int grabOffsetY;""")
rep("""    // ---- 拖选 ----
    private boolean dragging;
    private int dragAnchorX;
    private int dragAnchorY;
""",
    """    // ---- 拖选 ----
    private boolean dragging;
""")

# 7. fitSelection 保留居中语义但统一走 applySelectionSize
rep("""    /** 自动取图中心的正方形选区, 边长 = 边长框的值 (不超过图片) */
    private void fitSelection() {
        if (this.pixels == null) return;
        int side = Math.min(this.targetSize, Math.min(this.imgWidth, this.imgHeight));
        this.selSize = Math.max(1, side);
        this.selX = (this.imgWidth - this.selSize) / 2;
        this.selY = (this.imgHeight - this.selSize) / 2;
    }""",
    """    /** 自动取图中心的正方形选区, 边长 = 边长框的值 (不超过图片) */
    private void fitSelection() {
        if (this.pixels == null) return;
        this.applySelectionSize();
        this.selX = (this.imgWidth - this.selSize) / 2;
        this.selY = (this.imgHeight - this.selSize) / 2;
    }""")

open(p, "w", encoding="utf-8", newline="").write(s)
print("applied", count, "edits")
