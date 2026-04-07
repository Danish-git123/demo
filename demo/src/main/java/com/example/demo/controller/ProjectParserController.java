package com.example.demo.controller;

import com.example.demo.dto.ParseRequest;
import com.example.demo.dto.ParseResponse;
import com.example.demo.service.ProjectParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/parse")
public class ProjectParserController {
    @Autowired
    private ProjectParserService projectParserService;

    @PostMapping
    public ResponseEntity<ParseResponse> parseProject(@RequestBody ParseRequest request) {
        try {
            log.info("Received parse request for URL: {}", request.getGithuburl());

            ParseResponse response = projectParserService.ParseProject(request);

            log.info("Parse completed successfully. Project ID: {}", response.getProjectId());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid input: {}", e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (RuntimeException e) {
            log.error("Parsing failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        } catch (Exception e) {
            log.error("Unexpected error during parsing: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

}
