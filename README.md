[![Build Status](https://jenkins.ci.cloudbees.com/job/plugins/job/azure-batch-parallel-plugin/badge/icon)](https://jenkins.ci.cloudbees.com/job/plugins/job/azure-batch-parallel-plugin/)

# Azure Batch Parallel Test Execution Jenkins Plugin

This Jenkins post-build plugin allows you to execute tests in parallel with the Microsoft Azure Batch service, and can reduce the duration of your test runs, and therefore, potentially the cost. In this release, we support running tests with the Windows Server operating system only. Linux support will be available in a future release.

Azure Batch enables you to run parallel applications efficiently in the cloud. It's a platform service that schedules tasks to run on a managed collection of virtual machines.
You can find an introduction to the Batch service in the [Basics of Azure Batch](https://azure.microsoft.com/documentation/articles/batch-technical-overview/).

# Installation

This plugin is available in the Jenkins plugin manager. To install the plugin in Jenkins, go to:

**Manage Jenkins** > **Manage Plugins** > **Available** > **Azure Batch Parallel Test Execution Plugin** > **Install**

Restart Jenkins to complete the installation.

# Configuration

To use this plugin, you need to configure settings in **Global** configuration and **Project** configuration as specified below.

## Global configuration

At least one Azure Batch and one Azure Storage account must be configured in the global configuration before the plugin can be used. To add these accounts, navigate to the following and fill in the required information:

**Manage Jenkins** > **Configure System** > **Parallel Test Execution with Microsoft Azure Batch**

You can configure multiple Azure Batch and Azure Storage accounts in Global configuration, but you will need to pick one Azure Batch and one Azure Storage account in Project configuration for test execution with the plugin.

You can create a Batch account by following the steps in
[Create and manage an Azure Batch account in the Azure portal](https://azure.microsoft.com/documentation/articles/batch-account-create-portal/). You can create a Storage account by following the steps in
[About Azure storage accounts](https://azure.microsoft.com/documentation/articles/storage-create-storage-account).

## Project configuration

Add a post-build action "Execute tests in parallel with Microsoft Azure Batch" to your project, and fill out the required information in the
"Execute tests in parallel with Microsoft Azure Batch" section of project configuration:

1. **Batch Account**: Choose one Azure Batch account from the accounts configured in Global configuration
2. **Storage Account**: Choose one Azure Storage account from the accounts configured in Global configuration
3. **Parallel Test Project Config File**: Specify the absolute path or relative path to the Jenkins WORKSPACE on the Jenkins server. The Jenkins server will load VM, resource, and test configurations from this config file. You can review the [project config file schema](https://github.com/jenkinsci/azure-batch-parallel-plugin/blob/master/src/configs/schemas/ProjectConfigSchema.json) and see a [sample project config](https://github.com/azurebatch/azure-mobile-apps-net-server/blob/master/batchtest/SampleProjectSettings.json) for the `azure-mobile-apps-net-server` tests.
4. **Parallel Test Split Config File**: Specify the absolute path or relative path to the Jenkins WORKSPACE on the Jenkins server. The Jenkins server will load task split configurations from this config file. You can review the [task split config file schema](https://github.com/jenkinsci/azure-batch-parallel-plugin/blob/master/src/configs/schemas/TestSplitConfigSchema.json) and see a [sample task split config](https://github.com/azurebatch/azure-mobile-apps-net-server/blob/master/batchtest/SampleSplitterConfig.json) for the `azure-mobile-apps-net-server` tests.
5. **Enable VM Utilization Profiler**: Check this option if you want to enable the VM Utilization Profiler to help fine tune the VM and test split configuration.

# How this plugin works

This plugin utilizes the [Microsoft Azure Batch](https://azure.microsoft.com/services/batch/) service to execute tests in parallel. You specify the number of VMs and the spec of the VMs in the Parallel Test Project Config File, and the plugin creates a pool of such VMs for running tests. The VMs and the pool will be automatically deleted when the job finishes, so you don't need to keep VMs running when there are no tests running.

You specify how you want to split the tests into groups in the Parallel Test Split Config File, and each group will be scheduled to a VM automatically. Each group of tests defined in your split config file will be a task in a job in the Azure Batch service. When each test
finishes, its logs will be uploaded the the Azure Storage account you specify in the Project config file, and later will be downloaded to the Jenkins server for analysis.

# Understand the outputs of test run

This plugin has the following channels of outputs to help you understand the result and run information of your tests, and diagnose potential issues during the test run:

- **Console Output**: You can view console output from your **project** > **build #** > **Console Output**. You will find all detailed status and debug
logs of the plugin for the selected test run.
- **Stdout and Stderr outputs of failed tasks**: You can find Stdout and Stderr output files in the `JenkinsWorkspace\azurebatchtemp\jenkins-task-DateTime-GUID-output` folder on the Jenkins server. These log files will help you diagnose failures that occurred on VMs. The Stderr output will be shown in Console Output as well, for your convenience.
- **Test results**: Test results will be downloaded to the folder specified in Parallel Test Split Config File on the Jenkins server. Your test reporting modules need to obtain results from there.
- **VM Utilization Profiler logs**: If you enable VM Utilization Profiler, you will see the VM Utilization report in Console Output, and detail reports in the `JenkinsWorkspace\azurebatchtemp\` folder as the `vmUtilizaton.csv` file. You can find more information about VM Utilization Profiler below.
- **Scripts running on VMs**: You will find all scripts running on VMs under `JenkinsWorkspace\azurebatchtemp\scripts` folder, which may help you
diagnose potential issues.

# Run sample test project: azure-mobile-apps-net-server

You may use https://github.com/azurebatch/azure-mobile-apps-net-server tests to try out this plugin.

### Pre-requisites

- Azure Batch account
- Azure Storage account
- Install Jenkins server
- Install [git Jenkins plugin](http://wiki.jenkins-ci.org/display/JENKINS/Git+Plugin)
- Install xUnit result parser Jenkins plugin, [xUnit plugin](http://wiki.jenkins-ci.org/display/JENKINS/xUnit+Plugin) in below sample.
- Install Azure Batch Parallel Test Execution Plugin (this plugin).

### Configure Azure Batch Parallel Test Execution Jenkins plugin

Go to **Manage Jenkins** > **Configure System** > **Parallel Test Execution with Microsoft Azure Batch**, and fill pre-created Azure Batch and Storage account information. And you may put multiple Batch and Storage account there, and different Jenkins project may use different Batch or Storage account.

### Create and configure test project

1. Create a **Freestyle project**, say "Job1"
2. Go to **Job1** > **Configure**, and configure following:
    1. In **Source Code Management** choose "Git". Use `https://github.com/azurebatch/azure-mobile-apps-net-server.git` as the Repository URL.
    2. Add a **Post-build action** "Execute tests in parallel with Microsoft Azure Batch":

        - **Batch Account**: Choose the account you configured in Global Configuration
        - **Storage Account**: Choose the account you configured in Global Configuration
        - **Parallel Test Project Config File**: Use `batchtest/SampleProjectSettings.json`
        - **Parallel Test Split Config File**: Use `batchtest/SampleSplitterConfig.json`
        - **Enable VM Utilization Profiler**: Check this option if you want to enable the VM Utilization Profiler to help fine-tune the VM and test
split configuration.
    3. Add a **Post-build action** "Publish xUnit test result report" for the test results report:

        - Add **xUnit.Net-v2 (default)**, and configure **Pattern** as `azurebatchtemp/**/*result.xml`
3. Save the job

### Build test project

Build the job "Job1", and it should result in a successful build and you should see all tests pass.

# Tune VM and test split configurations

To achieve a better balance of test run time and cost, you might need to tune the VM and test split configurations; the below information will be helpful for this process.

The test run can be divided into the following stages:

1. **Create pool of VMs and start VMs**: This plugin will create a pool of VMs of the spec defined in the "vmConfigs" section of Parallel Test Project Config File. You aren't charged for a VM until it has been started.
2. **Download and process resources**: The Batch service will download the resources you specify in the "resources" section of Parallel Test Project Config File to each VM, unzip if necessary, and copy them to the `%AZ_BATCH_NODE_SHARED_DIR%\\%AZ_BATCH_JOB_ID%` folder on each VM for Windows. (NOTE: You can find more information about this folder and VM environment settings in the [Batch feature overview for developers](https://azure.microsoft.com/documentation/articles/batch-api-basics/).)
3. **Run VM setup task**: The Batch service will run the VM setup command line specified in the "vmConfigs" section of Parallel Test Project Config File.
4. **Run tests**: The Batch service will schedule and run the tests specified in Parallel Test Split Config File. During the test run, the pool size will shrink if all scheduled tasks have completed, and idle VMs will be deleted.
5. **Delete job and pool**: When all tasks are finished, the job and pool will be deleted.
6. **Retrieve test results**: The plugin will download tests results from the Azure Storage account to the Jenkins server.

## Run duration factors

Several factors can impact the total test run duration, and therefore the **total core hours** you use in the Batch service--this is the *cost you pay for computation*. Please note that the below list is not exhaustive, and there may be additional factors that can impact run duration.

- **VM spec**: According to the nature of your tests, you should choose an appropriate spec for your VMs to provide the best balance of test run time and cost. VMs that are underpowered for your tests will incur more core hours because the tests might run long. VMs that are overpowered for your tests may be under-utilitized, so you might pay for more core hours than your tests use.
- **Resource size**: Resources will be uploaded (if not already in Azure Storage) and then downloaded to all VMs in the pool, and larger-sized resources might result in longer downloading and processing time.
- **Number of VMs**: More VMs will result in greater parallel distribution, however, it might also add time (and therefore cost) to VM setup and deletion.
- **Test split**: An even test split will help reduce the total test run duration and cost. However, a test group that is too small can be inefficient since task scheduling and test log uploading might be a relatively large overhead compared to the tests themselves.

In the end, you should tune VM and test split configurations to find the right balance of time and cost for your own tests. Based on the above discussion, here is a summary of our recommendations:

- Choose a VM spec that fits your tests
- Minimize resource size
- Minimize number of VMs
- Minimize groups of tests
- Evenly split tests into groups in terms of test run time, and avoid tests with a long tail run time
- Use the VM Utilization Profiler to collect VM utilization data to help tune your test runs

## Cost factors

Besides above **computation cost** (or **total core hours**), there may be additional storage and data transfer cost incurred by the test run. Below is a summary of our recommendations to minimize these costs:

- Have Batch account and Storage account in same region
- Minimize resource size
- Minimize test results size

## VM Utilization Profiler

When the "Enable VM Utilization Profiler" option is checked in project settings, it will collect VM utilization metrics every minute. A report similar to the below will be shown in Console Output when your tests finish. **Please notice that numbers in report are estimated, in accuracy of minutes, may be different with the final service billing from Azure; the report is provided for helping tune VM and test split configurations only, not for billing purpose.**

``` Report
2016/07/15 13:26:56 VM utilization report of this run: (below numbers are estimated, in accuracy of minutes, may be different with the final service billing from Azure)
2016/07/15 13:26:56 This report is provided for helping tune VM and test split configurations only, not for billing purpose.
2016/07/15 13:26:56 Total estimated billed VM usage: 6.45 VM Count*Hours.
2016/07/15 13:26:56 Total 0.07 VM Count*Hours in Starting state, or 1.0% of total estimated billed VM Count*Hours.
2016/07/15 13:26:56 Total 0.03 VM Count*Hours in Preparing state, or 0.5% of total estimated billed VM Count*Hours.
2016/07/15 13:26:56 Total 6.35 VM Count*Hours in Running state, or 98.4% of total estimated billed VM Count*Hours.
2016/07/15 13:26:56 Total 0.00 VM Count*Hours in Deleting state, or 0.0% of total estimated billed VM Count*Hours.
```

This report outlines the total estimated billed VM usage. Note that you may need to convert `VM Count*Hours` to `core*Hours` according to the VM spec you're using, when you compare this number to a different VM spec. In the above example, the `small` VM size is used and `small` VM has 1 core, so the estimated `core*Hours` will be the same as `VM Count*Hours`. If you have another test run using `Standard_D4` VM which has 8 cores, you need to multiply `VM Count*Hours` by 8 to get `core*Hours`. Then you may compare total `core*Hours` of the run using `small` VMs and total `core*Hours` of the run using `Standard_D4` VMs, rather than directly comparing `VM Count*Hours`. You can find more information about VM size and number of cores in [Sizes for Cloud Services](https://azure.microsoft.com/documentation/articles/cloud-services-sizes-specs/).

The report also shows the percentage of VM utilization in different states:

- **Starting state**: Batch/Azure is creating and starting the VM
- **Preparing state**: VM is downloading and processing resources, and running your VM setup task
- **Running state**: VM is running your tests
- **Deleting state**: Idle VM is being deleted by the Batch service

Ideally, you want to *maximize* the % Running state, and *minimize* % Starting, % Preparing and % Deleting states.

You can get a more detailed report for VMs in different states every minute; the report file is in the Jenkins WORKSPACE folder `/azurebatchtemp/vmUtilizaton.csv`. Below
is an example:

|      timestamp      | billedVmCount | unbilledVmCount | startingVmCount | rebootingVmCount | preparingVmCount | runningVmCount | leavingVmCount | otherStateVmCount |
|:-------------------:|:-------------:|:---------------:|:---------------:|:----------------:|:----------------:|:--------------:|:--------------:|:-----------------:|
| 2016/07/13 17:48:02 |             0 |               2 |               2 |                0 |                0 |              0 |              0 |                 0 |
| 2016/07/13 17:49:02 |             0 |               2 |               2 |                0 |                0 |              0 |              0 |                 0 |
| 2016/07/13 17:50:02 |             0 |               2 |               2 |                0 |                0 |              0 |              0 |                 0 |
| 2016/07/13 17:51:02 |             0 |               2 |               2 |                0 |                0 |              0 |              0 |                 0 |
| 2016/07/13 17:52:02 |             0 |               2 |               2 |                0 |                0 |              0 |              0 |                 0 |
| 2016/07/13 17:53:02 |             1 |               1 |               1 |                0 |                1 |              0 |              0 |                 0 |
| 2016/07/13 17:54:02 |             2 |               0 |               0 |                0 |                1 |              1 |              0 |                 0 |
| 2016/07/13 17:55:02 |             2 |               0 |               0 |                0 |                1 |              1 |              0 |                 0 |
| 2016/07/13 17:56:02 |             2 |               0 |               0 |                0 |                0 |              1 |              1 |                 0 |
| 2016/07/13 17:57:02 |             2 |               0 |               0 |                0 |                0 |              1 |              1 |                 0 |

As you can see from above example, at timestamp `2016/07/13 17:53:02`, 1 VM is *preparing*. At timestamp `2016/07/13 17:56:02` a VM is *leaving*.

# Help
If you encounter any bugs with this plugin, please file issues via [Issues](https://github.com/jenkinsci/azure-batch-parallel-plugin/issues).

# Contribute

If you would like to become an active contributor to this project, please follow the instructions in the [Microsoft Azure Projects Contribution Guidelines](http://azure.github.io/guidelines.html).

1. Fork this repository
2. Create your feature branch (`git checkout -b my-new-feature`)
3. Commit your changes (`git commit -am 'Add some feature'`)
4. Push to the branch (`git push origin my-new-feature`)
5. Create new Pull Request

# More Information
* [Azure Batch documentation](https://azure.microsoft.com/documentation/services/batch/)
* If you don't already have one, you can create a [FREE Microsoft Azure account](http://go.microsoft.com/fwlink/?LinkId=330212).

# Release Notes
Release notes and changelog are at [here](https://github.com/jenkinsci/azure-batch-parallel-plugin/wiki).