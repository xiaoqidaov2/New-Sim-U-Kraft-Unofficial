package com.xiaoliang.simukraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.xiaoliang.simukraft.building.CommercialBuildingManager;
import com.xiaoliang.simukraft.building.IndustrialBuildingManager;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.FloatingBuildBoxEntity;
import com.xiaoliang.simukraft.entity.WorkStatus;
import com.xiaoliang.simukraft.init.ModEntities;
import com.xiaoliang.simukraft.utils.FileUtils;
import com.xiaoliang.simukraft.utils.NPCDataManager;
import com.xiaoliang.simukraft.world.CityData;
import com.xiaoliang.simukraft.world.OfficialInvitationService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

@SuppressWarnings("null")
public class CommandSimukraft {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("simukraft")
                .requires(source -> source.hasPermission(2))
                // reload - 重载配置
                .then(Commands.literal("reload")
                    .executes(context -> {
                        var source = context.getSource();
                        var server = source.getServer();
                        
                        if (server == null) {
                            source.sendFailure(Component.literal("§c无法获取服务器实例"));
                            return 0;
                        }
                        
                        try {
                            // 清理并重新加载所有建筑相关缓存
                            // 1. 清理建筑数据管理器缓存
                            com.xiaoliang.simukraft.utils.BuildingDataManager.clearCache();

                            // 2. 重新加载工业建筑配置
                            IndustrialBuildingManager.reload(server);

                            // 3. 重新加载商业建筑配置
                            CommercialBuildingManager.reload(server);

                            // 4. 重新加载SK文件缓存
                            FileUtils.reloadSkFileCache(server);

                            // 5. 预加载建筑数据到缓存
                            com.xiaoliang.simukraft.utils.BuildingDataManager.reloadCache();

                            // 6. 发送指南书重载数据包到所有在线玩家（在客户端执行重载）
                            final int playerCount = server.getPlayerList().getPlayers().size();
                            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                com.xiaoliang.simukraft.network.NetworkManager.sendTo(
                                    new com.xiaoliang.simukraft.network.ReloadGuideBookPacket(), player);
                            }

                            final int finalPlayerCount = playerCount;
                            source.sendSuccess(() -> Component.literal(
                                "§aSimuKraft 配置文件已重新加载！§7(已向 " + finalPlayerCount + " 名玩家发送指南书重载指令)"), true);
                            return 1;
                        } catch (Exception e) {
                            source.sendFailure(Component.literal("§c重新加载配置时出错: " + e.getMessage()));
                            e.printStackTrace();
                            return 0;
                        }
                    })
                )
                // npc - NPC相关指令
                .then(Commands.literal("npc")
                    .then(Commands.literal("spawn")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player != null) {
                                CustomEntity npc = ModEntities.CUSTOM_ENTITY.get().spawn(
                                        player.serverLevel(),
                                        player.blockPosition(),
                                        MobSpawnType.COMMAND
                                );
                                if (npc != null) {
                                    Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                                    context.getSource().sendSuccess(() ->
                                        Component.translatable("command.simnpc.spawned", npcName, npc.getGender().getName(), npc.getWorkStatus().getDisplayName()), true);
                                }
                                return 1;
                            }
                            return 0;
                        }))
                    .then(Commands.literal("status")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player != null) {
                                var entities = player.level().getEntitiesOfClass(CustomEntity.class,
                                    player.getBoundingBox().inflate(5.0));
                                if (entities.isEmpty()) {
                                    context.getSource().sendFailure(Component.translatable("command.simnpc.no_npc_found"));
                                    return 0;
                                }

                                for (CustomEntity npc : entities) {
                                    Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                                    context.getSource().sendSuccess(() ->
                                        Component.translatable("command.simnpc.status", npcName, npc.getWorkStatus().getDisplayName()), false);
                                }
                                return 1;
                            }
                            return 0;
                        }))
                    .then(Commands.literal("work")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player != null) {
                                var entities = player.level().getEntitiesOfClass(CustomEntity.class, 
                                    player.getBoundingBox().inflate(5.0));
                                if (entities.isEmpty()) {
                                    context.getSource().sendFailure(Component.translatable("command.simnpc.no_npc_found"));
                                    return 0;
                                }
                                
                                for (CustomEntity npc : entities) {
                                    npc.setWorkStatus(WorkStatus.WORKING);
                                    Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                                    context.getSource().sendSuccess(() ->
                                        Component.translatable("command.simnpc.start_work", npcName), false);
                                }
                                return 1;
                            }
                            return 0;
                        }))
                    .then(Commands.literal("idle")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player != null) {
                                var entities = player.level().getEntitiesOfClass(CustomEntity.class, 
                                    player.getBoundingBox().inflate(5.0));
                                if (entities.isEmpty()) {
                                    context.getSource().sendFailure(Component.translatable("command.simnpc.no_npc_found"));
                                    return 0;
                                }
                                
                                for (CustomEntity npc : entities) {
                                    npc.setWorkStatus(WorkStatus.IDLE);
                                    Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                                    context.getSource().sendSuccess(() ->
                                        Component.translatable("command.simnpc.set_idle", npcName), false);
                                }
                                return 1;
                            }
                            return 0;
                        }))
                )
                // npcxp - NPC经验值
                .then(Commands.literal("npcxp")
                    .then(Commands.literal("add")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    int amount = IntegerArgumentType.getInteger(context, "amount");
                                    var entities = player.level().getEntitiesOfClass(CustomEntity.class,
                                            player.getBoundingBox().inflate(5.0));
                                    if (entities.isEmpty()) {
                                        context.getSource().sendFailure(Component.translatable("command.npcxp.no_npc_found"));
                                        return 0;
                                    }

                                    int count = 0;
                                    for (CustomEntity npc : entities) {
                                        boolean leveledUp = NPCDataManager.addXp(player.getServer(), npc.getUUID(), amount);
                                        int newLevel = NPCDataManager.getNPCLevel(player.getServer(), npc.getUUID());
                                        int newXp = NPCDataManager.getNPCXp(player.getServer(), npc.getUUID());
                                        Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());

                                        if (leveledUp) {
                                            context.getSource().sendSuccess(() ->
                                                    Component.translatable("command.npcxp.added_and_leveled", npcName, amount, newLevel), false);
                                        } else {
                                            context.getSource().sendSuccess(() ->
                                                    Component.translatable("command.npcxp.added", npcName, amount, newLevel, newXp), false);
                                        }
                                        count++;
                                    }
                                    return count;
                                }
                                return 0;
                            })))
                    .then(Commands.literal("set")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    int amount = IntegerArgumentType.getInteger(context, "amount");
                                    var entities = player.level().getEntitiesOfClass(CustomEntity.class,
                                            player.getBoundingBox().inflate(5.0));
                                    if (entities.isEmpty()) {
                                        context.getSource().sendFailure(Component.translatable("command.npcxp.no_npc_found"));
                                        return 0;
                                    }

                                    int count = 0;
                                    for (CustomEntity npc : entities) {
                                        int currentLevel = NPCDataManager.getNPCLevel(player.getServer(), npc.getUUID());
                                        NPCDataManager.saveSkillData(player.getServer(), npc.getUUID(), currentLevel, amount);
                                        Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                                        context.getSource().sendSuccess(() ->
                                                Component.translatable("command.npcxp.set", npcName, amount), false);
                                        count++;
                                    }
                                    return count;
                                }
                                return 0;
                            })))
                    .then(Commands.literal("query")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player != null) {
                                var entities = player.level().getEntitiesOfClass(CustomEntity.class,
                                        player.getBoundingBox().inflate(5.0));
                                if (entities.isEmpty()) {
                                    context.getSource().sendFailure(Component.translatable("command.npcxp.no_npc_found"));
                                    return 0;
                                }

                                for (CustomEntity npc : entities) {
                                    int level = NPCDataManager.getNPCLevel(player.getServer(), npc.getUUID());
                                    int xp = NPCDataManager.getNPCXp(player.getServer(), npc.getUUID());
                                    Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                                    context.getSource().sendSuccess(() ->
                                            Component.translatable("command.npcxp.query", npcName, level, xp), false);
                                }
                                return 1;
                            }
                            return 0;
                        }))
                )
                // funds - 资金
                .then(Commands.literal("funds")
                    .then(Commands.literal("add")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    double amount = DoubleArgumentType.getDouble(context, "amount");
                                    CityData cityData = CityData.get(player.serverLevel());
                                    String playerName = player.getName().getString();
                                    
                                    double currentFunds = cityData.getPlayerCityFunds(playerName);
                                    double newFunds = currentFunds + amount;
                                    
                                    cityData.setPlayerCityFunds(playerName, newFunds);
                                    
                                    context.getSource().sendSuccess(
                                () -> Component.translatable("command.funds.add.success", amount, newFunds), true);
                                    return 1;
                                }
                                return 0;
                            })))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    double amount = DoubleArgumentType.getDouble(context, "amount");
                                    CityData cityData = CityData.get(player.serverLevel());
                                    String playerName = player.getName().getString();
                                    
                                    double currentFunds = cityData.getPlayerCityFunds(playerName);
                                    
                                    if (currentFunds < amount) {
                                        context.getSource().sendFailure(
                                                    Component.translatable("command.funds.remove.failed", currentFunds, amount));
                                        return 0;
                                    }
                                    
                                    double newFunds = currentFunds - amount;
                                    cityData.setPlayerCityFunds(playerName, newFunds);
                                    
                                    context.getSource().sendSuccess(
                                                () -> Component.translatable("command.funds.remove.success", amount, newFunds), true);
                                    return 1;
                                }
                                return 0;
                            })))
                    .then(Commands.literal("set")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    double amount = DoubleArgumentType.getDouble(context, "amount");
                                    CityData cityData = CityData.get(player.serverLevel());
                                    String playerName = player.getName().getString();
                                    
                                    cityData.setPlayerCityFunds(playerName, amount);
                                    
                                    context.getSource().sendSuccess(
                                        () -> Component.translatable("command.funds.set.success", amount), true);
                                    return 1;
                                }
                                return 0;
                            })))
                    .then(Commands.literal("query")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player != null) {
                                CityData cityData = CityData.get(player.serverLevel());
                                String playerName = player.getName().getString();
                                double funds = cityData.getPlayerCityFunds(playerName);
                                
                                context.getSource().sendSuccess(
                                    () -> Component.translatable("command.funds.query", funds), false);
                                return 1;
                            }
                            return 0;
                        }))
                )
                // spawnbox - 生成悬浮建筑盒
                .then(Commands.literal("spawnbox")
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        Vec3 pos = source.getPosition();
                        return spawnFloatingBuildBox(source, new BlockPos((int)pos.x, (int)pos.y, (int)pos.z), 0.5f, 0.02f);
                    })
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                            return spawnFloatingBuildBox(source, pos, 0.5f, 0.02f);
                        })
                        .then(Commands.argument("height", FloatArgumentType.floatArg(0.1f, 5.0f))
                            .executes(context -> {
                                CommandSourceStack source = context.getSource();
                                BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                float height = FloatArgumentType.getFloat(context, "height");
                                return spawnFloatingBuildBox(source, pos, height, 0.02f);
                            })
                            .then(Commands.argument("speed", FloatArgumentType.floatArg(0.001f, 0.1f))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                    float height = FloatArgumentType.getFloat(context, "height");
                                    float speed = FloatArgumentType.getFloat(context, "speed");
                                    return spawnFloatingBuildBox(source, pos, height, speed);
                                })
                            )
                        )
                    )
                )
        );

        // 保留官方邀请命令（聊天栏点击用，不需要/simukraft前缀）
        dispatcher.register(
            Commands.literal("skofficial")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("accept")
                    .then(Commands.argument("invitationId", StringArgumentType.word())
                        .executes(context -> executeOfficial(context.getSource(), true,
                                StringArgumentType.getString(context, "invitationId")))))
                .then(Commands.literal("deny")
                    .then(Commands.argument("invitationId", StringArgumentType.word())
                        .executes(context -> executeOfficial(context.getSource(), false,
                                StringArgumentType.getString(context, "invitationId")))))
        );
    }

    private static int spawnFloatingBuildBox(CommandSourceStack source, BlockPos pos, float height, float speed) {
        if (!source.getLevel().isClientSide) {
            FloatingBuildBoxEntity entity = ModEntities.FLOATING_BUILD_BOX.get().create(source.getLevel());
            if (entity != null) {
                entity.setPos(pos.getX() + 0.5, pos.getY() + height, pos.getZ() + 0.5);
                entity.setFloatHeight(height);
                entity.setFloatSpeed(speed);
                source.getLevel().addFreshEntity(entity);
                
                source.sendSuccess(() -> Component.translatable("message.simukraft.command.spawn_floating_buildbox.success"), true);
                return 1;
            }
        }
        return 0;
    }

    private static int executeOfficial(CommandSourceStack source, boolean accepted, String invitationIdString) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            return 0;
        }

        UUID invitationId;
        try {
            invitationId = UUID.fromString(invitationIdString);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.translatable("message.simukraft.official.invitation_id_invalid"));
            return 0;
        }

        OfficialInvitationService.handleResponse(player, invitationId, accepted);
        return 1;
    }
}
