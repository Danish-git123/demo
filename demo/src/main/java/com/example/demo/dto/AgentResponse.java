package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    private String answer;
    private List<String> investigatedNodeIds;
    private List<String> toolsUsed;
    private String executionTrace;
    private boolean simulationPerformed;
    private String simulationResult;
}
