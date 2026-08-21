package com.aid.compose.controller.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Data;

/** 阿里云 IMS HTTP 回调请求。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImsCallbackRequest
{
    @JsonAlias({"JobId", "jobId"})
    private String jobId;

    @JsonAlias({"EventMessage", "eventMessage", "Message", "message"})
    private JsonNode eventMessage;

    @JsonAlias({"MessageBody", "messageBody"})
    private JsonNode messageBody;

    @JsonAlias({"UserData", "userData"})
    private JsonNode userData;
}
