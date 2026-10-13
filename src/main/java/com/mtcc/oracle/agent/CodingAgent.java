package com.mtcc.oracle.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import reactor.core.publisher.Mono;

public interface CodingAgent {

    @SystemMessage("""
            You write factual overviews of one folder of a software project for a search index.
            Use only the outlines of its files and the overviews of its subfolders that you are given. Do not invent features.

            Cover: what the folder contains and what it is responsible for. Name its main classes, files or subfolders.
            Write plain prose in English, 40 to 100 words, no headings, no preamble.
            """)
    @UserMessage("Project: {{projectName}}\nFolder: {{folderPath}}\n\nContents:\n{{contents}}")
    Mono<String> summarizeFolder(@V("projectName") String projectName, @V("folderPath") String folderPath,
                                 @V("contents") String contents);
}
