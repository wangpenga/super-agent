package org.javaup.ai.chatagent.rag.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventWriter;



public final class ExecutorEventSupport {

    private ExecutorEventSupport() {
    }

    public static void publishThinking(TaskInfo taskInfo, StreamEventWriter writer, String content) {
        if (taskInfo == null || writer == null || StrUtil.isBlank(content)) {
            return;
        }
        taskInfo.thinkingSteps().add(content);
        SinkEmitHelper.emitNext(taskInfo.sink(), writer.thinking(content, taskInfo.eventMetadata()));
    }

    public static void publishStatus(TaskInfo taskInfo, StreamEventWriter writer, String content) {
        if (taskInfo == null || writer == null || StrUtil.isBlank(content)) {
            return;
        }
        SinkEmitHelper.emitNext(taskInfo.sink(), writer.status(content, taskInfo.eventMetadata()));
    }
}
