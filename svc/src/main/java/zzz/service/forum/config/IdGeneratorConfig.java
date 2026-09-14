package zzz.service.forum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zzz.service.forum.utils.SnowflakeIdGenerator;

@Configuration
public class IdGeneratorConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(1, 1);
    }
}
