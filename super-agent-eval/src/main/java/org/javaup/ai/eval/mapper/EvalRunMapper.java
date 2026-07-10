package org.javaup.ai.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.eval.data.EvalRun;

/**
 * 评估运行记录 Mapper
 *
 * @author wangpeng
 */
@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRun> {
}
