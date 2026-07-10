package org.javaup.ai.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.eval.data.EvalMetricDaily;

/**
 * 评估指标日汇总 Mapper
 *
 * @author wangpeng
 */
@Mapper
public interface EvalMetricDailyMapper extends BaseMapper<EvalMetricDaily> {
}
