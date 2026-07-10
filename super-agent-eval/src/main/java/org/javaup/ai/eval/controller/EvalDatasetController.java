package org.javaup.ai.eval.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.data.EvalDataset;
import org.javaup.ai.eval.service.EvalDatasetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评估测试集管理接口
 * <p>
 * 提供数据集的自动生成、CRUD、导入导出功能。
 * 数据集是 Context Recall 计算的 ground truth 依据。
 *
 * @author 阿星不是程序员
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eval/dataset")
@RequiredArgsConstructor
public class EvalDatasetController {

    private final EvalDatasetService datasetService;

    /**
     * 自动生成测试集
     * POST /api/admin/eval/dataset/generate
     *
     * @param request { documentId, questions: [可选，如果为空会自动调用 LLM 生成] }
     * @return { generated: 生成数量 }
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> request) {
        Long documentId = request.get("documentId") != null
            ? Long.valueOf(request.get("documentId").toString()) : null;
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) request.get("questions");

        if (documentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "documentId 不能为空"));
        }

        int count = datasetService.autoGenerate(documentId, questions);
        return ResponseEntity.ok(Map.of(
            "documentId", documentId,
            "generated", count,
            "message", "生成 " + count + " 条测试数据"
        ));
    }

    /**
     * 列表查询
     * POST /api/admin/eval/dataset/list
     */
    @PostMapping("/list")
    public ResponseEntity<List<EvalDataset>> list(@RequestBody(required = false) Map<String, Object> params) {
        if (params != null && params.get("documentId") != null) {
            Long documentId = Long.valueOf(params.get("documentId").toString());
            return ResponseEntity.ok(datasetService.listByDocument(documentId));
        }
        return ResponseEntity.ok(datasetService.listActive());
    }

    /**
     * 删除
     * POST /api/admin/eval/dataset/delete
     */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> request) {
        Long documentId = request.get("documentId") != null
            ? Long.valueOf(request.get("documentId").toString()) : null;
        if (documentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "documentId 不能为空"));
        }
        int count = datasetService.deleteByDocument(documentId);
        return ResponseEntity.ok(Map.of("deleted", count));
    }

    /**
     * 导出数据集为 JSON
     * POST /api/admin/eval/dataset/export
     */
    @PostMapping("/export")
    public ResponseEntity<String> exportJson() {
        return ResponseEntity.ok(datasetService.exportAsJson());
    }

    /**
     * 从 JSON 导入数据集
     * POST /api/admin/eval/dataset/import
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importJson(@RequestBody String jsonStr) {
        int count = datasetService.importFromJson(jsonStr);
        return ResponseEntity.ok(Map.of("imported", count));
    }
}
