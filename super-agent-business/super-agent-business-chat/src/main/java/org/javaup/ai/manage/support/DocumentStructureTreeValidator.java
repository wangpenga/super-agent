package org.javaup.ai.manage.support;

// ────────────── 导入工具类 ──────────────
import cn.hutool.core.util.StrUtil;                            // Hutool 字符串工具
import org.javaup.enums.DocumentStructureNodeTypeEnum;          // 节点类型枚举
import org.springframework.stereotype.Component;                // Spring 组件注解

import java.util.ArrayList;       // 动态数组
import java.util.Comparator;      // 比较器（排序）
import java.util.LinkedHashMap;   // 有序哈希表
import java.util.List;            // 列表接口
import java.util.Map;             // 映射接口
import java.util.Objects;         // 对象工具（equals）

/**
 * 文档结构树校验器 — 管线第四站（最后一站）。
 * <p>
 * 职责：接收 {@link DocumentStructureHierarchyResolver} 产出的节点草稿列表，
 * 经过五步修复校验，生成最终的 {@link DocumentStructureNodeCandidate} 列表。
 * <p>
 * === 五步校验流水线 ===
 * <ol>
 *   <li><b>去重标题</b>（{@link #collapseSyntheticTitleSection}）：
 *       如果文档正文中有与文档标题完全相同的章节，合并到根节点并移除冗余节点。</li>
 *   <li><b>修复编号层级</b>（{@link #repairNumberedHierarchy}）：
 *       对多级数字编号标题，根据数字路径（numericPath）修正父子关系，
 *       确保 "1.2.3" 的父节点是 "1.2" 而非 "1"。</li>
 *   <li><b>修复非法父节点</b>（{@link #repairInvalidParents}）：
 *       修复引用不存在父节点、或章节挂在列表节点下的情况。</li>
 *   <li><b>重算深度</b>（{@link #recomputeDepths}）：
 *       按 nodeNo 顺序从根节点开始重新遍历，保证每个节点的 depth 正确。</li>
 *   <li><b>重建路径和同级链接</b>（{@link #rebuildPaths} / {@link #rebuildSiblingLinks}）：
 *       为每个节点生成 canonicalPath 和 sectionPath，建立前后同级链接。</li>
 * </ol>
 *
 * @see DocumentStructureHierarchyResolver 上游：层级构建器
 */
@Component  // 声明为 Spring 组件
public class DocumentStructureTreeValidator {

    /**
     * 校验并构建最终的节点候选列表 — 管线最后一站入口。
     * <p>
     * 内部依次执行五步修复流水线，然后将每个节点草稿转换为不可变的
     * {@link DocumentStructureNodeCandidate} 对象（只保留下游切块所需字段）。
     *
     * @param documentTitle 文档标题（用于标题去重和路径构建）
     * @param drafts        {@link DocumentStructureHierarchyResolver#resolve} 输出的节点草稿列表
     * @return 校验后的节点候选列表，按 nodeNo 升序排列
     *         - 如果 drafts 为 null 或空，返回空列表
     *         - 列表第一个元素始终是根 DOCUMENT 节点
     *         - 每个节点携带完整的路径、深度、同级链接信息
     */
    public List<DocumentStructureNodeCandidate> validateAndBuild(String documentTitle,
                                                                 List<DocumentStructureNodeDraft> drafts) {
        // ── Step 0: 空保护 ──────────────────────────────────────────
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }

        // ── Step 1: 构建 nodeNo → draft 映射 ────────────────────────
        // 使用 LinkedHashMap 保持插入顺序（即 nodeNo 升序）
        Map<Integer, DocumentStructureNodeDraft> draftMap = new LinkedHashMap<>();
        for (DocumentStructureNodeDraft draft : drafts) {
            if (draft != null && draft.getNodeNo() != null) {
                draftMap.put(draft.getNodeNo(), draft);
            }
        }

        // ── Step 2: 执行五步修复流水线 ───────────────────────────────
        // ① 去重：合并与文档标题相同且挂在根节点下的章节
        collapseSyntheticTitleSection(documentTitle, draftMap);

