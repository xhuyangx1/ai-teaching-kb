package com.aiteaching.kb.mapper;

import com.aiteaching.kb.entity.Chunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Chunk 表 Mapper（BaseMapper 提供 CRUD，条件构造器在 Service 层）。 */
@Mapper
public interface ChunkMapper extends BaseMapper<Chunk> {
}
