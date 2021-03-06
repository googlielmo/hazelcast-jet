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

package com.hazelcast.jet.pipeline;

import com.hazelcast.jet.accumulator.LongAccumulator;
import com.hazelcast.jet.aggregate.AggregateOperation;
import com.hazelcast.jet.aggregate.AggregateOperation1;
import com.hazelcast.jet.aggregate.AggregateOperations;
import com.hazelcast.jet.aggregate.CoAggregateOperationBuilder;
import com.hazelcast.jet.datamodel.ItemsByTag;
import com.hazelcast.jet.datamodel.Tag;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.datamodel.Tuple3;
import com.hazelcast.jet.function.DistributedBiFunction;
import com.hazelcast.jet.function.DistributedFunction;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collector;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.aggregate.AggregateOperations.aggregateOperation2;
import static com.hazelcast.jet.aggregate.AggregateOperations.aggregateOperation3;
import static com.hazelcast.jet.aggregate.AggregateOperations.coAggregateOperationBuilder;
import static com.hazelcast.jet.datamodel.ItemsByTag.itemsByTag;
import static com.hazelcast.jet.datamodel.Tuple2.tuple2;
import static com.hazelcast.jet.datamodel.Tuple3.tuple3;
import static java.util.Collections.singletonList;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;
import static org.junit.Assert.assertEquals;

public class BatchAggregateTest extends PipelineTestSupport {

    private static final AggregateOperation1<Integer, LongAccumulator, Long> SUMMING =
            AggregateOperations.summingLong(i -> i);

    private static final int FACTOR_1 = 1_000;
    private static final int FACTOR_2 = 1_000_000;

    private static final DistributedFunction<Entry<Integer, Long>, String> FORMAT_FN =
            e -> String.format("(%04d: %04d)", e.getKey(), e.getValue());
    private static final DistributedBiFunction<Integer, Tuple2<Long, Long>, String> FORMAT_FN_2 =
            (key, t2) -> String.format("(%04d: %04d, %04d)", key, t2.f0(), t2.f1());
    private static final DistributedBiFunction<Integer, Tuple3<Long, Long, Long>, String> FORMAT_FN_3 =
            (key, t3) -> String.format("(%04d: %04d, %04d, %04d)", key, t3.f0(), t3.f1(), t3.f2());

    private List<Integer> input;

    @Before
    public void before() {
        input = sequence(itemCount);
    }

    @Test
    public void aggregate() {
        // When
        BatchStage<Long> aggregated = sourceStageFromInput().aggregate(SUMMING);

        // Then
        aggregated.drainTo(sink);
        execute();
        assertEquals(
                singletonList(input.stream().mapToLong(i -> i).sum()),
                new ArrayList<>(sinkList)
        );

    }

