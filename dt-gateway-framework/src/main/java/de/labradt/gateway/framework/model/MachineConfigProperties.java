package de.labradt.gateway.framework.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dt.framework")
public class MachineConfigProperties {

    private String configPath;

    /**
     * When true, the gateway refuses every write (PATCH) request and returns 403.
     * Use this for the first lab session against a real machine: read-only,
     * zero risk of an accidental write damaging the asset.
     */
    private boolean safeMode = false;

    /**
     * Domain-data recorder configuration. When enabled, every SubmodelStore.put()
     * appends a row to a file (JSONL format) so machine values can be inspected
     * or analysed later.
     */
    private DataLog dataLog = new DataLog();

    /**
     * Deadband filtering. When enabled, SubmodelStore.put() only notifies
     * listeners (data recorder, UI, etc.) when the value or statusCode has
     * actually changed since the previous put. Prevents log/UI thrashing
     * when poll cadence is faster than the underlying value change rate.
     */
    private Deadband deadband = new Deadband();

    public String getConfigPath() { return configPath; }
    public void setConfigPath(String configPath) { this.configPath = configPath; }

    public boolean isSafeMode() { return safeMode; }
    public void setSafeMode(boolean safeMode) { this.safeMode = safeMode; }

    public DataLog getDataLog() { return dataLog; }
    public void setDataLog(DataLog dataLog) { this.dataLog = dataLog; }

    public Deadband getDeadband() { return deadband; }
    public void setDeadband(Deadband deadband) { this.deadband = deadband; }

    public static class DataLog {
        private boolean enabled = false;
        private String path = "logs/data.jsonl";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public static class Deadband {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
