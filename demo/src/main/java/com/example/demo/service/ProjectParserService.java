package com.example.demo.service;

import com.example.demo.entity.AstNode;
import com.example.demo.entity.Project;
import com.example.demo.repository.ProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ProjectParserService {
    @Autowired
    private ProjectRepository projectRepository;

//    Upload a github Url and then parse the message
    @Transactional
    public AstNode ParseProject(String GithubUrl){

    }
}