    @Test
    public void aggregate2_withSeparateAggrOps() {
        // Given
        BatchStage<Integer> stage = sourceStageFromInput();

        // When
        BatchStage<Tuple2<Long, Long>> aggregated = sourceStageFromInput()
                .aggregate2(SUMMING, stage, SUMMING);

        // Then
        aggregated.drainTo(sink);
        execute();
        long expectedSum = input.stream().mapToLong(i -> i).sum();
        assertEquals(
                singletonList(tuple2(expectedSum, expectedSum)),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    public void aggregate2_withAggrOp2() {
        // When
        BatchStage<Tuple2<Long, Long>> aggregated = sourceStageFromInput()
                .aggregate2(sourceStageFromInput(), aggregateOperation2(SUMMING, SUMMING));

        // Then
        aggregated.drainTo(sink);
        execute();
        long expectedSum = input.stream().mapToLong(i -> i).sum();
        assertEquals(
                singletonList(tuple2(expectedSum, expectedSum)),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    public void aggregate2_withSeparateAggrOps_withOutputFn() {
        // Given
        DistributedBiFunction<Long, Long, Long> outputFn = (a, b) -> 10_000 * a + b;

        // When
        BatchStage<Long> aggregated = sourceStageFromInput().aggregate2(SUMMING,
                sourceStageFromInput(), SUMMING,
                outputFn);

        // Then
        aggregated.drainTo(sink);
        execute();
        long expectedSum = input.stream().mapToLong(i -> i).sum();
        assertEquals(
                singletonList(outputFn.apply(expectedSum, expectedSum)),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    public void aggregate2_withAggrOp2_with_finishFn() {
        // Given
        DistributedBiFunction<Long, Long, Long> outputFn = (a, b) -> 10_000 * a + b;

        // When
        BatchStage<Long> aggregated = sourceStageFromInput().aggregate2(
                sourceStageFromInput(),
                aggregateOperation2(SUMMING, SUMMING, outputFn));

        // Then
        aggregated.drainTo(sink);
        execute();
        long expectedSum = input.stream().mapToLong(i -> i).sum();
        assertEquals(
                singletonList(outputFn.apply(expectedSum, expectedSum)),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    public void aggregate3_withSeparateAggrOps() {
        // Given
        BatchStage<Integer> stage11 = sourceStageFromInput();

        // When
        BatchStage<Tuple2<Long, Long>> aggregated = sourceStageFromInput()
                .aggregate2(SUMMING, stage11, SUMMING);

        // Then
        aggregated.drainTo(sink);
        execute();
        long expectedSum = input.stream().mapToLong(i -> i).sum();
        assertEquals(
                singletonList(tuple2(expectedSum, expectedSum)),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    public void aggregate3_withAggrOp3() {
        // Given
        BatchStage<Integer> stage1 = sourceStageFromInput();
        BatchStage<Integer> stage2 = sourceStageFromInput();

        // When
        BatchStage<Tuple3<Long, Long, Long>> aggregated = sourceStageFromInput().aggregate3(
                stage1, stage2, aggregateOperation3(SUMMING, SUMMING, SUMMING));

        // Then
        aggregated.drainTo(sink);
        execute();
        long expectedSum = input.stream().mapToLong(i -> i).sum();
        assertEquals(
                singletonList(tuple3(expectedSum, expectedSum, expectedSum)),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    public void aggregate3_withSeparateAggrOps_withOutputFn() {
        // When
        BatchStage<Long> aggregated = sourceStageFromInput().aggregate3(SUMMING,
                sourceStageFromInput(), SUMMING,
                sourceStageFromInput(), SUMMING,
                (r0, r1, r2) -> r0 + r1 + r2);

        // Then
        aggregated.drainTo(sink);
        execute();
        assertEquals(
                singletonList(3 * input.stream().mapToLong(i -> i).sum()),
                new ArrayList<>(sinkList));
    }

    @Test
    public void aggregate3_withAggrOp3_withOutputFn() {
        // When
        BatchStage<Long> aggregated = sourceStageFromInput().aggregate3(
                sourceStageFromInput(),
                sourceStageFromInput(),
                aggregateOperation3(SUMMING, SUMMING, SUMMING, (r0, r1, r2) -> r0 + r1 + r2));

        // Then
        aggregated.drainTo(sink);
        execute();
        assertEquals(
                singletonList(3 * input.stream().mapToLong(i -> i).sum()),
                new ArrayList<>(sinkList));
    }

    private class AggregateBuilderFixture {
        DistributedFunction<Integer, Integer> mapFn1 = i -> FACTOR_1 * i;
        DistributedFunction<Integer, Integer> mapFn2 = i -> FACTOR_2 * i;

        BatchStage<Integer> stage1 = sourceStageFromInput().map(mapFn1);
        BatchStage<Integer> stage2 = sourceStageFromInput().map(mapFn2);
    }

    @Test
    public void aggregateBuilder_withSeparateAggrOps() {
        // Given
        AggregateBuilderFixture fx = new AggregateBuilderFixture();

        // When
        AggregateBuilder<Long> b = sourceStageFromInput().aggregateBuilder(SUMMING);
        Tag<Long> tag0 = b.tag0();
        Tag<Long> tag1 = b.add(fx.stage1, SUMMING);
        Tag<Long> tag2 = b.add(fx.stage2, SUMMING);
        BatchStage<ItemsByTag> aggregated = b.build();

        // Then
        aggregated.drainTo(sink);
        execute();
        long sum0 = input.stream().mapToLong(i -> i).sum();
        assertEquals(
                singletonList(itemsByTag(tag0, sum0, tag1, FACTOR_1 * sum0, tag2, FACTOR_2 * sum0)),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    public void aggregateBuilder_withComplexAggrOp() {
        // Given
        AggregateBuilderFixture fx = new AggregateBuilderFixture();

        // When
        AggregateBuilder1<Integer> b = sourceStageFromInput().aggregateBuilder();
        Tag<Integer> tag0_in = b.tag0();
        Tag<Integer> tag1_in = b.add(fx.stage1);
        Tag<Integer> tag2_in = b.add(fx.stage2);

        CoAggregateOperationBuilder agb = coAggregateOperationBuilder();
        Tag<Long> tag0 = agb.add(tag0_in, SUMMING);
        Tag<Long> tag1 = agb.add(tag1_in, SUMMING);
        Tag<Long> tag2 = agb.add(tag2_in, SUMMING);
        AggregateOperation<Object[], ItemsByTag> aggrOp = agb.build();

        BatchStage<ItemsByTag> aggregated = b.build(aggrOp);

        // Then
        aggregated.drainTo(sink);
        execute();
        long sum0 = input.stream().mapToLong(i -> i).sum();
        assertEquals(
                singletonList(itemsByTag(tag0, sum0, tag1, FACTOR_1 * sum0, tag2, FACTOR_2 * sum0)),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void aggregateBuilder_withSeparateAggrOps_withOutputFn() {
        // Given
        BatchStage<Integer> stage1 = sourceStageFromInput();
        BatchStage<Integer> stage2 = sourceStageFromInput();

        // When
        AggregateBuilder<Long> b = sourceStageFromInput().aggregateBuilder(SUMMING);
        Tag<Long> tag0 = b.tag0();
        Tag<Long> tag1 = b.add(stage1, SUMMING);
        Tag<Long> tag2 = b.add(stage2, SUMMING);
        BatchStage<Long> aggregated = b.build(ibt -> ibt.get(tag0) + ibt.get(tag1) + ibt.get(tag2));

        // Then
        aggregated.drainTo(sink);
        execute();
        assertEquals(
                singletonList(3 * input.stream().mapToLong(i -> i).sum()),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void aggregateBuilder_with_complexAggrOp_withOutputFn() {
        // Given
        BatchStage<Integer> stage1 = sourceStageFromInput();
        BatchStage<Integer> stage2 = sourceStageFromInput();

        // When
        AggregateBuilder1<Integer> b = sourceStageFromInput().aggregateBuilder();
        Tag<Integer> tag0_in = b.tag0();
        Tag<Integer> tag1_in = b.add(stage1);
        Tag<Integer> tag2_in = b.add(stage2);

        CoAggregateOperationBuilder agb = coAggregateOperationBuilder();
        Tag<Long> tag0 = agb.add(tag0_in, SUMMING);
        Tag<Long> tag1 = agb.add(tag1_in, SUMMING);
        Tag<Long> tag2 = agb.add(tag2_in, SUMMING);
        AggregateOperation<Object[], Long> SUMMING = agb.build(ibt -> ibt.get(tag0) + ibt.get(tag1) + ibt.get(tag2));

        BatchStage<Long> aggregated = b.build(SUMMING);

        // Then
        aggregated.drainTo(sink);
        execute();
        assertEquals(
                singletonList(3 * input.stream().mapToLong(i -> i).sum()),
                new ArrayList<>(sinkList)
        );
    }

    @Test
    public void groupAggregate() {
        // Given
        DistributedFunction<Integer, Integer> keyFn = i -> i % 5;

        // When
        BatchStage<Entry<Integer, Long>> aggregated = sourceStageFromInput()
                .groupingKey(keyFn)
                .aggregate(SUMMING);

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expected = input.stream().collect(groupingBy(keyFn, summingLong(i -> i)));
        assertEquals(
                streamToString(expected.entrySet().stream(), FORMAT_FN),
                streamToString(sinkStreamOfEntry(), FORMAT_FN));
    }

    @Test
    public void groupAggregate_withOutputFn() {
        // Given
        DistributedFunction<Integer, Integer> keyFn = i -> i % 5;

        // When
        BatchStage<String> aggregated = sourceStageFromInput()
                .groupingKey(keyFn)
                .aggregate(SUMMING, (key, sum) -> FORMAT_FN.apply(entry(key, sum)));

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expected = input.stream().collect(groupingBy(keyFn, summingLong(i -> i)));
        assertEquals(
                streamToString(expected.entrySet().stream(), FORMAT_FN),
                streamToString(sinkStreamOf(String.class), identity()));
    }

    private class GroupAggregateFixture {
        final DistributedFunction<Integer, Integer> keyFn;
        final DistributedFunction<Integer, Integer> mapFn1;
        final DistributedFunction<Integer, Integer> mapFn2;
        final Collector<Integer, ?, Long> collectOp;
        final BatchStage<Integer> srcStage0;

        // Initialization in costructor to avoid lambda capture of `this`
        GroupAggregateFixture() {
            int offset = itemCount;
            keyFn = i -> i % 10;
            mapFn1 = i -> i + offset;
            mapFn2 = i -> i + 2 * offset;
            srcStage0 = sourceStageFromInput();
            collectOp = summingLong(i -> i);
        }

        BatchStage<Integer> srcStage1() {
            return sourceStageFromInput().map(mapFn1);
        }

        BatchStage<Integer> srcStage2() {
            return sourceStageFromInput().map(mapFn2);
        }
    }

    @Test
    public void groupAggregate2_withSeparateAggrOps() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStage<Entry<Integer, Tuple2<Long, Long>>> aggregated = stage0.aggregate2(SUMMING, stage1, SUMMING);

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap.entrySet().stream(),
                        e -> FORMAT_FN_2.apply(e.getKey(), tuple2(e.getValue(), expectedMap1.get(e.getKey())))),
                streamToString(this.<Integer, Tuple2<Long, Long>>sinkStreamOfEntry(),
                        e -> FORMAT_FN_2.apply(e.getKey(), e.getValue()))
        );
    }

    @Test
    public void groupAggregate2_withAggrOp2() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStage<Entry<Integer, Tuple2<Long, Long>>> aggregated =
                stage0.aggregate2(stage1, aggregateOperation2(SUMMING, SUMMING));

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap0 = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap0.entrySet().stream(),
                        e -> FORMAT_FN_2.apply(e.getKey(), tuple2(e.getValue(), expectedMap1.get(e.getKey())))),
                streamToString(this.<Integer, Tuple2<Long, Long>>sinkStreamOfEntry(),
                        e -> FORMAT_FN_2.apply(e.getKey(), e.getValue()))
        );
    }

    @Test
    public void groupAggregate2_withSeparateAggrOps_withOutputFn() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStage<String> aggregated = stage0.aggregate2(SUMMING, stage1, SUMMING,
                (key, sum0, sum1) -> FORMAT_FN_2.apply(key, tuple2(sum0, sum1)));

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap0 = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap0.entrySet().stream(),
                        e -> FORMAT_FN_2.apply(e.getKey(), tuple2(e.getValue(), expectedMap1.get(e.getKey())))),
                streamToString(sinkStreamOf(String.class), identity())
        );
    }

    @Test
    public void groupAggregate2_withAggrOp2_withOutputFn() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStage<String> aggregated = stage0.aggregate2(stage1, aggregateOperation2(SUMMING, SUMMING), FORMAT_FN_2);

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap0 = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap0.entrySet().stream(),
                        e -> FORMAT_FN_2.apply(e.getKey(), tuple2(e.getValue(), expectedMap1.get(e.getKey())))),
                streamToString(sinkStreamOf(String.class), identity())
        );
    }

    @Test
    public void groupAggregate3_withSeparateAggrOps() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage2 = fx.srcStage2().groupingKey(fx.keyFn);
        BatchStage<Entry<Integer, Tuple3<Long, Long, Long>>> aggregated =
                stage0.aggregate3(stage1, stage2, aggregateOperation3(SUMMING, SUMMING, SUMMING));

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap0 = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap2 = input.stream().map(fx.mapFn2).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap0.entrySet().stream(), e -> FORMAT_FN_3.apply(
                        e.getKey(),
                        tuple3(e.getValue(), expectedMap1.get(e.getKey()), expectedMap2.get(e.getKey())))),
                streamToString(this.<Integer, Tuple3<Long, Long, Long>>sinkStreamOfEntry(), e -> FORMAT_FN_3.apply(
                        e.getKey(),
                        e.getValue()))
        );
    }

    @Test
    public void groupAggregate3_withAggrOp3() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage2 = fx.srcStage2().groupingKey(fx.keyFn);
        BatchStage<Entry<Integer, Tuple3<Long, Long, Long>>> aggregated =
                stage0.aggregate3(stage1, stage2, aggregateOperation3(SUMMING, SUMMING, SUMMING));

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap2 = input.stream().map(fx.mapFn2).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap.entrySet().stream(), e -> FORMAT_FN_3.apply(
                        e.getKey(),
                        tuple3(e.getValue(), expectedMap1.get(e.getKey()), expectedMap2.get(e.getKey())))),
                streamToString(this.<Integer, Tuple3<Long, Long, Long>>sinkStreamOfEntry(), e -> FORMAT_FN_3.apply(
                        e.getKey(),
                        e.getValue()))
        );
    }

