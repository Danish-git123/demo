package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;


@Entity
@Table(name = "ast_nodes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class AstNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // e.g. API_ENDPOINT, SERVICE, REPOSITORY, FUNCTION, CLASS
    @Column(name = "node_type", nullable = false)
    private String nodeType;

    // Human readable name e.g. "UserController", "getUser()"
    @Column(name = "label", nullable = false)
    private String label;

    // File path in the repo e.g. "src/main/java/UserController.java"
    @Column(name = "file_path")
    private String filePath;

    // The raw code chunk for this node — sent to LLM for explanation
    @Column(name = "raw_code", columnDefinition = "TEXT")
    private String rawCode;

    // AI-generated explanation — null until user clicks this node (lazy load)
    // Once generated, cached here so we don't call LLM again
    @Column(name = "ai_explanation", columnDefinition = "TEXT")
    private String aiExplanation;

    // JSON string storing relationships to other node IDs (edges of the graph)
    // e.g. ["uuid-1", "uuid-2"] — parsed by frontend React Flow
    @Column(name = "dependencies", columnDefinition = "TEXT")
    private String dependencies;

    // X,Y position hints for React Flow graph layout
//    this is like for custom dragging of nodes in ui which is optional
    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Many nodes belong to one project
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}