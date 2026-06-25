package org.javaup.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.javaup.ai.chatagent.data.SuperAgentChatDialogue;
import org.javaup.ai.chatagent.data.SuperAgentChatExchange;
import org.javaup.ai.chatagent.mapper.SuperAgentChatDialogueMapper;
import org.javaup.ai.chatagent.mapper.SuperAgentChatExchangeMapper;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.ChatQueryMode;
import org.javaup.enums.ChatSessionStatus;
import org.javaup.enums.ChatTurnStatus;
import org.javaup.util.DateUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Repository
public class MybatisConversationArchiveStore implements ConversationArchiveStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<SearchReference>> REFERENCE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<ChatDebugTrace> DEBUG_TRACE_TYPE = new TypeReference<>() {
    };

    private final SuperAgentChatDialogueMapper dialogueMapper;
    private final SuperAgentChatExchangeMapper exchangeMapper;
    private final ObjectMapper objectMapper;

    @Resource
    private UidGenerator uidGenerator;

    public MybatisConversationArchiveStore(SuperAgentChatDialogueMapper dialogueMapper,
                                           SuperAgentChatExchangeMapper exchangeMapper,
                                           ObjectMapper objectMapper) {
        this.dialogueMapper = dialogueMapper;
        this.exchangeMapper = exchangeMapper;
        this.objectMapper = objectMapper;
    }

/**
 * 开启新一轮对话（在数据库中创建 exchange 记录）
 * <p>
 * <b>这个方法做两件事（同一个事务）：</b>
 * <ol>
 *   <li><b>维护会话记录（dialogue）</b>：
 *       如果 conversationId 不存在 → 新建一条 dialogue，状态 = RUNNING；
 *       如果已存在 → 更新 chatMode / documentScope 如果有变化</li>
 *   <li><b>创建本轮交换记录（exchange）</b>：
 *       用雪花 ID 生成器产生全局唯一 exchangeId，插入一条 exchange，
 *       状态 = RUNNING，answer/thinking/references 暂时全空。
 *       这些字段在对话结束后由 {@link #completeExchange} 回填。</li>
 * </ol>
 * <p>
 * <b>为什么 start + complete 要分两步？</b>
 * 因为流式对话是异步的：start 时还不知道回答内容，
 * 只能先占位一条 RUNNING 记录，等 LLM 生成完毕后 complete 再回来填坑。
 * <p>
 * <b>对话表（dialogue）vs 轮次表（exchange）的关系：</b>
 * 1 个 dialogue（会话）= N 个 exchange（轮次）。
 * 例如用户连续问了 3 个问题，就是 1 条 dialogue + 3 条 exchange。
 *
 * @param conversationId     会话ID
 * @param question           用户问题
 * @param chatMode           聊天模式（OPEN_CHAT / DOCUMENT / AUTO_DOCUMENT）
 * @param selectedDocumentId 锁定的文档ID（DOCUMENT 模式下有值）
 * @param selectedDocumentName 锁定的文档名称
 * @return 本轮交换的视图对象（含 exchangeId，后续流程需要用它做追踪和收尾）
 */
