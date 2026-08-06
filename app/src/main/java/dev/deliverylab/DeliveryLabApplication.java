package dev.deliverylab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class DeliveryLabApplication {

	public static void main(String[] args) {
		SpringApplication.run(DeliveryLabApplication.class, args);
	}

	@RestController
    static class HelloResource {
		@GetMapping("/hello")
		public String hello() {
			return "Hello World!!";
		}
	}
}
