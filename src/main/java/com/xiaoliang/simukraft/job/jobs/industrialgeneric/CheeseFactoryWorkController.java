package com.xiaoliang.simukraft.job.jobs.industrialgeneric;

import com.xiaoliang.simukraft.building.IndustrialBuildingConfig;
import com.xiaoliang.simukraft.building.PlacedBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.init.ModBlocks;
import com.xiaoliang.simukraft.utils.ContainerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 奶酪工厂专用工作状态机。
 * 按老版本流程拆出专属控制，避免污染通用工业配方逻辑。
 */
@SuppressWarnings("null")
public final class CheeseFactoryWorkController {
    private static final String BUILDING_ID = "cheeseFactory";
    private static final int CHEST_SEARCH_RADIUS = 8;
    private static final int MAX_POUR_BUCKETS = 20;
    private static final int POUR_ACTION_TICKS = 100;
    private static final int CHECK_VISCOSITY_TICKS = 40;
    private static final int SECRET_INGREDIENT_TICKS = 100;
    private static final int COAGULATE_TICKS = 100;
    private static final long ACTION_SWING_INTERVAL_TICKS = 10L;
    private static final BlockPos[] CONTAINER_ANCHORS = new BlockPos[]{
            new BlockPos(2, 1, 0),
            new BlockPos(2, 1, 1)
    };

    // 用户已改需求：奶酪工人不再上二楼，改为一楼指定地毯点位倾倒和操作。
    private static final BlockPos[] ROUTE_TO_POUR = new BlockPos[0];
    private static final BlockPos[] ROUTE_TO_STIR = new BlockPos[0];
    private static final BlockPos[] ROUTE_TO_CHEST = new BlockPos[]{
            new BlockPos(-7, 1, -8),
            new BlockPos(-5, 1, -5),
            new BlockPos(-3, 1, -2),
            new BlockPos(1, 1, 0)
    };

    // 倾倒地毯：NBT 12 1 7 => 相对控制盒 (-1, 1, -5)
    private static final BlockPos POUR_POINT_ANCHOR = new BlockPos(-1, 1, -5);
    // 操作地毯：NBT 4 1 7 => 相对控制盒 (-9, 1, -5)
    private static final BlockPos PROCESS_POINT_ANCHOR = new BlockPos(-9, 1, -5);

    // 可视倾倒区：NBT 8 1 4 到 9 5 5，按从上到下排序，共 20 格。
    private static final List<BlockPos> POUR_VISUAL_POSITIONS = createPourVisualPositions();
    // 操作/收集区：NBT 3 1 4 到 14 1 5。
    private static final List<BlockPos> PROCESS_POOL_POSITIONS = createProcessPoolPositions();

    private static final String[] PROCESS_STATUS_KEYS = new String[]{
            "gui.npc.status.cheese_factory_check_viscosity",
            "gui.npc.status.cheese_factory_secret_ingredient",
            "gui.npc.status.cheese_factory_coagulating"
    };
    private static final int[] PROCESS_STEP_DURATIONS = new int[]{
            CHECK_VISCOSITY_TICKS,
            SECRET_INGREDIENT_TICKS,
            COAGULATE_TICKS
    };

    private static final ConcurrentMap<BlockPos, CheeseFactoryState> STATES = new ConcurrentHashMap<>();

    private CheeseFactoryWorkController() {
    }

    public static boolean handles(@Nullable IndustrialBuildingConfig config, @Nullable String buildingFileName) {
        if (config != null && BUILDING_ID.equalsIgnoreCase(config.getBuildingId())) {
            return true;
        }
        return buildingFileName != null && BUILDING_ID.equalsIgnoreCase(buildingFileName);
    }

    public static boolean shouldSuppressWorkplacePull(@Nullable ServerLevel level,
                                                      @Nullable BlockPos workplacePos,
                                                      @Nullable CustomEntity npc,
                                                      @Nullable String buildingFileName) {
        if (!handles(null, buildingFileName) || level == null || workplacePos == null || npc == null) {
            return false;
        }

        BlockPos key = workplacePos.immutable();
        CheeseFactoryState state = STATES.get(key);
        PlacedBuildingManager.PlacedBuildingData building = getPlacedBuilding(workplacePos);
        if (building != null) {
            if (PlacedBuildingManager.isPosInBuilding(building.buildingId, npc.blockPosition())) {
                return true;
            }
            if (isActiveWorkStage(state)) {
                return npc.distanceToSqr(
                        workplacePos.getX() + 0.5D,
                        workplacePos.getY() + 1.0D,
                        workplacePos.getZ() + 0.5D
                ) <= 576.0D;
            }
            return false;
        }

        double distanceSqr = npc.distanceToSqr(
                workplacePos.getX() + 0.5D,
                workplacePos.getY() + 1.0D,
                workplacePos.getZ() + 0.5D
        );
        if (isActiveWorkStage(state)) {
            return distanceSqr <= 576.0D && Math.abs(npc.getBlockY() - workplacePos.getY()) <= 16;
        }
        return distanceSqr <= 256.0D && Math.abs(npc.getBlockY() - workplacePos.getY()) <= 12;
    }

