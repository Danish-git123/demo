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

    Optional<AstNode> findByIdAndRepoUrl(String id, String repoUrl);

    List<AstNode> findByRepoUrlAndNodeType(String repoUrl, String nodeType);

    // Check if AI explanation already exists for this node (cache hit check)
    @Query("SELECT n FROM AstNode n WHERE n.id = :id AND n.aiExplanation IS NOT NULL")
    Optional<AstNode> findByIdWithExplanation(@Param("id") UUID id);

    // Get all nodes that have NO explanation yet (cache miss — needs LLM call)
    List<AstNode> findByProjectIdAndAiExplanationIsNull(UUID projectId);

    // Delete all nodes for a project (when re-analyzing)
    void deleteByProjectId(UUID projectId);

    // Find nodes that reference a specific class name — for dependency resolution
    @Query("""
        SELECT n FROM ASTNode n
        WHERE n.repoUrl = :repoUrl
        AND (n.rawCode LIKE %:className% OR n.metadata::text LIKE %:className%)
        AND n.id != :excludeId
        ORDER BY n.nodeType
        """)
    List<AstNode> findRelatedByClassName(
            @Param("repoUrl") String repoUrl,
            @Param("className") String className,
            @Param("excludeId") String excludeId
    );

    @Query("""
        SELECT n FROM ASTNode n
        WHERE n.repoUrl = :repoUrl
        AND n.filePath = :filePath
        """)
    List<AstNode> findByRepoUrlAndFilePath(
            @Param("repoUrl") String repoUrl,
            @Param("filePath") String filePath
    );
}
