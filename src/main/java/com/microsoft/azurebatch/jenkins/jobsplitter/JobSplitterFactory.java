/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.jobsplitter;

import com.microsoft.azurebatch.jenkins.jobsplitter.autogen.JobSplitter;
import com.microsoft.azurebatch.jenkins.jobsplitter.autogen.Task;
import com.google.gson.Gson;
import com.microsoft.azurebatch.jenkins.logger.Logger;
import com.microsoft.azurebatch.jenkins.utils.Utils;
import hudson.model.BuildListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InvalidObjectException;
import java.io.Reader;
import java.nio.charset.Charset;

/**
 * JobSplitterFactory class
 */
public class JobSplitterFactory {

    /**
     * Generate JobSplitter
     * @param listener BuildListener
     * @param fullFilePath full file path of config file
     * @return JobSplitter object
     * @throws IOException
     */
    public static JobSplitter generateJobSplitter(BuildListener listener, String fullFilePath)
            throws IOException
    {
        Logger.log(listener, "Reading job splitter configurations from %s...", fullFilePath);
        
        if (!Utils.fileExists(fullFilePath)) {
            throw new IOException(String.format("Job splitter config file '%s' doesn't exist, please double check your configuration.", fullFilePath));
        }
        
        Gson gson = new Gson();     
        JobSplitter splitter = null;
        try (Reader reader = new InputStreamReader(new FileInputStream(new File(fullFilePath)), Charset.defaultCharset())) {
            splitter = gson.fromJson(reader, JobSplitter.class);
        }
                
        // TODO: validate against schema
        // Do some basic check for splitter config, in case customer may provide wrong config file.
        basicCheckJobSplitterConfig(splitter);
        
        Logger.log(listener, "Created job splitter from config %s", fullFilePath);
        return splitter;
    }

    private static void basicCheckJobSplitterConfig(JobSplitter config) throws InvalidObjectException {
        if (config.getVersion() == null || config.getVersion().isEmpty()) {
            throw new InvalidObjectException("version is not set in splitter config file, please double check your configuration.");
        }
        
        if (config.getJobConfigs() == null || config.getJobConfigs().getTasks() == null || config.getJobConfigs().getTasks().size() < 1) {
            throw new InvalidObjectException("tasks are not set in splitter config file, please double check your configuration.");
        }
        
        int index = 0;
        for (Task task : config.getJobConfigs().getTasks()) {
            if (task.getName() == null || task.getName().isEmpty()) {
                throw new InvalidObjectException(String.format("#%d task's name is not set or empty in splitter config file, please double check your configuration.", index));
            }
            
            if (task.getCommands().isEmpty()) {
                throw new InvalidObjectException(String.format("task '%s''s commands are not set in splitter config file, please double check your configuration.", task.getName()));                
            }
            
            index++;
        }
    }   
}
