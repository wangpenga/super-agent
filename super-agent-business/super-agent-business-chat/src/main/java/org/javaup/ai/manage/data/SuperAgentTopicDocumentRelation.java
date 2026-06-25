package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

import java.math.BigDecimal;

/**
 * 主题文档关联表 (super_agent_topic_document_relation)
 * <p>
 * 记录知识主题与文档之间的关联关系，包括关联分数、关联来源（自动/人工/混合）
 * 和关联原因。用于 AUTO_DOCUMENT 模式下，通过主题匹配快速定位相关文档。
 * <p>
 * 关联关系可以由系统自动生成（基于 embedding 相似度或关键词匹配），
 * 也可以由人工维护以确保关键文档的主题归属准确。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_topic_document_relation")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentTopicDocumentRelation extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 主题编码 */
    private String topicCode;

    /** 文档id */
    private Long documentId;

    /** 关联分数 */
    private BigDecimal relationScore;

    /**
     * 关联来源
     * auto / manual / mixed
     */
    private String relationSource;

    /** 关联原因 */
    private String reason;
}
