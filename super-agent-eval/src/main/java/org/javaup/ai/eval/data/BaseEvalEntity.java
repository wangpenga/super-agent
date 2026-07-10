package org.javaup.ai.eval.data;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.util.Date;

/**
 * 评估模块实体基类
 * <p>
 * 所有评估相关的数据库实体继承此类，统一管理创建时间、编辑时间和逻辑删除状态。
 *
 * @author wangpeng
 */
@Data
public class BaseEvalEntity {

    /** 创建时间（插入时自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /** 编辑时间（插入和更新时自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date editTime;

    /** 逻辑删除状态：1=正常 0=删除 */
    private Integer status;
}
