package com.example.demo.controller;

import com.example.demo.service.MemoryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Adjust as per existing CORS policy
public class MemoryController {

    private final MemoryService memoryService;

    @PostMapping("/add")
    public ResponseEntity<String> addMemory(@RequestBody MemoryRequest request) {
        if (request.getProjectId() == null || request.getMemoryType() == null || request.getContent() == null) {
            return ResponseEntity.badRequest().body("Missing required fields: projectId, memoryType, content");
        }
        
        memoryService.addMemory(request.getProjectId(), request.getMemoryType(), request.getContent());
        return ResponseEntity.ok("Memory added successfully");
    }

    @Data
    public static class MemoryRequest {
        private String projectId;
        private String memoryType; // 'semantic_rule' or 'episodic'
        private String content;
    }
}
