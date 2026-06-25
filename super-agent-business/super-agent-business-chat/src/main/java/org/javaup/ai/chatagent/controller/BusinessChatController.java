package org.javaup.ai.chatagent.controller;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.javaup.ai.chatagent.dto.ChatRequestDto;
import org.javaup.ai.chatagent.dto.ConversationExchangeDetailQueryDto;
import org.javaup.ai.chatagent.dto.ConversationIdentityDto;
import org.javaup.ai.chatagent.dto.ConversationSessionListQueryDto;
import org.javaup.ai.chatagent.dto.RetrievalObserveQueryDto;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.ConversationExchangeDetailView;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.KnowledgeDocumentOptionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.StageBenchmarkView;
import org.javaup.ai.chatagent.service.BusinessChatService;
import org.javaup.ai.chatagent.vo.ConversationResetVo;
import org.javaup.ai.chatagent.vo.ConversationSessionListVo;
import org.javaup.ai.chatagent.vo.ConversationStopVo;
import org.javaup.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 业务聊天控制器 - SSE 流式对话入口
 * <p>
 * <b>核心链路：</b>
 * <pre>
 * Controller.stream(dto)
 *   → Service.openConversationStream
 *       ├─ buildLaunchPlan     参数校验 + 规范化
 *       ├─ claimConversationLease    Redis 租约防并发
 *       └─ bootstrapConversation    DB 记录 + TaskInfo + Sink 绑定
 *           └─ bindClientChannel → activateGeneration
 *                ├─ startLeaseRenewal     定时续约
 *                └─ buildConversationExecution
 *                     ├─ prepareExecutionPlan    ChatPreparationOrchestrator
 *                     │    ├─ summarizeHistory        装载会话记忆
 *                     │    ├─ 按 chatMode 路由:
 *                     │    │    OPEN_CHAT     → REACT_AGENT (ReactAgent LLM+工具)
 *                     │    │    AUTO_DOCUMENT → 自动选文档或 CLARIFICATION
 *                     │    │    DOCUMENT      → RAG 检索或结构图查询
 *                     │    └─ 返回 ExecutionPlan
 *                     ├─ executorRegistry.get(mode)    选执行器
 *                     └─ executor.execute(taskInfo)    5 种执行器之一
 *                          ├─ ReactAgentExecutor      LLM 自主推理
 *                          ├─ RagChatExecutor         双通道检索+LLM生成
 *                          ├─ ClarificationExecutor   直接返回澄清文本
 *                          ├─ GraphOnlyExecutor        结构图直接回答
 *                          └─ GraphThenEvidenceExecutor 结构图定位+校验
 *   → emitModelChunk(每条文本推SSE) → finishSuccessfully/finishWithFailure/stopTask
 * </pre>
 *
 * @author 阿星不是程序员
 **/
@AllArgsConstructor
@RestController
@RequestMapping("/api/chat")
public class BusinessChatController {

    private final BusinessChatService businessChatService;

    /**
     * SSE 流式对话（POST /api/chat/stream）
     * <p>
     * 接收用户问题，通过 {@link Flux#defer} 延迟执行，返回 SSE 事件流。
     * 每个元素是一条 JSON 格式的 SSE 事件（text/thinking/error/reference/recommend）。
     * <p>
     * <b>入参：</b>
     * <ul>
     *   <li>question（必填）：用户问题</li>
     *   <li>conversationId（可选，不传自动生成 UUID）</li>
     *   <li>chatMode（必填）：OPEN_CHAT / DOCUMENT / AUTO_DOCUMENT</li>
     *   <li>selectedDocumentId（DOCUMENT 模式下必填）</li>
     * </ul>
     * <p>
     * <b>链路：</b>openConversationStream → 租约加锁 → 引导启动 → 执行计划编排 → 执行器执行 → 收尾
     *
     * @param dto 聊天请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@Valid @RequestBody ChatRequestDto dto) {
        return businessChatService.openConversationStream(dto);
    }

    @PostMapping("/document/options")
    public ApiResponse<List<KnowledgeDocumentOptionView>> documentOptions() {
        return ApiResponse.ok(businessChatService.listKnowledgeDocumentOptions());
    }

    @PostMapping("/session/stop")
    public ApiResponse<ConversationStopVo> stop(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.stopConversation(dto.getConversationId()));
    }

    @PostMapping("/session/detail")
    public ApiResponse<ConversationSessionView> session(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.getSession(dto.getConversationId()));
    }

    @PostMapping("/exchange/detail")
    public ApiResponse<ConversationExchangeDetailView> exchange(@Valid @RequestBody ConversationExchangeDetailQueryDto dto) {
        return ApiResponse.ok(businessChatService.getExchangeDetail(dto.getConversationId(), dto.getExchangeId()));
    }

    @PostMapping("/session/list")
    public ApiResponse<ConversationSessionListVo> sessions(@RequestBody(required = false) ConversationSessionListQueryDto dto) {
        return ApiResponse.ok(businessChatService.listSessions(dto));
    }

    @PostMapping("/session/reset")
    public ApiResponse<ConversationResetVo> reset(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.resetConversation(dto.getConversationId()));
    }

    @PostMapping("/session/summary/rebuild")
    public ApiResponse<ConversationMemorySummaryView> rebuildSummary(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.rebuildConversationSummary(dto.getConversationId()));
    }

    @PostMapping("/exchange/retrieval/results")
    public ApiResponse<List<RetrievalResultView>> retrievalResults(@Valid @RequestBody RetrievalObserveQueryDto dto) {
        return ApiResponse.ok(businessChatService.getRetrievalResults(dto.getConversationId(), Long.parseLong(dto.getExchangeId())));
    }

    @PostMapping("/exchange/channel/executions")
    public ApiResponse<List<ChannelExecutionView>> channelExecutions(@Valid @RequestBody RetrievalObserveQueryDto dto) {
        return ApiResponse.ok(businessChatService.getChannelExecutions(dto.getConversationId(), Long.parseLong(dto.getExchangeId())));
    }

    @PostMapping("/stage/benchmarks")
    public ApiResponse<List<StageBenchmarkView>> stageBenchmarks() {
        return ApiResponse.ok(businessChatService.getStageBenchmarks());
    }
}
