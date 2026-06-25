package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/**
 * 文档画像表 (super_agent_document_profile)
 * <p>
 * 记录文档的智能画像信息，由系统自动生成（也可人工确认）。包括：
 * <ul>
 *   <li>文档摘要和类型（intro/manual/rule/faq/troubleshooting/spec）</li>
 *   <li>核心主题和典型问题（JSON 数组）</li>
 *   <li>结构图能力标记：是否适合图结构问答、是否支持章节列表/目录类回答、
 *       是否支持步骤/item 查询、是否支持图辅助检索</li>
 *   <li>画像来源和状态（auto/manual/mixed）</li>
 * </ul>
 * <p>
 * 文档画像用于 ChatPreparationOrchestrator 的路由决策，帮助判断
 * 应该走结构图查询还是语义检索。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_profile")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentProfile extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文档id */
    private Long documentId;

    /** 画像版本 */
    private Integer profileVersion;

    /** 文档摘要 */
    private String documentSummary;

    /**
     * 文档类型
     * intro / manual / rule / faq / troubleshooting / spec
     */
    private String documentType;

    /** 核心主题，JSON 数组 */
    private String coreTopics;

    /** 典型问题，JSON 数组 */
    private String exampleQuestions;

    /** 是否适合图结构问答：1是 0否 */
    private Integer graphFriendly;

    /** 是否支持章节列表/目录类图回答：1是 0否 */
    private Integer supportsGraphOutline;

    /** 是否支持步骤/item 查询：1是 0否 */
    private Integer supportsItemLookup;

    /** 是否支持图辅助检索：1是 0否 */
    private Integer supportsGraphAssist;

    /**
     * 画像来源
     * auto / manual / mixed
     */
    private String profileSource;

    /**
     * 画像状态
     * 1:待生成 2:生成成功 3:生成失败 4:人工确认
     */
    private Integer profileStatus;

    /** 画像失败原因 */
    private String errorMsg;
}
