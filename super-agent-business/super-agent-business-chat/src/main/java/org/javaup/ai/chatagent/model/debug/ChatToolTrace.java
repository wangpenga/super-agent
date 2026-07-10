package org.javaup.ai.chatagent.model.debug;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 单次工具调用观测快照
 * @author: wangpeng
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatToolTrace {

    private String toolName;

    private String status;

    private String inputSummary;

    private String effectiveInput;

    private String outputSummary;

    private String errorMessage;

    private Integer referenceCount;

    private String topic;

    private Long durationMs;
}
