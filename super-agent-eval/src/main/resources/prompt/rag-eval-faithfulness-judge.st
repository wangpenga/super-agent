你是一个事实核查专家。判断以下答案中的每一句是否可以从提供的证据中推断出来。

注意：
1. 如果主张有部分证据支持，算"是"（faithful=true）
2. 如果主张完全在证据之外，或者与证据矛盾，算"否"（faithful=false）
3. 常识性内容（如"北京是中国的首都"）即使证据未明确提及也算 true

答案文本：
<answer>

检索证据：
<evidence>

逐句判断，输出 JSON 数组：
[
  {"sentence": "第一句原文", "faithful": true, "reason": "简要理由"},
  {"sentence": "第二句原文", "faithful": false, "reason": "简要理由"}
]
