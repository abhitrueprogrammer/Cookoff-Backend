package com.uni.cookoff.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import com.uni.cookoff.dto.request.*;
import com.uni.cookoff.dto.response.JudgeCallback;
import com.uni.cookoff.dto.response.JudgeResponse;
import com.uni.cookoff.dto.response.RunCodeResponse;
import com.uni.cookoff.dto.response.SubmissionResponse;
import com.uni.cookoff.models.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeExecutionService {


    private final RestTemplate restTemplate;
    private final TestcaseService testcaseService;
    private final SubmissionService submissionService;
    private final SubmissionResultService submissionResultService;
    private final UserService userService;
    private final QuestionService questionService;
    private final SubmissionTokenService submissionTokenService;

    @Value("${judge0.uri}")
    private String judge0Uri;

    @Value("${judge0.token}")
    private String judge0Token;

    @Value("${callback.url}")
    private String callbackUrl;

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    public RunCodeResponse runCode(SubmissionRequest request) {
        List<Testcase> testCases = testcaseService.findByQuestionId(request.getQuestionId());

        if (testCases.isEmpty()) {
            throw new RuntimeException("No test cases found for question");
        }

        List<JudgeResponse> results = new ArrayList<>();
        int testCasesPassed = 0;

        for (Testcase testCase : testCases) {
            JudgeResponse result = executeTestCase(request, testCase);
            results.add(result);

            if ("Accepted".equals(result.getStatus().getDescription())) {
                testCasesPassed++;
            }
        }

        return RunCodeResponse.builder()
                .result(results)
                .testCasesPassed(testCasesPassed)
                .build();
    }
    public JudgeResponse runCustomTestcase(CustomTestcaseRequest request) {

        Question question;

        Optional<Question> questionOpt = Optional.ofNullable(questionService.findById(request.getQuestionId())
                .orElseThrow(() -> new RuntimeException("Question not found")));
        if (questionOpt.isEmpty()) {
            throw new RuntimeException("Question not found");
        }
        else {
            question = questionOpt.get();
        }

        String expectedOutput = "";

        try {
            // Execute the solution code locally in a separate thread
            CustomCodeExecutor executor = new CustomCodeExecutor(question.getSolutionCode(), request.getCustomInput());
            Thread executionThread = new Thread(executor);
            executionThread.start();

            // Wait for execution with timeout
            executionThread.join(10000); // 10 second timeout

            if (executionThread.isAlive()) {
                executionThread.interrupt();
                throw new RuntimeException("Execution timed out");
            }

            expectedOutput = executor.getOutput();
        } catch (Exception e) {
            log.error("Error generating expected output: {}", e.getMessage(), e);
            expectedOutput = "Could not generate expected output";
        }
        // Create a JudgeSubmission with the custom input
        JudgeSubmission submission = JudgeSubmission.builder()
                .languageId(request.getLanguageId())
                .sourceCode(request.getSourceCode())
                .input(request.getCustomInput())
                .runtime(BigDecimal.valueOf(20.0))
                .output(expectedOutput)
                .build();

        // Send to Judge0 and get response
        JudgeResponse response = sendToJudge0(submission);

        // Enrich with input for display purposes
        response.setInput(request.getCustomInput());
        response.setExpectedOutput(expectedOutput);
        response.setTestCaseId("Custom"); // Custom test case ID

        return response;
    }

    public SubmissionResponse submitCode(SubmissionRequest request, String userId) {
        List<Testcase> testCases = testcaseService.findByQuestionId(request.getQuestionId());
        User user = userService.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (testCases.isEmpty()) {
            throw new RuntimeException("No test cases found for question");
        }

        String submission_id = UuidCreator.getTimeOrdered().toString();
        Submission submission = Submission.builder()
                .user(user)
                .id(submission_id)
                .question(Question.builder().id(request.getQuestionId()).build())
                .languageId(request.getLanguageId())
                .description(request.getSourceCode())
                .status("PENDING")
                .build();

        submission = submissionService.saveSubmission(submission);

        Submission finalSubmission = submission;
        CompletableFuture.runAsync(() -> {
            System.out.println("reached here ");
            submitToJudge0(finalSubmission, testCases);
        }, executorService);

        return SubmissionResponse.builder()
                .submissionId(submission.getId())
                .build();
    }

    public void processCallback(JudgeCallback callback) {
        log.debug("Processing callback: {}", callback);
        System.out.println("Callback content: " + callback.toString());
        System.out.println("Submission ID from callback: " + callback.getSubmissionId());
        System.out.println("Test Case ID from callback: " + callback.getTestCaseId());
        try {
            String submissionId = callback.getSubmissionId();
            String testCaseId = callback.getTestCaseId();

            // Fetch the actual entities from the database
            Submission submission = submissionService.findById(submissionId)
                    .orElseThrow(() -> new RuntimeException("Submission not found: " + submissionId));

            Testcase testcase = testcaseService.findById(testCaseId)
                    .orElseThrow(() -> new RuntimeException("Testcase not found: " + testCaseId));

            SubmissionResult result = SubmissionResult.builder()
                    .submission(submission) // Use the fetched entity
                    .testcase(testcase)     // Use the fetched entity
                    .runtime(Double.parseDouble(callback.getTime()))
                    .memory(callback.getMemory())
                    .status(mapStatus(callback.getStatus().getId()))
                    .description(callback.getStatus().getDescription())
                    .build();

            result.setId(UuidCreator.getTimeOrdered().toString());

            submissionResultService.saveSubmissionResult(result);
            updateSubmissionStatus(submissionId);

        } catch (Exception e) {
            log.error("Error processing callback: {}", e.getMessage(), e);
        }
    }

    private JudgeResponse executeTestCase(SubmissionRequest request, Testcase testCase) {
        Integer languageId = request.getLanguageId();

        System.out.println(request);

        JudgeSubmission submission = JudgeSubmission.builder()
                .callback(callbackUrl)
                .languageId(languageId)
                .sourceCode((request.getSourceCode()))
                .input((testCase.getInput()))
                .output((testCase.getExpectedOutput()))
                .runtime(BigDecimal.valueOf(Math.min(testCase.getRuntime(), 20.0)))
                .build();

        JudgeResponse response = sendToJudge0(submission);
        // Enrich with input and expectedOutput from the testcase
        response.setInput(testCase.getInput());
        response.setExpectedOutput(testCase.getExpectedOutput());
        response.setTestCaseId(testCase.getId());
        return response;
    }
   private JudgeResponse sendToJudge0(JudgeSubmission submission) {
       try {
           String url = judge0Uri + "/submissions?base64_encoded=false&wait=true";

        HttpHeaders headers = createHeaders();
           headers.setContentType(MediaType.APPLICATION_JSON);

           ObjectMapper mapper = new ObjectMapper();
           String rawJson = mapper.writeValueAsString(submission);
           System.out.println("Raw JSON: " + rawJson);

           HttpEntity<String> entity = new HttpEntity<>(rawJson, headers);
           ResponseEntity<JudgeResponse> response = restTemplate.postForEntity(url, entity, JudgeResponse.class);

           if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
               return response.getBody();
           } else {
               throw new RuntimeException("Judge0 failed: " + response.getStatusCode() + ", body: " + mapper.writeValueAsString(response.getBody()));
           }


       } catch (HttpClientErrorException e) {
           log.error("Judge0 API Error: Status={}, Response={}", e.getStatusCode(), e.getResponseBodyAsString());
           throw new RuntimeException("Judge0 API Error: " + e.getResponseBodyAsString(), e);
       } catch (Exception e) {
           log.error("Error in sendToJudge0: {}", e.getMessage(), e);
           throw new RuntimeException("Error in sendToJudge0", e);
       }
   }

    private void submitToJudge0(Submission submission, List<Testcase> testCases) {
        System.out.println("in submit tp judge 0");
        List<JudgeSubmission> submissions = new ArrayList<>();
        List<String> testCaseIds = new ArrayList<>();

        for (Testcase testCase : testCases) {
            // Include submission ID and test case ID in the callback URL
            String enhancedCallbackUrl = callbackUrl + "?submissionId=" + submission.getId() + "&testCaseId=" + testCase.getId();
            System.out.println("Enhanced callback URL: " + enhancedCallbackUrl); // Debug log

            JudgeSubmission judgeSubmission = JudgeSubmission.builder()
                    .languageId(submission.getLanguageId())
                    .sourceCode(submission.getDescription())
                    .input((testCase.getInput()))
                    .output((testCase.getExpectedOutput()))
                    .runtime(BigDecimal.valueOf(Math.min(testCase.getRuntime(), 20.0)))
                    .callback(enhancedCallbackUrl)  // Use the enhanced callback URL
                    .build();
            submissions.add(judgeSubmission);
            testCaseIds.add(testCase.getId());
        }

        try {
            String url = judge0Uri + "/submissions/batch?base64_encoded=false&wait=true";
            HttpHeaders headers = createHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ObjectMapper mapper = new ObjectMapper();

            BatchSubmissionRequest batchRequest = new BatchSubmissionRequest(submissions);
            String rawJson = mapper.writeValueAsString(batchRequest);
            HttpEntity<String> entity = new HttpEntity<>(rawJson, headers);
            System.out.println("Raw JSON for batch submission: " + rawJson);

            ResponseEntity<JudgeToken[]> response = restTemplate.postForEntity(url, entity, JudgeToken[].class);

            if (response.getStatusCode() == HttpStatus.CREATED && response.getBody() != null) {
                storeTokens(submission.getId(), response.getBody(), testCaseIds);
            }

        } catch (Exception e) {
            log.error("Error submitting to Judge0: {}", e.getMessage(), e);
            updateSubmissionStatus(submission.getId());
        }
    }
    // The storeTokens method remains the same for batch processing.
    private void storeTokens(String submissionId, JudgeToken[] tokens, List<String> testCaseIds) {
        new SubmissionToken();
        SubmissionToken submissionToken;
        for (int i = 0; i < tokens.length && i < testCaseIds.size(); i++) {
            submissionToken = SubmissionToken.builder()
                    .token(tokens[i].getToken())
                    .submission(Submission.builder().id(submissionId).build())
                    .testcase(Testcase.builder().id(testCaseIds.get(i)).build())
                    .build();
            submissionTokenService.saveSubmissionToken(submissionToken);
        }
    }

    private void updateSubmissionStatus(String submissionId) {
        List<SubmissionResult> results = submissionResultService.findBySubmissionId(submissionId);

        if (!results.isEmpty()) {
            long passed = results.stream()
                    .filter(r -> "success".equals(r.getStatus()))
                    .count();

            long failed = results.size() - passed;

            Submission submission = submissionService.findById(submissionId).orElse(null);
            if (submission != null) {
                submission.setTestcasesPassed((int) passed); // Corrected method name
                submission.setTestcasesFailed((int) failed); // Corrected method name
                submission.setStatus("COMPLETED");
                submissionService.saveSubmission(submission);
            }
        }
    }




    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (judge0Uri.contains("rapidapi")) {
            headers.set("x-rapidapi-host", "judge0-ce.p.rapidapi.com");
            headers.set("x-rapidapi-key", judge0Token);
        } else {
            headers.set("Authorization", "Bearer " + judge0Token);
        }

        return headers;
    }
    private String mapStatus(String statusId) {
        return switch (statusId) {
            case "1" -> "In Queue";
            case "2" -> "Processing";
            case "3" -> "success";
            case "4" -> "wrong answer";
            case "5" -> "Time Limit Exceeded";
            case "6" -> "Compilation error";
            case "7", "8", "9", "10", "11", "12" -> "Runtime error";
            case "13" -> "Internal Error";
            case "14" -> "Exec Format Error";
            default -> "Unknown";
        };
    }
}