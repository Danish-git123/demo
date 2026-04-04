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
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Service
@Slf4j
public class ProjectParserService {

    private static final int BATCH_SIZE = 250;
    private static final int MAX_FILE_SIZE = 500 * 1024;
    private static final int HTTP_REQUEST_TIMEOUT = 120000;
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "dist", "build", ".next", "target",
            "out", ".gradle", "__pycache__", ".venv", "vendor", ".vscode",
            ".idea", ".DS_Store", "coverage", ".nyc_output", "test", "tests"
    );

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "js", "jsx", "ts", "tsx", "py", "java", "go", "rs", "rb", "cs", "cpp", "c", "php", "vue"
    );

    private final ExecutorService fileReaderExecutor;
    private final ExecutorService httpExecutor;
    private final RestTemplate restTemplate;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AstNodeRepository astNodeRepository;

    public ProjectParserService() {
        this.fileReaderExecutor = new ForkJoinPool(THREAD_POOL_SIZE);
        this.httpExecutor = Executors.newFixedThreadPool(Math.min(4, THREAD_POOL_SIZE));
        this.restTemplate = createRestTemplate();
    }

    private RestTemplate createRestTemplate() {
        RestTemplate template = new RestTemplate();
        return template;
    }

    @PostConstruct
    public void checkSidecarHealth() {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    "http://localhost:3001/health",
                    Map.class
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Parser sidecar is healthy and ready.");
            } else {
                log.warn("Parser sidecar returned non-200 status: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("Parser sidecar health check failed: http://localhost:3001/health", e);
        }
    }

    @Transactional
    public ParseResponse ParseProject(ParseRequest request) throws IOException {
        String githuburl = request.getGithuburl();
        validateGithubUrl(githuburl);

        User user = getAuthenticatedUser();

        projectRepository.findByGithubUrlAndUserId(githuburl, user.getId())
                .ifPresent(existing -> {
                    log.info("Re-analyzing existing project: {}", existing.getId());
                    astNodeRepository.deleteByProjectId(existing.getId());
                    projectRepository.delete(existing);
                });

        String repoName = extractRepoName(githuburl);
        Project project = Project.builder()
                .githubUrl(githuburl)
                .repoName(repoName)
                .user(user)
                .status(Project.ProjectStatus.PROCESSING)
                .build();

        project = projectRepository.save(project);
        log.info("Project created with id: {}", project.getId());

        Path tempDir = null;
        long startTime = System.currentTimeMillis();
        try {
            tempDir = cloneRepo(githuburl);
            log.info("Repository cloned to: {} (took {}ms)", tempDir, System.currentTimeMillis() - startTime);

            long stackDetectStart = System.currentTimeMillis();
            Map<StackType, Path> detectedStacks = detectAllStacks(tempDir);
            log.info("Detected Stacks: {} (took {}ms)", detectedStacks.keySet(), System.currentTimeMillis() - stackDetectStart);

            if (detectedStacks.isEmpty()) {
                project.setDetectedStack("UNKNOWN");
                project.setStatus(Project.ProjectStatus.COMPLETED);
                projectRepository.save(project);
                return buildResponse(project, new ArrayList<>());
            }

            // ===== NEW: Pass detected framework to parser =====
            StackType primaryFramework = detectedStacks.keySet().iterator().next();
            String frameworkName = stackTypeToFrameworkName(primaryFramework);

            // Parse all files in parallel
            long parseStart = System.currentTimeMillis();
            List<AstNode> allNodes = parseWithTreeSitterOptimized(tempDir, project, frameworkName);
            log.info("Parsed {} nodes total (took {}ms) for framework: {}",
                    allNodes.size(), System.currentTimeMillis() - parseStart, frameworkName);

            List<AstNode> savedNodes = astNodeRepository.saveAll(allNodes);
            log.info("Saved {} nodes for project {}", savedNodes.size(), project.getId());

            project.setDetectedStack(String.join(",", detectedStacks.keySet()
                    .stream()
                    .map(StackType::name)
                    .toList()));
            project.setStatus(Project.ProjectStatus.COMPLETED);
            project = projectRepository.save(project);

            log.info("Total parsing time: {}ms", System.currentTimeMillis() - startTime);
            return buildResponse(project, savedNodes);
        } catch (Exception e) {
            if (project != null && project.getId() != null) {
                project.setStatus(Project.ProjectStatus.FAILED);
                projectRepository.save(project);
            }
            log.error("Parsing failed for {}: {}", githuburl, e.getMessage(), e);
            throw new RuntimeException("Failed to parse project: " + e.getMessage(), e);
        } finally {
            if (tempDir != null) {
                try {
                    forceDeleteDirectory(tempDir);
                } catch (Exception ex) {
                    log.warn("Could not fully clean temp dir {}: {}", tempDir, ex.getMessage());
                }
            }
        }
    }



    private List<AstNode> parseWithTreeSitterOptimized(Path repoRoot, Project project, String frameworkName) {
        List<AstNode> allNodes = Collections.synchronizedList(new ArrayList<>());
        List<Path> validFiles = new ArrayList<>();

        long fileCollectionStart = System.currentTimeMillis();
        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(dirName) || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".") + 1) : "";

                    if (SUPPORTED_EXTENSIONS.contains(ext) && attrs.size() <= MAX_FILE_SIZE) {
                        validFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            log.info("Found {} valid files in {} (took {}ms)",
                    validFiles.size(), repoRoot, System.currentTimeMillis() - fileCollectionStart);
        } catch (Exception e) {
            log.error("Error walking file tree: {}", e.getMessage());
            return allNodes;
        }

        if (validFiles.isEmpty()) {
            log.warn("No valid files found in {}", repoRoot);
            return allNodes;
        }

        long httpStart = System.currentTimeMillis();
        List<Map<String, Object>> batchRequests = new ArrayList<>();

        // Convert files to batch request format
        for (Path filePath : validFiles) {
            try {
                String code = Files.readString(filePath);
                String relativePath = repoRoot.relativize(filePath).toString();
                String ext = filePath.getFileName().toString()
                        .substring(filePath.getFileName().toString().lastIndexOf(".") + 1);

                batchRequests.add(Map.of(
                        "filePath", relativePath,
                        "code", code,
                        "extension", ext
                ));
            } catch (Exception e) {
                log.debug("Error reading file {}: {}", filePath, e.getMessage());
            }
        }

        // Split into batches
        List<CompletableFuture<Void>> httpFutures = new ArrayList<>();
        for (int i = 0; i < batchRequests.size(); i += BATCH_SIZE) {
            int endIdx = Math.min(i + BATCH_SIZE, batchRequests.size());
            List<Map<String, Object>> chunk = batchRequests.subList(i, endIdx);
            int batchIndex = i / BATCH_SIZE;

            CompletableFuture<Void> httpFuture = CompletableFuture.runAsync(() -> {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    // ===== NEW: Include framework in request =====
                    Map<String, Object> requestBody = new HashMap<>();
                    requestBody.put("files", chunk);
                    requestBody.put("framework", frameworkName);

                    HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

                    ResponseEntity<Map> response = restTemplate.postForEntity(
                            "http://localhost:3001/parseBatch",
                            request,
                            Map.class
                    );

                    Map responseBody = response.getBody();

                    if (responseBody != null && responseBody.containsKey("results")) {
                        List<Map<String, Object>> results = (List<Map<String, Object>>) responseBody.get("results");

                        for (Map<String, Object> fileResult : results) {
                            String filePath = (String) fileResult.get("filePath");
                            List<Map<String, Object>> nodes = (List<Map<String, Object>>) fileResult.get("nodes");

                            if (nodes != null) {
                                for (Map<String, Object> node : nodes) {
                                    AstNode astNode = AstNode.builder()
                                            .nodeType((String) node.get("nodeType"))
                                            .label((String) node.get("label"))
                                            .filePath(filePath)
                                            .rawCode((String) node.get("rawCode"))
                                            .project(project)
                                            .build();
                                    allNodes.add(astNode);
                                }
                            }
                        }
                    }
                    log.debug("Batch {} completed: {} files", batchIndex, chunk.size());
                } catch (Exception e) {
                    log.error("Batch {} request failed: {}", batchIndex, e.getMessage());
                }
            }, httpExecutor);

            httpFutures.add(httpFuture);
        }

        CompletableFuture<Void> allHttpRequests = CompletableFuture.allOf(
                httpFutures.toArray(new CompletableFuture[0])
        );

        try {
            allHttpRequests.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.error("HTTP requests timeout after 5 minutes");
        } catch (Exception e) {
            log.error("HTTP requests failed: {}", e.getMessage());
        }

        log.info("HTTP batch requests completed (took {}ms)", System.currentTimeMillis() - httpStart);
        return allNodes;
    }

    private void forceDeleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            path.toFile().setWritable(true);
                            Files.delete(path);
                        } catch (Exception e) {
                            log.debug("Could not delete {}: {}", path, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.debug("Failed during directory walk deletion: {}", e.getMessage());
        }
    }

    private ParseResponse buildResponse(Project project, List<AstNode> nodes) {
        List<NodeDTO> nodeDTOs = nodes.stream()
                .map(node -> NodeDTO.builder()
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

    private User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String supabaseId = (String) authentication.getPrincipal();

        return userRepository.findBySupabaseId(supabaseId)
                .orElseThrow(() -> new RuntimeException("User not found for supabaseId: " + supabaseId));
    }

    private void validateGithubUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("GitHub URL cannot be empty");
        }
        if (!url.startsWith("https://github.com/")) {
            throw new IllegalArgumentException("URL must be a public GitHub URL (https://github.com/...)");
        }
    }

    private String extractRepoName(String githubUrl) {
        String[] parts = githubUrl.split("/");
        return parts[parts.length - 1].replace(".git", "");
    }

    private Path cloneRepo(String githuburl) throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-");
        log.info("Cloning {} into {}", githuburl, tempDir);

        Git.cloneRepository()
                .setURI(githuburl)
                .setDirectory(tempDir.toFile())
                .setDepth(1)
                .setNoTags()
                .setCloneAllBranches(false)
                .call()
                .close();

        return tempDir;
    }

    private Map<StackType, Path> detectAllStacks(Path repoRoot) throws IOException {
        Map<StackType, Path> detectedStacks = Collections.synchronizedMap(new HashMap<>());

        try (Stream<Path> paths = Files.walk(repoRoot, 3)) {
            paths.filter(Files::isRegularFile)
                    .parallel()
                    .forEach(filePath -> {
                        try {
                            String fileName = filePath.getFileName().toString();
                            StackType stack = detectStackByFileName(fileName);

                            if (stack != StackType.UNKNOWN) {
                                // NEW: Look inside the file to refine the exact framework
                                stack = refineStackType(filePath, stack);

                                Path stackRoot = filePath.getParent() != null ? filePath.getParent() : repoRoot;
                                detectedStacks.putIfAbsent(stack, stackRoot);
                                log.info("Detected {} in {}", stack, stackRoot);
                            }
                        } catch (Exception e) {
                            log.debug("Error checking file {}: {}", filePath, e.getMessage());
                        }
                    });
        }
        return detectedStacks;
    }

    // NEW: Reads package.json and requirements.txt to differentiate JS/Python frameworks
    private StackType refineStackType(Path filePath, StackType currentStack) {
        String fileName = filePath.getFileName().toString();
        try {
            if (currentStack == StackType.NODE_EXPRESS && fileName.equals("package.json")) {
                String content = Files.readString(filePath).toLowerCase();
                if (content.contains("\"next\"") || content.contains("\"react\"")) return StackType.REACT_NEXT;
                if (content.contains("\"vue\"") || content.contains("\"nuxt\"")) return StackType.VUE;
                if (content.contains("\"@angular/core\"")) return StackType.ANGULAR;
            } else if (currentStack == StackType.DJANGO && (fileName.equals("requirements.txt") || fileName.equals("pyproject.toml"))) {
                String content = Files.readString(filePath).toLowerCase();
                if (content.contains("flask")) return StackType.FLASK;
            }
        } catch (IOException e) {
            log.debug("Could not read file for stack refinement");
        }
        return currentStack;
    }


    private Path findStackRootDirectory(Path filePath, Path repoRoot, StackType stackType) {
        Path parent = filePath.getParent();
        String fileName = filePath.getFileName().toString();

        if (stackType == StackType.NODE_EXPRESS && fileName.equals("package.json")) {
            try {
                String content = Files.readString(filePath);
                if (content.contains("react") || content.contains("vue") || content.contains("angular")) {
                    return parent;
                }
            } catch (IOException e) {
                log.debug("Could not read package.json content");
            }
        }

        return parent != null ? parent : repoRoot;
    }

    private StackType detectStackByFileName(String fileName) {
        if (fileName.endsWith(".csproj")) return StackType.ASP_NET;

        return switch (fileName) {
            case "pom.xml", "build.gradle" -> StackType.SPRING_BOOT;
            case "package.json" -> StackType.NODE_EXPRESS;
            case "requirements.txt", "Pipfile", "pyproject.toml" -> StackType.DJANGO;
            case "Gemfile" -> StackType.RUBY_RAILS;
            case "artisan", "composer.json" -> StackType.LARAVEL;
            case "angular.json" -> StackType.ANGULAR;
            default -> StackType.UNKNOWN;
        };
    }

    // 2. Updated String Mapper
    private String stackTypeToFrameworkName(StackType stackType) {
        return stackType.name(); // Since the enum names now match the Node config exactly
    }

    private enum StackType {
        SPRING_BOOT, NODE_EXPRESS, REACT_NEXT, ANGULAR, VUE,
        DJANGO, FLASK, RUBY_RAILS, LARAVEL, ASP_NET, UNKNOWN
    }
}