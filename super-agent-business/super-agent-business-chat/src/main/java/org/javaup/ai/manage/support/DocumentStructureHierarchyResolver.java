package org.javaup.ai.manage.support;

// ────────────── 导入工具类 ──────────────
import cn.hutool.core.util.StrUtil;                            // Hutool 字符串工具
import org.javaup.enums.DocumentStructureNodeTypeEnum;          // 节点类型枚举：DOCUMENT/SECTION/STEP/LIST_ITEM
import org.springframework.stereotype.Component;                // Spring 组件注解

import java.util.ArrayDeque;       // 数组双端队列（用作列表栈）
import java.util.ArrayList;        // 动态数组
import java.util.Comparator;       // 比较器（排序）
import java.util.Deque;            // 双端队列接口
import java.util.LinkedHashMap;    // 有序哈希表
import java.util.List;             // 列表接口
import java.util.Map;              // 映射接口
import java.util.Objects;          // 对象工具（equals）

/**
 * 文档结构层级构建器 — 管线第三站。
 * <p>
 * 职责：接收经过歧义消解的结构信号列表，将其组装为具有父子层级关系的章节树草稿。
 * <p>
 * === 核心流程 ===
 * <ol>
 *   <li><b>创建根节点</b>：DOCUMENT 类型，固定 nodeNo=1，depth=0</li>
 *   <li><b>遍历信号</b>：按 lineNo 升序遍历每一个信号，根据信号类型做不同处理</li>
 *   <li><b>标题信号</b>（HEADING / HEADING_CANDIDATE）：创建 SECTION 节点，
 *       通过 resolveHeadingDepth 确定其在树中的深度，挂在正确的父节点下</li>
 *   <li><b>列表信号</b>（LIST_ITEM / STEP_ITEM）：创建 LIST_ITEM / STEP 节点，
 *       通过 resolveListParent 确定其父节点（当前章节或上一级列表项）</li>
 *   <li><b>正文行</b>（BODY / TABLE_ROW / QUOTE）：追加到最近的标题节点或列表项节点的 content 中</li>
 *   <li><b>空行</b>（BLANK）：清除列表栈，使得后续列表项从上层的章节开始</li>
 * </ol>
 * <p>
 * === 深度决定策略 ===
 * <ul>
 *   <li>Markdown 标题：levelHint（# 的个数）直接作为深度 1~6</li>
 *   <li>"第X章" / "附录"：固定深度为 1</li>
 *   <li>多级数字编号（如 1.2.3）：优先通过 numericPath 查找精确父节点（1.2）；
 *       若找不到精确父节点，再通过 numericPath 的第一段找章节点（1）作为父节点</li>
 *   <li>Plain 标题候选：通过 resolveHeadingDepth 查找最近的层级父节点，继承深度</li>
 *   <li>列表项：depth = 父节点.depth + 1</li>
 * </ul>
 *
 * @see DocumentStructureAmbiguityResolver 上游：歧义消解器
 * @see DocumentStructureTreeValidator     下游：树校验器
 */
@Component  // 声明为 Spring 组件
public class DocumentStructureHierarchyResolver {

