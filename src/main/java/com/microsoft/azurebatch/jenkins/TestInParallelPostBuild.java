/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins;

import com.microsoft.azure.batch.BatchErrorCodeStrings;
import com.microsoft.azurebatch.jenkins.projectconfig.ProjectConfigHelper;
import com.microsoft.azurebatch.jenkins.jobsplitter.JobSplitterHelper;
import com.microsoft.azurebatch.jenkins.utils.ZipHelper;
import com.microsoft.azurebatch.jenkins.utils.Utils;
import com.microsoft.azurebatch.jenkins.azurebatch.AzureBatchHelper;
import com.microsoft.azurebatch.jenkins.azurebatch.BatchAccountInfo;
import com.microsoft.azurebatch.jenkins.azurestorage.AzureStorageHelper;
import com.microsoft.azurebatch.jenkins.azurestorage.StorageAccountInfo;
import com.microsoft.azurebatch.jenkins.logger.Logger;
import com.microsoft.azure.batch.protocol.models.*;
import com.microsoft.azurebatch.jenkins.resource.AzureBlobResourceEntity;
import com.microsoft.azurebatch.jenkins.resource.ResourceEntity;
import com.microsoft.azurebatch.jenkins.utils.WorkspaceHelper;
import com.microsoft.windowsazure.storage.StorageException;

import com.microsoft.windowsazure.storage.blob.CloudBlobContainer;
import hudson.Launcher;
import hudson.Extension;
import hudson.model.*;
import hudson.model.BuildListener;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.security.InvalidKeyException;

import java.util.concurrent.TimeoutException;

/**
 * Post build plugin for parallel testing.
 */
public class TestInParallelPostBuild  extends Recorder {

    //Fields from Jenkins plugin UX
    private final String batchAccount;
    private final String storageAccount;
    private final String projectConfigFilePath;
    private final String splitConfigFilePath;
    private final boolean enableVmUtilizationProfiler;
    
    // Shared resource files between all tasks, will be extracted under path %AZ_BATCH_NODE_SHARED_DIR%\%AZ_BATCH_JOB_ID%
    private List<ResourceEntity> sharedResourceEntityList;
    
