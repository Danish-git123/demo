package com.example.demo.service;

import com.example.demo.entity.AstNode;
import com.example.demo.repository.AstNodeRepository;
import com.example.demo.repository.UserRepository;
import org.springframework.ai.chat.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NodeExplanationService {
    @Autowired
    private AstNodeRepository astNodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatClient.Builder chatClientBuilder;


    private ChatClient chatClient;

    public ResponseEntity<?>NodeExplanation(UUID nodeId){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String supabaseId = (String) authentication.getPrincipal();

        userRepository.findBySupabaseId(supabaseId)
                .orElseThrow(() -> new RuntimeException("User not found for supabaseId: " + supabaseId));

        AstNode node = astNodeRepository.findById(nodeId)
                .orElseThrow(() -> new RuntimeException("Node not found for id: " + nodeId));
        String rawCode = node.getRawCode();




    }
}
