/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.azurebatch;

import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.BatchClientBehavior;
import com.microsoft.azure.batch.interceptor.BatchClientParallelOptions;
import com.microsoft.azure.batch.protocol.models.BatchErrorException;
import com.microsoft.azure.batch.protocol.models.JobAddParameter;
import com.microsoft.azure.batch.protocol.models.JobConstraints;
import com.microsoft.azure.batch.protocol.models.JobPreparationTask;
import com.microsoft.azure.batch.protocol.models.PoolInformation;
import com.microsoft.azure.batch.protocol.models.ResourceFile;
import com.microsoft.azure.batch.protocol.models.TaskAddParameter;
import com.microsoft.azure.batch.protocol.models.TaskConstraints;
import com.microsoft.azurebatch.jenkins.TestInParallelPostBuild;
import com.microsoft.azurebatch.jenkins.azurestorage.AzureStorageHelper;
import com.microsoft.azurebatch.jenkins.azurestorage.StorageAccountInfo;
import com.microsoft.azurebatch.jenkins.jobsplitter.JobSplitterHelper;
import com.microsoft.azurebatch.jenkins.logger.Logger;
import com.microsoft.azurebatch.jenkins.projectconfig.ProjectConfigHelper;
import com.microsoft.azurebatch.jenkins.resource.LocalResourceEntity;
import com.microsoft.azurebatch.jenkins.resource.ResourceEntity;
import com.microsoft.azurebatch.jenkins.resource.ResourceEntityHelper;
import com.microsoft.azurebatch.jenkins.utils.Utils;
import com.microsoft.azurebatch.jenkins.utils.WorkspaceHelper;
import com.microsoft.windowsazure.storage.StorageException;
import com.microsoft.windowsazure.storage.blob.CloudBlobContainer;
import hudson.model.BuildListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.joda.time.Period;
    
/**
 * Class to create Batch job
 */
class JobGenerator {
    
    private final BuildListener listener;
    private final WorkspaceHelper workspaceHelper;
    private final ProjectConfigHelper projectConfigHelper;
    private final JobSplitterHelper jobSplitterHelper;
    private final List<ResourceEntity> sharedResourceEntityList;
    private final BatchClient client;
    private final String scriptTempFolder;
    private final String jobId;
    private final String poolId;
    
    private final StorageAccountInfo storageAccountInfo;
    private final String containerSasKey;
    
    private static final String staticScriptResourceFolder = "TestInParallelPostBuild/scripts/";
    
    JobGenerator(BuildListener listener, WorkspaceHelper workspaceHelper,
            ProjectConfigHelper projectConfigHelper, JobSplitterHelper jobSplitterHelper, List<ResourceEntity> sharedResourceEntityList,
            BatchClient client, String jobId, String poolId,
            StorageAccountInfo storageAccountInfo, String containerSasKey) throws URISyntaxException, StorageException, InvalidKeyException, IOException {
        this.listener = listener;
        this.workspaceHelper = workspaceHelper;
        this.projectConfigHelper = projectConfigHelper;
        this.jobSplitterHelper = jobSplitterHelper;
        this.sharedResourceEntityList = sharedResourceEntityList;
        this.client = client;
        this.jobId = jobId;
        this.poolId = poolId;
        this.storageAccountInfo = storageAccountInfo;
        this.containerSasKey = containerSasKey;
        
        scriptTempFolder = workspaceHelper.getPathRelativeToTempFolder("scripts");
        if (!Utils.dirExists(scriptTempFolder)) {
            Files.createDirectory(Paths.get(scriptTempFolder));
        }
    }
    
    /**
     * Create Batch job with tasks
     * @param jobTimeoutInMins Timeout in minutes of the job, see more <a href="https://azure.microsoft.com/documentation/articles/batch-api-basics/">here</a>
     * @throws URISyntaxException
     * @throws StorageException
     * @throws InvalidKeyException
     * @throws IOException
     * @throws IllegalArgumentException
     * @throws InterruptedException
     * @throws BatchErrorException
     * @throws TimeoutException 
     */
    void createJobWithTasks(int jobTimeoutInMins) throws URISyntaxException, StorageException, InvalidKeyException, IOException, IllegalArgumentException, InterruptedException, BatchErrorException, TimeoutException {
        List<TaskAddParameter> taskList = null;
        try (InputStream resoureceStream = TestInParallelPostBuild.class.getResourceAsStream(staticScriptResourceFolder + "TaskPostProcess.cmd")) {
            taskList = createTaskList(resoureceStream);
        }
                
        // Create JobPrep task which be running on VM as VM setup task.
        JobPreparationTask jobPreparationTask = createJobPreparationTask();

        // Create the actual job with tasks using pool with poolId.
        createJobWithTasks(jobTimeoutInMins, jobPreparationTask, taskList);
    }
    
