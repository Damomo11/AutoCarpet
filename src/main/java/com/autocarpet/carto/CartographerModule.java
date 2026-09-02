package com.autocarpet.carto;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICustomGoalProcess;
import com.autocarpet.module.Module;
import com.autocarpet.module.Setting;
import com.autocarpet.util.BlockActions;
import com.autocarpet.util.Chat;
import com.autocarpet.util.FindItemResult;
import com.autocarpet.util.InvUtils;
import com.autocarpet.util.Names;
import com.autocarpet.util.PlaceUtils;
import com.autocarpet.util.Rotations;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 制图师 (以 Lotus-19-p8 w_0 为权威源码逐状态移植, 基类链 ar/an/z_0 已并入本类)。
 * <p>
 * 流程 (与原版一致): 加载投影 -> READY -> 扫描行需求「开始打印第[N]行」-> CHECK_BACKPACK
 * -> TAKE_ITEM (补地毯) -> 补完后 SUPPLY (前往补给: 空地图/玻璃板/铁砧/经验瓶)
 * -> NEXT (打印机打印地毯画) -> 全部行打印完 finishAll「地毯打印完毕, 开始绘制地图」
 * -> SUPPLY -> DRAW 绘制空地图 -> LOCK 制图台锁定 -> NAME 铁砧命名 (原版 USE 态)
 * -> PUT 放入成品 -> 无成品后收尾 (移除投影/归档 momomap/加载下一张, 「暂停一会, 等待水收回...」)。
 * 投影原点必须是地图画的左上角 (128x128, 每 128 格一张地图)。
 */
public class CartographerModule extends Module implements AbstractGameEventListener {
    private static final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

    // ------------------------------------------------------------------
    // Baritone / litematica 静态资源 (Lotus an + s_0.l)
    // ------------------------------------------------------------------
    private static final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private static final ICustomGoalProcess goalProcess = baritone.getCustomGoalProcess();
    private static final Settings baritoneSettings = BaritoneAPI.getSettings();
    private static final SchematicPlacementManager placementManager = DataManager.getSchematicPlacementManager();
    /** 一张地图 128x128 */
    private static final int MAP_SIZE = 128;
    /** 移动保护距离 */
    private static final int MAX_PATH_DISTANCE = 1000;

    // ------------------------------------------------------------------
    // 状态机 (Lotus ag_0, 只保留制图师用到的状态)
    // ------------------------------------------------------------------
    public enum State {
        /** 无事可做 */
        NONE,
        /** 破坏挡路的方块 */
        BREAK,
        /** 延迟后关闭界面并跳转 */
        CLOSE_SCREEN,
        /** 初始化完成, 开始新的一行 */
        READY,
        /** baritone 寻路中 */
        WALKING,
        /** 打印当前行的下一个位置 */
        NEXT,
        /** 启动打印机继续打印 */
        OPEN_PRINTER,
        /** 前往补给箱 (红石标记) 拿空地图/玻璃板/铁砧/经验瓶 (原版 G) */
        SUPPLY,
        /** 绘制空地图 */
        DRAW,
        /** 制图台锁定地图 */
        LOCK,
        /** 放入成品地图 */
        PUT,
        /** 背包整理 (独立版跳过, 原版 O 完成后回 TAKE_ITEM) */
        SORT,
        /** 铁砧命名成品地图 (原版 USE/au_0.I) */
        NAME,
        /** 等待输出箱确认成品地图已接收 (独立版增强, 用于归档时机) */
        OUTPUT_CONFIRM,
        /** 加载下一张投影 */
        LOAD_SCHEMATIC,
        /** 检查背包缺哪些地毯 */
        CHECK_BACKPACK,
        /** 前往地毯补给箱补货 */
        TAKE_ITEM
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------
    public final Setting.Int printRange;
    public final Setting.Int extraSupply;
    public final Setting.Int pauseTicks;
    public final Setting.Str schematicDir;
    public final Setting.Str schematicNames;
    public final Setting.Bool initSetting;
    public final Setting.Bool autoName;
    public final Setting.Int namingDelay;
    public final Setting.Int actionDelay;
    public final Setting.Int restartDelay;
    public final Setting.Bool turning;
    public final Setting.Bool randomView;
    public final Setting.Int toggleKey;
    public final Setting.Int configKey;
    // 打印机设置 (Lotus af)
    public final Setting.Int printerRange;
    public final Setting.Int printerCount;
    public final Setting.Int placeTimeout;
    public final Setting.Int faceCheckLimit;
    public final Setting.Bool printerNearest;
    // 反反作弊 (Lotus an.e, 可配置化)
    public final Setting.Double randomLooking;
    public final Setting.Double randomLooking113;

    // ------------------------------------------------------------------
    // 状态机字段 (Lotus aj/an: n/o/p/q/r/s + 重启调度)
    // ------------------------------------------------------------------
    private State state = State.NONE;
    private State pendingState;
    private State delayedState = State.NONE;
    private int pendingTransitionTicks;
    private BooleanSupplier delayedCondition = () -> true;
    private final Map<State, Runnable> handlers = new HashMap<>();
    private int restartTimer;
    private State restartTarget;

    // ------------------------------------------------------------------
    // NEXT 卡住计数与寻路兜底 (独立版保底出口, 原版无)
    // ------------------------------------------------------------------
    private int nextRow = -1;
    private int nextColumn = -1;
    private int nextStuckTicks;
    private int walkingTicks;
    private BlockPos lastGoal;
    private int lastGoalRange;

    // ------------------------------------------------------------------
    // 通用字段 (Lotus u_0)
    // ------------------------------------------------------------------
    private int delayTicks;
    private boolean openSent;
    private long lastOpenTime;
    private BlockPos lastOpenPos;
    private int invCursor;
    private int containerCursor;

    // ------------------------------------------------------------------
    // 制图师字段 (Lotus s_0)
    // ------------------------------------------------------------------
    private BlockPos mapOrigin;
    private BlockPos mapCenter;
    private BlockPos supplyChest;
    private BlockPos outputChest;
    private BlockPos cartographyTable;
    private final Map<Item, List<BlockPos>> supplyPoints = new HashMap<>();
    private final List<List<BlockPos>> rows = new LinkedList<>();
    private final Map<Item, Integer> needed = new HashMap<>();
    private final Map<Item, Integer> missing = new HashMap<>();
    private Item supplyItem;
    private int supplyAmount;
    private BlockPos supplyTarget;
    /** SUPPLY 完成后的去向: 打印前补物资时为 NEXT, 绘图流程中为 DRAW (原版恒为 DRAW) */
    private State supplyReturn = State.DRAW;
    private final Deque<BlockPos> obstacles = new ArrayDeque<>();
    private BlockPos breakTarget;
    private WorldSchematic schematicWorld;
    private int rowIndex;
    private int columnIndex;
    private BlockPos anchor;
    private BlockPos anvilPos;
    private String mapName;

    private static final Path MOMOMAP_DIR = DataManager.getSchematicsBaseDirectory().resolve("momomap");
    private Path currentQueueFile;
    private SchematicPlacement currentPlacement;
    private String currentMapBaseName;
    private int scanCooldown;
    private Path stableCandidate;
    private long stableSize = -1L;
    private long stableModified = Long.MIN_VALUE;
    private int stableTicks;
    private int outputConfirmTicks;
    private int outputWaitTicks;
    private boolean outputShiftSent;
    private int outputInventoryFilledCount;
    private boolean previousAntiCheat;
    private double previousRandomLooking;
    private double previousRandomLooking113;
    private boolean previousAllowBreak;
    private boolean previousAllowPlace;
    private boolean baritoneSettingsCaptured;
    /** activate 时自动切到第三人称背面视角 (原版开始打印时的行为), deactivate 时还原 */
    private boolean cameraSwitched;
    /** 本轮会话中加载失败、已跳过的投影文件 */
    private final Set<Path> skippedFiles = new HashSet<>();

    /** 投影打印机 (Lotus af 的投影打印模式) */
    public final CarpetPrinter printer = new CarpetPrinter(this);

    private static Field anvilNameField;

