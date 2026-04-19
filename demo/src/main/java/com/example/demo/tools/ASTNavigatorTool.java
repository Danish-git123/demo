package com.example.demo.tools;

import com.example.demo.entity.AstNode;
import com.example.demo.repository.AstNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
//@RequiredArgsConstructor
@Slf4j
public class ASTNavigatorTool {
    @Autowired
    private   AstNodeRepository astNodeRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Tool(
            name = "fetchNodeStructure",
            description = """
            Fetches the complete AST node structure for a given node ID.
            Use this when you need to inspect the exact code, file path, and node type of a specific node.
            Always call this first when the user asks about a specific node.
            """
    )
    public String fetchNodeStructure(
            @ToolParam(description = "The unique ID of the AST node to fetch") String nodeId,
            @ToolParam(description = "The UUID of the project being analyzed") String projectId
    ){
        log.info("[ASTNavigator] Fetching node: {} from project: {}", nodeId, projectId);

        try {
            // Convert Strings from the LLM prompt into actual UUIDs
            UUID nodeUuid = UUID.fromString(nodeId);
            UUID projectUuid = UUID.fromString(projectId);

            Optional<AstNode> nodeOptional = astNodeRepository.findByIdAndProjectId(nodeUuid, projectUuid);

            if(nodeOptional.isEmpty()){
                return String.format("{\"error\": \"Node %s not found in project %s. Try using fetchRelatedNodesByIdentifier.\"}",
                        nodeId, projectId
                );
            }

            AstNode node = nodeOptional.get();

            // Map the exact fields available in your AstNode entity
            Map<String,Object> result = Map.of(
                    "nodeId", node.getId(),
                    "nodeType", node.getNodeType(),
                    "label", node.getLabel() != null ? node.getLabel() : "N/A",
                    "filePath", node.getFilePath() != null ? node.getFilePath() : "N/A",
                    "rawCode", node.getRawCode() != null ? node.getRawCode() : ""
            );
            return objectMapper.writeValueAsString(result);

        } catch (IllegalArgumentException e) {
            return "{\"error\": \"Invalid UUID format provided by the agent.\"}";
        } catch (Exception e) {
            log.error("[ASTNavigator] Serialization error: ", e);
            return "{\"error\": \"Failed to serialize node data.\"}";
        }
    }
    @Tool(
            name = "fetchNodeDependencies",
            description = """
            Fetches the actual code and metadata for all dependencies of a specific node.
            Use this to pull in related files when the primary node relies on other services, utilities, or components.
            """
    )
    public String fetchNodeDependencies(
            @ToolParam(description = "The unique ID of the primary AST node") String nodeId,
            @ToolParam(description = "The UUID of the project being analyzed") String projectId
    ){
        log.info("[ASTNavigator] Searching for identifier: {} in project: {}",nodeId, projectId);

        try{
            UUID nodeUuid = UUID.fromString(nodeId);
            UUID projectUuid = UUID.fromString(projectId);

            Optional<AstNode> primaryNodeOpt = astNodeRepository.findByIdAndProjectId(nodeUuid, projectUuid);
            if (primaryNodeOpt.isEmpty()) {
                return String.format("{\"error\": \"Primary node %s not found.\"}", nodeId);
            }

            String depsJson = primaryNodeOpt.get().getDependencies();
            // If there are no dependencies, return early
            if (depsJson == null || depsJson.isBlank() || depsJson.equals("[]")) {
                return "{\"message\": \"This node has no explicit dependencies recorded.\", \"count\": 0}";
            }

            List<String> depIdStrings = objectMapper.readValue(depsJson, new TypeReference<List<String>>() {
            });

            List<UUID> depUuids = depIdStrings.stream()
                    .map(UUID::fromString)
                    .toList();

            List<AstNode> relatedNodes = astNodeRepository.findAllById(depUuids);

            List<Map<String, Object>> results = relatedNodes.stream()
                    .map(node -> {
                        // Using a standard HashMap fixes the "Inconvertible types" error
                        Map<String, Object> map = new java.util.HashMap<>();
                        map.put("nodeId", node.getId().toString()); // Convert UUID to String
                        map.put("nodeType", node.getNodeType());
                        map.put("label", node.getLabel() != null ? node.getLabel() : "N/A");
                        map.put("filePath", node.getFilePath() != null ? node.getFilePath() : "N/A");

                        // Safely truncate code to prevent token overflow
                        String code = node.getRawCode();
                        if (code != null) {
                            map.put("rawCode", code.substring(0, Math.min(1500, code.length())));
                        } else {
                            map.put("rawCode", "");
                        }

                        return map;
                    })
                    .toList();

            return objectMapper.writeValueAsString(Map.of("count", results.size(), "nodes", results));
        } catch (Exception e) {
            log.error(e.getMessage());
            return "{\"error\": \"Failed to parse dependencies or fetch data: " + e.getMessage() + "\"}";
        }
    }


}
