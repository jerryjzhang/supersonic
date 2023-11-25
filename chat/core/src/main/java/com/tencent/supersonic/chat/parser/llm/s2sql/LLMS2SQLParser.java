package com.tencent.supersonic.chat.parser.llm.s2sql;

import com.tencent.supersonic.chat.agent.tool.CommonAgentTool;
import com.tencent.supersonic.chat.api.component.SemanticParser;
import com.tencent.supersonic.chat.api.pojo.ChatContext;
import com.tencent.supersonic.chat.api.pojo.QueryContext;
import com.tencent.supersonic.chat.api.pojo.request.QueryReq;
import com.tencent.supersonic.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.chat.query.llm.s2sql.LLMReq.ElementValue;
import com.tencent.supersonic.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.common.util.ContextUtils;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;

@Slf4j
public class LLMS2SQLParser implements SemanticParser {

    @Override
    public void parse(QueryContext queryCtx, ChatContext chatCtx) {
        QueryReq request = queryCtx.getRequest();
        LLMRequestService requestService = ContextUtils.getBean(LLMRequestService.class);
        //1.determine whether to skip this parser.
        if (requestService.check(queryCtx)) {
            return;
        }
        try {
            //2.get modelId from queryCtx and chatCtx.
            Long modelId = requestService.getModelId(queryCtx, chatCtx, request.getAgentId());
            if (Objects.isNull(modelId) || modelId <= 0) {
                return;
            }
            //3.get agent tool and determine whether to skip this parser.
            CommonAgentTool commonAgentTool = requestService.getParserTool(request, modelId);
            if (Objects.isNull(commonAgentTool)) {
                log.info("no tool in this agent, skip {}", LLMS2SQLParser.class);
                return;
            }
            //4.construct a request, call the API for the large model, and retrieve the results.
            List<ElementValue> linkingValues = requestService.getValueList(queryCtx, modelId);
            LLMReq llmReq = requestService.getLlmReq(queryCtx, modelId, linkingValues);
            LLMResp llmResp = requestService.requestLLM(llmReq, modelId);

            if (Objects.isNull(llmResp)) {
                return;
            }
            //5. deduplicate the SQL result list and build parserInfo
            LLMResponseService responseService = ContextUtils.getBean(LLMResponseService.class);
            Map<String, Double> deduplicationSqlWeight = responseService.getDeduplicationSqlWeight(llmResp);
            ParseResult parseResult = ParseResult.builder()
                    .request(request)
                    .modelId(modelId)
                    .commonAgentTool(commonAgentTool)
                    .llmReq(llmReq)
                    .llmResp(llmResp)
                    .linkingValues(linkingValues)
                    .build();

            if (MapUtils.isEmpty(deduplicationSqlWeight)) {
                responseService.addParseInfo(queryCtx, parseResult, llmResp.getSqlOutput(), 1D);
            } else {
                deduplicationSqlWeight.forEach((sql, weight) -> {
                    responseService.addParseInfo(queryCtx, parseResult, sql, weight);
                });
            }

        } catch (Exception e) {
            log.error("parse", e);
        }
    }


}