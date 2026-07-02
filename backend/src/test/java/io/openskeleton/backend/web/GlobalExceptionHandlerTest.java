package io.openskeleton.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exercises {@link GlobalExceptionHandler} via a small test-only controller that
 * throws each mapped exception, asserting the RFC 7807 shapes + the TM-72
 * JSON-only guarantee. Uses the full context so the advice + WebConfig are active.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.TestErrorController.class)
class GlobalExceptionHandlerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mvc;

    @Test
    void notFoundIsMappedTo404ProblemDetail() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    @Test
    void conflictIsMappedTo409() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void objectOptimisticLockingFailureIsMappedTo409() throws Exception {
        // The Spring-translated form of a stale-version write (OSK-84) -> 409 problem+json.
        mvc.perform(get("/test/optimistic-object"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void jpaOptimisticLockExceptionIsMappedTo409() throws Exception {
        // The JPA-native optimistic-lock exception is mapped to the same 409 contract.
        mvc.perform(get("/test/optimistic-jpa"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void validationIsMappedTo400WithErrors() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unexpectedIsMappedTo500WithoutLeakingDetail() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                // the real exception message must NOT be serialised to the client
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("top secret"))));
    }

    @Test
    void healthStaysJsonEvenForAcceptXml() throws Exception {
        // TM-72: Accept: application/xml must still yield JSON app-wide (not XML, not 406).
        mvc.perform(get("/health").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @RestController
    static class TestErrorController {
        @org.springframework.web.bind.annotation.GetMapping("/test/not-found")
        String notFound() {
            throw new NotFoundException("no such thing");
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/conflict")
        String conflict() {
            throw new ConflictException("already exists");
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/boom")
        String boom() {
            throw new RuntimeException("top secret internal detail");
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/optimistic-object")
        String optimisticObject() {
            throw new ObjectOptimisticLockingFailureException("users", new RuntimeException("stale version"));
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/optimistic-jpa")
        String optimisticJpa() {
            throw new OptimisticLockException("stale entity");
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return "ok";
        }

        record Payload(@NotBlank String name) {}
    }
}
