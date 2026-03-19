package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ParseResponse {
    private UUID projectId;
    private String repoName;
    private String detectedStack;
    private String status;
    private List<NodeDTO> nodes;
}
