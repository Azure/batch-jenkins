/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.IOUtils;

/**
 * ZipHelper class
 */
public class ZipHelper {        

    /**
     * Zip folder
     * @param srcFolderPath source folder path
     * @param outputZipPath output zip file path
     * @throws FileNotFoundException
     * @throws IOException
     */
    public static void zipFolder(String srcFolderPath, String outputZipPath) throws FileNotFoundException, IOException {
        ZipOutputStream zip = null;
        FileOutputStream fileWriter = null;
        
        // Create the output stream to zip file result
        fileWriter = new FileOutputStream(outputZipPath);
        zip = new ZipOutputStream(fileWriter);
        
        // Add the folder to the zip
        addFolderToZip("", srcFolderPath, zip);
        
        // Close the zip objects
        zip.flush();
        zip.close();
    }
    
    /**
     * Unzip all zip files in a folder
     * @param srcFolderPath source folder
     * @param outputFolderPath output folder
     * @throws IOException
     */
    public static void unzipFolder(String srcFolderPath, String outputFolderPath) throws IOException {
        File srcFolder = new File(srcFolderPath);
        if (!srcFolder.exists()) {
            return;
        }
        
        if (!Utils.dirExists(outputFolderPath)) {
            Files.createDirectory(Paths.get(outputFolderPath));
        }
        
        File[] files = srcFolder.listFiles();
        if (files != null) {
            for (File file: files) {
                unzipFile(file, outputFolderPath);
            }
        }
    }
    
    private static void addFolderToZip(String path, String srcFolder, ZipOutputStream zip) throws IOException {
        File folder = new File(srcFolder);

        // Check the empty folder
        String[] files = folder.list();
        if (files == null || files.length == 0) {
            addFileToZip(path, srcFolder, zip, true);
        } else {
            // List the files in the folder
            for (String fileName : files) {
                if (path.equals("")) {
                    addFileToZip(folder.getName(), srcFolder + File.separator + fileName, zip, false);
                } else {
                    addFileToZip(path + File.separator + folder.getName(), srcFolder + File.separator + fileName, zip, false);
                }
            }
        }
    }
    
    // Recursively add files to the zip file
    private static void addFileToZip(String path, String srcFile, ZipOutputStream zip, boolean isEmptyFolder) throws IOException {
        File folder = new File(srcFile);

        if (isEmptyFolder == true) {
            zip.putNextEntry(new ZipEntry(path + "/" + folder.getName() + "/"));
        } else { 
            if (folder.isDirectory()) {
                addFolderToZip(path, srcFile, zip);
            } else {
                byte[] buf = new byte[1024];
                int len;
                try (FileInputStream in = new FileInputStream(srcFile)) {
                    zip.putNextEntry(new ZipEntry(path + "/" + folder.getName()));
                    while ((len = in.read(buf)) > 0) {
                        zip.write(buf, 0, len);
                    }
                }
            }
        }
    }
    
    private static void unzipFile(File zipFile, String outputFolderPath) throws FileNotFoundException, IOException {
        ZipInputStream zip = null;
        try {
            zip = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                File entryFile = new File(outputFolderPath, entry.getName());
                if (entry.isDirectory()) {
                    if (!entryFile.exists()) {
                        if (!entryFile.mkdirs()) {
                            throw new IOException(String.format("Failed to create folder %s %s", outputFolderPath, entry.getName()));
                        }
                    }
                } else {
                    // Create parent folder if it's not exist
                    File folder = entryFile.getParentFile();
                    if (!folder.exists()) {
                        if (!folder.mkdirs()) {
                            throw new IOException(String.format("Failed to create folder %s", folder.getAbsolutePath()));
                        }
                    }
                    
                    // Create the target file
                    if (!entryFile.createNewFile()) {
                        throw new IOException(String.format("Failed to create entry file %s", entryFile.getAbsolutePath()));
                    }

                    // And rewrite data from stream
                    OutputStream os = null;
                    try {
                        os = new FileOutputStream(entryFile);
                        IOUtils.copy(zip, os);
                    } finally {
                        IOUtils.closeQuietly(os);
                    }
                }
            }
        } finally {
            IOUtils.closeQuietly(zip);
        }
    }
}
