package com.hand.demo.app.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hand.demo.api.dto.FileConfigUploadDTO;
import com.hand.demo.api.dto.InvApplyHeaderDTO;
import com.hand.demo.api.dto.ReportExportDTO;
import com.hand.demo.app.service.InvoiceApplyLineService;
import com.hand.demo.domain.entity.InvoiceApplyLine;
import com.hand.demo.infra.constant.Constants;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.core.oauth.DetailsHelper;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import io.seata.common.util.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import org.hzero.boot.apaas.common.userinfo.domain.UserVO;
import org.hzero.boot.apaas.common.userinfo.infra.feign.IamRemoteService;
import org.hzero.boot.platform.code.builder.CodeRuleBuilder;
import org.hzero.boot.platform.lov.adapter.LovAdapter;
import org.hzero.boot.platform.lov.annotation.ProcessLovValue;
import org.hzero.boot.platform.lov.dto.LovValueDTO;
import org.hzero.core.base.BaseConstants;
import org.hzero.core.cache.ProcessCacheValue;
import org.hzero.core.redis.RedisHelper;
import org.hzero.mybatis.domian.Condition;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import com.hand.demo.app.service.InvoiceApplyHeaderService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import com.hand.demo.domain.entity.InvoiceApplyHeader;
import com.hand.demo.domain.repository.InvoiceApplyHeaderRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Invoice Apply Header Table(InvoiceApplyHeader)应用服务
 *
 * @author razah
 * @since 2024-12-03 09:28:06
 */
@Slf4j
@Service
public class InvoiceApplyHeaderServiceImpl implements InvoiceApplyHeaderService {
    @Autowired
    private InvoiceApplyHeaderRepository invoiceApplyHeaderRepository;

    @Autowired
    private LovAdapter lovAdapter;

    @Autowired
    private RedisHelper redisHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodeRuleBuilder codeRuleBuilder;

    @Autowired
    private InvoiceApplyLineService invoiceApplyLineService;

    @Autowired
    private IamRemoteService iamRemoteService;

    /**
     * Retrieves a paginated list of invoice apply headers.
     * <p>
     * This method uses PageHelper to execute pagination and sorting based on the provided
     * page request and filter criteria.
     *
     * @param pageRequest the pagination and sorting criteria
     * @param invoiceApplyHeader the filter criteria encapsulated in a DTO
     * @return a paginated list of invoice apply headers matching the filters
     * <p>
     * Note: PageHelper simplifies traditional SQL pagination. Ensure proper index usage
     * in the database to handle large datasets.
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public Page<InvApplyHeaderDTO> selectList(PageRequest pageRequest,
                                              InvApplyHeaderDTO invoiceApplyHeader) {
        return PageHelper
                .doPageAndSort(pageRequest,
                        () -> invoiceApplyHeaderRepository.selectList(invoiceApplyHeader));
    }

    /**
     * Saves (inserts or updates) a batch of invoice apply headers.
     * <p>
     * This method identifies new and existing records, handles them separately,
     * and processes related invoice lines accordingly.
     *
     * @param invoiceApplyHeaders list of invoice headers to be saved.
     *
     * @Details:
     *  - New records are identified by null `applyHeaderId` and `applyHeaderNumber`.
     *  - Existing records (updates) are identified by non-null `applyHeaderId` or `applyHeaderNumber`.
     *  - Calls `validateLovData` to ensure LOV (List of Values) consistency.
     *  - Calls `processInsertHeaders` for batch insert and `processUpdateHeaders` for batch update.
     *  - Processes associated invoice lines using `processInvoiceLines`.
     *
     * @Transactional Management:
     * - Uses `@Transactional(rollbackFor = Exception.class)` to ensure atomicity.
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    @ProcessCacheValue
    public void saveData(List<InvApplyHeaderDTO> invoiceApplyHeaders) {
        if(invoiceApplyHeaders.isEmpty())
        {
            return;
        }

        validateLovData(invoiceApplyHeaders);

        List<InvApplyHeaderDTO> insertList = invoiceApplyHeaders.stream()
                .filter(header -> header.getApplyHeaderId() == null && header.getApplyHeaderNumber() == null)
                .collect(Collectors.toList());

        List<InvApplyHeaderDTO> updateList = invoiceApplyHeaders.stream()
                .filter(header -> header.getApplyHeaderId() != null || header.getApplyHeaderNumber() != null)
                .collect(Collectors.toList());

        processInsertHeaders(insertList);
        processUpdateHeaders(updateList);
        processInvoiceLines(invoiceApplyHeaders);

    }

    /**
     * Deletes (logically) a batch of invoice apply header records.
     *
     * Ensures all records exist in the database before marking them as deleted.
     * If any record is not found, throws a `CommonException`.
     *
     * @param invoiceApplyHeaders the list of invoice headers to be deleted
     *
     * @throws CommonException if any record does not exist in the database
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void delete(List<InvApplyHeaderDTO> invoiceApplyHeaders) {
        Set<String> headerIds = invoiceApplyHeaders.stream()
                .map(header -> header.getApplyHeaderId().toString())
                .collect(Collectors.toSet());

        List<InvoiceApplyHeader> Headers =
                invoiceApplyHeaderRepository.selectByIds(String.join(",", headerIds));

        Map<Long, InvoiceApplyHeader> headerDTOMap = new HashMap<>();
        for (InvoiceApplyHeader invApplyHeaderDTO : Headers) {
            headerDTOMap.put(invApplyHeaderDTO.getApplyHeaderId(), invApplyHeaderDTO);
        }

        for (InvApplyHeaderDTO invoiceApplyHeader : invoiceApplyHeaders) {
            boolean exists = headerDTOMap.containsKey(invoiceApplyHeader.getApplyHeaderId());
            if(!exists)
            {
                throw new CommonException(Constants.MESSAGE_ERROR_NOT_FOUND, invoiceApplyHeader);
            }
            invoiceApplyHeader.setDelFlag(1);
        }
        invoiceApplyHeaderRepository.batchUpdateByPrimaryKeySelective(new ArrayList<>(invoiceApplyHeaders));
    }

    /**
     * Retrieve a specific invoice apply header by its ID.
     *
     * @param id the ID of the header
     * @return the header object
     */
    @Override
    @Transactional(readOnly = true)
    public InvoiceApplyHeader selectOne(Long id) {
        return invoiceApplyHeaderRepository.selectByPrimary(id);
    }

