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

package com.hazelcast.jet.function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Represents the function you pass to a windowed aggregation method in the
 * Pipeline API, such as {@link
 * com.hazelcast.jet.pipeline.StageWithWindow#aggregate stage.aggregate()}.
 * It creates the item to emit based on the results of a single aggregate
 * operation performed for a particular window.
 * <p>
 * The parameters are:
 * <ol><li>
 *     {@code winStart} and {@code winEnd}: the starting and ending timestamp
 *     of the window (the end timestamp is the exclusive upper bound)
 * </li><li>
 *     {@code windowResult} the result of the aggregate operation
 * </li></ol>
 *
 * @param <R> the type of aggregation result this function receives
 * @param <OUT> the type of the output item this function returns
 */
@FunctionalInterface
public interface WindowResultFunction<R, OUT> extends Serializable {

    @Nullable
    OUT apply(long start, long end, @Nonnull R result);

    default KeyedWindowResultFunction<Object, R, OUT> toKeyedWindowResultFn() {
        return (start, end, k, result) -> apply(start, end, result);
    }
}
