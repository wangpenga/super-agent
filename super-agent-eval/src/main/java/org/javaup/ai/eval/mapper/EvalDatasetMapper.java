package org.javaup.ai.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.eval.data.EvalDataset;

/**
 * 评估测试集 Mapper
 * <p>
 * 提供对 super_agent_eval_dataset 表的 CRUD 操作。
 *
 * @author wangpeng
 */
@Mapper
public interface EvalDatasetMapper extends BaseMapper<EvalDataset> {
}
