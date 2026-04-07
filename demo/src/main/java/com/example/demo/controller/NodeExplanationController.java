package com.example.demo.controller;

import com.example.demo.dto.AiExplanationResponse;
import com.example.demo.service.NodeExplanationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/node")
public class NodeExplanationController {
    @Autowired
    private NodeExplanationService nodeExplanationService;

    @GetMapping("/{nodeId}")
    public ResponseEntity<?>explainNode(@PathVariable UUID nodeId){
        try {
            String explanation = nodeExplanationService.nodeExplanation(nodeId);
            // DTO mein wrap karke bhejo
            return ResponseEntity.ok(new AiExplanationResponse(explanation));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

}
