package org.javaup.ai.manage.service.keyword;

// ────────────── 导入工具类 ──────────────
import cn.hutool.core.collection.CollUtil;                     // Hutool 集合工具
import cn.hutool.core.util.StrUtil;                             // Hutool 字符串工具
import co.elastic.clients.elasticsearch.ElasticsearchClient;    // Elasticsearch 官方 Java 客户端（8.x）
import co.elastic.clients.elasticsearch._types.FieldValue;      // ES 字段值包装
import co.elastic.clients.elasticsearch._types.Refresh;         // ES 刷新策略
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;  // ES 查询类型（BestFields）
import co.elastic.clients.elasticsearch.core.BulkRequest;       // 批量写入请求
import co.elastic.clients.elasticsearch.core.BulkResponse;      // 批量写入响应
import co.elastic.clients.elasticsearch.core.SearchResponse;    // 搜索响应
import co.elastic.clients.elasticsearch.core.search.Hit;        // 搜索结果命中
import lombok.extern.slf4j.Slf4j;                               // Lombok：日志
import org.javaup.ai.manage.config.DocumentManageProperties;   // 应用配置（ES 索引名等）
import org.javaup.ai.manage.data.SuperAgentDocument;           // 文档实体
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;      // 切块实体
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;   // 文档表 Mapper
import org.javaup.ai.manage.model.DocumentRetrieveFilters;     // 检索过滤器
import org.javaup.ai.manage.model.DocumentRetrieveRequest;     // 检索请求
import org.javaup.ai.manage.model.es.DocumentKeywordIndexRecord; // ES 关键词索引记录实体
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys; // 知识检索元数据键名常量
import org.springframework.ai.document.Document;                // Spring AI Document
import org.springframework.beans.factory.annotation.Qualifier;  // Bean 名称限定注入
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;  // 条件注入
import org.springframework.stereotype.Service;                  // Spring 服务注解

import java.io.IOException;      // IO 异常
import java.util.ArrayList;     // 动态数组
import java.util.Arrays;        // 数组工具
import java.util.LinkedHashMap; // 有序哈希表
import java.util.List;          // 列表接口
import java.util.Locale;        // 地区设置（toLowerCase）
import java.util.Map;           // 映射接口
import java.util.Objects;       // 对象工具
import java.util.stream.Collectors; // 流收集器

/**
 * Elasticsearch 关键词检索网关 — 切块的关键词索引与检索服务。
 * <p>
 * 与 PGVector 向量检索互补：关键词通道通过 BM25 / 词法匹配做检索，
 * 与向量通道的检索结果通过 RRF（Reciprocal Rank Fusion）融合排序。
 * <p>
 * 功能职责：
 * <ul>
 *   <li><b>索引写入</b>（{@link #indexChunks}）：文档切块完成后，将 chunk 数据
 *       批量写入 ES 关键词索引，用于后续的词法检索。</li>
 *   <li><b>关键词检索</b>（{@link #search}）：接收用户查询，在 ES 中对
 *       sectionPath、chunkText、documentName 等字段做 matchPhrase 和 multiMatch 检索。</li>
 *   <li><b>文档删除</b>（{@link #deleteByDocumentId}）：文档被删除时同步清理 ES 索引。</li>
 * </ul>
 * <p>
 * 条件注入：仅在 {@code app.manage.elasticsearch.enabled=true}（默认 true）时创建此 Bean。
 */
@Slf4j      // Lombok：生成 log 字段
@Service    // 声明为 Spring 服务
@ConditionalOnProperty(
    prefix = "app.manage.elasticsearch",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true       // 配置缺省时默认启用
)
public class ElasticsearchDocumentKeywordSearchGateway implements DocumentKeywordSearchGateway {

    // ═══════════════════════════════════════════════════════════
    //  依赖注入
    // ═══════════════════════════════════════════════════════════

    /** Elasticsearch 客户端 — 与 ES 集群通信，执行索引和搜索操作。 */
    private final ElasticsearchClient elasticsearchClient;

    /** 文档表 Mapper — 用于批量查询文档元数据（文档名、知识范围等）。 */
    private final SuperAgentDocumentMapper documentMapper;

    /** 应用配置 — 读取 ES 索引名、analyzer 等参数。 */
    private final DocumentManageProperties properties;