    /**
     * 将消解后的信号列表组装为层级树草稿 — 管线第三站入口。
     * <p>
     * 处理过程按信号类型分为五个分支：
     * <ul>
     *   <li>BLANK → 清除列表栈（重置列表嵌套上下文）</li>
     *   <li>NOISE → 忽略（不生成任何节点）</li>
     *   <li>BODY / TABLE_ROW / QUOTE → 追加到最近的标题或列表节点</li>
     *   <li>LIST_ITEM / STEP_ITEM → 创建列表节点，维护列表栈实现嵌套</li>
     *   <li>HEADING / HEADING_CANDIDATE → 创建章节节点，通过深度决定父子关系</li>
     * </ul>
     *
     * @param documentTitle 文档标题，用于填充根节点的标题
     * @param signals       经歧义消解后的信号列表（按 lineNo 升序排列）
     * @return 节点草稿列表（未校验），按 nodeNo 升序排序
     *         - 第一个元素永远是根 DOCUMENT 节点
     *         - 章节节点深度由标题层级决定
     *         - 列表节点深度由缩进和栈状态决定
     */
    public List<DocumentStructureNodeDraft> resolve(String documentTitle,
                                                    List<DocumentStructureSignal> signals) {

        // ── Step 1: 创建根节点 ───────────────────────────────────────
        List<DocumentStructureNodeDraft> drafts = new ArrayList<>();  // 存放所有节点草稿
        DocumentStructureNodeDraft root = new DocumentStructureNodeDraft();  // 创建根节点

        // 根节点固定 nodeNo=1，depth=0，type=DOCUMENT
        root.setNodeNo(1);                                              // 节点编号=1（固定）
        root.setLineNo(0);                                              // 行号=0（不对应任何物理行）
        root.setNodeType(DocumentStructureNodeTypeEnum.DOCUMENT.getCode());  // 类型=文档根
        root.setParentNodeNo(null);                                     // 根节点无父节点
        root.setDepth(0);                                               // 根节点深度=0
        root.setNodeCode("");                                           // 无编码
        root.setTitle(StrUtil.blankToDefault(documentTitle, "文档"));   // 标题=文档标题
        root.setAnchorText(StrUtil.blankToDefault(documentTitle, "文档"));  // 锚点文本
        root.setCanonicalPath("/document");                             // 规范路径=/document
        root.setSectionPath("");                                        // 章节路径=空
        root.setSourceFamily("document");                               // 来源家族=document
        root.setConfidence(1.0D);                                       // 置信度=1.0
        drafts.add(root);                                               // 加入列表

        // ── Step 2: 初始化状态变量 ───────────────────────────────────
        int nextNodeNo = 2;                           // 下一个节点编号（从 2 开始）
        DocumentStructureNodeDraft currentSection = root;   // 当前章节节点（默认根节点）
        DocumentStructureNodeDraft currentListItem = null;  // 当前列表项节点（默认为空）

        // 列表栈：用于处理列表嵌套（如缩进级别不同的列表项）
        // Deque 的 last 元素 = 最内层（最近）的列表上下文
        Deque<ListContext> listStack = new ArrayDeque<>();

        // 按深度记录最近的章节节点编号：depth → nodeNo
        // 用于标题的父节点查找（depth-1 深度上最近的节点就是父节点）
        Map<Integer, Integer> latestHeadingByDepth = new LinkedHashMap<>();

        // 按数字路径记录最近的章节节点编号："1.2" → nodeNo
        // 用于多级数字编号标题的精确父节点查找
        Map<String, Integer> latestHeadingByNumericPath = new LinkedHashMap<>();

        // ── Step 3: 遍历信号列表 ─────────────────────────────────────
        for (DocumentStructureSignal signal : signals) {
            // 跳过 null 信号和 lineNo=0 的信号（DOCUMENT_TITLE，已由根节点表示）
            if (signal == null || signal.getLineNo() == 0) {
                continue;
            }

            // 根据信号类型分发处理
            switch (signal.getKind()) {

                // ── 3a) BLANK（空行） ──
                // 空行触发列表栈清除：之后出现的列表项将从上层的章节开始，不再嵌套
                case BLANK -> {
                    currentListItem = null;    // 清除当前列表项
                    listStack.clear();          // 清除整个列表栈
                }

                // ── 3b) NOISE（噪声） ──
                // 噪声被完全忽略，不做任何操作
                case NOISE -> {
                    // do nothing — 静默跳过
                }

                // ── 3c) BODY / TABLE_ROW / QUOTE（正文行） ──
                // 正文行追加到最近的标题节点或列表项节点的 content 中
                case TABLE_ROW, QUOTE, BODY -> {
                    appendBody(signal, currentSection, currentListItem, root, drafts);
                }

                // ── 3d) STEP_ITEM / LIST_ITEM（列表项） ──
                // 创建列表节点，通过缩进级别决定嵌套关系
                case STEP_ITEM, LIST_ITEM -> {
                    // 确定列表节点的父节点：通过缩进级别在 listStack 中查找
                    DocumentStructureNodeDraft listParent = resolveListParent(
                        signal, currentSection == null ? root : currentSection, listStack, root);
                    // 创建列表节点
                    DocumentStructureNodeDraft listNode = buildListNode(signal, nextNodeNo++, listParent);
                    drafts.add(listNode);        // 加入草稿列表
                    currentListItem = listNode;  // 更新当前列表项
                    registerListContext(signal, listNode, listStack);  // 注册到列表栈
                    // 列表项正文也同步追加到当前章节
                    if (currentSection != null) {
                        currentSection.appendLine(signal.getNormalizedText());
                    }
                }

                // ── 3e) HEADING / HEADING_CANDIDATE（标题） ──
                // 标题节点是层级树的核心，需要计算深度和父节点
                case HEADING, HEADING_CANDIDATE -> {
                    // 构建标题节点：自动计算深度、查找父节点、更新映射表
                    DocumentStructureNodeDraft headingNode = buildHeadingNode(
                        signal,
                        nextNodeNo++,                        // 分配节点编号
                        drafts,                              // 已有节点列表（用于查找父节点）
                        latestHeadingByDepth,                // 按深度记录的最近标题映射
                        latestHeadingByNumericPath           // 按数字路径记录的标题映射
                    );
                    drafts.add(headingNode);   // 加入草稿列表
                    currentSection = headingNode;  // 更新当前章节
                    currentListItem = null;         // 清除当前列表项（新标题开始新段落）
                    listStack.clear();              // 清除列表栈
                }

                // ── 3f) 默认分支 → 同 BODY 处理 ──
                default -> appendBody(signal, currentSection, currentListItem, root, drafts);
            }
        }

        // ── Step 4: 按 nodeNo 排序后返回 ─────────────────────────────
        drafts.sort(Comparator.comparing(DocumentStructureNodeDraft::getNodeNo));
        return drafts;
    }

