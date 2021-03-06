/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.monitor.LocalMapStats;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Test for MapStatisticsAwareService
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class MapStatisticsAwareServiceTest extends HazelcastTestSupport {

    @Test
    public void getStats() {
        HazelcastInstance hz = createHazelcastInstance();
        warmUpPartitions(hz);
        // when one map exists
        hz.getMap("map");
        MapService mapService = getNodeEngineImpl(hz).getService(MapService.SERVICE_NAME);
        Map<String, LocalMapStats> mapStats = mapService.getStats();

        // then we obtain 1 local map stats instance
        assertEquals(1, mapStats.size());
        assertNotNull(mapStats.get("map"));

        hz.shutdown();
    }
}
