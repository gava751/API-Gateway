package com.innoboard.apigateway.controller;

import com.innoboard.apigateway.dto.RegisterViaGatewayRequest;
import com.innoboard.apigateway.dto.RegistrationResponse;
import com.innoboard.apigateway.service.RegistrationOrchestrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationOrchestrationService orchestrationService;

    @PostMapping("/register")
    public Mono<ResponseEntity<RegistrationResponse>> register(
            @Valid @RequestBody RegisterViaGatewayRequest request) {
        return orchestrationService.register(request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }
}