    private WorkspaceHelper workspaceHelper = null;
    
    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.STEP;
    }

    /**
     * Get the batchAccount will be used in testing.
     * @return batchAccount
     */
    public String getBatchAccount() {
        return batchAccount;
    }

    /**
     * Get the storageAccount will be used in testing.
     * @return storageAccount
     */
    public String getStorageAccount() {
        return storageAccount;
    }

    /**
     * Get the projectConfigFilePath will be used in testing.
     * @return projectConfigFilePath
     */
    public String getProjectConfigFilePath() {
        return projectConfigFilePath;
    }

    /**
     * Get the splitConfigFilePath will be used in testing.
     * @return splitConfigFilePath
     */
    public String getSplitConfigFilePath() {
        return splitConfigFilePath;
    }

    /**
     * Get if enable VM utilization profiler in testing.
     * @return enableVmUtilizationProfiler
     */
    public boolean isEnableVmUtilizationProfiler() {
        return enableVmUtilizationProfiler;
    }

    @DataBoundConstructor
    public TestInParallelPostBuild(final String batchAccount,
                                   final String storageAccount,
                                   final String projectConfigFilePath,
                                   final String splitConfigFilePath,
                                   final boolean enableVmUtilizationProfiler
                                   ) {
        super();
        this.batchAccount = batchAccount;
        this.storageAccount = storageAccount;
        this.projectConfigFilePath = projectConfigFilePath;
        this.splitConfigFilePath = splitConfigFilePath;
        this.enableVmUtilizationProfiler = enableVmUtilizationProfiler;
    }

    @Override
    public TestInParallelPostBuildDescriptor getDescriptor() {
        return (TestInParallelPostBuildDescriptor) super.getDescriptor();
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                           BuildListener listener) throws InterruptedException, IOException {

        long startTime = System.currentTimeMillis();
        
        // Create poolJobId and jobId
        String poolJobId = AzureBatchHelper.createJobId("pool");
        String jobId = AzureBatchHelper.createJobId("task");
        Logger.log(listener, "Using PoolJobId %s, JobId %s", poolJobId, jobId);
        
        ProjectConfigHelper projectConfigHelper = null;
        JobSplitterHelper jobSplitterHelper = null;
        
        try {
            // Do Pre-req steps
            initialize(build, listener);
            Logger.log(listener, "Jenkins workspace path: " + workspaceHelper.getWorkspacePath());

            // Parse project and test splitter config
            projectConfigHelper = loadProjectConfigs(listener);
            jobSplitterHelper = loadJobSplitterConfigs(listener);
                        
            // Start creating tasks, and wait till all tasks finishes
            useBatchAndSubmitTasks(build, listener, poolJobId, jobId, projectConfigHelper, jobSplitterHelper);
        } catch (Exception e) {
            handleGeneralException(build, listener, e);
        } finally {
            StorageAccountInfo storageAcc = null;
            CloudBlobContainer cloudBlobContainer = null;
            
            try {            
                storageAcc = getDescriptor().getStorageAccountByFriendlyName(this.storageAccount);
                cloudBlobContainer = AzureStorageHelper.getBlobContainer(listener, storageAcc, jobId, false);

                // Download tests results from Azure Storage to the folder specified by customer in config.
                if (cloudBlobContainer.exists()) {                        
                    String tempFolderName = workspaceHelper.getPathRelativeToTempFolder("results" + UUID.randomUUID().toString());

                    Logger.log(listener, String.format("Downloading logs from storage for job %s to %s", jobId, tempFolderName));
                    AzureStorageHelper.download(listener, cloudBlobContainer, "logs/", tempFolderName);                    

                    String targetFolder = workspaceHelper.getPathRelativeToTempFolder("results");
                    if (projectConfigHelper != null && projectConfigHelper.getTestConfigs() != null &&
                            projectConfigHelper.getTestConfigs().getResultFilesSaveToFolder() != null) {
                        targetFolder = workspaceHelper.getPathRelativeToWorkspace(projectConfigHelper.getTestConfigs().getResultFilesSaveToFolder());
                    }
                    
                    Logger.log(listener, "Unzip log files to folder " + targetFolder);
                    ZipHelper.unzipFolder(tempFolderName, targetFolder);

                    Utils.deleteDirectoryIncludeContent(tempFolderName);
                } else {
                    Logger.log(listener, "No results are found, blob %s doesn't exists on storage.", jobId);
                }
            } catch (Exception e) {
                handleGeneralException(build, listener, e);
            }

            try {
                if (cloudBlobContainer != null) {
                    Logger.log(listener, "Cleaning up storage for job: " + jobId);
                    cloudBlobContainer.deleteIfExists();
                    Logger.log(listener, "Storage is cleaned up for job: %s", jobId);
                }
            } catch (Exception e) {
                handleGeneralException(build, listener, e);
            }
        }
        
        long timeSpanInMilliSeconds = System.currentTimeMillis() - startTime;

        Logger.log(listener, "Time Elapsed: " + String.format("%02d min, %02d sec",
                TimeUnit.MILLISECONDS.toMinutes(timeSpanInMilliSeconds),
                TimeUnit.MILLISECONDS.toSeconds(timeSpanInMilliSeconds) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(timeSpanInMilliSeconds))));

        return true;
    }
    
    private ProjectConfigHelper loadProjectConfigs(BuildListener listener) throws IOException, URISyntaxException, StorageException, InvalidKeyException
    {                
        String fullFilePath = this.getProjectConfigFilePath();
        if (!Paths.get(fullFilePath).isAbsolute()) {
            fullFilePath = this.workspaceHelper.getPathRelativeToWorkspace(fullFilePath);
        }
        ProjectConfigHelper projectConfigHelper = new ProjectConfigHelper(listener, fullFilePath);
                
        // Add resoureces to sharedResourceEntityList
        projectConfigHelper.addLocalResources(workspaceHelper.getWorkspacePath(), this.sharedResourceEntityList);
        projectConfigHelper.addBlobResources(this.sharedResourceEntityList);
        
        // Generate SAS keys for all AzureBlobResourceEntity
        for (ResourceEntity resource : this.sharedResourceEntityList) {
            if (resource instanceof AzureBlobResourceEntity) {
                AzureBlobResourceEntity blobResource = (AzureBlobResourceEntity) resource;
                boolean containsSasKey = blobResource.containsSasKey();
                if (!containsSasKey) {
                    String storageAccountName = blobResource.getStorageAccount();
                    StorageAccountInfo accountInfo = getDescriptor().getStorageAccountByAccountName(storageAccountName);
                    if (accountInfo == null) {
                        throw new UnsupportedOperationException(String.format("Unknown storage account %s to generate SAS for resource %s. " +
                                "If you're using public blob path, you may simply 'set' sas field as empty string in config file.", 
                                storageAccountName, resource.getBlobPath()));
                    }       
                    
                    String containerName = blobResource.getContainerName();
                    CloudBlobContainer container = AzureStorageHelper.getBlobContainer(listener, accountInfo, containerName, false);
                    if (!container.exists()) {
                        throw new UnsupportedOperationException(String.format("Container %s doesn't exist for resource %s, failed to generate SAS.", 
                                containerName, resource.getBlobPath()));
                    }
                    blobResource.setSasKey(AzureStorageHelper.getContainerSas(listener, container, 3 * 60));
                }
            }
        }     
        
        return projectConfigHelper;
    }
    
    private JobSplitterHelper loadJobSplitterConfigs(BuildListener listener) throws IOException, URISyntaxException, StorageException, InvalidKeyException
    {                
        String fullFilePath = this.getSplitConfigFilePath();
        if (!Paths.get(fullFilePath).isAbsolute()) {
            fullFilePath = this.workspaceHelper.getPathRelativeToWorkspace(fullFilePath);
        }
        return new JobSplitterHelper(listener, fullFilePath);
    }
   
    private void useBatchAndSubmitTasks(AbstractBuild<?, ?> build, final BuildListener listener, 
            String poolJobId, String jobId, ProjectConfigHelper projectConfigHelper, JobSplitterHelper jobSplitterHelper) 
            throws BatchErrorException, IOException, InterruptedException, TimeoutException, FileNotFoundException, IllegalArgumentException, URISyntaxException, StorageException, InvalidKeyException  {  
        AzureBatchHelper batchHelper = null;
        
        try
        {
            batchHelper = new AzureBatchHelper(listener, workspaceHelper, projectConfigHelper, jobSplitterHelper, 
                    sharedResourceEntityList, isEnableVmUtilizationProfiler(), poolJobId, jobId, 
                    getDescriptor().getBatchAccountByFriendlyName(this.batchAccount), 
                    getDescriptor().getStorageAccountByFriendlyName(this.storageAccount));
            
            // Create Batch job and wait for finish
            batchHelper.startJobAndWaitForCompletion();
            
        } catch (BatchErrorException e) {
            Logger.log(listener, "Found BatchErrorException");
            Logger.log(listener, String.format("BatchError code = %s, message = %s", e.getBody().getCode(), e.getBody().getMessage().getValue()));
            if (BatchErrorCodeStrings.ActiveJobAndScheduleQuotaReached.equals(e.getBody().getCode())) {
                Logger.log(listener, "You've reached your Batch account ActiveJobAndSchedule quota limit (default is 20 if you haven't " + 
                            "requested increase), and you may want to request quota increase if needed. For more information on Batch quotas " +
                            "and how to increase them, see https://azure.microsoft.com/documentation/articles/batch-quota-limit/");
            } else {
                Logger.log(listener, e);                
            }
            if (e.getBody().getValues() != null) {
                for (BatchErrorDetail detail : e.getBody().getValues()) {
                    Logger.log(listener, String.format("Detail %s=%s", detail.getKey(), detail.getValue()));
                }
            }
            takeActionFromException(build, listener, e, Result.FAILURE);
        } catch (Exception e) {
            handleGeneralException(build, listener, e);
        } finally {
            if (batchHelper != null) {
                try {
                    // Try to retrieve job results regardless job passing or failing
                    batchHelper.retrieveJobOutputsFromVM(); 
                } catch (Exception e) {
                    handleGeneralException(build, listener, e);
                }
                
                try {
                    // Clean up actual job
                    batchHelper.deleteTaskJob();
                } catch (Exception e) {
                    handleGeneralException(build, listener, e);
                }
                
                try {
                    // Clean up Batch job and pool
                    batchHelper.deletePoolJob();
                } catch (Exception e) {
                    handleGeneralException(build, listener, e);
                }
                
                if (isEnableVmUtilizationProfiler()) {
                    batchHelper.stopVmUtilizationProfiler();
                }
            }
        }
    }
    
    private void initialize(AbstractBuild<?, ?> build, BuildListener listener) throws FileNotFoundException, IOException, InterruptedException {        
        this.sharedResourceEntityList = new ArrayList<>();
        
        Map<String, String> envVars = build.getEnvironment(listener);
        workspaceHelper = new WorkspaceHelper(envVars.get("WORKSPACE"));
        
        String tempFolderPath = workspaceHelper.getTempFolderPath();

        // if the directory does not exist, create it else detlete it
        if (Utils.dirExists(tempFolderPath)) {
            Logger.log(listener, workspaceHelper.getTempFolderPath() + " already exist.. deleting it");
            Utils.deleteDirectoryIncludeContent(tempFolderPath);
        }

        Logger.log(listener, "Creating directory: " + tempFolderPath);
        Files.createDirectory(Paths.get(tempFolderPath));

        if (Utils.dirExists(tempFolderPath)) {
            Logger.log(listener, workspaceHelper.getTempFolderPath() + " got created");
        }
        else
        {
            throw new FileNotFoundException(String.format("Directory %s doesn't exist", tempFolderPath));
        }
    }

    void takeActionFromException(AbstractBuild<?, ?> build, BuildListener listener, Exception e, Result r)
    {
        build.setResult(r);
    }
    
    void handleGeneralException(AbstractBuild<?, ?> build, BuildListener listener, Exception e) {
        Logger.log(listener, "Found exception");
        e.printStackTrace(listener.getLogger());
        takeActionFromException(build, listener, e, Result.FAILURE);        
    }

    @Extension
    public static final class TestInParallelPostBuildDescriptor extends
            BuildStepDescriptor<Publisher> {

        private final CopyOnWriteList<BatchAccountInfo> batchAccounts = new CopyOnWriteList<>();
        private final CopyOnWriteList<StorageAccountInfo> storageAccounts = new CopyOnWriteList<>();

        public TestInParallelPostBuildDescriptor() {
            super();
            load();
        }
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException{
            batchAccounts.replaceBy(req.bindJSONToList(BatchAccountInfo.class, formData.get("batchAccountConfig")));
            storageAccounts.replaceBy(req.bindJSONToList(StorageAccountInfo.class, formData.get("storageAccountConfig")));
            save();
            return super.configure(req, formData);
        }

        public BatchAccountInfo[] getBatchAccounts() {
            return batchAccounts
                    .toArray(new BatchAccountInfo[batchAccounts.size()]);
        }

        public StorageAccountInfo[] getStorageAccounts() {
            return storageAccounts
                    .toArray(new StorageAccountInfo[storageAccounts.size()]);
        }

        public BatchAccountInfo getBatchAccountByFriendlyName(String friendlyName) {

            if (friendlyName == null || (friendlyName.trim().length() == 0)) {
                throw new IllegalArgumentException("Unable to find Batch account with null friendly name %s");
            }
            
            BatchAccountInfo[] batchAccounts = getBatchAccounts();

            if (batchAccounts != null) {
                for (BatchAccountInfo ba : batchAccounts) {
                    if (ba.getFriendlyName().equals(friendlyName)) {
                        return ba;
                    }
                }
            }
            throw new IllegalArgumentException(String.format("Unable to find Batch account with friendly name %s", friendlyName));
        }

        public StorageAccountInfo getStorageAccountByFriendlyName(String friendlyName) {

            if (friendlyName == null || (friendlyName.trim().length() == 0)) {
                throw new IllegalArgumentException("Unable to find Storage account with null friendly name %s");
            }

            StorageAccountInfo[] storageAccounts = getStorageAccounts();

            if (storageAccounts != null) {
                for (StorageAccountInfo sa : storageAccounts) {
                    if (sa.getFriendlyName().equals(friendlyName)) {
                        return sa;
                    }
                }
            }
            throw new IllegalArgumentException(String.format("Unable to find Storage account with friendly name %s", friendlyName));
        }

        public StorageAccountInfo getStorageAccountByAccountName(String accountName) {

            if (accountName == null || (accountName.trim().length() == 0)) {
                return null;
            }

            StorageAccountInfo storageAccountInfo = null;
            StorageAccountInfo[] storageAccounts = getStorageAccounts();

            if (storageAccounts != null) {
                for (StorageAccountInfo sa : storageAccounts) {
                    if (sa.getAccountName().equals(accountName)) {
                        storageAccountInfo = sa;
                    }
                    break;
                }
            }
            return storageAccountInfo;
        }

        public ListBoxModel doFillBatchAccountItems() {
            ListBoxModel m = new ListBoxModel();
            BatchAccountInfo[] BatchAccounts = getBatchAccounts();

            if (BatchAccounts != null) {
                for (BatchAccountInfo batchAccount : BatchAccounts) {
                    m.add(batchAccount.getFriendlyName());
                }
            }
            return m;
        }

        public ListBoxModel doFillStorageAccountItems() {
            ListBoxModel m = new ListBoxModel();
            StorageAccountInfo[] StorageAccounts = getStorageAccounts();

            if (StorageAccounts != null) {
                for (StorageAccountInfo storageAccount : StorageAccounts) {
                    m.add(storageAccount.getFriendlyName());
                }
            }
            return m;
        }

        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Execute tests in parallel with Microsoft Azure Batch";
        }
    
        public FormValidation doCheckBatchAccountConfig(
                @QueryParameter("friendlyName") final String friendlyName,
                @QueryParameter("accountName") final String accountName,
                @QueryParameter("accountKey") final String accountKey,
                @QueryParameter("serviceURL") final String serviceURL) {
            try {                
                AzureBatchHelper.validateBatchAccount(accountName, accountKey, serviceURL);
                
                return FormValidation.ok(String.format("Validation succeeded for Azure Batch account %s.", accountName));
            } catch (Exception e) {
                return FormValidation.error("Validation failed, please double check Azure Batch account settings. Error: " + e);
            }
        } 
    
        public FormValidation doCheckStorageAccountConfig(
                @QueryParameter("friendlyName") final String friendlyName,
                @QueryParameter("accountName") final String accountName,
                @QueryParameter("accountKey") final String accountKey,
            @QueryParameter("endpointDomain") final String endpointDomain) {
            try {                
                AzureStorageHelper.validateStorageAccount(accountName, accountKey, endpointDomain);
                
                return FormValidation.ok(String.format("Validation succeeded for Azure Storage account %s.", accountName));
            } catch (Exception e) {
                return FormValidation.error("Validation failed, please double check Azure Storage account settings. Error: " + e);
            }
        } 
        
        public FormValidation doCheckFriendlyName(
                @QueryParameter("friendlyName") final String friendlyName) {            
            if (friendlyName == null || friendlyName.isEmpty()) {
                return FormValidation.error("Validation failed, please set friendly name.");
            }
            return FormValidation.ok();
        }
    }
}

