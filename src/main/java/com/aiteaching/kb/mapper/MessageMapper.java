package com.aiteaching.kb.mapper;

import com.aiteaching.kb.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Message 表 Mapper（BaseMapper 提供 CRUD，条件构造器在 Service 层）。 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
