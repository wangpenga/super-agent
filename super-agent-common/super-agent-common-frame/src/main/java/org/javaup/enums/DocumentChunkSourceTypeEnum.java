package org.javaup.enums;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 枚举定义
 * @author: wangpeng
 **/

public enum DocumentChunkSourceTypeEnum {
    ORIGINAL(1, "原文切块"),
    ENRICHED(2, "后处理补全文本");

    private final Integer code;

    private final String msg;

    DocumentChunkSourceTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentChunkSourceTypeEnum getRc(Integer code) {
        for (DocumentChunkSourceTypeEnum item : DocumentChunkSourceTypeEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}
