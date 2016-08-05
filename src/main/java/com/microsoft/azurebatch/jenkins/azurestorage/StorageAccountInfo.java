/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azurebatch.jenkins.azurestorage;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * StorageAccountInfo class
 */
public class StorageAccountInfo {

    // Windows Azure storage account friendly name.
    private String friendlyName;
    
    // Windows Azure storage account name.
    private String accountName;

    // Windows azure storage account primary access key.
    private String accountKey;

    // Windows Azure stoarge endpointDomain. 
    private String endpointDomain;

    /**
     * StorageAccountInfo constructor
     * @param friendlyName storage account friendly name
     * @param accountName account name
     * @param accountKey account key
     * @param endpointDomain endpoint domain
     */
    @DataBoundConstructor
    public StorageAccountInfo(final String friendlyName, final String accountName, final String accountKey,
                              final String endpointDomain) {
        this.friendlyName = friendlyName;
        this.accountName = accountName;
        this.accountKey = accountKey;
        this.endpointDomain = endpointDomain;
    }

    /**
     * Get friendly name
     * @return friendly name
     */
    public String getFriendlyName() {
        return friendlyName;
    }

    /**
     * Set friendly name
     * @param friendlyName friendly name
     */
    public void setFriendlyName(final String friendlyName) {
        this.friendlyName = friendlyName;
    }

    /**
     * Get account name
     * @return account name
     */
    public String getAccountName() {
        return accountName;
    }

    /**
     * Set account name
     * @param accountName account name
     */
    public void setAccountName(final String accountName) {
        this.accountName = accountName;
    }

    /**
     * Get account key
     * @return account key
     */
    public String getAccountKey() {
        return accountKey;
    }

    /**
     * Set account key
     * @param accountKey account key
     */
    public void setAccountKey(final String accountKey) {
        this.accountKey = accountKey;
    }

    /**
     * Get endpoint domain
     * @return endpoint domain
     */
    public String getEndpointDomain() {
        return endpointDomain;
    }

    /**
     * Set endpoint domain
     * @param endpointDomain endpoint domain
     */
    public void setEndpointDomain(final String endpointDomain) {
        this.endpointDomain = endpointDomain;
    }
    
    /**
     * Get blob endpoint URL
     * @return blob endpoint URL
     */
    public String getBlobEndpointURL() {
        return String.format("https://%s.blob.%s", accountName, endpointDomain);
    }
}
