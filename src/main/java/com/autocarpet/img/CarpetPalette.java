package com.autocarpet.img;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 16 色地毯调色板 (RGB 取自 assets/autocarpet/carpet_colors.json 的 normal 档,
 * 即地图画上地毯的实际显示色), 顺序与 {@link com.autocarpet.carto.CartoItems#CARPETS} 一致。
 * 颜色匹配: RGB 欧氏距离最近色。
 */
public final class CarpetPalette {
    private CarpetPalette() {
    }

    public static final int SIZE = 16;

    /** normal 档 RGB (与 carpet_colors.json 一一对应) */
    private static final int[] RGB = {
            0xABABAB,   // white
            0xBA6D2C,   // orange
            0x9941BA,   // magenta
            0x5884BA,   // light_blue
            0xC5C52C,   // yellow
            0x6DB015,   // lime
            0xD06D8E,   // pink
            0x414141,   // gray
            0x848484,   // light_gray
            0x416D84,   // cyan
            0x6D3699,   // purple
            0x2C4199,   // blue
            0x58412C,   // brown
            0x586D2C,   // green
            0x842C2C,   // red
            0x151515,   // black
    };

    private static final Block[] BLOCKS = {
            Blocks.WHITE_CARPET, Blocks.ORANGE_CARPET, Blocks.MAGENTA_CARPET, Blocks.LIGHT_BLUE_CARPET,
            Blocks.YELLOW_CARPET, Blocks.LIME_CARPET, Blocks.PINK_CARPET, Blocks.GRAY_CARPET,
            Blocks.LIGHT_GRAY_CARPET, Blocks.CYAN_CARPET, Blocks.PURPLE_CARPET, Blocks.BLUE_CARPET,
            Blocks.BROWN_CARPET, Blocks.GREEN_CARPET, Blocks.RED_CARPET, Blocks.BLACK_CARPET,
    };

    /** 地毯无属性, defaultBlockState 即唯一状态 */
    public static BlockState state(int index) {
        return BLOCKS[index].defaultBlockState();
    }

    public static Block block(int index) {
        return BLOCKS[index];
    }

    public static int rgb(int index) {
        return RGB[index];
    }

    /** RGB 欧氏距离最近色索引 (忽略 alpha, 透明判定由调用方处理) */
    public static int nearestIndex(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < SIZE; i++) {
            int dr = r - ((RGB[i] >> 16) & 0xFF);
            int dg = g - ((RGB[i] >> 8) & 0xFF);
            int db = b - (RGB[i] & 0xFF);
            long dist = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }
}
