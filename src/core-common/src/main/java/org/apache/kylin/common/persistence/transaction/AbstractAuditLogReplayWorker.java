/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kylin.common.persistence.transaction;

import static org.apache.kylin.common.exception.CommonErrorCode.FAILED_CONNECT_META_DATABASE;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.collections.CollectionUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.constant.LogConstant;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.logging.SetLogCategory;
import org.apache.kylin.common.persistence.AuditLog;
import org.apache.kylin.common.persistence.UnitMessages;
import org.apache.kylin.common.persistence.event.Event;
import org.apache.kylin.common.persistence.metadata.AuditLogStore;
import org.apache.kylin.common.util.DaemonThreadFactory;
import org.apache.kylin.common.util.ExecutorServiceUtil;
import org.apache.kylin.guava30.shaded.common.base.Preconditions;
import org.apache.kylin.guava30.shaded.common.collect.Maps;

import lombok.Getter;
import lombok.Setter;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractAuditLogReplayWorker {

    protected static final long STEP = 1000;
    protected final AuditLogStore auditLogStore;
    protected final KylinConfig config;

    protected volatile ScheduledExecutorService consumeExecutor;

    // only a thread is necessary
    protected static volatile ScheduledExecutorService publicExecutorPool = Executors.newScheduledThreadPool(1,
            new DaemonThreadFactory("PublicReplayWorker"));

    protected final AtomicBoolean isStopped = new AtomicBoolean(false);

    protected final int replayWaitMaxRetryTimes;
    protected final long replayWaitMaxTimeoutMills;

    @Setter
    protected String modelUuid;

    protected AbstractAuditLogReplayWorker(KylinConfig config, AuditLogStore auditLogStore) {
        this.config = config;
        this.auditLogStore = auditLogStore;
        this.replayWaitMaxRetryTimes = config.getReplayWaitMaxRetryTimes();
        this.replayWaitMaxTimeoutMills = config.getReplayWaitMaxTimeout();
    }

    public abstract void startSchedule(long currentId, boolean syncImmediately);

    public void catchup() {
        if (isStopped.get()) {
            return;
        }
        consumeExecutor.submit(() -> catchupInternal(1));
    }

    public void catchupFrom(long expected) {
        updateOffset(expected);
        catchup();
    }

    public void close(boolean isGracefully) {
        isStopped.set(true);
        if (!consumeExecutor.equals(publicExecutorPool)) {
            if (isGracefully) {
                ExecutorServiceUtil.shutdownGracefully(consumeExecutor, 60);
            } else {
                ExecutorServiceUtil.forceShutdown(consumeExecutor);
            }
        }
    }

    protected void replayLogs(MessageSynchronization replayer, List<AuditLog> logs) {
        if (CollectionUtils.isEmpty(logs)) {
            return;
        }
        Map<String, UnitMessages> messagesMap = Maps.newLinkedHashMap();
        for (AuditLog log : logs) {
            if (modelUuid != null && !modelUuid.equals(log.getModelUuid())) {
                continue;
            }

            val event = Event.fromLog(log);
            String unitId = log.getUnitId();
            if (messagesMap.get(unitId) == null) {
                UnitMessages newMessages = new UnitMessages();
                newMessages.getMessages().add(event);
                messagesMap.put(unitId, newMessages);
            } else {
                messagesMap.get(unitId).getMessages().add(event);
            }
        }

        try (SetLogCategory ignored = new SetLogCategory(LogConstant.METADATA_CATEGORY)) {
            for (UnitMessages message : messagesMap.values()) {
                log.debug("replay {} event for project:{}", message.getMessages().size(), message.getKey());
                replayer.replay(message);
            }
        }
    }

    public abstract long getLogOffset();

    public abstract void updateOffset(long expected);

    protected abstract void catchupInternal(int countDown);

    protected abstract boolean hasCatch(long targetId);

    protected boolean logAllCommit(long startOffset, long endOffset) {
        return auditLogStore.count(startOffset, endOffset) == (endOffset - startOffset);
    }

    protected void handleReloadAll(Exception e) {
        log.error("Critical exception happened, try to reload metadata ", e);
        try {
            MessageSynchronization messageSynchronization = MessageSynchronization
                    .getInstance(KylinConfig.getInstanceFromEnv());
            messageSynchronization.replayAllMetadata(false);
        } catch (Throwable th) {
            log.error("reload all failed", th);
        }
        log.info("Reload finished");
    }

    protected void threadWait(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public void waitForCatchup(long targetId, long timeout) throws TimeoutException {
        long endTime = System.currentTimeMillis() + timeout * 1000;
        try {
            while (System.currentTimeMillis() < endTime) {
                if (hasCatch(targetId)) {
                    return;
                }
                Thread.sleep(50);
            }
        } catch (Exception e) {
            log.info("Wait for catchup to {} failed", targetId, e);
            Thread.currentThread().interrupt();
        }
        throw new TimeoutException(String.format(Locale.ROOT, "Cannot reach %s before %s, current is %s", targetId,
                endTime, getLogOffset()));
    }

    public void reStartSchedule(long currentId) {
        if (!isStopped.get()) {
            log.info("replayer is running , don't need restart");
            return;
        }
        isStopped.set(false);
        consumeExecutor = Executors.newScheduledThreadPool(1, new DaemonThreadFactory("ReplayWorker"));
        startSchedule(currentId, false);
    }

    public static class StartReloadEvent {
    }

    public static class EndReloadEvent {
    }

    protected static class DatabaseNotAvailableException extends KylinException {
        public DatabaseNotAvailableException(Exception e) {
            super(FAILED_CONNECT_META_DATABASE, e);
        }
    }

    @Getter
    /**
     * (start,end]
     */
    protected static class FixedWindow {
        protected long start;
        protected long end;

        public FixedWindow(long start, long end) {
            this.start = start;
            this.end = end;
            Preconditions.checkState(start <= end);
        }

        boolean isEmpty() {
            return length() == 0;
        }

        long length() {
            return end - start;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "(%s,%s]", start, end);
        }
    }

    protected static class SlideWindow extends FixedWindow {

        private final long upperLimit;

        public SlideWindow(FixedWindow batchWindow) {
            super(batchWindow.getStart(), batchWindow.getStart());
            upperLimit = batchWindow.getEnd();
            Preconditions.checkState(end <= upperLimit);
        }

        boolean canForwardRight() {
            return upperLimit > end;
        }

        boolean forwardRightStep(long step) {
            if (!canForwardRight()) {
                return false;
            }
            end = Math.min(end + step, upperLimit);
            return true;
        }

        void syncRightStep() {
            start = end;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "(%s,%s,%s]", start, end, upperLimit);
        }
    }

}
