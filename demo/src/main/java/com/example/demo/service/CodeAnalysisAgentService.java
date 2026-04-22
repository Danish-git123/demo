package com.example.demo.service;

import com.example.demo.dto.AgentRequest;
import com.example.demo.tools.ASTNavigatorTool;
import com.example.demo.tools.CodeSimulatorTool;
import com.example.demo.tools.VectorSearchTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CodeAnalysisAgentService {

    private final ASTNavigatorTool astNavigatorTool;

    private final VectorSearchTool vectorSearchTool;

    private final CodeSimulatorTool codeSimulatorTool;

    private final ChatClient.Builder chatClientBuilder;

    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;


    @Value("${agent.max-iterations:8}")
    private int maxIterations;



    private ChatClient buildAgentChatClient() {
        return chatClientBuilder.build();
    }

    public Flux<String> streamAgentResponse(AgentRequest request){
        log.info("[Agent] Starting streaming analysis. Project: {}, Node: {}",
                request.getProjectId(), request.getActiveNodeId());

        String semanticContext=fetchSemanticMemoryProgrammatically(request.getUserPrompt(),request.getProjectId());

        ChatClient chatClient=buildAgentChatClient();

        String systemPrompt=buildSystemPrompt(request.getDetectedFramwork());

        String userMessage=buildUserMessage(request);

//        this is streaming response
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .tools(astNavigatorTool,vectorSearchTool,codeSimulatorTool)
                .stream()
                .content()
                .doOnError(e->log.error("[Agent] Streaming error",e));

    }

    private String fetchSemanticMemoryProgrammatically(String userQuery,String projectId){
        try{
            SearchRequest searchRequest=SearchRequest.builder()
                    .query(userQuery)
                    .topK(3)
                    .filterExpression("projectId== '"+projectId+"' && memoryType =='semantic_rule'")
                    .build();
            List<Document> results = vectorStore.similaritySearch(searchRequest);
            if (results.isEmpty()) {
                return "No pre-existing semantic memory found for this query. You must deduce the architecture from the AST tools.";
            }

            return results.stream()
                    .map(Document::getFormattedContent)
                    .collect(Collectors.joining("\n- "));
        } catch (Exception e) {
            log.error((e.getMessage()));
            return "Memory retrieval failed. Proceed with standard AST analysis.";

        }
    }

    private String buildSystemPrompt(String framework) {
        // We dynamically inject the detected framework so the LLM knows what syntax to expect
        String frameworkContext = (framework != null && !framework.isBlank())
                ? framework
                : "a multi-language architecture";

        return String.format("""
            You are an expert software architect and code analysis agent embedded inside a \
            codebase visualization tool called CodeLens. 
            
            You are currently analyzing a project built using: %s
            
            YOUR USERS ARE:
            1. PROFESSIONAL DEVELOPERS — they need precise, accurate technical answers and execution tracing.
            2. STUDENTS — they need clear explanations of patterns and business logic.
            3. OPEN SOURCE CONTRIBUTORS — they need to understand architecture and dependencies.
            
            YOUR BEHAVIOR & RULES:
            - You have access to powerful tools to traverse the Abstract Syntax Tree (AST) and Vector database.
            - ALWAYS start by fetching the primary node's structure before answering.
            - You MUST pass the `projectId` to your tools to scope your searches correctly.
            - Iteratively use tools to pull in dependencies when needed — do not hallucinate code you haven't explicitly fetched.
            - When the user provides a demo payload, ALWAYS use `prepareSimulationContext` and trace execution step by step.
            - Use `fetchRelatedNodesByIdentifier` or `semanticCodeSearch` to find related patterns or dependencies.
            - NEVER fabricate code or make up method names.
            
            RESPONSE FORMAT:
            - Be concise, use technical terms, and show execution traces when applicable.
            - Use markdown for code blocks.
            - Always end your response with a brief list: "Nodes Investigated: [list of labels]" so developers know what you looked at.
            """, frameworkContext);
    }

    private String buildUserMessage(AgentRequest request) {
        StringBuilder sb = new StringBuilder();

        sb.append("PROJECT ID: ").append(request.getProjectId()).append("\n");
        sb.append("ACTIVE NODE ID: ").append(request.getActiveNodeId()).append("\n");
        sb.append("\nUSER QUESTION:\n").append(request.getUserPrompt()).append("\n");

        if (request.getDemoPayload() != null && !request.getDemoPayload().isBlank()) {
            sb.append("\nDEMO PAYLOAD TO SIMULATE:\n```json\n")
                    .append(request.getDemoPayload())
                    .append("\n```\n");
            sb.append("The user wants you to simulate what happens when this payload is processed.\n");
        }

        sb.append("\nInstructions: Use your tools to investigate the codebase thoroughly. ");
        sb.append("Start by fetching the active node using its ID and the Project ID. ");
        sb.append("Provide a complete, accurate, and educational response.");

        return sb.toString();
    }


}
