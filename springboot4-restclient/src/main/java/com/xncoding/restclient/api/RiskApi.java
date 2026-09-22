package com.xncoding.restclient.api;

import com.xncoding.restclient.model.RiskRequest;
import com.xncoding.restclient.model.RiskResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** 风控服务声明式接口 */
@HttpExchange("/risk")
public interface RiskApi {

    @PostExchange("/check")
    RiskResult check(@RequestBody RiskRequest request);
}
