package com.example.demo.repository;

import com.example.demo.entity.ChatMessage;
import com.example.demo.entity.ChatMessage.MessageRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    // Get full conversation history for a project (ordered oldest → newest)
    // Sent to LLM as context for the next message
    List<ChatMessage> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    // Get all messages tied to a specific active node
    // Useful for showing node-specific chat history
    List<ChatMessage> findByProjectIdAndActiveNodeIdOrderByCreatedAtAsc(UUID projectId, UUID activeNodeId);

    // Get only user messages or only AI messages for a project
    List<ChatMessage> findByProjectIdAndRole(UUID projectId, MessageRole role);

    // Delete all chat history for a project (reset conversation)
    void deleteByProjectId(UUID projectId);
}