    /**
     * 将正文行追加到最近的标题节点或列表项节点 — resolve 的正文处理分支。
     * <p>
     * 追加规则：
     * <ul>
     *   <li>如果有当前列表项 → 追加到列表项</li>
     *   <li>如果有当前章节 → 追加到章节</li>
     *   <li>如果既无列表项也无章节 → 追加到根</li>
     *   <li>特殊：同时有列表项和章节时，正文行同时追加到两者（列表项显示时继承章节上下文）</li>
     * </ul>
     *
     * @param signal            正文信号
     * @param currentSection    当前章节节点
     * @param currentListItem   当前列表项节点
     * @param root              根节点（兜底目标）
     * @param drafts            节点草稿列表
     */
    private void appendBody(DocumentStructureSignal signal,
                            DocumentStructureNodeDraft currentSection,
                            DocumentStructureNodeDraft currentListItem,
                            DocumentStructureNodeDraft root,
                            List<DocumentStructureNodeDraft> drafts) {
        // 获取行的标准化文本
        String line = signal == null ? "" : signal.getNormalizedText();
        // 空行不追加
        if (StrUtil.isBlank(line)) {
            return;
        }

        // 确定追加目标：优先列表项，其次章节，兜底根节点
        DocumentStructureNodeDraft target = currentListItem != null
            ? currentListItem
            : (currentSection == null ? root : currentSection);

        // 追加到目标节点
        target.appendLine(line);

        // 双归属：如果同时有列表项和章节节点（且列表项不是章节节点本身），
        // 正文行也追加到章节节点，确保章节的 content 包含所有子行
        if (currentListItem != null
            && currentSection != null
            && !Objects.equals(currentSection.getNodeNo(), currentListItem.getNodeNo())) {
            currentSection.appendLine(line);
        }

        // 保护：如果当前章节为空且目标不是根节点，行也要追加到根节点
        if (currentSection == null && target != root) {
            root.appendLine(line);
        }
    }

