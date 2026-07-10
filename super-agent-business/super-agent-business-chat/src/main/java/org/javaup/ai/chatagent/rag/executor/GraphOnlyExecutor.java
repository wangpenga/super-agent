package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.service.GraphAnswerRenderer;
import org.javaup.ai.chatagent.rag.service.StructureGraphQueryEngine;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.manage.model.graph.GraphQueryResult;
import org.javaup.ai.manage.model.graph.GraphSectionWithChildren;
import org.javaup.ai.manage.model.graph.GraphSectionWithSiblings;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 结构图直接回答执行器 - 调用链路第5层分支 D（GRAPH_ONLY 模式）
 * <p>
 * <b>适用场景：</b>文档问答中，问题可以通过文档结构图直接回答，无需 RAG 检索。
 * 典型如："第三章有哪些小节？""第5条的前一条是什么？""这个文档有几章？"
 * <p>
 * <b>核心逻辑：</b>
 * 直接查询文档结构图（预构建的章节层级关系图），根据 navigationAction 选择查询方式：
 * <ul>
 *   <li><b>SECTION_ADJACENCY_LOOKUP</b>：查询章节的相邻关系（父章节 + 前/后兄弟章节）
 *       适用于 "上一章是什么？""下一节是什么？" 类问题</li>
 *   <li><b>其他（默认）</b>：查询章节的子章节（目标章节 + 所有子章节列表）
 *       适用于 "第三章有哪些小节？""这一节有哪些子节点？" 类问题</li>
 * </ul>
 * <p>
 * <b>与 GraphThenEvidenceExecutor 的区别：</b>
 * <ul>
 *   <li>GRAPH_ONLY：直接返回结构图查询结果，不做证据校验</li>
 *   <li>GRAPH_THEN_EVIDENCE：先走结构图定位章节/编号项，再校验是否有足够证据</li>
 * </ul>
 * <p>
 * <b>不调用 LLM：</b>和 ClarificationExecutor 一样，本执行器不调用 LLM，直接返回渲染后的结构图文本。
 *
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 结构图直接回答执行器 - 不调用 LLM，直接从文档结构图查询并渲染答案
 * @author: wangpeng
 **/
@Component
@Slf4j
public class GraphOnlyExecutor implements ConversationExecutor {

    /**
     * 文档结构图查询引擎：提供基于文档章节树的查询能力
     * - findSectionWithSiblings: 查找章节及其相邻兄弟
     * - findSectionWithChildren: 查找章节及其子章节
     */
    private final StructureGraphQueryEngine structureGraphQueryEngine;
    /**
     * 图查询结果渲染器：将 GraphQueryResult 渲染为用户可读的自然语言文本
     */
    private final GraphAnswerRenderer graphAnswerRenderer;
    private final StreamEventWriter streamEventWriter;

    public GraphOnlyExecutor(StructureGraphQueryEngine structureGraphQueryEngine,
                             GraphAnswerRenderer graphAnswerRenderer,
                             StreamEventWriter streamEventWriter) {
        this.structureGraphQueryEngine = structureGraphQueryEngine;
        this.graphAnswerRenderer = graphAnswerRenderer;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        // 本执行器处理 GRAPH_ONLY 模式（纯结构图查询）
        return ExecutionMode.GRAPH_ONLY;
    }

    /**
     * 执行结构图查询并渲染答案
     * <p>
     * <b>前置条件：</b>executionPlan 中必须有有效的 navigationDecision.structureAnchor.structureNodeId。
     * 如果没有（计划阶段路由失败），直接返回无证据兜底文本。
     * <p>
     * <b>查询路径分派：</b>
     * navigationAction == SECTION_ADJACENCY_LOOKUP → 查相邻兄弟
     * 否则 → 查子章节
     * <p>
     * <b>答案渲染：</b>graphAnswerRenderer 将结构化的章节信息渲染为可读文本，
     * 如 "第三章「系统设计」包含以下小节：3.1 架构概述、3.2 模块设计、3.3 接口定义"
     *
     * @param taskInfo 运行时任务上下文
     * @return 包含渲染答案文本的单元素 Flux
     */
    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        DocumentNavigationDecision decision = plan == null ? null : plan.getNavigationDecision();
        if (plan == null || decision == null || decision.getStructureAnchor() == null || decision.getStructureAnchor().getStructureNodeId() == null) {
            log.info("GRAPH_ONLY 执行器直接返回无证据: planPresent={}, decisionPresent={}, structureNodeId={}",
                plan != null,
                decision != null,
                decision == null || decision.getStructureAnchor() == null ? null : decision.getStructureAnchor().getStructureNodeId());
            return Flux.just(StrUtil.blankToDefault(plan == null ? "" : plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。"));
        }
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在通过结构图直接查询章节关系。");
        ConversationTraceRecorder.StageHandle graphStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.GRAPH_QUERY, mode().name(), "正在执行结构图查询。", null);
        Long documentId = plan.getSelectedDocumentId();
        Long sectionNodeId = decision.getStructureAnchor().getStructureNodeId();
        log.info("GRAPH_ONLY 执行开始: documentId={}, sectionNodeId={}, action={}, navigationSummary='{}'",
            documentId,
            sectionNodeId,
            decision.getNavigationAction(),
            decision.getSummaryText());
        GraphQueryResult graphResult;
        if (decision.getNavigationAction() == org.javaup.ai.chatagent.rag.model.DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP) {
            GraphSectionWithSiblings result = structureGraphQueryEngine.findSectionWithSiblings(documentId, sectionNodeId);
            graphResult = GraphQueryResult.builder()
                .targetSection(result.getSection())
                .parentSection(result.getParent())
                .previousSibling(result.getPreviousSibling())
                .nextSibling(result.getNextSibling())
                .build();
        }
        else {
            GraphSectionWithChildren result = structureGraphQueryEngine.findSectionWithChildren(documentId, sectionNodeId);
            graphResult = GraphQueryResult.builder()
                .targetSection(result.getSection())
                .children(result.getChildren())
                .build();
        }
        String answer = graphAnswerRenderer.renderGraphAnswer(mode(), decision, graphResult);
        log.info("GRAPH_ONLY 执行完成: documentId={}, sectionNodeId={}, targetSection='{}', answerLength={}",
            documentId,
            sectionNodeId,
            graphResult.getTargetSection() == null ? "" : graphResult.getTargetSection().displayTitle(),
            answer == null ? 0 : answer.length());
        if (taskInfo.traceRecorder() != null) {
            taskInfo.traceRecorder().completeStage(graphStage, "结构图查询完成。", Map.of(
                "targetSection", graphResult.getTargetSection() == null ? "" : StrUtil.blankToDefault(graphResult.getTargetSection().displayTitle(), ""),
                "parentSection", graphResult.getParentSection() == null ? "" : StrUtil.blankToDefault(graphResult.getParentSection().displayTitle(), ""),
                "childCount", graphResult.getChildren() == null ? 0 : graphResult.getChildren().size(),
                "previousSibling", graphResult.getPreviousSibling() == null ? "" : StrUtil.blankToDefault(graphResult.getPreviousSibling().displayTitle(), ""),
                "nextSibling", graphResult.getNextSibling() == null ? "" : StrUtil.blankToDefault(graphResult.getNextSibling().displayTitle(), ""),
                "answer", StrUtil.blankToDefault(answer, "")
            ));
        }
        return Flux.fromIterable(answer.isBlank() ? List.of(StrUtil.blankToDefault(plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。")) : List.of(answer));
    }
}
