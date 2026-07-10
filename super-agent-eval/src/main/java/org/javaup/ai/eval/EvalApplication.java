package org.javaup.ai.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG 评估服务入口
 * <p>
 * 独立部署的微服务，基于 RAGAS 指标对主服务的检索管道进行离线质量评估。
 * 通过 RestTemplate 调用主服务的内部检索 API，不直接依赖主服务的数据库或 Bean。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>Context Precision — 检索结果的排序质量（排在前面的文档是否相关）</li>
 *   <li>Context Recall — 所有 ground truth 相关文档的召回率</li>
 *   <li>Faithfulness — 生成答案是否忠实于检索内容</li>
 *   <li>Answer Relevancy — 答案是否回答了用户问题</li>
 * </ul>
 *
 * @author wangpeng
 */
@SpringBootApplication
public class EvalApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvalApplication.class, args);
    }
}
