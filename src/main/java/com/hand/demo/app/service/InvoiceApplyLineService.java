package com.hand.demo.app.service;

import io.choerodon.core.domain.Page;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import com.hand.demo.domain.entity.InvoiceApplyLine;
import org.hzero.core.base.AopProxy;

import java.util.List;

/**
 * Invoice Apply Line Table(InvoiceApplyLine)应用服务
 *
 * @author razah
 * @since 2024-12-03 09:27:59
 */
public interface InvoiceApplyLineService extends AopProxy<InvoiceApplyLineService> {

    /**
     * 查询数据
     *
     * @param pageRequest       分页参数
     * @param invoiceApplyLines 查询条件
     * @return 返回值
     */
    Page<InvoiceApplyLine> selectList(PageRequest pageRequest, InvoiceApplyLine invoiceApplyLines);

    /**
     * 保存数据
     *
     * @param invoiceApplyLines 数据
     */
    void saveData(List<InvoiceApplyLine> invoiceApplyLines);
    void removeData(List<InvoiceApplyLine> invoiceApplyLines);
    List<InvoiceApplyLine> selectByInvoiceHeader(Long headerId);
    List<InvoiceApplyLine> selectList(InvoiceApplyLine invoiceApplyLine);
    List<InvoiceApplyLine> selectAll();

    List<InvoiceApplyLine> exportData(InvoiceApplyLine invoiceApplyLine);
    List<InvoiceApplyLine> getFromHeaders(List<Long> headerIds);
}