    public CartographerModule() {
        super("制图师", "自动打印地图画(仅地毯), 需要搭配制图平台使用, 投影原点必须是左上角");
        // ---- 设置 (Lotus s_0 + aj + u_0 + af) ----
        this.printRange = this.intSetting("打印范围", "每次打印的行宽(格), 1-64", 2, 1, 64);
        this.extraSupply = this.intSetting("额外补货量", "补货时, 至少多补的数量", 32, 0, 64);
        this.pauseTicks = this.intSetting("暂停时间(tick)", "画完一张后的暂停时间", 200, 0, 2000);
        this.schematicDir = this.str("投影路径(相对)", "内部兼容设置；队列固定使用 schematics/momomap", "momomap");
        this.schematicNames = this.str("投影名称", "内部兼容设置；队列从 momomap 自动发现", "");
        this.initSetting = this.bool("初始化", "使用前需要先初始化", false);
        this.initSetting.onChanged = () -> this.onInitSettingChanged();
        this.autoName = this.bool("自动命名", "自动将成品地图画按投影名称命名", true);
        this.namingDelay = this.intSetting("命名延迟(tick)", "命名地图后的等待时间", 200, 0, 2000);
        this.actionDelay = this.intSetting("延迟", "操作之间的间隔延迟(tick)", 5, 0, 40);
        this.restartDelay = this.intSetting("重启延时(秒)", "出错重启的延时(秒)", 2, 1, 200);
        this.turning = this.bool("转向", "打印和寻路时调整视角", false);
        this.randomView = this.bool("随机视角", "启用随机视角抖动", false);
        this.toggleKey = this.intSetting("快捷键", "切换制图师的 GLFW 按键代码", GLFW.GLFW_KEY_RIGHT_CONTROL, 0, 512);
        this.configKey = this.intSetting("配置页快捷键", "打开配置界面的按键, 无表示未绑定", 0, 0, 512);
        this.printerRange = this.intSetting("打印·放置范围(格)", "打印机搜索放置位置的范围", 4, 1, 128);
        this.printerCount = this.intSetting("打印·数量(每tick)", "打印机每tick放置的数量", 5, 1, 16);
        this.placeTimeout = this.intSetting("打印·放置超时", "同一位置失败后冷却 tick", 20, 0, 100);
        this.faceCheckLimit = this.intSetting("打印·面校验上限", "每tick进行射线面校验的最大次数", 16, 1, 64);
        this.printerNearest = this.bool("打印·优先近处", "开启后优先打印离玩家近的方块", true);
        this.randomLooking = this.doubleSetting("随机视角幅度", "随机视角的偏移角度", 0.01, 0.0, 10.0);
        this.randomLooking113 = this.doubleSetting("随机视角幅度(大)", "大角度随机视角的偏移角度", 2.0, 0.0, 30.0);
        // ---- 状态注册表 (Lotus s_0 构造器) ----
        this.register(State.NONE, () -> {
        });
        this.register(State.WALKING, () -> {
        });
        this.register(State.CLOSE_SCREEN, this::closeScreenTransition);
        this.register(State.LOAD_SCHEMATIC, this::loadSchematic);
        this.register(State.CHECK_BACKPACK, this::checkBackpack);
        this.register(State.TAKE_ITEM, this::takeItem);
        this.register(State.READY, this::onReady);
        this.register(State.NEXT, this::onNext);
        this.register(State.BREAK, this::onBreak);
        this.register(State.OPEN_PRINTER, this::onOpenPrinter);
        this.register(State.SUPPLY, this::onSupply);
        this.register(State.DRAW, this::onDraw);
        this.register(State.LOCK, this::onLock);
        this.register(State.PUT, this::onPut);
        this.register(State.SORT, this::onSort);
        this.register(State.NAME, this::onName);
        this.register(State.OUTPUT_CONFIRM, this::confirmOutput);
        // ---- baritone 事件 (Lotus an 构造器) ----
        baritone.getGameEventHandler().registerEventListener(this);
    }

    private void register(State state, Runnable handler) {
        this.handlers.put(state, handler);
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    public void onActivate() {
        Chat.info("制图师已开启");
        if (this.rows.isEmpty()) {
            Chat.warning("启动前没有初始化, 自动初始化");
            this.init(true);
            if (this.rows.isEmpty()) {
                Chat.warning("自动初始化失败");
                this.toggle();
                return;
            }
        }
        // Capture Baritone globals so disabling this module never changes another user's settings.
        if (!this.baritoneSettingsCaptured) {
            this.previousAntiCheat = baritoneSettings.antiCheatCompatibility.value;
            this.previousRandomLooking = baritoneSettings.randomLooking.value;
            this.previousRandomLooking113 = baritoneSettings.randomLooking113.value;
            this.previousAllowBreak = baritoneSettings.allowBreak.value;
            this.previousAllowPlace = baritoneSettings.allowPlace.value;
            this.baritoneSettingsCaptured = true;
        }
        baritoneSettings.antiCheatCompatibility.value = true;
        baritoneSettings.randomLooking.value = this.randomView.get() ? this.randomLooking.get() : 0.0;
        baritoneSettings.randomLooking113.value = this.randomView.get() ? this.randomLooking113.get() : 0.0;
        baritoneSettings.allowBreak.value = false;
        baritoneSettings.allowPlace.value = false;
        Rotations.configure(this.turning.get(), this.randomView.get());
        // 原版开始打印时自动切第三人称背面视角; 只在当前是第一人称时切换并记录, 停用时还原, 不覆盖用户 F5 偏好
        if (mc.player != null && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            this.cameraSwitched = true;
        }
        Chat.warning("延迟启动...");
        this.delayedRestart(State.LOAD_SCHEMATIC);
    }

    @Override
    public void onDeactivate() {
        Chat.warning("制图师已关闭");
        this.state = State.NONE;
        this.cancelRestart();
        baritone.getPathingBehavior().cancelEverything();
        if (this.baritoneSettingsCaptured) {
            baritoneSettings.antiCheatCompatibility.value = this.previousAntiCheat;
            baritoneSettings.randomLooking.value = this.previousRandomLooking;
            baritoneSettings.randomLooking113.value = this.previousRandomLooking113;
            baritoneSettings.allowBreak.value = this.previousAllowBreak;
            baritoneSettings.allowPlace.value = this.previousAllowPlace;
            this.baritoneSettingsCaptured = false;
        }
        if (this.cameraSwitched) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            this.cameraSwitched = false;
        }
        this.printer.deactivate();
        BlockActions.stopBreaking();
        this.resetRunState();
        Rotations.setEnabled(false);
    }

    /** 真实视角只允许在 baritone 寻路行走 (WALKING) 时转动; 打印相关状态一律不动真实视角 */
    public boolean isWalkingState() {
        return this.state == State.WALKING;
    }

    @Override
    public void onTick() {
        if (this.scanCooldown > 0) this.scanCooldown--;
        this.scanQueue();
        // Keep Baritone's globals aligned while active, including changes made in the screen.
        baritoneSettings.antiCheatCompatibility.value = true;
        baritoneSettings.randomLooking.value = this.randomView.get() ? this.randomLooking.get() : 0.0;
        baritoneSettings.randomLooking113.value = this.randomView.get() ? this.randomLooking113.get() : 0.0;
        Rotations.configure(this.turning.get(), this.randomView.get());
        // 重启调度 (Lotus aj.d 的 ScheduledFuture 改为 tick 计时)
        if (this.restartTimer > 0) {
            this.restartTimer--;
            if (this.restartTimer == 0) {
                Chat.info("启动");
                this.state = this.restartTarget;
            }
        }
        if (mc.player == null || mc.level == null) return;
        // 非寻路状态不得残留任何真实视角目标 (放置/破坏一律走 PlaceUtils 静默旋转)
        if (this.state != State.WALKING) Rotations.clearTarget();
        // 寻路兜底 (独立版保底出口): 正常到达/取消由 onPathEvent(CANCELED) 恢复;
        // 这里兜底事件丢失或寻路失败, 保证 WALKING 绝不无限卡死
        if (this.state == State.WALKING) {
            this.walkingTicks++;
            if (this.pendingState != null && this.lastGoal != null) {
                BlockPos feet = mc.player.blockPosition();
                if (Math.abs(feet.getX() - this.lastGoal.getX()) <= this.lastGoalRange
                        && Math.abs(feet.getZ() - this.lastGoal.getZ()) <= this.lastGoalRange) {
                    this.state = this.pendingState;
                    this.pendingState = null;
                    this.walkingTicks = 0;
                }
            }
            if (this.state == State.WALKING && this.walkingTicks >= 100) {
                this.walkingTicks = 0;
                if (this.lastGoal != null) {
                    Chat.warning("寻路停滞, 重新寻路");
                    goalProcess.setGoalAndPath(new GoalNear(this.lastGoal, this.lastGoalRange));
                }
            }
        } else {
            this.walkingTicks = 0;
        }
        if (this.tickDelay()) {
            Runnable handler = this.handlers.get(this.state);
            if (handler == null) {
                Chat.warning("未处理状态 : %s", this.state);
                this.toggle();
                return;
            }
            try {
                handler.run();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                Chat.error("发生未知异常 : %s", throwable.getMessage());
                this.toggle();
                return;
            }
        }
        // 打印机独立于状态机运行 (Lotus aj.onTickPost), 且在状态机之后 (原版 TickEvent.Pre -> Post 顺序),
        // 状态机被延迟跳过时打印机照常运行
        this.printer.tick();
    }

    @Override
    public void onPathEvent(PathEvent event) {
        if (event == PathEvent.CANCELED && this.pendingState != null) {
            this.state = this.pendingState;
            this.pendingState = null;
        }
    }

    // ==================================================================
    // 基础设施 (Lotus aj/u_0/an 并入)
    // ==================================================================