    private void createJobWithTasks(
            int jobCompletedTimeoutInMin,            
            JobPreparationTask jobPreparationTask,
            List<TaskAddParameter> taskList) throws BatchErrorException, IOException, InterruptedException, TimeoutException {  
        Logger.log(listener, "Create job %s with pool: %s", jobId, poolId);
            
        PoolInformation poolInformation = new PoolInformation();
        poolInformation.withPoolId(poolId);
        
        JobConstraints jobConstraints = new JobConstraints();
        jobConstraints.withMaxWallClockTime(Period.minutes(jobCompletedTimeoutInMin));  
        Logger.log(listener, "Set job %s constraints with timeout %d minutes.", jobId, jobCompletedTimeoutInMin);    
        
        JobAddParameter param = new JobAddParameter();
        param.withId(jobId)
                .withJobPreparationTask(jobPreparationTask)
                .withPoolInfo(poolInformation)
                .withConstraints(jobConstraints);  

        client.jobOperations().createJob(param);
        
        // We may createTasks with multi-threads if we have many tasks.        
        ArrayList<BatchClientBehavior> listBehaviors = new ArrayList<>();
        listBehaviors.add(new BatchClientParallelOptions(20));
            
        Logger.log(listener, "Job with Id %s is created, now to add tasks...", jobId);        
        client.taskOperations().createTasks(jobId, taskList, listBehaviors);
        Logger.log(listener, "%d tasks are added to job %s.", taskList.size(), jobId);
    }
    
    private static class TaskAddParameterComp implements Comparator<TaskAddParameter>, Serializable { 
        @Override
        public int compare(TaskAddParameter e1, TaskAddParameter e2) {
            if(e1.constraints().maxWallClockTime().getMinutes() < e2.constraints().maxWallClockTime().getMinutes()){
                return 1;
            } else {
                return -1;
            }
        }
    }
    
    private List<TaskAddParameter> createTaskList(InputStream taskPostProcessFileResourceStream) throws URISyntaxException, StorageException, InvalidKeyException, IOException, IllegalArgumentException, InterruptedException {
        // Copy TaskPostProcess.cmd to scriptTempFolder
        String taskPostProcessFileName = "TaskPostProcess.cmd";
        Files.copy(taskPostProcessFileResourceStream, Paths.get(scriptTempFolder + File.separator + taskPostProcessFileName));   
        
        List<TaskDefinition> taskDefinitionList = jobSplitterHelper.createTaskDefinitionList();
        List<TaskAddParameter> taskList = new ArrayList<>();
        Logger.log(listener, "Preparing task resources for %d tasks...", taskDefinitionList.size());
        for(TaskDefinition td : taskDefinitionList)
        {
            taskList.add(getTaskOnePerTaskType(td, taskPostProcessFileName));                
        }
        Logger.log(listener, "Prepared task resources for %d tasks.", taskDefinitionList.size());
        
        LocalResourceEntity scriptsResource = new LocalResourceEntity(scriptTempFolder);
        sharedResourceEntityList.add(scriptsResource);
        
        // Sort taskList to make longer tasks queueing first, so hopefully we may reduce the overall running time.
        // Note: it's not guaranteed tasks queueing first will run first.
        Collections.sort(taskList, new TaskAddParameterComp());
        
        return taskList;
    }