    /**
     * 构造函数 — Spring 依赖注入。
     * @param elasticsearchClient ES 客户端（Bean 名 documentManageElasticsearchClient）
     * @param documentMapper      文档 Mapper
     * @param properties          应用配置
     */
    public ElasticsearchDocumentKeywordSearchGateway(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        SuperAgentDocumentMapper documentMapper,
        DocumentManageProperties properties) {
        this.elasticsearchClient = elasticsearchClient;
        this.documentMapper = documentMapper;
        this.properties = properties;
    }

    // ═══════════════════════════════════════════════════════════
    //  索引写入入口：indexChunks
    //  被 DocumentAsyncProcessServiceImpl.handleIndexBuild 调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 将文档切块批量写入 Elasticsearch 关键词索引。
     * <p>
     * 调用时机：切块+向量化完成后，handleIndexBuild 流水线调用此方法，
     * 将每个 chunk 写入 ES，用于后续的关键词通道检索（BM25/词法匹配）。
     * <p>
     * 写入流程：
     * <ol>
     *   <li>从 chunk 列表中提取所有不重复的 documentId，批量查询文档元数据</li>
     *   <li>构建 BulkRequest，将每个 chunk 转为 {@link DocumentKeywordIndexRecord}</li>
     *   <li>设置 refresh=WaitFor，保证写入后立即可搜</li>
     *   <li>执行批量写入，检查错误响应</li>
     * </ol>
     * <p>
     * 注意：此方法同步阻塞等待 ES 返回（refresh=WaitFor），
     * 确保调用返回后索引已更新可搜。批量大小受上游 BATCH_SIZE_LIMIT=10 控制。
     *
     * @param chunkList 待索引的切块列表（经过向量化后，vectorStatus 已更新）
     */
    @Override
    public void indexChunks(List<SuperAgentDocumentChunk> chunkList) {

        // ── Step 1: 空保护 — 无切块则直接返回 ──
        if (CollUtil.isEmpty(chunkList)) {
            return;
        }

        // ── Step 2: 批量加载文档元数据 ──
        // 从所有 chunk 中提取不重复的 documentId，批量查询文档表
        // 获取 documentName、knowledgeScopeName、businessCategory 等元数据
        Map<Long, SuperAgentDocument> documentMap = loadDocumentMap(chunkList);

        // ── Step 3: 构建批量写入请求 ──
        // 使用 ES Bulk API 一次性写入所有 chunk，减少网络开销
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder()
            .index(properties.getElasticsearch().getIndexName())  // 索引名（配置可调）
            .refresh(Refresh.WaitFor);                            // 同步刷新，写入后立即可搜

        // 遍历每个 chunk，转为 ES 索引记录后追加到 bulk 操作列表
        for (SuperAgentDocumentChunk chunk : chunkList) {
            // 获取该 chunk 所属文档的元数据（用于填充索引记录的文档级字段）
            SuperAgentDocument document = documentMap.get(chunk.getDocumentId());
            // 将 chunk + document 转为 ES 索引记录对象
            DocumentKeywordIndexRecord indexRecord = toIndexRecord(chunk, document);
            // 追加 index 操作到 bulk 请求
            bulkBuilder.operations(operation -> operation
                .index(index -> index
                    .id(indexRecord.getChunkId())       // 文档 ID = chunkId（与 PGVector 一致）
                    .document(indexRecord)              // 索引文档内容
                )
            );
        }

        // ── Step 4: 执行批量写入 ──
        try {
            BulkResponse response = elasticsearchClient.bulk(bulkBuilder.build());

            // ── Step 5: 检查错误 ──
            // ES Bulk API 是部分失败模式：即使有失败也会返回 200，
            // 需要通过 response.errors() 检查是否有写入失败的 item
            if (response.errors()) {
                // 收集所有错误 item 的信息（ID + 错误原因）
                String errorMessage = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> item.id() + ":" + item.error().reason())
                    .collect(Collectors.joining("; "));
                // 抛出异常，由上游 handleIndexBuild 的 catch 块处理
                throw new IllegalStateException("批量写入 Elasticsearch 失败: " + errorMessage);
            }

            // ── Step 6: 日志记录 ──
            log.info("文档 chunk 已同步写入 Elasticsearch: chunkCount={}, index={}",
                chunkList.size(), properties.getElasticsearch().getIndexName());
        }
        catch (IOException exception) {
            // 网络异常/ES 不可用等情况
            throw new IllegalStateException("写入 Elasticsearch 失败", exception);
        }
    }

    @Override
    public List<Document> search(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        List<FieldValue> documentFieldValues = request.resolvedDocumentIds().stream()
            .map(FieldValue::of)
            .toList();
        List<FieldValue> taskFieldValues = request.resolvedTaskIds().stream()
            .map(FieldValue::of)
            .toList();

        String retrievalQuery = request.getRetrievalQuery().trim();
        DocumentRetrieveFilters filters = request.getFilters();
        List<String> queryContextHints = request.getQueryContextHints() == null ? List.of() : request.getQueryContextHints();

        try {
            SearchResponse<DocumentKeywordIndexRecord> response = elasticsearchClient.search(search -> search
                    .index(properties.getElasticsearch().getIndexName())
                    .size(resolveTopK(request.getTopK()))
                    .query(query -> query.bool(bool -> {

                        bool.filter(filter -> filter.terms(terms -> terms
                            .field("documentId")
                            .terms(values -> values.value(documentFieldValues))
                        ));
                        bool.filter(filter -> filter.terms(terms -> terms
                            .field("taskId")
                            .terms(values -> values.value(taskFieldValues))
                        ));
                        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
                            bool.filter(filter -> filter.bool(sectionBool -> {
                                for (String sectionHint : filters.getSectionPathHints()) {
                                    sectionBool.should(should -> should.wildcard(wildcard -> wildcard
                                        .field("sectionPath")
                                        .value("*" + sectionHint.toLowerCase(Locale.ROOT) + "*")
                                    ));
                                }
                                sectionBool.minimumShouldMatch("1");
                                return sectionBool;
                            }));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getCanonicalPathHints())) {
                            bool.filter(filter -> filter.bool(pathBool -> {
                                for (String pathHint : filters.getCanonicalPathHints()) {
                                    pathBool.should(should -> should.wildcard(wildcard -> wildcard
                                        .field("canonicalPath")
                                        .value(pathHint + "*")
                                    ));
                                }
                                pathBool.minimumShouldMatch("1");
                                return pathBool;
                            }));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getStructureNodeIdHints())) {
                            List<FieldValue> structureNodeValues = filters.getStructureNodeIdHints().stream()
                                .map(FieldValue::of)
                                .toList();
                            bool.filter(filter -> filter.terms(terms -> terms
                                .field("structureNodeId")
                                .terms(values -> values.value(structureNodeValues))
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getItemIndexHints())) {
                            List<FieldValue> itemIndexValues = filters.getItemIndexHints().stream()
                                .map(FieldValue::of)
                                .toList();
                            bool.filter(filter -> filter.terms(terms -> terms
                                .field("itemIndex")
                                .terms(values -> values.value(itemIndexValues))
                            ));
                        }

                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("sectionPath")
                            .query(retrievalQuery)
                            .boost(8.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("chunkText")
                            .query(retrievalQuery)
                            .boost(5.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("documentName")
                            .query(retrievalQuery)
                            .boost(4.0f)
                        ));
                        bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                            .query(retrievalQuery)
                            .fields("sectionPath^6", "documentName^4", "knowledgeScopeName^3", "chunkText")
                            .type(TextQueryType.BestFields)
                        ));
                        if (filters != null && CollUtil.isNotEmpty(filters.getBusinessCategoryHints())) {

                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getBusinessCategoryHints()))
                                .fields("businessCategory^5", "knowledgeScopeName^2")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getDocumentTagHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getDocumentTagHints()))
                                .fields("documentTags^4", "documentName^2", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getDocumentNameHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getDocumentNameHints()))
                                .fields("documentName^6", "sectionPath^2", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getSectionPathHints()))
                                .fields("sectionPath^7", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (CollUtil.isNotEmpty(queryContextHints)) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", queryContextHints))
                                .fields("documentName^2", "knowledgeScopeName^2", "sectionPath^2", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        bool.minimumShouldMatch("1");
                        return bool;
                    })),
                DocumentKeywordIndexRecord.class);

            List<Document> result = new ArrayList<>();
            for (Hit<DocumentKeywordIndexRecord> hit : response.hits().hits()) {
                DocumentKeywordIndexRecord source = hit.source();
                if (source == null) {
                    continue;
                }
                result.add(toSpringDocument(source, hit.score()));
            }
            return result;
        }
        catch (IOException exception) {
            log.error("Elasticsearch 关键词检索失败, retrievalQuery={}", retrievalQuery, exception);
            return List.of();
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(properties.getElasticsearch().getIndexName())
                .refresh(true)
                .query(query -> query.term(term -> term
                    .field("documentId")
                    .value(documentId)
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除 Elasticsearch 文档失败", exception);
        }
    }

    private Map<Long, SuperAgentDocument> loadDocumentMap(List<SuperAgentDocumentChunk> chunkList) {
        List<Long> documentIds = chunkList.stream()
            .map(SuperAgentDocumentChunk::getDocumentId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        List<SuperAgentDocument> documents = documentMapper.selectBatchIds(documentIds);
        Map<Long, SuperAgentDocument> documentMap = new LinkedHashMap<>();
        for (SuperAgentDocument document : documents) {
            documentMap.put(document.getId(), document);
        }
        return documentMap;
    }

    private DocumentKeywordIndexRecord toIndexRecord(SuperAgentDocumentChunk chunk, SuperAgentDocument document) {
        return DocumentKeywordIndexRecord.builder()
            .chunkId(String.valueOf(chunk.getId()))
            .documentId(chunk.getDocumentId())
            .taskId(chunk.getTaskId())
            .parentBlockId(chunk.getParentBlockId())
            .chunkNo(chunk.getChunkNo())
            .documentName(document == null ? "" : safeText(document.getDocumentName()))
            .sectionPath(safeText(chunk.getSectionPath()))
            .structureNodeId(chunk.getStructureNodeId())
            .structureNodeType(chunk.getStructureNodeType())
            .canonicalPath(safeText(chunk.getCanonicalPath()))
            .itemIndex(chunk.getItemIndex())
            .knowledgeScopeCode(document == null ? "" : safeText(document.getKnowledgeScopeCode()))
            .knowledgeScopeName(document == null ? "" : safeText(document.getKnowledgeScopeName()))
            .businessCategory(document == null ? "" : safeText(document.getBusinessCategory()))
            .documentTags(splitTags(document == null ? "" : document.getDocumentTags()))
            .chunkText(safeText(chunk.getChunkText()))
            .build();
    }

    private Document toSpringDocument(DocumentKeywordIndexRecord source, Double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, "keyword");
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score == null ? 0D : score.doubleValue());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, parseLong(source.getChunkId()));
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, source.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, source.getTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, source.getParentBlockId());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, source.getChunkNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(source.getSectionPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, source.getStructureNodeId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, source.getStructureNodeType());
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(source.getCanonicalPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, source.getItemIndex());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, safeText(source.getDocumentName()));
        metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_CODE, safeText(source.getKnowledgeScopeCode()));
        metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_NAME, safeText(source.getKnowledgeScopeName()));
        metadata.put(DocumentKnowledgeMetadataKeys.BUSINESS_CATEGORY, safeText(source.getBusinessCategory()));
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_TAGS, String.join(",", source.getDocumentTags()));

        return Document.builder()
            .id(source.getChunkId())
            .text(source.getChunkText())
            .metadata(metadata)
            .score(score == null ? 0D : score.doubleValue())
            .build();
    }

    private boolean isSearchableRequest(DocumentRetrieveRequest request) {
        return request != null
            && StrUtil.isNotBlank(request.getQuestion())
            && StrUtil.isNotBlank(request.getRetrievalQuery())
            && !request.resolvedDocumentIds().isEmpty()
            && !request.resolvedTaskIds().isEmpty();
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private int resolveTopK(int topK) {
        return topK <= 0 ? 10 : Math.min(topK, 50);
    }

    private List<String> splitTags(String documentTags) {
        if (StrUtil.isBlank(documentTags)) {
            return List.of();
        }
        return Arrays.stream(documentTags.split(","))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }

    private Long parseLong(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }
}
