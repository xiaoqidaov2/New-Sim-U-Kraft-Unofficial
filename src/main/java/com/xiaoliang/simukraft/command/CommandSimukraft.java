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
import com.xiaoliang.simukraft.utils.NPCFoodMarket;
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
                            // menglannnn: 修复reload命令不刷新缓存的问题
                            // 正确的顺序：先复制文件 -> 再加载缓存
                            
                            // 1. 先重新复制建筑文件（从jar/resources到simukraftbuilding文件夹）
                            com.xiaoliang.simukraft.utils.BuildingDataManager.checkAndCopyBuildingFiles();
                            
                            // 2. 清理所有缓存
                            com.xiaoliang.simukraft.utils.BuildingDataManager.clearCache();
                            
                            // 3. 重新加载建筑数据到缓存（从simukraftbuilding文件夹）
                            com.xiaoliang.simukraft.utils.BuildingDataManager.reloadCache();

                            // 4. 重新加载工业建筑配置
                            IndustrialBuildingManager.reload(server);

                            // 5. 重新加载商业建筑配置
                            CommercialBuildingManager.reload(server);

                            // 6. 重新加载SK文件缓存（世界目录中的已放置建筑）
                            FileUtils.reloadSkFileCache(server);

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
                    .then(Commands.literal("buyfoodtest")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) {
                                return 0;
                            }

                            var entities = player.level().getEntitiesOfClass(CustomEntity.class,
                                    player.getBoundingBox().inflate(5.0));
                            if (entities.isEmpty()) {
                                context.getSource().sendFailure(Component.translatable("command.simnpc.no_npc_found"));
                                return 0;
                            }

                            int count = 0;
                            for (CustomEntity npc : entities) {
                                NPCFoodMarket.PurchasePlan plan = NPCFoodMarket.findPurchasePlan(player.serverLevel(), npc);
                                if (plan == null) {
                                    Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                                    context.getSource().sendFailure(Component.literal("§cNPC " + npcName.getString() + " 附近没有可测试的食物商店"));
                                    continue;
                                }

                                npc.stopNewPathfinder();
                                npc.setStatusLabel(NPCFoodMarket.getTravelStatusLabel(plan));
                                npc.setWorkNeedDetail(NPCFoodMarket.getFoodDetailKey(plan));

                                boolean started = npc.moveToWithNewPathfinder(plan.shopPos());
                                Component npcName = npc.getCustomName() != null ? npc.getCustomName() : Component.literal(npc.getFullName());
                                if (started) {
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "§a已强制NPC开始买食物测试寻路: " + npcName.getString() + " -> " + plan.shopPos() + " / " + plan.itemId()), false);
                                    count++;
                                } else {
                                    context.getSource().sendFailure(Component.literal(
                                            "§cNPC买食物测试寻路启动失败: " + npcName.getString()));
                                }
                            }
                            return count;
                        })
                        .then(Commands.literal("uuid")
                            .then(Commands.argument("uuid", StringArgumentType.string())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    if (player == null) {
                                        return 0;
                                    }

                                    String uuidText = StringArgumentType.getString(context, "uuid");
                                    UUID targetUuid;
                                    try {
                                        targetUuid = UUID.fromString(uuidText);
                                    } catch (IllegalArgumentException e) {
                                        context.getSource().sendFailure(Component.literal("§cUUID格式无效: " + uuidText));
                                        return 0;
                                    }

                                    CustomEntity targetNpc = null;
                                    for (CustomEntity npc : player.level().getEntitiesOfClass(CustomEntity.class, player.getBoundingBox().inflate(64.0))) {
                                        if (npc.getUUID().equals(targetUuid)) {
                                            targetNpc = npc;
                                            break;
                                        }
                                    }
                                    if (targetNpc == null) {
                                        context.getSource().sendFailure(Component.literal("§c未找到指定UUID的NPC: " + uuidText));
                                        return 0;
                                    }

                                    NPCFoodMarket.PurchasePlan plan = NPCFoodMarket.findPurchasePlan(player.serverLevel(), targetNpc);
                                    if (plan == null) {
                                        context.getSource().sendFailure(Component.literal("§c该NPC附近没有可测试的食物商店: " + targetNpc.getFullName()));
                                        return 0;
                                    }

                                    final CustomEntity finalTargetNpc = targetNpc;
                                    final NPCFoodMarket.PurchasePlan finalPlan = plan;

                                    finalTargetNpc.stopNewPathfinder();
                                    finalTargetNpc.setStatusLabel(NPCFoodMarket.getTravelStatusLabel(finalPlan));
                                    finalTargetNpc.setWorkNeedDetail(NPCFoodMarket.getFoodDetailKey(finalPlan));

                                    boolean started = finalTargetNpc.moveToWithNewPathfinder(finalPlan.shopPos());
                                    if (!started) {
                                        context.getSource().sendFailure(Component.literal("§c指定NPC买食物测试寻路启动失败: " + finalTargetNpc.getFullName()));
                                        return 0;
                                    }

                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "§a已强制指定NPC开始买食物测试寻路: " + finalTargetNpc.getFullName() + " -> " + finalPlan.shopPos() + " / " + finalPlan.itemId()), false);
                                    return 1;
                                }))))
                    .then(Commands.literal("stoptest")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) {
                                return 0;
                            }

                            var entities = player.level().getEntitiesOfClass(CustomEntity.class,
                                    player.getBoundingBox().inflate(5.0));
                            if (entities.isEmpty()) {
                                context.getSource().sendFailure(Component.translatable("command.simnpc.no_npc_found"));
                                return 0;
                            }

                            int count = 0;
                            for (CustomEntity npc : entities) {
                                npc.stopNewPathfinder();
                                npc.getNavigation().stop();
                                npc.setStatusLabel(null);
                                npc.setWorkNeedDetail("");
                                context.getSource().sendSuccess(() -> Component.literal("§a已停止NPC测试寻路: " + npc.getFullName()), false);
                                count++;
                            }
                            return count;
                        })
                        .then(Commands.literal("uuid")
                            .then(Commands.argument("uuid", StringArgumentType.string())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    if (player == null) {
                                        return 0;
                                    }

                                    String uuidText = StringArgumentType.getString(context, "uuid");
                                    UUID targetUuid;
                                    try {
                                        targetUuid = UUID.fromString(uuidText);
                                    } catch (IllegalArgumentException e) {
                                        context.getSource().sendFailure(Component.literal("§cUUID格式无效: " + uuidText));
                                        return 0;
                                    }

                                    CustomEntity targetNpc = null;
                                    for (CustomEntity npc : player.level().getEntitiesOfClass(CustomEntity.class, player.getBoundingBox().inflate(64.0))) {
                                        if (npc.getUUID().equals(targetUuid)) {
                                            targetNpc = npc;
                                            break;
                                        }
                                    }
                                    if (targetNpc == null) {
                                        context.getSource().sendFailure(Component.literal("§c未找到指定UUID的NPC: " + uuidText));
                                        return 0;
                                    }

                                    final CustomEntity finalTargetNpc = targetNpc;

                                    finalTargetNpc.stopNewPathfinder();
                                    finalTargetNpc.getNavigation().stop();
                                    finalTargetNpc.setStatusLabel(null);
                                    finalTargetNpc.setWorkNeedDetail("");
                                    context.getSource().sendSuccess(() -> Component.literal("§a已停止指定NPC测试寻路: " + finalTargetNpc.getFullName()), false);
                                    return 1;
                                }))))
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
