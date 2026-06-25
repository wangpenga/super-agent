package org.javaup.ai.chatagent.model.trace;

/**
 * 阶段执行状态 —— 写入 trace_stage.stage_state
 *
 * @author 阿星不是程序员
 */
public enum ConversationTraceStageState {

    /** 进行中 —— startStage 时写入，表示阶段正在执行 */
    RUNNING(1, "进行中"),
    /** 已完成 —— completeStage 时写入 */
    COMPLETED(2, "已完成"),
    /** 失败 —— failStage 时写入，同时写入 error_message */
    FAILED(3, "失败"),
    /** 跳过 —— 阶段被跳过（如 OPEN_CHAT 模式跳过 REWRITE 阶段） */
    SKIPPED(4, "跳过");

    /** 状态码 → 写入 trace_stage.stage_state */
    private final int code;
    /** 中文标签 */
    private final String label;

    ConversationTraceStageState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ConversationTraceStageState fromCode(Integer code) {
        if (code == null) {
            return RUNNING;
        }
        for (ConversationTraceStageState value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的阶段状态 code: " + code);
    }
}
