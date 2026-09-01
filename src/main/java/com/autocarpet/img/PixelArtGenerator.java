package com.autocarpet.img;

import com.autocarpet.util.Chat;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

/**
 * 像素画 -> 单层纯地毯投影:
 * 选区按最近邻缩放到输出边长 (不插值, 保持像素风), 每个像素映射为最近颜色地毯,
 * 透明像素 (alpha < 100) 留空气; 生成 X×Z 平面、Y=0 单层的 .litematic, 写入 schematics/momomap。
 */
public final class PixelArtGenerator {
    private PixelArtGenerator() {
    }

    /** 透明阈值: alpha 低于此值视为空气 */
    public static final int ALPHA_THRESHOLD = 100;
    /** 投影作者名 */
    public static final String AUTHOR = "Damomo1";

    /** 生成结果: outSize×outSize 的地毯状态 (索引 = z * outSize + x) + 各色数量统计 */
    public static class Result {
        public final int size;
        public final BlockState[] states;
        /** 每种地毯的数量, 下标与 CarpetPalette 一致 */
        public final int[] counts = new int[CarpetPalette.SIZE];
        public int totalBlocks;

        private Result(int size) {
            this.size = size;
            this.states = new BlockState[size * size];
        }
    }

    /**
     * 把 ARGB 像素数组的正方形选区映射为单层地毯。
     *
     * @param pixels    ARGB 像素 (行优先)
     * @param imgWidth  图片宽
     * @param imgHeight 图片高
     * @param selX      选区左上角 X (图片像素坐标)
     * @param selY      选区左上角 Y (图片像素坐标)
     * @param selSize   选区边长 (图片像素)
     * @param outSize   输出边长 (投影尺寸)
     */
    public static Result generate(int[] pixels, int imgWidth, int imgHeight, int selX, int selY, int selSize, int outSize) {
        Result result = new Result(outSize);
        for (int z = 0; z < outSize; z++) {
            // 最近邻缩放: 输出像素中心对应回选区像素
            int sy = Math.min(imgHeight - 1, selY + (int) ((z + 0.5) * selSize / outSize));
            for (int x = 0; x < outSize; x++) {
                int sx = Math.min(imgWidth - 1, selX + (int) ((x + 0.5) * selSize / outSize));
                int pixel = pixels[sy * imgWidth + sx];
                if ((pixel >>> 24) < ALPHA_THRESHOLD) continue;   // 透明 -> 空气
                int index = CarpetPalette.nearestIndex(pixel);
                result.states[z * outSize + x] = CarpetPalette.state(index);
                result.counts[index]++;
                result.totalBlocks++;
            }
        }
        return result;
    }

    /** momomap 队列目录 (与制图师一致) */
    public static Path momomapDir() {
        return DataManager.getSchematicsBaseDirectory().resolve("momomap");
    }

    /**
     * 把生成结果写成单层 .litematic 投影: 唯一子区域, pos1=(0,0,0), pos2=(size-1,0,size-1), 原点 (0,0,0)。
     * 容器坐标 (x,0,z) 即投影内相对坐标, 与地图画左上角原点约定一致。
     *
     * @return 写入的文件路径
     */
    public static Path writeSchematic(String baseName, Result result) {
        AreaSelection area = new AreaSelection();
        area.setName(baseName);
        area.setExplicitOrigin(BlockPos.ZERO);
        Box box = new Box(BlockPos.ZERO, new BlockPos(result.size - 1, 0, result.size - 1), baseName);
        area.addSubRegionBox(box, true);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, AUTHOR);
        LitematicaBlockStateContainer container = schematic.getSubRegionContainer(baseName);
        for (int z = 0; z < result.size; z++) {
            for (int x = 0; x < result.size; x++) {
                BlockState state = result.states[z * result.size + x];
                if (state == null) continue;   // 空气
                container.set(x, 0, z, state);
            }
        }
        Path dir = momomapDir();
        if (!schematic.writeToFile(dir, baseName, true)) {
            throw new IllegalStateException("litematica 写入失败: " + dir.resolve(baseName + ".litematic"));
        }
        return dir.resolve(baseName + ".litematic");
    }

    /** 数量统计发聊天: 每色一行内的紧凑列表, 超过一组 (>64) 的标红 */
    public static void sendStatsToChat(Result result) {
        StringBuilder builder = new StringBuilder("所需地毯: ");
        for (int i = 0; i < CarpetPalette.SIZE; i++) {
            int count = result.counts[i];
            if (count == 0) continue;
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != ' ') builder.append(' ');
            String piece = com.autocarpet.util.Names.get(CarpetPalette.block(i).asItem()) + "x" + count;
            builder.append(count > 64 ? "§c" + piece + "§7" : piece);
        }
        builder.append(" §8(共").append(result.totalBlocks).append(")");
        Chat.info("%s", builder);
    }
}
