package com.beanLoyal.backend;

import com.google.cloud.firestore.Firestore;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

	// Under the "test" profile FirebaseAdminConfig is skipped (no real credentials), so provide
	// mock Firestore/FirebaseAuth beans the wiring depends on. Keeps the context-load smoke test
	// runnable in CI without a service account.
	@TestConfiguration
	static class FirebaseTestConfig {

		@Bean
		@Primary
		Firestore firestore() {
			return mock(Firestore.class);
		}

		@Bean
		@Primary
		FirebaseAuth firebaseAuth() {
			return mock(FirebaseAuth.class);
		}
	}
}
