package org.javaup.ai.manage.service.impl;

// ────────────── 导入工具类 ──────────────
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;                       // 百度 UID 生成器（分布式唯一 ID）
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;  // MyBatis-Plus 查询条件
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper; // MyBatis-Plus 更新条件
import jakarta.annotation.Resource;                           // Jakarta 资源注入
import lombok.AllArgsConstructor;                              // Lombok：全参数构造函数
import lombok.extern.slf4j.Slf4j;                             // Lombok：日志
import org.javaup.ai.manage.data.SuperAgentDocument;          // 文档实体
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;     // 切块实体
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock; // 父块实体
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan; // 策略方案实体
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;  // 策略步骤实体
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode; // 结构节点实体
import org.javaup.ai.manage.data.SuperAgentDocumentTask;      // 任务实体
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;       // 切块 Mapper
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;            // 文档 Mapper
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper; // 父块 Mapper
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper; // 策略方案 Mapper
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper; // 策略步骤 Mapper
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;         // 任务 Mapper
import org.javaup.ai.manage.service.DocumentAsyncProcessService;         // 异步处理服务接口
import org.javaup.ai.manage.service.DocumentNavigationIndexService;      // 导航索引服务
import org.javaup.ai.manage.service.DocumentParserService;               // 文档解析服务
import org.javaup.ai.manage.service.DocumentProfileService;              // 文档画像服务
import org.javaup.ai.manage.service.DocumentStorageService;              // 文档存储服务（MinIO）
import org.javaup.ai.manage.service.DocumentStrategyService;             // 策略推荐服务
import org.javaup.ai.manage.service.DocumentStructureGraphProjectionService; // 结构图投影服务
import org.javaup.ai.manage.service.DocumentStructureNodeService;        // 结构节点服务
import org.javaup.ai.manage.service.DocumentTaskLogService;              // 任务日志服务
import org.javaup.ai.manage.service.DocumentVectorGateway;               // 向量存储网关（PG）
import org.javaup.ai.manage.service.keyword.DocumentKeywordSearchGateway; // 关键词检索网关（ES）
import org.javaup.ai.manage.support.ChunkCandidate;          // 切块候选
import org.javaup.ai.manage.support.DocumentAnalysisResult;  // 文档解析结果
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;        // 策略方案草稿
import org.javaup.ai.manage.support.DocumentStrategyStepDraft;        // 策略步骤草稿
import org.javaup.ai.manage.support.ParentBlockCandidate;    // 父块候选
import org.javaup.enums.BusinessStatus;                      // 业务状态枚举（YES/NO）
import org.javaup.enums.DocumentChunkSourceTypeEnum;         // 切块来源类型
import org.javaup.enums.DocumentFileTypeEnum;                // 文件类型枚举
import org.javaup.enums.DocumentIndexStatusEnum;             // 索引状态枚举
import org.javaup.enums.DocumentLogLevelEnum;                // 日志级别枚举
import org.javaup.enums.DocumentOperatorTypeEnum;            // 操作者类型枚举
import org.javaup.enums.DocumentParseStatusEnum;             // 解析状态枚举
import org.javaup.enums.DocumentPlanSourceEnum;              // 方案来源枚举
import org.javaup.enums.DocumentPlanStatusEnum;              // 方案状态枚举
import org.javaup.enums.DocumentStrategyExecuteStatusEnum;   // 策略执行状态枚举
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;    // 策略管线类型枚举
import org.javaup.enums.DocumentStrategyStatusEnum;          // 策略状态枚举
import org.javaup.enums.DocumentTaskEventTypeEnum;           // 任务事件类型枚举
import org.javaup.enums.DocumentTaskStageEnum;               // 任务阶段枚举
import org.javaup.enums.DocumentTaskStatusEnum;              // 任务状态枚举
import org.javaup.enums.DocumentVectorStatusEnum;            // 向量状态枚举
import org.javaup.enums.DocumentVectorStoreTypeEnum;         // 向量存储类型枚举
import org.springframework.beans.factory.ObjectProvider;     // Spring Bean 延迟获取器
import org.springframework.stereotype.Service;               // Spring 服务注解

import java.util.ArrayList;        // 动态数组
import java.util.Comparator;       // 比较器（排序）
import java.util.Date;             // 日期对象
import java.util.LinkedHashMap;    // 有序哈希表
import java.util.List;             // 列表接口
import java.util.Map;              // 映射接口

/**
 * 文档异步处理服务实现 — 文档流水线的异步执行引擎。
 * <p>
 * 该类负责两大异步处理入口：
 * <ol>
 *   <li><b>handleParseRoute</b>：文档上传后的"解析 + 策略推荐"阶段
 *       （被 {@code DocumentKafkaConsumer.consumeParseRoute} 调用）</li>
 *   <li><b>handleIndexBuild</b>：策略确认后的"切块 + 向量化 + 入库"阶段
 *       （被 {@code DocumentKafkaConsumer.consumeIndexBuild} 调用）</li>
 * </ol>
 * <p>
 * === 流水线概览 ===
 * <pre>
 *   上传 → handleParseRoute（解析+策略推荐）
 *                       ↓
 *                   用户确认策略
 *                       ↓
 *              handleIndexBuild（切块+向量化+入库）
 * </pre>
 */
@Slf4j      // Lombok：生成 log 字段
@AllArgsConstructor  // Lombok：为所有 final 字段生成构造函数（Spring 自动注入）
@Service    // 声明为 Spring 服务
public class DocumentAsyncProcessServiceImpl implements DocumentAsyncProcessService {