    /**
     * 创建列表节点 — resolve 的列表项处理分支。
     * <p>
     * 根据信号类型设置节点类型：
     * <ul>
     *   <li>STEP_ITEM → STEP 类型节点</li>
     *   <li>LIST_ITEM → LIST_ITEM 类型节点</li>
     * </ul>
     * <p>
     * 深度 = 父节点深度 + 1。
     * 节点的内容自动包含信号本身的文本（通过 appendLine 添加）。
     *
     * @param signal  列表信号
     * @param nodeNo  分配的节点编号
     * @param parent  父节点
     * @return 创建完成的列表节点草稿
     */
    private DocumentStructureNodeDraft buildListNode(DocumentStructureSignal signal,
                                                     int nodeNo,
                                                     DocumentStructureNodeDraft parent) {
        DocumentStructureNodeDraft draft = new DocumentStructureNodeDraft();
        draft.setNodeNo(nodeNo);                                        // 节点编号
        draft.setLineNo(signal.getLineNo());                            // 行号
        draft.setNodeType(signal.getKind() == DocumentStructureSignalKind.STEP_ITEM
            ? DocumentStructureNodeTypeEnum.STEP.getCode()              // 步骤节点
            : DocumentStructureNodeTypeEnum.LIST_ITEM.getCode());       // 列表项节点
        draft.setParentNodeNo(parent == null ? 1 : parent.getNodeNo());// 父节点编号
        draft.setDepth((parent == null ? 0 : parent.getDepth()) + 1);  // 深度 = 父深度 + 1
        draft.setNodeCode(StrUtil.blankToDefault(signal.getNodeCode(),  // 节点编码
            signal.getItemIndex() == null ? "" : String.valueOf(signal.getItemIndex())));
        draft.setTitle(signal.getTitle());                               // 标题
        draft.setAnchorText(StrUtil.blankToDefault(                     // 锚点文本
            signal.getNormalizedText(), signal.getTitle()));
        draft.setItemIndex(signal.getItemIndex());                       // 序号
        draft.setSourceFamily(signal.getKind() == DocumentStructureSignalKind.STEP_ITEM
            ? "step" : "list");                                         // 来源家族
        draft.setConfidence(signal.getConfidence());                     // 置信度
        draft.appendLine(signal.getNormalizedText());                   // 包含自身文本
        return draft;
    }

    /**
     * 确定列表项的父节点 — resolve 的列表处理分支。
     * <p>
     * 通过列表栈（{@link #listStack}）实现嵌套：
     * <ul>
     *   <li>栈中维护了当前已处理的列表项及其缩进级别</li>
     *   <li>新列表项的缩进级别 ≥ 栈顶 → 嵌套在栈顶内（栈顶 item 为父节点）</li>
     *   <li>新列表项的缩进级别 < 栈顶 → 弹出栈顶，直到缩进级别符合</li>
     *   <li>栈为空 → 父节点为当前章节</li>
     * </ul>
     *
     * @param signal          列表信号
     * @param currentSection  当前章节节点
     * @param listStack       列表栈（item → indentLevel）
     * @param root            根节点（兜底）
     * @return 父节点
     */
    private DocumentStructureNodeDraft resolveListParent(DocumentStructureSignal signal,
                                                         DocumentStructureNodeDraft currentSection,
                                                         Deque<ListContext> listStack,
                                                         DocumentStructureNodeDraft root) {
        // 获取当前列表项的缩进级别
        int indentLevel = safeIndentLevel(signal);

        // 弹出所有缩进 ≥ 当前缩进的栈顶元素（缩进更大的列表项已经结束）
        while (!listStack.isEmpty() && listStack.peekLast().indentLevel() >= indentLevel) {
            listStack.removeLast();
        }

        // 如果栈非空且当前缩进 > 栈顶缩进 → 当前项嵌套在栈顶项内
        if (!listStack.isEmpty() && indentLevel > listStack.peekLast().indentLevel()) {
            return listStack.peekLast().node();  // 父节点 = 栈顶列表项
        }

        // 栈为空（或不嵌套）→ 父节点为当前章节（或根）
        return currentSection == null ? root : currentSection;
    }

