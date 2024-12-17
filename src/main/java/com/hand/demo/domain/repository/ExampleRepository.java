package com.hand.demo.domain.repository;

import com.hand.demo.api.dto.FileConfigUploadDTO;
import com.hand.demo.domain.entity.Example;
import org.hzero.mybatis.base.BaseRepository;

import java.util.List;

/**
 * Repository
 */
public interface ExampleRepository extends BaseRepository<Example> {
    List<FileConfigUploadDTO> selectList(FileConfigUploadDTO record);
}
