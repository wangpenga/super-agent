package org.javaup.ai.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.eval.data.EvalQuestionResult;

/**
 * 单问题评估结果 Mapper
 *
 * @author wangpeng
 */
@Mapper
public interface EvalQuestionResultMapper extends BaseMapper<EvalQuestionResult> {
}