    public static void resetRuntimeState() {
        STATES.clear();
    }

    public static void onNpcAssigned(CustomEntity npc, ServerLevel level, BlockPos buildingPos) {
        if (npc == null || level == null || buildingPos == null) {
            return;
        }
        BlockPos key = buildingPos.immutable();
        CheeseFactoryState state = new CheeseFactoryState();
        state.rotation = resolveRotation(level, key, null);
        STATES.put(key, state);
        ensureWorkingState(npc);
        setStage(state, resolveResumeStage(level, key, state));
        updateHeldItemForStage(npc, state.stage);
        updateStatusLabelForStage(npc, state);
    }

    public static void restoreNpcAfterRest(CustomEntity npc, ServerLevel level, BlockPos buildingPos) {
        if (npc == null || level == null || buildingPos == null) {
            return;
        }
        BlockPos key = buildingPos.immutable();
        CheeseFactoryState state = STATES.computeIfAbsent(key, ignored -> new CheeseFactoryState());
        state.rotation = resolveRotation(level, key, state.rotation);
        setStage(state, resolveResumeStage(level, key, state));
        ensureWorkingState(npc);
        updateHeldItemForStage(npc, state.stage);
        updateStatusLabelForStage(npc, state);
    }

    /**
     * 奶酪工厂需要在首次雇佣后的启动阶段持续推进状态机，
     * 不能完全受普通工业工作时间限制，否则夜间首次雇佣会直接停在挂机态。
     */
    public static boolean shouldRunOutsideWorkTime(ServerLevel level, BlockPos buildingPos) {
        if (level == null || buildingPos == null) {
            return false;
        }
        BlockPos key = buildingPos.immutable();
        CheeseFactoryState state = STATES.get(key);
        if (isActiveWorkStage(state)) {
            return true;
        }

        CheeseFactoryState previewState = new CheeseFactoryState();
        previewState.rotation = resolveRotation(level, key, state != null ? state.rotation : null);
        return resolveNextStage(level, key, previewState) != Stage.WAITING;
    }

    public static void tickWork(CustomEntity npc,
                                BlockPos buildingPos,
                                ServerLevel level,
                                @Nullable IndustrialBuildingConfig config) {
        if (npc == null || buildingPos == null || level == null) {
            return;
        }

        BlockPos key = buildingPos.immutable();
        CheeseFactoryState state = STATES.computeIfAbsent(key, ignored -> new CheeseFactoryState());
        state.rotation = resolveRotation(level, key, state.rotation);
        ensureWorkingState(npc);

        if (state.carriedCheeseBlocks > 0 && state.stage != Stage.MOVE_TO_CHEST && state.stage != Stage.STORE_CHEESE) {
            setStage(state, Stage.MOVE_TO_CHEST);
            state.operationIndex = 0;
        }

        if (state.stage == Stage.WAITING) {
            setStage(state, resolveNextStage(level, key, state));
        }

        switch (state.stage) {
            case MOVE_TO_POUR -> tickMoveToPour(npc, level, key, state);
            case POUR_MILK -> tickPourMilk(npc, level, key, state);
            case MOVE_TO_STIR -> tickMoveToStir(npc, level, key, state);
            case PROCESSING -> tickProcessing(npc, level, key, state);
            case HARVEST_CHEESE -> tickHarvestCheese(npc, level, key, state);
            case MOVE_TO_CHEST -> tickMoveToChest(npc, level, key, state);
            case STORE_CHEESE -> tickStoreCheese(npc, level, key, state);
            case WAITING -> tickWaiting(npc, level, key, state, config);
        }
    }

    private static void tickMoveToPour(CustomEntity npc, ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        npc.setStatusLabel("gui.npc.status.cheese_factory_go_pour");
        updateHeldItemForStage(npc, Stage.MOVE_TO_POUR);

        if (!canStartBatchPour(level, buildingPos)) {
            setStage(state, hasMilkInPool(level, buildingPos) ? Stage.MOVE_TO_STIR : Stage.WAITING);
            return;
        }

        BlockPos target = findStandPos(level, buildingPos, toWorldPos(buildingPos, state.rotation, POUR_POINT_ANCHOR), 0, 0);
        if (followRoute(npc, level, buildingPos, state, ROUTE_TO_POUR, target)) {
            setStage(state, Stage.POUR_MILK);
        }
    }

