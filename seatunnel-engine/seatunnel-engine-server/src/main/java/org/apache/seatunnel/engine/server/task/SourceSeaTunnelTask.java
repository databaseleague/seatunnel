/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.engine.server.task;

import org.apache.seatunnel.api.common.metrics.MetricsContext;
import org.apache.seatunnel.api.env.EnvCommonOptions;
import org.apache.seatunnel.api.serialization.Serializer;
import org.apache.seatunnel.api.source.SourceSplit;
import org.apache.seatunnel.core.starter.flowcontrol.FlowControlStrategy;
import org.apache.seatunnel.engine.core.dag.actions.SourceAction;
import org.apache.seatunnel.engine.server.dag.physical.config.SourceConfig;
import org.apache.seatunnel.engine.server.dag.physical.flow.PhysicalExecutionFlow;
import org.apache.seatunnel.engine.server.execution.ProgressState;
import org.apache.seatunnel.engine.server.execution.TaskLocation;
import org.apache.seatunnel.engine.server.task.flow.SourceFlowLifeCycle;
import org.apache.seatunnel.engine.server.task.record.Barrier;

import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import lombok.Getter;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SourceSeaTunnelTask<T, SplitT extends SourceSplit> extends SeaTunnelTask {

    private static final ILogger LOGGER = Logger.getLogger(SourceSeaTunnelTask.class);

    private transient SeaTunnelSourceCollector<T> collector;

    private transient Object checkpointLock;
    @Getter private transient Serializer<SplitT> splitSerializer;
    private final Map<String, Object> envOption;
    private final PhysicalExecutionFlow<SourceAction, SourceConfig> sourceFlow;

    public SourceSeaTunnelTask(
            long jobID,
            TaskLocation taskID,
            int indexID,
            PhysicalExecutionFlow<SourceAction, SourceConfig> executionFlow,
            Map<String, Object> envOption) {
        super(jobID, taskID, indexID, executionFlow);
        this.sourceFlow = executionFlow;
        this.envOption = envOption;
    }

    @Override
    public void init() throws Exception {
        super.init();
        this.checkpointLock = new Object();
        this.splitSerializer = sourceFlow.getAction().getSource().getSplitSerializer();

        LOGGER.info("starting seatunnel source task, index " + indexID);
        if (!(startFlowLifeCycle instanceof SourceFlowLifeCycle)) {
            throw new TaskRuntimeException(
                    "SourceSeaTunnelTask only support SourceFlowLifeCycle, but get "
                            + startFlowLifeCycle.getClass().getName());
        } else {
            this.collector =
                    new SeaTunnelSourceCollector<>(
                            checkpointLock,
                            outputs,
                            this.getMetricsContext(),
                            getFlowControlStrategy(),
                            sourceFlow.getAction().getSource().getProducedType());
            ((SourceFlowLifeCycle<T, SplitT>) startFlowLifeCycle).setCollector(collector);
        }
    }

    @Override
    protected SourceFlowLifeCycle<?, ?> createSourceFlowLifeCycle(
            SourceAction<?, ?, ?> sourceAction,
            SourceConfig config,
            CompletableFuture<Void> completableFuture,
            MetricsContext metricsContext) {
        return new SourceFlowLifeCycle<>(
                sourceAction,
                indexID,
                config.getEnumeratorTask(),
                this,
                taskLocation,
                completableFuture,
                metricsContext);
    }

    @Override
    protected void collect() throws Exception {
        ((SourceFlowLifeCycle<T, SplitT>) startFlowLifeCycle).collect();
    }

    @NonNull @Override
    public ProgressState call() throws Exception {
        stateProcess();
        return progress.toState();
    }

    public void receivedSourceSplit(List<SplitT> splits) {
        ((SourceFlowLifeCycle<T, SplitT>) startFlowLifeCycle).receivedSplits(splits);
    }

    @Override
    public void triggerBarrier(Barrier barrier) throws Exception {
        SourceFlowLifeCycle<T, SplitT> sourceFlow =
                (SourceFlowLifeCycle<T, SplitT>) startFlowLifeCycle;
        sourceFlow.triggerBarrier(barrier);
    }

    private FlowControlStrategy getFlowControlStrategy() {
        FlowControlStrategy strategy;
        if (envOption.containsKey(EnvCommonOptions.READ_LIMIT_BYTES_PER_SECOND.key())
                && envOption.containsKey(EnvCommonOptions.READ_LIMIT_ROW_PER_SECOND.key())) {
            strategy =
                    FlowControlStrategy.of(
                            Integer.parseInt(
                                    envOption
                                            .get(EnvCommonOptions.READ_LIMIT_BYTES_PER_SECOND.key())
                                            .toString()),
                            Integer.parseInt(
                                    envOption
                                            .get(EnvCommonOptions.READ_LIMIT_ROW_PER_SECOND.key())
                                            .toString()));
        } else if (envOption.containsKey(EnvCommonOptions.READ_LIMIT_BYTES_PER_SECOND.key())) {
            strategy =
                    FlowControlStrategy.ofBytes(
                            Integer.parseInt(
                                    envOption
                                            .get(EnvCommonOptions.READ_LIMIT_BYTES_PER_SECOND.key())
                                            .toString()));
        } else if (envOption.containsKey(EnvCommonOptions.READ_LIMIT_ROW_PER_SECOND.key())) {
            strategy =
                    FlowControlStrategy.ofCount(
                            Integer.parseInt(
                                    envOption
                                            .get(EnvCommonOptions.READ_LIMIT_ROW_PER_SECOND.key())
                                            .toString()));
        } else {
            strategy = null;
        }
        return strategy;
    }
}
