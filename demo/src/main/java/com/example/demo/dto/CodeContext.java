package com.example.demo.dto;

import com.example.demo.entity.AstNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.aspectj.weaver.ast.ASTNode;

import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeContext {
    private ASTNode primaryNode;
    private List<AstNode> relatedNodes;
    private String assembledCodeContext;
}