    private static void tickPourMilk(CustomEntity npc, ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        npc.setStatusLabelForTicks("gui.npc.status.cheese_factory_pouring_milk", 40);
        updateHeldItemForStage(npc, Stage.POUR_MILK);

        if (!hasEmptyPoolSlot(level, buildingPos)) {
            setStage(state, hasMilkInPool(level, buildingPos) ? Stage.MOVE_TO_STIR : resolveNextStage(level, buildingPos, state));
            return;
        }

        if (state.pendingMilkBuckets <= 0) {
            if (!canStartBatchPour(level, buildingPos)) {
                setStage(state, hasMilkInPool(level, buildingPos) ? Stage.MOVE_TO_STIR : Stage.WAITING);
                return;
            }
            if (!consumeNearbyItem(level, buildingPos, Items.MILK_BUCKET, MAX_POUR_BUCKETS)) {
                setStage(state, hasMilkInPool(level, buildingPos) ? Stage.MOVE_TO_STIR : Stage.WAITING);
                return;
            }
            returnEmptyBuckets(level, buildingPos, MAX_POUR_BUCKETS);
            state.pendingMilkBuckets = MAX_POUR_BUCKETS;
            state.pouredBuckets = 0;
            state.nextActionGameTime = 0L;
        }

        if (!isActionScheduled(state)) {
            scheduleNextAction(level, state, POUR_ACTION_TICKS);
        }
        if (!isActionReady(level, state)) {
            swingWorkingHand(npc, level);
            return;
        }

        int pouredCount = pourPendingMilkBatch(level, buildingPos, state);
        state.nextActionGameTime = 0L;
        if (pouredCount <= 0) {
            clearPendingMilkBatch(state);
            setStage(state, hasMilkInPool(level, buildingPos) ? Stage.MOVE_TO_STIR : Stage.WAITING);
            return;
        }
        setStage(state, Stage.MOVE_TO_STIR);
    }

    private static void tickMoveToStir(CustomEntity npc, ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        npc.setStatusLabel("gui.npc.status.cheese_factory_go_stir");
        updateHeldItemForStage(npc, Stage.MOVE_TO_STIR);

        if (!hasMilkInPool(level, buildingPos)) {
            setStage(state, resolveNextStage(level, buildingPos, state));
            return;
        }

        BlockPos target = findStandPos(level, buildingPos, toWorldPos(buildingPos, state.rotation, PROCESS_POINT_ANCHOR), 0, 0);
        if (followRoute(npc, level, buildingPos, state, ROUTE_TO_STIR, target)) {
            setStage(state, Stage.PROCESSING);
            state.operationIndex = 0;
        }
    }

    private static void tickProcessing(CustomEntity npc, ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        if (!hasMilkInPool(level, buildingPos)) {
            setStage(state, resolveNextStage(level, buildingPos, state));
            state.operationIndex = 0;
            state.nextActionGameTime = 0L;
            return;
        }

        int step = Math.max(0, Math.min(PROCESS_STATUS_KEYS.length - 1, state.operationIndex));
        npc.setStatusLabelForTicks(PROCESS_STATUS_KEYS[step], 40);
        updateHeldItemForStage(npc, Stage.PROCESSING);
        if (!isActionScheduled(state)) {
            scheduleNextAction(level, state, PROCESS_STEP_DURATIONS[step]);
        }
        if (!isActionReady(level, state)) {
            swingWorkingHand(npc, level);
            return;
        }

        if (step == PROCESS_STATUS_KEYS.length - 1) {
            coagulateMilkIntoCheese(level, buildingPos);
            clearPourVisualMilk(level, buildingPos);
            state.pouredBuckets = 0;
            state.operationIndex = 0;
            state.nextActionGameTime = 0L;
            if (hasCheeseInPool(level, buildingPos)) {
                setStage(state, Stage.HARVEST_CHEESE);
            } else {
                setStage(state, resolveResumeStage(level, buildingPos, state));
            }
            return;
        }

        state.operationIndex = Math.min(PROCESS_STATUS_KEYS.length - 1, state.operationIndex + 1);
        state.nextActionGameTime = 0L;
    }

    private static void tickHarvestCheese(CustomEntity npc, ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        npc.setStatusLabelForTicks("gui.npc.status.cheese_factory_collecting", 40);
        updateHeldItemForStage(npc, Stage.HARVEST_CHEESE);

        BlockPos cheesePos = findFirstCheesePos(level, buildingPos);
        if (cheesePos == null) {
            setStage(state, resolveNextStage(level, buildingPos, state));
            return;
        }

        level.setBlockAndUpdate(cheesePos, Blocks.AIR.defaultBlockState());
        state.carriedCheeseBlocks++;
        npc.swing(InteractionHand.MAIN_HAND);

        if (!hasCheeseInPool(level, buildingPos)) {
            setStage(state, Stage.MOVE_TO_CHEST);
        }
    }

    private static void tickMoveToChest(CustomEntity npc, ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        npc.setStatusLabel("gui.npc.status.cheese_factory_go_store");
        updateHeldItemForStage(npc, Stage.MOVE_TO_CHEST);

        BlockPos chestPos = findPrimaryChestPos(level, buildingPos);
        if (chestPos == null) {
            return;
        }

        BlockPos target = findStandPos(level, buildingPos, chestPos, 2, 2);
        if (followRoute(npc, level, buildingPos, state, ROUTE_TO_CHEST, target)) {
            setStage(state, Stage.STORE_CHEESE);
        }
    }