    /**
     * 将当前列表项注册到列表栈 — resolve 的列表处理分支。
     * <p>
     * 注册后，后续缩进级别更大的列表项将以此项为父节点（嵌套）。
     * 注册前会先弹出所有缩进 ≥ 当前缩进的栈顶元素（清理已结束的嵌套）。
     *
     * @param signal   列表信号
     * @param listNode 创建的列表节点草稿
     * @param listStack 列表栈
     */
    private void registerListContext(DocumentStructureSignal signal,
                                     DocumentStructureNodeDraft listNode,
                                     Deque<ListContext> listStack) {
        // 获取缩进级别
        int indentLevel = safeIndentLevel(signal);
        // 弹出所有缩进 ≥ 当前缩进的栈顶元素
        while (!listStack.isEmpty() && listStack.peekLast().indentLevel() >= indentLevel) {
            listStack.removeLast();
        }
        // 将当前列表项压入栈顶
        listStack.addLast(new ListContext(listNode, indentLevel));
    }

    /**
     * 构建标题节点 — resolve 的标题处理分支，核心方法。
     * <p>
     * 标题节点的构建分三步：
     * <ol>
     *   <li><b>确定深度</b>（{@link #resolveHeadingDepth}）：
     *       根据信号类型、levelHint、numericPath 等信息，计算标题在树中的深度</li>
     *   <li><b>查找父节点</b>（{@link #resolveHeadingParentNodeNo}）：
     *       根据深度和 numericPath，在已有节点中查找最合适的父节点</li>
     *   <li><b>更新映射表</b>：将新节点记录到 latestHeadingByDepth 和 latestHeadingByNumericPath</li>
     * </ol>
     *
     * @param signal                   标题信号
     * @param nodeNo                   分配的节点编号
     * @param drafts                   已有节点列表（用于查找父节点）
     * @param latestHeadingByDepth     深度→最近节点编号的映射
     * @param latestHeadingByNumericPath 数字路径→节点编号的映射
     * @return 构建完成的标题节点草稿
     */
    private DocumentStructureNodeDraft buildHeadingNode(DocumentStructureSignal signal,
                                                        int nodeNo,
                                                        List<DocumentStructureNodeDraft> drafts,
                                                        Map<Integer, Integer> latestHeadingByDepth,
                                                        Map<String, Integer> latestHeadingByNumericPath) {
        // ── ① 确定深度 ──
        int depth = resolveHeadingDepth(signal, drafts, latestHeadingByDepth, latestHeadingByNumericPath);

        // ── ② 查找父节点编号 ──
        Integer parentNodeNo = resolveHeadingParentNodeNo(
            signal, depth, drafts, latestHeadingByDepth, latestHeadingByNumericPath);

        // ── ③ 创建节点对象 ──
        DocumentStructureNodeDraft draft = new DocumentStructureNodeDraft();
        draft.setNodeNo(nodeNo);                                          // 节点编号
        draft.setLineNo(signal.getLineNo());                              // 行号
        draft.setNodeType(DocumentStructureNodeTypeEnum.SECTION.getCode());   // 类型=章节
        draft.setParentNodeNo(parentNodeNo);                              // 父节点编号
        draft.setDepth(depth);                                            // 深度
        draft.setNodeCode(StrUtil.blankToDefault(signal.getNodeCode(), ""));  // 编码
        draft.setTitle(signal.getTitle());                                // 标题
        draft.setAnchorText(buildHeadingAnchorText(signal));              // 锚点文本
        draft.setNumericPath(signal.getNumericPath() == null              // 数字路径
            ? List.of()
            : new ArrayList<>(signal.getNumericPath()));
        draft.setSourceFamily(resolveHeadingFamily(signal));              // 来源家族
        draft.setConfidence(signal.getConfidence());                      // 置信度
        draft.appendLine(signal.getNormalizedText());                     // 包含标题行自身

        // ── ④ 更新映射表 ──
        // 深度映射：清除当前深度及更深的所有记录（同级/下级标题已被替换）
        latestHeadingByDepth.entrySet().removeIf(entry -> entry.getKey() >= depth);
        latestHeadingByDepth.put(depth, nodeNo);                          // 记录当前深度

        // 数字路径映射：记录数字编码（如 "1.2" → nodeNo）
        String numericKey = numericKey(draft.getNumericPath());
        if (StrUtil.isNotBlank(numericKey)) {
            latestHeadingByNumericPath.put(numericKey, nodeNo);
        }

        return draft;
    }

