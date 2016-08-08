/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.azurebatch;

import com.microsoft.azurebatch.jenkins.logger.Logger;
import com.microsoft.azure.batch.*;
import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials;
import com.microsoft.azure.batch.protocol.models.AllocationState;
import com.microsoft.azure.batch.protocol.models.AutoPoolSpecification;
import com.microsoft.azure.batch.protocol.models.BatchErrorException;
import com.microsoft.azure.batch.protocol.models.CloudJob;
import com.microsoft.azure.batch.protocol.models.CloudPool;
import com.microsoft.azure.batch.protocol.models.CloudServiceConfiguration;
import com.microsoft.azure.batch.protocol.models.CloudTask;
import com.microsoft.azure.batch.protocol.models.ComputeNode;
import com.microsoft.azure.batch.protocol.models.ComputeNodeDeallocationOption;
import com.microsoft.azure.batch.protocol.models.ComputeNodeState;
import com.microsoft.azure.batch.protocol.models.JobAddParameter;
import com.microsoft.azure.batch.protocol.models.JobConstraints;
import com.microsoft.azure.batch.protocol.models.JobPatchParameter;
import com.microsoft.azure.batch.protocol.models.JobPreparationAndReleaseTaskExecutionInformation;
import com.microsoft.azure.batch.protocol.models.JobPreparationTaskExecutionInformation;
import com.microsoft.azure.batch.protocol.models.JobPreparationTaskState;
import com.microsoft.azure.batch.protocol.models.PoolInformation;
import com.microsoft.azure.batch.protocol.models.PoolLifetimeOption;
import com.microsoft.azure.batch.protocol.models.PoolSpecification;
import com.microsoft.azure.batch.protocol.models.TaskExecutionInformation;
import com.microsoft.azure.batch.protocol.models.TaskSchedulingError;
import com.microsoft.azure.batch.protocol.models.TaskState;
import com.microsoft.azurebatch.jenkins.azurestorage.AzureStorageHelper;
import com.microsoft.azurebatch.jenkins.azurestorage.StorageAccountInfo;
import com.microsoft.azurebatch.jenkins.jobsplitter.JobSplitterHelper;
import com.microsoft.azurebatch.jenkins.projectconfig.ProjectConfigHelper;
import com.microsoft.azurebatch.jenkins.projectconfig.autogen.VmConfigs;
import com.microsoft.azurebatch.jenkins.resource.ResourceEntity;
import com.microsoft.azurebatch.jenkins.utils.Utils;
import com.microsoft.azurebatch.jenkins.utils.WorkspaceHelper;
import com.microsoft.windowsazure.storage.StorageException;
import com.microsoft.windowsazure.storage.blob.CloudBlobContainer;
import hudson.model.BuildListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.apache.commons.io.FileUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Period;

/**
 * Azure Batch Helper class.
 */
public class AzureBatchHelper {
    
    private final BuildListener listener;
    private final ProjectConfigHelper projectConfigHelper;
    private final JobSplitterHelper jobSplitterHelper;
    private final List<ResourceEntity> sharedResourceEntityList;
    private final WorkspaceHelper workspaceHelper;
    private final boolean enableVmUtilizationProfiler;
    private VmUtilizationProfiler vmUtilizationProfiler;
    private final String poolJobId;
    private final String jobId;    
    private final StorageAccountInfo storageAccountInfo;
    
    private final BatchClient client;
    private final String taskLogDirPath;
    private final Set<String> retrievedTasks = new HashSet<>();
    
    // Batch auto pool Id prefix
    private static final String autoPoolIdPrefix = "jenkinspool";
    
    private static final String stdoutFileName = "stdout.txt";
    
    private static final String stderrFileName = "stderr.txt";
    
    /**
     * AzureBatchHelper constructor
     * @param listener BuildListener
     * @param workspaceHelper WorkspaceHelper
     * @param projectConfigHelper ProjectConfigHelper
     * @param jobSplitterHelper JobSplitterHelper
     * @param sharedResourceEntityList the shared resource entity list
     * @param enableVmUtilizationProfiler whether enable VM utilization profiler
     * @param poolJobId pool job Id
     * @param jobId working job Id
     * @param batchAccountInfo batch account used in testing
     * @param storageAccountInfo storage account used in testing
     * @throws URISyntaxException
     * @throws StorageException
     * @throws InvalidKeyException
     * @throws BatchErrorException
     * @throws IOException 
     */
    public AzureBatchHelper(BuildListener listener, 
            WorkspaceHelper workspaceHelper,
            ProjectConfigHelper projectConfigHelper, 
            JobSplitterHelper jobSplitterHelper, 
            List<ResourceEntity> sharedResourceEntityList,
            boolean enableVmUtilizationProfiler,
            String poolJobId, 
            String jobId, 
            BatchAccountInfo batchAccountInfo,
            StorageAccountInfo storageAccountInfo) throws URISyntaxException, StorageException, InvalidKeyException, BatchErrorException, IOException {
        this.listener = listener;
        this.projectConfigHelper = projectConfigHelper;
        this.jobSplitterHelper = jobSplitterHelper;
        this.sharedResourceEntityList = sharedResourceEntityList;
        this.workspaceHelper = workspaceHelper;
        this.enableVmUtilizationProfiler = enableVmUtilizationProfiler;
        this.poolJobId = poolJobId;
        this.jobId = jobId;
        this.storageAccountInfo = storageAccountInfo;
        
        taskLogDirPath = workspaceHelper.getPathRelativeToTempFolder(jobId + "-output");
        if (!Utils.dirExists(taskLogDirPath)) {
            Files.createDirectory(Paths.get(taskLogDirPath));
        }
                
        client = createBatchClient(batchAccountInfo);
    }
    
