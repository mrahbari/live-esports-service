package com.abioscase.live;

import com.abioscase.live.livedata.application.LiveDataService;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LiveEsportsApplication {

    public static void main(String[] args) {
        loadLocalEnv();
        SpringApplication.run(LiveEsportsApplication.class, args);
    }

    @Bean
    public CommandLineRunner eagerLoad(LiveDataService service) {
        return args -> service.refreshInBackground();
    }

    /**
     * Picks up project root {@code .env} so {@code abios.api-key: ${ABIOS_API_KEY:}} is resolved. Does
     * nothing if the file is missing (e.g. CI). Real environment variables and {@code -D} always win.
     */
    private static void loadLocalEnv() {
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            dotenv
                    .entries()
                    .forEach(e -> {
                        String k = e.getKey();
                        if (System.getenv(k) == null && System.getProperty(k) == null) {
                            System.setProperty(k, e.getValue());
                        }
                    });
        } catch (Exception ignored) {
            // optional .env
        }
    }
}
