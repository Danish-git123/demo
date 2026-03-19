package com.example.demo.service;

import com.example.demo.dto.NodeDTO;
import com.example.demo.dto.ParseRequest;
import com.example.demo.dto.ParseResponse;
import com.example.demo.entity.AstNode;
import com.example.demo.entity.Project;
import com.example.demo.entity.User;
import com.example.demo.repository.AstNodeRepository;
import com.example.demo.repository.ProjectRepository;
import com.example.demo.repository.UserRepository;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

//import static org.apache.tomcat.util.http.fileupload.FileUtils.deleteDirectory;

@Service
@Slf4j
public class ProjectParserService {
    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AstNodeRepository astNodeRepository;

//    Upload a github Url and then parse the message
    @Transactional
    public ParseResponse ParseProject(ParseRequest request) throws IOException {
        String githuburl=request.getGithuburl();
        validateGithubUrl(githuburl);

        User user=getAuthenticatedUser();

//        checking if project already exist
//        if yes then delete this and create a new fresh parse
        projectRepository.findByGithubUrlAndUserId(githuburl,user.getId())
                .ifPresent(existing->{
                    log.info("Re-analyzing existing project:{}",existing.getId());
                    astNodeRepository.deleteByProjectId(existing.getId());
                    projectRepository.delete(existing);
                });

//        if project not exist then now build the project
        String repoName=extractRepoName(githuburl);
        Project project=Project.builder()
                .githubUrl(githuburl)
                .repoName(repoName)
                .user(user)
                .status(Project.ProjectStatus.PROCESSING)
                .build();
//      saving the project
        project=projectRepository.save(project);
        log.info("Project saved with id:{}",project.getId());

// cloning the repo into a temp directory
        Path tempDir=null;
        try{
            tempDir=cloneRepo(githuburl);

//            Now we need to detect stack and build the map acordingly
            Map<StackType,Path> detectedStacks=detectAllStacks(tempDir);
            log.info("Detected Stacks:{} ",detectedStacks.keySet());

            if(detectedStacks.isEmpty()){
                project.setDetectedStack("UNKNOWN");
                project.setStatus(Project.ProjectStatus.COMPLETED);
                projectRepository.save(project);
                return buildResponse(project, new ArrayList<>());
            }

//            parsing nodes for each detected stack
            List<AstNode>allNodes=new ArrayList<>();
            for(Map.Entry<StackType, Path> entry:detectedStacks.entrySet()){
                StackType stack=entry.getKey();
                Path stackRoot=entry.getValue();

                List<AstNode> stackNodes = switch (stack) {
                    case SPRING_BOOT -> parseSpringBootNodes(stackRoot, project);
                    case NODE_EXPRESS -> parseNodeExpressNodes(stackRoot, project);
                    case DJANGO -> parseDjangoNodes(stackRoot, project);
                    default -> new ArrayList<>();
                };

                allNodes.addAll(stackNodes);

            }

            astNodeRepository.saveAll(allNodes);
            log.info("Saved {} nodes for project {}", allNodes.size(), project.getId());

            // Step 9 — Mark project as COMPLETED
            project.setDetectedStack(String.join(",", detectedStacks.keySet()
                    .stream()
                    .map(StackType::name)
                    .toList()));
            project.setStatus(Project.ProjectStatus.COMPLETED);
            project = projectRepository.save(project);

            // Step 10 — Build and return response for React frontend
            return buildResponse(project, allNodes);
        } catch (Exception e) {
            // Mark project as FAILED if anything goes wrong
            project.setStatus(Project.ProjectStatus.FAILED);
            projectRepository.save(project);
            log.error("Parsing failed for {}: {}", githuburl, e.getMessage());
            throw new RuntimeException("Failed to parse project: " + e.getMessage(), e);
        }finally {
            // Always clean up temp cloned repo from disk
            if (tempDir != null) {
                try {
                    forceDeleteDirectory(tempDir);
                } catch (IOException ex) {
                    log.warn("Could not fully clean temp dir {}: {}", tempDir, ex.getMessage());
                }
            }
        }
    }
    private void forceDeleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        // Make all files writable before deletion (fixes Windows .git lock issues)
        Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder()) // delete children before parents
                .forEach(path -> {
                    try {
                        path.toFile().setWritable(true);
                        Files.delete(path);
                    } catch (IOException e) {
                        log.debug("Could not delete {}: {}", path, e.getMessage());
                    }
                });
    }

    private List<AstNode> parseSpringBootNodes(Path repoRoot, Project project) throws IOException {
        List<AstNode> nodes = new ArrayList<>();

        // Walk all .java files in the repo
        try (Stream<Path> javaFiles = Files.walk(repoRoot)
                .filter(p -> p.toString().endsWith(".java"))) {

            javaFiles.forEach(javaFile -> {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(javaFile);

                    // Visit every class in the file
                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {

                        String nodeType = detectNodeType(classDecl);
                        String relativePath = repoRoot.relativize(javaFile).toString();

                        // Create a node for the class itself
                        AstNode classNode = AstNode.builder()
                                .nodeType(nodeType)
                                .label(classDecl.getNameAsString())
                                .filePath(relativePath)
                                .rawCode(classDecl.toString())
                                .project(project)
                                .build();
                        nodes.add(classNode);

                        // For controllers: also extract each endpoint as its own node
                        if (nodeType.equals("API_ENDPOINT")) {
                            classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                                boolean isEndpoint = method.getAnnotations().stream()
                                        .map(AnnotationExpr::getNameAsString)
                                        .anyMatch(a -> a.matches("GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping"));

                                if (isEndpoint) {
                                    AstNode methodNode = AstNode.builder()
                                            .nodeType("API_ENDPOINT")
                                            .label(classDecl.getNameAsString() + "#" + method.getNameAsString())
                                            .filePath(relativePath)
                                            .rawCode(method.toString())
                                            .project(project)
                                            .build();
                                    nodes.add(methodNode);
                                }
                            });
                        }
                    });

                } catch (Exception e) {
                    // Skip unparseable files — don't crash the whole analysis
                    log.warn("Could not parse file {}: {}", javaFile.getFileName(), e.getMessage());
                }
            });
        }

        return nodes;
    }

    private List<AstNode> parseNodeExpressNodes(Path repoRoot, Project project) throws IOException {
        List<AstNode> nodes = new ArrayList<>();

        log.info("Node/Express parsing not yet implemented");
        // TODO: Implement JavaScript/TypeScript parsing
        // You could use Nashorn, GraalVM, or a JavaScript parser library

        return nodes;
    }

    // ─────────────────────────────────────────────
    // STEP 7c — PARSE DJANGO NODES (Placeholder)
    // ─────────────────────────────────────────────

    private List<AstNode> parseDjangoNodes(Path repoRoot, Project project) throws IOException {
        List<AstNode> nodes = new ArrayList<>();

        log.info("Django parsing not yet implemented");
        // TODO: Implement Python parsing
        // You could use Jython or a Python AST parser library

        return nodes;
    }

    private String detectNodeType(ClassOrInterfaceDeclaration classDecl) {
        List<String> annotations = classDecl.getAnnotations()
                .stream()
                .map(AnnotationExpr::getNameAsString)
                .toList();

        if (annotations.contains("RestController") || annotations.contains("Controller")) {
            return "API_ENDPOINT";
        }
        if (annotations.contains("Service")) {
            return "SERVICE";
        }
        if (annotations.contains("Repository")) {
            return "REPOSITORY";
        }
        if (annotations.contains("Component")) {
            return "COMPONENT";
        }
        if (annotations.contains("Configuration")) {
            return "CONFIG";
        }
        if (annotations.contains("Entity")) {
            return "ENTITY";
        }
        return "CLASS";
    }

    private ParseResponse buildResponse(Project project, List<AstNode>nodes){
        List<NodeDTO> nodeDTOs=nodes.stream()
                .map(node->NodeDTO.builder()
                        .id(node.getId())
                        .label(node.getLabel())
                        .nodeType(node.getNodeType())
                        .filePath(node.getFilePath())
                        .dependancies(new ArrayList<>())

                        .build()
                ).toList();

        return ParseResponse.builder()
                .projectId(project.getId())
                .repoName(project.getRepoName())
                .detectedStack(project.getDetectedStack())
                .status(project.getStatus().name())
                .nodes(nodeDTOs)
                .build();
    }

    private User getAuthenticatedUser(){
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        String supabaseId = (String) authentication.getPrincipal();

        return userRepository.findBySupabaseId(supabaseId).orElseThrow(()->new RuntimeException("User Not found in DB for supabaseId"));
    }

    private void validateGithubUrl(String url){
        if(url==null || url.isBlank()){
            throw new IllegalArgumentException("GitHub URL cannot be empty");

        }
        if (!url.startsWith("https://github.com/")) {
            throw new IllegalArgumentException("URL must be a public GitHub URL (https://github.com/...)");
        }
    }

    private String extractRepoName(String githubUrl){
        String[] parts=githubUrl.split("/");
        return parts[parts.length-1].replace(".git","");
    }

    private Path cloneRepo(String githuburl) throws Exception{
        Path tempDir= Files.createTempDirectory("codelens-");
        log.info("Cloning {} into {}",githuburl,tempDir);

        Git.cloneRepository()
                .setURI(githuburl)
                .setDirectory(tempDir.toFile())
                .setDepth(1)
                .setNoTags()
                .call()
                .close();

        return tempDir;
    }

    private Map<StackType,Path> detectAllStacks(Path repoRoot) throws IOException{
        Map<StackType,Path>detectedStacks=new HashMap<>();
        try (Stream<Path> paths = Files.walk(repoRoot, 3)) {
            paths.filter(Files::isRegularFile)
                    .forEach(filePath -> {
                        try {
                            String fileName = filePath.getFileName().toString();
                            StackType stack = detectStackByFileName(fileName);

                            if (stack != StackType.UNKNOWN) {
                                // Find the most relevant root directory for this stack
                                Path stackRoot = findStackRootDirectory(filePath, repoRoot, stack);

                                // Only add if we haven't already detected this stack
                                // (or if this is a better/closer match)
                                detectedStacks.putIfAbsent(stack, stackRoot);
                                log.info("Detected {} in {}", stack, stackRoot);
                            }
                        } catch (Exception e) {
                            // Log but don't fail — just continue scanning
                            log.debug("Error checking file {}: {}", filePath, e.getMessage());
                        }
                    });
        }

        return detectedStacks;

    }

    private Path findStackRootDirectory(Path filePath, Path repoRoot, StackType stackType) {
        Path parent = filePath.getParent();
        String fileName = filePath.getFileName().toString();

        // Special handling for package.json — it's often in a subfolder (frontend, ui, etc.)
        if (stackType == StackType.NODE_EXPRESS && fileName.equals("package.json")) {
            // Check if this is a frontend package.json by looking for common frontend markers
            try {
                String content = Files.readString(filePath);
                if (content.contains("react") || content.contains("vue") || content.contains("angular")) {
                    return parent;  // This is the frontend root
                }
            } catch (IOException e) {
                log.debug("Could not read package.json content");
            }
        }

        // For other stacks, the parent directory is typically the root
        return parent != null ? parent : repoRoot;
    }

    private StackType detectStackByFileName(String fileName) {
        return switch (fileName) {
            case "pom.xml" -> StackType.SPRING_BOOT;
            case "package.json" -> StackType.NODE_EXPRESS;
            case "requirements.txt" -> StackType.DJANGO;
            case "Gemfile" -> StackType.RUBY_RAILS;
            case "go.mod" -> StackType.GO;
            case "Cargo.toml" -> StackType.RUST;
            default -> StackType.UNKNOWN;
        };
    }

    private enum StackType {
        SPRING_BOOT,
        NODE_EXPRESS,
        DJANGO,
        RUBY_RAILS,
        GO,
        RUST,
        UNKNOWN
    }


}
