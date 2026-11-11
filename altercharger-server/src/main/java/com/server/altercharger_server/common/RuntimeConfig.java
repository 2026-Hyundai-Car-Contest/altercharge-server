package com.server.altercharger_server.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RuntimeConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
