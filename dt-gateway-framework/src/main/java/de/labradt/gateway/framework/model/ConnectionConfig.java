package de.labradt.gateway.framework.model;

/**
 * Per-machine connection details, carried inside the machine's instance JSON so
 * that one file fully describes a machine (identity + connection + mappings).
 * Protocol adapters read the fields they need; unused fields are simply ignored.
 */
public class ConnectionConfig {

    /** Where to reach the machine, e.g. "opc.tcp://192.168.1.10:4840". */
    private String endpoint;

    /** Optional credentials. Blank username means anonymous access. */
    private String username = "";
    private String password = "";

    /** security policy name, e.g. "None" or "Basic256Sha256". */
    private String securityPolicy = "None";
    private String securityMode = "SignAndEncrypt";
    private String serverCertificate;
    private String usernameEnv;
    private String passwordEnv;

    private long reconnectInitialBackoffMs = 1_000;
    private long reconnectMaxBackoffMs = 30_000;

    /**
     * Push protocols (MQTT) only: the machine-level silence tolerance. If no
     * message arrives from the machine (on any topic) within this window, the
     * machine counts as not alive and ALL its elements are served as STALE.
     * Set by the engineer who knows the machine's publish behaviour;
     * 0 disables the check (report-by-exception machines that may stay quiet).
     */
    private long quietWindowMs = 15_000;

    /**
     * Push protocols (MQTT) only: optional subscription filter, e.g.
     * "factory/molder-mqtt/#". When unset, the adapter subscribes to exactly
     * the topics named by the machine's mappings.
     */
    private String topicFilter;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSecurityPolicy() { return securityPolicy; }
    public void setSecurityPolicy(String securityPolicy) { this.securityPolicy = securityPolicy; }
    public String getSecurityMode() { return securityMode; }
    public void setSecurityMode(String value) { this.securityMode = value; }
    public String getServerCertificate() { return serverCertificate; }
    public void setServerCertificate(String value) { this.serverCertificate = value; }
    public String getUsernameEnv() { return usernameEnv; }
    public void setUsernameEnv(String value) { this.usernameEnv = value; }
    public String getPasswordEnv() { return passwordEnv; }
    public void setPasswordEnv(String value) { this.passwordEnv = value; }

    public long getReconnectInitialBackoffMs() { return reconnectInitialBackoffMs; }
    public void setReconnectInitialBackoffMs(long ms) { this.reconnectInitialBackoffMs = ms; }

    public long getReconnectMaxBackoffMs() { return reconnectMaxBackoffMs; }
    public void setReconnectMaxBackoffMs(long ms) { this.reconnectMaxBackoffMs = ms; }

    public long getQuietWindowMs() { return quietWindowMs; }
    public void setQuietWindowMs(long ms) { this.quietWindowMs = ms; }

    public String getTopicFilter() { return topicFilter; }
    public void setTopicFilter(String topicFilter) { this.topicFilter = topicFilter; }
}
