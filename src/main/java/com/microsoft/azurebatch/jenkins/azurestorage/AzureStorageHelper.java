/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.azurestorage;

import com.microsoft.azurebatch.jenkins.logger.Logger;
import com.microsoft.azurebatch.jenkins.utils.Utils;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import com.microsoft.windowsazure.storage.CloudStorageAccount;
import com.microsoft.windowsazure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.windowsazure.storage.StorageException;
import com.microsoft.windowsazure.storage.blob.BlobContainerPermissions;
import com.microsoft.windowsazure.storage.blob.BlobListingDetails;
import com.microsoft.windowsazure.storage.blob.BlobRequestOptions;
import com.microsoft.windowsazure.storage.blob.CloudBlobClient;
import com.microsoft.windowsazure.storage.blob.CloudBlobContainer;
import com.microsoft.windowsazure.storage.blob.CloudBlockBlob;
import com.microsoft.windowsazure.storage.blob.ListBlobItem;
import com.microsoft.windowsazure.storage.blob.SharedAccessBlobPermissions;
import com.microsoft.windowsazure.storage.blob.SharedAccessBlobPolicy;
import hudson.FilePath;
import hudson.model.BuildListener;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidKeyException;

/**
 * AzureStorageHelper class
 */
public class AzureStorageHelper {
    private static final String QUEUE = "queue";
    private static final String TABLE = "table";
    private static final String BLOB = "blob";
    
    /**
     * Validate storage account
     * @param accountName account name
     * @param accountKey account key
     * @param endpointDomain endpoint domain
     * @throws URISyntaxException
     * @throws StorageException
     */
    public static void validateStorageAccount(String accountName, String accountKey, String endpointDomain) throws URISyntaxException, StorageException {
        CloudStorageAccount cloudStorageAccount = getCloudStorageAccount(accountName, accountKey, endpointDomain);

        CloudBlobClient client = cloudStorageAccount.createCloudBlobClient();

        // Try to list non-exist containers to check if storage account settings are right
        Iterable<CloudBlobContainer> containers = client.listContainers("somecontainernotexist");
        for (CloudBlobContainer container : containers)
        {
            container.exists();
        }
    }
    
    private static CloudStorageAccount getCloudStorageAccount(String accountName, String accountKey, String endpointDomain) throws URISyntaxException {
        StorageCredentialsAccountAndKey credentials = new StorageCredentialsAccountAndKey(accountName, accountKey);

        CloudStorageAccount cloudStorageAccount = new CloudStorageAccount(credentials, 
                    new URI(getEndpointURI(accountName, BLOB, endpointDomain)),
                    new URI(getEndpointURI(accountName, QUEUE, endpointDomain)),
                    new URI(getEndpointURI(accountName, TABLE, endpointDomain)));
        
        return cloudStorageAccount;
    }
    
    private static CloudStorageAccount getCloudStorageAccount(StorageAccountInfo accountInfo) throws URISyntaxException {
        return getCloudStorageAccount(accountInfo.getAccountName(), accountInfo.getAccountKey(), accountInfo.getEndpointDomain());
    }
    
    /**
     * Get blob container
     * @param listener BuildListener
     * @param accountInfo storage account info
     * @param containerName container name
     * @param createIfNotExist create blob if not exist
     * @return blob container reference
     * @throws URISyntaxException
     * @throws StorageException
     */
    public static CloudBlobContainer getBlobContainer(BuildListener listener,
            StorageAccountInfo accountInfo, String containerName, boolean createIfNotExist) throws URISyntaxException, StorageException {
        CloudStorageAccount cloudStorageAccount = getCloudStorageAccount(accountInfo);

        CloudBlobClient client = cloudStorageAccount.createCloudBlobClient();
        
        CloudBlobContainer container = client.getContainerReference(containerName);
        if (createIfNotExist) {
            container.createIfNotExists();
        }
        
        return container;
    }
    
    /**
     * Get container SAS
     * @param listener BuildListener
     * @param container storage container
     * @param expirationInMins SAS expiration in minutes
     * @return container SAS
     * @throws StorageException
     * @throws InvalidKeyException
     */
    public static String getContainerSas(BuildListener listener, CloudBlobContainer container, int expirationInMins) throws StorageException, InvalidKeyException {
        SharedAccessBlobPolicy policy = new SharedAccessBlobPolicy();
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTime(new Date());

        calendar.add(Calendar.MINUTE, expirationInMins);
        policy.setSharedAccessExpiryTime(calendar.getTime());
        // Set READ permission for downloading resource files to VM.
        // Set WRITE and LIST permissions for uploading test results to storage.
        policy.setPermissions(EnumSet.of(SharedAccessBlobPermissions.READ, 
                SharedAccessBlobPermissions.WRITE, SharedAccessBlobPermissions.LIST));

        BlobContainerPermissions containerPermissions = new BlobContainerPermissions();
        containerPermissions.getSharedAccessPolicies().put("jenkins" + System.currentTimeMillis(), policy);
        container.uploadPermissions(containerPermissions);

        // Create a shared access signature for the container.
        return container.generateSharedAccessSignature(policy, null);
    }
    
    /**
     * Upload file or folder to blob
     * @param listener BuildListener
     * @param blob storage blob to upload
     * @param src source file or folder
     * @return uploaded blob URI
     * @throws StorageException
     * @throws IOException
     * @throws InterruptedException
     */
    public static URI upload(BuildListener listener, CloudBlockBlob blob, FilePath src)
            throws StorageException, IOException, InterruptedException {
        try (InputStream inputStream = src.read()) {
            blob.upload(inputStream, src.length(), null,
                    getBlobRequestOptions(), null);
            return blob.getUri();
        }
    }
    
    /**
     * Download blobs having given prefix
     * @param listener BuildListener
     * @param container storage container
     * @param blobPrefix blob prefix
     * @param targetFolderName target folder name
     * @throws StorageException
     * @throws IOException
     * @throws URISyntaxException
     */
    public static void download(BuildListener listener, CloudBlobContainer container, String blobPrefix, String targetFolderName) throws StorageException, IOException, URISyntaxException {
        if (!Utils.dirExists(targetFolderName)) {
            Files.createDirectory(Paths.get(targetFolderName));
        }
        
        int count = 0;
        for (ListBlobItem blobItem : container.listBlobs(blobPrefix, true, EnumSet.of(BlobListingDetails.METADATA), null, null))
        {
            if (blobItem instanceof CloudBlockBlob) {
                CloudBlockBlob retrievedBlob = (CloudBlockBlob) blobItem;
                
                String fileName = retrievedBlob.getName();
                fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
                    
                retrievedBlob.downloadToFile(targetFolderName + File.separator + fileName);
                
                count++;
            }
        }
        if (count == 0) {
            Logger.log(listener, "No blobs are found in storage container %s.", container.getName());            
        } else {
            Logger.log(listener, "Downloaded %d blobs.", count);            
        }
    }

    private static BlobRequestOptions getBlobRequestOptions() {
        BlobRequestOptions options = new BlobRequestOptions();
        options.setConcurrentRequestCount(Runtime.getRuntime().availableProcessors());

        return options;
    }

    private static String getEndpointURI(String storageAccountName, String type, String endpointDomain) {        
        return String.format("https://%s.%s.%s", storageAccountName, type, endpointDomain);
    }
}
