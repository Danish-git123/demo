package com.example.demo.controller;

import com.example.demo.dto.AgentRequest;
import com.example.demo.service.CodeAnalysisAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
//@CrossOrigin(origins = "*") // Allows your React frontend to connect without CORS errors
@RequiredArgsConstructor
@Slf4j
public class CodeLensAgentController {

    private final CodeAnalysisAgentService agentService;

    /**
     * Endpoint to trigger the CodeLens autonomous agent.
     * Produces TEXT_EVENT_STREAM_VALUE to stream the Flux<String> response back to the client.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithAgent(@RequestBody AgentRequest request) {
        log.info("Received agent request for Project: {} and Node: {}",
                request.getProjectId(), request.getActiveNodeId());

        return agentService.streamAgentResponse(request);
    }
}