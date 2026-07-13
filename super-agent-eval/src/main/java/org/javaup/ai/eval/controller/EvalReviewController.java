package org.javaup.ai.eval.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.service.EvalReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工抽检接口
 * <p>
 * 允许质检人员逐条查看「问题 → 检索的 chunks → LLM 生成的答案 → 参考答案」，
 * 并给出人工评分（1~5 分）。
 * <p>
 * 页面入口：管理后台 → 人工抽检
 *
 * @author 阿星不是程序员
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eval/review")
@RequiredArgsConstructor
public class EvalReviewController {

    private final EvalReviewService reviewService;

    /**
     * 获取抽检列表
     * POST /api/admin/eval/review/list
     */
    @PostMapping("/list")
    public ResponseEntity<List<EvalReviewService.EvalReviewItem>> list(@RequestBody Map<String, Object> params) {
        Long documentId = params.get("documentId") != null
            ? Long.valueOf(params.get("documentId").toString()) : null;
        Integer reviewStatus = params.get("reviewStatus") != null
            ? Integer.valueOf(params.get("reviewStatus").toString()) : null;
        return ResponseEntity.ok(reviewService.listReviewItems(documentId, reviewStatus));
    }

    /**
     * 获取单条详情
     * POST /api/admin/eval/review/detail
     */
    @PostMapping("/detail")
    public ResponseEntity<EvalReviewService.EvalReviewItem> detail(@RequestBody Map<String, Long> params) {
        Long datasetId = params.get("datasetId");
        if (datasetId == null) return ResponseEntity.badRequest().build();
        EvalReviewService.EvalReviewItem item = reviewService.getReviewDetail(datasetId);
        return item != null ? ResponseEntity.ok(item) : ResponseEntity.notFound().build();
    }

    /**
     * 生成 LLM 答案
     * POST /api/admin/eval/review/generate-answer
     */
    @PostMapping("/generate-answer")
    public ResponseEntity<Map<String, Object>> generateAnswer(@RequestBody Map<String, Long> params) {
        Long datasetId = params.get("datasetId");
        if (datasetId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "datasetId 不能为空"));
        }
        String answer = reviewService.generateAnswer(datasetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("answer", answer);
        result.put("answerLen", answer != null ? answer.length() : 0);
        return ResponseEntity.ok(result);
    }

    /**
     * 保存参考答案
     * POST /api/admin/eval/review/save-reference
     */
    @PostMapping("/save-reference")
    public ResponseEntity<Map<String, Object>> saveReference(@RequestBody Map<String, Object> params) {
        Long datasetId = params.get("datasetId") != null
            ? Long.valueOf(params.get("datasetId").toString()) : null;
        String referenceAnswer = (String) params.get("referenceAnswer");
        if (datasetId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "datasetId 不能为空"));
        }
        reviewService.saveReferenceAnswer(datasetId, referenceAnswer);
        return ResponseEntity.ok(Map.of("datasetId", datasetId, "saved", true));
    }

    /**
     * 保存人工评分
     * POST /api/admin/eval/review/rate
     */
    @PostMapping("/rate")
    public ResponseEntity<Map<String, Object>> rate(@RequestBody Map<String, Object> params) {
        Long datasetId = params.get("datasetId") != null
            ? Long.valueOf(params.get("datasetId").toString()) : null;
        Integer score = params.get("score") != null
            ? Integer.valueOf(params.get("score").toString()) : null;
        String comment = (String) params.get("comment");
        if (datasetId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "datasetId 不能为空"));
        }
        try {
            reviewService.saveHumanRating(datasetId, score, comment);
            return ResponseEntity.ok(Map.of("datasetId", datasetId, "score", score, "saved", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 从真实对话导入测试数据
     * POST /api/admin/eval/review/import-conversations
     */
    @PostMapping("/import-conversations")
    public ResponseEntity<Map<String, Object>> importConversations(@RequestBody Map<String, Object> params) {
        Long documentId = params.get("documentId") != null
            ? Long.valueOf(params.get("documentId").toString()) : null;
        int limit = params.get("limit") != null
            ? Integer.parseInt(params.get("limit").toString()) : 20;

        int imported = reviewService.importFromConversations(documentId, null, limit);
        return ResponseEntity.ok(Map.of(
            "imported", imported,
            "message", "导入 " + imported + " 条对话记录到测试集"
        ));
    }
}
