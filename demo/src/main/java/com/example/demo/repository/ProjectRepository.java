package com.example.demo.repository;

import com.example.demo.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByUserId(UUID userId);

    Optional<Project> findByGithubUrlAndUserId(String githubUrl,UUID userId);

    List<Project> findByStatus(Project.ProjectStatus status);

    List<Project> findByUserIdAndStatus(UUID userId, Project.ProjectStatus status);
}
