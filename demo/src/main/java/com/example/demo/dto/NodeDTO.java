package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeDTO {
    private UUID id;
    private String label;
    private String nodeType;
    private String filePath;
    private List<String> dependancies;//list of nodes the node will connect to
}