    /**
     * 解析标题在树中的深度 — buildHeadingNode 的子方法。
     * <p>
     * 深度决定策略（按优先级递减）：
     * <ol>
     *   <li>Markdown 标题（# → 1, ## → 2）：levelHint 作为深度</li>
     *   <li>"第X章" / "附录"：固定深度 = 1</li>
     *   <li>多级数字编号（如 1.2.3）：
     *       <ul>
     *         <li>优先通过 numericPath[0..n-1] 查找精确父节点 → 父深度 + 1</li>
     *         <li>其次通过 numericPath[0] 查找章节点 → 父深度 + 1</li>
     *         <li>兜底：numericPath.size() 作为深度</li>
     *       </ul></li>
     *   <li>其他标题（含中文提纲、单级数字、Plain 标题候选）：max(1, levelHint)</li>
     * </ol>
     *
     * @param signal                   标题信号
     * @param drafts                   已有节点列表
     * @param latestHeadingByDepth     深度→最近节点编号的映射
     * @param latestHeadingByNumericPath 数字路径→节点编号的映射
     * @return 计算出的深度（最小为 1）
     */
    private int resolveHeadingDepth(DocumentStructureSignal signal,
                                    List<DocumentStructureNodeDraft> drafts,
                                    Map<Integer, Integer> latestHeadingByDepth,
                                    Map<String, Integer> latestHeadingByNumericPath) {
        // 获取标题家族类型（markdown / chapter / appendix / decimal / plain）
        String family = resolveHeadingFamily(signal);
        // 获取数字路径（可能为空）
        List<Integer> numericPath = signal.getNumericPath() == null
            ? List.of()
            : signal.getNumericPath();

        // ── Markdown 标题：levelHint = # 个数，直接作为深度 ──
        if ("markdown".equals(family)) {
            return Math.max(1, safeLevel(signal.getLevelHint(), 1));
        }

        // ── "第X章" / "附录"：固定深度 = 1 ──
        if ("chapter".equals(family) || "appendix".equals(family)) {
            return 1;
        }

        // ── 多级数字编号（如 1.2.3） ──
        if ("decimal".equals(family)) {
            // 只有一级（如 "1"、"2"）→ 深度 = 1
            if (numericPath.size() <= 1) {
                return 1;
            }
            // 优先：查找数字路径的精确父节点（如 "1.2.3" → 找 "1.2"）
            List<Integer> parentPath = numericPath.subList(0, numericPath.size() - 1);
            Integer parentNodeNo = latestHeadingByNumericPath.get(numericKey(parentPath));
            if (parentNodeNo != null) {
                DocumentStructureNodeDraft parent = findByNodeNo(drafts, parentNodeNo);
                if (parent != null) {
                    return parent.getDepth() + 1;  // 父深度 + 1
                }
            }
            // 次优：查找章节点（如 "1.2.3" → 找 "1"）
            Integer chapterParent = latestHeadingByNumericPath.get(
                numericKey(List.of(numericPath.get(0))));
            if (chapterParent != null) {
                DocumentStructureNodeDraft parent = findByNodeNo(drafts, chapterParent);
                if (parent != null) {
                    return parent.getDepth() + 1;
                }
            }
            // 兜底：数字路径长度作为深度（如 [1,2,3] → depth=3）
            return numericPath.size();
        }

        // ── 其他标题（中文提纲 / 单级数字 / Plain 标题候选等） ──
        // 用 levelHint 作为深度，至少为 1
        return Math.max(1, safeLevel(signal.getLevelHint(), 1));
    }