    // ═══════════════════════════════════════════════════════════
    //  依赖注入（所有 final 字段由 @AllArgsConstructor 注入）
    // ═══════════════════════════════════════════════════════════

    /** 文档表 Mapper — 读写 super_agent_document 表 */
    private final SuperAgentDocumentMapper documentMapper;

    /** 策略方案表 Mapper — 读写 super_agent_document_strategy_plan 表 */
    private final SuperAgentDocumentStrategyPlanMapper planMapper;

    /** 策略步骤表 Mapper — 读写 super_agent_document_strategy_step 表 */
    private final SuperAgentDocumentStrategyStepMapper stepMapper;

    /** 任务表 Mapper — 读写 super_agent_document_task 表 */
    private final SuperAgentDocumentTaskMapper taskMapper;

    /** 父块表 Mapper — 读写 super_agent_document_parent_block 表 */
    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    /** 切块表 Mapper — 读写 super_agent_document_chunk 表 */
    private final SuperAgentDocumentChunkMapper chunkMapper;

    /** 文档存储服务 — MinIO 文件的上传、下载、删除 */
    private final DocumentStorageService storageService;

    /** 文档解析服务 — 调用 Tika 解析 PDF/Office/HTML/TXT 等文件格式 */
    private final DocumentParserService parserService;

    /** 策略推荐服务 — 根据解析结果生成切块策略方案 */
    private final DocumentStrategyService strategyService;

    /** 结构节点服务 — 存储和管理章节结构树 */
    private final DocumentStructureNodeService structureNodeService;

    /** 任务日志服务 — 记录每个任务的阶段变更、事件和错误信息 */
    private final DocumentTaskLogService taskLogService;

    /** 向量存储网关 — 将切块向量写入 PostgreSQL PGVector */
    private final DocumentVectorGateway vectorGateway;

    /** 关键词检索网关（延迟获取）— 将切块写入 Elasticsearch 做关键词索引 */
    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    /** 导航索引服务（延迟获取）— 将章节结构同步到 ES 导航索引 */
    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;

    /** 结构图投影服务（延迟获取）— 将章节结构投影到 Neo4j 图数据库 */
    private final ObjectProvider<DocumentStructureGraphProjectionService> graphProjectionServiceProvider;

    /** 文档画像服务 — 根据解析结果和结构树生成文档摘要/标签等画像数据 */
    private final DocumentProfileService documentProfileService;

    /** 分布式唯一 ID 生成器 — 基于百度 UID 算法，生成全局唯一的 Long 类型 ID */
    @Resource
    private UidGenerator uidGenerator;

