package zzz.service.forum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

@SpringBootApplication()
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"zzz.service.forum"})
public class BizLensAiApplication {

    public static void main(String[] args) throws UnknownHostException {
        ConfigurableApplicationContext application = SpringApplication.run(BizLensAiApplication.class, args);
        Environment env = application.getEnvironment();
        String ip = InetAddress.getLocalHost().getHostAddress();
        String port = env.getProperty("server.port");
        System.out.println(
                "\n\t" +
                        "----------------------------------------------------------\n\t" +
                        "BizLens AI is running! Access URLs:\n\t" +
                        "Local: \t\thttp://localhost:" + port + "/doc.html\n\t" +
                        "External: \thttp://" + ip + ":" + port + "/doc.html\n\t" +
                        "Web: \t\thttp://" + ip + ":" + port + "\n\t" +
                        "\n\t" +
                        "------------------------------------------------------------");
    }
}
