package com.autocarpet.carto;

import com.autocarpet.util.Chat;
import com.autocarpet.util.FindItemResult;
import com.autocarpet.util.InvUtils;
import com.autocarpet.util.PlaceUtils;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 投影打印机 (移植自 Lotus af 打印机的「投影打印」模式, 供制图师调用)。
 * 从 litematica 原理图世界读取目标方块, 用 gameMode.useItemOn 放置,
 * 含放置范围(4.5 格内可达性)、朝向、失败冷却与防抖。
 */
public class CarpetPrinter {
    private static final Logger LOG = LoggerFactory.getLogger(CarpetPrinter.class);
    private static final Minecraft mc = Minecraft.getInstance();

    private final CartographerModule module;

    private boolean active;
    private WorldSchematic schematicWorld;
    /** 制图师提供的候选位置来源 (每 tick 重新计算) */
    private Supplier<List<BlockPos>> supplier;
    /** 已选好面的待放置目标 (af.w) */
    private final List<PrintTarget> pending = new ArrayList<>();
    /** 位置 -> 放置失败冷却 (af.v) */
    private final Map<BlockPos, Integer> cooldowns = new HashMap<>();
    /** 扫描轮转游标 (af.x) */
    private int scanCursor;

    public CarpetPrinter(CartographerModule module) {
        this.module = module;
    }

    public boolean isActive() {
        return this.active;
    }

    /** 激活 (Lotus aw_0.e / af.a): 若未激活则开启, 并获取原理图世界 */
    public void activate() {
        this.schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (this.schematicWorld == null) {
            Chat.warning("未加载投影");
            this.active = false;
            return;
        }
        this.active = true;
    }

    /** 停用 (Lotus aw_0.f / af.onDeactivate) */
    public void deactivate() {
        if (this.active) {
            this.active = false;
        }
        this.reset();
    }

    /** 重置计数与队列 (Lotus af.b) */
    public void reset() {
        this.pending.clear();
        this.cooldowns.clear();
        this.scanCursor = 0;
    }

    public void setSupplier(Supplier<List<BlockPos>> supplier) {
        this.supplier = supplier;
    }

