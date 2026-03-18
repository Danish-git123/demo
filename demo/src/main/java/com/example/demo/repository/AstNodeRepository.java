package com.example.demo.repository;

import com.example.demo.entity.AstNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AstNodeRepository extends JpaRepository<AstNode, UUID> {

    List<AstNode> findByProjectId(UUID projectId);
    List<AstNode> findByProjectIdAndNodeType(UUID projectId, String nodeType);

    // Check if AI explanation already exists for this node (cache hit check)
    @Query("SELECT n FROM AstNode n WHERE n.id = :id AND n.aiExplanation IS NOT NULL")
    Optional<AstNode> findByIdWithExplanation(@Param("id") UUID id);

    // Get all nodes that have NO explanation yet (cache miss — needs LLM call)
    List<AstNode> findByProjectIdAndAiExplanationIsNull(UUID projectId);

    // Delete all nodes for a project (when re-analyzing)
    void deleteByProjectId(UUID projectId);
}
