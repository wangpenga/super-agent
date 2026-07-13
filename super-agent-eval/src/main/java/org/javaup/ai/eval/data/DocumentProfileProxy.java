package org.javaup.ai.eval.data;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 文档画像代理实体 —— 只读
 * <p>
 * 仅映射需要用到的字段：example_questions、document_summary、core_topics。
 * 与主服务的 SuperAgentDocumentProfile 共享同一张表 super_agent_document_profile。
 *
 * @author 阿星不是程序员
 */
@Data
@TableName("super_agent_document_profile")
public class DocumentProfileProxy {

    private Long id;
    private Long documentId;
    private String documentSummary;
    private String coreTopics;
    private String exampleQuestions;
}