    /**
     * 解析标题节点的父节点编号 — buildHeadingNode 的子方法。
     * <p>
     * 查找策略：
     * <ul>
     *   <li>"第X章" / "附录" → 根节点（nodeNo=1）</li>
     *   <li>多级数字编号 → 优先按数字路径找精确父节点，兜底按 depth 就近查找</li>
     *   <li>其他标题 → 从 depth-1 开始向下查找最近的已注册标题节点</li>
     * </ul>
     *
     * @param signal                   标题信号
     * @param depth                    已计算出的深度
     * @param drafts                   已有节点列表
     * @param latestHeadingByDepth     深度→最近节点编号的映射
     * @param latestHeadingByNumericPath 数字路径→节点编号的映射
     * @return 父节点编号（至少为 1，即根节点）
     */
    private Integer resolveHeadingParentNodeNo(DocumentStructureSignal signal,
                                               int depth,
                                               List<DocumentStructureNodeDraft> drafts,
                                               Map<Integer, Integer> latestHeadingByDepth,
                                               Map<String, Integer> latestHeadingByNumericPath) {
        // 获取标题家族类型
        String family = resolveHeadingFamily(signal);
        // 获取数字路径
        List<Integer> numericPath = signal.getNumericPath() == null
            ? List.of()
            : signal.getNumericPath();

        // ── "第X章" / "附录" → 父节点 = 根节点（nodeNo=1） ──
        if ("chapter".equals(family) || "appendix".equals(family)) {
            return 1;
        }

        // ── 多级数字编号：优先按数字路径查找精确父节点 ──
        if ("decimal".equals(family) && numericPath.size() > 1) {
            // 查找精确父节点（如 "1.2.3" → 找 "1.2"）
            List<Integer> exactParentPath = numericPath.subList(0, numericPath.size() - 1);
            Integer exactParent = latestHeadingByNumericPath.get(numericKey(exactParentPath));
            if (exactParent != null) {
                return exactParent;
            }
            // 查找章节点（如 "1.2.3" → 找 "1"）
            Integer chapterParent = latestHeadingByNumericPath.get(
                numericKey(List.of(numericPath.get(0))));
            if (chapterParent != null) {
                return chapterParent;
            }
        }

        // ── 兜底：按 depth 就近查找父节点 ──
        // 从 depth-1 开始，向下搜索最近的已注册标题节点
        return findNearestParentByDepth(depth, latestHeadingByDepth);
    }

    /**
     * 按深度查找最近的父节点编号 — resolveHeadingParentNodeNo 的兜底策略。
     * <p>
     * 从 depth-1 开始，逐级向下搜索，找到最近的有记录的深度。
     * 例如 depth=3 → 先找 depth=2，没有则找 depth=1，再没有则返回根节点 1。
     */
    private Integer findNearestParentByDepth(int depth,
                                             Map<Integer, Integer> latestHeadingByDepth) {
        // 从 depth-1 开始向下遍历
        for (int candidateDepth = depth - 1; candidateDepth >= 1; candidateDepth--) {
            Integer parentNodeNo = latestHeadingByDepth.get(candidateDepth);
            if (parentNodeNo != null) {
                return parentNodeNo;  // 找到最近的父节点
            }
        }
        // 没有找到 → 返回根节点（nodeNo=1）
        return 1;
    }

    /**
     * 解析标题的家族类型 — 用于决定深度和父节点的查找策略。
     * <p>
     * 根据信号的原因标签（reasons）判断：
     * <ul>
     *   <li>markdown-heading → "markdown"</li>
     *   <li>chapter-heading → "chapter"</li>
     *   <li>appendix-heading → "appendix"</li>
     *   <li>decimal-heading → "decimal"</li>
     *   <li>single-digit-ambiguous-heading → "decimal"</li>
     *   <li>其他 → "plain"</li>
     * </ul>
     */
    private String resolveHeadingFamily(DocumentStructureSignal signal) {
        // 空信号或无原因标签 → 默认 "plain"
        if (signal == null || signal.getReasons() == null) {
            return "plain";
        }
        // 按原因标签判断家族
        if (signal.getReasons().contains("markdown-heading")) {
            return "markdown";
        }
        if (signal.getReasons().contains("chapter-heading")) {
            return "chapter";
        }
        if (signal.getReasons().contains("appendix-heading")) {
            return "appendix";
        }
        if (signal.getReasons().contains("decimal-heading")) {
            return "decimal";
        }
        if (signal.getReasons().contains("single-digit-ambiguous-heading")) {
            return "decimal";
        }
        // 其他 → plain
        return "plain";
    }

