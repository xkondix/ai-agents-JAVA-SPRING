package com.xkondix.codemcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "code-mcp")
public class CodeMcpProperties {
    private String projectRoot = "C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING";
    private List<String> allowedExtensions = List.of(
            ".java",".xml",".yml",".yaml",".properties",
            ".md",".json",".py",".jsx",".js",".ts",".txt",".sql");
    private List<String> ignoredDirs = List.of(
            ".git","target","node_modules",".idea",".mvn","__pycache__","logs");
    private int approvalTimeoutMinutes = 10;
}
