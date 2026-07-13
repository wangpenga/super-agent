package org.javaup.ai.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.eval.data.ExchangeProxy;

/**
 * 对话轮次归档 Mapper（只读）
 * <p>
 * 查询 super_agent_chat_exchange 表获取大模型的真实回答。
 *
 * @author 阿星不是程序员
 */
@Mapper
public interface ExchangeProxyMapper extends BaseMapper<ExchangeProxy> {
}
