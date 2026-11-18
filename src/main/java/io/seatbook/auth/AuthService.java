package io.seatbook.auth;

import io.seatbook.common.ApiException;
import io.seatbook.config.SecurityConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
class AuthService implements ApplicationRunner {
    private static final String ADMIN_USERNAME = "admin";
    private static final Duration TOKEN_DURATION = Duration.ofMinutes(30);

    private final JdbcClient db;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final String adminPassword;
    private final String dummyPasswordHash;

    AuthService(JdbcClient db, PasswordEncoder passwords, JwtEncoder tokens,
                @Value("${seatbook.admin-password}") String adminPassword) {
        this.db = db;
        this.passwords = passwords;
        this.tokens = tokens;
        this.adminPassword = adminPassword;
        this.dummyPasswordHash = passwords.encode("unused-timing-placeholder");
    }

    Account register(String username, String password) {
        String normalized = normalize(username);
        validatePasswordBytes(password);
        if (ADMIN_USERNAME.equals(normalized)) {
            throw usernameTaken();
        }
        try {
            insert(normalized, passwords.encode(password), "STUDENT");
        } catch (DuplicateKeyException ex) {
            throw usernameTaken();
        }
        return find(normalized).orElseThrow().account();
    }

    Token login(String username, String password) {
        validatePasswordBytes(password);
        Optional<StoredAccount> stored = find(normalize(username));
        boolean valid = passwords.matches(password, stored.map(StoredAccount::passwordHash).orElse(dummyPasswordHash));
        if (!valid || stored.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        Account account = stored.orElseThrow().account();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
            .issuer(SecurityConfig.JWT_ISSUER)
            .subject(Long.toString(account.id()))
            .issuedAt(now)
            .expiresAt(now.plus(TOKEN_DURATION))
            .claim("roles", List.of(account.role()))
            .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = tokens.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Token(token, "Bearer", TOKEN_DURATION.toSeconds(), account);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminPassword.isEmpty() || find(ADMIN_USERNAME).isPresent()) {
            return;
        }
        if (adminPassword.length() < 10 || adminPassword.length() > 64) {
            throw new IllegalArgumentException("SEATBOOK_ADMIN_PASSWORD must have 10–64 characters");
        }
        validatePasswordBytes(adminPassword);
        try {
            insert(ADMIN_USERNAME, passwords.encode(adminPassword), "ADMIN");
        } catch (DuplicateKeyException ignored) {
            // Another instance completed the same bootstrap.
        }
    }

    private Optional<StoredAccount> find(String username) {
        return db.sql("SELECT id, username, password_hash, role FROM accounts WHERE username = :name")
            .param("name", username)
            .query((rs, row) -> new StoredAccount(
                new Account(rs.getLong("id"), rs.getString("username"), rs.getString("role")),
                rs.getString("password_hash")))
            .optional();
    }

    private void insert(String username, String hash, String role) {
        db.sql("INSERT INTO accounts (username, password_hash, role) VALUES (:name, :hash, :role)")
            .param("name", username).param("hash", hash).param("role", role).update();
    }

    private String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "密码的 UTF-8 编码不能超过 72 字节");
        }
    }

    private ApiException usernameTaken() {
        return new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "该用户名不可用");
    }

    record Account(long id, String username, String role) {}
    record Token(String accessToken, String tokenType, long expiresIn, Account account) {}
    private record StoredAccount(Account account, String passwordHash) {}
}
