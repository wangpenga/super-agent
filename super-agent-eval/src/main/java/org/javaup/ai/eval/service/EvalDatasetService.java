package org.javaup.ai.eval.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.config.EvalProperties;
import org.javaup.ai.eval.data.DocumentProfileProxy;
import org.javaup.ai.eval.data.EvalDataset;
import org.javaup.ai.eval.mapper.DocumentProfileProxyMapper;
import org.javaup.ai.eval.mapper.EvalDatasetMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 评估测试集管理服务
 * <p>
 * 提供数据集的 CRUD、自动生成、导入导出功能。
 * 生成时优先使用真实对话日志，其次是文档画像问题，最后 LLM 补充。
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    private final EvalDatasetMapper datasetMapper;
    private final EvalDatasetGenerator datasetGenerator;
    private final DocumentProfileProxyMapper documentProfileMapper;
    private final EvalProperties evalProperties;

    /**
     * 为指定文档自动生成测试集。
     * 如果 questions 为空或没传，自动从文档画像表 super_agent_document_profile
     * 的 example_questions 字段捞取。
     *
     * @param documentId 文档 ID
     * @param questions  候选问题列表（可选，为 null 时自动从文档画像获取）
     * @return 新生成的条目数
     */
    public int autoGenerate(Long documentId, List<String> questions) {
        EvalProperties.DatasetProperties cfg = evalProperties.getDataset();

        // ★ 如果没传问题，自动从文档画像表捞 example_questions
        if (questions == null || questions.isEmpty()) {
            questions = fetchQuestionsFromProfile(documentId, cfg.getMaxQuestionsPerDocument());
        }

        if (questions == null || questions.isEmpty()) {
            log.warn("文档 {} 没有任何候选问题（文档画像表也无数据），跳过生成", documentId);
            return 0;
        }

        // 生成并持久化
        List<EvalDataset> generated = datasetGenerator.generateForDocument(
            documentId, questions, cfg.getMaxQuestionsPerDocument());

        if (generated.isEmpty()) {
            log.info("文档 {} 未生成有效测试数据", documentId);
            return 0;
        }

        int saved = 0;
        for (EvalDataset item : generated) {
            // 检查重复：同文档 + 同问题
            LambdaQueryWrapper<EvalDataset> wrapper = new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getDocumentId, documentId)
                .eq(EvalDataset::getQuestion, item.getQuestion());
            if (datasetMapper.selectCount(wrapper) > 0) {
                log.debug("跳过重复问题: documentId={}, question='{}'", documentId, truncate(item.getQuestion(), 50));
                continue;
            }
            datasetMapper.insert(item);
            saved++;
        }

        log.info("文档 {} 自动生成测试集完成：新增 {} 条/共 {} 条候选", documentId, saved, generated.size());
        return saved;
    }

    /**
     * 查询活跃的测试集列表
     */
    public List<EvalDataset> listActive() {
        LambdaQueryWrapper<EvalDataset> wrapper = new LambdaQueryWrapper<EvalDataset>()
            .eq(EvalDataset::getIsActive, 1)
            .eq(EvalDataset::getStatus, 1)
            .orderByAsc(EvalDataset::getDocumentId);
        return datasetMapper.selectList(wrapper);
    }

    /**
     * 按主键查单条
     */
    public EvalDataset getById(Long id) {
        return datasetMapper.selectById(id);
    }

    /**
     * 新增或更新。有 id 更新，无 id 新增
     */
    public void save(EvalDataset dataset) {
        if (dataset.getId() != null) {
            datasetMapper.updateById(dataset);
        } else {
            datasetMapper.insert(dataset);
        }
    }

    /**
     * 按主键删除
     */
    public void deleteById(Long id) {
        datasetMapper.deleteById(id);
    }

    /**
     * 按文档 ID 查询测试集
     */
    public List<EvalDataset> listByDocument(Long documentId) {
        LambdaQueryWrapper<EvalDataset> wrapper = new LambdaQueryWrapper<EvalDataset>()
            .eq(EvalDataset::getDocumentId, documentId)
            .eq(EvalDataset::getStatus, 1);
        return datasetMapper.selectList(wrapper);
    }

    /**
     * 删除指定文档的测试集
     */
    public int deleteByDocument(Long documentId) {
        LambdaQueryWrapper<EvalDataset> wrapper = new LambdaQueryWrapper<EvalDataset>()
            .eq(EvalDataset::getDocumentId, documentId);
        return datasetMapper.delete(wrapper);
    }

    /**
     * 导出数据集为 JSON 字符串
     */
    public String exportAsJson() {
        List<EvalDataset> list = listActive();
        return JSONUtil.toJsonPrettyStr(list);
    }

    /**
     * 从 JSON 字符串导入数据集
     */
    public int importFromJson(String jsonStr) {
        List<EvalDataset> list = JSONUtil.toList(jsonStr, EvalDataset.class);
        if (list == null || list.isEmpty()) return 0;

        int imported = 0;
        for (EvalDataset item : list) {
            // 检查重复
            LambdaQueryWrapper<EvalDataset> wrapper = new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getDocumentId, item.getDocumentId())
                .eq(EvalDataset::getQuestion, item.getQuestion());
            if (datasetMapper.selectCount(wrapper) > 0) {
                continue;
            }
            item.setIsActive(item.getIsActive() != null ? item.getIsActive() : 1);
            item.setStatus(1);
            datasetMapper.insert(item);
            imported++;
        }
        log.info("导入数据集完成：新增 {} 条", imported);
        return imported;
    }

    /**
     * 从文档画像表获取候选问题
     * 优先级：example_questions → LLM 基于摘要生成
     */
    private List<String> fetchQuestionsFromProfile(Long documentId, int maxQuestions) {
        LambdaQueryWrapper<DocumentProfileProxy> wrapper = new LambdaQueryWrapper<DocumentProfileProxy>()
            .eq(DocumentProfileProxy::getDocumentId, documentId);
        DocumentProfileProxy profile = documentProfileMapper.selectOne(wrapper);

        if (profile == null) {
            log.warn("文档 {} 在 super_agent_document_profile 表中无记录", documentId);
            return List.of();
        }

        // 优先用 example_questions
        String exampleQuestions = profile.getExampleQuestions();
        if (exampleQuestions != null && !exampleQuestions.isBlank()) {
            List<String> questions = datasetGenerator.parseJsonArray(exampleQuestions);
            if (!questions.isEmpty()) {
                log.info("从文档画像获取 {} 条候选问题: documentId={}", questions.size(), documentId);
                return questions;
            }
        }

        // example_questions 为空，用 LLM 基于文档摘要生成
        log.info("文档画像没有 example_questions，尝试用 LLM 生成: documentId={}", documentId);
        List<String> llmQuestions = datasetGenerator.generateQuestionsByLLM(
            profile.getDocumentSummary(),
            profile.getCoreTopics(),
            maxQuestions
        );
        if (!llmQuestions.isEmpty()) {
            log.info("LLM 生成了 {} 个问题: documentId={}", llmQuestions.size(), documentId);
            return llmQuestions;
        }

        return List.of();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
