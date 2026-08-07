package dev.deliverylab;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DeliveryLabApplicationTests {
	@Test
	void contextLoads() {
	}

	@Test
	void startupFailureModeStopsTheApplication() {
		var failure = new DeliveryLabApplication.StartupFailure(true);

		assertThrows(IllegalStateException.class, failure::failWhenEnabled);
	}
}
