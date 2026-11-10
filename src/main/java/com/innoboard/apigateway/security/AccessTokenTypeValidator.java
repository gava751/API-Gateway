package com.innoboard.apigateway.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AccessTokenTypeValidator implements OAuth2TokenValidator<Jwt> {

    public static final String TYPE_CLAIM = "type";
    public static final String ACCESS_TOKEN_TYPE = "access";

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (ACCESS_TOKEN_TYPE.equals(token.getClaimAsString(TYPE_CLAIM))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "Refresh token is not an access token", null));
    }
}