    /** 每 tick 驱动 (Lotus af.onTickPost) */
    public void tick() {
        if (!this.active) return;
        this.cooldowns.replaceAll((pos, ticks) -> ticks - 1);
        this.cooldowns.entrySet().removeIf(entry -> entry.getValue() <= 0);
        if (mc.player == null || mc.level == null) return;
        try {
            List<PrintTarget> list = this.collectTargets();
            this.scan(list);
            this.place();
        } catch (Exception exception) {
            LOG.error("printer tick error", exception);
            Chat.error("打印出错: %s", exception.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 收集目标 (Lotus af.z, 投影打印模式)
    // ------------------------------------------------------------------

    private List<PrintTarget> collectTargets() {
        double range = module.printerRange.get();
        int intRange = (int) Math.ceil(range);
        double maxDistSq = range * range;
        ArrayList<PrintTarget> result = new ArrayList<>();
        List<BlockPos> positions;
        if (this.supplier != null) {
            positions = this.supplier.get();
        } else {
            positions = new ArrayList<>(intRange * intRange * intRange * 4);
            BlockPos center = mc.player.blockPosition();
            for (int dy = -intRange; dy < intRange; dy++) {
                for (int dz = -intRange; dz <= intRange; dz++) {
                    for (int dx = -intRange; dx <= intRange; dx++) {
                        positions.add(center.offset(dx, dy, dz));
                    }
                }
            }
        }
        if (positions == null) positions = Collections.emptyList();
        for (BlockPos pos : positions) {
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;
            double distSq = mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
            if (distSq > maxDistSq) continue;
            PrintTarget target = new PrintTarget(pos, distSq);
            target.states = Collections.singletonList(this.schematicWorld.getBlockState(pos));
            target.current = mc.level.getBlockState(pos);
            result.add(target);
        }
        if (this.supplier == null) {
            if (module.printerNearest.get()) {
                result.sort((a, b) -> Double.compare(a.dist, b.dist));
            } else {
                result.sort((a, b) -> Double.compare(b.dist, a.dist));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 扫描: 挑出本 tick 要放的位置 (Lotus af.a(List<ab_0>))
    // ------------------------------------------------------------------

    private void scan(List<PrintTarget> list) {
        if (!this.pending.isEmpty() || list.isEmpty()) return;
        int budget = module.faceCheckLimit.get();
        int size = list.size();
        int advanced = 0;
        for (int i = 0; i < size && budget > 0; i++) {
            PrintTarget target = list.get((this.scanCursor + i) % size);
            advanced++;
            BlockPos pos = target.pos;
            if (pos.getY() > DataManager.getRenderLayerRange().getLayerMax()) continue;
            BlockState current = target.current;
            if (PlaceUtils.isSolid(current)) continue;
            if ((module.placeTimeout.get() != 0 && this.cooldowns.containsKey(pos)) || !current.canBeReplaced()) continue;
            for (BlockState state : target.states) {
                if (state == null || state.isAir()) continue;
                Block block = state.getBlock();
                if (!(block.asItem() instanceof BlockItem)) continue;
                if (!hasItem(block.asItem()) || entityBlocks(pos, state)) continue;
                budget--;
                Direction side = PlaceUtils.findPlaceSide(pos, module.printerRange.get());
                if (side == null) break;
                target.side = side;
                this.pending.add(target);
                break;
            }
            if (this.pending.size() >= module.printerCount.get()) break;
        }
        this.scanCursor = (this.scanCursor + advanced) % size;
    }

    // ------------------------------------------------------------------
    // 放置 (Lotus af.v)
    // ------------------------------------------------------------------

    private void place() {
        for (PrintTarget target : this.pending) {
            BlockPos pos = target.pos;
            BlockState state = null;
            Block block = null;
            int slot = -1;
            for (BlockState candidate : target.states) {
                block = candidate.getBlock();
                Item item = block.asItem();
                int found = findInventorySlot(item);
                if (found == -1) continue;
                state = candidate;
                slot = found;
                break;
            }
            if (state == null) return;
            InvUtils.swapWithSelected(slot);
            if (block instanceof SlabBlock) {
                PlaceUtils.placeSlab(pos, state);
            } else if (block instanceof StairBlock) {
                PlaceUtils.placeStair(pos, state);
            } else if (target.platform) {
                placeGeneric(target, pos);
            } else if (state.getProperties().contains(BlockStateProperties.FACING)) {
                PlaceUtils.placeWithFacing(state, block, pos, BlockStateProperties.FACING, target.side);
            } else if (state.getProperties().contains(BlockStateProperties.HORIZONTAL_FACING)) {
                PlaceUtils.placeWithFacing(state, block, pos, BlockStateProperties.HORIZONTAL_FACING, target.side);
            } else if (state.getProperties().contains(BlockStateProperties.FACING_HOPPER)) {
                PlaceUtils.placeWithFacing(state, block, pos, BlockStateProperties.FACING_HOPPER, target.side);
            } else {
                placeGeneric(target, pos);
            }
            this.cooldowns.put(pos, module.placeTimeout.get());
            InvUtils.swapWithSelected(slot);
            InvUtils.sendClosePacket();
        }
        this.pending.clear();
    }

    private static void placeGeneric(PrintTarget target, BlockPos pos) {
        if (target.side != null) {
            PlaceUtils.placeViaNeighbor(pos, target.side);
        } else {
            PlaceUtils.placeAuto(pos);
        }
    }

    // ------------------------------------------------------------------
    // 工具 (Lotus ar_0.f / ar_0.e / at_0.a)
    // ------------------------------------------------------------------

    /** 在整个物品栏 (0-44) 中找物品槽位 (Lotus ar_0.f) */
    private static int findInventorySlot(Item item) {
        if (item == net.minecraft.world.item.Items.AIR || mc.player == null) return -1;
        for (int i = 0; i < 45; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    /** 物品栏中是否有该物品 (Lotus ar_0.e) */
    private static boolean hasItem(Item item) {
        return findInventorySlot(item) != -1;
    }

    /** 放置位置是否被实体挡住 (Lotus at_0.a(BlockPos, BlockState)) */
    private static boolean entityBlocks(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(mc.level, pos);
        if (shape.isEmpty()) return false;
        shape = shape.move(pos.getX(), pos.getY(), pos.getZ());
        AABB box = new AABB(pos);
        List<Entity> entities = mc.level.getEntities((Entity) null, box,
                entity -> entity.isAlive() && !(entity instanceof ItemEntity)
                        && !(entity instanceof ExperienceOrb)
                        && !(entity instanceof ThrownExperienceBottle)
                        && !(entity instanceof Arrow)
                        && !(entity instanceof EndCrystal));
        for (Entity entity : entities) {
            if (Shapes.joinIsNotEmpty(shape, Shapes.create(entity.getBoundingBox()), BooleanOp.AND)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 打印目标 (Lotus c + ab_0)
    // ------------------------------------------------------------------
    static class PrintTarget {
        final BlockPos pos;
        final double dist;
        List<BlockState> states;
        BlockState current;
        boolean platform;
        Direction side;

        PrintTarget(BlockPos pos, double dist) {
            this.pos = pos;
            this.dist = dist;
        }
    }
}
