package org.javaup.ai.model;

import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加  微信，添加时备注 super 来获取项目的完整资料
 * @description: 模型对象
 *
 **/
public record MilvusSearchResult(
        String id,
        String content,
        Double score,
        String docId,
        String category,
        Map<String, Object> metadata
) {
}