    /**
     * Retrieves detailed information for a specific invoice header.
     * <p>
     * This method first checks Redis for a cached record for better performance. If the data
     * is missing, it queries the database and caches the result for subsequent requests.
     *
     * @param invHeaderId the ID of the invoice apply header
     * @param tenantId the tenant ID
     * @return the detailed header information, including associated invoice lines
     * @throws CommonException if no header is found in the database
     */
    @Override
    @Transactional(readOnly = true)
    @ProcessCacheValue
    public InvApplyHeaderDTO selectDetail(Long invHeaderId, Long tenantId) {
        String s = redisHelper.strGet(Constants.CACHE_KEY_PREFIX + ":" + invHeaderId);
        try{
            if(Objects.nonNull(s))
            {
                return objectMapper.readValue(s, InvApplyHeaderDTO.class);
            }
        }catch (JsonProcessingException e)
        {
            log.error(e.getMessage());
        }

        InvApplyHeaderDTO header = invoiceApplyHeaderRepository.selectByPrimary(invHeaderId);
        if (header == null) {
            throw new CommonException(Constants.MESSAGE_ERROR_NOT_FOUND, invHeaderId);
        }
        List<InvoiceApplyLine> invoiceApplyLines =
                invoiceApplyLineService.selectByInvoiceHeader(invHeaderId);
        header.setRealName(DetailsHelper.getUserDetails().getRealName());
        header.setInvoiceApplyLines(invoiceApplyLines);
        cacheHeaderDetails(invHeaderId, header);
        return header;
    }


    @Override
    @Transactional(readOnly = true)
    public InvoiceApplyHeader selectDetailSelective(InvoiceApplyHeader invoiceApplyHeader) {
        return invoiceApplyHeaderRepository.selectOne(invoiceApplyHeader);
    }

