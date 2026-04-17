package com.example.demo.tools;

import com.example.demo.entity.AstNode;
import com.example.demo.repository.AstNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

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
            Use this when you need to inspect the exact code, method signatures,
            annotations, file path, class name, and metadata of a specific node.
            Always call this first when the user asks about a specific node.
            Returns the raw code, node type, language, and all metadata.
            """
    )
    public String fetchNodeStructure(
         @ToolParam(description = "The unique ID of the AST node to fetch") String nodeId,
         @ToolParam(description = "The repository URL for scoping the search") String repoUrl
    ){
        log.info("[ASTNavigator] Fetching node: {} from repo: {}", nodeId, repoUrl);

        Optional<AstNode>nodeOptional=astNodeRepository.findByIdAndRepoUrl(nodeId,repoUrl);

        if(nodeOptional.isEmpty()){
            return  String.format("{\"error\": \"Node %s not found in repo %s. Try using vectorSearch to locate related nodes.\"}",
                    nodeId, repoUrl
            );
        }

        AstNode node = nodeOptional.get();

        try{
            Map<String,Object> result=Map.of(
                    "nodeId",node.getId(),
                    "nodeType",node.getNodeType(),
                    "filepath",node.getFilePath(),
                    "rawCode",node.getRawCode()
            );
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error(e.getMessage());
            return "{\"error\": \"Failed to serialize data.\"}";
        }
    }

    @Tool(
            name = "fetchRelatedNodesByClass",
            description = "Finds AST nodes referencing a specific class name."
    )
    public String fetchRelatedNodesByClass(

    )
}
