package org.javaup.ai.manage.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 目标编号项及其所在章节上下文
 * @author: wangpeng
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphItemWithContext {

    private GraphSection section;

    private GraphItem item;

    @Builder.Default
    private List<GraphItem> siblingItems = new ArrayList<>();
}
