/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.utils;

import java.io.*;

/**
 * Utils class
 */
public class Utils {

    /**
     * Delete directory including contents
     * @param folderPath folder path
     * @throws IOException
     */
    public static void deleteDirectoryIncludeContent(String folderPath) throws IOException {
        deleteDirectoryIncludeContent(new File(folderPath));
    }
    
    private static void deleteDirectoryIncludeContent(File f) throws IOException {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File c : files) {
                    deleteDirectoryIncludeContent(c);
                }
            }
        }
        if (!f.delete()) {
            throw new FileNotFoundException("Failed to delete : " + f);
        }
    }

    /**
     * Check whether file exists
     * @param path the file path
     * @return true if file exists
     */
    public static boolean fileExists(String path) {
        File f = new File(path);
        return f.exists() && !f.isDirectory();
    }

    /**
     * Check whether if folder exists
     * @param path the folder path
     * @return true if folder exists
     */
    public static boolean dirExists(String path) {
        File f = new File(path);
        return f.isDirectory() && f.exists();
    }           
}
