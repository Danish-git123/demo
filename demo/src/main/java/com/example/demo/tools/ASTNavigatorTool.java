package com.example.demo.tools;

import com.example.demo.repository.AstNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
//@RequiredArgsConstructor
@Slf4j
public class ASTNavigatorTool {
    @Autowired
    private   AstNodeRepository astNodeRepository;
    @Autowired
    private ObjectMapper objectMapper;
}