    private static void tickStoreCheese(CustomEntity npc, ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        npc.setStatusLabelForTicks("gui.npc.status.cheese_factory_storing", 40);
        updateHeldItemForStage(npc, Stage.STORE_CHEESE);

        if (state.carriedCheeseBlocks <= 0) {
            closeAnimatedChest(level, state.activeChestPos);
            state.activeChestPos = null;
            setStage(state, resolveNextStage(level, buildingPos, state));
            return;
        }

        BlockPos chestPos = findInsertableChestPos(level, buildingPos, ModBlocks.CHEESE_BLOCK_ITEM.get(), state.carriedCheeseBlocks);
        if (chestPos == null) {
            closeAnimatedChest(level, state.activeChestPos);
            state.activeChestPos = null;
            return;
        }

        if (state.activeChestPos == null || !state.activeChestPos.equals(chestPos)) {
            closeAnimatedChest(level, state.activeChestPos);
            state.activeChestPos = chestPos.immutable();
            openAnimatedChest(level, state.activeChestPos);
        }

        if (!isActionScheduled(state)) {
            scheduleNextAction(level, state, 20L);
        }
        if (!isActionReady(level, state)) {
            swingWorkingHand(npc, level);
            return;
        }

        if (!insertItemIntoChest(level, chestPos, ModBlocks.CHEESE_BLOCK_ITEM.get(), state.carriedCheeseBlocks)) {
            closeAnimatedChest(level, state.activeChestPos);
            state.activeChestPos = null;
            state.nextActionGameTime = 0L;
            return;
        }

        closeAnimatedChest(level, state.activeChestPos);
        state.activeChestPos = null;
        state.carriedCheeseBlocks = 0;
        resetProductionCycle(state);
        setStage(state, resolveNextStage(level, buildingPos, state));
    }

    private static void tickWaiting(CustomEntity npc,
                                    ServerLevel level,
                                    BlockPos buildingPos,
                                    CheeseFactoryState state,
                                    @Nullable IndustrialBuildingConfig config) {
        updateHeldItemForStage(npc, Stage.WAITING);
        resetProductionCycle(state);
        Stage nextStage = resolveNextStage(level, buildingPos, state);
        if (nextStage != Stage.WAITING) {
            setStage(state, nextStage);
            return;
        }

        if (config != null && config.getJobName() != null) {
            npc.setStatusLabelForTicks("gui.npc.status.cheese_factory_waiting_milk", 80);
        } else {
            npc.setStatusLabelForTicks("gui.npc.status.cheese_factory_waiting_milk", 80);
        }
    }

    private static Stage resolveNextStage(ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        if (state.carriedCheeseBlocks > 0) {
            return Stage.MOVE_TO_CHEST;
        }
        if (hasCheeseInPool(level, buildingPos)) {
            return Stage.HARVEST_CHEESE;
        }
        if (hasMilkInPool(level, buildingPos) || hasMilkInPourVisual(level, buildingPos)) {
            return Stage.MOVE_TO_STIR;
        }
        if (canStartBatchPour(level, buildingPos) && state.pouredBuckets < MAX_POUR_BUCKETS) {
            return Stage.MOVE_TO_POUR;
        }
        return Stage.WAITING;
    }

    private static Stage resolveResumeStage(ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        if (state != null && state.carriedCheeseBlocks > 0) {
            return Stage.MOVE_TO_CHEST;
        }
        if (hasCheeseInPool(level, buildingPos)) {
            return Stage.HARVEST_CHEESE;
        }
        if (state != null && state.pendingMilkBuckets > 0) {
            return Stage.POUR_MILK;
        }
        if (hasMilkInPool(level, buildingPos) || hasMilkInPourVisual(level, buildingPos)) {
            return state != null && state.stage == Stage.PROCESSING ? Stage.PROCESSING : Stage.MOVE_TO_STIR;
        }
        return resolveNextStage(level, buildingPos, state != null ? state : new CheeseFactoryState());
    }

    private static void ensureWorkingState(CustomEntity npc) {
        if (npc.getWorkStatus() != WorkStatus.WORKING) {
            npc.setWorkStatus(WorkStatus.WORKING);
        }
        npc.setWorking(true);
    }

    private static void updateHeldItemForStage(CustomEntity npc, Stage stage) {
        ItemStack stack = switch (stage) {
            case MOVE_TO_POUR, POUR_MILK -> new ItemStack(Items.MILK_BUCKET);
            case MOVE_TO_CHEST, STORE_CHEESE, HARVEST_CHEESE, MOVE_TO_STIR, PROCESSING -> new ItemStack(ModBlocks.CHEESE_BLOCK_ITEM.get());
            case WAITING -> ItemStack.EMPTY;
        };
        npc.setItemInHand(Objects.requireNonNull(npc.getUsedItemHand()), stack);
    }

    private static void setStage(CheeseFactoryState state, Stage nextStage) {
        if (state == null || nextStage == null) {
            return;
        }
        if (state.stage != nextStage) {
            state.stage = nextStage;
            state.routeIndex = 0;
            state.nextActionGameTime = 0L;
        }
    }

