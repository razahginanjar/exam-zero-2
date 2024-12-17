package com.hand.demo.app.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hand.demo.api.dto.FileConfigUploadDTO;
import com.hand.demo.app.service.ExampleService;
import com.hand.demo.domain.repository.ExampleRepository;
import io.choerodon.core.oauth.CustomUserDetails;
import org.hzero.boot.apaas.common.userinfo.domain.UserVO;
import org.hzero.boot.apaas.common.userinfo.infra.feign.IamRemoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * ExampleServiceImpl
 */
@Service
public class ExampleServiceImpl implements ExampleService {
    @Autowired
    private ExampleRepository exampleRepository;

    @Autowired
    private IamRemoteService iamRemoteService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public List<FileConfigUploadDTO> getInfoUploadConfig(Long organization) {
        try{
            ResponseEntity<String> s = iamRemoteService.selectSelf();
            String body = s.getBody();

            UserVO userVO = objectMapper.readValue(body, UserVO.class);
            Boolean tenantAdminFlag = userVO.getTenantAdminFlag();
            FileConfigUploadDTO fileConfigUploadDTO = new FileConfigUploadDTO();
            if(tenantAdminFlag == null || !tenantAdminFlag)
            {
                fileConfigUploadDTO.setCreatedBy(userVO.getId());
            }
            fileConfigUploadDTO.setTenantId(userVO.getTenantId());
            return exampleRepository.selectList(fileConfigUploadDTO);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