    /** 延迟计时 (Lotus u_0.c): 返回 false 表示本 tick 跳过 */
    private boolean tickDelay() {
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return false;
        }
        return true;
    }

    /** 重载默认延迟 (Lotus u_0.k) */
    private void k() {
        this.delayTicks = this.actionDelay.get();
    }

    /** 设置自定义延迟 (Lotus u_0.d) */
    private void d(int ticks) {
        this.delayTicks = ticks;
    }

    /** 剩余延迟 (Lotus u_0.l) */
    public int getDelayTicks() {
        return this.delayTicks;
    }

    // ---- baritone 寻路 (Lotus an) ----

    private void pathTo(BlockPos pos, int range, State then, String message) {
        double dist = mc.player.position().distanceTo(pos.getCenter());
        if (dist > MAX_PATH_DISTANCE) {
            Chat.warning("移动距离超过1000格, 功能关闭");
            this.toggle();
            return;
        }
        if (dist > range) {
            if (message != null) Chat.info(message);
            this.pendingState = then;
            this.startPath(pos, range);
        } else {
            this.state = then;
        }
    }

    private void pathTo(BlockPos pos, int range, State then) {
        this.pendingState = then;
        this.startPath(pos, range);
    }

    private void startPath(BlockPos pos, int range) {
        this.lastGoal = new BlockPos(pos);
        this.lastGoalRange = range;
        goalProcess.setGoalAndPath(new GoalNear(pos, range));
        this.state = State.WALKING;
    }

    // ---- 重启调度 (Lotus aj.d/A/B) ----

    private void delayedRestart(State target) {
        this.cancelRestart();
        GameType mode = mc.gameMode.getPlayerMode();
        if (mode == GameType.SPECTATOR || mode == GameType.ADVENTURE) return;
        this.restartTimer = this.restartDelay.get() * 20;
        this.restartTarget = target;
    }

    private void cancelRestart() {
        this.restartTimer = 0;
        this.restartTarget = null;
    }

    /** 关闭容器 + 延迟重启 (Lotus s_0.e) */
    private void restart(State target) {
        InvUtils.closeContainer();
        this.state = State.NONE;
        this.cancelRestart();
        this.delayedRestart(target);
    }

    // ---- 延迟跳转 (Lotus aj.a/b/c/y) ----

    private void delayedTransition(State target) {
        this.delayedState = target;
        this.state = State.CLOSE_SCREEN;
    }

    private void delayedTransitionIf(State target, BooleanSupplier condition) {
        this.delayedCondition = condition;
        this.delayedTransition(target);
        this.k();
    }

    /** CLOSE_SCREEN 处理器 (Lotus aj.y, 也会被 s_0.C 直接调用) */
    private void closeScreenTransition() {
        if (!this.delayedCondition.getAsBoolean()) {
            this.k();
            return;
        }
        InvUtils.closeContainer();
        if (this.delayedState != null) this.state = this.delayedState;
        if (this.pendingTransitionTicks > 0) {
            this.d(this.pendingTransitionTicks);
            this.pendingTransitionTicks = 0;
        } else {
            this.k();
        }
    }

    /** 直接失败: 提示并停止 (Lotus aj.a(String)) */
    private void failState(String message) {
        Chat.warning(message);
        this.state = State.NONE;
        this.delayedState = State.NONE;
    }

    // ---- 容器打开 (Lotus u_0.a/b/c) ----

    private void openContainer(BlockPos pos, Consumer<AbstractContainerMenu> consumer) {
        this.openContainer(pos, null, consumer);
    }

