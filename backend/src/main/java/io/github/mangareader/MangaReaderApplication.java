package io.github.mangareader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MangaReaderApplication {
    public static void main(String[] args) {
        SpringApplication.run(MangaReaderApplication.class, args);
    }
}
