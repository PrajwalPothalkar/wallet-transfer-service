package com.paytm.exercise.wallet.middleware;

import com.paytm.exercise.wallet.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DemoAuthentication {
    private static final Pattern TOKEN = Pattern.compile("^Bearer user:([A-Za-z0-9_.-]{1,64})$");

    /** Exercise-only identity parsing. Replace this adapter with signed-token verification in production. */
    public DemoPrincipal requirePrincipal(String authorization) {
        Matcher matcher = TOKEN.matcher(authorization == null ? "" : authorization);
        if (!matcher.matches()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "use Authorization: Bearer user:<user-id>");
        }
        return new DemoPrincipal(matcher.group(1));
    }
}
