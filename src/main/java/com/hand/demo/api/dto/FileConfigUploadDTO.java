package com.hand.demo.api.dto;

import io.choerodon.mybatis.annotation.ModifyAudit;
import io.choerodon.mybatis.annotation.VersionAudit;
import lombok.Getter;
import lombok.Setter;
import org.hzero.boot.alert.vo.AuditDomain;

@Getter
@Setter
@ModifyAudit
//@Table(name = "file")
@VersionAudit
public class FileConfigUploadDTO extends AuditDomain {
    private Long tenantId;
    private String bucketName;
    private String contentType;
    private String directory;
    private String storageSize;
    private String storageUnit;
}
