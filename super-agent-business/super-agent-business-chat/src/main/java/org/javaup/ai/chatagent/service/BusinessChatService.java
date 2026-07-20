package org.javaup.ai.chatagent.service;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.dto.ChatRequestDto;
import org.javaup.ai.chatagent.dto.ConversationSessionListQueryDto;
import org.javaup.ai.chatagent.model.ConversationExchangeDetailView;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.KnowledgeDocumentOptionView;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.StageBenchmarkView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.executor.ConversationExecutor;
import org.javaup.ai.chatagent.rag.executor.ConversationExecutorRegistry;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.chatagent.rag.service.ChatPreparationOrchestrator;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventMetadata;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.chatagent.vo.ConversationResetVo;
import org.javaup.ai.chatagent.vo.ConversationSessionListVo;
import org.javaup.ai.chatagent.vo.ConversationStopVo;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.javaup.enums.ChatTurnStatus;
import org.javaup.enums.ChatQueryMode;
import org.javaup.exception.SuperAgentFrameException;
import org.javaup.lease.RedisLeaseManager;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务聊天服务 - 流式对话的核心编排层（调用链路第2~6层）
 * <p>
 * <b>在整个调用链路中的角色：</b>
 * 作为 Controller → Executor 之间的中央协调器，负责：
 * <ul>
 *   <li><b>启动保护</b>：Redis 分布式租约防并发 + 运行时注册表防重复</li>
 *   <li><b>会话引导</b>：数据库记录轮次 + 创建 TaskInfo 上下文 + Sink 绑定</li>
 *   <li><b>执行管道</b>：prepareExecutionPlan → 选择 Executor → 流式输出</li>
 *   <li><b>生命周期管理</b>：租约续期、成功/失败/取消三种收尾路径</li>
 * </ul>
 *
 * <pre>
 * 核心方法调用流程：
 *
 * openConversationStream(dto)
 *   └─ openDeferredConversationStream(dto)           ← Flux.defer 延迟执行
 *        ├─ buildLaunchPlan(dto)                     ← 构建 StreamLaunchPlan
 *        ├─ claimConversationLease(plan)             ← Redis 租约加锁
 *        └─ bootstrapConversation(plan)              ← 启动引导
 *             ├─ archiveStore.startExchange(...)     ← DB 记录本轮对话
 *             ├─ createTaskInfo(plan, exchange)      ← 构建 TaskInfo + Sink + RunnableConfig
 *             ├─ runtimeRegistry.register(taskInfo)  ← 注册运行时任务
 *             └─ bindClientChannel(taskInfo)         ← 返回 Sink.asFlux() 作为 SSE 流
 *                  │
 *                  │ .doOnSubscribe → activateGeneration(taskInfo)
 *                  │      ├─ startLeaseRenewal(taskInfo)        ← 定时续约
 *                  │      └─ buildConversationExecution(taskInfo).subscribe()
 *                  │           ├─ emit thinking("正在分析问题上下文")
 *                  │           ├─ prepareExecutionPlan(taskInfo)  ← ChatPreparationOrchestrator
 *                  │           ├─ executorRegistry.get(mode)      ← 按 ExecutionMode 选执行器
 *                  │           └─ executor.execute(taskInfo)      ← 具体执行器流式输出
 *                  │
 *                  │ .doOnNext  → emitModelChunk(taskInfo, chunk) ← 每条文本推入 Sink
 *                  │ .doOnError → finishWithFailure(taskInfo, error)
 *                  │ .doOnComplete → finishSuccessfully(taskInfo)
 *                  │
 *                  │ .doOnCancel → stopTask(taskInfo, "客户端已取消请求")
 * </pre>
 *
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层 - 流式聊天核心编排协调器
 * @author: wangpeng
 **/

@Slf4j
@AllArgsConstructor
@Service
public class BusinessChatService {

    private static final ZoneId CHAT_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final String CHAT_RUNNING_LEASE_PREFIX = "chat:running:";
    private static final Duration CHAT_RUNNING_LEASE_TTL = Duration.ofSeconds(30);
    private static final Duration CHAT_RUNNING_LEASE_RENEW_INTERVAL = Duration.ofSeconds(10);

    private final ReactAgent businessChatReactAgent;
    private final ChatCheckpointManager checkpointManager;
    private final ChatAgentProperties chatAgentProperties;
    private final ConversationArchiveStore conversationArchiveStore;
    private final ChatRuntimeRegistry chatRuntimeRegistry;
    private final RecommendationService recommendationService;
    private final StreamEventWriter streamEventWriter;
    private final RedisLeaseManager redisLeaseManager;
    private final ChatPreparationOrchestrator chatPreparationOrchestrator;
    private final ConversationExecutorRegistry conversationExecutorRegistry;
    private final ConversationMemoryService conversationMemoryService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ConversationTraceStageStore conversationTraceStageStore;
    private final RetrievalObserveStore retrievalObserveStore;
    private final StageBenchmarkService stageBenchmarkService;
    private final PromptTemplateService promptTemplateService;

    /**
     * 调用链路第2层入口 - 打开流式会话
     * <p>
     * 使用 {@link Flux#defer} 包装，确保每次客户端订阅时才真正执行后续逻辑。
     * 这是 Reactor 的冷发布（Cold Publisher）模式：每个订阅者会触发独立的执行链路。
     * <p>
     * <b>为什么用 defer？</b>
     * SSE 连接建立时 Spring WebFlux 才会订阅这个 Flux。如果不用 defer，
     * 参数校验、租约抢占等逻辑会在请求线程（非订阅线程）中提前执行，导致异常传播不正确。
     *
     * @param request 聊天请求参数
     * @return 延迟执行的 SSE 事件流
     */
    public Flux<String> openConversationStream(ChatRequestDto request) {
        // 延迟到订阅时执行，进入 openDeferredConversationStream
        return Flux.defer(() -> openDeferredConversationStream(request));
    }

    /**
     * 调用链路第2层核心 - 延迟执行的流式会话启动逻辑
     * <p>
     * <b>三步启动流程：</b>
     * <ol>
     *   <li><b>buildLaunchPlan</b>：解析并规范化请求参数 → {@link StreamLaunchPlan}
     *       <ul>
     *         <li>规范化 question（trim、非空校验）</li>
     *         <li>规范化 conversationId（传了就用，没传就生成 UUID）</li>
     *         <li>解析 chatMode 枚举 + 校验 selectedDocumentId 合法性</li>
     *         <li>构建 Redis 租约 Key</li>
     *         <li>生成当前日期（亚洲/上海时区，含中文星期）</li>
     *       </ul>
     *   </li>
     *   <li><b>claimConversationLease</b>：通过 Redis 获取分布式租约
     *       <ul>
     *         <li>防止同一 conversationId 被并发重复执行</li>
     *         <li>租约 TTL 30 秒，后台每 10 秒续期（见 startLeaseRenewal）</li>
     *         <li>获取失败 → 返回 error 事件 "该会话当前正在执行中"</li>
     *       </ul>
     *   </li>
     *   <li><b>bootstrapConversation</b>：引导启动会话（见该方法的详细注释）</li>
     * </ol>
     * <p>
     * <b>异常处理：</b>
     * 捕获所有 RuntimeException，安全释放已获取的租约，返回 error SSE 事件。
     *
     * @param request 聊天请求参数
     * @return SSE 事件流 Flux<String>
     */
    private Flux<String> openDeferredConversationStream(ChatRequestDto request) {

        log.info("======request内容：{}", JSON.toJSONString(request));
        StreamLaunchPlan launchPlan = null;
        boolean leaseClaimed = false;
        try {
            // ─── 步骤 1/3：构建启动计划，参数校验 + 规范化 ───
            launchPlan = buildLaunchPlan(request);

            // ─── 步骤 2/3：获取 Redis 分布式租约，防止同一会话并发执行 ───
            leaseClaimed = claimConversationLease(launchPlan);
            if (!leaseClaimed) {
                // 租约获取失败 → 返回友好拒绝信息（SSE error 事件）
                return rejectionFlux("该会话当前正在执行中，请稍后再试", launchPlan.getConversationId(), null);
            }

            // ─── 步骤 3/3：启动会话引导（DB 记录、TaskInfo 创建、Sink 绑定）───
            BootstrapResult bootstrapResult = bootstrapConversation(launchPlan);
            if (StrUtil.isNotBlank(bootstrapResult.getRejectionMessage())) {
                // 引导阶段被拒绝（如运行时注册表冲突）
                return rejectionFlux(bootstrapResult.getRejectionMessage(), launchPlan.getConversationId(), null);
            }
            // 正常路径：返回绑定了 SSE Sink 的 Flux
            return bootstrapResult.getOutbound();
        }
        catch (RuntimeException exception) {
            log.error("会话启动失败, conversationId={}, question={}",
                launchPlan == null ? "" : launchPlan.getConversationId(),
                request.getQuestion(),
                exception);
            // 异常时安全释放已获取的租约
            if (leaseClaimed && launchPlan != null) {
                releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
            }
            // 返回错误事件给客户端
            return rejectionFlux(
                buildErrorMessage(exception),
                launchPlan == null ? null : launchPlan.getConversationId(),
                null
            );
        }
    }

