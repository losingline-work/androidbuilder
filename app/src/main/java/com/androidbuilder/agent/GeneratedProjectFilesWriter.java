package com.androidbuilder.agent;

import com.androidbuilder.model.GeneratedProject;
import com.androidbuilder.model.GeneratedProjectFile;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.IOException;

public class GeneratedProjectFilesWriter {
    public void write(File sourceDir, GeneratedProject project) throws IOException {
        FileUtils.deleteRecursively(sourceDir);
        String rootPath = sourceDir.getCanonicalPath();
        for (GeneratedProjectFile generatedFile : project.files) {
            File target = new File(sourceDir, generatedFile.path);
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                throw new IOException("Generated file escapes project source directory: " + generatedFile.path);
            }
            FileUtils.writeText(target, generatedFile.content);
        }
    }
}
