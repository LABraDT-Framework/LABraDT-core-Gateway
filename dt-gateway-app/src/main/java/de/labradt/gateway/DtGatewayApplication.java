package de.labradt.gateway;

import de.labradt.gateway.framework.model.MachineConfigProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "de.labradt.gateway")
@EnableConfigurationProperties(MachineConfigProperties.class)
public class DtGatewayApplication {

    public static void main(String[] args) {
        PluginLauncher.run(DtGatewayApplication.class, args);  // plus the JARs in plugins/
    }
}
