package com.hao.redisstudy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class RedisStudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisStudyApplication.class, args);
    }
}