    /**
     * Create a random job Id.
     * @return job Id
     * @param tag tag for job Id
     */
    public static String createJobId(String tag) {
        SimpleDateFormat dateFormatUtc = new SimpleDateFormat("yyyyMMddHHmmss");
        dateFormatUtc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String utcTime = dateFormatUtc.format(new Date());
        
        String jobIdPrefix = "jenkins-" + tag;
        return jobIdPrefix + "-" + utcTime + "-" + UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * Validate Batch account with specified parameters.
     * @param accountName account name of Batch account
     * @param accountKey account key of Batch account
     * @param serviceURL service URL of Batch account
     * @throws BatchErrorException
     * @throws IOException 
     */
    public static void validateBatchAccount(String accountName, String accountKey, String serviceURL) 
            throws BatchErrorException, IOException {
        BatchSharedKeyCredentials cred = new BatchSharedKeyCredentials(serviceURL, accountName, accountKey);
        BatchClient client = BatchClient.open(cred);
        
        // Check a pool exists to validate Batch account, this pool doesn't need to exist
        client.poolOperations().existsPool("someRandomPoolJustForValidation");        
    }
    
    /**
     * Create and start Batch job and then wait for completion
     * @throws BatchErrorException
     * @throws IOException
     * @throws InterruptedException
     * @throws TimeoutException
     * @throws URISyntaxException
     * @throws StorageException
     * @throws InvalidKeyException 
     */
    public void startJobAndWaitForCompletion() throws BatchErrorException, IOException, InterruptedException, TimeoutException, URISyntaxException, StorageException, InvalidKeyException {
        // Our autopool has a lifetime, which we initially set to 3 hours for resources setup.
        // When tasks are added, we extend the pool's lifetime to ensure that all the tasks can be completed.
        int poolLifeTimeBeforeTasksAddedInMinutes = 3 * 60;

        // Create an auto pool for the job.
        VmConfigs vmConfigs = projectConfigHelper.getVMConfigs();
        createJobWithAutoPool(vmConfigs.getOsFamily(), 
                vmConfigs.getTargetOSVersion(), 
                vmConfigs.getNumVMs(), 
                vmConfigs.getVmSize(), 
                vmConfigs.isPoolKeepAlive(), 
                vmConfigs.getMaxTasksPerNode(),
                poolLifeTimeBeforeTasksAddedInMinutes);

        String poolId = client.jobOperations().getJob(poolJobId).executionInfo().poolId();

        if (enableVmUtilizationProfiler) {
            vmUtilizationProfiler = new VmUtilizationProfiler(listener, client, poolId, workspaceHelper.getPathRelativeToTempFolder("vmUtilizaton.csv"));
            vmUtilizationProfiler.start();
        }
        
        // Create storage container and generate SAS for resource upload.
        CloudBlobContainer storageContainer = AzureStorageHelper.getBlobContainer(listener, storageAccountInfo, jobId, true);
        int containerSasExpirationInMins = poolLifeTimeBeforeTasksAddedInMinutes + jobSplitterHelper.getJobTimeoutInMinutes() + 60;
        String containerSasKey = AzureStorageHelper.getContainerSas(listener, storageContainer, containerSasExpirationInMins);
        
        // Create working job with tasks
        JobGenerator jobGenerator = new JobGenerator(listener, workspaceHelper, projectConfigHelper, jobSplitterHelper, sharedResourceEntityList,
                client, jobId, poolId, storageAccountInfo, containerSasKey);
        jobGenerator.createJobWithTasks(jobSplitterHelper.getJobTimeoutInMinutes());

        // Extend poolJob (the autopool) timeout long enough to run the actual job.
        extendPoolJobTimeout(jobSplitterHelper.getJobTimeoutInMinutes());              

        // Wait for all tasks completed.
        waitForAllTasksCompleted(poolId, jobSplitterHelper.getJobTimeoutInMinutes());
    }
    
    /**
     * Retrieve job JobPrep and all tasks stdout and stderr outputs from VM.
     * @throws IOException
     * @throws BatchErrorException
     * @throws InterruptedException
     * @throws TimeoutException 
     */
    public void retrieveJobOutputsFromVM() throws IOException, BatchErrorException, InterruptedException, TimeoutException {        
        // Check if job exists
        try {
            client.jobOperations().getJob(jobId);
        } catch (BatchErrorException e) {
            if (BatchErrorCodeStrings.JobNotFound.equals(e.getBody().code())) {
                Logger.log(listener, "Batch job is not created, no results to retrieve.");
                return;
            }
            throw e;
        } 
        
        // Retrieve JobPrep task stdout and stderr files
        retrieveAllJobPrepTasksOutput();
        
        // Retrieve test tasks results files
        retrieveJobTestTaskResults();
    }
    
    /**
     * Delete pool job.
     * @throws BatchErrorException
     * @throws IOException 
     */
    public void deletePoolJob() throws BatchErrorException, IOException {
        deleteJob(poolJobId);
    }
    
    /**
     * Delete task job.
     * @throws BatchErrorException
     * @throws IOException 
     */
    public void deleteTaskJob() throws BatchErrorException, IOException {
        deleteJob(jobId);
    }
    
    /**
     * Stop VM utilization profiler
     * @throws InterruptedException 
     */
    public void stopVmUtilizationProfiler() throws InterruptedException {
        if (vmUtilizationProfiler != null) {            
            vmUtilizationProfiler.interrupt();
            vmUtilizationProfiler.join(); 
        }
    }
        
    /**
     * Create Batch client.
     * @param accountInfo Batch account info
     * @return the client
     * @throws BatchErrorException
     * @throws IOException 
     */
    private BatchClient createBatchClient(
            BatchAccountInfo accountInfo) throws BatchErrorException, IOException {                
        String batchServiceUrl = accountInfo.getServiceURL();
        String batchAccount = accountInfo.getAccountName();
        String batchAccountKey = accountInfo.getAccountKey();
        
        BatchSharedKeyCredentials cred = new BatchSharedKeyCredentials(batchServiceUrl, batchAccount, batchAccountKey);
        
        Logger.log(listener, "Creating Azure Batch client with account %s batchServiceUrl %s", batchAccount, batchServiceUrl);
        return BatchClient.open(cred);
    }
        
    /**
     * Create an pool via creating a Batch job with auto pool. We set an initial timeout for the pool life, and will extend it to add 
     * extra timeout from the task job. We use auto pool to help manage the life cycle of pool, so even when Jenkins server has some
     * issue, the life cycle of pool will be limited, and customers won't be charged for that. Creating pool and VMs could be time consuming,
     * we use a separate pool job to create pool at earliest possible, and in future, we plan to allow customers to create pool in earlier
     * step in Jenkins project, say, during building step, which will help improve the start up experience.
     * @param poolSpecOsFamily Os Family
     * @param poolSpecTargetOSVersion Target OS version, see more <a href="https://azure.microsoft.com/documentation/articles/batch-api-basics/">here</a>
     * @param poolSpecTargetDedicated Target dedicated, see more <a href="https://azure.microsoft.com/documentation/articles/batch-api-basics/">here</a>
     * @param poolSpecVmSize VM size, see more <a href="https://azure.microsoft.com/documentation/articles/batch-api-basics/">here</a>
     * @param poolSpecAutoPoolKeepAlive Keep auto pool alive, see more <a href="https://azure.microsoft.com/documentation/articles/batch-api-basics/">here</a>
     * @param poolSpecMaxTasksPerNode Max tasks per VM, see more <a href="https://azure.microsoft.com/documentation/articles/batch-api-basics/">here</a>
     * @param poolTimeoutInMin Timeout in minutes of the pool, see more <a href="https://azure.microsoft.com/documentation/articles/batch-api-basics/">here</a>
     * @throws BatchErrorException
     * @throws IOException
     * @throws InterruptedException
     * @throws TimeoutException 
     */
    private void createJobWithAutoPool(
            String poolSpecOsFamily,
            String poolSpecTargetOSVersion,
            int poolSpecTargetDedicated,
            String poolSpecVmSize,
            boolean poolSpecAutoPoolKeepAlive,
            int poolSpecMaxTasksPerNode,
            int poolTimeoutInMin) throws BatchErrorException, IOException, InterruptedException, TimeoutException {  
        Logger.log(listener, "Creating auto pool for poolJob %s", poolJobId);

        Logger.log(listener, "Set CloudServiceConfiguration: OsFamily %s, TargetOSVersion %s", poolSpecOsFamily, poolSpecTargetOSVersion);
        CloudServiceConfiguration cloudServiceConfiguration = new CloudServiceConfiguration();
        cloudServiceConfiguration.withOsFamily(poolSpecOsFamily)
                .withTargetOSVersion(poolSpecTargetOSVersion);        

        Logger.log(listener, "Set PoolSpecification: TargetDedicated %d, VmSize %s", poolSpecTargetDedicated, poolSpecVmSize);
        PoolSpecification poolSpecification = new PoolSpecification();
        poolSpecification.withTargetDedicated(poolSpecTargetDedicated)
                .withVmSize(poolSpecVmSize)
                .withMaxTasksPerNode(poolSpecMaxTasksPerNode)
                .withCloudServiceConfiguration(cloudServiceConfiguration);

        Logger.log(listener, "Set AutoPoolSpecification: AutoPoolIdPrefix %s, KeepAlive %b", getAutoPoolIdPrefix(), poolSpecAutoPoolKeepAlive);
        AutoPoolSpecification autoPoolSpecification = new AutoPoolSpecification();
        autoPoolSpecification.withAutoPoolIdPrefix(getAutoPoolIdPrefix())
                .withKeepAlive(poolSpecAutoPoolKeepAlive)
                .withPoolLifetimeOption(PoolLifetimeOption.JOB)
                .withPool(poolSpecification);

        PoolInformation poolInformation = new PoolInformation();
        poolInformation.withAutoPoolSpecification(autoPoolSpecification);

        // Set timeout for the job so it won't run forever even when there's issue with the tests
        JobConstraints jobConstraints = new JobConstraints();
        jobConstraints.withMaxWallClockTime(Period.minutes(poolTimeoutInMin));  
        Logger.log(listener, "Set poolJob %s constraints with timeout %d minutes.", poolJobId, poolTimeoutInMin);    

        JobAddParameter param = new JobAddParameter();
        param.withId(poolJobId)
                .withPoolInfo(poolInformation)
                .withConstraints(jobConstraints);        

        client.jobOperations().createJob(param);
        Logger.log(listener, "PoolJob %s is created.", poolJobId);
    }
    
    /**
     * Extend pool job's timeout
     * @param extraTimeoutInMin Extra timeout in minutes
     * @throws BatchErrorException
     * @throws IOException 
     */
    private void extendPoolJobTimeout(int extraTimeoutInMin) throws BatchErrorException, IOException {
        CloudJob job = client.jobOperations().getJob(poolJobId);
        
        DateTime jobCreatedTime = job.creationTime();
        DateTime now = new DateTime(DateTimeZone.UTC);
                
        // Add some safe buffer timeout to the new job timeout
        final int safeMoreTimeoutInMin = 15;
        int newJobTimeoutInMin = (int)((now.getMillis() - jobCreatedTime.getMillis()) / 1000 / 60) + extraTimeoutInMin + safeMoreTimeoutInMin;
        
        JobConstraints jobConstraints = new JobConstraints();
        jobConstraints.withMaxWallClockTime(Period.minutes(newJobTimeoutInMin));  
        
        JobPatchParameter jpp = new JobPatchParameter();
        jpp.withConstraints(jobConstraints);        
        
        client.jobOperations().patchJob(poolJobId, jpp);
        Logger.log(listener, "Set poolJob %s new timeout to %d minutes.", poolJobId, newJobTimeoutInMin);
    }
    
    /**
     * Wait for all tasks completed
     * @param poolId Pool Id
     * @param jobCompletedTimeoutInMin Timeout in minutes of the job
     * @throws BatchErrorException
     * @throws IOException
     * @throws InterruptedException
     * @throws TimeoutException 
     */
    private void waitForAllTasksCompleted(
            String poolId,
            int jobCompletedTimeoutInMin) throws BatchErrorException, IOException, InterruptedException, TimeoutException {        
        if (!Utils.dirExists(taskLogDirPath)) {
            Files.createDirectory(Paths.get(taskLogDirPath));
        }
        
        // Wait for pool is ready
        waitForPoolReady(poolId);
        
        // Check if allocated target VM count and resize if necessary
        checkAllocatedVMsAndAttemptResize(poolId);
        
        // Wait for at least one VM are ready
        waitForAtLeastOneVmReady(poolId);
        
        // Wait for jobPreparationTask completed
        final int preparationTaskTimeoutInMin = 15;
        waitForAtLeastOneJobPreparationTaskCompleted(poolId, preparationTaskTimeoutInMin);
        
        // Wait for job completed
        waitForAllTasksCompleted(jobCompletedTimeoutInMin);
    }
    
    /**
     * Clean up Batch account for given job, basically delete the job.
     * @param jobId Job Id
     * @throws BatchErrorException
     * @throws IOException 
     */
    private void deleteJob(String jobId) 
            throws BatchErrorException, IOException {        
        try {
            // Try to get the job in case the job is not created yet
            client.jobOperations().getJob(jobId);
            
            Logger.log(listener, "Cleanup, will delete job %s.", jobId);
            client.jobOperations().deleteJob(jobId);
            Logger.log(listener, "Job %s is deleted.", jobId);
        } catch (BatchErrorException e) {
            if (BatchErrorCodeStrings.JobNotFound.equals(e.getBody().code())) {                
                return;
            }
            throw e;
        }
    }
    
    private String getAutoPoolIdPrefix() {
        return autoPoolIdPrefix;
    }
    
    private void checkAllocatedVMsAndAttemptResize(String poolId) throws BatchErrorException, IOException, InterruptedException, TimeoutException {
        CloudPool pool = client.poolOperations().getPool(poolId);                
        if (pool.currentDedicated() < pool.targetDedicated()) {
            if (PoolResizeErrorCodes.AccountCoreQuotaReached.equals(pool.resizeError().code())) {
                if (pool.currentDedicated() == 0) {
                    throw new IllegalStateException("Failed to allocate any VM. You've reached your Batch account quota limit (default is 20 cores if you haven't " + 
                            "requested increase), and you may want to request quota increase if needed. For more information on Batch quotas and how to increase them, " +
                            "see https://azure.microsoft.com/documentation/articles/batch-quota-limit/");
                } else {                    
                    String warning = String.format("Warning: allocated %d VMs < target %d VMs. " + 
                            "Tests might running slower than expected, and you may cancel the test run at any time. " +
                            "You've reached your Batch account quota limit (default is 20 cores if you haven't " + 
                            "requested increase), please login to Azure management portal to look up your quota; " + 
                            "and you may want to request quota increase if needed.", 
                            pool.currentDedicated(), pool.targetDedicated());
                    Logger.log(listener, warning);
                    return;
                }
            }
            
            // If we didn't get expected VMs, try to resize.
            Logger.log(listener, "Allocated VMs are less than target, try to resize...");
            client.poolOperations().resizePool(poolId, pool.targetDedicated());
            
            // Wait for pool is ready
            waitForPoolReady(poolId);
            
            if (pool.currentDedicated() == 0) {
                throw new IllegalStateException(String.format("Failed to allocate any VM (error code: %s, message %s), please double check your Azure Batch account.", 
                        pool.resizeError().code(), pool.resizeError().message()));
            } else if (pool.currentDedicated() < pool.targetDedicated()) {
                String warning = String.format("Warning: allocated %d VMs < target %d VMs. " + 
                        "Tests might running slower than expected, and you may cancel the test run at any time. " +
                        "This might be transient issue, and you may try again later. ",
                        pool.currentDedicated(), pool.targetDedicated());
                Logger.log(listener, warning);
            }
        }
    }
    
    private void waitForPoolReady(String poolId) 
            throws BatchErrorException, IOException, InterruptedException, TimeoutException {
        long startTime = System.currentTimeMillis();
        long elapsedTime = 0L;
        boolean poolSteady = false;
        
        // Wait max 15 minutes for pool to reach steady
        final long maxPoolSteadyWaitTimeInMinutes = 15;
        Logger.log(listener, String.format("Waiting for pool %s steady...", poolId));
        while (elapsedTime < maxPoolSteadyWaitTimeInMinutes * 60 * 1000) {
            CloudPool pool = client.poolOperations().getPool(poolId);
            if (pool.allocationState() == AllocationState.STEADY) {
                poolSteady = true;
                break;
            }
            
            Thread.sleep(15 * 1000);
            elapsedTime = System.currentTimeMillis() - startTime;
        }
        
        if (!poolSteady) {
            throw new TimeoutException(String.format("Pool %s is not steady after %d minutes.", poolId, maxPoolSteadyWaitTimeInMinutes));
        } else {
            Logger.log(listener, "Pool %s is steady.", poolId);
        }
    }
    
    private void waitForAtLeastOneVmReady(String poolId) 
            throws BatchErrorException, IOException, InterruptedException, TimeoutException {
        long startTime = System.currentTimeMillis();
        long elapsedTime = 0L;
        boolean vmReady = false;
        
        // Wait max 20 minutes for VM to start up
        final long maxVmIdleWaitTimeInMinutes = 20;
        Logger.log(listener, String.format("Waiting for pool %s at least one VM ready...", poolId));
        while (elapsedTime < maxVmIdleWaitTimeInMinutes * 60 * 1000) {

            List<ComputeNode> nodeCollection = client.computeNodeOperations().listComputeNodes(poolId, 
                    new DetailLevel.Builder().withSelectClause("state").withFilterClause("state eq 'idle' or state eq 'running'").build());
            for (ComputeNode node : nodeCollection) {
                ComputeNodeState nodeState = node.state();
                if (nodeState == ComputeNodeState.IDLE || nodeState == ComputeNodeState.RUNNING) {
                    vmReady = true;
                    break;
                }
            }

            if (vmReady) {
                break;
            }

            long nextWaitTime = 15 * 1000 - (System.currentTimeMillis() - startTime - elapsedTime);
            if (nextWaitTime > 0) {
                Thread.sleep(nextWaitTime);
            }
            
            elapsedTime = System.currentTimeMillis() - startTime;
        }
        
        if (!vmReady) {
            throw new TimeoutException(String.format("Pool %s no VM is ready after %d minutes.", poolId, maxVmIdleWaitTimeInMinutes));
        } else {
            Logger.log(listener, "Pool %s at least one VM is ready.", poolId);
        }
    }
    
    private void retrieveTaskLogs(CloudTask task) throws InterruptedException, BatchErrorException, IOException, TimeoutException {
        TaskExecutionInformation execInfo = task.executionInfo();
        
        if (task.state() == TaskState.ACTIVE || task.state() == TaskState.PREPARING || execInfo == null) {            
            // Task is not started running yet, don't try to retrieve logs.
            return;
        } else if (task.state() == TaskState.COMPLETED) {
            if (execInfo.schedulingError() != null) {
                TaskSchedulingError schedulingError = execInfo.schedulingError();
                Logger.log(listener, "Task %s(id: %s) is failed to schedule with SchedulingError Category (%s), Code (%s), Message (%s).", 
                        task.displayName(), task.id(), schedulingError.category(), schedulingError.code(), schedulingError.message());
                return;
            } else if (0 == execInfo.exitCode()) {            
                // Do not retrieve results for succeeded tasks.
                return;
            }                
        }

        // Task is timeout and killed by Batch service. -1073741510 is the error code returned by VM if task timeout on Windows.
        final int exitCodeKilled = -1073741510;
        
        if (task.state() == TaskState.RUNNING) {
            Logger.log(listener, "Task %s(id: %s) is still running, will retrieve its stdout and stderr files...", task.displayName(), task.id());
        } else if (exitCodeKilled == execInfo.exitCode()) {            
            Logger.log(listener, "Task %s(id: %s) is killed, will retrieve its stdout and stderr files... It might be due to task timeout, consider to increase the timeout for this task.", task.displayName(), task.id(), execInfo.exitCode());
        } else {
            Logger.log(listener, "Task %s(id: %s) failed with exit code %d, will retrieve its stdout and stderr files...", task.displayName(), task.id(), execInfo.exitCode());
        }

        String filePrefix = taskLogDirPath + File.separator + task.id();      

        getFileFromTaskAndSave(task, stdoutFileName, filePrefix + "_" + stdoutFileName);

        String localFileName = filePrefix + "_" + stderrFileName;
        getFileFromTaskAndSave(task, stderrFileName, localFileName);

        File errFile = new File(localFileName);
        if (errFile.exists() && errFile.length() > 0) {
            Logger.log(listener, "StdErr output in %s:", localFileName);
            Logger.log(listener, FileUtils.readFileToString(errFile));
            Logger.log(listener, "End of StdErr output in %s.", localFileName);
        }

        Logger.log(listener, "Retrieved stdout and stderr files for failed task %s(id: %s).", task.displayName(), task.id());                     
    }
    
    private void waitForAllTasksCompleted(int waitTimeoutInMin) 
            throws InterruptedException, BatchErrorException, IOException, TimeoutException {        
        long startTime = System.currentTimeMillis();
        long elapsedTime = 0;
        boolean completed = false;
        String poolId = client.jobOperations().getJob(jobId).executionInfo().poolId();
        int lastTotalNodeCount = 0;
        int lastActiveNodeCount = 0;
        boolean allJobPrepTasksDone = false;
        
        // wait for all tasks to complete
        while (elapsedTime < (long)waitTimeoutInMin * 60 * 1000) {
            // Check all JobPrep tasks and retrieve logs
            if (!allJobPrepTasksDone) {
                allJobPrepTasksDone = checkAndRetrieveAllJobPrepTasksOutput(poolId);
            }
            
            List<CloudTask> taskCollection = client.taskOperations().listTasks(jobId, 
                    new DetailLevel.Builder().withSelectClause("id, state").build());
            
            // Try to shrink the pool if needed
            tryToShrinkPool(poolId, waitTimeoutInMin, taskCollection);

            int activeTasksCount = 0;
            int preparingTasksCount = 0;
            int runningTasksCount = 0;
            int completedTasksCount = 0;
            
            for (CloudTask task : taskCollection) {
                switch (task.state()) {
                    case ACTIVE:
                        activeTasksCount++;
                        break;
                    case PREPARING:
                        preparingTasksCount++;
                        break;
                    case RUNNING:
                        runningTasksCount++;
                        break;
                    case COMPLETED:
                        completedTasksCount++;
                        if (!retrievedTasks.contains(task.id())) {
                            // If task completed, retrieve task log
                            retrieveTaskLogs(task);
                            
                            // Mark task as log retrieved
                            retrievedTasks.add(task.id());
                        }   break;
                    default:
                        break;
                }
            }
            
            if (completedTasksCount == taskCollection.size()) {
                completed = true;
                break;
            }
            
            int currentActiveNodeCount = 0;
            List<ComputeNode> nodes = client.computeNodeOperations().listComputeNodes(poolId, 
                    new DetailLevel.Builder().withSelectClause("state").build());
            if (nodes != null) {
                for (ComputeNode node : nodes) {
                    if (node.state() != ComputeNodeState.LEAVINGPOOL) {
                        currentActiveNodeCount++;
                    }
                }
            }            

            if (nodes != null && nodes.size() != lastTotalNodeCount ||
                    currentActiveNodeCount != lastActiveNodeCount) {
                Logger.log(listener, "Waiting for all tasks to complete, %d/%d active VM(s) running tasks. Task statistics: %d active, %d preparing, %d running, %d completed.", 
                        currentActiveNodeCount, nodes.size(), activeTasksCount, preparingTasksCount, runningTasksCount, completedTasksCount);
            }
            if (nodes != null) {
                lastTotalNodeCount = nodes.size();
            }
            lastActiveNodeCount = currentActiveNodeCount;
            
            long nextWaitTime = 15 * 1000 - (System.currentTimeMillis() - startTime - elapsedTime);
            if (nextWaitTime > 0) {
                Thread.sleep(nextWaitTime);
            }
            
            elapsedTime = System.currentTimeMillis() - startTime;
        }
        
        if (!completed) {
            throw new TimeoutException(String.format("Job %s not all tasks are completed after %d minutes.", jobId, waitTimeoutInMin));
        } else {
            Logger.log(listener, "Job %s all tasks are completed.", jobId);
        }
    }
    
    private void tryToShrinkPool(String poolId, int waitTimeoutInMin, List<CloudTask> taskCollection) throws BatchErrorException, IOException {        
        // Try to down scale pool size if we don't have active tasks but have idle VMs.
        boolean toResize = true;
        for (CloudTask task : taskCollection) {
            if (task.state() == TaskState.ACTIVE) {
                // If there's still some task in active, we don't need to resize.
                toResize = false;
                break;
            }
        }

        if (toResize) {                
            CloudPool pool = client.poolOperations().getPool(poolId);

            if (pool.allocationState() == AllocationState.STEADY) {
                Logger.log(listener, "Shrinking pool since we dont't have active tasks but have idle VMs...");

                client.poolOperations().resizePool(poolId, 0, Period.minutes(waitTimeoutInMin), ComputeNodeDeallocationOption.TASKCOMPLETION);
            }
        }
    }
    
    private void waitForAtLeastOneJobPreparationTaskCompleted(String poolId, int waitTimeoutInMin) 
            throws InterruptedException, BatchErrorException, IOException, TimeoutException {        
        long startTime = System.currentTimeMillis();
        long elapsedTime = 0;
        boolean completed = false;
        int lastPreparingVmCount = 0;
        int lastTotalVmCount = 0;
        
        // wait for at least one JobPreparationTask to complete
        while (elapsedTime < (long)waitTimeoutInMin * 60 * 1000) {
            List<JobPreparationAndReleaseTaskExecutionInformation> statusList = client.jobOperations().listPreparationAndReleaseTaskStatus(jobId);
            if (statusList.size() > 0) {
                for (JobPreparationAndReleaseTaskExecutionInformation info : statusList) {
                    JobPreparationTaskExecutionInformation taskInfo = info.jobPreparationTaskExecutionInfo();
                    if (taskInfo != null && taskInfo.state() == JobPreparationTaskState.COMPLETED) {
                        if (taskInfo.exitCode() != 0) {
                            Logger.log(listener, "Warning: JobPreparation task failed (ExitCode %d is non-zero) on VM %s.",
                                    taskInfo.exitCode(), info.nodeId());                      
                            Logger.log(listener, "Warning: One or more JobPreparation tasks failed on VM(s), no test tasks " +
                                    "will be scheduled to such VMs. Please check your VM setup script for that VM. " +
                                    "You may find more information from JobPreparation tasks' log files stdout.txt " +
                                    "and stderr.txt.");
                        }
                        
                        completed = true;
                        break;
                    }                    
                }
            }
            
            if (completed) {
                break;
            }
            
            int totalVmCount = 0;
            int preparingVmCount = 0;
            List<ComputeNode> nodes = client.computeNodeOperations().listComputeNodes(poolId,
                    new DetailLevel.Builder().withSelectClause("state").withFilterClause("state eq 'idle'").build());
            if (nodes != null) {
                totalVmCount = nodes.size();
                for (ComputeNode node : nodes) {
                    if (node.state() == ComputeNodeState.IDLE) {
                        preparingVmCount++;
                    }
                }
            }

            if (lastTotalVmCount != totalVmCount || lastPreparingVmCount != preparingVmCount) {
                Logger.log(listener, "Waiting for at least one Azure Batch JobPreparation task to complete, %d/%d VM(s) are preparing...", 
                        preparingVmCount, totalVmCount);
                
                lastTotalVmCount = totalVmCount;
                lastPreparingVmCount = preparingVmCount;
            }
            
            long nextWaitTime = 15 * 1000 - (System.currentTimeMillis() - startTime - elapsedTime);
            if (nextWaitTime > 0) {
                Thread.sleep(nextWaitTime);
            }
            
            elapsedTime = System.currentTimeMillis() - startTime;
        }
        
        if (!completed) {
            throw new TimeoutException(String.format("No JobPreparationTask of job %s is completed after %d minutes.", jobId, waitTimeoutInMin));
        } else {
            Logger.log(listener, "At least one JobPreparationTask of job %s is completed, and tasks will be running.", jobId);
        }
    }
    
    private boolean checkAndRetrieveAllJobPrepTasksOutput(String poolId) throws BatchErrorException, IOException {
        int retrievedJobPrepTaskCount = retrieveAllJobPrepTasksOutput();
        
        CloudPool pool = client.poolOperations().getPool(poolId);                
        return retrievedJobPrepTaskCount >= pool.currentDedicated();
    }
    
    private int retrieveAllJobPrepTasksOutput() throws BatchErrorException, IOException {
        List<JobPreparationAndReleaseTaskExecutionInformation> execInfoList = client.jobOperations().listPreparationAndReleaseTaskStatus(jobId);
        
        int retrievedJobPrepTaskCount = 0;
        int failedJobPrepTaskCount = 0;
        for (JobPreparationAndReleaseTaskExecutionInformation info : execInfoList) {
            JobPreparationTaskExecutionInformation taskInfo = info.jobPreparationTaskExecutionInfo();            
            if (taskInfo != null && taskInfo.state() == JobPreparationTaskState.COMPLETED) {
                String taskKey = info.poolId() + info.nodeId() + "JobPrepTask";
                if (!retrievedTasks.contains(taskKey)) {
                    if (taskInfo.exitCode() != 0) {
                        // We only retrieve JobPrepTask output for failed tasks.
                        failedJobPrepTaskCount++;
                        retrieveJobPrepTaskOutput(info.poolId(), info.nodeId());
                    }
                    
                    retrievedTasks.add(taskKey);
                }                
                retrievedJobPrepTaskCount++;
            }
        }
        if (failedJobPrepTaskCount > 0){
            Logger.log(listener, "Retrieved %d failed JobPrep task logs.", failedJobPrepTaskCount);
        }
        
        return retrievedJobPrepTaskCount;
    }
    
    private void retrieveJobPrepTaskOutput(String poolId, String nodeId) throws BatchErrorException, IOException { 
        ComputeNode node = client.computeNodeOperations().getComputeNode(poolId, nodeId);
        
        final String filePrefix = taskLogDirPath + File.separator + poolId + "_" + node.id();
        String taskType = "jobpreparation";
        String taskFolder = String.format("workitems/%s/job-1/%s", jobId, taskType);

        String fileNameOnNode = String.format("%s/%s", taskFolder, stdoutFileName);
        String localFileName = String.format("%s_%s_%s", filePrefix, taskType, stdoutFileName);
        getFileFromComputeNodeAndSave(poolId, node, fileNameOnNode, localFileName);

        fileNameOnNode = String.format("%s/%s", taskFolder, stderrFileName);
        localFileName = String.format("%s_%s_%s", filePrefix, taskType, stderrFileName);
        getFileFromComputeNodeAndSave(poolId, node, fileNameOnNode, localFileName);

        File errFile = new File(localFileName);
        if (errFile.exists() && errFile.length() > 0) {
            Logger.log(listener, "StdErr output in %s:", localFileName);
            Logger.log(listener, FileUtils.readFileToString(errFile));
            Logger.log(listener, "End of StdErr output in %s.", localFileName);
        }
    } 
    
    private void getFileFromComputeNodeAndSave(String poolId, ComputeNode node,
            String fileNameOnNode, String localFileName) throws BatchErrorException, IOException {
        try {
            InputStream stream = client.fileOperations().getFileFromComputeNode(poolId, node.id(), fileNameOnNode);
            copyInputStreamToFile(stream,  new File(localFileName));
        } catch(BatchErrorException | IOException e) {
            if (e instanceof InterruptedIOException) {
                Logger.log(listener, "Retrieving logs from VMs is cancelled.");
                throw e;
            }            
            Logger.log(listener, "Failed to get file %s on VM %s (state: %s) in pool %s and save to %s, with error: %s.", 
                    fileNameOnNode, node.id(), node.state(), poolId, localFileName, e.getMessage()); 
        }
    }
    
    private void retrieveJobTestTaskResults()throws BatchErrorException, IOException, InterruptedException, TimeoutException {
                
        Logger.log(listener, "Retrieving test results for job %s...", jobId);
        List<CloudTask> tasks = client.taskOperations().listTasks(jobId);
        Logger.log(listener, "Total %d tasks...", tasks.size());
        for (CloudTask task : tasks) {
            if (retrievedTasks.contains(task.id())) {
                // already retrieved
                continue;
            }
            
            retrieveTaskLogs(task);
            retrievedTasks.add(task.id());
        }
        Logger.log(listener, "Retrieved test results for job: " + jobId);
    }
    
    private void getFileFromTaskAndSave(CloudTask task, String fileNameOnNode, String localFileName) throws BatchErrorException, IOException {        
        try {
            InputStream stream = client.fileOperations().getFileFromTask(jobId, task.id(), fileNameOnNode);
            copyInputStreamToFile(stream,  new File(localFileName));
        } catch(BatchErrorException | IOException e) {
            if (e instanceof InterruptedIOException) {
                Logger.log(listener, "Retrieving logs for tasks is cancelled.");
                throw e;
            }
            Logger.log(listener, "Failed to get file %s for task %s(id: %s) (state: %s) of job %s and save to %s, with error: %s.", 
                    fileNameOnNode, task.displayName(), task.id(), task.state(), jobId, localFileName, e.getMessage());   
        }
    }
    
    private void copyInputStreamToFile(InputStream in, File file) throws FileNotFoundException, IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        in.close();
    }
}
