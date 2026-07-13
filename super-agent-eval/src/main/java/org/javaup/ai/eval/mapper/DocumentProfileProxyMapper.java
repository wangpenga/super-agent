package org.javaup.ai.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.eval.data.DocumentProfileProxy;

/**
 * 文档画像代理 Mapper —— 只读
 * <p>
 * 查询 super_agent_document_profile 表获取 example_questions，
 * 作为测试集的种子问题来源。
 *
 * @author 阿星不是程序员
 */
@Mapper
public interface DocumentProfileProxyMapper extends BaseMapper<DocumentProfileProxy> {
}
