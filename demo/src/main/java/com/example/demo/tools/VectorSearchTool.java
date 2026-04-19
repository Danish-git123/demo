package com.example.demo.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class VectorSearchTool {
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ObjectMapper objectMapper;

    @Tool(
            name = "semanticCodeSearch",
            description = """
                    Performs semantic (meaning-based) search over the entire codebase using vector embeddings.
                                Use this when you need to find code related to a concept or feature without knowing
                                exact labels, file paths, or function names. This goes beyond keyword matching.
                    
                                Good use cases:
                                - Finding all authentication/authorization logic
                                - Locating error handling patterns
                                - Finding validation logic across different files
                                - Discovering where a business concept is implemented
                    
                                Always combine results with fetchNodeStructure for complete context.
                    """
    )
    public String semanticCodeSearch(
            @ToolParam(description = "Natural language description of what user is looking for ,e.g ., ->'JWT token validation' or 'database transaction handling any query by the user ")String semanticQuery,
            @ToolParam(description = "The UUID of project being analyzed") String projectId,
            @ToolParam(description = "Maximum number of results to return (1-10,default 5)")int maxResults
    ){
        log.info("[VectorSearch] Semantic query: '{}' in project: {}", semanticQuery, projectId);

        int limit=Math.max(1,Math.min(maxResults,10));

        try{
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(semanticQuery)
                    .topK(limit)
                    .filterExpression("projecctid== '" + projectId + "'")
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);

            if(results.isEmpty()){
                return "{\"message\": \"No semantically similar code found. Try rephrasing your query or broadening the concept.\", \"count\": 0}";

            }

            List<Map<String, Object>> formattedResults = results.stream().map(doc -> {
                Map<String, Object> metadata = doc.getMetadata();
                Map<String, Object> map = new HashMap<>();
                map.put("nodeId", metadata.getOrDefault("nodeId", "unknown"));
                map.put("filePath", metadata.getOrDefault("filePath", "unknown"));
                map.put("nodeType", metadata.getOrDefault("nodeType", "unknown"));
                map.put("label", metadata.getOrDefault("label", "unknown")); // Replaced className with label

                String content = doc.getFormattedContent();
                map.put("codeSnippet", content != null ? content.substring(0, Math.min(800, content.length())) : "");

                return map;
            }).toList();

            return objectMapper.writeValueAsString(
                    Map.of("query", semanticQuery, "count", formattedResults.size(), "results", formattedResults)
            );
        } catch (Exception e) {
            log.error("Eroor is smeanticCodeSerach Tool",e.getMessage());
            return "{\"error\": \"Vector search failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool(
            name = "findSimilarNodes",
            description = """
            Finds AST nodes that are semantically similar to a given node's code.
            Use this to discover patterns, understand how similar features are implemented
            elsewhere in the codebase, or find potential code duplication across different frameworks.
            """
    )
    public String findSimilarNodes(
            @ToolParam(description = "The raw code snippet to find similarities for") String codeSnippet,
            @ToolParam(description = "The UUID of the project being analyzed") String projectId,
            @ToolParam(description = "The source node ID to exclude from results") String excludeNodeId
    ) {

            log.info("[VectorSearch] Finding nodes similar to code snippet in project: {}, excluding: {}", projectId, excludeNodeId);

            try{
                SearchRequest searchRequest = SearchRequest.builder()
                        .query(codeSnippet)
                        .topK(5)
                        .filterExpression("projectId == '" + projectId + "' && nodeId != '" + excludeNodeId + "'")
                        .build();

                List<Document> results = vectorStore.similaritySearch(searchRequest);
                List<Map<String, Object>> formattedResults = results.stream()
                        .map(doc -> {
                            Map<String, Object> metadata = doc.getMetadata();
                            // Using standard HashMap for type safety
                            Map<String, Object> map = new java.util.HashMap<>();
                            map.put("nodeId", metadata.getOrDefault("nodeId", "unknown"));
                            map.put("filePath", metadata.getOrDefault("filePath", "unknown"));
                            map.put("label", metadata.getOrDefault("label", "unknown"));

                            String content = doc.getFormattedContent();
                            map.put("codeSnippet", content != null ? content.substring(0, Math.min(600, content.length())) : "");

                            return map;
                        })
                        .toList();

                return objectMapper.writeValueAsString(
                        Map.of("similarNodesCount", formattedResults.size(), "similarNodes", formattedResults)
                );
            } catch (Exception e) {
                log.error("Error in findSImilarNodes tool",e.getMessage());
                return "{\"error\": \"Failed to find similar nodes: " + e.getMessage() + "\"}";
            }
    }
}

