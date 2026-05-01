package com.xiaoliang.simukraft.job.core;

import com.xiaoliang.simukraft.job.api.JobContext;
import com.xiaoliang.simukraft.job.api.JobRuntimeState;

public final class JobStateMachine {

    public JobRuntimeState nextState(JobContext context) {
        return nextState(context, null);
    }

    public JobRuntimeState nextState(JobContext context, JobRuntimeState currentState) {
        if (context == null || context.assignment() == null || !context.assignment().isAssigned()) {
            return JobRuntimeState.IDLE;
        }
        if (context.definition() == null) {
            return JobRuntimeState.INVALID;
        }
        if (!context.hasLoadedNpc()) {
            return JobRuntimeState.PAUSED;
        }

        if (currentState != null) {
            switch (currentState) {
                case BLOCKED:
                    if (!canRecoverFromBlocked(context)) {
                        return JobRuntimeState.BLOCKED;
                    }
                    break;
                case INVALID:
                    if (!canRecoverFromInvalid(context)) {
                        return JobRuntimeState.INVALID;
                    }
                    break;
                case PAUSED:
                    break;
                default:
                    break;
            }
        }

        if (!context.definition().schedule().isWorkTime(context.dayTime())) {
            return JobRuntimeState.RESTING;
        }

        if (!context.definition().workflow().canWork(context)) {
            return JobRuntimeState.PAUSED;
        }

        return JobRuntimeState.WORKING;
    }

    private boolean canRecoverFromBlocked(JobContext context) {
        return context.hasLoadedNpc() && context.definition() != null;
    }

    private boolean canRecoverFromInvalid(JobContext context) {
        return context.definition() != null
                && context.assignment() != null
                && context.assignment().isAssigned();
    }
}
