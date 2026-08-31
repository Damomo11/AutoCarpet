package com.autocarpet.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 方块放置/交互工具 (移植自 Lotus ap_0 + av_0 中制图师与打印机用到的部分)。
 * 旋转通过 ServerboundMovePlayerPacket.PosRot 包同步, 交互通过 mc.gameMode.useItemOn。
 */
public class PlaceUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    // ------------------------------------------------------------------
    // 旋转 (Lotus av_0)
    // ------------------------------------------------------------------

    /** 计算看向目标点的 yaw/pitch (Lotus av_0.c(Vec3)) */
    public static float[] rotationsTo(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new float[]{yaw, pitch};
    }

    /**
     * 静默旋转 (Lotus bk.a(float,float)): 只发送 ServerboundMovePlayerPacket$PosRot 数据包,
     * 不修改玩家真实视角 (不调 setYRot/setYHeadRot/setXRot), 放置/破坏/开容器专用。
     */
    public static void packetRotate(float yaw, float pitch) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
    }

    public static void packetRotate(Vec3 target) {
        float[] r = rotationsTo(target);
        packetRotate(r[0], r[1]);
    }

    /** 旋转到方块上的点击点 (Lotus av_0.a(BlockPos, Direction)) */
    public static void packetRotate(BlockPos pos, Direction side) {
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3i step = side.getUnitVec3i();
        packetRotate(center.add(step.getX() * 0.5, step.getY() * 0.5, step.getZ() * 0.5));
    }

    // ------------------------------------------------------------------
    // 基础交互 (Lotus ap_0)
    // ------------------------------------------------------------------

    /** 方块是否可交互 (右键会打开界面) */
    public static boolean isInteractiveBlock(Block block) {
        return block instanceof ChestBlock || block instanceof EnderChestBlock
                || block instanceof CraftingTableBlock || block instanceof FurnaceBlock
                || block instanceof AnvilBlock || block instanceof BrewingStandBlock
                || block instanceof HopperBlock || block instanceof DispenserBlock
                || block instanceof EnchantingTableBlock || block instanceof ShulkerBoxBlock
                || block instanceof BarrelBlock || block instanceof BedBlock
                || block instanceof TrapDoorBlock;
    }

    /** BlockState 是否 "可放置目标" (非空气且无流体); Lotus ap_0.b(BlockState) */
    public static boolean isSolid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** Lotus ap_0.a(BlockState) = !b(state) */
    public static boolean isClickable(BlockState state) {
        return !isSolid(state);
    }

    /** 方块上方是否有空间 (Lotus ap_0.e(BlockPos)) */
    public static boolean hasSpaceAbove(BlockPos pos) {
        BlockPos above = pos.above();
        return mc.level.getBlockState(above).getCollisionShape(mc.level, above).isEmpty();
    }

    /** 方块状态可被替换 (Lotus ap_0.b(BlockPos)) */
    public static boolean canBeReplaced(BlockPos pos) {
        return mc.level.getBlockState(pos).canBeReplaced();
    }

    /** Lotus ap_0.a(BlockPos[, boolean]): 是否可交互点击 (形状非空, 可交互方块需要潜行) */
    public static boolean canInteract(BlockPos pos, boolean ignoreInteractive) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.getShape(mc.level, pos).isEmpty()) return false;
        if (ignoreInteractive) return true;
        if (isInteractiveBlock(state.getBlock())) return mc.player.isShiftKeyDown();
        return true;
    }

    public static boolean canInteract(BlockPos pos) {
        return canInteract(pos, false);
    }

    /** 射线是否畅通 (Lotus ap_0.a(Vec3, Direction)) */
    public static boolean isLineClear(Vec3 target, Direction side) {
        if (side == null) return false;
        BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }

    /** 对指定点执行 useItemOn + 挥手 (Lotus ap_0.a(BlockHitResult)) */
    public static boolean interact(BlockHitResult hit) {
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        boolean sneaking = isInteractiveBlock(state.getBlock()) && !mc.player.isShiftKeyDown();
        if (sneaking) mc.player.setShiftKeyDown(true);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
        if (sneaking) mc.player.setShiftKeyDown(false);
        return result.consumesAction();
    }

    /** 在 (pos, side, hitVec) 处执行一次 useItemOn (Lotus ap_0.a(BlockPos, Direction, Vec3)) */
    public static void interact(BlockPos pos, Direction side, Vec3 hitVec) {
        BlockHitResult hit = new BlockHitResult(hitVec, side, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    // ------------------------------------------------------------------
    // 打开容器 (Lotus ap_0.c / ap_0.a(BlockPos, Direction))
    // ------------------------------------------------------------------

    /** 自动选面打开容器 (Lotus ap_0.c(BlockPos)) */
    public static void openAuto(BlockPos pos) {
        Direction side = chooseSideByAir(pos);
        if (side != null) {
            openFacing(pos, side);
        } else {
            Chat.warning("无法找到可以打开容器的面");
        }
    }

    /** 朝指定面旋转并点击打开 (Lotus ap_0.a(BlockPos, Direction)) */
    public static void openFacing(BlockPos pos, Direction side) {
        packetRotate(pos, side);
        Vec3i step = side.getUnitVec3i();
        Vec3 hitVec = new Vec3(pos.getX() + 0.5 + step.getX() * 0.45,
                pos.getY() + 0.5 + step.getY() * 0.45,
                pos.getZ() + 0.5 + step.getZ() * 0.45);
        interact(pos, side, hitVec);
    }

    /** 用最佳面打开方块 (u_0.a(BlockPos, null): BlockUtils.getDirection + 点击) */
    public static void openBestSide(BlockPos pos) {
        Direction side = getDirectionEye(pos);
        if (side == null) side = chooseSideByAir(pos);
        if (side == null) return;
        packetRotate(pos, side);
        Vec3i step = side.getUnitVec3i();
        Vec3 hitVec = new Vec3(pos.getX() + 0.5 + step.getX() * 0.45,
                pos.getY() + 0.5 + step.getY() * 0.45,
                pos.getZ() + 0.5 + step.getZ() * 0.45);
        interact(pos, side, hitVec);
    }

    // ------------------------------------------------------------------
    // 面选择 (Lotus ap_0.j / h / a(BlockPos,double,double))
    // ------------------------------------------------------------------

    /** 与眼睛方位相关的面集合 (Lotus ap_0.a(Vec3, Vec3)) */
    public static Set<Direction> sidesToward(Vec3 eye, Vec3 target) {
        Set<Direction> set = new HashSet<>(6);
        double dx = eye.x - target.x;
        double dy = eye.y - target.y;
        double dz = eye.z - target.z;
        if (dy > 0.5) set.add(Direction.UP);
        else if (dy < -0.5) set.add(Direction.DOWN);
        else {
            set.add(Direction.UP);
            set.add(Direction.DOWN);
        }
        if (dx > 0.5) set.add(Direction.EAST);
        else if (dx < -0.5) set.add(Direction.WEST);
        else {
            set.add(Direction.EAST);
            set.add(Direction.WEST);
        }
        if (dz > 0.5) set.add(Direction.SOUTH);
        else if (dz < -0.5) set.add(Direction.NORTH);
        else {
            set.add(Direction.SOUTH);
            set.add(Direction.NORTH);
        }
        return set;
    }

    /** 找一个空气侧面来打开方块 (Lotus ap_0.j(BlockPos)) */
    public static Direction chooseSideByAir(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Set<Direction> sides = sidesToward(eye, pos.getCenter());
        Direction bestAir = null;
        double bestAirDist = Double.MAX_VALUE;
        Direction bestReplaceable = null;
        double bestReplaceableDist = Double.MAX_VALUE;
        for (Direction side : sides) {
            BlockPos neighbor = pos.relative(side);
            BlockState neighborState = mc.level.getBlockState(neighbor);
            Vec3 faceCenter = pos.getCenter().add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
            double dist = eye.distanceToSqr(faceCenter);
            if (neighborState.isAir()) {
                if (dist < bestAirDist) {
                    bestAirDist = dist;
                    bestAir = side;
                }
            } else if (neighborState.canBeReplaced() && dist < bestReplaceableDist) {
                bestReplaceableDist = dist;
                bestReplaceable = side;
            }
        }
        return bestAir != null ? bestAir : bestReplaceable;
    }

    /** 找最佳可点击面: 面向玩家、邻块实心、射线畅通 (Lotus ap_0.h(BlockPos)) */
    public static Direction getDirectionEye(BlockPos pos) {
        Set<Direction> sides = sidesToward(mc.player.getEyePosition(), pos.getCenter());
        for (Direction dir : Direction.values()) {
            BlockState neighborState = mc.level.getBlockState(pos.relative(dir));
            if (neighborState.isAir() || neighborState.getBlock() instanceof LiquidBlock) continue;
            if (isInteractiveBlock(neighborState.getBlock()) && !mc.player.isShiftKeyDown()) continue;
            if (!sides.contains(dir.getOpposite())) continue;
            return dir.getOpposite();
        }
        return null;
    }

    /** 找放置时的点击面: 附近可点击方块、可见、距离限制内 (Lotus be.a(BlockPos, double, double)) */
    public static Direction findPlaceSide(BlockPos pos, double maxDistance) {
        double maxDistSq = maxDistance * maxDistance;
        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();
        Set<Direction> sides = sidesToward(eye, pos.getCenter());
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (!sides.contains(dir.getOpposite())) continue;
            if (!canInteract(neighbor)) continue;   // 原版 be.a(邻块): 有碰撞箱且交互方块需潜行
            if (mc.level.getBlockState(neighbor).canBeReplaced()) continue;   // 原版 be.b(邻块): 可替换方块(空气/水等)不能作为点击面
            Vec3 hitPoint = faceHitVec(neighbor, dir.getOpposite());
            if (!isLineClear(hitPoint, dir.getOpposite())) continue;
            double distSq = eye.distanceToSqr(hitPoint);
            if (Mth.sqrt((float) distSq) > maxDistance || distSq >= bestDist) continue;
            best = dir;
            bestDist = distSq;
        }
        return best;
    }

    /** 方块 pos 上 side 面的中心点 (Lotus ap_0.f(BlockPos, Direction)) */
    public static Vec3 faceHitVec(BlockPos pos, Direction side) {
        VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        if (shape.isEmpty()) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        AABB box = shape.bounds();
        double halfX = (box.maxX - box.minX) * 0.5;
        double halfY = (box.maxY - box.minY) * 0.5;
        double halfZ = (box.maxZ - box.minZ) * 0.5;
        double x = pos.getX() + box.minX + halfX;
        double y = pos.getY() + box.minY + halfY;
        double z = pos.getZ() + box.minZ + halfZ;
        return new Vec3(x + side.getStepX() * halfX, y + side.getStepY() * halfY, z + side.getStepZ() * halfZ);
    }

    // ------------------------------------------------------------------
    // 放置
    // ------------------------------------------------------------------

    /** meteor BlockUtils.canPlaceBlock 等价实现 */
    public static boolean canPlaceBlock(BlockPos pos, boolean checkNeighbour, Block block) {
        BlockState state = mc.level.getBlockState(pos);
        if (!state.canBeReplaced()) return false;
        if (!mc.level.getWorldBorder().isWithinBounds(pos)) return false;
        if (checkNeighbour) {
            for (Direction dir : Direction.values()) {
                if (canPlaceBlock(pos.relative(dir), false, block)) return true;
            }
            return false;
        }
        return true;
    }

    /** 从快捷栏槽位在 pos 放置方块 (Lotus ap_0.a(BlockPos, int, boolean, Direction)) */
    public static boolean placeFromHotbar(BlockPos pos, int hotbarSlot, boolean checkNeighbour, Direction clickSide) {
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        Block block = net.minecraft.world.level.block.Blocks.OBSIDIAN;
        Inventory inv = mc.player.getInventory();
        ItemStack stack = inv.getItem(hotbarSlot);
        if (stack.getItem() instanceof BlockItem blockItem) {
            block = blockItem.getBlock();
        }
        if (!canPlaceBlock(pos, checkNeighbour, block)) return false;
        BlockPos neighbor = pos.relative(clickSide);
        Vec3i step = clickSide.getOpposite().getUnitVec3i();
        Vec3 hitVec = new Vec3(neighbor.getX() + 0.5 + step.getX() * 0.45,
                neighbor.getY() + 0.5 + step.getY() * 0.45,
                neighbor.getZ() + 0.5 + step.getZ() * 0.45);
        BlockHitResult hit = new BlockHitResult(hitVec, clickSide.getOpposite(), neighbor, false);
        if (inv.getSelectedSlot() != hotbarSlot) {
            InvUtils.swapSlots(hotbarSlot, inv.getSelectedSlot());
        }
        packetRotate(hitVec);
        interact(hit);
        return true;
    }

    /** 通过邻块的面把方块放进 pos (Lotus ap_0.b(BlockPos, Direction): 点邻块背面) */
    public static boolean placeViaNeighbor(BlockPos pos, Direction side) {
        BlockPos neighbor = pos.relative(side);
        return placeOnFace(neighbor, side.getOpposite());
    }

    /** 点击 pos 的 side 面, 命中点取面中心 (Lotus ap_0.c(BlockPos, Direction)) */
    public static boolean placeOnFace(BlockPos pos, Direction side) {
        Vec3 hitVec = faceHitVec(pos, side);
        packetRotate(hitVec);
        return interact(new BlockHitResult(hitVec, side, pos, false));
    }

    /** 点击 pos 的 side 面, 命中点 = pos 中心 + offset (Lotus ap_0.b(BlockPos, Direction, Vec3)) */
    public static boolean placeOnFace(BlockPos pos, Direction side, Vec3 offset) {
        Vec3 hitVec = pos.getCenter().add(offset);
        packetRotate(hitVec);
        return interact(new BlockHitResult(hitVec, side, pos, false));
    }

    /** 自动找面放置 (Lotus ap_0.d(BlockPos)) */
    public static boolean placeAuto(BlockPos pos) {
        Direction side = getDirectionEye(pos);
        if (side == null) return false;
        BlockPos neighbor = pos.relative(side.getOpposite());
        return placeOnFace(neighbor, side);
    }

    /** 半砖放置 (Lotus ap_0.a(BlockPos, BlockState)) */
    public static void placeSlab(BlockPos pos, BlockState state) {
        if (!state.getProperties().contains(BlockStateProperties.SLAB_TYPE)) {
            placeAuto(pos);
            return;
        }
        SlabType slabType = state.getValue(BlockStateProperties.SLAB_TYPE);
        Direction side = chooseSlabSide(pos, slabType == SlabType.TOP);
        if (side == null) return;
        BlockPos neighbor = pos.relative(side.getOpposite());
        if (slabType == SlabType.TOP || slabType == SlabType.BOTTOM) {
            placeOnFace(neighbor, side);
        } else if (slabType == SlabType.DOUBLE) {
            placeOnFace(neighbor, side, new Vec3(0.0, 0.25, 0.0));
        } else {
            placeAuto(pos);
        }
    }

    /** 楼梯放置 (Lotus ap_0.b(BlockPos, BlockState)): 命中点偏移到目标半格, 玩家朝向 = 方块 facing */
    public static void placeStair(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Half half = state.getValue(BlockStateProperties.HALF);
        if (half == Half.TOP) {
            Direction side = chooseSlabSide(pos, true);
            if (side == null) return;
            placeWithOffset(pos.relative(side.getOpposite()), pos, side, facing, new Vec3(0.0, 0.25, 0.0));
        } else {
            Direction side = chooseSlabSide(pos, false);
            if (side == null) return;
            placeWithOffset(pos.relative(side.getOpposite()), pos, side, facing, new Vec3(0.0, -0.25, 0.0));
        }
    }

    /**
     * Lotus ap_0.a(BlockPos clickPos, BlockPos hitSource, Direction face, Direction clickSide, Vec3 offset):
     * 命中点 = hitSource 中心 + offset (带视线校正), 玩家朝向 clickSide。
     */
    public static boolean placeWithOffset(BlockPos clickPos, BlockPos hitSource, Direction face, Direction clickSide, Vec3 offset) {
        Vec3 hitVec = adjustHitForEye(hitSource.getCenter().add(offset), face);
        packetRotate(yawFor(clickSide), clickSide == Direction.UP ? -90.0f : clickSide == Direction.DOWN ? 90.0f : 5.0f);
        return interact(new BlockHitResult(hitVec, face, clickPos, false));
    }

    /** 带朝向方块 (FACING/HORIZONTAL_FACING/FACING_HOPPER) 的放置 (Lotus af.a(...)) */
    public static void placeWithFacing(BlockState state, Block block, BlockPos pos,
                                       Property<Direction> property, Direction fallbackSide) {
        Direction target = state.getValue(property);
        boolean observerLike = block == net.minecraft.world.level.block.Blocks.OBSERVER
                || block == net.minecraft.world.level.block.Blocks.HOPPER;
        Direction clickSide = observerLike ? target : target.getOpposite();
        BlockPos neighbor;
        Direction face;
        if (fallbackSide != null) {
            neighbor = pos.relative(fallbackSide);
            face = fallbackSide.getOpposite();
        } else {
            Direction side = getDirectionEye(pos);
            if (side == null) return;
            neighbor = pos.relative(side.getOpposite());
            face = side;
        }
        Vec3i step = face.getUnitVec3i();
        placeWithOffset(neighbor, neighbor, face, clickSide,
                new Vec3(step.getX() * 0.5, step.getY() * 0.5, step.getZ() * 0.5));
    }

    /** 半砖用面选择 (Lotus ap_0.b(BlockPos, boolean)) */
    public static Direction chooseSlabSide(BlockPos pos, boolean top) {
        Set<Direction> sides = sidesToward(mc.player.getEyePosition(), pos.getCenter());
        if (sides.remove(Direction.DOWN) && top && mc.level.getBlockState(pos.above()).canBeReplaced()) {
            return Direction.DOWN;
        }
        if (sides.remove(Direction.UP) && !top && mc.level.getBlockState(pos.below()).canBeReplaced()) {
            return Direction.UP;
        }
        for (Direction side : sides) {
            BlockState neighborState = mc.level.getBlockState(pos.relative(side.getOpposite()));
            if (!neighborState.canBeReplaced()) continue;
            if (neighborState.getBlock() instanceof SlabBlock) {
                SlabType neighborSlab = neighborState.getValue(BlockStateProperties.SLAB_TYPE);
                if (neighborSlab != SlabType.DOUBLE && (neighborSlab != SlabType.BOTTOM || top)
                        && (neighborSlab != SlabType.TOP || !top)) continue;
                return side;
            }
            return side;
        }
        return null;
    }

    /** 视线校正: 目标点被挡时在非面轴向上搜索可见替代点 (Lotus ap_0.a(Vec3, Direction, boolean)) */
    public static Vec3 adjustHitForEye(Vec3 target, Direction face) {
        Vec3 eye = mc.player.getEyePosition();
        if (isLineClear(target, face)) return target;
        double offX = face.getAxis() == Direction.Axis.X ? 0.0 : 0.25;
        double offY = face.getAxis() == Direction.Axis.Y ? 0.0 : 0.25;
        double offZ = face.getAxis() == Direction.Axis.Z ? 0.0 : 0.25;
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (double ox : new double[]{-offX, 0.0, offX}) {
            for (double oy : new double[]{-offY, 0.0, offY}) {
                for (double oz : new double[]{-offZ, 0.0, offZ}) {
                    if (ox == 0.0 && oy == 0.0 && oz == 0.0) continue;
                    Vec3 candidate = target.add(ox, oy, oz);
                    if (!isLineClear(candidate, face)) continue;
                    double distSq = eye.distanceToSqr(candidate);
                    if (distSq < bestDist) {
                        bestDist = distSq;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** Lotus ap_0.a(Direction): 朝向对应 yaw */
    public static float yawFor(Direction side) {
        if (side == null) return 0.0f;
        return switch (side) {
            case NORTH -> 180.0f;
            case WEST -> 90.0f;
            case EAST -> -90.0f;
            default -> 0.0f;
        };
    }

    // ------------------------------------------------------------------
    // 其他 (Lotus ap_0)
    // ------------------------------------------------------------------

    /** 是否容器方块实体 (Lotus ap_0.i(BlockPos)) */
    public static boolean isContainer(BlockPos pos) {
        BlockEntity be = mc.level.getBlockEntity(pos);
        return be instanceof net.minecraft.world.Container;
    }

    /** 站立位置搜索 (Lotus ap_0.a(BlockPos, int)): 附近可站立的空气位 */
    public static BlockPos findStandableNear(BlockPos center, int range) {
        for (int x = center.getX() - range; x <= center.getX() + range; x++) {
            for (int z = center.getZ() - range; z <= center.getZ() + range; z++) {
                BlockPos pos = new BlockPos(x, center.getY(), z);
                if (isStandable(pos)) return pos;
            }
        }
        return null;
    }

    public static boolean isStandable(BlockPos pos) {
        return mc.level.getBlockState(pos).isAir() && mc.level.getBlockState(pos.above()).isAir();
    }

    /** 立方体范围点 (Lotus ap_0.a(int, BlockPos)) */
    public static List<BlockPos> spherePositions(int range, BlockPos center) {
        Vec3 centerVec = center.getCenter();
        List<BlockPos> list = new ArrayList<>();
        for (int x = center.getX() - range; x < center.getX() + range; x++) {
            for (int z = center.getZ() - range; z < center.getZ() + range; z++) {
                for (int y = center.getY() - range; y < center.getY() + range; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (pos.getCenter().distanceTo(centerVec) > range || list.contains(pos)) continue;
                    list.add(pos);
                }
            }
        }
        return list;
    }
}
