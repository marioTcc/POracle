package com.mtcc.oracle.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import reactor.core.publisher.Mono;

public interface CodingAgent {

    @SystemMessage("""
            You are an expert senior developer. Your task is to analyze the general details of a project
            by the structure of its content.
            
            Get the project's files tree, and add your considerations at the end, three phrases at most.
            """)
    @UserMessage("Analyze this project: {{projectStructure}}")
    Mono<String> analyzeProjectStructure(@V("projectStructure") String projectStructure);
}
