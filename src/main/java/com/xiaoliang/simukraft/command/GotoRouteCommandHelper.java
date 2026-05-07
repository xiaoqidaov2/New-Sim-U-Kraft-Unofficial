package com.xiaoliang.simukraft.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.ai.path.NPCPathNavigator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

@SuppressWarnings("null")
public final class GotoRouteCommandHelper {
    private static final int NPC_SEARCH_RADIUS = 16;
    private static final double WAYPOINT_REACH_DISTANCE = 0.8D;

    private static final ConcurrentHashMap<UUID, ConcurrentSkipListMap<Integer, SavedWaypoint>> PLAYER_WAYPOINTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, RouteSession> ACTIVE_ROUTES = new ConcurrentHashMap<>();

    private GotoRouteCommandHelper() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> buildGotoCommand() {
        return Commands.literal("goto")
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(context -> saveWaypoint(context.getSource(), IntegerArgumentType.getInteger(context, "index"))))
                .then(Commands.literal("go")
                        .executes(context -> startRoute(context.getSource())))
                .then(Commands.literal("clear")
                        .executes(context -> clearWaypoints(context.getSource())));
    }

    private static int saveWaypoint(CommandSourceStack source, int index) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c只能由玩家执行 goto 命令"));
            return 0;
        }

        SavedWaypoint waypoint = new SavedWaypoint(
                player.blockPosition().immutable(),
                player.serverLevel().dimension().location()
        );
        ConcurrentSkipListMap<Integer, SavedWaypoint> points = PLAYER_WAYPOINTS.computeIfAbsent(
                player.getUUID(),
                ignored -> new ConcurrentSkipListMap<>()
        );
        points.put(index, waypoint);

        source.sendSuccess(() -> Component.literal(
                "§a已设置 goto 点 " + index + " -> " + waypoint.dimensionId() + " " + formatPos(waypoint.pos())
        ), false);
        return 1;
    }

    private static int startRoute(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c只能由玩家执行 goto 命令"));
            return 0;
        }

        ConcurrentSkipListMap<Integer, SavedWaypoint> points = PLAYER_WAYPOINTS.get(player.getUUID());
        if (points == null || points.isEmpty()) {
            source.sendFailure(Component.literal("§c你还没有设置任何 goto 点，先使用 /simukraft goto 1 之类的命令记点"));
            return 0;
        }

        CustomEntity npc = findNearestNpc(player);
        if (npc == null) {
            source.sendFailure(Component.literal("§c附近 " + NPC_SEARCH_RADIUS + " 格内没有找到可测试的 NPC"));
            return 0;
        }

        List<Map.Entry<Integer, SavedWaypoint>> orderedEntries = new ArrayList<>(points.entrySet());
        orderedEntries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));

        List<RouteWaypoint> orderedRoute = new ArrayList<>();
        ResourceLocation npcDimension = player.serverLevel().dimension().location();
        for (Map.Entry<Integer, SavedWaypoint> entry : orderedEntries) {
            SavedWaypoint waypoint = entry.getValue();
            if (!npcDimension.equals(waypoint.dimensionId())) {
                source.sendFailure(Component.literal(
                        "§cgoto 点 " + entry.getKey() + " 位于其他维度: " + waypoint.dimensionId() + "，当前 NPC 在 " + npcDimension
                ));
                return 0;
            }
            orderedRoute.add(new RouteWaypoint(entry.getKey(), waypoint.pos(), waypoint.dimensionId()));
        }

        RouteSession session = new RouteSession(UUID.randomUUID(), player.getUUID(), npc.getUUID(), orderedRoute);
        ACTIVE_ROUTES.put(npc.getUUID(), session);
        clearNavigatorCallbacks(npc);
        npc.stopNewPathfinder();
        npc.getNavigation().stop();

        if (!startWaypoint(serverOf(source), session, 0)) {
            ACTIVE_ROUTES.remove(npc.getUUID(), session);
            clearNavigatorCallbacks(npc);
            source.sendFailure(Component.literal("§c启动 goto 路线失败: " + npc.getFullName()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "§a已启动 goto 路线，NPC: " + npc.getFullName() + "，共 " + orderedRoute.size() + " 个点"
        ), true);
        return orderedRoute.size();
    }

    private static int clearWaypoints(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c只能由玩家执行 goto 命令"));
            return 0;
        }

        ConcurrentSkipListMap<Integer, SavedWaypoint> removed = PLAYER_WAYPOINTS.remove(player.getUUID());
        int cleared = removed == null ? 0 : removed.size();
        source.sendSuccess(() -> Component.literal("§a已清空 goto 点，共清除 " + cleared + " 个"), false);
        return cleared;
    }

    private static boolean startWaypoint(@Nullable MinecraftServer server, RouteSession session, int routeIndex) {
        if (server == null) {
            return false;
        }
        CustomEntity npc = findNpc(server, session.npcUuid());
        if (npc == null || routeIndex < 0 || routeIndex >= session.route().size()) {
            return false;
        }
        if (ACTIVE_ROUTES.get(session.npcUuid()) != session) {
            return false;
        }

        RouteWaypoint waypoint = session.route().get(routeIndex);
        NPCPathNavigator navigator = npc.getNPCPathNavigator();
        if (navigator == null) {
            return false;
        }

        navigator.setOnPathComplete(() -> handleWaypointComplete(server, session, routeIndex));
        navigator.setOnPathFail(() -> handleWaypointFail(server, session, routeIndex));
        return npc.moveToWithNewPathfinder(waypoint.pos(), WAYPOINT_REACH_DISTANCE);
    }

    private static void handleWaypointComplete(MinecraftServer server, RouteSession session, int routeIndex) {
        if (ACTIVE_ROUTES.get(session.npcUuid()) != session) {
            return;
        }

        CustomEntity npc = findNpc(server, session.npcUuid());
        if (npc == null) {
            ACTIVE_ROUTES.remove(session.npcUuid(), session);
            return;
        }

        int nextIndex = routeIndex + 1;
        if (nextIndex >= session.route().size()) {
            ACTIVE_ROUTES.remove(session.npcUuid(), session);
            clearNavigatorCallbacks(npc);
            sendPlayerMessage(server, session.playerUuid(), Component.literal(
                    "§aGoto 路线执行完成: " + npc.getFullName()
            ));
            return;
        }

        if (!startWaypoint(server, session, nextIndex)) {
            ACTIVE_ROUTES.remove(session.npcUuid(), session);
            clearNavigatorCallbacks(npc);
            sendPlayerMessage(server, session.playerUuid(), Component.literal(
                    "§cGoto 路线切换到下一个点失败: " + npc.getFullName()
            ));
        }
    }

    private static void handleWaypointFail(MinecraftServer server, RouteSession session, int routeIndex) {
        if (ACTIVE_ROUTES.get(session.npcUuid()) != session) {
            return;
        }

        ACTIVE_ROUTES.remove(session.npcUuid(), session);
        CustomEntity npc = findNpc(server, session.npcUuid());
        if (npc != null) {
            clearNavigatorCallbacks(npc);
        }

        RouteWaypoint failed = routeIndex >= 0 && routeIndex < session.route().size() ? session.route().get(routeIndex) : null;
        String pointLabel = failed == null ? "未知" : String.valueOf(failed.index());
        sendPlayerMessage(server, session.playerUuid(), Component.literal(
                "§cGoto 路线失败，卡在点 " + pointLabel + (npc != null ? "，NPC: " + npc.getFullName() : "")
        ));
    }

    @Nullable
    private static CustomEntity findNearestNpc(ServerPlayer player) {
        return player.serverLevel()
                .getEntitiesOfClass(CustomEntity.class, player.getBoundingBox().inflate(NPC_SEARCH_RADIUS),
                        npc -> npc.isAlive() && !npc.isSleeping())
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    @Nullable
    private static CustomEntity findNpc(MinecraftServer server, UUID npcUuid) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!(level.getEntity(npcUuid) instanceof CustomEntity npc)) {
                continue;
            }
            return npc;
        }
        return null;
    }

    private static void clearNavigatorCallbacks(CustomEntity npc) {
        if (npc == null || npc.getNPCPathNavigator() == null) {
            return;
        }
        npc.getNPCPathNavigator().setOnPathComplete(null);
        npc.getNPCPathNavigator().setOnPathFail(null);
    }

    private static void sendPlayerMessage(@Nullable MinecraftServer server, UUID playerUuid, Component message) {
        if (server == null || message == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    @Nullable
    private static MinecraftServer serverOf(CommandSourceStack source) {
        return source == null ? null : source.getServer();
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private record SavedWaypoint(BlockPos pos, ResourceLocation dimensionId) {
    }

    private record RouteWaypoint(int index, BlockPos pos, ResourceLocation dimensionId) {
    }

    private record RouteSession(UUID sessionId, UUID playerUuid, UUID npcUuid, List<RouteWaypoint> route) {
    }
}
