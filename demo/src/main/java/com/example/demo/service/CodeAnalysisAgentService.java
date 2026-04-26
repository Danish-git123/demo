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
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import reactor.core.publisher.Flux;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

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

        String semanticContext = fetchSemanticMemoryProgrammatically(request.getUserPrompt(), request.getProjectId());
        String episodicContext = fetchEpisodicMemoryProgrammatically(request.getUserPrompt(), request.getProjectId());

        ChatClient chatClient = buildAgentChatClient();

        String systemPrompt = buildSystemPrompt(request.getDetectedFramwork(), semanticContext, episodicContext);

        String userMessage = buildUserMessage(request);

        String conversationId = request.getConversationId() != null ? request.getConversationId() : "default-session";
        
//        StringBuilder fullResponse = new StringBuilder();

        return Flux.defer(() -> {
            try {
                String fullResponse = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userMessage)
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                                .conversationId(conversationId)
                                .build())
                        .tools(astNavigatorTool, vectorSearchTool, codeSimulatorTool)
                        // USE .call() INSTEAD OF .stream() TO BYPASS THE NVIDIA BUG
                        .call()
                        .content();

                log.info("[Agent] Analysis completed. Triggering background episodic memory summarization.");

                // Run memory summarization async so it doesn't block the response
                CompletableFuture.runAsync(() -> summarizeAndSaveEpisodicMemory(request, fullResponse, chatClient));

                return Flux.just(fullResponse);

            } catch (Exception e) {
                log.error("[Agent] Execution error", e);
                return Flux.error(e);
            }
        });

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
                return "No pre-existing semantic memory found for this query.";
            }

            return results.stream()
                    .map(Document::getFormattedContent)
                    .collect(Collectors.joining("\n- "));
        } catch (Exception e) {
            log.error((e.getMessage()));
            return "Memory retrieval failed.";
        }
    }

    private String fetchEpisodicMemoryProgrammatically(String userQuery, String projectId){
        try{
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(userQuery)
                    .topK(3)
                    .filterExpression("projectId== '"+projectId+"' && memoryType =='episodic'")
                    .build();
            List<Document> results = vectorStore.similaritySearch(searchRequest);
            if (results.isEmpty()) {
                return "No past episodic experiences found for this context.";
            }

            return results.stream()
                    .map(Document::getFormattedContent)
                    .collect(Collectors.joining("\n- "));
        } catch (Exception e) {
            log.error("Episodic Memory retrieval failed", e);
            return "Episodic memory retrieval failed.";
        }
    }

    private void summarizeAndSaveEpisodicMemory(AgentRequest request, String agentResponse, ChatClient chatClient) {
        try {
            String prompt = String.format("Summarize the following user request and your resolution into a concise 'past experience' fact. " +
                    "Focus only on what the problem was and how it works/was resolved. " +
                    "\n\nUser Request: %s\n\nAgent Resolution: %s", request.getUserPrompt(), agentResponse);
            
            String summary = chatClient.prompt().user(prompt).call().content();
            
            log.info("[Memory] Saving new Episodic memory for project {}", request.getProjectId());
            Document doc = new Document(summary, Map.of(
                    "projectId", request.getProjectId(),
                    "memoryType", "episodic"
            ));
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            log.error("[Memory] Failed to summarize and save episodic memory", e);
        }
    }

    private String buildSystemPrompt(String framework, String semanticContext, String episodicContext) {
        String frameworkContext = (framework != null && !framework.isBlank())
                ? framework
                : "a multi-language architecture";

        return String.format("""
            You are an expert software architect and code analysis agent embedded inside a \
            codebase visualization tool called CodeLens. 
            
            You are currently analyzing a project built using: %s
            
            [SEMANTIC KNOWLEDGE (Rules & Facts)]:
            %s
            
            [EPISODIC KNOWLEDGE (Past Experiences)]:
            %s
            
            YOUR USERS ARE:
            1. PROFESSIONAL DEVELOPERS — they need precise, accurate technical answers and execution tracing.
            2. STUDENTS — they need clear explanations of patterns and business logic.
            3. OPEN SOURCE CONTRIBUTORS — they need to understand architecture and dependencies.
            
            YOUR BEHAVIOR & RULES:
            - You have access to powerful tools to traverse the Abstract Syntax Tree (AST) and Vector database.
            - You MUST pass the `projectId` to your tools to scope your searches correctly.
            - Iteratively use tools to pull in dependencies when needed.
            - IF an ACTIVE NODE ID is provided, use fetchNodeStructure first. IF the ACTIVE NODE ID is NONE, do not use fetchNodeStructure.
            - When answering general project questions, use `semanticCodeSearch` to understand the architecture.
            - NEVER fabricate code or make up method names.
            
            RESPONSE FORMAT:
            - Be concise, use technical terms, and show execution traces when applicable.
            - Use markdown for code blocks.
            - Always end your response with a brief list: "Nodes Investigated: [list of labels]" so developers know what you looked at.
            """, frameworkContext, semanticContext, episodicContext);
    }

    private String buildUserMessage(AgentRequest request) {
        StringBuilder sb = new StringBuilder();

        sb.append("PROJECT ID: ").append(request.getProjectId()).append("\n");
        sb.append("\nUSER QUESTION:\n").append(request.getUserPrompt()).append("\n");

        if (request.getDemoPayload() != null && !request.getDemoPayload().isBlank()) {
            sb.append("\nDEMO PAYLOAD TO SIMULATE:\n```json\n")
                    .append(request.getDemoPayload())
                    .append("\n```\n");
            sb.append("The user wants you to simulate what happens when this payload is processed.\n");
        }

        sb.append("\nINSTRUCTIONS FOR USING YOUR TOOLS:\n");
        sb.append("1. If the user is asking about a SPECIFIC class, component, or file (e.g., 'OwnerRepository'), ALWAYS use the 'searchNodeByName' tool first to fetch its code.\n");
        sb.append("2. If the user is asking a GENERAL question about the whole project architecture, use the 'semanticCodeSearch' tool.\n");
        sb.append("3. Read the code carefully from the tool results before answering.\n");
        sb.append("Provide a complete, accurate, and educational response.");

        return sb.toString();
    }


}
