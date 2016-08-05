/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.projectconfig;

import com.microsoft.azurebatch.jenkins.projectconfig.autogen.ProjectConfig;
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
 * ProjectConfigFactory class
 */
public class ProjectConfigFactory {

    /**
     * Generate project config
     * @param listener BuildListener
     * @param fullFilePath full file path of project config file
     * @return ProjectConfig instance
     * @throws IOException
     */
    public static ProjectConfig generateProjectConfig(BuildListener listener, String fullFilePath) throws IOException
    {              
        Logger.log(listener, "Reading project configurations from %s...", fullFilePath);
                
        if (!Utils.fileExists(fullFilePath)) {
            throw new IOException(String.format("Project config file '%s' doesn't exist, please double check your configuration.", fullFilePath));
        }
        
        Gson gson = new Gson();
        ProjectConfig config = null;
        try (Reader reader = new InputStreamReader(new FileInputStream(new File(fullFilePath)), Charset.defaultCharset())) {
            config = gson.fromJson(reader, ProjectConfig.class);
        }
        
        // TODO: validate against schema
        // Do some basic check for project config, in case customer may provide wrong config file.
        basicCheckProjectConfig(config);
                
        Logger.log(listener, "Created project config from config %s", fullFilePath);
        return config;
    }

    private static void basicCheckProjectConfig(ProjectConfig config) throws InvalidObjectException {
        if (config.getVersion() == null || config.getVersion().isEmpty()) {
            throw new InvalidObjectException("version is not set in project config file, please double check your configuration.");
        }
        
        if (config.getVmConfigs() == null || config.getVmConfigs().getNumVMs() < 1) {
            throw new InvalidObjectException("numVMs is not set in project config file, please double check your configuration.");
        }
    }      
}
