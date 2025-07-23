/*
 * Copyright © 2021 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.example.cpt;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.DeployResourceCommandStep1;
import io.camunda.client.api.response.*;
import io.camunda.client.api.response.Process;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.ProcessInstanceSelectors;
import io.camunda.zeebe.process.test.assertions.BpmnAssert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@CamundaSpringProcessTest
public class PullRequestProcessTest {

    private static final String PULL_REQUEST_PROCESS_RESOURCE_NAME = "pr-created.bpmn";
    private static final String AUTOMATED_TESTS_PROCESS_RESOURCE_NAME = "automated-tests.bpmn";
    private static final String PULL_REQUEST_PROCESS_ID = "prCreatedProcess";
    private static final String AUTOMATED_TESTS_PROCESS_ID = "automatedTestsProcess";
    private static final String AUTOMATED_TESTS_RUN_TESTS = "runTests";
    private static final String PR_CREATED_MSG = "prCreated";
    private static final String REVIEW_RECEIVED_MSG = "reviewReceived";
    private static final String PR_ID_VAR = "prId";
    private static final String REVIEW_RESULT_VAR = "reviewResult";
    private static final String REQUEST_REVIEW = "requestReview";
    private static final String REMIND_REVIEWER = "remindReviewer";
    private static final String MAKE_CHANGES = "makeChanges";
    private static final String MERGE_CODE = "mergeCode";
    private static final String DEPLOY_SNAPSHOT = "deploySnapshot";

    // injected by ZeebeProcessTest annotation
    @Autowired
    private CamundaProcessTestContext processTestContext;
    // injected by ZeebeProcessTest annotation
    @Autowired
    private CamundaClient client;

    @BeforeEach
    void deployProcesses() {
        // The embedded engine is completely reset before each test run.

        // Therefore, we need to deploy the process each time
        final DeploymentEvent deploymentEvent =
                deployResources(PULL_REQUEST_PROCESS_RESOURCE_NAME, AUTOMATED_TESTS_PROCESS_RESOURCE_NAME);

        assertThat(deploymentEvent.getProcesses())
                .extracting(Process::getResourceName)
                .contains(PULL_REQUEST_PROCESS_RESOURCE_NAME, AUTOMATED_TESTS_PROCESS_RESOURCE_NAME);
    }

    @Test
    void testPullRequestCreatedHappyPath() throws InterruptedException, TimeoutException {
        // Given
        final String pullRequestId = "123";

        // When

        //  -> send message to create process instance
        final CorrelateMessageResponse correlateMessageResponse =
                sendMessage(PR_CREATED_MSG, "", singletonMap(PR_ID_VAR, pullRequestId));

        //  -> complete user task
        completeUserTask(REQUEST_REVIEW);

        //  -> send another message to drive the process forward
        sendMessage(REVIEW_RECEIVED_MSG, pullRequestId, singletonMap(REVIEW_RESULT_VAR, "approved"));

        /*  -> on a parallel branch of the process, a sub process is called, which spawns three service
         *     tasks as part of a multi instance embedded sub process. These lines complete the called
         *     service tasks
         */
        completeServiceTasks(AUTOMATED_TESTS_RUN_TESTS, 3);

        //  -> back on the main process, there are two more tasks to complete to reach the end
        completeUserTask(MERGE_CODE);
        completeServiceTask(DEPLOY_SNAPSHOT);

        // Then
        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(correlateMessageResponse.getProcessInstanceKey()))
                .hasCompletedElementsInOrder(REQUEST_REVIEW, MERGE_CODE, DEPLOY_SNAPSHOT)
                .hasNotActivatedElements(REMIND_REVIEWER, MAKE_CHANGES)
                .hasVariable(REVIEW_RESULT_VAR, "approved");

        CamundaAssert.assertThat(ProcessInstanceSelectors.byProcessId(AUTOMATED_TESTS_PROCESS_ID))
                .hasCompletedElement(AUTOMATED_TESTS_RUN_TESTS, 3)
                .isCompleted();
    }

    @Test
    void testRemindReviewer() throws InterruptedException, TimeoutException {
        // Given
        final String prId = "123";

        // When
        final CorrelateMessageResponse correlateMessageResponse =
                sendMessage(PR_CREATED_MSG, "", singletonMap(PR_ID_VAR, prId));
        completeUserTask(REQUEST_REVIEW);

        completeServiceTasks(AUTOMATED_TESTS_RUN_TESTS, 3);

        //  This is how you can manipulate the time of the engine to trigger timer events
        increaseTime(Duration.ofDays(1));

        completeServiceTask(REMIND_REVIEWER);

        sendMessage(REVIEW_RECEIVED_MSG, prId, singletonMap(REVIEW_RESULT_VAR, "approved"));

        completeUserTask(MERGE_CODE);
        completeServiceTask(DEPLOY_SNAPSHOT);

        // Then
        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(correlateMessageResponse.getProcessInstanceKey()))
                .hasCompletedElementsInOrder(REQUEST_REVIEW, REMIND_REVIEWER, MERGE_CODE, DEPLOY_SNAPSHOT)
                .hasNotActivatedElements(MAKE_CHANGES)
                .isCompleted();
    }

    @Test
    void testRejectReview() throws InterruptedException, TimeoutException {
        // Given
        final String prId = "123";

        // When
        final CorrelateMessageResponse correlateMessageResponse =
                sendMessage(PR_CREATED_MSG, "", singletonMap(PR_ID_VAR, prId));

        completeUserTask(REQUEST_REVIEW);

        completeServiceTasks(AUTOMATED_TESTS_RUN_TESTS, 3);

        sendMessage(REVIEW_RECEIVED_MSG, prId, singletonMap(REVIEW_RESULT_VAR, "rejected"));

        completeUserTask(MAKE_CHANGES);

        completeUserTask(REQUEST_REVIEW);

        sendMessage(REVIEW_RECEIVED_MSG, prId, singletonMap(REVIEW_RESULT_VAR, "approved"));

        completeUserTask(MERGE_CODE);
        completeServiceTask(DEPLOY_SNAPSHOT);

        // Then
        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(correlateMessageResponse.getProcessInstanceKey()))
                .hasCompletedElementsInOrder(REQUEST_REVIEW, MAKE_CHANGES, REQUEST_REVIEW, MERGE_CODE, DEPLOY_SNAPSHOT)
                .hasNotActivatedElements(REMIND_REVIEWER)
                .isCompleted();
    }

    private DeploymentEvent deployResources(final String... resources) {
        final DeployResourceCommandStep1 commandStep1 = client.newDeployResourceCommand();

        DeployResourceCommandStep1.DeployResourceCommandStep2 commandStep2 = null;
        for (final String process : resources) {
            if (commandStep2 == null) {
                commandStep2 = commandStep1.addResourceFromClasspath(process);
            } else {
                commandStep2 = commandStep2.addResourceFromClasspath(process);
            }
        }

        return commandStep2.send().join();
    }

    private CorrelateMessageResponse sendMessage(
            final String messageName, final String correlationKey, final Map<String, Object> variables)
            throws InterruptedException, TimeoutException {

    /*
     To avoid flaky tests, we recommend publishing messages without a time to live when using time
     manipulation in the same test case. Alternatively, you could plan out the timings of your time
     manipulation and the published message's expiry.

     In these tests, we assume that a timer event has triggered after the {@code increaseTime}
     method returns. However, this is not guaranteed if a time to live is set because the message
     could expire. Depending on the time to live, the message can expire due to time manipulation.

     The {@code increaseTime} method will return after waiting for the engine to become idle again.
     However, message expiry can cause the engine to be busy followed by being idle again. So, the
     increaseTime method can return before the timer event has triggered when a message expires
     due to time manipulation. This can be the cause of a flaky test.

     Note that by default, the time to live is set to 1 hour.
     See {@code CamundaClientBuilder#defaultTimeToLive}.
    */
        final Duration timeToLive = Duration.ZERO;

        final CorrelateMessageResponse response =
                client
                        .newCorrelateMessageCommand()
                        .messageName(messageName)
                        .correlationKey(correlationKey)
                        .variables(variables)
                        .send()
                        .join();
        return response;
    }

    private void increaseTime(final Duration duration) throws InterruptedException, TimeoutException {
        // this method increases the time in a deterministic manner
        processTestContext.increaseTime(duration);
    }

    private void completeServiceTask(final String jobType)
            throws InterruptedException, TimeoutException {
        completeServiceTasks(jobType, 1);
    }

    private void completeServiceTasks(final String jobType, final int count)
            throws InterruptedException, TimeoutException {

        final var activateJobsResponse =
                client.newActivateJobsCommand().jobType(jobType).maxJobsToActivate(count).send().join();

        final int activatedJobCount = activateJobsResponse.getJobs().size();
        if (activatedJobCount < count) {
            Assertions.fail(
                    "Unable to activate %d jobs, because only %d were activated."
                            .formatted(count, activatedJobCount));
        }

        for (int i = 0; i < count; i++) {
            final var job = activateJobsResponse.getJobs().get(i);

            client.newCompleteCommand(job.getKey()).send().join();
        }
    }

    private void completeUserTask(final String elementId)
            throws InterruptedException, TimeoutException {
        // user tasks can be controlled similarly to service tasks
        // all user tasks share a common job type
        final var activateJobsResponse =
                client
                        .newActivateJobsCommand()
                        .jobType("io.camunda.zeebe:userTask")
                        .maxJobsToActivate(100)
                        .send()
                        .join();

        boolean userTaskWasCompleted = false;

        for (final ActivatedJob userTask : activateJobsResponse.getJobs()) {
            if (userTask.getElementId().equals(elementId)) {
                // complete the user task we care about
                client.newCompleteCommand(userTask).send().join();
                userTaskWasCompleted = true;
            } else {
                // fail all other user tasks that were activated
                // failing a task with a retry value >0 means the task can be reactivated in the future
                client.newFailCommand(userTask).retries(Math.max(userTask.getRetries(), 1)).send().join();
            }
        }

        if (!userTaskWasCompleted) {
            Assertions.fail("Tried to complete task `%s`, but it was not found".formatted(elementId));
        }
    }
}