    /**
     * 调用链路第2.3步 - 会话启动引导
     * <p>
     * <b>职责：</b>完成从"请求到达"到"SSE 流就绪"的所有准备工作。
     * <p>
     * <b>三步引导流程：</b>
     * <ol>
     *   <li><b>archiveStore.startExchange</b>：在数据库创建本轮交换记录（question + chatMode + document 信息）
     *       返回 {@link ConversationExchangeView}，含 exchangeId。</li>
     *   <li><b>createTaskInfo</b>：构建完整的运行时上下文对象 {@link TaskInfo}。关键字段：
     *       <ul>
     *         <li>{@code sink}：Sinks.Many.unicast().onBackpressureBuffer() — 内存中的 SSE 事件管道</li>
     *         <li>{@code runnableConfig}：Alibaba Cloud AI 的图配置，threadId=conversationId；Context 中注入 sink、metadata、thinkingSteps、references 等</li>
     *         <li>{@code traceRecorder}：对话追踪记录器，记录每个阶段的耗时和状态</li>
     *         <li>{@code eventMetadata}：包含 conversationId + exchangeId，每个 SSE 事件都会带上</li>
     *       </ul>
     *   </li>
     *   <li><b>runtimeRegistry.register</b>：将 taskInfo 注册到运行时注册表（按 conversationId 索引）
     *       如果已有运行中的任务 → 拒绝并释放租约。</li>
     *   <li><b>bindClientChannel</b>：将 sink.asFlux() 包装为 SSE 流，绑定 doOnSubscribe/doOnCancel 生命周期。</li>
     * </ol>
     * <p>
     * <b>异常处理：</b>任何步骤失败都会安全释放租约、标记 exchange 为 FAILED。
     *
     * @param launchPlan 启动参数（question, conversationId, chatMode...）
     * @return BootstrapResult，成功时 outbound 是绑定的 SSE Flux，失败时 rejectionMessage 非空
     */
    private BootstrapResult bootstrapConversation(StreamLaunchPlan launchPlan) {

        ConversationExchangeView exchangeView = null;
        try {
            // ── ① DB：创建本轮 exchange 占位记录（状态=RUNNING，答案全空）──
            // startExchange 内部做了两件事：
            //   a) upsertDialogue —— 维护 dialogue 表（新建或更新会话元信息）
            //   b) INSERT exchange —— 占位一条 RUNNING 状态的空 exchange
            exchangeView = conversationArchiveStore.startExchange(
                launchPlan.getConversationId(),
                launchPlan.getQuestion(),
                launchPlan.getChatMode(),
                launchPlan.getSelectedDocumentId(),
                launchPlan.getSelectedDocumentName()
            );

            // ── ② 构建 TaskInfo：把所有运行时对象打包成一个上下文 ──
            // Sink（内存管道）+ RunnableConfig（图配置）+ TraceRecorder（追踪器）+ ...
            TaskInfo taskInfo = createTaskInfo(launchPlan, exchangeView);

            // ── ③ 注册到运行时表（内存 ConcurrentHashMap）──
            // register 用 putIfAbsent，如果 conversationId 已存在则返回 false
            // 这是第二道并发防线（第一道是 Redis 租约）
            if (!chatRuntimeRegistry.register(taskInfo)) {
                // 注册失败 → 说明同一 conversationId 已经有任务在跑
                // 把刚才创建的 exchange 标记为 FAILED，释放租约
                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), "该会话当前正在执行中，请稍后再试");
                releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
                return BootstrapResult.rejected("该会话当前正在执行中，请稍后再试");
            }

