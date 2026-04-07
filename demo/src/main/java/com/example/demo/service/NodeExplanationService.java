package com.example.demo.service;

import com.example.demo.entity.AstNode;
import com.example.demo.repository.AstNodeRepository;
import com.example.demo.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class NodeExplanationService {
    @Autowired
    private AstNodeRepository astNodeRepository;

    @Autowired
    private UserRepository userRepository;

    private ChatClient chatClient;

    public NodeExplanationService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String nodeExplanation(UUID nodeId) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String supabaseId = (String) authentication.getPrincipal();

            userRepository.findBySupabaseId(supabaseId)
                    .orElseThrow(() -> new RuntimeException("User not found for supabaseId: " + supabaseId));

            AstNode node = astNodeRepository.findById(nodeId)
                    .orElseThrow(() -> new RuntimeException("Node not found for id: " + nodeId));

            if (node.getAiExplanation() != null && !node.getAiExplanation().isBlank()) {
                log.info("Returning cached explanation for node: {}", nodeId);
                return node.getAiExplanation();
            }

            String prompt = buildExplanationPrompt(node);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.info("Successfully generated explanation for node: {} (type: {})", nodeId, node.getNodeType());
            node.setAiExplanation(response);
            astNodeRepository.save(node);
            return response;

        } catch (Exception e) {
            log.error("Error in nodeExplanation service for nodeId: {}", nodeId, e);
            throw new RuntimeException("Failed to generate explanation: " + e.getMessage(), e);
        }
    }

    private String buildExplanationPrompt(AstNode node) {
        return """
        You are an Expert Code Architect analyzing a specific component within a larger system.
        
        ## Your Role
        Provide a concise, developer-focused explanation that gives a clear mental model of what this code does and why it matters.
        
        ## Code Context
        Project Architecture: Universal AST-based code graph with multi-language support
        Node Type: %s
        Component Path: %s
        
        ## Explanation Format (REQUIRED)
        Follow this exact structure:
        
        ### 🎯 Purpose (1-2 sentences)
        What does this code do? What is its primary responsibility in the system?
        
        ### 🔗 In the System Context
        Where is this used? What other components depend on it or does it depend on?
        
        ### ⚙️ How It Works (Step by Step)
        - Break down the logic into 3-5 key steps
        - Use plain language, not implementation details
        - Example: "1. Receives input → 2. Validates data → 3. Transforms to internal format → 4. Stores result"
        
        ### 🧩 Key Components
        List the most important variables, functions, or concepts:
        - [Name]: [1 sentence on what it does]
        
        ### 💡 Why It Matters
        Business impact or architectural significance in 1-2 sentences.
        
        ### ⚠️ Important Notes (if any)
        Any edge cases, performance considerations, or common mistakes to avoid.
        
        ## Code to Analyze
        ```
        %s
        ```
        
        CRITICAL REQUIREMENTS:
        - Be concise but complete
        - Assume the reader is a developer with NO prior knowledge of this codebase
        - Avoid implementation jargon; explain concepts instead
        - Make it scannable with clear sections
        - Maximum 400 words
        """.formatted(
                node.getNodeType() != null ? node.getNodeType() : "UNKNOWN",
                node.getFilePath() != null ? node.getFilePath() : "N/A",
                node.getRawCode() != null ? node.getRawCode() : ""
        );
    }
}