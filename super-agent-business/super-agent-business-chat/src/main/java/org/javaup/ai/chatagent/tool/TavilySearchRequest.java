package org.javaup.ai.chatagent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 工具类
 * @author: wangpeng
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchRequest {

    private String query;
    private String topic;
    private Integer maxResults;
}
