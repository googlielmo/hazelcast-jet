/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.impl;

import com.hazelcast.core.IMap;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.JetTestSupport;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.TestProcessors;
import com.hazelcast.jet.core.TestProcessors.NoOutputSourceP;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.Sources;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.test.HazelcastSerialClassRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Properties;

import static com.hazelcast.jet.impl.JobRepository.RANDOM_IDS_MAP_NAME;
import static com.hazelcast.jet.impl.util.JetGroupProperty.JOB_SCAN_PERIOD;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(HazelcastSerialClassRunner.class)
public class JobRepositoryTest extends JetTestSupport {

    private static final long RESOURCES_EXPIRATION_TIME_MILLIS = SECONDS.toMillis(1);
    private static final long JOB_SCAN_PERIOD_IN_MILLIS = HOURS.toMillis(1);

    private JobConfig jobConfig = new JobConfig();
    private JetInstance instance;
    private JobRepository jobRepository;
    private IMap<Long, Long> jobIds;

    @Before
    public void setup() {
        JetConfig config = new JetConfig();
        Properties properties = config.getProperties();
        properties.setProperty(JOB_SCAN_PERIOD.getName(), Long.toString(JOB_SCAN_PERIOD_IN_MILLIS));

        instance = createJetMember(config);
        jobRepository = new JobRepository(instance);
        jobRepository.setResourcesExpirationMillis(RESOURCES_EXPIRATION_TIME_MILLIS);

        jobIds = instance.getMap(RANDOM_IDS_MAP_NAME);

        TestProcessors.reset(2);
    }

    @Test
    public void when_jobIsCompleted_then_jobIsCleanedUp() {
        long jobId = uploadResourcesForNewJob();
        Data dag = createDAGData();
        JobRecord jobRecord = createJobRecord(jobId, dag);
        jobRepository.putNewJobRecord(jobRecord);
        long executionId1 = jobRepository.newExecutionId(jobId);
        long executionId2 = jobRepository.newExecutionId(jobId);
        JobResult jobResult = new JobResult(jobId, jobConfig, "uuid", jobRecord.getCreationTime(),
                System.currentTimeMillis(), null);
        instance.getMap(JobRepository.JOB_RESULTS_MAP_NAME).put(jobId, jobResult);

        jobRepository.cleanup(emptySet());

        assertNull("jobRecord not null", jobRepository.getJobRecord(jobId));
        // There's a race in IMap.destroy: when two threads destroy the same map, the isEmpty call
        // just after the destroy call can return false. In this test, one thread is this test and the other
        // is the automatic cleanup.
        assertTrueEventually(() ->
                assertTrue("job resources not empty", jobRepository.getJobResources(jobId).isEmpty()), 3);
        assertFalse("jobIds contains executionId1", jobIds.containsKey(executionId1));
        assertFalse("jobIds contains executionId2", jobIds.containsKey(executionId2));
    }

    @Test
    public void when_jobIsRunning_then_expiredJobIsNotCleanedUp() {
        long jobId = uploadResourcesForNewJob();
        Data dag = createDAGData();
        JobRecord jobRecord = createJobRecord(jobId, dag);
        jobRepository.putNewJobRecord(jobRecord);
        long executionId1 = jobRepository.newExecutionId(jobId);
        long executionId2 = jobRepository.newExecutionId(jobId);

        sleepUntilJobExpires();

        jobRepository.cleanup(singleton(jobId));

        assertNotNull(jobRepository.getJobRecord(jobId));
        assertFalse(jobRepository.getJobResources(jobId).isEmpty());
        assertTrue(jobIds.containsKey(executionId1));
        assertTrue(jobIds.containsKey(executionId2));
    }

    @Test
    public void when_jobRecordIsPresentForExpiredJob_then_jobIsNotCleanedUp() {
        long jobId = uploadResourcesForNewJob();
        Data dag = createDAGData();
        JobRecord jobRecord = createJobRecord(jobId, dag);
        jobRepository.putNewJobRecord(jobRecord);
        long executionId1 = jobRepository.newExecutionId(jobId);
        long executionId2 = jobRepository.newExecutionId(jobId);

        sleepUntilJobExpires();

        jobRepository.cleanup(emptySet());

        assertNotNull(jobRepository.getJobRecord(jobId));
        assertFalse(jobRepository.getJobResources(jobId).isEmpty());
        assertTrue(jobIds.containsKey(executionId1));
        assertTrue(jobIds.containsKey(executionId2));
    }

    @Test
    public void when_onlyJobResourcesExist_then_jobResourcesClearedAfterExpiration() {
        long jobId = uploadResourcesForNewJob();

        sleepUntilJobExpires();

        jobRepository.cleanup(emptySet());

        assertTrue(jobRepository.getJobResources(jobId).isEmpty());
    }

    @Test
    public void when_jobResourceUploadFails_then_jobResourcesCleanedUp() {
        jobConfig.addResource("invalid path");
        try {
            jobRepository.uploadJobResources(jobConfig);
            fail();
        } catch (JetException e) {
            assertTrue(instance.getMap(RANDOM_IDS_MAP_NAME).isEmpty());
        }
    }

    @Test
    public void test_getJobRecordFromClient() {
        JetInstance client = createJetClient();
        Pipeline p = Pipeline.create();
        p.drawFrom(Sources.streamFromProcessor("source", ProcessorMetaSupplier.of(() -> new NoOutputSourceP())))
         .withoutTimestamps()
         .drainTo(Sinks.logger());
        Job job = instance.newJob(p, new JobConfig()
                .setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE)
                .setSnapshotIntervalMillis(100));
        JobRepository jobRepository = new JobRepository(client);
        assertTrueEventually(() -> assertNotNull(jobRepository.getJobRecord(job.getId())));
        client.shutdown();
    }

    private long uploadResourcesForNewJob() {
        jobConfig.addClass(DummyClass.class);
        return jobRepository.uploadJobResources(jobConfig);
    }

    private Data createDAGData() {
        return getNodeEngineImpl(instance.getHazelcastInstance()).toData(new DAG());
    }

    private JobRecord createJobRecord(long jobId, Data dag) {
        return new JobRecord(jobId, System.currentTimeMillis(), dag, "", jobConfig);
    }

    private void sleepUntilJobExpires() {
        sleepAtLeastMillis(2 * RESOURCES_EXPIRATION_TIME_MILLIS);
    }

    private static class DummyClass { }
}
