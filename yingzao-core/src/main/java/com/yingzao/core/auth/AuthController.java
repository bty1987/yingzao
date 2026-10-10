package com.yingzao.core.auth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

@RestController
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/login")
    public ApiResponse<AuthService.LoginResult> login(@RequestBody LoginRequest body) {
        return ApiResponse.ok(authService.login(body.username(), body.password()));
    }
}
