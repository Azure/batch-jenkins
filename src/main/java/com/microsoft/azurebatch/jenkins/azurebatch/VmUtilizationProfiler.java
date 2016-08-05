/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.azurebatch;

import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.BatchErrorCodeStrings;
import com.microsoft.azure.batch.DetailLevel;
import com.microsoft.azure.batch.protocol.models.BatchErrorDetail;
import com.microsoft.azure.batch.protocol.models.BatchErrorException;
import com.microsoft.azure.batch.protocol.models.ComputeNode;
import com.microsoft.azure.batch.protocol.models.ComputeNodeState;
import com.microsoft.azure.batch.protocol.models.PoolState;
import com.microsoft.azurebatch.jenkins.logger.Logger;
import hudson.model.BuildListener;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * VmUtilizationProfiler class
 */
public class VmUtilizationProfiler extends Thread {
    private final BuildListener listener;
    private final BatchClient client;
    private final String poolId;
    private final String vmUtilizatonLogFilePath;
    
    /**
     * VmUtilizationProfiler constructor
     * @param listener BuildListener
     * @param client Batch client
     * @param poolId Pool Id
     * @param vmUtilizatonLogFilePath log file path
     */
    public VmUtilizationProfiler(BuildListener listener, BatchClient client, String poolId, String vmUtilizatonLogFilePath) {
        this.listener = listener;
        this.client = client;
        this.poolId = poolId;
        this.vmUtilizatonLogFilePath = vmUtilizatonLogFilePath;
    }
    
    @Override
    public void run() {
        long startTime = System.currentTimeMillis();
        long elapsedTime = 0;
        BufferedWriter bw = null;
        
        long totalBilledVmCountMinutes = 0;
        long totalStartingVmCountMinutes = 0;
        long totalPreparingVmCountMinutes = 0;
        long totalRunningVmCountMinutes = 0;
        long totalLeavingPoolVmCountMinutes = 0;
        
        try
        {
            FileOutputStream fos = new FileOutputStream(new File(vmUtilizatonLogFilePath));
            bw = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.Charset.defaultCharset()));
            
            bw.write("timestamp,billedVmCount,unbilledVmCount,startingVmCount,rebootingVmCount,preparingVmCount,runningVmCount,leavingVmCount,otherStateVmCount");
            bw.newLine();
            
            Set<String> runningVMs = new HashSet();
            
