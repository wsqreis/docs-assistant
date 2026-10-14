package dev.wsqreis.docs.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    /** ToolCallbackProvider beans are published by the Spring AI MCP server as MCP tools. */
    @Bean
    ToolCallbackProvider docsToolCallbacks(DocsTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
