package com.mtcc.project.extractor.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class IOProperties {

    @Value("${IGNORE.HIDDEN.FILES.AND.FOLDERS}")
    private Boolean ignoreHiddenFilesAndFolders;

    @Value("${PROJECTS.DOWNLOAD.FOLDER}")
    private String projectsDownloadFolder;
}