            while(true) {
                
                long nextWaitTime = 60 * 1000 - (System.currentTimeMillis() - startTime - elapsedTime);
                if (nextWaitTime > 0) {
                    Thread.sleep(nextWaitTime);
                }
                elapsedTime = System.currentTimeMillis() - startTime;
            
                long billedVmCount = 0;
                long unbilledVmCount = 0;
                
                long startingVmCount = 0;
                long rebootingVmCount = 0;
                long preparingVmCount = 0;
                long runningVmCount = 0;
                long leavingVmCount = 0;
                long otherStateVmCount = 0;

                List<ComputeNode> nodes = client.getComputeNodeOperations().listComputeNodes(poolId,
                        new DetailLevel.Builder().selectClause("id, state").build());
                if (nodes != null) {
                    for (ComputeNode node : nodes) {
                        switch (node.getState()) {
                            case CREATING:
                            case STARTING:
                                startingVmCount++;
                                billedVmCount++;
                                totalStartingVmCountMinutes++;
                                break;
                            case REBOOTING:
                            case REIMAGING:
                                rebootingVmCount++;
                                billedVmCount++;
                                break;
                            case IDLE:
                            case RUNNING:
                                if (ComputeNodeState.RUNNING == node.getState() || runningVMs.contains(node.getId())) {                                    
                                    runningVmCount++;
                                    billedVmCount++;
                                    totalRunningVmCountMinutes++;
                                    
                                    runningVMs.add(node.getId());
                                } else {
                                    preparingVmCount++;
                                    billedVmCount++;
                                    totalPreparingVmCountMinutes++;                                    
                                }
                                break;
                            case LEAVINGPOOL:
                                leavingVmCount++;
                                billedVmCount++;
                                totalLeavingPoolVmCountMinutes++;
                                break;
                            case UNUSABLE:
                            case OFFLINE:
                            case UNKNOWN:
                                otherStateVmCount++;
                                billedVmCount++;
                                break;
                            case WAITINGFORSTARTTASK:
                            case STARTTASKFAILED:
                                otherStateVmCount++;
                                billedVmCount++;
                                break;
                            default:
                                otherStateVmCount++;
                                unbilledVmCount++;
                                break;
                        }
                    }
                }                
                
                totalBilledVmCountMinutes += billedVmCount;
                if (billedVmCount + unbilledVmCount > 0) {                    
                    DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                    Date date = new Date();
                    
                    bw.write(String.format("%s,%d,%d,%d,%d,%d,%d,%d,%d",
                            dateFormat.format(date), billedVmCount, unbilledVmCount, startingVmCount, rebootingVmCount, preparingVmCount, runningVmCount, leavingVmCount, otherStateVmCount));
                    bw.newLine();
                } else if (client.getPoolOperations().getPool(poolId).getState() == PoolState.DELETING) {
                    // No VM in the pool and pool is in deleting state, stop profiling
                    break;
                }
            }
        } catch (InterruptedException | InterruptedIOException e) {
            Logger.log(listener, "VmUtilizationProfiler is cancelled, stop profiling.");
        } catch (BatchErrorException e) {
            if (BatchErrorCodeStrings.PoolNotFound.equals(e.getBody().getCode())) {
                Logger.log(listener, "VmUtilizationProfiler: Pool %s does not exist, stop profiling.", poolId);
            } else {
                Logger.log(listener, "Found BatchErrorException in VmUtilizationProfiler");
                Logger.log(listener, e);
                Logger.log(listener, String.format("BatchError code = %s, message = %s", e.getBody().getCode(), e.getBody().getMessage().getValue()));
                if (e.getBody().getValues() != null) {
                    for (BatchErrorDetail detail : e.getBody().getValues()) {
                        Logger.log(listener, String.format("Detail %s=%s", detail.getKey(), detail.getValue()));
                    }
                }
            }
        } catch (Exception e) {
            Logger.log(listener, "Found exception in VmUtilizationProfiler");
            Logger.log(listener, e);
        } finally {
            if (bw != null) {
                try {
                    bw.close();
                } catch (IOException ex) {
                    Logger.log(listener, "Failed to close VmUtilization file");
                    Logger.log(listener, ex);
                }
            }
            
            // Show VM utilization report
            Logger.log(listener, "VM utilization report of this run: (below numbers are estimated, in accuracy of minutes, may be different with the final service billing from Azure)");
            Logger.log(listener, "This report is provided for helping tune VM and test split configurations only, not for billing purpose.");
            Logger.log(listener, "Total estimated billed VM usage: %.2f VM Count*Hours.", totalBilledVmCountMinutes / 60.0);
            Logger.log(listener, "Total %.2f VM Count*Hours in Starting state, or %.1f%% of total estimated billed VM Count*Hours.", 
                    totalStartingVmCountMinutes / 60.0, 100.0 * totalStartingVmCountMinutes / totalBilledVmCountMinutes);
            Logger.log(listener, "Total %.2f VM Count*Hours in Preparing state, or %.1f%% of total estimated billed VM Count*Hours.", 
                    totalPreparingVmCountMinutes / 60.0, 100.0 * totalPreparingVmCountMinutes / totalBilledVmCountMinutes);
            Logger.log(listener, "Total %.2f VM Count*Hours in Running state, or %.1f%% of total estimated billed VM Count*Hours.", 
                    totalRunningVmCountMinutes / 60.0, 100.0 * totalRunningVmCountMinutes / totalBilledVmCountMinutes);
            Logger.log(listener, "Total %.2f VM Count*Hours in Deleting state, or %.1f%% of total estimated billed VM Count*Hours.", 
                    totalLeavingPoolVmCountMinutes / 60.0, 100.0 * totalLeavingPoolVmCountMinutes / totalBilledVmCountMinutes);
            
            long totalOtherStatesVmCountMinutes = totalBilledVmCountMinutes - totalStartingVmCountMinutes - totalPreparingVmCountMinutes - totalRunningVmCountMinutes - totalLeavingPoolVmCountMinutes;
            if (totalOtherStatesVmCountMinutes > 0) {                
                Logger.log(listener, "Total %.2f VM Count*Hours in other states, or %.1f%% of total estimated billed VM Count*Hours.", 
                        totalOtherStatesVmCountMinutes / 60.0, 100.0 * totalOtherStatesVmCountMinutes / totalBilledVmCountMinutes);
            }
        }
    }
}
