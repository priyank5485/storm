/*
 * Copyright 2017 The Apache Software Foundation.
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
package org.apache.storm.kafka.spout;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyCollection;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.storm.kafka.spout.builders.SingleTopicKafkaSpoutConfiguration;
import org.apache.storm.kafka.spout.internal.KafkaConsumerFactory;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.apache.storm.kafka.spout.builders.SingleTopicKafkaSpoutConfiguration.getKafkaSpoutConfigBuilder;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.util.HashSet;
import org.apache.storm.utils.Time;
import org.apache.storm.utils.Time.SimulatedTime;
import org.mockito.InOrder;

public class KafkaSpoutEmitTest {

    private final long offsetCommitPeriodMs = 2_000;
    private final TopologyContext contextMock = mock(TopologyContext.class);
    private final SpoutOutputCollector collectorMock = mock(SpoutOutputCollector.class);
    private final Map<String, Object> conf = new HashMap<>();
    private final TopicPartition partition = new TopicPartition(SingleTopicKafkaSpoutConfiguration.TOPIC, 1);
    private KafkaConsumer<String, String> consumerMock;
    private KafkaSpout<String, String> spout;
    private KafkaSpoutConfig spoutConfig;

    private void setupSpout(Set<TopicPartition> assignedPartitions) {
        spoutConfig = getKafkaSpoutConfigBuilder(-1)
            .setOffsetCommitPeriodMs(offsetCommitPeriodMs)
            .build();

        consumerMock = mock(KafkaConsumer.class);
        KafkaConsumerFactory<String, String> consumerFactory = new KafkaConsumerFactory<String, String>() {
            @Override
            public KafkaConsumer<String, String> createConsumer(KafkaSpoutConfig<String, String> kafkaSpoutConfig) {
                return consumerMock;
            }
        };

        //Set up a spout listening to 1 topic partition
        spout = new KafkaSpout<>(spoutConfig, consumerFactory);

        spout.open(conf, contextMock, collectorMock);
        spout.activate();

        ArgumentCaptor<ConsumerRebalanceListener> rebalanceListenerCapture = ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
        verify(consumerMock).subscribe(anyCollection(), rebalanceListenerCapture.capture());

        //Assign partitions to the spout
        ConsumerRebalanceListener consumerRebalanceListener = rebalanceListenerCapture.getValue();
        consumerRebalanceListener.onPartitionsAssigned(assignedPartitions);
    }

    @Test
    public void testNextTupleEmitsAtMostOneTuple() {
        //The spout should emit at most one message per call to nextTuple
        //This is necessary for Storm to be able to throttle the spout according to maxSpoutPending
        setupSpout(Collections.singleton(partition));
        Map<TopicPartition, List<ConsumerRecord<String, String>>> records = new HashMap<>();
        List<ConsumerRecord<String, String>> recordsForPartition = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            recordsForPartition.add(new ConsumerRecord(partition.topic(), partition.partition(), i, "key", "value"));
        }
        records.put(partition, recordsForPartition);

        when(consumerMock.poll(anyLong()))
            .thenReturn(new ConsumerRecords(records));

        spout.nextTuple();

        verify(collectorMock, times(1)).emit(anyString(), anyList(), anyObject());
    }

    @Test
    public void testNextTupleEmitsFailedMessagesEvenWhenMaxUncommittedOffsetsIsExceeded() {
        //The spout must reemit failed messages waiting for retry even if it is not allowed to poll for new messages due to maxUncommittedOffsets being exceeded
        
        //Emit maxUncommittedOffsets messages, and fail all of them. Then ensure that the spout will retry them when the retry backoff has passed
        try (SimulatedTime simulatedTime = new SimulatedTime()) {
            setupSpout(Collections.singleton(partition));
            Map<TopicPartition, List<ConsumerRecord<String, String>>> records = new HashMap<>();
            List<ConsumerRecord<String, String>> recordsForPartition = new ArrayList<>();
            for (int i = 0; i < spoutConfig.getMaxUncommittedOffsets(); i++) {
                //This is cheating a bit since maxPollRecords would normally spread this across multiple polls
                recordsForPartition.add(new ConsumerRecord(partition.topic(), partition.partition(), i, "key", "value"));
            }
            records.put(partition, recordsForPartition);

            when(consumerMock.poll(anyLong()))
                .thenReturn(new ConsumerRecords(records));

            for (int i = 0; i < recordsForPartition.size(); i++) {
                spout.nextTuple();
            }

            ArgumentCaptor<KafkaSpoutMessageId> messageIds = ArgumentCaptor.forClass(KafkaSpoutMessageId.class);
            verify(collectorMock, times(recordsForPartition.size())).emit(anyString(), anyList(), messageIds.capture());

            for (KafkaSpoutMessageId messageId : messageIds.getAllValues()) {
                spout.fail(messageId);
            }

            reset(collectorMock);

            Time.advanceTime(50);
            //No backoff for test retry service, just check that messages will retry immediately
            for (int i = 0; i < recordsForPartition.size(); i++) {
                spout.nextTuple();
            }

            ArgumentCaptor<KafkaSpoutMessageId> retryMessageIds = ArgumentCaptor.forClass(KafkaSpoutMessageId.class);
            verify(collectorMock, times(recordsForPartition.size())).emit(anyString(), anyList(), retryMessageIds.capture());

            //Verify that the poll started at the earliest retriable tuple offset
            List<Long> failedOffsets = new ArrayList<>();
            for(KafkaSpoutMessageId msgId : messageIds.getAllValues()) {
                failedOffsets.add(msgId.offset());
            }
            InOrder inOrder = inOrder(consumerMock);
            inOrder.verify(consumerMock).seek(partition, failedOffsets.get(0));
            inOrder.verify(consumerMock).poll(anyLong());
        }
    }
    
    @Test
    public void testNextTupleEmitsAtMostMaxUncommittedOffsetsPlusMaxPollRecordsWhenRetryingTuples() {
        /*
        The spout must reemit failed messages waiting for retry even if it is not allowed to poll for new messages due to maxUncommittedOffsets being exceeded.
        numUncommittedOffsets is equal to numNonRetriableEmittedTuples + numRetriableTuples.
        The spout will only emit if numUncommittedOffsets - numRetriableTuples < maxUncommittedOffsets (i.e. numNonRetriableEmittedTuples < maxUncommittedOffsets)
        This means that the latest offset a poll can start at for a retriable partition,
        counting from the last committed offset, is maxUncommittedOffsets,
        where there are maxUncommittedOffsets - 1 uncommitted tuples "to the left".
        If the retry poll starts at that offset, it at most emits the retried tuple plus maxPollRecords - 1 new tuples.
        The limit on uncommitted offsets for one partition is therefore maxUncommittedOffsets + maxPollRecords - 1.
        
        It is only necessary to test this for a single partition, because partitions can't contribute negatively to numNonRetriableEmittedTuples,
        so if the limit holds for one partition, it will also hold for each individual partition when multiple are involved.
        
        This makes the actual limit numPartitions * (maxUncommittedOffsets + maxPollRecords - 1)
         */
        
        //Emit maxUncommittedOffsets messages, and fail only the last. Then ensure that the spout will allow no more than maxUncommittedOffsets + maxPollRecords - 1 uncommitted offsets when retrying
        try (SimulatedTime simulatedTime = new SimulatedTime()) {
            setupSpout(Collections.singleton(partition));
            
            Map<TopicPartition, List<ConsumerRecord<String, String>>> firstPollRecords = new HashMap<>();
            List<ConsumerRecord<String, String>> firstPollRecordsForPartition = new ArrayList<>();
            for (int i = 0; i < spoutConfig.getMaxUncommittedOffsets(); i++) {
                //This is cheating a bit since maxPollRecords would normally spread this across multiple polls
                firstPollRecordsForPartition.add(new ConsumerRecord(partition.topic(), partition.partition(), i, "key", "value"));
            }
            firstPollRecords.put(partition, firstPollRecordsForPartition);
            
            int maxPollRecords = 5;
            Map<TopicPartition, List<ConsumerRecord<String, String>>> secondPollRecords = new HashMap<>();
            List<ConsumerRecord<String, String>> secondPollRecordsForPartition = new ArrayList<>();
            for(int i = 0; i < maxPollRecords; i++) {
                secondPollRecordsForPartition.add(new ConsumerRecord(partition.topic(), partition.partition(), spoutConfig.getMaxUncommittedOffsets() + i, "key", "value"));
            }
            secondPollRecords.put(partition, secondPollRecordsForPartition);

            when(consumerMock.poll(anyLong()))
                .thenReturn(new ConsumerRecords(firstPollRecords))
                .thenReturn(new ConsumerRecords(secondPollRecords));

            for (int i = 0; i < spoutConfig.getMaxUncommittedOffsets() + maxPollRecords; i++) {
                spout.nextTuple();
            }

            ArgumentCaptor<KafkaSpoutMessageId> messageIds = ArgumentCaptor.forClass(KafkaSpoutMessageId.class);
            verify(collectorMock, times(firstPollRecordsForPartition.size())).emit(anyString(), anyList(), messageIds.capture());

            KafkaSpoutMessageId failedMessageId = messageIds.getAllValues().get(messageIds.getAllValues().size() - 1);
            spout.fail(failedMessageId);

            reset(collectorMock);

            //Now make the single failed tuple retriable
            Time.advanceTime(50);
            //The spout should allow another poll since there are now only maxUncommittedOffsets - 1 nonretriable tuples
            for (int i = 0; i < firstPollRecordsForPartition.size() + maxPollRecords; i++) {
                spout.nextTuple();
            }

            ArgumentCaptor<KafkaSpoutMessageId> retryBatchMessageIdsCaptor = ArgumentCaptor.forClass(KafkaSpoutMessageId.class);
            verify(collectorMock, times(maxPollRecords)).emit(anyString(), anyList(), retryBatchMessageIdsCaptor.capture());
            reset(collectorMock);
            
            //Check that the consumer started polling at the failed tuple offset
            InOrder inOrder = inOrder(consumerMock);
            inOrder.verify(consumerMock).seek(partition, failedMessageId.offset());
            inOrder.verify(consumerMock).poll(anyLong());
            
            //Now fail all except one of the last batch, and check that the spout won't reemit any tuples because there are more than maxUncommittedOffsets nonretriable tuples
            Time.advanceTime(50);
            List<KafkaSpoutMessageId> retryBatchMessageIds = retryBatchMessageIdsCaptor.getAllValues();
            KafkaSpoutMessageId firstTupleFromRetryBatch = retryBatchMessageIds.remove(0);
            for(KafkaSpoutMessageId msgId : retryBatchMessageIds) {
                spout.fail(msgId);
            }
            for (int i = 0; i < firstPollRecordsForPartition.size() + maxPollRecords; i++) {
                spout.nextTuple();
            }
            verify(collectorMock, never()).emit(anyString(), anyList(), anyObject());
            
            //Fail the last tuple, which brings the number of nonretriable tuples back under the limit, and check that the spout polls again
            spout.fail(firstTupleFromRetryBatch);
            spout.nextTuple();
            verify(collectorMock, times(1)).emit(anyString(), anyList(), anyObject());
        }
    }

}