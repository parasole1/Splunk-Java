/*
 * Copyright 2017 Splunk, Inc..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.splunk.kafka.connect;

import com.splunk.hecclient.EventBatch;
import com.splunk.hecclient.UnitUtil;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class KafkaRecordTrackerTest {
    @Test
    public void addFailedEventBatch() {
        EventBatch batch = UnitUtil.createBatch();
        batch.fail();
        batch.getEvents().get(0).setTied(createSinkRecord(1));
        KafkaRecordTracker tracker = new KafkaRecordTracker();
        tracker.open(createTopicPartitionList());
        tracker.addFailedEventBatch(batch);
        Collection<EventBatch> failed = tracker.getAndRemoveFailedRecords();
        Assert.assertEquals(1, failed.size());
    }

    @Test(expected = RuntimeException.class)
    public void addNonFailedEventBatch() {
        EventBatch batch = UnitUtil.createBatch();
        KafkaRecordTracker tracker = new KafkaRecordTracker();
        tracker.addFailedEventBatch(batch);
    }

    @Test
    public void removeEventBatchMultiThread() {
        List<EventBatch> batches = new ArrayList<>();
        KafkaRecordTracker tracker = new KafkaRecordTracker();
        tracker.open(createTopicPartitionList(500));

        for (int i = 0; i < 100; i++) {
            EventBatch batch = UnitUtil.createMultiBatch(500);
            for (int j = 0; j < 500; j++) {
                batch.getEvents().get(j).setTied(createSinkRecord(j, i * 1000 + j));
            }
            batch.commit();
            batches.add(batch);
            tracker.addEventBatch(batch);
        }

        Assert.assertEquals(50000, tracker.totalEvents());
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executorService.submit(() -> tracker.removeAckedEventBatches(batches));
            Future<?> second = executorService.submit(() -> tracker.removeAckedEventBatches(batches));

            first.get();
            second.get();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdown();
        }

        Assert.assertEquals(0, tracker.totalEvents());
    }
    @Test
    public void addEventBatch() {
        List<EventBatch> batches = new ArrayList<>();
        KafkaRecordTracker tracker = new KafkaRecordTracker();
        for (int i = 0; i < 3; i++) {
            EventBatch batch = UnitUtil.createBatch();
            batch.getEvents().get(0).setTied(createSinkRecord(i));
            batches.add(batch);
            tracker.open(createTopicPartitionList());
            tracker.addEventBatch(batch);
        }
        Map<TopicPartition, OffsetAndMetadata> offsets = tracker.computeOffsets();
        Assert.assertTrue(offsets.isEmpty());

        batches.get(0).commit();
        tracker.removeAckedEventBatches(batches);
        offsets = tracker.computeOffsets();
        Assert.assertEquals(1, offsets.size());

        batches.get(2).commit();
        tracker.removeAckedEventBatches(batches);
        offsets = tracker.computeOffsets();
        Assert.assertEquals(1, offsets.size());

        batches.get(1).commit();
        tracker.removeAckedEventBatches(batches);
        offsets = tracker.computeOffsets();
        Assert.assertEquals(1, offsets.size());

        offsets = tracker.computeOffsets();
        Assert.assertEquals(1, offsets.size());

    }

    @Test
    public void addEventBatchWithNonSinkRecord() {
        KafkaRecordTracker tracker = new KafkaRecordTracker();
        for (int i = 0; i < 3; i++) {
            EventBatch batch = UnitUtil.createBatch();
            batch.getEvents().get(0).setTied("");
            batch.commit();
            tracker.addEventBatch(batch);
        }
        Map<TopicPartition, OffsetAndMetadata> offsets = tracker.computeOffsets();
        Assert.assertEquals(0, offsets.size());
    }

    private SinkRecord createSinkRecord(long offset) {
        return new SinkRecord("t", 1, null, null, null, "ni, hao", offset);
    }

    private List<TopicPartition> createTopicPartitionList() {
        ArrayList<TopicPartition> tps = new ArrayList<>();
        tps.add(new TopicPartition("t", 1));
        return tps;
    }

    private SinkRecord createSinkRecord(int partition, long offset) {
        return new SinkRecord("t", partition, null, null, null, "ni, hao", offset);
    }

    private List<TopicPartition> createTopicPartitionList(int number) {
        ArrayList<TopicPartition> tps = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            tps.add(new TopicPartition("t", i));
        }
        return tps;
    }
}
