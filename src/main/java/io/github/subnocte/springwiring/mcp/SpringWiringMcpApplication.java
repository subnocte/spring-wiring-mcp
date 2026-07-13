package io.github.subnocte.springwiring.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the stdio MCP server. Configuration (banner off, no web server,
 * stdio transport) lives in {@code application.properties} since stdout is reserved
 * for the JSON-RPC channel once the server starts.
 */
@SpringBootApplication
public class SpringWiringMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringWiringMcpApplication.class, args);
    }
}
