package com.aiteaching.kb.mapper;

import com.aiteaching.kb.entity.Conversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Conversation 表 Mapper（BaseMapper 提供 CRUD，条件构造器在 Service 层）。 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
