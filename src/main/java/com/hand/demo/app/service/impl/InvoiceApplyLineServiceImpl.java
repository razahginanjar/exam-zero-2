package com.hand.demo.app.service.impl;


import com.hand.demo.api.dto.InvApplyHeaderDTO;
import com.hand.demo.domain.entity.InvoiceApplyHeader;
import com.hand.demo.domain.repository.InvoiceApplyHeaderRepository;
import com.hand.demo.infra.constant.Constants;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import org.hzero.core.redis.RedisHelper;
import org.springframework.beans.factory.annotation.Autowired;
import com.hand.demo.app.service.InvoiceApplyLineService;
import org.springframework.stereotype.Service;
import com.hand.demo.domain.entity.InvoiceApplyLine;
import com.hand.demo.domain.repository.InvoiceApplyLineRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Invoice Apply Line Table(InvoiceApplyLine)应用服务
 *
 * @author razah
 * @since 2024-12-03 09:27:59
 */
@Service
public class InvoiceApplyLineServiceImpl implements InvoiceApplyLineService {
    @Autowired
    private InvoiceApplyLineRepository invoiceApplyLineRepository;

    @Autowired
    private InvoiceApplyHeaderRepository invoiceApplyHeaderRepository;

    @Autowired
    private RedisHelper redisHelper;

