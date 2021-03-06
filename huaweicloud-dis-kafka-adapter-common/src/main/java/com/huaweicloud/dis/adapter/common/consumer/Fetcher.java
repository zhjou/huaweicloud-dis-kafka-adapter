/*
 * Copyright 2002-2010 the original author or authors.
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

package com.huaweicloud.dis.adapter.common.consumer;

import com.huaweicloud.dis.DISAsync;
import com.huaweicloud.dis.DISClientAsync;
import com.huaweicloud.dis.DISConfig;
import com.huaweicloud.dis.adapter.common.model.StreamPartition;
import com.huaweicloud.dis.core.handler.AsyncHandler;
import com.huaweicloud.dis.iface.data.request.GetRecordsRequest;
import com.huaweicloud.dis.iface.data.response.GetRecordsResult;
import com.huaweicloud.dis.iface.data.response.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;


public class Fetcher {

    private static final Logger log = LoggerFactory.getLogger(Fetcher.class);

    private DISAsync disAsync;

    private ConcurrentHashMap<StreamPartition, PartitionCursor> nextIterators;

    private SubscriptionState subscriptions;

    private AtomicInteger receivedCnt;

    private Map<StreamPartition, Future<GetRecordsResult>> futures;

    private Coordinator coordinator;

    private int defaultMaxPartitionFetchRecords = 1000;

    private int defaultMaxFetchThreads = 100;

    public static final String KEY_MAX_PARTITION_FETCH_RECORDS = "max.partition.fetch.records";

    public static final String KEY_MAX_FETCH_THREADS = "max.fetch.threads";

    public Fetcher(DISConfig disConfig, SubscriptionState subscriptions, Coordinator coordinator, ConcurrentHashMap<StreamPartition, PartitionCursor> nextIterators) {
        if (disConfig.get(KEY_MAX_PARTITION_FETCH_RECORDS) != null) {
            defaultMaxPartitionFetchRecords = Integer.valueOf(disConfig.get(KEY_MAX_PARTITION_FETCH_RECORDS).toString());
        }

        if (disConfig.get(KEY_MAX_FETCH_THREADS) != null) {
            defaultMaxFetchThreads = Integer.valueOf(disConfig.get(KEY_MAX_FETCH_THREADS).toString());
        }

        this.subscriptions = subscriptions;
        this.coordinator = coordinator;
        receivedCnt = new AtomicInteger(0);
        this.nextIterators = nextIterators;
        this.futures = new HashMap<>();
        this.disAsync = new DISClientAsync(disConfig, Executors.newFixedThreadPool(defaultMaxFetchThreads));
    }


    public void sendFetchRequests() {
        if (!subscriptions.hasAllFetchPositions()) {
            coordinator.updateFetchPositions(subscriptions.missingFetchPositions());
        }
        Set<StreamPartition> needUpdateOffset = new HashSet<>();
        for (StreamPartition partition : subscriptions.assignedPartitions()) {
            if (!subscriptions.isPaused(partition)) {
                if (nextIterators.get(partition) == null || nextIterators.get(partition).isExpire()) {
                    //coordinator.seek(partition, subscriptions.position(partition));
                    needUpdateOffset.add(partition);
                }
            }
        }
        CountDownLatch countDownLatch = new CountDownLatch(needUpdateOffset.size());
        for (StreamPartition partition : needUpdateOffset) {
            coordinator.seek(partition, countDownLatch);
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }

        for (StreamPartition partition : subscriptions.fetchablePartitions()) {
            GetRecordsRequest getRecordsParam = new GetRecordsRequest();
            if (nextIterators.get(partition) == null || nextIterators.get(partition).isExpire()) {
                log.warn("partition " + partition + " next cursor is null, can not send fetch request");
                continue;
            }
            getRecordsParam.setPartitionCursor(nextIterators.get(partition).getNextPartitionCursor());
            getRecordsParam.setLimit(defaultMaxPartitionFetchRecords);
            if (futures.get(partition) == null) {
                futures.put(partition, disAsync.getRecordsAsync(getRecordsParam, new AsyncHandler<GetRecordsResult>() {
                    @Override
                    public void onError(Exception exception) {
                        receivedCnt.getAndIncrement();
                        log.error(exception.getMessage(), exception);
                        if (exception.getMessage().contains("DIS.4319") || exception.getMessage().contains("DIS.4302")) {
                            log.warn(exception.getMessage(), exception);
                            coordinator.requestRebalance();
                        }
                    }

                    @Override
                    public void onSuccess(GetRecordsResult getRecordsResult) {
                        receivedCnt.getAndIncrement();
                    }
                }));
            }
        }
    }

    public Map<StreamPartition, List<Record>> fetchRecords(long timeout) {
        Map<StreamPartition, List<Record>> multipleRecords = new HashMap<>();
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() - now <= timeout && receivedCnt.get() == 0) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            }
        }
        if (receivedCnt.get() > 0) {
            for (StreamPartition partition : subscriptions.fetchablePartitions()) {
                if (futures.get(partition) != null && futures.get(partition).isDone()) {
                    try {
                        GetRecordsResult getRecordsResult = futures.get(partition).get();
                        List<Record> records = getRecordsResult.getRecords();
                        multipleRecords.putIfAbsent(partition, new ArrayList<>());
                        multipleRecords.get(partition).addAll(records);
                        nextIterators.put(partition, new PartitionCursor(getRecordsResult.getNextPartitionCursor()));
                        if (!records.isEmpty()) {
                            subscriptions.seek(partition,
                                    Long.valueOf(records.get(records.size() - 1).getSequenceNumber()) + 1L);
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        log.error(e.getMessage(), e);
                    } finally {
                        futures.remove(partition);
                        receivedCnt.decrementAndGet();
                    }
                }
            }
        }
        return multipleRecords;
    }

    public void pause(StreamPartition partition) {
        futures.remove(partition);
        nextIterators.remove(partition);
    }
}
