package com.autocarpet.debug;

import com.autocarpet.AutoCarpet;
import com.autocarpet.carto.CartographerModule;
import com.autocarpet.util.Chat;
import com.autocarpet.util.FindItemResult;
import com.autocarpet.util.InvUtils;
import com.autocarpet.util.PlaceUtils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 临时调试命令 (/acdebug): 排查"补放铁砧"一直失败的原因。
 * 客户端本地命令, 不会发到服务器。全部输出走 Chat (聊天栏)。
 * <p>
 * /acdebug info           查看 anvilPos、铁砧位方块/邻居状态、canPlaceBlock、玩家距离、bot 前置条件
 * /acdebug place [x y z]  用与 bot 完全相同的代码路径手动放一次铁砧 (默认放 anvilPos)
 * /acdebug scan           重新扫描 mapCenter 附近的铁砧, 列出全部坐标
 */
public final class CartoDebugCommands {
    private static final Minecraft mc = Minecraft.getInstance();

    private CartoDebugCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("acdebug")
                        .then(ClientCommands.literal("info").executes(ctx -> {
                            dumpInfo();
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommands.literal("scan").executes(ctx -> {
                            scanAnvils();
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommands.literal("place")
                                .executes(ctx -> {
                                    placeAnvil(null);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            placeAnvil(new BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z")));
                                                            return Command.SINGLE_SUCCESS;
                                                        })))))));
    }

    private static CartographerModule cartographer() {
        for (var module : AutoCarpet.get().modules) {
            if (module instanceof CartographerModule carto) return carto;
        }
        return null;
    }

    // ==================================================================
    // /acdebug info
    // ==================================================================

    private static void dumpInfo() {
        CartographerModule carto = cartographer();
        if (notReady()) return;
        Chat.info("[调试] ============ 铁砧放置诊断 ============");
        Chat.info("[调试] 制图师: %s, 状态=%s", carto != null && carto.isEnabled() ? "开启(建议先关闭再手动测)" : "关闭",
                carto != null ? carto.debugState() : "-");
        Chat.info("[调试] mapCenter=%s", pos(carto != null ? carto.debugMapCenter() : null));
        BlockPos anvilPos = carto != null ? carto.debugAnvilPos() : null;
        if (anvilPos == null) {
            Chat.warning("[调试] anvilPos=null (未初始化, 或初始化时范围内没有铁砧)");
            return;
        }
        Chat.info("[调试] anvilPos=%s  anvilPlaced=%s", pos(anvilPos), carto.debugAnvilPlaced());
        BlockState state = mc.level.getBlockState(anvilPos);
        Chat.info("[调试] 铁砧位方块=%s canBeReplaced=%s 流体=%s",
                id(state), state.canBeReplaced(), state.getFluidState().isEmpty() ? "无" : "有");
        if (!state.canBeReplaced()) {
            Chat.warning("[调试] 铁砧位被上面的方块占着, bot 的放置包服务器必拒 (若是地毯等先清掉)");
        }
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = anvilPos.relative(dir);
            BlockState ns = mc.level.getBlockState(neighbor);
            Chat.info("[调试] 邻居 %s: %s canBeReplaced=%s", dir.getName(), id(ns), ns.canBeReplaced());
        }
        Chat.info("[调试] canPlaceBlock: 仅自身=%s, 要求可点击邻块=%s (false 时包根本不发)",
                PlaceUtils.canPlaceBlock(anvilPos, false, Blocks.ANVIL),
                PlaceUtils.canPlaceBlock(anvilPos, true, Blocks.ANVIL));
        // 玩家 / 距离
        Vec3 eye = mc.player.getEyePosition();
        BlockPos below = anvilPos.relative(Direction.DOWN);
        Vec3 hitVec = new Vec3(below.getX() + 0.5, below.getY() + 0.95, below.getZ() + 0.5);
        Chat.info("[调试] 玩家=%s 眼睛=%s", pos(mc.player.blockPosition()), eye);
        Chat.info("[调试] 眼睛到实际点击点距离=%.2f (原版上限 4.5, 超了服务器拒收)", eye.distanceTo(hitVec));
        Chat.info("[调试] 玩家碰撞箱与铁砧位相交=%s", mc.player.getBoundingBox().intersects(new AABB(anvilPos)));
        // bot 自己的前置条件 (onName 里的 nearContainer(raiseToPlayerY(anvilPos)))
        BlockPos stand = anvilPos.getY() < mc.player.getBlockY()
                ? new BlockPos(anvilPos.getX(), mc.player.getBlockY(), anvilPos.getZ()) : anvilPos;
        boolean near = Math.abs(mc.player.getBlockX() - stand.getX()) <= 1
                && Math.abs(mc.player.getBlockY() - stand.getY()) <= 1
                && Math.abs(mc.player.getBlockZ() - stand.getZ()) <= 1;
        Chat.info("[调试] bot 前置 nearContainer(stand=%s)=%s (false 时 bot 只会走过去, 不会放)", pos(stand), near);
        // 物品
        FindItemResult anvil = InvUtils.find(Items.ANVIL);
        Chat.info("[调试] 手持=%s  背包铁砧=%s",
                idOf(mc.player.getMainHandItem()),
                anvil.found() ? "槽位 " + anvil.slot + " x" + anvil.count() : "未找到");
    }

    // ==================================================================
    // /acdebug place [x y z]
    // ==================================================================

    private static void placeAnvil(BlockPos target) {
        CartographerModule carto = cartographer();
        if (notReady()) return;
        if (carto != null && carto.isEnabled()) {
            Chat.warning("[调试] 制图师开启中, 状态机也在操作, 建议先右 Ctrl 关闭再测");
        }
        BlockPos pos = target != null ? target : (carto != null ? carto.debugAnvilPos() : null);
        if (pos == null) {
            Chat.warning("[调试] 未指定坐标且 anvilPos=null, 用 /acdebug place <x> <y> <z>");
            return;
        }
        Chat.info("[调试] ====== 手动放铁砧 -> %s ======", pos(pos));
        BlockState state = mc.level.getBlockState(pos);
        Chat.info("[调试] 目标方块=%s canBeReplaced=%s", id(state), state.canBeReplaced());
        if (!state.canBeReplaced()) {
            Chat.warning("[调试] 目标格不可替换, 放置不会成功");
        }
        boolean canSelf = PlaceUtils.canPlaceBlock(pos, false, Blocks.ANVIL);
        boolean canNeighbor = PlaceUtils.canPlaceBlock(pos, true, Blocks.ANVIL);
        Chat.info("[调试] canPlaceBlock: 仅自身=%s, 要求可点击邻块=%s (仅作参考, 实际放置已跳过邻块检查)", canSelf, canNeighbor);
        if (!canSelf) {
            Chat.warning("[调试] 目标格本身不可替换, 包不会发");
        }
        FindItemResult anvil = InvUtils.find(Items.ANVIL);
        if (!anvil.found()) {
            Chat.warning("[调试] 背包没有铁砧, 中止");
            return;
        }
        Chat.info("[调试] 背包铁砧: 槽位=%s x%s", anvil.slot, anvil.count());
        int before = InvUtils.selectedIndex();
        InvUtils.swapWithSelected(anvil.slot);
        Chat.info("[调试] 换手: 槽%s -> 槽%s, 换手后手持=%s", before, InvUtils.selectedIndex(),
                idOf(mc.player.getMainHandItem()));
        boolean sent = PlaceUtils.placeFromHotbar(pos, mc.player.getInventory().getSelectedSlot(), false, Direction.DOWN);
        if (sent) {
            Chat.info("[调试] 已发送放置包 (点击 %s 的 UP 面), 等 2-3 秒后 /acdebug info 看该格是否变成铁砧",
                    pos(pos.relative(Direction.DOWN)));
            Chat.info("[调试] 若没变成铁砧 = 服务器拒收了包 (反作弊/碰撞), 不是客户端没发");
        } else {
            Chat.warning("[调试] placeFromHotbar 返回 false, 包没有发出 (看上面的 canPlaceBlock 日志)");
        }
    }

    // ==================================================================
    // /acdebug scan
    // ==================================================================

    private static void scanAnvils() {
        CartographerModule carto = cartographer();
        if (notReady()) return;
        BlockPos center = carto != null ? carto.debugMapCenter() : null;
        if (center == null) {
            Chat.warning("[调试] 尚未初始化 (mapCenter=null), 改以玩家位置为中心扫描");
            center = mc.player.blockPosition();
        }
        Chat.info("[调试] ====== 扫描铁砧: 中心=%s, dx/dz ±8, dy -2..+1 ======", pos(center));
        int count = 0;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -8; dx < 8; dx++) {
                for (int dz = -8; dz < 8; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState s = mc.level.getBlockState(p);
                    if (s.getBlock() instanceof AnvilBlock) {
                        count++;
                        Chat.info("[调试] 铁砧: %s (%s)", pos(p), id(s));
                    }
                }
            }
        }
        if (count == 0) {
            Chat.warning("[调试] 范围内没有铁砧");
        } else {
            Chat.info("[调试] 共找到 %s 个", count);
        }
        if (carto != null) {
            BlockPos anvilPos = carto.debugAnvilPos();
            Chat.info("[调试] 当前 anvilPos=%s (只在重新初始化时更新)", anvilPos == null ? "null" : pos(anvilPos));
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static boolean notReady() {
        if (mc.player == null || mc.level == null) {
            Chat.warning("[调试] 游戏未就绪");
            return true;
        }
        return false;
    }

    private static String pos(BlockPos pos) {
        return pos == null ? "null" : "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String idOf(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