    /**
     * Retrieves a paginated list of invoice line entries based on filter criteria.
     *
     * @param pageRequest      Pagination and sorting information provided by the caller.
     * @param invoiceApplyLine Filter criteria to search for matching line entries.
     * @return A `Page` object containing the filtered list of `InvoiceApplyLine` entries.
     *
     * Workflow:
     * 1. The function queries the database for invoice-line data based on the filter `invoiceApplyLine`.
     * 2. Pagination details (such as page size and current page) are applied to the result.
     * 3. Results are returned in a `Page` object, making it easy for UI/middleware consumption.
     *
     * Performance Considerations:
     * - Ensure that indexes on the database are optimized for filtering fields in `InvoiceApplyLine`.
     * - Avoid excessive data loads by controlling the `PageRequest` size (e.g., consider a max limit).
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public Page<InvoiceApplyLine> selectList(PageRequest pageRequest, InvoiceApplyLine invoiceApplyLine) {
        return PageHelper
                .doPageAndSort(pageRequest,
                        () -> invoiceApplyLineRepository.selectList(invoiceApplyLine));
    }

    /**
     * Saves a list of invoice lines while ensuring headers are consistently updated.
     *
     * @param invoiceApplyLines List of `InvoiceApplyLine` entries to persist in the database.
     *
     * @Transactional Safety:
     * - Rolls back all changes, including inserts, updates, and header updates, if any exception occurs.
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void saveData(List<InvoiceApplyLine> invoiceApplyLines) {
        // Early validation: Avoid processing if the list is empty to save unnecessary computation.
        if (invoiceApplyLines.isEmpty()) {
            return;  // No data to process.
        }

        //calculate amounts and validate
        for (InvoiceApplyLine invoiceApplyLine : invoiceApplyLines) {
            calculateLineAmounts(invoiceApplyLine);
            // Ensure the header exists for the line
            if (invoiceApplyLine.getApplyHeaderId() == null) {
                throw new CommonException(Constants.MESSAGE_ERROR_HEADER_ID_CANNOT_BE_NULL);
            }
        }

        // Retrieve all necessary headers and lines once at the beginning
        Map<Long, InvoiceApplyHeader> headerCache = new HashMap<>();

        Map<Long, InvoiceApplyHeader> headerMap = getHeadersByLines(invoiceApplyLines);


        // Prepare lists for insert and update
        List<InvoiceApplyLine> insertList = invoiceApplyLines.stream()
                .filter(line -> line.getApplyLineId() == null)
                .collect(Collectors.toList());
        List<InvoiceApplyLine> updateList = invoiceApplyLines.stream()
                .filter(line -> line.getApplyLineId() != null)
                .collect(Collectors.toList());

        updateData(updateList, headerMap, headerCache);
        insertData(insertList, headerMap, headerCache);

        // Batch update headers at the end
        List<InvoiceApplyHeader> headerUpdates = new ArrayList<>(headerCache.values());
        if (!headerUpdates.isEmpty()) {
            invoiceApplyHeaderRepository.batchUpdateByPrimaryKeySelective(headerUpdates);
        }
        List<String> keys = new ArrayList<>();
        headerUpdates.forEach(
                invoiceApplyHeader -> {
                    keys.add(Constants.CACHE_KEY_PREFIX +":"+invoiceApplyHeader.getApplyHeaderId());
                }
        );
        redisHelper.delKeys(keys);
    }

    /**
     * Inserts a batch of new invoice lines and updates totals in their associated headers.
     *
     * @param requests    List of new `InvoiceApplyLine` entries to insert.
     * @param headerMap   Map of associated headers for quick lookup during processing.
     * @param headerCache Cache to store updated headers for future processing or persistence.
     *
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void insertData(List<InvoiceApplyLine> requests,
                            Map<Long, InvoiceApplyHeader> headerMap,
                            Map<Long, InvoiceApplyHeader> headerCache)
    {
        if (requests.isEmpty()) {
            return;
        }
        for (InvoiceApplyLine line : requests) {
            InvoiceApplyHeader headerDTO = headerMap.get(line.getApplyHeaderId());
            if (headerDTO == null) {
                throw new CommonException(Constants.MESSAGE_ERROR_NOT_FOUND);
            }

            // Use cache to retrieve or set the header
            InvoiceApplyHeader header = headerCache.computeIfAbsent(line.getApplyHeaderId(),
                    id -> headerDTO);
            // update amount header
            updateHeaderAmounts(header, line.getTotalAmount(), line.getTaxAmount(), line.getExcludeTaxAmount());
            // Ensure header is updated in the cache
            headerCache.put(line.getApplyHeaderId(), header);
        }
        invoiceApplyLineRepository.batchInsertSelective(requests);
    }


    /**
     * Updates a batch of invoice lines and recalculates associated header amounts based on changes (deltas).
     *
     * @param requests    List of invoice lines to be updated in the database.
     * @param headerMap   Map of associated headers, keyed by their IDs, for quick lookup during processing.
     * @param headerCache Cache to store updated header objects, which will undergo further batch updates later.
     *
     * @Error Handling:
     * - Any exception during the update process should trigger a rollback to maintain consistency.
     *
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void updateData(List<InvoiceApplyLine> requests,
                                              Map<Long, InvoiceApplyHeader> headerMap,
                                              Map<Long, InvoiceApplyHeader> headerCache)
    {
        if (requests.isEmpty()) {
            return;
        }
        Set<String> headerIds = requests.stream()
                .map(header -> header.getApplyLineId().toString())
                .collect(Collectors.toSet());
        Map<Long, InvoiceApplyLine> lineMap = invoiceApplyLineRepository
                .selectByIds(String.join(",", headerIds)).stream()
                .collect(Collectors.toMap(InvoiceApplyLine::getApplyLineId, line -> line));
        for (InvoiceApplyLine line : requests) {

            // Ensure the header exists for the line
            if (line.getApplyHeaderId() == null) {
                throw new CommonException(Constants.MESSAGE_ERROR_HEADER_ID_CANNOT_BE_NULL);
            }

            InvoiceApplyHeader headerDTO = headerMap.get(line.getApplyHeaderId());
            if (headerDTO == null) {
                throw new CommonException(Constants.MESSAGE_ERROR_NOT_FOUND);
            }

            // Use cache to retrieve or set the header
            InvoiceApplyHeader header = headerCache.computeIfAbsent(line.getApplyHeaderId(),
                    id -> headerDTO);

                // Existing line: Update operation
            InvoiceApplyLine existingLine = lineMap.get(line.getApplyLineId());
            if (existingLine == null) {
                throw new CommonException(Constants.MESSAGE_ERROR_INV_LINE_NOT_FOUND);
            }

            updateHeaderAmounts(header,
                        line.getTotalAmount().subtract(existingLine.getTotalAmount()),
                        line.getTaxAmount().subtract(existingLine.getTaxAmount()),
                        line.getExcludeTaxAmount().subtract(existingLine.getExcludeTaxAmount()));
            // Ensure header is updated in the cache
            headerCache.put(line.getApplyHeaderId(), header);

            if(line.getQuantity() == null)
            {
                line.setQuantity(existingLine.getQuantity());
            }
            if(line.getTaxRate() == null)
            {
                line.setTaxRate(existingLine.getTaxRate());
            }
            if(line.getContentName() == null)
            {
                line.setContentName(existingLine.getContentName());
            }
            if(line.getInvoiceName() == null)
            {
                line.setInvoiceName(existingLine.getInvoiceName());
            }
            if(line.getUnitPrice() == null)
            {
                line.setUnitPrice(existingLine.getUnitPrice());
            }
            if(line.getTaxClassificationNumber() == null)
            {
                line.setTaxClassificationNumber(existingLine.getTaxClassificationNumber());
            }
            if(line.getRemark() == null)
            {
                line.setRemark(existingLine.getRemark());
            }

        }

        invoiceApplyLineRepository.batchUpdateOptional(
                requests,
                InvoiceApplyLine.FIELD_INVOICE_NAME,
                InvoiceApplyLine.FIELD_CONTENT_NAME,
                InvoiceApplyLine.FIELD_QUANTITY,
                InvoiceApplyLine.FIELD_REMARK,
                InvoiceApplyLine.FIELD_TAX_CLASSIFICATION_NUMBER,
                InvoiceApplyLine.FIELD_TAX_RATE,
                InvoiceApplyLine.FIELD_UNIT_PRICE
        );
    }



    /**
     * Calculates and updates critical financial amounts for an invoice line based on provided business rules.
     *
     * @param line The `InvoiceApplyLine` entity for which amounts need to be computed.
     *
     */
    private void calculateLineAmounts(InvoiceApplyLine line) {
        BigDecimal totalAmount = line.getUnitPrice().multiply(line.getQuantity());
        BigDecimal taxAmount = totalAmount.multiply(line.getTaxRate());
        BigDecimal excludeTaxAmount = totalAmount.subtract(taxAmount);

        line.setTotalAmount(totalAmount);
        line.setTaxAmount(taxAmount);
        line.setExcludeTaxAmount(excludeTaxAmount);
    }