    @Test
    public void groupAggregate3_withSeparateAggrOps_withOutputFn() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage2 = fx.srcStage2().groupingKey(fx.keyFn);
        BatchStage<String> aggregated = stage0.aggregate3(SUMMING, stage1, SUMMING, stage2, SUMMING,
                (key, sum0, sum1, sum2) -> FORMAT_FN_3.apply(key, tuple3(sum0, sum1, sum2)));

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap0 = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap2 = input.stream().map(fx.mapFn2).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap0.entrySet().stream(), e -> FORMAT_FN_3.apply(
                        e.getKey(),
                        tuple3(e.getValue(), expectedMap1.get(e.getKey()), expectedMap2.get(e.getKey())))),
                streamToString(sinkStreamOf(String.class), identity())
        );
    }

    @Test
    public void groupAggregate3_withAggrOp3_withOutputFn() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage2 = fx.srcStage2().groupingKey(fx.keyFn);
        BatchStage<String> aggregated = stage0.aggregate3(stage1, stage2,
                aggregateOperation3(SUMMING, SUMMING, SUMMING),
                FORMAT_FN_3);

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap0 = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap2 = input.stream().map(fx.mapFn2).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap0.entrySet().stream(), e -> FORMAT_FN_3.apply(
                        e.getKey(),
                        tuple3(e.getValue(), expectedMap1.get(e.getKey()), expectedMap2.get(e.getKey())))),
                streamToString(sinkStreamOf(String.class), identity())
        );
    }

    @Test
    public void groupAggregateBuilder_withSeparateAggrOps_withOutputFn() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage2 = fx.srcStage2().groupingKey(fx.keyFn);
        GroupAggregateBuilder<Integer, Long> b = stage0.aggregateBuilder(SUMMING);
        Tag<Long> tag0 = b.tag0();
        Tag<Long> tag1 = b.add(stage1, SUMMING);
        Tag<Long> tag2 = b.add(stage2, SUMMING);
        BatchStage<String> aggregated = b.build((key, ibt) -> FORMAT_FN_3.apply(
                key, tuple3(ibt.get(tag0), ibt.get(tag1), ibt.get(tag2))
        ));

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap0 = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap2 = input.stream().map(fx.mapFn2).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap0.entrySet().stream(), e -> FORMAT_FN_3.apply(
                        e.getKey(),
                        tuple3(e.getValue(), expectedMap1.get(e.getKey()), expectedMap2.get(e.getKey())))),
                streamToString(sinkStreamOf(String.class), identity())
        );
    }

    @Test
    public void groupAggregateBuilder_withComplexAggrOp_withOutputFn() {
        // Given
        GroupAggregateFixture fx = new GroupAggregateFixture();

        // When
        BatchStageWithKey<Integer, Integer> stage0 = fx.srcStage0.groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage1 = fx.srcStage1().groupingKey(fx.keyFn);
        BatchStageWithKey<Integer, Integer> stage2 = fx.srcStage2().groupingKey(fx.keyFn);

        GroupAggregateBuilder1<Integer, Integer> b = stage0.aggregateBuilder();
        Tag<Integer> inTag0 = b.tag0();
        Tag<Integer> inTag1 = b.add(stage1);
        Tag<Integer> inTag2 = b.add(stage2);

        CoAggregateOperationBuilder agb = coAggregateOperationBuilder();
        Tag<Long> tag0 = agb.add(inTag0, SUMMING);
        Tag<Long> tag1 = agb.add(inTag1, SUMMING);
        Tag<Long> tag2 = agb.add(inTag2, SUMMING);
        AggregateOperation<Object[], ItemsByTag> complexAggrOp = agb.build();
        BatchStage<String> aggregated = b.build(complexAggrOp, (key, ibt) -> FORMAT_FN_3.apply(
                key, tuple3(ibt.get(tag0), ibt.get(tag1), ibt.get(tag2))
        ));

        // Then
        aggregated.drainTo(sink);
        execute();
        Map<Integer, Long> expectedMap0 = input.stream().collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap1 = input.stream().map(fx.mapFn1).collect(groupingBy(fx.keyFn, fx.collectOp));
        Map<Integer, Long> expectedMap2 = input.stream().map(fx.mapFn2).collect(groupingBy(fx.keyFn, fx.collectOp));
        assertEquals(
                streamToString(expectedMap0.entrySet().stream(), e -> FORMAT_FN_3.apply(
                        e.getKey(),
                        tuple3(e.getValue(), expectedMap1.get(e.getKey()), expectedMap2.get(e.getKey())))),
                streamToString(sinkStreamOf(String.class), identity())
        );
    }

    private BatchStage<Integer> sourceStageFromInput() {
        List<Integer> input = this.input;
        BatchSource<Integer> source = SourceBuilder
                .batch("sequence", x -> null)
                .<Integer>fillBufferFn((x, buf) -> {
                    input.forEach(buf::add);
                    buf.close();
                }).build();
        return p.drawFrom(source);
    }
}
