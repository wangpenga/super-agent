package org.javaup.ai.manage.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 章节及其父章节、相邻兄弟章节
 * @author: wangpeng
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphSectionWithSiblings {

    private GraphSection section;

    private GraphSection parent;

    private GraphSection previousSibling;

    private GraphSection nextSibling;
}
