package com.synechisveltiosi.platform.order.api;

import com.synechisveltiosi.platform.order.application.ApiFailure;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiFailure.class)
    ProblemDetail application(ApiFailure error) { return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status()), error.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    ProblemDetail invalid(RuntimeException error) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request values are invalid"); }
    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(RuntimeException error) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with current state"); }
    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
    ProblemDetail unavailable(RuntimeException error) { return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Persistence is temporarily unavailable; retry the request"); }
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error) {
        // Do not expose SQL, rejected payloads, credentials, or signed URLs in responses/log messages.
        logger.error("Unexpected API failure: " + error.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed");
    }
}