    /**
     * Updates the financial summary amounts for an invoice header based on associated lines.
     *
     * @param header   The `InvoiceApplyHeader` entity whose financial totals are to be adjusted.
     * @param totalDelta   total amount from line.
     * @param excludeDelta   exclude tax amount from line.
     * @param taxDelta   tax amount from line.
     *
     */
    private void updateHeaderAmounts(InvoiceApplyHeader header, BigDecimal totalDelta, BigDecimal taxDelta, BigDecimal excludeDelta) {
        header.setTotalAmount(header.getTotalAmount().add(totalDelta));
        header.setTaxAmount(header.getTaxAmount().add(taxDelta));
        header.setExcludeTaxAmount(header.getExcludeTaxAmount().add(excludeDelta));
    }



    /**
     * Removes a batch of invoice lines from the system and adjusts the corresponding invoice headers accordingly.
     *
     * @param invoiceApplyLines      List of `InvoiceApplyLine` entities to be deleted.
     *
     * @Error Handling:
     * - **Missing Header References**:
     *    - If a line does not correspond to a valid header in `headerMap`, this could throw an exception or log a warning, based on business requirements.
     * - **Empty Input**:
     *    - If the `lines` list is empty, the function should return immediately without making any database calls.
     * - **Transactional Context**:
     *    - The operation should ideally be wrapped in a transaction to ensure consistency between deletions and header updates.
     *
     * @Performance Considerations:
     * - Batch delete operations minimize the number of database calls.
     * - Using a caching mechanism for headers (`headerCache`) reduces redundant lookups or updates for lines sharing the same header.
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void removeData(List<InvoiceApplyLine> invoiceApplyLines) {
        // Retrieve necessary headers and lines once
        Map<Long, InvoiceApplyHeader> headerMap = getHeadersByLines(invoiceApplyLines);

        Set<String> headerIds = invoiceApplyLines.stream()
                .map(header -> header.getApplyLineId().toString())
                .collect(Collectors.toSet());
        Map<Long, InvoiceApplyLine> lineMap = invoiceApplyLineRepository
                .selectByIds(String.join(",", headerIds)).stream()
                .collect(Collectors.toMap(InvoiceApplyLine::getApplyLineId, line -> line));


        // Process each line to update header amounts
        for (InvoiceApplyLine line : invoiceApplyLines) {
            InvoiceApplyLine existingLine = lineMap.get(line.getApplyLineId());
            if (existingLine == null) {
                throw new CommonException(Constants.MESSAGE_ERROR_INV_LINE_NOT_FOUND);
            }

            InvoiceApplyHeader header = headerMap.get(line.getApplyHeaderId());

            if(Objects.isNull(header))
            {
                throw new CommonException(Constants.MESSAGE_ERROR_NOT_FOUND);
            }

            updateHeaderAmounts(header,
                    existingLine.getTotalAmount().negate(),
                    existingLine.getTaxAmount().negate(),
                    existingLine.getExcludeTaxAmount().negate());
        }

        // Batch delete lines and update headers
        invoiceApplyLineRepository
                .batchDeleteByPrimaryKey(invoiceApplyLines);
        invoiceApplyHeaderRepository
                .batchUpdateByPrimaryKeySelective(new ArrayList<>(headerMap.values()));
        List<String> keys = new ArrayList<>();
        headerMap.values().forEach(
                invApplyHeaderDTO -> {
                    keys.add(Constants.CACHE_KEY_PREFIX+":"+invApplyHeaderDTO.getApplyHeaderId());
                }
        );
        redisHelper.delKeys(keys);
    }

    /**
     * Retrieves a collection of invoice headers based on a set of given invoice lines.
     *
     * @param invoiceApplyLines      A list of `InvoiceApplyLine` entities that are used to identify associated headers.
     * @return A list of unique `InvoiceApplyHeader` objects associated with the provided lines.
     *
     * Error Handling:
     * - **Missing Header References**:
     *    - If a line references a header ID that is not found in the `headerMap`, the function gracefully skips over that line without throwing an error.
     * - **Empty or Null Inputs**:
     *    - If the `lines` list is empty or null, the function performs no operations and returns an empty result.
     */
    @Transactional(readOnly = true)
    public Map<Long, InvoiceApplyHeader> getHeadersByLines(List<InvoiceApplyLine> invoiceApplyLines) {
        Set<String> headerIds = invoiceApplyLines.stream()
                .map(header -> header.getApplyHeaderId().toString())
                .collect(Collectors.toSet());

        List<InvoiceApplyHeader> invoiceApplyHeaders =
                invoiceApplyHeaderRepository.selectByIds(String.join(",", headerIds));

         return invoiceApplyHeaders.stream()
                .collect(Collectors.toMap(InvoiceApplyHeader::getApplyHeaderId,
                        header -> header));
    }

