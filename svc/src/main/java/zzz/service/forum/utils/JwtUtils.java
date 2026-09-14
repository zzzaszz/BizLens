package zzz.service.forum.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.util.Date;

public class JwtUtils {

    private static final String SECRET = System.getenv().getOrDefault("JWT_SECRET", "bizlens-dev-only-change-me");

    private static final long ACCESS_EXPIRE = 1000L * 60 * 30;

    private static final long REFRESH_EXPIRE = 1000L * 60 * 60 * 24 * 30;

    public static final String TOKEN_TYPE_ACCESS = "access";

    public static final String TOKEN_TYPE_REFRESH = "refresh";

    public static String generateAccessToken(Long userId, String deviceId) {
        return createToken(userId, deviceId, TOKEN_TYPE_ACCESS, ACCESS_EXPIRE);
    }

    public static String generateRefreshToken(Long userId, String deviceId) {
        return createToken(userId, deviceId, TOKEN_TYPE_REFRESH, REFRESH_EXPIRE);
    }

    private static String createToken(Long userId, String deviceId, String tokenType, long expire) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expire);

        return Jwts.builder()
                .claim("userId", userId)
                .claim("deviceId", deviceId)
                .claim("type", tokenType)
                .setIssuedAt(now)
                .setExpiration(expireDate)
                .signWith(SignatureAlgorithm.HS256, SECRET)
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .setSigningKey(SECRET)
                .parseClaimsJws(token)
                .getBody();
    }

    public static String getTokenType(String token) {
        Claims claims = parseToken(token);
        return claims.get("type", String.class);
    }

    public static boolean isAccessToken(String token) {
        return TOKEN_TYPE_ACCESS.equals(getTokenType(token));
    }

    public static boolean isRefreshToken(String token) {
        return TOKEN_TYPE_REFRESH.equals(getTokenType(token));
    }
}