    private void openContainer(BlockPos pos, Direction side, Consumer<AbstractContainerMenu> consumer) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu instanceof InventoryMenu || !pos.equals(this.lastOpenPos)) {
            this.attemptOpen(pos, side);
        } else if (menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu) {
            this.openSent = false;
            consumer.accept(menu);
        } else {
            this.openSent = false;
            InvUtils.closeContainer();
            Chat.warning("打开的容器错误");
            this.k();
        }
    }

    private void attemptOpen(BlockPos pos, Direction side) {
        long now = System.currentTimeMillis();
        if (!this.openSent || now - this.lastOpenTime > 1000) {
            if (side == null) {
                PlaceUtils.openAuto(pos);
            } else {
                PlaceUtils.openFacing(pos, side);
            }
            this.openSent = true;
            this.lastOpenTime = now;
            this.lastOpenPos = new BlockPos(pos);
        } else {
            Chat.warning("打开没有反应...");
        }
        this.k();
    }

    // ---- 物品扫描 (Lotus u_0 的轮转扫描) ----

    /** 轮转扫描背包 (Lotus u_0.a(Predicate)) */
    private ItemStack scanInventory(Predicate<ItemStack> predicate) {
        Inventory inventory = mc.player.getInventory();
        int start = this.invCursor;
        int end = MAIN_INVENTORY_SIZE - 1;
        do {
            this.invCursor = this.invCursor >= end ? 0 : ++this.invCursor;
            ItemStack stack = inventory.getItem(this.invCursor);
            if (stack.isEmpty() || !predicate.test(stack)) continue;
            return stack;
        } while (this.invCursor != start);
        return ItemStack.EMPTY;
    }

    /** 轮转扫描当前容器窗口槽位 (Lotus u_0.b(Predicate)) */
    private ItemStack scanContainer(Predicate<ItemStack> predicate) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        int end = this.containerSlotCount() - 1;
        int start = this.containerCursor;
        do {
            this.containerCursor = this.containerCursor >= end ? 0 : ++this.containerCursor;
            ItemStack stack = menu.getSlot(this.containerCursor).getItem();
            if (stack.isEmpty() || !predicate.test(stack)) continue;
            return stack;
        } while (this.containerCursor != start);
        return ItemStack.EMPTY;
    }

    private static final int MAIN_INVENTORY_SIZE = 36;

    /** 容器自身槽位数 (不含玩家背包) (Lotus u_0.n) */
    private int containerSlotCount() {
        if (mc.player.containerMenu instanceof InventoryMenu) {
            return mc.player.containerMenu.slots.size() - MAIN_INVENTORY_SIZE - 1;
        }
        return mc.player.containerMenu.slots.size() - MAIN_INVENTORY_SIZE;
    }

    /** 上次轮转扫描的背包槽位 (Lotus u_0.s) */
    private int invScanSlot() {
        return this.invCursor;
    }

    /** 上次轮转扫描的容器窗口槽位 (Lotus u_0.u) */
    private int containerScanSlot() {
        return this.containerCursor;
    }

    /** 第一个空背包槽位 (Lotus u_0.o) */
    private int firstEmptyInvSlot() {
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            if (inventory.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /** 背包是否已满 (Lotus u_0.q) */
    private boolean inventoryFull() {
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            if (inventory.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    // ---- 位置助手 (Lotus aj/u_0/s_0) ----

    /** 玩家距离方块中心 (Lotus aj.a(BlockPos,double)) */
    private boolean fartherThan(BlockPos pos, double dist) {
        return mc.player.position().distanceTo(pos.getCenter()) > dist;
    }

    /** 眼睛距离方块中心 (Lotus aj.b(BlockPos,double)) */
    private boolean eyeFartherThan(BlockPos pos, double dist) {
        return mc.player.getEyePosition().distanceTo(pos.getCenter()) > dist;
    }

    /** 低处方块抬高到玩家脚部高度 (Lotus s_0.e(BlockPos)) */
    private BlockPos raiseToPlayerY(BlockPos pos) {
        int playerY = mc.player.getBlockY();
        return pos.getY() < playerY ? new BlockPos(pos.getX(), playerY, pos.getZ()) : pos;
    }

    // ==================================================================
    // 状态处理器 (Lotus s_0)
    // ==================================================================

    /** LOAD_SCHEMATIC: load the oldest stable direct child of schematics/momomap. */
    private void loadSchematic() {
        if (this.currentQueueFile == null || !Files.isRegularFile(this.currentQueueFile)) {
            this.scanQueue();
            if (this.currentQueueFile == null) return;
        }
        Path path = this.currentQueueFile;
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);
        if (schematic == null) {
            Chat.warning("投影文件加载失败, 已跳过: %s", path.getFileName());
            this.skipCurrentFile(path);
            return;
        }
        String fileName = path.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - ".litematic".length());
        this.currentMapBaseName = base;
        this.mapName = base;
        this.currentPlacement = SchematicPlacement.createFor(schematic, this.mapOrigin, base, true, true);
        placementManager.addSchematicPlacement(this.currentPlacement, false);
        placementManager.setSelectedSchematicPlacement(this.currentPlacement);
        Chat.info("加载投影: %s", path.getFileName());
        this.goReady();
    }

    /** 加载失败时跳过该文件: 尽量移入 momomap/failed, 否则内存跳过, 然后继续下一个任务 */
    private void skipCurrentFile(Path path) {
        this.currentQueueFile = null;
        this.stableCandidate = null;
        this.stableTicks = 0;
        this.scanCooldown = 0;
        this.skippedFiles.add(path);
        try {
            Path failedDir = MOMOMAP_DIR.resolve("failed");
            Files.createDirectories(failedDir);
            Path target = failedDir.resolve(path.getFileName());
            if (!Files.exists(target)) {
                try {
                    Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailure) {
                    Files.move(path, target);
                }
            }
        } catch (IOException moveFailure) {
            // 移动失败: 文件留在队列目录, 靠 skippedFiles 内存跳过
        }
    }

    /** READY (Lotus w) */
    private void onReady() {
        this.resetRunState();   // T()
        this.rowIndex = 0;
        this.columnIndex = 0;
        this.schematicWorld = SchematicWorldHandler.getSchematicWorld();
        this.startPrinter();   // Q()
        this.scanRowRequirements();   // K()
    }

    /**
     * 水平对齐到锚点方块中心 (X+0.5, Z+0.5)。
     * 原版由 baritone 寻路把玩家停在方块中心再打印; 独立版寻路可能停在方块边缘,
     * 边缘站位到角落方块中心的距离会超出可及范围导致打不到。
     * 每 tick 最多移动 0.05 格, 平滑不瞬移; 返回 true 表示已对齐 (偏差 <= 0.15)。
     */
    private boolean alignToBlockCenter(BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= 0.15) return true;
        double step = Math.min(0.05, dist);
        mc.player.setPos(mc.player.getX() + dx / dist * step, mc.player.getY(), mc.player.getZ() + dz / dist * step);
        mc.player.setDeltaMovement(Vec3.ZERO);
        return false;
    }

    /** NEXT (Lotus x): 巡检当前位置, 决定打印/移动/破坏; 原版 do-while 在同一 tick 内推进空锚点 */
    private void onNext() {
        // 卡住计数: 同一行同一列连续多次进入仍无进展时, 走兜底出口 (绝不无限卡死)
        if (this.nextRow == this.rowIndex && this.nextColumn == this.columnIndex) {
            this.nextStuckTicks++;
        } else {
            this.nextStuckTicks = 0;
            this.nextRow = this.rowIndex;
            this.nextColumn = this.columnIndex;
        }
        if (this.rowIndex < 0 || this.rowIndex >= this.rows.size()) {
            this.scanRowRequirements();   // K()
            return;
        }
        List<BlockPos> row;
        List<BlockPos> toPlace = null;
        do {
            row = this.rows.get(this.rowIndex);
            // 边界防御: 任何情况下不允许 -1/越界下标进入取值
            if (this.columnIndex < 0) this.columnIndex = 0;
            if (this.columnIndex >= row.size()) {
                this.scanRowRequirements();   // K()
                return;
            }
            this.anchor = row.get(this.columnIndex);
            List<BlockPos> crossSection = this.crossSection(this.anchor);   // a(O)
            toPlace = new ArrayList<>(crossSection.size());
            for (BlockPos pos : crossSection) {
                BlockState schematicState = this.schematicWorld.getBlockState(pos);
                if (schematicState.isAir()) continue;
                BlockState realState = mc.level.getBlockState(pos);
                if (schematicState.getBlock().asItem() == realState.getBlock().asItem()) continue;
                if (!realState.isAir() && realState.getBlock() != Blocks.WATER) {
                    this.obstacles.add(pos);
                    continue;
                }
                toPlace.add(pos);
            }
            if (!this.obstacles.isEmpty()) {
                this.state = State.BREAK;
                return;
            }
            if (!toPlace.isEmpty()) break;
            this.columnIndex++;
        } while (this.columnIndex < row.size());
        if (toPlace.isEmpty()) {
            this.scanRowRequirements();   // K()
            return;
        }
        AABB playerBox = mc.player.getBoundingBox();
        if (!playerBox.intersects(new AABB(this.anchor))) {
            this.pathTo(this.anchor, 0, State.NEXT);
            return;
        }
        // 已进入锚点方块: 先水平对齐到方块中心 (X+0.5, Z+0.5) 再开始放置 (对齐前不打印)
        if (!this.alignToBlockCenter(this.anchor)) {
            this.nextStuckTicks = 0;   // 对齐本身就是进展, 不计入卡住
            return;
        }
        boolean allCarried = true;
        BlockPos candidate = null;
        for (BlockPos pos : toPlace) {
            BlockState schematicState = this.schematicWorld.getBlockState(pos);
            Item item = schematicState.getBlock().asItem();
            ItemStack have = this.scanInventory(stack -> stack.getItem() == item);
            if (have.isEmpty()) {
                Chat.info("缺少物品, 重新检查物品量");
                this.scanRowRequirements();   // K()
                return;
            }
            if (playerBox.intersects(new AABB(pos))) continue;
            allCarried = false;
            if (this.rowIndex % 2 == 0) {
                if (pos.getX() >= this.anchor.getX() - this.printRange.get()) continue;
                candidate = pos;
            } else {
                if (pos.getX() <= this.anchor.getX() + this.printRange.get()) continue;
                candidate = pos;
            }
        }
        if (allCarried) {
            Chat.info("可能挡住脚下了, 尝试移动");
            int[] offsets;
            if (this.rowIndex % 2 == 0) {
                offsets = new int[]{-1, -2, 1, 2};
            } else {
                offsets = new int[]{1, 1, -2};
            }
            for (int offset : offsets) {
                BlockPos standPos = this.anchor.offset(offset, 0, 0);
                BlockState schematicState = this.schematicWorld.getBlockState(standPos);
                BlockState realState = mc.level.getBlockState(standPos);
                if (!schematicState.isAir() && !CartoItems.isCarpet(realState.getBlock().asItem())) continue;
                this.pathTo(standPos, 0, State.NEXT);
                return;
            }
            Chat.warning("找不到可以移动的位置...");
        } else if (candidate != null) {
            Chat.info("检测到前面出现错误, 回去修复");
            if (this.columnIndex > 0) this.columnIndex--;   // 防御: columnIndex=0 时几何上不存在回退目标, 不允许产生 -1
        }
        // 兜底出口: 同一锚点长时间无进展 (候选被拒/对齐后仍不可及等) -> 先按原版"可能挡住脚下了"逻辑换位, 仍不行按原版方式重启
        if (this.nextStuckTicks >= 20) {
            if (this.nextStuckTicks % 20 == 0) {
                int[] moveOffsets = this.rowIndex % 2 == 0 ? new int[]{-1, -2, 1, 2} : new int[]{1, 1, -2};
                for (int offset : moveOffsets) {
                    BlockPos standPos = this.anchor.offset(offset, 0, 0);
                    BlockState schematicState = this.schematicWorld.getBlockState(standPos);
                    BlockState realState = mc.level.getBlockState(standPos);
                    if (!schematicState.isAir() && !CartoItems.isCarpet(realState.getBlock().asItem())) continue;
                    if (playerBox.intersects(new AABB(standPos))) continue;
                    Chat.warning("打印停滞, 尝试移动位置");
                    this.nextStuckTicks = 0;
                    this.pathTo(standPos, 0, State.NEXT);
                    return;
                }
            }
            if (this.nextStuckTicks >= 120) {
                Chat.warning("打印停滞过久, 稍后重启");
                this.nextStuckTicks = 0;
                this.restart(State.NEXT);
            }
        }
    }

    /** BREAK (Lotus G) */
    private void onBreak() {
        while (true) {
            if (this.breakTarget == null) {
                if (this.obstacles.isEmpty()) {
                    this.state = State.OPEN_PRINTER;
                    return;
                }
                this.breakTarget = this.obstacles.pollFirst();
            }
            BlockState schematicState = this.schematicWorld.getBlockState(this.breakTarget);
            BlockState realState = mc.level.getBlockState(this.breakTarget);
            if (schematicState.getBlock().asItem() != realState.getBlock().asItem() && !realState.isAir()) break;
            this.breakTarget = null;
        }
        if (this.eyeFartherThan(this.breakTarget, 4.1)) {   // 原版 H 用眼睛坐标距离 b(pos, 4.1)
            BlockPos stand = new BlockPos(this.breakTarget.getX(), this.anchor.getY(), this.anchor.getZ());
            this.pathTo(stand, 1, State.BREAK);
            return;
        }
        this.printer.deactivate();   // aw_0.f()
        PlaceUtils.packetRotate(Vec3.atCenterOf(this.breakTarget));
        BlockActions.breakBlock(this.breakTarget);
    }

    /** OPEN_PRINTER (Lotus P) */
    private void onOpenPrinter() {
        this.nextStuckTicks = 0;
        this.startPrinter();   // Q()
        this.goNext();   // c(ag_0.h)
    }

    /** SUPPLY (原版 G): 去补给箱拿 空地图/玻璃板/铁砧/经验瓶, 备齐后按 supplyReturn 去向跳转 */
    private void onSupply() {
        Inventory inventory = mc.player.getInventory();
        boolean hasMap = false;
        boolean hasGlass = false;
        boolean hasAnvil = false;
        boolean hasXP = false;
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            Item item = inventory.getItem(i).getItem();
            if (item == Items.GLASS_PANE) {
                hasGlass = true;
                continue;
            }
            if (item == Items.MAP) {
                hasMap = true;
                continue;
            }
            if (item == Items.ANVIL) {
                hasAnvil = true;
                continue;
            }
            if (item == Items.EXPERIENCE_BOTTLE) hasXP = true;
        }
        boolean needAnvil = !hasAnvil && !this.anvilPlaced();
        boolean needXP = !hasXP && mc.player.experienceLevel < 1;
        if (hasMap && hasGlass && !needAnvil && !needXP) {
            this.delayedTransition(State.DRAW);
            return;
        }
        BlockPos stand = this.raiseToPlayerY(this.supplyChest);
        if (this.fartherThan(stand, 1.0)) {   // 原版 G: 脚部距离 a(pos, 1.0), pathTo range 0
            Chat.info("前往补给");
            this.pathTo(stand, 0, State.SUPPLY);
            return;
        }
        boolean needMap = !hasMap;
        boolean needGlass = !hasGlass;
        Direction side = stand != this.supplyChest ? Direction.UP : null;
        this.openContainer(this.supplyChest, side, menu -> {
            ItemStack stack = this.scanContainer(item -> {
                Item it = item.getItem();
                return needMap && it == Items.MAP
                        || needGlass && it == Items.GLASS_PANE
                        || needAnvil && it == Items.ANVIL
                        || needXP && it == Items.EXPERIENCE_BOTTLE;
            });
            if (stack.isEmpty()) {
                Chat.warning("无法补给, 稍后重启");
                this.restart(State.SUPPLY);
                return;
            }
            int empty = this.firstEmptyInvSlot();
            if (empty != -1) {
                Chat.info("补给: %s", Names.get(stack));
                if (stack.getItem() == Items.EXPERIENCE_BOTTLE) {
                    InvUtils.shiftClick(this.containerScanSlot());
                } else {
                    // 原版 bg.d(v(), n3): 左键拿起整组 -> 右键放 1 个进背包空位 -> 左键放回剩余
                    InvUtils.takeOneFromContainer(this.containerScanSlot(), InvUtils.indexToId(empty));
                }
                this.k();
            } else {
                ItemStack useless = this.scanInventory(item -> CartoItems.isCarpet(item.getItem()));
                if (!useless.isEmpty()) {
                    Chat.info("背包没有空位, 丢弃地毯: %s", Names.get(useless));
                    InvUtils.dropSlot(this.invScanSlot());
                    this.k();
                    return;
                }
                Chat.warning("身上没有空位, 且没有可丢弃的地毯, 稍后重启");
                this.restart(State.SUPPLY);
            }
        });
    }

    /** DRAW (原版 F): 在地图中心绘制空地图; 已有成品地图则转 LOCK */
    private void onDraw() {
        ItemStack filled = this.scanInventory(stack -> stack.getItem() == Items.FILLED_MAP);
        if (!filled.isEmpty()) {
            Chat.warning("地图绘制完成");
            this.state = State.LOCK;
            return;
        }
        if (!(mc.player.containerMenu instanceof InventoryMenu)) {
            this.delayedTransition(State.DRAW);
            return;
        }
        ItemStack empty = this.scanInventory(stack -> stack.getItem() == Items.MAP);
        if (empty.isEmpty()) {
            this.supplyReturn = State.DRAW;
            this.state = State.SUPPLY;
            return;
        }
        if (this.fartherThan(this.mapCenter, 2.0)) {   // 原版 F: 脚部距离 a(y, 2.0)
            Chat.warning("前往地图中心");
            this.pathTo(this.mapCenter, 1, State.DRAW);
            return;
        }
        int slot = this.invScanSlot();
        if (slot != mc.player.getInventory().getSelectedSlot()) {
            InvUtils.swapWithSelected(slot);
            this.k();
            return;
        }
        Chat.warning("绘制地图, 并等待加载...");
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        this.d(this.pauseTicks.get());
    }

    /** LOCK (原版 D): 制图台 放地图+玻璃板 -> 取锁定结果 */
    private void onLock() {
        if (mc.level.getBlockState(this.cartographyTable).getBlock() != Blocks.CARTOGRAPHY_TABLE) {
            Chat.warning("制图台位置错误, 稍后重启");
            this.restart(State.LOCK);
            return;
        }
        BlockPos stand = this.raiseToPlayerY(this.cartographyTable);
        if (this.fartherThan(stand, 1.0)) {   // 原版 D: 脚部距离 a(pos, 1.0), pathTo range 0
            Chat.info("前往制图台");
            this.pathTo(stand, 0, State.LOCK);
            return;
            }
        if (mc.player.containerMenu instanceof InventoryMenu) {
            if (stand != this.cartographyTable) {
                PlaceUtils.openFacing(this.cartographyTable, Direction.UP);
            } else {
                PlaceUtils.openAuto(this.cartographyTable);
            }
            this.k();
        } else if (mc.player.containerMenu instanceof CartographyTableMenu menu) {
            if (menu.getSlot(0).getItem().isEmpty()) {
                ItemStack filled = this.scanInventory(stack -> stack.getItem() == Items.FILLED_MAP);
                if (filled.isEmpty()) {
                    Chat.warning("身上没有已绘制的地图, 重新绘制");
                    this.supplyReturn = State.DRAW;
                    this.delayedTransition(State.SUPPLY);
                    return;
                }
                MapItemSavedData data = MapItem.getSavedData(filled, mc.level);
                if (data != null && data.locked) {
                    Chat.info("已锁定, 不需要锁定");
                    this.delayedTransition(State.NAME);
                    return;
                }
                InvUtils.shiftClickInv(this.invScanSlot());
            } else if (menu.getSlot(1).getItem().isEmpty()) {
                ItemStack glass = this.scanInventory(stack -> stack.getItem() == Items.GLASS_PANE);
                if (glass.isEmpty()) {
                    Chat.warning("身上没有玻璃板, 去补给");
                    this.supplyReturn = State.DRAW;
                    this.delayedTransition(State.SUPPLY);
                    return;
                }
                InvUtils.shiftClickInv(this.invScanSlot());
            } else if (!menu.getSlot(2).getItem().isEmpty()) {
                Chat.info("锁定地图");
                InvUtils.shiftClick(2);
                this.delayedTransition(this.autoName.get() ? State.NAME : State.PUT);
            }
            this.k();
        }
    }

    /** PUT (原版 C): 放入成品地图; 没有成品时收尾并加载下一张 */
    private void onPut() {
        ItemStack filled = this.scanInventory(stack -> stack.getItem() == Items.FILLED_MAP);
        if (filled.isEmpty()) {
            this.finishPainting();
            return;
        }
        MapItemSavedData data = MapItem.getSavedData(filled, mc.level);
        if (data != null && !data.locked) {
            this.state = State.LOCK;
            return;
        }
        if (this.autoName.get()) {
            Component customName = filled.getCustomName();
            if (customName == null || !this.mapName.equals(customName.getString())) {
                this.state = State.NAME;
                return;
            }
        }
        BlockPos stand = this.raiseToPlayerY(this.outputChest);
        if (this.fartherThan(stand, 1.0)) {   // 原版 C: 脚部距离 a(pos, 1.0), pathTo range 0
            Chat.info("前往放入成品");
            this.pathTo(stand, 0, State.PUT);
            return;
            }
        this.openContainer(this.outputChest, menu -> {
            if (this.outputShiftSent) return;
            Chat.info("放入成品地图");
            this.outputInventoryFilledCount = countFilledMapsInInventory();
            InvUtils.shiftClickInv(this.invScanSlot());
            this.outputShiftSent = true;
            this.outputConfirmTicks = 2;
            this.outputWaitTicks = 0;
            this.state = State.OUTPUT_CONFIRM;
            this.k();
        });
    }

    /**
     * 原版 C 无成品地图分支 (y() 关界面 -> N() 移除投影 -> M() 加载下一张 -> 「暂停一会, 等待水收回...」),
     * 独立版在中间插入 momomap 归档: 当前投影文件移入 schematics/momomap/complete。
     */
    private void finishPainting() {
        this.delayedState = State.LOAD_SCHEMATIC;
        this.closeScreenTransition();       // 原版 y(): 关闭输出箱等界面
        this.removeCurrentPlacement();      // 原版 N()
        this.currentPlacement = null;
        if (this.currentQueueFile != null) {
            try {
                Path complete = MOMOMAP_DIR.resolve("complete");
                Files.createDirectories(complete);
                Path target = complete.resolve(this.currentQueueFile.getFileName());
                try {
                    Files.move(this.currentQueueFile, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailure) {
                    Files.move(this.currentQueueFile, target);
                }
                this.currentQueueFile = null;
                this.currentMapBaseName = null;
            } catch (IOException failure) {
                Chat.warning("无法移动已完成投影, 稍后重试: %s", failure.getMessage());
                this.state = State.PUT;
                this.d(20);
                return;
            }
        }
        this.outputShiftSent = false;
        this.scanCooldown = 0;
        this.state = State.LOAD_SCHEMATIC;  // 原版 M(): 加载下一张
        Chat.info("暂停一会, 等待水收回...");
        this.d(this.pauseTicks.get());
    }

    private int countFilledMapsInInventory() {
        int count = 0;
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) if (mc.player.getInventory().getItem(i).getItem() == Items.FILLED_MAP) count++;
        return count;
    }

    private boolean outputHasFilledMap() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu instanceof InventoryMenu) return false;
        int count = Math.max(0, menu.slots.size() - MAIN_INVENTORY_SIZE);
        for (int i = 0; i < count; i++) if (menu.getSlot(i).getItem().getItem() == Items.FILLED_MAP) return true;
        return false;
    }

    /** 确认成品地图已进入输出箱 (防丢) 后回到 PUT 继续放剩余的成品 */
    private void confirmOutput() {
        if (this.outputConfirmTicks > 0) {
            this.outputConfirmTicks--;
            return;
        }
        boolean received = countFilledMapsInInventory() < this.outputInventoryFilledCount && outputHasFilledMap();
        if (!received && this.outputWaitTicks < 60) {
            this.outputWaitTicks++;
            return;
        }
        this.outputWaitTicks = 0;
        this.outputShiftSent = false;
        this.state = State.PUT;
        this.k();
    }

    /** SORT (原版 O): 独立版没有快捷物品操作模块, 跳过排序后回 TAKE_ITEM (原版 c(au_0.t)) */
    private void onSort() {
        Chat.warning("独立版没有快捷物品操作模块, 跳过排序");
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        this.state = State.TAKE_ITEM;
        this.k();
    }

    /** NAME (原版 USE/au_0.I, handler E): 铁砧命名成品地图, 命名用投影文件名 */
    private void onName() {
        if (!this.autoName.get()) {
            this.onPut();
            return;
        }
        if (mc.player.containerMenu instanceof AnvilMenu anvilMenu) {
            if (anvilMenu.getSlot(0).getItem().isEmpty()) {
                ItemStack current = this.scanInventory(this::isMapToName);
                if (current.isEmpty()) {
                    this.delayedTransition(State.PUT);
                    return;
                }
                InvUtils.shiftClickInv(this.invScanSlot());
            } else {
                ItemStack result = anvilMenu.getSlot(2).getItem();
                String name = this.mapName == null ? "" : this.mapName;
                Component customName = result.getCustomName();
                if (customName == null || !name.equals(customName.getString())) {
                    Chat.info("命名地图: %s", name);
                    anvilMenu.setItemName(name);
                    if (mc.screen instanceof AnvilScreen anvilScreen) {
                        EditBox box = getAnvilNameBox(anvilScreen);
                        if (box != null) {
                            box.setValue(name);
                            box.setFocused(false);
                        }
                    }
                    mc.player.connection.send(new ServerboundRenameItemPacket(name));
                } else {
                    Chat.info("命名完成: %s", name);
                    InvUtils.shiftClick(2);
                    this.delayedTransition(State.PUT);
                    return;
                }
            }
            this.k();
            return;
        }
        if (mc.player.experienceLevel < 1) {
            FindItemResult xp = InvUtils.find(stack -> stack.getItem() == Items.EXPERIENCE_BOTTLE);
            if (!xp.found()) {
                Chat.info("缺少XP, 前往补给");
                this.supplyReturn = State.DRAW;
                this.state = State.SUPPLY;
                return;
            }
            InvUtils.swapWithSelected(xp.slot);
            PlaceUtils.packetRotate(mc.player.getYRot(), 90.0f);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            this.d(20);
            return;
        }
        BlockPos stand = this.raiseToPlayerY(this.anvilPos);
        if (this.fartherThan(stand, 1.0)) {   // 原版 E: 脚部距离 a(pos, 1.0), pathTo range 0
            Chat.info("前往铁砧");
            this.pathTo(stand, 0, State.NAME);
            return;
            }
        if (!this.anvilPlaced()) {
            FindItemResult anvil = InvUtils.find(Items.ANVIL);
            if (!anvil.found()) {
                Chat.info("铁砧损坏且背包没有铁砧, 前往补给");
                this.supplyReturn = State.DRAW;
                this.state = State.SUPPLY;
                return;
            }
            Chat.info("补放铁砧");
            InvUtils.swapWithSelected(anvil.slot);
            PlaceUtils.placeFromHotbar(this.anvilPos, mc.player.getInventory().getSelectedSlot(), true, Direction.DOWN);
            this.k();
            return;
        }
        if (mc.player.containerMenu instanceof InventoryMenu) {
            if (stand != this.anvilPos) {
                PlaceUtils.openFacing(this.anvilPos, Direction.UP);
            } else {
                PlaceUtils.openAuto(this.anvilPos);
            }
            this.k();
        }
    }

    /** 原版 E 的谓词 a(ItemStack): 待命名的成品地图 (未命名或名字 != 投影名) */
    private boolean isMapToName(ItemStack stack) {
        if (stack.getItem() != Items.FILLED_MAP) return false;
        Component customName = stack.getCustomName();
        return customName == null || !this.mapName.equals(customName.getString());
    }

    /** 铁砧位置是否放着铁砧 (原版 instanceof AnvilBlock 判定, 含残损铁砧) */
    private boolean anvilPlaced() {
        return this.anvilPos != null && mc.level != null
                && mc.level.getBlockState(this.anvilPos).getBlock() instanceof AnvilBlock;
    }

    /**
     * CHECK_BACKPACK (原版 J): 计算缺物。
     * 地毯与原版一致 (needed 对比背包地毯数); 另外把 SUPPLY 的前置物资判定
     * (空地图/玻璃板/铁砧/经验瓶) 纳入缺物计算, 使开印前先去补给箱备齐 (用户实测的原版顺序)。
     */
    private void checkBackpack() {
        Map<Item, Integer> have = countCarpetsInInventory();
        this.missing.clear();
        for (Map.Entry<Item, Integer> entry : this.needed.entrySet()) {
            Integer count = have.get(entry.getKey());
            if (count == null) {
                this.missing.put(entry.getKey(), entry.getValue());
            } else if (count < entry.getValue()) {
                this.missing.put(entry.getKey(), entry.getValue() - count);
            }
        }
        if (this.missing.isEmpty()) {
            this.state = State.NEXT;
        } else {
            this.supplyItem = null;
            this.state = State.TAKE_ITEM;
        }
    }

    /** TAKE_ITEM (原版 I): 按缺物地毯去各自补给箱补货 */
    private void takeItem() {
        if (this.supplyItem == null) {
            boolean anyCarpet = false;
            for (Item item : this.missing.keySet()) {
                if (CartoItems.isCarpet(item)) {
                    anyCarpet = true;
                    break;
                }
            }
            if (!anyCarpet) {
                this.state = State.NEXT;
                return;
            }
            if (this.rowIndex < 0 || this.rowIndex >= this.rows.size()) {
                this.scanRowRequirements();
                return;
            }
            List<BlockPos> row = this.rows.get(this.rowIndex);
            int index = Math.clamp((long) this.columnIndex, 0, row.size() - 1);
            Vec3 anchorCenter = row.get(index).getCenter();
            Vec3 playerPos = mc.player.position();
            double best = Double.MAX_VALUE;
            BlockPos bestPos = null;
            Item bestItem = null;
            for (Item item : this.missing.keySet()) {
                if (!CartoItems.isCarpet(item)) continue;
                List<BlockPos> points = this.supplyPoints.get(item);
                if (points == null) continue;
                for (BlockPos point : points) {
                    Vec3 center = point.getCenter();
                    double d = Math.min(anchorCenter.distanceTo(center), playerPos.distanceTo(center));
                    if (d >= best) continue;
                    best = d;
                    bestPos = point;
                    bestItem = item;
                }
            }
            if (bestPos == null) {
                String names = this.missing.keySet().stream()
                        .filter(CartoItems::isCarpet)
                        .map(Names::get).collect(Collectors.joining(","));
                this.failState("无法补货, 缺少补给点: " + names);
                return;
            }
            this.supplyTarget = bestPos;
            this.supplyItem = bestItem;
            this.supplyAmount = this.missing.remove(bestItem) + this.extraSupply.get();
        }
        if (this.fartherThan(this.supplyTarget, 3.0)) {   // 原版 I: 脚部距离 a(K, 3.0)
            Chat.info("前往补货: %s = %s", Names.get(this.supplyItem), this.supplyAmount);
            this.pathTo(this.supplyTarget, 2, State.TAKE_ITEM);
            return;
        }
        this.openContainer(this.supplyTarget, menu -> {
            if (this.inventoryFull()) {
                Chat.info("背包满了, 尝试清理");
                Map<Item, Integer> have = countCarpetsInInventory();
                Item excess = findExcessItem(have);
                if (excess != Items.AIR && !this.scanInventory(stack -> stack.getItem() == excess).isEmpty()) {
                    Chat.info("丢弃无用地毯: %s", Names.get(excess));
                    InvUtils.dropSlot(this.invScanSlot());
                    this.k();
                    return;
                }
                Chat.warning("无法清理背包, 尝试整理");
                this.delayedTransition(State.SORT);
                return;
            }
            ItemStack stack = this.scanContainer(item -> item.getItem() == this.supplyItem);
            if (stack.isEmpty()) {
                Chat.warning("无法补货: %s, 稍后重启", Names.get(this.supplyItem));
                this.restart(State.CHECK_BACKPACK);
                return;
            }
            int taken = stack.getCount();
            InvUtils.shiftClick(this.containerScanSlot());
            if (this.supplyAmount <= taken) {
                this.supplyItem = null;
                // 原版 a(au_0.t): 关闭箱子后回 TAKE_ITEM 重新计算缺物
                this.delayedTransition(State.TAKE_ITEM);
            } else {
                this.supplyAmount -= taken;
            }
            this.k();
        });
    }

    // ==================================================================
    // 打印机对接 (Lotus Q/R/S + af)
    // ==================================================================

    private void startPrinter() {
        this.printer.setSupplier(() -> {
            if (this.anchor == null) return Collections.emptyList();
            if (mc.player.position().distanceTo(this.anchor.getCenter()) > 10.0) return Collections.emptyList();
            return this.placementPositions(this.anchor);   // d(O)
        });
        this.printer.activate();
    }

    /** 所有行打印完毕 (原版 S): 「地毯打印完毕, 开始绘制地图」-> SUPPLY 备齐绘图物资 -> DRAW */
    private void finishAll() {
        Chat.info("地毯打印完毕, 开始绘制地图");
        this.printer.deactivate();
        this.supplyReturn = State.DRAW;
        this.state = State.SUPPLY;
    }

    private void goReady() {
        this.state = State.READY;
        this.k();
    }

    private void goNext() {
        this.state = State.NEXT;
        this.k();
    }

    // ==================================================================
    // 行/位置计算 (Lotus J/I/K/c/d/a)
    // ==================================================================

    /** 扫描当前行需要的材料 (Lotus K) */
    private void scanRowRequirements() {
        int range = this.printRange.get();
        int band = range * 2 + 1;
        int previousRow = this.rowIndex;
        do {
            if (this.rowIndex >= this.rows.size()) {
                this.finishAll();
                return;
            }
            this.needed.clear();
            int start = band * this.rowIndex;
            int end = Math.min(start + band - 1, MAP_SIZE - 1);
            start = Math.clamp((long) start, 0, end - range);
            for (int z = start; z <= end; z++) {
                for (int x = 0; x < MAP_SIZE; x++) {
                    BlockPos pos = this.mapOrigin.offset(x, 0, z);
                    BlockState schematicState = this.schematicWorld.getBlockState(pos);
                    if (schematicState.isAir()) continue;
                    BlockState realState = mc.level.getBlockState(pos);
                    if (schematicState.getBlock().asItem() == realState.getBlock().asItem()) continue;
                    if (realState.getBlock() == Blocks.VOID_AIR) {
                        Chat.warning("区块未加载, 稍后重启");
                        this.restart(State.NEXT);
                        return;
                    }
                    this.needed.merge(schematicState.getBlock().asItem(), 1, Integer::sum);
                }
            }
            if (!this.needed.isEmpty()) break;
            this.rowIndex++;
        } while (this.rowIndex < this.rows.size());
        if (this.needed.isEmpty()) {
            if (previousRow == 0) {
                this.finishAll();
            } else {
                this.rowIndex = 0;
                this.columnIndex = 0;
            }
            return;
        }
        Chat.info("开始打印第[%s]行, 共[%s]行", this.rowIndex + 1, this.rows.size());
        this.columnIndex = 0;
        this.checkBackpack();   // I()
    }

    /** 背包内地毯数量统计 (Lotus K) */
    private static Map<Item, Integer> countCarpetsInInventory() {
        Inventory inventory = mc.player.getInventory();
        HashMap<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            Item item = stack.getItem();
            if (!CartoItems.isCarpet(item)) continue;
            counts.merge(item, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** 找超量的无用代理... 无用地毯 (Lotus s_0.a(Map)) */
    private Item findExcessItem(Map<Item, Integer> have) {
        for (Map.Entry<Item, Integer> entry : have.entrySet()) {
            Item item = entry.getKey();
            if (this.needed.containsKey(item)) {
                if (entry.getValue() <= this.needed.get(item) + 64) continue;
                return item;
            }
            return item;
        }
        return Items.AIR;
    }

    /**
     * 打印机供应商: 锚点附近的待放置位置 (Lotus w_0.c(BlockPos), 行 1000-1013)。
     * x 偏移限定 ±range (偶数行 -range..+range, 奇数行 +range..-range),
     * z 用蛇形顺序 (a(BlockPos,int,int,List)); 覆盖与 crossSection 的巡检一致且都在玩家可及范围内。
     */
    private List<BlockPos> placementPositions(BlockPos anchor) {
        int range = this.printRange.get();
        ArrayList<BlockPos> result = new ArrayList<>();
        if (this.rowIndex % 2 == 0) {
            for (int dx = -range; dx <= range; dx++) {
                this.collectZigZag(anchor, range, dx, result);
            }
        } else {
            for (int dx = range; dx >= -range; dx--) {
                this.collectZigZag(anchor, range, dx, result);
            }
        }
        return result;
    }

    /** 蛇形 z 扫描 (Lotus s_0.a(BlockPos,int,int,List)) */
    private void collectZigZag(BlockPos anchor, int range, int dx, List<BlockPos> result) {
        int[] zOffsets = new int[range * 2 + 1];
        int index = 1;
        int value = 1;
        while (value <= range) {
            zOffsets[index] = -value;
            index++;
            zOffsets[index] = value;
            value++;
            index++;
        }
        for (int dz : zOffsets) {
            BlockPos pos = anchor.offset(dx, 0, dz);
            BlockState schematicState = this.schematicWorld.getBlockState(pos);
            if (schematicState.isAir()) continue;
            BlockState realState = mc.level.getBlockState(pos);
            if (schematicState.getBlock().asItem() == realState.getBlock().asItem()) continue;
            if (!realState.isAir() && realState.getBlock() != Blocks.WATER) continue;
            result.add(pos);
        }
    }

    /** 锚点周围的横截面 (Lotus w_0.a(BlockPos), 行 978-998: NEXT 巡检用宽 x 范围) */
    private List<BlockPos> crossSection(BlockPos anchor) {
        int range = this.printRange.get();
        int band = range * 2 + 1;
        ArrayList<BlockPos> result = new ArrayList<>((range + band + 1) * band);
        if (this.rowIndex % 2 == 0) {
            int from = Math.max(-range - band, this.mapOrigin.getX() - anchor.getX());
            for (int dx = from; dx <= 1; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    result.add(anchor.offset(dx, 0, dz));
                }
            }
        } else {
            int from = Math.min(range + band, this.mapOrigin.getX() + MAP_SIZE - 1 - anchor.getX());
            for (int dx = from; dx >= -1; dx--) {
                for (int dz = -range; dz <= range; dz++) {
                    result.add(anchor.offset(dx, 0, dz));
                }
            }
        }
        return result;
    }

    /** 锚点附近是否全部放置完毕 (Lotus s_0.a(BlockPos)) */
    private boolean allPlaced(BlockPos anchor) {
        return this.placementPositions(anchor).isEmpty();
    }

    // ==================================================================
    // 初始化 (Lotus s_0.a(Boolean)/N)
    // ==================================================================

    private void onInitSettingChanged() {
        this.init(this.initSetting.get());
    }

    private void init(boolean enabled) {
        if (!enabled) return;
        this.initSetting.set(false);
        if (mc.player == null || mc.level == null) {
            Chat.warning("玩家或世界未加载");
            return;
        }
        this.resetRunState();   // T()
        BlockPos playerPos = mc.player.blockPosition();
        int originX = Math.floorDiv(playerPos.getX() + 64, MAP_SIZE) * MAP_SIZE - 64;
        int originZ = Math.floorDiv(playerPos.getZ() + 64, MAP_SIZE) * MAP_SIZE - 64;
        this.mapOrigin = new BlockPos(originX, playerPos.getY(), originZ);
        this.mapCenter = this.mapOrigin.offset(64, 0, 64);
        AABB box = new AABB(playerPos.getX() - 128.0, playerPos.getY() - 2.0, playerPos.getZ() - 128.0,
                playerPos.getX() + 128.0, playerPos.getY() + 4.0, playerPos.getZ() + 128.0);
        List<ItemFrame> frames = mc.level.getEntities(EntityTypeTest.forClass(ItemFrame.class), box,
                frame -> !frame.getItem().isEmpty());
        this.supplyPoints.clear();
        this.supplyChest = null;
        this.outputChest = null;
        for (ItemFrame frame : frames) {
            BlockPos framePos = frame.getPos();
            if (framePos == null) continue;
            BlockPos chestPos = framePos.relative(frame.getNearestViewDirection().getOpposite());
            Block block = mc.level.getBlockState(chestPos).getBlock();
            if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST) continue;
            Item item = frame.getItem().getItem();
            if (item == Items.REDSTONE) {
                this.supplyChest = chestPos;
                continue;
            }
            if (item == Items.REDSTONE_BLOCK) {
                this.outputChest = chestPos;
                continue;
            }
            // 原版: 补给点只登记地毯 (空地图/玻璃板/铁砧/经验瓶统一放红石标记的补给箱)
            if (!CartoItems.isCarpet(item)) continue;
            this.supplyPoints.computeIfAbsent(item, key -> new ArrayList<>()).add(chestPos);
        }
        if (this.supplyChest == null) {
            Chat.warning("未识别到<补给>容器");
            return;
        }
        if (this.outputChest == null) {
            Chat.warning("未识别到<输出>容器");
            return;
        }
        this.cartographyTable = this.findCartographyTable();
        if (this.cartographyTable == null) {
            Chat.warning("未识别到<制图台>");
            return;
        }
        this.anvilPos = this.findAnvil();
        if (this.anvilPos == null) {
            Chat.warning("未识别到<铁砧>");
            return;
        }
        if (this.supplyPoints.size() < CartoItems.CARPETS.size()) {
            List<Item> lacking = new ArrayList<>(CartoItems.CARPETS);
            lacking.removeAll(this.supplyPoints.keySet());
            String names = lacking.stream().map(Names::get).collect(Collectors.joining(","));
            Chat.warning("缺少地毯容器: %s", names);
            return;
        }
        // 行规划 (Lotus w_0 a(Boolean) 行 918-957: do-while + break block19 跳过末尾补行)
        this.rows.clear();
        int range = this.printRange.get();
        int step = 2;
        int band = range * 2 + 1;
        int startX = range;
        int endX = MAP_SIZE - range;
        int z = range;
        boolean brokeOut = false;   // 原版 break block19: 直接跳出, 不再执行末尾 if
        do {
            List<BlockPos> row = new ArrayList<>();
            for (int x = startX; x <= endX; x += step) {
                row.add(this.mapOrigin.offset(x, 0, z));
            }
            row.add(this.mapOrigin.offset(127, 0, z));
            this.rows.add(row);
            z += band;
            if (z >= MAP_SIZE) {
                if (z < MAP_SIZE + range) {
                    List<BlockPos> extra = new ArrayList<>();
                    for (int x = endX; x >= 0; x -= step) {
                        extra.add(this.mapOrigin.offset(x, 0, endX));
                    }
                    extra.add(this.mapOrigin.offset(0, 0, z));
                    this.rows.add(extra);
                }
                brokeOut = true;
                break;
            }
            List<BlockPos> rowBack = new ArrayList<>();
            for (int x = endX; x >= 0; x -= step) {
                rowBack.add(this.mapOrigin.offset(x, 0, z));
            }
            rowBack.add(this.mapOrigin.offset(0, 0, z));
            this.rows.add(rowBack);
            z += band;
        } while (z < MAP_SIZE);
        if (!brokeOut && z < MAP_SIZE + range) {
            List<BlockPos> last = new ArrayList<>();
            for (int x = startX; x < MAP_SIZE; x += step) {
                last.add(this.mapOrigin.offset(x, 0, endX));
            }
            last.add(this.mapOrigin.offset(127, 0, z));
            this.rows.add(last);
        }
        int total = this.supplyPoints.values().stream().mapToInt(List::size).sum();
        Chat.warning("初始化成功, 共[%s]个补给点, 共需打印[%s]行", total, this.rows.size() + 1);
    }

    /** 找制图台 (Lotus N) */
    private BlockPos findCartographyTable() {
        int range = 8;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -range; dx < range; dx++) {
                for (int dz = -range; dz < range; dz++) {
                    BlockPos pos = this.mapCenter.offset(dx, dy, dz);
                    if (mc.level.getBlockState(pos).getBlock() == Blocks.CARTOGRAPHY_TABLE) return pos;
                }
            }
        }
        return null;
    }

    /** 找附近的铁砧 (Lotus s_0.findAnvil) */
    private BlockPos findAnvil() {
        if (this.anvilPos != null) return this.anvilPos;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -8; dx < 8; dx++) {
                for (int dz = -8; dz < 8; dz++) {
                    BlockPos pos = this.mapCenter.offset(dx, dy, dz);
                    Block block = mc.level.getBlockState(pos).getBlock();
                    if (block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL) return pos;
                }
            }
        }
        return null;
    }

    // ==================================================================
    // 投影管理 (Lotus M/b/U)
    // ==================================================================

    private void removeCurrentPlacement() {
        SchematicPlacement selected = this.currentPlacement != null ? this.currentPlacement : placementManager.getSelectedSchematicPlacement();
        if (selected != null && selected.isEnabled()) {
            Chat.info("移除投影: %s", selected.getName());
            selected.setEnabled(false);
            placementManager.removeSchematicPlacement(selected);
        }
    }

    /** Scan momomap at a throttled cadence; only stable direct regular files enter the queue. */
    private void scanQueue() {
        if (this.scanCooldown > 0 || this.currentQueueFile != null) return;
        this.scanCooldown = 30;
        if (!Files.isDirectory(MOMOMAP_DIR)) {
            try { Files.createDirectories(MOMOMAP_DIR); } catch (IOException ignored) { }
            return;
        }
        List<Path> candidates;
        try (var stream = Files.list(MOMOMAP_DIR)) {
            candidates = stream.filter(Files::isRegularFile)
                    .filter(path -> !this.skippedFiles.contains(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".litematic"))
                    .sorted((a, b) -> {
                        try {
                            BasicFileAttributes aa = Files.readAttributes(a, BasicFileAttributes.class);
                            BasicFileAttributes bb = Files.readAttributes(b, BasicFileAttributes.class);
                            int c = aa.creationTime().compareTo(bb.creationTime());
                            if (c == 0) c = aa.lastModifiedTime().compareTo(bb.lastModifiedTime());
                            if (c == 0) c = a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                            return c;
                        } catch (IOException e) { return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()); }
                    }).toList();
        } catch (IOException ignored) { return; }
        if (candidates.isEmpty()) {
            this.stableCandidate = null;
            this.stableTicks = 0;
            return;
        }
        Path candidate = candidates.getFirst();
        try {
            long size = Files.size(candidate);
            long modified = Files.getLastModifiedTime(candidate).toMillis();
            if (!candidate.equals(this.stableCandidate) || size != this.stableSize || modified != this.stableModified) {
                this.stableCandidate = candidate;
                this.stableSize = size;
                this.stableModified = modified;
                this.stableTicks = 1;
                return;
            }
            if (++this.stableTicks < 2) return;
            this.currentQueueFile = candidate;
            this.stableCandidate = null;
            this.stableTicks = 0;
            if (this.state == State.LOAD_SCHEMATIC || this.state == State.NONE) this.state = State.LOAD_SCHEMATIC;
        } catch (IOException ignored) { }
    }

    // ==================================================================
    // 重置与杂项 (Lotus s_0.T + bl)
    // ==================================================================

    private void resetRunState() {
        this.needed.clear();
        this.missing.clear();
        this.obstacles.clear();
        this.supplyItem = null;
        this.supplyTarget = null;
        this.breakTarget = null;
        this.anchor = null;
        this.outputConfirmTicks = 0;
        this.outputWaitTicks = 0;
        this.outputShiftSent = false;
        this.supplyReturn = State.DRAW;
        this.pendingState = null;
        this.delayedState = State.NONE;
        this.nextStuckTicks = 0;
        this.walkingTicks = 0;
        this.lastGoal = null;
    }

    /** 当前服务器标识 (Lotus bl.b: 服务器名/世界名, 可用作文件路径) */
    public static String serverFolderName() {
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            return server.ip.replaceAll("[\\\\/:*?\"<>|]", "_");
        }
        if (mc.getSingleplayerServer() != null) {
            return mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "other";
    }

    /** 当前维度标识 (Lotus bl.a) */
    public static String dimensionName() {
        if (mc.level != null) {
            return mc.level.dimension().identifier().toString();
        }
        return "other";
    }

    /** 反射获取铁砧界面的名称输入框 (Lotus 通过 mixin 访问 AnvilScreen.name) */
    private static EditBox getAnvilNameBox(AnvilScreen screen) {
        try {
            if (anvilNameField == null) {
                for (Field field : AnvilScreen.class.getDeclaredFields()) {
                    if (field.getType() != EditBox.class) continue;
                    field.setAccessible(true);
                    anvilNameField = field;
                    break;
                }
            }
            return anvilNameField == null ? null : (EditBox) anvilNameField.get(screen);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
