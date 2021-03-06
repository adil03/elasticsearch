/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.recovery;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.replication.ESIndexLevelReplicationTestCase;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.recovery.RecoveriesCollection;
import org.elasticsearch.indices.recovery.RecoveryFailedException;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.indices.recovery.RecoveryTargetService;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;

public class RecoveriesCollectionTests extends ESIndexLevelReplicationTestCase {
    static final RecoveryTargetService.RecoveryListener listener = new RecoveryTargetService.RecoveryListener() {
        @Override
        public void onRecoveryDone(RecoveryState state) {

        }

        @Override
        public void onRecoveryFailure(RecoveryState state, RecoveryFailedException e, boolean sendShardFailure) {

        }
    };

    public void testLastAccessTimeUpdate() throws Exception {
        try (ReplicationGroup shards = createGroup(0)) {
            final RecoveriesCollection collection = new RecoveriesCollection(logger, threadPool);
            final long recoveryId = startRecovery(collection, shards.getPrimaryNode(), shards.addReplica());
            try (RecoveriesCollection.RecoveryRef status = collection.getRecovery(recoveryId)) {
                final long lastSeenTime = status.status().lastAccessTime();
                assertBusy(() -> {
                    try (RecoveriesCollection.RecoveryRef currentStatus = collection.getRecovery(recoveryId)) {
                        assertThat("access time failed to update", lastSeenTime, lessThan(currentStatus.status().lastAccessTime()));
                    }
                });
            } finally {
                collection.cancelRecovery(recoveryId, "life");
            }
        }
    }

    public void testRecoveryTimeout() throws Exception {
        try (ReplicationGroup shards = createGroup(0)) {
            final RecoveriesCollection collection = new RecoveriesCollection(logger, threadPool);
            final AtomicBoolean failed = new AtomicBoolean();
            final CountDownLatch latch = new CountDownLatch(1);
            final long recoveryId = startRecovery(collection, shards.getPrimaryNode(), shards.addReplica(),
            new RecoveryTargetService.RecoveryListener() {
                @Override
                public void onRecoveryDone(RecoveryState state) {
                    latch.countDown();
                }

                @Override
                public void onRecoveryFailure(RecoveryState state, RecoveryFailedException e, boolean sendShardFailure) {
                    failed.set(true);
                    latch.countDown();
                }
            }, TimeValue.timeValueMillis(100));
            try {
                latch.await(30, TimeUnit.SECONDS);
                assertTrue("recovery failed to timeout", failed.get());
            } finally {
                collection.cancelRecovery(recoveryId, "meh");
            }
        }

    }

    public void testRecoveryCancellation() throws Exception {
        try (ReplicationGroup shards = createGroup(0)) {
            final RecoveriesCollection collection = new RecoveriesCollection(logger, threadPool);
            final long recoveryId = startRecovery(collection, shards.getPrimaryNode(), shards.addReplica());
            final long recoveryId2 = startRecovery(collection, shards.getPrimaryNode(), shards.addReplica());
            try (RecoveriesCollection.RecoveryRef recoveryRef = collection.getRecovery(recoveryId)) {
                ShardId shardId = recoveryRef.status().shardId();
                assertTrue("failed to cancel recoveries", collection.cancelRecoveriesForShard(shardId, "test"));
                assertThat("all recoveries should be cancelled", collection.size(), equalTo(0));
            } finally {
                collection.cancelRecovery(recoveryId, "meh");
                collection.cancelRecovery(recoveryId2, "meh");
            }
        }
    }

    public void testResetRecovery() throws Exception {
        try (ReplicationGroup shards = createGroup(0)) {
            shards.startAll();
            int numDocs = randomIntBetween(1, 15);
            shards.indexDocs(numDocs);
            final RecoveriesCollection collection = new RecoveriesCollection(logger, threadPool);
            IndexShard shard = shards.addReplica();
            final long recoveryId = startRecovery(collection, shards.getPrimaryNode(), shard);
            try (RecoveriesCollection.RecoveryRef recovery = collection.getRecovery(recoveryId)) {
                final int currentAsTarget = shard.recoveryStats().currentAsTarget();
                final int referencesToStore = recovery.status().store().refCount();
                String tempFileName = recovery.status().getTempNameForFile("foobar");
                collection.resetRecovery(recoveryId, recovery.status().shardId());
                try (RecoveriesCollection.RecoveryRef resetRecovery = collection.getRecovery(recoveryId)) {
                    assertNotSame(recovery.status(), resetRecovery);
                    assertSame(recovery.status().CancellableThreads(), resetRecovery.status().CancellableThreads());
                    assertSame(recovery.status().indexShard(), resetRecovery.status().indexShard());
                    assertSame(recovery.status().store(), resetRecovery.status().store());
                    assertEquals(referencesToStore + 1, resetRecovery.status().store().refCount());
                    assertEquals(currentAsTarget+1, shard.recoveryStats().currentAsTarget()); // we blink for a short moment...
                    recovery.close();
                    expectThrows(ElasticsearchException.class, () -> recovery.status().store());
                    assertEquals(referencesToStore, resetRecovery.status().store().refCount());
                    String resetTempFileName = resetRecovery.status().getTempNameForFile("foobar");
                    assertNotEquals(tempFileName, resetTempFileName);
                }
                assertEquals(currentAsTarget, shard.recoveryStats().currentAsTarget());
            }
            try (RecoveriesCollection.RecoveryRef resetRecovery = collection.getRecovery(recoveryId)) {
                shards.recoverReplica(shard, (s, n) -> {
                    assertSame(s, resetRecovery.status().indexShard());
                    return resetRecovery.status();
                }, false);
            }
            shards.assertAllEqual(numDocs);
            assertNull("recovery is done", collection.getRecovery(recoveryId));
        }
    }

    long startRecovery(RecoveriesCollection collection, DiscoveryNode sourceNode, IndexShard shard) {
        return startRecovery(collection,sourceNode, shard, listener, TimeValue.timeValueMinutes(60));
    }

    long startRecovery(RecoveriesCollection collection, DiscoveryNode sourceNode, IndexShard indexShard,
                       RecoveryTargetService.RecoveryListener listener, TimeValue timeValue) {
        final DiscoveryNode rNode = getDiscoveryNode(indexShard.routingEntry().currentNodeId());
        indexShard.markAsRecovering("remote", new RecoveryState(indexShard.shardId(), false, RecoveryState.Type.REPLICA, sourceNode,
            rNode));
        indexShard.prepareForIndexRecovery();
        return collection.startRecovery(indexShard, sourceNode, listener, timeValue);
    }
}