    /**
     * Create job preparation task, see more <a href="https://azure.microsoft.com/documentation/articles/batch-job-prep-release/">here</a>
     * @return job preparation task
     * @throws FileNotFoundException
     * @throws IOException
     * @throws IllegalArgumentException
     * @throws URISyntaxException
     * @throws StorageException
     * @throws InterruptedException
     * @throws InvalidKeyException 
     */
    private JobPreparationTask createJobPreparationTask() throws FileNotFoundException, IOException, IllegalArgumentException, URISyntaxException, StorageException, InterruptedException, InvalidKeyException 
    {                
        // Resource to install python and blobxfer on target VM
        String installPythonFileName = scriptTempFolder + File.separator + "JobPrepareInstallPython.cmd";
        Files.copy(TestInParallelPostBuild.class.getResourceAsStream(staticScriptResourceFolder + "JobPrepareInstallPython.cmd"), Paths.get(installPythonFileName));       
        LocalResourceEntity resourceInstallPythonFile = new LocalResourceEntity(installPythonFileName);
        
        // Resource to unzip using Python script on target VM
        String unzipUsePythonFileName = scriptTempFolder + File.separator + "JobPrepareUnzipUsePython.cmd";
        Files.copy(TestInParallelPostBuild.class.getResourceAsStream(staticScriptResourceFolder + "JobPrepareUnzipUsePython.cmd"), Paths.get(unzipUsePythonFileName));       
        LocalResourceEntity resourceUnzipUsePythonFile = new LocalResourceEntity(unzipUsePythonFileName);
        
        // Resource of Python script to unzip on target VM
        String unzipPythonFileName = scriptTempFolder + File.separator + "Zip.py";
        Files.copy(TestInParallelPostBuild.class.getResourceAsStream(staticScriptResourceFolder + "Zip.py"), Paths.get(unzipPythonFileName));       
        sharedResourceEntityList.add(new LocalResourceEntity(unzipPythonFileName));
                        
        // Generate BatchVMSetup.cmd script content
        List<String> commandLineList = new ArrayList<>();   
        
        // Install Python and some other Python libraries
        commandLineList.add(String.format("cmd /c %s", resourceInstallPythonFile.getResourceName()));     
        
        // Create folder %AZ_BATCH_NODE_SHARED_DIR%\\%AZ_BATCH_JOB_ID% for resources
        commandLineList.add("cmd /c mkdir %AZ_BATCH_NODE_SHARED_DIR%\\%AZ_BATCH_JOB_ID%");

        // Extract resources, which may require Python to unzip.
        for (ResourceEntity resource : sharedResourceEntityList) {
            String commandExtractResource = ResourceEntityHelper.generateExtractResourceEntityCommandOnVM(resource, resourceUnzipUsePythonFile.getResourceName());
            if (commandExtractResource != null) {
                commandLineList.add(ResourceEntityHelper.generateExtractResourceEntityCommandOnVM(resource, resourceUnzipUsePythonFile.getResourceName()));
            }
        }
                
        sharedResourceEntityList.add(resourceInstallPythonFile);
        sharedResourceEntityList.add(resourceUnzipUsePythonFile);
        
        String customerVMSetupCommandLine = this.projectConfigHelper.getVMConfigs().getVmSetupCommandLine();
                        
        customerVMSetupCommandLine = String.format("%s > JobPreparationTask_stdout.txt 2>JobPreparationTask_stderr.txt", customerVMSetupCommandLine);
        commandLineList.add(customerVMSetupCommandLine);

        // Add VMSetup.cmd script as ResourceEntity
        String vmSetupCmdFileName = scriptTempFolder + File.separator + "VMSetup.cmd";        
        try (Writer vmSetupFileWriter = new OutputStreamWriter(new FileOutputStream(new File(vmSetupCmdFileName), false), java.nio.charset.Charset.defaultCharset())) {
            for (String line : commandLineList) {
                vmSetupFileWriter.write(line);
                vmSetupFileWriter.write(System.getProperty("line.separator"));
            }
        }
        
        LocalResourceEntity resourceVMSetupCmdFile = new LocalResourceEntity(vmSetupCmdFileName);
        sharedResourceEntityList.add(resourceVMSetupCmdFile);
               
        // Upload ResourceEntitys
        String containerName = jobId;

        CloudBlobContainer cloudBlobContainer = AzureStorageHelper.getBlobContainer(listener, storageAccountInfo, containerName, false);
        String sasKey = AzureStorageHelper.getContainerSas(listener, cloudBlobContainer, 3 * 60);
        
        String tempZipFolder = workspaceHelper.getPathRelativeToTempFolder("tempZip");
        if (!Utils.dirExists(tempZipFolder)) {
            Files.createDirectory(Paths.get(tempZipFolder));
        }
     
        Logger.log(listener, "Uploading %d resources to Azure storage...", sharedResourceEntityList.size());
        for (ResourceEntity resource : sharedResourceEntityList) {
            if (resource instanceof LocalResourceEntity){
                ResourceEntityHelper.zipAndUploadLocalResourceEntity(listener, cloudBlobContainer, sasKey, (LocalResourceEntity)resource, tempZipFolder, false);
            }
        }
        Logger.log(listener, "Uploaded %d resources to Azure storage.", sharedResourceEntityList.size());
        
        // Create JobPreparationTask        
        JobPreparationTask jobPreparationTask = new JobPreparationTask();  
        jobPreparationTask.withRunElevated(true);
        
        List<ResourceFile> batchResourceFileList = new ArrayList<>();
        
        for (ResourceEntity resource : sharedResourceEntityList) {
            ResourceFile rfe = new ResourceFile();
            rfe.withFilePath(resource.getBlobName())
                    .withBlobSource(resource.getBlobPath());
            batchResourceFileList.add(rfe);            
        }
        
        jobPreparationTask.withResourceFiles(batchResourceFileList);
        
        // Make sure the command line is path on the VM
        jobPreparationTask.withCommandLine(resourceVMSetupCmdFile.getResourceName());

        return jobPreparationTask;
    }
    
