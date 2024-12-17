package com.hand.demo.infra.repository.impl;

import com.hand.demo.api.dto.FileConfigUploadDTO;
import com.hand.demo.domain.entity.Example;
import com.hand.demo.domain.repository.ExampleRepository;
import com.hand.demo.infra.mapper.ExampleMapper;
import com.netflix.discovery.converters.Auto;
import org.hzero.mybatis.base.impl.BaseRepositoryImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * Repository Impl
 */
@Repository
public class ExampleRepositoryImpl extends BaseRepositoryImpl<Example> implements ExampleRepository {

    @Autowired
    private ExampleMapper exampleMapper;


    @Override
    public List<FileConfigUploadDTO> selectList(FileConfigUploadDTO record) {
        return exampleMapper.selectList(record);
    }
}
