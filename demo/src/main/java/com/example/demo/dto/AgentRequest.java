package com.example.demo.dto;

import com.example.demo.entity.AstNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {
    private String activeNodeId;
    private String projectId;
    private String userPrompt;
    private String demoPayload;
    private String detectedFramwork;
    private String conversationId;//this is for the session to track
}
