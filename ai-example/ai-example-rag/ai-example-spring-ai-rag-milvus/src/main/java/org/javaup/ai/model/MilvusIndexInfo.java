package org.javaup.ai.model;

import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加  微信，添加时备注 super 来获取项目的完整资料
 * @description: 模型对象
 *
 **/
public record MilvusIndexInfo(
        String fieldName,
        String indexName,
        String state,
        long indexedRows,
        long totalRows,
        Map<String, String> params
) {
}