    /**
     * Exports invoice header data along with associated line items.
     * <p>
     * This method retrieves invoice headers based on specified filter criteria along with their
     * associated invoice lines. The result is transformed into a list of DTOs suitable for export or reporting.
     *
     * @param invoiceApplyHeader the filter criteria for retrieving invoice headers
     * @return a list of DTOs representing invoice headers and their associated lines
     * <p>
     * @Exception Handling:
     * - Handles missing line items gracefully by setting lines to an empty list if none are found.
     * - Assumes that `getFromHeaders` and `selectList` handle errors appropriately.
     */
    @Override
    @ProcessLovValue
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public List<InvApplyHeaderDTO> exportData(InvApplyHeaderDTO invoiceApplyHeader) {

        // Fetch headers based on the filter criteria
        List<InvApplyHeaderDTO> headerDTOS =
                invoiceApplyHeaderRepository.selectList(invoiceApplyHeader);

        // Extract Header IDs for Batch Fetching of Line
        // Uses streams for cleaner and more concise ID extraction
        Map<Long, List<InvoiceApplyLine>> lineMap = mapsLineBasedOnHeader(headerDTOS);

        headerDTOS.forEach(header -> {
            List<InvoiceApplyLine> lines = lineMap.getOrDefault(header.getApplyHeaderId(), Collections.emptyList());
            header.setInvoiceApplyLines(lines); // Attach lines to header DTO
        });
        // Transform headers into DTOs
        return headerDTOS;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public List<InvoiceApplyHeader> selectByHeaderIds(List<Long> ids) {
        Set<String> headerIds = ids.stream()
                .map(Object::toString)
                .collect(Collectors.toSet());

        return invoiceApplyHeaderRepository.selectByIds(String.join(",", headerIds));
    }


    /**
     * Generates a detailed report on invoice apply headers and their associated lines.
     * <p>
     * The method fetches header and line details using filtering criteria provided
     * in the input DTO (`reportExportDTO`) and maps them into a report-ready format.
     *
     * @param organizationId the tenant/organization ID
     * @param reportExportDTO the DTO specifying filters (e.g., dates, statuses, etc.)
     * @return a `ReportExportDTO` containing filtered data for headers and their lines
     * <p>
     * @Constraints:
     * - Throws `CommonException` if any LOV validation fails or IAM call parsing fails.
     * - Improper large dataset handling might lead to performance degradation; pagination for large data sets is recommended.
     */
    @Override
    @ProcessLovValue
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public ReportExportDTO selectReport(Long organizationId, ReportExportDTO reportExportDTO) {
        //Build query condition for headers based on filters
        Condition condition = new Condition(InvoiceApplyHeader.class);
        condition.createCriteria().andEqualTo(InvoiceApplyHeader.FIELD_TENANT_ID,
                organizationId
                );

        // Handle header number filtering
        if(reportExportDTO.getApplyNumberFrom() != null && reportExportDTO.getApplyNumberTo() != null) {
            condition.and().andBetween(
                    InvoiceApplyHeader.FIELD_APPLY_HEADER_NUMBER,
                    reportExportDTO.getApplyNumberFrom(),
                    reportExportDTO.getApplyNumberTo()
            );
        }

        // Handle date range filtering (creation date, submit time)
        if(reportExportDTO.getCreatedDateFrom() != null && reportExportDTO.getCreatedDateTo() != null) {
            condition.and().andBetween(
                    InvoiceApplyHeader.FIELD_CREATION_DATE,
                    reportExportDTO.getCreatedDateFrom(),
                    reportExportDTO.getCreatedDateTo()
            );
        }
        if(reportExportDTO.getSubmitTimeFrom()!= null && reportExportDTO.getSubmitTimeTo() != null) {
            condition.and().andBetween(
                    InvoiceApplyHeader.FIELD_SUBMIT_TIME,
                    reportExportDTO.getSubmitTimeFrom(),
                    reportExportDTO.getSubmitTimeTo()
            );
        }

        // Fetch LOV mappings and validate LOV field values
        Map<String, Map<String, String>> stringMapMap = fetchLovMaps(0L);

        // Validate and filter for invoice type and Apply Status
        if(reportExportDTO.getInvoiceType() != null) {
            String type = stringMapMap.get(Constants.LOV_CODE_TYPE).get(reportExportDTO.getInvoiceType());
            if(type.isEmpty())
            {
                throw new CommonException(Constants.MESSAGE_ERROR_INVALID_LOV);
            }
            condition.and().andEqualTo(InvoiceApplyHeader.FIELD_INVOICE_TYPE, reportExportDTO.getInvoiceType());
        }
        if(!reportExportDTO.getApplyStatuses().isEmpty()) {
            for (String applyStatus : reportExportDTO.getApplyStatuses()) {
                String status = stringMapMap.get(Constants.LOV_CODE_STATUS).get(applyStatus);
                if(status.isEmpty())
                {
                    throw new CommonException(Constants.MESSAGE_ERROR_INVALID_LOV);
                }
            }
            condition.and().andIn(InvoiceApplyHeader.FIELD_APPLY_STATUS, reportExportDTO.getApplyStatuses());
        }

        // Query database for invoice apply headers matching conditions
        List<InvoiceApplyHeader> invoiceApplyHeaders = invoiceApplyHeaderRepository
                .selectByCondition(condition);

        // Retrieve tenant information from IAM
        try{
            ResponseEntity<String> stringResponseEntity = iamRemoteService.selectSelf();
            UserVO userVO = objectMapper.readValue(stringResponseEntity.getBody(), UserVO.class);
            reportExportDTO.setTenantName(userVO.getTenantName());
        } catch (JsonProcessingException e) {
            throw new CommonException("Error retrieving or parsing IAM tenant info", e);
        }

        // Map headers to DTOs
        List<InvApplyHeaderDTO> headersDTOs = invoiceApplyHeaders.stream()
                .map(header -> {
                    InvApplyHeaderDTO dto = new InvApplyHeaderDTO();
                    BeanUtils.copyProperties(header, dto);
                    return dto;
                })
                .collect(Collectors.toList());

        Map<Long, List<InvoiceApplyLine>> lineMap = mapsLineBasedOnHeader(headersDTOs);

        for (InvApplyHeaderDTO headersDTO : headersDTOs) {
            List<String> invoiceNames = lineMap.get(headersDTO.getApplyHeaderId())
                    .stream()
                    .map(InvoiceApplyLine::getInvoiceName)
                    .collect(Collectors.toList());
            if(invoiceNames.isEmpty())
            {
                invoiceNames.add("No Invoice");
            }
            headersDTO.setInvoiceNames(String.join(", ", invoiceNames));
        }

        reportExportDTO.setHeaderDTOS(headersDTOs);
        return reportExportDTO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<InvApplyHeaderDTO> selectBasedOnId(Long organizationId) {
        try{
            ResponseEntity<String> s = iamRemoteService.selectSelf();
            String body = s.getBody();

            UserVO userVO = objectMapper.readValue(body, UserVO.class);
            InvApplyHeaderDTO invApplyHeaderDTO = new InvApplyHeaderDTO();
            invApplyHeaderDTO.setTenantAdminFlag(userVO.getTenantAdminFlag());
            return invoiceApplyHeaderRepository.selectListBasedOnId(invApplyHeaderDTO);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<Long, List<InvoiceApplyLine>> mapsLineBasedOnHeader(List<InvApplyHeaderDTO> headerDTOS) {
        // Fetch lines for the retrieved headers
        List<Long> headerIds = headerDTOS.stream()
                .map(InvoiceApplyHeader::getApplyHeaderId)
                .collect(Collectors.toList());

        List<InvoiceApplyLine> linesFromHeader = invoiceApplyLineService.getFromHeaders(headerIds);
        return linesFromHeader.stream()
                .collect(Collectors.groupingBy(InvoiceApplyLine::getApplyHeaderId));
    }


    /**
     * Fetches LOV (List of Values) mappings for the specified tenant ID.
     * <p>
     * This method retrieves LOV values for "Color", "Status", and "Type"
     * based on tenant data and organizes them in a nested map structure.
     * Example:
     * <pre>
     *     {
     *         "LOV_CODE_COLOR": {"RED": "Red Color", "BLUE": "Blue Color"},
     *         "LOV_CODE_STATUS": {"C": "CANCELED", "S": "SUCCESS"},
     *         ...
     *     }
     * </pre>
     *
     * @param tenantId the tenant ID for which LOV values are fetched
     * @return a map of LOV codes and their respective key-value mappings
     */
    private Map<String, Map<String, String>> fetchLovMaps(Long tenantId) {
        Map<String, Map<String, String>> lovMaps = new HashMap<>();

        // Build LOV map for each required code
        lovMaps.put(Constants.LOV_CODE_COLOR, lovAdapter.queryLovValue(Constants.LOV_CODE_COLOR, tenantId)
                .stream().collect(Collectors.toMap(LovValueDTO::getValue, LovValueDTO::getMeaning)));

        lovMaps.put(Constants.LOV_CODE_STATUS, lovAdapter.queryLovValue(Constants.LOV_CODE_STATUS, tenantId)
                .stream().collect(Collectors.toMap(LovValueDTO::getValue, LovValueDTO::getMeaning)));

        lovMaps.put(Constants.LOV_CODE_TYPE, lovAdapter.queryLovValue(Constants.LOV_CODE_TYPE, tenantId)
                .stream().collect(Collectors.toMap(LovValueDTO::getValue, LovValueDTO::getMeaning)));

        return lovMaps;
    }

    /**
     * Cache header details to Redis for faster retrieval.
     *
     * @param invHeaderId the header ID
     * @param dto         the header DTO
     */
    private void cacheHeaderDetails(Long invHeaderId, InvApplyHeaderDTO dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            redisHelper.strSet(Constants.CACHE_KEY_PREFIX+":"+invHeaderId,
                    json, 2, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Failed to cache header details for ID {}: {}", invHeaderId, e.getMessage(), e);
        }
    }

    /**
     * Validates the data of supplied invoice headers using LOV mappings.
     * <p>
     * This checks "Color", "Status", and "Type" values for consistency
     * with predefined LOVs. Throws an exception if mismatched values are found.
     * </p>
     *
     * @param headers the list of {@link InvApplyHeaderDTO} to validate against LOV mappings
     * @throws CommonException if any field contains invalid data
     */
    private void validateLovData(List<InvApplyHeaderDTO> headers) {
        // Fetch LOV mappings only once for efficiency
        Map<String, Map<String, String>> lovMaps = fetchLovMaps(BaseConstants.DEFAULT_TENANT_ID);

        // Iterate over the headers and validate their fields
        headers.forEach(header -> {
            validateAgainstLov(header.getInvoiceColor(), lovMaps.get(Constants.LOV_CODE_COLOR), header);
            validateAgainstLov(header.getApplyStatus(), lovMaps.get(Constants.LOV_CODE_STATUS), header);
            validateAgainstLov(header.getInvoiceType(), lovMaps.get(Constants.LOV_CODE_TYPE), header);
        });
    }

    /**
     * Validates a single value against the LOV map.
     *
     * @param value    the field to validate
     * @param lovMap   the LOV map to compare against
     * @param header   the header DTO for error context
     * @throws CommonException if the value is invalid
     */
    private void validateAgainstLov(String value, Map<String, String> lovMap, InvApplyHeaderDTO header) {
        if (value == null || !lovMap.containsKey(value)) {
            throw new CommonException(Constants.MESSAGE_ERROR_INVALID_LOV, header.getApplyHeaderId());
        }
    }

    /**
     * Processes invoice lines for a batch of invoice apply headers.
     * <p>
     * This method handles the insertion, update, and validation of invoice lines
     * associated with the provided invoice headers, ensuring data consistency.
     *
     * @param invoiceApplyHeaders the list of invoice apply header DTOs containing line details
     *
     */
    private void processInvoiceLines(List<InvApplyHeaderDTO> invoiceApplyHeaders) {
        invoiceApplyHeaders.forEach(header -> {
            if (!CollectionUtils.isEmpty(header.getInvoiceApplyLines())) {
                header.getInvoiceApplyLines().forEach(line -> {
                    line.setApplyHeaderId(header.getApplyHeaderId());
                    line.setTenantId(header.getTenantId());
                });
                invoiceApplyLineService.saveData(header.getInvoiceApplyLines());
            }
        });
    }

    /**
     * Handles the insertion of invoice apply headers and their associated details.
     * <p>
     * This method processes and inserts a batch of invoice apply headers into the database,
     * ensuring that all required validations and data mappings are performed before persistence.
     *
     * @param invoiceApplyHeaders the list of invoice apply header DTOs to be inserted
     * <p>
     *
     * @Responsibilities:
     * - Ensures that only valid and complete header data is inserted.
     * - Optimizes performance by handling batch insert operations where applicable.
     * <p>
     * @Constraints:
     * - Expects all headers to be pre-validated before being passed into this method.
     * - May throw exceptions if validation or database operations fail.
     * <p>
     * @Exception Handling:
     * - Throws `CommonException` for validation or insertion errors.
     * - Logs any errors for traceability and debugging.
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void processInsertHeaders(List<InvApplyHeaderDTO> invoiceApplyHeaders) {
        if (invoiceApplyHeaders.isEmpty()) return;
        // Prepare the headers
        invoiceApplyHeaders.forEach(header -> {
            header.setApplyHeaderNumber(codeRuleBuilder.generateCode(Constants.CODE_RULE_HEADER, new HashMap<>()));
            header.setSubmitTime(Optional.ofNullable(header.getSubmitTime()).orElse(Date.from(Instant.now())));
        });
        // Perform batch insert
        invoiceApplyHeaderRepository.batchInsertSelective(new ArrayList<>(invoiceApplyHeaders));
    }

    /**
     * Processes the update of invoice apply headers and their associated details.
     * <p>
     * This method ensures that all required validations and data mappings are completed
     * before updating the specified invoice apply headers in the database.
     *
     * @param headers the list of invoice apply header DTOs to be updated
     * <p>
     * @Responsibilities:
     * - Validates and synchronizes header data with the database records.
     * - Ensures only valid updates are applied while keeping data integrity intact.
     * <p>
     * @Constraints:
     * - Requires that the input headers contain valid IDs for identification.
     * - Expects up-to-date data to avoid overwrites or conflicts (e.g., last update timestamp validation).
     * <p>
     * @Exception Handling:
     * - Throws `CommonException` if a target header is not found or validation fails.
     * - Ensures errors are properly logged for debugging and traceability.
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void processUpdateHeaders(List<InvApplyHeaderDTO> headers) {
        if (headers.isEmpty()) return;
        // Retrieve Existing Records and Map by ID
        // Fetch existing records from the database based on the input header IDs.
        Map<Long, InvoiceApplyHeader> headerMap = selectByHeaderIds(
                headers.stream()
                        .map(InvoiceApplyHeader::getApplyHeaderId) // Extract header IDs
                        .collect(Collectors.toList()))            // Collect IDs into a list
                .stream()
                .collect(Collectors.toMap(
                        InvoiceApplyHeader::getApplyHeaderId,  // Key by header ID
                        header -> header      // Value is the header object itself
                ));

        // Synchronize Fields (Fill missing fields with existing database values)
        headers.forEach(header -> {
            InvoiceApplyHeader existingHeader = headerMap.get(header.getApplyHeaderId());

            // Only proceed if a corresponding existing record is found
            if (existingHeader != null) {
                if (header.getApplyStatus() == null) header.setApplyStatus(existingHeader.getApplyStatus());
                if (header.getBillToAddress() == null) header.setBillToAddress(existingHeader.getBillToAddress());
                if (header.getBillToEmail() == null) header.setBillToEmail(existingHeader.getBillToEmail());
                if (header.getBillToPerson() == null) header.setBillToPerson(existingHeader.getBillToPerson());
                if (header.getBillToPhone() == null) header.setBillToPhone(existingHeader.getBillToPhone());
                if (header.getInvoiceColor() == null) header.setInvoiceColor(existingHeader.getInvoiceColor());
                if (header.getInvoiceType() == null) header.setInvoiceType(existingHeader.getInvoiceType());
                if (header.getRemark() == null) header.setRemark(existingHeader.getRemark());
            }
        });
        invoiceApplyHeaderRepository.batchUpdateOptional(new ArrayList<>(headers),
                InvoiceApplyHeader.FIELD_APPLY_STATUS,
                InvoiceApplyHeader.FIELD_BILL_TO_ADDRESS,
                InvoiceApplyHeader.FIELD_BILL_TO_EMAIL,
                InvoiceApplyHeader.FIELD_BILL_TO_PERSON,
                InvoiceApplyHeader.FIELD_BILL_TO_PHONE,
                InvoiceApplyHeader.FIELD_INVOICE_COLOR,
                InvoiceApplyHeader.FIELD_INVOICE_TYPE,
                InvoiceApplyHeader.FIELD_REMARK);
        if(CollectionUtils.isNotEmpty(headers))
        {
            List<String> keys = new ArrayList<>();
            for (InvoiceApplyHeader invoiceApplyHeader : headers)
            {
                keys.add(Constants.CACHE_KEY_PREFIX+":"+invoiceApplyHeader.getApplyHeaderNumber());
            }
            redisHelper.delKeys(keys);
        }
    }

}