    /**
     * Retrieves a list of `InvoiceApplyLine` entities associated with a specific invoice header.
     *
     * @param headerId The unique identifier of the `InvoiceApplyHeader` for which the lines are to be retrieved.
     * @return A list of `InvoiceApplyLine` objects corresponding to the provided header ID.
     *
     */
    @Override
    @Transactional(readOnly = true)
    public List<InvoiceApplyLine> selectByInvoiceHeader(Long headerId) {
        InvoiceApplyLine invoiceApplyLine = new InvoiceApplyLine();
        invoiceApplyLine.setApplyHeaderId(headerId);
        return invoiceApplyLineRepository.selectList(invoiceApplyLine);
    }

    /**
     * Retrieves a paginated list of `InvApplyHeaderDTO` objects based on the given query parameters.
     *
     * @param invoiceApplyLine       Condition for getting a list of line.
     * @return A `List<InvoiceApplyLine>` object containing the list of matching invoice line records.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InvoiceApplyLine> selectList(InvoiceApplyLine invoiceApplyLine) {
        return invoiceApplyLineRepository.selectList(invoiceApplyLine);
    }

    /**
     * Retrieves a paginated list of `InvApplyHeaderDTO` objects based on the given query parameters.
     *
     * @return A `List<InvoiceApplyLine>` object containing the list all invoice line records.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InvoiceApplyLine> selectAll() {
        return invoiceApplyLineRepository.selectAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceApplyLine> exportData(InvoiceApplyLine invoiceApplyLine) {
        return invoiceApplyLineRepository.selectList(invoiceApplyLine);
    }

    @Override
    public List<InvoiceApplyLine> getFromHeaders(List<Long> headerIds) {
        return invoiceApplyLineRepository.selectByHeaderIds(headerIds);
    }
}

