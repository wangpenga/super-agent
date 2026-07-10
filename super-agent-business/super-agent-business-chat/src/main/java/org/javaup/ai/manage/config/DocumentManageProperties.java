package org.javaup.ai.manage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 配置属性
 * @author: wangpeng
 **/

@Data
@ConfigurationProperties(prefix = "app.manage")
public class DocumentManageProperties {

    private Minio minio = new Minio();

    private Kafka kafka = new Kafka();

    private Chunk chunk = new Chunk();

    private StructureParsing structureParsing = new StructureParsing();

    private PgVector pgVector = new PgVector();

    private Elasticsearch elasticsearch = new Elasticsearch();

    private Neo4j neo4j = new Neo4j();

    @Data
    public static class Minio {
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucketName = "super-agent-document";
        private String objectPrefix = "rag/document";
        private String parsedTextPrefix = "rag/parsed-text";
    }

    @Data
    public static class Kafka {
        private String parseTopic = "super-agent-document-parse-route";
        private String indexTopic = "super-agent-document-index-build";
        private String groupId = "super-agent-document-manage";
        private Boolean autoCreateTopics = Boolean.TRUE;
    }

    @Data
    public static class Chunk {
        /** 递归切块最大字符数，超过此值的文本会继续向下切分。子块管线默认 800。 */
        private Integer recursiveMaxChars = 800;
        /** 相邻递归切块之间的重叠字符数，缓解边界断裂。子块管线默认 120。 */
        private Integer recursiveOverlapChars = 120;
        /** 语义切块最大字符数，超过此值强制切分。子块管线默认 700。 */
        private Integer semanticMaxChars = 700;
        /** 语义切块最小字符数，低于此值不做语义切分。子块管线默认 240。 */
        private Integer semanticMinChars = 240;
        /** Jaccard 相似度阈值（旧方案），低于此值视为主题切换，默认 0.18。 */
        private Double semanticSimilarityThreshold = 0.18D;

        // ──── Embedding 语义切块参数（替换 Jaccard 方案）────

        /** Embedding 语义切块滑窗大小（句子数）。滑窗越大，上下文越充分，但边界越模糊。默认 3。 */
        private Integer embeddingSemanticWindowSize = 3;
        /**
         * Embedding 余弦相似度切分阈值（固定阈值模式）。
         * 相邻滑窗的余弦相似度低于此值时视为主题切换，触发切分。
         * 取值范围 [0, 1]，越低切分越保守（块越大），默认 0.45。
         * 仅当 embeddingSemanticDynamicPercentile = 0 时生效。
         */
        private Double embeddingSemanticThreshold = 0.45;
        /**
         * 动态阈值 percentile（0-1）。>0 时启用动态阈值替代固定阈值：
         * 取文档所有相邻滑窗余弦相似度的指定百分位值作为切分阈值。
         * 例如 0.15 表示取第 15% 分位的相似度值作为阈值。
         * 推荐 0.15，设为 0 则使用固定 threshold。
         */
        private Double embeddingSemanticDynamicPercentile = 0.15;

        /** 是否启用大模型智能切块（默认关闭，成本高）。 */
        private Boolean llmEnabled = Boolean.FALSE;
        /** 大模型智能切块时，单次送给模型的最大字符数，默认 3500。 */
        private Integer llmMaxChars = 3500;
        /** 当文档内容质量较差时，是否推荐追加大模型智能切块，默认 true。 */
        private Boolean recommendLlmWhenLowQuality = Boolean.TRUE;
    }

    @Data
    public static class StructureParsing {

        private Boolean llmDisambiguationEnabled = Boolean.TRUE;

        private Integer maxAmbiguousSignalsPerCall = 8;

        private Integer contextWindowLines = 2;

        private Integer maxPlainHeadingChars = 32;

        private Double ambiguityConfidenceFloor = 0.45D;

        private Double ambiguityConfidenceCeil = 0.80D;
    }

    @Data
    public static class PgVector {

        private Boolean enabled = Boolean.TRUE;

        private String host = "localhost";

        private Integer port = 5432;

        private String database = "super_agent_pgvector";

        private String schema = "public";

        private String username = "postgres";

        private String password = "postgres";

        private String poolName = "super-agent-manage-pgvector-hikari";

        private Integer maximumPoolSize = 15;

        private Integer minimumIdle = 5;

        private Integer connectionTimeout = 10000;

        private Integer leakDetectionThreshold = 60000;
    }

    @Data
    public static class Elasticsearch {

        private Boolean enabled = Boolean.TRUE;

        private List<String> uris = new ArrayList<>(List.of("http://127.0.0.1:9200"));

        private String username = "elastic";

        private String password = "elastic";

        private String indexName = "super_agent_document_keyword";

        private String analyzer = "ik_max_word";

        private String searchAnalyzer = "ik_smart";

        private String navigationIndexName = "super_agent_document_navigation";

        private String routeIndexName = "super_agent_knowledge_route";

        private Integer connectTimeoutMillis = 3000;

        private Integer socketTimeoutMillis = 5000;
    }

    @Data
    public static class Neo4j {

        private Boolean enabled = Boolean.FALSE;

        private String uri = "bolt://127.0.0.1:7687";

        private String username = "neo4j";

        private String password = "neo4j";

        private String database = "neo4j";

        private Integer queryTimeoutSeconds = 5;
    }
}
