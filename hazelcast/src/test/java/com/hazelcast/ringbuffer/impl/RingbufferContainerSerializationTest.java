package com.hazelcast.ringbuffer.impl;


import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.nio.BufferObjectDataInput;
import com.hazelcast.nio.BufferObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.IOException;

import static com.hazelcast.config.InMemoryFormat.BINARY;
import static com.hazelcast.config.InMemoryFormat.OBJECT;
import static com.hazelcast.nio.IOUtil.closeResource;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * This test verifies that the RingbufferContainer can serialize itself
 * correctly using different in memory formats and using enabling/disabling
 * TTL.
 * <p/>
 * This test also forces a delay between the serialization and deserialization. If a ringbuffer is configured
 * with a ttl, we don't want to send over the actual expiration time, because on a different member in the
 * cluster, there could be a big time difference which can lead to the ringbuffer immediately cleaning or cleaning
 * very very late.
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class RingbufferContainerSerializationTest extends HazelcastTestSupport {

    private static final int CLOCK_DIFFERENCE_MS = 2000;

    private InternalSerializationService serializationService;
    private NodeEngineImpl nodeEngine;

    @Before
    public void setup() {
        HazelcastInstance hz = createHazelcastInstance();
        this.nodeEngine = getNodeEngineImpl(hz);
        this.serializationService = getSerializationService(hz);
    }

    private Data toData(Object item) {
        return serializationService.toData(item);
    }

    @Test
    public void whenObjectInMemoryFormat_andTTLEnabled() {
        test(OBJECT, 100);
    }

    @Test
    public void whenObjectInMemoryFormat_andTTLDisabled() {
        test(OBJECT, 0);
    }

    @Test
    public void whenBinaryInMemoryFormat_andTTLEnabled() {
        test(BINARY, 100);
    }

    @Test
    public void whenBinaryInMemoryFormat_andTTLDisabled() {
        test(BINARY, 0);
    }

    public void test(InMemoryFormat inMemoryFormat, int ttlSeconds) {
        final RingbufferConfig config = new RingbufferConfig("foobar")
                .setCapacity(3)
                .setAsyncBackupCount(2)
                .setBackupCount(2)
                .setInMemoryFormat(inMemoryFormat)
                .setTimeToLiveSeconds(ttlSeconds);

        final RingbufferContainer rbContainer = new RingbufferContainer(config.getName(), config,
                nodeEngine.getSerializationService(), nodeEngine.getConfigClassLoader());
        testSerialization(rbContainer);

        for (int k = 0; k < config.getCapacity() * 2; k++) {
            rbContainer.add(toData("old"));
            testSerialization(rbContainer);
        }

        // now we are going to force the head to move
        final ArrayRingbuffer ringbuffer = (ArrayRingbuffer) rbContainer.getRingbuffer();
        for (int k = 0; k < config.getCapacity() / 2; k++) {
            ringbuffer.ringItems[k] = null;
            if (ttlSeconds != 0) {
                // we need to set the expiration slot to 0, because it won't be serialized (optimization)
                // serialization will only dump what is between head and tail
                rbContainer.getExpirationPolicy().ringExpirationMs[k] = 0;
            }
            ringbuffer.setHeadSequence(ringbuffer.headSequence() + 1);
            testSerialization(rbContainer);
        }
    }

    private void testSerialization(RingbufferContainer original) {
        RingbufferContainer clone = clone(original);

        assertEquals(original.headSequence(), clone.headSequence());
        assertEquals(original.tailSequence(), clone.tailSequence());
        assertEquals(original.getCapacity(), clone.getCapacity());
        if (original.getExpirationPolicy() != null) {
            assertNotNull(clone.getExpirationPolicy());
            assertEquals(original.getExpirationPolicy().getTtlMs(), clone.getExpirationPolicy().getTtlMs());
        }
        final ArrayRingbuffer originalRingbuffer = (ArrayRingbuffer) original.getRingbuffer();
        final ArrayRingbuffer cloneRingbuffer = (ArrayRingbuffer) original.getRingbuffer();
        assertArrayEquals(originalRingbuffer.ringItems, cloneRingbuffer.ringItems);


        // the most complicated part is the expiration.
        if (original.getConfig().getTimeToLiveSeconds() == 0) {
            assertNull(clone.getExpirationPolicy());
            return;
        }

        assertNotNull(clone.getExpirationPolicy());
        assertEquals(original.getExpirationPolicy().ringExpirationMs.length, clone.getExpirationPolicy().ringExpirationMs.length);

        for (long seq = original.headSequence(); seq <= original.tailSequence(); seq++) {
            int index = original.getExpirationPolicy().toIndex(seq);
            long originalExpiration = original.getExpirationPolicy().ringExpirationMs[index];
            long actualExpiration = clone.getExpirationPolicy().ringExpirationMs[index];
            double difference = actualExpiration - originalExpiration;
            assertTrue("difference was:" + difference, difference > 0.50 * CLOCK_DIFFERENCE_MS);
            assertTrue("difference was:" + difference, difference < 1.50 * CLOCK_DIFFERENCE_MS);
        }
    }

    private RingbufferContainer clone(RingbufferContainer original) {
        BufferObjectDataOutput out = serializationService.createObjectDataOutput(100000);
        BufferObjectDataInput in = null;
        try {
            original.writeData(out);
            byte[] bytes = out.toByteArray();
            sleepMillis(CLOCK_DIFFERENCE_MS);
            RingbufferContainer clone = new RingbufferContainer(original.getName());
            in = serializationService.createObjectDataInput(bytes);
            clone.readData(in);
            return clone;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            closeResource(out);
            closeResource(in);
        }
    }
}