@Override
@Transactional(rollbackFor = Exception.class)
public ConversationExchangeView startExchange(String conversationId,
                                              String question,
                                              ChatQueryMode chatMode,
                                              Long selectedDocumentId,
                                              String selectedDocumentName) {
    // 第一步：维护 dialogue 表 —— 没有就创建，有就按需更新（chatMode/文档变化时）
    upsertDialogue(conversationId, ChatSessionStatus.RUNNING, chatMode, selectedDocumentId, selectedDocumentName);

    // 第二步：用百度 UID 生成器产生全局唯一、趋势递增的 exchangeId（雪花算法）
    long exchangeId = uidGenerator.getUid();

    // 第三步：插入一条"占位"exchange，answer/thinking/references 暂时全空
    SuperAgentChatExchange exchange = new SuperAgentChatExchange();
    exchange.setId(exchangeId);
    exchange.setConversationId(conversationId);
    exchange.setQuestion(question);
    exchange.setAnswer("");                          // 还没生成，先空着
    exchange.setThinkingSteps(writeJson(List.of()));  // JSON [] 占位
    exchange.setReferenceList(writeJson(List.of()));  // JSON [] 占位
    exchange.setRecommendationList(writeJson(List.of())); // JSON [] 占位
    exchange.setUsedToolList(writeJson(List.of()));   // JSON [] 占位
    exchange.setDebugTraceJson(null);                 // 调试信息稍后补
    exchange.setTurnStatus(ChatTurnStatus.RUNNING.getCode()); // 标记为"进行中"
    exchange.setErrorMessage("");                     // 无错误
    exchange.setFirstResponseTimeMs(null);            // 首字延迟稍后补
    exchange.setTotalResponseTimeMs(null);            // 总耗时稍后补
    exchange.setStatus(BusinessStatus.YES.getCode()); // 1=正常
    exchangeMapper.insert(exchange);

    // 返回视图对象（给 BusinessChatService.createTaskInfo 用）
    return new ConversationExchangeView(
        exchangeId,
        question,
        "",                     // answer 还是空的
        List.of(),              // thinkingSteps 空的
        List.of(),              // references 空的
        List.of(),              // recommendations 空的
        List.of(),              // usedTools 空的
        null,                   // debugTrace 稍后补
        ChatTurnStatus.RUNNING,
        "",
        null,                   // 首字延迟
        null,                   // 总耗时
        DateUtils.now(),
        DateUtils.now()
    );
}

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshSessionScope(String conversationId,
                                    ChatQueryMode chatMode,
                                    Long selectedDocumentId,
                                    String selectedDocumentName) {
        upsertDialogue(conversationId, ChatSessionStatus.RUNNING, chatMode, selectedDocumentId, selectedDocumentName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeExchange(String conversationId,
                                 long exchangeId,
                                 String answer,
                                 List<String> thinkingSteps,
                                 List<SearchReference> references,
                                 List<String> recommendations,
                                 List<String> usedTools,
                                 ChatDebugTrace debugTrace,
                                 ChatTurnStatus status,
                                 String errorMessage,
                                 Long firstResponseTimeMs,
                                 Long totalResponseTimeMs) {

        SuperAgentChatExchange existingExchange = exchangeMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getId, exchangeId)
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .last("LIMIT 1")
        );
        if (existingExchange == null) {

            return;
        }

        SuperAgentChatExchange updateExchange = new SuperAgentChatExchange();
        updateExchange.setId(exchangeId);
        updateExchange.setAnswer(safeText(answer));
        updateExchange.setThinkingSteps(writeJson(thinkingSteps));
        updateExchange.setReferenceList(writeJson(references));
        updateExchange.setRecommendationList(writeJson(recommendations));
        updateExchange.setUsedToolList(writeJson(usedTools));
        updateExchange.setDebugTraceJson(writeNullableJson(debugTrace));
        updateExchange.setTurnStatus(status.getCode());
        updateExchange.setErrorMessage(safeText(errorMessage));
        updateExchange.setFirstResponseTimeMs(firstResponseTimeMs);
        updateExchange.setTotalResponseTimeMs(totalResponseTimeMs);
        exchangeMapper.updateById(updateExchange);

        dialogueMapper.update(
            null,
            new LambdaUpdateWrapper<SuperAgentChatDialogue>()
                .eq(SuperAgentChatDialogue::getConversationId, conversationId)

                .set(SuperAgentChatDialogue::getSessionStatus, ChatSessionStatus.IDLE.getCode())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationArchiveRecord> getSessionRecord(String conversationId) {

        SuperAgentChatDialogue dialogue = dialogueMapper.selectOne(
            activeDialogueByConversation(conversationId)
                .orderByDesc(SuperAgentChatDialogue::getId)
                .last("LIMIT 1")
        );
        if (dialogue == null) {
            return Optional.empty();
        }

        List<ConversationExchangeView> exchanges = loadExchangeViews(List.of(conversationId))
            .getOrDefault(conversationId, List.of());

        return Optional.of(new ConversationArchiveRecord(
            dialogue.getConversationId(),
            ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
            resolveChatMode(dialogue),
            dialogue.getSelectedDocumentId(),
            safeText(dialogue.getSelectedDocumentName()),
            toInstant(dialogue.getCreateTime()),
            toInstant(dialogue.getEditTime()),
            exchanges
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listExchanges(String conversationId) {

        return selectConversationExchanges(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listExchangesAfter(String conversationId, long afterExchangeId) {

        return selectConversationExchanges(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .gt(afterExchangeId > 0, SuperAgentChatExchange::getId, afterExchangeId)
                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listRecentExchanges(String conversationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .orderByDesc(SuperAgentChatExchange::getCreateTime)
                .orderByDesc(SuperAgentChatExchange::getId)
                .last("LIMIT " + limit)
        );
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }
        List<ConversationExchangeView> views = new ArrayList<>(exchanges.size());
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            views.add(toExchangeView(exchanges.get(index)));
        }
        return views;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationArchiveRecord> listSessionRecords() {
        List<SuperAgentChatDialogue> rawDialogues = dialogueMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatDialogue>()
                .orderByDesc(SuperAgentChatDialogue::getEditTime)
                .orderByDesc(SuperAgentChatDialogue::getId)
        );
        if (rawDialogues == null || rawDialogues.isEmpty()) {
            return List.of();
        }

        Map<String, SuperAgentChatDialogue> latestDialogues = new LinkedHashMap<>();
        for (SuperAgentChatDialogue dialogue : rawDialogues) {

            latestDialogues.putIfAbsent(dialogue.getConversationId(), dialogue);
        }
        List<SuperAgentChatDialogue> dialogues = new ArrayList<>(latestDialogues.values());

        List<String> conversationIds = dialogues.stream()
            .map(SuperAgentChatDialogue::getConversationId)
            .toList();

        Map<String, List<ConversationExchangeView>> exchangeViewMap = loadExchangeViews(conversationIds);

        List<ConversationArchiveRecord> result = new ArrayList<>(dialogues.size());
        for (SuperAgentChatDialogue dialogue : dialogues) {
            result.add(new ConversationArchiveRecord(
                dialogue.getConversationId(),
                ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
                resolveChatMode(dialogue),
                dialogue.getSelectedDocumentId(),
                safeText(dialogue.getSelectedDocumentName()),
                toInstant(dialogue.getCreateTime()),
                toInstant(dialogue.getEditTime()),
                exchangeViewMap.getOrDefault(dialogue.getConversationId(), List.of())
            ));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationArchivePage listSessionRecordPage(int pageNo,
                                                         int pageSize,
                                                         String keyword,
                                                         ChatQueryMode chatMode,
                                                         ChatTurnStatus latestTurnStatus) {
        int resolvedPageNo = Math.max(pageNo, 1);
        int resolvedPageSize = Math.max(pageSize, 1);

        LambdaQueryWrapper<SuperAgentChatDialogue> wrapper = new LambdaQueryWrapper<SuperAgentChatDialogue>()
            .orderByDesc(SuperAgentChatDialogue::getEditTime)
            .orderByDesc(SuperAgentChatDialogue::getId);
        applySessionPageFilters(wrapper, keyword, chatMode, latestTurnStatus);

        Page<SuperAgentChatDialogue> page = new Page<>(resolvedPageNo, resolvedPageSize);
        IPage<SuperAgentChatDialogue> resultPage = dialogueMapper.selectPage(
            page,
            wrapper
        );

        List<String> conversationIds = resultPage.getRecords().stream()
            .map(SuperAgentChatDialogue::getConversationId)
            .toList();
        Map<String, ConversationExchangeView> latestExchangeMap = loadLatestExchangeMap(conversationIds);

        List<ConversationArchiveRecord> records = resultPage.getRecords().stream()
            .map(dialogue -> new ConversationArchiveRecord(
                dialogue.getConversationId(),
                ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
                resolveChatMode(dialogue),
                dialogue.getSelectedDocumentId(),
                safeText(dialogue.getSelectedDocumentName()),
                toInstant(dialogue.getCreateTime()),
                toInstant(dialogue.getEditTime()),
                latestExchangeMap.containsKey(dialogue.getConversationId())
                    ? List.of(latestExchangeMap.get(dialogue.getConversationId()))
                    : List.of()
            ))
            .toList();

        return new ConversationArchivePage(
            resultPage.getCurrent(),
            resultPage.getSize(),
            resultPage.getTotal(),
            records
        );
    }

    @Override
    @Transactional
    public ConversationArchiveStore.ConversationRemovalResult deleteSession(String conversationId) {
        LambdaQueryWrapper<SuperAgentChatExchange> exchangeQuery = exchangesByConversation(conversationId);
        LambdaQueryWrapper<SuperAgentChatDialogue> dialogueQuery = activeDialogueByConversation(conversationId);

        int removedExchangeCount = toInt(exchangeMapper.selectCount(exchangeQuery));
        int removedDialogueCount = toInt(dialogueMapper.selectCount(dialogueQuery));

        if (removedExchangeCount > 0) {

            exchangeMapper.delete(exchangesByConversation(conversationId));
        }
        if (removedDialogueCount > 0) {
            dialogueMapper.delete(activeDialogueByConversation(conversationId));
        }

        return new ConversationArchiveStore.ConversationRemovalResult(removedDialogueCount, removedExchangeCount);
    }

/**
 * 插入或更新对话会话记录（dialogue 表）
 * <p>
 * <b>逻辑：</b>
 * <ol>
 *   <li>按 conversationId 查一条现有的 dialogue（取最新一条）</li>
 *   <li><b>不存在 → INSERT</b>：新建一条 dialogue，状态设为 RUNNING，
 *       记录当前的 chatMode 和锁定的文档范围</li>
 *   <li><b>存在 → 按需 UPDATE</b>：只有当状态、chatMode、或文档范围发生变化时才更新。
 *       如果完全没变就不发 SQL，减少无意义的数据库写操作</li>
 * </ol>
 * <p>
 * <b>为什么"没变就不更新"？</b>
 * 每次 startExchange 都会调这个方法。如果用户在同一个会话里连续问多个问题，
 * chatMode 和文档范围通常不变，没必要每次都 UPDATE 一遍 dialogue 表。
 *
 * @param conversationId     会话ID
 * @param dialogueStage      目标会话状态（通常为 RUNNING）
 * @param chatMode           聊天模式
 * @param selectedDocumentId 锁定的文档ID
 * @param selectedDocumentName 锁定的文档名称
 */
private void upsertDialogue(String conversationId,
                            ChatSessionStatus dialogueStage,
                            ChatQueryMode chatMode,
                            Long selectedDocumentId,
                            String selectedDocumentName) {
        Objects.requireNonNull(chatMode, "chatMode 不能为空");
        SuperAgentChatDialogue dialogue = dialogueMapper.selectOne(
            activeDialogueByConversation(conversationId)
                .orderByDesc(SuperAgentChatDialogue::getId)
                .last("LIMIT 1")
        );

        if (dialogue == null) {
            // 分支 A：这个 conversationId 是全新的 → 插入一条 dialogue
            SuperAgentChatDialogue newDialogue = new SuperAgentChatDialogue();
            newDialogue.setId(uidGenerator.getUid());
            newDialogue.setConversationId(conversationId);
            newDialogue.setSessionStatus(dialogueStage.getCode());   // RUNNING
            newDialogue.setChatMode(chatMode.getCode());
            newDialogue.setSelectedDocumentId(selectedDocumentId);
            newDialogue.setSelectedDocumentName(selectedDocumentName);
            newDialogue.setStatus(BusinessStatus.YES.getCode());

            dialogueMapper.insert(newDialogue);
            return;
        }

        // 分支 B：dialogue 已存在 → 检查三个维度是否有变化
        boolean stageChanged = !dialogueStage.equals(ChatSessionStatus.fromCode(dialogue.getSessionStatus()));
        boolean chatModeChanged = !Objects.equals(chatMode.getCode(), dialogue.getChatMode());
        boolean documentScopeChanged = !Objects.equals(selectedDocumentId, dialogue.getSelectedDocumentId())
            || !Objects.equals(safeText(selectedDocumentName), safeText(dialogue.getSelectedDocumentName()));

        if (stageChanged || chatModeChanged || documentScopeChanged) {
            // 有任一维度变化 → UPDATE
            SuperAgentChatDialogue updateDialogue = new SuperAgentChatDialogue();
            updateDialogue.setId(dialogue.getId());
            updateDialogue.setSessionStatus(dialogueStage.getCode());
            updateDialogue.setChatMode(chatMode.getCode());
            updateDialogue.setSelectedDocumentId(selectedDocumentId);
            updateDialogue.setSelectedDocumentName(selectedDocumentName);
            dialogueMapper.updateById(updateDialogue);
        }
        // 没变化 → 什么都不做，省一次 UPDATE
    }

    private ChatQueryMode resolveChatMode(SuperAgentChatDialogue dialogue) {
        if (dialogue == null || dialogue.getChatMode() == null) {
            throw new IllegalStateException("会话记录缺少 chatMode，当前教学版项目要求数据库使用最新结构");
        }
        return ChatQueryMode.fromCode(dialogue.getChatMode());
    }

    private Map<String, List<ConversationExchangeView>> loadExchangeViews(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }

        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .in(SuperAgentChatExchange::getConversationId, conversationIds)

                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getConversationId)
                .orderByAsc(SuperAgentChatExchange::getId)
        );

        Map<String, List<ConversationExchangeView>> exchangeViewsByConversation = new LinkedHashMap<>();
        for (SuperAgentChatExchange exchange : exchanges) {

            exchangeViewsByConversation.computeIfAbsent(exchange.getConversationId(), key -> new ArrayList<>())
                .add(toExchangeView(exchange));
        }
        return exchangeViewsByConversation;
    }

    private void applySessionPageFilters(LambdaQueryWrapper<SuperAgentChatDialogue> wrapper,
                                         String keyword,
                                         ChatQueryMode chatMode,
                                         ChatTurnStatus latestTurnStatus) {
        if (wrapper == null) {
            return;
        }
        if (chatMode != null) {
            wrapper.eq(SuperAgentChatDialogue::getChatMode, chatMode.getCode());
        }
        if (StrUtil.isNotBlank(keyword)) {
            String likeKeyword = "%" + keyword.trim() + "%";
            wrapper.and(query -> query
                .like(SuperAgentChatDialogue::getConversationId, keyword.trim())
                .or()
                .like(SuperAgentChatDialogue::getSelectedDocumentName, keyword.trim())
                .or()
                .apply(
                    "EXISTS (SELECT 1 FROM super_agent_chat_exchange e WHERE e.dialogue_code = super_agent_chat_dialogue.dialogue_code"
                        + " AND (e.user_prompt LIKE {0} OR e.reply_content LIKE {0} OR e.finish_note LIKE {0}))",
                    likeKeyword
                )
            );
        }
        if (latestTurnStatus == null) {
            return;
        }
        if (latestTurnStatus == ChatTurnStatus.RUNNING) {
            wrapper.eq(SuperAgentChatDialogue::getSessionStatus, ChatSessionStatus.RUNNING.getCode());
            return;
        }
        wrapper.eq(SuperAgentChatDialogue::getSessionStatus, ChatSessionStatus.IDLE.getCode());
        wrapper.apply(
            "EXISTS (SELECT 1 FROM super_agent_chat_exchange e"
                + " WHERE e.dialogue_code = super_agent_chat_dialogue.dialogue_code"
                + " AND e.id = (SELECT latest.id FROM super_agent_chat_exchange latest"
                + " WHERE latest.dialogue_code = super_agent_chat_dialogue.dialogue_code"
                + " ORDER BY latest.create_time DESC, latest.id DESC LIMIT 1)"
                + " AND e.exchange_state = {0})",
            latestTurnStatus.getCode()
        );
    }

    private Map<String, ConversationExchangeView> loadLatestExchangeMap(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .in(SuperAgentChatExchange::getConversationId, conversationIds)
                .orderByDesc(SuperAgentChatExchange::getCreateTime)
                .orderByDesc(SuperAgentChatExchange::getId)
        );
        if (exchanges == null || exchanges.isEmpty()) {
            return Map.of();
        }

        Map<String, ConversationExchangeView> latestExchangeMap = new LinkedHashMap<>();
        for (SuperAgentChatExchange exchange : exchanges) {
            if (exchange == null || latestExchangeMap.containsKey(exchange.getConversationId())) {
                continue;
            }
            latestExchangeMap.put(exchange.getConversationId(), toExchangeView(exchange));
        }
        return latestExchangeMap;
    }

    private List<ConversationExchangeView> selectConversationExchanges(LambdaQueryWrapper<SuperAgentChatExchange> queryWrapper) {
        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(queryWrapper);
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }
        List<ConversationExchangeView> result = new ArrayList<>(exchanges.size());
        for (SuperAgentChatExchange exchange : exchanges) {
            result.add(toExchangeView(exchange));
        }
        return result;
    }

    private ConversationExchangeView toExchangeView(SuperAgentChatExchange exchange) {

        return new ConversationExchangeView(
            exchange.getId(),
            safeText(exchange.getQuestion()),
            safeText(exchange.getAnswer()),
            readStringList(exchange.getThinkingSteps()),
            readReferenceList(exchange.getReferenceList()),
            readStringList(exchange.getRecommendationList()),
            readStringList(exchange.getUsedToolList()),
            readDebugTrace(exchange.getDebugTraceJson()),
            ChatTurnStatus.fromCode(exchange.getTurnStatus()),
            safeText(exchange.getErrorMessage()),
            exchange.getFirstResponseTimeMs(),
            exchange.getTotalResponseTimeMs(),
            exchange.getCreateTime(),
            exchange.getEditTime()
        );
    }

    private List<String> readStringList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {

            return objectMapper.readValue(json, STRING_LIST_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析字符串列表失败", exception);
        }
    }

    private List<SearchReference> readReferenceList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {

            return objectMapper.readValue(json, REFERENCE_LIST_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析引用来源列表失败", exception);
        }
    }

    private ChatDebugTrace readDebugTrace(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {

            return objectMapper.readValue(json, DEBUG_TRACE_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析调试轨迹失败", exception);
        }
    }

    private String writeJson(Object value) {
        try {

            return objectMapper.writeValueAsString(value != null ? value : List.of());
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化会话字段失败", exception);
        }
    }

    private String writeNullableJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化可空会话字段失败", exception);
        }
    }

    private Instant toInstant(Date date) {

        return date != null ? date.toInstant() : null;
    }

    private String safeText(String text) {

        return text != null ? text : "";
    }

    private LambdaQueryWrapper<SuperAgentChatDialogue> activeDialogueByConversation(String conversationId) {

        return new LambdaQueryWrapper<SuperAgentChatDialogue>()
            .eq(SuperAgentChatDialogue::getConversationId, conversationId);
    }

    private LambdaQueryWrapper<SuperAgentChatExchange> exchangesByConversation(String conversationId) {

        return new LambdaQueryWrapper<SuperAgentChatExchange>()
            .eq(SuperAgentChatExchange::getConversationId, conversationId);
    }

    private int toInt(Long count) {

        return count == null ? 0 : count.intValue();
    }
}
