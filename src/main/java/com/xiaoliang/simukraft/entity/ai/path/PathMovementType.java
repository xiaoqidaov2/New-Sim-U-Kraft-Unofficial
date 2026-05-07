package com.xiaoliang.simukraft.entity.ai.path;

/**
 * 寻路移动类型：供代价引擎与主寻路器共用
 */
public enum PathMovementType {
    TRAVERSE,
    ASCEND,
    JUMP_OVER,
    DESCEND,
    FALL,
    DOOR;

    public static PathMovementType fromNodeAction(NPCPathNode.MovementAction action) {
        if (action == null) {
            return TRAVERSE;
        }
        return switch (action) {
            case ASCEND -> ASCEND;
            case JUMP_OVER -> JUMP_OVER;
            case DESCEND -> DESCEND;
            case FALL -> FALL;
            case DOOR -> DOOR;
            case TRAVERSE -> TRAVERSE;
        };
    }
}
