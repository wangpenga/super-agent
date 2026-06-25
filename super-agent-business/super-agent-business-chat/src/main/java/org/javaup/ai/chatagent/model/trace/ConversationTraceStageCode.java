package org.javaup.ai.chatagent.model.trace;

/**
 * 对话执行阶段编码 —— 定义一轮对话从开始到结束的所有追踪阶段
 * <p>
 * 每个阶段按 order 顺序执行，order 值表示执行的先后顺序。
 *
 * @author 阿星不是程序员
 */
public enum ConversationTraceStageCode {

    /** 会话记忆装载 —— 加载长期摘要 + 近期对话窗口 */
    MEMORY("MEMORY", "会话记忆", 10),

    /** 意图分析 —— 判断用户想干什么 */
    INTENT("INTENT", "意图分析", 20),

    /** 问题改写 —— LLM 将口语化问题改写为检索友好的查询 */
    REWRITE("REWRITE", "问题改写", 30),

    /** 路由判定 —— 决定走 REACT_AGENT / GRAPH_ONLY / RETRIEVAL 等 */
    ROUTE("ROUTE", "路由判定", 40),

    /** 结构图查询 —— 从文档结构图查询章节/编号项关系 */
    GRAPH_QUERY("GRAPH_QUERY", "结构图查询", 45),

    /** RAG 检索 —— 双通道混合检索（结构图 + 向量语义） */
    RAG_RETRIEVE("RAG_RETRIEVE", "RAG 检索", 50),

    /** 证据评估与预算控制 —— Token 预算下组织检索证据到 Prompt */
    EVIDENCE_BUDGET("EVIDENCE_BUDGET", "证据评估与预算控制", 60),

    /** 回答生成 —— LLM 流式生成最终答案 */
    ANSWER_GENERATE("ANSWER_GENERATE", "回答生成", 70),

    /** ReAct Agent 执行 —— OPEN_CHAT 模式下 LLM 自主推理 + 工具调用 */
    REACT_AGENT("REACT_AGENT", "ReAct Agent", 75),

    /** 推荐问题 —— 生成推荐追问 */
    RECOMMENDATION("RECOMMENDATION", "推荐问题", 80),

    /** 收尾归档 —— 落库保存、释放资源 */
    FINALIZE("FINALIZE", "收尾归档", 90);

    /** 编码 → 写入 trace_stage.stage_code */
    private final String code;
    /** 中文标签 → 写入 trace_stage.stage_name */
    private final String label;
    /** 执行顺序 → 写入 trace_stage.stage_order */
    private final int order;

    ConversationTraceStageCode(String code, String label, int order) {
        this.code = code;
        this.label = label;
        this.order = order;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public int getOrder() {
        return order;
    }
}
