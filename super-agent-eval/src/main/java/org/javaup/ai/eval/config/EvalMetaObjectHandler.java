package org.javaup.ai.eval.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis-Plus 自动填充处理器
 * <p>
 * 自动处理 {@code BaseEvalEntity} 中的 createTime、editTime、status 字段。
 *
 * @author 阿星不是程序员
 */
@Component
public class EvalMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
        this.strictInsertFill(metaObject, "editTime", Date.class, new Date());
        this.strictInsertFill(metaObject, "status", Integer.class, 1);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "editTime", Date.class, new Date());
    }
}
