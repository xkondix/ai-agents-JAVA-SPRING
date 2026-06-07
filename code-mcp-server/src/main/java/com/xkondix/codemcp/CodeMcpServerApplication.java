package com.xkondix.codemcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class CodeMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeMcpServerApplication.class, args);
    }
}
