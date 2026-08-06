package dev.deliverylab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "delivery-lab.demo.failure-mode=readiness")
class DeliveryLabApplicationTests {
	@Autowired
	private ApplicationAvailability applicationAvailability;

	@Test
	void contextLoads() {
	}

	@Test
	void readinessDemoModeRefusesTrafficWithoutFailingTheProcess() {
		assertEquals(ReadinessState.REFUSING_TRAFFIC,
				applicationAvailability.getReadinessState());
	}

}