    /**
     * 构建标题节点的锚点文本 — 显示用完整标题。
     * <p>
     * 规则：
     * <ul>
     *   <li>如果没有编码 → 直接用标题</li>
     *   <li>如果标题已包含编码前缀（如 "1.2 数据校验"）→ 直接用标题</li>
     *   <li>否则 → "编码 + 标题"（如 "1.2" + "数据校验" → "1.2 数据校验"）</li>
     * </ul>
     */
    private String buildHeadingAnchorText(DocumentStructureSignal signal) {
        // 获取编码（如 "1.2"）和标题（如 "数据校验"）
        String code = StrUtil.blankToDefault(signal.getNodeCode(), "").trim();
        String title = StrUtil.blankToDefault(signal.getTitle(), "").trim();

        // 无编码 → 直接用标题
        if (StrUtil.isBlank(code)) {
            return title;
        }
        // 标题已包含编码前缀（如 "1.2 数据校验"）→ 直接用标题
        if (title.startsWith(code)) {
            return title;
        }
        // 否则拼接为 "编码 标题"
        return code + " " + title;
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
        // 将每个数字转为字符串，用 "." 连接
        return numericPath.stream()
            .map(String::valueOf)
            .reduce((left, right) -> left + "." + right)
            .orElse("");
    }

    /**
     * 安全获取 levelHint — 处理 null 和 <= 0 的情况。
     *
     * @param levelHint    层级提示（可能为 null）
     * @param defaultValue 默认值
     * @return levelHint（合法时）或 defaultValue
     */
    private int safeLevel(Integer levelHint, int defaultValue) {
        return levelHint == null || levelHint <= 0 ? defaultValue : levelHint;
    }

    /**
     * 安全获取信号的缩进级别 — 处理 null 和负数。
     *
     * @param signal 信号
     * @return 缩进级别（最小为 0）
     */
    private int safeIndentLevel(DocumentStructureSignal signal) {
        if (signal == null || signal.getIndentLevel() == null || signal.getIndentLevel() < 0) {
            return 0;
        }
        return signal.getIndentLevel();
    }

    /**
     * 按节点编号在草稿列表中查找节点 — 辅助方法。
     *
     * @param drafts 草稿列表
     * @param nodeNo 待查找的节点编号
     * @return 找到的节点草稿，未找到返回 null
     */
    private DocumentStructureNodeDraft findByNodeNo(List<DocumentStructureNodeDraft> drafts, Integer nodeNo) {
        // nodeNo 为 null → 未找到
        if (nodeNo == null) {
            return null;
        }
        // 遍历查找
        for (DocumentStructureNodeDraft draft : drafts) {
            if (draft != null && nodeNo.equals(draft.getNodeNo())) {
                return draft;
            }
        }
        return null;
    }

    /**
     * 列表上下文记录 — 用于列表栈实现嵌套。
     * <p>
     * 记录每个列表项的节点引用和缩进级别：
     * <ul>
     *   <li>缩进越大 → 嵌套越深</li>
     *   <li>新列表项缩进 > 栈顶缩进 → 嵌套在栈顶项下</li>
     *   <li>新列表项缩进 ≤ 栈顶缩进 → 弹出栈顶直到缩进匹配</li>
     * </ul>
     *
     * @param node        列表节点草稿
     * @param indentLevel 缩进级别
     */
    private record ListContext(
        DocumentStructureNodeDraft node,
        int indentLevel
    ) {
    }
}
