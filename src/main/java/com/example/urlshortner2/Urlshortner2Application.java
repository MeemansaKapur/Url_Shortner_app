package com.example.urlshortner2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Urlshortner2Application {

	public static void main(String[] args) {
		SpringApplication.run(Urlshortner2Application.class, args);
	}
}