    private static void resetProductionCycle(CheeseFactoryState state) {
        if (state == null) {
            return;
        }
        state.pouredBuckets = 0;
        state.pendingMilkBuckets = 0;
        state.operationIndex = 0;
        state.nextActionGameTime = 0L;
    }

    private static boolean isActionScheduled(CheeseFactoryState state) {
        return state != null && state.nextActionGameTime > 0L;
    }

    private static void swingWorkingHand(CustomEntity npc, ServerLevel level) {
        if (npc == null || level == null) {
            return;
        }
        if (level.getGameTime() % ACTION_SWING_INTERVAL_TICKS == 0L) {
            npc.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static void updateStatusLabelForStage(CustomEntity npc, CheeseFactoryState state) {
        if (npc == null || state == null) {
            return;
        }
        String key = switch (state.stage) {
            case MOVE_TO_POUR -> "gui.npc.status.cheese_factory_go_pour";
            case POUR_MILK -> "gui.npc.status.cheese_factory_pouring_milk";
            case MOVE_TO_STIR -> "gui.npc.status.cheese_factory_go_stir";
            case PROCESSING -> PROCESS_STATUS_KEYS[Math.max(0, Math.min(PROCESS_STATUS_KEYS.length - 1, state.operationIndex))];
            case HARVEST_CHEESE -> "gui.npc.status.cheese_factory_collecting";
            case MOVE_TO_CHEST -> "gui.npc.status.cheese_factory_go_store";
            case STORE_CHEESE -> "gui.npc.status.cheese_factory_storing";
            case WAITING -> "gui.npc.status.cheese_factory_waiting_milk";
        };
        npc.setStatusLabel(key);
    }

    private static int pourPendingMilkBatch(ServerLevel level, BlockPos buildingPos, CheeseFactoryState state) {
        if (level == null || buildingPos == null || state == null || state.pendingMilkBuckets <= 0) {
            return 0;
        }
        int pouredCount = 0;
        while (state.pendingMilkBuckets > 0) {
            BlockPos poolPos = findFirstEmptyPoolPos(level, buildingPos);
            BlockPos visualPos = findFirstEmptyPourVisualPos(level, buildingPos);
            if (poolPos == null || visualPos == null) {
                break;
            }
            level.setBlockAndUpdate(poolPos, ModBlocks.MILK_BLOCK.get().defaultBlockState());
            level.setBlockAndUpdate(visualPos, ModBlocks.MILK_BLOCK.get().defaultBlockState());
            pouredCount++;
            state.pouredBuckets++;
            state.pendingMilkBuckets--;
        }
        return pouredCount;
    }

    private static boolean isActionReady(ServerLevel level, CheeseFactoryState state) {
        if (level == null || state == null) {
            return false;
        }
        return level.getGameTime() >= state.nextActionGameTime;
    }

    private static void scheduleNextAction(ServerLevel level, CheeseFactoryState state, long delayTicks) {
        if (level == null || state == null) {
            return;
        }
        state.nextActionGameTime = level.getGameTime() + Math.max(1L, delayTicks);
    }

    private static boolean followRoute(CustomEntity npc,
                                       ServerLevel level,
                                       BlockPos buildingPos,
                                       CheeseFactoryState state,
                                       BlockPos[] routeAnchors,
                                       @Nullable BlockPos finalTarget) {
        if (npc == null || level == null || buildingPos == null || state == null) {
            return false;
        }

        List<BlockPos> route = buildRoute(level, buildingPos, routeAnchors, finalTarget);
        if (route.isEmpty()) {
            return finalTarget != null && moveNpcTowards(npc, finalTarget);
        }

        int index = Math.max(0, Math.min(state.routeIndex, route.size() - 1));
        BlockPos target = route.get(index);
        if (!moveNpcTowards(npc, target)) {
            return false;
        }

        if (index >= route.size() - 1) {
            state.routeIndex = 0;
            return true;
        }

        state.routeIndex = index + 1;
        return false;
    }

    private static List<BlockPos> buildRoute(ServerLevel level,
                                             BlockPos buildingPos,
                                             BlockPos[] routeAnchors,
                                             @Nullable BlockPos finalTarget) {
        List<BlockPos> route = new ArrayList<>();
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        if (routeAnchors != null) {
            for (BlockPos anchor : routeAnchors) {
                BlockPos standPos = findStandPos(level, buildingPos, toWorldPos(buildingPos, rotation, anchor), 2, 2);
                if (standPos != null && (route.isEmpty() || !route.get(route.size() - 1).equals(standPos))) {
                    route.add(standPos);
                }
            }
        }
        if (finalTarget != null && (route.isEmpty() || !route.get(route.size() - 1).equals(finalTarget))) {
            route.add(finalTarget);
        }
        return route;
    }

    private static boolean moveNpcTowards(CustomEntity npc, @Nullable BlockPos targetPos) {
        if (npc == null || targetPos == null) {
            return false;
        }
        double distanceSqr = npc.distanceToSqr(
                targetPos.getX() + 0.5D,
                targetPos.getY(),
                targetPos.getZ() + 0.5D
        );
        if (distanceSqr <= 2.25D) {
            return true;
        }
        if (!npc.isUsingCustomPathfinder() || !npc.isPathfindingTo(targetPos)) {
            if (!npc.moveToWithNewPathfinder(targetPos, 1.0D)) {
                npc.scheduleHireArrivalTeleport(targetPos);
            }
        }
        return false;
    }

    private static StructureRotation resolveRotation(ServerLevel level,
                                                     BlockPos buildingPos,
                                                     @Nullable StructureRotation cachedRotation) {
        if (level == null || buildingPos == null) {
            return cachedRotation != null ? cachedRotation : StructureRotation.NORTH;
        }
        if (cachedRotation != null && hasExpectedContainers(level, buildingPos, cachedRotation)) {
            return cachedRotation;
        }
        for (StructureRotation rotation : StructureRotation.values()) {
            if (hasExpectedContainers(level, buildingPos, rotation)) {
                return rotation;
            }
        }
        return cachedRotation != null ? cachedRotation : StructureRotation.NORTH;
    }

    private static boolean hasExpectedContainers(ServerLevel level, BlockPos buildingPos, StructureRotation rotation) {
        if (level == null || buildingPos == null || rotation == null) {
            return false;
        }
        for (BlockPos anchor : CONTAINER_ANCHORS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, anchor);
            if (!level.isLoaded(worldPos) || !ContainerUtils.isContainer(level, worldPos)) {
                return false;
            }
        }
        return true;
    }

    private static BlockPos toWorldPos(BlockPos buildingPos, @Nullable StructureRotation rotation, BlockPos relativePos) {
        if (buildingPos == null || relativePos == null) {
            return BlockPos.ZERO;
        }
        return buildingPos.offset(rotateRelative(relativePos, rotation != null ? rotation : StructureRotation.NORTH));
    }

    private static BlockPos rotateRelative(BlockPos pos, StructureRotation rotation) {
        if (pos == null || rotation == null) {
            return BlockPos.ZERO;
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        return switch (rotation) {
            case NORTH -> new BlockPos(x, y, z);
            case EAST -> new BlockPos(-z, y, x);
            case SOUTH -> new BlockPos(-x, y, -z);
            case WEST -> new BlockPos(z, y, -x);
        };
    }

    @Nullable
    private static BlockPos findStandPos(ServerLevel level,
                                         BlockPos buildingPos,
                                         BlockPos anchor,
                                         int horizontalRadius,
                                         int verticalRadius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    BlockPos pos = anchor.offset(dx, dy, dz);
                    if (!isPosInsideBuilding(buildingPos, pos)) {
                        continue;
                    }
                    if (isStandable(level, pos)) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return findStandPos(level, anchor, horizontalRadius, verticalRadius);
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(pos -> pos.distSqr(anchor)))
                .orElse(anchor)
                .immutable();
    }

    @Nullable
    private static BlockPos findStandPos(ServerLevel level, BlockPos anchor, int horizontalRadius, int verticalRadius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    BlockPos pos = anchor.offset(dx, dy, dz);
                    if (isStandable(level, pos)) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return anchor.immutable();
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(pos -> pos.distSqr(anchor)))
                .orElse(anchor)
                .immutable();
    }

