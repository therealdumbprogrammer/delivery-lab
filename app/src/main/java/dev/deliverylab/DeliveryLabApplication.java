package dev.deliverylab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
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
			return "Hello ArgoCD Updated!!";
		}
	}

	/**
	 * An opt-in startup failure used only by the Checkpoint 2 delivery demo.
	 * The dev-overlay patch enables it, so failure and recovery both flow through Git.
	 */
	@Component
	static class StartupFailure {
		private final boolean enabled;

		StartupFailure(
				@Value("${delivery-lab.demo.startup-failure:false}") boolean enabled) {
			this.enabled = enabled;
		}

		@EventListener(ApplicationReadyEvent.class)
		void failWhenEnabled() {
			if (enabled) {
				throw new IllegalStateException("Checkpoint 2 deliberate startup failure");
			}
		}
	}
}