            // ── ④ 绑定客户端通道：Sink → Flux → SSE ──
            // 返回的 Flux 已经绑定了 doOnSubscribe（激活执行）和 doOnCancel（停止任务）
            return BootstrapResult.ready(bindClientChannel(taskInfo));
        }
        catch (RuntimeException exception) {
            // 任何步骤异常 → 安全释放租约，标记 exchange 为 FAILED
            releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
            if (exchangeView != null) {
                // 只有 startExchange 成功了才需要 fail，否则 DB 里还没记录
                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), buildErrorMessage(exception));
            }
            return BootstrapResult.rejected(buildErrorMessage(exception));
        }
    }

    /**
     * 调用链路第2.3.2步 - 构建运行时任务上下文 TaskInfo
     * <p>
     * <b>核心构建逻辑：</b>
     * <ol>
     *   <li>创建 {@code Sinks.Many<String>} — 内存管道，所有 SSE 事件通过它推送到客户端</li>
     *   <li>构建 {@code RunnableConfig} — 图的执行配置，在 Context 中注入：
     *       <ul>
     *         <li>EVENT_SINK: Sink 引用（执行器产生的文本块通过它推送）</li>
     *         <li>EVENT_METADATA: 事件元数据（conversationId, exchangeId）</li>
     *         <li>THINKING_STEPS: 线程安全的思考步骤列表</li>
     *         <li>REFERENCES: 线程安全的引用列表</li>
     *         <li>USED_TOOLS: 并发安全的工具名称集合</li>
     *         <li>TRACE_ID: 本次执行的唯一追踪 ID</li>
     *         <li>QUESTION, CHAT_MODE, CURRENT_DATE 等上下文信息</li>
     *         <li>DEBUG_TRACE: 调试追踪对象（包含检索备注、使用通道等）</li>
     *       </ul>
     *   </li>
     *   <li>创建 {@code ConversationTraceRecorder} — 分阶段记录对话执行的追踪信息</li>
     *   <li>创建 {@code StreamEventMetadata} — SSE 事件的元数据载体</li>
     * </ol>
     * <p>
     * <b>TaskInfo 的关键字段：</b>
     * <ul>
     *   <li>sink: 通过它推送每条 SSE 事件到客户端</li>
     *   <li>runnableConfig: 传递给 ReactAgent/Alibaba Cloud AI Graph 的配置</li>
     *   <li>answerBuffer: 并发不安全的 StringBuffer，累积完整回答文本</li>
     *   <li>finalized: AtomicBoolean，保证收尾逻辑只执行一次</li>
     *   <li>firstResponseTimeMs: 首字 Token 延迟计时</li>
     *   <li>leaseKey/leaseOwnerToken: 用于安全释放 Redis 租约</li>
     * </ul>
     */
    private TaskInfo createTaskInfo(StreamLaunchPlan launchPlan, ConversationExchangeView exchangeView) {

        // ═══════════════════════════════════════════════════════════════
        // 1. 创建 SSE 内存管道 —— 所有流式数据通过它推送给客户端
        // ═══════════════════════════════════════════════════════════════
        // Sinks：Reactor 的"水槽"概念，主动往里塞数据的入口
        // .many()：可以多次 emit
        // .unicast()：只允许 1 个订阅者（一个 SSE 连接 = 一个订阅者）
        // .onBackpressureBuffer()：客户端网络慢时，数据在内存排队等，保证不丢字
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // ═══════════════════════════════════════════════════════════════
        // 2. 构建 Alibaba Cloud AI Graph 的执行配置
        // ═══════════════════════════════════════════════════════════════
        // threadId = conversationId：图框架用它管理对话的 checkpoint 状态
        // buildSessionConfig 只是 new RunnableConfig.builder().threadId(conversationId).build()
        RunnableConfig runnableConfig = buildSessionConfig(launchPlan.getConversationId());

        // ═══════════════════════════════════════════════════════════════
        // 3. 创建线程安全的共享集合
        // ═══════════════════════════════════════════════════════════════
        // 执行器内部是多线程的（检索线程、LLM 回调线程等），
        // 这些列表/集合会被并发写入，所以必须用同步包装
        List<String> thinkingSteps = Collections.synchronizedList(new ArrayList<>());  // 思考步骤
        List<SearchReference> references = Collections.synchronizedList(new ArrayList<>());  // 检索引用
        Set<String> usedTools = ConcurrentHashMap.newKeySet();  // 使用的工具名（CHM.keySet 代替无 ConcurrentHashSet）

        // ═══════════════════════════════════════════════════════════════
        // 4. 创建追踪 ID
        // ═══════════════════════════════════════════════════════════════
        // traceId 是一次执行的唯一标识，会贯穿 trace_stage 表和 retrieval_result 表
        // 去掉 UUID 的横线，让 ID 更短且不含特殊字符
        String traceId = UUID.randomUUID().toString().replace("-", "");

        // ═══════════════════════════════════════════════════════════════
        // 5. 创建对话追踪记录器
        // ═══════════════════════════════════════════════════════════════
        // traceRecorder 分阶段记录执行的耗时和状态：
        //   startStage("MEMORY", ...) → completeStage(...)  记录一个阶段
        // 最终数据写入 super_agent_chat_exchange_trace_stage 表
        ConversationTraceRecorder traceRecorder = new ConversationTraceRecorder(
            conversationTraceStageStore,   // 阶段轨迹表的存储接口
            retrievalObserveStore,         // 检索结果表的存储接口
            launchPlan.getConversationId(),
            exchangeView.getExchangeId(),
            traceId
        );

        // ═══════════════════════════════════════════════════════════════
        // 6. 创建 SSE 事件元数据
        // ═══════════════════════════════════════════════════════════════
        // 每个 SSE 事件的 JSON 里都会自动带上 conversationId 和 exchangeId，
        // 客户端用这两个字段区分"哪个会话的哪一轮"
        StreamEventMetadata eventMetadata = new StreamEventMetadata(
            launchPlan.getConversationId(),
            exchangeView.getExchangeId()
        );

        // ═══════════════════════════════════════════════════════════════
        // 7. 将共享对象注入到图的 Context（关键步骤！）
        // ═══════════════════════════════════════════════════════════════
        // 注入后，执行器链路的任何环节都可以通过 config.context().get(key) 获取这些对象
        // Context 本质是一个 Map<String, Object>，图框架会把它透传到每个节点

        // sink：执行器产生的文本通过它推送到 SSE 管道
        runnableConfig.context().put(ChatContextKeys.EVENT_SINK, sink);
        // eventMetadata：每条事件都带 conversationId + exchangeId
        runnableConfig.context().put(ChatContextKeys.EVENT_METADATA, eventMetadata);
        // thinkingSteps：思考过程，最终落库到 exchange.thinkingSteps
        runnableConfig.context().put(ChatContextKeys.THINKING_STEPS, thinkingSteps);
        // references：检索引用，最终在 finishSuccessfully 中补发给客户端
        runnableConfig.context().put(ChatContextKeys.REFERENCES, references);
        // usedTools：使用的工具名集合
        runnableConfig.context().put(ChatContextKeys.USED_TOOLS, usedTools);
        // traceId：唯一追踪标识
        runnableConfig.context().put(ChatContextKeys.TRACE_ID, traceId);

        // ═══════════════════════════════════════════════════════════════
        // 8. 注入业务上下文信息
        // ═══════════════════════════════════════════════════════════════
        // 这些信息供执行器内部使用（如构建 LLM prompt 时的日期锚定）
        runnableConfig.context().put(ChatContextKeys.QUESTION, launchPlan.getQuestion());
        runnableConfig.context().put(ChatContextKeys.CHAT_MODE, launchPlan.getChatMode().name());
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE, launchPlan.getCurrentDate().toString());
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE_TEXT, launchPlan.getCurrentDateText());

        // ═══════════════════════════════════════════════════════════════
        // 9. 注入文档上下文（仅在 DOCUMENT 模式下有值）
        // ═══════════════════════════════════════════════════════════════
        // putContextIfNotNull：value 为 null 时不放入（避免 Context 中有 null key 对应的 null value）
        // putContextIfNotBlank：value 为空字符串时不放入
        putContextIfNotNull(runnableConfig, ChatContextKeys.SELECTED_DOCUMENT_ID, launchPlan.getSelectedDocumentId());
        putContextIfNotBlank(runnableConfig, ChatContextKeys.SELECTED_DOCUMENT_NAME, launchPlan.getSelectedDocumentName());
        putContextIfNotNull(runnableConfig, ChatContextKeys.SELECTED_TASK_ID, launchPlan.getSelectedTaskId());

        // ═══════════════════════════════════════════════════════════════
        // 10. 初始化调试追踪对象
        // ═══════════════════════════════════════════════════════════════
        // 初始化为空壳（只有空 note 和 channel 列表）。
        // 之后在 prepareExecutionPlan 中会用 executionPlan 重新初始化，
        // 填入 rewriteQuestion、navigationDecision、retrievalNotes 等完整调试信息
        ChatDebugTrace debugTrace = initializeDebugTrace(null);
        runnableConfig.context().put(ChatContextKeys.DEBUG_TRACE, debugTrace);

        // ═══════════════════════════════════════════════════════════════
        // 11. 组装 TaskInfo —— 整个调用链路的共享上下文
        // ═══════════════════════════════════════════════════════════════
        // TaskInfo 是 immutable 风格（字段 final），通过全参构造器一次性创建
        return new TaskInfo(
            launchPlan.getConversationId(),    // 会话 ID
            exchangeView.getExchangeId(),      // 本轮交换 ID（startExchange 返回的）
            launchPlan.getQuestion(),          // 用户问题
            launchPlan.getChatMode(),          // 聊天模式
            traceId,                           // 本次执行的唯一追踪 ID
            launchPlan.getSelectedDocumentId(),    // 文档 ID（可能 null）
            launchPlan.getSelectedDocumentName(),  // 文档名（可能空）
            launchPlan.getSelectedTaskId(),        // 索引任务 ID（可能 null）
            launchPlan.getCurrentDate(),           // 当前日期 LocalDate
            launchPlan.getCurrentDateText(),       // 当前日期文本 "2026-06-25（星期四）"
            null,                              // executionPlan：稍后在 prepareExecutionPlan 中设置
            debugTrace,                        // 调试追踪对象
            runnableConfig,                    // 图执行配置（Context 中已注入所有共享对象）
            traceRecorder,                     // 阶段追踪记录器
            sink,                              // ★ 核心：SSE 数据管道
            eventMetadata,                     // 事件元数据
            launchPlan.getLeaseKey(),          // Redis 租约 Key（cleanup 时释放用）
            launchPlan.getLeaseOwnerToken(),   // Redis 租约 Token（防误删他人锁）
            thinkingSteps,                     // 线程安全的思考步骤列表
            references,                        // 线程安全的引用列表
            usedTools,                         // 并发安全的工具集合
            System.currentTimeMillis()         // startTime：首字延迟和总耗时的计时起点
        );
    }

    /**
     * 调用链路第2.3.4步 - 将内存 Sink 绑定为 SSE 流
     * <p>
     * <b>核心机制：</b>
     * 将 {@code Sinks.Many<String>} 转为 {@code Flux<String>}，并在其生命周期钩子上绑定业务逻辑。
     * <p>
     * <b>生命周期绑定：</b>
     * <ul>
     *   <li><b>doOnSubscribe</b>（客户端连接成功）→ {@link #activateGeneration(TaskInfo)}
     *       <ul>
     *         <li>启动 Redis 租约续期定时器</li>
     *         <li>构建执行管道（prepareExecutionPlan → Executor.execute）并 subscribe</li>
     *       </ul>
     *   </li>
     *   <li><b>doOnCancel</b>（客户端断开连接/主动取消）→ {@link #stopTask(TaskInfo, String)}
     *       <ul>
     *         <li>中断 ReactAgent</li>
     *         <li>dispose 订阅</li>
     *         <li>发送停止状态 → complete sink → DB 落库 → 清理资源</li>
     *       </ul>
     *   </li>
     * </ul>
     * <p>
     * <b>数据流方向：</b>
     * Sink (executor 写入) → asFlux() → SSE 连接 → 客户端浏览器
     * <p>
     * 这里是冷发布和热发布的桥梁：Sink 是热源（主动推送），Flux 是冷源（按需消费）。
     *
     * @param taskInfo 运行时任务上下文（包含 sink）
     * @return 绑定了生命周期的 SSE 事件流
     */
    private Flux<String> bindClientChannel(TaskInfo taskInfo) {

        return taskInfo.sink().asFlux()
            // 客户端成功订阅 → 启动实际执行
            .doOnSubscribe(ignored -> activateGeneration(taskInfo))
            // 客户端断开连接/取消请求 → 停止生成
            .doOnCancel(() -> stopTask(taskInfo, "客户端已取消请求"));
    }

    /**
     * 调用链路第3层 - 激活生成执行
     * <p>
     * 客户端 SSE 连接建立后，此方法被 doOnSubscribe 触发。
     * <p>
     * <b>两步启动：</b>
     * <ol>
     *   <li><b>startLeaseRenewal</b>：启动定时器，每 10 秒续约一次 Redis 租约（TTL 30 秒）
     *       如果续约失败（租约被别人抢占），则自动 stopTask。</li>
     *   <li><b>buildConversationExecution.subscribe()</b>：构建并订阅执行管道
     *       <ul>
     *         <li>prepareExecutionPlan → ChatPreparationOrchestrator（第4层）</li>
     *         <li>executorRegistry.get(mode) → 按计划选择执行器</li>
     *         <li>executor.execute(taskInfo) → ReactAgent/RAG/Clarification/Graph</li>
     *       </ul>
     *   </li>
     * </ol>
     * <p>
     * <b>防御性检查：</b>每次异步操作后都检查 finalized 标志，防止在启动过程中任务已被取消。
     *
     * @param taskInfo 运行时任务上下文
     */
    private void activateGeneration(TaskInfo taskInfo) {
        try {
            // 防御：如果已被取消，不再启动
            if (taskInfo.finalized().get()) {
                return;
            }

            // ─── 启动 Redis 租约续期定时器（每 10 秒续约一次，防止长任务被误杀）───
            Disposable leaseRenewalDisposable = startLeaseRenewal(taskInfo);
            taskInfo.setLeaseRenewalDisposable(leaseRenewalDisposable);
            // 再次检查：续约启动期间任务可能被取消
            if (taskInfo.finalized().get() && !leaseRenewalDisposable.isDisposed()) {
                leaseRenewalDisposable.dispose();
                return;
            }

            // ─── 构建并订阅执行管道（核心链路由此展开）───
            Disposable disposable = buildConversationExecution(taskInfo).subscribe();
            // 保存订阅引用，用于后续 stopTask 时 dispose
            taskInfo.setDisposable(disposable);
            // 再次检查：订阅期间任务可能被取消
            if (taskInfo.finalized().get() && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }
        catch (RuntimeException exception) {
            // 启动阶段异常 → 直接走失败收尾
            finishWithFailure(taskInfo, exception);
        }
    }

    /**
     * 调用链路第4层 - 构建执行管道
     * <p>
     * <b>这是整个流式对话的核心管道，采用 Reactive Stream 响应式编程模式：</b>
     * <p>
     * <b>管道流程：</b>
     * <pre>
     * Flux.defer           ← 延迟到订阅时执行
     *   │
     *   ├─ emit thinking("正在分析问题上下文")   ← 向客户端发送"思考中"状态
     *   │
     *   ├─ prepareExecutionPlan(taskInfo)       ← 阻塞调用，在 boundedElastic 线程池执行
     *   │   └─ ChatPreparationOrchestrator.prepare(taskInfo)  ← 第5层：执行计划编排
     *   │       ├─ summarizeHistory               ← 装载会话记忆
     *   │       ├─ 按 chatMode 分派路由            ← OPEN_CHAT / AUTO_DOCUMENT / DOCUMENT
     *   │       ├─ queryRewriteService.rewrite    ← 问题改写（非 OPEN_CHAT 模式）
     *   │       ├─ documentQuestionRouter.route   ← 文档路由判断
     *   │       └─ 返回 ConversationExecutionPlan(含 ExecutionMode)
     *   │
     *   ├─ executorRegistry.get(plan.mode)       ← 按 ExecutionMode 选择执行器
     *   │   ├─ REACT_AGENT     → ReactAgentExecutor
     *   │   ├─ RETRIEVAL       → RagChatExecutor
     *   │   ├─ CLARIFICATION   → ClarificationExecutor
     *   │   ├─ GRAPH_ONLY      → GraphOnlyExecutor
     *   │   └─ GRAPH_THEN_EVIDENCE → GraphThenEvidenceExecutor
     *   │
     *   └─ executor.execute(taskInfo)            ← 执行器流式输出文本块
     *       ├─ ReactAgentExecutor: reactAgent.stream(agentQuestion, config)
     *       ├─ RagChatExecutor: ragRetrievalEngine.retrieve → promptAssembly → chatModel.streamText
     *       ├─ ClarificationExecutor: 直接返回澄清问题文本
     *       ├─ GraphOnlyExecutor: structureGraphQueryEngine → graphAnswerRenderer
     *       └─ GraphThenEvidenceExecutor: structureGraphQueryEngine → 证据校验 → graphAnswerRenderer
     * </pre>
     * <p>
     * <b>Reactor 线程模型：</b>
     * <ul>
     *   <li><b>subscribeOn(boundedElastic)</b>：plan 准备（阻塞 I/O）在弹性线程池执行</li>
     *   <li><b>publishOn(boundedElastic)</b>：doOnNext/doOnError/doOnComplete 回调在弹性线程池执行</li>
     *   <li>Executor 内部的 ReactAgent.stream 是异步非阻塞的，不占用额外线程</li>
     * </ul>
     * <p>
     * <b>三个收尾出口：</b>
     * <ul>
     *   <li>doOnNext → emitModelChunk：每条文本块实时推送到 Sink → 客户端</li>
     *   <li>doOnError → finishWithFailure：异常 → 发送错误事件 + DB 落库 + 清理</li>
     *   <li>doOnComplete → finishSuccessfully：正常完成 → 推荐追问 + 引用 + DB 落库 + 清理</li>
     * </ul>
     *
     * @param taskInfo 运行时任务上下文
     * @return 执行器的文本块流，已绑定生命周期回调
     */
    private Flux<String> buildConversationExecution(TaskInfo taskInfo) {
        return Flux.defer(() -> {
                // ─── 第一步：发送"正在分析"的思考状态事件 ───
                safeEmit(taskInfo.sink(), streamEventWriter.thinking("正在分析问题上下文。", taskInfo.eventMetadata()));
                // ─── 第二步：在弹性线程池中准备执行计划（阻塞操作）───
                return Mono.fromCallable(() -> prepareExecutionPlan(taskInfo))
                    .subscribeOn(Schedulers.boundedElastic())  // 将阻塞的 prepare 放到弹性线程池
                    .flatMapMany(plan -> {
                        // ─── 第三步：按执行计划中的 ExecutionMode 选择对应的执行器 ───
                        ConversationExecutor executor = conversationExecutorRegistry.get(plan.getMode());
                        // ─── 第四步：执行器流式输出文本块 ───
                        return executor.execute(taskInfo);
                    });
            })
            // 切换后续操作到弹性线程池（避免阻塞 Netty I/O 线程）
            .publishOn(Schedulers.boundedElastic())
            // ─── 每条文本块实时推送到客户端 ───
            .doOnNext(chunk -> emitModelChunk(taskInfo, chunk))
            // ─── 异常 → 失败收尾 ───
            .doOnError(error -> finishWithFailure(taskInfo, error))
            // ─── 正常完成 → 成功收尾 ───
            .doOnComplete(() -> finishSuccessfully(taskInfo));
    }

    /**
     * 调用链路第2.1步 - 构建启动计划
     * <p>
     * <b>职责：</b>解析、校验、规范化前端传来的原始请求参数，生成 {@link StreamLaunchPlan}。
     * <p>
     * <b>处理逻辑：</b>
     * <ol>
     *   <li><b>normalizeQuestion</b>：question 不能为空，trim 处理</li>
     *   <li><b>normalizeConversationId</b>：有则 trim，无则生成 UUID（去横线）</li>
     *   <li><b>parseRequiredChatMode</b>：解析 chatMode 枚举（OPEN_CHAT / DOCUMENT / AUTO_DOCUMENT）</li>
     *   <li><b>resolveSelectedDocument</b>：
     *       <ul>
     *         <li>OPEN_CHAT / AUTO_DOCUMENT 模式不能传 selectedDocumentId</li>
     *         <li>DOCUMENT 模式必须传 selectedDocumentId，且文档必须可检索</li>
     *       </ul>
     *   </li>
     *   <li><b>currentDate</b>：使用 Asia/Shanghai 时区，格式为 "2026-06-25（星期四）"</li>
     *   <li><b>leaseKey</b>：Redis 键 "chat:running:{conversationId}"</li>
     *   <li><b>leaseOwnerToken</b>：随机 UUID，用于安全释放租约（防止误删他人租约）</li>
     * </ol>
     *
     * @param request 前端请求 DTO
     * @return 规范化后的启动计划
     */
    private StreamLaunchPlan buildLaunchPlan(ChatRequestDto request) {

        String question = normalizeQuestion(request.getQuestion());

        String conversationId = normalizeConversationId(request.getConversationId());
        ChatQueryMode chatMode = parseRequiredChatMode(request.getChatMode());

        KnowledgeDocumentDescriptor selectedDocument = resolveSelectedDocument(chatMode, request.getSelectedDocumentId());

        LocalDate currentDate = LocalDate.now(CHAT_ZONE_ID);
        String currentDateText = formatCurrentDate(currentDate);
        return new StreamLaunchPlan(
            question,
            conversationId,
            chatMode,
            selectedDocument == null ? null : selectedDocument.getDocumentId(),
            selectedDocument == null ? "" : selectedDocument.getDocumentName(),
            selectedDocument == null ? null : selectedDocument.getLastIndexTaskId(),

            buildChatLeaseKey(conversationId),

            UUID.randomUUID().toString(),
            currentDate,
            currentDateText
        );
    }

    private boolean claimConversationLease(StreamLaunchPlan launchPlan) {

        return redisLeaseManager.acquire(
            launchPlan.getLeaseKey(),
            launchPlan.getLeaseOwnerToken(),
            CHAT_RUNNING_LEASE_TTL
        );
    }

/**
 * 启动失败时，把刚才 startExchange 创建的 exchange 标记为 FAILED
 * <p>
 * 与正常 completeExchange 的区别：
 * <ul>
 *   <li>answer / thinkingSteps / references 全传空 —— 启动阶段根本没执行到 LLM</li>
 *   <li>status = FAILED —— 标记失败状态</li>
 *   <li>errorMessage 记录失败原因（如"该会话当前正在执行中"）</li>
 * </ul>
 * <p>
 * 同时会把 dialogue 的状态从 RUNNING 恢复为 IDLE。
 */
private void failBootstrappedExchange(String conversationId, long exchangeId, String errorMessage) {
    // answer、thinking、references 全空，因为还没走到 LLM 就失败了
    conversationArchiveStore.completeExchange(
        conversationId,
        exchangeId,
        "",                        // 无回答
        List.of(),                 // 无思考步骤
        List.of(),                 // 无引用
        List.of(),                 // 无推荐
        List.of(),                 // 无用工具
        null,                      // 无调试信息
        ChatTurnStatus.FAILED,     // 失败
        errorMessage,              // 失败原因
        null,                      // 无首字延迟
        null                       // 无总耗时
    );
}

    private Flux<String> rejectionFlux(String message) {
        return rejectionFlux(message, null, null);
    }

    private Flux<String> rejectionFlux(String message, String conversationId, Long exchangeId) {

        return Flux.just(streamEventWriter.error(message, new StreamEventMetadata(conversationId, exchangeId)));
    }

    public ConversationStopVo stopConversation(String conversationId) {
        return stopConversation(conversationId, "用户已停止生成");
    }

    public ConversationStopVo stopConversation(String conversationId, String reason) {
        Optional<TaskInfo> taskInfoOptional = chatRuntimeRegistry.get(conversationId);
        if (taskInfoOptional.isEmpty()) {
            return new ConversationStopVo(conversationId, false, "没有找到正在执行的会话");
        }
        return stopTask(taskInfoOptional.get(), reason);
    }

    /**
     * 调用链路第6层 - 停止正在执行的任务（取消路径）
     * <p>
     * <b>触发场景：</b>
     * <ul>
     *   <li>客户端断开 SSE 连接（doOnCancel 触发）</li>
     *   <li>用户主动调用 /api/chat/session/stop 接口</li>
     *   <li>Redis 租约续期失败（被其他节点抢占）</li>
     *   <li>会话被重置</li>
     * </ul>
     * <p>
     * <b>停止流程：</b>
     * <ol>
     *   <li><b>CAS 防重入</b>：finalized.compareAndSet(false, true)</li>
     *   <li><b>验证任务身份</b>：确认运行时注册表中仍是当前 taskInfo（未被新任务接管）</li>
     *   <li><b>中断 ReactAgent</b>：businessChatReactAgent.interrupt(runnableConfig) — 向 Agent 线程发中断信号</li>
     *   <li><b>Dispose 订阅</b>：取消 Reactor 订阅链，停止上游数据推送</li>
     *   <li><b>发送停止状态</b>：safeEmit(sink, status("⏹ " + reason)) — 通知客户端</li>
     *   <li><b>关闭 Sink</b>：safeComplete(sink) — 正常关闭 SSE 流</li>
     *   <li><b>DB 落库</b>：状态=STOPPED，保存已有的部分回答</li>
     *   <li><b>清理</b>：释放租约、注销运行时注册</li>
     * </ol>
     *
     * @param taskInfo 要停止的任务
     * @param reason   停止原因（如"客户端已取消请求"、"用户已停止生成"）
     * @return 停止结果 VO
     */
    private ConversationStopVo stopTask(TaskInfo taskInfo, String reason) {

        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已经结束");
        }

        Optional<TaskInfo> currentTask = chatRuntimeRegistry.get(taskInfo.conversationId());
        if (currentTask.isPresent() && currentTask.get() != taskInfo) {

            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已由新的执行接管");
        }

        try {

            businessChatReactAgent.interrupt(taskInfo.runnableConfig());
        }
        catch (RuntimeException exception) {
            log.debug("中断 ReactAgent 时出现异常，继续释放资源", exception);
        }

        Disposable disposable = taskInfo.disposable();
        if (disposable != null && !disposable.isDisposed()) {

            disposable.dispose();
        }

        String responseMessage = "已停止会话生成";
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾停止中的会话。",
                null
            );
        try {
            safeEmit(taskInfo.sink(), streamEventWriter.status("⏹ " + reason, taskInfo.eventMetadata()));
        }
        catch (RuntimeException exception) {
            log.warn("发送停止事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            responseMessage = "会话已停止，停止事件发送失败";
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭停止中的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                refreshDebugTraceRuntimeStats(taskInfo);
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    taskInfo.answerBuffer().toString(),
                    snapshotStringList(taskInfo.thinkingSteps()),
                    deduplicateReferences(snapshotReferenceList(taskInfo.references())),
                    List.of(),
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.STOPPED,
                    reason,
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(finalizeStage, "会话已按停止状态收尾。", Map.of(
                        "finalStatus", ChatTurnStatus.STOPPED.name(),
                        "reason", reason,
                        "answerLength", taskInfo.answerBuffer().length()
                    ));
                }
            }
            catch (RuntimeException exception) {
                log.error("停止会话落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                responseMessage = "会话已停止，收尾落库失败";
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "停止态收尾失败。", exception.getMessage(), null);
                }
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
        return new ConversationStopVo(taskInfo.conversationId(), true, responseMessage);
    }

    public ConversationSessionView getSession(String conversationId) {
        ConversationArchiveStore.ConversationArchiveRecord archiveRecord = conversationArchiveStore.getSessionRecord(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        return overlayRuntimeSnapshot(toSessionView(archiveRecord, true, true));
    }

    public ConversationExchangeDetailView getExchangeDetail(String conversationId, String exchangeId) {
        long resolvedExchangeId = parseRequiredLong(exchangeId, "exchangeId");
        ConversationSessionView sessionView = getSession(conversationId);
        ConversationExchangeView exchangeView = sessionView.getExchanges().stream()
            .filter(item -> item != null && item.getExchangeId() == resolvedExchangeId)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("轮次不存在: " + exchangeId));
        return new ConversationExchangeDetailView(
            conversationId,
            exchangeView,
            conversationTraceStageStore.listStageViews(conversationId, resolvedExchangeId)
        );
    }

    public ConversationSessionListVo listSessions(ConversationSessionListQueryDto dto) {
        int pageNo = parsePositiveInt(dto == null ? null : dto.getPageNo(), 1);
        int pageSize = parsePositiveInt(dto == null ? null : dto.getPageSize(), 20);
        String keyword = normalizeOptionalText(dto == null ? null : dto.getKeyword());
        ChatQueryMode chatMode = parseOptionalChatMode(dto == null ? null : dto.getChatMode());
        ChatTurnStatus turnStatus = parseOptionalTurnStatus(dto == null ? null : dto.getTurnStatus());

        ConversationArchiveStore.ConversationArchivePage archivePage = conversationArchiveStore.listSessionRecordPage(
            pageNo,
            pageSize,
            keyword,
            chatMode,
            turnStatus
        );
        List<ConversationSessionView> sessions = archivePage.records()
            .stream()
            .map(record -> toSessionView(record, false, false))
            .toList();

        long totalPages = archivePage.totalSize() <= 0
            ? 0
            : (archivePage.totalSize() + archivePage.pageSize() - 1) / archivePage.pageSize();
        return new ConversationSessionListVo(
            archivePage.pageNo(),
            archivePage.pageSize(),
            archivePage.totalSize(),
            totalPages,
            sessions
        );
    }

    public List<KnowledgeDocumentOptionView> listKnowledgeDocumentOptions() {
        return documentKnowledgeService.listRetrievableDocuments().stream()
            .map(this::toKnowledgeDocumentOptionView)
            .toList();
    }

    public ConversationMemorySummaryView rebuildConversationSummary(String conversationId) {
        return conversationMemoryService.rebuildConversationSummary(conversationId);
    }

    public ConversationResetVo resetConversation(String conversationId) {

        ConversationStopVo stopResult = stopConversation(conversationId, "会话被重置");

        ConversationArchiveStore.ConversationRemovalResult removalResult = conversationArchiveStore.deleteSession(conversationId);

        conversationMemoryService.deleteConversationSummary(conversationId);
        conversationTraceStageStore.deleteStages(conversationId);
        retrievalObserveStore.deleteByConversation(conversationId);
        int removedCheckpointCount = checkpointManager.clearThread(conversationId);
        return new ConversationResetVo(
            conversationId,
            stopResult.isStopped(),
            removalResult.removedDialogueCount(),
            removalResult.removedExchangeCount(),
            removedCheckpointCount,
            "会话已重置"
        );
    }

    /**
     * 调用链路第6层 - 实时推送文本块到 SSE 管道
     * <p>
     * <b>每个文本块的旅程：</b>
     * Executor 产生 chunk → emitModelChunk → answerBuffer 累积 → StreamEventWriter.text → Sink.emitNext → SSE → 客户端
     * <p>
     * <b>首字延迟记录：</b>当第一条文本通过时，用 CAS 记录 firstResponseTimeMs（当前时间 - startTime），
     * 用于后续的性能指标展示。
     *
     * @param taskInfo 运行时任务上下文（包含 sink 和 answerBuffer）
     * @param chunk   执行器产生的一段文本
     */
    private void emitModelChunk(TaskInfo taskInfo, String chunk) {
        // 累积到完整回答缓冲区（用于最终 DB 落库）
        taskInfo.answerBuffer().append(chunk);
        // 记录首字响应时间（毫秒），只记录一次
        if (taskInfo.firstResponseTimeMs().get() == 0L) {
            taskInfo.firstResponseTimeMs().compareAndSet(0L, System.currentTimeMillis() - taskInfo.startTime());
        }
        // 将文本包装为 SSE text 事件，通过 Sink 推送到客户端
        safeEmit(taskInfo.sink(), streamEventWriter.text(chunk, taskInfo.eventMetadata()));
    }

    /**
     * 调用链路第6层 - 流式生成正常完成时的收尾处理
     * <p>
     * <b>收尾步骤（按顺序执行）：</b>
     * <ol>
     *   <li><b>CAS 防重入</b>：finalized.compareAndSet(false, true) — 保证只执行一次收尾</li>
     *   <li><b>生成推荐追问</b>：
     *       <ul>
     *         <li>CLARIFICATION 模式：直接用 plan 中的 clarificationOptions</li>
     *         <li>其他模式：调用 recommendationService.generateRecommendations(question, answer, history)</li>
     *       </ul>
     *   </li>
     *   <li><b>补发 SSE 事件</b>：
     *       <ul>
     *         <li>references 事件：去重后的检索引用列表</li>
     *         <li>recommendations 事件：推荐追问列表</li>
     *       </ul>
     *   </li>
     *   <li><b>关闭 SSE 流</b>：safeComplete(sink)</li>
     *   <li><b>DB 落库</b>：archiveStore.completeExchange(...) — 保存回答、引用、推荐、状态=COMPLETED、耗时</li>
     *   <li><b>异步刷新摘要</b>：safeRefreshConversationSummary — 触发会话记忆摘要异步更新</li>
     *   <li><b>资源清理</b>：cleanup(taskInfo) — 释放租约、注销运行时注册、dispose 订阅</li>
     * </ol>
     *
     * @param taskInfo 运行时任务上下文
     */
    private void finishSuccessfully(TaskInfo taskInfo) {
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        String answer = taskInfo.answerBuffer().toString();
        List<SearchReference> uniqueReferences = deduplicateReferences(snapshotReferenceList(taskInfo.references()));
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾已完成会话。",
                null
            );
        ConversationTraceRecorder.StageHandle recommendationStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.RECOMMENDATION,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在生成推荐追问。",
                null
            );
        List<String> recommendations;
        if (taskInfo.executionPlan() != null
            && taskInfo.executionPlan().getMode() == org.javaup.ai.chatagent.rag.model.ExecutionMode.CLARIFICATION) {
            recommendations = taskInfo.executionPlan().getClarificationOptions() == null
                ? List.of()
                : new ArrayList<>(taskInfo.executionPlan().getClarificationOptions());
        }
        else {
            recommendations = recommendationService.generateRecommendations(
                taskInfo.question(),
                answer,
                historicalRecentExchanges(taskInfo),
                taskInfo.traceRecorder()
            );
        }
        if (taskInfo.traceRecorder() != null) {
            taskInfo.traceRecorder().completeStage(recommendationStage, "推荐追问生成完成。", Map.of(
                "recommendationCount", recommendations.size(),
                "recommendations", recommendations
            ));
        }

        try {
            if (!uniqueReferences.isEmpty()) {
                safeEmit(taskInfo.sink(), streamEventWriter.references(uniqueReferences, taskInfo.eventMetadata()));
            }
            if (!recommendations.isEmpty()) {
                safeEmit(taskInfo.sink(), streamEventWriter.recommendations(recommendations, taskInfo.eventMetadata()));
            }
        }
        catch (RuntimeException exception) {
            log.warn("补发引用或推荐事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭成功完成的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                refreshDebugTraceRuntimeStats(taskInfo);
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    answer,
                    snapshotStringList(taskInfo.thinkingSteps()),
                    uniqueReferences,
                    recommendations,
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.COMPLETED,
                    "",
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(finalizeStage, "会话已按完成状态收尾。", Map.of(
                        "finalStatus", ChatTurnStatus.COMPLETED.name(),
                        "referenceCount", uniqueReferences.size(),
                        "recommendationCount", recommendations.size(),
                        "answerLength", answer.length()
                    ));
                }
            }
            catch (RuntimeException exception) {
                log.error("成功会话收尾落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "完成态收尾失败。", exception.getMessage(), null);
                }
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
    }

    /**
     * 调用链路第6层 - 流式生成失败时的收尾处理
     * <p>
     * <b>与 finishSuccessfully 的差异：</b>
     * <ul>
     *   <li>不生成推荐追问</li>
     *   <li>发送 error SSE 事件（而不是 references/recommendations）</li>
     *   <li>DB 状态标记为 FAILED，保存 errorMessage</li>
     *   <li>追溯异常链：
     *       <ul>
     *         <li>WebClientResponseException → 提取 HTTP 状态码和响应体</li>
     *         <li>WebClientRequestException → 根据连接异常类型返回中文可读信息</li>
     *       </ul>
     *   </li>
     * </ul>
     * <p>
     * <b>错误信息提取逻辑</b>（buildErrorMessage）：
     * 沿异常链向上查找 WebClientResponseException（HTTP 4xx/5xx 错误响应），
     * 找到则返回 "状态码 from 方法 URL | responseBody=..." 的详细错误信息；
     * 如果找到 WebClientRequestException（连接层面错误：连接被拒、超时、DNS 失败等），
     * 则根据底层异常类型返回用户可读的中文错误信息；
     * 否则返回最外层异常的 message。
     *
     * @param taskInfo 运行时任务上下文
     * @param error   执行过程中抛出的异常
     */
    private void finishWithFailure(TaskInfo taskInfo, Throwable error) {
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        String errorMessage = buildErrorMessage(error);
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾失败会话。",
                null
            );

        log.error("会话执行失败, conversationId={}, exchangeId={}, error={}",
            taskInfo.conversationId(),
            taskInfo.exchangeId(),
            errorMessage,
            error);

        try {
            safeEmit(taskInfo.sink(), streamEventWriter.error(errorMessage, taskInfo.eventMetadata()));
        }
        catch (RuntimeException exception) {
            log.warn("发送失败事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭失败中的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                refreshDebugTraceRuntimeStats(taskInfo);
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    taskInfo.answerBuffer().toString(),
                    snapshotStringList(taskInfo.thinkingSteps()),
                    deduplicateReferences(snapshotReferenceList(taskInfo.references())),
                    List.of(),
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.FAILED,
                    errorMessage,
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(finalizeStage, "会话已按失败状态收尾。", Map.of(
                        "finalStatus", ChatTurnStatus.FAILED.name(),
                        "errorMessage", errorMessage,
                        "answerLength", taskInfo.answerBuffer().length()
                    ));
                }
            }
            catch (RuntimeException exception) {
                log.error("失败会话收尾落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "失败态收尾失败。", exception.getMessage(), null);
                }
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
    }

    private String buildErrorMessage(Throwable error) {

        Throwable current = error;
        while (current != null) {
            // ═══ 分支 A：HTTP 响应错误（4xx/5xx）═══
            // WebClientResponseException 带响应体和状态码，提取关键信息返回
            if (current instanceof WebClientResponseException responseException) {
                String responseBody = responseException.getResponseBodyAsString();
                if (StrUtil.isNotBlank(responseBody)) {
                    return responseException.getStatusCode()
                        + " from "
                        + responseException.getRequest().getMethod()
                        + " "
                        + responseException.getRequest().getURI()
                        + " | responseBody="
                        + responseBody;
                }

                return responseException.getMessage();
            }

            // ═══ 分支 B：连接层面错误（连接被拒、超时、DNS 解析失败等）═══
            // WebClientRequestException 是 WebClientResponseException 的父类，
            // 必须放在 WebClientResponseException 之后检查，确保更具体的类型优先匹配。
            if (current instanceof WebClientRequestException requestException) {
                URI uri = requestException.getUri();
                Throwable cause = requestException.getCause();
                String target = uri != null ? uri.toString() : "未知端点";

                // 根据底层连接异常类型返回可读的中文错误信息
                if (cause != null) {
                    String causeName = cause.getClass().getSimpleName();
                    return switch (causeName) {
                        case "ConnectException" ->
                            "无法连接到 AI 服务（" + target + "），请检查网络连接和 API 服务状态。";
                        case "SocketTimeoutException", "TimeoutException" ->
                            "AI 服务连接超时（" + target + "），请稍后重试。";
                        case "UnknownHostException" ->
                            "无法解析 AI 服务地址（" + target + "），请检查 DNS 配置。";
                        case "SSLHandshakeException", "SSLException" ->
                            "AI 服务 SSL 连接错误（" + target + "），请检查证书配置。";
                        case "ConnectionPoolTimeoutException" ->
                            "AI 服务连接池已满（" + target + "），请稍后重试。";
                        default ->
                            "AI 服务调用失败（" + target + "）：" + cause.getMessage();
                    };
                }

                return "AI 服务调用失败（" + target + "），请检查网络连接。";
            }

            current = current.getCause();
        }

        // ═══ 兜底：无法匹配任何已知异常类型，返回原始异常信息 ═══
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private void refreshDebugTraceRuntimeStats(TaskInfo taskInfo) {
        if (taskInfo == null || taskInfo.debugTrace() == null || taskInfo.traceRecorder() == null) {
            return;
        }
        taskInfo.debugTrace().setModelUsageTraces(taskInfo.traceRecorder().snapshotModelUsageTraces());
        org.javaup.ai.chatagent.model.debug.ChatLimitStats limitStats = taskInfo.traceRecorder().limitStats();
        limitStats.setModelCallsUsed(taskInfo.traceRecorder().snapshotModelUsageTraces().size());
        limitStats.setModelCallsRunLimit(chatAgentProperties.getMaxModelCallsPerRun());
        limitStats.setModelCallsThreadLimit(chatAgentProperties.getMaxModelCallsPerThread());
        limitStats.setToolCallsUsed(snapshotUsedTools(taskInfo.usedTools()).size());
        limitStats.setToolCallsRunLimit(chatAgentProperties.getMaxToolCallsPerRun());
        limitStats.setToolCallsThreadLimit(chatAgentProperties.getMaxToolCallsPerThread());
        taskInfo.debugTrace().setLimitStats(limitStats);
    }

    /**
     * 调用链路最后一步 - 资源清理
     * <p>
     * <b>清理顺序（保证安全释放所有资源）：</b>
     * <ol>
     *   <li><b>dispose 租约续期定时器</b>：停止 Redis 租约续约的 interval Flux</li>
     *   <li><b>dispose 执行订阅</b>：取消 Reactor 订阅链，停止上游数据推送</li>
     *   <li><b>释放 Redis 租约</b>：releaseLeaseQuietly — 静默释放，异常只打日志</li>
     *   <li><b>注销运行时注册</b>：从 ChatRuntimeRegistry 中移除 taskInfo，允许下一次请求</li>
     * </ol>
     * <p>
     * <b>注意：</b>cleanup 在所有三种收尾路径（成功/失败/取消）的 finally 块中调用，
     * 确保无论何种退出方式，资源都会被正确回收。
     *
     * @param taskInfo 运行时任务上下文
     */
    private void cleanup(TaskInfo taskInfo) {
        // 1. 停止租约续期定时器
        Disposable disposable = taskInfo.disposable();
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();

        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {
            // 停止 Flux.interval 的定时 tick
            leaseRenewalDisposable.dispose();
        }

        if (disposable != null && !disposable.isDisposed()) {
            // 取消执行管道的 Reactor 订阅链
            disposable.dispose();
        }

        // 2. 释放 Redis 分布式租约
        releaseLeaseQuietly(taskInfo.leaseKey(), taskInfo.leaseOwnerToken());

        // 3. 从运行时注册表中移除当前任务
        chatRuntimeRegistry.remove(taskInfo.conversationId(), taskInfo);
    }

    private List<SearchReference> deduplicateReferences(List<SearchReference> references) {
        Map<String, SearchReference> unique = new LinkedHashMap<>();

        for (SearchReference reference : references) {
            if (reference == null) {
                continue;
            }
            unique.putIfAbsent(reference.uniqueKey(), reference);
        }
        return new ArrayList<>(unique.values());
    }

/**
 * 初始化调试追踪对象 ChatDebugTrace
 * <p>
 * 这个对象最终写入 exchange.debugTraceJson 字段，在前端调试面板展示。
 * 它记录了一轮对话的完整决策过程：从原始问题 → 问题改写 → 路由决策 → 检索 → LLM 生成。
 * <p>
 * <b>两个调用时机：</b>
 * <ol>
 *   <li>createTaskInfo 中传 null → 创建一个空壳 debugTrace（只有空的 note 和 channel 列表）</li>
 *   <li>prepareExecutionPlan 中传 executionPlan → 用执行计划数据填充完整信息</li>
 * </ol>
 *
 * @param executionPlan 执行计划（null 时创建空壳，非 null 时完整填充）
 * @return 调试追踪对象
 */
private ChatDebugTrace initializeDebugTrace(ConversationExecutionPlan executionPlan) {
    // ── 分支 A：plan 为 null（createTaskInfo 阶段调用）──
    // 此时还不知道执行计划，只创建一个空壳
    if (executionPlan == null) {
        return ChatDebugTrace.builder()
            // 两个动态写入的列表（执行过程中 Executor 往里塞数据）
            .retrievalNotes(Collections.synchronizedList(new ArrayList<>()))  // 检索备注（如"走 ReactAgent 路径"）
            .usedChannels(Collections.synchronizedList(new ArrayList<>()))    // 使用的检索通道（如 vector/keyword）
            .build();
    }

    // ── 分支 B：plan 有值（prepareExecutionPlan 阶段调用）──
    // 把执行计划中的所有决策信息都填充进去
    return ChatDebugTrace.builder()

        // ═══ 基础执行信息 ═══
        .executionMode(executionPlan.getMode() == null ? "" : executionPlan.getMode().name())  // 执行模式：REACT_AGENT/RETRIEVAL/...
        .chatMode(executionPlan.getChatMode())  // 聊天模式：OPEN_CHAT/DOCUMENT/AUTO_DOCUMENT

        // ═══ 问题改写过程 ═══
        .originalQuestion(executionPlan.getOriginalQuestion())    // 用户原始提问
        .rewriteQuestion(executionPlan.getRewriteQuestion())       // LLM 改写后的检索问题
        .rewriteSubQuestions(executionPlan.getRewriteSubQuestions() == null   // 改写后的子问题列表（安全拷贝）
            ? List.of()
            : new ArrayList<>(executionPlan.getRewriteSubQuestions()))
        .retrievalQuestion(executionPlan.getRetrievalQuestion())  // 最终用于检索的问题（可能经过路由调整）
        .agentQuestion(executionPlan.getAgentQuestion())          // 最终交给 LLM Agent 的问题文本

        // ═══ 路由决策 ═══
        .navigationDecision(executionPlan.getNavigationDecision())  // 文档路由决策：走结构图还是检索

        // ═══ 历史记忆上下文 ═══
        .historySummary(executionPlan.getHistorySummary())            // 压缩后的历史摘要（给 LLM 看的）
        .longTermSummary(executionPlan.getLongTermSummary())          // 长期摘要文本（覆盖 N 轮之前的对话）
        .recentHistoryTranscript(executionPlan.getRecentHistoryTranscript())  // 近期对话窗口原文（最新几轮）
        .answerRecentTranscript(executionPlan.getAnswerRecentTranscript())    // 近期的助手回答原文
        .answerHistoryContext(executionPlan.getAnswerHistoryContext() == null  // 回答历史上下文的渲染文本
            ? ""
            : executionPlan.getAnswerHistoryContext().getRenderedText())
        .answerHistoryFollowUpQuestion(executionPlan.getAnswerHistoryContext() != null  // 当前问题是否为追问
            && executionPlan.getAnswerHistoryContext().isFollowUpQuestion())

        // ═══ 历史压缩统计 ═══
        .historyCompressionApplied(executionPlan.isHistoryCompressionApplied())  // 本轮是否应用了历史压缩
        .historyCoveredExchangeId(executionPlan.getHistoryCoveredExchangeId())    // 压缩已覆盖到的最后一条 exchangeId
        .historyCoveredExchangeCount(executionPlan.getHistoryCoveredExchangeCount())  // 压缩已覆盖的轮次数
        .historyCompressionCount(executionPlan.getHistoryCompressionCount())          // 累计压缩次数

        // ═══ 时间感知 ═══
        .currentDateText(executionPlan.getCurrentDateText())                     // 当前日期文本："2026-06-25（星期四）"
        .requiresFreshSearch(executionPlan.isRequiresFreshSearch())              // 是否需要实时搜索（问题含"最新""现在"等词）
        .requiresCurrentDateAnchoring(executionPlan.isRequiresCurrentDateAnchoring())  // 是否需要日期锚定（问题含"今天""当前"等词）

        // ═══ 检索相关 ═══
        .retrievalSubQuestions(executionPlan.getRetrievalSubQuestions() == null  // 检索用子问题列表（安全拷贝）
            ? List.of()
            : new ArrayList<>(executionPlan.getRetrievalSubQuestions()))
        .selectedDocumentId(executionPlan.getSelectedDocumentId())  // 当前锁定的文档 ID
        .selectedTaskId(executionPlan.getSelectedTaskId())          // 当前锁定的索引任务 ID

        // ═══ 运行时填充的列表（初始化为空，执行过程中 Executor 往里塞数据）═══
        .retrievalNotes(Collections.synchronizedList(new ArrayList<>()))   // 检索备注（如"走 ReactAgent 执行路径"）
        .usedChannels(Collections.synchronizedList(new ArrayList<>()))     // 使用的检索通道名称
        .toolTraces(Collections.synchronizedList(new ArrayList<>()))       // 工具调用轨迹（ChatToolTrace 列表）

        // ═══ 无证据兜底回复 ═══
        .noEvidenceReply(executionPlan.getNoEvidenceReply())  // 检索结果为空时的兜底回复文本

        .build();
}

    /**
     * 调用链路第4.2步（第5层入口）- 准备执行计划
     * <p>
     * <b>核心调用：</b>{@link ChatPreparationOrchestrator#prepare(TaskInfo)}
     * <p>
     * Orchestrator 内部会完成以下工作（详见该类的注释）：
     * <ol>
     *   <li><b>会话记忆装载</b>：加载历史摘要 + 近期对话窗口</li>
     *   <li><b>时间感知判断</b>：是否需要当日锚定 + 是否需要实时搜索</li>
     *   <li><b>模式路由</b>：
     *       <ul>
     *         <li>OPEN_CHAT → REACT_AGENT</li>
     *         <li>AUTO_DOCUMENT → KnowledgeRouteService → CLARIFICATION 或 RETRIEVAL</li>
     *         <li>DOCUMENT → DocumentQuestionRouter → GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL</li>
     *       </ul>
     *   </li>
     *   <li><b>问题改写</b>（非 OPEN_CHAT 模式）：ChatQueryRewriteService</li>
     * </ol>
     * <p>
     * <b>计划产出后的处理：</b>
     * <ul>
     *   <li>buildAgentQuestion：将执行计划渲染为 Agent 可理解的问题文本</li>
     *   <li>如果 Orchestrator 重新分配了 documentId（AUTO_DOCUMENT 自动选择），刷新 DB 中的会话范围</li>
     *   <li>更新 taskInfo 的 executionPlan、debugTrace、以及 runnableConfig 的 context</li>
     * </ul>
     *
     * @param taskInfo 运行时任务上下文
     * @return 完整的执行计划（含 ExecutionMode 和路由决策）
     */
    private ConversationExecutionPlan prepareExecutionPlan(TaskInfo taskInfo) {
        // ─── 进入第5层：ChatPreparationOrchestrator 编排执行计划 ───
        ConversationExecutionPlan executionPlan = chatPreparationOrchestrator.prepare(taskInfo);

        executionPlan.setAgentQuestion(buildAgentQuestion(executionPlan));
        if (executionPlan.getSelectedDocumentId() != null
            && !Objects.equals(executionPlan.getSelectedDocumentId(), taskInfo.selectedDocumentId())) {
            conversationArchiveStore.refreshSessionScope(
                taskInfo.conversationId(),
                executionPlan.getChatMode(),
                executionPlan.getSelectedDocumentId(),
                executionPlan.getSelectedDocumentName()
            );
            putContextIfNotNull(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_DOCUMENT_ID, executionPlan.getSelectedDocumentId());
            putContextIfNotBlank(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_DOCUMENT_NAME, executionPlan.getSelectedDocumentName());
            putContextIfNotNull(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_TASK_ID, executionPlan.getSelectedTaskId());
        }
        taskInfo.setExecutionPlan(executionPlan);
        taskInfo.setDebugTrace(initializeDebugTrace(executionPlan));
        taskInfo.runnableConfig().context().put(ChatContextKeys.DEBUG_TRACE, taskInfo.debugTrace());
        return executionPlan;
    }

    private ConversationSessionView toSessionView(ConversationArchiveStore.ConversationArchiveRecord archiveRecord,
                                                  boolean includeMemorySummary,
                                                  boolean includeExchanges) {

        RunnableConfig runnableConfig = RunnableConfig.builder()
            .threadId(archiveRecord.conversationId())
            .build();

        Map<String, Object> state = checkpointManager.get(runnableConfig)
            .map(Checkpoint::getState)
            .orElseGet(Map::of);
        Object messages = state.getOrDefault("messages", List.of());
        List<?> messageList = messages instanceof List<?> list ? list : List.of();
        List<ConversationExchangeView> archiveExchanges = archiveRecord.exchanges() == null ? List.of() : archiveRecord.exchanges();
        List<ConversationExchangeView> exchanges = includeExchanges ? archiveExchanges : List.of();
        int businessMessageCount = businessMessageCount(archiveExchanges);
        String businessLatestUserMessage = latestExchangeQuestion(archiveExchanges);
        String businessLatestAssistantMessage = latestExchangeAnswer(archiveExchanges);
        ConversationExchangeView latestExchange = latestExchange(archiveExchanges);

        return new ConversationSessionView(
            archiveRecord.conversationId(),
            archiveRecord.running(),

            checkpointManager.list(runnableConfig).size(),

            businessMessageCount > 0 ? businessMessageCount : messageList.size(),
            StrUtil.isNotBlank(businessLatestUserMessage) ? businessLatestUserMessage : latestMessage(messageList, MessageType.USER),
            StrUtil.isNotBlank(businessLatestAssistantMessage) ? businessLatestAssistantMessage : latestMessage(messageList, MessageType.ASSISTANT),
            latestExchange == null ? null : latestExchange.getExchangeId(),
            latestExchange == null || latestExchange.getStatus() == null ? "" : latestExchange.getStatus().name(),
            latestExchange == null || latestExchange.getErrorMessage() == null ? "" : latestExchange.getErrorMessage(),
            archiveRecord.chatMode(),
            archiveRecord.selectedDocumentId() == null ? "" : String.valueOf(archiveRecord.selectedDocumentId()),
            archiveRecord.selectedDocumentName(),
            archiveRecord.createdAt(),
            archiveRecord.updatedAt(),
            exchanges,
            includeMemorySummary ? conversationMemoryService.getConversationSummary(archiveRecord.conversationId()) : null
        );
    }

    private ConversationSessionView overlayRuntimeSnapshot(ConversationSessionView sessionView) {
        if (sessionView == null || sessionView.getExchanges() == null || sessionView.getExchanges().isEmpty()) {
            return sessionView;
        }
        Optional<TaskInfo> runtimeOptional = chatRuntimeRegistry.get(sessionView.getConversationId());
        if (runtimeOptional.isEmpty()) {
            return sessionView;
        }
        TaskInfo taskInfo = runtimeOptional.get();
        List<ConversationExchangeView> exchanges = new ArrayList<>(sessionView.getExchanges().size());
        boolean replaced = false;
        for (ConversationExchangeView exchange : sessionView.getExchanges()) {
            if (exchange == null) {
                continue;
            }
            if (exchange.getExchangeId() == taskInfo.exchangeId()) {
                exchanges.add(mergeRuntimeExchange(exchange, taskInfo));
                replaced = true;
                continue;
            }
            exchanges.add(exchange);
        }
        if (!replaced) {
            return sessionView;
        }
        sessionView.setExchanges(exchanges);
        sessionView.setMessageCount(businessMessageCount(exchanges));
        sessionView.setRunning(true);
        sessionView.setUpdatedAt(Instant.now());
        sessionView.setLatestExchangeId(taskInfo.exchangeId());
        sessionView.setLatestTurnStatus(ChatTurnStatus.RUNNING.name());
        String liveAnswer = taskInfo.answerBuffer().toString();
        if (StrUtil.isNotBlank(liveAnswer)) {
            sessionView.setLatestAssistantMessage(liveAnswer);
        }
        return sessionView;
    }

    private ConversationExchangeView mergeRuntimeExchange(ConversationExchangeView exchange,
                                                          TaskInfo taskInfo) {
        return new ConversationExchangeView(
            exchange.getExchangeId(),
            exchange.getQuestion(),
            taskInfo.answerBuffer().toString(),
            snapshotStringList(taskInfo.thinkingSteps()),
            deduplicateReferences(snapshotReferenceList(taskInfo.references())),
            exchange.getRecommendations() == null ? List.of() : exchange.getRecommendations(),
            snapshotUsedTools(taskInfo.usedTools()),
            taskInfo.debugTrace(),
            ChatTurnStatus.RUNNING,
            exchange.getErrorMessage(),
            toNullable(taskInfo.firstResponseTimeMs().get()),
            System.currentTimeMillis() - taskInfo.startTime(),
            exchange.getCreateTime(),
            exchange.getEditTime()
        );
    }

    private KnowledgeDocumentDescriptor resolveSelectedDocument(ChatQueryMode chatMode, String selectedDocumentId) {
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }
        String normalizedDocumentId = StrUtil.trimToNull(selectedDocumentId);
        if (chatMode == ChatQueryMode.OPEN_CHAT) {

            if (normalizedDocumentId != null) {
                throw new IllegalArgumentException("开放式提问模式下不能传 selectedDocumentId");
            }
            return null;
        }
        if (chatMode == ChatQueryMode.AUTO_DOCUMENT) {
            if (normalizedDocumentId != null) {
                throw new IllegalArgumentException("自动知识问答模式下不能传 selectedDocumentId");
            }
            return null;
        }

        if (normalizedDocumentId == null) {
            throw new IllegalArgumentException("当前文档问答模式下必须选择一个文档");
        }
        final Long resolvedDocumentId = parseRequiredLong(normalizedDocumentId, "selectedDocumentId");
        return documentKnowledgeService.listRetrievableDocuments().stream()
            .filter(item -> Objects.equals(item.getDocumentId(), resolvedDocumentId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("所选文档当前不可检索: " + normalizedDocumentId));
    }

    private KnowledgeDocumentOptionView toKnowledgeDocumentOptionView(KnowledgeDocumentDescriptor descriptor) {
        return new KnowledgeDocumentOptionView(
            descriptor.getDocumentId() == null ? "" : String.valueOf(descriptor.getDocumentId()),
            descriptor.getDocumentName(),
            descriptor.getKnowledgeScopeName(),
            descriptor.getBusinessCategory(),
            descriptor.getDocumentTags()
        );
    }

    private Long parseRequiredLong(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " 非法: " + value, exception);
        }
    }

    private int parsePositiveInt(String value, int defaultValue) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("分页参数非法: " + value, exception);
        }
    }

    private String normalizeOptionalText(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private ChatQueryMode parseOptionalChatMode(String value) {
        if (StrUtil.isBlank(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return ChatQueryMode.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("chatMode 非法: " + value, exception);
        }
    }

    private ChatQueryMode parseRequiredChatMode(String value) {
        ChatQueryMode chatMode = parseOptionalChatMode(value);
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }
        return chatMode;
    }

    private ChatTurnStatus parseOptionalTurnStatus(String value) {
        if (StrUtil.isBlank(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return ChatTurnStatus.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("turnStatus 非法: " + value, exception);
        }
    }

    private int businessMessageCount(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ConversationExchangeView exchange : exchanges) {
            if (exchange == null) {
                continue;
            }
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                count++;
            }
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                count++;
            }
        }
        return count;
    }

    private String latestExchangeQuestion(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null && StrUtil.isNotBlank(exchange.getQuestion())) {
                return exchange.getQuestion();
            }
        }
        return "";
    }

    private String latestExchangeAnswer(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null && StrUtil.isNotBlank(exchange.getAnswer())) {
                return exchange.getAnswer();
            }
        }
        return "";
    }

    private ConversationExchangeView latestExchange(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return null;
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null) {
                return exchange;
            }
        }
        return null;
    }

    private String latestMessage(List<?> messages, MessageType type) {

        for (int index = messages.size() - 1; index >= 0; index--) {
            Object candidate = messages.get(index);
            if (candidate instanceof AbstractMessage message && message.getMessageType() == type) {
                return message.getText();
            }
        }
        return "";
    }

    private List<ConversationExchangeView> recentExchanges(String conversationId) {

        return conversationArchiveStore.listRecentExchanges(
            conversationId,
            Math.max(1, chatAgentProperties.getHistoryPreviewTurns())
        );
    }

    private List<ConversationExchangeView> historicalRecentExchanges(TaskInfo taskInfo) {
        return recentExchanges(taskInfo.conversationId()).stream()
            .filter(exchange -> exchange.getExchangeId() != taskInfo.exchangeId())
            .toList();
    }

    private RunnableConfig buildSessionConfig(String conversationId) {

        return RunnableConfig.builder()
            .threadId(conversationId)
            .build();
    }

    private void putContextIfNotNull(RunnableConfig runnableConfig, String key, Object value) {
        if (runnableConfig == null || StrUtil.isBlank(key) || value == null) {
            return;
        }
        runnableConfig.context().put(key, value);
    }

    private void putContextIfNotBlank(RunnableConfig runnableConfig, String key, String value) {
        if (runnableConfig == null || StrUtil.isBlank(key) || StrUtil.isBlank(value)) {
            return;
        }
        runnableConfig.context().put(key, value.trim());
    }

    /**
     * 启动 Redis 租约续期定时器
     * <p>
     * <b>工作原理：</b>
     * 使用 {@link Flux#interval} 每 10 秒触发一次续约（TTL 为 30 秒，保证 3 次续约窗口）。
     * 如果续约失败（租约被别人抢占/过期），自动 {@link #renewLeaseOrStop(TaskInfo)} 停止当前任务。
     * <p>
     * <b>为什么需要租约？</b>
     * 防止同一 conversationId 的流式任务被多个请求并发执行（如用户重复点击发送按钮）。
     * 租约在启动时获取（claimConversationLease），执行期间定时续约，完成/失败/取消后释放。
     *
     * @param taskInfo 运行时任务上下文
     * @return 定时器的 Disposable，用于 cleanup 时取消
     */
    private Disposable startLeaseRenewal(TaskInfo taskInfo) {

        return Flux.interval(CHAT_RUNNING_LEASE_RENEW_INTERVAL, CHAT_RUNNING_LEASE_RENEW_INTERVAL)
            // 每次 tick 尝试续约；失败则停止任务
            .subscribe(ignored -> renewLeaseOrStop(taskInfo), error ->
                log.warn("租约续期任务出现异常, conversationId={}, exchangeId={}",
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    error)
            );
    }

    /**
     * 尝试续约 Redis 租约，失败则停止任务
     * <p>
     * <b>租约续约机制：</b>
     * Redis 使用 SET ... NX EX TTL 原子命令实现分布式锁。renew 命令检查 owner token 匹配后才能续约，
     * 防止误续他人的锁。如果续约失败（锁已过期或被抢占），立即停止当前任务。
     * <p>
     * <b>续约失败的常见原因：</b>
     * <ul>
     *   <li>Redis 网络闪断导致锁过期</li>
     *   <li>同一 conversationId 的新请求强行抢占（极端场景）</li>
     *   <li>任务执行时间过长，超过续约周期容忍度</li>
     * </ul>
     *
     * @param taskInfo 运行时任务上下文
     */
    private void renewLeaseOrStop(TaskInfo taskInfo) {
        // 尝试续约（TTL 重置为 30 秒）
        boolean renewed = redisLeaseManager.renew(
            taskInfo.leaseKey(),
            taskInfo.leaseOwnerToken(),
            CHAT_RUNNING_LEASE_TTL
        );
        if (renewed) {
            // 续约成功，任务继续执行
            return;
        }
        // 续约失败 → 停止自身的定时器 + 停止当前任务
        log.warn("会话租约续期失败，准备停止当前会话, conversationId={}, exchangeId={}",
            taskInfo.conversationId(),
            taskInfo.exchangeId());
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();
        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {
            leaseRenewalDisposable.dispose();
        }
        stopTask(taskInfo, "会话租约已失效，已停止生成");
    }

    private void releaseLeaseQuietly(String leaseKey, String leaseOwnerToken) {
        try {

            redisLeaseManager.release(leaseKey, leaseOwnerToken);
        }
        catch (RuntimeException exception) {

            log.warn("释放会话租约时出现异常, leaseKey={}", leaseKey, exception);
        }
    }

    private String buildChatLeaseKey(String conversationId) {

        return CHAT_RUNNING_LEASE_PREFIX + conversationId;
    }

    private Long toNullable(long value) {

        return value > 0 ? value : null;
    }

    private String normalizeQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            throw new SuperAgentFrameException("question 不能为空");
        }

        return question.trim();
    }

    private String normalizeConversationId(String conversationId) {
        if (StrUtil.isNotBlank(conversationId)) {

            return conversationId.trim();
        }

        return UUID.randomUUID().toString().replace("-", "");
    }

    private String buildAgentQuestion(ConversationExecutionPlan executionPlan) {
        return promptTemplateService.render(PromptTemplateNames.AGENT_QUESTION, Map.of(
            "currentDateText", StrUtil.blankToDefault(executionPlan.getCurrentDateText(), ""),
            "requiresCurrentDateAnchoring", executionPlan.isRequiresCurrentDateAnchoring(),
            "requiresFreshSearch", executionPlan.isRequiresFreshSearch(),
            "hasHistorySummary", StrUtil.isNotBlank(executionPlan.getHistorySummary()),
            "historySummary", StrUtil.blankToDefault(executionPlan.getHistorySummary(), ""),
            "question", StrUtil.blankToDefault(executionPlan.getOriginalQuestion(), "")
        ));
    }

    private String formatCurrentDate(LocalDate currentDate) {

        return currentDate + "（" + chineseWeekday(currentDate.getDayOfWeek()) + "）";
    }

    private String chineseWeekday(DayOfWeek dayOfWeek) {

        return switch (dayOfWeek) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private void safeEmit(Sinks.Many<String> sink, String payload) {

        SinkEmitHelper.emitNext(sink, payload);
    }

    private void safeComplete(Sinks.Many<String> sink) {

        SinkEmitHelper.emitComplete(sink);
    }

    private List<String> snapshotStringList(List<String> source) {
        synchronized (source) {
            return List.copyOf(source);
        }
    }

    private List<SearchReference> snapshotReferenceList(List<SearchReference> source) {
        synchronized (source) {
            return new ArrayList<>(source);
        }
    }

    private List<String> snapshotUsedTools(Set<String> source) {
        return new ArrayList<>(source);
    }

    public List<RetrievalResultView> getRetrievalResults(String conversationId, long exchangeId) {
        return retrievalObserveStore.listResults(conversationId, exchangeId);
    }

    public List<ChannelExecutionView> getChannelExecutions(String conversationId, long exchangeId) {
        return retrievalObserveStore.listChannelExecutions(conversationId, exchangeId);
    }

    public List<StageBenchmarkView> getStageBenchmarks() {
        return stageBenchmarkService.listAll();
    }

    private void safeRefreshConversationSummary(String conversationId) {
        try {
            conversationMemoryService.refreshConversationSummaryAsync(conversationId);
        }
        catch (RuntimeException exception) {
            log.warn("刷新会话摘要失败, conversationId={}", conversationId, exception);
        }
    }

}
