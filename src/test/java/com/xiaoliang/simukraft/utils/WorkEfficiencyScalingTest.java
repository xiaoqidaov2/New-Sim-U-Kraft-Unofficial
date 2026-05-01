package com.xiaoliang.simukraft.utils;

import org.junit.jupiter.api.Test;

import com.xiaoliang.simukraft.job.jobs.commercialgeneric.CommercialWorkHandler;
import com.xiaoliang.simukraft.job.jobs.industrialgeneric.IndustrialWorkHandler;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkEfficiencyScalingTest {

    @Test
    void industrialYieldMultiplierKeepsGrowingAfterLevelSeven() {
        float level7 = IndustrialWorkHandler.getYieldMultiplierByLevel(7);
        float level8 = IndustrialWorkHandler.getYieldMultiplierByLevel(8);

        assertTrue(level8 > level7, "industrial yield should keep growing after level 7");
    }

    @Test
    void industrialWorkIntervalDoesNotResetAfterLevelSeven() {
        long level7 = IndustrialWorkHandler.getWorkTickInterval(7);
        long level8 = IndustrialWorkHandler.getWorkTickInterval(8);

        assertTrue(level8 <= level7, "industrial work interval should not become slower after level 7");
    }

    @Test
    void commercialEfficiencyKeepsGrowingAfterLevelSeven() {
        float level7 = CommercialWorkHandler.getEfficiencyByLevel(7);
        float level8 = CommercialWorkHandler.getEfficiencyByLevel(8);

        assertTrue(level8 > level7, "commercial efficiency should keep growing after level 7");
    }
}