    private static boolean isPosInsideBuilding(BlockPos buildingPos, BlockPos pos) {
        if (buildingPos == null || pos == null) {
            return false;
        }
        PlacedBuildingManager.PlacedBuildingData building = getPlacedBuilding(buildingPos);
        if (building == null) {
            return true;
        }
        return PlacedBuildingManager.isPosInBuilding(building.buildingId, pos);
    }

    @Nullable
    private static PlacedBuildingManager.PlacedBuildingData getPlacedBuilding(BlockPos buildingPos) {
        if (buildingPos == null) {
            return null;
        }
        return PlacedBuildingManager.getBuildingByControlBox(buildingPos);
    }

    private static boolean isActiveWorkStage(@Nullable CheeseFactoryState state) {
        return state != null && state.stage != Stage.WAITING;
    }

    @SuppressWarnings("deprecation")
    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState floor = level.getBlockState(pos.below());
        return !feet.blocksMotion()
                && !head.blocksMotion()
                && floor.blocksMotion();
    }

    private static boolean hasEmptyPoolSlot(ServerLevel level, BlockPos buildingPos) {
        return findFirstEmptyPoolPos(level, buildingPos) != null;
    }

    @Nullable
    private static BlockPos findFirstEmptyPoolPos(ServerLevel level, BlockPos buildingPos) {
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : PROCESS_POOL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (!level.isLoaded(worldPos)) {
                continue;
            }
            BlockState state = level.getBlockState(worldPos);
            if (state.isAir()) {
                return worldPos;
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findFirstCheesePos(ServerLevel level, BlockPos buildingPos) {
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : PROCESS_POOL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (level.isLoaded(worldPos) && level.getBlockState(worldPos).is(ModBlocks.CHEESE_BLOCK.get())) {
                return worldPos;
            }
        }
        return null;
    }

    private static boolean hasMilkInPool(ServerLevel level, BlockPos buildingPos) {
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : PROCESS_POOL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (level.isLoaded(worldPos) && level.getBlockState(worldPos).is(ModBlocks.MILK_BLOCK.get())) {
                return true;
            }
        }
        return false;
    }

    private static boolean canStartBatchPour(ServerLevel level, BlockPos buildingPos) {
        return countNearbyItem(level, buildingPos, Items.MILK_BUCKET) >= MAX_POUR_BUCKETS
                && countEmptyProcessPoolSlots(level, buildingPos) >= MAX_POUR_BUCKETS
                && countEmptyPourVisualSlots(level, buildingPos) >= MAX_POUR_BUCKETS;
    }

    private static void clearPendingMilkBatch(CheeseFactoryState state) {
        if (state == null) {
            return;
        }
        state.pendingMilkBuckets = 0;
    }

    private static int countEmptyProcessPoolSlots(ServerLevel level, BlockPos buildingPos) {
        int total = 0;
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : PROCESS_POOL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (level.isLoaded(worldPos) && level.getBlockState(worldPos).isAir()) {
                total++;
            }
        }
        return total;
    }

    private static int countEmptyPourVisualSlots(ServerLevel level, BlockPos buildingPos) {
        int total = 0;
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : POUR_VISUAL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (level.isLoaded(worldPos) && level.getBlockState(worldPos).isAir()) {
                total++;
            }
        }
        return total;
    }

    @Nullable
    private static BlockPos findFirstEmptyPourVisualPos(ServerLevel level, BlockPos buildingPos) {
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : POUR_VISUAL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (!level.isLoaded(worldPos)) {
                continue;
            }
            BlockState state = level.getBlockState(worldPos);
            if (state.isAir()) {
                return worldPos;
            }
        }
        return null;
    }

    private static boolean hasMilkInPourVisual(ServerLevel level, BlockPos buildingPos) {
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : POUR_VISUAL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (level.isLoaded(worldPos) && level.getBlockState(worldPos).is(ModBlocks.MILK_BLOCK.get())) {
                return true;
            }
        }
        return false;
    }

    private static void clearPourVisualMilk(ServerLevel level, BlockPos buildingPos) {
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : POUR_VISUAL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (level.isLoaded(worldPos) && level.getBlockState(worldPos).is(ModBlocks.MILK_BLOCK.get())) {
                level.setBlockAndUpdate(worldPos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static boolean hasCheeseInPool(ServerLevel level, BlockPos buildingPos) {
        return findFirstCheesePos(level, buildingPos) != null;
    }

    private static void coagulateMilkIntoCheese(ServerLevel level, BlockPos buildingPos) {
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos relative : PROCESS_POOL_POSITIONS) {
            BlockPos worldPos = toWorldPos(buildingPos, rotation, relative);
            if (level.isLoaded(worldPos) && level.getBlockState(worldPos).is(ModBlocks.MILK_BLOCK.get())) {
                level.setBlockAndUpdate(worldPos, ModBlocks.CHEESE_BLOCK.get().defaultBlockState());
            }
        }
    }

    @Nullable
    private static BlockPos findPrimaryChestPos(ServerLevel level, BlockPos buildingPos) {
        List<BlockPos> containers = findNearbyContainers(level, buildingPos);
        if (containers.isEmpty()) {
            return null;
        }
        return containers.get(0);
    }

    private static List<BlockPos> findNearbyContainers(ServerLevel level, BlockPos buildingPos) {
        List<BlockPos> containers = new ArrayList<>();
        StructureRotation rotation = resolveRotation(level, buildingPos, null);
        for (BlockPos anchor : CONTAINER_ANCHORS) {
            BlockPos checkPos = toWorldPos(buildingPos, rotation, anchor);
            if (level.isLoaded(checkPos) && ContainerUtils.isContainer(level, checkPos)) {
                containers.add(checkPos.immutable());
            }
        }
        if (!containers.isEmpty()) {
            containers.sort(Comparator.comparingDouble(pos -> pos.distSqr(buildingPos)));
            return containers;
        }
        for (int dx = -CHEST_SEARCH_RADIUS; dx <= CHEST_SEARCH_RADIUS; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -CHEST_SEARCH_RADIUS; dz <= CHEST_SEARCH_RADIUS; dz++) {
                    BlockPos checkPos = buildingPos.offset(dx, dy, dz);
                    if (!level.isLoaded(checkPos)) {
                        continue;
                    }
                    if (ContainerUtils.isContainer(level, checkPos)) {
                        containers.add(checkPos.immutable());
                    }
                }
            }
        }
        containers.sort(Comparator.comparingDouble(pos -> pos.distSqr(buildingPos)));
        return containers;
    }

    private static int countNearbyItem(ServerLevel level, BlockPos buildingPos, Item item) {
        int total = 0;
        for (BlockPos containerPos : findNearbyContainers(level, buildingPos)) {
            total += ContainerUtils.countItem(level, containerPos, new ItemStack(item, 64));
        }
        return total;
    }

    private static boolean consumeNearbyItem(ServerLevel level, BlockPos buildingPos, Item item, int count) {
        int remaining = count;
        for (BlockPos containerPos : findNearbyContainers(level, buildingPos)) {
            if (remaining <= 0) {
                break;
            }
            int available = ContainerUtils.countItem(level, containerPos, new ItemStack(item, remaining));
            if (available <= 0) {
                continue;
            }
            int toConsume = Math.min(available, remaining);
            if (ContainerUtils.consumeItem(level, containerPos, new ItemStack(item, toConsume))) {
                remaining -= toConsume;
            }
        }
        return remaining <= 0;
    }

    @Nullable
    private static BlockPos findInsertableChestPos(ServerLevel level, BlockPos buildingPos, Item item, int count) {
        if (level == null || buildingPos == null || item == null || count <= 0) {
            return null;
        }
        ItemStack stack = new ItemStack(item, count);
        for (BlockPos containerPos : findNearbyContainers(level, buildingPos)) {
            if (ContainerUtils.canInsertItem(level, containerPos, stack)) {
                return containerPos;
            }
        }
        return null;
    }

    private static boolean insertItemIntoChest(ServerLevel level, BlockPos chestPos, Item item, int count) {
        if (level == null || chestPos == null || item == null || count <= 0) {
            return false;
        }
        ItemStack stack = new ItemStack(item, count);
        if (!ContainerUtils.canInsertItem(level, chestPos, stack)) {
            return false;
        }
        return ContainerUtils.insertItem(level, chestPos, stack) >= count;
    }

    private static void openAnimatedChest(ServerLevel level, @Nullable BlockPos chestPos) {
        animateChest(level, chestPos, true);
    }

    private static void closeAnimatedChest(ServerLevel level, @Nullable BlockPos chestPos) {
        animateChest(level, chestPos, false);
    }

    private static void animateChest(ServerLevel level, @Nullable BlockPos chestPos, boolean open) {
        if (level == null || chestPos == null || !level.isLoaded(chestPos)) {
            return;
        }
        BlockState state = level.getBlockState(chestPos);
        Block block = state.getBlock();
        int eventType = isChestLikeBlock(block) ? 1 : 0;
        if (eventType <= 0) {
            return;
        }
        level.blockEvent(chestPos, block, eventType, open ? 1 : 0);
    }

    private static boolean isChestLikeBlock(Block block) {
        return block instanceof ChestBlock || block instanceof EnderChestBlock;
    }

    private static void returnEmptyBuckets(ServerLevel level, BlockPos buildingPos, int count) {
        if (level == null || buildingPos == null || count <= 0) {
            return;
        }
        int remaining = count;
        for (BlockPos containerPos : findNearbyContainers(level, buildingPos)) {
            if (remaining <= 0) {
                break;
            }
            ItemStack stack = new ItemStack(Items.BUCKET, remaining);
            if (!ContainerUtils.canInsertItem(level, containerPos, stack)) {
                continue;
            }
            int inserted = ContainerUtils.insertItem(level, containerPos, stack);
            remaining -= inserted;
        }
        if (remaining > 0) {
            BlockPos dropPos = findPrimaryChestPos(level, buildingPos);
            BlockPos spawnPos = dropPos != null ? dropPos : buildingPos;
            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                    level,
                    spawnPos.getX() + 0.5D,
                    spawnPos.getY() + 1.0D,
                    spawnPos.getZ() + 0.5D,
                    new ItemStack(Items.BUCKET, remaining)
            ));
        }
    }

    private static List<BlockPos> createPourVisualPositions() {
        List<BlockPos> positions = new ArrayList<>(MAX_POUR_BUCKETS);
        for (int y = 5; y >= 1; y--) {
            for (int x = -5; x <= -4; x++) {
                for (int z = -8; z <= -7; z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(positions);
    }

    private static List<BlockPos> createProcessPoolPositions() {
        List<BlockPos> positions = new ArrayList<>();
        for (int x = -10; x <= 1; x++) {
            for (int z = -8; z <= -7; z++) {
                positions.add(new BlockPos(x, 1, z));
            }
        }
        return List.copyOf(positions);
    }

    private enum Stage {
        MOVE_TO_POUR,
        POUR_MILK,
        MOVE_TO_STIR,
        PROCESSING,
        HARVEST_CHEESE,
        MOVE_TO_CHEST,
        STORE_CHEESE,
        WAITING
    }

    private static final class CheeseFactoryState {
        private Stage stage = Stage.WAITING;
        private int operationIndex;
        private int carriedCheeseBlocks;
        private int pouredBuckets;
        private int pendingMilkBuckets;
        private int routeIndex;
        private StructureRotation rotation;
        private BlockPos activeChestPos;
        private long nextActionGameTime;
    }

    private enum StructureRotation {
        NORTH,
        EAST,
        SOUTH,
        WEST
    }
}
