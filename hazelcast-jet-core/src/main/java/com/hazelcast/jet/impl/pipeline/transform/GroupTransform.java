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

package com.hazelcast.jet.impl.pipeline.transform;

import com.hazelcast.jet.aggregate.AggregateOperation;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.function.DistributedBiFunction;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.impl.pipeline.Planner;
import com.hazelcast.jet.impl.pipeline.Planner.PlannerVertex;

import javax.annotation.Nonnull;
import java.util.List;

import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.Partitioner.HASH_CODE;
import static com.hazelcast.jet.core.processor.Processors.accumulateByKeyP;
import static com.hazelcast.jet.core.processor.Processors.aggregateByKeyP;
import static com.hazelcast.jet.core.processor.Processors.combineByKeyP;
import static com.hazelcast.jet.function.DistributedFunctions.entryKey;
import static com.hazelcast.jet.impl.pipeline.transform.AbstractTransform.Optimization.MEMORY;
import static com.hazelcast.jet.impl.pipeline.transform.AggregateTransform.FIRST_STAGE_VERTEX_NAME_SUFFIX;

public class GroupTransform<K, A, R, OUT> extends AbstractTransform {
    @Nonnull
    private final List<DistributedFunction<?, ? extends K>> groupKeyFns;
    @Nonnull
    private final AggregateOperation<A, R> aggrOp;
    @Nonnull
    private final DistributedBiFunction<? super K, ? super R, OUT> mapToOutputFn;

    public GroupTransform(
            @Nonnull List<Transform> upstream,
            @Nonnull List<DistributedFunction<?, ? extends K>> groupKeyFns,
            @Nonnull AggregateOperation<A, R> aggrOp,
            @Nonnull DistributedBiFunction<? super K, ? super R, OUT> mapToOutputFn
    ) {
        super(createName(upstream), upstream);
        this.groupKeyFns = groupKeyFns;
        this.aggrOp = aggrOp;
        this.mapToOutputFn = mapToOutputFn;
    }

    private static String createName(@Nonnull List<Transform> upstream) {
        return upstream.size() == 1
                ? "group-and-aggregate"
                : upstream.size() + "-way cogroup-and-aggregate";
    }

    @Override
    public void addToDag(Planner p) {
        if (getOptimization() == MEMORY || aggrOp.combineFn() == null) {
            addToDagSingleStage(p);
        } else {
            addToDagTwoStage(p);
        }
    }

    //                   ---------        ---------
    //                  | source0 |  ... | sourceN |
    //                   ---------        ---------
    //                       |                  |
    //                  distributed        distributed
    //                  partitioned        partitioned
    //                       |                  |
    //                       \                  /
    //                        ----\      /------
    //                            v      v
    //                         -----------------
    //                        | aggregateByKeyP |
    //                         -----------------
    private void addToDagSingleStage(Planner p) {
        PlannerVertex pv = p.addVertex(this, name(), localParallelism(),
                aggregateByKeyP(groupKeyFns, aggrOp, mapToOutputFn));
        p.addEdges(this, pv.v, (e, ord) -> e.distributed().partitioned(groupKeyFns.get(ord)));
    }

    //                   ---------        ---------
    //                  | source0 |  ... | sourceN |
    //                   ---------        ---------
    //                       |                |
    //                     local            local
    //                  partitioned      partitioned
    //                       v                v
    //                      --------------------
    //                     |  accumulateByKeyP  |
    //                      --------------------
    //                                |
    //                           distributed
    //                           partitioned
    //                                v
    //                         ---------------
    //                        | combineByKeyP |
    //                         ---------------
    private void addToDagTwoStage(Planner p) {
        List<DistributedFunction<?, ? extends K>> groupKeyFns = this.groupKeyFns;
        Vertex v1 = p.dag.newVertex(name() + FIRST_STAGE_VERTEX_NAME_SUFFIX, accumulateByKeyP(groupKeyFns, aggrOp))
                .localParallelism(localParallelism());
        PlannerVertex pv2 = p.addVertex(this, name(), localParallelism(),
                combineByKeyP(aggrOp, mapToOutputFn));
        p.addEdges(this, v1, (e, ord) -> e.partitioned(groupKeyFns.get(ord), HASH_CODE));
        p.dag.edge(between(v1, pv2.v).distributed().partitioned(entryKey()));
    }
}
