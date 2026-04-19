package com.example.demo.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class CodeSimulatorTool {
    @Autowired
    private ObjectMapper objectMapper;

    @Tool(
            name = "prepareSimulationContext",
            description = """
                    Prepares a structured simulation context for executing code mentally
                                with a given demo payload. Use this when the user wants to:
                                - Test what happens with a specific JSON input
                                - Trace the execution path through multiple method calls
                                - Understand validation rules applied to input data
                                - See what exceptions might be thrown for edge cases
                    """
    )
    public String prepareSimulationContext(
            @ToolParam(description = "The raw code of the entry point method/endpoint to simulate") String entryPointCode,
            @ToolParam(description = "The demo JSON payload as a string") String demoPayload,
            @ToolParam(description = "The framework or language (e.g., NODE_EXPRESS, SPRING_BOOT, DJANGO, REACT_NEXT)") String framework,
            @ToolParam(description = "Any additional code context (dependency files, utilities, etc.)") String additionalContext
    ){
        log.info("[CodeSimulator] Preparing simulation for {} code", framework);

        boolean isValidjson=false;
        String payloadSummary="Invalid or non-JSON payload";
        List<String> payloadFields=new ArrayList<>();

        try{
            JsonNode payloadNode=objectMapper.readTree(demoPayload);
            isValidjson=true;
            payloadSummary="Valid JSON with "+payloadNode.size()+" top-level field(s)";
            payloadNode.fieldNames().forEachRemaining(payloadFields::add);
        } catch (Exception e) {

            payloadSummary = "Raw (non-JSON) input: " + demoPayload.substring(0, Math.min(100, demoPayload.length()));
        }

        SimulationHints hints = analyzeCodePatterns(entryPointCode);

        try{
            Map<String, Object> simulationContext = new HashMap<>();
            simulationContext.put("simulationReady", true);
            simulationContext.put("framework", framework);
            simulationContext.put("payloadIsValidJson", isValidjson);
            simulationContext.put("payloadSummary", payloadSummary);
            simulationContext.put("payloadFields", payloadFields);
            simulationContext.put("entryPointCodeLength", entryPointCode.length());
            simulationContext.put("detectedPatterns", hints.patterns());
            simulationContext.put("hasValidation", hints.hasValidation());
            simulationContext.put("hasExceptionHandling", hints.hasExceptionHandling());
            simulationContext.put("simulationInstruction", buildSimulationInstruction(
                    entryPointCode, demoPayload, additionalContext, framework, payloadFields, hints
            ));

            return objectMapper.writeValueAsString(simulationContext);
        } catch (Exception e) {
            log.error("Error in Simulation context",e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Tool(
            name = "analyzeExecutionPath",
            description = """
                    Analyzes the potential execution paths through a multi-language code block.
                                Identifies all possible branches (if/else, switch), loops, early returns,\s
                                and exception handling (try/catch/except).
                                Returns a structured breakdown of all execution branches.
                    """
    )
    public String analyzeExecutionPath(
            @ToolParam(description = "The code to analyze for execution paths") String code,
            @ToolParam(description = "The programming language or framework") String framework
    ){
        log.info("[CodeSimulator] Analyzing execution paths in {} code", framework);
        List<String> branches=new ArrayList<>();
        List<String> exceptionPoints = new ArrayList<>();
        List<String> returnPoints = new ArrayList<>();
        List<String> loops = new ArrayList<>();

        String [] lines=code.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            int lineNum = i + 1;

            // Detect branching (Multi-language: Java/JS/C#, Python elif)
            if (trimmed.startsWith("if ") || trimmed.startsWith("if(") || trimmed.startsWith("elif")) {
                branches.add("Line " + lineNum + ": Conditional branch — " + trimmed.substring(0, Math.min(60, trimmed.length())));
            } else if (trimmed.startsWith("} else") || trimmed.startsWith("else {") || trimmed.equals("else:")) {
                branches.add("Line " + lineNum + ": Else branch");
            } else if (trimmed.startsWith("switch") || trimmed.startsWith("case ") || trimmed.startsWith("match ")) {
                branches.add("Line " + lineNum + ": Switch/Match branch");
            }

            // Detect exception handling (Java/JS throw/catch, Python raise/except)
            if (trimmed.startsWith("throw ") || trimmed.startsWith("raise ")) {
                exceptionPoints.add("Line " + lineNum + ": Throws/Raises — " + trimmed.substring(0, Math.min(60, trimmed.length())));
            } else if (trimmed.startsWith("catch") || trimmed.startsWith("except")) {
                exceptionPoints.add("Line " + lineNum + ": Catches/Excepts — " + trimmed.substring(0, Math.min(60, trimmed.length())));
            }

            // Detect returns
            if (trimmed.startsWith("return ")) {
                returnPoints.add("Line " + lineNum + ": Returns — " + trimmed.substring(0, Math.min(60, trimmed.length())));
            }

            // Detect loops
            if (trimmed.startsWith("for ") || trimmed.startsWith("for(") || trimmed.startsWith("while") || trimmed.contains(".forEach") || trimmed.contains(".map(")) {
                loops.add("Line " + lineNum + ": Loop — " + trimmed.substring(0, Math.min(60, trimmed.length())));
            }
        }

        try{
            Map<String, Object> result = new HashMap<>();
            result.put("framework", framework);
            result.put("totalLines", lines.length);
            result.put("conditionalBranches", branches);
            result.put("exceptionPoints", exceptionPoints);
            result.put("returnPoints", returnPoints);
            result.put("loops", loops);
            result.put("complexityEstimate", estimateComplexity(branches.size(), loops.size(), exceptionPoints.size()));

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Error in analyzeExecutionPath",e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String estimateComplexity(int branches, int loops, int exceptions) {
        int score = branches + (loops * 2) + exceptions;
        if (score <= 2) return "Low — straightforward linear execution";
        if (score <= 6) return "Medium — some branching, manageable trace";
        return "High — complex execution flow, careful simulation required";
    }

    private SimulationHints analyzeCodePatterns(String code) {
        String lower = code.toLowerCase();
        List<String> patterns = new ArrayList<>();

        // Validation (Spring @Valid, JS Joi/Zod, Python is_valid)
        boolean hasValidation = lower.contains("@valid") || lower.contains("joi.") || lower.contains("zod.") || lower.contains("is_valid") || lower.contains("validate");
        if (hasValidation) patterns.add("Data Validation Logic Detected");

        // DB Operations (Spring Repo, Django ORM, JS Mongoose/Prisma, Laravel Eloquent)
        if (lower.contains("repository") || lower.contains(".save(") || lower.contains(".find") || lower.contains("objects.filter") || lower.contains("insert into")) {
            patterns.add("Database Interaction Detected");
        }

        // HTTP Calls (Spring RestTemplate, JS fetch/axios, Python requests)
        if (lower.contains("fetch(") || lower.contains("axios") || lower.contains("requests.") || lower.contains("resttemplate") || lower.contains("httpclient")) {
            patterns.add("External HTTP API Call Detected");
        }

        // Authentication logic
        if (lower.contains("jwt") || lower.contains("bearer") || lower.contains("req.user") || lower.contains("request.user")) {
            patterns.add("Authentication/Authorization Logic Detected");
        }

        // Async/Promises (JS async/await, Spring Mono/Flux, Python async def)
        if (lower.contains("async ") || lower.contains("await ") || lower.contains("promise") || lower.contains("mono<")) {
            patterns.add("Asynchronous Execution Flow");
        }

        boolean hasExceptionHandling = lower.contains("catch") || lower.contains("except") || lower.contains("try {") || lower.contains("try:");

        return new SimulationHints(patterns, hasValidation, hasExceptionHandling);
    }

    private String buildSimulationInstruction(
            String entryPointCode, String demoPayload,
            String additionalContext, String framework,
            List<String> payloadFields, SimulationHints hints
    ) {
        return String.format("""
            SIMULATION TASK:
            Act as a runtime environment. Trace the execution of the following %s code step-by-step using the provided payload.
            
            ENTRY POINT CODE:
            %s
            
            DEMO PAYLOAD:
            %s
            
            PAYLOAD FIELDS DETECTED: %s
            
            ADDITIONAL CONTEXT (Dependencies, helpers, etc.):
            %s
            
            DETECTED ARCHITECTURE PATTERNS: %s
            
            INSTRUCTIONS:
            1. Map the JSON payload to the function parameters or request body object.
            2. Walk through the code sequentially. State what happens at each conditional branch.
            3. Evaluate validation rules (if any) against the payload. Will it pass or fail?
            4. Detail the final return value, response object, or status code.
            5. Identify edge cases where this specific payload might trigger an exception.
            """,
                framework, entryPointCode, demoPayload,
                String.join(", ", payloadFields), additionalContext,
                String.join("; ", hints.patterns())
        );
    }

    private record SimulationHints(
            List<String> patterns,
            boolean hasValidation,
            boolean hasExceptionHandling
    ) {}
}
