package com.hand.demo.app.service;

import com.hand.demo.api.dto.FileConfigUploadDTO;
import org.hzero.core.base.AopProxy;

import java.util.List;

/**
 * ExampleService
 */
public interface ExampleService extends AopProxy<ExampleService> {
    List<FileConfigUploadDTO> getInfoUploadConfig(Long organization);
}