    private TaskAddParameter getTaskOnePerTaskType(TaskDefinition taskDefinition, String taskPostProcessFileName) throws IOException, URISyntaxException, StorageException, IllegalArgumentException, InterruptedException, InvalidKeyException {
        List<String> resultFilePatterns = projectConfigHelper.getTestConfigs().getResultFilePatterns();
        String taskId = taskDefinition.getTaskId();

        TaskAddParameter taskAddParam = new TaskAddParameter();
        taskAddParam.withId(taskId)
                .withDisplayName(taskDefinition.getName());

        List<String> commandLineList = new ArrayList<>();

        for (String command : taskDefinition.getCommands()) {
            commandLineList.add(String.format("cmd /c %s >> task_stdout.txt 2>>task_stderr.txt", command));
        }
        
        // Copy results to a temp folder
        List<String> resultFilePatternList = new ArrayList<>(resultFilePatterns);

        // Copy all result files to randomUUID_ResultFolder\taskId\ folder.
        String tempResultFolderName = "tempResults" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        commandLineList.add(String.format("mkdir %s\\%s", tempResultFolderName, taskId));
        for (String resultFilePattern : resultFilePatternList) {
            // Copy results and ignore failures
            commandLineList.add(String.format("copy %s %s\\%s /Y 2>null", resultFilePattern, tempResultFolderName, taskId));
        }
        
        // Call taskPostProcessFile
        // SAS key may contain '%', replace it with '%%' before passing to .cmd script.
        commandLineList.add(String.format("cmd /c %s %s %s %s \"%s\" %s", 
                taskPostProcessFileName, jobId, taskId, storageAccountInfo.getAccountName(), 
                containerSasKey.replace("%", "%%"), tempResultFolderName));

        String workPathCmd = scriptTempFolder + File.separator + taskId + ".cmd";
        Path p = Paths.get(workPathCmd);
        if (Files.exists(p)) {
            Files.delete(p);
        }

        // Generate the task command line at run time based on the dll
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(workPathCmd), false), java.nio.charset.Charset.defaultCharset())) {
            for (String line : commandLineList) {
                writer.write(line);
                writer.write(System.getProperty("line.separator"));
            }
        } catch (IOException e) {
            Logger.log(listener, "Task command file generation failed");
            Logger.log(listener, e.getMessage());
            throw e;
        }
        
        TaskConstraints taskConstraints = new TaskConstraints();
        taskConstraints.withMaxWallClockTime(Period.minutes(taskDefinition.getTimeoutInMins()));        

        taskAddParam.withCommandLine(String.format(
                "cmd /c copy %s\\scripts\\%s.cmd && cmd /c copy %s\\scripts\\%s && %s.cmd",
                "%AZ_BATCH_NODE_SHARED_DIR%\\%AZ_BATCH_JOB_ID%", taskId, "%AZ_BATCH_NODE_SHARED_DIR%\\%AZ_BATCH_JOB_ID%", taskPostProcessFileName, taskId));
        taskAddParam.withConstraints(taskConstraints);

        return taskAddParam;
    }
}
