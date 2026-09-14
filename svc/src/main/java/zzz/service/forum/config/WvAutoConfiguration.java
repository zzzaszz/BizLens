package zzz.service.forum.config;

import com.alibaba.fastjson.JSON;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import javax.annotation.PostConstruct;
import java.util.TimeZone;

//配合META-INF/spring.factories 实现自动配置：作为依赖时，省去启动类扫包配置
@Configuration
@EnableAsync
@ComponentScan("zzz.service.forum") // 自动扫描 Component
@MapperScan("zzz.service.forum.mapper") // 自动扫描 Mapper
public class WvAutoConfiguration {

    @PostConstruct
    public void init() {
        // FastJSON 默认时区设为东八区，解决日期反序列化时区偏移
        JSON.defaultTimeZone = TimeZone.getTimeZone("GMT+8");
        JSON.DEFFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    }
}