    // ═══════════════════════════════════════════════════════════
    //  流程入口 ①：解析 + 策略推荐（handleParseRoute）
    //  被 Kafka 消费者 consumeParseRoute 调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 异步处理"文档解析 + 策略推荐"流水线 — 文档上传后的第一步异步处理。
     * <p>
     * 调用时机：用户在界面上传文档后，Kafka 消息触发此方法。
     * <p>
     * === 执行流程（8个阶段）===
     * <ol>
     *   <li><b>前置校验</b>：检查文档和任务记录是否存在，不存在则静默返回</li>
     *   <li><b>状态初始化</b>：设置任务为 RUNNING，文档为 PARSING</li>
     *   <li><b>内容解析</b>：从 MinIO 下载原始文件 → Tika 解析为纯文本 → 上传解析结果</li>
     *   <li><b>结构抽取</b>：从纯文本中提取章节层级树（标题→子标题→正文边界）</li>
     *   <li><b>导航同步</b>：将章节树同步到 ES 导航索引和 Neo4j 结构图</li>
     *   <li><b>文档画像</b>：生成文档摘要、标签、关键词等画像数据</li>
     *   <li><b>策略推荐</b>：根据文档特征（字数、结构、质量）推荐切块方案</li>
     *   <li><b>结果持久化</b>：保存策略方案和步骤，更新文档状态为 PARSE_SUCCESS + RECOMMENDED</li>
     * </ol>
     * <p>
     * 异常处理：任何步骤抛出异常都会将文档状态设为 PARSE_FAILED，任务设为 FAILED，
     * 同时保存失败日志，不会影响后续其他文档的异步处理。
     *
     * @param documentId 文档 ID
     * @param taskId     异步任务 ID
     */
    @Override
    public void handleParseRoute(Long documentId, Long taskId) {

        // ── Step 1: 前置校验 ─────────────────────────────────────────
        // 查询文档和任务记录，任一不存在则无法继续，静默返回
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (document == null || task == null) {
            log.warn("解析任务对应的文档或任务不存在，documentId={}, taskId={}", documentId, taskId);
            return;
        }

        // ── Step 2: 记录开始时间，用于后续计算耗时 ────────────────────
        Date startTime = new Date();

        try {
            // ═══════════════════════════════════════════════════════════
            //  阶段 ②：状态初始化
            // ═══════════════════════════════════════════════════════════
            // 将任务状态设为 RUNNING，当前阶段设为 CONTENT_PARSE（内容解析）
            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CONTENT_PARSE.getCode());
            task.setStartTime(startTime);       // 记录任务开始时间
            taskMapper.updateById(task);

            // 将文档解析状态设为 PARSING（解析中）
            document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
            documentMapper.updateById(document);

            // 记录"开始解析"的日志
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),   // 阶段=内容解析
                DocumentTaskEventTypeEnum.START.getCode(),        // 事件=开始
                DocumentLogLevelEnum.INFO.getCode(),             // 日志级别=INFO
                DocumentOperatorTypeEnum.SYSTEM.getCode(),       // 操作者=系统
                null,                                             // 操作人 ID=null
                "开始解析文档内容。",                              // 日志内容
                Map.of("objectName", document.getObjectName()));  // 附带信息=MinIO 对象路径

            // ═══════════════════════════════════════════════════════════
            //  阶段 ③：内容解析
            // ═══════════════════════════════════════════════════════════
            // 从 MinIO 下载原始文件的二进制内容
            byte[] fileBytes = storageService.downloadObject(document.getObjectName());

            // 调用 TikaDocumentParserService 解析：
            // - PDF/Office → Tika 全功能解析
            // - TXT/MD → UTF-8 直接读取
            // - HTML → Tika 剥离标签提取正文
            DocumentAnalysisResult analysisResult = parserService.parse(
                fileBytes,
                document.getOriginalFileName(),        // 原始文件名
                document.getMimeType(),                 // MIME 类型
                DocumentFileTypeEnum.getRc(document.getFileType()));  // 文件类型枚举

            // 将清洗后的纯文本上传到 MinIO（持久化存储，供后续切块阶段使用）
            String parseTextPath = storageService.uploadParsedText(documentId, analysisResult.getParsedText());

            // ═══════════════════════════════════════════════════════════
            //  阶段 ④：结构抽取
            // ═══════════════════════════════════════════════════════════
            // 将章节树节点存入 MySQL，替换该文档之前解析生成的结构节点
            List<SuperAgentDocumentStructureNode> structureNodes = structureNodeService.replaceDocumentNodes(
                documentId,
                taskId,
                analysisResult.getStructureNodes()      // DocumentStructureNodeCandidate 列表
            );
            int structureNodeCount = structureNodes.size();  // 章节节点总数

            // ═══════════════════════════════════════════════════════════
            //  阶段 ⑤：导航同步
            // ═══════════════════════════════════════════════════════════
            // 将章节树同步到外部索引服务（ES 导航索引 + Neo4j 结构图）
            syncNavigationArtifacts(documentId, taskId, structureNodes);

            // ═══════════════════════════════════════════════════════════
            //  阶段 ⑥：文档画像
            // ═══════════════════════════════════════════════════════════
            // 根据解析结果和结构树，生成文档摘要、标签、关键词等画像数据
            documentProfileService.generateProfile(documentId, analysisResult, structureNodes);

            // 记录"解析完成"的日志（附带统计数据：字符数、token 数、结构等级等）
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "文档解析完成。",
                Map.of(
                    "charCount", analysisResult.getCharCount(),          // 总字符数
                    "tokenCount", analysisResult.getTokenCount(),        // 估算 token 数
                    "structureLevel", analysisResult.getStructureLevel(),// 结构等级
                    "contentQualityLevel", analysisResult.getContentQualityLevel(),  // 内容质量
                    "structureNodeCount", structureNodeCount              // 章节节点数
                ));

            // ═══════════════════════════════════════════════════════════
            //  阶段 ⑦：策略推荐
            // ═══════════════════════════════════════════════════════════
            // 更新任务阶段为 STRATEGY_ROUTE（策略路由）
            task.setCurrentStage(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode());
            taskMapper.updateById(task);

            // 调用策略推荐服务，根据文档特征生成切块方案
            DocumentStrategyPlanDraft planDraft = strategyService.recommendStrategy(document, analysisResult);

            // 生成方案 ID 和版本号
            Long planId = uidGenerator.getUid();               // 分布式唯一 ID
            int planVersion = getNextPlanVersion(documentId);   // 当前文档的方案版本号（递增）

            // ═══════════════════════════════════════════════════════════
            //  阶段 ⑧：结果持久化 — 保存策略方案
            // ═══════════════════════════════════════════════════════════
            // 创建策略方案记录
            SuperAgentDocumentStrategyPlan plan = new SuperAgentDocumentStrategyPlan();
            plan.setId(planId);                                              // 方案 ID
            plan.setDocumentId(documentId);                                  // 文档 ID
            plan.setPlanVersion(planVersion);                                // 版本号
            plan.setPlanSource(DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode());  // 来源=系统推荐
            plan.setPlanStatus(DocumentPlanStatusEnum.WAIT_CONFIRM.getCode());       // 状态=待用户确认
            plan.setStrategyCount(planDraft.getParentSteps().size()          // 策略总步数
                + planDraft.getChildSteps().size());
            plan.setStrategySnapshot(planDraft.getStrategySnapshot());       // 策略快照（JSON）
            plan.setRecommendReason(planDraft.getRecommendReason());         // 推荐理由
            plan.setStatus(BusinessStatus.YES.getCode());                    // 有效状态
            planMapper.insert(plan);

            // 保存父级步骤（如"结构感知切块"）
            for (int index = 0; index < planDraft.getParentSteps().size(); index++) {
                DocumentStrategyStepDraft draft = planDraft.getParentSteps().get(index);
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());                              // 步骤 ID
                step.setPlanId(planId);                                         // 所属方案 ID
                step.setDocumentId(documentId);                                 // 文档 ID
                step.setPipelineType(draft.getPipelineType());                   // 管线类型（PARENT/CHILD）
                step.setStepNo(index + 1);                                      // 步骤序号
                step.setStrategyType(draft.getStrategyType());                   // 策略类型
                step.setStrategyRole(draft.getStrategyRole());                   // 策略角色
                step.setSourceType(draft.getSourceType());                       // 来源类型
                step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());  // 待执行
                step.setRecommendReason(draft.getRecommendReason());             // 推荐理由
                step.setStatus(BusinessStatus.YES.getCode());
                stepMapper.insert(step);
            }

            // 保存子级步骤（如"语义分块"）
            for (int index = 0; index < planDraft.getChildSteps().size(); index++) {
                DocumentStrategyStepDraft draft = planDraft.getChildSteps().get(index);
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());
                step.setPlanId(planId);
                step.setDocumentId(documentId);
                step.setPipelineType(draft.getPipelineType());
                step.setStepNo(index + 1);
                step.setStrategyType(draft.getStrategyType());
                step.setStrategyRole(draft.getStrategyRole());
                step.setSourceType(draft.getSourceType());
                step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
                step.setRecommendReason(draft.getRecommendReason());
                step.setStatus(BusinessStatus.YES.getCode());
                stepMapper.insert(step);
            }

            // ═══════════════════════════════════════════════════════════
            //  最终：更新文档状态
            // ═══════════════════════════════════════════════════════════
            // 解析成功 + 策略已推荐，等待用户在界面上确认方案
            document.setParseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode());     // 解析成功
            document.setStrategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode()); // 已推荐
            document.setCharCount(analysisResult.getCharCount());          // 字符数
            document.setTokenCount(analysisResult.getTokenCount());        // token 数
            document.setStructureLevel(analysisResult.getStructureLevel());  // 结构等级
            document.setContentQualityLevel(analysisResult.getContentQualityLevel());  // 内容质量
            document.setParseTextPath(parseTextPath);                      // 解析文本路径（MinIO）
            document.setParseErrorMsg(null);                               // 清空错误信息
            document.setCurrentPlanId(planId);                             // 当前方案 ID
            document.setLastParseTaskId(taskId);                           // 最后一次解析任务 ID
            document.setStructureNodeCount(structureNodeCount);            // 章节节点数
            documentMapper.updateById(document);

            // 标记任务为成功完成
            finishTaskSuccess(task, DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(), startTime);

            // 记录"策略已推荐"的日志（附方案统计信息）
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(),
                DocumentTaskEventTypeEnum.RECOMMEND_STRATEGY.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "系统已生成推荐策略。",
                detail("planId", planId,
                    "strategySnapshot", planDraft.getStrategySnapshot(),
                    "parentStepCount", planDraft.getParentSteps().size(),
                    "childStepCount", planDraft.getChildSteps().size(),
                    "structureNodeCount", structureNodeCount,
                    "recommendReason", planDraft.getRecommendReason()));
        }
        catch (Exception exception) {
            // ═══════════════════════════════════════════════════════════
            //  异常处理：任何步骤报错 → 文档状态设为解析失败
            // ═══════════════════════════════════════════════════════════
            log.error("异步解析文档失败，documentId={}, taskId={}", documentId, taskId, exception);

            // 文档：PARSE_FAILED（解析失败），保留错误信息
            document.setParseStatus(DocumentParseStatusEnum.PARSE_FAILED.getCode());
            document.setParseErrorMsg(exception.getMessage());
            documentMapper.updateById(document);

            // 任务：FAILED（失败），记录错误
            failTask(task, startTime, exception, DocumentTaskStageEnum.CONTENT_PARSE.getCode());

            // 记录失败日志
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "文档解析失败。",
                detail("error", exception.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  流程入口 ②：切块 + 向量化 + 入库（handleIndexBuild）
    //  被 Kafka 消费者 consumeIndexBuild 调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 异步处理"索引构建"流水线 — 用户确认策略后的第二步异步处理。
     * <p>
     * 调用时机：用户在界面上确认切块方案后，Kafka 消息触发此方法。
     * <p>
     * === 执行流程（8个阶段）===
     * <ol>
     *   <li><b>前置校验</b>：检查文档、任务、方案是否存在</li>
     *   <li><b>状态初始化</b>：任务 RUNNING，文档 BUILDING，步骤 EXECUTING</li>
     *   <li><b>切块执行</b>：按策略方案对纯文本执行切块（按结构/语义/长度切分）</li>
     *   <li><b>后处理</b>：过滤空白块，统计父子块数量</li>
     *   <li><b>实体构建</b>：将切块候选转为 MySQL 实体（parent_block + chunk）</li>
     *   <li><b>向量化</b>：调用 EmbeddingModel 生成向量 → 写入 PGVector</li>
     *   <li><b>关键词索引</b>：写入 Elasticsearch 关键词索引（可选）</li>
     *   <li><b>完成收尾</b>：更新文档状态为 BUILD_SUCCESS，任务为 SUCCESS</li>
     * </ol>
     *
     * @param documentId 文档 ID
     * @param taskId     异步任务 ID
     * @param planId     已确认的策略方案 ID
     */
    @Override
    public void handleIndexBuild(Long documentId, Long taskId, Long planId) {

        // ── Step 1: 前置校验 ─────────────────────────────────────────
        // 查询文档、任务、方案记录，任一不存在则无法继续
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(planId);
        if (document == null || task == null || plan == null) {
            log.warn("索引任务对应的数据不存在，documentId={}, taskId={}, planId={}",
                documentId, taskId, planId);
            return;
        }

        Date startTime = new Date();

        // 查询策略步骤列表（按管线类型排序：先 PARENT 后 CHILD）
        List<SuperAgentDocumentStrategyStep> stepList = listSteps(planId);

        try {
            // ═══════════════════════════════════════════════════════════
            //  阶段 ②：状态初始化
            // ═══════════════════════════════════════════════════════════
            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());  // 阶段=切块执行
            task.setStartTime(startTime);
            taskMapper.updateById(task);

            document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());  // 文档=构建中
            documentMapper.updateById(document);

            // 所有策略步骤的状态设为 EXECUTING（执行中）
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTING.getCode());

            // 记录"开始切块"日志
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始执行切块流水线。",
                Map.of("strategySnapshot", plan.getStrategySnapshot()));

            // ═══════════════════════════════════════════════════════════
            //  阶段 ③：切块执行
            // ═══════════════════════════════════════════════════════════
            // 从 MinIO 下载之前解析阶段保存的纯文本
            String parsedText = storageService.downloadText(document.getParseTextPath());

            // 调用策略服务执行切块：
            // - 按章节结构（structureNodes）切分
            // - 按长度递归切分（recursiveMaxChars=800）
            // - 按语义相似度切分（semanticMaxChars=700）
            List<ParentBlockCandidate> parentBlockCandidateList =
                strategyService.buildParentBlocks(document, plan, stepList, parsedText);

            // 策略步骤执行成功
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_SUCCESS.getCode());

            // 记录"切块完成"日志
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "切块执行完成。",
                Map.of(
                    "parentCount", parentBlockCandidateList.size(),
                    "childCount", countChildCandidates(parentBlockCandidateList)
                ));

            // ═══════════════════════════════════════════════════════════
            //  阶段 ④：后处理 — 过滤空白父块和空白子块
            // ═══════════════════════════════════════════════════════════
            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode());
            taskMapper.updateById(task);

            // 过滤条件：
            // - parentBlock 非空且 text 不为 blank
            // - parentBlock 的 childChunks 非空且至少有一个 chunk 的 text 不为 blank
            List<ParentBlockCandidate> finalParentBlockList = parentBlockCandidateList.stream()
                .filter(item -> item != null
                    && StrUtil.isNotBlank(item.getText())
                    && item.getChildChunks() != null
                    && item.getChildChunks().stream()
                        .anyMatch(child -> StrUtil.isNotBlank(child.getText())))
                .toList();

            // 记录"后处理完成"日志
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "切块后处理完成。",
                Map.of(
                    "parentCount", finalParentBlockList.size(),
                    "childCount", countChildCandidates(finalParentBlockList)
                ));

            // ═══════════════════════════════════════════════════════════
            //  阶段 ⑤：实体构建 — 候选 → MySQL 实体
            // ═══════════════════════════════════════════════════════════
            ParentChildEntityBundle entityBundle = buildParentChildEntities(
                documentId, taskId, planId, finalParentBlockList);
            List<SuperAgentDocumentParentBlock> parentBlockEntityList = entityBundle.parentBlocks();
            List<SuperAgentDocumentChunk> chunkEntityList = entityBundle.childChunks();

            // 批量插入 MySQL
            for (SuperAgentDocumentParentBlock parentBlock : parentBlockEntityList) {
                parentBlockMapper.insert(parentBlock);
            }
            for (SuperAgentDocumentChunk chunk : chunkEntityList) {
                chunkMapper.insert(chunk);
            }

            // ═══════════════════════════════════════════════════════════
            //  阶段 ⑥：向量化 — 生成 embedding → PGVector
            // ═══════════════════════════════════════════════════════════
            task.setCurrentStage(DocumentTaskStageEnum.VECTORIZE.getCode());
            taskMapper.updateById(task);

            // 记录"开始向量化"日志
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.VECTORIZE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始执行向量化。",
                detail("chunkCount", chunkEntityList.size(),
                    "embeddingBatchSize", DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT,
                    "embeddingBatchCount",
                    (chunkEntityList.size() + DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT - 1)
                        / DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT,
                    "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg(),
                    "parentCount", parentBlockEntityList.size()));

            // 核心步骤：调用 vectorGateway 执行向量化 + PG 入库
            // 内部逻辑：
            //   1. 获取 EmbeddingModel（Spring AI 的 OpenAI 兼容客户端）
            //   2. 按 batchSize=10 分批调用 text-embedding-v4 生成向量
            //   3. 通过 JDBC batch upsert 写入 super_agent_document_embedding 表
            //   4. 更新 MySQL chunk 的 vectorStatus = VECTOR_SUCCESS
            vectorGateway.vectorize(chunkEntityList);

            // ═══════════════════════════════════════════════════════════
            //  阶段 ⑦：关键词索引 → Elasticsearch（可选）
            // ═══════════════════════════════════════════════════════════
            DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
            if (keywordSearchGateway != null) {
                // 将切块写入 ES 关键词索引，用于 BM25/词法检索
                keywordSearchGateway.indexChunks(chunkEntityList);
            }

            // 更新 MySQL 中的 chunk 记录（vectorStatus 等字段已被 vectorize 修改）
            for (SuperAgentDocumentChunk chunk : chunkEntityList) {
                chunkMapper.updateById(chunk);
            }

            // 记录"向量化完成"日志
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.VECTORIZE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "向量化完成。",
                detail("chunkCount", chunkEntityList.size(),
                    "embeddingBatchSize", DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT,
                    "embeddingBatchCount",
                    (chunkEntityList.size() + DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT - 1)
                        / DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT,
                    "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg(),
                    "parentCount", parentBlockEntityList.size()));

            // ═══════════════════════════════════════════════════════════
            //  阶段 ⑧：完成收尾
            // ═══════════════════════════════════════════════════════════
            task.setCurrentStage(DocumentTaskStageEnum.STORE_COMPLETE.getCode());
            taskMapper.updateById(task);

            // 方案状态改为 EXECUTED（已执行）
            plan.setPlanStatus(DocumentPlanStatusEnum.EXECUTED.getCode());
            planMapper.updateById(plan);

            // 文档索引状态改为 BUILD_SUCCESS（构建成功）
            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
            document.setLastIndexTaskId(taskId);
            documentMapper.updateById(document);

            // 标记任务成功
            finishTaskSuccess(task, DocumentTaskStageEnum.STORE_COMPLETE.getCode(), startTime);

            // 记录"索引构建完成"日志
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.STORE_COMPLETE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "索引构建完成。",
                Map.of("taskId", taskId,
                    "chunkCount", chunkEntityList.size(),
                    "parentCount", parentBlockEntityList.size()));
        }
        catch (Exception exception) {
            // ═══════════════════════════════════════════════════════════
            //  异常处理
            // ═══════════════════════════════════════════════════════════
            log.error("异步构建索引失败，documentId={}, taskId={}, planId={}",
                documentId, taskId, planId, exception);

            // 文档：BUILD_FAILED
            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
            documentMapper.updateById(document);

            // 批量更新切块状态为 VECTOR_FAILED
            chunkMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getTaskId, taskId)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .set(SuperAgentDocumentChunk::getVectorStatus, DocumentVectorStatusEnum.VECTOR_FAILED.getCode())
                .set(SuperAgentDocumentChunk::getVectorStoreType, DocumentVectorStoreTypeEnum.PG_VECTOR.getCode()));

            // 策略步骤状态改为 FAILED
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_FAILED.getCode());

            // 任务：FAILED
            failTask(task, startTime, exception, task.getCurrentStage());

            // 记录失败日志
            taskLogService.saveLog(taskId, documentId,
                task.getCurrentStage(),
                DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "索引构建失败。",
                detail("error", exception.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法：实体构建
    // ═══════════════════════════════════════════════════════════

    /**
     * 将切块候选列表转换为 MySQL 实体（ParentBlock + Chunk）— handleIndexBuild 的第⑤步。
     * <p>
     * 转换规则：
     * <ul>
     *   <li>每个 ParentBlockCandidate → 一个 SuperAgentDocumentParentBlock（父块）</li>
     *   <li>每个 ChunkCandidate → 一个 SuperAgentDocumentChunk（子切块）</li>
     *   <li>子切块从属于父块（通过 parentBlockId 关联）</li>
     *   <li>chunk_no 全局递增（跨父块连续编号）</li>
     *   <li>子切块初始化 vectorStatus=WAIT_VECTOR（待向量化）</li>
     * </ul>
     *
     * @param documentId              文档 ID
     * @param taskId                  任务 ID
     * @param planId                  方案 ID
     * @param parentBlockCandidateList 过滤后的父块候选列表
     * @return 实体对（父块列表 + 子切块列表）
     */
    private ParentChildEntityBundle buildParentChildEntities(Long documentId,
                                                             Long taskId,
                                                             Long planId,
                                                             List<ParentBlockCandidate> parentBlockCandidateList) {
        // 创建父块和子切块的实体列表
        List<SuperAgentDocumentParentBlock> parentBlockEntityList = new ArrayList<>();
        List<SuperAgentDocumentChunk> chunkEntityList = new ArrayList<>();
        int globalChunkNo = 1;  // 全局切块编号（跨父块连续递增）

        // 遍历每个父块候选
        for (int parentIndex = 0; parentIndex < parentBlockCandidateList.size(); parentIndex++) {
            ParentBlockCandidate parentCandidate = parentBlockCandidateList.get(parentIndex);
            // 跳过空父块
            if (parentCandidate == null || StrUtil.isBlank(parentCandidate.getText())) {
                continue;
            }

            // ── 创建父块实体 ──
            SuperAgentDocumentParentBlock parentBlock = new SuperAgentDocumentParentBlock();
            parentBlock.setId(uidGenerator.getUid());                    // 父块 ID
            parentBlock.setDocumentId(documentId);                       // 文档 ID
            parentBlock.setTaskId(taskId);                               // 任务 ID
            parentBlock.setPlanId(planId);                               // 方案 ID
            parentBlock.setParentNo(parentIndex + 1);                    // 父块序号（从 1 开始）
            parentBlock.setSourceType(parentCandidate.getSourceType() == null
                ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode()         // 默认=原文切块
                : parentCandidate.getSourceType());
            parentBlock.setSectionPath(parentCandidate.getSectionPath());    // 章节路径
            parentBlock.setStructureNodeId(parentCandidate.getStructureNodeId());  // 结构节点 ID
            parentBlock.setStructureNodeType(parentCandidate.getStructureNodeType());  // 结构节点类型
            parentBlock.setCanonicalPath(parentCandidate.getCanonicalPath());  // 规范路径
            parentBlock.setItemIndex(parentCandidate.getItemIndex());    // 列表项序号
            parentBlock.setParentText(parentCandidate.getText().trim()); // 父块正文
            parentBlock.setCharCount(parentCandidate.getText().length()); // 字符数
            parentBlock.setTokenCount(estimateTokenCount(parentCandidate.getText()));  // token 数
            parentBlock.setStatus(BusinessStatus.YES.getCode());

            // ── 遍历当前父块的子切块 ──
            int startChunkNo = globalChunkNo;  // 记录起始切块编号
            int childCount = 0;
            for (ChunkCandidate childCandidate : parentCandidate.getChildChunks()) {
                if (childCandidate == null || StrUtil.isBlank(childCandidate.getText())) {
                    continue;
                }
                // 创建子切块实体
                SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
                chunk.setId(uidGenerator.getUid());                      // 切块 ID
                chunk.setDocumentId(documentId);                         // 文档 ID
                chunk.setTaskId(taskId);                                 // 任务 ID
                chunk.setPlanId(planId);                                 // 方案 ID
                chunk.setParentBlockId(parentBlock.getId());             // 所属父块 ID
                chunk.setChunkNo(globalChunkNo++);                       // 全局切块编号（递增）
                chunk.setSourceType(childCandidate.getSourceType() == null
                    ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode()
                    : childCandidate.getSourceType());
                chunk.setSectionPath(StrUtil.blankToDefault(             // 章节路径
                    childCandidate.getSectionPath(), parentCandidate.getSectionPath()));
                chunk.setStructureNodeId(childCandidate.getStructureNodeId());
                chunk.setStructureNodeType(childCandidate.getStructureNodeType());
                chunk.setCanonicalPath(childCandidate.getCanonicalPath());
                chunk.setItemIndex(childCandidate.getItemIndex());
                chunk.setChunkText(childCandidate.getText().trim());     // 切块正文
                chunk.setCharCount(childCandidate.getText().length());   // 字符数
                chunk.setTokenCount(estimateTokenCount(childCandidate.getText()));  // token 数
                chunk.setVectorStatus(DocumentVectorStatusEnum.WAIT_VECTOR.getCode());  // 待向量化
                chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());  // 存储类型=PG
                chunk.setStatus(BusinessStatus.YES.getCode());
                chunkEntityList.add(chunk);
                childCount++;
            }

            // 更新父块统计信息
            parentBlock.setChildCount(childCount);                      // 子切块数量
            parentBlock.setStartChunkNo(childCount == 0 ? null : startChunkNo);  // 起始编号
            parentBlock.setEndChunkNo(childCount == 0 ? null : globalChunkNo - 1);  // 结束编号
            parentBlockEntityList.add(parentBlock);
        }

        // 返回实体对
        return new ParentChildEntityBundle(parentBlockEntityList, chunkEntityList);
    }

    /**
     * 统计所有父块候选中的子切块总数 — 用于日志输出。
     *
     * @param parentBlockCandidateList 父块候选列表
     * @return 非空子切块的总数
     */
    private int countChildCandidates(List<ParentBlockCandidate> parentBlockCandidateList) {
        // 空列表 → 0
        if (parentBlockCandidateList == null || parentBlockCandidateList.isEmpty()) {
            return 0;
        }
        int count = 0;
        // 遍历每个父块，累加其下非空子切块的数量
        for (ParentBlockCandidate candidate : parentBlockCandidateList) {
            if (candidate == null || candidate.getChildChunks() == null) {
                continue;
            }
            count += (int) candidate.getChildChunks().stream()
                .filter(child -> child != null && StrUtil.isNotBlank(child.getText()))
                .count();
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法：策略步骤操作
    // ═══════════════════════════════════════════════════════════

    /**
     * 批量更新策略步骤的执行状态 — handleIndexBuild 中多步调用。
     *
     * @param planId         方案 ID
     * @param executeStatus 目标执行状态
     */
    private void updateStepExecuteStatus(Long planId, Integer executeStatus) {
        // 批量更新：planId + status=YES 的所有步骤
        stepMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentDocumentStrategyStep::getExecuteStatus, executeStatus));
    }

    /**
     * 查询策略步骤列表 — 按管线类型和步骤号排序。
     * <p>
     * 排序规则：
     * <ol>
     *   <li>先按 pipelineType 排序：PARENT（主策略）→ CHILD（子策略）</li>
     *   <li>同 pipelineType 内按 stepNo 升序</li>
     *   <li>同 stepNo 内按 ID 升序</li>
     * </ol>
     *
     * @param planId 方案 ID
     * @return 排序后的步骤列表
     */
    private List<SuperAgentDocumentStrategyStep> listSteps(Long planId) {
        // 查询该方案下所有有效步骤
        List<SuperAgentDocumentStrategyStep> stepList = stepMapper.selectList(
            new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
                .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
                .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode()));

        // 排序
        return stepList.stream()
            .sorted(Comparator
                .comparingInt((SuperAgentDocumentStrategyStep step) ->
                    pipelineOrder(step.getPipelineType()))
                .thenComparing(SuperAgentDocumentStrategyStep::getStepNo)
                .thenComparing(SuperAgentDocumentStrategyStep::getId))
            .toList();
    }

    /**
     * 获取管线类型的排序值 — PARENT（主策略）排在 CHILD（子策略）前面。
     *
     * @param pipelineType 管线类型字符串
     * @return 排序值（0=PARENT 优先，1=CHILD 靠后）
     */
    private int pipelineOrder(String pipelineType) {
        // PARENT → 0（优先），其他（CHILD 等）→ 1（靠后）
        return DocumentStrategyPipelineTypeEnum.PARENT.getCode().equalsIgnoreCase(
            StrUtil.blankToDefault(pipelineType, "")
        ) ? 0 : 1;
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法：版本号管理
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取文档的下一个策略方案版本号 — 每次推荐策略时递增。
     * <p>
     * 查询该文档当前最大的版本号，返回 +1。
     * 如果之前没有方案，返回 1。
     *
     * @param documentId 文档 ID
     * @return 下一个版本号（1, 2, 3...）
     */
    private int getNextPlanVersion(Long documentId) {
        // 查询该文档的最大版本号
        List<SuperAgentDocumentStrategyPlan> planList = planMapper.selectList(
            new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
                .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId)
                .eq(SuperAgentDocumentStrategyPlan::getStatus, BusinessStatus.YES.getCode())
                .orderByDesc(SuperAgentDocumentStrategyPlan::getPlanVersion)
                .last("limit 1"));
        // 没有方案 → 1，有则 +1
        return planList.isEmpty() ? 1 : planList.get(0).getPlanVersion() + 1;
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法：任务状态管理
    // ═══════════════════════════════════════════════════════════

    /**
     * 标记任务成功完成 — 更新任务状态、完成时间、耗时。
     *
     * @param task      任务实体
     * @param stage     最终阶段
     * @param startTime 任务开始时间
     */
    private void finishTaskSuccess(SuperAgentDocumentTask task, Integer stage, Date startTime) {
        // 获取当前时间作为完成时间
        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.SUCCESS.getCode());       // 成功
        task.setCurrentStage(stage);                                        // 最终阶段
        task.setFinishTime(finishTime);                                     // 完成时间
        task.setCostMillis(finishTime.getTime() - startTime.getTime());     // 耗时（毫秒）
        task.setErrorCode(null);                                            // 清空错误
        task.setErrorMsg(null);
        taskMapper.updateById(task);
    }

    /**
     * 同步导航产物 — 将章节结构同步到外部索引和数据库。
     * <p>
     * 包括两个可选服务：
     * <ol>
     *   <li>ES 导航索引（DocumentNavigationIndexService）：
     *       将章节树写入 ES，用于文档内导航检索</li>
     *   <li>Neo4j 结构图投影（DocumentStructureGraphProjectionService）：
     *       将章节树投影到 Neo4j 图数据库，用于知识图谱查询</li>
     * </ol>
     * 任一服务不可用时静默跳过（通过 ObjectProvider 懒加载）。
     *
     * @param documentId    文档 ID
     * @param parseTaskId   解析任务 ID
     * @param structureNodes 结构节点列表
     */
    private void syncNavigationArtifacts(Long documentId,
                                         Long parseTaskId,
                                         List<SuperAgentDocumentStructureNode> structureNodes) {
        // ── 同步 ES 导航索引 ──
        log.info("开始同步导航产物: documentId={}, parseTaskId={}, structureNodeCount={}",
            documentId, parseTaskId,
            structureNodes == null ? 0 : structureNodes.size());
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService != null) {
            log.info("同步导航 ES 索引: documentId={}, parseTaskId={}", documentId, parseTaskId);
            navigationIndexService.reindexDocumentNodes(documentId, parseTaskId, structureNodes);
        } else {
            log.info("跳过导航 ES 索引同步，因为服务未启用: documentId={}, parseTaskId={}", documentId, parseTaskId);
        }

        // ── 同步 Neo4j 结构图投影 ──
        DocumentStructureGraphProjectionService graphProjectionService =
            graphProjectionServiceProvider.getIfAvailable();
        if (graphProjectionService != null && graphProjectionService.enabled()) {
            log.info("同步结构图投影: documentId={}, parseTaskId={}", documentId, parseTaskId);
            graphProjectionService.projectToGraph(documentId, parseTaskId);
        } else {
            log.info("跳过结构图投影，因为图服务未启用: documentId={}, parseTaskId={}", documentId, parseTaskId);
        }
    }

    /**
     * 标记任务失败 — 更新任务状态为 FAILED，记录错误信息。
     *
     * @param task         任务实体
     * @param startTime    任务开始时间
     * @param exception    异常对象
     * @param currentStage 失败时的阶段
     */
    private void failTask(SuperAgentDocumentTask task, Date startTime, Exception exception, Integer currentStage) {
        // 获取当前时间作为完成时间
        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());        // 失败
        task.setCurrentStage(currentStage);                                 // 当前阶段
        task.setFinishTime(finishTime);                                     // 完成时间
        task.setCostMillis(finishTime.getTime() - startTime.getTime());     // 耗时
        task.setErrorCode("TASK_FAILED");                                   // 错误码
        task.setErrorMsg(exception.getMessage());                           // 错误信息
        taskMapper.updateById(task);
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法：token 估算
    // ═══════════════════════════════════════════════════════════

    /**
     * 估算文本的 token 数 — 用于切块 token 预算控制。
     * <p>
     * 算法：中文字符数 + 英文单词数 + (非中文字符 / 4)
     *
     * @param text 输入文本
     * @return 估算的 token 数
     */
    private int estimateTokenCount(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        int chineseCount = 0;   // 中文字符计数
        int englishCount = 0;   // 英文单词计数

        // 统计中文字符
        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                chineseCount++;
            }
        }

        // 统计英文单词
        for (String word : text.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*")) {
                englishCount++;
            }
        }

        // 估算公式：中文字符 + 英文单词 + 剩余字符/4
        return chineseCount + englishCount + Math.max(1, (text.length() - chineseCount) / 4);
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法：日志详情构造
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建日志详情 Map — 将键值对数组转为 Map。
     * <p>
     * 用法：detail("key1", value1, "key2", value2, ...)
     *
     * @param keyValues 交替的键值对
     * @return 有序 Map
     */
    private Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detailMap = new LinkedHashMap<>();
        // 每两个参数为一组（key, value）
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            detailMap.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return detailMap;
    }

    /**
     * 父块-子切块实体对 — 内部记录类，用于 buildParentChildEntities 的返回值。
     *
     * @param parentBlocks 父块实体列表
     * @param childChunks  子切块实体列表
     */
    private record ParentChildEntityBundle(
        List<SuperAgentDocumentParentBlock> parentBlocks,
        List<SuperAgentDocumentChunk> childChunks
    ) {
    }
}
