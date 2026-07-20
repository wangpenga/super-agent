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
 * 提供数据集的自动生成、CRUD（新增/编辑/删除）、导入导出功能。
 *
 * @author 阿星不是程序员
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eval/dataset")
@RequiredArgsConstructor
public class EvalDatasetController {

    private final EvalDatasetService datasetService;

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

    @PostMapping("/list")
    public ResponseEntity<List<EvalDataset>> list(@RequestBody(required = false) Map<String, Object> params) {
        if (params != null && params.get("documentId") != null) {
            Long documentId = Long.valueOf(params.get("documentId").toString());
            return ResponseEntity.ok(datasetService.listByDocument(documentId));
        }
        if (params != null && params.get("id") != null) {
            Long id = Long.valueOf(params.get("id").toString());
            EvalDataset item = datasetService.getById(id);
            return ResponseEntity.ok(item != null ? List.of(item) : List.of());
        }
        return ResponseEntity.ok(datasetService.listActive());
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> params) {
        Long id = params.get("id") != null ? Long.valueOf(params.get("id").toString()) : null;
        Long documentId = params.get("documentId") != null
            ? Long.valueOf(params.get("documentId").toString()) : null;
        String question = (String) params.get("question");
        String referenceAnswer = (String) params.get("referenceAnswer");
        String groundTruthChunkIds = (String) params.get("groundTruthChunkIds");

        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question 不能为空"));
        }

        EvalDataset dataset;
        if (id != null) {
            dataset = datasetService.getById(id);
            if (dataset == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "条目不存在"));
            }
            // 编辑时只更新已存在的字段，null 表示不修改
            if (params.containsKey("documentId")) {
                dataset.setDocumentId(documentId);
            }
        } else {
            dataset = new EvalDataset();
            dataset.setDocumentId(documentId);  // 可为 null
            dataset.setSource("manual");
            dataset.setDifficulty("medium");
            dataset.setIsActive(1);
            dataset.setStatus(1);
        }

        dataset.setQuestion(question);
        if (referenceAnswer != null) {
            dataset.setReferenceAnswer(referenceAnswer);
        }

        // groundTruthChunkIds 可空，人工新增时通常不知道 chunk 编号
        if (groundTruthChunkIds != null && !groundTruthChunkIds.isBlank()) {
            dataset.setGroundTruthChunkIds(groundTruthChunkIds);
        } else if (dataset.getGroundTruthChunkIds() == null) {
            dataset.setGroundTruthChunkIds("[]");
        }

        if (params.get("difficulty") != null) {
            dataset.setDifficulty(params.get("difficulty").toString());
        }
        if (params.get("isActive") != null) {
            dataset.setIsActive(Integer.valueOf(params.get("isActive").toString()));
        }

        datasetService.save(dataset);
        log.info("测试集条目已保存: id={}, documentId={}, question='{}'",
            dataset.getId(), documentId, truncate(question, 50));

        return ResponseEntity.ok(Map.of("id", dataset.getId(), "saved", true));
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> request) {
        Long id = request.get("id") != null ? Long.valueOf(request.get("id").toString()) : null;
        Long documentId = request.get("documentId") != null
            ? Long.valueOf(request.get("documentId").toString()) : null;

        if (id != null) {
            datasetService.deleteById(id);
            return ResponseEntity.ok(Map.of("deleted", 1));
        } else if (documentId != null) {
            int count = datasetService.deleteByDocument(documentId);
            return ResponseEntity.ok(Map.of("deleted", count));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "id 或 documentId 不能为空"));
    }

    @PostMapping("/export")
    public ResponseEntity<String> exportJson() {
        return ResponseEntity.ok(datasetService.exportAsJson());
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importJson(@RequestBody String jsonStr) {
        int count = datasetService.importFromJson(jsonStr);
        return ResponseEntity.ok(Map.of("imported", count));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
