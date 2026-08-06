package dev.deliverylab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
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
	 * An opt-in failure switch used only by the Checkpoint 2 delivery demo.
	 * A dev-overlay patch supplies the command-line property at runtime, so the
	 * resulting failure is applied and recovered through Git reconciliation.
	 */
	@Component
	static class CheckpointTwoFailureMode {
		private final ApplicationContext applicationContext;
		private final String failureMode;

		CheckpointTwoFailureMode(
				ApplicationContext applicationContext,
				@Value("${delivery-lab.demo.failure-mode:none}") String failureMode) {
			this.applicationContext = applicationContext;
			this.failureMode = failureMode;
		}

		@EventListener(ApplicationReadyEvent.class)
		void applyFailureMode() {
			switch (failureMode) {
				case "none" -> {
					// Normal application behaviour.
				}
				case "startup" -> throw new IllegalStateException(
						"Checkpoint 2 deliberate startup failure");
				case "readiness" -> {
					// Refuse traffic after Spring Boot publishes its ready state below.
				}
				default -> throw new IllegalArgumentException(
						"Unsupported delivery-lab.demo.failure-mode: " + failureMode);
			}
		}

		@EventListener
		void refuseTrafficAfterStartup(AvailabilityChangeEvent<?> event) {
			if ("readiness".equals(failureMode)
					&& event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
				AvailabilityChangeEvent.publish(
						applicationContext, ReadinessState.REFUSING_TRAFFIC);
			}
		}
	}
}