        // ② 修复编号层级：根据 numericPath 修正父子关系
        repairNumberedHierarchy(draftMap);

        // ③ 修复非法父节点：处理父节点不存在、章节挂在列表下等情况
        repairInvalidParents(draftMap);

        // ④ 重算深度：从根节点开始重新计算所有节点的深度
        recomputeDepths(draftMap);

        // ⑤ 重建路径和同级链接
        rebuildPaths(documentTitle, draftMap);     // 重建 canonicalPath 和 sectionPath
        rebuildSiblingLinks(draftMap);              // 重建 prev/nextSiblingNodeNo

        // ── Step 3: 转换为不可变的 NodeCandidate 并返回 ──────────────
        return draftMap.values().stream()
            .sorted(Comparator.comparingInt(DocumentStructureNodeDraft::getNodeNo))  // 按 nodeNo 排序
            .map(this::toCandidate)       // 转换为最终候选对象
            .toList();
    }

    /**
     * 第一步（修复①）：去重 — 合并与文档标题相同的合成章节。
     * <p>
     * 例如文档标题为 "王者荣耀综合介绍"，正文中第一个章节也是 "# 王者荣耀综合介绍"。
     * 这种情况下，正文中与文档标题完全相同且挂在根节点下的章节会被移除，
     * 其子节点和正文内容会被重新挂到根节点下。
     * <p>
     * 这样避免了标题树中出现两个完全相同的根级标题（一个来自 DOCUMENT_TITLE 信号，
     * 一个来自正文的 HEADING 信号）。
     */
    private void collapseSyntheticTitleSection(String documentTitle,
                                               Map<Integer, DocumentStructureNodeDraft> draftMap) {
        // 标准化文档标题（去 Markdown 前缀、去扩展名、去空白、小写）
        String normalizedTitle = normalizeComparableTitle(documentTitle);
        // 文档标题为空 → 无法去重，直接返回
        if (normalizedTitle.isBlank()) {
            return;
        }

        // 在草稿中查找与文档标题相同的章节节点
        Integer duplicateNodeNo = null;
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            // 跳过条件：
            // - 空节点 / nodeNo 为 null / nodeNo=1（根节点）
            // - 不是章节节点
            // - 父节点不是根节点（太深的节点不可能是文档标题的重复）
            // - 有编码前缀（带编号的章节不可能是文档标题的重复）
            if (draft == null
                || draft.getNodeNo() == null
                || draft.getNodeNo() == 1
                || !draft.isSection()
                || !Objects.equals(draft.getParentNodeNo(), 1)
                || StrUtil.isNotBlank(draft.getNodeCode())) {
                continue;
            }
            // 标准化标题比较
            if (normalizedTitle.equals(normalizeComparableTitle(draft.getTitle()))) {
                duplicateNodeNo = draft.getNodeNo();  // 找到重复节点
                break;
            }
        }

        // 没有发现重复 → 无需处理
        if (duplicateNodeNo == null) {
            return;
        }

        // 将重复节点的所有子节点重新挂到根节点下
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft != null && Objects.equals(draft.getParentNodeNo(), duplicateNodeNo)) {
                draft.setParentNodeNo(1);  // 父节点改为根节点
            }
        }

        // 移除重复的章节节点
        draftMap.remove(duplicateNodeNo);
    }

    /**
     * 第二步（修复②）：修复编号层级 — 根据数字路径修正父子关系。
     * <p>
     * 无论是信号提取阶段还是层级构建阶段，数字编号标题的层级都可能不准确。
     * 此方法遍历所有有 numericPath 的章节节点，根据数字路径重新设置父节点：
     * <ul>
     *   <li>一级编号（如 "1"、"一"）→ 父节点 = 根节点（nodeNo=1）</li>
     *   <li>多级编号（如 "1.2"）→ 优先找 "1" 作为父节点</li>
     *   <li>三级编号（如 "1.2.3"）→ 优先找 "1.2" 作为父节点，其次找 "1"</li>
     * </ul>
     * <p>
     * 这确保了数字编号文档的层级结构与编号体系完全一致。
     */
    private void repairNumberedHierarchy(Map<Integer, DocumentStructureNodeDraft> draftMap) {
        // 先构建所有 numericPath → nodeNo 的映射（只取首次出现的节点）
        Map<String, Integer> numericPathMap = new LinkedHashMap<>();
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            // 只处理章节节点
            if (draft == null || !draft.isSection()) {
                continue;
            }
            String key = numericKey(draft.getNumericPath());
            if (StrUtil.isNotBlank(key)) {
                // putIfAbsent：只记录首次出现的节点（通常对应最外层的编号）
                numericPathMap.putIfAbsent(key, draft.getNodeNo());
            }
        }

        // 遍历所有章节节点，根据数字路径修复父子关系
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null || !draft.isSection()) {
                continue;
            }
            List<Integer> numericPath = draft.getNumericPath();
            if (numericPath == null || numericPath.isEmpty()) {
                continue;  // 没有数字路径 → 跳过（由其他逻辑处理）
            }

            // 一级编号 → 父节点 = 根节点
            if (numericPath.size() == 1) {
                draft.setParentNodeNo(1);
                continue;
            }

            // 多级编号 → 查找精确父节点（如 [1,2,3] → 找 "1.2"）
            String directParentKey = numericKey(numericPath.subList(0, numericPath.size() - 1));
            Integer directParent = numericPathMap.get(directParentKey);
            if (directParent != null) {
                draft.setParentNodeNo(directParent);
                continue;
            }

            // 兜底：查找章节点（如 [1,2,3] → 找 "1"）
            String chapterParentKey = numericKey(List.of(numericPath.get(0)));
            Integer chapterParent = numericPathMap.get(chapterParentKey);
            if (chapterParent != null) {
                draft.setParentNodeNo(chapterParent);
            }
            // 如果都找不到，保持原来的父节点不变
        }
    }

    /**
     * 第三步（修复③）：修复非法父节点。
     * <p>
     * 修复两种场景：
     * <ul>
     *   <li>父节点不存在（parentNodeNo 在 draftMap 中找不到）→ 挂到根节点</li>
     *   <li>章节节点挂在列表节点下 → 上浮到列表节点的父节点</li>
     * </ul>
     * <p>
     * 场景二出现的原因：当列表项内有子标题时，层级构建阶段可能将标题挂在列表项下，
     * 但章节不应是列表的子节点（列表节点是叶子节点），所以需要上浮。
     */
    private void repairInvalidParents(Map<Integer, DocumentStructureNodeDraft> draftMap) {
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            // 跳过空节点和根节点
            if (draft == null || draft.getNodeNo() == 1) {
                continue;
            }

            // 修复①：父节点不存在 → 挂到根节点
            DocumentStructureNodeDraft parent = draft.getParentNodeNo() == null
                ? null
                : draftMap.get(draft.getParentNodeNo());
            if (parent == null) {
                draft.setParentNodeNo(1);  // 重新挂到根节点
                continue;
            }

            // 修复②：章节节点挂在列表节点下 → 上浮
            if (draft.isSection() && parent.isListLike()) {
                // 父节点（列表项）的父节点作为新父节点
                draft.setParentNodeNo(
                    parent.getParentNodeNo() == null ? 1 : parent.getParentNodeNo());
            }
        }
    }

    /**
     * 第四步（修复④）：重算深度 — 从根节点开始重新遍历整棵树。
     * <p>
     * 因为前面的修复步骤可能改变了父子关系（父节点变化），
     * 所以需要重新计算所有节点的深度。
     * <p>
     * 计算规则：
     * <ul>
     *   <li>根节点 depth = 0（固定）</li>
     *   <li>其他节点 depth = 父节点.depth + 1</li>
     * </ul>
     * <p>
     * 按 nodeNo 升序遍历能保证父节点总是先于子节点被计算。
     */
    private void recomputeDepths(Map<Integer, DocumentStructureNodeDraft> draftMap) {
        // 获取根节点
        DocumentStructureNodeDraft root = draftMap.get(1);
        if (root == null) {
            return;  // 根节点不存在 → 无法重算
        }
        // 根节点深度固定为 0
        root.setDepth(0);

        // 按 nodeNo 升序排列（确保父节点先于子节点被处理）
        List<DocumentStructureNodeDraft> ordered = draftMap.values().stream()
            .sorted(Comparator.comparingInt(DocumentStructureNodeDraft::getNodeNo))
            .toList();

        // 遍历所有非根节点，深度 = 父节点深度 + 1
        for (DocumentStructureNodeDraft draft : ordered) {
            if (draft == null || draft.getNodeNo() == 1) {
                continue;  // 跳过根节点（已经设好了）
            }
            DocumentStructureNodeDraft parent = draftMap.get(draft.getParentNodeNo());
            draft.setDepth(parent == null ? 1 : parent.getDepth() + 1);
        }
    }

    /**
     * 第五步（修复⑤a）：重建路径 — 为每个节点生成 canonicalPath 和 sectionPath。
     * <p>
     * <b>canonicalPath</b>：从根节点到当前节点的 URL 风格路径，用于唯一标识。
     * 例如 "/document/第一章/第一节"。
     * 路径段由节点编码或标题的 slug（URL 安全格式）生成。
     * <p>
     * <b>sectionPath</b>：从根节点到当前章节的标题路径，用于用户可读的上下文。
     * 例如 "第一章 > 第一节"。
     * 非章节节点不会叠加自身的 sectionPath（继承父节点的 sectionPath）。
     *
     * @param documentTitle 文档标题（备用路径段）
     * @param draftMap      nodeNo → draft 的映射
     */
    private void rebuildPaths(String documentTitle,
                              Map<Integer, DocumentStructureNodeDraft> draftMap) {
        // 遍历所有节点
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null) {
                continue;
            }

            // 根节点：固定路径
            if (draft.getNodeNo() == 1) {
                draft.setCanonicalPath("/document");  // 规范路径 = /document
                draft.setSectionPath("");              // 章节路径 = 空
                continue;
            }

            // 非根节点：路径 = 父路径 + "/" + 当前段
            DocumentStructureNodeDraft parent = draftMap.get(draft.getParentNodeNo());
            String parentCanonicalPath = parent == null
                ? "/document"
                : StrUtil.blankToDefault(parent.getCanonicalPath(), "/document");
            String parentSectionPath = parent == null
                ? ""
                : StrUtil.blankToDefault(parent.getSectionPath(), "");

            // 生成当前节点的路径段
            String segment = buildPathSegment(draft);

            // canonicalPath：父子拼接
            draft.setCanonicalPath(parentCanonicalPath + "/" + segment);

            // sectionPath：只有章节节点才叠加自身标题
            if (draft.isSection()) {
                draft.setSectionPath(joinSectionPath(parentSectionPath, displayTitle(draft)));
            } else {
                // 非章节节点（列表项等）继承父节点的 sectionPath
                draft.setSectionPath(parentSectionPath);
            }
        }
    }

    /**
     * 第五步（修复⑤b）：重建同级链接 — 设置 prevSiblingNodeNo 和 nextSiblingNodeNo。
     * <p>
     * 将同一父节点下的所有子节点按 lineNo 排序，然后为每个节点设置前后兄弟链接。
     * 同级链接在导航（上下翻页）和上下文感知检索中有重要作用。
     */
    private void rebuildSiblingLinks(Map<Integer, DocumentStructureNodeDraft> draftMap) {
        // 先按父节点分组
        Map<Integer, List<DocumentStructureNodeDraft>> childrenByParent = new LinkedHashMap<>();
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null || draft.getNodeNo() == 1) {
                continue;  // 跳过根节点
            }
            childrenByParent.computeIfAbsent(
                draft.getParentNodeNo(), ignored -> new ArrayList<>()).add(draft);
        }

        // 为每个父节点的子节点设置兄弟链接
        for (List<DocumentStructureNodeDraft> siblings : childrenByParent.values()) {
            // 按 lineNo 排序（保证文档顺序）
            siblings.sort(Comparator.comparingInt(DocumentStructureNodeDraft::getLineNo));
            for (int index = 0; index < siblings.size(); index++) {
                DocumentStructureNodeDraft current = siblings.get(index);
                // 第一个子节点：prevSibling = 0（没有前兄弟）
                current.setPrevSiblingNodeNo(
                    index == 0 ? 0 : siblings.get(index - 1).getNodeNo());
                // 最后一个子节点：nextSibling = 0（没有后兄弟）
                current.setNextSiblingNodeNo(
                    index == siblings.size() - 1 ? 0 : siblings.get(index + 1).getNodeNo());
            }
        }
    }

    /**
     * 将节点草稿转换为最终的节点候选对象 — validateAndBuild 的最后一步。
     * <p>
     * 只保留下游切块阶段需要的字段，移除构建过程的中间状态。
     *
     * @param draft 节点草稿
     * @return 节点候选（不可变风格，仅保留必要字段）
     */
    private DocumentStructureNodeCandidate toCandidate(DocumentStructureNodeDraft draft) {
        return new DocumentStructureNodeCandidate(
            draft.getNodeNo(),                                // 节点编号
            draft.getNodeType(),                              // 节点类型（DOCUMENT/SECTION/STEP/LIST_ITEM）
            draft.getParentNodeNo(),                          // 父节点编号
            normalizeSibling(draft.getPrevSiblingNodeNo()),   // 前兄弟编号（null→0）
            normalizeSibling(draft.getNextSiblingNodeNo()),   // 后兄弟编号（null→0）
            draft.getDepth(),                                 // 树深度
            draft.getNodeCode(),                              // 节点编码（如 "1.2"）
            draft.getTitle(),                                 // 标题文本
            draft.getAnchorText(),                            // 锚点文本（显示用完整标题）
            draft.getCanonicalPath(),                         // 规范路径（如 "/document/第一节"）
            draft.getSectionPath(),                           // 章节路径（如 "第一章 > 第一节"）
            draft.contentText(),                              // 归属该节点的正文内容
            draft.getItemIndex()                               // 列表项序号
        );
    }

    /**
     * 规范化兄弟节点编号 — null → 0。
     * 下游切块代码期望兄弟编号永远不会是 null，0 表示"无兄弟"。
     */
    private Integer normalizeSibling(Integer siblingNodeNo) {
        return siblingNodeNo == null ? 0 : siblingNodeNo;
    }

    /**
     * 拼接章节路径 — rebuildPaths 的辅助方法。
     * <p>
     * 规则：
     * <ul>
     *   <li>父路径为空 → 直接用当前标题</li>
     *   <li>当前标题为空 → 沿用父路径</li>
     *   <li>都有值 → "父路径 > 当前标题"</li>
     * </ul>
     */
    private String joinSectionPath(String parentSectionPath, String currentTitle) {
        if (StrUtil.isBlank(parentSectionPath)) {
            return StrUtil.blankToDefault(currentTitle, "");
        }
        if (StrUtil.isBlank(currentTitle)) {
            return parentSectionPath;
        }
        return parentSectionPath + " > " + currentTitle;
    }

    /**
     * 生成节点的规范路径段 — rebuildPaths 的辅助方法。
     * <p>
     * 规则：
     * <ul>
     *   <li>列表项有 itemIndex → "item-{序号}"（如 "item-1"）</li>
     *   <li>列表项无 itemIndex → 标题的 slug 格式</li>
     *   <li>章节有编码 → 编码的 slug 格式</li>
     *   <li>章节无编码 → 标题的 slug 格式</li>
     * </ul>
     */
    private String buildPathSegment(DocumentStructureNodeDraft draft) {
        if (draft == null) {
            return "node";
        }
        // 列表节点
        if (draft.isListLike()) {
            if (draft.getItemIndex() != null && draft.getItemIndex() > 0) {
                return "item-" + draft.getItemIndex();  // 如 "item-1"
            }
            return slug(displayTitle(draft));            // 标题的 slug
        }
        // 章节节点：优先用编码作为路径段
        String code = StrUtil.blankToDefault(draft.getNodeCode(), "").trim();
        if (StrUtil.isNotBlank(code)) {
            return slug(code);  // 编码的 slug
        }
        // 兜底：用标题的 slug
        return slug(displayTitle(draft));
    }

    /**
     * 获取节点的显示标题 — 锚点文本构建的辅助方法。
     * <p>
     * 规则同 {@link DocumentStructureHierarchyResolver#buildHeadingAnchorText}：
     * <ul>
     *   <li>无编码 → 直接用标题</li>
     *   <li>标题已包含编码前缀 → 直接用标题</li>
     *   <li>否则 → "编码 标题"</li>
     * </ul>
     */
    private String displayTitle(DocumentStructureNodeDraft draft) {
        // 获取编码（如 "1.2"）和标题（如 "数据校验"）
        String code = StrUtil.blankToDefault(draft.getNodeCode(), "").trim();
        String title = StrUtil.blankToDefault(draft.getTitle(), "").trim();
        // 无编码 → 直接用标题
        if (StrUtil.isBlank(code)) {
            return title;
        }
        // 标题已包含编码前缀 → 直接用标题
        if (title.startsWith(code)) {
            return title;
        }
        // 拼接为 "编码 标题"
        return code + " " + title;
    }

    /**
     * 将文本转为 URL 安全的 slug 格式 — 用于构建规范路径。
     * <p>
     * 转换规则：
     * <ol>
     *   <li>空白 → 连字符（-）</li>
     *   <li>保留中文字符、字母、数字、点、下划线、连字符</li>
     *   <li>移除其他所有字符</li>
     * </ol>
     * <p>
     * 例如 "第一章 总则" → "第一章-总则"
     */
    private String slug(String value) {
        // 安全获取文本
        String normalized = StrUtil.blankToDefault(value, "").trim();
        // 空文本 → 默认 "node"
        if (normalized.isBlank()) {
            return "node";
        }
        // 转 slug：空白→连字符，移除非法字符
        String slug = normalized
            .replaceAll("\\s+", "-")                    // 空白 → "-"
            .replaceAll("[^\\p{IsHan}A-Za-z0-9_.-]", "");  // 只保留中文、字母、数字、_、.、-
        // 如果结果为空（全是非法字符）→ 兜底 "node"
        return slug.isBlank() ? "node" : slug;
    }

    /**
     * 将数字路径列表转为字符串键 — 用于映射表的 key。
     * <p>
     * 例如 [1, 2, 3] → "1.2.3"
     */
    private String numericKey(List<Integer> numericPath) {
        // 空路径 → 空字符串
        if (numericPath == null || numericPath.isEmpty()) {
            return "";
        }
        // 手动拼接比 stream 更高效
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < numericPath.size(); index++) {
            if (index > 0) {
                builder.append('.');  // 数字间加句点分隔
            }
            Integer segment = numericPath.get(index);
            if (segment != null) {
                builder.append(segment);
            }
        }
        return builder.toString();
    }

    /**
     * 标准化标题文本以进行比较 — 用于 collapseSyntheticTitleSection 的去重判断。
     * <p>
     * 与 {@link DocumentStructureSignalExtractor#normalizeComparableTitle} 逻辑一致：
     * <ol>
     *   <li>移除 Markdown # 前缀</li>
     *   <li>移除文件扩展名（如 .pdf / .docx）</li>
     *   <li>移除所有空白</li>
     *   <li>转为小写</li>
     * </ol>
     */
    private String normalizeComparableTitle(String text) {
        // 安全获取文本
        String normalized = StrUtil.blankToDefault(text, "").trim();
        // 空文本 → 返回空字符串
        if (normalized.isBlank()) {
            return "";
        }
        return normalized
            .replaceAll("^#+\\s*", "")              // 移除 Markdown # 前缀
            .replaceAll("\\.[A-Za-z0-9]{1,6}$", "") // 移除文件扩展名
            .replaceAll("\\s+", "")                 // 移除所有空白
            .toLowerCase();                         // 转为小写
    }
}
