package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryService {

    private final VectorStore vectorStore;

    /**
     * Adds a new memory to the VectorStore.
     * @param projectId The project ID this memory belongs to.
     * @param memoryType Type of memory ('semantic_rule', 'episodic').
     * @param content The actual content of the memory.
     */
    public void addMemory(String projectId, String memoryType, String content) {
        log.info("[MemoryService] Adding {} memory for project {}", memoryType, projectId);
        Document document = new Document(content, Map.of(
                "projectId", projectId,
                "memoryType", memoryType
        ));
        vectorStore.add(List.of(document));
        log.info("[MemoryService] Memory successfully added to VectorStore.");
    }
}
