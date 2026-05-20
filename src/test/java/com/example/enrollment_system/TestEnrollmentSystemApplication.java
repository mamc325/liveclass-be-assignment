package com.example.enrollment_system;

import org.springframework.boot.SpringApplication;

public class TestEnrollmentSystemApplication {

	public static void main(String[] args) {
		SpringApplication.from(EnrollmentSystemApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
