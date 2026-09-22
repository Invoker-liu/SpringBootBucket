package com.xncoding.restclient.api;

import com.xncoding.restclient.model.Waybill;
import com.xncoding.restclient.model.WaybillRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** 物流服务声明式接口 */
@HttpExchange("/logistics")
public interface LogisticsApi {

    @PostExchange("/waybills")
    Waybill createWaybill(@RequestBody WaybillRequest request);

    @GetExchange("/waybills/{no}")
    Waybill getWaybill(@PathVariable String no);
}
