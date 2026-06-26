package org.javaup.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.debug.ChatModelUsageTrace;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 带观测的 LLM 调用服务 —— 对 Spring AI ChatModel 的薄封装
 * <p>
 * 在原生 ChatModel.call() / ChatModel.stream() 之上增加：
 * <ul>
 *   <li><b>追踪</b>：每次调用记录 stageName/provider/model/duration/status → ChatModelUsageTrace</li>
 *   <li><b>Token 估算</b>：LLM 没返回 Usage 时用 char/4 粗略估算（{@link #estimateTokens}）</li>
 *   <li><b>成本估算</b>：按 qwen-plus/deepseek 的 $/1k token 费率计算（{@link #estimateCost}）</li>
 *   <li><b>参数覆盖</b>：支持按调用覆盖 temperature/topP/model（{@link #mergeOptions}）</li>
 * </ul>
 *
 * @author 阿星不是程序员
 */
@Slf4j
@Service
public class ObservedChatModelService {

    /** Spring AI 的 ChatModel 实例（由 application.yml 配置，如 DashScope/OpenAI/Ollama） */
    private final ChatModel chatModel;

    public ObservedChatModelService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 同步 LLM 调用（无自定义参数）—— 使用模型默认 temperature/model 等
     *
     * @param stageName     调用阶段名称（如 "summary" / "rewrite" / "rag_answer"），用于追踪
     * @param systemPrompt  系统提示词（可为空）
     * @param userPrompt    用户提示词
     * @param traceRecorder 追踪记录器（可 null，null 时不记录追踪）
     * @return LLM 返回的文本
     */
    public String callText(String stageName,
                           String systemPrompt,
                           String userPrompt,
                           ConversationTraceRecorder traceRecorder) {
        // callOptions=null → 使用模型默认参数
        return callText(stageName, systemPrompt, userPrompt, null, traceRecorder);
    }

    /**
     * 同步 LLM 调用（可覆盖参数）—— 允许传入自定义 temperature/model/topP 等
     * <p>
     * <b>执行流程：</b>
     * <ol>
     *   <li>合并参数：callOptions 覆盖默认值（{@link #mergeOptions}）</li>
     *   <li>构建 Prompt：SystemMessage + UserMessage</li>
     *   <li>调用 ChatModel.call(Prompt) → 阻塞等待 LLM 返回</li>
     *   <li>提取响应文本 + 构建用量追踪 → 写入 traceRecorder</li>
     * </ol>
     *
     * @param stageName     调用阶段名称
     * @param systemPrompt  系统提示词
     * @param userPrompt    用户提示词
     * @param callOptions   自定义调用参数（temperature/model/topP 等，null 则用默认）
     * @param traceRecorder 追踪记录器
     * @return LLM 返回的文本
     */
    public String callText(String stageName,
                           String systemPrompt,
                           String userPrompt,
                           ChatOptions callOptions,
                           ConversationTraceRecorder traceRecorder) {
        long startTime = System.currentTimeMillis();  // 计时起点
        // 从 ChatModel 的类名推断提供商（deepseek/openai/ollama）
        String provider = resolveProvider();
        // 从 ChatModel 的默认配置取模型名
        String model = resolveModel();
        try {
            // ① 合并调用参数：callOptions 覆盖默认值（如果 callOptions 为 null 则完全用默认）
            ChatOptions effectiveOptions = mergeOptions(callOptions);
            logStageCallOptions(stageName, provider, model, effectiveOptions);

            // ② 构建 Prompt（SystemMessage + UserMessage），调用 ChatModel
            ChatResponse response = chatModel.call(buildPrompt(systemPrompt, userPrompt, effectiveOptions));

            // ③ 提取响应文本（防御性空值处理）
            String responseText = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : StrUtil.blankToDefault(response.getResult().getOutput().getText(), "");

            // ④ 构建用量追踪：Token 数、耗时、费用
            ChatModelUsageTrace usageTrace = buildUsageTrace(
                stageName,
                provider,
                model,
                response == null ? null : response.getMetadata(),  // LLM 返回的 Usage 元数据
                System.currentTimeMillis() - startTime,             // 实际耗时
                "COMPLETED",
                systemPrompt,
                userPrompt,
                responseText
            );
            // ⑤ 写入追踪记录器（如果 traceRecorder 不为 null）
            appendUsage(traceRecorder, usageTrace);
            return responseText;
        }
        catch (RuntimeException exception) {
            // LLM 调用异常 → 记录 FAILED 追踪后重新抛出
            appendUsage(traceRecorder, ChatModelUsageTrace.builder()
                .stageName(stageName)
                .provider(provider)
                .model(model)
                .durationMs(System.currentTimeMillis() - startTime)
                .promptTokens(estimateTokens(systemPrompt) + estimateTokens(userPrompt))  // Token 估算（异常时没有实际 Usage）
                .status("FAILED")
                .build());
            throw exception;
        }
    }

    public Flux<String> streamText(String stageName,
                                   String systemPrompt,
                                   String userPrompt,
                                   ConversationTraceRecorder traceRecorder) {
        String provider = resolveProvider();
        String model = resolveModel();
        long startTime = System.currentTimeMillis();
        AtomicReference<ChatResponseMetadata> metadataRef = new AtomicReference<>();
        AtomicLong durationRef = new AtomicLong(0L);
        StringBuilder outputBuilder = new StringBuilder();

        return chatModel.stream(buildPrompt(systemPrompt, userPrompt))
            .map(response -> {
                if (response != null && response.getMetadata() != null) {
                    metadataRef.set(response.getMetadata());
                }
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                    return "";
                }
                return StrUtil.blankToDefault(response.getResult().getOutput().getText(), "");
            })
            .filter(StrUtil::isNotBlank)
            .doOnNext(outputBuilder::append)
            .doOnComplete(() -> {
                long durationMs = System.currentTimeMillis() - startTime;
                durationRef.set(durationMs);
                appendUsage(traceRecorder, buildUsageTrace(stageName, provider, model, metadataRef.get(), durationMs, "COMPLETED", systemPrompt, userPrompt, outputBuilder.toString()));
            })
            .doOnError(error -> appendUsage(traceRecorder, ChatModelUsageTrace.builder()
                .stageName(stageName)
                .provider(provider)
                .model(model)
                .promptTokens(estimateTokens(systemPrompt) + estimateTokens(userPrompt))
                .completionTokens(estimateTokens(outputBuilder.toString()))
                .totalTokens(estimateTokens(systemPrompt) + estimateTokens(userPrompt) + estimateTokens(outputBuilder.toString()))
                .estimatedCost(estimateCost(model, estimateTokens(systemPrompt) + estimateTokens(userPrompt), estimateTokens(outputBuilder.toString())))
                .durationMs(durationRef.get() > 0 ? durationRef.get() : System.currentTimeMillis() - startTime)
                .status("FAILED")
                .build()));
    }

    /** 构建 Prompt（无自定义参数 → 用模型默认值） */
    private Prompt buildPrompt(String systemPrompt, String userPrompt) {
        return buildPrompt(systemPrompt, userPrompt, null);
    }

    /** 构建 Spring AI Prompt 对象：SystemMessage（可选）+ UserMessage */
    private Prompt buildPrompt(String systemPrompt, String userPrompt, ChatOptions callOptions) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(StrUtil.blankToDefault(userPrompt, "")));
        ChatOptions mergedOptions = mergeOptions(callOptions);
        return mergedOptions == null ? new Prompt(messages) : new Prompt(messages, mergedOptions);
    }

    /**
     * 合并调用参数：以默认参数为基础，callOptions 中的非 null 字段覆盖默认值
     * <p>
     * callOptions 为 null 时返回 null（表示完全用模型默认值），
     * 非 null 时 copy 一份默认值再用 callOptions 覆盖有值的字段。
     */
    private ChatOptions mergeOptions(ChatOptions callOptions) {
        if (callOptions == null) {
            return null;  // 不传递任何参数覆盖，完全用 ChatModel 的默认配置
        }
        ChatOptions defaultOptions = chatModel.getDefaultOptions();
        if (defaultOptions instanceof OpenAiChatOptions defaultOpenAi
            && callOptions instanceof OpenAiChatOptions overrideOpenAi) {
            // 以默认值为底，逐字段覆盖非 null 的 callOptions
            OpenAiChatOptions merged = defaultOpenAi.copy();
            if (overrideOpenAi.getModel() != null) {
                merged.setModel(overrideOpenAi.getModel());
            }
            if (overrideOpenAi.getTemperature() != null) {
                merged.setTemperature(overrideOpenAi.getTemperature());
            }
            if (overrideOpenAi.getTopP() != null) {
                merged.setTopP(overrideOpenAi.getTopP());
            }
            if (overrideOpenAi.getReasoningEffort() != null) {
                merged.setReasoningEffort(overrideOpenAi.getReasoningEffort());
            }
            if (overrideOpenAi.getVerbosity() != null) {
                merged.setVerbosity(overrideOpenAi.getVerbosity());
            }
            if (overrideOpenAi.getExtraBody() != null && !overrideOpenAi.getExtraBody().isEmpty()) {
                Map<String, Object> mergedExtraBody = new LinkedHashMap<>();
                if (merged.getExtraBody() != null && !merged.getExtraBody().isEmpty()) {
                    mergedExtraBody.putAll(merged.getExtraBody());
                }
                mergedExtraBody.putAll(overrideOpenAi.getExtraBody());
                merged.setExtraBody(mergedExtraBody);
            }
            return merged;
        }
        return callOptions;
    }

    /** 打印参数日志（debug 用） */
    private void logStageCallOptions(String stageName,
                                     String provider,
                                     String fallbackModel,
                                     ChatOptions effectiveOptions) {
        if (!(effectiveOptions instanceof OpenAiChatOptions openAiOptions)) {
            if (effectiveOptions != null) {
                log.info("模型调用参数: stage={}, provider={}, model={}, optionsClass={}",
                    StrUtil.blankToDefault(stageName, ""),
                    provider,
                    fallbackModel,
                    effectiveOptions.getClass().getName());
            }
            return;
        }
        log.info("模型调用参数: stage={}, provider={}, model={}, temperature={}, topP={}, reasoningEffort={}, verbosity={}, extraBody={}",
            StrUtil.blankToDefault(stageName, ""),
            provider,
            StrUtil.blankToDefault(openAiOptions.getModel(), fallbackModel),
            openAiOptions.getTemperature(),
            openAiOptions.getTopP(),
            StrUtil.blankToDefault(openAiOptions.getReasoningEffort(), ""),
            StrUtil.blankToDefault(openAiOptions.getVerbosity(), ""),
            openAiOptions.getExtraBody() == null ? Map.of() : openAiOptions.getExtraBody());
    }

    /** 将用量追踪写入 traceRecorder（null 安全） */
    private void appendUsage(ConversationTraceRecorder traceRecorder, ChatModelUsageTrace trace) {
        if (traceRecorder != null && trace != null) {
            traceRecorder.addModelUsageTrace(trace);
        }
    }

    /**
     * 构建 ChatModelUsageTrace
     * <p>
     * Token 优先取 LLM 返回的 Usage 元数据（精确值），
     * 如果 LLM 没返回（如某些 Ollama 部署不返回 Usage），用 {@link #estimateTokens} 估算。
     */
    private ChatModelUsageTrace buildUsageTrace(String stageName,
                                                String provider,
                                                String model,
                                                ChatResponseMetadata metadata,
                                                long durationMs,
                                                String status,
                                                String systemPrompt,
                                                String userPrompt,
                                                String responseText) {
        Usage usage = metadata == null ? null : metadata.getUsage();
        // 优先取 LLM 返回的精确 Token 数
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();
        // LLM 没返回 → 估算（char / 4.0 粗略估算 Token 数）
        if (promptTokens == null || promptTokens <= 0) {
            promptTokens = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
        }
        if (completionTokens == null || completionTokens <= 0) {
            completionTokens = estimateTokens(responseText);
        }
        if (totalTokens == null || totalTokens <= 0) {
            totalTokens = (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        }
        return ChatModelUsageTrace.builder()
            .stageName(stageName)
            .provider(provider)
            .model(StrUtil.blankToDefault(metadata == null ? model : metadata.getModel(), model))
            .promptTokens(promptTokens)
            .completionTokens(completionTokens)
            .totalTokens(totalTokens)
            .estimatedCost(estimateCost(model, promptTokens, completionTokens))  // 按费率算费用
            .durationMs(durationMs)
            .status(status)
            .build();
    }

    /**
     * 粗略估算 Token 数：字符数 ÷ 4
     * <p>
     * 经验公式：中文约 1 字 ≈ 1.5 token，英文约 1 词 ≈ 1.3 token，
     * char/4 是一个通用粗略估算，误差在 ±30% 以内。
     * 仅在 LLM 不返回精确 Usage 时使用。
     */
    private Integer estimateTokens(String content) {
        if (StrUtil.isBlank(content)) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(content.trim().length() / 4.0));
    }

    /** 从 ChatModel 的类名推断 LLM 提供商 */
    private String resolveProvider() {
        String className = chatModel.getClass().getName().toLowerCase();
        if (className.contains("deepseek")) {
            return "deepseek";
        }
        if (className.contains("openai")) {
            return "openai-compatible";
        }
        if (className.contains("ollama")) {
            return "ollama";
        }
        return "unknown";
    }

    /** 从 ChatModel 的默认配置中取模型名称 */
    private String resolveModel() {
        ChatOptions options = chatModel.getDefaultOptions();
        return options == null ? "" : StrUtil.blankToDefault(options.getModel(), "");
    }

    /**
     * 估算 LLM 调用费用
     * <p>
     * 当前仅支持 qwen-plus 和 deepseek 的费率，不支持时返回 null。
     * 费率单位：美元 / 1000 token。
     */
    private Double estimateCost(String model, Integer promptTokens, Integer completionTokens) {
        if ((promptTokens == null || promptTokens <= 0) && (completionTokens == null || completionTokens <= 0)) {
            return null;
        }
        String normalizedModel = StrUtil.blankToDefault(model, "").toLowerCase();
        double promptRatePer1k;
        double completionRatePer1k;
        // 按模型匹配费率（单位：$/1k token）
        if (normalizedModel.contains("qwen-plus")) {
            promptRatePer1k = 0.004;       // qwen-plus: $0.004/1k prompt tokens
            completionRatePer1k = 0.012;   // $0.012/1k completion tokens
        }
        else if (normalizedModel.contains("deepseek")) {
            promptRatePer1k = 0.002;
            completionRatePer1k = 0.008;
        }
        else {
            promptRatePer1k = 0.0;        // 未知模型 → 不计费
            completionRatePer1k = 0.0;
        }
        double promptCost = (promptTokens == null ? 0D : promptTokens / 1000D) * promptRatePer1k;
        double completionCost = (completionTokens == null ? 0D : completionTokens / 1000D) * completionRatePer1k;
        double total = promptCost + completionCost;
        return total > 0D ? total : null;  // 费用 ≤ 0 时返回 null（表示不适用）
    }
}
