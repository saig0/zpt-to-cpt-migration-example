package org.example.zpt;

import io.camunda.client.CamundaClient;
import io.camunda.zeebe.process.test.api.ZeebeTestEngine;
import io.camunda.zeebe.process.test.assertions.BpmnAssert;
import io.camunda.zeebe.spring.test.ZeebeSpringTest;
import org.example.model.Account;
import org.example.model.SignUpForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@SpringBootTest
@ZeebeSpringTest
public class SignUpProcessTest {

    private static final String PROCESS_ID = "sign-up";

    private static final String USER_NAME = "Demo";
    private static final String EMAIL = "demo@camunda.com";
    private static final String ACCOUNT_ID = "account-id-0001";
    private static final String ACTIVATION_CODE = "activation-code-0001";

    @Autowired
    private ZeebeTestEngine engine;

    @Autowired
    private CamundaClient client;

    @BeforeEach
    void configureMocks() {
        mockJobWorker("io.camunda:sendgrid:1");
        mockJobWorker("backend:confirm-account");
        mockJobWorker("accounts:activate");
        mockJobWorker("subscriptions:subscribe");
    }

    private void mockJobWorker(final String jobType) {
        mockJobWorker(jobType, Collections.emptyMap());
    }

    private void mockJobWorker(final String jobType, final Map<String, Object> variables) {
        client.newWorker().jobType(jobType)
                .handler(((jobClient, job) ->
                        jobClient.newCompleteCommand(job).variables(variables).send().join()
                )).open();
    }

    // Sign-up process (process-id: "sign-up")
    // |- New sign-up (element-id: "new-sign-up")
    // |--- Create account (element-id: "create-account")
    // |--- Send activation email (element-id: "send-activation-email")
    // |--- Send confirmation (element-id: "send-confirmation")
    // |--- Await email activation (element-id: "await-email-activation")
    // |--- (message) Email confirmed (element-id: "message-email-confirmed")
    // |--- Activate account (element-id: "activate-account")
    // |--- Subscribe to newsletter (element-id: "subscribe-to-newsletter")
    // |--- Account created (element-id: "account-created")
    // |- (timer) 3 days (element-id: "timer-three-days")
    // |--- Delete account (element-id: "delete-account")
    // |--- Account deleted (element-id: "account-deleted")
    // |- (error) Invalid (element-id: "error-invalid-account")
    // |--- Send rejection (element-id: "send-rejection")
    // |--- Sign-up rejected (element-id: "sign-up-rejected")

    @Test
    void shouldCreateAccountWithSubscription() throws InterruptedException, TimeoutException {
        // given
        final var signUpForm = new SignUpForm(USER_NAME, EMAIL, true);
        final var account = new Account(ACCOUNT_ID, USER_NAME, EMAIL, true, ACTIVATION_CODE);

        mockJobWorker("accounts:create",Map.of("account", account));

        final var processInstance = client
                .newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variable("signUpForm", signUpForm)
                .send()
                .join();

        // when
        BpmnAssert.assertThat(processInstance)
                .isActive()
                .hasPassedElement("create-account")
                .isWaitingAtElements("await-email-activation");

        client
                .newPublishMessageCommand()
                .messageName("backend:email-confirmed")
                .correlationKey(account.id())
                .send()
                .join();

        try {
            engine.waitForIdleState(Duration.ofSeconds(1));
        } catch (Exception e) {
            // ignore
        }

        // then
        BpmnAssert.assertThat(processInstance)
                .isCompleted()
                .hasPassedElementsInOrder(
                        "new-sign-up",
                        "create-account",
                        "send-activation-email",
                        "send-confirmation",
                        "message-email-confirmed",
                        "activate-account",
                        "subscribe-to-newsletter",
                        "account-created");
    }

}
